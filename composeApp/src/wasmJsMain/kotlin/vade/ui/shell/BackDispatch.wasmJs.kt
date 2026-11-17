@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package vade.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlin.JsFun
import kotlin.js.JsAny
import kotlinx.browser.window
import org.w3c.dom.PopStateEvent
import org.w3c.dom.events.Event

/**
 * Wasm actual（c7-wasm + i2-bridge）：浏览器「后退」= 系统返回。
 *
 * 浏览器里没有系统返回键，此前这端是空实现 —— 唯一的返回入口是 Esc
 * （`AppShell.handleShellKeys` 里「Esc 优先交给返回栈」）。于是**开了浮层按浏览器后退
 * = 直接离开整个应用**（桌面/移动端都是「先关浮层」），这是三端里最刺眼的一条不一致。
 *
 * ## 做法：把返回栈**镜像**成 N 条历史条目，开层时一次压好
 *
 * 栈深 d 对应历史里 d 条自有条目，每条带 `{vade: d}` 的 state。于是：
 *
 *  - 开 3 层 → 压 3 条（不是「先压 1 条、用户后退后再补 1 条」）；
 *  - 用户按后退 → 落到 `vade = d-1` 那条（一条一条往下退，popstate 每次都给），
 *    我们派发一次返回栈；
 *  - UI 主动关层（点遮罩 / 按 Esc / 关抽屉）→ 一次 `history.go(-k)` 把 k 条收回，
 *    历史位置回到与栈深一致的那条，用户的下一次后退仍然是「关下一层」。
 *
 * ### 为什么不能「后退后再 pushState 补一条」
 *
 * 那是更早的写法，被 Chromium 的 history intervention 判成 back-trap：同一文档
 * 后退之后又 `pushState` 出来的条目会被打上 `should_skip_on_back_forward_ui_`，
 * 后退按钮**整条跳过**它、连 popstate 都不投递 —— 表现是「按两次后退只关一层，
 * 或者直接离开页面」。开层时一次压好 N 条不触发这条启发式：压的时候没有发生过后退。
 *
 * 所以「后退之后还想让历史变长」只有两条合法出路，都由 [hold] / [onUserGesture] 走：
 *  1. `replaceState` 抬**当前**条目（不新建条目，不进启发式）；
 *  2. 真的要补物理条目时，等下一次**用户手势**（`pointerdown` / `keydown`）之后 ——
 *     那时 back-trap 的窗口已经过去。
 *
 * ### 一次 `go(-k)` 只投递一个 popstate，我们不会错账
 *
 * 多步 `history.go(-k)` 只派发**一次** popstate（落在终点条目上），所以「按深度记账的自发
 * pop 计数器」会永久多记 k-1 次，把用户之后的真实后退吞掉。现在不数次数，而是**认 state**：
 * `go(-k)` 之前记下期望落点 [BrowserBack.pendingClose] = 关完之后的深度，popstate 里读回
 * `state.vade`：命中 → 自己收的条目，只对账；没命中 → 有别人动过历史，按浏览器说的重建。
 *
 * ### 看门狗：`go(-k)` 可能永远不落地（P0-1）
 *
 * `history.go` 不抛错，也可能**什么都不做**（越界、被嵌入策略吞掉、页面正在卸载）。
 * 那时 `pendingClose` 会一直挂着，[reconcile] 里「在途就别再动历史」的短路会让桥永久
 * 卡死 —— 之后开层不再压条目、关层不再收条目，后退行为静默退化。所以每次发 `go` 都装
 * 一个 rAF×2 的看门狗：两帧之后 `pendingClose` 还在 → 清掉 + 一次性告警 + 永久降级
 * （历史不可信，回到「Esc 才是返回」的诚实状态）。
 *
 * 顺带把 `k` 夹在 `history.length - 1` 以内（[clampSteps]）：注定落不了地的 `go` 不发。
 *
 * ### 落地校验（P0-1）
 *
 * popstate 的 `event.state` 与 `history.state` 是两份东西：前者是「到我这儿的时候
 * 入口写的 state」，后者是「浏览器此刻认为的当前条目 state」。读到两者不一致（或者
 * 读不出 `vade`）说明模型已经不可信 —— 不拿它派发，改走 [BackAction.RESYNC]：
 * 把 [BrowserBack.historyDepth] 对齐到浏览器说的值，再收敛。
 *
 * ### 同一帧的两次 popstate（P1-3）
 *
 * 两次快速后退可能在同一个 rAF 里先后到达。此时**不在 popstate 里派发**，只记
 * `pendingDispatch = landed`；隔两帧后用**新的** `dispatcher.depth` 问 [decide]：
 * 栈已经缩到落点 → 不再派发；还在缩但没到 → 再派发一次；一条没降 → [BackAction.HOLD]。
 * 这样「一帧两次 pop」不会变成「关两层」，也不会只关一层。
 *
 * ### 刷新后的残局
 *
 * `pushState` 的 state 是浏览器持久化的，内存里的栈却随刷新清零。刷新后
 * `history.state.vade = 3` 而 App 里 0 层 —— 用户按一次后退，我们会把它当成「关层」
 * 白白吃掉一次（要按 3 次才真正离开页面）。所以 [BrowserBack.install] 启动时就把
 * `desired` 同步成 0，再主动 `go(-stale)` 把多出来的条目退回去（k 同样夹住），
 * 这次回程用 `pendingClose = 0` 认领、不派发，并同样受看门狗与落地校验保护。
 *
 * 历史 API 不可用时（`file://` 等不透明来源会抛 SecurityError、被嵌入策略拦掉同理）
 * 整段 [runCatching] 兜住并**一次性** `console.warn`：降级成空实现（Esc 照旧、后退
 * 照旧离开页面），绝不让异常冒进组合把首帧打白。
 */

