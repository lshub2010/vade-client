package vade.ui.skin.miuix

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Button
import vade.ui.skin.ApprovalRequest
import vade.ui.skin.AttachmentState
import vade.ui.skin.AttachmentView
import vade.ui.skin.BubbleRole
import vade.ui.skin.Decision
import vade.ui.skin.InputMode
import vade.ui.skin.MessageAction
import vade.ui.skin.MessageView
import vade.ui.skin.Scope
import vade.ui.skin.SkinColors
import vade.ui.skin.SkinShapes
import vade.ui.skin.SkinTypography
import vade.ui.skin.ToolCallView
import vade.ui.skin.ToolCardState

/**
 * `miuix` implementations of C-09 components 7-11.
 *
 * Same rule as `MaterialParts.kt`: free composables that `MiuixSkin` delegates to, so the
 * Skin class stays readable. Every container is a Miuix `Surface` with a 1 dp stroke and the
 * v2 §8 layering (static cards get `shadowSoft`, float layers get `shadowFloat`), and corners
 * stay on the v2 six-step ladder (xs 12 / sm 16 / md 20 / lg 24 / xl 28 / sheet 32).
 */

// --- 7 · ApprovalDialog ------------------------------------------------------

/**
 * A **floating sheet card** (v2 §9), not a flush bottom sheet and not a centred card —
 * margins 12 dp on left/right/bottom, all four corners at the v2 `sheet` step (32 dp),
 * glass base + 1 dp hairline + a real `shadowFloat`, and the 36×4 grabber that says
 * "pull down to dismiss". Miuix's own confirmation form is the bottom sheet (spike/miuix
 * RESULT.md §三.3); v2 §9 floats the same form above the screen edge.
 *
 * Deviations from an `OverlayDialog` build, on purpose: upstream `OverlayDialog` only draws
 * inside a `MiuixPopupHost`, which exists *solely* inside Miuix's `Scaffold` (RESULT.md §五
 * R6). The C-09 contract puts the dialog wherever the page composes it, and pages must not
 * grow a Miuix `Scaffold` just to host a sheet, so the scrim + card are composed here from
 * Miuix `Surface`/`Text`/`Button`.
 *
 * Layout rules from the task book §T2: the whole argument block scrolls, buttons stack
 * vertically (danger last), and the countdown is always visible.
 */
