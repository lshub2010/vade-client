package vade.ui.skin.material

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vade.ui.skin.BtnStyle
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.NavItem

/**
 * `material` skin — the only skin U1 ships (U2 = miuix, U3 = glass).
 *
 * Scope is deliberately "renders correctly", not "pixel-perfect": the task book
 * (§T6) asks for Material 3 wrappers and the three bottom-bar forms, and forbids
 * spending U1 time on per-state polish. Business pages must never import this file.
 *
 * [skinId] / [notInstalledNotice] exist so [vade.ui.skin.SkinRegistry] can register
 * token-compatible *placeholders* for `miuix` / `glass` (task book §T2: "返回 material
 * 的 token，按钮上打 `[miuix 未安装]` 角标") without inventing a second skin.
 */
class MaterialSkin(
    private val skinId: String = "material",
    private val notInstalledNotice: String? = null,
) : vade.ui.skin.Skin {

    override val id: String = skinId

    override val colors = vade.ui.skin.SkinColors(
        // Visual language §2 light tokens. No `#6750A4` anywhere: the accent is a
        // graphite-neutral indigo, and colour appears only on accent/status surfaces.
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
    )

    /** Visual language §3: 8 / 12 / 16 / 20 / 28 + pill. Three steps per screen, max. */
    override val shapes = vade.ui.skin.SkinShapes(
        small = 12.dp,
        medium = 16.dp,
        large = 20.dp,
        pill = 999.dp,
        xs = 8.dp,
        xl = 28.dp,
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
            // 主按钮 pill：accent 底 + 白字；禁用 textMuted @30% 底、无描边 (视觉规范 §5).
            BtnStyle.Primary -> { bg = c.primary; fg = c.onPrimary; radius = shapes.pill; border = null }
            // 次要按钮 sm(12dp)：透明底 + hairline.
            BtnStyle.Secondary -> { bg = Color.Transparent; fg = c.onSurface; radius = shapes.small; border = c.hairline }
            BtnStyle.Danger -> { bg = c.error; fg = c.onError; radius = shapes.small; border = null }
            BtnStyle.Text -> { bg = Color.Transparent; fg = c.primary; radius = shapes.small; border = null }
        }
        val fill = if (!enabled && style == BtnStyle.Primary) c.onSurfaceVariant.copy(alpha = 0.30f) else bg
        val shape = RoundedCornerShape(radius)
        Box(
            modifier = Modifier
                .height(if (style == BtnStyle.Text) 36.dp else 40.dp)
                .alpha(if (enabled) 1f else if (style == BtnStyle.Primary) 1f else 0.42f)
                .clip(shape)
                .background(fill)
                .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = if (style == BtnStyle.Text) 8.dp else 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = if (!enabled) c.onSurfaceVariant else fg,
                fontSize = 13.sp,
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
        Column {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true,
                isError = error != null,
                textStyle = typography.body,
                shape = RoundedCornerShape(shapes.small),
                placeholder = if (placeholder == null) {
                    null
                } else {
                    { Text(placeholder, fontSize = 13.sp, color = colors.onSurfaceVariant) }
                },
            )
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
        // 视觉规范 §1.2：1px 发丝线代替阴影；边框 + 底色，不叠阴影（禁止三者同用）。
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(shapes.medium))
                .background(colors.surface)
                .border(1.dp, colors.hairline, RoundedCornerShape(shapes.medium)),
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
        val bg = if (selected) colors.primary.copy(alpha = 0.10f) else Color.Transparent
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(shapes.small))
                .background(bg)
                .then(if (onClick != null && enabled) Modifier.clickable { onClick() } else Modifier)
                .alpha(if (enabled) 1f else 0.45f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
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
        val pill = RoundedCornerShape(shapes.pill)

        val row: @Composable () -> Unit = {
            Row(
                modifier = Modifier.fillMaxWidth().height(60.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == selected
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onSelect(index) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Text(
                                text = item.glyph ?: "•",
                                fontSize = 17.sp,
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
                            .background(c.outline.copy(alpha = 0.35f)),
                    )
                    Box(Modifier.fillMaxWidth().background(c.surface)) { row() }
                }
            }

            BottomBarStyle.Floating -> {
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Box(Modifier.shadow(10.dp, pill).clip(pill).background(c.surface)) { row() }
                }
            }

            BottomBarStyle.FloatingBlur -> {
                // TODO(u2-blur): the real backdrop filter is NOT implemented. `haze` is
                // reserved for U2 and has no verified wasm/desktop story (spike R4), so
                // this form is a translucent gradient + hairline only. Do not describe it
                // as "blur" in any UI copy until a per-platform screenshot proves otherwise.
                // A vertical alpha ramp (instead of one flat alpha) is what makes the form
                // visually distinct from `Floating` on a near-white background.
                Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                    Box(
                        Modifier
                            .shadow(12.dp, pill)
                            .clip(pill)
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(c.surface.copy(alpha = 0.92f), c.surface.copy(alpha = 0.38f)),
                                ),
                            )
                            .border(1.dp, c.outline.copy(alpha = 0.70f), pill),
                    ) {
                        row()
                    }
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

    /** 附件 chip（视觉规范 §5）：`sm` 圆角、28×28 缩略图、名称 13sp。 */
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
