package vade.ui.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * K4 · 跟随滚动的决策表（[FollowScroll.kt] 里 [FollowScrollGate] 的 KDoc 点名本类）。
 *
 * 状态机是纯的（无窗口、无时钟、无副作用），所以这里逐帧推进，不真的滚一次列表。
 * `valuePx` 的语义是**离底距离**（0 = 贴底），阈值 [FollowThreshold] 换算成近底区间。
 *
 * 覆盖三组契约：
 *  1. 跟随：贴底 + 新内容 → 钉回 0，胶囊不举；上滚 → 一次都不拽回。
 *  2. **不漂移**（j1-scroll P1-9）：上滚时内容在底部增长 → `scroll.value` 平移同样的增量，
 *     可视窗口顶边 `maxValue - value` 一个像素都不动。
 *  3. **不误报**（j1-scroll P1-10）：composer 高度只是布局，变高只补偿、绝不举「新内容」；
 *     胶囊只认内容键（items 数 + 末条正文长度）。
 */
class ChatFollowScrollGateTest {

    /** 近底区间（px）。取 48 而不是 0：惯性回弹会让 `scroll.value` 轻微离开 0。 */
    private val nearBottom = 48

    private fun gate() = FollowScrollGate(nearBottomPx = nearBottom)

    /**
     * 已经量过一轮的 gate：首帧测量只记账、不位移（否则首次组合会把满屏高度当增长补偿，
     * 读者凭空跳一段）。要测首帧本身用裸 [gate]。
     */
    private fun seededGate(messagesPx: Int = 1_000, composerPx: Int = 120): FollowScrollGate =
        gate().apply {
            onHeightChanged(FollowHeightKind.Messages, messagesPx, 0)
            onHeightChanged(FollowHeightKind.Composer, composerPx, 0)
        }

    /**
     * 举胶囊：先用当前内容键记账，再报一条新内容（位置必须在上滚区）。
     * 高度变化本身**永远不举胶囊**（P1-10），所以这里只走内容键。
     */
    private fun FollowScrollGate.armCapsule(valuePx: Int = 400, items: Int = 6, lastBodyLength: Int = 500) {
        onContentChanged(items = items, lastBodyLength = lastBodyLength, valuePx = valuePx)
        onContentChanged(items = items + 1, lastBodyLength = 12, valuePx = valuePx)
    }

    /** 可视窗口顶边（内容坐标）—— `reverseScrolling` 下就是 `maxValue - value`。 */
    private fun topEdge(maxValuePx: Int, valuePx: Int): Int = maxValuePx - valuePx

    // ------------------------------------------------------- 1 · 贴底跟随

    @Test
    fun contentGrowthWhileAtBottomPinsToBottom() {
        val gate = seededGate()
        assertEquals(
            FollowScrollDirective.PinToBottom,
            gate.onHeightChanged(FollowHeightKind.Messages, 1_060, 0),
            "贴底时新内容落地 → 钉回 0，最新一行保持可见",
        )
        assertFalse(gate.unseenBelow, "跟着走的时候不举「新内容」胶囊")
    }

    @Test
    fun bottomIsInclusiveUpToTheThreshold() {
        val gate = gate()
        assertTrue(gate.isAtBottom(0))
        assertTrue(gate.isAtBottom(nearBottom), "阈值本身算贴底（<=，不是 <）")
        assertFalse(gate.isAtBottom(nearBottom + 1))
    }

    @Test
    fun firstMeasurementNeverMovesTheReader() {
        val gate = gate()
        assertEquals(
            FollowScrollDirective.None,
            gate.onHeightChanged(FollowHeightKind.Messages, 2_000, 400),
            "首帧测量只是记账：满屏高度不是「内容增长」，不许补偿",
        )
        assertFalse(gate.unseenBelow)
    }

    @Test
    fun zeroHeightIsTreatedAsUnmeasured() {
        val gate = gate()
        gate.onHeightChanged(FollowHeightKind.Messages, 0, 400)
        assertEquals(
            FollowScrollDirective.None,
            gate.onHeightChanged(FollowHeightKind.Messages, 900, 400),
            "0 → 实高是首次测量（快照流初值），不是「内容增长」",
        )
        assertFalse(gate.unseenBelow)
    }

    @Test
    fun unchangedHeightIsNotAChange() {
        val gate = seededGate()
        assertEquals(FollowScrollDirective.None, gate.onHeightChanged(FollowHeightKind.Messages, 1_000, 400))
        assertEquals(FollowScrollDirective.None, gate.onHeightChanged(FollowHeightKind.Composer, 120, 400))
        assertFalse(gate.unseenBelow)
    }

