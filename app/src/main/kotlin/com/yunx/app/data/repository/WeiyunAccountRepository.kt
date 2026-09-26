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

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.yunx.app.data.db.WeiyunAccountDao
import com.yunx.app.data.db.WeiyunAccountEntity
import com.yunx.app.data.network.WeiyunApi
import com.yunx.app.data.network.WeiyunConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * 微云账号仓库：WebView 扫码登录（QQ/微信）→ 提取 Cookie 落库。
 * 凭证 = Cookie（关键字段 p_skey / wyctoken / wy_uf / openid）；失效时重新扫码登录。
 */
class WeiyunAccountRepository(
    private val dao: WeiyunAccountDao,
    private val api: WeiyunApi
) {

    fun observeAccount(): Flow<WeiyunAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): WeiyunAccountEntity? = dao.getAccount()

    /**
     * Cookie 校验并落库：先预检字段，再用 DiskUserInfoGet 确认登录态并取昵称。
     * @return 是否保存成功（Cookie 有效）
     */
    suspend fun saveCookie(cookie: String): Boolean {
        val c = cookie.trim()
        if (!WeiyunConstants.isValidCookie(c)) return false
        val nickname = api.fetchNickname(c)
        if (nickname == null) return false
        dao.upsert(
            WeiyunAccountEntity(
                id = "weiyun",
                cookie = c,
                nickname = nickname
            )
        )
        return true
    }

    /** 校验当前 Cookie 是否仍有效（失败自动清库，下次重新登录） */
    suspend fun validate(): Boolean {
        val acc = dao.getAccount() ?: return false
        val ok = api.fetchNickname(acc.cookie) != null
        if (!ok) dao.clear()
        return ok
    }

    /** 退出登录：清库 + 清理 WebView 登录态（Cookie 与 DOM 存储） */
    suspend fun logout() {
        withContext(Dispatchers.IO) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
            runCatching { WebStorage.getInstance().deleteAllData() }
        }
        dao.clear()
    }
}