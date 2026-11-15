// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

package com.latools.agent

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 通讯与日程：联系人、电话、短信、日历。
 *
 * 这几类都是敏感能力，处理方式统一：
 *  · 权限检查前置，没有权限就返回"去设置里授权"的明确提示，不用 shell 绕过；
 *  · 真正产生外部影响的操作（拨出电话、发短信）必须过用户确认；
 *  · 只读查询（搜联系人、看日程）不打断用户。
 */
object ContentTools : ExtToolGroup {

    override val group = ToolGroup.CONTENT

    private val names = setOf("search_contacts", "call", "send_sms", "calendar")

    override fun handles(name: String): Boolean = name in names

    override fun specs(config: AgentConfig, out: JSONArray) {
        appendTool(
            out,
            "search_contacts",
            "按姓名或号码搜索联系人，返回姓名和号码。需要「联系人」权限。",
            toolProps(
                "query" to toolProp("string", "姓名或号码片段；留空则列出前几个联系人"),
                "limit" to toolProp("integer", "可选，最多返回条数，默认 10"),
            ),
        )
        appendTool(
            out,
            "call",
            "打电话：默认只拉起拨号界面（用户自己按拨出）；mode=call 时直接拨出（会先确认）。需要「电话」权限。",
            toolProps(
                "number" to toolProp("string", "电话号码"),
                "mode" to toolProp("string", "可选，dial（默认，只填好号码）/ call（直接拨出）"),
            ),
            listOf("number"),
        )
        appendTool(
            out,
            "send_sms",
            "发送短信。执行前会先让用户确认。需要「短信」权限。",
            toolProps(
                "number" to toolProp("string", "接收方号码"),
                "text" to toolProp("string", "短信内容"),
            ),
            listOf("number", "text"),
        )
        appendTool(
            out,
            "calendar",
            "查看未来日程或新建日程。需要「日历」权限。",
            toolProps(
                "action" to toolProp("string", "list（查看）/ create（新建），默认 list"),
                "days" to toolProp("integer", "可选，list 时查看未来几天，默认 7"),
                "title" to toolProp("string", "create 时必填，日程标题"),
                "begin" to toolProp("string", "create 时必填，开始时间：yyyy-MM-dd HH:mm / HH:mm（今天或明天）/ 毫秒时间戳"),
                "duration_minutes" to toolProp("integer", "可选，时长分钟，默认 60"),
                "location" to toolProp("string", "可选，地点"),
                "description" to toolProp("string", "可选，备注"),
            ),
        )
    }

    override suspend fun execute(ctx: ToolCtx, name: String, args: JSONObject): String = when (name) {
        "search_contacts" -> searchContacts(ctx, args)
        "call" -> call(ctx, args)
        "send_sms" -> sendSms(ctx, args)
        "calendar" -> calendar(ctx, args)
        else -> "未知工具：$name"
    }

    private fun searchContacts(ctx: ToolCtx, args: JSONObject): String {
        ToolPermissions.check(ctx.context, ToolPerm.CONTACTS)?.let { return it }
        val query = args.optString("query").trim()
        val limit = args.optInt("limit", 10).coerceIn(1, 50)
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection: String?
        val selectionArgs: Array<String>?
        if (query.isBlank()) {
            selection = null
            selectionArgs = null
        } else {
            selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            selectionArgs = arrayOf("%$query%", "%$query%")
        }
        val results = linkedMapOf<String, String>()
        runCatching {
            ctx.context.contentResolver.query(
                uri, projection, selection, selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(projection[0])
                val numberIdx = cursor.getColumnIndex(projection[1])
                while (cursor.moveToNext() && results.size < limit * 3) {
                    val name = cursor.getString(nameIdx)?.trim().orEmpty()
                    val number = cursor.getString(numberIdx)?.trim().orEmpty()
                    if (number.isNotBlank()) {
                        val display = if (name.isBlank()) number else "$name：$number"
                        results.putIfAbsent(display, display)
                    }
                }
            }
        }.getOrElse { return "读取联系人失败：${it.message ?: it.javaClass.simpleName}" }
        if (results.isEmpty()) return if (query.isBlank()) "通讯录里没有读到联系人。" else "没有找到匹配「$query」的联系人。"
        return "匹配到 ${results.size} 个联系人：\n" + results.values.take(limit).joinToString("\n")
    }