/**
 * 读 state 里的 `vade`：只认 `{vade: Number}`，其它（null / 别人写的 state / 别的形状）
 * 返回 -1。
 *
 * 为什么在 JS 侧判型、不写 `state as VadeState`：外部接口的强转在 wasm 里不做运行时校验，
 * 真读到别人写的 `{}` 会在 `.vade` 属性访问处抛 TypeError —— 那是 popstate 监听里，
 * 会变成未捕获异常。深度从 0 起算，所以 -1 这个哨兵值不会和真值撞。
 */
@JsFun("(s) => (s !== null && typeof s === 'object' && typeof s.vade === 'number') ? s.vade : -1")
private external fun jsReadVade(state: JsAny?): Int

/** 造 state：`{vade: depth}`。 */
@JsFun("(d) => ({vade: d})")
private external fun jsVadeState(depth: Int): JsAny

/**
 * 会话历史的总条数（含当前条目）。
 *
 * 只用来夹 `go(-k)` 的 k：`length = 1` 表示没有更早的条目可退，任何 `go(-k)` 都会
 * 静默失败（看门狗随后会喊一次）。读不到时按 1 处理 —— 宁可当「退不了」，也不要发
 * 一个落不了地的 go 把桥卡住。
 */
@JsFun("() => window.history.length")
private external fun jsHistoryLength(): Int

/** `kotlinx.browser` 只绑了 window/document/storage，没有 console。 */
@JsFun("(m) => { console.warn(m); }")
private external fun jsWarn(message: String)

/** 一次性告警：同一条原因只喊一次，避免 popstate 每次都刷控制台。 */
private val warnedKeys = HashSet<String>()

private fun warnOnce(key: String, message: String) {
    if (!warnedKeys.add(key)) return
    runCatching { jsWarn(message) }
}

/**
 * 浏览器历史与返回栈之间的桥。单例：整个页面只有一份 popstate 监听（多份监听 =
 * 一次后退被派发多次），也只有一份深度账目。
 *
 * 所有「该不该派发 / 该不该补条目」的判断都问 commonMain 的 [decide]（纯函数，
 * desktopTest 逐条钉住）；这里只负责读事件、记账、执行 `pushState` / `go` /
 * `replaceState` 这三样有副作用的事。
 */
private object BrowserBack {

    /** popstate / 手势监听只挂一次；页面生命周期内不再摘（[onBack] 置空即失效）。 */
    private var installed = false

