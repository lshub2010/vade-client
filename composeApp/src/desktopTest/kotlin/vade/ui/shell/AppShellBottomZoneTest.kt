// g-appshell-zone · P1-3 真机回归：浮动胶囊把滚动列表的最后一行文字切掉一半。
//
// 病历：`AppShell.kt` 的 `BOTTOM_BAR_ZONE = 88.dp` 写成 64 + 12 + 12，即假设胶囊底部
// 边距是 12 dp。胶囊的真实边距是 `floatingBarBottomMargin(inset)`（BottomBar.kt）：
// 有导航条 inset 时 `8 + inset`，没有时回落到 KernelSU 的 28 dp。于是胶囊顶边落在
// 预留区**下面**，内容滚到底时最后一行正好被压掉一半 —— 88 dp 在任何一种导航条上
// 都不够（inset 0 → 需要 104，手势 24 → 108，三键 48 → 132）。
//
// 这里钉四层：
//  1. 纯函数 `bottomBarZone(inset)` 的三档目标值 + 与 `ComposerInsetsTest` 第 8 条
//     钉死的那两个数字（120 / 96）的换算关系；
//  2. 「随 inset 走」和「余量恒为 dock pad」这两个不变量（写死 132 会在桌面多推 28 dp，
//     写死 88 会在三键上少 44 dp —— 两个方向都被下面的算术挡住）；
//  3. 真渲染几何：把**真的** `VadeBottomBar` 按 AppShell 的包法（bottom dock pad +
//     胶囊自己的 margin）摆进 400×800 的盒子里，量它的顶边到窗口底的距离 == zone；
//  4. 内容侧：一个真滚动列 + 真胶囊，滚到最底后最后一行必须停在胶囊顶边之上。
//
// 桌面/Wasm 的 inset actual 是 0（`InsetsGuardTest` 在真窗口里量过），所以第 5 条同时
// 就是「桌面不会被多推」的守卫：zone 在 0 处必须正好等于胶囊足迹，不能多一个 dp。

package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * P1-3 · the shell's bottom zone must be the floating pill's real footprint on **every**
 * navigation-bar style, not one constant that only fits a 12 dp margin.
 *
 * Honest split of what a desktop JVM run can and cannot prove: the three inset values are
 * fed in directly (JVM reports 0 — `InsetsGuardTest`), so the arithmetic and the rendered
 * geometry are proven here; the Android non-zero pixels are the two device screenshots in
 * `shots/g-appshell-zone/`.
 */
class AppShellBottomZoneTest {

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun src(path: String) = File("src/$path")

