// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

package com.latools.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端更新检查（组件分发）。
 *
 * 提权模块 / 灵动岛插件 / LA Agent 都是独立安装的 APK，不在主工具箱的更新链路里。
 * 这里统一走官网公开接口 `GET /api/v1/components`（无需登录、无 attest 闸门）：
 *   · 只读自己对应的 component id；
 *   · 比较服务端 versionCode 与本地 PackageInfo.versionCode；
 *   · 有新版本就弹窗，展示后台填写的 versionName + changelog，点「去更新」打开下载接口。
 *
 * 设计取舍：
 *   · 不用 DownloadManager/静默安装：三个包分属不同签名与权限，交给系统浏览器下载安装最稳；
 *   · 网络失败/服务端未发布一律静默，不影响应用正常使用；
 *   · 每次 Activity 创建只查一次，用户点「稍后」当次不再弹。
 */
private data class LaCloudUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
)

private object LaCloudUpdate {
    /** 官网/接口根地址；组件下载地址由后台返回相对路径。 */
    private const val BASE_URL = "https://latools.top"

    suspend fun check(context: Context, componentId: String): LaCloudUpdateInfo? =
        withContext(Dispatchers.IO) {
            runCatching {
                val current = currentVersionCode(context)
                val conn = (URL("$BASE_URL/api/v1/components").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4_000
                    readTimeout = 6_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "LAToolsComponent/1.0")
                }
                try {
                    if (conn.responseCode != 200) return@runCatching null
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val item = JSONObject(body)
                        .optJSONObject("components")
                        ?.optJSONObject(componentId)
                        ?: return@runCatching null
                    val code = item.optInt("version_code", 0)
                    if (code <= current) return@runCatching null
                    val rawUrl = item.optString("download_url", "")
                    if (rawUrl.isBlank()) return@runCatching null
                    val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
                        rawUrl
                    } else {
                        BASE_URL + rawUrl
                    }
                    LaCloudUpdateInfo(
                        versionCode = code,
                        versionName = item.optString("version_name").ifBlank { code.toString() },
                        changelog = item.optString("changelog"),
                        downloadUrl = url,
                    )
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()
        }

    fun openDownload(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun currentVersionCode(context: Context): Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)
}

/**
 * 在 Activity 的 Compose 内容里调用一次即可；有更新时自动弹窗。
 *
 * @param componentId 后台组件分发的 id：privilege / island / agent
 */
@Composable
fun LaCloudUpdateEffect(componentId: String) {
    val context = LocalContext.current
    var info by remember { mutableStateOf<LaCloudUpdateInfo?>(null) }
    LaunchedEffect(componentId) {
        info = LaCloudUpdate.check(context.applicationContext, componentId)
    }
    val update = info ?: return
    AlertDialog(
        onDismissRequest = { info = null },
        title = { Text("发现新版本 v${update.versionName}") },
        text = {
            Text(
                buildString {
                    append("当前安装的是旧版本，建议更新到最新版。")
                    if (update.changelog.isNotBlank()) {
                        append("\n\n")
                        append(update.changelog.take(600))
                    }
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                LaCloudUpdate.openDownload(context, update.downloadUrl)
                info = null
            }) { Text("去更新") }
        },
        dismissButton = {
            TextButton(onClick = { info = null }) { Text("稍后") }
        },
    )
}
