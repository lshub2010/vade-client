package vade.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * wasm 历史桥的状态机（[decide]）与 `go(-k)` 夹取（[clampSteps]）。
 *
 * 为什么这些判断要在 commonMain 里做纯函数、在这里逐条钉住：它们唯一的真实运行环境
 * 是浏览器（`window.history` + popstate + Chromium 的 back-trap 启发式），wasm actual
 * 在 desktopTest 里跑不起来。把决策抽出来之后，「后退之后不能 push」「同一帧两次
 * popstate 不能关两层」「抽屉不关层不能把后退吞光」这几条都能在没有浏览器的地方变红。
 *
 * 表里的 14 行就是终审那张状态机（`previous × landed × expected × desired`）。
 * 行名即场景，改一行就有一个具名断言失败 —— 不是「跑一遍没崩」。
 */
class HistoryMirrorDecisionTest {

    /**
     * 一行 = 一次判定。
     *
     * @param previous 上一轮派发时的栈深（没派发过 = 历史深度）
     * @param landed 这次 popstate 落到的历史深度（-1 = state 不是我们的）
     * @param expected 在途 `go(-k)` 的期望落点；null = 没有自发回程
     * @param desired **新的** `dispatcher.depth`
     */
    private data class Row(
        val name: String,
        val previous: Int,
        val landed: Int,
        val expected: Int?,
        val desired: Int,
        val action: BackAction,
    )

    private val rows = listOf(
        // --- 基座 / 孤立子树 ---------------------------------------------------
        Row("基座·原地（0 层 0 条）", previous = 0, landed = 0, expected = null, desired = 0, action = BackAction.IGNORE),
        Row("模型不可信（两处 state 对不上）", previous = 0, landed = -1, expected = null, desired = 0, action = BackAction.RESYNC),
        Row("原地（popstate 但位置没动）", previous = 3, landed = 3, expected = null, desired = 3, action = BackAction.IGNORE),
        Row("前进", previous = 3, landed = 4, expected = null, desired = 4, action = BackAction.IGNORE),
        Row("前进但栈没跟（前进不改栈深）", previous = 3, landed = 4, expected = null, desired = 3, action = BackAction.IGNORE),

        // --- 自发 go(-k) 的回程 -------------------------------------------------
        Row("自发回程命中期望", previous = 3, landed = 2, expected = 2, desired = 2, action = BackAction.ADOPT),
        Row("自发回程命中·栈又长回来了", previous = 3, landed = 2, expected = 2, desired = 3, action = BackAction.ADOPT),
        Row("自发回程踏空（别人动过历史）", previous = 3, landed = 2, expected = 5, desired = 2, action = BackAction.RESYNC),

        // --- 用户后退 ----------------------------------------------------------
        Row("后退·栈已缩到落点", previous = 3, landed = 2, expected = null, desired = 2, action = BackAction.IGNORE),
        Row("后退·栈缩过头（同帧两次 pop 的后一次）", previous = 3, landed = 2, expected = null, desired = 1, action = BackAction.IGNORE),
        Row("后退·栈一条没降（抽屉不关层）", previous = 3, landed = 2, expected = null, desired = 3, action = BackAction.HOLD),
        Row("后退·栈反而长高（层又开了一层）", previous = 3, landed = 2, expected = null, desired = 4, action = BackAction.HOLD),
        Row("后退·在缩但没到落点 → 再派发", previous = 3, landed = 1, expected = null, desired = 2, action = BackAction.DISPATCH),
        Row("后退·落到终点（不再派发）", previous = 3, landed = 1, expected = null, desired = 1, action = BackAction.IGNORE),
    )

    @Test
    fun everyRowOfTheStateMachineHolds() {
        assertEquals(14, rows.size, "终审表是 14 行；加行请连注释一起更新")
        for (row in rows) {
            assertEquals(
                row.action,
                decide(row.previous, row.landed, row.expected, row.desired),
                "「${row.name}」previous=${row.previous} landed=${row.landed} " +
                    "expected=${row.expected} desired=${row.desired}",
            )
        }
    }

    // --- 不变量（不是抽样：把整个区间铺开） -------------------------------------

    /**
     * P1-1 的核心：**后退之后、栈没降，就不许 push**。
     *
     * 落点比 `previous` 浅、而新栈深还没降到落点（`desired >= previous`）时，答案只能是
     * [BackAction.HOLD] —— 既不能 DISPATCH（派发解决不了「层不关层」），也不能 IGNORE
     * （IGNORE 会落到 reconcile 的补条目分支，那就是后退后 push，back-trap 的成因）。
     */
    @Test
    fun aBackThatDidNotShrinkTheStackCanOnlyHold() {
        for (previous in 1..6) {
            for (landed in 0 until previous) {
                for (desired in previous..(previous + 3)) {
                    assertEquals(
                        BackAction.HOLD,
                        decide(previous, landed, null, desired),
                        "previous=$previous landed=$landed desired=$desired 必须 HOLD（不 push）",
                    )
                }
            }
        }
    }

