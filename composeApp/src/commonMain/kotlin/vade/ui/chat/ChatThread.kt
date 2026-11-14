package vade.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import vade.ui.skin.BtnStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.MessageView

/**
 * U1-b · 会话详情 — the message flow, the tool cards, the composer and the image preview.
 *
 * Layout rules from D92 / D88 + `research/ui-visual-language.md`:
 *  - the reading column is centred and **capped at 760 dp**; the assistant message is
 *    plain full-width text (no bubble, no border — §1.4 内容优先), the user bubble is
 *    right-aligned, ≤78 % wide, 20dp radius with a 6dp bottom-right tail;
 *  - message-to-message gap is 20, paragraph gap inside a message is 12 (§4);
 *  - attachments render as C-09 chips *outside* the bubble (D95/D96);
 *  - the composer sticks to the bottom and the thread scrolls above it, so IME / the
 *    floating capsule can never cover the send button (the Android inset story is U1-g).
 *
 * Every visual component used here is one of the frozen eleven. Three of them carry an
 * **optional slot added after the freeze** (all default to the old behaviour), which is
 * how this page gets rich markdown, tool meta rows and a disabled send button without
 * a 12th component:
 *  - `MessageBubble(body = …)` — rich body instead of one plain `Text`;
 *  - `ToolCallCard(meta = …)` — `duration_ms` / `batch_id` footer;
 *  - `InputBar(sendEnabled = …, maxLines = …)` — disabled send + 6-line growth.
 */
@Composable
fun ChatThread(
    state: ChatState,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val skin = LocalSkin.current
    val scroll = rememberScrollState()

    // Streaming: one chunk per frame-ish tick. `animate = false` freezes the state so the
    // screenshot loop captures a deterministic frame.
    LaunchedEffect(animate, state.isStreaming) {
        if (!animate) return@LaunchedEffect
        while (state.isStreaming) {
            delay(45)
            state.tick(chunk = 5)
        }
    }

    // Fake upload progress for the composer chips. Without this the chip sits at 「上传中 0%」
    // forever — `tickUploads` existed but only the tests ever drove it, so the state that is
    // supposed to be "real UI, not a hard-coded string" was frozen. Same `animate` gate as the
    // stream so a preset frame stays deterministic.
    LaunchedEffect(animate, state.staged.size) {
        if (!animate) return@LaunchedEffect
        while (state.tickUploads()) delay(160)
    }

    // Pin the thread to its newest row (FR-8.35: a streaming reply must stay visible).
    //
    // `scrollTo`, not `animateScrollTo`: every streaming tick re-keys this effect, and a
    // cancelled animation stops wherever it had got to, so the animated version never reaches
    // the bottom during a live stream and the last row ends up half-cut behind the composer.
    // A still frame (`animate = false`) deliberately keeps the reader where it is, so a preset
    // capture can show the top of the thread instead of only its tail.
    LaunchedEffect(state.items.size, state.messages.lastOrNull()?.bodyText?.length) {
        if (!animate) return@LaunchedEffect
        runCatching { scroll.scrollTo(scroll.maxValue) }
    }

    Box(modifier.fillMaxSize().background(skin.colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // 页面左右边距：手机 16 / 桌面 24（视觉规范 §4）。
                    val gutter = if (maxWidth >= 600.dp) 24.dp else 16.dp
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(horizontal = gutter, vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(
                            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                            // 消息之间 20dp（视觉规范 §4）。
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            state.items.forEach { item ->
                                when (item) {
                                    is ChatItem.Message -> MessageRow(state, item.message)
                                    is ChatItem.Tool -> ToolRow(state, item.call)
                                }
                            }
                            state.lastNotice?.let { notice ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                                    StatusPill(notice)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
            Composer(state)
        }

        // Fullscreen preview is an overlay of the page, not a platform dialog: the skin must
        // not decide windowing (same reasoning as ApprovalDialog in U1-a).
        state.preview?.let { image ->
            ImagePreview(image, onClose = state::closePreview)
        }
    }
}

// --- one message -------------------------------------------------------------

@Composable
private fun MessageRow(state: ChatState, message: ChatMessage) {
    val skin = LocalSkin.current
    val view: MessageView = message.toView()
    val isUser = message.role == ChatRole.User

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        // Queue badge (FR-8.36) sits on the user bubble it belongs to, not in a global list.
        val position = state.queuePosition(message.id)
        if (position > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                StatusPill("排队中 #$position · 取消", Tone.Accent)
                Spacer(Modifier.width(6.dp))
                BasicText(
                    text = "×",
                    style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                    modifier = Modifier.clickable {
                        state.queue.firstOrNull { it.messageId == message.id }?.let { state.cancelQueued(it.clientMsgId) }
                    },
                )
            }
        }

        // Attachments first, then the text bubble — the order the parts[] carry (FR-8.41).
        val attachments = message.attachments
        if (attachments.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            ) {
                attachments.forEach { part ->
                    val like = part.toAttachmentLike()
                    AttachmentRow(
                        like = like,
                        onOpen = { like.image?.let(state::openPreview) },
                        onRemove = null,
                        onRetry = null,
                    )
                }
            }
        }

        // One component, two chrome treatments: assistant = no bubble (§1.4), user = bubble.
        // `body` carries the markdown renderer so bold / code / fences survive the trip.
        skin.MessageBubble(
            message = view,
            role = message.role.toBubbleRole(),
            maxWidth = 760.dp,
            body = {
                MarkdownText(
                    source = message.bodyText,
                    color = if (message.state == MessageState.Failed) skin.colors.error else skin.colors.onSurface,
                )
            },
        )

        if (message.isStreaming) {
            Row(Modifier.padding(top = 4.dp)) {
                StatusPill("正在生成 · 已 ${message.bodyText.length} 字", Tone.Accent)
            }
        }
    }
}