    /** 派发目标：`BackHost` 传进来的 `dispatcher.dispatch()`。 */
    var onBack: (() -> Unit)? = null

    /**
     * 栈深的**实时**读取器（`dispatcher.depth`）。
     *
     * 不能直接用 [sync] 记下来的 [desired]：帧决策要的是「层的 `onBack` 跑完之后」
     * 的深度，而 [sync] 只在重组时被 `LaunchedEffect` 推进 —— 两者之间可能差一帧。
     */
    var depthNow: (() -> Int)? = null

    /** 应用想要的深度（`dispatcher.depth` 的最新值，由 [sync] 推进）。 */
    private var desired = 0

    /** 历史位置对应的深度 = 当前位置条目的 `vade`（基座条目 0）。 */
    private var historyDepth = 0

    /** UI 主动关层发起、尚未落地的 `go(-k)` 期望落点；非空时不再动历史。 */
    private var pendingClose: Int? = null

    /** 看门狗的令牌：每发一次 go / 每落地一次自增，过期的回调直接丢。 */
    private var watchdog = 0

    /** popstate 已到、还没做帧决策的落点（`-1` = 两处 state 对不上，模型不可信）。只记不算。 */
    private var pendingDispatch: Int? = null

    /** 这一批 pop 之前我们记的历史深度（decide 的 `previous`）。 */
    private var pendingPrevious = 0

    /** 这一批 pop 里那次自发 go 的期望（decide 的 `expected`）。 */
    private var pendingExpected: Int? = null

    /** 帧决策只排一次：同一帧多次 popstate 合并成一次判定。 */
    private var decisionScheduled = false

    /** 这批后退是否已经派发过一次（首次派发不吃 decide，见 [decide] 的调用契约）。 */
    private var dispatchedThisBack = false

    /** 上一次后退还没被用户手势解除：这期间绝不 push（back-trap）。 */
    private var afterBack = false

    /** 欠着的物理条目数（[hold] 记的账，等下一次用户手势补）。 */
    private var owedEntries = 0

    /** 历史 API 已判定不可用：永久降级，不再尝试。 */
    private var unavailable = false

    fun install() {
        if (installed) return
        installed = true
        // 启动即同步：App 里 0 层（刷新后的清理见 discardStaleEntries）。
        desired = 0
        window.addEventListener("popstate", { event: Event -> handlePopState(event) })
        // 用户手势 = back-trap 窗口结束的信号，补条目只能在这之后（见文件头）。
        window.addEventListener("pointerdown", { _: Event -> onUserGesture() })
        window.addEventListener("keydown", { _: Event -> onUserGesture() })
        discardStaleEntries()
    }

    /**
     * 让「历史深度」追上「栈深」：多了就 `go(-k)` 收回（只投递一个 popstate，用
     * [pendingClose] 认领），少了就一次压好差额的条目（每条带 `{vade: d}`）。
     */
    fun sync(depth: Int) {
        if (unavailable) return
        desired = depth
        reconcile()
    }

    private fun reconcile() {
        if (unavailable) return
        // 在途的那次 go 落地后会重新收敛；这期间再发 go 会把位置算错。
        if (pendingClose != null) return
        // 帧决策没做完之前不动历史：那一刻才知道该不该补/该不该再派发。
        if (pendingDispatch != null) return

        when {
            // 收条目永远安全（go 是「后退」，不踩 back-trap）：欠着补条目也照收。
            desired < historyDepth -> closeTo(desired)
            desired > historyDepth -> {
                // 欠着补条目：等用户手势，绝不在后退后立刻 push。
                if (owedEntries > 0) return
                // 后退之后想变长：pushState 会被判成 back-trap —— 抬当前条目 + 记账。
                if (afterBack) {
                    hold(historyDepth, desired)
                    return
                }
                pushUpTo(desired)
            }
        }
    }

    /** 一次压好差额的条目：`historyDepth+1 .. target`，每条带自己的 `{vade}`。 */
    private fun pushUpTo(target: Int) {
        for (d in historyDepth + 1..target) {
            val pushed = runCatching { window.history.pushState(jsVadeState(d), "", null) }
            if (pushed.isFailure) {
                degrade("pushState($d)", pushed.exceptionOrNull())
                return
            }
            historyDepth = d
        }
    }

