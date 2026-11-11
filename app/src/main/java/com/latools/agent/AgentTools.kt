package com.latools.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 工具注册表。
 *
 * 每个工具 = 一份 JSON Schema + 一个执行函数，放在同一处。
 * 之前是 "schema 在 AgentApi、分发在 AgentEngine 的 when、提示词在 AgentStore" 三处散着写，
 * 加一个工具要改三个文件，漏一处就静默失效；现在加工具只需要在 [specs] 里加一条。
 *
 * 危险度分三档：
 *  · SAFE     直接执行
 *  · CONFIRM  执行前先问用户（run_shell 命中危险模式、http_request 的 POST/PUT/DELETE）
 *  · 由通道权限决定（ROOT / Shizuku / Dhizuku 由设置里的勾选控制）
 */
object AgentTools {

    /** 工具返回图片时的标记，引擎会把它转成一条带图的用户消息。 */
    const val IMAGE_MARKER = "__IMAGE__:"

    private val DANGEROUS = listOf(
        Regex("""\\brm\\s+-[a-z]*r[a-z]*f\\b""", RegexOption.IGNORE_CASE),
        Regex("""\\bdd\\b.*\\bof=/dev/block""", RegexOption.IGNORE_CASE),
        Regex("""\\bmkfs(\\.\\w+)?\\b""", RegexOption.IGNORE_CASE),
        Regex("""\\breboot\\s+edl\\b""", RegexOption.IGNORE_CASE),
        Regex("""\\bfastboot\\b""", RegexOption.IGNORE_CASE),
        Regex("""\\bpm\\s+(uninstall|disable|disable-user)\\b""", RegexOption.IGNORE_CASE),
        Regex("""\\b(wipe|format)\\b.*\\b(data|userdata|system)\\b""", RegexOption.IGNORE_CASE),
        Regex(""">\\s*/dev/block/""", RegexOption.IGNORE_CASE),
    )

    fun isDangerous(command: String): Boolean = DANGEROUS.any { it.containsMatchIn(command) }