    // ------------------------------------- 2 · 上滚：不拽回，也不漂移（P1-9）

    @Test
    fun growthWhileScrolledUpShiftsByTheGrowthInsteadOfDragging() {
        val gate = seededGate()
        gate.armCapsule(400)
        val directive = gate.onHeightChanged(FollowHeightKind.Messages, 1_080, 400)
        assertEquals(
            FollowScrollDirective.ShiftBy(80),
            directive,
            "上滚期间内容长了 80px：value 平移 80px，不是 0，也不是跳底",
        )
        val shift = (directive as FollowScrollDirective.ShiftBy).deltaPx
        assertEquals(
            topEdge(maxValuePx = 4_000, valuePx = 400),
            topEdge(maxValuePx = 4_000 + 80, valuePx = 400 + shift),
            "可视窗口顶边不动 = 读者读到的那一段原地不动（P1-9 的核心判据）",
        )
        assertTrue(gate.unseenBelow, "没看到的新内容仍在底部举着胶囊")
    }

    @Test
    fun perFrameGrowthAccumulatesWithoutDrift() {
        // 流式正文按帧长：120px → 60px → 30px，三帧各自补偿，总和必须等于总增长。
        val gate = seededGate()
        var value = 400
        var content = 1_000
        listOf(120, 60, 30).forEach { delta ->
            val directive = gate.onHeightChanged(FollowHeightKind.Messages, content + delta, value)
            content += delta
            value += (directive as FollowScrollDirective.ShiftBy).deltaPx
        }
        assertEquals(1_210, content)
        assertEquals(610, value, "400 + 120 + 60 + 30 —— 逐帧补偿不会丢增量，也不会重复计")
        assertEquals(
            topEdge(5_000, 400),
            topEdge(5_000 + 210, value),
            "三帧之后阅读位置仍然一个像素都没动",
        )
    }

    @Test
    fun contentShrinkWhileScrolledUpCompensatesDownward() {
        val gate = seededGate(messagesPx = 1_200)
        assertEquals(
            FollowScrollDirective.ShiftBy(-100),
            gate.onHeightChanged(FollowHeightKind.Messages, 1_100, 400),
            "内容收缩同样要补偿，否则文字会往下滑",
        )
        assertFalse(gate.unseenBelow, "收缩不是新内容")
    }

    @Test
    fun compensateNeverPushesValueBelowZero() {
        // value=50、内容缩了 200：只回 50 到 0，不能变成负数。
        assertEquals(
            -50,
            followShiftForGrowth(previousHeightPx = 1_000, newHeightPx = 800, valuePx = 50),
            "补偿把位置压到 0 以下时只回到 0",
        )
        assertEquals(
            200,
            followShiftForGrowth(previousHeightPx = 1_000, newHeightPx = 1_200, valuePx = 30),
            "增长方向不被 0 截断（上限由 ScrollState 按 maxValue 截）",
        )
    }

    // ------------------------------------ 3 · composer 只是布局，不许举胶囊（P1-10）

    @Test
    fun composerGrowthWhileScrolledUpCompensatesButNeverRaisesTheCapsule() {
        val gate = seededGate(composerPx = 120)
        val directive = gate.onHeightChanged(FollowHeightKind.Composer, 168, 400)
        assertEquals(
            FollowScrollDirective.ShiftBy(48),
            directive,
            "输入框变高会抬高列表底部内边距，同样会把文字上推，所以要补偿",
        )
        assertFalse(
            gate.unseenBelow,
            "P1-10：composer 高度只用于布局，上滚时变高绝不能举「新内容」胶囊",
        )
    }

    @Test
    fun composerGrowthAtBottomOnlyPinsAndStaysQuiet() {
        val gate = seededGate(composerPx = 120)
        assertEquals(FollowScrollDirective.PinToBottom, gate.onHeightChanged(FollowHeightKind.Composer, 168, 0))
        assertFalse(gate.unseenBelow, "贴底时输入框变高更不该报「新内容」")
    }

    @Test
    fun composerAndMessagesAccountSeparately() {
        // 同一帧两列都变了：各补各的增量；高度一路一条都不许举胶囊。
        val gate = seededGate()
        val fromMessages = gate.onHeightChanged(FollowHeightKind.Messages, 1_040, 400)
        val fromComposer = gate.onHeightChanged(FollowHeightKind.Composer, 144, 440)
        assertEquals(FollowScrollDirective.ShiftBy(40), fromMessages)
        assertEquals(FollowScrollDirective.ShiftBy(24), fromComposer)
        assertFalse(gate.unseenBelow, "两列高度都变了也只是布局，胶囊由内容键单独决定")
    }

