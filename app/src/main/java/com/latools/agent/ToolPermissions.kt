// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

package com.latools.agent

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** Android 13 起相册权限拆成 READ_MEDIA_IMAGES。 */
private fun photoPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES)
    else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

/**
 * 扩展工具依赖的权限 / 特殊访问。
 *
 * 普通运行时权限可以在设置页里直接申请；特殊访问（修改系统设置、通知读取、用量统计、
 * 勿扰、精确闹钟）Android 不允许弹窗申请，只能跳到对应的系统设置页由用户手动开启。
 */
enum class ToolPerm(
    val key: String,
    val label: String,
    val desc: String,
    val runtime: List<String> = emptyList(),
    val special: Boolean = false,
) {
    LOCATION(
        "location", "位置", "读取当前位置（拒绝精确时退化为城市级）",
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
    ),
    CONTACTS("contacts", "联系人", "搜索联系人", listOf(Manifest.permission.READ_CONTACTS)),
    PHONE("phone", "电话", "直接拨出电话", listOf(Manifest.permission.CALL_PHONE)),
    SMS("sms", "短信", "发送短信", listOf(Manifest.permission.SEND_SMS)),
    CALENDAR(
        "calendar", "日历", "读取与新建日程",
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
    ),
    PHOTOS("photos", "照片", "读取相册最近照片", photoPermissions()),
    WRITE_SETTINGS("write_settings", "修改系统设置", "调节屏幕亮度等系统设置", special = true),
    NOTIFICATION_LISTENER("notification_listener", "通知读取", "读取当前通知列表", special = true),
    USAGE_ACCESS("usage_access", "使用情况访问", "查看应用使用时长统计", special = true),
    DND("dnd", "勿扰模式", "切换勿扰 / 静音模式", special = true),
    EXACT_ALARM("exact_alarm", "精确闹钟", "让提醒到点准时触发（不开启则可能延迟）", special = true),
    ;

    fun granted(context: Context): Boolean = when (this) {
        LOCATION -> ToolPermissions.has(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            ToolPermissions.has(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        WRITE_SETTINGS -> Settings.System.canWrite(context)
        NOTIFICATION_LISTENER -> ToolPermissions.notificationListenerEnabled(context)
        USAGE_ACCESS -> ToolPermissions.usageAccessGranted(context)
        DND -> context.getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted == true
        EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else true
        else -> runtime.all { ToolPermissions.has(context, it) }
    }

    /** 特殊访问的跳转页；运行时权限返回 null（由 Activity 弹窗申请）。 */
    fun specialIntent(context: Context): Intent? = when (this) {
        WRITE_SETTINGS -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
        NOTIFICATION_LISTENER -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        DND -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
        } else null
        else -> null
    }
}

object ToolPermissions {

    fun has(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun notificationListenerEnabled(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        enabled.split(':').any { it.contains(context.packageName) }
    }.getOrDefault(false)

    fun usageAccessGranted(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName,
        )
        if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }.getOrDefault(false)

    /** 工具被调用但缺权限时，返回给模型的人话提示（模型会转述给用户）。 */
    fun guidance(perm: ToolPerm): String =
        "缺少「${perm.label}」授权，无法执行。请让用户打开 Agent 设置 → 工具与权限 → 「${perm.label}」完成授权后再试。" +
            "不要尝试用 run_shell 绕过权限。"

    fun missing(perm: ToolPerm): String = "缺少「${perm.label}」授权"

    /** 特别访问的统一检查，返回 null 表示已授权。 */
    fun check(context: Context, perm: ToolPerm): String? =
        if (perm.granted(context)) null else guidance(perm)

    fun checkRuntime(context: Context, permission: String, label: String): String? =
        if (has(context, permission)) null
        else "缺少「$label」权限，无法执行。请让用户在 Agent 设置 → 工具与权限 里授权后重试；不要用 run_shell 绕过权限。"
}