    private suspend fun call(ctx: ToolCtx, args: JSONObject): String {
        val number = args.optString("number").trim()
        if (number.isBlank()) return "参数错误：缺少 number"
        val mode = args.optString("mode").ifBlank { "dial" }.lowercase(Locale.ROOT)
        if (mode == "call") {
            ToolPermissions.check(ctx.context, ToolPerm.PHONE)?.let { return it }
            if (!ctx.ask("直接拨出电话给 $number ？")) return "用户取消了拨号"
            return launchIntent(
                ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")), "拨出电话",
            )
        }
        return launchIntent(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")), "打开拨号界面")
    }

    private suspend fun sendSms(ctx: ToolCtx, args: JSONObject): String {
        val number = args.optString("number").trim()
        val text = args.optString("text")
        if (number.isBlank()) return "参数错误：缺少 number"
        if (text.isBlank()) return "参数错误：缺少 text"
        ToolPermissions.check(ctx.context, ToolPerm.SMS)?.let { return it }
        if (!ctx.ask("发送短信\n收件人：$number\n内容：$text")) return "用户取消了发送短信"
        return runCatching {
            val sms = ctx.context.getSystemService(SmsManager::class.java)
                ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            if (parts.size <= 1) {
                sms.sendTextMessage(number, null, text, null, null)
            } else {
                sms.sendMultipartTextMessage(number, null, parts, null, null)
            }
            "已发送短信给 $number（${text.length} 字，${parts.size} 条）"
        }.getOrElse { "发送失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private suspend fun calendar(ctx: ToolCtx, args: JSONObject): String {
        val action = args.optString("action").ifBlank { "list" }.lowercase(Locale.ROOT)
        return when (action) {
            "list" -> listCalendar(ctx, args)
            "create" -> createCalendar(ctx, args)
            else -> "未知 action：$action（可用 list / create）"
        }
    }

    private fun listCalendar(ctx: ToolCtx, args: JSONObject): String {
        ToolPermissions.check(ctx.context, ToolPerm.CALENDAR)?.let { return it }
        val days = args.optInt("days", 7).coerceIn(1, 60)
        val start = System.currentTimeMillis()
        val end = start + days * 24L * 60 * 60 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )
        val events = mutableListOf<String>()
        runCatching {
            ctx.context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
                ?.use { cursor ->
                    while (cursor.moveToNext() && events.size < 40) {
                        val title = cursor.getString(0).orEmpty()
                        val begin = cursor.getLong(1)
                        val endTime = cursor.getLong(2)
                        val location = cursor.getString(3).orEmpty()
                        val calendarName = cursor.getString(4).orEmpty()
                        events += buildString {
                            append("· ").append(fmtTime(begin))
                            if (endTime > begin) append(" ~ ").append(SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(endTime)))
                            append("  ").append(title.ifBlank { "（无标题）" })
                            if (location.isNotBlank()) append("  @").append(location)
                            if (calendarName.isNotBlank()) append("  [").append(calendarName).append("]")
                        }
                    }
                }
        }.getOrElse { return "读取日历时出错：${it.message ?: it.javaClass.simpleName}" }
        if (events.isEmpty()) return "未来 $days 天没有日程。"
        return "未来 $days 天的日程：\n" + events.joinToString("\n")
    }

    private suspend fun createCalendar(ctx: ToolCtx, args: JSONObject): String {
        ToolPermissions.check(ctx.context, ToolPerm.CALENDAR)?.let { return it }
        val title = args.optString("title").trim()
        if (title.isBlank()) return "参数错误：create 需要 title"
        val beginText = args.optString("begin").trim()
        if (beginText.isBlank()) return "参数错误：create 需要 begin（yyyy-MM-dd HH:mm / HH:mm / 时间戳）"
        val begin = parseBegin(beginText) ?: return "无法解析时间：$beginText"
        val duration = args.optInt("duration_minutes", 60).coerceIn(5, 24 * 60)
        val end = begin + duration * 60_000L

        val calendarId = findWritableCalendar(ctx.context)
            ?: return "没有找到可写的日历账户。请让用户先在系统日历里添加一个账户。"
        if (!ctx.askWrite("新建日程：$title\n${fmtTime(begin)}（${duration} 分钟）")) return "用户取消了新建日程"
        return runCatching {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (args.optString("location").isNotBlank()) {
                    put(CalendarContract.Events.EVENT_LOCATION, args.optString("location"))
                }
                if (args.optString("description").isNotBlank()) {
                    put(CalendarContract.Events.DESCRIPTION, args.optString("description"))
                }
            }
            val uri = ctx.context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) "新建失败（系统拒绝写入日历）"
            else "已创建日程：$title，${fmtTime(begin)} ~ ${fmtTime(end)}（id=${ContentUris.parseId(uri)}）"
        }.getOrElse { "新建日程失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun findWritableCalendar(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        var primary: Long? = null
        var fallback: Long? = null
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE}=1",
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val isPrimary = cursor.getInt(1) == 1
                    val access = cursor.getInt(2)
                    if (access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                    if (isPrimary && primary == null) primary = id
                    if (fallback == null) fallback = id
                }
            }
        }
        return primary ?: fallback
    }

    private fun parseBegin(text: String): Long? {
        text.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        for (pattern in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA).parse(text)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        // HH:mm：取今天，已过则取明天
        val parsedTime = runCatching {
            SimpleDateFormat("HH:mm", Locale.CHINA).parse(text)
        }.getOrNull() ?: return null
        val cal = Calendar.getInstance().apply {
            time = parsedTime
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
