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

package com.yunx.app.data.repository

import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.WeiyunApi
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 微云分享解析仓库（参考 123 云盘：分享解析无需登录，直链匿名取链）：
 * - createSession：WeiyunShareView 校验提取码 + 取标题（根 pdir_key 作为根目录 id）；
 * - listFiles：根目录用 WeiyunShareView 返回的列表；子目录用 WeiyunShareDirList；
 * - getShareDownloadLink：WeiyunSharePartDownload 匿名取直链（失败回落 BatchDownload 需登录）。
 *
 * 微云分享**不需要转存**即可下载（匿名直链），故 ensureTempDir / transferFile 不支持。
 */
class WeiyunResolveRepository(
    private val api: WeiyunApi,
    private val cookieProvider: suspend () -> String? = { null }
) : ShareResolveRepository {

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> {
        val parsed = ShareLinkParser.parse(link)
            ?: return Result.failure(IllegalArgumentException("无法识别分享链接"))
        return runCatching {
            val sharePwd = pwd?.takeIf { it.isNotBlank() } ?: parsed.pwd.orEmpty()
            val ck = cookie.ifBlank { cookieProvider() }.orEmpty()
            val token = api.getShareView(parsed.shareId, sharePwd, ck)
                ?: throw IllegalStateException("分享无效或已失效（可能需登录）")
            ShareSession(
                shareId = parsed.shareId,
                stoken = sharePwd,
                title = token.title
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )
    }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val ck = cookie.ifBlank { cookieProvider() }.orEmpty()
            // 根目录 / 空目录：WeiyunShareView 直接返回根列表；子目录：WeiyunShareDirList
            if (dirFid.isBlank() || dirFid == "root") {
                api.getShareRootFiles(session.shareId, session.stoken, ck)
                    ?: throw IllegalStateException("分享无效或已失效")
            } else {
                api.getShareDirList(session.shareId, session.stoken, dirFid, ck)
                    ?: throw IllegalStateException("获取目录列表失败")
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) }
        )

    /** 微云分享下载无需转存 */
    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("微云分享无需转存"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = Result.failure(UnsupportedOperationException("微云分享暂不支持转存"))

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("微云分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val ck = cookie.ifBlank { cookieProvider() }.orEmpty()
        val link = api.getShareDownloadLink(session.shareId, session.stoken, file, ck)
            ?: throw IllegalStateException("获取下载链接失败（可能需登录微云）")
        link.copy(filename = file.fname.ifBlank { link.filename })
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { Result.failure(it) }
    )
}