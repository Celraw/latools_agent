// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

package com.latools.agent

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * 时间与提醒：系统闹钟、计时器、App 内提醒。
 *
 * 闹钟 / 计时器走系统时钟 App 的标准 Intent（AlarmClock.ACTION_SET_*），
 * 这样响铃、贪睡、勿扰策略都由系统接管，比自己起 AlarmManager 更符合用户预期；
 * 提醒类（"30 分钟后提醒我喝水"）没有对应的系统入口，用 App 自己的 AlarmManager + 通知实现。
 */
object TimeTools : ExtToolGroup {

    override val group = ToolGroup.TIME

    private val names = setOf(
        "get_time", "set_alarm", "set_timer", "query_alarm", "dismiss_alarm",
        "create_reminder", "list_reminders", "cancel_reminder",
    )

    override fun handles(name: String): Boolean = name in names

    override fun specs(config: AgentConfig, out: JSONArray) {
        appendTool(
            out,
            "get_time",
            "获取设备当前日期、时间、星期和时区。回答涉及“现在几点/今天几号/明天”的问题前先调用。",
        )
        appendTool(
            out,
            "set_alarm",
            "设置系统闹钟。默认静默直接写入（skip_ui=true）；部分 ROM 会忽略静默、弹出确认界面，请提示用户确认。",
            toolProps(
                "hour" to toolProp("integer", "小时，0~23"),
                "minute" to toolProp("integer", "分钟，0~59，默认 0"),
                "label" to toolProp("string", "闹钟标签，例如「起床」"),
                "days" to toolProp("array", "可选，重复星期：1=周一 … 7=周日，不传表示只响一次"),
                "vibrate" to toolProp("boolean", "可选，是否振动，默认 true"),
                "skip_ui" to toolProp("boolean", "可选，是否不弹系统确认界面，默认 true"),
            ),
            listOf("hour"),
        )
        appendTool(
            out,
            "set_timer",
            "设置系统倒计时/计时器（例如煮面 5 分钟）。",
            toolProps(
                "seconds" to toolProp("integer", "倒计时秒数；也可以传 minutes"),
                "minutes" to toolProp("integer", "倒计时分钟数（seconds 未传时使用）"),
                "label" to toolProp("string", "计时器标签"),
                "skip_ui" to toolProp("boolean", "可选，默认 true"),
            ),
        )
        appendTool(out, "query_alarm", "查询系统里下一个闹钟的时间和剩余时长。")
        appendTool(
            out,
            "dismiss_alarm",
            "关闭/跳过当前正在响或即将响的闹钟。",
            toolProps("label" to toolProp("string", "可选，按标签匹配要关闭的闹钟")),
        )
        appendTool(
            out,
            "create_reminder",
            "创建 App 内提醒（到点弹通知），适合「30 分钟后提醒我…」。与系统闹钟不同，它不会响铃，只是一条通知提醒。",
            toolProps(
                "message" to toolProp("string", "提醒内容"),
                "minutes" to toolProp("integer", "多少分钟后提醒"),
                "at" to toolProp("string", "可选，绝对时间 HH:mm（今天或明天最近的一次），与 minutes 二选一"),
            ),
            listOf("message"),
        )
        appendTool(out, "list_reminders", "列出尚未触发的 App 内提醒。")
        appendTool(
            out,
            "cancel_reminder",
            "取消一个或全部 App 内提醒。",
            toolProps(
                "id" to toolProp("integer", "提醒 id（list_reminders 返回）；不传且 all=true 时取消全部"),
                "all" to toolProp("boolean", "可选，true 表示取消全部提醒"),
            ),
        )
    }

    override suspend fun execute(ctx: ToolCtx, name: String, args: JSONObject): String = when (name) {
        "get_time" -> nowText()
        "set_alarm" -> setAlarm(ctx, args)
        "set_timer" -> setTimer(ctx, args)
        "query_alarm" -> queryAlarm(ctx)
        "dismiss_alarm" -> dismissAlarm(ctx, args)
        "create_reminder" -> createReminder(ctx, args)
        "list_reminders" -> listReminders(ctx)
        "cancel_reminder" -> cancelReminder(ctx, args)
        else -> "未知工具：$name"
    }

