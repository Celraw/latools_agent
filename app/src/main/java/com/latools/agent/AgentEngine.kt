package com.latools.agent

import android.content.Context
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit


/** org.json 的 optString 对 JSONObject.NULL 会返回字面量 "null"，必须显式过滤。 */
private fun JSONObject.text(key: String): String {
    if (!has(key) || isNull(key)) return ""
    return optString(key, "").takeIf { it != "null" } ?: ""
}

/**
 * 上一次流式请求的诊断信息。
 *
 * 没有 adb 的时候，用户没法看 logcat；把 HTTP 状态、Content-Type、收到的 delta 数
 * 直接展示在界面上，"为什么没流式"就能一眼定位（接口不支持 SSE / 被 gzip / 401 等）。
 */
object SseDiagnostics {
    @Volatile var lastHttpStatus: Int = 0
    @Volatile var lastContentType: String = "-"
    @Volatile var lastDeltaCount: Int = 0
    @Volatile var lastError: String = ""
    @Volatile var fallbackUsed: Boolean = false
}

/** 一条命令的执行结果。 */
data class ShellResult(val ok: Boolean, val output: String, val channel: String)

/**
 * 通道执行器。
 *
 * 用户可以在设置里多选允许使用的通道，这里按 ROOT → Shizuku → Dhizuku 顺序尝试，
 * 第一个能用的通道执行成功就返回；全部失败时把每条通道的失败原因拼起来 ——
 * "执行失败"四个字对排查没有任何帮助。
 */
object AgentShell {

    fun available(context: Context): List<AgentChannel> = buildList {
        if (rootAvailable()) add(AgentChannel.ROOT)
        if (shizukuAvailable()) add(AgentChannel.SHIZUKU)
        if (runCatching {
                com.rosan.dhizuku.api.Dhizuku.init(context) && com.rosan.dhizuku.api.Dhizuku.isPermissionGranted()
            }.getOrDefault(false)
        ) add(AgentChannel.DHIZUKU)
    }

    fun run(context: Context, command: String, channels: Set<String>, timeoutMs: Long = 20_000): ShellResult {
        if (channels.isEmpty()) return ShellResult(false, "没有勾选任何特权通道", "-")
        val errors = mutableListOf<String>()
        for (channel in AgentChannel.entries) {
            if (channel.id !in channels) continue
            val result = runCatching { runOn(context, channel, command, timeoutMs) }
                .getOrElse { ShellResult(false, "通道异常：${it.message ?: it.javaClass.simpleName}", channel.label) }
            if (result.ok) return result
            errors += "${channel.label}: ${result.output.take(220)}"
        }
        return ShellResult(false, errors.joinToString("\n"), "-")
    }

    private fun runOn(context: Context, channel: AgentChannel, command: String, timeoutMs: Long): ShellResult {
        /*
         * 命令用哨兵包裹：`(cmd) 2>&1; echo __LA_RC:$?`
         *
         * 为什么不再用 waitFor / exitValue：
         * Shizuku / Dhizuku 的 RemoteProcess 是代理进程，`exitValue()` 在进程未结束时会抛
         * IllegalThreadStateException("process hasn't exited")，而它们的 waitFor(timeout)
         * 实现也不能保证等到真实退出 —— 真机上表现出来就是每条命令都失败。
         *
         * 现在只依赖 stdout 的 EOF：命令跑完、shell 退出，读流自然结束；
         * 退出码由 shell 自己回显，再去掉最后那行解析出来。完全不碰 exitValue。
         */
        val wrapped = "($command) 2>&1; echo __LA_RC:${'$'}?"
        val process = when (channel) {
            AgentChannel.ROOT -> {
                require(rootAvailable()) { "未获得 ROOT 授权" }
                Runtime.getRuntime().exec(arrayOf("su", "-c", wrapped))
            }
            AgentChannel.SHIZUKU -> {
                require(shizukuAvailable()) { "Shizuku 未授权" }
                shizukuProcess(wrapped)
            }
            AgentChannel.DHIZUKU -> {
                require(
                    com.rosan.dhizuku.api.Dhizuku.init(context) &&
                        com.rosan.dhizuku.api.Dhizuku.isPermissionGranted(),
                ) { "Dhizuku 未授权" }
                com.rosan.dhizuku.api.Dhizuku.newProcess(arrayOf("sh", "-c", wrapped), null, null)
            }
        }

        val buf = StringBuilder()
        val reader = kotlin.concurrent.thread(name = "agent-shell-reader") {
            runCatching { buf.append(process.inputStream.bufferedReader().readText()) }
        }
        reader.join(timeoutMs)
        if (reader.isAlive) {
            runCatching { process.destroy() }
            reader.join(300)
            return ShellResult(false, "执行超时（${timeoutMs / 1000}s），已终止", channel.label)
        }

        val raw = buf.toString()
        val rcMatch = Regex("__LA_RC:(\\d+)").findAll(raw).lastOrNull()
        val code = rcMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val output = raw.replace(Regex("\\s*__LA_RC:\\d+\\s*$"), "").trim()
        val text = buildString {
            if (output.isNotEmpty()) append(output)
            if (isEmpty()) append("（无输出）")
            append("\n[exit=").append(code).append(']')
        }
        return ShellResult(code == 0, text.take(8_000), channel.label)
    }

