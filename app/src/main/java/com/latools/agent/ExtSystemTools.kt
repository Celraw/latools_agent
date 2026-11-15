// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

package com.latools.agent

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 设备控制：锁屏、手电筒、音量、媒体、亮度、显示、勿扰、振动、系统开关、朗读、传感器。
 *
 * 设计原则：
 *  · 能用标准 API 的不用 shell（手电筒、音量、亮度、勿扰）；
 *  · 应用无权改的系统开关（WiFi/蓝牙/飞行模式）才走特权通道，并在没通道时如实说明；
 *  · 改动前能读现状先读现状，返回里带上"改成了什么"，模型和用户都能验证。
 */
object SystemTools : ExtToolGroup {

    override val group = ToolGroup.DEVICE

    private val names = setOf(
        "global_action", "flashlight", "set_volume", "media_control", "vibrate",
        "set_brightness", "set_display", "dnd", "system_toggle", "speak", "sensors",
    )

    override fun handles(name: String): Boolean = name in names

    override fun specs(config: AgentConfig, out: JSONArray) {
        appendTool(
            out,
            "global_action",
            "执行系统级动作：锁屏、电源菜单、下拉通知栏/快捷设置、返回桌面、回退、最近任务、截屏（无障碍实现，无需 ROOT）。",
            toolProps(
                "action" to toolProp(
                    "string",
                    "lock_screen / power_menu / screenshot / notifications / quick_settings / home / back / recents",
                ),
            ),
            listOf("action"),
        )
        appendTool(
            out,
            "flashlight",
            "开关手电筒（后置闪光灯）。",
            toolProps("action" to toolProp("string", "on / off / toggle / status，默认 toggle")),
        )
        appendTool(
            out,
            "set_volume",
            "调节音量或静音：媒体 / 铃声 / 闹钟 / 通话 / 通知。",
            toolProps(
                "stream" to toolProp("string", "media / ring / alarm / call / notification / system，默认 media"),
                "percent" to toolProp("integer", "目标音量百分比 0~100"),
                "index" to toolProp("integer", "可选，直接给音量档位"),
                "mute" to toolProp("boolean", "可选，true 表示静音该音频流"),
            ),
        )
        appendTool(
            out,
            "media_control",
            "控制正在播放的音乐/视频：播放暂停、上一首、下一首、停止。",
            toolProps("action" to toolProp("string", "play_pause / play / pause / next / previous / stop")),
            listOf("action"),
        )
        appendTool(
            out,
            "vibrate",
            "振动提醒。",
            toolProps(
                "duration_ms" to toolProp("integer", "振动时长毫秒，默认 300"),
                "pattern" to toolProp("array", "可选，[振动, 间隔, 振动…] 毫秒数组"),
            ),
        )
        appendTool(
            out,
            "set_brightness",
            "设置屏幕亮度或自动亮度。",
            toolProps(
                "percent" to toolProp("integer", "亮度百分比 1~100；不传则只读取当前值"),
                "auto" to toolProp("boolean", "可选，true 自动亮度 / false 手动亮度"),
                "get" to toolProp("boolean", "可选，true 只查询不修改"),
            ),
        )
        appendTool(
            out,
            "set_display",
            "修改显示相关设置：深色模式、自动旋转、息屏时间、屏幕常亮、刷新率、分辨率。多数需要 ROOT/Shizuku 通道。",
            toolProps(
                "feature" to toolProp("string", "dark_mode / auto_rotate / screen_timeout / stay_awake / refresh_rate / resolution / status"),
                "value" to toolProp("string", "取值随 feature：dark_mode=on/off/auto；auto_rotate=on/off；screen_timeout=秒；stay_awake=on/off；refresh_rate=Hz；resolution=宽x高 或 reset"),
            ),
            listOf("feature"),
        )
        appendTool(
            out,
            "dnd",
            "切换勿扰模式或查询当前状态。需要授予「勿扰模式」访问权限。",
            toolProps("filter" to toolProp("string", "all / priority / none / alarms / get，默认 get")),
        )
        appendTool(
            out,
            "system_toggle",
            "开关 WiFi / 移动数据 / 蓝牙 / 飞行模式 / 定位。Android 10+ 普通应用无权直接开关，需要 ROOT 或 Shizuku 通道。",
            toolProps(
                "target" to toolProp("string", "wifi / data / bluetooth / airplane / location"),
                "action" to toolProp("string", "on / off / status，默认 status"),
            ),
            listOf("target"),
        )
        appendTool(
            out,
            "speak",
            "用系统 TTS 朗读一段文字（适合用户说「念出来」）。",
            toolProps("text" to toolProp("string", "要朗读的文字")),
            listOf("text"),
        )
        appendTool(
            out,
            "sensors",
            "读取传感器：指南针方向、环境光、距离感应。",
            toolProps("type" to toolProp("string", "compass / light / proximity，默认 compass")),
        )
    }

