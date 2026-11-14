package vade.ui.skin.miuix

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import vade.ui.skin.BtnStyle
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.NavItem
import vade.ui.skin.SkinColors
import vade.ui.skin.SkinMotion
import vade.ui.skin.SkinShapes
import vade.ui.skin.SkinTypography

/**
 * `miuix` skin — C-09 components 1-6.
 *
 * Written against Miuix 0.9.4 (`top.yukonga.miuix.kmp`). Design deltas versus `material`
 * (task book §四) that a reviewer should be able to see in a blind test:
 *
 *  - **shape** 12/16/20 dp on the spec's 8/12/16/20/28 ladder (8 dp kept as `radiusXs`,
 *    28 dp for sheets), instead of material's 8/14/22; the user bubble squares off one corner;
 *  - **depth** hairline strokes and flat surface colours, zero elevation everywhere;
 *  - **density** 48 dp rows / 32 dp buttons / 11–13 sp type;
 *  - **buttons** outline-first — only the primary action is filled;
 *  - **motion** 120/160/200 ms (Miuix's own press feedback replaces the ripple).
 *
 * Nothing here imports `androidx.compose.material3`; the widgets are Miuix's own or, where
 * upstream has no equivalent (the three-form bottom bar, the approval sheet), Miuix
 * `Surface` + `Text` compositions.
 */