    /** Shizuku 13.x 起 newProcess 是私有静态方法，沿用 CommandRunner 的反射方案。 */
    private fun shizukuProcess(command: String): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
    }

    private fun rootAvailable(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        output.contains("uid=0")
    }.getOrDefault(false)

    private fun shizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

}

// ------------------------------------------------------------------ API

data class AssistantReply(val content: String?, val reasoning: String?, val calls: List<ToolCall>)

/**
 * OpenAI 兼容接口。只实现 chat/completions 一种调用，非流式。
 *
 * 为什么不做流式：工具调用轮次多、每轮还要回传结果，SSE 解析会让状态机复杂一倍；
 * 而 Agent 场景里用户看的是"命令执行结果"，不是逐字打字效果。
 */
object AgentApi {

    private fun toolsJson(includeSearch: Boolean, includeControl: Boolean): String =
        AgentTools.toolsJson(includeSearch, includeControl)

    /**
     * 直接抓取一个 URL 的正文。
     *
     * - 只允许 http/https（file://、content:// 一律拒绝，避免读本机私有数据）
     * - 限制响应大小与输出长度，防止一个超大页面把上下文撑爆
     * - HTML 粗转纯文本：去 script/style、去标签、压空白；JSON/纯文本原样返回
     */
    fun fetchUrl(url: String): String {
        val normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            return "只支持 http/https 地址"
        }
        val conn = (URL(normalized).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,application/json,text/plain,*/*")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LAToolsAgent/1.0")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n <= 0) break
                    total += n
                    if (total > 512 * 1024) break
                    buf.write(chunk, 0, n)
                }
                buf.toString("UTF-8")
            }.orEmpty()
            if (code !in 200..299) {
                "抓取失败（HTTP $code）：${raw.take(200)}"
            } else {
                val contentType = conn.contentType.orEmpty()
                val text = if (contentType.contains("html", ignoreCase = true)) htmlToText(raw) else raw
                "URL: $normalized\nHTTP: $code\n\n" + text.take(8_000)
            }
        } catch (e: Exception) {
            "抓取失败：${e.message ?: e.javaClass.simpleName}"
        } finally {
            conn.disconnect()
        }
    }

    private fun htmlToText(html: String): String = html
        .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</(p|div|li|h[1-6]|tr)>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** Tavily 搜索：返回给模型的纯文本结果（标题 + 链接 + 摘要）。 */
    fun tavilySearch(config: AgentConfig, query: String): String {
        if (config.tavilyKey.isBlank()) return "未配置 Tavily Key，无法联网搜索"
        val body = JSONObject().apply {
            put("api_key", config.tavilyKey)
            put("query", query)
            put("search_depth", "basic")
            put("max_results", 5)
            put("include_answer", false)
        }
        val conn = (URL("https://api.tavily.com/search").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) return "搜索失败（HTTP $code）：${text.take(200)}"
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return "搜索返回格式异常"
        val results = root.optJSONArray("results") ?: return "没有搜索结果"
        val sb = StringBuilder()
        for (i in 0 until minOf(results.length(), 5)) {
            val item = results.optJSONObject(i) ?: continue
            sb.append(i + 1).append(". ").append(item.optString("title", "").replace("null", "")).append('\n')
            sb.append(item.optString("url", "")).append('\n')
            sb.append(item.optString("content", "").take(600)).append("\n\n")
        }
        return sb.toString().trim().ifBlank { "没有搜索结果" }
    }

    /** 400 时用于自愈的请求变体。 */
    private enum class Repair { FULL, NO_REASONING, NO_TEMPERATURE, TEXT_ONLY, HALF_HISTORY }

    /**
     * 构造请求体。
     *
     * [repair] 用于 400 后逐级降级：有些"OpenAI 兼容"服务不认识 reasoning_effort /
     * 不接受图片 / 或干脆上下文超限，逐级去掉比直接失败好。
     */
    private fun buildBody(
        config: AgentConfig,
        messages: List<ChatMsg>,
        stream: Boolean,
        repair: Repair = Repair.FULL,
    ): JSONObject {
        var history = messages
        if (repair == Repair.HALF_HISTORY && history.size > 6) {
            // 保留 system（如果有）+ 最近一半
            val keep = history.take(1).filter { it.role == "system" } + history.takeLast(history.size / 2)
            history = keep
        }
        if (repair == Repair.TEXT_ONLY) {
            history = history.map { m ->
                if (m.images.isEmpty()) m else m.copy(content = (m.content ?: "") + "（图片已省略）", images = emptyList())
            }
        }
        return JSONObject().apply {
            put("model", config.model)
            put("messages", buildMessages(history))
            put("tools", JSONArray(toolsJson(config.tavilyKey.isNotBlank(), config.visionEnabled)))
            put("tool_choice", "auto")
            put("stream", stream)
            if (repair != Repair.NO_TEMPERATURE) put("temperature", config.temperature)
            if (repair != Repair.NO_REASONING &&
                config.reasoningEffort != "none" && config.reasoningEffort.isNotBlank()
            ) {
                put("reasoning_effort", config.reasoningEffort)
            }
        }
    }

    /** 400 的修复顺序：从"最小改动"到"最狠的裁剪"。 */
    private fun nextRepair(current: Repair): Repair? = when (current) {
        Repair.FULL -> Repair.NO_REASONING
        Repair.NO_REASONING -> Repair.NO_TEMPERATURE
        Repair.NO_TEMPERATURE -> Repair.TEXT_ONLY
        Repair.TEXT_ONLY -> Repair.HALF_HISTORY
        Repair.HALF_HISTORY -> null
    }

    /** 流式 tool_calls 的聚合容器（按 index 累积 id/name/arguments）。 */
    private class Acc {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    fun chat(config: AgentConfig, messages: List<ChatMsg>): AssistantReply {
        var repair = Repair.FULL
        var lastError = ""
        while (true) {
            val body = buildBody(config, messages, stream = false, repair = repair)
            val result = runCatching { post(config, body) }
            result.getOrNull()?.let {
                if (!it.startsWith("HTTP 4")) return parse(it)
                lastError = it
            }
            result.exceptionOrNull()?.let { lastError = it.message ?: it.javaClass.simpleName }
            val next = nextRepair(repair)
                ?: return AssistantReply(content = "请求失败：$lastError", reasoning = null, calls = emptyList())
            repair = next
        }
    }

    /**
     * 流式对话（SSE）。
     *
     * 400 时同样逐级降级重试（不重试 401/403，那些重试也没用）。
     */
    suspend fun chatStream(
        config: AgentConfig,
        messages: List<ChatMsg>,
        onDelta: suspend (content: String, reasoning: String) -> Unit,
    ): AssistantReply {
        var repair = Repair.FULL
        var lastError = ""
        while (true) {
            val body = buildBody(config, messages, stream = true, repair = repair)
            val conn = (URL(config.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 180_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Accept-Encoding", "identity")
                if (config.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            SseDiagnostics.lastHttpStatus = code
            SseDiagnostics.lastContentType = conn.contentType ?: "-"
            SseDiagnostics.lastDeltaCount = 0
            SseDiagnostics.lastError = ""
            if (code !in 200..299) {
                val text = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                lastError = "HTTP $code ${text.take(300)}"
                SseDiagnostics.lastError = lastError
                val next = if (code == 400 || code == 422) nextRepair(repair) else null
                if (next == null) throw IllegalStateException(lastError)
                repair = next
                continue
            }

            val content = StringBuilder()
            val reasoning = StringBuilder()
            val calls = sortedMapOf<Int, Acc>()
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") {
                        if (payload == "[DONE]") break else continue
                    }
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    delta.text("content").takeIf { it.isNotEmpty() }?.let { content.append(it) }
                    delta.text("reasoning_content").ifEmpty { delta.text("reasoning") }
                        .takeIf { it.isNotEmpty() }?.let { reasoning.append(it) }
                    delta.optJSONArray("tool_calls")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val tc = arr.optJSONObject(i) ?: continue
                            val idx = tc.optInt("index", 0)
                            val acc = calls.getOrPut(idx) { Acc() }
                            tc.text("id").takeIf { it.isNotEmpty() }?.let { acc.id = it }
                            val fn = tc.optJSONObject("function")
                            fn?.text("name")?.takeIf { it.isNotEmpty() }?.let { acc.name = it }
                            fn?.text("arguments")?.takeIf { it.isNotEmpty() }?.let { acc.args.append(it) }
                        }
                    }
                    SseDiagnostics.lastDeltaCount += 1
                    onDelta(content.toString(), reasoning.toString())
                }
            }
            conn.disconnect()
            return AssistantReply(
                content = content.toString().ifBlank { null },
                reasoning = reasoning.toString().ifBlank { null },
                calls = calls.toSortedMap().values.map {
                    ToolCall(it.id.ifBlank { "call_${System.nanoTime()}" }, it.name, it.args.toString().ifBlank { "{}" })
                },
            )
        }
    }