@Composable
internal fun MiuixApprovalDialog(
    request: ApprovalRequest,
    remainingSec: Int,
    onDecide: (Decision, Scope) -> Unit,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
) {
    MiuixScope(colors) {
        // §9: the scrim is rgba(0,0,0,.24) + backdrop blur — blur needs the shell-side
        // HazeState wiring, so the scrim ships alone (documented degrade, §T3).
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.24f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // Four corners equal (§9: 四角同圆); `widthIn` must come *before* `fillMaxWidth`:
            // chained the other way round the width is already pinned to the parent's, so the
            // 560 dp cap silently did nothing and the desktop card stretched the full window.
            val cardShape = RoundedCornerShape(MiuixTokens.sheetRadius)
            Surface(
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    // §8 row 2: a float layer is defined by having a shadow.
                    .shadow(
                        elevation = if (colors.isDark) 16.dp else 12.dp,
                        shape = cardShape,
                        ambientColor = colors.shadowFloat,
                        spotColor = colors.shadowFloat,
                    ),
                shape = cardShape,
                color = colors.surfaceGlass,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.hairline),
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // §9 grabber: 36×4dp, radius 2, white 70% (dark) / black 24% (light),
                    // 8 dp from the card top. Its only job is "this sheet can be pulled down".
                    Box(
                        Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 2.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (colors.isDark) Color.White.copy(alpha = 0.70f)
                                else Color.Black.copy(alpha = 0.24f),
                            ),
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "需要批准：${request.toolName}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        RiskTag(request.risk, colors)
                    }

                    Text(
                        text = "剩余 ${remainingSec}s · 超时按拒绝处理",
                        fontSize = 11.sp,
                        color = if (remainingSec <= 10) colors.error else colors.onSurfaceVariant,
                    )

                    // Full parameter block, monospace, scrolls *inside* a fixed-height well so
                    // a long argument list can never push the buttons off screen.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp, max = 240.dp)
                            .clip(RoundedCornerShape(shapes.small))
                            .background(if (colors.isDark) Color(0xFF000000) else colors.surfaceVariant)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = request.arguments,
                            style = typography.mono,
                            color = colors.onSurface,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }

                    // Vertical stack, danger last (task book §T2).
                    Button(
                        onClick = { onDecide(Decision.Approve, Scope.Once) },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = shapes.small,
                        minHeight = 40.dp,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        insideMargin = PaddingValues(vertical = 8.dp),
                    ) { Text("仅本次允许", fontSize = 14.sp) }

                    Button(
                        onClick = { onDecide(Decision.Approve, Scope.Session) },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = shapes.small,
                        minHeight = 40.dp,
                        colors = ButtonDefaults.buttonColors(
                            color = if (colors.isDark) Color(0xFF26262A) else colors.surfaceVariant,
                            contentColor = colors.onSurface,
                        ),
                        insideMargin = PaddingValues(vertical = 8.dp),
                    ) { Text("本会话都允许", fontSize = 14.sp) }

                    Button(
                        onClick = { onDecide(Decision.Deny, Scope.Once) },
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = shapes.small,
                        minHeight = 40.dp,
                        colors = ButtonDefaults.buttonColors(color = colors.error, contentColor = colors.onError),
                        insideMargin = PaddingValues(vertical = 8.dp),
                    ) { Text("拒绝", fontSize = 14.sp) }

                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun RiskTag(risk: String, colors: SkinColors) {
    val high = risk == "high"
    Text(
        text = "risk=$risk",
        // 字号四档的下限是 11（v2 字号纪律：不得回到 8–10sp）。
        fontSize = MiuixTokens.tinySize,
        fontWeight = FontWeight.Medium,
        color = if (high) colors.error else colors.onSurfaceVariant,
        modifier = Modifier
            // Pill, not a 4 dp tag: §三 forbids <8 dp radii, and a status pill is the spec's
            // named use for `pill`.
            .clip(RoundedCornerShape(MiuixTokens.shapes.pill))
            .background(
                if (high) colors.error.copy(alpha = 0.10f) else colors.outline.copy(alpha = 0.25f),
            )
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// --- 8 · MessageBubble -------------------------------------------------------

/**
 * D92 layout, brought onto the v2 radius ladder (`ui-visual-language.md` §五 消息 + §10).
 *
 * The three roles are *not* three variations of one bubble — the spec makes them three
 * different things, and the previous revision collapsed that distinction:
 *
 *  - **assistant**: no bubble, no border, no fill. Full width up to [maxWidth], text sits
 *    directly on the page. This is what makes a long answer read like a document instead of a
 *    chat bubble wall (§一 原则 4), and it is the single biggest visual delta vs `material`.
 *  - **user**: right-aligned, `surface` + hairline, `lg` (24 dp) corners with the **6 dp**
 *    bottom-end tail (v2 keeps the fixed 6 dp tail — it is not on the radius ladder), capped
 *    at 78 % of the reading column.
 *  - **system**: centred 11 sp caption in a hairline pill.
 *
 * The old revision gave the assistant a `Surface` + hairline at the `md` radius, i.e. it drew
 * the user's container around the assistant's prose — a spec violation, not a taste call.
 */
@Composable
internal fun MiuixMessageBubble(
    message: MessageView,
    role: BubbleRole,
    maxWidth: Dp,
    actions: List<MessageAction>,
    onAction: (MessageAction) -> Unit,
    body: (@Composable () -> Unit)?,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
) {
    val user = role == BubbleRole.User
    val system = role == BubbleRole.System

    MiuixScope(colors) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = when {
                user -> Alignment.End
                system -> Alignment.CenterHorizontally
                else -> Alignment.Start
            },
        ) {
            when {
                // --- assistant: prose, not a bubble ---------------------------------
                role == BubbleRole.Assistant -> Column(
                    Modifier.widthIn(max = maxWidth).padding(vertical = 2.dp),
                ) {
                    Text(
                        text = message.text,
                        style = typography.body,
                        color = if (message.isError) colors.error else colors.onSurface,
                    )
                }

                // --- system: centred caption pill ------------------------------------
                system -> Text(
                    text = message.text,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .widthIn(max = maxWidth)
                        .clip(RoundedCornerShape(shapes.pill))
                        .background(colors.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )

                // --- user: the only real bubble --------------------------------------
                else -> Surface(
                    modifier = Modifier.widthIn(max = minOf(maxWidth * 0.78f, 520.dp)),
                    // `lg` (24 dp) with the 6 dp bottom-end tail (§五 消息（用户）:
                    // "右下角 6dp（气泡尾）" — v2 keeps the fixed 6 dp value).
                    shape = RoundedCornerShape(
                        topStart = shapes.lg,
                        topEnd = shapes.lg,
                        bottomStart = shapes.lg,
                        bottomEnd = MiuixTokens.bubbleTail,
                    ),
                    color = colors.surface,
                    contentColor = colors.onSurface,
                    border = BorderStroke(1.dp, colors.hairline),
                    shadowElevation = 0.dp,
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text(
                            text = message.text,
                            style = typography.body,
                            color = if (message.isError) colors.error else colors.onSurface,
                        )
                    }
                }
            }
            if (message.badges.isNotEmpty()) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    message.badges.forEach { badge ->
                        Text(
                            text = badge,
                            fontSize = MiuixTokens.tinySize,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(shapes.pill))
                                .background(colors.outline.copy(alpha = 0.22f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (actions.isNotEmpty()) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    actions.forEach { action ->
                        Text(
                            text = action.label,
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.clickable { onAction(action) }.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

// --- 9 · AttachmentChip ------------------------------------------------------

/**
 * v2 附件卡 (§10: md 20dp) — a 28 dp mime square at the `xs` (12 dp) step, the file name,
 * the state copy, and — when uploading — a 2 dp progress hairline under the text. Static
 * card layering: hairline + `shadowSoft` (§8 row 1).
 *
 * Copy rule (D96): an uploaded image says "已压缩，原图未保留", never "点击查看原图".
 */
@Composable
internal fun MiuixAttachmentChip(
    attachment: AttachmentView,
    onRetry: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    leading: (@Composable () -> Unit)?,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
) {
    val failed = attachment.state == AttachmentState.Failed
    val status = when (attachment.state) {
        AttachmentState.Validating -> "校验中…"
        AttachmentState.Uploading -> "上传中 ${(attachment.progress * 100).toInt()}%"
        AttachmentState.Uploaded -> attachment.detail ?: "已上传 · 已压缩，原图未保留"
        AttachmentState.Failed -> attachment.detail ?: "上传失败"
    }
    MiuixScope(colors) {
        Surface(
            modifier = Modifier
                .heightIn(min = 38.dp)
                .shadow(
                    elevation = 2.dp,
                    shape = RoundedCornerShape(shapes.md),
                    ambientColor = colors.shadowSoft,
                    spotColor = colors.shadowSoft,
                ),
            shape = RoundedCornerShape(shapes.md),
            color = colors.surface,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, if (failed) colors.error else colors.hairline),
            shadowElevation = 0.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(MiuixTokens.radiusXs))
                        .background(colors.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        attachment.mime.take(1).uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = attachment.fileName,
                        fontSize = 11.sp,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status,
                        style = typography.caption,
                        color = if (failed) colors.error else colors.onSurfaceVariant,
                    )
                }
                if (failed && onRetry != null) {
                    Spacer(Modifier.width(8.dp))
                    // v2 §3 图标即按钮：重试用 ↻，不用文字。
                    Text("↻", fontSize = 14.sp, color = colors.primary, modifier = Modifier.clickable { onRetry() })
                }
                if (onRemove != null) {
                    Spacer(Modifier.width(8.dp))
                    Text("×", fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.clickable { onRemove() })
                }
            }
        }
    }
}

// --- 10 · ToolCallCard -------------------------------------------------------

/**
 * D94 states are rendered, not decided: `Collapsed` shows one line, `Expanded` /
 * `PendingApproval` show the redacted args plus the result. Hierarchy comes from a 3 dp
 * leading accent bar rather than a filled header, which is what makes it read as a tool log
 * instead of another card. Static-card layering: hairline + `shadowSoft` (v2 §8 row 1).
 */
@Composable
internal fun MiuixToolCallCard(
    call: ToolCallView,
    state: ToolCardState,
    onToggle: () -> Unit,
    meta: (@Composable () -> Unit)?,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
) {
    val expanded = state != ToolCardState.Collapsed
    // Spec §五 工具卡: 左侧 3dp 状态条（成功 success / 失败 danger / 审批 warn）.
    // The old mapping fell through to `outline` for the success case, i.e. the bar was painted
    // in the hairline colour and read as "no status at all".
    val accent = when {
        state == ToolCardState.PendingApproval -> MiuixTokens.warn(colors.isDark)
        call.isError -> colors.error
        else -> MiuixTokens.success(colors.isDark)
    }
    val label = when (state) {
        ToolCardState.PendingApproval -> "等待审批 · ${call.risk}"
        ToolCardState.Expanded -> if (call.isError) "失败 · 已展开" else "已完成 · 已展开"
        ToolCardState.Collapsed -> if (call.isError) "失败" else "已完成"
    }
    MiuixScope(colors) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 2.dp,
                    shape = RoundedCornerShape(shapes.md),
                    ambientColor = colors.shadowSoft,
                    spotColor = colors.shadowSoft,
                ),
            shape = RoundedCornerShape(shapes.md),
            color = colors.surface,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, if (call.isError) colors.error.copy(alpha = 0.5f) else colors.hairline),
            shadowElevation = 0.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onToggle() }.padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Accent bar: the state colour, 3 dp wide, full header height.
                    Box(Modifier.width(3.dp).height(40.dp).background(accent))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(
                            text = call.name,
                            fontFamily = typography.mono.fontFamily,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(label, fontSize = MiuixTokens.tinySize, color = accent)
                    }
                    Text(if (expanded) "▾" else "▸", fontSize = 11.sp, color = colors.onSurfaceVariant)
                }
                if (expanded) {
                    Column(
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text("参数", fontSize = MiuixTokens.tinySize, color = colors.onSurfaceVariant)
                        Box(
                            Modifier.fillMaxWidth().heightIn(max = 140.dp)
                                .clip(RoundedCornerShape(shapes.small))
                                .background(if (colors.isDark) Color(0xFF000000) else colors.surfaceVariant)
                                .padding(8.dp),
                        ) {
                            Text(
                                text = call.args,
                                style = typography.mono,
                                color = colors.onSurface,
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            )
                        }
                        if (call.result != null) {
                            Text("结果", fontSize = MiuixTokens.tinySize, color = colors.onSurfaceVariant)
                            Text(
                                text = call.result,
                                style = typography.mono,
                                color = if (call.isError) colors.error else colors.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- 11 · InputBar -----------------------------------------------------------

/**
 * The v2 **floating composer** (§1/§2):
 *
 *  - an **independent float layer** with 16 dp clear on every side — never a flush,
 *    full-width bar; `xl` (28 dp) corners, glass base (`surfaceGlass` + 1 dp hairline) and a
 *    real `shadowFloat` (§8 row 2: a float layer is defined by its shadow);
 *  - the send control is a **48 dp ball OUTSIDE the field**, 12 dp to its right, vertically
 *    centred (§2) — nothing send-shaped lives inside the box. States: content = `accent`
 *    solid + 12 dp accent glow (§8 row 3); empty = glass + hairline, muted glyph; streaming
 *    = the same ball turns **stop** (■), tinted icon + stroke only, never a red ball;
 *  - press = `scale 0.92` over 180 ms with the one v2 curve (§2/§7);
 *  - `voiceAvailable = false` draws nothing at all (D97) — not a greyed-out mic.
 *
 * The glass is the *degrade* shape for now: `surfaceGlass` tint + hairline + `shadowFloat`.
 * A real backdrop blur (§5) needs the app shell to register a `HazeState` +
 * `Modifier.hazeSource`, which lives in `chat/`/`shell/` — outside this batch's directory.
 */
@Composable
internal fun MiuixInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    mode: InputMode,
    onSend: () -> Unit,
    onStop: () -> Unit,
    attachments: (@Composable () -> Unit)?,
    enabled: Boolean,
    voiceAvailable: Boolean,
    sendEnabled: Boolean,
    maxLines: Int,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
) {
    val streaming = mode == InputMode.Streaming
    MiuixScope(colors) {
        val fieldShape = RoundedCornerShape(shapes.xl)
        val floatElevation = if (colors.isDark) 16.dp else 12.dp
        Column(
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = MiuixTokens.composerMargin,
                    vertical = MiuixTokens.composerMargin,
                ),
        ) {
            if (attachments != null) {
                attachments()
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Focus is tracked from the field's own InteractionSource, so the accent ring
                // appears only when the caret is really in it (the first v1 screenshot had it
                // always on, which read as an error state).
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // §8 row 2: the composer is a float layer — shadow first, so it casts
                        // from the surface bounds instead of being clipped by the glass.
                        .shadow(
                            elevation = floatElevation,
                            shape = fieldShape,
                            ambientColor = colors.shadowFloat,
                            spotColor = colors.shadowFloat,
                        )
                        .clip(fieldShape)
                        .background(colors.surfaceGlass)
                        // §五 输入框: 1 dp hairline at rest, 2 dp `accent` stroke on focus —
                        // not a glow, not an underline.
                        .border(
                            width = if (focused) 2.dp else 1.dp,
                            color = if (focused) colors.primary else colors.hairline,
                            shape = fieldShape,
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        if (voiceAvailable) {
                            GlassIconButton(colors, enabled) { }
                            Spacer(Modifier.width(8.dp))
                        }
                        Box(Modifier.weight(1f).heightIn(min = 32.dp)) {
                            if (value.isEmpty()) {
                                Text("输入消息…", style = typography.body, color = colors.onSurfaceVariant)
                            }
                            BasicTextField(
                                value = value,
                                onValueChange = onValueChange,
                                enabled = enabled,
                                interactionSource = interaction,
                                textStyle = typography.body.copy(color = colors.onSurface),
                                cursorBrush = SolidColor(colors.primary),
                                maxLines = maxLines,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(MiuixTokens.sendBallGap))
                SendBall(
                    streaming = streaming,
                    sendEnabled = sendEnabled,
                    enabled = enabled,
                    colors = colors,
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}

/**
 * The §2 send ball: 48 dp, *outside* the field, vertically centred. Glow is a 12 dp radial
 * halo behind the ball (`glowAccent` @ 28 % → transparent, §8 row 3) — a glow, not a shadow;
 * press scales the whole thing (halo included) to 0.92 over 180 ms (§2).
 */
@Composable
private fun SendBall(
    streaming: Boolean,
    sendEnabled: Boolean,
    enabled: Boolean,
    colors: SkinColors,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(durationMillis = 180, easing = MiuixTokens.motion.easing),
        label = "sendBallPress",
    )
    // `active` is the accent state: content present (or the page's own canSend signal).
    val active = !streaming && sendEnabled
    // §2: an empty ball stays clickable ("触发提示"); only a fully disabled field is inert.
    val clickable = streaming || enabled
    val haloSize = MiuixTokens.sendBallSize + 24.dp // 12 dp of glow on every side
    Box(modifier = Modifier.size(haloSize), contentAlignment = Alignment.Center) {
        if (active) {
            Box(
                Modifier
                    .size(haloSize)
                    .background(
                        brush = Brush.radialGradient(
                            // Hold ~full strength up to the ball's rim, fade over the 12 dp ring.
                            0.64f to colors.glowAccent.copy(alpha = 0.28f),
                            1f to Color.Transparent,
                        ),
                        shape = CircleShape,
                    ),
            )
        }
        Box(
            modifier = Modifier
                .size(MiuixTokens.sendBallSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (!enabled && !streaming) 0.45f else 1f
                }
                .clip(CircleShape)
                .then(
                    when {
                        // 生成中：同一球位变停止，只染图标与描边，不整球变红（§2）。
                        streaming -> Modifier
                            .background(colors.surfaceGlass)
                            .border(1.5.dp, colors.error, CircleShape)
                        // 有内容：accent 实心 + 辉光（§2）。
                        active -> Modifier.background(colors.primary)
                        // 空：玻璃 + 1px 发丝线，图标 muted（§2）。
                        else -> Modifier
                            .background(colors.surfaceGlass)
                            .border(1.dp, colors.hairline, CircleShape)
                    },
                )
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = clickable,
                ) { if (streaming) onStop() else onSend() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (streaming) "■" else "↑",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    streaming -> colors.error
                    active -> colors.onPrimary
                    else -> colors.onSurfaceVariant
                },
            )
        }
    }
}

/** D97: mic placeholder — a hairline glass orb, only drawn when [voiceAvailable] is true. */
@Composable
private fun GlassIconButton(colors: SkinColors, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(colors.surfaceGlass)
            .border(1.dp, colors.hairline, CircleShape)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text("◉", fontSize = 14.sp, color = colors.onSurfaceVariant)
    }
}
