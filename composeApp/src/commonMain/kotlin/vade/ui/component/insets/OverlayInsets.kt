// c6-insets · 贴底浮层的安全区（审查 L4 / L2）
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
// 两端（desktop / wasm）这两个读数都是 0，返回值恒为 0，几何逐像素不变。

package vade.ui.component.insets

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 浮层底部的安全区余量：导航条扣掉 IME 之后剩下的那一段。
 *
 * [imeBottom] 是 IME 的原始底部 inset（键盘收起时 0），[navInset] 是导航条的底部
 * inset。两者都读到 0 的宿主（desktop / wasm）返回 0 dp。
 */
fun bottomSafeClearance(imeBottom: Dp, navInset: Dp): Dp =
    (navInset - imeBottom).coerceAtLeast(0.dp)

/** [bottomSafeClearance] 的实时读数（原始平台值，不受祖先 padding 的消费影响）。 */
@Composable
fun bottomSafeClearance(): Dp {
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return bottomSafeClearance(imeBottom, navInset)
}
