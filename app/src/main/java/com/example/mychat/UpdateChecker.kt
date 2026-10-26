package com.example.mychat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * v1.2.0 应用内自动检查更新
 *
 * 纯原生 OkHttp + org.json，不加任何新依赖。
 * 请求 GitHub Releases API，比对版本号，返回结构化信息供 UI 展示。
 */

/** 一次检查的结果。null = 网络失败或没有更新。 */
data class UpdateInfo(
    val latestVersion: String,   // 如 "1.2.0"（已去 v 前缀）
    val releaseTitle: String,    // Release 标题
    val releaseNotes: String,    // 更新日志（Markdown 原文）
    val githubUrl: String,       // Release HTML 页面
    val lanzouUrl: String        // 蓝奏云极速下载地址
)

/**
 * 检查 GitHub 最新 Release。
 *
 * 在 Dispatchers.IO 中执行网络请求；解析 tag_name 去掉 v 前缀后与当前版本比对。
 * 返回 UpdateInfo 表示有更新；返回 null 表示无更新或网络出错。
 */
suspend fun checkGitHubRelease(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
    try {
        val req = Request.Builder()
            .url("https://api.github.com/repos/jianling17/CoolStream/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CoolStream-Android")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w("UpdateChecker", "GitHub API HTTP " + resp.code)
                return@withContext null
            }
            val body = resp.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "")
            val latestVer = tagName.removePrefix("v").removePrefix("V").trim()
            if (latestVer.isBlank()) {
                Log.w("UpdateChecker", "tag_name 为空")
                return@withContext null
            }

            if (!isNewerVersion(currentVersion, latestVer)) {
                Log.d("UpdateChecker", "当前 " + currentVersion + " >= 最新 " + latestVer + "，无需更新")
                return@withContext null
            }

            val title = json.optString("name", latestVer)
            val notes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "https://github.com/jianling17/CoolStream/releases")
            val lanzou = "https://wwbbn.lanzouw.com/i5hX94dguj3g"

            Log.d("UpdateChecker", "发现新版本: " + latestVer + " (当前 " + currentVersion + ")")
            UpdateInfo(latestVer, title, notes, htmlUrl, lanzou)
        }
    } catch (e: Exception) {
        Log.w("UpdateChecker", "检查更新失败: " + e.message)
        null
    }
}

/**
 * 语义化版本比较：latest > current 时返回 true。
 *
 * 按点号分段，逐段比数字；非数字段按字符串字典序比。
 * 如 "1.2.0" vs "1.2.1" → true（1.2.1 更大）。
 */
fun isNewerVersion(current: String, latest: String): Boolean {
    val cur = current.removePrefix("v").removePrefix("V").trim()
    val lat = latest.removePrefix("v").removePrefix("V").trim()
    if (cur == lat) return false

    val curParts = cur.split(".")
    val latParts = lat.split(".")
    val maxLen = maxOf(curParts.size, latParts.size)
    for (i in 0 until maxLen) {
        val c = curParts.getOrElse(i) { "0" }
        val l = latParts.getOrElse(i) { "0" }
        val cNum = c.toIntOrNull()
        val lNum = l.toIntOrNull()
        if (cNum != null && lNum != null) {
            if (lNum > cNum) return true
            if (lNum < cNum) return false
        } else {
            // 非数字段：字典序
            if (l > c) return true
            if (l < c) return false
        }
    }
    return false
}
