/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadPlatform
import com.yunx.app.data.network.WeiyunApi
import com.yunx.app.data.network.WeiyunConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 微云云盘浏览 UI 状态 */
sealed interface WeiyunCloudUiState {
    data object Loading : WeiyunCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        /** 当前目录 id（根 = "main" 根目录） */
        val dirId: String
    ) : WeiyunCloudUiState
    data class Error(val message: String) : WeiyunCloudUiState
}

/**
 * 微云云盘浏览 ViewModel：
 * - 目录浏览（根/子目录/面包屑回退）+ 下拉刷新；
 * - 文件下载：DiskFileBatchDownload（cmd 2402）取直链（携带 cookie），下载需同款 UA + Cookie。
 * 认证走 Cookie（WeiyunAccountEntity.cookie）。微云云盘暂未实现重命名/移动/删除等管理操作。
 */
class WeiyunCloudViewModel(
    private val api: WeiyunApi,
    private val cookieProvider: suspend () -> String?,
    private val downloadManager: DownloadManager,
    private val loginState: Flow<Boolean>
) : ViewModel() {

    private val _uiState = MutableStateFlow<WeiyunCloudUiState>(WeiyunCloudUiState.Loading)
    val uiState: StateFlow<WeiyunCloudUiState> = _uiState.asStateFlow()

    var cloudMessage by mutableStateOf<String?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var downloadTriggered by mutableStateOf(0)
        private set

    /** 待确认下载直链（弹窗展示） */
    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set
    private var pendingDownload: PendingDownload? = null
    var isFetchingLink by mutableStateOf(false)
        private set

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    init {
        loadRoot()
        // 登录态从无到有后自动重载根目录（对齐其它平台行为）
        viewModelScope.launch {
            loginState
                .drop(1)
                .distinctUntilChanged()
                .collect { loggedIn -> if (loggedIn) loadRoot() }
        }
    }

    private suspend fun cookie(): String =
        cookieProvider() ?: throw IllegalStateException("请先登录微云")

    // ---------- 目录浏览 ----------

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load("", emptyList())
    }

    fun openFolder(file: ShareFile) {
        dirStack.addLast(file.fid)
        nameStack.addLast(file.fname)
        load(file.fid, nameStack.toList())
    }

    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: "", nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: "", nameStack.toList())
    }

    fun refresh() {
        val current = uiState.value
        if (current !is WeiyunCloudUiState.Loaded) {
            loadRoot()
            return
        }
        refreshing = true
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(current.dirId, cookie())
                _uiState.value = WeiyunCloudUiState.Loaded(files, current.pathNames, current.dirId)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    // ---------- 下载 ----------

    /** 下载请求头：桌面 UA + 登录 Cookie（微云 CDN 需要 Cookie 校验） */
    private fun downloadHeaders(cookie: String, extraCookie: String): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to WeiyunConstants.UA,
            "Referer" to WeiyunConstants.SHARE_HOST + "/"
        )
        // 优先组装直链 Cookie，其次登录 Cookie（部分域名走登录态）
        val fullCookie = buildString {
            if (extraCookie.isNotBlank()) {
                append(extraCookie)
                append("; ")
            }
            append(cookie)
        }.trimEnd(' ', ';')
        if (fullCookie.isNotBlank()) headers["Cookie"] = fullCookie
        return headers
    }

    /** 打开文件 → 取直链 → 下载确认弹窗 */
    fun openDownload(file: ShareFile) {
        viewModelScope.launch {
            isFetchingLink = true
            try {
                val ck = cookie()
                val link = api.getDownloadLink(file, ck)
                    ?: throw IllegalStateException("获取下载链接失败")
                pendingDownload = PendingDownload(
                    url = link.downloadUrl,
                    fileName = file.fname.ifBlank { link.filename },
                    size = link.size,
                    headers = downloadHeaders(ck, link.downloadCookie)
                )
                downloadLink = link
            } catch (e: Exception) {
                cloudMessage = e.message ?: "下载失败"
            } finally {
                isFetchingLink = false
            }
        }
    }

    /** 下载弹窗确认：入队 */
    fun startDownload() {
        val pd = pendingDownload ?: return
        downloadLink = null
        pendingDownload = null
        viewModelScope.launch {
            downloadManager.enqueue(
                url = pd.url,
                fileName = pd.fileName,
                size = pd.size,
                platform = DownloadPlatform.WEIYUN,
                headers = pd.headers
            )
            cloudMessage = "已加入下载：${pd.fileName}"
            downloadTriggered++
        }
    }

    fun dismissDownloadDialog() {
        downloadLink = null
        pendingDownload = null
    }

    fun consumeMessage() {
        cloudMessage = null
    }

    fun consumeDownloadTriggered() {
        downloadTriggered = 0
    }

    // ---------- 内部 ----------

    private fun load(dirId: String, pathNames: List<String>) {
        _uiState.value = WeiyunCloudUiState.Loading
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(dirId, cookie())
                _uiState.value = WeiyunCloudUiState.Loaded(files, pathNames, dirId)
            } catch (e: Exception) {
                _uiState.value = WeiyunCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    class Factory(
        private val api: WeiyunApi,
        private val cookieProvider: suspend () -> String?,
        private val downloadManager: DownloadManager,
        private val loginState: Flow<Boolean>
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WeiyunCloudViewModel(api, cookieProvider, downloadManager, loginState) as T
    }
}