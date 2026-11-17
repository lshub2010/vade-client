package vade.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * wasm 历史桥的**记账序列**（[HistoryMirror]）—— 纯逻辑，不依赖 DOM。
 *
 * `HistoryMirrorDecisionTest` 钉的是「单次判定」（[decide] / [clampSteps]）；这里钉的是
 * **连续动作**：开层压条目 → 用户后退 → HOLD 记账 → 手势补条目 → 关层收条目 → 看门狗超时。
 * 那是评审里两条 P1/P2 真正出事的地方，而旧实现把它们全埋在 `BackDispatch.wasmJs.kt` 里，
 * `window` 在 JVM 上不存在 —— 只能靠读代码「确认」，于是：
 *
 *  - **P1-1**：看门狗一超时就 `degrade()`，一次没落地的 `go` 把整座桥永久关掉；
 *  - **P2-1**：HOLD 时 `replaceState({vade: now})` + 手势后 `pushState({vade: now})`，
 *    历史里出现**两条 vade 相同**的条目，后退落到同深度那条上连 popstate 都不投递。
 *
 * [FakeHistory] 就是一个真的会话历史：位置、条目、`state`（基座 `null`）、`go` 会/不会落地。
 * 断言全落在「历史里到底有哪些条目」「发了几次 go、各几步」上 —— 不是「函数被调用过」。
 */
class HistoryMirrorAccountingTest {

    // --- 假 history + 手动帧 ---------------------------------------------------

    /**
     * 一段会话历史。
     *
     * @param before 进入本页之前会话里已有的条目数（`history.length` 因此**总是**大于
     *   我们自己的条目数 —— 这正是 `length - 1` 不能当「身后条目数」用的原因）。
     */
    private class FakeHistory(private val before: Int = 0) : HistoryPort {

        /** `null` = 基座条目（`history.state` 是 null，读不出 vade）。 */
        private val entries = MutableList<Int?>(before + 1) { null }

        /** 当前条目下标（绝对下标，含 [before] 条外部条目）。 */
        var position = before

        /** false = `go` 静默无效（越界 / 被嵌入策略吞掉）：位置不动，也没有 popstate。 */
        var goLands = true

        /** 每次 `push` 的 vade（按时间顺序，含被丢弃的前进条目后的重压）。 */
        val pushes = mutableListOf<Int>()

        /** 每次 `go` 的步数。 */
        val gos = mutableListOf<Int>()

        var throwOnPush = false
        var throwOnGo = false

        override val length: Int get() = entries.size

        override fun currentVade(): Int = entries[position] ?: -1

        override fun push(vade: Int) {
            if (throwOnPush) throw IllegalStateException("SecurityError: opaque origin")
            pushes += vade
            // pushState 丢弃前进条目
            while (entries.size > position + 1) entries.removeAt(entries.lastIndex)
            entries += vade
            position = entries.lastIndex
        }

        override fun goBack(steps: Int) {
            if (throwOnGo) throw IllegalStateException("history.go")
            gos += steps
            if (goLands) position = (position - steps).coerceAtLeast(0)
        }

        /** 浏览器自己把位置挪了（用户按下后退），不经过 `go`。 */
        fun landAt(index: Int) {
            position = index.coerceIn(0, entries.lastIndex)
        }

        /** 整段历史里的 vade（基座是 null）。同 vade 出现两次 = P2-1 的双条目。 */
        val vades: List<Int?> get() = entries.toList()

        /** 造一段「刷新残局」：state 写着这些 vade，会话里也只有这些条目。 */
        fun seed(states: List<Int>) {
            entries.clear()
            entries += null
            entries += states
            position = entries.lastIndex
        }
    }

    /**
     * 手动帧：`requestAnimationFrame ×2` 的替身。
     *
     * 帧回调**必须**延迟 —— 同一帧的多次 popstate 要并成一次判定，看门狗要等 popstate
     * 投递完。立刻执行等于把这两个 bug 掩盖掉。
     */
    private class ManualFrames {
        private val queue = mutableListOf<() -> Unit>()

        val pending: Int get() = queue.size

        fun schedule(block: () -> Unit) {
            queue += block
        }

        /** 跑完所有排队的回调（帧回调里再排的也跑）。 */
        fun runAll(limit: Int = 200) {
            var n = 0
            while (queue.isNotEmpty()) {
                val next = queue.removeAt(0)
                next()
                check(++n < limit) { "帧回调没有收敛：$limit 次之后仍在排队" }
            }
        }
    }

    /** 一台「浏览器 + 返回栈」：`onBack` 默认关掉一层（= 真实层的 `onBack` 改 state 出栈）。 */
    private class Harness(before: Int = 0) {
        val history = FakeHistory(before)
        val frames = ManualFrames()
        val warnings = mutableListOf<String>()
        val mirror = HistoryMirror(history, frames::schedule) { message -> warnings += message }

