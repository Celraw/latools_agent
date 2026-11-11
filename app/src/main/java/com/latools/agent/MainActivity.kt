package com.latools.agent

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import android.content.ClipData
import android.content.ClipboardManager
import java.util.UUID
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import org.json.JSONObject

// ------------------------------------------------------------ 设计令牌
private val Bg = Color(0xFFEAECEF)
private val Surface = Color(0xFFFFFFFF)
private val Surface2 = Color(0xFFF4F6F9)
private val Line = Color(0xFFDCE1E8)
private val TextMain = Color(0xFF22303C)
private val TextSub = Color(0xFF5D7285)
private val TextFaint = Color(0xFF8497A8)
private val Primary = Color(0xFF1976D2)
private val Danger = Color(0xFFC0322B)
private val Ok = Color(0xFF1B7F4B)
private val CodeBg = Color(0xFF12161D)
private val CodeFg = Color(0xFFD7E3F4)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AgentApp() }
    }
}

@Composable
private fun AgentApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf(AgentStore.config(context)) }
    var session by remember { mutableStateOf(AgentSession.create()) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var dangerCommand by remember { mutableStateOf<String?>(null) }
    var dangerDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var version by remember { mutableStateOf(0) }
    var channels by remember { mutableStateOf<List<AgentChannel>>(emptyList()) }
    var messageAction by remember { mutableStateOf<Pair<ChatMsg, Int>?>(null) }
    /*
     * 返回键只做「先关页面、再退出」；预测性返回的动画交给 AOSP 默认实现
     * （Manifest 里 enableOnBackInvokedCallback=true，系统会在滑动时给 Activity
     *  做缩放/位移预览，不需要也不应该自己再画一套）。
     */
    val overlayOpen = showSettings || showHistory
    BackHandler(enabled = overlayOpen) {
        if (showSettings) showSettings = false
        if (showHistory) showHistory = false
    }

    fun refreshChannels() {
        scope.launch { channels = withContext(Dispatchers.IO) { AgentShell.available(context) } }
    }
    LaunchedEffect(Unit) { refreshChannels() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            attachments = attachments + withContext(Dispatchers.IO) { uris.mapNotNull { readAttachment(context, it) } }
        }
    }

    fun send() {
        if (busy) return
        if (!config.ready) { showSettings = true; return }
        if (input.isBlank() && attachments.isEmpty()) return
        val images = attachments.filter { it.second.startsWith("data:image") }.map { it.second }
        val texts = attachments.filterNot { it.second.startsWith("data:image") }
        val body = buildString {
            append(input.trim())
            texts.forEach { (name, content) ->
                if (isNotEmpty()) append("\n\n")
                append("【附件：").append(name).append("】\n```\n").append(content.take(20_000)).append("\n```")
            }
        }
        input = ""; attachments = emptyList(); busy = true
        /*
         * 同步插入用户消息并立刻 version++：
         * launch 是"排队执行"，用户点完发送到真正进入协程之间会有一帧以上的空档，
         * 期间界面毫无变化，观感就是"点了没反应"。这里直接在主线程把消息写进会话。
         */
        session.messages.add(ChatMsg("user", body, images = images))
        session.updatedAt = System.currentTimeMillis()
        version++
        scope.launch {
            val engine = AgentEngine(
                context = context,
                config = config,
                confirmDanger = { cmd ->
                    val d = CompletableDeferred<Boolean>()
                    dangerCommand = cmd
                    dangerDeferred = d
                    d.await()
                },
                onUpdate = { version++ },
            )
            engine.send(session, body, images, userMessageAlreadyAdded = true)
            AgentStore.save(context, session)
            busy = false
            version++
        }
    }

    /*
     * 会话快照必须在 composable 作用域取：LazyColumn { } 的 content lambda 是
     * LazyListScope，不是 @Composable，里面不能调 remember。
     * 依赖 version 后，流式 delta 每段 version++ → 重建快照 → 气泡重绘。
     */
    /*
     * 快照要同时依赖 session.id：切换会话时 version 不会变，
     * 之前只依赖 version，导致"切过去还是旧会话的内容，直到发一条消息才刷新"。
     */
    val msgsSnapshot = remember(session.id, version) { session.messages.toList() }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                onBack = { (context as? ComponentActivity)?.finish() },
                onHistory = { showHistory = true },
                onSettings = { showSettings = true },
            )

            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val atBottom by remember {
                androidx.compose.runtime.derivedStateOf {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
                }
            }
            /*
             * 贴底滚动。
             *
             * 不能写成 LaunchedEffect(version) { ... delay(16) ... }：流式 delta 往往
             * 十几毫秒一段，key 每次变化都会取消上一个 effect，delay 永远走不完，
             * 表现就是"完全不自动滚动"。这里用一个常驻 effect 监听 version，
             * 并把高频更新 conflate 掉，保证每轮都能真正滚到底。
             */
            LaunchedEffect(session.id) {
                delay(30)
                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                if (lastIndex >= 0) listState.scrollToItem(lastIndex)
            }
            LaunchedEffect(listState) {
                snapshotFlow { version }
                    .conflate()
                    .collect {
                        if (atBottom) {
                            delay(24)
                            val lastIndex = listState.layoutInfo.totalItemsCount - 1
                            if (lastIndex >= 0) listState.scrollToItem(lastIndex)
                        }
                    }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val msgs = msgsSnapshot
                if (msgs.isEmpty()) item { EmptyHint(onSettings = { showSettings = true }) }
                items(msgs.size) { index ->
                    val msg = msgs[index]
                    when {
                        msg.role == "tool" -> Unit
                        msg.role == "assistant" -> Column(
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { messageAction = msg to index },
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            /*
                             * 顺序：思考 → 正文 → 工具调用。
                             * 之前正文在上、思考在下，读起来像"结论在前、过程在后"，
                             * 而实际对话里推理发生在回答之前，顺序应当与生成时序一致。
                             */
                            msg.reasoning?.takeIf { it.isNotBlank() }?.let { r ->
                                val key = "reason$index"
                                ReasoningCard(r, key in expanded) {
                                    expanded = if (key in expanded) expanded - key else expanded + key
                                }
                            }
                            msg.content?.takeIf { it.isNotBlank() }?.let { AssistantBubble(it) }
                            msg.toolCalls?.forEach { call ->
                                val output = msgs.drop(index + 1)
                                    .firstOrNull { it.role == "tool" && it.toolCallId == call.id }
                                    ?.content ?: "（等待执行）"
                                ToolCard(call, output, call.id in expanded) {
                                    expanded = if (call.id in expanded) expanded - call.id else expanded + call.id
                                }
                            }
                        }
                        else -> Box(
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { messageAction = msg to index },
                            ),
                        ) { UserBubble(msg) }
                    }
                }
                if (busy) item { TypingDots() }
                item { SseStatusLine() }
                item { Spacer(Modifier.height(2.dp)) }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = !atBottom,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp, bottom = 10.dp),
                enter = fadeIn(tween(150)),
                exit = fadeOut(tween(120)),
            ) {
                Box(
                    Modifier.size(38.dp).shadow(3.dp, CircleShape).background(Surface, CircleShape)
                        .clickable {
                            scope.launch {
                                val lastIndex = listState.layoutInfo.totalItemsCount - 1
                                if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        val w = size.width; val h = size.height
                        val path = Path().apply {
                            moveTo(w * 0.5f, h * 0.12f); lineTo(w * 0.5f, h * 0.78f)
                            moveTo(w * 0.16f, h * 0.46f); lineTo(w * 0.5f, h * 0.80f); lineTo(w * 0.84f, h * 0.46f)
                        }
                        drawPath(path, TextSub, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
            }
            }

            Composer(
                input = input,
                onInputChange = { input = it },
                attachments = attachments,
                busy = busy,
                onPickFile = { picker.launch(arrayOf("*/*")) },
                onSend = ::send,
            )
        }

        AnimatedVisibility(
            visible = showSettings,
            enter = slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(180)) { it / 3 } + fadeOut(tween(150)),
        ) {
            SettingsPanel(
                config = config,
                available = channels,
                onRefresh = { refreshChannels() },
                onRequest = { ch ->
                    scope.launch {
                        withContext(Dispatchers.IO) { requestChannel(context, ch) }
                        delay(600)
                        refreshChannels()
                    }
                },
                onDismiss = { showSettings = false },
                onSave = {
                    AgentStore.saveConfig(context, it)
                    config = it
                    showSettings = false
                },
            )
        }
        AnimatedVisibility(
            visible = showHistory,
            enter = slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(180)) { it / 3 } + fadeOut(tween(150)),
        ) {
            HistoryPanel(
                initial = AgentStore.list(context),
                onDismiss = { showHistory = false },
                onOpen = {
                    session = it
                    expanded = emptySet()
                    version++
                    showHistory = false
                },
                onNew = {
                    session = AgentSession.create()
                    expanded = emptySet()
                    input = ""
                    attachments = emptyList()
                    version++
                    showHistory = false
                },
                onDelete = { AgentStore.delete(context, it) },
            )
        }
        messageAction?.let { (msg, index) ->
            DialogShell(onDismiss = { messageAction = null }) {
                T("消息操作", 16.sp, TextMain, FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("复制文本", Modifier.weight(1f)) {
                        val text = buildString {
                            msg.reasoning?.takeIf { it.isNotBlank() }?.let { append("【思考】\n").append(it).append("\n\n") }
                            msg.content?.let { append(it) }
                        }.trim()
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Agent 消息", text))
                        messageAction = null
                        android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    Btn("从此分支", Modifier.weight(1f), primary = true) {
                        val now = System.currentTimeMillis()
                        val branched = AgentSession(
                            id = UUID.randomUUID().toString(),
                            title = "分支·${session.title}".take(28),
                            createdAt = now,
                            updatedAt = now,
                            messages = session.messages.take(index + 1).toMutableList(),
                        )
                        AgentStore.save(context, branched)
                        session = branched
                        expanded = emptySet()
                        version++
                        messageAction = null
                        showHistory = false
                    }
                }
                Spacer(Modifier.height(8.dp))
                Btn("取消", Modifier.fillMaxWidth()) { messageAction = null }
            }
        }
        dangerCommand?.let { cmd ->
            DialogShell(
                onDismiss = {
                    dangerDeferred?.complete(false); dangerDeferred = null; dangerCommand = null
                },
            ) {
                T("需要你确认", 16.sp, TextMain, FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                T("AI 请求执行下面的操作，请确认后才会继续。", 12.sp, TextSub)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(10.dp)).padding(12.dp)) {
                    T(cmd, 12.sp, TextMain, mono = true)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("拒绝", Modifier.weight(1f)) {
                        dangerDeferred?.complete(false); dangerDeferred = null; dangerCommand = null
                    }
                    Btn("允许执行", Modifier.weight(1f), primary = true) {
                        dangerDeferred?.complete(true); dangerDeferred = null; dangerCommand = null
                    }
                }
            }
        }
    }
}


