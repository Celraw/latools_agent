package com.latools.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * AI 手机操控的无障碍服务。
 *
 * 职责只有三件：
 *  1. 提供截图（[takeScreenBitmap]）给视觉模型看；
 *  2. 执行点击/滑动（[tap]/[swipe]）与返回/主页/最近任务；
 *  3. 监听音量上键连按两次 → 立即停止操控（用户随时能夺回控制权）。
 *
 * 霓虹边框由 [PhoneControl] 负责显示，本服务只负责能力。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        PhoneControl.onServiceReady(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        PhoneControl.onServiceGone()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        PhoneControl.onServiceGone()
        super.onDestroy()
    }

    /** 音量上键连按两次：停止操控。返回 false 表示不消费按键，系统音量行为照常。 */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP && event.action == KeyEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastVolumeUpAt in 1..650L && PhoneControl.isActive) {
                lastVolumeUpAt = 0
                PhoneControl.stopByUser("音量键连按")
                return false
            }
            lastVolumeUpAt = now
        }
        return false
    }

    // ------------------------------------------------------------ 能力

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(gesture, null, null)
        if (dispatched) PhoneControl.showTap(applicationContext, x, y)
        return dispatched
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(80, 2000))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun booleanGlobal(action: Int): Boolean = performGlobalAction(action)

    /** 截屏并缩放到最长边 1080、压缩为 JPEG，减少传图 token。 */
    fun takeScreenBitmap(onResult: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onResult(null); return
        }
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = runCatching {
                        val hb = result.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                        bmp
                    }.getOrNull()
                    onResult(bitmap?.let(::scaleDown))
                }

                override fun onFailure(errorCode: Int) {
                    onResult(null)
                }
            },
        )
    }

    private fun scaleDown(src: Bitmap): Bitmap {
        val maxSide = maxOf(src.width, src.height)
        if (maxSide <= 1080) return src
        val ratio = 1080f / maxSide
        val out = Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
        if (out !== src) src.recycle()
        return out
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        @Volatile
        private var lastVolumeUpAt = 0L

        fun isConnected(): Boolean = instance != null

        fun requestStopFromOutside() {
            if (PhoneControl.isActive) PhoneControl.stopByUser("外部请求")
        }

        fun toast(text: String) {
            instance?.let { Toast.makeText(it, text, Toast.LENGTH_SHORT).show() }
        }
    }
}
