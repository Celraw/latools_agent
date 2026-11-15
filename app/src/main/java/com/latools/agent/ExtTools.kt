// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

package com.latools.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 扩展工具分组。
 *
 * 为什么要分组：工具很快就有几十个，全塞给模型会稀释注意力、增加选错工具的概率。
 * 设置页按组开关，关掉的分组直接从 tools 列表里移除（模型根本看不到），
 * 比"执行时再拒绝"更干净，也让用户能按需缩小 Agent 的能力面。
 */
enum class ToolGroup(val id: String, val label: String, val hint: String) {
    TIME("time", "时间与提醒", "闹钟、计时器、提醒"),
    LOCATION("location", "位置与导航", "定位、地图导航"),
    DEVICE("device", "设备控制", "锁屏、手电筒、音量、亮度等"),
    STATUS("status", "状态查询", "电量、网络、通知、用量"),
    CONTENT("content", "通讯与日程", "联系人、电话、短信、日历"),
    FILES("files", "文件与媒体", "目录、搜索、照片、录屏"),
    ADVANCED("advanced", "高级与危险", "安装/卸载应用（需确认）");

    companion object {
        val DEFAULT: Set<String> = entries.map { it.id }.toSet()

        fun from(id: String?): ToolGroup? = entries.firstOrNull { it.id == id }
    }
}

/** 工具执行上下文：把 context / 配置 / 确认回调打成一个包，避免每个函数都传一串参数。 */
class ToolCtx(
    val context: Context,
    val config: AgentConfig,
    val confirm: suspend (String) -> Boolean,
) {
    fun shell(command: String, timeoutMs: Long = 15_000): ShellResult =
        AgentShell.run(context, command, config.channels, timeoutMs)

    fun hasShell(): Boolean = AgentShell.available(context).isNotEmpty()

    suspend fun ask(message: String): Boolean = confirm(message)

    /** 普通写操作：APPROVE 模式下问，AUTO/FULL 不问（危险操作另用 ask）。 */
    suspend fun askWrite(message: String): Boolean =
        config.approvalMode != ApprovalMode.FULL && confirm(message)

    fun appLabel(packageName: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

/** 一个工具分组：负责生成自己的 schema，并执行自己的工具。 */
interface ExtToolGroup {
    val group: ToolGroup

    fun specs(config: AgentConfig, out: JSONArray)

    fun handles(name: String): Boolean

    suspend fun execute(ctx: ToolCtx, name: String, args: JSONObject): String
}

/** 扩展工具注册表：内置工具（run_shell 等）之外的工具都在这里汇总。 */
object AgentExtTools {

    private val groups: List<ExtToolGroup> = listOf(
        TimeTools,
        SystemTools,
        IntentTools,
        StatusTools,
        LocationTools,
        ContentTools,
        FileTools,
        AdvancedTools,
    )

    fun appendSpecs(config: AgentConfig, out: JSONArray) {
        for (g in groups) {
            if (g.group.id in config.toolGroups) g.specs(config, out)
        }
    }

    /** 返回 null 表示不是扩展工具（交给内置工具的 when 继续处理）。 */
    suspend fun execute(ctx: ToolCtx, name: String, args: JSONObject): String? {
        for (g in groups) {
            // 分组被关掉时即使模型从历史里翻出旧工具名也不执行
            if (g.group.id !in ctx.config.toolGroups) continue
            if (g.handles(name)) return g.execute(ctx, name, args)
        }
        return null
    }
}

// ---------------------------------------------------------------- schema 构造

fun toolProp(type: String, desc: String): JSONObject = JSONObject().apply {
    put("type", type)
    put("description", desc)
}

fun appendTool(
    out: JSONArray,
    name: String,
    description: String,
    props: JSONObject = JSONObject(),
    required: List<String> = emptyList(),
) {
    out.put(JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", props)
                put("required", JSONArray().apply { required.forEach { put(it) } })
            })
        })
    })
}

fun toolProps(vararg pairs: Pair<String, JSONObject>): JSONObject = JSONObject().apply {
    pairs.forEach { (k, v) -> put(k, v) }
}

// ---------------------------------------------------------------- 通用小工具

/** shell 单引号转义：' -> '\'' */
fun shqExt(text: String): String = "'" + text.replace("'", "'\\''") + "'"

/** 拉起一个系统界面，把 ActivityNotFoundException 转成人话。 */
fun launchIntent(ctx: ToolCtx, intent: Intent, what: String): String = runCatching {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.context.startActivity(intent)
    "已$what"
}.getOrElse { e ->
    "无法$what：${e.message ?: e.javaClass.simpleName}（设备上可能没有处理该操作的应用）"
}

fun fmtBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.CHINA, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0)
    bytes >= 1L shl 10 -> String.format(Locale.CHINA, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}

fun fmtDuration(millis: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return buildString {
        if (h > 0) append(h).append(" 小时 ")
        if (h > 0 || m > 0) append(m).append(" 分 ")
        append(s).append(" 秒")
    }.trim()
}

fun fmtTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(millis))

fun nowText(): String {
    val now = ZonedDateTime.now()
    return "当前时间：${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (EEEE)", Locale.CHINA))}\n" +
        "时区：${now.zone.id}（${now.offset}）\n" +
        "时间戳：${System.currentTimeMillis()}"
}

/**
 * 无障碍截屏（Android 11+ 的 takeScreenshot，无需 ROOT/Shizuku）。
 * 返回和 shell 截图一样的 IMAGE_MARKER 字符串，失败返回 null。
 */
object AccessibilityCapture {

    suspend fun capture(): String? {
        val svc = AgentAccessibilityService.instance ?: return null
        val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            svc.takeScreenBitmap { bmp -> cont.resume(bmp) }
        } ?: return null
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        val w = bitmap.width
        val h = bitmap.height
        bitmap.recycle()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return AgentTools.IMAGE_MARKER + "data:image/jpeg;base64," + b64 +
            "\n截图分辨率 ${w}x${h}。tap/swipe 的坐标请直接使用这个分辨率，不要按其它尺寸换算。"
    }
}
