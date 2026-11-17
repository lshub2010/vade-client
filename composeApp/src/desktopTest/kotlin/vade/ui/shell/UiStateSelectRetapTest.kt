package vade.ui.shell

import vade.ui.data.FakeSessionSource
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 审查 P1-12 · 「重复点当前 tab」的判定必须区分 **双栏 / 单栏**。
 *
 * 病灶：`select()` 以前对「会话页 + 有选中」一律 [UiState.back]。双栏里列表就在详情
 * 左边，根本没有可退的一层 —— 再点一次「会话」把用户正在读的会话关掉、右栏退回
 * 「在左侧选择一个会话」，用户看到的是「点了个 tab，会话没了」。
 *
 * 形状：布局事实（列表是否就在详情左边）由 `AppShell` 的 `ShellMetrics.twoPane` 传进来，
 * 默认 `false` = 单栏 —— 历史调用点（不带参的 `ui.select(dest)`）因此仍是「退一层」，
 * P2-1 那条语义在手机 / 600–719 rail 上逐字节不变。
 *
 * 本文件钉住四条边：双栏不退、单栏照退、列表根 no-op、离开会话页照旧清选中。
 */
class UiStateSelectRetapTest {

    private fun ui() = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
        source = FakeSessionSource(),
    )

    @Test
    fun twoPaneRetapOnTheCurrentTabKeepsTheSelectedSession() {
        val state = ui()
        state.open("s-u1a")
        assertNotNull(state.selectedSessionId)

        state.select(Destination.Sessions, listBesideDetail = true)

        assertEquals(
            "s-u1a",
            state.selectedSessionId,
            "双栏里再点当前 tab 不许清掉选中 —— 列表就在左边，没有可退的一层（P1-12）",
        )
        assertNotNull(state.selectedSession, "右侧详情必须还在（选中没被清）")
        assertFalse(state.sessionsDrawerOpen, "双栏不渲染抽屉，重复点 tab 也不许把它翻成 open")
        assertEquals(Destination.Sessions, state.dest, "目的地不变")
    }

    @Test
    fun singlePaneRetapOnTheCurrentTabStillBacksOut() {
        val state = ui()
        state.open("s-u1a")

        state.select(Destination.Sessions) // 默认 = 单栏（手机抽屉 / 600–719 rail）

        assertNull(state.selectedSessionId, "单栏：再点当前 tab = 退一层（P2-1 的既有语义）")
        assertTrue(state.sessionsDrawerOpen, "单栏退回后抽屉必须滑出（列表是唯一出口）")
        assertEquals(Destination.Sessions, state.dest)
    }

    @Test
    fun twoPaneRetapOnTheListRootIsANoOp() {
        val state = ui()
        assertNull(state.selectedSessionId)

        state.select(Destination.Sessions, listBesideDetail = true)

        assertNull(state.selectedSessionId)
        assertTrue(state.sessionsDrawerOpen, "列表根的抽屉初始就开着；重复点 tab 不许改它")
        assertEquals(Destination.Sessions, state.dest)
    }

    @Test
    fun twoPaneLeavingTheTabStillClearsTheSelection() {
        val state = ui()
        state.open("s-u1a")

        state.select(Destination.Me, listBesideDetail = true)

        assertNull(state.selectedSessionId, "离开会话页 ⇒ 清选中（旧行为，逐字节不变）")
        assertEquals(Destination.Me, state.dest)
    }

    @Test
    fun retapOnANonSessionsTabStillClearsTheSelection() {
        val state = ui()
        // 造出「目的地不是会话页、但有选中」的形状（`open()` 只写选中，不改目的地），
        // 再重复点当前 tab —— 走的正是 `when` 里「离开会话页 ⇒ 清选中」那条。
        state.select(Destination.Capabilities)
        state.open("s-u1a")
        assertNotNull(state.selectedSessionId)
        assertEquals(Destination.Capabilities, state.dest)

        state.select(Destination.Capabilities, listBesideDetail = true)

        assertNull(state.selectedSessionId, "非会话页重复点 tab ⇒ 清选中（旧行为，逐字节不变）")
        assertEquals(Destination.Capabilities, state.dest)
    }
}