    override suspend fun execute(ctx: ToolCtx, name: String, args: JSONObject): String = when (name) {
        "global_action" -> globalAction(ctx, args)
        "flashlight" -> flashlight(ctx, args)
        "set_volume" -> setVolume(ctx, args)
        "media_control" -> mediaControl(ctx, args)
        "vibrate" -> vibrate(ctx, args)
        "set_brightness" -> setBrightness(ctx, args)
        "set_display" -> setDisplay(ctx, args)
        "dnd" -> dnd(ctx, args)
        "system_toggle" -> systemToggle(ctx, args)
        "speak" -> speak(ctx, args)
        "sensors" -> sensors(ctx, args)
        else -> "未知工具：$name"
    }

    // ------------------------------------------------------------ 各类实现

    private suspend fun globalAction(ctx: ToolCtx, args: JSONObject): String {
        val svc = AgentAccessibilityService.instance
            ?: return "无障碍服务未连接：锁屏/通知栏/截屏等系统动作需要先在 设置 → 手机操控 开启无障碍服务"
        val action = args.optString("action").lowercase(Locale.ROOT)
        if (action == "screenshot") {
            return AccessibilityCapture.capture() ?: "截屏失败（系统拒绝或服务未就绪）"
        }
        val code = when (action) {
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "power_menu" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return "未知动作：$action"
        }
        return if (svc.booleanGlobal(code)) "已执行：$action" else "执行失败：系统拒绝了这个动作"
    }