    /**
     * 关层收条目：`go(-k)`，k 夹在 `history.length - 1` 以内，落地靠 [pendingClose] 认领。
     * 夹到 0 = 没有更早的条目可退，那就干脆不发（发了也落不了地，只会招来看门狗）。
     */
    private fun closeTo(target: Int) {
        val steps = clampSteps(historyDepth - target, historyLength())
        // 收几条就少欠几条（欠账是「物理条目比 desired 少」的度量）。
        owedEntries = (owedEntries - (historyDepth - target)).coerceAtLeast(0)
        if (steps <= 0) {
            // 没有可退的条目：把模型对齐到 target（历史本来就只有基座），不再发 go。
            warnOnce(
                "close-no-entries",
                "vade: 想收回 ${historyDepth - target} 条历史条目，但 history 里只有基座" +
                    "—— 后退仍可关层，只是历史不再镜像",
            )
            historyDepth = target
            return
        }
        val moved = runCatching { window.history.go(-steps) }
        if (moved.isFailure) {
            degrade("history.go(-$steps)", moved.exceptionOrNull())
            return
        }
        // 注意不预先改 historyDepth：真正的位置等 popstate 回来再对。
        pendingClose = target
        armCloseWatchdog()
    }

    /**
     * popstate：**只记账**，不动栈、不派发。
     *
     * 派发与否留到帧后（[onDecisionFrame]）——那里才有「层跑完 onBack 之后」的栈深，
     * 也才能把同一帧的两次 popstate 合成一次判定（P1-3）。
     */
    private fun handlePopState(event: Event) {
        val raw = readRawVade((event as? PopStateEvent)?.state)
        val actual = readRawVade(window.history.state)
        // 落地校验（P0-1）：事件里的 state 与当前位置的 history.state 必须对得上。
        //
        // 注意「读不出 vade」不等于「不是我们的」：**基座条目本来就没有 state**，
        // 退到它上面 raw 自然是 -1。把那种情况判成「不可信」会让每次退到基座都被
        // 吞掉（浮层永远关不掉）。所以只有两处**互相矛盾**才走 RESYNC。
        val landed = when {
            raw >= 0 && actual == raw -> raw // 我们的条目，两处一致
            raw < 0 && actual < 0 -> 0       // 基座条目（或别人的 state）：按 0 记
            else -> -1                       // 两处不一致：模型不可信
        }

        // previous / expected 必须在改写账目之前取：decide 要的是「这一跳之前」的我们。
        val previous = historyDepth
        val expected = pendingClose
        pendingDispatch = landed
        pendingPrevious = previous
        pendingExpected = expected
        pendingClose = null
        watchdog++ // 在途看门狗作废：这次 go 有回音了
        historyDepth = if (landed >= 0) landed else 0
        dispatchedThisBack = false
        scheduleDecision()
    }

    /** 合并同一帧的多次 popstate：只排一次帧回调。 */
    private fun scheduleDecision() {
        if (decisionScheduled) return
        decisionScheduled = true
        window.requestAnimationFrame {
            window.requestAnimationFrame {
                decisionScheduled = false
                onDecisionFrame()
            }
        }
    }

    /**
     * 帧决策：用**新的** `dispatcher.depth` 问 [decide]。
     *
     * 首次派发不问 decide（后退一定先给栈顶一次机会）；之后每一次复检都问 ——
     * 栈还在往落点缩就再派发，一条没降就 [BackAction.HOLD] 停手并转入「禁止 push」。
     */
    private fun onDecisionFrame() {
        val landed = pendingDispatch ?: return
        val previous = pendingPrevious
        val expected = pendingExpected
        pendingDispatch = null
        pendingExpected = null
        if (unavailable) return

        val now = liveDepth()

        if (!dispatchedThisBack && landed >= 0 && expected == null && landed < previous) {
            dispatchedThisBack = true
            dispatchOnce(landed)
            return
        }

        when (decide(previous, landed, expected, now)) {
            BackAction.ADOPT, BackAction.IGNORE -> {
                // 这一跳确实后退过（哪怕栈已经缩到位）：之后想变长就得等手势，
                // 否则 pushState 会落进 back-trap 的窗口。
                afterBack = landed >= 0 && landed < previous
                reconcile()
            }
            BackAction.RESYNC -> {
                // 模型不可信：按浏览器此刻说的对齐，再收敛。落点比原来浅就是发生过后退。
                val actual = readRawVade(window.history.state)
                historyDepth = actual.coerceAtLeast(0)
                afterBack = actual >= 0 && actual < previous
                reconcile()
            }
            // landed < 0 已经在上面被 decide 判成 RESYNC，走不到这一支。
            BackAction.HOLD -> hold(landed, now)
            BackAction.DISPATCH -> {
                dispatchedThisBack = true
                dispatchOnce(landed)
            }
        }
    }

