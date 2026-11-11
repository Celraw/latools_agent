package com.latools.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Agent 模块的数据层：消息、会话、配置、持久化。
 *
 * 设计约束（都是踩过的坑）：
 *  · 消息要能**原样回放**：assistant 的 tool_calls、tool 的 tool_call_id 都必须完整序列化，
 *    否则恢复历史后模型会拒绝继续（"tool 消息没有对应的 call"）。
 *  · 会话一个文件：删除/搜索都只碰一个文件，不需要数据库事务。
 *  · API Key 与提示词存在 SharedPreferences，会话内容存文件 —— 两者生命周期不同。
 */
data class ChatMsg(
    val role: String,
    val content: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    /** 模型返回的思考链（DeepSeek 的 reasoning_content、部分兼容实现的 reasoning）。 */
    val reasoning: String? = null,
    /** 用户消息附带的图片（data URL，仅在支持视觉的模型上真正发送）。 */
    val images: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role)
        put("content", content ?: "")
        toolCalls?.let { calls ->
            put("tool_calls", JSONArray().apply {
                calls.forEach { tc ->
                    put(JSONObject().apply {
                        put("id", tc.id)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tc.name); put("arguments", tc.arguments)
                        })
                    })
                }
            })
        }
        toolCallId?.let { put("tool_call_id", it) }
        reasoning?.let { put("reasoning", it) }
        if (images.isNotEmpty()) put("images", JSONArray(images))
    }

    companion object {
        fun fromJson(o: JSONObject): ChatMsg {
            val calls = o.optJSONArray("tool_calls")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    val f = c.optJSONObject("function") ?: return@mapNotNull null
                    ToolCall(c.optString("id"), f.optString("name"), f.optString("arguments"))
                }
            }
            val images = o.optJSONArray("images")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
            }.orEmpty()
            return ChatMsg(
                role = o.optString("role", "assistant"),
                content = o.optString("content", "").ifBlank { null },
                toolCalls = calls,
                toolCallId = o.optString("tool_call_id", "").ifBlank { null },
                reasoning = o.optString("reasoning", "").ifBlank { null },
                images = images,
            )
        }
    }
}

data class ToolCall(val id: String, val name: String, val arguments: String)

data class AgentSession(
    val id: String,
    var title: String,
    val createdAt: Long,
    var updatedAt: Long,
    val messages: MutableList<ChatMsg> = mutableListOf(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title)
        put("created_at", createdAt); put("updated_at", updatedAt)
        put("messages", JSONArray().apply { messages.forEach { put(it.toJson()) } })
    }

    companion object {
        fun create(): AgentSession = AgentSession(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )

        fun fromJson(o: JSONObject): AgentSession = AgentSession(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", "新会话"),
            createdAt = o.optLong("created_at", System.currentTimeMillis()),
            updatedAt = o.optLong("updated_at", System.currentTimeMillis()),
            messages = o.optJSONArray("messages")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(ChatMsg::fromJson) }
                    .toMutableList()
            } ?: mutableListOf(),
        )
    }
}

/** 执行通道。用户可多选，引擎按顺序尝试：ROOT → Shizuku → Dhizuku。 */
enum class AgentChannel(val id: String, val label: String) {
    ROOT("root", "ROOT"),
    SHIZUKU("shizuku", "Shizuku"),
    DHIZUKU("dhizuku", "Dhizuku"),
}

