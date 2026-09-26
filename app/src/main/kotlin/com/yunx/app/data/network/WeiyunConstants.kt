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

/**
 * 腾讯微云（Weiyun）解析常量（协议逆向自 share.weiyun.com H5 前端与开源实现 weiyun_share / weiyun-sdk-go）。
 *
 * 解析链路（与官网分享页完全一致）：
 * - 分享查看：POST /webapp/json/weiyunShareNoLogin/WeiyunShareView（cmd 12002，未登录）或 weiyunShare（登录）→ 分享信息 + 根目录文件列表；
 * - 子目录：POST /webapp/json/weiyunShareNoLogin/WeiyunShareDirList（cmd 12031）；
 * - 下载直链：POST /webapp/json/weiyunShareNoLogin/WeiyunSharePartDownload（cmd 12023，匿名可用）或
 *             /webapp/json/weiyunShare/WeiyunShareBatchDownload（cmd 12024，需登录），返回 https_download_url + cookie；
 *
 * 登录（扫码/网页登录，与百度/139 一致）：
 * - WebView 打开官网 https://www.weiyun.com/ 由用户 QQ/微信扫码登录；
 * - 从 CookieManager 提取 weiyun.com 域 Cookie（关键字段 p_skey / wyctoken / wy_uf / qq_openid / openid…），
 *   DiskUserInfoGet（cmd 2201）校验并取昵称。
 */
object WeiyunConstants {

    // ---------- BaseURL ----------

    /** 官网（WebView 登录页 / 个人盘 API host） */
    const val WEB_HOST = "https://www.weiyun.com"

    /** 分享解析 host（与官网分享页同源接口） */
    const val SHARE_HOST = "https://share.weiyun.com"

    /** WebView 登录页：官网首页（QQ / 微信扫码由官网承载） */
    const val LOGIN_URL = "https://www.weiyun.com/"

    /** 个人盘 API host（DiskDirFileList / DiskFileBatchDownload / DiskUserInfoGet） */
    const val API_HOST = "https://www.weiyun.com"

    /** webapp JSON 协议路径模板：/webapp/json/{protocol}/{name}（与官网前端一致） */
    const val WEBAPP_PATH = "/webapp/json/{protocol}/{name}"

    /** 页面 refer / query 的 refer 参数（浏览器 chrome + windows，仿官网前端常量） */
    const val REFER = "chrome_windows"

    // ---------- UA ----------

    /** 桌面 Chrome UA（官网接口 UA 校验宽松；对齐 js 前端浏览器环境） */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ---------- 协议常量（cmd / protocol / name，来自分享页 JS 与 SDK） ----------

    /** 分享查看（cmd 12002）：protocol 视登录态 weiyunShare / weiyunShareNoLogin */
    const val PROTOCOL_SHARE_LOGIN = "weiyunShare"
    const val PROTOCOL_SHARE_NO_LOGIN = "weiyunShareNoLogin"
    const val NAME_SHARE_VIEW = "WeiyunShareView"
    const val CMD_SHARE_VIEW = 12002

    /** 分享子目录列表（cmd 12031，weiyunShareNoLogin） */
    const val NAME_SHARE_DIR_LIST = "WeiyunShareDirList"
    const val CMD_SHARE_DIR_LIST = 12031

    /** 分享文件下载（cmd 12023，weiyunShareNoLogin，匿名可用） */
    const val NAME_SHARE_PART_DOWNLOAD = "WeiyunSharePartDownload"
    const val CMD_SHARE_PART_DOWNLOAD = 12023

    /** 分享文件下载（cmd 12024，weiyunShare，需登录；原图/大文件场景） */
    const val NAME_SHARE_BATCH_DOWNLOAD = "WeiyunShareBatchDownload"
    const val CMD_SHARE_BATCH_DOWNLOAD = 12024

    /** 个人盘目录列表（cmd 2208，weiyunQdisk） */
    const val PROTOCOL_QDISK = "weiyunQdisk"
    const val NAME_DISK_DIR_FILE_LIST = "DiskDirFileList"
    const val CMD_DISK_DIR_FILE_LIST = 2208