        var depth = 0
        var dispatches = 0

        init {
            mirror.depthNow = { depth }
            mirror.onBack = {
                dispatches++
                // 层出栈：改栈深，**下一帧**才推 sync —— Compose 的
                // `LaunchedEffect(depth, resync)` 就是在下一帧跑的，不是回调里同步跑。
                depth = (depth - 1).coerceAtLeast(0)
                frames.schedule { mirror.sync(depth) }
            }
        }

        /** 栈深变了（开层 / UI 关层）→ 推给桥。 */
        fun sync(newDepth: Int) {
            depth = newDepth
            mirror.sync(newDepth)
        }

        /** 浏览器投递一次 popstate（位置已经动过了），再把帧跑完。 */
        fun deliverPopState() {
            mirror.onPopState(history.currentVade(), history.currentVade())
            frames.runAll()
        }

        /** 用户按后退：位置退一条，然后投递 popstate。 */
        fun userBack() {
            history.landAt(history.position - 1)
            deliverPopState()
        }

        /** 模拟「层的 `onBack` 改了 state → 重组 → `LaunchedEffect` 推 sync」。 */
        fun settle() = sync(depth)
    }

    /** P2-1 的守卫：历史里不许出现两条 vade 相同的条目。 */
    private fun assertNoDuplicateVades(h: Harness) {
        val vades = h.history.vades.filterNotNull()
        assertEquals(
            vades.size,
            vades.toSet().size,
            "历史里出现了同 vade 的双条目：$vades（整段会话 ${h.history.vades}）",
        )
    }

    // --- 开层 / 关层 ----------------------------------------------------------

    /**
     * 开 3 层 → 历史里恰好 3 条自有条目（vade 1,2,3，一次压好）；
     * UI 关干净 → **一次** `go(-3)`；回程只对账，不关层。
     */
    @Test
    fun threeLayersBecomeThreeEntriesAndOneGoTakesThemBack() {
        val h = Harness()
        h.sync(3)
        h.frames.runAll()

        assertEquals(listOf(1, 2, 3), h.history.pushes, "开 3 层 = 一次压好 vade 1..3（不是先压 1 条）")
        assertEquals(3, h.mirror.currentDepth)
        assertEquals(emptyList(), h.history.gos)

        // 点遮罩 / 按 Esc 关掉全部 3 层
        h.sync(0)
        assertEquals(listOf(3), h.history.gos, "关 3 层 = 一次 go(-3)，不是三次 go(-1)")
        assertEquals(0, h.mirror.expectedLanding, "期望落点是基座")

        // 浏览器落地：多步 go 只投递一个 popstate
        h.deliverPopState()

        assertEquals(0, h.dispatches, "自己收条目不是用户后退 —— 不许派发（否则会顺手关掉下一层）")
        assertEquals(0, h.mirror.currentDepth)
        assertNull(h.mirror.expectedLanding, "回程落地后不该还有在途的 go")
        assertFalse(h.mirror.isDegraded)
        assertNoDuplicateVades(h)
    }

    /**
     * 抽屉那条真实路径（`SessionsDrawerHost`）：抽屉开着时 `onBack` **只收抽屉、层还在**。
     *
     * 历史退了一条、栈深一点没动 —— 旧实现在这里 `replaceState(now)` 抬当前条目，
     * 手势后再 `pushState(now)`，历史里于是有两条 vade 相同的条目（P2-1）。现在的账：
     *
     *  1. 后退落基座 + 层不关 → HOLD：**不动历史**（不 push、不 replace），只记欠 1 条；
     *  2. 用户手势 → 补条目走 `pushUpTo(1..desired)`：压一条 vade=1；
     *  3. 再后退 → 这次 `state.back()` 真的出栈 → 栈 0，不再补派发、不再补条目。
     */
    @Test
    fun holdOnlyRecordsDebtAndTheGestureRefillsBySteps() {
        val h = Harness()
        var drawerOpen = true
        h.mirror.onBack = {
            h.dispatches++
            // 抽屉开着 → 只收抽屉（层还在）；收着 → 真的出栈
            if (drawerOpen) drawerOpen = false else h.depth = 0
        }

        h.sync(1)
        assertEquals(listOf(1), h.history.pushes)

        // 1 · 用户后退：落到基座（vade 读不出 → 0），层的 onBack 只收抽屉 → 栈仍是 1
        h.userBack()

        assertEquals(1, h.dispatches, "后退先把一次机会给栈顶")
        assertEquals(emptyList(), h.history.gos, "HOLD 不动历史：不发 go")
        assertEquals(listOf(1), h.history.pushes, "HOLD 不动历史：不 push（更不 replaceState + push 同深度）")
        assertEquals(0, h.mirror.currentDepth, "物理位置就在基座 —— 账目必须跟着浏览器，不许抬条目")
        assertTrue(
            h.warnings.any { it.contains("back-trap") },
            "HOLD 必须留一条可读的告警，实际：${h.warnings}",
        )
        assertNoDuplicateVades(h)

        // 2 · 用户手势：back-trap 窗口过去，欠的条目按台阶补齐
        h.mirror.onUserGesture()
        h.frames.runAll()

        assertEquals(listOf(1, 1), h.history.pushes, "补条目走 pushUpTo(1..desired)：再压一条 vade=1")
        assertEquals(1, h.mirror.currentDepth, "镜像恢复：历史 1 条 / 栈 1 层")
        assertNoDuplicateVades(h)

        // 3 · 再后退：这一回层真的出栈
        h.userBack()
        h.settle()

        assertEquals(2, h.dispatches)
        assertEquals(0, h.mirror.currentDepth, "落在基座，栈也空了")
        assertEquals(listOf(1, 1), h.history.pushes, "栈已缩到落点：不许再补条目")
        assertEquals(emptyList(), h.history.gos, "已经没有条目可收")
        assertNoDuplicateVades(h)
    }