    /**
     * P1-3 的核心：一次落地最多派发一次。
     *
     * 栈深已经降到落点或更浅 = 这次后退已经被消化；再派发就是「按两次后退关两层」，
     * 在层的 `onBack` 不关层时甚至会把整摞层一次关光。
     */
    @Test
    fun aLandingBelowTheStackNeverDispatchesTwice() {
        for (previous in 1..6) {
            for (landed in 0 until previous) {
                for (desired in 0..landed) {
                    assertEquals(
                        BackAction.IGNORE,
                        decide(previous, landed, null, desired),
                        "previous=$previous landed=$landed desired=$desired 不能再派发",
                    )
                }
            }
        }
    }

    /** 没后退（原地 / 前进）就绝不派发：前进不该关层，否则「前进」键会变成「返回」键。 */
    @Test
    fun forwardAndSamePositionNeverDispatch() {
        for (previous in 0..6) {
            for (desired in 0..6) {
                assertEquals(BackAction.IGNORE, decide(previous, previous, null, desired))
                assertEquals(BackAction.IGNORE, decide(previous, previous + 1, null, desired))
            }
        }
    }

    // --- 抽屉不关层序列 --------------------------------------------------------

    /**
     * 抽屉那条真实路径（`SessionsDrawerHost` 的 `BackLayer`）：
     *
     * ```
     * BackLayer(enabled = selected != null, onBack = { if (drawerOpen) closeDrawer() else state.back() })
     * ```
     *
     * 抽屉开着时 `onBack` **只收抽屉、层还在** —— 这就是「抽屉不关层」：历史退了一条、
     * 栈深一点没动。旧实现在这里补一条 `pushState`，正好落进 Chromium 的 back-trap，
     * 后退按钮之后整条跳过它：按两次只关一层，或者直接离开页面。
     *
     * 序列（1 层，历史 1 条）：
     *  1. 后退 → 落点 0，页面的 onBack 收抽屉 → 栈仍是 1 → **HOLD**（不 push，抬条目 + 记账）；
     *  2. 用户手势之后补条目 → 历史仍是 1 条、栈 1 层，镜像恢复；
     *  3. 再后退 → 落点 0，这一回 `state.back()` 真的出栈 → 栈 0 → **IGNORE**（已消化，不再补派发）。
     */
    @Test
    fun drawerThatOnlyClosesItselfHoldsInsteadOfPushing() {
        // 1 · 后退落地：历史 1 → 0，栈还是 1。
        assertEquals(
            BackAction.HOLD,
            decide(previous = 1, landed = 0, expected = null, desired = 1),
            "抽屉只收自己：栈没降 → 不许 push（back-trap），只能 HOLD",
        )

        // 2 · 手势之后补条目成功，镜像回到「历史 1 条 / 栈 1 层」，下一次后退照常派发。
        //     （补条目本身在 wasm actual 的 onUserGesture 里，这里钉的是随后那次判定。）

        // 3 · 第二次后退：这一次层真的出栈，栈缩到落点 0 —— 已消化，不能再派发。
        assertEquals(
            BackAction.IGNORE,
            decide(previous = 1, landed = 0, expected = null, desired = 0),
            "栈已经缩到落点：这一跳已经消化，再派发会把下一层也关掉",
        )
    }

    /**
     * 同一帧两次 popstate（P1-3）：`handlePopState` 只记落点，帧后用新的栈深判。
     *
     * 3 层、两次快速后退落在 1（一个 popstate 走两条 = 浏览器的合并行为）；
     * 第一次派发之后栈降到 2 —— 还在往落点缩，所以**再**派发一次；降到 1 就停。
     */
    @Test
    fun twoPopsInOneFrameDispatchOncePerClosedLayer() {
        assertEquals(
            BackAction.DISPATCH,
            decide(previous = 3, landed = 1, expected = null, desired = 2),
            "栈在缩但还没到落点：同帧的第二次后退不能被吞",
        )
        assertEquals(
            BackAction.IGNORE,
            decide(previous = 3, landed = 1, expected = null, desired = 1),
            "降到落点就停手",
        )
    }

    // --- go(-k) 的 k 夹取 ------------------------------------------------------

    /**
     * `k = min(k, 身后条目数的下界)`。上界**不是** `history.length - 1`：
     *
     * `history.length` 数的是整个会话的条目 —— 含**前进**条目，也含进入本页之前的条目，
     * 所以 `length - 1` 只是「身后有几条」的一个上界（评审 P2-1）。拿它当条目数会算出比
     * 实际更大的步数：`go` 落空（白等一个看门狗周期），或者更糟 —— 退到本页之前的那一页，
     * 直接离开应用。所以调用点先把「账目说的 historyDepth」和「length - 1」取小
     * （[HistoryMirror] 的 `entriesBehind`），再交给这里。
     *
     * 夹到 0 时调用点直接不发 go（不是「防越界」：越界的 go 静默无效、不抛错）。
     */
    @Test
    fun goStepsAreClampedToTheEntriesActuallyBehind() {
        assertEquals(3, clampSteps(3, 4), "身后确实有 4 条的把握：3 步原样")
        assertEquals(4, clampSteps(9, 4), "身后只有 4 条的把握：夹到 4")
        assertEquals(0, clampSteps(1, 0), "身后一条也没有（站在基座）→ 不发 go")
        assertEquals(0, clampSteps(5, -1), "负数按 0 兜底（读不到 length 的情形）→ 不发 go")
        assertEquals(0, clampSteps(-2, 4), "方向只允许后退")
        assertEquals(0, clampSteps(0, 4))
    }
}
