// g-zone-split · 两种 barStyle 的底部预留区必须各算各的足迹。
//
// 病历（上一棒自报）：`bottomBarZone(navInset) = FloatingBarPillHeight + margin + dockPad`
// 被 `Fixed`（贴底导航条）与 `Floating`（浮动胶囊）共用。`Fixed` 条根本不存在 64dp 的
// 胶囊，却照样预留了它 —— 桌面实测（本文件 `desktopFixedBlankDropsFromThirtyNineToZero`
// 打印的真渲染数字）：
//
//   Fixed 条真实足迹 65dp（1dp hairline + 64dp 行），共用 zone 104dp → **空白尾垫 39dp**；
//   P1-3 之前的 88dp 常量留下的是 23dp。任务书里的「40dp」是 104 − 64（漏掉 1dp 线），
//   多算了 1dp —— 本文件以渲染结果为准。
//
// 拆法：`floatingBarZone` 保持 pill + margin + dockPad（104/108/132 不变），
// `fixedBarZone = FixedBarTopRule + FixedBarRowHeight + navInset`（65/89/113），
// `barZoneFor(style, inset)` 按形态分发，`contentBottomInset` 带着 style 进 ShellBody。
//
// 关于「Fixed 与 Floating 的差值 == FloatingBarPillHeight」：这条字面要求在算术上与
// 「Fixed 不含 pill 且不被压」互斥 —— 两形态的行高都是 64dp，若差值恒为 64，Fixed 只能
// 预留 margin + dockPad = 40dp（inset 0），而它自己就有 65dp，最后一行反被压掉 25dp
// （P1-3 的反面）。可钉且为真的解耦证据是：
//   `floatingBarZone(i) − FloatingBarPillHeight − floatingBarBottomMargin(i) == 12dp`
//   `fixedBarZone(i) − FixedBarRowHeight − i == 1dp`（只有它自己的线，没有 pill）
// 加上 `fixedZoneExpressionNeverMentionsThePillHeight`（源码级反向守卫）与两条真渲染
// 几何（各自顶边到容器底 == 自己的 zone）。见 `fixedZoneDropsThePillTermButKeepsItsOwnRow`。

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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import vade.ui.component.liquid.rememberGlassBackdrop
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * Per-form bottom zones: the docked bar must stop reserving the floating pill.
 *
 * Desktop JVM can prove the arithmetic and the *rendered* geometry (nav inset actual = 0,
 * `InsetsGuardTest`); the non-zero Android insets are fed in directly as values, exactly
 * like `AppShellBottomZoneTest` documents. No device is touched by this batch.
 */
class BarStyleZoneSplitTest {

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun src(path: String) = File("src/$path")

