// c6-insets · 贴底浮层的安全区（审查 L4 / L2）；f4-insets · 原始读数在根节点读一次下传（P1-8）
//
// 分工回顾（c-inset / g-inset2）：MainActivity 的根布局只消费 statusBars + ime，
// **不**消费 navigationBars —— 底部 inset 由各贴底层自己读（`BottomBar.kt` 是第一个
// 这样做的，`ComposerInsets.kt` 是第二个）。贴底的浮层（模型选择 sheet、搜索卡、
// 重命名 Popup）此前用固定 12dp 间距：三键导航条 / 手势胶囊直接压在最后一行上
// （L4 真机截图，2026-11-16）。
//
// 这里只给一个数：[bottomSafeClearance] —— 导航条的**余量** `max(0, nav − ime)`。
// 为什么是余量而不是导航条本身：根的 `imePadding()` 已经把整页抬到键盘之上，
// 键盘弹起时 IME 盖住导航条，余量归零，所以永远不会与 IME 相加（并集不变式，
// `ComposerInsetsTest` ③ 钉住）。独立的 Popup 窗口另算，见 `MetaSheets.kt`。
//
// f4-insets · P1-8：**读数只在根节点做一次**。`windowInsetsPadding` /
// `consumeWindowInsets` 会消费 inset，叶子层再 `asPaddingValues()` 读到的可能已被
// 祖先扣掉（键盘弹起时读成 0，余量就退化成整条导航条，与 IME 相加）。所以
// `MainActivity` 在应用任何 padding **之前**调用 [rememberRawOverlayInsets]，把原始
// 值经 [LocalOverlayInsets] 下传；浮层只读这个 local，不再自己读 `WindowInsets`。
//
// 两端（desktop / wasm）这两个读数都是 0，返回值恒为 0，几何逐像素不变；不提供
// provider 时的默认值同样是 0，所以预览 / 单元测试的组合也不会凭空多出一条间距。

package vade.ui.component.insets

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 根节点读到的**原始**窗口 inset。
 *
 * [imeBottom] 是键盘底部高度（收起时 0），[navBottom] 是导航条底部高度；两者都不含
 * 任何祖先 padding 的扣减。desktop / wasm 的 actual 都是 0。
 */
@Immutable
data class OverlayInsets(
    val imeBottom: Dp = 0.dp,
    val navBottom: Dp = 0.dp,
) {
    /** 导航条余量（见 [bottomSafeClearance]）：键盘弹起时归零，与 IME 不相加。 */
    val bottomClearance: Dp
        get() = bottomSafeClearance(imeBottom, navBottom)
}

/**
 * 原始读数由根节点（Android 是 `MainActivity`）提供。
 *
 * 默认值 0 是**有意的**：desktop / wasm 的真实读数就是 0；没有被 provider 包住的组合
 * （预览、单元测试、桌面浮层）也应保持原有几何。
 */
val LocalOverlayInsets = staticCompositionLocalOf { OverlayInsets() }

/**
 * 在**根节点**读一次原始 `WindowInsets.ime` / `WindowInsets.navigationBars`。
 *
 * 必须在同一棵组合树里任何 `statusBarsPadding()` / `imePadding()` /
 * `windowInsetsPadding()` **之前**调用：这些 modifier 会消费 inset，子树里再读到的
 * 是扣减后的值（P1-8）。返回值随 inset 动画重组更新，调用方用
 * `CompositionLocalProvider(LocalOverlayInsets provides …)` 下传即可。
 */
@Composable
fun rememberRawOverlayInsets(): OverlayInsets {
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return OverlayInsets(imeBottom = imeBottom, navBottom = navBottom)
}

/**
 * 浮层底部的安全区余量：导航条扣掉 IME 之后剩下的那一段。
 *
 * [imeBottom] 是 IME 的原始底部 inset（键盘收起时 0），[navInset] 是导航条的底部
 * inset。两者都读到 0 的宿主（desktop / wasm）返回 0 dp。
 */
fun bottomSafeClearance(imeBottom: Dp, navInset: Dp): Dp =
    (navInset - imeBottom).coerceAtLeast(0.dp)

/**
 * 浮层读取的实时余量：来自 [LocalOverlayInsets]（根节点的原始读数）。
 *
 * 不再在叶子里读 `WindowInsets`：贴底浮层可能位于已经 `imePadding()` 的子树内，
 * 那样读到的 IME 已被消费成 0，余量会把导航条整条叠到已经抬起的布局上（P1-8）。
 */
@Composable
fun bottomSafeClearance(): Dp = LocalOverlayInsets.current.bottomClearance
