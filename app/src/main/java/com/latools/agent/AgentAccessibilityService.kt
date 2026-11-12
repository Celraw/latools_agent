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
        /*
         * 微信这类应用如果拿不到节点树，最常见原因不是权限，而是无障碍服务没开
         * FLAG_RETRIEVE_INTERACTIVE_WINDOWS —— 没有它 `windows` 恒为空、
         * rootInActiveWindow 也可能为 null，ui_dump 自然 0 个元素，
         * input_text 也就找不到 EditText。这里在运行时再强制补一次，防止 ROM 忽略 XML。
         */
        runCatching {
            val info = serviceInfo ?: return@runCatching
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            setServiceInfo(info)
        }
        PhoneControl.onServiceReady(this)
    }

    /** 每次窗口/内容变化 +1，用于"点击后有没有生效"的验证。 */
    @Volatile
    var eventSeq: Long = 0L
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> eventSeq += 1
        }
    }

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
        if (dispatched) PhoneControl.showTap(x, y)
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

    /** 当前活动窗口标题（很多应用会带页面名，如"微信"、聊天对象名），用于让模型确认自己在哪个页面。 */
    fun windowTitle(): String? = runCatching {
        windows.firstOrNull { it.isActive }?.title?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 当前前台应用包名（用于"点击前确认目标已在前台"）。 */
    fun foregroundPackage(): String? =
        runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /** 一次 ui_dump 的节点快照：文本 + 屏幕坐标 bounds。 */
    data class UiNode(
        val label: String,
        val className: String,
        val clickable: Boolean,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    @Volatile
    private var lastNodes: List<UiNode> = emptyList()

    /**
     * 遍历当前窗口的节点树，返回精简文本 + 缓存节点快照。
     *
     * 这是手机操控最可靠的一环：节点自带 bounds（屏幕坐标）和文本，
     * 不需要模型看图估坐标，误触率会明显下降；canvas 类界面节点为空时才退回截图。
     */
    fun dumpNodes(maxNodes: Int = 80): Pair<String, List<UiNode>> {
        val root = rootInActiveWindow ?: return "当前没有可用窗口（节点树为空）" to emptyList()
        val nodes = mutableListOf<UiNode>()
        val sb = StringBuilder()
        var index = 0
        try {
            val queue = ArrayDeque<Pair<android.view.accessibility.AccessibilityNodeInfo, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty() && nodes.size < maxNodes) {
                val (node, depth) = queue.removeFirst()
                if (depth > 14) continue
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val label = (text?.takeIf { it.isNotEmpty() } ?: desc?.takeIf { it.isNotEmpty() }).orEmpty()
                val cls = node.className?.toString()?.substringAfterLast('.') ?: "-"
                val clickable = node.isClickable || node.isCheckable || node.isEditable
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (node.isVisibleToUser && (label.isNotEmpty() || clickable) && rect.width() > 0) {
                    val shown = label.take(48)
                    nodes += UiNode(shown, cls, clickable, rect.left, rect.top, rect.right, rect.bottom)
                    sb.append('[').append(index).append("] ")
                        .append(cls)
                        .append(if (clickable) " 可点" else "")
                        .append(" \"").append(shown).append("\"")
                        .append(" bounds=(").append(rect.left).append(',').append(rect.top)
                        .append(")-(").append(rect.right).append(',').append(rect.bottom).append(")\n")
                    index++
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it to depth + 1) }
                }
            }
        } catch (_: Exception) {
            // 部分 ROM 在遍历中途会抛异常，已收集到的部分照常返回
        }
        lastNodes = nodes
        if (nodes.isEmpty()) return "节点树为空（可能是游戏/canvas 界面）" to emptyList()
        return sb.toString() to nodes
    }

    fun nodeCount(): Int = lastNodes.size

    /** 按索引点击上次 dump 的节点：先试 ACTION_CLICK，失败再用坐标手势。 */
    fun tapNode(index: Int, nodes: List<UiNode>): String {
        val node = nodes.getOrNull(index) ?: return "节点索引越界（共 ${nodes.size} 个）"
        val path = Path().apply { moveTo(node.centerX, node.centerY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val before = eventSeq
        val ok = try {
            dispatchGesture(gesture, null, null).also { dispatched ->
                if (dispatched) PhoneControl.showTap(node.centerX, node.centerY)
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) return "点击失败：无障碍拒绝执行手势"
        // 首次点击可能撞上页面动画/滚动惯性，700ms 内没变化就补点一次
        if (!awaitUiChange(before, 700)) {
            try {
                dispatchGesture(gesture, null, null)
                PhoneControl.showTap(node.centerX, node.centerY)
            } catch (_: Exception) { Unit }
        }
        return if (ok) {
            "已点击 [$index] \"${node.label}\" @(${node.centerX.toInt()}, ${node.centerY.toInt()})"
        } else {
            "点击失败：无障碍拒绝执行手势"
        }
    }

    /**
     * 把文本写进聊天输入框（等效粘贴）。
     *
     * 为什么微信会失败、QQ 却正常：
     * 微信的输入框是自绘控件，`isEditable` 有时为 false，而且键盘弹出时
     * `rootInActiveWindow` 可能指向**输入法窗口**而不是应用窗口 ——
     * 在输入法窗口里当然找不到可编辑节点。
     *
     * 所以这里不再只信 rootInActiveWindow，而是：
     *  1. 遍历所有窗口，优先应用窗口（跳过输入法窗口），收集候选可编辑节点；
     *  2. 对每个候选：先 ACTION_SET_TEXT，再回读校验；
     *  3. 仍失败则把文本放进剪贴板，对该节点 ACTION_PASTE；
     *  4. 还不行，才回退到全局 KEYCODE_PASTE。
     *
     * @return null 表示成功；非 null 是失败原因（含候选诊断信息）
     */
    fun setFocusedText(text: String): String? {
        val candidates = collectEditableCandidates()
        if (candidates.isEmpty()) {
            return "没有找到输入框。当前窗口：" + dumpWindowSummary() +
                "。请先点击聊天输入框让光标出现"
        }

        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val args = android.os.Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }

        val diagnostics = StringBuilder()
        for (node in candidates) {
            val cls = node.className?.toString() ?: "-"
            // 路径 A：直接 SET_TEXT
            if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                Thread.sleep(120)
                node.refresh()
                val now = node.text?.toString().orEmpty()
                if (now.isEmpty() || now.contains(text.take(8)) || text.isBlank()) return null
                diagnostics.append("SET_TEXT 返回成功但内容未变（$cls）；")
            } else {
                diagnostics.append("SET_TEXT 被拒绝（$cls）；")
            }

            // 路径 B：剪贴板 + ACTION_PASTE（微信这类自绘输入框通常只认粘贴）
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Agent", text))
            if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)) {
                Thread.sleep(180)
                node.refresh()
                val now = node.text?.toString().orEmpty()
                if (now.isEmpty() || now.contains(text.take(8))) return null
                diagnostics.append("ACTION_PASTE 后内容仍未变（$cls）；")
            } else {
                diagnostics.append("ACTION_PASTE 被拒绝（$cls）；")
            }
        }

        return "写入失败：$diagnostics 候选=" + candidates.joinToString(",") { it.className?.toString()?.substringAfterLast('.') ?: "-" }
    }

    /** 收集所有窗口里的可编辑候选，应用窗口优先，跳过输入法窗口。 */
    private fun collectEditableCandidates(): List<android.view.accessibility.AccessibilityNodeInfo> {
        val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        val ordered = runCatching {
            windows.sortedWith(
                compareByDescending<android.view.accessibility.AccessibilityWindowInfo> {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                }.thenByDescending { it.isActive },
            )
        }.getOrElse { emptyList() }

        for (window in ordered) {
            if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = runCatching { window.root }.getOrNull() ?: continue
            // 焦点节点优先
            root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ?.takeIf { isEditableNode(it) }
                ?.let { result += it }
            // 再遍历补漏（自绘输入框 isEditable 可能为 false，用类名/输入类型兜底）
            val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            queue.add(root)
            var guard = 0
            while (queue.isNotEmpty() && guard++ < 300) {
                val node = queue.removeFirst()
                if (isEditableNode(node) && result.none { it == node }) result += node
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }

    private fun isEditableNode(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser) return false
        if (node.isEditable) return true
        val cls = node.className?.toString().orEmpty().lowercase()
        // 微信等自绘输入框：类名里通常还有 EditText / Input
        if (cls.contains("edittext") || cls.contains("input")) return true
        // 最后放宽：可聚焦但不可点击的节点（自绘输入区常见特征）
        return node.isFocusable && !node.isClickable && node.text == null
    }

    private fun dumpWindowSummary(): String = runCatching {
        windows.joinToString(",") { w ->
            val pkg = w.root?.packageName?.toString() ?: "-"
            "type=${w.type}/$pkg"
        }
    }.getOrElse { "-" }

    /** 长按某点：用于唤起"粘贴"菜单做兜底。 */
    fun longPress(x: Float, y: Float, durationMs: Long = 700): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        return runCatching {
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }.getOrDefault(false)
    }

    /** 输入框候选的中心点（屏幕坐标），供长按粘贴兜底使用。 */
    fun inputCandidateCenters(): List<Pair<Float, Float>> = collectEditableCandidates().mapNotNull { node ->
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) (rect.exactCenterX() to rect.exactCenterY()) else null
    }

    /** 读取焦点输入框当前文本，用于写入后校验。 */
    fun focusedText(): String? = runCatching {
        val root = rootInActiveWindow ?: return@runCatching null
        val focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        focused?.text?.toString()
    }.getOrNull()

    /** 等界面安静下来（无事件）或超时，返回是否观察到变化。 */
    fun awaitUiChange(before: Long, timeoutMs: Long = 600): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (eventSeq != before) return true
            Thread.sleep(30)
        }
        return false
    }

    /** 屏幕真实像素尺寸，tap 坐标换算用。 */
    fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * 截屏，保持**原始分辨率**。
     *
     * 之前缩到最长边 1080，导致模型按截图像素给 tap 坐标、和真实屏幕对不上
     * （用户屏幕 1440x3200，截图 1080x2400，点击位置全部偏移）。
     * 视觉模型多花一点 token 换坐标准确，是值得的。
     */
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
                    onResult(bitmap)
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