    private fun setAlarm(ctx: ToolCtx, args: JSONObject): String {
        val hour = args.optInt("hour", -1)
        if (hour !in 0..23) return "参数错误：hour 需要在 0~23 之间"
        val minute = args.optInt("minute", 0).coerceIn(0, 59)
        val label = args.optString("label")
        val days = args.optJSONArray("days")?.let { arr ->
            (0 until arr.length()).map { arr.optInt(it) }.filter { it in 1..7 }
        }.orEmpty()
        val skipUi = args.optBoolean("skip_ui", true)
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_VIBRATE, args.optBoolean("vibrate", true))
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            if (days.isNotEmpty()) putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        }
        val timeText = String.format(Locale.CHINA, "%02d:%02d", hour, minute)
        val repeatText = if (days.isEmpty()) "只响一次" else "每周 " + days.sorted().joinToString("、")
        var result = launchIntent(ctx, intent, "设置闹钟")
        // 少数 ROM 没有处理 SKIP_UI 的能力，会直接报无法打开：退回带界面的方式让用户确认
        if (skipUi && result.startsWith("无法")) {
            intent.putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            result = launchIntent(ctx, intent, "设置闹钟（带确认界面）")
        }
        return "$result：$timeText（$repeatText）" +
            if (skipUi) "。如果屏幕没有闪出确认界面，说明系统已静默写入；若闪出了界面，请让用户点保存。" else "。"
    }

    private fun setTimer(ctx: ToolCtx, args: JSONObject): String {
        val seconds = when {
            args.has("seconds") -> args.optInt("seconds", 0)
            args.has("minutes") -> args.optInt("minutes", 0) * 60
            else -> 0
        }
        if (seconds <= 0) return "参数错误：需要 seconds 或 minutes（大于 0）"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, args.optBoolean("skip_ui", true))
            if (args.optString("label").isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, args.optString("label"))
        }
        return launchIntent(ctx, intent, "设置计时器") + "：${fmtDuration(seconds * 1000L)}"
    }

    private fun queryAlarm(ctx: ToolCtx): String {
        val am = ctx.context.getSystemService(AlarmManager::class.java)
            ?: return "读取不到 AlarmManager"
        val next = am.nextAlarmClock ?: return "系统里没有设置闹钟。\n${nowText()}"
        val remain = next.triggerTime - System.currentTimeMillis()
        return buildString {
            append("下一个闹钟：").append(fmtTime(next.triggerTime))
            if (remain > 0) append("（还有 ").append(fmtDuration(remain)).append("）")
            append("\n").append(nowText())
        }
    }

    private fun dismissAlarm(ctx: ToolCtx, args: JSONObject): String {
        val label = args.optString("label")
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_NEXT)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
        }
        return launchIntent(ctx, intent, "关闭闹钟")
    }

    private suspend fun createReminder(ctx: ToolCtx, args: JSONObject): String {
        val message = args.optString("message").trim()
        if (message.isBlank()) return "参数错误：缺少 message"
        val at = when {
            args.has("minutes") -> {
                val minutes = args.optInt("minutes", 0)
                if (minutes <= 0) return "参数错误：minutes 需要大于 0"
                System.currentTimeMillis() + minutes * 60_000L
            }
            args.optString("at").isNotBlank() -> {
                val text = args.optString("at").trim()
                val time = runCatching { LocalTime.parse(text) }.getOrElse {
                    return "参数错误：at 需要 HH:mm 格式，例如 09:30"
                }
                var target = LocalDate.now().atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (target <= System.currentTimeMillis()) target += 24 * 60 * 60 * 1000L
                target
            }
            else -> return "参数错误：需要 minutes 或 at"
        }
        val (id, exact) = Reminders.schedule(ctx.context, at, message)
        val notifyOk = ToolPermissions.has(ctx.context, Manifest.permission.POST_NOTIFICATIONS)
        return buildString {
            append("已创建提醒 #").append(id).append("：").append(fmtTime(at))
            append("（").append(message).append("）")
            append("\n").append(if (exact) "已使用精确闹钟，到点准时提醒。" else "未获得精确闹钟权限，系统可能延迟几分钟触发；可在设置 → 工具与权限 → 精确闹钟 开启。")
            if (!notifyOk) append("\n注意：通知权限未开启，到点可能看不到提醒。")
        }
    }

    private fun listReminders(ctx: ToolCtx): String {
        val items = Reminders.list(ctx.context).sortedBy { it.at }
        if (items.isEmpty()) return "没有待触发的提醒。"
        return buildString {
            append("共 ").append(items.size).append(" 条提醒：\n")
            items.forEach { item ->
                append("#").append(item.id).append("  ").append(fmtTime(item.at))
                append("  ").append(item.message)
                append("（还有 ").append(fmtDuration(item.at - System.currentTimeMillis())).append("）\n")
            }
        }.trim()
    }

    private fun cancelReminder(ctx: ToolCtx, args: JSONObject): String {
        if (args.optBoolean("all", false)) {
            val count = Reminders.cancelAll(ctx.context)
            return "已取消全部提醒（$count 条）"
        }
        val id = args.optInt("id", 0)
        if (id == 0) return "参数错误：需要 id，或传 all=true 取消全部"
        return if (Reminders.cancel(ctx.context, id)) "已取消提醒 #$id" else "没有找到提醒 #$id"
    }
}