    /** 找到带闪光灯的摄像头 ID。 */
    private fun torchCameraId(cm: CameraManager): String? = runCatching {
        cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    private fun torchEnabled(ctx: ToolCtx, cm: CameraManager, id: String): Boolean? {
        var state: Boolean? = null
        val latch = CountDownLatch(1)
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) {
                    state = enabled
                    latch.countDown()
                }
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                latch.countDown()
            }
        }
        return runCatching {
            cm.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
            latch.await(600, TimeUnit.MILLISECONDS)
            cm.unregisterTorchCallback(callback)
            state
        }.getOrNull()
    }

    private fun flashlight(ctx: ToolCtx, args: JSONObject): String {
        val cm = ctx.context.getSystemService(CameraManager::class.java)
            ?: return "设备没有相机服务，无法控制手电筒"
        val id = torchCameraId(cm) ?: return "这台设备没有带闪光灯的摄像头"
        val action = args.optString("action").ifBlank { "toggle" }.lowercase(Locale.ROOT)
        val current = torchEnabled(ctx, cm, id)
        return runCatching {
            when (action) {
                "status" -> "手电筒当前：" + when (current) {
                    true -> "已开启"
                    false -> "已关闭"
                    null -> "未知（系统未回报状态）"
                }
                "on" -> {
                    cm.setTorchMode(id, true)
                    "已打开手电筒"
                }
                "off" -> {
                    cm.setTorchMode(id, false)
                    "已关闭手电筒"
                }
                else -> {
                    val target = !(current ?: false)
                    cm.setTorchMode(id, target)
                    if (target) "已打开手电筒" else "已关闭手电筒"
                }
            }
        }.getOrElse { "手电筒操作失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun streamOf(name: String): Int? = when (name.lowercase(Locale.ROOT)) {
        "media", "music" -> AudioManager.STREAM_MUSIC
        "ring", "ringtone" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "call", "voice" -> AudioManager.STREAM_VOICE_CALL
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "system" -> AudioManager.STREAM_SYSTEM
        else -> null
    }

    private fun streamName(stream: Int): String = when (stream) {
        AudioManager.STREAM_MUSIC -> "媒体"
        AudioManager.STREAM_RING -> "铃声"
        AudioManager.STREAM_ALARM -> "闹钟"
        AudioManager.STREAM_VOICE_CALL -> "通话"
        AudioManager.STREAM_NOTIFICATION -> "通知"
        AudioManager.STREAM_SYSTEM -> "系统"
        else -> "音频流 $stream"
    }

    private fun setVolume(ctx: ToolCtx, args: JSONObject): String {
        val am = ctx.context.getSystemService(AudioManager::class.java)
            ?: return "读取不到音频服务"
        val stream = streamOf(args.optString("stream").ifBlank { "media" })
            ?: return "未知音频流：${args.optString("stream")}"
        val max = am.getStreamMaxVolume(stream)
        val current = am.getStreamVolume(stream)
        if (args.has("percent") || args.has("index") || args.optBoolean("mute", false)) {
            val index = when {
                args.optBoolean("mute", false) -> 0
                args.has("index") -> args.optInt("index", current)
                else -> Math.round(args.optInt("percent", 50).coerceIn(0, 100) / 100f * max)
            }.coerceIn(0, max)
            return runCatching {
                am.setStreamVolume(stream, index, 0)
                val now = am.getStreamVolume(stream)
                "已把${streamName(stream)}音量调到 $now/$max（${Math.round(now * 100f / max)}%）"
            }.getOrElse { "调节音量失败：${it.message ?: it.javaClass.simpleName}" }
        }
        return "${streamName(stream)}音量：$current/$max（${Math.round(current * 100f / max)}%）"
    }

    private fun mediaControl(ctx: ToolCtx, args: JSONObject): String {
        val am = ctx.context.getSystemService(AudioManager::class.java)
            ?: return "读取不到音频服务"
        val keys = when (args.optString("action").lowercase(Locale.ROOT)) {
            "play_pause", "toggle" -> intArrayOf(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "play" -> intArrayOf(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            "pause" -> intArrayOf(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            "next" -> intArrayOf(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous", "prev" -> intArrayOf(android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "stop" -> intArrayOf(android.view.KeyEvent.KEYCODE_MEDIA_STOP)
            else -> return "未知动作：${args.optString("action")}"
        }
        return runCatching {
            keys.forEach { code ->
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                Thread.sleep(30)
                am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
            }
            "已发送媒体按键：${args.optString("action")}"
        }.getOrElse { "媒体控制失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun vibrate(ctx: ToolCtx, args: JSONObject): String {
        val vm = ctx.context.getSystemService(VibratorManager::class.java)
            ?: return "设备没有振动器"
        val vibrator = vm.defaultVibrator
        if (!vibrator.hasVibrator()) return "设备没有振动器"
        val pattern = args.optJSONArray("pattern")
        return runCatching {
            if (pattern != null && pattern.length() > 0) {
                val timings = LongArray(pattern.length()) { pattern.optLong(it, 200L) }
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
                "已按图案振动（${timings.size} 段）"
            } else {
                val ms = args.optLong("duration_ms", 300L).coerceIn(50L, 10_000L)
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
                "已振动 ${ms}ms"
            }
        }.getOrElse { "振动失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun setBrightness(ctx: ToolCtx, args: JSONObject): String {
        val context = ctx.context
        val resolver = context.contentResolver
        val canWrite = Settings.System.canWrite(context)
        val current = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrNull() ?: -1
        val autoMode = runCatching {
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        }.getOrNull() == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

        if (args.optBoolean("get", false) || (!args.has("percent") && !args.has("auto"))) {
            return "当前亮度：${if (current >= 0) "$current/255（${Math.round(current * 100f / 255)}%）" else "未知"}；" +
                "自动亮度：${if (autoMode) "开启" else "关闭"}"
        }

        val auto = if (args.has("auto")) args.optBoolean("auto") else autoMode
        val percent = args.optInt("percent", -1).let { if (it < 0) Math.round(current * 100f / 255) else it.coerceIn(1, 100) }
        val level = (percent / 100f * 255).toInt().coerceIn(1, 255)

        if (!canWrite) {
            if (ctx.hasShell()) {
                val r = ctx.shell(
                    "settings put system screen_brightness_mode ${if (auto) 1 else 0}; " +
                        "settings put system screen_brightness $level",
                )
                return if (r.ok) "已通过特权通道设置亮度 $percent%（自动亮度${if (auto) "开" else "关"}）"
                else "设置失败：${r.output.take(200)}"
            }
            return ToolPermissions.guidance(ToolPerm.WRITE_SETTINGS)
        }
        return runCatching {
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                if (auto) Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC else Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            if (!auto) Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
            "已设置亮度 $percent%，自动亮度${if (auto) "开启" else "关闭"}"
        }.getOrElse { "设置亮度失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private suspend fun setDisplay(ctx: ToolCtx, args: JSONObject): String {
        val feature = args.optString("feature").lowercase(Locale.ROOT)
        val value = args.optString("value").trim()
        if (feature.isBlank()) return "参数错误：缺少 feature"
        if (feature == "status") {
            if (!ctx.hasShell()) return "查询显示设置需要 ROOT 或 Shizuku 通道"
            val r = ctx.shell(
                "echo 深色模式:; cmd uimode night; " +
                    "echo 自动旋转:; settings get system accelerometer_rotation; " +
                    "echo 息屏时间ms:; settings get system screen_off_timeout; " +
                    "echo 峰值刷新率:; settings get system peak_refresh_rate; " +
                    "echo 分辨率:; wm size",
            )
            return r.output
        }
        if (feature == "resolution") {
            if (!ctx.hasShell()) return "改分辨率需要 ROOT 或 Shizuku 通道"
            if (value.isBlank()) return "参数错误：resolution 需要 value=宽x高 或 reset"
            if (!ctx.ask("修改屏幕分辨率：$value\n可能导致显示异常，确认执行？")) return "用户拒绝了改分辨率"
            val safe = if (value == "reset") "wm size reset" else "wm size ${shqExt(value)}"
            val r = ctx.shell(safe)
            return "分辨率命令已执行：\n${r.output}"
        }
        if (!ctx.hasShell()) return "这个操作需要 ROOT 或 Shizuku 通道（当前没有可用通道）"
        val cmd = when (feature) {
            "dark_mode" -> {
                val v = if (value in setOf("on", "off", "auto")) value else return "dark_mode 取值需要 on/off/auto"
                "cmd uimode night $v"
            }
            "auto_rotate" -> {
                val on = value == "on" || value == "true" || value == "1"
                "settings put system accelerometer_rotation ${if (on) 1 else 0}"
            }
            "screen_timeout" -> {
                val seconds = value.toIntOrNull() ?: return "screen_timeout 需要秒数，例如 30"
                "settings put system screen_off_timeout ${seconds * 1000}"
            }
            "stay_awake" -> {
                val on = value == "on" || value == "true" || value == "1"
                "svc power stayon ${if (on) "true" else "false"}"
            }
            "refresh_rate" -> {
                if (value.isBlank()) return "refresh_rate 需要 Hz 数值，例如 120"
                if (value.toFloatOrNull() == null) return "refresh_rate 需要数字"
                "settings put system peak_refresh_rate $value"
            }
            else -> return "未知 feature：$feature"
        }
        val r = ctx.shell(cmd)
        return if (r.ok) "已执行：$feature = $value\n${r.output}" else "执行失败：${r.output.take(300)}"
    }

    private fun dnd(ctx: ToolCtx, args: JSONObject): String {
        val nm = ctx.context.getSystemService(NotificationManager::class.java)
            ?: return "读取不到通知服务"
        if (!nm.isNotificationPolicyAccessGranted) {
            return ToolPermissions.guidance(ToolPerm.DND) + "（系统不允许 App 直接申请该权限，必须用户手动开启）"
        }
        val filter = args.optString("filter").ifBlank { "get" }.lowercase(Locale.ROOT)
        val currentText = when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "全部通知（勿扰关闭）"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "仅优先/重要"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "完全静音（勿扰开启）"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "仅闹钟"
            else -> "未知"
        }
        if (filter == "get") return "当前通知过滤：$currentText"
        val target = when (filter) {
            "all", "off" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "priority" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "none", "on" -> NotificationManager.INTERRUPTION_FILTER_NONE
            "alarms" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            else -> return "未知 filter：$filter"
        }
        return runCatching {
            nm.setInterruptionFilter(target)
            "已切换通知过滤，原状态：$currentText"
        }.getOrElse { "切换失败：${it.message ?: it.javaClass.simpleName}" }
    }

    private fun systemToggle(ctx: ToolCtx, args: JSONObject): String {
        val target = args.optString("target").lowercase(Locale.ROOT)
        val action = args.optString("action").ifBlank { "status" }.lowercase(Locale.ROOT)
        if (target.isBlank()) return "参数错误：缺少 target"
        if (!ctx.hasShell()) {
            return "Android 10+ 普通应用无权开关 $target，需要先在 设置 → 特权通道 里启用 ROOT / Shizuku 通道"
        }
        val (cmd, statusCmd) = when (target) {
            "wifi" -> when (action) {
                "on" -> "svc wifi enable" to "settings get global wifi_on"
                "off" -> "svc wifi disable" to "settings get global wifi_on"
                else -> "settings get global wifi_on" to "settings get global wifi_on"
            }
            "data" -> when (action) {
                "on" -> "svc data enable" to "settings get global mobile_data"
                "off" -> "svc data disable" to "settings get global mobile_data"
                else -> "settings get global mobile_data" to "settings get global mobile_data"
            }
            "bluetooth" -> when (action) {
                "on" -> "cmd bluetooth_manager enable || svc bluetooth enable" to "settings get global bluetooth_on"
                "off" -> "cmd bluetooth_manager disable || svc bluetooth disable" to "settings get global bluetooth_on"
                else -> "settings get global bluetooth_on" to "settings get global bluetooth_on"
            }
            "airplane" -> when (action) {
                "on" -> "cmd connectivity airplane-mode enable" to "settings get global airplane_mode_on"
                "off" -> "cmd connectivity airplane-mode disable" to "settings get global airplane_mode_on"
                else -> "settings get global airplane_mode_on" to "settings get global airplane_mode_on"
            }
            "location" -> when (action) {
                "on" -> "cmd location set-location-enabled true" to "settings get secure location_mode"
                "off" -> "cmd location set-location-enabled false" to "settings get secure location_mode"
                else -> "settings get secure location_mode" to "settings get secure location_mode"
            }
            else -> return "未知 target：$target（可用 wifi / data / bluetooth / airplane / location）"
        }
        val r = ctx.shell(cmd)
        val status = ctx.shell(statusCmd).output.replace(Regex("\\[exit=\\d+]"), "").trim()
        val stateText = when (target) {
            "location" -> when (status) {
                "0" -> "已关闭"
                "1", "2", "3" -> "已开启"
                else -> status
            }
            else -> status
        }
        return if (action == "status") "$target 状态：$stateText" else
            "已请求 $target $action\n状态：$stateText\n${r.output.take(300)}"
    }

    private fun speak(ctx: ToolCtx, args: JSONObject): String {
        val text = args.optString("text").trim()
        if (text.isBlank()) return "参数错误：缺少 text"
        return Speaker.speak(ctx.context, text)
    }

    private fun sensors(ctx: ToolCtx, args: JSONObject): String {
        val type = args.optString("type").ifBlank { "compass" }.lowercase(Locale.ROOT)
        val sm = ctx.context.getSystemService(SensorManager::class.java)
            ?: return "读取不到传感器服务"
        return when (type) {
            "light" -> readSensor(sm, Sensor.TYPE_LIGHT)?.let { "环境光：${"%.1f".format(it)} lx" }
                ?: "没有环境光传感器"
            "proximity" -> readSensor(sm, Sensor.TYPE_PROXIMITY)?.let { "距离：${"%.1f".format(it)} cm" }
                ?: "没有距离传感器"
            "compass" -> compass(sm)
            else -> "未知 type：$type"
        }
    }

    private fun readSensor(sm: SensorManager, type: Int): Float? {
        val sensor = sm.getDefaultSensor(type) ?: return null
        var value: Float? = null
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                value = event.values.firstOrNull()
                latch.countDown()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        runCatching {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper()))
            latch.await(1_500, TimeUnit.MILLISECONDS)
            sm.unregisterListener(listener)
        }
        return value
    }

    private fun compass(sm: SensorManager): String {
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return "没有加速度计，无法判断方向"
        val magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) ?: return "没有磁力计，无法判断方向"
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var ready = false
        val latch = CountDownLatch(1)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> System.arraycopy(event.values, 0, gravity, 0, 3)
                    Sensor.TYPE_MAGNETIC_FIELD -> System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                }
                if (gravity.any { it != 0f } && geomagnetic.any { it != 0f }) {
                    ready = true
                    latch.countDown()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        runCatching {
            sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI, Handler(Looper.getMainLooper()))
            sm.registerListener(listener, magnet, SensorManager.SENSOR_DELAY_UI, Handler(Looper.getMainLooper()))
            latch.await(1_800, TimeUnit.MILLISECONDS)
            sm.unregisterListener(listener)
        }
        val rotation = FloatArray(9)
        if (!ready || !SensorManager.getRotationMatrix(rotation, null, gravity, geomagnetic)) {
            return "暂时读不到方向（设备可能没有磁力计，或环境磁场受干扰）"
        }
        val orientation = FloatArray(3)
        SensorManager.getOrientation(rotation, orientation)
        val azimuth = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
        val directions = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        val dir = directions[((azimuth + 22.5f) / 45f).toInt() % 8]
        return "指南针：${"%.0f".format(azimuth)}°（$dir）"
    }
}

/** 朗读：复用同一个 TTS 引擎，避免每次调用都重新初始化。 */
object Speaker {
    private var engine: TextToSpeech? = null

    fun speak(context: Context, text: String): String = runCatching {
        val current = engine
        if (current != null) {
            current.speak(text, TextToSpeech.QUEUE_FLUSH, null, "la-agent")
            return "正在朗读（${text.length} 字）"
        }
        val tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine?.language = Locale.CHINA
            }
        }
        engine = tts
        tts.language = Locale.CHINA
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "la-agent")
        "正在朗读（${text.length} 字）"
    }.getOrElse { "朗读失败：${it.message ?: it.javaClass.simpleName}" }
}

