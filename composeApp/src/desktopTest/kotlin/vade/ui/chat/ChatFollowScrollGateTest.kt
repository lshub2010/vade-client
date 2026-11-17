package vade.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * K4 · 跟随滚动的四个状态（[ChatThread.kt] 里 [FollowScrollGate] 的 KDoc 点名本类）。
 *
 * 状态机是纯的（无窗口、无时钟），所以这里逐帧推进，不真的滚一次列表。
 * `valuePx` 的语义是**离底距离**（0 = 贴底），阈值 [FollowThreshold] 换算成近底区间。
 *
 * 四态：
 *  1. 贴底 + 新内容 → 跟随（`true`），胶囊不举；
 *  2. 上滚 + 新内容 → **一个像素都不动**（`false`），胶囊举起；
 *  3. 用户自己拖回底部 → 胶囊收起，跟随恢复（下一次 `onContentChanged` 又是 `true`）；
 *  4. 点胶囊 → 立刻收起（滚动由调用方发起，回滚动画期间不许还挂着提示）。
 */
class ChatFollowScrollGateTest {

    /** 近底区间（px）。取 48 而不是 0：惯性回弹会让 `scroll.value` 轻微离开 0。 */
    private val nearBottom = 48

    private fun gate() = FollowScrollGate(nearBottomPx = nearBottom)

    // ------------------------------------------------------------ 1 · 贴底跟随

    @Test
    fun contentChangeWhileAtBottomFollows() {
        val gate = gate()
        assertTrue(gate.onContentChanged(0), "贴底时新内容落地 → 调用方应该 scrollTo(0)")
        assertFalse(gate.unseenBelow, "跟着走的时候不举「新内容」胶囊")
    }

    @Test
    fun bottomIsInclusiveUpToTheThreshold() {
        val gate = gate()
        assertTrue(gate.isAtBottom(0))
        assertTrue(gate.isAtBottom(nearBottom), "阈值本身算贴底（<=，不是 <）")
        assertFalse(gate.isAtBottom(nearBottom + 1))
    }

    // ------------------------------------------------------------ 2 · 上滚不拽

    @Test
    fun contentChangeWhileScrolledUpDoesNotMoveTheReader() {
        val gate = gate()
        assertFalse(gate.onContentChanged(400), "上滚期间新内容落地：一个像素都不许动")
        assertTrue(gate.unseenBelow, "没看到的新内容要在底部举胶囊")
    }

    @Test
    fun capsuleStaysUpWhileStillScrolledUp() {
        val gate = gate()
        gate.onContentChanged(400)
        gate.onScroll(300)
        assertTrue(gate.unseenBelow, "还在上面读旧内容，胶囊不许自己收起")
        gate.onContentChanged(600) // 又一条新内容
        assertTrue(gate.unseenBelow, "再举一次也不会误收起")
    }

    // ------------------------------------------------------------ 3 · 回底清提示

    @Test
    fun scrollingBackToBottomClearsTheCapsuleAndRestoresFollowing() {
        val gate = gate()
        gate.onContentChanged(400)
        gate.onScroll(nearBottom + 1)
        assertTrue(gate.unseenBelow, "差 1px 还没贴上，胶囊继续举")
        gate.onScroll(nearBottom)
        assertFalse(gate.unseenBelow, "自己拖回底部 = 已经看到新内容，收起")
        assertTrue(gate.onContentChanged(nearBottom), "回底之后跟随恢复")
    }

    // ------------------------------------------------------------ 4 · 点胶囊

    @Test
    fun tappingTheCapsuleClearsItImmediately() {
        val gate = gate()
        gate.onContentChanged(400)
        gate.onJumpToBottom()
        assertFalse(gate.unseenBelow, "点了胶囊立刻收起（回滚动画期间也不挂提示）")
        assertTrue(gate.onContentChanged(0), "回到底部后，下一次内容变化照常跟随")
    }

    @Test
    fun jumpToBottomOnAnIdleGateIsHarmless() {
        val gate = gate()
        gate.onJumpToBottom()
        assertFalse(gate.unseenBelow, "本来就没举，点了也不会凭空出现")
        assertTrue(gate.onContentChanged(0))
    }
}
