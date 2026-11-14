package vade.ui.skin.material

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.BtnStyle
import vade.ui.skin.NavItem
import vade.ui.skin.glass.GlassSurface

/**
 * `material` skin — v2 观感（docs/visual-language.md §1–§12，2026-11-14 口径）。
 *
 * The skin id stays `material`; the look is the v2 graphite-neutral system: 石墨中性色 +
 * 单一强调色，六档大圆角，发丝线 + 分层阴影，纯图标按钮。What was dropped:
 *
 *  - `material3.OutlinedTextField` / `material3.Switch` — without an M3 theme they fall
 *    back to the baseline Material palette (the banned default purple) on focus / checked
 *    states. Both are rebuilt on foundation primitives so every colour comes from
 *    [vade.ui.skin.SkinColors] (w1-material task §8: no Material purple).
 *  - the 4dp elevation ladder — replaced by §8's two shadow tiers (`shadowSoft` for
 *    static cards, `shadowFloat` for float layers) plus `glowAccent` for main actions.
 *
 * [skinId] / [notInstalledNotice] exist so [vade.ui.skin.SkinRegistry] can register
 * token-compatible *placeholders* for `glass` without inventing a second skin.
 */
class MaterialSkin(
    private val skinId: String = "material",
    private val notInstalledNotice: String? = null,
) : vade.ui.skin.Skin {

    override val id: String = skinId

    override val colors = vade.ui.skin.SkinColors(
        // Visual language §2 light tokens: graphite neutrals + one indigo accent. Colour
        // appears only on accent/status surfaces — never as a large tonal block.
        primary = Color(0xFF5B5BD6),
        onPrimary = Color(0xFFFFFFFF),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0F0F2), // surfaceSunken: code blocks, chips, inputs
        onSurface = Color(0xFF17171A),
        onSurfaceVariant = Color(0xFF6B6B73),
        outline = Color(0xFFE6E6E9), // hairline
        error = Color(0xFFD93A3A),
        onError = Color(0xFFFFFFFF),
        isDark = false,
        canvas = Color(0xFFF7F7F8),
        hairline = Color(0xFFE6E6E9),
        success = Color(0xFF2F7D5A),
        warning = Color(0xFFB26A00),
        // --- v2 §5/§8 glass + shadow tokens -----------------------------------------
        // surfaceGlass is a *thin* translucent base so the hairline + shadow carry the
        // float; the real backdrop blur is `skin/glass/GlassSurface` once shell/ wires the
        // root hazeSource (MaterialParts documents this at the composer).
        surfaceGlass = Color(0xFFFFFFFF).copy(alpha = 0.84f),
        // §8 row 1 static-card shadow rendered at 2dp elevation; row 2 float shadow at 16dp.
        shadowSoft = Color(0x4D000000), // black @30%  → ≈ 0 1 2 rgba(0,0,0,.06)
        shadowFloat = Color(0x80000000), // black @50% → ≈ 0 12 32 rgba(0,0,0,.18)
        glowAccent = Color(0xFF5B5BD6), // §8 row 3: the accent glow of a primary action
        textOnGlass = Color(0xFF17171A),
    )

    /**
     * Visual language §10 six-step scale (v2 覆盖 §三): xs 12 / sm 16 / md 20 / lg 24 /
     * xl 28 / sheet 32 + pill. Adjacent steps differ ≥4dp; the v1 aliases point at the
     * raised steps by name (MiuixParts and the pages read them).
     */
    override val shapes = vade.ui.skin.SkinShapes(
        small = 16.dp,
        medium = 20.dp,
        large = 24.dp,
        pill = 999.dp,
        xs = 12.dp,
        sm = 16.dp,
        md = 20.dp,
        lg = 24.dp,
        xl = 28.dp,
        sheet = 32.dp,
    )
    override val typography = vade.ui.skin.SkinTypography()
    override val motion = vade.ui.skin.SkinMotion()

    // --- 1 · Button ----------------------------------------------------------

    @Composable
    override fun Button(text: String, onClick: () -> Unit, style: BtnStyle, enabled: Boolean) {
        val c = colors
        val bg: Color
        val fg: Color
        val radius: Dp
        val border: Color?
        when (style) {
            // 主按钮 pill：accent 底 + 反白内容（视觉规范 §5）；发送球这类主行动的辉光
            // 由调用方用 colors.glowAccent 画（§8 row 3），按钮本身不带阴影。
            BtnStyle.Primary -> { bg = c.primary; fg = c.onPrimary; radius = shapes.pill; border = null }
            // 次要按钮 sm(16dp)：透明底 + hairline.
            BtnStyle.Secondary -> { bg = Color.Transparent; fg = c.onSurface; radius = shapes.sm; border = c.hairline }
            BtnStyle.Danger -> { bg = c.error; fg = c.onError; radius = shapes.sm; border = null }
            // 文字样式按次要样式处理：透明底 + accent 文字，不变成蓝底填充（w1 任务 §5）。
            BtnStyle.Text -> { bg = Color.Transparent; fg = c.primary; radius = shapes.sm; border = null }
        }
        val fill = if (!enabled && style == BtnStyle.Primary) c.onSurfaceVariant.copy(alpha = 0.30f) else bg
        val shape = RoundedCornerShape(radius)
        // v2 §3 图标即按钮：页面把单个字形（↑ ■ ＋ ✕ ⌕ …）传进 text 时按 20dp 级图标渲染，
        // 并保证 44dp 见方的可点面积；多字文案只出现在 §3 允许的例外里（破坏性确认 /
        // 首次引导主行动），按次要字号渲染。
        val isGlyph = text.length == 1
        Box(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .widthIn(min = if (isGlyph) 44.dp else 64.dp)
                .alpha(if (enabled) 1f else if (style == BtnStyle.Primary) 1f else 0.42f)
                .clip(shape)
                .background(fill)
                .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = if (isGlyph) 8.dp else 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = if (!enabled) c.onSurfaceVariant else fg,
                fontSize = if (isGlyph) 18.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // --- 2 · TextField -------------------------------------------------------

    @Composable
    override fun TextField(
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String?,
        error: String?,
        enabled: Boolean,
    ) {
        // Foundation 原语重写：M3 OutlinedTextField 无主题时的聚焦描边正是被禁的
        // Material 默认紫；这里聚焦 = 2dp accent（视觉规范 §5，不是发光）。
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val shape = RoundedCornerShape(shapes.sm)
        val borderColor = when {
            error != null -> colors.error
            focused -> colors.primary
            else -> colors.hairline
        }
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clip(shape)
                    .background(colors.surface)
                    .border(width = if (focused) 2.dp else 1.dp, color = borderColor, shape = shape)
                    .alpha(if (enabled) 1f else 0.5f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    interactionSource = interaction,
                    textStyle = typography.body.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (error != null) {
                Text(
                    text = error,
                    color = colors.error,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }
    }

    // --- 3 · Card ------------------------------------------------------------

    @Composable
    override fun Card(modifier: Modifier, content: @Composable () -> Unit) {
        // v2 §8 row 1 静态卡：md(20) 圆角 + 发丝线为主 + 极浅 shadowSoft 作第二层
        // （发丝线 + 浅阴影 + 底色是 §8 明确允许的组合，不是 v1 禁的三者同重）。
        val shape = RoundedCornerShape(shapes.md)
        Box(
            modifier = modifier
                .shadow(
                    elevation = 2.dp,
                    shape = shape,
                    ambientColor = colors.shadowSoft,
                    spotColor = colors.shadowSoft,
                )
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, colors.hairline, shape),
        ) {
            content()
            if (notInstalledNotice != null) {
                Text(
                    text = notInstalledNotice,
                    fontSize = 9.sp,
                    color = colors.error,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
        }
    }

    // --- 4 · ListItem --------------------------------------------------------

    @Composable
    override fun ListItem(
        title: String,
        leading: (@Composable () -> Unit)?,
        subtitle: String?,
        trailing: (@Composable () -> Unit)?,
        onClick: (() -> Unit)?,
        enabled: Boolean,
        selected: Boolean,
    ) {
        // v2 §12：行圆角 sm(16)、选中 = accentSoft 底 + 左侧 3dp accent 条（不是大色块 pill）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(shapes.sm))
                .background(if (selected) colors.primary.copy(alpha = 0.08f) else Color.Transparent)
                .then(if (onClick != null && enabled) Modifier.clickable { onClick() } else Modifier)
                .alpha(if (enabled) 1f else 0.45f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                // 左侧 3dp 选中指示条（§12 会话列表）。
                Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(colors.primary))
                Spacer(Modifier.width(10.dp))
            }
            if (leading != null) {
                leading()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (selected) colors.primary else colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }

    // --- 5 · Switch ----------------------------------------------------------

    @Composable
    override fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
        // M3 Switch 无主题时的选中态就是被禁的 Material 默认紫 —— 改成 token 化的自绘：
        // 52×32 轨道 + 24dp 圆钮，整行 ≥44dp 可点。
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(enabled = enabled) { onCheckedChange(!checked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(52.dp)
                    .height(32.dp)
                    .alpha(if (enabled) 1f else 0.45f)
                    .clip(CircleShape)
                    .background(if (checked) colors.primary else colors.surfaceVariant)
                    .border(1.dp, if (checked) colors.primary else colors.hairline, CircleShape)
                    .padding(4.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(colors.onPrimary)
                        .border(1.dp, colors.hairline, CircleShape),
                )
            }
        }
    }

    // --- 6 · BottomBar (three forms) -----------------------------------------

    @Composable
    override fun BottomBar(
        items: List<NavItem>,
        selected: Int,
        onSelect: (Int) -> Unit,
        style: BottomBarStyle,
    ) {
        val c = colors
        val pillShape = RoundedCornerShape(shapes.pill)

        val row: @Composable () -> Unit = {
            Row(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clickable { onSelect(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Text(
                                // v2 §12：选中 = icon 填充 + accent，不做大色块 pill 选中块。
                                text = item.glyph ?: "•",
                                fontSize = 20.sp,
                                color = if (isSelected) c.primary else c.onSurfaceVariant,
                            )
                            if (item.badge != null) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(c.error),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(item.badge, fontSize = 8.sp, color = c.onError)
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = item.label,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) c.primary else c.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        when (style) {
            BottomBarStyle.Fixed -> {
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(c.hairline),
                    )
                    Box(Modifier.fillMaxWidth().background(c.surfaceGlass)) { row() }
                }
            }

            BottomBarStyle.Floating -> {
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Box(
                        Modifier
                            .shadow(16.dp, pillShape, ambientColor = c.shadowFloat, spotColor = c.shadowFloat)
                            .clip(pillShape)
                            .background(c.surfaceGlass)
                            .border(1.dp, c.hairline, pillShape),
                    ) { row() }
                }
            }

            BottomBarStyle.FloatingBlur -> {
                // v2 §5：这个形态 = 真背景模糊。用 skin/glass/GlassSurface（根 hazeSource
                // 由 shell/ 接线；未接线时按其文档降级为 hairline + 阴影，绝不用垂直
                // alpha 渐变假装模糊 —— v2 明令禁止）。
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    GlassSurface(shape = pillShape, skin = this@MaterialSkin) { row() }
                }
            }
        }
    }

    // --- 7-11: delegated to MaterialParts.kt so this file stays readable -------

    @Composable
    override fun ApprovalDialog(
        request: vade.ui.skin.ApprovalRequest,
        remainingSec: Int,
        onDecide: (vade.ui.skin.Decision, vade.ui.skin.Scope) -> Unit,
    ) = MaterialApprovalDialog(request, remainingSec, onDecide, colors, shapes, typography)

    @Composable
    override fun MessageBubble(
        message: vade.ui.skin.MessageView,
        role: vade.ui.skin.BubbleRole,
        maxWidth: androidx.compose.ui.unit.Dp,
        actions: List<vade.ui.skin.MessageAction>,
        onAction: (vade.ui.skin.MessageAction) -> Unit,
        body: (@Composable () -> Unit)?,
    ) = MaterialMessageBubble(message, role, maxWidth, actions, onAction, body, colors, shapes, typography)

    /** 附件 chip（v2 §10）：`md` 圆角、28×28 缩略图、名称 13sp。 */
    @Composable
    override fun AttachmentChip(
        attachment: vade.ui.skin.AttachmentView,
        onRetry: (() -> Unit)?,
        onRemove: (() -> Unit)?,
        leading: (@Composable () -> Unit)?,
    ) = MaterialAttachmentChip(attachment, onRetry, onRemove, leading, colors, shapes, typography)

    @Composable
    override fun ToolCallCard(
        call: vade.ui.skin.ToolCallView,
        state: vade.ui.skin.ToolCardState,
        onToggle: () -> Unit,
        meta: (@Composable () -> Unit)?,
    ) = MaterialToolCallCard(call, state, onToggle, meta, colors, shapes, typography)

    @Composable
    override fun InputBar(
        value: String,
        onValueChange: (String) -> Unit,
        mode: vade.ui.skin.InputMode,
        onSend: () -> Unit,
        onStop: () -> Unit,
        attachments: (@Composable () -> Unit)?,
        enabled: Boolean,
        voiceAvailable: Boolean,
        sendEnabled: Boolean,
        maxLines: Int,
    ) = MaterialInputBar(
        value = value,
        onValueChange = onValueChange,
        mode = mode,
        onSend = onSend,
        onStop = onStop,
        attachments = attachments,
        enabled = enabled,
        voiceAvailable = voiceAvailable,
        sendEnabled = sendEnabled,
        maxLines = maxLines,
        colors = colors,
        shapes = shapes,
        typography = typography,
    )
}