    /** 单条命令的安全性审查结果。 */
    data class ReviewResult(val dangerous: Boolean, val reason: String)

    /**
     * 一次性让模型审查一条命令是否危险。
     *
     * 注意：这是一次**独立的、不进入会话历史**的请求 —— 不会在聊天里多出
     * 一条消息，也不会污染上下文；审查完就丢弃。
     *
     * 失败（网络/解析）时退回本地正则判断，绝不因为审查器挂了就把危险命令放行。
     */
    fun reviewCommand(config: AgentConfig, command: String): ReviewResult {
        val system = ChatMsg(
            "system",
            "你是 Android shell 命令安全审查器。判断命令被自动执行是否可能造成不可逆损害：" +
                "删除/覆盖重要数据、格式化分区、刷机、卸载或禁用系统应用、提权后门、外发隐私数据等。" +
                "只输出 JSON，不要任何多余文字：{\"dangerous\":true/false,\"reason\":\"一句话中文理由\"}",
        )
        val user = ChatMsg("user", "命令：\n" + command.take(2_000))
        return runCatching {
            val body = JSONObject().apply {
                put("model", config.model)
                put("messages", buildMessages(listOf(system, user)))
                put("temperature", 0)
                put("stream", false)
                put("max_tokens", 200)
            }
            val text = post(config, body)
            val content = parse(text).content.orEmpty()
            val json = JSONObject(content.substringAfter('{').let { "{" + it.substringBeforeLast('}') + "}" })
            val dangerous = json.optBoolean("dangerous", true)
            val reason = json.optString("reason").ifBlank { if (dangerous) "审查器认为有风险" else "审查通过" }
            ReviewResult(dangerous, reason)
        }.getOrElse {
            // 审查失败：保守处理，交给本地危险模式判断
            val risky = AgentTools.isDangerous(command)
            ReviewResult(risky, if (risky) "本地规则命中危险模式（审查器不可用）" else "审查器不可用，本地规则未命中")
        }
    }

