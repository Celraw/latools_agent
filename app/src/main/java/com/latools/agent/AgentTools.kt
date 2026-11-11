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
    fun toolsJson(includeSearch: Boolean, includeControl: Boolean): String = JSONArray().apply {
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
            "open_app", "按包名启动一个已安装的应用（拉起其主界面）。需要操控时请先 phone_control_start 取得授权再调用本工具。",
            JSONObject().put("package", p("string", "应用包名，例如 com.tencent.mm")), listOf("package"),
        )
        fn(
            "screenshot", "截取当前屏幕并作为图片交给模型查看。",
            JSONObject(), emptyList(),
        )
        fn(
            "read_file", "读取一个文本文件的内容（自动截断，超大文件只返回开头）。应用无权读取的路径会尝试用特权通道 cat。",
            JSONObject().put("path", p("string", "文件的绝对路径")), listOf("path"),
        )
        fn(
            "write_file", "把文本写入文件。只允许写入本应用目录（files / Android/data），其它路径请用 run_shell。",
            JSONObject()
                .put("path", p("string", "目标绝对路径，必须在应用目录内"))
                .put("content", p("string", "要写入的文本内容"))
                .put("append", p("boolean", "可选，true 表示追加而不是覆盖")),
            listOf("path", "content"),
        )
        fn(
            "list_packages", "列出已安装应用（包名）。默认只列第三方应用。",
            JSONObject()
                .put("filter", p("string", "可选，按包名子串过滤"))
                .put("include_system", p("boolean", "可选，是否包含系统应用，默认 false")),
            emptyList(),
        )
        fn(
            "app_control", "对指定应用执行操作：force-stop（强停）/ disable（禁用）/ enable（启用）/ clear（清除数据）/ suspend（挂起）/ unsuspend（恢复）。",
            JSONObject()
                .put("package", p("string", "应用包名"))
                .put("action", p("string", "force-stop / disable / enable / clear / suspend / unsuspend")),
            listOf("package", "action"),
        )
        if (includeControl) {
            fn(
                "phone_control_start",
                "请求用户授权后开始操控手机：屏幕四周会出现霓虹边框，Agent 会退到桌面。必须在 open_app 之前调用——否则目标应用已在前台，用户得切回来才能授权。",
                JSONObject(), emptyList(),
            )
            fn("phone_control_stop", "结束手机操控并关闭霓虹边框。", JSONObject(), emptyList())
            fn("screen_snapshot", "截取当前屏幕作为图片查看（需要已开始手机操控）。", JSONObject(), emptyList())
            fn(
                "tap",
                "点击屏幕。推荐传 0~1 的相对坐标（x=0.5 表示屏幕水平中央），避免图片被接口缩放导致的像素偏差；也可以传绝对像素。",
                JSONObject()
                    .put("x", p("number", "0~1 相对坐标（推荐）或绝对像素"))
                    .put("y", p("number", "0~1 相对坐标（推荐）或绝对像素")),
                listOf("x", "y"),
            )
            fn("get_foreground_app", "查询当前前台应用包名。点击前应先确认目标应用在前台。", JSONObject(), emptyList())
            fn(
                "swipe", "滑动。坐标规则同 tap：推荐 0~1 相对坐标，也可用绝对像素。",
                JSONObject()
                    .put("x1", p("number", "起点 x（0~1 或像素）")).put("y1", p("number", "起点 y"))
                    .put("x2", p("number", "终点 x（0~1 或像素）")).put("y2", p("number", "终点 y"))
                    .put("duration", p("number", "滑动时长毫秒，默认 300")),
                listOf("x1", "y1", "x2", "y2"),
            )
            fn(
                "press", "按系统导航键：back / home / recents。",
                JSONObject().put("key", p("string", "back / home / recents")), listOf("key"),
            )
        }
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
            "open_app" -> openApp(context, config, args.optString("package"))
            "screenshot" -> screenshot(context, config)
            "read_file" -> readFile(context, config, args.optString("path"))
            "write_file" -> writeFile(
                context,
                args.optString("path"),
                args.optString("content"),
                args.optBoolean("append", false),
            )
            "list_packages" -> listPackages(
                context,
                config,
                args.optString("filter"),
                args.optBoolean("include_system", false),
            )
            "phone_control_start" -> {
                if (!config.visionEnabled) {
                    "当前模型设置为不支持识图，手机操控工具已关闭（设置 → 手机操控）"
                } else if (!PhoneControl.canControl()) {
                    "无障碍服务未连接：请先在 设置 → 手机操控 里开启无障碍服务"
                } else if (PhoneControl.isActive) {
                    "已经在操控中"
                } else {
                    val allowed = confirmDanger(
                        "AI 请求操控这台手机：\n" +
                            "· 会读取屏幕截图并模拟点击/滑动\n" +
                            "· 屏幕四周会亮起霓虹边框表示正在操控\n" +
                            "· 连按两次音量上键可随时中止",
                    )
                    if (!allowed) "用户拒绝了 AI 操控手机"
                    else if (PhoneControl.start(context)) {
                        /*
                         * 开始操控后把 Agent 自己退到桌面：
                         * 否则 Agent 的界面盖在目标应用上面，AI 的点击会打进自己的界面。
                         */
                        AgentAccessibilityService.instance?.booleanGlobal(
                            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME,
                        )
                        val (w, h) = AgentAccessibilityService.instance?.screenSize() ?: (0 to 0)
                        "已开始操控。屏幕 ${w}x${h}。已把 Agent 退到桌面；" +
                            "请先 open_app 打开目标应用，用 get_foreground_app 确认前台后再 tap。" +
                            "tap/swipe 推荐用 0~1 相对坐标（图片被接口缩放时绝对像素会偏）。"
                    } else "启动操控失败"
                }
            }
            "get_foreground_app" -> {
                val pkg = AgentAccessibilityService.instance?.foregroundPackage()
                if (pkg.isNullOrBlank()) "读不到前台应用（无障碍未连接或当前无窗口）" else "前台应用：$pkg"
            }
            "phone_control_stop" -> {
                PhoneControl.stop(context)
                "已结束手机操控"
            }
            "screen_snapshot" -> {
                if (!PhoneControl.isActive) "尚未开始手机操控，请先调用 phone_control_start"
                else screenSnapshot()
            }
            "tap" -> {
                if (!PhoneControl.isActive) "尚未开始手机操控"
                else {
                    val svc = AgentAccessibilityService.instance
                    if (svc == null) "无障碍服务未连接"
                    else {
                        val (sw, sh) = svc.screenSize()
                        val (px, py) = toPx(args.optDouble("x", 0.0), args.optDouble("y", 0.0), sw, sh)
                        if (svc.tap(px, py)) "已点击 ($px, $py)［相对 ${args.optDouble("x")}, ${args.optDouble("y")}］"
                        else "点击失败"
                    }
                }
            }
            "swipe" -> {
                if (!PhoneControl.isActive) "尚未开始手机操控"
                else {
                    val svc = AgentAccessibilityService.instance
                    if (svc == null) "无障碍服务未连接"
                    else {
                        val (sw, sh) = svc.screenSize()
                        val (x1, y1) = toPx(args.optDouble("x1", 0.0), args.optDouble("y1", 0.0), sw, sh)
                        val (x2, y2) = toPx(args.optDouble("x2", 0.0), args.optDouble("y2", 0.0), sw, sh)
                        val ok = svc.swipe(x1, y1, x2, y2, args.optLong("duration", 300L))
                        if (ok) "已滑动 ($x1,$y1) → ($x2,$y2)" else "滑动失败"
                    }
                }
            }
            "press" -> {
                if (!PhoneControl.isActive) "尚未开始手机操控"
                else {
                    val svc = AgentAccessibilityService.instance
                    if (svc == null) "无障碍服务未连接"
                    else {
                        val action = when (args.optString("key").lowercase()) {
                            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                            else -> -1
                        }
                        if (action == -1) "未知按键：${args.optString("key")}"
                        else if (svc.booleanGlobal(action)) "已按下 ${args.optString("key")}" else "按键失败"
                    }
                }
            }
            "app_control" -> appControl(
                context,
                config,
                args.optString("package"),
                args.optString("action"),
                confirmDanger,
            )
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

    /** shell 单引号转义：' -> '\'' */
    private fun shq(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    /**
     * 坐标换算：<=1 视为相对坐标（按屏幕宽高折算），>1 视为绝对像素。
     *
     * 视觉模型拿到的图片常被接口缩放（1440x3200 的截图它可能只"看到" 900x2000），
     * 按图目测的绝对值天生带比例误差；用 0~1 相对坐标就没有这个问题。
     */
    private fun toPx(x: Double, y: Double, screenW: Int, screenH: Int): Pair<Float, Float> {
        val px = if (x <= 1.0) (x * screenW).toFloat() else x.toFloat()
        val py = if (y <= 1.0) (y * screenH).toFloat() else y.toFloat()
        return px.coerceIn(0f, screenW.toFloat()) to py.coerceIn(0f, screenH.toFloat())
    }

    private suspend fun readFile(context: Context, config: AgentConfig, path: String): String {
        if (path.isBlank()) return "参数错误：缺少 path"
        val direct = runCatching {
            val f = java.io.File(path)
            if (!f.isFile) return@runCatching null
            if (f.length() > 2 * 1024 * 1024) return@runCatching null
            f.readText()
        }.getOrNull()
        val text = direct ?: run {
            val r = AgentShell.run(context, "cat -- ${shq(path)} 2>/dev/null | head -c 200000", config.channels, 15_000)
            if (r.output.startsWith("（无输出）") || r.output.isBlank()) {
                return "读取失败：文件不存在或无权访问（$path）"
            }
            r.output.substringBeforeLast("\n[exit=")
        }
        return if (text.length > 20_000) text.take(20_000) + "\n…（已截断，原长 ${text.length} 字符）" else text
    }

    private fun writeFile(context: Context, path: String, content: String, append: Boolean): String {
        if (path.isBlank()) return "参数错误：缺少 path"
        val allowedRoots = listOfNotNull(
            context.filesDir?.absolutePath,
            context.cacheDir?.absolutePath,
            context.getExternalFilesDir(null)?.absolutePath,
        )
        if (allowedRoots.none { path.startsWith(it) }) {
            return "拒绝写入：只允许写应用目录（${allowedRoots.joinToString(" / ")}）。其它路径请用 run_shell 并自行确认风险。"
        }
        return runCatching {
            val f = java.io.File(path)
            f.parentFile?.mkdirs()
            if (append) f.appendText(content) else f.writeText(content)
            "已写入 ${content.length} 字符到 $path" + if (append) "（追加）" else "（覆盖）"
        }.getOrElse { "写入失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private suspend fun listPackages(
        context: Context,
        config: AgentConfig,
        filter: String,
        includeSystem: Boolean,
    ): String {
        val cmd = "pm list packages" + (if (includeSystem) "" else " -3")
        val r = AgentShell.run(context, cmd, config.channels, 20_000)
        val names = r.output.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
        var list = names
        if (filter.isNotBlank()) list = list.filter { it.contains(filter, ignoreCase = true) }
        if (list.isEmpty()) return "没有匹配的应用（原始输出：${r.output.take(200)}）"
        return "共 ${list.size} 个：\n" + list.sorted().take(300).joinToString("\n")
    }

    private suspend fun appControl(
        context: Context,
        config: AgentConfig,
        pkg: String,
        action: String,
        confirmDanger: suspend (String) -> Boolean,
    ): String {
        if (pkg.isBlank()) return "参数错误：缺少 package"
        val cmd = when (action) {
            "force-stop" -> "am force-stop ${shq(pkg)}"
            "disable" -> "pm disable-user --user 0 ${shq(pkg)}"
            "enable" -> "pm enable ${shq(pkg)}"
            "clear" -> "pm clear --user 0 ${shq(pkg)}"
            "suspend" -> "pm suspend --user 0 ${shq(pkg)}"
            "unsuspend" -> "pm unsuspend --user 0 ${shq(pkg)}"
            else -> return "未知动作：$action（可用：force-stop / disable / enable / clear / suspend / unsuspend）"
        }
        // 破坏性动作先确认
        if (action == "clear" || action == "disable") {
            if (!confirmDanger("app_control: $action $pkg\n$cmd")) return "用户拒绝了这个操作"
        }
        val r = AgentShell.run(context, cmd, config.channels, 20_000)
        return "$action $pkg\n${r.output}\n[通道=${r.channel}]"
    }

    /**
     * 启动后轮询前台，确认真的切过去了再返回。
     *
     * 为什么必须轮询：`am start` / startActivity 只是"请求启动"，系统动画 + 首帧
     * 往往要 300~1500ms；期间前台仍是桌面。如果这时就告诉模型"已启动"，
     * 模型会立刻 tap，点在桌面上。AI 实测踩过这个坑。
     */
    private fun awaitForegroundResult(pkg: String, timeoutMs: Long = 3_000): String {
        val svc = AgentAccessibilityService.instance
        if (svc == null) {
            Thread.sleep(600)
            return "已请求启动 $pkg（无障碍未连接，无法确认前台）"
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastForeground: String? = null
        while (System.currentTimeMillis() < deadline) {
            val fg = runCatching { svc.foregroundPackage() }.getOrNull()
            lastForeground = fg
            if (fg == pkg) return "已启动并确认前台：$pkg"
            Thread.sleep(60)
        }
        return "已请求启动 $pkg，但前台仍是 ${lastForeground ?: "未知"}（可能被启动页/权限弹窗拦截）"
    }

    private suspend fun openApp(context: Context, config: AgentConfig, pkg: String): String {
        if (pkg.isBlank()) return "参数错误：缺少 package"
        /*
         * 首选 PackageManager；拿不到 launch intent 时不要直接判定"没装" ——
         * Android 11+ 的包可见性会让已安装应用也返回 null。
         * 这里退回特权通道：先 resolve-activity 再 am start，两条命令都是 shell 标准做法。
         */
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
            if (ok) return awaitForegroundResult(pkg)
        }
        val resolved = AgentShell.run(
            context,
            "cmd package resolve-activity --brief ${shq(pkg)} | tail -n 1",
            config.channels, 10_000,
        ).output.substringBeforeLast("\n[exit=").trim()
        if (resolved.isBlank() || !resolved.contains('/')) {
            return "找不到可启动的应用：$pkg（未安装，或没有可启动的 Activity）"
        }
        val start = AgentShell.run(context, "am start -n ${shq(resolved)}", config.channels, 10_000)
        return if (start.ok) awaitForegroundResult(pkg) else "启动失败：${start.output.take(300)}"
    }

    private suspend fun screenSnapshot(): String {
        val svc = AgentAccessibilityService.instance ?: return "无障碍服务未连接"
        val bitmap = kotlinx.coroutines.suspendCancellableCoroutine<android.graphics.Bitmap?> { cont ->
            svc.takeScreenBitmap { bmp -> cont.resumeWith(Result.success(bmp)) }
        } ?: return "截图失败（系统拒绝或无显示）"
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
        val w = bitmap.width
        val h = bitmap.height
        bitmap.recycle()
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        /*
         * 第一行必须是纯 data URL（引擎按行拆分，第一行进图片消息）；
         * 分辨率说明放在第二行，由引擎拼进工具结果文本给模型看。
         * data URL 里混任何额外文字都会导致服务端 400 Invalid base64 data。
         */
        return IMAGE_MARKER + "data:image/jpeg;base64," + b64 +
            "\n截图分辨率 ${w}x${h}。tap/swipe 的坐标请直接使用这个分辨率，不要按其它尺寸换算。"
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
