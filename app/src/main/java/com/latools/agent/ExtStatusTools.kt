// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

package com.latools.agent

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 状态查询：设备状态、网络、通知、应用使用时长。
 *
 * 这些工具都是只读的，不弹确认；目的是让模型"先查后说"，少靠猜。
 */
object StatusTools : ExtToolGroup {

    override val group = ToolGroup.STATUS

    private val names = setOf("get_status", "get_network", "list_notifications", "usage_stats")

    override fun handles(name: String): Boolean = name in names

    override fun specs(config: AgentConfig, out: JSONArray) {
        appendTool(
            out,
            "get_status",
            "读取设备整体状态：电量/充电/温度、内存、存储、网络、亮屏、亮度、音量、勿扰、下一个闹钟、定位开关、运行时长。",
        )
        appendTool(
            out,
            "get_network",
            "读取当前网络类型、IP、DNS、是否计量网络，可顺便测延迟（ping）。",
            toolProps("ping" to toolProp("string", "可选，要 ping 的主机名或 IP，例如 www.baidu.com")),
        )
        appendTool(
            out,
            "list_notifications",
            "读取当前通知栏里的通知（来源应用、标题、内容、时间）。需要用户授予「通知读取」权限。",
            toolProps("limit" to toolProp("integer", "最多返回条数，默认 30")),
        )
        appendTool(
            out,
            "usage_stats",
            "查看今天各应用的使用时长排行。需要用户授予「使用情况访问」权限。",
            toolProps("top" to toolProp("integer", "返回前几名，默认 10")),
        )
    }

    override suspend fun execute(ctx: ToolCtx, name: String, args: JSONObject): String = when (name) {
        "get_status" -> status(ctx)
        "get_network" -> network(ctx, args)
        "list_notifications" -> notifications(ctx, args)
        "usage_stats" -> usageStats(ctx, args)
        else -> "未知工具：$name"
    }

    private fun status(ctx: ToolCtx): String {
        val context = ctx.context
        val bm = context.getSystemService(BatteryManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)

        val batteryLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        val batteryIntent = runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context,
                null,
                android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.getOrNull()
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1

        val memInfo = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val stat = runCatching { StatFs(Environment.getDataDirectory().absolutePath) }.getOrNull()
        val storageTotal = (stat?.blockCountLong ?: 0L) * (stat?.blockSizeLong ?: 0L)
        val storageFree = (stat?.availableBlocksLong ?: 0L) * (stat?.blockSizeLong ?: 0L)

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val netText = networkTypeText(caps)

        val brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull() ?: -1
        val maxVolume = audio?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
        val volume = audio?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
        val dnd = when (nm?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "关闭"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "仅优先"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "完全静默"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "仅闹钟"
            else -> "未知"
        }
        val nextAlarm = alarm?.nextAlarmClock?.triggerTime
        val locationOn = runCatching {
            context.getSystemService(android.location.LocationManager::class.java)?.isLocationEnabled == true
        }.getOrDefault(false)
        val uptimeMs = android.os.SystemClock.elapsedRealtime()
        val listenerOn = ToolPermissions.notificationListenerEnabled(context)

        return buildString {
            appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）")
            appendLine("电量：${if (batteryLevel >= 0) "$batteryLevel%" else "未知"}，${if (charging) "充电中" else "未充电"}，温度 ${if (tempTenths > 0) "${tempTenths / 10f}℃" else "未知"}")
            appendLine("内存：可用 ${fmtBytes(memInfo.availMem)} / 共 ${fmtBytes(memInfo.totalMem)}")
            appendLine("存储：可用 ${fmtBytes(storageFree)} / 共 ${fmtBytes(storageTotal)}")
            appendLine("网络：$netText")
            appendLine("屏幕：${if (pm?.isInteractive == true) "亮屏" else "息屏"}，亮度 ${if (brightness >= 0) "$brightness/255" else "未知"}")
            appendLine("媒体音量：$volume/$maxVolume")
            appendLine("勿扰：$dnd；通知读取权限：${if (listenerOn) "已开启" else "未开启"}")
            appendLine("下一个闹钟：" + (nextAlarm?.let { "${fmtTime(it)}（还有 ${fmtDuration(it - System.currentTimeMillis())}）" } ?: "无"))
            appendLine("定位开关：${if (locationOn) "开启" else "关闭"}")
            append("运行时长：${fmtDuration(uptimeMs)}；当前时间：${fmtTime(System.currentTimeMillis())}")
        }
    }

