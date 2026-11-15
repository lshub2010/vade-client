package vade.ui.skin.miuix

import androidx.compose.animation.animateContentSize
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Button
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow
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
import vade.ui.skin.SkinMotion
import vade.ui.skin.SkinOrb
import vade.ui.skin.SkinShapes
import vade.ui.skin.SkinTypography
import vade.ui.skin.ToolCallView
import vade.ui.skin.ToolCardState
import vade.ui.skin.cardAccentBar

/**
 * `miuix` implementations of C-09 components 7-11.
 *
 * Same rule as `MaterialParts.kt`: free composables that `MiuixSkin` delegates to, so the
 * Skin class stays readable. Every container is a Miuix `Surface` with a 1 dp stroke and the
 * v2 §8 layering (static cards get `shadowSoft`, float layers get `shadowFloat`), and corners
 * stay on the v2 six-step ladder (xs 12 / sm 16 / md 20 / lg 24 / xl 28 / sheet 32).
 */

// --- slot consumption (C-09 contract, not a style call) ----------------------

/**
 * Consumes a C-09 **slot** — in exactly one place, so no component can quietly forget it.
 *
 * `MessageBubble.body` (rich text) and `ToolCallCard.meta` (duration / batch / error-code rows)
 * were added to the frozen contract *after* the freeze, and both mean the same thing: the page
 * owns the content, the skin owns the frame. A skin may pick its own padding, line height,
 * corner radius and colour for the slot — it may **not** drop it, because the data then simply
 * is not on screen under that skin (measured A/B on one session: shots 26/33 `material` vs
 * 27/32 `miuix` in `shots/g-visual-audit2/RESULT.md` · D3/D4). Two skins are allowed to look
 * different; they are not allowed to disagree about what the message contains.
 *
 * [fallback] is what the component drew before the slot existed, byte for byte, so passing
 * `null` keeps the frozen rendering.
 */