@Composable
private fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    onAction: (() -> Unit)? = null,
    actionGlyph: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTap(onBack) { sz, tint ->
                drawLine(tint, Offset(sz.width * 0.62f, sz.height * 0.20f), Offset(sz.width * 0.28f, sz.height * 0.5f), 2.2.dp.toPx(), StrokeCap.Round)
                drawLine(tint, Offset(sz.width * 0.28f, sz.height * 0.5f), Offset(sz.width * 0.62f, sz.height * 0.80f), 2.2.dp.toPx(), StrokeCap.Round)
            }
            Spacer(Modifier.width(2.dp))
            T(title, 21.sp, TextMain, FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (onAction != null && actionGlyph != null) {
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onAction), contentAlignment = Alignment.Center) {
                    actionGlyph()
                }
            }
        }
        content()
    }
}

@Composable
private fun WhiteCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp))
            .background(Surface, RoundedCornerShape(14.dp)).padding(14.dp),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.height(14.dp))
    T(text, 13.sp, TextSub, FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
}

/** mockup 里的「关/低/中/高」分段控件：外灰底 + 选中白底。 */
@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val count = options.size
    val idx = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface2).padding(4.dp)) {
        val gap = 4.dp
        val itemWidth = (maxWidth - gap * (count - 1)) / count
        val targetX = (itemWidth + gap) * idx
        val x by animateDpAsState(targetX, tween(220), label = "segIndicator")
        Box(
            Modifier.offset(x = x).width(itemWidth).height(34.dp)
                .shadow(1.dp, RoundedCornerShape(8.dp))
                .background(Surface, RoundedCornerShape(8.dp)),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEach { (value, label) ->
                val on = value == selected
                Box(
                    Modifier.weight(1f).height(34.dp).clickable { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    T(label, 12.sp, if (on) TextMain else TextSub, if (on) FontWeight.SemiBold else null)
                }
            }
        }
    }
}