/**
 * App 内提醒的存储与调度。
 *
 * 提醒只在本地存活（重启设备会丢），换取的是实现简单、不需要开机广播和额外权限；
 * 需要长期稳定的提醒时应该用系统闹钟（set_alarm）。
 */
object Reminders {

    private const val PREFS = "agent_reminders"
    private const val KEY = "items"
    private const val CHANNEL_ID = "agent_reminder"

    data class Item(val id: Int, val at: Long, val message: String)

    fun schedule(context: Context, at: Long, message: String): Pair<Int, Boolean> {
        val id = (System.currentTimeMillis() % 1_000_000).toInt() + 1
        val item = Item(id, at, message)
        val all = list(context).toMutableList()
        all.removeAll { it.id == id }
        all.add(item)
        write(context, all)

        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("message", message)
        }
        val pi = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = am != null && (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
        if (am != null) {
            runCatching {
                if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
        return id to exact
    }

    fun list(context: Context): List<Item> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                Item(o.optInt("id"), o.optLong("at"), o.optString("message"))
            }
        }
    }.getOrDefault(emptyList())

    fun cancel(context: Context, id: Int): Boolean {
        val all = list(context).toMutableList()
        val removed = all.removeAll { it.id == id }
        write(context, all)
        context.getSystemService(AlarmManager::class.java)?.let { am ->
            val pi = PendingIntent.getBroadcast(
                context, id, Intent(context, ReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) am.cancel(pi)
        }
        return removed
    }

    fun cancelAll(context: Context): Int {
        val all = list(context)
        all.forEach { cancel(context, it.id) }
        return all.size
    }

    fun remove(context: Context, id: Int) {
        write(context, list(context).filterNot { it.id == id })
    }

    private fun write(context: Context, items: List<Item>) {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("at", item.at)
                put("message", item.message)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun postNotification(context: Context, id: Int, message: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Agent 提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "AI 创建的本地提醒"
                },
            )
        }
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("LA Agent 提醒")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { manager.notify(0x2A70 + id, notification) }
    }
}

/** 提醒到点：弹通知并从待触发列表里移除。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val message = intent.getStringExtra("message").orEmpty().ifBlank { "你设置的提醒到时间了" }
        Reminders.postNotification(context, id, message)
        if (id != 0) Reminders.remove(context, id)
    }
}
