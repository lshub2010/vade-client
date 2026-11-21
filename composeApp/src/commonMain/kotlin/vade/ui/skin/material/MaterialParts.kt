package vade.ui.skin.material

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vade.ui.component.liquid.VadeShadows
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
import vade.ui.skin.Skin
import vade.ui.skin.SkinColors
import vade.ui.skin.SkinMotion
import vade.ui.skin.SkinOrb
import vade.ui.skin.SkinShapes
import vade.ui.skin.SkinTypography
import vade.ui.skin.ToolCallView
import vade.ui.skin.ToolCardState
import vade.ui.skin.cardAccentBar
import vade.ui.skin.glass.GlassSurface

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
            // 纯图标 Refresh（v2 §3：禁止文字按钮；w-g-icons 起为真矢量，非 ↻ 字符）；44dp 触摸区。
            IconTap(onClick = onRetry) { VadeIcon(VadeIcons.Refresh, contentDescription = "重试", size = 16.dp, tint = colors.primary) }
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
            // 状态条必须在 `.background` / `.border` **之前**：`Modifier.border` 是画在内容
            // 之后的（画完 content 再描边），所以竖条若挂在最后，它左侧 1dp 会被发丝线盖住，
            // 实宽从 3dp 掉到 2dp 并留下一条缝。挂在最外层后 drawContent() 把底色 + 发丝线
            // 全画完，竖条最后覆盖上去。
            // 顶端沿 md(20dp) 圆弧走、3dp 收细到 0、65% 高度处收尖；半径用形状 token。
            .cardAccentBar(status, cornerRadius = shapes.md)
            .background(colors.surface)
            .border(1.dp, colors.hairline, shape),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable(onClick = onToggle)
                        // 原来这里左边是「3dp 竖条 + 13dp 内边距」= 16dp；竖条改由
                        // `cardAccentBar` 画在卡片背景上之后，正文内边距直接站在 16dp。
                        .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
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
                    // 展开/收起 affordance：真矢量 chevron（v2 §3 禁字符图标）。
                    VadeIcon(
                        vector = if (expanded) VadeIcons.ChevronDown else VadeIcons.ChevronRight,
                        contentDescription = null,
                        size = 12.dp,
                        tint = colors.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    Column(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 12.dp)) {
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
                            .padding(start = 16.dp, end = 12.dp, bottom = 12.dp),
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
    attachments: (@Composable () -> Unit)?,
    enabled: Boolean,
    voiceAvailable: Boolean,
    maxLines: Int,
    // Post-freeze additive slot (see Skin.InputBar KDoc): page-level control sharing the
    // panel's existing bottom row. null = frozen bottom row, byte-for-byte.
    leading: (@Composable () -> Unit)?,
    // Post-freeze additive slot #2 (see Skin.InputBar KDoc): page-level controls on a
    // SECOND line, inside this same panel (model chip). null = nothing drawn at all.
    secondRow: (@Composable () -> Unit)?,
    skin: Skin,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
    motion: SkinMotion,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()

    // v2 §1 悬浮 composer：xl(28) 圆角、**真玻璃底**、发丝线 + 浮层阴影（§8 row 2）。
    //
    // P0-1（审查 r1-client-chat）：这里原来是一块实色 `.background(colors.surfaceGlass)` ——
    // 84 % 白/78 % 黑，没有任何模糊，而同一屏的底栏 FloatingBlur 是真的在糊。视觉语言
    // `docs/visual-language.md:233` 把「悬浮 composer」列进**必须模糊的层**，所以底座换成
    // 与底栏同一条玻璃实现 [GlassSurface]：tint 只走 `colorEffects(listOf(HazeColorEffect
    // .tint(tint)))` 那一行（`SkinGlass` 的 26 %/34 %，底栏读的就是同一份 token）。
    //
    // 没有 backdrop（单测/预览/页面还没接 `GlassScope`）时必须**逐像素**退回原来那块实色：
    // [GlassSurface] 的 `degradeColor = colors.surfaceGlass` 就是这条退化 —— 采不到背景就
    // 根本不建 haze 节点，直接画同一个 surfaceGlass，描边 (1dp hairline / 聚焦 2dp accent)
    // 与下行阴影（同一个 `downwardShelf`，`outsideOnly` 语义）都还是原来的那一份，所以所有
    // composer 像素钉不动。
    //
    // RFC-0011：面板**不再自带 16dp 浮层外边距** —— 页面把 [＋] [InputBar] [12dp] [SendOrb]
    // 拼进同一行时，组件内边距会叠加成 32dp、框球间距变成 28dp，§1/§2 的数字全部失真
    // （RFC §4.1/D5）。面板紧贴自身槽位，边距归页面布局。
    //
    // 发送/停止控件不在这里：v2 §2 把它们移出框外成独立圆球 —— Skin.SendOrb（组件 12）。
    val panelShape = RoundedCornerShape(shapes.xl)
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = panelShape,
        skin = skin,
        // §五 输入框：静止 1dp hairline、聚焦 2dp accent —— 与 miuix/glass 同一条语义。
        stroke = if (focused) colors.primary else colors.hairline,
        strokeWidth = if (focused) 2.dp else 1.dp,
        // §8.2 shadow2 档位显式写出来（默认值就是它）：这一层是**浮层**，不是静态卡。
        //
        // g-composer-corner：阴影必须**跟着面板形状**画。原来是 `Modifier.shadow(elevation
        // = 16.dp, shape = panelShape, …)` —— shape 传了，但 Compose/Skia 的仰角阴影把
        // 「形状正下方不画阴影」那块挖空做成了**外接矩形内缩**，84 % 的 surfaceGlass 把那块
        // 矩形透出来，圆角处就露四个方角（桌面密度 1 实测：胶囊 x40..560/y40..107 内出现
        // x49..551/y61..87 的矩形，每条边 4/255 台阶）。GlassSurface 的 floatShell 走
        // `downwardShelf`：用 shape 的 RoundedCornerRect 做轮廓、只向下扩散（§8.3），四条边
        // 与圆角都跟面板走，且不挖矩形空。
        shadow = VadeShadows.float,
        degradeColor = colors.surfaceGlass,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // g-shadow-android：§7 的多行高度跟随（200ms glide）必须画在**内容**上，不能画在
                // 页面包装上 —— 页面原来的 animateContentSize 包装自带裁剪层（rest 态也在），
                // 会把面板下沿之外的整条下行阴影裁成 0 像素（probe-v3: shelf-acs 实测全平）。
                // 动画节点收在玻璃**里面**：玻璃与其阴影在裁剪层之外，阴影跟着动画后的下沿走。
                .animateContentSize(
                    animationSpec = tween(durationMillis = 200, easing = motion.easing),
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (attachments != null) {
                attachments()
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                // `leading` 槽（C-09 追加，见 Skin.InputBar KDoc）：行首、与文本域同一行；
                // null 时整段不组成，冻结布局逐像素不变。
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(10.dp))
                }
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
                    ) {
                        // D97 的语音按钮：真麦克风矢量（w-g-icons 起，`◍` 字符占位退场）。
                        VadeIcon(VadeIcons.Mic, contentDescription = "语音输入", size = 16.dp, tint = colors.onSurfaceVariant)
                    }
                }
            }
            // 第二行（C-09 追加槽 secondRow）：**同一块面板内**、输入行下方 —— 共享
            // 玻璃底 / 发丝线 / xl 圆角，不是另起一层 surface。null 时这一段整块不组成
            // （没有 Spacer、没有节点），冻结布局逐像素不变。
            if (secondRow != null) {
                Spacer(Modifier.height(8.dp))
                secondRow()
            }
        }
    }
}