class MiuixSkin(
    /** Dark tokens. Selected by the host entry point (`--dark`, or the settings page later). */
    private val dark: Boolean = false,
) : vade.ui.skin.Skin {

    override val id: String = "miuix"

    override val colors: SkinColors = MiuixTokens.colors(dark)
    override val shapes: SkinShapes = MiuixTokens.shapes
    override val typography: SkinTypography = MiuixTokens.typography
    override val motion: SkinMotion = MiuixTokens.motion

    // --- 1 · Button ----------------------------------------------------------

    /**
     * Outline-first. `Primary` is the only filled style — and the only `pill` one, per spec
     * §五 主按钮; `Secondary`/`Danger` are 1 dp strokes over the surface colour at `small`
     * (12 dp); `Text` is colour + weight only, no fill and no stroke. Every style is 32 dp
     * tall, half the vertical weight of the material skin's 38 dp pill-ish buttons.
     */
    @Composable
    override fun Button(text: String, onClick: () -> Unit, style: BtnStyle, enabled: Boolean) {
        val c = colors
        val shape = RoundedCornerShape(shapes.small)
        when (style) {
            BtnStyle.Primary -> MiuixScope(c) {
                Button(
                    onClick = onClick,
                    enabled = enabled,
                    // Spec §五 主按钮: `pill`. Was `shapes.small` — a 12 dp rect reads as a
                    // *secondary* control, so primary and secondary were visually swapped.
                    cornerRadius = shapes.pill,
                    minHeight = MiuixTokens.buttonHeight,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    insideMargin = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                ) {
                    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            BtnStyle.Text -> MiuixScope(c) {
                // NOT `TextButton` + `textButtonColorsPrimary()`: in 0.9.4 that combination
                // paints a *filled primary pill*, so the screenshot showed 批注 and 发送 as two
                // identical blue buttons. A text button must read as text — colour + weight
                // only, no fill, no stroke.
                Text(
                    text = text,
                    color = if (enabled) c.primary else c.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(shapes.small))
                        .clickable(enabled = enabled) { onClick() }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }

            BtnStyle.Secondary, BtnStyle.Danger -> {
                val accent = if (style == BtnStyle.Danger) c.error else c.outline
                val fg = if (style == BtnStyle.Danger) c.error else c.onSurface
                MiuixScope(c) {
                    Surface(
                        onClick = onClick,
                        enabled = enabled,
                        shape = shape,
                        // Transparent, not `surface`: an outlined button must work on the
                        // page background *and* inside a card without a visible seam.
                        color = Color.Transparent,
                        contentColor = fg,
                        border = BorderStroke(1.dp, if (enabled) accent else accent.copy(alpha = 0.4f)),
                    ) {
                        Box(
                            Modifier
                                .heightIn(min = MiuixTokens.buttonHeight)
                                .padding(horizontal = 16.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text,
                                color = fg,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    // --- 2 · TextField -------------------------------------------------------

    /**
     * Miuix `TextField` is a filled, rounded field with a floating label; the skin keeps
     * that but forces the *stroke* to carry state (focus → primary, error → red) instead of
     * a Material underline + helper row. The error copy sits under the field, 10 sp.
     */
    @Composable
    override fun TextField(
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String?,
        error: String?,
        enabled: Boolean,
    ) {
        val c = colors
        MiuixScope(c) {
            Column(Modifier.fillMaxWidth()) {
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    colors = TextFieldDefaults.textFieldColors(
                        backgroundColor = if (c.isDark) Color(0xFF26262A) else c.surfaceVariant,
                        labelColor = c.onSurfaceVariant,
                        borderColor = when {
                            error != null -> c.error
                            else -> c.outline
                        },
                    ),
                    cornerRadius = shapes.small,
                    label = placeholder ?: "",
                    useLabelAsPlaceholder = true,
                    enabled = enabled,
                    textStyle = typography.body.copy(color = c.onSurface),
                )
                if (error != null) {
                    Text(
                        text = error,
                        color = c.error,
                        fontSize = MiuixTokens.tinySize,
                        modifier = Modifier.padding(start = 4.dp, top = 3.dp),
                    )
                }
            }
        }
    }

    // --- 3 · Card ------------------------------------------------------------

    /**
     * Stroke + flat surface, never a shadow. Miuix's `Card` has **no** stroke parameter and
     * its default colours paint `surface` == `background` (spike/miuix RESULT.md §五 R5), so
     * a literal `Card` here would render as an invisible rectangle — the container is
     * therefore Miuix `Surface(border = …)`, which is the upstream primitive `Card` itself
     * is built on.
     */
    @Composable
    override fun Card(modifier: Modifier, content: @Composable () -> Unit) {
        val c = colors
        MiuixScope(c) {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(shapes.medium),
                color = c.surface,
                contentColor = c.onSurface,
                border = BorderStroke(1.dp, c.outline),
                shadowElevation = 0.dp,
            ) {
                Column { content() }
            }
        }
    }

    // --- 4 · ListItem --------------------------------------------------------

    /**
     * 48 dp row, 20 dp leading slot, hairline at the bottom inset 12 dp from the leading
     * edge (the Miuix list rhythm).
     *
     * Known deviation, deliberate: C-09's `ListItem` carries no position information, so
     * "the last row draws no divider" cannot be decided inside the skin. The hairline is
     * drawn on every row; the fix is a `isLast: Boolean = false` default parameter on
     * `Skin.ListItem` (additive, allowed by C-09 §"only additive changes") — raised as an
     * RFC rather than patched around here. See README §已知偏差.
     */
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
        val c = colors
        val titleColor = when {
            selected -> c.primary
            enabled -> c.onSurface
            else -> c.onSurfaceVariant.copy(alpha = 0.6f)
        }
        MiuixScope(c) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // Tint, not a pill: a selected Miuix row darkens its own surface.
                    .then(
                        if (selected) Modifier.clip(RoundedCornerShape(shapes.small))
                        else Modifier,
                    ),
            ) {
                BasicComponent(
                    modifier = Modifier.fillMaxWidth().heightIn(min = MiuixTokens.rowHeight),
                    title = title,
                    titleColor = BasicComponentDefaults.titleColor(color = titleColor),
                    summary = subtitle,
                    summaryColor = BasicComponentDefaults.summaryColor(color = c.onSurfaceVariant),
                    startAction = leading?.let { { it() } },
                    endActions = trailing?.let { { it() } },
                    insideMargin = MiuixTokens.listInsideMargin,
                    onClick = if (onClick != null && enabled) onClick else null,
                    enabled = enabled,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 12.dp),
                    thickness = MiuixTokens.dividerThickness,
                    color = if (selected) c.primary.copy(alpha = 0.35f) else c.outline.copy(alpha = 0.7f),
                )
            }
        }
    }

    // --- 5 · Switch ----------------------------------------------------------

    /** Miuix's own switch: a thinner, longer travel than Material's, thumb 20 dp. */
    @Composable
    override fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) {
        val c = colors
        MiuixScope(c) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.switchColors(
                    checkedThumbColor = c.onPrimary,
                    uncheckedThumbColor = c.surface,
                    disabledCheckedThumbColor = c.onSurfaceVariant.copy(alpha = 0.5f),
                    disabledUncheckedThumbColor = c.onSurfaceVariant.copy(alpha = 0.5f),
                    checkedTrackColor = c.primary,
                    uncheckedTrackColor = c.outline.copy(alpha = 0.75f),
                    disabledCheckedTrackColor = c.outline.copy(alpha = 0.5f),
                    disabledUncheckedTrackColor = c.outline.copy(alpha = 0.4f),
                ),
            )
        }
    }

    // --- 6 · BottomBar (three forms) -----------------------------------------

    /**
     * Fixed / Floating / FloatingBlur, all three sharing one **64 dp** item row so the bar
     * cannot "collapse" when the form changes (task book §六 判据 1).
     *
     * Spec conformance (`ui-visual-language.md` §五 底栏):
     *  - height 64 dp (was 52 — the compact value was a phone-density guess, but the spec fixes
     *    the bar height so the three forms stay comparable);
     *  - `surface` at 92 % + a top hairline;
     *  - selected item = icon 20 + label 11, tinted and weighted — **no** rounded selection
     *    block. The old revision drew a 34×24 / 6 dp tinted pill behind the selected glyph,
     *    which is precisely the "Material 浮动 pill 选中块" §五 forbids.
     *
     * Why not Miuix `NavigationBar`? Its items (`NavigationBarItem`) take an `ImageVector`
     * icon, while C-09's `NavItem` carries a single CJK glyph string. The container is
     * therefore composed from Miuix `Surface` + `Text` (the same "upstream has no
     * equivalent" escape hatch the task book allows).
     *
     * `FloatingBlur` is a **translucent hairline capsule, not a real backdrop blur**: `haze`
     * is not on the verified C0c matrix and the spike never proved a backdrop filter on
     * wasm (RESULT.md §五 R4). Degrade-and-document, per task book §T3.
     */
    @Composable
    override fun BottomBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, style: BottomBarStyle) {
        val c = colors
        val barHeight = MiuixTokens.barHeight
        // 92 % surface per spec; the alpha is what lets a scrolling list read *through* the bar.
        val barColor = c.surface.copy(alpha = 0.92f)
        when (style) {
            BottomBarStyle.Fixed -> {
                MiuixScope(c) {
                    Column(Modifier.fillMaxWidth()) {
                        HorizontalDivider(thickness = 1.dp, color = c.outline)
                        Box(Modifier.fillMaxWidth().height(barHeight).background(barColor)) {
                            Row(Modifier.fillMaxWidth().fillMaxHeight()) { barItems(items, selected, onSelect) }
                        }
                    }
                }
            }

            BottomBarStyle.Floating -> {
                MiuixScope(c) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(barHeight),
                            shape = RoundedCornerShape(shapes.pill),
                            color = barColor,
                            border = BorderStroke(1.dp, c.outline),
                            shadowElevation = 0.dp,
                        ) {
                            Row(Modifier.fillMaxWidth().fillMaxHeight()) { barItems(items, selected, onSelect) }
                        }
                    }
                }
            }

            BottomBarStyle.FloatingBlur -> {
                MiuixScope(c) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(barHeight),
                            shape = RoundedCornerShape(shapes.pill),
                            // 0.78 alpha over the page: readable, obviously translucent, but
                            // honestly not a blur. Do not call it 模糊 in UI copy.
                            color = c.surface.copy(alpha = 0.78f),
                            border = BorderStroke(1.dp, c.outline.copy(alpha = 0.85f)),
                            shadowElevation = 0.dp,
                        ) {
                            Row(Modifier.fillMaxWidth().fillMaxHeight()) { barItems(items, selected, onSelect) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.barItems(
        items: List<NavItem>,
        selected: Int,
        onSelect: (Int) -> Unit,
    ) {
        val c = colors
        items.forEachIndexed { index, item ->
            val isSelected = index == selected
            val tint = if (isSelected) c.primary else c.onSurfaceVariant
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Icon slot is 20 dp per spec — no selection block, the glyph carries state
                    // through colour + weight alone.
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = item.glyph ?: "•",
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = tint,
                        )
                    }
                    if (item.badge != null) {
                        Box(
                            modifier = Modifier
                                // Offset off the glyph: at 14 dp on TopEnd it sat *on top of* the
                                // 活 glyph and made the badge the only readable thing.
                                .align(Alignment.TopEnd)
                                .offset(x = 9.dp, y = (-5).dp)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(c.error),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(item.badge, fontSize = MiuixTokens.tinySize, color = c.onError)
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = item.label,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // --- 7-11 delegate to MiuixParts.kt --------------------------------------

    @Composable
    override fun ApprovalDialog(
        request: vade.ui.skin.ApprovalRequest,
        remainingSec: Int,
        onDecide: (vade.ui.skin.Decision, vade.ui.skin.Scope) -> Unit,
    ) = MiuixApprovalDialog(request, remainingSec, onDecide, colors, shapes, typography)

    @Composable
    override fun MessageBubble(
        message: vade.ui.skin.MessageView,
        role: vade.ui.skin.BubbleRole,
        maxWidth: Dp,
        actions: List<vade.ui.skin.MessageAction>,
        onAction: (vade.ui.skin.MessageAction) -> Unit,
    ) = MiuixMessageBubble(message, role, maxWidth, actions, onAction, colors, shapes, typography)

    @Composable
    override fun AttachmentChip(
        attachment: vade.ui.skin.AttachmentView,
        onRetry: (() -> Unit)?,
        onRemove: (() -> Unit)?,
    ) = MiuixAttachmentChip(attachment, onRetry, onRemove, colors, shapes, typography)

    @Composable
    override fun ToolCallCard(
        call: vade.ui.skin.ToolCallView,
        state: vade.ui.skin.ToolCardState,
        onToggle: () -> Unit,
    ) = MiuixToolCallCard(call, state, onToggle, colors, shapes, typography)

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
    ) = MiuixInputBar(
        value = value,
        onValueChange = onValueChange,
        mode = mode,
        onSend = onSend,
        onStop = onStop,
        attachments = attachments,
        enabled = enabled,
        voiceAvailable = voiceAvailable,
        colors = colors,
        shapes = shapes,
        typography = typography,
    )
}

/**
 * The registration seam (task book §二·五). Idempotent: calling it twice only re-points the
 * same factory at `miuix`. Must be called before the first `SkinRegistry.get("miuix")`.
 */
object MiuixSkinEntry {
    /**
     * Dark selection for the *next* factory call. Set by the host entry point (`--dark`,
     * `VADE_DARK=1`, or the appearance page in a later slice) before the first composition;
     * kept as a plain var so the registry's `() -> Skin` signature stays untouched (C-09).
     */
    var dark: Boolean = false

    fun register() = vade.ui.skin.SkinRegistry.register("miuix") { MiuixSkin(dark) }
}