    /**
     * Source with comments stripped — guards match **code**, not prose (the KDoc here names
     * `FloatingBarPillHeight` on purpose, so a comment-blind grep would false-positive).
     */
    private fun codeOf(file: File): String {
        assertTrue(file.isFile, "guard target missing: ${file.path}")
        val noBlock = file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    /** From a declaration signature to the next top-level declaration (column-0 `internal`). */
    private fun bodyOf(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue(start >= 0, "找不到声明：$signature")
        val tail = code.substring(start + signature.length)
        val end = tail.indexOf("\ninternal ").let { if (it >= 0) it else tail.length }
        return tail.substring(0, end)
    }

    private fun count(code: String, pattern: String): Int =
        Regex(pattern).findAll(code).count()

    private fun barItems(): List<BottomBarItem> = Destination.entries.map {
        BottomBarItem(id = it.id, label = it.label, icon = it.icon)
    }

    // --- 1 · Floating 三档回归：拆函数不得把上一棒修好的数字碰坏 ---------------------

    /**
     * The capsule form's three pinned targets, asserted through every entry point that can
     * produce it: the form function, the `barZoneFor` dispatcher (both floating styles),
     * and the legacy `bottomBarZone` name the older tests still use. 104 = 64 + 28 + 12,
     * 108 = 64 + 32 + 12, 132 = 64 + 56 + 12.
     */
    @Test
    fun floatingZoneKeepsTheThreePinnedTargets() {
        assertEquals(104.dp, floatingBarZone(0.dp))
        assertEquals(108.dp, floatingBarZone(24.dp))
        assertEquals(132.dp, floatingBarZone(48.dp))
        listOf(BottomBarStyle.Floating, BottomBarStyle.FloatingBlur).forEach { style ->
            assertEquals(104.dp, barZoneFor(style, 0.dp), "$style @0")
            assertEquals(108.dp, barZoneFor(style, 24.dp), "$style @24")
            assertEquals(132.dp, barZoneFor(style, 48.dp), "$style @48")
        }
        // The historical name is the floating form, not a second formula.
        assertEquals(floatingBarZone(0.dp), bottomBarZone(0.dp))
        assertEquals(floatingBarZone(48.dp), bottomBarZone(48.dp))
    }

    // --- 2 · Fixed 只有自己的行 + 自己那条线 + inset -------------------------------

    /**
     * The docked form's zone, term by term: its own 64 dp row (pinned as a literal, so a
     * rename/refactor cannot slide the number), its 1 dp top hairline, and the navigation
     * inset (the same term `FixedBottomBar` adds to its glass height — on a 3-button device
     * the docked bar really is 48 dp taller, and the content must clear all of it).
     */
    @Test
    fun fixedZoneIsItsOwnRowBehindItsRulePlusTheInset() {
        assertEquals(64.dp, FixedBarRowHeight, "Fixed 行高必须是 64dp 字面量（KernelSU 56 + 2×4）")
        assertEquals(1.dp, FixedBarTopRule)
        assertEquals(65.dp, fixedBarZone(0.dp), "桌面：1 线 + 64 行，没有任何 margin/pill")
        assertEquals(89.dp, fixedBarZone(24.dp), "手势：65 + 24")
        assertEquals(113.dp, fixedBarZone(48.dp), "三键：65 + 48")
        listOf(0.dp, 1.dp, 24.dp, 32.dp, 48.dp).forEach { inset ->
            assertEquals(FixedBarTopRule, fixedBarZone(inset) - FixedBarRowHeight - inset)
        }
    }

    /** Fixed is strictly smaller than Floating at every real inset — that is the whole point. */
    @Test
    fun fixedZoneIsSmallerThanFloatingOnEveryInset() {
        listOf(0.dp, 24.dp, 48.dp).forEach { inset ->
            assertTrue(
                fixedBarZone(inset) < floatingBarZone(inset),
                "Fixed 必须比 Floating 小：inset=$inset fixed=${fixedBarZone(inset)} floating=${floatingBarZone(inset)}",
            )
        }
    }

    // --- 3 · 结构解耦：Floating 的 pill 项一条都不许出现在 Fixed 侧 ------------------

    /**
     * Where the pill term lives, exactly: stripping the Floating zone of its own margin and
     * dock pad leaves `FloatingBarPillHeight` and nothing else; stripping the Fixed zone of
     * its own row and inset leaves its 1 dp hairline and nothing else. The residual
     * difference between the zones is therefore margins, not a leftover pill:
     * 39 dp at inset 0 (28 + 12 − 1) and 19 dp at 24/48 ((8 + i) + 12 − i − 1).
     *
     * (A literal `floating − fixed == 64` would require the Fixed zone to be 40 dp at inset
     * 0 — 25 dp shorter than the bar it must clear; see the file header.)
     */
    @Test
    fun fixedZoneDropsThePillTermButKeepsItsOwnRow() {
        assertEquals(
            FloatingBarPillHeight,
            floatingBarZone(0.dp) - floatingBarBottomMargin(0.dp) - BOTTOM_BAR_DOCK_PAD,
            "Floating 侧去掉 margin + dock pad 后只剩 pill",
        )
        assertEquals(39.dp, floatingBarZone(0.dp) - fixedBarZone(0.dp))
        assertEquals(19.dp, floatingBarZone(24.dp) - fixedBarZone(24.dp))
        assertEquals(19.dp, floatingBarZone(48.dp) - fixedBarZone(48.dp))
        listOf(0.dp, 24.dp, 48.dp).forEach { inset ->
            assertTrue(
                floatingBarZone(inset) - fixedBarZone(inset) < FloatingBarPillHeight,
                "差值若仍 ≥ pill 高，说明 Fixed 侧还留着胶囊的足迹（inset=$inset）",
            )
        }
    }

    /** `barZoneFor` is the single dispatcher; each style gets its own formula, never a mix. */
    @Test
    fun barZoneForRoutesEachStyleToItsOwnFormula() {
        listOf(0.dp, 24.dp, 48.dp).forEach { inset ->
            assertEquals(fixedBarZone(inset), barZoneFor(BottomBarStyle.Fixed, inset))
            assertEquals(floatingBarZone(inset), barZoneFor(BottomBarStyle.Floating, inset))
            assertEquals(floatingBarZone(inset), barZoneFor(BottomBarStyle.FloatingBlur, inset))
        }
        assertTrue(barZoneFor(BottomBarStyle.Fixed, 0.dp) != barZoneFor(BottomBarStyle.Floating, 0.dp))
    }

    // --- 4 · 隐藏底栏：两形态都不预留 ------------------------------------------------

    /** D88 (pushed detail / rail breakpoints): no bar composed → no zone, for every style. */
    @Test
    fun hiddenBarReservesNothingOnBothForms() {
        BottomBarStyle.entries.forEach { style ->
            listOf(0.dp, 24.dp, 48.dp).forEach { inset ->
                assertEquals(
                    0.dp,
                    contentBottomInset(showBar = false, navInset = inset, style = style),
                    "$style inset=$inset：底栏没绘制就不能预留",
                )
            }
        }
        // And the shown bar still routes to the form's own zone.
        assertEquals(
            104.dp,
            contentBottomInset(showBar = true, navInset = 0.dp, style = BottomBarStyle.Floating),
        )
        assertEquals(
            65.dp,
            contentBottomInset(showBar = true, navInset = 0.dp, style = BottomBarStyle.Fixed),
        )
    }

    // --- 5 · 单调：两形态都随 inset 增长 --------------------------------------------

    /** Both forms grow with the inset; across the `8 + inset` branch the growth is 1:1. */
    @Test
    fun bothFormsGrowWithTheInset() {
        listOf(0.dp, 24.dp, 48.dp).zipWithNext().forEach { (low, high) ->
            assertTrue(fixedBarZone(high) > fixedBarZone(low), "Fixed 必须随 inset 增长")
            assertTrue(floatingBarZone(high) > floatingBarZone(low), "Floating 必须随 inset 增长")
        }
        var inset = 24.dp
        while (inset < 48.dp) {
            val next = inset + 4.dp
            assertEquals(4.dp, fixedBarZone(next) - fixedBarZone(inset), "Fixed：inset +4 → zone +4（inset=$next）")
            assertEquals(
                4.dp,
                floatingBarZone(next) - floatingBarZone(inset),
                "Floating：inset +4 → zone +4（inset=$next）",
            )
            inset = next
        }
    }

    // --- 6 · 真渲染 · 桌面 Fixed：空白从 39dp 降到 0（这是本批的核心数字） -----------

    /**
     * The measurement this batch exists for, done on the **real** bars, not on constants:
     * render `VadeBottomBar` in both styles the way `ShellBody` wraps them, and compare the
     * rendered footprint with the zone each form reserves on desktop (inset actual = 0).
     *
     * Printed numbers (the run's `system-out`): Fixed footprint 65 dp → 39 dp blank under
     * the old shared zone (104), 0 dp under `fixedBarZone` (65); the pre-P1-3 88 dp constant
     * left 23 dp. Floating: footprint 104 dp → 0 dp blank, unchanged.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun desktopFixedBlankDropsFromThirtyNineToZero() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val backdrop = rememberGlassBackdrop()
                Box(Modifier.width(400.dp).fillMaxHeight().testTag(FIXED_STAGE)) {
                    // ShellBody's Fixed branch: barInsets = PaddingValues(0.dp).
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        VadeBottomBar(
                            items = barItems(),
                            selected = 0,
                            onSelect = {},
                            style = BottomBarStyle.Fixed,
                            backdrop = backdrop,
                            modifier = Modifier.testTag(FIXED_BAR),
                        )
                    }
                }
                Box(Modifier.width(400.dp).fillMaxHeight().testTag(FLOATING_STAGE)) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                PaddingValues(start = 12.dp, end = 12.dp, bottom = BOTTOM_BAR_DOCK_PAD),
                            ),
                    ) {
                        VadeBottomBar(
                            items = barItems(),
                            selected = 0,
                            onSelect = {},
                            style = BottomBarStyle.Floating,
                            backdrop = backdrop,
                            modifier = Modifier.testTag(FLOATING_BAR),
                        )
                    }
                }
            }
        }
        waitForIdle()
        val fixedStage = onNodeWithTag(FIXED_STAGE).fetchSemanticsNode().boundsInRoot
        val fixedBar = onNodeWithTag(FIXED_BAR).fetchSemanticsNode().boundsInRoot
        val floatingStage = onNodeWithTag(FLOATING_STAGE).fetchSemanticsNode().boundsInRoot
        val floatingBar = onNodeWithTag(FLOATING_BAR).fetchSemanticsNode().boundsInRoot
        with(density) {
            val fixedFootprint = (fixedStage.bottom - fixedBar.top).toDp()
            val floatingFootprint = (floatingStage.bottom - floatingBar.top).toDp()
            val blankBefore = bottomBarZone(0.dp) - fixedFootprint
            val blankAfter = fixedBarZone(0.dp) - fixedFootprint
            val blankLegacy88 = 88.dp - fixedFootprint
            val floatingBlank = floatingBarZone(0.dp) - floatingFootprint
            println(
                "ZONE_SPLIT desktop(inset=0) " +
                    "fixedFootprint=$fixedFootprint fixedZoneBefore=${bottomBarZone(0.dp)} " +
                    "blankBefore=$blankBefore fixedZoneAfter=${fixedBarZone(0.dp)} blankAfter=$blankAfter " +
                    "blankUnderLegacy88=$blankLegacy88 " +
                    "floatingFootprint=$floatingFootprint floatingZone=${floatingBarZone(0.dp)} " +
                    "floatingBlank=$floatingBlank",
            )

            assertEquals(65.dp, fixedFootprint, "Fixed 条真渲染足迹必须正好是 1dp 线 + 64dp 行")
            assertEquals(0.dp, blankAfter, "桌面 Fixed：预留必须贴紧真足迹，不留空白尾垫")
            assertTrue(blankAfter <= blankBefore, "拆函数后不得比拆前更空（前 $blankBefore / 后 $blankAfter）")
            assertEquals(39.dp, blankBefore, "拆前的空白就是 39dp（任务书说 40dp，漏掉了 1dp 线）")
            assertTrue(blankAfter <= blankLegacy88, "也不得比 P1-3 之前的 88dp 常量更多留白")
            assertEquals(0.dp, floatingBlank, "Floating 桌面空白必须仍是 0（上一棒的修复不许被碰坏）")
        }
    }

    // --- 7 · 真渲染几何：两形态各自顶边到容器底 == 自己的 zone ----------------------

    /**
     * Floating form: the real pill's top edge sits exactly `floatingBarZone(0)` above the
     * container bottom — same invariant the P1-3 batch pinned, re-checked here so the split
     * cannot silently move it.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun floatingBarRendersExactlyItsZoneTall() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val backdrop = rememberGlassBackdrop()
                Box(Modifier.width(400.dp).fillMaxHeight().testTag(FIXED_STAGE)) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                PaddingValues(start = 12.dp, end = 12.dp, bottom = BOTTOM_BAR_DOCK_PAD),
                            ),
                    ) {
                        VadeBottomBar(
                            items = barItems(),
                            selected = 0,
                            onSelect = {},
                            style = BottomBarStyle.Floating,
                            backdrop = backdrop,
                            modifier = Modifier.testTag(FLOATING_BAR),
                        )
                    }
                }
            }
        }
        val stage = onNodeWithTag(FIXED_STAGE).fetchSemanticsNode().boundsInRoot
        val bar = onNodeWithTag(FLOATING_BAR).fetchSemanticsNode().boundsInRoot
        with(density) {
            assertEquals(
                floatingBarZone(0.dp),
                (stage.bottom - bar.top).toDp(),
                "胶囊顶边到容器底必须等于 Floating zone (104dp)",
            )
        }
    }

    /**
     * Fixed form: the rendered docked bar (hairline included) is exactly `fixedBarZone(0)`
     * tall. This is what keeps `FixedBarRowHeight` honest — if `FixedBottomBar` ever changes
     * its row, this goes red instead of the content drifting under the bar.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fixedBarRendersExactlyItsZoneTall() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val backdrop = rememberGlassBackdrop()
                Box(Modifier.width(400.dp).fillMaxHeight().testTag(FIXED_STAGE)) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        VadeBottomBar(
                            items = barItems(),
                            selected = 0,
                            onSelect = {},
                            style = BottomBarStyle.Fixed,
                            backdrop = backdrop,
                            modifier = Modifier.testTag(FIXED_BAR),
                        )
                    }
                }
            }
        }
        val stage = onNodeWithTag(FIXED_STAGE).fetchSemanticsNode().boundsInRoot
        val bar = onNodeWithTag(FIXED_BAR).fetchSemanticsNode().boundsInRoot
        with(density) {
            assertEquals(
                fixedBarZone(0.dp),
                (stage.bottom - bar.top).toDp(),
                "条顶边到容器底必须等于 Fixed zone (65dp = 1dp 线 + 64dp 行)",
            )
            assertEquals(
                FixedBarRowHeight + FixedBarTopRule,
                bar.height.toDp(),
                "条自身高度 = 自己那条线 + 自己那行",
            )
        }
    }

    // --- 8 · 真滚动：Fixed 的最后一行也滚得出来 ------------------------------------

    /**
     * The docked form's content side, end to end: a real scrollable fed the Fixed zone as
     * content padding, with the real docked bar over it. Scrolled to the end, the last row's
     * bottom must sit at the bar's top edge — not under it (the analogue of the P1-3 symptom
     * for the form that used to be over-reserved).
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun fixedContentScrollsClearOfTheDockedBar() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val backdrop = rememberGlassBackdrop()
                val scroll = rememberScrollState()
                LaunchedEffect(Unit) {
                    snapshotFlow { scroll.maxValue }.first { it > 0 }
                    scroll.scrollTo(scroll.maxValue)
                }
                Box(Modifier.width(400.dp).fillMaxHeight().testTag(FIXED_STAGE)) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(
                                PaddingValues(
                                    bottom = contentBottomInset(
                                        showBar = true,
                                        navInset = 0.dp,
                                        style = BottomBarStyle.Fixed,
                                    ),
                                ),
                            ),
                    ) {
                        repeat(20) { index ->
                            Box(Modifier.fillMaxWidth().height(48.dp).testTag("fix-row-$index"))
                        }
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag(FIXED_LAST_ROW))
                    }
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        VadeBottomBar(
                            items = barItems(),
                            selected = 0,
                            onSelect = {},
                            style = BottomBarStyle.Fixed,
                            backdrop = backdrop,
                            modifier = Modifier.testTag(FIXED_BAR),
                        )
                    }
                }
            }
        }
        waitForIdle()
        val stage = onNodeWithTag(FIXED_STAGE).fetchSemanticsNode().boundsInRoot
        val last = onNodeWithTag(FIXED_LAST_ROW).fetchSemanticsNode().boundsInRoot
        val bar = onNodeWithTag(FIXED_BAR).fetchSemanticsNode().boundsInRoot
        with(density) {
            assertEquals(
                (stage.bottom - fixedBarZone(0.dp).toPx()).toDp(),
                last.bottom.toDp(),
                "滚到底后最后一行必须停在 Fixed zone 的上沿（= 条顶边），既不被压也不被白推",
            )
            assertTrue(
                last.bottom <= bar.top + 0.5f,
                "最后一行底边 ${last.bottom} 低于条顶边 ${bar.top} —— 会被玻璃条压住",
            )
        }
    }

    // --- 9 · 反向守卫：Fixed 的表达式中不得出现 FloatingBarPillHeight ---------------

    /**
     * The bug was a *structural* re-coupling, so the guard is structural too: the docked
     * form's expression, its row constant and the Fixed arm of the dispatcher must not name
     * `FloatingBarPillHeight`. `FixedBarRowHeight` must stay a literal `64.dp` — writing
     * `= FloatingBarPillHeight` would pass every numeric test above while re-tying the two
     * forms together.
     */
    @Test
    fun fixedZoneExpressionNeverMentionsThePillHeight() {
        val appShell = codeOf(src("commonMain/kotlin/vade/ui/shell/AppShell.kt"))

        val fixedBody = bodyOf(appShell, "internal fun fixedBarZone(navInset: Dp): Dp")
        assertTrue(fixedBody.contains("FixedBarRowHeight"), "fixedBarZone 必须用 Fixed 自己的行高：$fixedBody")
        assertTrue(fixedBody.contains("FixedBarTopRule"), "fixedBarZone 必须含自己那条线：$fixedBody")
        assertEquals(
            0,
            count(fixedBody, "FloatingBarPillHeight"),
            "fixedBarZone 不得引用 FloatingBarPillHeight —— 那正是本批拆掉的耦合",
        )

        val rowDecl = Regex("""internal val FixedBarRowHeight: Dp = ([0-9.]+)\.dp""").find(appShell)
        assertNotNull(rowDecl, "FixedBarRowHeight 必须是 64.dp 字面量")
        assertEquals("64", rowDecl.groupValues[1], "FixedBarRowHeight 不得由 FloatingBarPillHeight 推导")

        val dispatcher = bodyOf(appShell, "internal fun barZoneFor(style: BottomBarStyle, navInset: Dp): Dp")
        val fixedArm = dispatcher.substringAfter("BottomBarStyle.Fixed ->").substringBefore("BottomBarStyle.Floating")
        assertTrue(fixedArm.contains("fixedBarZone"), "Fixed 分支必须走 fixedBarZone：$fixedArm")
        assertEquals(0, count(fixedArm, "FloatingBarPillHeight"), "Fixed 分支不得提 pill 高")

        val contentInset = bodyOf(appShell, "internal fun contentBottomInset(")
        assertTrue(contentInset.contains("barZoneFor(style, navInset)"), "contentBottomInset 必须按 style 分发")
    }

    // --- 10 · 接线：ShellBody 真的按 barStyle 取 zone ------------------------------

    /** Arithmetic in a helper nobody passes the style to is not a fix. */
    @Test
    fun shellFeedsTheStyleAwareZoneIntoTheContentInsets() {
        val appShell = codeOf(src("commonMain/kotlin/vade/ui/shell/AppShell.kt"))
        assertEquals(
            1,
            count(appShell, """bottom = contentBottomInset\(showBar, navInset, state\.barStyle\)"""),
            "contentInsets 的 bottom 必须带 barStyle 进 contentBottomInset",
        )
        assertEquals(
            1,
            count(appShell, """val navInset = navigationBarBottomInset\(\)"""),
            "inset 必须只读一次并复用",
        )
        assertEquals(
            1,
            count(appShell, """bottom = BOTTOM_BAR_DOCK_PAD"""),
            "胶囊外层 padding 必须与 zone 用同一个 dock pad 常量",
        )
        assertEquals(0, count(appShell, "BOTTOM_BAR_ZONE"), "88dp 死常量不得回来")
        val shellBody = appShell.substringAfter("private fun ShellBody")
        assertEquals(
            0,
            count(shellBody, """floatingBarZone\("""),
            "ShellBody 不得绕过 barZoneFor 直接用 Floating 公式",
        )
    }
}

/** Test tags for the rendered stage / bar pairs. */
private const val FIXED_STAGE = "fixed-stage"
private const val FIXED_BAR = "fixed-bar"
private const val FLOATING_STAGE = "floating-stage"
private const val FLOATING_BAR = "floating-bar"
private const val FIXED_LAST_ROW = "fixed-last-row"
