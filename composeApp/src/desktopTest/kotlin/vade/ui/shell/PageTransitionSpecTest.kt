// n3-blur · 页面切换动画（用户 2026-11-18 真机反馈第 3 条：「页面切换动画缺失」）。
//
// 口径（任务书原文）：列表 ⇄ 会话、四个 tab 之间**至少一条统一过渡**（淡入 + 轻微位移
// 或缩放，**≤220ms**，尊重 reduced motion），且**不许**用会抢点击的
// `clickable(enabled=false)` 遮罩。
//
// 这里钉的是那段过渡的**配置**（纯函数），不是像素：
//  - `pageEnter` 的时长必须 ≤ [PAGE_ENTER_MAX_MS]（换皮肤把 slowMs 调大也压得住）；
//  - 位移只有一个 8dp 刻度；
//  - reduced-motion 下 = 纯淡入（位移 0、时长封顶 100ms）；
//  - 结构上：`AppShell` 里只有**一处** `.pageEnter(`，挂在内容列上；过渡文件里没有
//    任何 `clickable` / 覆盖层 —— 那条路本仓踩过。

package vade.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.skin.REDUCE_MOTION_MAX_MS
import vade.ui.skin.SkinMotion

class PageTransitionSpecTest {

    @Test
    fun theDefaultTransitionIsAtMost220msWithOneEightDpStep() {
        val spec = pageEnterSpec(animate = true, reduceMotion = false)
        assertEquals(
            SkinMotion().slowMs,
            spec.durationMillis,
            "默认时长必须就是皮肤的 slowMs（§六 的页面/面板档）",
        )
        assertTrue(
            spec.durationMillis <= PAGE_ENTER_MAX_MS,
            "页面过渡不得超 ${PAGE_ENTER_MAX_MS}ms（实际 ${spec.durationMillis}）",
        )
        assertEquals(PAGE_ENTER_TRAVEL_DP, spec.travelDp, "位移是一个 8dp 刻度")
    }

    /**
     * 「≤220ms」是**代码里成立的**约束，不是「默认值恰好等于它」：皮肤把 slowMs 调到 300
     * 时，页面过渡仍然停在 220。
     */
    @Test
    fun aSlowerSkinCannotPushTheTransitionPastTheCap() {
        val spec = pageEnterSpec(animate = true, reduceMotion = false, motion = SkinMotion(slowMs = 300))
        assertEquals(PAGE_ENTER_MAX_MS, spec.durationMillis, "slowMs=300 时也必须被压到 220")
    }

    @Test
    fun animateFalseSnapsImmediately() {
        val spec = pageEnterSpec(animate = false)
        assertEquals(0, spec.durationMillis, "截图 / 测试路径必须 0ms 到位")
    }

    /**
     * K1 · reduced-motion：**位移归零**（前庭敏感的读者被滑动冒犯，不被淡入冒犯），
     * 时长封顶 [REDUCE_MOTION_MAX_MS]，淡入保留 —— 不是 snap。
     */
    @Test
    fun reduceMotionKeepsTheFadeButDropsTheTravel() {
        val spec = pageEnterSpec(animate = true, reduceMotion = true)
        assertEquals(0f, spec.travelDp, "reduced-motion 下位移必须归零（纯淡入）")
        assertTrue(
            spec.durationMillis in 1..REDUCE_MOTION_MAX_MS,
            "reduced-motion 下时长必须落在 1..${REDUCE_MOTION_MAX_MS}ms（实际 ${spec.durationMillis}）—— 归零会让页面凭空出现",
        )
    }

    // --- 接线与反模式 -------------------------------------------------------------

    private fun codeOf(path: String): String {
        val file = java.io.File("src/commonMain/kotlin/$path")
        assertTrue(file.isFile, "缺 ${file.path}")
        return file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    /**
     * 接线：内容列的过渡只认目的地，会话换页的过渡挂在详情 pane 自己身上（审查 P1-2）。
     *
     * 旧写法把 `sessionId` 也塞进列键，双栏点一行会话时整列（含左边的列表）一起淡出。
     * 这条与 `ShellPerfGuardsTest.theContentColumnKeyIsTheDestinationOnly` 同口径 ——
     * 这里从「过渡契约」一侧钉，那里从「壳层实现」一侧钉。
     */
    @Test
    fun theShellWiresTheColumnTransitionToTheDestinationOnly() {
        val shell = codeOf("vade/ui/shell/AppShell.kt")
        assertTrue(
            Regex("""\.pageEnter\(key = state\.dest\b""").containsMatchIn(shell),
            "内容列必须用 `.pageEnter(key = state.dest` —— 键只认目的地",
        )
        assertFalse(
            shell.contains("state.dest to state.selectedSessionId"),
            "列键又带上了会话 id ⇒ 双栏切会话会把列表 pane 一起淡出",
        )
        assertEquals(
            2,
            Regex("""key = state\.selectedSessionId""").findAll(shell).count(),
            "详情 pane（双栏 + rail 单栏）各要一条 pageEnter(key = sessionId)",
        )
    }

    /**
     * 反模式守卫：过渡**不许**用会抢点击的遮罩。
     *
     * 本仓踩过：`clickable(enabled = false)` 仍然是一个命中测试节点，会吃掉底下真实控件的
     * 点击（用户看到「点了没反应」）。过渡文件里出现 `clickable` 本身就是这条禁令的违例。
     */
    @Test
    fun theTransitionNeverUsesAClickableScrim() {
        val transition = codeOf("vade/ui/shell/PageTransition.kt")
        listOf("clickable", "pointerInput", "Box(Modifier.matchParentSize").forEach { forbidden ->
            assertFalse(
                transition.contains(forbidden),
                "PageTransition.kt 里出现了 $forbidden —— 过渡不许铺任何命中节点（clickable(enabled=false) 遮罩坑）",
            )
        }
        // 整段动画只在 graphicsLayer 里读进度：读进组合就会每帧重组整页。
        assertTrue(
            Regex("""\.graphicsLayer \{""").containsMatchIn(transition),
            "过渡必须走 graphicsLayer（draw 阶段读进度），否则动画期间整页每帧重组",
        )
        assertTrue(
            transition.contains("alpha = progress.value"),
            "graphicsLayer 里必须延迟读取 progress.value —— 这才是「零重组」的那条路",
        )
    }
}