    /**
     * HOLD 落在**自有条目**上（`landed > 0`）—— 这是旧实现真正造出双条目的那一支：
     * `replaceState({vade: 3})` 把当前位置那条抬成 3，手势后再 `pushState({vade: 3})`，
     * 历史里就有两条 vade=3 的条目（后退一次落到同深度那条上，连 popstate 都不投递）。
     *
     * 现在物理位置原地不动（3 层退到 vade=2 就停在 2），手势后只按台阶补 3：
     * 会话里仍是 `[基座, 1, 2, 3]`，没有兄弟条目。
     */
    @Test
    fun holdOnANonBaseEntryNeverCreatesASiblingWithTheSameVade() {
        val h = Harness()
        h.mirror.onBack = { h.dispatches++ } // 抽屉式：只收抽屉，栈深一点都不降

        h.sync(3)
        assertEquals(listOf(1, 2, 3), h.history.pushes)

        h.userBack() // 3 → 2，层不关

        assertEquals(1, h.dispatches)
        assertEquals(2, h.mirror.currentDepth, "账目跟着浏览器落在 vade=2")
        assertEquals(listOf(1, 2, 3), h.history.pushes, "HOLD 期间不 push（也不 replaceState 抬条目）")
        assertEquals(listOf(null, 1, 2, 3), h.history.vades, "历史原封不动")
        assertNoDuplicateVades(h)

        h.mirror.onUserGesture()
        h.frames.runAll()

        assertEquals(listOf(1, 2, 3, 3), h.history.pushes, "补条目：只补欠的那一条 vade=3")
        assertEquals(3, h.mirror.currentDepth)
        assertEquals(listOf(null, 1, 2, 3), h.history.vades, "会话里没有两条 vade=3")
        assertNoDuplicateVades(h)
    }

    // --- P1-1 · 看门狗 ---------------------------------------------------------

    /**
     * `go` 没落地（越界 / 被嵌入策略吞掉）**不是**降级信号。
     *
     * 旧实现：看门狗超时 → `degrade()` → 之后开层不压条目、关层不收条目，浏览器后退
     * 静默退回「离开整个应用」。现在：清在途状态 → 按 `history.state.vade` 重建账目 →
     * 至多重发一次 go；桥仍然活着，用户的下一次后退照样派发。
     */
    @Test
    fun aGoThatNeverLandsIsRebuiltAndRetriedOnceInsteadOfDegrading() {
        val h = Harness()
        h.sync(2)
        assertEquals(listOf(1, 2), h.history.pushes, "基线：历史里两条自有条目")

        h.history.goLands = false // 这一次 go 注定落不了地
        h.sync(0) // 两层全关 → go(-2)，永远等不到 popstate
        assertEquals(listOf(2), h.history.gos)
        assertEquals(1, h.frames.pending, "发 go 时装了看门狗")

        h.frames.runAll() // 两帧过去：看门狗超时

        assertFalse(h.mirror.isDegraded, "go 没落地 ≠ 历史不可用 —— 不许永久降级（P1-1）")
        assertEquals(2, h.mirror.currentDepth, "账目按浏览器此刻说的重建（位置没动，还在 vade=2）")
        assertEquals(
            listOf(2, 2),
            h.history.gos,
            "重建之后**重发一次**同样的 go（不是一次就放弃、也不是无限重发）",
        )
        assertEquals(0, h.frames.pending, "第二次超时后不再排队：不自己追尾")
        assertTrue(h.warnings.any { it.contains("没有落地") }, "要留告警，实际：${h.warnings}")

        // 桥还活着（1）：历史不收缩了，但**加长**照旧 —— 开第 3 层压 vade=3
        h.history.goLands = true
        h.sync(3)
        assertEquals(listOf(1, 2, 3), h.history.pushes, "历史收缩失败不影响继续镜像")

        // 桥还活着（2）：用户后退照样派发 —— 落到 vade=1 就是关两层，栈跟着缩
        h.history.landAt(1)
        h.deliverPopState()
        assertEquals(2, h.dispatches, "「后退 = 关层」必须还在（旧实现在这里把桥永久关掉了）")
        assertEquals(1, h.depth)
        assertEquals(listOf(2, 2), h.history.gos, "派发只动栈，不动历史")
        assertNoDuplicateVades(h)
    }