    /** Source with comments stripped — guards match **calls**, not prose. */
    private fun codeOf(file: File): String {
        assertTrue(file.isFile, "guard target missing: ${file.path}")
        val noBlock = file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    private fun count(code: String, pattern: String): Int =
        Regex(pattern).findAll(code).count()

    /** The pill's footprint without the shell's dock padding — what must never be covered. */
    private fun pillStack(navInset: Dp): Dp = FloatingBarPillHeight + floatingBarBottomMargin(navInset)

    private fun barItems(): List<BottomBarItem> = Destination.entries.map {
        BottomBarItem(id = it.id, label = it.label, icon = it.icon)
    }

    // --- 1-3 · the three navigation styles the device evidence covers ------------------

    /**
     * ① desktop / wasm / any device without a nav bar: inset 0 → KernelSU's 28 dp margin.
     * 64 + 28 + 12 = 104. The old 88 was 64 + 12 + 12 — it assumed a 12 dp margin and was
     * 16 dp short, which is why the settings page's last line showed with its bottom half
     * under the glass even before any device inset existed.
     */
    @Test
    fun noNavInsetReservesTheKernelSuFootprint() {
        assertEquals(28.dp, floatingBarBottomMargin(0.dp), "precondition: KernelSU fallback margin")
        assertEquals(104.dp, bottomBarZone(0.dp))
        assertTrue(bottomBarZone(0.dp) >= 104.dp, "inset=0 目标 ≥104 dp，实际 ${bottomBarZone(0.dp)}")
        assertTrue(bottomBarZone(0.dp) > 88.dp, "旧 88 dp 常量在这档就短 16 dp")
    }

    /** ② gesture navigation (inset ≈ 24 dp): 8 + 24 = 32 margin → 64 + 32 + 12 = 108. */
    @Test
    fun gesturePillInsetReservesOneHundredAndEight() {
        assertEquals(32.dp, floatingBarBottomMargin(24.dp), "precondition: 8 dp + inset")
        assertEquals(108.dp, bottomBarZone(24.dp))
        assertTrue(bottomBarZone(24.dp) >= 108.dp, "手势 24 目标 ≥108 dp，实际 ${bottomBarZone(24.dp)}")
        assertTrue(bottomBarZone(24.dp) > bottomBarZone(0.dp), "有 inset 必须比无 inset 更宽松")
    }

    /** ③ 3-button navigation (inset ≈ 48 dp): 8 + 48 = 56 margin → 64 + 56 + 12 = 132. */
    @Test
    fun threeButtonInsetReservesOneHundredAndThirtyTwo() {
        assertEquals(56.dp, floatingBarBottomMargin(48.dp), "precondition: 8 dp + inset")
        assertEquals(132.dp, bottomBarZone(48.dp))
        assertTrue(bottomBarZone(48.dp) >= 120.dp, "三键 48 目标 ≥120 dp，实际 ${bottomBarZone(48.dp)}")
        // The single-constant fix the review rejected: 132 hardcoded is right here and
        // 28 dp too generous on inset 0 — the two-sided check below keeps it honest.
        assertTrue(bottomBarZone(48.dp) - bottomBarZone(0.dp) == 28.dp)
    }

    // --- 4 · the function actually tracks the inset --------------------------------

    /**
     * Monotonic non-decreasing over the three real nav styles, and strictly increasing
     * across the continuous `8 + inset` branch (24 → 48 in 4 dp steps) — the property a
     * hardcoded constant fails by construction.
     */
    @Test
    fun zoneNeverShrinksAsTheInsetGrows() {
        val styles = listOf(0.dp, 24.dp, 48.dp)
        styles.zipWithNext().forEach { (low, high) ->
            assertTrue(
                bottomBarZone(high) >= bottomBarZone(low),
                "zone 必须随 inset 单调不减：inset $low → ${bottomBarZone(low)}，inset $high → ${bottomBarZone(high)}",
            )
        }
        var previous = bottomBarZone(24.dp)
        var inset = 24.dp
        while (inset < 48.dp) {
            inset += 4.dp
            val current = bottomBarZone(inset)
            assertEquals(4.dp, current - previous, "inset 每涨 4 dp，zone 必须同步涨 4 dp（inset=$inset）")
            previous = current
        }
    }

    /**
     * The invariant that survives the KernelSU fallback step: for **every** inset the zone
     * is exactly the pill stack plus the shell's dock padding. Zero slack above, zero
     * overlap below — so desktop (inset 0) is not pushed further than the pill itself
     * occupies, and 3-button is not short.
     */
    @Test
    fun zoneIsExactlyThePillFootprintPlusTheDockPad() {
        listOf(0.dp, 1.dp, 24.dp, 32.dp, 48.dp).forEach { inset ->
            assertEquals(
                BOTTOM_BAR_DOCK_PAD,
                bottomBarZone(inset) - pillStack(inset),
                "inset=$inset：zone 与胶囊足迹之间只能差一个 dock pad",
            )
            assertTrue(
                bottomBarZone(inset) >= pillStack(inset),
                "inset=$inset：zone 小于胶囊足迹 → 最后一行必被压（P1-3）",
            )
        }
    }

    /** ⑤ hidden bar (pushed detail / rail breakpoints, D88): nothing is reserved. */
    @Test
    fun hiddenBarReservesNothing() {
        assertEquals(0.dp, contentBottomInset(showBar = false, navInset = 0.dp))
        assertEquals(0.dp, contentBottomInset(showBar = false, navInset = 48.dp))
        assertEquals(bottomBarZone(48.dp), contentBottomInset(showBar = true, navInset = 48.dp))
        assertEquals(bottomBarZone(0.dp), contentBottomInset(showBar = true, navInset = 0.dp))
    }

    // --- 6 · parity with the numbers g-inset2 already pinned -----------------------

    /**
     * `ComposerInsetsTest.floatingPillStackExceedsTheShell88dpZoneOnBothNavStyles` pins the
     * pill stacks (120 dp on 3-button, 96 dp on gesture) and that both exceed the old
     * 88 dp zone. The shell zone must sit **above** those numbers by exactly the dock pad,
     * so the two batches cannot drift apart.
     */
    @Test
    fun targetNumbersAgreeWithComposerInsetsTest() {
        assertEquals(120.dp, FloatingBarPillHeight + floatingBarBottomMargin(48.dp))
        assertEquals(96.dp, FloatingBarPillHeight + floatingBarBottomMargin(24.dp))
        assertEquals(12.dp, bottomBarZone(48.dp) - (FloatingBarPillHeight + floatingBarBottomMargin(48.dp)))
        assertEquals(12.dp, bottomBarZone(24.dp) - (FloatingBarPillHeight + floatingBarBottomMargin(24.dp)))
        assertTrue(bottomBarZone(48.dp) > 88.dp)
        assertTrue(bottomBarZone(24.dp) > 88.dp)
    }

    // --- 7 · rendered geometry: the real pill's top edge IS the zone ---------------

    /**
     * Render the real [VadeBottomBar] exactly the way `ShellBody` wraps it (bottom dock pad
     * inside a bottom-aligned Box), and measure the pill's top edge against the **rendered**
     * container bottom — the Skia test window clamps the requested height, so the window's
     * own height is the truth here, not a number written in the test. The distance from
     * that edge to the container bottom must equal [bottomBarZone]: the number the
     * scrollable reserves is the number the pill actually occupies, measured in pixels.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun desktopPillRendersExactlyTheReservedZoneTall() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.width(400.dp).fillMaxHeight().testTag(STAGE)) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                PaddingValues(
                                    start = 12.dp,
                                    end = 12.dp,
                                    bottom = BOTTOM_BAR_DOCK_PAD,
                                ),
                            ),
                    ) {
                        VadeBottomBar(
                            items = barItems(),
                            selected = 0,
                            onSelect = {},
                            style = BottomBarStyle.FloatingBlur,
                            modifier = Modifier.testTag(PILL),
                        )
                    }
                }
            }
        }
        val stage = onNodeWithTag(STAGE).fetchSemanticsNode().boundsInRoot
        val pill = onNodeWithTag(PILL).fetchSemanticsNode().boundsInRoot
        with(density) {
            assertEquals(
                bottomBarZone(0.dp),
                (stage.bottom - pill.top).toDp(),
                "胶囊顶边到窗口底的距离必须正好等于内容预留的 zone（多推开=内容被白推，少推开=最后一行被切）",
            )
            assertTrue(
                bottomBarZone(0.dp) - (stage.bottom - pill.top).toDp() == 0.dp,
                "zone 与真实渲染足迹之间不得有余量差",
            )
        }
    }

    // --- 8 · content side: the last row scrolls clear of the rendered pill ---------

    /**
     * A real scrollable that consumes the zone as **content** padding (the shell's
     * contract), with the real pill floating over it. Scrolled to the very end, the last
     * row's bottom must sit at or above the pill's top edge — the P1-3 symptom was that it
     * landed *under* the glass, with its bottom half sliced off.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun lastRowScrollsClearOfTheRenderedPill() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val scroll = rememberScrollState()
                LaunchedEffect(Unit) {
                    // First layout has to settle before maxValue is meaningful.
                    snapshotFlow { scroll.maxValue }.first { it > 0 }
                    scroll.scrollTo(scroll.maxValue)
                }
                Box(Modifier.width(400.dp).fillMaxHeight().testTag(STAGE)) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(PaddingValues(bottom = contentBottomInset(showBar = true, navInset = 0.dp))),
                    ) {
                        repeat(20) { index ->
                            Box(Modifier.fillMaxWidth().height(48.dp).testTag("row-$index"))
                        }
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag(LAST_ROW))
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                PaddingValues(
                                    start = 12.dp,
                                    end = 12.dp,
                                    bottom = BOTTOM_BAR_DOCK_PAD,
                                ),
                            ),
                    ) {
                        VadeBottomBar(
                            items = barItems(),
                            selected = 0,
                            onSelect = {},
                            style = BottomBarStyle.FloatingBlur,
                            modifier = Modifier.testTag(PILL),
                        )
                    }
                }
            }
        }
        waitForIdle()
        val stage = onNodeWithTag(STAGE).fetchSemanticsNode().boundsInRoot
        val last = onNodeWithTag(LAST_ROW).fetchSemanticsNode().boundsInRoot
        val pill = onNodeWithTag(PILL).fetchSemanticsNode().boundsInRoot
        with(density) {
            assertEquals(
                (stage.bottom - bottomBarZone(0.dp).toPx()).toDp(),
                last.bottom.toDp(),
                "滚到底后最后一行必须落在 zone 的上沿（= 胶囊顶边），而不是被压在胶囊下面",
            )
            assertTrue(
                last.bottom <= pill.top + 0.5f,
                "最后一行底边 ${last.bottom} 低于胶囊顶边 ${pill.top} —— 文字会被切一半（P1-3）",
            )
        }
    }

    // --- 9 · the shell actually consumes the function (wiring, not arithmetic) ----

    /**
     * Arithmetic in a helper nobody calls is not a fix. This pins the wiring: AppShell
     * feeds `contentBottomInset(showBar, navInset, state.barStyle)` into `contentInsets`
     * (g-zone-split: the zone is per form now, so the call carries the style), keeps the
     * dock pad as the single source for the pill's outer padding, and no longer carries
     * the 88 dp constant at all.
     */
    @Test
    fun shellFeedsTheInsetDerivedZoneIntoTheContentInsets() {
        val appShell = codeOf(src("commonMain/kotlin/vade/ui/shell/AppShell.kt"))
        assertEquals(
            1,
            count(appShell, """bottom = contentBottomInset\(showBar, navInset, state\.barStyle\)"""),
            "contentInsets 的 bottom 必须走 contentBottomInset(showBar, navInset, state.barStyle)",
        )
        assertEquals(
            1,
            count(appShell, """val navInset = navigationBarBottomInset\(\)"""),
            "inset 必须只读一次并复用（两次读会在动画中间拿到不同的值）",
        )
        assertEquals(
            1,
            count(appShell, """bottom = BOTTOM_BAR_DOCK_PAD"""),
            "胶囊外层 padding 必须与 zone 用同一个 dock pad 常量，否则两者会漂",
        )
        assertEquals(
            0,
            count(appShell, """BOTTOM_BAR_ZONE"""),
            "88 dp 的死常量必须彻底消失——留着它，下一个人就会再写回去",
        )
    }
}

/** Test tags: the rendered stage, the pill root, and the last row of the scroll fixture. */
private const val STAGE = "stage"
private const val PILL = "pill"
private const val LAST_ROW = "last-row"