/**
 * v2 §2 发送球（RFC-0011 · C-09 组件 12）：48dp 正圆、**框外独立元素** —— 页面把它拼在
 * InputBar 右侧 12dp 处。三态由 [mode] 驱动（页面握 policy，皮肤握像素）：
 *
 *  - Idle      = 空态：surfaceGlass + 1px hairline，图标 muted，**仍可点**（§2 触发提示）；
 *  - Composing = armed：accent 实心 + 向外 glowRadius(12dp) 柔光（glowAccent @28% 渐隐，
 *                §8 row 3 辉光不是阴影）+ 反白箭头；
 *  - Streaming = 停止：同一球位变 ■，只染图标与 1.5dp 描边为 danger，**不整球变红**（§2）。
 *
 * 按压 = scale [SkinOrb.pressScale] / [SkinOrb.pressDurationMs]，曲线 [SkinMotion.easing]。
 * 图标是 Canvas 描边矢量（v2 §3.1）：皮肤层不能 import chat/ 的图标包，两枚自绘。
 */
@Composable
internal fun MaterialSendOrb(
    mode: InputMode,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    orb: SkinOrb,
    motion: SkinMotion,
    colors: SkinColors,
) {
    val streaming = mode == InputMode.Streaming
    val armed = mode == InputMode.Composing

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) orb.pressScale else 1f,
        animationSpec = tween(durationMillis = orb.pressDurationMs, easing = motion.easing),
        label = "sendOrbPress",
    )

    Box(
        modifier = modifier
            .size(orb.size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (!enabled && !streaming) 0.45f else 1f
            }
            .drawBehind {
                // §8 row 3：辉光不是阴影 — accent 28%，向外 glowRadius 渐隐；只在 armed 画。
                if (armed) {
                    val outer = size.minDimension / 2f + orb.glowRadius.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.62f to colors.glowAccent.copy(alpha = 0.28f),
                            1f to colors.glowAccent.copy(alpha = 0f),
                            center = center,
                            radius = outer,
                        ),
                        radius = outer,
                    )
                }
            }
            .clip(CircleShape)
            .background(if (armed) colors.primary else colors.surfaceGlass)
            .border(
                width = if (streaming) 1.5.dp else 1.dp,
                color = when {
                    streaming -> colors.error
                    armed -> Color.Transparent
                    else -> colors.hairline
                },
                shape = CircleShape,
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                // §2：空球仍可点（触发提示）；只有页面显式禁用（enabled=false）时静止，
                // 流式停止不受 enabled 影响 —— 中断生成永远可用。
                enabled = streaming || enabled,
            ) { onClick() }
            .semantics { contentDescription = if (streaming) "停止生成" else "发送消息" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(orb.size * 0.42f)) {
            if (streaming) {
                paintOrbStop(colors.error)
            } else {
                paintOrbSend(if (armed) colors.onPrimary else colors.onSurfaceVariant)
            }
        }
    }
}

/** ↑ 发送箭头 — Canvas 描边（v2 §3.1 矢量、线宽随尺寸缩放）。 */
private fun DrawScope.paintOrbSend(color: Color) {
    val s = size.minDimension
    val cx = size.width / 2f
    val stroke = s * 0.10f
    drawLine(color, Offset(cx, s * 0.80f), Offset(cx, s * 0.24f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, Offset(cx, s * 0.24f), Offset(cx - s * 0.24f, s * 0.48f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, Offset(cx, s * 0.24f), Offset(cx + s * 0.24f, s * 0.48f), strokeWidth = stroke, cap = StrokeCap.Round)
}

/** ■ 停止 — 填充圆角方块（§2：方形图标，不是红球）。 */
private fun DrawScope.paintOrbStop(color: Color) {
    val s = size.minDimension
    drawRoundRect(
        color = color,
        topLeft = Offset(s * 0.22f, s * 0.22f),
        size = Size(s * 0.56f, s * 0.56f),
        cornerRadius = CornerRadius(s * 0.09f),
    )
}