// ------------------------------------------------------------ 自绘组件

@Composable
private fun T(
    text: String,
    size: TextUnit = 14.sp,
    color: Color = TextMain,
    weight: FontWeight? = null,
    mono: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text,
        style = TextStyle(
            fontSize = size,
            color = color,
            fontWeight = weight,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            lineHeight = (size.value * 1.55f).sp,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TopBar(onBack: () -> Unit, onHistory: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTap(onBack) { s, tint ->
            drawLine(tint, Offset(s.width * 0.62f, s.height * 0.20f), Offset(s.width * 0.28f, s.height * 0.5f), 2.2.dp.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(s.width * 0.28f, s.height * 0.5f), Offset(s.width * 0.62f, s.height * 0.80f), 2.2.dp.toPx(), StrokeCap.Round)
        }
        Spacer(Modifier.width(2.dp))
        T("Agent", 21.sp, TextMain, FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        IconTap(onHistory) { s, tint ->
            val cx = s.width / 2f; val cy = s.height / 2f; val r = s.minDimension * 0.32f
            drawCircle(tint, r, Offset(cx, cy), style = Stroke(2.dp.toPx()))
            drawLine(tint, Offset(cx, cy), Offset(cx, cy - r * 0.55f), 2.dp.toPx(), StrokeCap.Round)
            drawLine(tint, Offset(cx, cy), Offset(cx + r * 0.5f, cy), 2.dp.toPx(), StrokeCap.Round)
        }
        IconTap(onSettings) { s, tint ->
            val cx = s.width / 2f; val cy = s.height / 2f; val r = s.minDimension * 0.17f
            drawCircle(tint, r, Offset(cx, cy), style = Stroke(1.8.dp.toPx()))
            for (i in 0 until 8) {
                val a = Math.toRadians((i * 45).toDouble())
                val cos = Math.cos(a).toFloat(); val sin = Math.sin(a).toFloat()
                drawLine(
                    tint,
                    Offset(cx + cos * r * 1.35f, cy + sin * r * 1.35f),
                    Offset(cx + cos * r * 2.0f, cy + sin * r * 2.0f),
                    1.8.dp.toPx(), StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun IconTap(onClick: () -> Unit, draw: DrawScope.(Size, Color) -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) { draw(size, TextSub) }
    }
}

@Composable
private fun SseStatusLine() {
    val d = SseDiagnostics
    if (d.lastHttpStatus == 0 && d.lastError.isBlank()) return
    val text = when {
        d.lastError.isNotBlank() -> "接口异常：${d.lastError}"
        d.fallbackUsed -> "流式未生效（HTTP ${d.lastHttpStatus} · ${d.lastContentType} · delta 0），已改用整段返回"
        else -> "流式正常：HTTP ${d.lastHttpStatus} · delta ${d.lastDeltaCount}"
    }
    T(text, 11.sp, if (d.lastError.isNotBlank()) Danger else TextFaint)
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
        label = "phase",
    )
    Row(Modifier.padding(vertical = 2.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val on = phase.toInt() % 3 == i
            Box(
                Modifier.size(5.dp).background(
                    if (on) Primary else TextFaint.copy(alpha = 0.45f), CircleShape,
                ),
            )
            if (i < 2) Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(8.dp))
        T("思考中", 12.sp, TextFaint)
    }
}

@Composable
private fun EmptyHint(onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)).background(Surface, RoundedCornerShape(16.dp)).padding(16.dp)) {
        T("Agent 可以用自然语言操作这台手机", 15.sp, TextMain, FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        T("例如「看看现在电池温度」「把刷新率锁到 120」。命令通过勾选的特权通道执行，危险命令会先确认。", 12.sp, TextSub)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.clickable(onClick = onSettings)) { T("先配置接口 →", 12.sp, Primary) }
    }
}

@Composable
private fun AssistantBubble(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth(0.94f).animateContentSize(tween(120))
                .shadow(1.dp, RoundedCornerShape(16.dp))
                .background(Surface, RoundedCornerShape(16.dp)).padding(14.dp),
        ) { MarkdownContent(text) }
    }
}