/**
 * Chip + thumbnail as one unit: `AttachmentChip` owns name / size / state / ×, the page
 * owns the bitmap and the tap — both through the optional `leading` slot added after the
 * C-09 freeze (the frozen signature had no `onClick`; the chip row is the tap target).
 */
@Composable
private fun AttachmentRow(
    like: AttachmentLike,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    val skin = LocalSkin.current
    Box(Modifier.clip(RoundedCornerShape(skin.shapes.small)).clickable(enabled = like.image != null, onClick = onOpen)) {
        skin.AttachmentChip(
            attachment = like.view,
            onRetry = onRetry,
            onRemove = onRemove,
            leading = like.image?.let { { MockImage(Modifier.fillMaxSize()) } },
        )
    }
}

// --- one tool card -----------------------------------------------------------

@Composable
private fun ToolRow(state: ChatState, call: ToolCall) {
    val skin = LocalSkin.current
    val cardState = state.cardState(call)
    val collapsible = toolCardCollapsible(call)
    val expanded = cardState != vade.ui.skin.ToolCardState.Collapsed

    Column(Modifier.fillMaxWidth()) {
        skin.ToolCallCard(
            call = call.toView(),
            state = cardState,
            onToggle = { if (collapsible) state.toggleToolCard(call.id) },
            // The frozen view has no `duration_ms` / `batch_id` / `error_code` fields, and
            // FR-8.51 says a missing field is *omitted*, never rendered as a placeholder —
            // so the rows come from `toolMetaRows` and simply disappear when the event lacked
            // them. They live *inside* the card (the meta slot), not as a floating stripe.
            meta = if (!expanded) {
                null
            } else {
                {
                    val rows = toolMetaRows(call, state.batchSize(call.batchId))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        rows.forEach { meta ->
                            BasicText(
                                text = "${meta.label} · ${meta.value}",
                                style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                            )
                        }
                        if (!collapsible) {
                            BasicText(
                                text = "risk=high · 审批卡永远展开（D94）",
                                style = skin.typography.caption.copy(color = skin.colors.warning),
                            )
                        }
                    }
                }
            },
        )
    }
}

// --- composer ----------------------------------------------------------------

