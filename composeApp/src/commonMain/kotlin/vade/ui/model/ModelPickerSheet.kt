// The session model picker as a §9 floating sheet card (g-model-chip).
//
// docs/visual-language.md §9 (line 334): ALL global popouts — 审批详情、模型选择、设置、
// 附件预览、危险确认 — are floating sheet cards, never an inline block in the page flow.
// The session page used to expand `ModelPickerCard` between header and thread, pushing the
// transcript down; that placement is gone. This sheet:
//
//  - floats OVER the whole screen (bottom-anchored): 12dp left/right, 12dp bottom, all four
//    corners 28dp (`skin.shapes.xl` — §334 「四角 28dp」), glass base (GlassSurface: blur +
//    tint + hairline + float shadow), never edge-to-edge, never full-width;
//  - carries the 36×4dp grabber 8dp under the card top — the only job is "this can be
//    dragged/closed";
//  - closes on mask tap, ✕, `Esc`, and the **system back** — g-nav-stack: the sheet itself
//    registers one `BackLayer` while it is composed, so Android's back / gesture closes only
//    this overlay instead of the session under it (G3), and on desktop `Esc` is claimed by
//    the same stack before the shell's "leave the session" fallback can see it;
//  - body = the SAME row rendering `ModelPickerCard` uses (`PickerRows` / `PickerEmptyState`
//    / notices — widened to `internal`, not duplicated): the honest tri-states (loading /
//    load-failed / no-models / read-only) and the write-failure lines all live here too.
//
//  Selecting a row does NOT auto-close the sheet: the write's outcome (成功提示 or 失败)
//  must stay visible (§8.5 — an outcome nobody can see is a silent write). The chip outside
//  updates immediately via the optimistic selection.

package vade.ui.model

import vade.ui.shell.BackLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.component.insets.bottomSafeClearance
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.LocalSkin
import vade.ui.skin.glass.GlassSurface

/** Esc policy of the sheet, pure so the UI test can drive the real predicate. */
internal fun isSheetEscape(
    event: androidx.compose.ui.input.key.KeyEvent,
): Boolean = event.type == KeyEventType.KeyDown && event.key == Key.Escape

/**
 * Readability backing for the sheet card (§5 blur-less degrade shape).
 *
 * The sheet's glass *reads* the shell's shared haze state, but haze 2.1.0 forbids a glass
 * layer that is a DESCENDANT of the `Modifier.hazeSource` node (GlassBridge.kt structure
 * rule, verbatim: 「hazeEffect nodes cannot draw an ancestor hazeSource」). The shell
 * registers the whole body as the one backdrop source, so EVERY page-level glass — this
 * sheet, the composer, the image preview — is a descendant and the blur is structurally
 * unavailable in-app (sibling layers like the top bar blur fine). Restructuring that is
 * shell-side work (AppShell.kt is another bat's file).
 *
 * Until then the card carries interactive text rows that MUST be readable, so the content
 * is padded with the same near-solid wash the composer's `glassFallback` uses (§5 fallback:
 * tint + near-solid backing). Applied unconditionally — when the shell is restructured and
 * the blur starts applying, dropping this wash is a one-line shell-side change.
 */
@Composable
private fun Modifier.sheetReadabilityBacking(skin: vade.ui.skin.Skin, shape: Shape): Modifier =
    background(skin.colors.surfaceGlass.copy(alpha = 0.88f), shape)

/**
 * The §9 floating sheet for the session model picker. [title] is the header copy (the
 * screen passes 「会话模型」); [footerNote] carries the honest provenance line the screen
 * owns (fixture vs. real adapter).
 */
@Composable
fun ModelPickerSheet(
    state: ModelState,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    footerNote: String? = null,
) {
    val skin = LocalSkin.current
    // §334: 四角 28dp —— 全局弹窗卡在 §10 的 sheet 档之上更贴近内容，任务书明确 28dp。
    val shape = RoundedCornerShape(skin.shapes.xl)
    val grabber = if (skin.colors.isDark) Color.White.copy(alpha = 0.70f) else Color.Black.copy(alpha = 0.24f)
    val focusRequester = remember { FocusRequester() }

    // The card takes focus so Esc reaches this hierarchy (a modal sheet owns the keyboard).
    LaunchedEffect(state, title) { runCatching { focusRequester.requestFocus() } }

    // g-nav-stack（G3）· 浮层就是返回栈的一层：系统返回 / 返回手势 / 桌面 Esc 只关这张卡，
    // 不会穿透到下面的会话（旧行为：返回栈里没有这一层，Esc 落到「退出详情」→ 整个会话被关）。
    // 本组合存在 ⟺ 浮层开着 ⇒ `enabled = true`；关掉后组合离开，层自动出栈。
    BackLayer(enabled = true, onBack = onClose)

    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (isSheetEscape(event)) {
                    onClose()
                    true
                } else {
                    false
                }
            },
    ) {
        // 遮罩：玻璃（矩形）+ 24% 黑，不是纯黑 60%（§9）—— 背后内容仍隐约可见；点它关闭。
        GlassSurface(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                )
                .semantics { contentDescription = "模型选择遮罩" },
            shape = RectangleShape,
            skin = skin,
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.24f)))
        }

        // 卡片：BottomCenter、左右 12dp、底部 12dp + 导航条余量、四角 28dp —— 永不贴边、
        // 不满宽。c6-insets · L4：固定 12dp 时三键导航条 / 手势胶囊压在卡片最后一行上
        // （真机截图），现在读 `WindowInsets.navigationBars` 的余量（键盘弹起时为 0，
        // 由根的 imePadding 抬升，不与 IME 相加）。
        GlassSurface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp + bottomSafeClearance()),
            shape = shape,
            skin = skin,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .sheetReadabilityBacking(skin, shape)
                    .focusRequester(focusRequester)
                    .focusable(),
            ) {
                // 白条手柄：36×4dp、圆角 2dp、距卡顶 8dp —— 只说明「可下拉关闭」（§9）。
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(grabber),
                )

                // 标题行：图标 + 标题 + ✕（图标按钮，44dp 触摸区）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VadeIcon(
                        VadeIcons.Model,
                        contentDescription = null, // decorative: the title names the sheet
                        size = 20.dp,
                        tint = skin.colors.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    UiText(
                        title,
                        size = 15,
                        color = skin.colors.textOnGlass,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(skin.shapes.xs))
                            .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        VadeIcon(
                            VadeIcons.Close,
                            contentDescription = "关闭会话模型",
                            size = 18.dp,
                            tint = skin.colors.onSurfaceVariant,
                        )
                    }
                }

                // 与卡片同一套诚实状态行：读退化 / 写被拒 / 拉取失败 / 写入失败 / 最近一次成功。
                state.readOnlyReason?.let { NoticeLine(it) }
                if (!state.readOnly) state.writeBlockedReason?.let { NoticeLine(it) }
                state.loadFailure?.let { FailureNotice(it) }
                state.writeFailure?.let { FailureNotice(it) }
                state.lastNotice?.let { NoticeLine(it) }

                Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.hairline))

                when (val content = state.content()) {
                    is ModelPickerContent.Rows -> PickerRows(state, content)
                    is ModelPickerContent.ReadOnly -> PickerEmptyState(content.title, content.body)
                    is ModelPickerContent.Loading -> PickerEmptyState(content.title, content.body)
                    is ModelPickerContent.LoadFailed -> PickerEmptyState(content.title, content.body)
                    is ModelPickerContent.NoModels -> PickerEmptyState(content.title, content.body)
                }

                footerNote?.let {
                    UiText(
                        text = it,
                        size = 11,
                        color = skin.colors.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}