data class AgentConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double,
    /** none / minimal / low / medium / high；不支持的模型会忽略。 */
    val reasoningEffort: String,
    val contextLimitChars: Int,
    val maxToolRounds: Int,
    val systemPrompt: String,
    val channels: Set<String>,
    /** 流式输出：逐字显示，体验更好；关闭后等整段返回。 */
    val stream: Boolean,
    /** Tavily 搜索 Key；留空则不给模型开放 web_search 工具。 */
    val tavilyKey: String,
    /** 模型是否支持识图：开启后才给模型开放手机操控相关工具。 */
    val visionEnabled: Boolean,
) {
    val ready: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

object AgentStore {

    private const val PREFS = "agent_config"
    private const val DIR = "agent_sessions"

    /**
     * 内置专业提示词。
     *
     * 目标是让模型**先查后改、小步执行、危险操作先说清楚**，而不是一上来就甩命令：
     *  · 明确身份与本机特权通道，避免它建议"你去电脑上 adb"；
     *  · 强制只读优先，改系统前先读现状；
     *  · 危险操作（刷分区、格式化、删系统应用）必须先解释后果；
     *  · 输出要求简洁，命令与结论分开，方便用户扫。
     */
    const val DEFAULT_SYSTEM_PROMPT: String =
        "你是 LA 工具箱内置的 Android 设备 Agent，运行在用户本人的手机上，可以通过内置工具执行 shell 命令。\n" +
            "工作原则：\n" +
            "1. 先查后改：涉及修改系统状态时，先用只读命令确认现状，再给出变更命令。\n" +
            "2. 最小动作：只执行完成当前任务所必需的命令，不要顺手清理、优化或重置无关项。\n" +
            "3. 风险透明：可能造成数据丢失、无法开机、丢失保修的操作，必须先解释后果并等待用户确认；用户明确要求时才执行。\n" +
            "4. 失败要如实说：命令返回非 0 或权限不足时，原样说明失败原因，不要编造结果。\n" +
            "5. 回答用中文，先给结论再给依据；命令放在代码块里，输出过长时只摘关键行。\n" +
            "6. 不确定设备型号/ROM 差异时先问，不要猜测。\n" +
            "可用工具：run_shell、get_device_info、fetch_url / http_request、screenshot、clipboard_get / clipboard_set、open_app、read_file / write_file（仅限应用目录）、list_packages、app_control。配置 Tavily Key 后有 web_search。若模型支持识图，可调用 phone_control_start 请求用户授权后操控手机（screen_snapshot 看屏幕、tap / swipe / press 操作、phone_control_stop 结束）；截图保持真实屏幕分辨率，tap/swipe 坐标直接按截图上的像素填，不要换算；每次操控都必须先经用户同意，用户可随时连按两次音量上键中止。"

    fun config(context: Context): AgentConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AgentConfig(
            baseUrl = p.getString("base_url", "")?.trim().orEmpty().trimEnd('/'),
            apiKey = p.getString("api_key", "").orEmpty(),
            model = p.getString("model", "").orEmpty(),
            temperature = p.getFloat("temperature", 0.3f).toDouble(),
            reasoningEffort = p.getString("reasoning_effort", "none").orEmpty(),
            contextLimitChars = p.getInt("context_limit", 24_000),
            /*
             * 迁移：旧版本默认值就是 8，并且被写进过 prefs，导致升级后仍然是 8。
             * 只迁移一次（用 migrated_rounds_v2 标记），之后用户自己改的值不再覆盖。
             */
            maxToolRounds = run {
                val stored = p.getInt("max_tool_rounds", 50)
                if (!p.getBoolean("migrated_rounds_v2", false) && stored <= 8) {
                    p.edit().putInt("max_tool_rounds", 50).putBoolean("migrated_rounds_v2", true).apply()
                    50
                } else {
                    if (!p.getBoolean("migrated_rounds_v2", false)) {
                        p.edit().putBoolean("migrated_rounds_v2", true).apply()
                    }
                    stored
                }
            },
            systemPrompt = p.getString("system_prompt", DEFAULT_SYSTEM_PROMPT)
                ?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT,
            channels = p.getStringSet("channels", setOf(AgentChannel.ROOT.id, AgentChannel.SHIZUKU.id, AgentChannel.DHIZUKU.id))
                ?.toSet().orEmpty(),
            stream = p.getBoolean("stream", true),
            tavilyKey = p.getString("tavily_key", "").orEmpty(),
            visionEnabled = p.getBoolean("vision_enabled", true),
        )
    }

    fun saveConfig(context: Context, config: AgentConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("base_url", config.baseUrl.trim().trimEnd('/'))
            .putString("api_key", config.apiKey.trim())
            .putString("model", config.model.trim())
            .putFloat("temperature", config.temperature.toFloat())
            .putString("reasoning_effort", config.reasoningEffort)
            .putInt("context_limit", config.contextLimitChars)
            .putInt("max_tool_rounds", config.maxToolRounds)
            .putString("system_prompt", config.systemPrompt.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT)
            .putStringSet("channels", config.channels)
            .putBoolean("stream", config.stream)
            .putString("tavily_key", config.tavilyKey.trim())
            .putBoolean("vision_enabled", config.visionEnabled)
            .apply()
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun list(context: Context): List<AgentSession> = dir(context).listFiles()
        ?.filter { it.name.endsWith(".json") }
        ?.mapNotNull { f -> runCatching { AgentSession.fromJson(JSONObject(f.readText())) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        .orEmpty()

    fun save(context: Context, session: AgentSession) {
        runCatching { File(dir(context), "${session.id}.json").writeText(session.toJson().toString()) }
    }

    fun delete(context: Context, id: String) {
        runCatching { File(dir(context), "$id.json").delete() }
    }

    fun search(sessions: List<AgentSession>, query: String): List<AgentSession> {
        val q = query.trim()
        if (q.isEmpty()) return sessions
        return sessions.filter { s ->
            s.title.contains(q, ignoreCase = true) ||
                s.messages.any { it.content?.contains(q, ignoreCase = true) == true }
        }
    }
}
