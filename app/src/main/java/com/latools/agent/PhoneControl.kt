// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Celraw

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

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var service: AgentAccessibilityService? = null
    private var overlay: View? = null
    private var neonView: NeonBorderView? = null
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
        showBorder(svc)
        AgentGuardService.start(context)
        return true
    }

    /** 结束操控并撤掉霓虹边框。 */
    fun stop(context: Context? = null) {
        isActive = false
        hideBorder()
        context?.let { AgentGuardService.stop(it) }
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
    fun showTap(x: Float, y: Float) {
        if (!isActive) return
        mainHandler.post {
            /*
             * 关键：优先画在**霓虹边框那个 overlay 窗口**里。
             * 之前每次点击都 new 一个新窗口 + addView：
             *  · 用的是 applicationContext 的 WindowManager，TYPE_ACCESSIBILITY_OVERLAY 会被拒（BadToken）→ 光晕永远不出现
             *  · 每次点击新建/销毁窗口，开销大且在动画期间容易丢帧
             * 同一个 overlay 里画，既不会拿错 token，也没有额外窗口开销。
             */
            val view = neonView
            if (view != null) {
                view.addTap(x, y)
                return@post
            }
            // 兜底：理论上不会走到（showTap 只在操控中存在），留一条独立窗口路径
            val svc = service ?: return@post
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val sizePx = (56 * svc.resources.displayMetrics.density).toInt()
            val fallback = TapRippleView(svc)
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
                wm.addView(fallback, params)
                fallback.start {
                    mainHandler.post { runCatching { wm.removeView(fallback) } }
                }
            }
        }
    }

    // ------------------------------------------------------------ 霓虹边框

    private fun showBorder(svc: AgentAccessibilityService) {
        if (overlay != null) return
        // TYPE_ACCESSIBILITY_OVERLAY 必须用 AccessibilityService 的 window token，
        // 且 View 的创建/addView 都必须在主线程 —— 之前从工具执行线程直接 addView 会闪退。
        mainHandler.post {
            val wm = svc.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            val view = NeonBorderView(svc)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
            runCatching {
                wm.addView(view, params)
                overlay = view
                neonView = view
                windowManager = wm
            }
        }
    }

    private fun hideBorder() {
        mainHandler.post {
            overlay?.let { view -> runCatching { windowManager?.removeView(view) } }
            overlay = null
            neonView = null
            windowManager = null
        }
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

    /**
     * 四周霓虹光带：真正的「炫彩循环」。
     *
     * 之前是叠 4 个不同颜色的圆角矩形 + 只改 alpha，视觉上是静态的紫粉混色，
     * 不是用户要的流动彩虹。现在改用 [SweepGradient]：七色沿圆周均匀分布，
     * 再用 Matrix 每帧旋转 shader，颜色就会沿着屏幕边缘连续流动。
     *
     * 三层结构：
     *  · glow：粗描边 + BlurMaskFilter，做出外溢光晕
     *  · mid ：中等描边，鲜明的主体色带
     *  · core：细白描边，保证在浅色界面也看得出边界
     */
    private class NeonBorderView(context: Context) : View(context) {

        private val rainbow = intArrayOf(
            Color.parseColor("#FF1744"), // 红
            Color.parseColor("#FF9100"), // 橙
            Color.parseColor("#FFEA00"), // 黄
            Color.parseColor("#00E676"), // 绿
            Color.parseColor("#00E5FF"), // 青
            Color.parseColor("#2979FF"), // 蓝
            Color.parseColor("#D500F9"), // 紫
            Color.parseColor("#FF1744"), // 首尾同色，保证循环无缝
        )

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(20f)
            alpha = 105
            maskFilter = android.graphics.BlurMaskFilter(dp(18f), android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        private val midPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(5f)
            alpha = 215
        }
        private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            color = Color.WHITE
            alpha = 180
        }
        private val rect = RectF()
        private val shaderMatrix = android.graphics.Matrix()

        // 正在播放的点击光晕：(x, y, 开始时间)
        private val taps = mutableListOf<Triple<Float, Float, Long>>()
        private val tapFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val tapRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2f)
            color = Color.parseColor("#40C4FF")
        }

        /**
         * 追加一次点击光晕。
         *
         * 传入的是**屏幕坐标**（dispatchGesture 的坐标系），但画布坐标的原点是 View 左上角。
         * overlay 即使加了 FLAG_LAYOUT_IN_SCREEN，实际也不一定从 y=0 开始（部分 ROM 会把它
         * 排到状态栏下面），于是光球整体偏下。这里用 getLocationOnScreen 实测偏移再换算，
         * 不管系统怎么排都能对上。
         */
        fun addTap(screenX: Float, screenY: Float) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            taps += Triple(screenX - loc[0], screenY - loc[1], System.currentTimeMillis())
            invalidate()
        }
        private var phase = 0f
        private var shader: android.graphics.SweepGradient? = null

        init {
            // 线性匀速旋转：0→1 一圈，无缝衔接下一圈
            android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 3200L
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            shader = android.graphics.SweepGradient(
                w / 2f, h / 2f, rainbow, null,
            )
            glowPaint.shader = shader
            midPaint.shader = shader
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = shader ?: return
            val inset = dp(8f)
            rect.set(inset, inset, width - inset, height - inset)
            val radius = dp(30f)

            // 旋转 shader，让颜色沿边缘流动
            shaderMatrix.setRotate(phase * 360f, width / 2f, height / 2f)
            s.setLocalMatrix(shaderMatrix)

            canvas.drawRoundRect(rect, radius, radius, glowPaint)
            canvas.drawRoundRect(rect, radius, radius, midPaint)
            canvas.drawRoundRect(rect, radius, radius, corePaint)

            drawTaps(canvas)
        }

        /** 每个光晕 620ms：径向蓝色渐变扩散 + 描边，结束自动移除。 */
        private fun drawTaps(canvas: Canvas) {
            if (taps.isEmpty()) return
            val now = System.currentTimeMillis()
            val iterator = taps.iterator()
            while (iterator.hasNext()) {
                val (tx, ty, started) = iterator.next()
                val progress = ((now - started) / 620f).coerceIn(0f, 1f)
                if (progress >= 1f) {
                    iterator.remove()
                    continue
                }
                val maxR = dp(34f)
                val radiusPx = maxR * (0.35f + 0.65f * progress)
                val alpha = ((1f - progress) * 235).toInt().coerceIn(0, 255)
                tapFill.shader = android.graphics.RadialGradient(
                    tx, ty, radiusPx,
                    intArrayOf(
                        Color.argb(alpha, 64, 196, 255),
                        Color.argb((alpha * 0.55f).toInt(), 41, 121, 255),
                        Color.argb(0, 41, 121, 255),
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(tx, ty, radiusPx, tapFill)
                tapRing.alpha = (alpha * 0.9f).toInt()
                canvas.drawCircle(tx, ty, radiusPx, tapRing)
            }
            if (taps.isNotEmpty()) postInvalidateOnAnimation()
        }

        private fun dp(v: Float): Float = v * resources.displayMetrics.density

    }
}
