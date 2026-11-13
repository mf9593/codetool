package com.codetool.app.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Releases 更新检查。
 *
 * 直连 api.github.com，失败时回退 gh-proxy.com 镜像（国内网络）。
 * 只读公开 API，无需任何凭据。
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO = "mf9593/codetool"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val API_PROXY = "https://gh-proxy.com/$API"

    data class Release(
        val tag: String,          // 如 v2.0.1
        val title: String,        // release 名称
        val notes: String,        // 更新说明（body）
        val pageUrl: String,      // release 页面
        val apkUrl: String?,      // APK 直链（无资产时为 null）
        val apkSizeMb: Double?,   // APK 大小（MB）
    )

    sealed interface Result {
        /** 发现新版本 */
        data class Update(val release: Release) : Result
        /** 已是最新 */
        object UpToDate : Result
        /** 仓库还没有 Release */
        object NoRelease : Result
        /** 网络 / 解析失败 */
        data class Failure(val reason: String) : Result
    }

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        val body = fetch(API) ?: fetch(API_PROXY)
            ?: return@withContext Result.Failure("网络不可达")
        if (body == "404") return@withContext Result.NoRelease
        val rel = runCatching { parse(body) }.getOrNull()
            ?: return@withContext Result.Failure("返回数据不完整")
        if (isNewer(rel.tag, currentVersion)) Result.Update(rel) else Result.UpToDate
    }

    /** 成功返回 body；404 返回 "404"；失败返回 null。 */
    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "CodeTool-App")
            val code = conn.responseCode
            Log.d(TAG, "GET $url -> $code")
            when (code) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                HttpURLConnection.HTTP_NOT_FOUND -> "404"
                else -> null
            }
        } catch (e: Exception) {
            Log.d(TAG, "GET $url failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(body: String): Release? {
        val o = JSONObject(body)
        val tag = o.optString("tag_name").ifEmpty { return null }
        val title = o.optString("name").ifEmpty { tag }
        val notes = o.optString("body")
        val page = o.optString("html_url")
        var apkUrl: String? = null
        var sizeMb: Double? = null
        val assets = o.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    val bytes = a.optLong("size", -1)
                    if (bytes > 0) sizeMb = bytes / 1024.0 / 1024.0
                    break
                }
            }
        }
        return Release(tag, title, notes, page, apkUrl, sizeMb)
    }

    /**
     * 语义化版本比较。忽略前导 v 与预发布后缀（2.0.1-beta → 2.0.1）。
     * 返回 >0 表示 a 更新，0 相等，<0 表示 a 更旧。
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = normalize(a)
        val pb = normalize(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    fun isNewer(latest: String, current: String): Boolean =
        compareVersions(latest, current) > 0

    private fun normalize(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.')
            .map { seg -> seg.filter { it.isDigit() }.toIntOrNull() ?: 0 }
}
