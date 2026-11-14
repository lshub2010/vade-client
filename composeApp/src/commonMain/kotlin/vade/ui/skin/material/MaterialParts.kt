package vade.ui.skin.material

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * `material` implementations of C-09 components 7-11, written against
 * `research/ui-visual-language.md` (2026-11-14). The four rules that drive every
 * shape below:
 *
 *  1. **1px hairline instead of a shadow.** No `Modifier.shadow` on any static card;
 *     a card may carry a fill *or* a fill + hairline, never fill + border + shadow.
 *  2. **Radius comes from the token scale** 8 / 12 / 16 / 20 / 28 — cards 16, user
 *     bubble 20 with a 6dp tail, secondary buttons 12, primary buttons pill.
 *  3. **Assistant messages are not bubbles.** They are full-width text capped at
 *     `maxWidth`; only the user gets a bubble.
 *  4. **Every tappable row is ≥44dp tall** (mouse targets may be 36, touch may not).
 */

@Composable
internal fun MaterialApprovalDialog(
    request: ApprovalRequest,
    remainingSec: Int,
    onDecide: (Decision, Scope) -> Unit,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
) {
    // U1-a renders this in-tree (scrim + centred card) instead of a platform dialog:
    // the approval surfaces land in U1-d, which will decide the host. The skin must
    // not own that decision. Visual language: overlays get 0 8 24 rgba(0,0,0,.18) —
    // approximated with a scrim only until a real elevation story exists (no 4dp
    // Material elevation anywhere).
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .clip(RoundedCornerShape(shapes.large))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(shapes.large))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "需要批准",
                        fontSize = 11.sp,
                        color = colors.warning,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = request.toolName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "risk=${request.risk}",
                    fontSize = 11.sp,
                    color = if (request.risk == "high") colors.error else colors.onSurfaceVariant,
                )
            }
            // 参数：surfaceSunken + sm 圆角，等宽、可滚（FR-8.53）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .clip(RoundedCornerShape(shapes.small))
                    .background(colors.surfaceVariant)
                    .padding(12.dp),
            ) {
                Text(
                    text = request.arguments,
                    style = typography.mono,
                    color = colors.onSurface,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
            Text(
                text = "剩余 ${remainingSec}s · 超时按拒绝处理",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton(
                    label = "仅本次",
                    fill = colors.primary,
                    fg = colors.onPrimary,
                    radius = shapes.pill,
                    onClick = { onDecide(Decision.Approve, Scope.Once) },
                )
                DialogButton(
                    label = "本会话",
                    fill = Color.Transparent,
                    fg = colors.onSurface,
                    radius = shapes.small,
                    border = colors.hairline,
                    onClick = { onDecide(Decision.Approve, Scope.Session) },
                )
                DialogButton(
                    label = "拒绝",
                    fill = Color.Transparent,
                    fg = colors.error,
                    radius = shapes.small,
                    border = colors.hairline,
                    onClick = { onDecide(Decision.Deny, Scope.Once) },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.DialogButton(
    label: String,
    fill: Color,
    fg: Color,
    radius: Dp,
    border: Color? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
internal fun MaterialMessageBubble(
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
    val isUser = role == BubbleRole.User
    val isSystem = role == BubbleRole.System
    val textColor = when {
        message.isError -> colors.error
        isSystem -> colors.onSurfaceVariant
        else -> colors.onSurface
    }

    val content: @Composable () -> Unit = {
        if (body != null) body() else Text(
            text = message.text,
            style = typography.body,
            color = textColor,
        )
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 视觉规范 §5: 用户气泡最大 78%，助手全宽 ≤760。
        val container = this.maxWidth
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = when {
                isUser -> Alignment.End
                isSystem -> Alignment.CenterHorizontally
                else -> Alignment.Start
            },
        ) {
            when {
                // 助手：无气泡、无边框、无底色，只有正文（"读起来像文档"）。
                !isUser && !isSystem -> Box(
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = maxWidth)
                        .padding(vertical = 2.dp),
                ) { content() }

                // 系统：居中的弱化提示，同样没有气泡。
                isSystem -> Box(
                    Modifier
                        .widthIn(max = minOf(container * 0.82f, maxWidth))
                        .padding(vertical = 2.dp),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            content()
                        }
                    }
                }

                // 用户：surface 底 + hairline，lg 圆角 + 右下 6dp 气泡尾。
                else -> Box(
                    Modifier
                        .widthIn(max = minOf(container * 0.78f, maxWidth))
                        .clip(
                            RoundedCornerShape(
                                topStart = shapes.large,
                                topEnd = shapes.large,
                                bottomEnd = 6.dp,
                                bottomStart = shapes.large,
                            ),
                        )
                        .background(colors.surface)
                        .border(
                            1.dp,
                            colors.hairline,
                            RoundedCornerShape(
                                topStart = shapes.large,
                                topEnd = shapes.large,
                                bottomEnd = 6.dp,
                                bottomStart = shapes.large,
                            ),
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) { content() }
            }

            if (message.badges.isNotEmpty()) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    message.badges.forEach { badge ->
                        Text(
                            text = badge,
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(shapes.pill))
                                .background(colors.surfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            if (actions.isNotEmpty()) {
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    actions.forEach { action ->
                        Text(
                            text = action.label,
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier
                                .heightIn(min = 32.dp)
                                .clickable { onAction(action) }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MaterialAttachmentChip(
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
    val shape = RoundedCornerShape(shapes.small)
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, if (failed) colors.error.copy(alpha = 0.6f) else colors.hairline, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 28×28 缩略图，xs 圆角；页面给的 leading 优先，缺省用 mime 占位。
        if (leading != null) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(shapes.xs))) { leading() }
        } else {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(shapes.xs))
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text(attachment.mime.take(1).uppercase(), fontSize = 10.sp, color = colors.onSurfaceVariant) }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.widthIn(max = 210.dp)) {
            Text(
                text = attachment.fileName,
                fontSize = 13.sp,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = status,
                fontSize = 11.sp,
                color = if (failed) colors.error else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (failed && onRetry != null) {
            Spacer(Modifier.width(8.dp))
            CircleTap(size = 28.dp, onClick = onRetry) {
                Text("重试", fontSize = 11.sp, color = colors.primary)
            }
        }
        if (onRemove != null) {
            Spacer(Modifier.width(4.dp))
            CircleTap(size = 28.dp, onClick = onRemove) {
                Text("×", fontSize = 15.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

/** A small square tap target. Visually 28dp, but the parent row is already 44dp tall. */
@Composable
private fun CircleTap(size: Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
internal fun MaterialToolCallCard(
    call: ToolCallView,
    state: ToolCardState,
    onToggle: () -> Unit,
    meta: (@Composable () -> Unit)?,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
) {
    // D94: 成功默认折叠、失败展开、审批常驻展开。页面给 state，皮肤只负责画。
    val expanded = state != ToolCardState.Collapsed
    val status = when {
        state == ToolCardState.PendingApproval -> colors.warning
        call.isError -> colors.error
        else -> colors.success
    }
    val stateLabel = when (state) {
        ToolCardState.PendingApproval -> "等待审批 · risk=${call.risk}"
        ToolCardState.Expanded -> if (call.isError) "失败" else "已完成"
        ToolCardState.Collapsed -> "成功"
    }
    val shape = RoundedCornerShape(shapes.medium)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            // 静态卡片：底色 + 1px 发丝线，不叠阴影、不叠 elevation。
            .background(colors.surface)
            .border(1.dp, colors.hairline, shape),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // 左侧 3dp 状态条：success / danger / warn（视觉规范 §5 工具卡）。
            Box(Modifier.width(3.dp).fillMaxHeight().background(status))
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable(onClick = onToggle)
                        .padding(start = 13.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = call.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "工具",
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(stateLabel, fontSize = 11.sp, color = status)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (expanded) "▾" else "▸",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    Column(Modifier.padding(start = 13.dp, end = 12.dp, bottom = 12.dp)) {
                        Text("参数", fontSize = 11.sp, color = colors.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        CodeBlock(call.args, colors, shapes, typography)
                        if (call.result != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("结果", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            CodeBlock(
                                call.result,
                                colors,
                                shapes,
                                typography,
                                ink = if (call.isError) colors.error else colors.onSurface,
                            )
                        }
                    }
                }
                // meta 槽：页面给的耗时 / 批次 / 错误码，折叠与展开都显示（页面自己决定内容）。
                if (meta != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 13.dp, end = 12.dp, bottom = 12.dp),
                    ) { meta() }
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(
    text: String,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
    ink: Color = colors.onSurface,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapes.small))
            .background(colors.surfaceVariant)
            .padding(10.dp),
    ) {
        Text(text = text, style = typography.mono, color = ink)
    }
}

@Composable
internal fun MaterialInputBar(
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (attachments != null) {
            attachments()
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val interaction = remember { MutableInteractionSource() }
            val focused by interaction.collectIsFocusedAsState()
            // md 圆角 + surface 底 + hairline；聚焦时 2dp accent 描边（不是发光）。
            val fieldShape = RoundedCornerShape(shapes.medium)
            Box(
                modifier = Modifier
                    .weight(1f)
                    // 多行最高 maxLines 行后内部滚动（每行 ~22dp + 上下 padding）。
                    .heightIn(min = 44.dp, max = (maxLines * 22 + 24).dp)
                    .clip(fieldShape)
                    .background(colors.surface)
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color = if (focused) colors.primary else colors.hairline,
                        shape = fieldShape,
                    )
                    .alpha(if (enabled) 1f else 0.5f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) {
                    Text("输入消息…", style = typography.body, color = colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    maxLines = maxLines,
                    interactionSource = interaction,
                    textStyle = typography.body.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(10.dp))
            // D97: 无端侧 STT 时不画麦克风（不是画一个禁用的）。
            if (voiceAvailable) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceVariant)
                        .clickable { },
                    contentAlignment = Alignment.Center,
                ) { Text("◍", fontSize = 14.sp, color = colors.onSurfaceVariant) }
                Spacer(Modifier.width(10.dp))
            }
            // 主按钮 pill：accent 底 + 白字；禁用 or 空文本 → textMuted @30%、无描边。
            val sendShape = RoundedCornerShape(shapes.pill)
            val active = streaming || (enabled && sendEnabled)
            Box(
                Modifier
                    .height(44.dp)
                    .widthIn(min = 64.dp)
                    .clip(sendShape)
                    .background(
                        when {
                            streaming -> colors.error
                            active -> colors.primary
                            else -> colors.onSurfaceVariant.copy(alpha = 0.30f)
                        },
                    )
                    .clickable(enabled = enabled && (streaming || sendEnabled)) {
                        if (streaming) onStop() else onSend()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (streaming) "停止" else "发送",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        streaming -> colors.onError
                        active -> colors.onPrimary
                        else -> colors.onSurfaceVariant
                    },
                )
            }
        }
    }
}