// ------------------------------------------------------------ Markdown

private sealed interface MdBlock {
    data class Para(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Ordered(val index: Int, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** 轻量 Markdown 解析：只覆盖模型实际会用的语法，代码块 / 标题 / 列表 / 分隔线。 */
private fun parseMarkdown(source: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = source.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) blocks.add(MdBlock.Para(para.toString().trim()))
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flushPara()
                val lang = trimmed.removePrefix("```").trim()
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    buf.appendLine(lines[i]); i++
                }
                blocks.add(MdBlock.Code(lang, buf.toString().trimEnd()))
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> { flushPara(); blocks.add(MdBlock.Rule) }
            trimmed.startsWith(">") -> {
                flushPara()
                val buf = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    if (buf.isNotEmpty()) buf.append('\n')
                    buf.append(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                i--
                blocks.add(MdBlock.Quote(buf.toString()))
            }
            trimmed.startsWith("|") && i + 1 < lines.size && Regex("""^\|?[\s:|-]+\|?""").matches(lines[i + 1].trim()) -> {
                flushPara()
                fun cells(row: String) = row.trim().trim('|').split('|').map { it.trim() }
                val header = cells(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows.add(cells(lines[i])); i++
                }
                i--
                blocks.add(MdBlock.Table(header, rows))
            }
            trimmed.startsWith("#") -> {
                flushPara()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                blocks.add(MdBlock.Heading(level, trimmed.dropWhile { it == '#' }.trim()))
            }
            trimmed.startsWith("- [ ]") || trimmed.startsWith("- [x]") || trimmed.startsWith("- [X]") -> {
                flushPara()
                val checked = trimmed[3] == 'x' || trimmed[3] == 'X'
                blocks.add(MdBlock.Bullet((if (checked) "\u2611 " else "\u2610 ") + trimmed.drop(5).trim()))
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                flushPara(); blocks.add(MdBlock.Bullet(trimmed.drop(2).trim()))
            }
            Regex("""^\d+[.)]\s+""").containsMatchIn(trimmed) -> {
                flushPara()
                val n = trimmed.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                blocks.add(MdBlock.Ordered(n, trimmed.dropWhile { it.isDigit() }.drop(1).trim()))
            }
            trimmed.isEmpty() -> flushPara()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flushPara()
    return blocks
}

/** 行内样式：**粗体**、*斜体*、`代码`、[文字](链接)。 */
private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > 0) {
                    withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text.startsWith("***", i) -> {
                val end = text.indexOf("***", i + 3)
                if (end > 0) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 3, end))
                    }
                    i = end + 3
                } else { append(text[i]); i++ }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > 0) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }; i = end + 2 }
                else { append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > 0) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Surface2)) { append(text.substring(i + 1, end)) }; i = end + 1 }
                else { append(text[i]); i++ }
            }
            text.startsWith("[", i) -> {
                val close = text.indexOf("](", i)
                val endParen = if (close > 0) text.indexOf(')', close) else -1
                if (close > 0 && endParen > 0) {
                    withStyle(SpanStyle(color = Primary)) { append(text.substring(i + 1, close)) }
                    i = endParen + 1
                } else { append(text[i]); i++ }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }; i = end + 1 }
                else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
private fun MarkdownContent(text: String) {
    val blocks = remember(text) { parseMarkdown(text) }
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(CodeBg).padding(12.dp),
                ) { T(block.code, 11.sp, CodeFg, mono = true) }
                is MdBlock.Heading -> T(
                    block.text, when (block.level) { 1 -> 18.sp; 2 -> 16.sp; else -> 15.sp },
                    TextMain, FontWeight.Bold,
                )
                is MdBlock.Bullet -> Row(verticalAlignment = Alignment.Top) {
                    T("·", 14.sp, TextSub)
                    Spacer(Modifier.width(6.dp))
                    MarkdownInline(block.text)
                }
                is MdBlock.Ordered -> Row(verticalAlignment = Alignment.Top) {
                    T("${block.index}.", 13.sp, TextSub)
                    Spacer(Modifier.width(6.dp))
                    MarkdownInline(block.text)
                }
                MdBlock.Rule -> Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(3.dp).heightIn(min = 18.dp).background(Line, RoundedCornerShape(2.dp)))
                    Spacer(Modifier.width(10.dp))
                    MarkdownInline(block.text)
                }
                is MdBlock.Table -> MarkdownTable(block.header, block.rows)
                is MdBlock.Para -> MarkdownInline(block.text)
            }
        }
    }
}