/** 打开链接、设置页、应用详情、系统分享等 Intent 类工具。 */
object IntentTools : ExtToolGroup {

    override val group = ToolGroup.DEVICE

    private val names = setOf("open_uri", "open_settings", "app_details", "share_text")

    override fun handles(name: String): Boolean = name in names

    override fun specs(config: AgentConfig, out: JSONArray) {
        appendTool(
            out,
            "open_uri",
            "用系统应用打开一个链接或协议：https、tel、mailto、geo、market、sms 等（例如打开网页、发邮件、跳到应用市场）。",
            toolProps("uri" to toolProp("string", "完整 URI，例如 https://…、mailto:a@b.com、market://details?id=…")),
            listOf("uri"),
        )
        appendTool(
            out,
            "open_settings",
            "直接打系统设置页。",
            toolProps(
                "page" to toolProp(
                    "string",
                    "wifi / bluetooth / location / display / sound / battery / apps / notification / accessibility / usage_access / notification_listener / write_settings / dnd / alarm / date / storage / security / home / dev / nfc / cast / airplanemode",
                ),
                "package" to toolProp("string", "可选，page=app 时打开该应用的详情页"),
            ),
            listOf("page"),
        )
        appendTool(
            out,
            "app_details",
            "打开指定应用的信息页（可看权限、清理缓存、强制停止）。",
            toolProps("package" to toolProp("string", "应用包名")),
            listOf("package"),
        )
        appendTool(
            out,
            "share_text",
            "调起系统分享面板，把一段文字分享到微信、笔记等应用。",
            toolProps(
                "text" to toolProp("string", "要分享的文字"),
                "title" to toolProp("string", "可选，分享面板标题"),
            ),
            listOf("text"),
        )
    }