@Composable
private fun SlottedOrFallback(
    slot: (@Composable () -> Unit)?,
    fallback: @Composable () -> Unit,
) {
    if (slot != null) slot() else fallback()
}

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
                            .background(MiuixTokens.sunken(colors))
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
 *
 * **The `body` slot is consumed in all three roles** (C-09 contract, added after the freeze):
 * the page hands in its markdown renderer and the bubble draws *that* inside its own frame.
 * The frame stays miuix's (padding 14/10 in the user bubble, the 11 sp pill for system, no
 * container for the assistant) — but the rich text must go through `body()`. The previous
 * revision took the parameter and rendered `Text(message.text)` anyway, so `**原图未保留**`,
 * `` `UP-TO-DATE` `` and ```kotlin fences reached the screen as literal asterisks on this skin
 * and only on this skin (`shots/g-visual-audit2` · D3, A/B 26/33 vs 27/32).
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
                    SlottedOrFallback(body) {
                        Text(
                            text = message.text,
                            style = typography.body,
                            color = if (message.isError) colors.error else colors.onSurface,
                        )
                    }
                }

                // --- system: centred caption pill ------------------------------------
                system -> Box(
                    Modifier
                        .widthIn(max = maxWidth)
                        .clip(RoundedCornerShape(shapes.pill))
                        .background(colors.surfaceVariant)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    SlottedOrFallback(body) {
                        Text(
                            text = message.text,
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }

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
                        SlottedOrFallback(body) {
                            Text(
                                text = message.text,
                                style = typography.body,
                                color = if (message.isError) colors.error else colors.onSurface,
                            )
                        }
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
                    // v2 §3 图标即按钮：重试用 Refresh 矢量，不用文字（w-g-icons 起非 ↻ 字符）。
                    VadeIcon(
                        vector = VadeIcons.Refresh,
                        contentDescription = "重试",
                        size = 14.dp,
                        tint = colors.primary,
                        modifier = Modifier.clickable { onRetry() },
                    )
                }
                if (onRemove != null) {
                    Spacer(Modifier.width(8.dp))
                    VadeIcon(
                        vector = VadeIcons.Close,
                        contentDescription = "移除附件",
                        size = 14.dp,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.clickable { onRemove() },
                    )
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
 *
 * Both frozen slots are consumed: [call] args/result render as labelled `ARGS` / `RESULT`
 * wells (the v2 code-block shape, on this skin's tokens), and [meta] — the duration / batch /
 * error-code / started-at rows the frozen `ToolCallView` has no field for — is drawn whenever
 * the page passes it. The *page* decides to pass `null` while collapsed (`ChatThread.ToolRow`);
 * the skin never drops content it was handed.
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
                )
                .clip(RoundedCornerShape(shapes.md))
                // 左侧状态条：顶端沿 md(20dp) 圆弧走、3dp 收细到 0、65% 高度处消失。
                // 半径用形状 token（不是写死的数）；三端同一份绘制代码。
                .cardAccentBar(accent, cornerRadius = shapes.md),
            shape = RoundedCornerShape(shapes.md),
            color = colors.surface,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, if (call.isError) colors.error.copy(alpha = 0.5f) else colors.hairline),
            shadowElevation = 0.dp,
        ) {
            Column {
                Row(
                    // 原来这里是「3dp 竖条 + 9dp 间隔」= 正文 12dp 起排；竖条改由 `cardAccentBar`
                    // 画在卡片背景上之后，内边距直接站在 12dp。
                    modifier = Modifier.fillMaxWidth().clickable { onToggle() }
                        .padding(start = 12.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                    // 展开/收起 affordance：真矢量 chevron（v2 §3 禁字符图标）。
                    VadeIcon(
                        vector = if (expanded) VadeIcons.ChevronDown else VadeIcons.ChevronRight,
                        contentDescription = null,
                        size = 12.dp,
                        tint = colors.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    Column(
                        Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text("参数", fontSize = MiuixTokens.tinySize, color = colors.onSurfaceVariant)
                        MiuixCodeBlock(
                            text = call.args,
                            label = "ARGS",
                            colors = colors,
                            typography = typography,
                        )
                        if (call.result != null) {
                            Text("结果", fontSize = MiuixTokens.tinySize, color = colors.onSurfaceVariant)
                            MiuixCodeBlock(
                                text = call.result,
                                label = "RESULT",
                                colors = colors,
                                typography = typography,
                                ink = if (call.isError) colors.error else colors.onSurface,
                            )
                        }
                    }
                }
                // meta 槽（C-09）：耗时 / 批次 / 错误码 / 发起时间。**必须消费** —— 页面在折叠时
                // 传 null（`ChatThread.ToolRow`），展开时把这四行交进来；皮肤只负责「有就画」，
                // 与 material 同构。曾经整个形参被丢掉，四行在 miuix 下凭空消失
                // （`shots/g-visual-audit2` · D4）。
                if (meta != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    ) { meta() }
                }
            }
        }
    }
}

/**
 * The card's inline code well (`ARGS` / `RESULT`) — the miuix counterpart of `material`'s
 * `CodeBlock` (v2 §五/§10: sunken base + xs-step corners + a small label row).
 *
 * Same *job*, this skin's tokens: the base is [MiuixTokens.sunken] (light = the warm grey
 * `surfaceVariant`, dark = the pure black Miuix dark already uses), corners are
 * [MiuixTokens.radiusXs] (the ladder's "代码块" step), and the label row is separated by a
 * 1 dp `hairline` instead of material's translucent white overlay. The label is drawn in the
 * mono family so `ARGS` / `RESULT` read as a code kind, not as prose.
 *
 * Body height is capped and scrolls inside the well (140 dp header→body, same ceiling the
 * inline 参数 well used) so a long redacted argument list can never push the meta rows out of
 * the card.
 */
@Composable
private fun MiuixCodeBlock(
    text: String,
    label: String,
    colors: SkinColors,
    typography: SkinTypography,
    ink: Color = colors.onSurface,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiuixTokens.radiusXs))
            .background(MiuixTokens.sunken(colors)),
    ) {
        Text(
            text = label,
            fontFamily = typography.mono.fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = MiuixTokens.tinySize,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(MiuixTokens.dividerThickness)
                .background(colors.hairline),
        )
        Text(
            text = text,
            style = typography.mono,
            color = ink,
            modifier = Modifier
                .heightIn(max = 140.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

// --- 11 · InputBar -----------------------------------------------------------

/**
 * The v2 **floating composer**'s field (§1) — RFC-0011 narrowed it to the *input area only*:
 *
 *  - an **independent float layer** — `xl` (28 dp) corners, glass base (`surfaceGlass` +
 *    1 dp hairline) and a real `shadowFloat` (§8 row 2: a float layer is defined by its
 *    shadow). The 16 dp clear on every side is **page layout** now (RFC-0011 D5): the page
 *    composes `[＋] [InputBar] [12 dp] [SendOrb]` in one row, and component-side margins
 *    would stack into 32 dp / a 28 dp frame-ball gap, breaking §1/§2's numbers;
 *  - focus is tracked from the field's own InteractionSource, so the 2 dp accent ring
 *    appears only when the caret is really in it — not a glow, not an underline;
 *  - the send control is **not here**: v2 §2 made it the separate [MiuixSendOrb]
 *    (C-09 component 12, RFC-0011) the page places outside the frame;
 *  - `voiceAvailable = false` draws nothing at all (D97) — not a greyed-out mic.
 *
 * The glass is the *degrade* shape for now: `surfaceGlass` tint + hairline + `shadowFloat`.
 * A real backdrop blur (§5) needs `skin/glass/GlassSurface` wired inside the skin
 * (RFC-0011 §7.2) — not this batch.
 */
@Composable
internal fun MiuixInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    mode: InputMode,
    attachments: (@Composable () -> Unit)?,
    enabled: Boolean,
    voiceAvailable: Boolean,
    maxLines: Int,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
    motion: SkinMotion,
) {
    MiuixScope(colors) {
        val fieldShape = RoundedCornerShape(shapes.xl)
        Column(Modifier.fillMaxWidth()) {
            if (attachments != null) {
                attachments()
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val interaction = remember { MutableInteractionSource() }
                val focused by interaction.collectIsFocusedAsState()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // §8 row 2: the composer is a float layer — but the shadow must follow
                        // the SHAPE, not its bounding rectangle. `Modifier.shadow` receives
                        // `fieldShape` and still knocks its hole out as an inset rectangle;
                        // 78 % surfaceGlass shows that rectangle through, leaving four square
                        // corners where the panel curls (g-composer-corner). layeredShadow
                        // draws one gaussian halo per layer from the shape's RoundedCornerRect.
                        .layeredShadow(VadeShadows.float, fieldShape, dark = colors.isDark, outsideOnly = true)
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
                        // g-shadow-android：多行高度跟随（200ms glide）画在文本域上 —— 动画
                        // 节点在胶囊**里面**，胶囊（和它的下行阴影）在裁剪层之外。页面原来
                        // 的 animateContentSize 包装自带裁剪层，会把下沿之外的阴影裁成
                        // 0 像素（probe-v3: shelf-acs 实测全平）。
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 32.dp)
                                .animateContentSize(
                                    animationSpec = tween(durationMillis = 200, easing = motion.easing),
                                ),
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
                                cursorBrush = SolidColor(colors.primary),
                                maxLines = maxLines,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The §2 send ball (RFC-0011 · C-09 component 12): 48 dp, *outside* the field — the page
 * composes it 12 dp to the right of [MiuixInputBar], vertically centred. Three states from
 * [mode]: Idle = glass + hairline, muted glyph, still clickable (§2 触发提示);
 * Composing = `accent` solid, on-accent glyph; Streaming = the same slot turns **stop**,
 * tinted icon + stroke only, never a red ball. Glow is a 12 dp radial halo behind the ball
 * (`glowAccent` @ 28 % → transparent, §8 row 3) — a glow, not a shadow; press scales the
 * whole thing (halo included) by [SkinOrb.pressScale] over [SkinOrb.pressDurationMs] (§2).
 */
@Composable
internal fun MiuixSendOrb(
    mode: InputMode,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    orb: SkinOrb,
    motion: SkinMotion,
    colors: SkinColors,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) orb.pressScale else 1f,
        animationSpec = tween(durationMillis = orb.pressDurationMs, easing = motion.easing),
        label = "sendOrbPress",
    )
    val streaming = mode == InputMode.Streaming
    // `armed` is the accent state: content present (draft text / staged attachments).
    val armed = mode == InputMode.Composing
    // §2: an empty ball stays clickable ("触发提示"); only a fully disabled orb is inert,
    // and the streaming stop always answers (interrupting must never be disabled).
    val clickable = streaming || enabled
    val haloSize = orb.size + orb.glowRadius * 2 // glowRadius of halo on every side
    Box(modifier = modifier.size(haloSize), contentAlignment = Alignment.Center) {
        if (armed) {
            Box(
                Modifier
                    .size(haloSize)
                    .background(
                        brush = Brush.radialGradient(
                            // Hold ~full strength up to the ball's rim, fade over the glow ring.
                            0.64f to colors.glowAccent.copy(alpha = 0.28f),
                            1f to Color.Transparent,
                        ),
                        shape = CircleShape,
                    ),
            )
        }
        Box(
            modifier = Modifier
                .size(orb.size)
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
                        armed -> Modifier.background(colors.primary)
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
                ) { onClick() }
                .semantics { contentDescription = if (streaming) "停止生成" else "发送消息" },
            contentAlignment = Alignment.Center,
        ) {
            // 发送/停止球心：矢量 Send / 唯一填充例外 Stop（v2 §3.1）。语义已挂在球的
            // contentDescription 上（"发送消息"/"停止生成"），图标本身装饰性。
            VadeIcon(
                vector = if (streaming) VadeIcons.Stop else VadeIcons.Send,
                contentDescription = null,
                size = 20.dp,
                tint = when {
                    streaming -> colors.error
                    armed -> colors.onPrimary
                    else -> colors.onSurfaceVariant
                },
            )
        }
    }
}

/** D97: mic button — a hairline glass orb carrying the real mic vector (no `◉` glyph placeholder). */
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
        VadeIcon(VadeIcons.Mic, contentDescription = "语音输入", size = 14.dp, tint = colors.onSurfaceVariant)
    }
}