@Composable
private fun MarkdownTable(header: List<String>, rows: List<List<String>>) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .border(1.dp, Line, RoundedCornerShape(10.dp)),
    ) {
        Row(Modifier.fillMaxWidth().background(Surface2).padding(vertical = 8.dp)) {
            header.forEach { cell ->
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { T(cell, 12.sp, TextMain, FontWeight.SemiBold) }
            }
        }
        rows.forEachIndexed { rowIndex, row ->
            if (rowIndex > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                row.forEach { cell ->
                    Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { MarkdownInline(cell) }
                }
            }
        }
    }
}

@Composable
private fun MarkdownInline(text: String) {
    val annotated = remember(text) { inlineMarkdown(text) }
    BasicText(
        annotated,
        style = TextStyle(fontSize = 14.sp, color = TextMain, lineHeight = 22.sp),
    )
}

@Composable
private fun UserBubble(msg: ChatMsg) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(Modifier.fillMaxWidth(0.92f).background(Primary, RoundedCornerShape(16.dp)).padding(14.dp)) {
            msg.content?.takeIf { it.isNotBlank() }?.let { T(it, 14.sp, Color.White) }
            if (msg.images.isNotEmpty()) T("附带 ${msg.images.size} 张图片", 11.sp, Color.White.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun ReasoningCard(reasoning: String, open: Boolean, onToggle: () -> Unit) {
    Column(Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp)).background(Surface, RoundedCornerShape(14.dp)).padding(13.dp)) {
        Box(Modifier.clickable(onClick = onToggle)) { T(if (open) "收起思考过程" else "查看思考过程", 12.sp, Primary) }
        AnimatedVisibility(open, enter = expandVertically(tween(180)) + fadeIn(tween(150)), exit = shrinkVertically(tween(150)) + fadeOut(tween(120))) {
            Column {
                Spacer(Modifier.height(8.dp))
                T(reasoning, 12.sp, TextSub)
            }
        }
    }
}

@Composable
private fun ToolCard(call: ToolCall, output: String, open: Boolean, onToggle: () -> Unit) {
    val command = remember(call.arguments, call.name) {
        runCatching {
            val obj = JSONObject(call.arguments)
            when (call.name) {
                "web_search" -> obj.optString("query")
                "fetch_url" -> obj.optString("url")
                "http_request" -> obj.optString("method").ifBlank { "GET" } + " " + obj.optString("url")
                "open_app" -> obj.optString("package")
                "clipboard_set" -> obj.optString("text").take(80)
                "read_file", "write_file" -> obj.optString("path")
                "list_packages" -> obj.optString("filter")
                "app_control" -> obj.optString("action") + " " + obj.optString("package")
                "clipboard_get", "screenshot", "get_device_info" -> ""
                else -> obj.optString("command")
            }
        }.getOrDefault("")
    }
    val rejected = output.contains("用户拒绝")
    Column(Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp)).background(Surface, RoundedCornerShape(14.dp)).clickable(onClick = onToggle)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(if (rejected) Danger else Ok, CircleShape))
            Spacer(Modifier.width(8.dp))
            T(call.name, 12.sp, TextMain, FontWeight.SemiBold, mono = true)
            Spacer(Modifier.weight(1f))
            T(if (rejected) "已拒绝" else "已执行", 11.sp, if (rejected) Danger else Ok)
            Spacer(Modifier.width(10.dp))
            T(if (open) "收起" else "展开", 11.sp, TextSub)
        }
        if (command.isNotBlank()) {
            Box(Modifier.fillMaxWidth().background(Surface2).padding(horizontal = 13.dp, vertical = 9.dp)) {
                T(command, 11.sp, TextMain, mono = true, maxLines = if (open) 6 else 1)
            }
        }
        AnimatedVisibility(open, enter = expandVertically(tween(200)) + fadeIn(tween(160)), exit = shrinkVertically(tween(160)) + fadeOut(tween(120))) {
            Box(Modifier.fillMaxWidth().background(CodeBg).padding(13.dp)) {
                T(output, 11.sp, CodeFg, mono = true)
            }
        }
    }
}

