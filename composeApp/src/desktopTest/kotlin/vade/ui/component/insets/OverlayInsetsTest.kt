// f4-insets · `LocalOverlayInsets` 下的 `bottomSafeClearance()`（审查 L2/L4 + P1-8 的零覆盖补丁）。
//
// 已经有 `ComposerInsetsTest` 钉住**纯函数** `composerNavClearance(ime, nav)`；这里补的是
// 另一条从没被覆盖过的路：**贴底浮层在叶子里读 `LocalOverlayInsets`**（
// `OverlayInsets.bottomClearance` / `@Composable bottomSafeClearance()`），以及三处 Popup
// 共用的底距 `12.dp + clearance + imeBottom` 的并集不变式。
//
// 为什么叶子不能自己读 `WindowInsets`（P1-8）：`windowInsetsPadding` / `imePadding` 会
// **消费** inset，已经 `imePadding()` 的子树里再读 IME 会读成 0，余量就退化成整条导航条，
// 与已经抬起的布局相加 = 多出一整条导航条的空隙。所以读数只在根节点做一次、经 local 下传；
// 本文件的组合用例证明叶子确实读的是那份**原始**读数（键盘弹起 ⇒ 余量归零，而不是 48dp）。

package vade.ui.component.insets

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class OverlayInsetsTest {

    // ------------------------------------------------------------ 1. 纯读数：IME 盖住导航条 ⇒ 余量为 0

    /** 键盘弹起（IME 200 > nav 48）：导航条被键盘盖住，余量必须是 0，不是 48、也不是 −152。 */
    @Test
    fun anImeTallerThanTheNavBarLeavesNoClearance() {
        assertEquals(0.dp, bottomSafeClearance(imeBottom = 200.dp, navInset = 48.dp))
        assertEquals(0.dp, OverlayInsets(imeBottom = 200.dp, navBottom = 48.dp).bottomClearance)
    }

    /** 键盘收起（IME 0）：余量就是整条导航条（三键 48dp / 手势胶囊 24dp）。 */
    @Test
    fun withTheKeyboardClosedTheWholeNavBarIsClearance() {
        assertEquals(48.dp, bottomSafeClearance(imeBottom = 0.dp, navInset = 48.dp))
        assertEquals(48.dp, OverlayInsets(imeBottom = 0.dp, navBottom = 48.dp).bottomClearance)
        assertEquals(24.dp, OverlayInsets(imeBottom = 0.dp, navBottom = 24.dp).bottomClearance)
    }

    // ------------------------------------------------------------ 2. 组合路径：叶子读的是下传的原始读数

    /**
     * 叶子里的 `bottomSafeClearance()` 跟着 `LocalOverlayInsets` 走：
     * 键盘收起读到 48dp；键盘弹起（同一个 nav 48dp）读到 0dp。
     *
     * 这条正是「叶子不许自己读 `WindowInsets`」的行为判据 —— 如果它去读已经被祖先
     * `imePadding()` 消费成 0 的 IME，第二次读到的还会是 48dp（余量不退）。
     */
    @Test
    fun theComposableClearanceTracksTheProvidedRawInsets() = runComposeUiTest {
        val insets = mutableStateOf(OverlayInsets(imeBottom = 0.dp, navBottom = 48.dp))
        var read: Dp? = null
        setContent {
            CompositionLocalProvider(LocalOverlayInsets provides insets.value) {
                read = bottomSafeClearance()
            }
        }
        waitForIdle()
        assertEquals(48.dp, read, "键盘收起：叶子的余量 = 整条导航条")

        // 键盘弹起：同一个导航条读数，但原始 IME 200 > nav 48 ⇒ 余量归零（不相加）。
        runOnIdle { insets.value = OverlayInsets(imeBottom = 200.dp, navBottom = 48.dp) }
        waitForIdle()
        assertEquals(0.dp, read, "键盘弹起 ⇒ 叶子余量必须归零（读的是根节点下传的原始读数）")
    }

    /**
     * 没有 provider 时读者是 0dp —— desktop / wasm 的真实读数就是 0，预览与单元测试的
     * 组合也不该凭空多出一条间距。
     */
    @Test
    fun withoutAProviderTheClearanceIsZero() = runComposeUiTest {
        var read: Dp? = null
        setContent { read = bottomSafeClearance() }
        waitForIdle()
        assertEquals(0.dp, read, "默认 LocalOverlayInsets() = 全 0 ⇒ 余量 0（几何逐像素不变）")
    }

    // ------------------------------------------------------------ 3. 贴底 Popup 的并集不变式

    /**
     * 三处贴底浮层共用的底距表达式（`MetaSheets.kt` 的 Popup 是 `12.dp + clearance + imeBottom`；
     * `ModelPickerSheet` / `SearchOverlay` 用同一族的 `12.dp + bottomSafeClearance()`）。
     *
     * 不变式：`12 + clearance + ime == 12 + max(ime, nav)` —— 余量是导航条扣掉 IME 之后
     * 剩下的那一段，所以两者**永不相加**。键盘弹起时 Popup 抬到键盘之上（12 + 200），
     * 收起时抬到导航条之上（12 + 48），动画中途也不会出现 12 + 48 + 24 这种叠加。
     */
    @Test
    fun theDockedPopupBottomGapIsTheUnionOfImeAndNavNeverTheirSum() {
        fun popupBottomGap(overlay: OverlayInsets): Dp =
            12.dp + overlay.bottomClearance + overlay.imeBottom

        val cases = listOf(
            OverlayInsets(), // desktop / wasm：读数全 0
            OverlayInsets(navBottom = 48.dp), // 三键导航条，键盘收起
            OverlayInsets(navBottom = 24.dp), // 手势胶囊，键盘收起
            OverlayInsets(imeBottom = 24.dp, navBottom = 48.dp), // 键盘 / 导航条动画中途
            OverlayInsets(imeBottom = 200.dp, navBottom = 48.dp), // 键盘盖住导航条
            OverlayInsets(imeBottom = 800.dp, navBottom = 48.dp), // 键盘完全弹起
            OverlayInsets(imeBottom = 600.dp, navBottom = 600.dp), // 病态相等
        )
        cases.forEach { overlay ->
            val union = if (overlay.imeBottom > overlay.navBottom) overlay.imeBottom else overlay.navBottom
            assertEquals(
                12.dp + union,
                popupBottomGap(overlay),
                "ime=${overlay.imeBottom} nav=${overlay.navBottom}：底距必须是 12 + 两者的并集",
            )
        }

        // 具体两个口径值（三键导航）：键盘收起 12 + 48 = 60dp；键盘弹起 12 + 200 = 212dp。
        assertEquals(60.dp, popupBottomGap(OverlayInsets(navBottom = 48.dp)))
        assertEquals(212.dp, popupBottomGap(OverlayInsets(imeBottom = 200.dp, navBottom = 48.dp)))
    }
}