    fun summarize(config: AgentConfig, messages: List<ChatMsg>): String {
        val prompt = ChatMsg(
            role = "system",
            content = "把下面的对话压缩成一份简洁摘要，保留：用户目标、已确认的结论、执行过的关键命令及其结果、未完成事项。" +
                "不要编造，不要新增建议，直接输出摘要正文。",
        )
        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", buildMessages(listOf(prompt) + messages.map { ChatMsg("user", it.content ?: "") }))
            put("temperature", 0.2)
            put("stream", false)
        }
        return parse(post(config, body)).content?.takeIf { it.isNotBlank() } ?: "（摘要生成失败）"
    }

    /**
     * 历史消毒：保证 tool 消息一定紧跟带 tool_calls 的 assistant，且每条 tool_call 都有对应响应。
     *
     * 孤立 tool 的来源不止取消：分支会话、压缩、手动删改都可能留下。
     * 与其在每个入口修，不如发请求前统一过一遍 —— 任何脏历史都不会再触发
     * 400: Messages with role 'tool' must be a response to a preceding message with 'tool_calls'。
     */
    private fun sanitize(messages: List<ChatMsg>): List<ChatMsg> {
        val out = mutableListOf<ChatMsg>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i]
            if (m.role == "tool") { i++; continue }
            val calls = m.toolCalls
            if (m.role == "assistant" && !calls.isNullOrEmpty()) {
                val ids = calls.map { it.id }.toSet()
                val answers = mutableListOf<ChatMsg>()
                var j = i + 1
                while (j < messages.size && messages[j].role == "tool") {
                    val t = messages[j]
                    if (t.toolCallId in ids) answers += t
                    j++
                }
                val answeredIds = answers.mapNotNull { it.toolCallId }.toSet()
                val keptCalls = calls.filter { it.id in answeredIds }
                if (keptCalls.isEmpty()) {
                    if (!m.content.isNullOrBlank()) out += m.copy(toolCalls = null)
                } else {
                    out += m.copy(toolCalls = keptCalls)
                    out += answers
                }
                i = j
            } else {
                out += m
                i++
            }
        }
        /*
         * 再兜一层：把"没有任何内容也没有 tool_calls"的消息剔掉。
         * 有些兼容接口对空 assistant/空 user 直接返回 400（content 必须非空）。
         */
        val seenCallIds = mutableSetOf<String>()
        val cleaned = mutableListOf<ChatMsg>()
        for (m in out) {
            when (m.role) {
                "assistant" -> {
                    val calls = m.toolCalls.orEmpty().map { tc ->
                        // 工具 id 重复（模型偶尔复用）会导致部分服务端 400，这里加后缀去重
                        if (seenCallIds.add(tc.id)) tc else tc.copy(id = "${tc.id}_${seenCallIds.size}")
                    }
                    if (m.content.isNullOrBlank() && calls.isEmpty()) continue
                    cleaned += m.copy(toolCalls = calls.ifEmpty { null })
                }
                "tool" -> if (m.content.isNullOrBlank()) continue else cleaned += m
                "system" -> if (m.content.isNullOrBlank()) continue else cleaned += m
                else -> if (m.content.isNullOrBlank() && m.images.isEmpty()) continue else cleaned += m
            }
        }
        return cleaned
    }

    private fun buildMessages(rawMessages: List<ChatMsg>): JSONArray = JSONArray().apply {
        val messages = sanitize(rawMessages)
        messages.forEach { m ->
            put(JSONObject().apply {
                put("role", m.role)
                when {
                    m.images.isNotEmpty() && m.role == "user" -> put("content", JSONArray().apply {
                        m.content?.let { put(JSONObject().apply { put("type", "text"); put("text", it) }) }
                        m.images.forEach { url ->
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put("image_url", JSONObject().put("url", url))
                            })
                        }
                    })
                    m.content != null -> put("content", m.content)
                }
                m.toolCalls?.let { calls ->
                    put("tool_calls", JSONArray().apply {
                        calls.forEach { tc ->
                            put(JSONObject().apply {
                                put("id", tc.id); put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tc.name); put("arguments", tc.arguments)
                                })
                            })
                        }
                    })
                }
                m.toolCallId?.let { put("tool_call_id", it) }
            })
        }
    }

    private fun post(config: AgentConfig, body: JSONObject): String {
        val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (config.apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
            throw IllegalStateException("接口错误（HTTP $code）：${msg?.takeIf { it.isNotBlank() } ?: text.take(300)}")
        }
        return text
    }

    private fun parse(text: String): AssistantReply {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw IllegalStateException("接口返回不是 JSON：${text.take(200)}")
        val message = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("接口缺少 choices[0].message")
        val calls = message.optJSONArray("tool_calls")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val f = c.optJSONObject("function") ?: return@mapNotNull null
                ToolCall(c.optString("id"), f.optString("name"), f.optString("arguments"))
            }
        }.orEmpty()
        return AssistantReply(
            content = message.text("content").ifBlank { null },
            reasoning = message.text("reasoning_content").ifBlank { null }
                ?: message.text("reasoning").ifBlank { null },
            calls = calls,
        )
    }
}

