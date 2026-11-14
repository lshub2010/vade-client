package vade.ui.skin.miuix

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.ui.graphics.Color
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
 * Skin class stays readable. Every container is a Miuix `Surface` with a 1 dp stroke and no
 * elevation, and corners stay on the spec ladder (12 / 16 / 20 dp, 28 dp for the sheet)
 * rather than the material skin's 8 / 14 / 22.
 */

// --- 7 · ApprovalDialog ------------------------------------------------------

/**
 * A bottom sheet, not a centred card — that is Miuix's own confirmation form
 * (spike/miuix RESULT.md §三.3: "真实形态是底部 sheet").
 *
 * Deviations from an `OverlayDialog` build, on purpose: upstream `OverlayDialog` only draws
 * inside a `MiuixPopupHost`, which exists *solely* inside Miuix's `Scaffold` (RESULT.md §五
 * R6). The C-09 contract puts the dialog wherever the page composes it, and pages must not
 * grow a Miuix `Scaffold` just to host a sheet, so the scrim + sheet are composed here from
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
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (colors.isDark) 0.55f else 0.32f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                // `widthIn` must come *before* `fillMaxWidth`: chained the other way round the
                // width is already pinned to the parent's, so the 560 dp cap silently did
                // nothing and the desktop sheet stretched the full 1266 px window.
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                // Spec §五 弹层/sheet: `xl` (28 dp) top corners, flush with the window edge.
                // `SkinShapes` has no `xl` slot, so the spec's own value is used literally.
                shape = RoundedCornerShape(topStart = MiuixTokens.sheetRadius, topEnd = MiuixTokens.sheetRadius),
                color = colors.surface,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.outline),
                shadowElevation = 0.dp,
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Drag hint — the physical affordance of a sheet.
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).size(width = 32.dp, height = 3.dp)
                            .clip(CircleShape).background(colors.outline),
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
        fontSize = 10.sp,
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
 * D92 layout, brought onto the spec's radius ladder (`ui-visual-language.md` §五 消息).
 *
 * The three roles are *not* three variations of one bubble — the spec makes them three
 * different things, and the previous revision collapsed that distinction:
 *
 *  - **assistant**: no bubble, no border, no fill. Full width up to [maxWidth], text sits
 *    directly on the page. This is what makes a long answer read like a document instead of a
 *    chat bubble wall (§一 原则 4), and it is the single biggest visual delta vs `material`.
 *  - **user**: right-aligned, `surface` + hairline, `lg` (20 dp) corners with a squared-off
 *    bottom-end corner as the bubble tail, capped at 78 % of the reading column.
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
                    // `lg` with a 6 dp bottom-end tail (§五 消息（用户）: "右下角 6dp（气泡尾）").
                    shape = RoundedCornerShape(
                        topStart = shapes.large,
                        topEnd = shapes.large,
                        bottomStart = shapes.large,
                        bottomEnd = MiuixTokens.radiusXs,
                    ),
                    color = colors.surface,
                    contentColor = colors.onSurface,
                    border = BorderStroke(1.dp, colors.outline),
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
                            fontSize = 10.sp,
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
 * Compact 8 dp chip (material uses a pill): a 20 dp mime square, the file name, the state
 * copy, and — when uploading — a 2 dp progress hairline under the text.
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
            modifier = Modifier.heightIn(min = 38.dp),
            shape = RoundedCornerShape(shapes.small),
            color = colors.surface,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, if (failed) colors.error else colors.outline),
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
                    Text("重试", fontSize = 11.sp, color = colors.primary, modifier = Modifier.clickable { onRetry() })
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
 * instead of another card.
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
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(shapes.medium),
            color = colors.surface,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, if (call.isError) colors.error.copy(alpha = 0.5f) else colors.outline),
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
 * D88 composer. Spec (`ui-visual-language.md` §五 输入框): `md` corners, `surface` + hairline,
 * and a 2 dp accent stroke on focus — not a glow, not an underline.
 *
 * The send control is a **12 dp rounded square**, deliberately not the material skin's circle:
 * Miuix's own composer uses a rounded rect, and the shape contrast is what makes the two skins
 * distinguishable in a blind test. It is 38 dp, i.e. a comfortable mouse target (spec allows
 * 32 dp minimum on desktop).
 *
 * `voiceAvailable = false` draws nothing at all (D97) — not a greyed-out mic.
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
        Column(Modifier.fillMaxWidth().background(colors.surface)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outline))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                if (attachments != null) {
                    attachments()
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    // Spec §五 输入框: `surface` + `hairline` at rest, 2 dp `accent` stroke on
                    // focus. The first screenshot had the 2 dp accent ring *always on*, which
                    // reads as an error state rather than a focus state. Focus is now tracked
                    // from the field's own InteractionSource, so the ring appears only when the
                    // caret is really in it.
                    val interaction = remember { MutableInteractionSource() }
                    val focused by interaction.collectIsFocusedAsState()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 40.dp, max = 132.dp)
                            .clip(RoundedCornerShape(shapes.medium))
                            .background(colors.surface)
                            .border(
                                width = if (focused) 2.dp else 1.dp,
                                color = when {
                                    focused -> colors.primary
                                    else -> colors.outline
                                },
                                shape = RoundedCornerShape(shapes.medium),
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        if (value.isEmpty()) {
                            Text("输入消息…", style = typography.body, color = colors.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            enabled = enabled,
                            interactionSource = interaction,
                            textStyle = typography.body.copy(color = colors.onSurface),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    // D97: no on-device STT ⇒ no button at all.
                    if (voiceAvailable) {
                        SquareButton("◍", colors.surfaceVariant, colors.onSurfaceVariant, shapes, enabled) { }
                        Spacer(Modifier.width(6.dp))
                    }
                    SquareButton(
                        glyph = if (streaming) "■" else "↑",
                        bg = if (streaming) colors.error else colors.primary,
                        fg = if (streaming) colors.onError else colors.onPrimary,
                        shapes = shapes,
                        enabled = if (streaming) true else enabled,
                    ) { if (streaming) onStop() else onSend() }
                }
            }
        }
    }
}

/** 38 dp, `small` (12 dp) corner — deliberately not a circle (that is the material skin's shape). */
@Composable
private fun SquareButton(
    glyph: String,
    bg: Color,
    fg: Color,
    shapes: SkinShapes,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(shapes.small))
            .background(if (enabled) bg else bg.copy(alpha = 0.45f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 14.sp, color = fg)
    }
}
