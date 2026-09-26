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

package com.yunx.app.ui.screens

import com.yunx.app.ui.SnackbarController
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.ui.components.ScrollToTopButton
import com.yunx.app.ui.resolve.BackToParentItem
import com.yunx.app.ui.resolve.CrumbBar
import com.yunx.app.ui.resolve.DownloadLinkDialog
import com.yunx.app.ui.resolve.ShareFileRow
import com.yunx.app.ui.viewmodel.WeiyunCloudUiState
import com.yunx.app.ui.viewmodel.WeiyunCloudViewModel

/**
 * 微云云盘浏览页（精简版）：
 * - 目录浏览 + 下拉刷新 + 面包屑回退；
 * - 文件点击取直链下载（DiskFileBatchDownload，携带 Cookie）；
 * 微云暂未接入重命名/移动/删除/分享等管理操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeiyunCloudScreen(
    viewModel: WeiyunCloudViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    onExit: () -> Unit,
    onDownloadStarted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    // 系统返回键：子目录返回上一级，根目录返回账号列表
    BackHandler {
        val s = state
        if (s is WeiyunCloudUiState.Loaded && s.pathNames.isNotEmpty()) viewModel.back() else onExit()
    }
    val listState = rememberLazyListState()
    // 搜索过滤（本地过滤当前目录文件）
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    // 各目录滚动位置记忆
    val scrollPositions = remember { mutableStateMapOf<String, Int>() }
    val loadedState = state as? WeiyunCloudUiState.Loaded
    val displayFiles = remember(loadedState?.files, searchQuery) {
        val files = loadedState?.files ?: emptyList()
        val q = searchQuery.trim()
        if (q.isEmpty()) files else files.filter { it.fname.contains(q, ignoreCase = true) }
    }
    val currentDirKey = remember(loadedState?.pathNames) {
        loadedState?.pathNames?.joinToString("/") ?: ""
    }

    LaunchedEffect(viewModel.cloudMessage) {
        viewModel.cloudMessage?.let {
            SnackbarController.show(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(viewModel.downloadTriggered) {
        if (viewModel.downloadTriggered > 0) {
            viewModel.consumeDownloadTriggered()
            onDownloadStarted()
        }
    }

    // 单文件下载确认弹窗
    viewModel.downloadLink?.let { link ->
        DownloadLinkDialog(
            link = link,
            onDownload = { viewModel.startDownload() },
            onDismiss = { viewModel.dismissDownloadDialog() }
        )
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(140))
            },
            label = "weiyunCloudState"
        ) { s ->
            when (s) {
                is WeiyunCloudUiState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                is WeiyunCloudUiState.Error -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = onExit) { Text("返回") }
                            TextButton(onClick = { viewModel.loadRoot() }) { Text("重试") }
                        }
                    }
                }

                is WeiyunCloudUiState.Loaded -> Box(modifier = Modifier.fillMaxSize()) {
                    val loadedKey = remember(s.pathNames) { s.pathNames.joinToString("/") }
                    LaunchedEffect(loadedKey) {
                        listState.scrollToItem(scrollPositions[loadedKey] ?: 0)
                    }
                    PullToRefreshBox(
                        isRefreshing = viewModel.refreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = onExit) {
                                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "微云",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = if (searchQuery.isBlank()) "共 ${s.files.size} 项"
                                                else "匹配 ${displayFiles.size} / ${s.files.size} 项",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = { showSearch = !showSearch }) {
                                            Icon(
                                                imageVector = Icons.Outlined.Search,
                                                contentDescription = if (showSearch) "关闭搜索" else "搜索文件",
                                                tint = if (showSearch) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                }
                                            )
                                        }
                                    }
                                    CrumbBar(
                                        rootTitle = "微云",
                                        pathNames = s.pathNames,
                                        onNavigate = { level ->
                                            scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
                                            viewModel.navigateToLevel(level)
                                        }
                                    )
                                    AnimatedVisibility(
                                        visible = showSearch,
                                        enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                                        exit = shrinkVertically(tween(140)) + fadeOut(tween(120))
                                    ) {
                                        Column {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            OutlinedTextField(
                                                value = searchQuery,
                                                onValueChange = { searchQuery = it },
                                                modifier = Modifier.fillMaxWidth(),
                                                placeholder = { Text("搜索当前目录文件") },
                                                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                                trailingIcon = {
                                                    if (searchQuery.isNotEmpty()) {
                                                        IconButton(onClick = { searchQuery = "" }) {
                                                            Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                                                        }
                                                    }
                                                },
                                                singleLine = true,
                                                shape = MaterialTheme.shapes.large
                                            )
                                        }
                                    }
                                }
                            }

                            if (s.pathNames.isNotEmpty()) {
                                item {
                                    BackToParentItem(onClick = {
                                        scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
                                        viewModel.back()
                                    })
                                }
                            }

                            if (displayFiles.isEmpty()) {
                                item {
                                    Text(
                                        text = if (s.files.isEmpty()) "此目录为空" else "未找到匹配「${searchQuery.trim()}」的文件",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 32.dp),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            items(displayFiles, key = { it.fid }) { file ->
                                ShareFileRow(
                                    file = file,
                                    onClick = {
                                        if (file.isdir) {
                                            scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
                                            viewModel.openFolder(file)
                                        } else {
                                            viewModel.openDownload(file)
                                        }
                                    },
                                    onMore = null,
                                    onLongClick = null,
                                    selected = false,
                                    showCheckbox = false
                                )
                            }
                        }
                    }
                    ScrollToTopButton(
                        listState = listState,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 16.dp)
                    )
                }
            }
        }
    }

    // 取直链加载弹窗
    if (viewModel.isFetchingLink) {
        AlertDialog(
            onDismissRequest = { },
            confirmButton = { },
            title = { Text("获取下载链接") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("正在获取下载链接，请稍候…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        )
    }
}