    override suspend fun execute(ctx: ToolCtx, name: String, args: JSONObject): String = when (name) {
        "open_uri" -> openUri(ctx, args)
        "open_settings" -> openSettings(ctx, args)
        "app_details" -> appDetails(ctx, args)
        "share_text" -> shareText(ctx, args)
        else -> "未知工具：$name"
    }

    private fun openUri(ctx: ToolCtx, args: JSONObject): String {
        val text = args.optString("uri").trim()
        if (text.isBlank()) return "参数错误：缺少 uri"
        val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return "URI 格式错误：$text"
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        val allowed = setOf("http", "https", "tel", "mailto", "geo", "market", "sms", "smsto", "mms")
        if (scheme !in allowed) return "不支持的协议：$scheme（允许：${allowed.joinToString(" / ")}）"
        return launchIntent(ctx, Intent(Intent.ACTION_VIEW, uri), "打开 $text")
    }

    private fun openSettings(ctx: ToolCtx, args: JSONObject): String {
        val page = args.optString("page").lowercase(Locale.ROOT)
        val pkg = args.optString("package")
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "app" -> return appDetails(ctx, args)
            "notification", "notifications" -> "android.settings.NOTIFICATION_SETTINGS"
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "usage_access" -> Settings.ACTION_USAGE_ACCESS_SETTINGS
            "notification_listener" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            "write_settings" -> Settings.ACTION_MANAGE_WRITE_SETTINGS
            "dnd" -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            "alarm", "clock" -> android.provider.AlarmClock.ACTION_SHOW_ALARMS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "security" -> Settings.ACTION_SECURITY_SETTINGS
            "home" -> Settings.ACTION_HOME_SETTINGS
            "dev", "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "cast" -> Settings.ACTION_CAST_SETTINGS
            "airplanemode" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "privacy" -> Settings.ACTION_PRIVACY_SETTINGS
            else -> return "未知设置页：$page"
        }
        val intent = Intent(action)
        if (pkg.isNotBlank() && page == "app") {
            intent.data = Uri.parse("package:$pkg")
        }
        return launchIntent(ctx, intent, "打开设置页（$page）")
    }

    private fun appDetails(ctx: ToolCtx, args: JSONObject): String {
        val pkg = args.optString("package").trim()
        if (pkg.isBlank()) return "参数错误：缺少 package"
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        return launchIntent(ctx, intent, "打开 $pkg 的应用信息页")
    }

    private fun shareText(ctx: ToolCtx, args: JSONObject): String {
        val text = args.optString("text")
        if (text.isBlank()) return "参数错误：缺少 text"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, args.optString("title").ifBlank { "分享" })
        return launchIntent(ctx, chooser, "调起分享面板")
    }
}