    /** 个人盘文件下载（cmd 2402，weiyunQdiskClient） */
    const val PROTOCOL_QDISK_CLIENT = "weiyunQdiskClient"
    const val NAME_DISK_FILE_BATCH_DOWNLOAD = "DiskFileBatchDownload"
    const val CMD_DISK_FILE_BATCH_DOWNLOAD = 2402

    /** 用户信息 / 登录校验（cmd 2201，weiyunQdiskClient） */
    const val NAME_DISK_USER_INFO = "DiskUserInfoGet"
    const val CMD_DISK_USER_INFO = 2201

    /** 分享根目录 dir_key（WeiyunShareView 返回 pdir_key，作为根目录 id） */
    const val ROOT_DIR_KEY = "root"

    // ---------- 登录 Cookie 关键字段 ----------

    /** Cookie 中 g_tk 来源（wyctoken 值直接作为请求 query 的 g_tk，无需哈希） */
    const val COOKIE_G_TK = "wyctoken"

    /** QQ 登录 key（login_key_value） */
    const val COOKIE_P_SKEY = "p_skey"

    /** 登录类型（wy_uf：0=QQ 1=微信 2=openid） */
    const val COOKIE_WY_UF = "wy_uf"

    /** 尽量保留的 Cookie 字段（WebView 登录后按需筛选，保留登录/会话关键项） */
    val KEEP_COOKIE_KEYS = setOf(
        // QQ 系 / 会话核心
        "p_skey", "wyctoken", "wy_uf", "uin", "uid", "pt_key", "pt4_token", "pt_login_sig",
        "qm_keyst", "quneseckey", "skey", "p_uin", "p_skey_in",
        // 微信系
        "wx_login_ticket", "access_token", "refresh_token", "openid", "wy_appid", "wx_uid",
        "key_type", "weiyun_wx_openid", "weiyun_qq_openid", "qq_openid", "env_id",
        // 其它会话
        "wyctoken_expire", "hlp_login", "teana_uid", "teana_token"
    )

    /** 无效 Cookie 快速预检字段（p_skey 或 openid 系登录态至少要出现一个非空） */
    fun isValidCookie(cookie: String?): Boolean {
        if (cookie.isNullOrBlank()) return false
        val kvs = cookie.split(";").map { it.trim() }.filter { it.contains('=') }
        if (kvs.isEmpty()) return false
        // 登录态判定：wyctoken 非空（会话 token）+ 至少一个登录 key 非空
        fun nonEmpty(key: String): Boolean = kvs.any {
            val kv = it.split('=', limit = 2)
            kv[0].trim() == key && kv.getOrNull(1)?.isNotBlank() == true
        }
        if (!nonEmpty(COOKIE_G_TK)) return false
        return nonEmpty(COOKIE_P_SKEY) ||
            nonEmpty("openid") ||
            nonEmpty("weiyun_wx_openid") ||
            nonEmpty("weiyun_qq_openid")
    }

    /**
     * 从 CookieManager 提取 weiyun.com 登录态 Cookie：合并官网域 + 分享域，去重并按 [KEEP_COOKIE_KEYS] 筛选，
     * 拼成 "k=v; k2=v2"。
     * @param getCookie CookieManager.getCookie(domain) 的适配
     */
    fun extractCookies(getCookie: (String) -> String?): String {
        val out = linkedMapOf<String, String>()
        val domains = listOf(WEB_HOST, SHARE_HOST)
        for (domain in domains) {
            val raw = getCookie(domain) ?: continue
            for (item in raw.split(";")) {
                val kv = item.trim()
                val eq = kv.indexOf('=')
                if (eq <= 0) continue
                val k = kv.substring(0, eq)
                val v = kv.substring(eq + 1)
                if (k in KEEP_COOKIE_KEYS && k !in out) out[k] = v
            }
        }
        return out.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 从 Cookie 串提取指定键值（无则返回 null） */
    fun cookieValue(cookie: String?, key: String): String? {
        if (cookie.isNullOrBlank()) return null
        for (item in cookie.split(";")) {
            val kv = item.trim()
            val eq = kv.indexOf('=')
            if (eq > 0 && kv.substring(0, eq).trim() == key) {
                return kv.substring(eq + 1)
            }
        }
        return null
    }
}