    // ------------------------------------------- 4 · 胶囊只认内容键（不是高度）

    @Test
    fun firstContentStampDoesNotRaiseTheCapsule() {
        val gate = gate()
        assertFalse(
            gate.onContentChanged(items = 6, lastBodyLength = 500, valuePx = 400),
            "首次订阅只记账：进会话不该凭空举一枚胶囊",
        )
        assertFalse(gate.unseenBelow)
    }

    @Test
    fun sameLineStreamingRaisesTheCapsuleWithoutAnyHeightChange() {
        val gate = seededGate()
        assertFalse(gate.onContentChanged(items = 6, lastBodyLength = 500, valuePx = 400))
        assertTrue(
            gate.onContentChanged(items = 6, lastBodyLength = 517, valuePx = 400),
            "末条正文变长（还没换行、高度没变）也是新内容",
        )
        assertTrue(gate.unseenBelow)
        assertEquals(
            FollowScrollDirective.None,
            gate.onHeightChanged(FollowHeightKind.Messages, 1_000, 400),
            "高度没变 → 不需要位移，但胶囊已经在上面举着了",
        )
    }

    @Test
    fun newItemWhileScrolledUpRaisesTheCapsule() {
        val gate = seededGate()
        gate.onContentChanged(items = 6, lastBodyLength = 500, valuePx = 400)
        assertTrue(gate.onContentChanged(items = 7, lastBodyLength = 12, valuePx = 400), "items 增长 = 新内容")
        assertTrue(gate.unseenBelow)
    }

    @Test
    fun newContentWhileAtBottomDoesNotRaiseTheCapsule() {
        val gate = seededGate()
        gate.onContentChanged(items = 6, lastBodyLength = 500, valuePx = 0)
        assertTrue(gate.onContentChanged(items = 7, lastBodyLength = 12, valuePx = 0))
        assertFalse(gate.unseenBelow, "贴着底的人已经看到了，不用提示")
    }

    @Test
    fun removingARowIsNotNewContent() {
        val gate = seededGate()
        gate.onContentChanged(items = 6, lastBodyLength = 500, valuePx = 400)
        assertFalse(
            gate.onContentChanged(items = 5, lastBodyLength = 480, valuePx = 400),
            "乐观行被移除（发失败 / 编辑重发）是内容变少，不是新内容",
        )
        assertFalse(gate.unseenBelow)
    }

    // ------------------------------------------------- 5 · 胶囊生命周期

    @Test
    fun capsuleStaysUpWhileStillScrolledUp() {
        val gate = seededGate()
        gate.armCapsule(400)
        gate.onScroll(300)
        assertTrue(gate.unseenBelow, "还在上面读旧内容，胶囊不许自己收起")
        gate.armCapsule(valuePx = 300, items = 7)
        assertTrue(gate.unseenBelow, "再举一次也不会误收起")
    }

    @Test
    fun scrollingBackToBottomClearsTheCapsuleAndRestoresFollowing() {
        val gate = seededGate()
        gate.armCapsule(400)
        gate.onScroll(nearBottom + 1)
        assertTrue(gate.unseenBelow, "差 1px 还没贴上，胶囊继续举")
        gate.onScroll(nearBottom)
        assertFalse(gate.unseenBelow, "自己拖回底部 = 已经看到新内容，收起")
        assertEquals(
            FollowScrollDirective.PinToBottom,
            gate.onHeightChanged(FollowHeightKind.Messages, 1_520, nearBottom),
            "回底之后跟随恢复",
        )
    }

    @Test
    fun tappingTheCapsuleClearsItImmediately() {
        val gate = seededGate()
        gate.armCapsule(400)
        gate.onJumpToBottom()
        assertFalse(gate.unseenBelow, "点了胶囊立刻收起（回滚动画期间也不挂提示）")
        assertEquals(
            FollowScrollDirective.PinToBottom,
            gate.onHeightChanged(FollowHeightKind.Messages, 1_480, 0),
            "回到底部后，下一次内容变化照常跟随",
        )
    }

    @Test
    fun jumpToBottomOnAnIdleGateIsHarmless() {
        val gate = seededGate()
        gate.onJumpToBottom()
        assertFalse(gate.unseenBelow, "本来就没举，点了也不会凭空出现")
        assertEquals(FollowScrollDirective.PinToBottom, gate.onHeightChanged(FollowHeightKind.Messages, 1_100, 0))
    }
}
