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

package com.yunx.app.data.network

import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom

/**
 * 腾讯微云 API 封装（OkHttp）：账号校验 / 分享解析 / 下载直链 / 个人盘浏览下载。
 *
 * 协议（逆向自 share.weiyun.com 前端与 weiyun-sdk-go）：
 * 所有接口统一走 POST /webapp/json/{protocol}/{name}，body 为
 *   {"req_header": <json 字符串>, "req_body": <json 字符串>}
 * req_body 内层为 {ReqMsg_body: {ext_req_head: {...}, ".weiyun.<Name>MsgReq_body": {...}}}；
 * 响应统一为 {"ret":0,"data":{"rsp_header":{"retcode":0,...},"rsp_body":{"RspMsg_body":{...}}}}。
 *
 * 关键点：
 * - g_tk 取 Cookie 中 wyctoken 的值（无需哈希，对齐官网前端 webapp 封装）；
 * - token_info：QQ 登录用 login_key_value=p_skey，微信/openid 用 openid+access_token；
 * - 分享下载 WeiyunSharePartDownload 匿名可用（无需登录）；WeiyunShareBatchDownload 需登录；
 * - 下载直链返回 https_download_url + cookie_name/cookie_value，下载时必须带该 Cookie。
 */
class WeiyunApi(
    private val clientProvider: () -> OkHttpClient = { HttpClients.apiClient() }
) {
    private val client get() = clientProvider()

    private val jsonMediaType = "application/json;charset=UTF-8".toMediaType()

    // ---------- 账号 ----------

    /**
     * 校验登录态并取昵称：DiskUserInfoGet（cmd 2201）→ data.rsp_body.RspMsg_body.nick_name。
     * 失败（Cookie 失效 / 未登录）返回 null。
     */
    suspend fun fetchNickname(cookie: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val rsp = diskUserInfo(cookie)
            rspBody(rsp).optString("nick_name").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 网盘空间详情：DiskUserInfoGet → used_space / total_space */
    suspend fun getQuota(cookie: String): QuotaInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val rsp = diskUserInfo(cookie)
            val msg = rspBody(rsp)
            QuotaInfo(
                used = msg.optLong("used_space"),
                total = msg.optLong("total_space")
            )
        }.getOrNull()
    }

    private suspend fun diskUserInfo(cookie: String): JSONObject = withContext(Dispatchers.IO) {
        val data = JSONObject()
            .put("is_get_upload_flow_flag", false)
            .put("is_get_high_speed_flow_info", false)
            .put("is_get_weiyun_flag", false)
            .put("is_get_space_clean_info", false)
            .put("is_get_user_reward_info", false)
        val body = requestBody(
            protocol = WeiyunConstants.PROTOCOL_QDISK_CLIENT,
            name = WeiyunConstants.NAME_DISK_USER_INFO,
            cmd = WeiyunConstants.CMD_DISK_USER_INFO,
            data = data,
            cookie = cookie
        )
        execute(
            host = WeiyunConstants.API_HOST,
            protocol = WeiyunConstants.PROTOCOL_QDISK_CLIENT,
            name = WeiyunConstants.NAME_DISK_USER_INFO,
            body = body,
            cookie = cookie
        )
    }

    // ---------- 分享解析 ----------

    /**
     * 分享查看（cmd 12002）：返回分享信息（标题 / 根 pdir_key）。
     */
    suspend fun getShareView(
        shareKey: String,
        pwd: String,
        cookie: String?
    ): ShareToken? = withContext(Dispatchers.IO) {
        val data = JSONObject()
            .put("share_key", shareKey)
            .put("os_info", "windows")
            .put("browser", "chrome")
            .put("share_pwd", pwd ?: "")
        val protocol = if (cookie.isNullOrBlank()) {
            WeiyunConstants.PROTOCOL_SHARE_NO_LOGIN
        } else {
            WeiyunConstants.PROTOCOL_SHARE_LOGIN
        }
        val body = requestBody(
            protocol = protocol,
            name = WeiyunConstants.NAME_SHARE_VIEW,
            cmd = WeiyunConstants.CMD_SHARE_VIEW,
            data = data,
            cookie = cookie.orEmpty()
        )
        runCatching {
            val rsp = execute(
                host = WeiyunConstants.SHARE_HOST,
                protocol = protocol,
                name = WeiyunConstants.NAME_SHARE_VIEW,
                body = body,
                cookie = cookie.orEmpty()
            )
            val msg = rspBody(rsp)
            ShareToken(
                stoken = shareKey,
                title = msg.optString("share_name").ifBlank { shareKey },
                firstFid = msg.optString("pdir_key").ifBlank { WeiyunConstants.ROOT_DIR_KEY }
            )
        }.getOrNull()
    }

    /** 解析分享根目录文件列表：WeiyunShareView 响应 RspMsg_body 的 dir_list/file_list */
    suspend fun parseShareRootFiles(rspMsg: JSONObject): List<ShareFile>? = withContext(Dispatchers.IO) {
        runCatching { parseFileList(rspMsg) }.getOrNull()
    }

    /**
     * 分享根目录文件列表：复用 WeiyunShareView（cmd 12002）响应里的 dir_list/file_list。
     * 与 getShareDirList 同构；分享根目录无需再调 12031。
     */
    suspend fun getShareRootFiles(
        shareKey: String,
        pwd: String,
        cookie: String?
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val data = JSONObject()
            .put("share_key", shareKey)
            .put("os_info", "windows")
            .put("browser", "chrome")
            .put("share_pwd", pwd ?: "")
        val protocol = if (cookie.isNullOrBlank()) {
            WeiyunConstants.PROTOCOL_SHARE_NO_LOGIN
        } else {
            WeiyunConstants.PROTOCOL_SHARE_LOGIN
        }
        val body = requestBody(
            protocol = protocol,
            name = WeiyunConstants.NAME_SHARE_VIEW,
            cmd = WeiyunConstants.CMD_SHARE_VIEW,
            data = data,
            cookie = cookie.orEmpty()
        )
        runCatching {
            val rsp = execute(
                host = WeiyunConstants.SHARE_HOST,
                protocol = protocol,
                name = WeiyunConstants.NAME_SHARE_VIEW,
                body = body,
                cookie = cookie.orEmpty()
            )
            parseFileList(rspBody(rsp))
        }.getOrNull()
    }

    /** 分享子目录列表（cmd 12031）：dir_key 为目录 id */
    suspend fun getShareDirList(
        shareKey: String,
        pwd: String,
        dirKey: String,
        cookie: String?
    ): List<ShareFile>? = withContext(Dispatchers.IO) {
        val data = JSONObject()
            .put("share_key", shareKey)
            .put("share_pwd", pwd ?: "")
            .put("dir_key", dirKey)
            .put("dir_name", "")
            .put("get_type", 0)
            .put("start", 0)
            .put("count", 100)
            .put("sort_field", 0)
            .put("get_abstract_url", true)
        val body = requestBody(
            protocol = WeiyunConstants.PROTOCOL_SHARE_NO_LOGIN,
            name = WeiyunConstants.NAME_SHARE_DIR_LIST,
            cmd = WeiyunConstants.CMD_SHARE_DIR_LIST,
            data = data,
            cookie = cookie.orEmpty()
        )
        runCatching {
            val rsp = execute(
                host = WeiyunConstants.SHARE_HOST,
                protocol = WeiyunConstants.PROTOCOL_SHARE_NO_LOGIN,
                name = WeiyunConstants.NAME_SHARE_DIR_LIST,
                body = body,
                cookie = cookie.orEmpty()
            )
            parseFileList(rspBody(rsp))
        }.getOrNull()
    }

    /**
     * 分享文件下载直链：
     * - 先匿名 WeiyunSharePartDownload（cmd 12023，weiyunShareNoLogin）；
     * - 匿名失败且有 cookie 时回落 WeiyunShareBatchDownload（cmd 12024，weiyunShare）。
     */
    suspend fun getShareDownloadLink(
        shareKey: String,
        pwd: String,
        file: ShareFile,
        cookie: String?
    ): DownloadLink? = withContext(Dispatchers.IO) {
        val partData = JSONObject()
            .put("share_key", shareKey)
            .put("pwd", pwd ?: "")
            .put("pdir_key", file.pdirFid)
            .put(
                "file_list",
                JSONArray().put(
                    JSONObject()
                        .put("file_id", file.fid)
                        .put("pdir_key", file.pdirFid)
                )
            )
        val pb = requestBody(
            protocol = WeiyunConstants.PROTOCOL_SHARE_NO_LOGIN,
            name = WeiyunConstants.NAME_SHARE_PART_DOWNLOAD,
            cmd = WeiyunConstants.CMD_SHARE_PART_DOWNLOAD,
            data = partData,
            cookie = cookie.orEmpty()
        )
        val anonymous = runCatching {
            val rsp = execute(
                host = WeiyunConstants.SHARE_HOST,
                protocol = WeiyunConstants.PROTOCOL_SHARE_NO_LOGIN,
                name = WeiyunConstants.NAME_SHARE_PART_DOWNLOAD,
                body = pb,
                cookie = cookie.orEmpty()
            )
            parseDownloadLink(rspBody(rsp), file)
        }.getOrNull()
        if (anonymous != null) return@withContext anonymous

        if (!cookie.isNullOrBlank()) {
            val batchData = JSONObject()
                .put("share_key", shareKey)
                .put("pwd", pwd ?: "")
                .put("download_type", 0)
                .put(
                    "file_list",
                    JSONArray().put(
                        JSONObject()
                            .put("pdir_key", file.pdirFid)
                            .put("file_id", file.fid)
                            .put("filename", file.fname)
                            .put("file_size", file.fsize)
                    )
                )
            val bb = requestBody(
                protocol = WeiyunConstants.PROTOCOL_SHARE_LOGIN,
                name = WeiyunConstants.NAME_SHARE_BATCH_DOWNLOAD,
                cmd = WeiyunConstants.CMD_SHARE_BATCH_DOWNLOAD,
                data = batchData,
                cookie = cookie
            )
            runCatching {
                val rsp = execute(
                    host = WeiyunConstants.SHARE_HOST,
                    protocol = WeiyunConstants.PROTOCOL_SHARE_LOGIN,
                    name = WeiyunConstants.NAME_SHARE_BATCH_DOWNLOAD,
                    body = bb,
                    cookie = cookie
                )
                parseDownloadLink(rspBody(rsp), file)
            }.getOrNull()?.let { return@withContext it }
        }
        null
    }

    // ---------- 个人盘 ----------

    /** 个人盘目录列表（cmd 2208）：返回该目录下全部文件（分页拉取）。根目录（空 / "main"）先取 main_dir_key */
    suspend fun listCloudFiles(dirKey: String, cookie: String): List<ShareFile> = withContext(Dispatchers.IO) {
        // 根目录标识：微云根目录 id 为 DiskUserInfoGet 返回的 main_dir_key
        val effectiveDirKey = if (dirKey.isBlank() || dirKey == "main" || dirKey == WeiyunConstants.ROOT_DIR_KEY) {
            val info = diskUserInfo(cookie)
            rspBody(info).optString("main_dir_key").ifBlank { "" }
        } else {
            dirKey
        }
        if (effectiveDirKey.isBlank()) throw IllegalStateException("无法获取微云根目录")

        val all = mutableListOf<ShareFile>()
        var start = 0
        repeat(100) { // 封顶 100 页防异常死循环
            val data = JSONObject()
                .put("dir_key", effectiveDirKey)
                .put("start", start)
                .put("count", 100)
                .put("sort_field", 2)
                .put("reverse_order", false)
                .put("get_type", 0)
                .put("get_abstract_url", false)
                .put("get_dir_detail_info", false)
            val body = requestBody(
                protocol = WeiyunConstants.PROTOCOL_QDISK,
                name = WeiyunConstants.NAME_DISK_DIR_FILE_LIST,
                cmd = WeiyunConstants.CMD_DISK_DIR_FILE_LIST,
                data = data,
                cookie = cookie
            )
            val rsp = execute(
                host = WeiyunConstants.API_HOST,
                protocol = WeiyunConstants.PROTOCOL_QDISK,
                name = WeiyunConstants.NAME_DISK_DIR_FILE_LIST,
                body = body,
                cookie = cookie
            )
            val msg = rspBody(rsp)
            val files = parseFileList(msg)
            all += files
            if (msg.optBoolean("finish_flag", true) || files.isEmpty()) break
            start += 100
        }
        all
    }

    /** 个人盘文件下载（cmd 2402）：返回直链 + Cookie */
    suspend fun getDownloadLink(file: ShareFile, cookie: String): DownloadLink? = withContext(Dispatchers.IO) {
        val data = JSONObject()
            .put(
                "file_list",
                JSONArray().put(
                    JSONObject()
                        .put("pdir_key", file.pdirFid)
                        .put("file_id", file.fid)
                )
            )
            .put("download_type", 0)
        val body = requestBody(
            protocol = WeiyunConstants.PROTOCOL_QDISK_CLIENT,
            name = WeiyunConstants.NAME_DISK_FILE_BATCH_DOWNLOAD,
            cmd = WeiyunConstants.CMD_DISK_FILE_BATCH_DOWNLOAD,
            data = data,
            cookie = cookie
        )
        runCatching {
            val rsp = execute(
                host = WeiyunConstants.API_HOST,
                protocol = WeiyunConstants.PROTOCOL_QDISK_CLIENT,
                name = WeiyunConstants.NAME_DISK_FILE_BATCH_DOWNLOAD,
                body = body,
                cookie = cookie
            )
            parseDownloadLink(rspBody(rsp), file)
        }.getOrNull()
    }

    // ---------- 请求构造 / 响应解析 ----------

    /** 构造 webapp 请求体（与官网前端 request.webapp 一致） */
    private fun requestBody(protocol: String, name: String, cmd: Int, data: JSONObject, cookie: String): String {
        val tokenInfo = tokenInfo(cookie)
        val reqHeader = JSONObject()
            .put("seq", System.currentTimeMillis() / 1000 + ThreadLocalRandom.current().nextInt(10000))
            .put("type", 1)
            .put("cmd", cmd)
            .put("appid", 30113)
            .put("version", 3)
            .put("major_version", 3)
            .put("minor_version", 3)
            .put("fix_version", 3)
            .put("wx_openid", "")
            .put("qq_openid", "")
            .put("user_flag", 0)

        val extReqHead = JSONObject()
            .put("token_info", tokenInfo)
            .put("language_info", JSONObject().put("language_type", 2052))

        val reqMsgBody = JSONObject()
            .put("ext_req_head", extReqHead)
            .put(".weiyun." + name + "MsgReq_body", data)

        val reqBody = JSONObject().put("ReqMsg_body", reqMsgBody)

        return JSONObject()
            .put("req_header", reqHeader.toString())
            .put("req_body", reqBody.toString())
            .toString()
    }

    /** token_info：QQ 用 p_skey；微信/openid 用 openid+access_token；匿名空凭据 */
    private fun tokenInfo(cookie: String): JSONObject {
        val pSkey = WeiyunConstants.cookieValue(cookie, WeiyunConstants.COOKIE_P_SKEY)
        val openId = WeiyunConstants.cookieValue(cookie, "openid")
            ?: WeiyunConstants.cookieValue(cookie, "weiyun_wx_openid")
        val accessToken = WeiyunConstants.cookieValue(cookie, "access_token")
        val wyUf = WeiyunConstants.cookieValue(cookie, WeiyunConstants.COOKIE_WY_UF)
        return if (pSkey != null && pSkey.isNotBlank()) {
            JSONObject()
                .put("token_type", 0)
                .put("login_key_type", 27)
                .put("login_key_value", pSkey)
                .put("openid", "")
        } else if ((wyUf == "1" || wyUf == "2") && openId != null && accessToken != null) {
            JSONObject()
                .put("token_type", 1)
                .put("openid", openId)
                .put("open_appid", WeiyunConstants.cookieValue(cookie, "wy_appid") ?: "")
                .put("access_token", accessToken)
                .put("login_key_type", 192)
                .put("login_key_value", accessToken)
        } else {
            JSONObject()
                .put("token_type", 0)
                .put("login_key_type", 27)
                .put("login_key_value", "")
                .put("openid", "")
        }
    }

    /** 执行 POST：url = host + /webapp/json/{protocol}/{name}?g_tk=<wyctoken>&refer=…&r=… */
    private fun execute(
        host: String,
        protocol: String,
        name: String,
        body: String,
        cookie: String
    ): JSONObject {
        val gTk = WeiyunConstants.cookieValue(cookie, WeiyunConstants.COOKIE_G_TK).orEmpty()
        val url = buildString {
            append(host)
            append("/webapp/json/").append(protocol).append("/").append(name)
            append("?g_tk=").append(gTk)
            append("&refer=").append(WeiyunConstants.REFER)
            append("&r=").append(Math.random())
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WeiyunConstants.UA)
            .header("Content-Type", "application/json;charset=UTF-8")
            .apply {
                if (cookie.isNotBlank()) header("Cookie", cookie)
            }
            .apply {
                // Origin/Referer 与目标 host 保持一致（分享域 / 官网域）
                val origin = if (host == WeiyunConstants.SHARE_HOST) WeiyunConstants.SHARE_HOST else WeiyunConstants.WEB_HOST
                header("Origin", origin)
                header("Referer", origin + "/")
            }
            .post(body.toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string()
                ?: throw IllegalStateException("请求失败：响应为空（${response.code}）")
            if (response.code != 200) {
                throw IllegalStateException("请求失败（HTTP ${response.code}）")
            }
            val json = runCatching { JSONObject(respBody) }
                .getOrElse { throw IllegalStateException("响应解析失败：${respBody.take(200)}") }
            val ret = json.optInt("ret", 0)
            val data = json.optJSONObject("data")
            val rspHeader = data?.optJSONObject("rsp_header")
            val retcode = rspHeader?.optInt("retcode", 0) ?: 0
            if (ret != 0 || (data != null && retcode != 0)) {
                val msg = rspHeader?.optString("retmsg")
                    ?.ifBlank { json.optString("msg") }
                    ?: json.optString("msg").ifBlank { "请求失败" }
                throw IllegalStateException("$msg（ret=$ret, retcode=$retcode）")
            }
            json
        }
    }

    private fun rspBody(rsp: JSONObject): JSONObject {
        val data = rsp.optJSONObject("data")
            ?: throw IllegalStateException("响应缺少 data")
        return data.optJSONObject("rsp_body")
            ?.optJSONObject("RspMsg_body")
            ?: throw IllegalStateException("响应缺少 RspMsg_body")
    }

    /** 解析 dir_list / file_list → ShareFile 列表 */
    private fun parseFileList(msg: JSONObject): List<ShareFile> {
        val out = mutableListOf<ShareFile>()
        val dirList = msg.optJSONArray("dir_list") ?: JSONArray()
        for (i in 0 until dirList.length()) {
            val d = dirList.optJSONObject(i) ?: continue
            out.add(
                ShareFile(
                    fid = d.optString("dir_key"),
                    fname = d.optString("dir_name"),
                    fsize = 0L,
                    isdir = true,
                    pdirFid = d.optString("pdir_key"),
                    fidToken = "",
                    modifyTime = d.optString("dir_mtime")
                )
            )
        }
        val fileList = msg.optJSONArray("file_list") ?: JSONArray()
        for (i in 0 until fileList.length()) {
            val f = fileList.optJSONObject(i) ?: continue
            out.add(
                ShareFile(
                    fid = f.optString("file_id"),
                    fname = f.optString("filename").ifBlank { f.optString("file_name") },
                    fsize = f.optLong("file_size"),
                    isdir = false,
                    pdirFid = f.optString("pdir_key"),
                    fidToken = "",
                    modifyTime = f.optString("file_mtime")
                )
            )
        }
        return out
    }

    /** 解析下载直链响应 → DownloadLink（https_download_url 优先；携带 cookie）。
     * 兼容两种响应形态（官网 JS 实证）：
     * - WeiyunSharePartDownload（cmd 12023）：平铺 {https_download_url, cookie_name, cookie_value, …}；
     * - WeiyunShareBatchDownload（cmd 12024）：{file_list:[{https_download_url/download_url, cookie_name, cookie_value}]}
     */
    private fun parseDownloadLink(msg: JSONObject, file: ShareFile): DownloadLink {
        // 形态 1：平铺
        val flatUrl = msg.optString("https_download_url").ifBlank { msg.optString("download_url") }
        val (url, cookieName, cookieValue) = if (flatUrl.isNotBlank()) {
            Triple(flatUrl, msg.optString("cookie_name"), msg.optString("cookie_value"))
        } else {
            // 形态 2：file_list[]
            val arr = msg.optJSONArray("file_list") ?: JSONArray()
            if (arr.length() == 0) throw IllegalStateException("未返回下载链接")
            val item = arr.optJSONObject(0) ?: throw IllegalStateException("未返回下载链接")
            val u = item.optString("https_download_url").ifBlank { item.optString("download_url") }
            if (u.isBlank()) throw IllegalStateException("未返回下载地址")
            Triple(u, item.optString("cookie_name"), item.optString("cookie_value"))
        }
        return DownloadLink(
            fid = file.fid,
            filename = file.fname,
            downloadUrl = url,
            size = file.fsize,
            downloadCookie = if (cookieName.isNotBlank() && cookieValue.isNotBlank()) {
                "$cookieName=$cookieValue"
            } else {
                ""
            }
        )
    }
}