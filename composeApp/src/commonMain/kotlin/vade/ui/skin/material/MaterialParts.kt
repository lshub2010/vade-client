package vade.ui.skin.material

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * `material` implementations of C-09 components 7-11. Kept as free composables (the
 * Skin class delegates) so `MaterialSkin.kt` does not grow past readability.
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
    // the approval surfaces land in U1-d, which will decide the host (Android Dialog /
    // desktop window). The skin must not own that decision.
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(shapes.medium))
                .background(colors.surface)
                .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(shapes.medium))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "需要批准：${request.toolName}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "risk=${request.risk}",
                    fontSize = 10.sp,
                    color = if (request.risk == "high") colors.error else colors.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 170.dp)
                    .clip(RoundedCornerShape(shapes.small))
                    .background(colors.surfaceVariant)
                    .padding(10.dp),
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
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(shapes.small))
                        .background(colors.primary).clickable { onDecide(Decision.Approve, Scope.Once) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("仅本次", color = colors.onPrimary, fontSize = 12.sp) }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(shapes.small))
                        .background(colors.surfaceVariant).clickable { onDecide(Decision.Approve, Scope.Session) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("本会话", color = colors.onSurface, fontSize = 12.sp) }
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(shapes.small))
                        .background(colors.error).clickable { onDecide(Decision.Deny, Scope.Once) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("拒绝", color = colors.onError, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
internal fun MaterialMessageBubble(
    message: MessageView,
    role: BubbleRole,
    maxWidth: Dp,
    actions: List<MessageAction>,
    onAction: (MessageAction) -> Unit,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
) {
    val isUser = role == BubbleRole.User
    val isSystem = role == BubbleRole.System
    val bubbleColor = when (role) {
        BubbleRole.User -> colors.primary
        BubbleRole.Assistant -> colors.surface
        BubbleRole.System -> colors.surfaceVariant
    }
    val textColor = when (role) {
        BubbleRole.User -> colors.onPrimary
        BubbleRole.Assistant -> colors.onSurface
        BubbleRole.System -> colors.onSurfaceVariant
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = when {
            isUser -> Alignment.End
            isSystem -> Alignment.CenterHorizontally
            else -> Alignment.Start
        },
    ) {
        Box(
            modifier = Modifier
                // D92: assistant is full-width up to 760dp; the user bubble stays a
                // bubble (capped well below the reading column).
                .widthIn(max = if (isUser) minOf(maxWidth, 520.dp) else maxWidth)
                .clip(RoundedCornerShape(shapes.medium))
                .background(bubbleColor)
                .border(
                    width = if (isUser) 0.dp else 1.dp,
                    color = if (isUser) Color.Transparent else colors.outline.copy(alpha = 0.30f),
                    shape = RoundedCornerShape(shapes.medium),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = message.text,
                style = typography.body,
                color = if (message.isError) colors.error else textColor,
            )
        }
        if (message.badges.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                message.badges.forEach { badge ->
                    Text(
                        text = badge,
                        fontSize = 9.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(shapes.pill))
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (actions.isNotEmpty()) {
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEach { action ->
                    Text(
                        text = action.label,
                        fontSize = 10.sp,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.clickable { onAction(action) },
                    )
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
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
) {
    val status = when (attachment.state) {
        AttachmentState.Validating -> "校验中…"
        AttachmentState.Uploading -> "上传中 ${(attachment.progress * 100).toInt()}%"
        AttachmentState.Uploaded -> attachment.detail ?: "已上传 · 已压缩，原图未保留"
        AttachmentState.Failed -> attachment.detail ?: "上传失败"
    }
    val borderColor = if (attachment.state == AttachmentState.Failed) colors.error else colors.outline
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(shapes.pill))
            .background(colors.surfaceVariant)
            .border(1.dp, borderColor.copy(alpha = 0.45f), RoundedCornerShape(shapes.pill))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).background(colors.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Text(attachment.mime.take(1).uppercase(), fontSize = 9.sp, color = colors.primary) }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = attachment.fileName,
                fontSize = 11.sp,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = status, style = typography.caption, color = colors.onSurfaceVariant)
        }
        if (attachment.state == AttachmentState.Failed && onRetry != null) {
            Spacer(Modifier.width(8.dp))
            Text("重试", fontSize = 11.sp, color = colors.primary, modifier = Modifier.clickable { onRetry() })
        }
        if (onRemove != null) {
            Spacer(Modifier.width(8.dp))
            Text("×", fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.clickable { onRemove() })
        }
    }
}

@Composable
internal fun MaterialToolCallCard(
    call: ToolCallView,
    state: ToolCardState,
    onToggle: () -> Unit,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
) {
    // D94: success collapses, failure auto-expands, risk=high approval stays expanded.
    // The page passes `state`; we only render it (and let the header toggle).
    val expanded = state != ToolCardState.Collapsed
    val accent = when {
        state == ToolCardState.PendingApproval -> colors.primary
        call.isError -> colors.error
        else -> colors.onSurfaceVariant
    }
    val stateLabel = when (state) {
        ToolCardState.PendingApproval -> "等待审批 · risk=${call.risk}"
        ToolCardState.Expanded -> if (call.isError) "失败（已展开）" else "已完成（展开）"
        ToolCardState.Collapsed -> "已完成"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapes.medium))
            .background(colors.surface)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(shapes.medium)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("工具 · ${call.name}", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
            Spacer(Modifier.weight(1f))
            Text(stateLabel, fontSize = 10.sp, color = accent)
            Spacer(Modifier.width(6.dp))
            Text(if (expanded) "▾" else "▸", fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
        if (expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Text("参数", fontSize = 10.sp, color = colors.onSurfaceVariant)
                Text(call.args, style = typography.mono, color = colors.onSurface)
                if (call.result != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("结果", fontSize = 10.sp, color = colors.onSurfaceVariant)
                    Text(
                        call.result,
                        style = typography.mono,
                        color = if (call.isError) colors.error else colors.onSurface,
                    )
                }
            }
        }
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
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (attachments != null) {
            attachments()
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp, max = 132.dp)
                    .clip(RoundedCornerShape(shapes.medium))
                    .background(colors.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .alpha(if (enabled) 1f else 0.5f),
            ) {
                if (value.isEmpty()) {
                    Text("输入消息…", style = typography.body, color = colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    textStyle = typography.body.copy(color = colors.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(8.dp))
            // D97: no on-device STT ⇒ draw nothing at all, not a disabled mic.
            if (voiceAvailable) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceVariant)
                        .clickable { },
                    contentAlignment = Alignment.Center,
                ) { Text("◍", fontSize = 13.sp, color = colors.onSurfaceVariant) }
                Spacer(Modifier.width(8.dp))
            }
            val streaming = mode == InputMode.Streaming
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (streaming) colors.error else colors.primary)
                    .clickable(enabled = enabled) { if (streaming) onStop() else onSend() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (streaming) "■" else "↑",
                    fontSize = 13.sp,
                    color = if (streaming) colors.onError else colors.onPrimary,
                )
            }
        }
    }
}