    /**
     * 只有历史 API **真抛异常**（不透明来源的 SecurityError、被嵌入策略拦掉）才降级：
     * 降级后不再尝试任何历史操作 —— 但整座桥不会因为一次 `go` 静默无效而消失。
     */
    @Test
    fun onlyAThrowingHistoryApiDegradesTheBridge() {
        val pushFailure = Harness()
        pushFailure.history.throwOnPush = true
        pushFailure.sync(1)
        assertTrue(pushFailure.mirror.isDegraded, "pushState 真抛 ⇒ 降级")
        assertEquals(0, pushFailure.dispatches, "降级不影响已经派发过的事实：这里一次都没派发")
        pushFailure.history.throwOnPush = false
        pushFailure.sync(2)
        assertEquals(emptyList(), pushFailure.history.pushes, "降级之后不再碰历史")

        val goFailure = Harness()
        goFailure.sync(1)
        goFailure.history.throwOnGo = true
        goFailure.sync(0)
        assertTrue(goFailure.mirror.isDegraded, "go 真抛 ⇒ 降级")
    }

    // --- P2-5 · 夹取后的期望落点 ------------------------------------------------

    /**
     * 刷新残局 + 夹取：state 写着 `vade=3`，会话里却只剩 2 条（基座 + 它自己）。
     *
     * `history.length - 1` 在这里是 1 —— 只能退 1 条，但**期望落点是 3-1=2，不是目标 0**
     * （P2-5：把期望落点记成 target 会让 popstate 回来被判成「踏空」，白白 RESYNC 一次）。
     * 剩下那截不回头发 go，而是等落地后按浏览器说的位置继续收敛。
     */
    @Test
    fun aClampedGoAimsAtTheLandingItCanActuallyReach() {
        val history = FakeHistory().apply { seed(listOf(3)) }
        val frames = ManualFrames()
        val warnings = mutableListOf<String>()
        val mirror = HistoryMirror(history, frames::schedule) { message -> warnings += message }
        var dispatches = 0
        mirror.depthNow = { 0 }
        mirror.onBack = { dispatches++ }

        mirror.start()

        assertEquals(listOf(1), history.gos, "只退会话里真有的那 1 条（不按 vade=3 硬退 3 条）")
        assertEquals(2, mirror.expectedLanding, "期望落点 = 退 1 条之后（2），不是目标 0")

        // 浏览器落地：站在基座（vade 读不出 → 0）
        mirror.onPopState(history.currentVade(), history.currentVade())
        frames.runAll()

        assertEquals(0, dispatches, "自己清残留不是用户后退")
        assertEquals(0, mirror.currentDepth, "重建到浏览器说的位置")
        assertEquals(listOf(1), history.gos, "只有清理那一次 go：落地之后不再回头发")
        assertEquals(listOf(null, 3), history.vades, "清残留只后退：不压条目，会话里仍是基座 + state 那条")
        assertTrue(warnings.isEmpty(), "一路都走通了就不该告警，实际：$warnings")
    }

    // --- P1-3 · 同一帧两次 popstate（记账层的回归） -------------------------------

    /**
     * 3 层，一次后退落到 1（一个 popstate 走两条 = 浏览器的合并行为）：帧决策按**新的**
     * 栈深反复问 [decide]，关够两条就停 —— 不是只关一条，也不是把三层一次关光。
     */
    @Test
    fun twoPopsInOneFrameCloseExactlyTwoLayers() {
        val h = Harness()
        h.sync(3)

        h.history.landAt(h.history.position - 2)
        h.deliverPopState()

        assertEquals(2, h.dispatches, "落在 vade=1：应该关掉两层")
        assertEquals(1, h.depth)
        assertEquals(listOf(1, 2, 3), h.history.pushes, "只派发不改历史：没有多压条目")
        assertNoDuplicateVades(h)
    }
}