@Composable
private fun Composer(
    input: String,
    onInputChange: (String) -> Unit,
    attachments: List<Pair<String, String>>,
    busy: Boolean,
    onPickFile: () -> Unit,
    onSend: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (attachments.isNotEmpty()) {
            T("附件：" + attachments.joinToString("、") { it.first }.take(50), 11.sp, Primary)
            Spacer(Modifier.height(4.dp))
        }
        Row(
            Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(22.dp)).background(Surface, RoundedCornerShape(22.dp)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTap(onPickFile) { s, tint ->
                val w = s.width; val h = s.height
                val stroke = 1.9.dp.toPx()
                // 回形针：左侧竖线 + 底部半圆 + 右侧竖线 + 顶部小弯钩
                drawLine(tint, Offset(w * 0.34f, h * 0.30f), Offset(w * 0.34f, h * 0.62f), stroke, StrokeCap.Round)
                drawArc(
                    tint, 0f, 180f, false,
                    topLeft = Offset(w * 0.34f, h * 0.52f),
                    size = Size(w * 0.30f, h * 0.30f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                drawLine(tint, Offset(w * 0.64f, h * 0.66f), Offset(w * 0.64f, h * 0.36f), stroke, StrokeCap.Round)
                drawArc(
                    tint, 180f, 150f, false,
                    topLeft = Offset(w * 0.48f, h * 0.18f),
                    size = Size(w * 0.30f, h * 0.30f),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            BasicTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                textStyle = TextStyle(fontSize = 14.sp, color = TextMain),
                cursorBrush = SolidColor(Primary),
                maxLines = 4,
                decorationBox = { inner ->
                    if (input.isEmpty()) T("描述你要做的事…", 14.sp, TextFaint)
                    inner()
                },
            )
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Primary).clickable(enabled = !busy, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(18.dp)) {
                    val w = size.width; val h = size.height
                    val path = Path().apply {
                        moveTo(w * 0.06f, h * 0.5f); lineTo(w * 0.92f, h * 0.5f)
                        moveTo(w * 0.54f, h * 0.12f); lineTo(w * 0.92f, h * 0.5f); lineTo(w * 0.54f, h * 0.88f)
                    }
                    drawPath(path, Color.White, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
                }
            }
        }
    }
}

// ------------------------------------------------------------ 弹层

@Composable
private fun DialogShell(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x77000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth(0.92f).heightIn(max = 640.dp)
                .shadow(16.dp, RoundedCornerShape(20.dp))
                .background(Surface, RoundedCornerShape(20.dp))
                .padding(18.dp),
            content = content,
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    singleLine: Boolean = true,
    minLines: Int = 1,
    secret: Boolean = false,
) {
    // 密码类字段默认隐藏，点右侧小眼睛临时显示
    var revealed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        T(label, 11.sp, TextSub)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface2)
                .border(1.dp, Line, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    textStyle = TextStyle(fontSize = 13.sp, color = TextMain),
                    cursorBrush = SolidColor(Primary),
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = if (singleLine) 1 else 8,
                    visualTransformation = if (secret && !revealed) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    modifier = Modifier.weight(1f),
                )
                if (secret) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).clickable { revealed = !revealed },
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(Modifier.size(18.dp)) {
                            val w = size.width; val h = size.height
                            val stroke = 1.6.dp.toPx()
                            // 眼睛轮廓：两段对称弧
                            val path = Path().apply {
                                moveTo(w * 0.10f, h * 0.5f)
                                quadraticTo(w * 0.5f, h * 0.10f, w * 0.90f, h * 0.5f)
                                quadraticTo(w * 0.5f, h * 0.90f, w * 0.10f, h * 0.5f)
                            }
                            drawPath(path, TextSub, style = Stroke(stroke, cap = StrokeCap.Round))
                            drawCircle(TextSub, w * 0.13f, Offset(w * 0.5f, h * 0.5f))
                            if (!revealed) {
                                drawLine(TextSub, Offset(w * 0.18f, h * 0.84f), Offset(w * 0.82f, h * 0.16f), stroke, StrokeCap.Round)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Btn(label: String, modifier: Modifier = Modifier, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(90), label = "btnScale")
    Box(
        modifier.height(44.dp).graphicsLayer(scaleX = scale, scaleY = scale).clip(RoundedCornerShape(12.dp))
            .background(when { danger -> Danger; primary -> Primary; else -> Surface2 })
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { T(label, 14.sp, if (primary || danger) Color.White else TextMain, FontWeight.SemiBold) }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    // 0.3s 渐变：直接换色太生硬，通道只多三个，渐变能明显提升质感
    val bg by animateColorAsState(if (on) Primary else Surface2, tween(300), label = "chipBg")
    val borderColor by animateColorAsState(if (on) Primary else Line, tween(300), label = "chipBorder")
    val fg by animateColorAsState(if (on) Color.White else TextMain, tween(300), label = "chipFg")
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    ) { T(label, 12.sp, fg) }
}

@Composable
private fun SettingsPanel(
    config: AgentConfig,
    available: List<AgentChannel>,
    onRefresh: () -> Unit,
    onRequest: (AgentChannel) -> Unit,
    onDismiss: () -> Unit,
    onSave: (AgentConfig) -> Unit,
) {
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var model by remember { mutableStateOf(config.model) }
    var temperature by remember { mutableStateOf(config.temperature.toString()) }
    var limit by remember { mutableStateOf(config.contextLimitChars.toString()) }
    var effort by remember { mutableStateOf(config.reasoningEffort) }
    var prompt by remember { mutableStateOf(config.systemPrompt) }
    var channels by remember { mutableStateOf(config.channels) }
    var streamOut by remember { mutableStateOf(config.stream) }
    var tavilyKey by remember { mutableStateOf(config.tavilyKey) }
    var visionEnabled by remember { mutableStateOf(config.visionEnabled) }
    // 无障碍服务连接状态：每次进入设置页刷新一次
    var accessibilityOn by remember { mutableStateOf(AgentAccessibilityService.isConnected()) }
    LaunchedEffect(Unit) { accessibilityOn = AgentAccessibilityService.isConnected() }

    PageScaffold(title = "Agent 设置", onBack = onDismiss) {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 24.dp),
        ) {
            WhiteCard {
                Field("接口地址（OpenAI 兼容）", baseUrl, { baseUrl = it })
                Spacer(Modifier.height(12.dp))
                Field("API Key", apiKey, { apiKey = it }, secret = true)
                Spacer(Modifier.height(12.dp))
                Field("模型", model, { model = it })
            }

            SectionLabel("生成参数")
            WhiteCard {
                T("思考强度（reasoning_effort）", 12.sp, TextSub)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("none" to "关", "low" to "低", "medium" to "中", "high" to "高"), effort) { effort = it }
                Spacer(Modifier.height(8.dp))
                T("只有支持推理参数的模型会生效，其余模型会忽略该字段。", 11.sp, TextFaint)
                Spacer(Modifier.height(12.dp))
                T("流式输出", 12.sp, TextSub)
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("true" to "开启", "false" to "关闭"), streamOut.toString()) { streamOut = it == "true" }
                Spacer(Modifier.height(8.dp))
                T("开启后逐字显示回复；部分兼容接口不支持 SSE 时会退化成等整段返回。", 11.sp, TextFaint)
                Spacer(Modifier.height(12.dp))
                Field("温度（0 ~ 2）", temperature, { temperature = it })
                Spacer(Modifier.height(12.dp))
                Field("上下文压缩阈值（字符）", limit, { limit = it })
                Spacer(Modifier.height(8.dp))
                T("超过后自动把较早的消息压缩成摘要，避免超长被接口拒绝。", 11.sp, TextFaint)
            }

            SectionLabel("特权通道")
            WhiteCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("允许 AI 使用的通道（可多选）", 12.sp, TextSub)
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.clickable(onClick = onRefresh)) { T("刷新", 11.sp, Primary) }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AgentChannel.entries.forEach { ch ->
                        val usable = ch in available
                        Chip(if (usable) ch.label else "${ch.label}（点此授权）", usable && ch.id in channels) {
                            if (usable) channels = if (ch.id in channels) channels - ch.id else channels + ch.id
                            else onRequest(ch)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                T("执行时按 ROOT → Shizuku → Dhizuku 顺序尝试，命中第一个可用通道。", 11.sp, TextFaint)
            }

            SectionLabel("手机操控")
            WhiteCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("模型支持识图", 13.sp, TextMain, FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        T("关闭后不向模型开放截屏/点击/滑动工具", 11.sp, TextFaint)
                    }
                    Box(
                        Modifier.size(46.dp, 28.dp).clip(RoundedCornerShape(999.dp))
                            .background(if (visionEnabled) Primary else SurfaceHi)
                            .clickable { visionEnabled = !visionEnabled },
                        contentAlignment = if (visionEnabled) Alignment.CenterEnd else Alignment.CenterStart,
                    ) {
                        Box(Modifier.padding(horizontal = 3.dp).size(22.dp).background(Surface, CircleShape))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        T("无障碍服务", 13.sp, TextMain, FontWeight.SemiBold)
                        Spacer(Modifier.height(2.dp))
                        T(
                            if (accessibilityOn) "已连接，AI 可截屏与模拟点击" else "未连接，手机操控不可用",
                            11.sp, if (accessibilityOn) Ok else Danger,
                        )
                    }
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp)).background(Surface2)
                            .clickable {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                runCatching { context.startActivity(intent) }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) { T(if (accessibilityOn) "查看" else "去开启", 12.sp, Primary) }
                }
                Spacer(Modifier.height(10.dp))
                T(
                    "操控期间屏幕四周会出现霓虹边框。连按两次音量上键可立即停止；AI 也可以调用 phone_control_stop 主动结束。",
                    11.sp, TextFaint,
                )
            }

            SectionLabel("联网搜索")
            WhiteCard {
                Field("Tavily API Key", tavilyKey, { tavilyKey = it }, secret = true)
                Spacer(Modifier.height(8.dp))
                T("留空则不启用 web_search 工具。Tavily Key 在 tavily.com 注册后获取。", 11.sp, TextFaint)
            }

            SectionLabel("提示词")
            WhiteCard {
                Field("系统提示词（清空恢复内置）", prompt, { prompt = it }, singleLine = false, minLines = 5)
            }

            SectionLabel("安全")
            WhiteCard {
                T(
                    "命令以本机特权身份执行。Agent 只能调用内置的 run_shell / get_device_info 两个工具，无法访问网络接口或任意 App 私有数据。",
                    11.sp, TextFaint,
                )
            }

            Spacer(Modifier.height(20.dp))
            Btn("保存", Modifier.fillMaxWidth(), primary = true) {
                onSave(
                    config.copy(
                        baseUrl = baseUrl.trim(),
                        apiKey = apiKey.trim(),
                        model = model.trim(),
                        temperature = temperature.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.3,
                        reasoningEffort = effort,
                        contextLimitChars = limit.toIntOrNull()?.coerceIn(4_000, 200_000) ?: 24_000,
                        systemPrompt = prompt.trim().ifBlank { AgentStore.DEFAULT_SYSTEM_PROMPT },
                        channels = channels,
                        stream = streamOut,
                        tavilyKey = tavilyKey,
                        visionEnabled = visionEnabled,
                    ),
                )
            }
        }
    }
}

