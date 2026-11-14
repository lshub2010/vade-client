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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
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
 * `docs/visual-language.md` **v2** (§1–§12, 2026-11-14 口径). The rules that drive every
 * shape below:
 *
 *  1. **Six-step radius scale (§10)**: xs 12 / sm 16 / md 20 / lg 24 / xl 28 / sheet 32 —
 *     tool & attachment cards `md`, user bubble `lg` (6dp bottom-end tail), code blocks
 *     and badges `xs`/pill, composer `xl`, dialogs `sheet`; adjacent steps differ ≥4dp
 *     and nested outer ≥ inner + 4.
 *  2. **Hairline + layered shadows (§8)**: static cards = hairline + 极浅 `shadowSoft`
 *     (2dp elevation); float layers (composer / dialog / floating bar) = `shadowFloat`
 *     (16dp elevation); the main action glows with `glowAccent` — never the Material 4dp
 *     elevation ladder, never a coloured shadow (accent glow excepted).
 *  3. **Assistant messages are not bubbles** — full-width text capped at `maxWidth`; only
 *     the user gets a bubble.
 *  4. **Floating composer (§1)**: InputBar is an independent glass float with 16dp
 *     margins and `xl` corners. The send/stop control is NOT drawn inside — v2 §2 puts a
 *     48dp ball **outside** the frame (page layer); the frozen `onSend`/`onStop` params
 *     stay for the page to wire onto that ball.
 *  5. **Every tappable target is ≥44dp** (§3; desktop may go down to 32 — 44 passes both).
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
    // v2 §9 全局弹窗 = 悬浮卡片：遮罩 rgba(0,0,0,.24)（不是纯黑 60%）、四周 12dp 空隙、
    // 四角同圆 sheet(32)、玻璃底 + 发丝线 + 浮层阴影、顶部中央 36×4 grabber。
    // 聊天流不用它（内联审批卡是 chat/ 的活）；这个组件留给模块页面/管理面。
    val cardShape = RoundedCornerShape(shapes.sheet)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.24f))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .shadow(
                    elevation = 16.dp,
                    shape = cardShape,
                    ambientColor = colors.shadowFloat,
                    spotColor = colors.shadowFloat,
                )
                .clip(cardShape)
                .background(colors.surfaceGlass)
                .border(1.dp, colors.hairline, cardShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // grabber：唯一职责是提示“可下拉关闭”（§9）。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.onSurface.copy(alpha = 0.24f)),
            )
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
                // 风险徽标：warn@12% 底的小 pill（§9 卡头「需要批准 + 风险徽标」）。
                Text(
                    text = "risk=${request.risk}",
                    fontSize = 11.sp,
                    color = if (request.risk == "high") colors.error else colors.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(shapes.pill))
                        .background(
                            (if (request.risk == "high") colors.error else colors.onSurfaceVariant)
                                .copy(alpha = 0.12f),
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            // 参数：surfaceSunken + xs 圆角 + 顶部标签，等宽、可滚（FR-8.53）。
            CodeBlock(
                text = request.arguments,
                label = "ARGS",
                colors = colors,
                shapes = shapes,
                typography = typography,
                modifier = Modifier.heightIn(max = 180.dp),
            )
            Text(
                text = "剩余 ${remainingSec}s · 超时按拒绝处理",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 主行动带 accent 辉光（§8 row 3）；其余是 hairline 幽灵按钮。
                // 文案保留文字是 §3 的例外口径：这个对话框只出现在模块页面/管理面，
                // “仅本次 / 本会话”是 scope 语义，图标表达不了。
                DialogButton(
                    label = "仅本次",
                    fill = colors.primary,
                    fg = colors.onPrimary,
                    radius = shapes.pill,
                    glow = colors.glowAccent,
                    onClick = { onDecide(Decision.Approve, Scope.Once) },
                )
                DialogButton(
                    label = "本会话",
                    fill = Color.Transparent,
                    fg = colors.onSurface,
                    radius = shapes.sm,
                    border = colors.hairline,
                    onClick = { onDecide(Decision.Approve, Scope.Session) },
                )
                DialogButton(
                    label = "拒绝",
                    fill = Color.Transparent,
                    fg = colors.error,
                    radius = shapes.sm,
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
    glow: Color? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
            .then(
                if (glow != null) {
                    // 辉光不是阴影：accent 色的低高度投影在外圈读成柔光（§8 row 3）。
                    Modifier.shadow(6.dp, shape, ambientColor = glow.copy(alpha = 0.35f), spotColor = glow.copy(alpha = 0.35f))
                } else {
                    Modifier
                },
            )
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
                // 助手：无气泡、无边框、无底色，只有正文（“读起来像文档”，§五/v2 §4）。
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

                // 用户：surface 底 + hairline，lg(24) 圆角 + 右下 6dp 气泡尾（v2 §10）。
                else -> Box(
                    Modifier
                        .widthIn(max = minOf(container * 0.78f, maxWidth))
                        .clip(
                            RoundedCornerShape(
                                topStart = shapes.lg,
                                topEnd = shapes.lg,
                                bottomEnd = 6.dp,
                                bottomStart = shapes.lg,
                            ),
                        )
                        .background(colors.surface)
                        .border(
                            1.dp,
                            colors.hairline,
                            RoundedCornerShape(
                                topStart = shapes.lg,
                                topEnd = shapes.lg,
                                bottomEnd = 6.dp,
                                bottomStart = shapes.lg,
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
                                .heightIn(min = 44.dp)
                                .clickable { onAction(action) }
                                .padding(vertical = 10.dp),
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
    // v2 §10：附件卡 md(20)；缩略图 28×28 xs(12)。
    val shape = RoundedCornerShape(shapes.md)
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, if (failed) colors.error.copy(alpha = 0.6f) else colors.hairline, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
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
            Spacer(Modifier.width(4.dp))
            // 纯图标 ↻（v2 §3：禁止文字按钮）；44dp 触摸区。
            IconTap(onClick = onRetry) { Text("↻", fontSize = 16.sp, color = colors.primary) }
        }
        if (onRemove != null) {
            Spacer(Modifier.width(4.dp))
            IconTap(onClick = onRemove) { VadeIcon(VadeIcons.Close, contentDescription = "移除附件", size = 14.dp, tint = colors.onSurfaceVariant) }
        }
    }
}

/** 44dp 见方的图标点击区（v2 §3 触摸目标 ≥44；视觉 28dp 级图标居中其中）。 */
@Composable
private fun IconTap(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
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
    // v2 §8 row 1 静态卡：md(20) + 发丝线 + 极浅 shadowSoft（2dp elevation）。
    val shape = RoundedCornerShape(shapes.md)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, shape, ambientColor = colors.shadowSoft, spotColor = colors.shadowSoft)
            .clip(shape)
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
                        CodeBlock(
                            text = call.args,
                            label = "ARGS",
                            colors = colors,
                            shapes = shapes,
                            typography = typography,
                        )
                        if (call.result != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("结果", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            CodeBlock(
                                text = call.result,
                                label = "RESULT",
                                colors = colors,
                                shapes = shapes,
                                typography = typography,
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

/**
 * v2 代码块（§五/§10）：`surfaceSunken`（= `colors.surfaceVariant` #F0F0F2）+ xs(12) 圆角
 * + 顶部小标签行。[label] 是内容种类（ARGS / RESULT / 语言名），由调用方给。
 */
@Composable
private fun CodeBlock(
    text: String,
    label: String?,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
    ink: Color = colors.onSurface,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapes.xs))
            .background(colors.surfaceVariant),
    ) {
        if (label != null) {
            Text(
                text = label,
                fontSize = 10.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(
            text = text,
            style = typography.mono,
            color = ink,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        )
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
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // v2 §1 悬浮 composer：不再是贴底满宽的一行，而是一个独立浮层 —— 四周留白 16dp、
    // xl(28) 圆角（多行同圆角，不做胶囊变形）、玻璃底 surfaceGlass、发丝线 + 浮层阴影
    // （§8 row 2：浮层必须有阴影）。真正的背景模糊由 shell/ 在根上接 hazeSource 后换
    // skin/glass/GlassSurface；页面结构（composer 浮在列表上）也是 chat/ 的活，这里只
    // 负责“浮起来”的形状与质感。
    //
    // v2 §2：发送/停止控件不在框内 —— 它们是框外一颗 48dp 正圆球（页面层绘制：有内容 =
    // accent 实心 + glowAccent 辉光，空 = 玻璃 + 发丝线，生成中同一球位变 ■ 停止态）。
    // 冻结签名里的 onSend / onStop / sendEnabled 原样保留，由页面层接到那颗球上；
    // ⌘Enter / Ctrl+Enter 的发送路径在 ChatThread.handleComposerKeys，不受影响。
    val panelShape = RoundedCornerShape(shapes.xl)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .shadow(
                elevation = 16.dp,
                shape = panelShape,
                ambientColor = colors.shadowFloat,
                spotColor = colors.shadowFloat,
            )
            .clip(panelShape)
            .background(colors.surfaceGlass)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) colors.primary else colors.hairline,
                shape = panelShape,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (attachments != null) {
            attachments()
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            // 文本域直接落在玻璃面板上（不再嵌一个内层框 —— §10 同屏圆角 ≤3 种）；
            // 多行最高 maxLines 行后内部滚动。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = (maxLines * 22 + 24).dp)
                    .alpha(if (enabled) 1f else 0.5f),
                contentAlignment = Alignment.CenterStart,
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
            // D97: 无端侧 STT 时不画麦克风（不是画一个禁用的）。
            if (voiceAvailable) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceVariant)
                        .clickable { },
                    contentAlignment = Alignment.Center,
                ) { Text("◍", fontSize = 16.sp, color = colors.onSurfaceVariant) }
            }
        }
    }
}
