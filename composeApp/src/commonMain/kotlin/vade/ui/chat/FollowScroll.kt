package vade.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp

/*
 * K4 · 跟随滚动 —— 纯决策层（无窗口、无时钟、无副作用），`ChatThread` 只负责把
 * [FollowScrollDirective] 落到 `ScrollState` 上。
 *
 * ## 为什么需要「补偿」而不是「跳底」
 *
 * 会话列是 `verticalScroll(state, reverseScrolling = true)`：`scroll.value` 是**离底距离**，
 * 可视窗口顶边 = `maxValue - value`。流式正文或新行落在底部时 `maxValue` 增长 delta，
 * 顶边跟着 +delta —— 正在上滚读历史的人会看到**文字整体被上推**。旧实现只在贴底时
 * `scrollTo(0)`，非贴底时什么都不做，于是漂移（j1-scroll P1-9）。
 *
 * 正确做法是：非贴底时把 `value` 平移同样的 delta，`maxValue - value` 保持不变 →
 * 屏幕上读的那一段一个像素都不动。
 *
 * ## 两条观测不能合并（P1-10）
 *
 *  - **位置**：消息列与 composer 的实测高度（[FollowScrollGate.onHeightChanged]）。composer
 *    变高会让列表底部内边距（= 高度 + 24dp）变高，同样会把文字上推，所以它**要**参与
 *    补偿 —— 但它只是布局。
 *  - **内容**：items 数 + 末条正文长度（[FollowScrollGate.onContentChanged]）。只有它才算
 *    「有新内容落地」，才允许举底部胶囊。高度（任何一路）永远不写 `unseenBelow`。
 *
 * 旧实现把 `composerHeightPx` 塞进同一个 `snapshotFlow`，上滚时输入框一变高就误报「新内容」。
 */

/**
 * K4 · 「贴近底部」的判定半径：最后 96dp 之内仍算贴底。
 *
 * 不能取 0：`reverseScrolling` 下滚动位置是像素级量，一次轻微的惯性回弹就会让
 * `scroll.value` 离开 0，若阈值取 0，读者「没动」也会被判定成上滚，胶囊会误报。
 */
internal val FollowThreshold = 96.dp

/**
 * 高度变化来自哪一块。**只用于分账**（各自比较上一帧），两类的位移规则相同：
 * 位置补偿是布局问题，任何一路高度都不许举胶囊（P1-10）。
 */
internal enum class FollowHeightKind {
    /** 消息列（时间线本体：items / 流式正文 / 图片解码）。 */
    Messages,

    /** 浮动输入区的实测高度（只影响列表底部内边距，纯布局）。 */
    Composer,
}

/**
 * 高度变了之后调用方该做什么。**只描述位移，不描述动画** —— 补偿要按帧落地，
 * 不能去抢滚动互斥量（见 `ChatThread.applyFollow` 的 KDoc）。
 */
internal sealed interface FollowScrollDirective {
    /** 第一帧测量、高度没变，或没有可做的位移。 */
    data object None : FollowScrollDirective

    /** 贴底：钉回 0，最新一行保持可见（FR-8.35）。 */
    data object PinToBottom : FollowScrollDirective

    /**
     * 非贴底：把 `scroll.value` 平移 [deltaPx]（可为负 = 内容收缩），
     * 可视窗口顶边因此不动 —— 阅读位置一个像素都不许动。
     */
    data class ShiftBy(val deltaPx: Int) : FollowScrollDirective
}

/**
 * 内容在**底部**长了 `newHeight - previousHeight` 时，非贴底读者要保持阅读位置所需的
 * value 位移量（正 = 往上滚，负 = 往下回）。纯函数，测试直接钉。
 */
internal fun followShiftForGrowth(previousHeightPx: Int, newHeightPx: Int, valuePx: Int): Int {
    val delta = newHeightPx - previousHeightPx
    // 补偿不能把位置推到 0 以下；上限交给 ScrollState 自己按 maxValue 截断。
    return if (valuePx + delta < 0) -valuePx else delta
}