@Composable
private fun HistoryPanel(
    initial: List<AgentSession>,
    onDismiss: () -> Unit,
    onOpen: (AgentSession) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf(initial) }
    var pendingDelete by remember { mutableStateOf<AgentSession?>(null) }
    val filtered = AgentStore.search(sessions, query)

    Box(Modifier.fillMaxSize()) {
        PageScaffold(
            title = "历史会话",
            onBack = onDismiss,
            onAction = onNew,
            actionGlyph = {
                Canvas(Modifier.size(20.dp)) {
                    val w = size.width; val h = size.height
                    drawLine(TextSub, Offset(w / 2, h * 0.16f), Offset(w / 2, h * 0.84f), 2.dp.toPx(), StrokeCap.Round)
                    drawLine(TextSub, Offset(w * 0.16f, h / 2), Offset(w * 0.84f, h / 2), 2.dp.toPx(), StrokeCap.Round)
                }
            },
        ) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Row(
                    Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp))
                        .background(Surface, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Canvas(Modifier.size(16.dp)) {
                        val r = size.minDimension * 0.32f
                        drawCircle(TextFaint, r, Offset(size.width * 0.42f, size.height * 0.42f), style = Stroke(1.8.dp.toPx()))
                        drawLine(TextFaint, Offset(size.width * 0.62f, size.height * 0.62f), Offset(size.width * 0.86f, size.height * 0.86f), 1.8.dp.toPx(), StrokeCap.Round)
                    }
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 13.sp, color = TextMain),
                        cursorBrush = SolidColor(Primary),
                        singleLine = true,
                        decorationBox = { inner ->
                            if (query.isEmpty()) T("搜索标题或消息内容", 13.sp, TextFaint)
                            inner()
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(filtered.size) { i ->
                        val sessionItem = filtered[i]
                        Row(
                            Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp))
                                .background(Surface, RoundedCornerShape(14.dp))
                                .clickable { onOpen(sessionItem) }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                T(sessionItem.title, 14.sp, TextMain, FontWeight.SemiBold, maxLines = 1)
                                Spacer(Modifier.height(4.dp))
                                T("${sessionItem.messages.size} 条消息", 11.sp, TextFaint)
                            }
                            Box(
                                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                    .clickable { pendingDelete = sessionItem },
                                contentAlignment = Alignment.Center,
                            ) {
                                Canvas(Modifier.size(18.dp)) {
                                    val w = size.width; val h = size.height
                                    drawLine(Danger, Offset(w * 0.22f, h * 0.26f), Offset(w * 0.78f, h * 0.26f), 1.8.dp.toPx(), StrokeCap.Round)
                                    drawLine(Danger, Offset(w * 0.34f, h * 0.26f), Offset(w * 0.36f, h * 0.16f), 1.8.dp.toPx(), StrokeCap.Round)
                                    drawLine(Danger, Offset(w * 0.66f, h * 0.26f), Offset(w * 0.64f, h * 0.16f), 1.8.dp.toPx(), StrokeCap.Round)
                                    drawLine(Danger, Offset(w * 0.30f, h * 0.26f), Offset(w * 0.34f, h * 0.86f), 1.8.dp.toPx(), StrokeCap.Round)
                                    drawLine(Danger, Offset(w * 0.70f, h * 0.26f), Offset(w * 0.66f, h * 0.86f), 1.8.dp.toPx(), StrokeCap.Round)
                                    drawLine(Danger, Offset(w * 0.34f, h * 0.86f), Offset(w * 0.66f, h * 0.86f), 1.8.dp.toPx(), StrokeCap.Round)
                                }
                            }
                        }
                    }
                }
            }
        }

        pendingDelete?.let { target ->
            DialogShell(onDismiss = { pendingDelete = null }) {
                T("删除这个会话？", 16.sp, TextMain, FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                T("「${target.title}」将连同 ${target.messages.size} 条消息一起删除，无法恢复。", 12.sp, TextSub)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Btn("取消", Modifier.weight(1f)) { pendingDelete = null }
                    Btn("删除", Modifier.weight(1f), primary = true, danger = true) {
                        onDelete(target.id)
                        sessions = sessions.filterNot { it.id == target.id }
                        pendingDelete = null
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ 附件 / 授权

private fun readAttachment(context: android.content.Context, uri: Uri): Pair<String, String>? = runCatching {
    val mime = context.contentResolver.getType(uri).orEmpty()
    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "附件"
    if (mime.startsWith("image/")) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        if (bytes.size > 4 * 1024 * 1024) return null
        name to "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    } else {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return null
        name to text
    }
}.getOrNull()

private fun requestChannel(context: android.content.Context, channel: AgentChannel) {
    runCatching {
        when (channel) {
            AgentChannel.ROOT -> Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor(6, java.util.concurrent.TimeUnit.SECONDS)
            AgentChannel.SHIZUKU -> {
                if (rikka.shizuku.Shizuku.pingBinder()) rikka.shizuku.Shizuku.requestPermission(0x5A17)
                else android.widget.Toast.makeText(context, "Shizuku 未运行，请先启动", android.widget.Toast.LENGTH_SHORT).show()
            }
            AgentChannel.DHIZUKU -> {
                com.rosan.dhizuku.api.Dhizuku.init(context)
                android.widget.Toast.makeText(context, "请激活 Dhizuku 后再授权", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