    /**
     * 派发一次，并排下一帧复检。
     *
     * 基线取**派发前**的栈深：层的 `onBack` 是异步的（改 state → 重组 → 出栈），
     * 派发后立刻读 `depth` 会读到旧值，那样「一条没降」和「还没跑完」就分不开了。
     */
    private fun dispatchOnce(landed: Int) {
        val callback = onBack ?: return
        val baseline = liveDepth()
        callback()
        pendingDispatch = landed
        pendingPrevious = baseline
        pendingExpected = null
        scheduleDecision()
    }

    /**
     * 后退已经发生、栈却没降：层不关层（抽屉只收抽屉 / 页面只退一层）。
     *
     * 此时**不能 push**（back-trap），能做的是：
     *  - 站在自有条目上（`landed > 0`）→ `replaceState` 把当前条目抬到 `now`，
     *    模型继续按 `now` 记账，物理条目欠一条，等手势后补；
     *  - 站在基座条目上（`landed == 0`）→ 抬它**有害**：基座下面没有条目，抬完下一次
     *    后退仍然直接离开页面，却把「还欠着几条」的账抹平了。所以只记账，
     *    等手势后按台阶（1..`now`）补回来。
     */
    private fun hold(landed: Int, now: Int) {
        afterBack = true
        if (landed > 0) {
            val replaced = runCatching { window.history.replaceState(jsVadeState(now), "", null) }
            if (replaced.isFailure) {
                degrade("replaceState($now)", replaced.exceptionOrNull())
                return
            }
            historyDepth = now
            owedEntries = 1
        } else {
            historyDepth = landed
            owedEntries = (now - landed).coerceAtLeast(1)
        }
        warnOnce(
            "hold-after-back",
            "vade: 后退之后栈深没降（层的 onBack 没有关层）—— 按 back-trap 规则不 push，" +
                "补条目推迟到下一次用户手势（欠 $owedEntries 条）",
        )
    }

    /**
     * 用户手势到达 = back-trap 窗口过去：把欠的物理条目补上，恢复正常镜像。
     *
     * 监听是常驻的、**行为**是一次性的：[owedEntries] 归零后这里只剩 [reconcile]。
     */
    private fun onUserGesture() {
        if (unavailable) return
        afterBack = false
        if (owedEntries > 0) {
            owedEntries = 0
            // replaceState 抬过当前条目（historyDepth 已等于 desired）→ 只需要补一条
            // 同深度的物理条目；没抬过（站在基座上）→ 按台阶 1..desired 补回整摞。
            if (historyDepth >= desired) {
                val pushed = runCatching { window.history.pushState(jsVadeState(desired), "", null) }
                if (pushed.isFailure) {
                    degrade("pushState(补条目)", pushed.exceptionOrNull())
                    return
                }
            } else {
                pushUpTo(desired)
            }
        }
        reconcile()
    }