/**
 * K4 · 跟随滚动的决策 —— **纯状态机**（无窗口、无时钟、无副作用），因此测试可以逐帧推进
 * 而不需要真的滚动一次。
 *
 * 时刻表（`valuePx` = 离底距离，0 = 贴底）：
 *  - 高度变了 + 贴底 → [FollowScrollDirective.PinToBottom]，胶囊不举；
 *  - 高度变了 + 不贴底 → [FollowScrollDirective.ShiftBy]，胶囊不举（位置补偿 ≠ 新内容）；
 *  - 内容键变了 + 不贴底 → 举胶囊；贴底 → 什么都不做（高度流已经钉底）；
 *  - 用户自己滚回底部 → 胶囊收起，跟随恢复；
 *  - 点了胶囊 → 立刻收起（滚动由调用方发起）。
 *
 * 「上滚后不拽回」是这里的不变式：跟随只在贴底时成立，不是「永远跟随」；
 * 「上滚后不漂移」是它的另一半：[ShiftBy] 抵消布局增量，位置不变、内容不动。
 *
 * 首帧测量（`previous == null`）只记账、不做位移 —— 否则首次组合时会把满屏高度当成
 * 「内容增长」补偿一次，读者会凭空跳一段。`previous == 0` 同理：空列表的第一行不是
 * 「增长」出来的，而 0 高度时也根本不可能处于上滚位置。
 */
internal class FollowScrollGate(private val nearBottomPx: Int) {

    /** 上滚期间有新内容落地 —— 底部胶囊的可见性。 */
    var unseenBelow by mutableStateOf(false)
        private set

    private var messagesHeightPx: Int? = null
    private var composerHeightPx: Int? = null
    private var previousItems: Int? = null
    private var previousBodyLength: Int? = null

    fun isAtBottom(valuePx: Int): Boolean = valuePx <= nearBottomPx

    /** 滚动位置变了：回到贴底区间就清掉提示（拖回去读新内容 = 用户已经看到它了）。 */
    fun onScroll(valuePx: Int) {
        if (unseenBelow && isAtBottom(valuePx)) unseenBelow = false
    }

    /**
     * 内容键变了（items 数 + 末条正文长度）。这是**唯一**允许举胶囊的入口：
     * 贴底时交给高度流钉底，这里不重复做位移；上滚时举胶囊。
     *
     * 返回 `true` = 确实出现了新内容（items 增长或末条正文变长），供测试/调用方读取。
     * 首帧（还没记过账）返回 `false`：进入会话时不该凭空举一枚胶囊。
     */
    fun onContentChanged(items: Int, lastBodyLength: Int, valuePx: Int): Boolean {
        val previousCount = previousItems
        val previousLength = previousBodyLength
        previousItems = items
        previousBodyLength = lastBodyLength
        if (previousCount == null || previousLength == null) return false
        // 旧行被移除（失败的乐观行、编辑重发）是内容变少，不是「新内容」。
        val grew = items > previousCount || lastBodyLength > previousLength
        if (grew && !isAtBottom(valuePx)) unseenBelow = true
        return grew
    }

    /**
     * 某一列（消息 / composer）的实测高度变了。
     *
     * 贴底 → 钉回 0；非贴底 → 按增量补偿 [FollowScrollDirective.ShiftBy]，**不举胶囊**
     * （无论哪一类高度：位置补偿是布局问题，不是内容问题）。
     */
    fun onHeightChanged(kind: FollowHeightKind, newHeightPx: Int, valuePx: Int): FollowScrollDirective {
        val previous = when (kind) {
            FollowHeightKind.Messages -> messagesHeightPx
            FollowHeightKind.Composer -> composerHeightPx
        }
        when (kind) {
            FollowHeightKind.Messages -> messagesHeightPx = newHeightPx
            FollowHeightKind.Composer -> composerHeightPx = newHeightPx
        }
        if (previous == null || previous == 0 || previous == newHeightPx) {
            return FollowScrollDirective.None
        }
        if (isAtBottom(valuePx)) return FollowScrollDirective.PinToBottom
        return FollowScrollDirective.ShiftBy(followShiftForGrowth(previous, newHeightPx, valuePx))
    }

    /** 点了胶囊 / 程序性回底：先把提示收掉，避免回滚动画期间还挂着。 */
    fun onJumpToBottom() {
        unseenBelow = false
    }
}