    private fun networkTypeText(caps: NetworkCapabilities?): String {
        if (caps == null) return "无网络"
        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "未知"
        }
        val metered = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "非计量" else "计量"
        return "$type（$metered）"
    }

    private fun network(ctx: ToolCtx, args: JSONObject): String {
        val context = ctx.context
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val link: LinkProperties? = network?.let { cm.getLinkProperties(it) }
        val ipList = link?.linkAddresses?.mapNotNull { it.address?.hostAddress }?.filter { it.isNotBlank() }.orEmpty()

        val sb = StringBuilder()
        sb.appendLine("网络类型：${networkTypeText(caps)}")
        if (ipList.isNotEmpty()) sb.appendLine("本机地址：${ipList.joinToString("，")}")
        val dns = link?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
        if (dns.isNotEmpty()) sb.appendLine("DNS：${dns.joinToString("，")}")
        if (caps != null) {
            sb.appendLine("下行带宽估计：${caps.linkDownstreamBandwidthKbps / 1000} Mbps（系统估计）")
            sb.appendLine("网络已验证：${if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "是" else "否"}")
        }

        val host = args.optString("ping").trim()
        if (host.isNotBlank()) {
            val safe = host.filter { it.isLetterOrDigit() || it in ".-:_" }
            if (safe.isBlank()) return sb.append("ping 参数不合法").toString()
            val output = if (ctx.hasShell()) {
                ctx.shell("ping -c 3 -W 2 ${shqExt(safe)}", 12_000).output
            } else {
                runCatching {
                    val p = Runtime.getRuntime().exec(arrayOf("ping", "-c", "3", "-W", "2", safe))
                    p.inputStream.bufferedReader().readText()
                }.getOrElse { "ping 执行失败：${it.message}" }
            }
            sb.appendLine().append("ping $safe：\n").append(output.take(1_200))
        }
        return sb.toString().trim()
    }

    private fun notifications(ctx: ToolCtx, args: JSONObject): String {
        val items = AgentNotificationListener.snapshot(ctx.context)
            ?: return ToolPermissions.guidance(ToolPerm.NOTIFICATION_LISTENER) +
                "（开启后通知读取服务才可用）"
        if (items.isEmpty()) return "当前通知栏没有通知。"
        val limit = args.optInt("limit", 30).coerceIn(1, 80)
        return buildString {
            append("当前共 ").append(items.size).append(" 条通知（显示前 ").append(minOf(limit, items.size)).append(" 条）：\n")
            items.sortedByDescending { it.time }.take(limit).forEachIndexed { i, n ->
                append(i + 1).append(". [").append(n.appLabel).append("] ")
                if (n.ongoing) append("（常驻）")
                append(n.title.ifBlank { "（无标题）" })
                if (n.text.isNotBlank()) append("：").append(n.text.take(120))
                append("  ").append(fmtTime(n.time)).append("\n")
            }
        }.trim()
    }

    private fun usageStats(ctx: ToolCtx, args: JSONObject): String {
        ToolPermissions.check(ctx.context, ToolPerm.USAGE_ACCESS)?.let { return it }
        val context = ctx.context
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return "读取不到使用情况服务"
        val now = System.currentTimeMillis()
        val start = now - (now % TimeUnit.DAYS.toMillis(1)) // 当天 0 点（UTC 边界，仅用于大致排行）
        val stats = manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
        if (stats.isNullOrEmpty()) return "没有查到使用记录（可能今天还没开始使用，或系统限制了统计）"
        val top = stats.asSequence()
            .filter { it.totalTimeInForeground > 60_000 }
            .groupBy { it.packageName }
            .mapValues { entry -> entry.value.sumOf { it.totalTimeInForeground } }
            .entries.sortedByDescending { it.value }
            .take(args.optInt("top", 10).coerceIn(1, 30))
            .toList()
        if (top.isEmpty()) return "今天还没有明显的应用使用记录。"
        return buildString {
            append("今天应用使用时长排行：\n")
            top.forEachIndexed { index, entry ->
                append(index + 1).append(". ").append(ctx.appLabel(entry.key))
                append("（").append(entry.key).append("）：").append(fmtDuration(entry.value)).append("\n")
            }
        }.trim()
    }
}

/**
 * 通知读取服务。
 *
 * 系统只允许"用户手动授予通知使用权"的应用读取通知，所以这里不做任何请求，
 * 只负责在已授权时把 activeNotifications 整理成模型能看懂的文本。
 */
class AgentNotificationListener : NotificationListenerService() {

    data class Item(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val time: Long,
        val ongoing: Boolean,
    )

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: AgentNotificationListener? = null

        /** null 表示通知读取服务未连接（用户未授权或被系统回收）。 */
        fun snapshot(context: Context): List<Item>? {
            val service = instance ?: return null
            val list = runCatching { service.activeNotifications }.getOrElse { return null }
            return list.mapNotNull { sbn -> toItem(context, sbn) }
        }

        private fun toItem(context: Context, sbn: StatusBarNotification): Item? =
            runCatching {
                val pm = context.packageManager
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                }.getOrDefault(sbn.packageName)
                val extras = sbn.notification?.extras
                val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?: extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString().orEmpty()
                val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
                Item(
                    packageName = sbn.packageName,
                    appLabel = label,
                    title = title.orEmpty(),
                    text = text.orEmpty(),
                    time = sbn.postTime,
                    ongoing = (sbn.notification?.flags ?: 0) and Notification.FLAG_ONGOING_EVENT != 0,
                )
            }.getOrNull()
    }
}