    /**
     * 刷新后把「state 里写着、内存里却没有」的条目退回去（见文件头「刷新后的残局」）。
     * 期望落点是基座条目，所以用 `pendingClose = 0` 认领这次回程。
     */
    private fun discardStaleEntries() {
        val stale = readRawVade(window.history.state)
        if (stale <= 0) {
            historyDepth = 0
            return
        }
        val steps = clampSteps(stale, historyLength())
        if (steps <= 0) {
            // history 里没有更早的条目可退：按 0 起算，宁可少拦一层也不发无效 go。
            historyDepth = 0
            warnOnce(
                "stale-no-entries",
                "vade: 刷新残留的 vade=$stale 收不回去（history 只有基座）—— 按 0 层起算",
            )
            return
        }
        historyDepth = stale
        val moved = runCatching { window.history.go(-steps) }
        if (moved.isFailure) {
            historyDepth = 0
            warnOnce(
                "stale-cleanup",
                "vade: 清理刷新残留的历史条目失败（${moved.exceptionOrNull()?.message}）" +
                    "—— 后退可能要多按几次才离开页面",
            )
            return
        }
        pendingClose = 0
        armCloseWatchdog()
    }

    /**
     * `go(-k)` 的看门狗（P0-1）：两帧之内没等到 popstate 就判定「这次 go 不会落地」。
     *
     * 清掉 [pendingClose]（否则 [reconcile] 永久短路），并永久降级 —— 历史账目已经
     * 不可信，继续按它记账只会把后续的后退吞掉。只喊一次。
     */
    private fun armCloseWatchdog() {
        val token = ++watchdog
        window.requestAnimationFrame {
            window.requestAnimationFrame { onCloseWatchdog(token) }
        }
    }

    private fun onCloseWatchdog(token: Int) {
        if (token != watchdog) return // 期间落地了 / 又发了新的 go
        if (pendingClose == null) return
        pendingClose = null
        degrade("go 未落地")
    }

    /** 实时栈深：读不到就退回上次 sync 的值（孤立子树 / 已 dispose）。 */
    private fun liveDepth(): Int = runCatching { depthNow?.invoke() ?: desired }.getOrDefault(desired)

    private fun historyLength(): Int = runCatching { jsHistoryLength() }.getOrDefault(1)

    private fun readRawVade(state: JsAny?): Int = runCatching { jsReadVade(state) }.getOrDefault(-1)

    private fun degrade(reason: String, cause: Throwable? = null) {
        if (unavailable) return
        unavailable = true
        pendingClose = null
        pendingDispatch = null
        owedEntries = 0
        val detail = cause?.message?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
        warnOnce(
            "history-degraded",
            "vade: 浏览器历史账目不可信（$reason$detail）" +
                "—— 后退不再被浮层拦截，Esc 仍可关层",
        )
    }
}

/**
 * 浏览器的「后退」挂点（actual）。
 *
 * 驱动量是**栈深**：栈深 d 要求历史里有 d 条自有条目。`depth` 是
 * `SnapshotStateList.size`，在组合期读它就是快照读，层入栈/出栈会重算本函数并重启
 * [LaunchedEffect]。
 *
 * `enabled` 与 common 契约对齐（i2-bridge · P1-6）：`enabled = dispatcher.isHandling`
 * = 栈非空，而**孤立子树（没有 provider）没有栈，深度就是 0**。此前把 `enabled`
 * 折算成「1 层」，预览/独立测试里会凭空压一条历史条目，`dispatch` 又没人接 ——
 * 后退被吞掉且看不出原因。现在 [LocalBackDispatcher] 缺席 = 深度 0 = 不镜像、不拦截。
 *
 * 派发之后还要 `resync++` 逼一次重同步：层的 `onBack` 完全可能不关层（比如只做了一次
 * 「回到列表根」），那样历史已经少了一条，得把「还有层 → 一定还有条目可拦」补回来。
 */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val dispatcher = LocalBackDispatcher.current
    // common 契约：没有 provider 的孤立子树 = 0 层（不再把 enabled 折成 1）。
    val depth = dispatcher?.depth ?: 0
    val latestOnBack by rememberUpdatedState(onBack)
    var resync by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        BrowserBack.onBack = {
            latestOnBack()
            resync++
        }
        BrowserBack.depthNow = { dispatcher?.depth ?: 0 }
        BrowserBack.install()
        onDispose {
            BrowserBack.onBack = null
            BrowserBack.depthNow = null
        }
    }

    LaunchedEffect(depth, resync) {
        BrowserBack.sync(depth)
    }
}
