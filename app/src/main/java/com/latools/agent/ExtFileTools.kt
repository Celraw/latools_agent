// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

package com.latools.agent

import android.content.ContentUris
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件与媒体：列目录、搜文件、最近照片、录屏。
 *
 * 文件系统访问分两条路：
 *  · 应用自己有权限的目录（files / Android/data）直接 java.io.File 读，快且不依赖通道；
 *  · 其它路径（/sdcard 下的公共目录）走特权通道的 ls / find，避免申请 MANAGE_EXTERNAL_STORAGE
 *    这种高敏感权限。
 */
object FileTools : ExtToolGroup {

    override val group = ToolGroup.FILES

    private val names = setOf("list_dir", "search_files", "recent_photos", "record_screen")

    override fun handles(name: String): Boolean = name in names

    override fun specs(config: AgentConfig, out: JSONArray) {
        appendTool(
            out,
            "list_dir",
            "列出目录内容（名称、类型、大小、修改时间）。应用有权限的目录直接读，其它路径需要 ROOT/Shizuku 通道。",
            toolProps("path" to toolProp("string", "目录绝对路径，默认 /sdcard")),
        )
        appendTool(
            out,
            "search_files",
            "在指定目录下按文件名搜索文件（递归）。需要 ROOT/Shizuku 通道。",
            toolProps(
                "query" to toolProp("string", "文件名片段，例如 .mp3 或 发票"),
                "root" to toolProp("string", "可选，搜索根目录，默认 /sdcard"),
                "limit" to toolProp("integer", "可选，最多返回条数，默认 30"),
            ),
            listOf("query"),
        )
        appendTool(
            out,
            "recent_photos",
            "列出相册最近的照片（按时间倒序）。可选返回最新一张的缩略图。需要「照片」权限。",
            toolProps(
                "limit" to toolProp("integer", "可选，条数，默认 10"),
                "thumbnail" to toolProp("boolean", "可选，true 时额外返回最新一张照片的缩略图，默认 false"),
            ),
        )
        appendTool(
            out,
            "record_screen",
            "录屏指定秒数，保存为 mp4 到应用目录并返回路径。需要 ROOT/Shizuku 通道。",
            toolProps("seconds" to toolProp("integer", "录屏秒数，默认 10，最大 60")),
        )
    }

    override suspend fun execute(ctx: ToolCtx, name: String, args: JSONObject): String = when (name) {
        "list_dir" -> listDir(ctx, args)
        "search_files" -> searchFiles(ctx, args)
        "recent_photos" -> recentPhotos(ctx, args)
        "record_screen" -> recordScreen(ctx, args)
        else -> "未知工具：$name"
    }