// ------------------------------------------------------------------ 引擎

class AgentEngine(
    private val context: Context,
    private val config: AgentConfig,
    /** 危险命令确认：返回 true 才执行。 */
    private val confirmDanger: suspend (command: String) -> Boolean,
    /** 会话内容有更新时回调（UI 用它刷新列表）。 */
    private val onUpdate: suspend () -> Unit,
) {

    suspend fun send(
        session: AgentSession,
        userText: String,
        images: List<String>,
        /** true 表示 UI 已经同步插入过这条用户消息，避免重复添加。 */
        userMessageAlreadyAdded: Boolean = false,
    ) {
        // 注意：本方法整体跑在 Main 调度器上，所有阻塞 IO 必须显式切到 Dispatchers.IO。
        if (!userMessageAlreadyAdded) {
            session.messages.add(ChatMsg("user", userText, images = images))
        }
        session.updatedAt = System.currentTimeMillis()
        if (session.title == "新会话" && userText.isNotBlank()) {
            session.title = userText.trim().replace("\n", " ").take(24)
        }
        onUpdate()

        repeat(config.maxToolRounds) {
            compressIfNeeded(session)
            val messages = listOf(ChatMsg("system", config.systemPrompt)) + session.messages
            /*
             * 流式：先占一条空的 assistant 消息，delta 到达时原地替换最后一条，
             * UI 靠 animateContentSize 平滑长高；非流式则等整段返回后一次写入。
             */
            val placeholderIndex = session.messages.size
            session.messages.add(ChatMsg("assistant", ""))
            val reply = try {
                if (config.stream) {
                    withContext(Dispatchers.IO) {
                        AgentApi.chatStream(config, messages) { contentSoFar, reasoningSoFar ->
                            withContext(Dispatchers.Main) {
                                session.messages[placeholderIndex] = ChatMsg(
                                    role = "assistant",
                                    content = contentSoFar.ifBlank { null },
                                    reasoning = reasoningSoFar.ifBlank { null },
                                )
                                onUpdate()
                            }
                        }
                    }
                } else {
                    withContext(Dispatchers.IO) { AgentApi.chat(config, messages) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户主动停止：把占位气泡去掉，不要留下半截内容
                session.messages.removeAt(placeholderIndex)
                /*
                 * 占位气泡可能已经被替换成带 toolCalls 的 assistant 消息，
                 * 若此时取消，后面挂着的 tool 消息就成了"孤立的 tool"。
                 * 下一轮请求会报 400: Messages with role 'tool' must be a response
                 * to a preceding message with 'tool_calls'。这里一并清掉。
                 */
                while (session.messages.isNotEmpty() && session.messages.last().role == "tool") {
                    session.messages.removeAt(session.messages.size - 1)
                }
                session.updatedAt = System.currentTimeMillis(); onUpdate()
                throw e
            } catch (e: Exception) {
                session.messages[placeholderIndex] = ChatMsg("assistant", "请求失败：${e.message ?: e.javaClass.simpleName}")
                session.updatedAt = System.currentTimeMillis(); onUpdate()
                return
            }
            /*
             * 兼容性兜底：有些"OpenAI 兼容"服务不实现 SSE，返回的是一整段 JSON。
             * 这种情况 chatStream 解析不到任何 data: 行，content/calls 都是空的，
             * 此时自动改用非流式再请求一次，而不是给用户一个空气泡。
             */
            val finalReply = if (config.stream && reply.content.isNullOrBlank() && reply.calls.isEmpty()) {
                SseDiagnostics.fallbackUsed = true
                runCatching { withContext(Dispatchers.IO) { AgentApi.chat(config, messages) } }.getOrElse { reply }
            } else {
                if (config.stream) SseDiagnostics.fallbackUsed = SseDiagnostics.lastDeltaCount == 0
                reply
            }
            session.messages[placeholderIndex] = ChatMsg(
                role = "assistant",
                content = finalReply.content,
                toolCalls = finalReply.calls.takeIf { it.isNotEmpty() },
                reasoning = finalReply.reasoning,
            )
            session.updatedAt = System.currentTimeMillis(); onUpdate()

            if (finalReply.calls.isEmpty()) return

            val pendingImages = mutableListOf<String>()
            for (call in finalReply.calls) {
                var output = withContext(Dispatchers.IO) { AgentTools.execute(context, config, call, ::deviceInfo, confirmDanger) }
                // 图片类工具（screenshot）以标记返回，转成一条带图的用户消息，模型才"看得见"
                if (output.startsWith(AgentTools.IMAGE_MARKER)) {
                    // 第一行是 data URL，其余行是给模型的说明（如截图分辨率）
                    val payload = output.removePrefix(AgentTools.IMAGE_MARKER)
                    val firstLine = payload.lineSequence().first()
                    val note = payload.lineSequence().drop(1).joinToString("\n").trim()
                    pendingImages += firstLine
                    output = if (note.isBlank()) "已截图，图片见下一条消息。" else "已截图。$note"
                }
                session.messages.add(ChatMsg("tool", output, toolCallId = call.id))
                session.updatedAt = System.currentTimeMillis(); onUpdate()
            }
            if (pendingImages.isNotEmpty()) {
                session.messages.add(ChatMsg("user", "工具截图：", images = pendingImages))
                session.updatedAt = System.currentTimeMillis(); onUpdate()
            }
        }
        session.messages.add(ChatMsg("assistant", "已达到单轮工具调用上限（${config.maxToolRounds} 次），先停在这里。需要继续就再发一条消息。"))
        session.updatedAt = System.currentTimeMillis(); onUpdate()
    }


    private fun deviceInfo(): String = buildString {
        appendLine("型号: ${android.os.Build.MODEL}")
        appendLine("品牌: ${android.os.Build.BRAND}")
        appendLine("Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
        appendLine("SoC: ${android.os.Build.SOC_MODEL}")
        appendLine("ABI: ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "-"}")
        append("可用通道: ").append(AgentShell.available(context).joinToString { it.label }.ifBlank { "无" })
    }

    private suspend fun compressIfNeeded(session: AgentSession) {
        val size = session.messages.sumOf { (it.content?.length ?: 0) + (it.reasoning?.length ?: 0) }
        if (size <= config.contextLimitChars || session.messages.size <= 6) return
        val cut = session.messages.size - 6
        val older = session.messages.take(cut)
        if (older.isEmpty()) return
        val summary = runCatching { withContext(Dispatchers.IO) { AgentApi.summarize(config, older) } }.getOrElse { "（旧消息已省略：${older.size} 条）" }
        session.messages.subList(0, cut).clear()
        session.messages.add(0, ChatMsg("system", "【早前对话摘要】\n$summary"))
        session.updatedAt = System.currentTimeMillis()
        onUpdate()
    }
}