@Composable
private fun Composer(state: ChatState) {
    val skin = LocalSkin.current

    Column(Modifier.fillMaxWidth().background(skin.colors.surface)) {
        // 1px 发丝线代替阴影（视觉规范 §1.2）。
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.hairline))

        // 队列徽标 (FR-8.35): one line, cancellable, and it disappears the moment it empties.
        if (state.queue.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill("${state.queue.size} 条排队", Tone.Accent)
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = state.queue.mapIndexed { i, q -> "#${i + 1} ${q.text.take(12)}" }.joinToString(" · "),
                    style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                    modifier = Modifier.weight(1f),
                )
                BasicText(
                    text = "全部取消",
                    style = skin.typography.caption.copy(
                        color = skin.colors.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                    modifier = Modifier.clickable {
                        state.queue.map { it.clientMsgId }.forEach { state.cancelQueued(it) }
                    },
                )
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                // ⌘Enter / Ctrl+Enter sends; plain Enter stays a newline (multi-line composer).
                // The preview pass on this ancestor sees the key before the focused field does,
                // so returning true stops the newline from being inserted.
                .onPreviewKeyEvent { event -> handleComposerKeys(event, state) },
        ) {
            skin.InputBar(
                value = state.draft,
                onValueChange = state::updateDraft,
                mode = state.inputMode,
                onSend = { state.submit() },
                onStop = { state.stop() },
                enabled = true,
                // D97: desktop has no on-device STT in this slice → draw nothing at all.
                voiceAvailable = false,
                // 空文本不可发送：按钮走禁用态（textMuted @30%），页面不再用一句 hint 顶替它。
                sendEnabled = state.canSend || state.isStreaming,
                maxLines = 6,
                attachments = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.staged.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                state.staged.take(2).forEach { staged ->
                                    AttachmentRow(
                                        like = AttachmentLike(staged.attachment, (staged.part as? ContentPart.Image)),
                                        onOpen = {
                                            (staged.part as? ContentPart.Image)?.let(state::openPreview)
                                        },
                                        onRemove = { state.removeStaged(staged.attachment.fileId) },
                                        onRetry = if (staged.attachment.state == vade.ui.skin.AttachmentState.Failed) {
                                            { state.removeStaged(staged.attachment.fileId); state.attach(staged) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            if (state.staged.size > 2) {
                                BasicText(
                                    text = "还有 ${state.staged.size - 2} 个附件（单轮上限 ${ChatState.MAX_PER_TURN}）",
                                    style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 次要按钮（sm 12dp、透明底 + hairline），不用 Material 的 text button 紫。
                            skin.Button(
                                text = "+ 附件",
                                onClick = { state.attach() },
                                style = BtnStyle.Secondary,
                            )
                            Spacer(Modifier.width(10.dp))
                            BasicText(
                                text = state.sendHint,
                                style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                },
            )
        }
    }
}

// --- keyboard ----------------------------------------------------------------

/**
 * U1-b desktop key contract: `⌘Enter` / `Ctrl+Enter` sends, plain `Enter` stays a newline so
 * the composer is genuinely multi-line. Kept pure (and `internal`) so `ChatFlowTest` can drive
 * the *real* handler with real `KeyEvent`s instead of asserting on a copy of the logic.
 *
 * Consumed even when the composer is empty — otherwise the newline would still be inserted and
 * an empty ⌘Enter would look like the send was ignored for the wrong reason.
 */
internal fun handleComposerKeys(
    event: androidx.compose.ui.input.key.KeyEvent,
    state: ChatState,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val primary = event.isMetaPressed || event.isCtrlPressed
    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
    if (!primary || !enter) return false
    state.submit()
    return true
}

// --- fullscreen image preview ------------------------------------------------
@Composable
private fun ImagePreview(image: ContentPart.Image, onClose: () -> Unit) {
    val skin = LocalSkin.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = image.name,
                    style = skin.typography.body.copy(color = Color.White),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(skin.shapes.small))
                        .background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { BasicText("×", style = skin.typography.title.copy(color = Color.White)) }
            }
            MockImage(
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .aspectRatio(image.width.toFloat() / image.height.toFloat())
                    .clip(RoundedCornerShape(skin.shapes.medium)),
            )
            BasicText(
                text = "${formatBytes(image.size)} · ${image.width}×${image.height} · $COMPRESSED_NOTE（D96）",
                style = skin.typography.caption.copy(color = Color.White.copy(alpha = 0.78f)),
            )
        }
    }
}

// --- the "photo" -------------------------------------------------------------

/**
 * A deterministic stand-in for a compressed screenshot: gradient + fake log lines, drawn from
 * the image's own dimensions so the aspect ratio on screen is the real one.
 *
 * **It is a placeholder, not a decoded image.** U1-b reads no file bytes (no picker, no
 * compression, no `POST /ui/blob`); claiming otherwise would be the "假上传显示假成功" the task
 * book forbids. The dimensions and byte count shown next to it come from the `image` part.
 */
@Composable
fun MockImage(modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    Canvas(modifier) {
        drawMockScreenshot(skin.colors.primary, skin.colors.surfaceVariant, skin.colors.onSurfaceVariant)
    }
}

private fun DrawScope.drawMockScreenshot(accent: Color, paper: Color, ink: Color) {
    val w = size.width
    val h = size.height
    drawRect(
        brush = Brush.linearGradient(
            listOf(paper, accent.copy(alpha = 0.22f)),
            start = Offset(0f, 0f),
            end = Offset(w, h),
        ),
    )
    // Title bar + a few text lines: enough to read as "a screenshot", no more.
    drawRect(accent.copy(alpha = 0.55f), size = Size(w, h * 0.12f))
    val line = h * 0.045f
    var y = h * 0.22f
    val widths = listOf(0.72f, 0.55f, 0.84f, 0.40f, 0.66f, 0.30f)
    var i = 0
    while (y < h * 0.92f) {
        val frac = widths[i % widths.size]
        drawRect(
            color = ink.copy(alpha = if (i % 3 == 0) 0.42f else 0.20f),
            topLeft = Offset(w * 0.08f, y),
            size = Size(w * frac * 0.86f, line * 0.55f),
        )
        y += line * 1.6f
        i++
    }
}