    private fun listDir(ctx: ToolCtx, args: JSONObject): String {
        val path = args.optString("path").ifBlank { "/sdcard" }
        val dir = File(path)
        val direct = runCatching { if (dir.isDirectory) dir.listFiles() else null }.getOrNull()
        if (direct != null) {
            if (direct.isEmpty()) return "目录为空：$path"
            val lines = direct.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .take(300)
                .map { f ->
                    buildString {
                        append(if (f.isDirectory) "\uD83D\uDCC1 " else "\uD83D\uDCC4 ")
                        append(f.name)
                        if (f.isFile) append("  ").append(fmtBytes(f.length()))
                        append("  ").append(SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(f.lastModified())))
                    }
                }
            return "目录 $path（${direct.size} 项，显示 ${lines.size} 项）：\n" + lines.joinToString("\n")
        }
        if (!ctx.hasShell()) {
            return "没有权限直接读取 $path，且没有可用的 ROOT/Shizuku 通道。\n" +
                "应用自身目录可以直接读：${ctx.context.filesDir}"
        }
        val r = ctx.shell("ls -la --color=never ${shqExt(path)} 2>&1 | head -n 300")
        return "目录 $path（特权通道）：\n${r.output.take(6_000)}"
    }

    private fun searchFiles(ctx: ToolCtx, args: JSONObject): String {
        val query = args.optString("query").trim()
        if (query.isBlank()) return "参数错误：缺少 query"
        val root = args.optString("root").ifBlank { "/sdcard" }
        val limit = args.optInt("limit", 30).coerceIn(1, 100)
        if (!ctx.hasShell()) {
            return "搜索文件需要 ROOT 或 Shizuku 通道；也可以在应用目录里用 list_dir 查找。"
        }
        val safeQuery = query.replace("'", "")
        val cmd = "find ${shqExt(root)} -type f -iname ${shqExt("*$safeQuery*")} 2>/dev/null | head -n $limit"
        val r = ctx.shell(cmd, 25_000)
        val lines = r.output.lines()
            .filter { it.startsWith("/") }
            .take(limit)
        if (lines.isEmpty()) return "没有找到文件名包含「$query」的文件（根目录：$root）"
        return "找到 ${lines.size} 个文件：\n" + lines.joinToString("\n")
    }

    private suspend fun recentPhotos(ctx: ToolCtx, args: JSONObject): String {
        ToolPermissions.check(ctx.context, ToolPerm.PHOTOS)?.let { return it }
        val limit = args.optInt("limit", 10).coerceIn(1, 50)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val rows = mutableListOf<String>()
        var firstUri: Uri? = null
        var firstDate = 0L
        runCatching {
            ctx.context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                while (cursor.moveToNext() && rows.size < limit) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1).orEmpty()
                    val dateAdded = cursor.getLong(2) * 1000
                    val size = cursor.getLong(3)
                    val mime = cursor.getString(4).orEmpty()
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    if (firstUri == null) {
                        firstUri = uri
                        firstDate = dateAdded
                    }
                    rows += "· ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(dateAdded))}  " +
                        "$name（${fmtBytes(size)}，${mime.ifBlank { "图片" }}）"
                }
            }
        }.getOrElse { return "读取相册失败：${it.message ?: it.javaClass.simpleName}" }
        if (rows.isEmpty()) return "相册里没有照片（或系统未授权读取）。"
        val text = "最近 ${rows.size} 张照片：\n" + rows.joinToString("\n")

        if (args.optBoolean("thumbnail", false)) {
            val uri = firstUri
            if (uri != null) {
                val image = thumbnailDataUrl(ctx, uri)
                if (image != null) {
                    return AgentTools.IMAGE_MARKER + image + "\n最新一张拍摄/保存时间：${fmtTime(firstDate)}\n" + text
                }
            }
        }
        return text
    }

    private fun thumbnailDataUrl(ctx: ToolCtx, uri: Uri): String? = runCatching {
        val size = Size(640, 640)
        val bitmap: Bitmap = ctx.context.contentResolver.loadThumbnail(uri, size, null)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out)
        bitmap.recycle()
        "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrNull()

    private suspend fun recordScreen(ctx: ToolCtx, args: JSONObject): String {
        if (!ctx.hasShell()) return "录屏需要 ROOT 或 Shizuku 通道（screenrecord 需要 shell 身份）"
        val seconds = args.optInt("seconds", 10).coerceIn(1, 60)
        if (!ctx.ask("开始录屏 ${seconds} 秒？屏幕内容会被保存为 mp4 到应用目录。")) return "用户取消了录屏"
        val dir = ctx.context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.context.filesDir
        dir.mkdirs()
        val file = File(dir, "screenrecord_${System.currentTimeMillis()}.mp4")
        val r = ctx.shell(
            "screenrecord --time-limit $seconds --bit-rate 8000000 ${shqExt(file.absolutePath)}",
            seconds * 1000L + 15_000L,
        )
        if (!file.exists() || file.length() <= 0) {
            return "录屏失败：${r.output.take(400)}"
        }
        return "录屏完成：${file.absolutePath}（${fmtBytes(file.length())}，约 $seconds 秒）" +
            if (r.ok) "" else "\n注意：命令退出码非 0（${r.output.take(200)}），文件可能不完整"
    }
}