    /** 生成给接口的 tools 数组。 */
    fun toolsJson(includeSearch: Boolean): String = JSONArray().apply {
        fun fn(name: String, description: String, props: JSONObject, required: List<String>) {
            put(JSONObject().apply {
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
        fun p(type: String, desc: String) = JSONObject().apply { put("type", type); put("description", desc) }

        fn(
            "run_shell", "在本机以特权身份执行一条 shell 命令，返回 stdout/stderr 与退出码。",
            JSONObject().put("command", p("string", "要执行的完整 shell 命令")), listOf("command"),
        )
        fn("get_device_info", "读取设备基础信息（型号、系统版本、SoC、可用通道）。", JSONObject(), emptyList())
        fn(
            "fetch_url", "直接访问一个 http/https 网址并返回正文（HTML 会转成纯文本）。",
            JSONObject().put("url", p("string", "完整 URL，必须以 http:// 或 https:// 开头")), listOf("url"),
        )
        fn(
            "http_request", "发起任意 HTTP 请求（支持 GET/POST/PUT/DELETE），可自定义请求头和请求体。",
            JSONObject()
                .put("method", p("string", "GET / POST / PUT / DELETE，默认 GET"))
                .put("url", p("string", "完整 URL"))
                .put("headers", p("object", "可选，键值对形式的请求头"))
                .put("body", p("string", "可选，请求体（JSON 或文本）")),
            listOf("url"),
        )
        fn("clipboard_get", "读取系统剪贴板当前文本（需要应用在前台）。", JSONObject(), emptyList())
        fn(
            "clipboard_set", "把一段文本写入系统剪贴板。",
            JSONObject().put("text", p("string", "要复制的文本")), listOf("text"),
        )
        fn(
            "open_app", "按包名启动一个已安装的应用（拉起其主界面）。",
            JSONObject().put("package", p("string", "应用包名，例如 com.tencent.mm")), listOf("package"),
        )
        fn(
            "screenshot", "截取当前屏幕并作为图片交给模型查看。",
            JSONObject(), emptyList(),
        )
        if (includeSearch) {
            fn(
                "web_search", "联网搜索最新信息（Tavily）。",
                JSONObject().put("query", p("string", "搜索关键词，尽量具体")), listOf("query"),
            )
        }
    }.toString()

    /**
     * 执行一次工具调用。
     *
     * @param deviceInfo 由引擎提供的设备信息读取函数（避免这里再依赖 Build 之外的上下文）
     * @param confirmDanger 危险操作确认，返回 true 才执行
     */
    suspend fun execute(
        context: Context,
        config: AgentConfig,
        call: ToolCall,
        deviceInfo: () -> String,
        confirmDanger: suspend (String) -> Boolean,
    ): String {
        val args = runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }
        return when (call.name) {
            "run_shell" -> {
                val command = args.optString("command")
                when {
                    command.isBlank() -> "参数错误：缺少 command"
                    isDangerous(command) && !confirmDanger(command) -> "用户拒绝了这条命令的执行"
                    else -> {
                        val result = AgentShell.run(context, command, config.channels)
                        "${result.output}\n[通道=${result.channel}]"
                    }
                }
            }
            "get_device_info" -> deviceInfo()
            "fetch_url" -> {
                val url = args.optString("url")
                if (url.isBlank()) "参数错误：缺少 url" else AgentApi.fetchUrl(url)
            }
            "web_search" -> {
                val query = args.optString("query")
                if (query.isBlank()) "参数错误：缺少 query" else AgentApi.tavilySearch(config, query)
            }
            "http_request" -> httpRequest(args, confirmDanger)
            "clipboard_get" -> {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    ?.takeIf { it.isNotBlank() } ?: "剪贴板为空（或应用不在前台读不到）"
            }
            "clipboard_set" -> {
                val text = args.optString("text")
                if (text.isBlank()) "参数错误：缺少 text"
                else {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("Agent", text))
                    "已写入剪贴板（${text.length} 字符）"
                }
            }
            "open_app" -> openApp(context, args.optString("package"))
            "screenshot" -> screenshot(context, config)
            else -> "未知工具：${call.name}"
        }
    }

    // ------------------------------------------------------------ 具体实现

    private suspend fun httpRequest(args: JSONObject, confirmDanger: suspend (String) -> Boolean): String {
        val urlText = args.optString("url").trim()
        if (!urlText.startsWith("http://") && !urlText.startsWith("https://")) return "只支持 http/https 地址"
        val method = args.optString("method").ifBlank { "GET" }.uppercase()
        val body = args.optString("body")
        if (method != "GET" && method != "HEAD" && !confirmDanger("$method $urlText")) {
            return "用户拒绝了这个请求"
        }
        val conn = (URL(urlText).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LAToolsAgent/1.0")
            args.optJSONObject("headers")?.let { h ->
                h.keys().forEach { key -> setRequestProperty(key, h.optString(key)) }
            }
        }
        return try {
            if (body.isNotBlank() && method != "GET") {
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
            "HTTP $code ${conn.contentType ?: ""}\n\n" + raw.take(8_000)
        } catch (e: Exception) {
            "请求失败：${e.message ?: e.javaClass.simpleName}"
        } finally {
            conn.disconnect()
        }
    }

    private fun openApp(context: Context, pkg: String): String {
        if (pkg.isBlank()) return "参数错误：缺少 package"
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return "找不到可启动的应用：$pkg（可能未安装或没有启动界面）"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); "已启动 $pkg" }
            .getOrElse { "启动失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private suspend fun screenshot(context: Context, config: AgentConfig): String {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, "agent_shot.png")
        if (file.exists()) file.delete()
        // 用特权通道截屏：shell 身份才能访问 SurfaceFlinger
        val result = AgentShell.run(context, "screencap -p '${file.absolutePath}'", config.channels, 15_000)
        if (!file.exists() || file.length() <= 0) {
            return "截图失败：${result.output.take(300)}（需要可用的 ROOT / Shizuku 通道）"
        }
        val bytes = runCatching { file.readBytes() }.getOrDefault(ByteArray(0))
        file.delete()
        if (bytes.isEmpty()) return "截图读取失败"
        if (bytes.size > 4 * 1024 * 1024) return "截图过大（${bytes.size / 1024}KB），已放弃"
        return IMAGE_MARKER + "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
