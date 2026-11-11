package com.latools.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import androidx.core.animation.doOnEnd
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * AI 手机操控会话。
 *
 * 一个会话的生命周期：
 *
 * ```text
 * 用户确认「允许 AI 操控」→ start() → 四周亮起霓虹边框 → AI 调用 tap/swipe/screenshot
 *   → 结束（AI 调 stop / 用户连按两下音量上 / 无障碍断开）
 * ```
 *
 * 边框是**可见的授权提示**：用户任何时候低头都能看到"AI 正在操作我的手机"，
 * 而不是后台偷偷点。这也是为什么它必须有，而不是可选项。
 */
object PhoneControl {

    @Volatile
    var isActive: Boolean = false
        private set

    private var service: AgentAccessibilityService? = null
    private var overlay: View? = null
    private var windowManager: WindowManager? = null

    fun onServiceReady(service: AgentAccessibilityService) {
        this.service = service
    }

    fun onServiceGone() {
        service = null
        if (isActive) stopByUser("无障碍服务断开")
    }

    fun canControl(): Boolean = service != null

    fun start(context: Context): Boolean {
        val svc = service ?: return false
        if (isActive) return true
        isActive = true
        showBorder(context, svc)
        return true
    }

    /** 结束操控并撤掉霓虹边框。 */
    fun stop(context: Context? = null) {
        isActive = false
        hideBorder()
    }

    /** 用户主动中止（音量键 / 通知 / 界面按钮）。 */
    fun stopByUser(reason: String) {
        if (!isActive) return
        stop()
        AgentAccessibilityService.toast("AI 操控已停止（$reason）")
    }

    /**
     * AI 点击反馈：在 (x, y) 处闪一圈蓝色渐变光晕再淡出。
     *
     * 为什么需要：用户授权的是"AI 操作手机"，但他看不见 AI 到底点了哪。
     * 每次点击给一个短暂的可视反馈，用户才能判断"它是不是点歪了/点错了"，
     * 也才有依据决定要不要立刻按音量键中止。
     */
    fun showTap(context: Context, x: Float, y: Float) {
        if (!isActive) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val sizePx = (56 * context.resources.displayMetrics.density).toInt()
        val view = TapRippleView(context)
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - sizePx / 2f).toInt()
            this.y = (y - sizePx / 2f).toInt()
        }
        runCatching {
            wm.addView(view, params)
            view.start {
                runCatching { wm.removeView(view) }
            }
        }
    }

    // ------------------------------------------------------------ 霓虹边框

    private fun showBorder(context: Context, svc: AgentAccessibilityService) {
        if (overlay != null) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = NeonBorderView(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching {
            wm.addView(view, params)
            overlay = view
            windowManager = wm
        }
    }

    private fun hideBorder() {
        overlay?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlay = null
        windowManager = null
    }

    /** 单次点击的蓝色渐变光晕：扩散 + 淡出，约 600ms 后自毁。 */
    private class TapRippleView(context: Context) : View(context) {
        private var progress = 0f
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            color = Color.parseColor("#40C4FF")
        }

        fun start(onEnd: () -> Unit) {
            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 620L
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                doOnEnd { onEnd() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val maxR = minOf(cx, cy)
            val alpha = ((1f - progress) * 255).toInt().coerceIn(0, 255)
            // 径向蓝色渐变填充
            val shader = android.graphics.RadialGradient(
                cx, cy, maxR * (0.35f + 0.65f * progress),
                intArrayOf(
                    Color.argb(alpha, 64, 196, 255),
                    Color.argb((alpha * 0.55f).toInt(), 41, 121, 255),
                    Color.argb(0, 41, 121, 255),
                ),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
            core.shader = shader
            canvas.drawCircle(cx, cy, maxR * (0.35f + 0.65f * progress), core)
            ring.alpha = (alpha * 0.9f).toInt()
            canvas.drawCircle(cx, cy, maxR * (0.35f + 0.65f * progress), ring)
        }

        private fun dp(v: Float) = v * resources.displayMetrics.density
    }

    /** 四周霓虹光带：外发光 + 锐利内边，颜色沿线缓慢流动。 */
    private class NeonBorderView(context: Context) : View(context) {

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(18f)
            color = Color.parseColor("#7C4DFF")
            alpha = 60
            maskFilter = android.graphics.BlurMaskFilter(dp(16f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(6f)
            color = Color.parseColor("#00E5FF")
            alpha = 150
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            color = Color.WHITE
            alpha = 210
        }
        private val rect = RectF()
        private var phase = 0f

        init {
            // 呼吸/流动：只改 alpha 与色相，不重建图层
            val animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2600L
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = dp(6f)
            rect.set(inset, inset, width - inset, height - inset)
            val radius = dp(28f)
            val pulse = 0.55f + 0.45f * phase
            glowPaint.alpha = (40 + 70 * pulse).toInt()
            midPaint.alpha = (90 + 90 * pulse).toInt()
            // 四段渐变色：紫 → 青 → 蓝 → 品红
            val colors = intArrayOf(
                Color.parseColor("#7C4DFF"),
                Color.parseColor("#00E5FF"),
                Color.parseColor("#2979FF"),
                Color.parseColor("#FF4081"),
            )
            for (i in colors.indices) {
                val shift = (i + phase).toInt() % colors.size
                midPaint.color = colors[shift]
                val t = i * 0.12f
                rect.inset(-dp(2f) * t, -dp(2f) * t)
                canvas.drawRoundRect(rect, radius, radius, glowPaint)
                canvas.drawRoundRect(rect, radius, radius, midPaint)
            }
            rect.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(rect, radius, radius, corePaint)
        }

        private fun dp(v: Float): Float = v * resources.displayMetrics.density
    }
}
