package vade.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 系统返回的可扩展浮层栈（g-back-handler，修复 G1「开浮层按返回 = 整个 App 退出」）。
 *
 * 三个已知浮层（搜索浮层、附件预览、模型选择浮层）此前只接了**可见的**返回按钮，
 * Android 的系统返回键 / 返回手势没有任何一层注册，按下即走系统默认 → 直接退出
 * （审计证据 `shots/g-audit-gaps/f05.png`、`f09.png`，全仓 `grep -c BackHandler` = 0）。
 *
 * 模型（两条规则，缺一不可）：
 *
 *  1. **栈式，后注册的先处理（topmost wins）**。每个开着状态的浮层经 [BackLayer]
 *     注册一层；返回到来时栈顶先关，关掉后下一层自然顶上 —— 两层叠放只关最上层。
 *     栈序即组合序（后组成的在上面，与 Box 的视觉层叠一致）。
 *  2. **栈空交还系统**。[BackDispatcher.dispatch] 返回 `false` = 未消费，返回行为
 *     回到系统默认（允许退出 App）；桥接层 [BackHost] 的 `enabled` 也随之变 false，
 *     不再拦截。这个布尔就是「没开浮层 → 返回不吞」的可观测判据。
 *
 * 三端一条代码路径：栈与注册是 commonMain；系统返回的挂点拆成 expect/actual 的
 * [SystemBackHandler]（Android = `androidx.activity.compose.BackHandler` →
 * `ComponentActivity` 的 `OnBackPressedDispatcher`，该依赖已随 `setContent` 在用，
 * **没有**为此新增依赖；desktop / wasm 没有系统返回，actual 为空实现）。
 * desktop / wasm 的返回由 Esc 驱动：`AppShell.handleShellKeys` 里新增的
 * 「Esc 优先交给返回栈」一行 —— 栈顶浮层先关，栈空回落到既有 Esc 语义
 * （关搜索 / 关会话详情），桌面行为不变。
 *
 * 页面 / 浮层接入只需一行，与抽屉同构：在自己的组合里登记，开着时入栈、关掉或离开
 * 组合自动出栈；不要另起第二套返回机制。栈序 = 组合序 = 视觉层叠（后组成的在上面）。
 *
 * ```
 * BackLayer(enabled = drawerOpen, onBack = { drawerOpen = false })
 * ```
 *
 * 已接清单（g-nav-stack / c2-nav 收口）：
 *  - 搜索浮层（`SearchOverlay`）、附件预览（`ChatThread`）、模型选择浮层
 *    （`ModelPickerSheet`）；
 *  - 会话抽屉 + 详情：手机单栏由 `SessionsDrawerHost` 登记一层（抽屉开着 → 收抽屉；
 *    收着 → `state.back()` 清选中回列表根），≥600dp 的双栏 / rail 详情由
 *    `DestinationHost` 登记同一个 `state.back()` 动作；
 *  - 模块页（`ModulePageHost`）：返回退一层 —— 面包屑 >1 退回上一页，深度 1 退出模块页
 *    回能力页，与顶栏返回按钮同一个动作；
 *  - 关于子页（`ProfileScreen`）：返回关子页回我的页。
 *
 * 新浮层 / 新子页照上面加一层即可；「没登记」的页面按返回仍会直接退出 App。
 */
class BackDispatcher {

    /** 栈元素：只带回调。用引用相等配对 push/pop（`mutableStateListOf.remove` 按equals，普通类的 equals 即引用）。 */
    internal class Layer(val onBack: () -> Unit)

    private val stack = mutableStateListOf<Layer>()

    /** 当前是否有浮层接管返回（真值直接驱动 [BackHost] 的 `enabled`）。 */
    val isHandling: Boolean get() = stack.isNotEmpty()

    /** 栈深（诊断/测试用：浮层关干净后必须回到 0 —— 开关 5 次不泄漏的验收判据）。 */
    val depth: Int get() = stack.size

    /** 入栈。由 [BackLayer] 在 enabled 时调用；返回句柄供出栈配对。 */
    internal fun push(onBack: () -> Unit): Layer {
        val layer = Layer(onBack)
        stack.add(layer)
        return layer
    }

    /** 出栈（按句柄）。由 [BackLayer] 在 disabled / 离开组合时调用。 */
    internal fun pop(layer: Layer) {
        stack.remove(layer)
    }

    /**
     * 系统返回（或 Esc）到来：栈顶的 `onBack` 先处理，返回 `true` = 已消费；
     * 栈空返回 `false` = 未消费，调用方应把行为交还系统（Android 允许退出）。
     */
    fun dispatch(): Boolean {
        val top = stack.lastOrNull() ?: return false
        top.onBack()
        return true
    }
}

/**
 * 返回栈的 CompositionLocal。组合根（`App.kt`）provide 一次；没挂 provider 的孤立
 * 子树（组件预览、独立测试）里 [BackLayer] 退化为局部栈 —— 层语义仍可测，但接不到
 * 系统返回，生产树必须由组合根 provide。
 */
val LocalBackDispatcher = staticCompositionLocalOf<BackDispatcher?> { null }

/**
 * 把一段组合注册成返回栈的一层。
 *
 * `enabled` 为真 → 入栈；为假或本组合离开 → 出栈。所以「浮层真的没了」（组合离开）
 * 自动伴随「栈清干净」，不需要任何手动 unregister —— 开关 5 次后 [BackDispatcher.depth]
 * 必须回到 0（守卫测试钉住）。
 *
 * [onBack] 的最新值在派发时才读（`rememberUpdatedState`），lambda 身份变化不会把层
 * 重新压栈打乱栈序。
 *
 * 顺序语义：栈序 = 组合序，后组成的（视觉上更靠上的浮层）后入栈、先被消费 ——
 * topmost wins。
 */
@Composable
fun BackLayer(
    enabled: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit = {},
) {
    val dispatcher = LocalBackDispatcher.current ?: remember { BackDispatcher() }
    val latestOnBack by rememberUpdatedState(onBack)
    val stableOnBack = remember { { latestOnBack() } }
    DisposableEffect(dispatcher, enabled) {
        val layer = if (enabled) dispatcher.push(stableOnBack) else null
        onDispose { layer?.let(dispatcher::pop) }
    }
    content()
}

/**
 * 系统返回桥：组合根挂一次。栈非空时 `enabled = true` 拦截系统返回并把行为交给
 * [BackDispatcher.dispatch]（栈顶先处理）；栈空时不拦截 —— 系统默认（可退出）。
 *
 * Android：actual = `androidx.activity.compose.BackHandler`，挂靠
 * `ComponentActivity` 的 `OnBackPressedDispatcher`（`MainActivity` 已是
 * `ComponentActivity + setContent`，owner 就位）。
 * desktop / wasm：无系统返回，actual 为空实现；Esc 驱动在 `handleShellKeys`。
 */
@Composable
fun BackHost(dispatcher: BackDispatcher) {
    SystemBackHandler(enabled = dispatcher.isHandling) {
        dispatcher.dispatch()
    }
}

/**
 * 系统返回的挂点（expect）。`enabled = true` 时拦截系统返回并调 [onBack]；
 * `false` 时绝不拦截（系统默认行为，允许退出 App）。
 *
 * 之所以拆 actual：CMP 1.12 的 `androidx.compose.ui.backhandler.BackHandler` 所在的
 * `org.jetbrains.compose.ui:ui-backhandler` 不在任何已解析依赖的传递闭包里（gradle
 * dependencies 实测 0 命中），加它必须改 build.gradle.kts（本批禁改）；而
 * `androidx.activity.compose.BackHandler` 已随 `setContent` 在用，Android actual
 * 直接用它，desktop / wasm actual 为空 —— 三个 actual 文件都在 `vade.ui.shell`
 * 包下（BackDispatch 的平台拆分，见 RESULT 的越界单列）。
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)

// --- wasm 历史桥的记账（纯逻辑） ---------------------------------------------------
//
// 浏览器端的 actual（`BackDispatch.wasmJs.kt`）把返回栈**镜像**成 N 条历史条目。它的全部
// 判断与账目都收在这里，wasm 那一侧只剩「读事件 + 执行命令」，好让 desktopTest 在没有
// 浏览器的环境里把整套记账序列跑一遍（`HistoryMirrorAccountingTest`）。
//
// 三个层次，各有各的失败方式：
//  - [decide]：一次 popstate 该不该派发（单次判定，纯函数）；
//  - [clampSteps]：一次 `go(-k)` 最多走多远（单步边界，纯函数）；
//  - [HistoryMirror]：连续记账 —— 在途 `go`、后退之后的欠账、看门狗、降级全在这一层，
//    副作用经 [HistoryPort] 注入。

/**
 * 一次历史事件（或派发后的一次复检）该做什么。
 *
 * 命名按「调用点要做什么」而不是「谁的状态」，因为四个动作彼此不重叠。
 */
internal enum class BackAction {
    /** 模型不可信（两处 state 对不上 / 自发回程踏空）：按浏览器说的重建，不派发。 */
    RESYNC,

    /** 我们自己 `go(-k)` 的回程，落在期望深度：只对账，不派发。 */
    ADOPT,

    /** 原地 / 前进 / 栈已经缩到落点或更浅：不派发，收敛即可。 */
    IGNORE,

    /** 后退了、栈在缩但还没缩到落点：**再**派发一次（同帧多次后退不丢）。 */
    DISPATCH,

    /**
     * 后退已经发生、栈一条也没降（层的 `onBack` 不关层，比如抽屉只收抽屉）：
     * 不派发、也不 push —— 后退之后 `pushState` 会被 Chromium 判成 back-trap，
     * 新条目被标 `should_skip_on_back_forward_ui_`，后退按钮整条跳过它、
     * 连 popstate 都不投递。处理办法见 [HistoryMirror.holdAfterBack]：只记账，
     * 补条目推迟到下一次用户手势。
     */
    HOLD,
}

/**
 * 历史桥的状态机（纯函数）。
 *
 * 参数是**四个独立的量**，不是同一时刻的快照 —— 混起来看会读错：
 *
 *  - [previous]：上一轮派发时的栈深（没派发过就是历史深度）；
 *  - [landed]：这次 popstate 落在的历史深度；`-1` = 模型不可信（事件 state 与
 *    `history.state` 对不上，或自发回程踏空）。基座条目没有 state，折算成 0，
 *    不是 -1 —— 否则每次退到基座都会被当成「不是我们的」而吞掉。
 *  - [expected]：在途 `go(-k)` 的期望落点；`null` = 没有自发回程在飞；
 *  - [desired]：**新的**栈深（`dispatcher.depth`），不是派发前的旧值。
 *
 * 为什么 `desired` 必须是新的：层收到 `onBack` 之后可能什么都不关（抽屉收抽屉、
 * 页面只退一层），也可能关掉不止一层。拿派发前的栈深判「还要不要再派发」，
 * 要么把同一帧的两次后退判成一次，要么对着已经关掉的层再关一次。
 *
 * 调用契约（actual 侧两处）：
 *
 *  1. **首次派发不吃 decide**：一次后退一定先给栈顶一次机会（后退 = 关一层，
 *     三端一致的语义）。decide 回答的是「**再**派发 / 停手 / 能不能补条目」。
 *  2. 派发之后隔一帧再问一次，`desired` 取那一刻的 `dispatcher.depth`：
 *     栈降了但还没到 [landed] → [BackAction.DISPATCH]；一条没降 → [BackAction.HOLD]。
 */
internal fun decide(previous: Int, landed: Int, expected: Int?, desired: Int): BackAction {
    // state 不是我们的：别的量怎么摆账都对不上，只能按浏览器说的重建。
    if (landed < 0) return BackAction.RESYNC
    // 自发 go(-k) 的回程：命中期望 = 我们自己收的条目；踏空 = 有别人动过历史。
    if (expected != null) return if (landed == expected) BackAction.ADOPT else BackAction.RESYNC
    // 没后退（前进 / 原地）：没有要关的层。
    if (landed >= previous) return BackAction.IGNORE
    // 栈已经缩到落点或更浅：这次后退已经被消化掉了，再派发会多关一层。
    if (desired <= landed) return BackAction.IGNORE
    // 栈一条没降（或反而长高）：层不收 —— 派发解决不了，push 又踩 back-trap。
    if (desired >= previous) return BackAction.HOLD
    // 在缩但还没到落点：同一帧的多次后退，继续派发。
    return BackAction.DISPATCH
}

/**
 * `history.go(-k)` 的 k 夹在 `[0, entriesBehind]`。
 *
 * [entriesBehind] 是**身后条目数的下界**，不是会话历史总长度：
 *
 *  - `history.length` 数的是**整个会话**的条目（含前进条目，含进入本页之前的条目），
 *    `length - 1` 只是「身后到底有几条」的一个上界 —— 拿它当条目数会算出比实际更大的
 *    步数，`go` 落空（或更糟：退到本页之前的那一页，直接离开应用）；
 *  - 账目里的 `historyDepth`（当前位置的 vade）是我们自己的估计，同样可能偏大。
 *
 * 所以调用点先取两者的**下界**再交进来（见 [HistoryMirror.entriesBehind]）：宁可少退一次
 * （落地后 [HistoryMirror.reconcile] 会接着退），也不发一个落空的 go —— 落空要白等一个
 * 看门狗周期。k 被夹到 0 时调用点直接不发 go。
 */
internal fun clampSteps(steps: Int, entriesBehind: Int): Int =
    steps.coerceIn(0, entriesBehind.coerceAtLeast(0))

/**
 * 历史侧副作用的端口。
 *
 * 抽出来不是为了多一层抽象，而是为了让 [HistoryMirror] 的记账（在途 go、欠账、看门狗、
 * 降级）能在 desktopTest 里**真跑一遍** —— 那套逻辑此前埋在 wasm actual 里，`window`
 * 在 JVM 上不存在，只能靠读代码确认，P1-1（看门狗永久降级）就是这么漏过去的。
 *
 * 实现约定：`push` / `goBack` **真抛异常**才算「历史 API 不可用」（不透明来源的
 * SecurityError、被嵌入策略拦掉），由 [HistoryMirror] 捕获后降级；`history.go` 越界
 * 这类静默无效**不抛**，走看门狗那条路。
 */
internal interface HistoryPort {

    /** 会话历史条数（含当前条目）。读不到给 1 = 只有基座。 */
    val length: Int

    /** 当前条目 state 里的 `vade`；读不出（基座条目本来没有 state / 别人写的 state）给 -1。 */
    fun currentVade(): Int

    /** `history.pushState({vade: vade}, "", null)`。 */
    fun push(vade: Int)

    /** `history.go(-steps)`，`steps > 0`。 */
    fun goBack(steps: Int)
}

/**
 * 浏览器历史 ↔ 返回栈的**记账器**（纯逻辑，没有 DOM）。
 *
 * wasm actual 每个页面一份：popstate 只调 [onPopState] 记账，用户手势调 [onUserGesture]，
 * 栈深变化调 [sync]；要不要派发问 [decide]，一次走多远问 [clampSteps]。三条不变式：
 *
 *  1. **加长历史只有一条路径**（[pushUpTo]）：从 `historyDepth + 1` 逐条压到 `desired`，
 *     每条 vade 递增 1。这里曾经还有第二条路径 —— HOLD 时 `replaceState({vade: now})`
 *     把当前条目抬到 `now`，手势后再 `pushState({vade: now})` 补一条**同深度**的物理条目，
 *     历史里于是出现两条 vade 相同的条目（评审 P2-1）：后退一次落到同 vade 那条上，
 *     连 popstate 都不投递，表现为「按了没反应」。现在物理位置就停在浏览器说的那一条，
 *     欠的条目一律由手势后的 `pushUpTo` 按台阶补齐。
 *  2. **后退之后不 push**（`afterBack`，Chromium 的 back-trap 启发式）：欠账只记不改历史，
 *     等下一次 `pointerdown` / `keydown` 再补。收条目（`go`）不受限，`go` 是后退。
 *  3. **`go` 不落地 ≠ 历史不可用**（评审 P1-1）：`history.go` 不抛错也可能什么都不做
 *     （越界 / 被嵌入策略吞掉 / 页面正在卸载）。看门狗超时只清在途状态、按浏览器此刻
 *     说的位置重建账目、用剩余预算至多重发**一次** `go`；永久降级只留给 `pushState` /
 *     `go` 真抛异常的场合。
 *
 * 线程/重入：全部从 UI 线程（组合副作用、事件回调）进入，与浏览器历史一样是单线程的。
 * [frames] 是「两帧之后」的注入点：wasm 侧 = `requestAnimationFrame` ×2，测试里 = 手动队列
 * （延迟回调的必要性见 [onDecisionFrame] 的注释）。
 */
internal class HistoryMirror(
    private val port: HistoryPort,
    private val frames: (() -> Unit) -> Unit,
    private val warn: (String) -> Unit = {},
) {

    /** 派发目标（`dispatcher.dispatch()`）；组合 dispose 后置空 = 只记账不派发。 */
    var onBack: (() -> Unit)? = null

    /**
     * 栈深的**实时**读取器（`dispatcher.depth`）。
     *
     * 不能直接用 [sync] 记下来的 [desired]：帧决策要的是「层的 `onBack` 跑完之后」的深度，
     * 而 [sync] 只在重组时被 `LaunchedEffect` 推进 —— 两者之间可能差一帧。
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

    /** 看门狗超时后的重发预算：每次 [sync] / 每次落地重置为 1 —— 重发只针对当前这次意图。 */
    private var closeRetriesLeft = 1

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

    /** 历史 API 已因**真异常**判定不可用：永久降级，不再尝试。 */
    private var unavailable = false

    /** 同一条告警只喊一次，避免 popstate 每次都刷控制台。 */
    private val warnedKeys = HashSet<String>()

    /** 诊断：账目认为当前站在第几条（= 身后自有条目数）。 */
    val currentDepth: Int get() = historyDepth

    /** 诊断：在途 `go(-k)` 的期望落点（null = 没有回程在飞）。夹过 k 时它**不等于**目标深度。 */
    val expectedLanding: Int? get() = pendingClose

    /** 诊断：是否已因历史 API 真异常永久降级。 */
    val isDegraded: Boolean get() = unavailable

    /** 欠着的物理条目数：账目想要的深度比历史位置多出来的部分。 */
    private val owedEntries: Int get() = (desired - historyDepth).coerceAtLeast(0)

    /**
     * 挂载时调一次：App 里 0 层（[desired] 的初值就是 0），先把刷新残留的条目退回去。
     *
     * 这里**不**再赋 `desired = 0`：本函数与 [sync] 的先后取决于两个副作用的排队顺序，
     * 谁先谁后都不该把栈深重置掉。
     */
    fun start() {
        discardStaleEntries()
    }

    /**
     * 让「历史深度」追上「栈深」：多了就 `go(-k)` 收回（只投递一个 popstate，用
     * [pendingClose] 认领），少了就一次压好差额的条目（每条带 `{vade: d}`）。
     */
    fun sync(depth: Int) {
        if (unavailable) return
        desired = depth
        // 新的意图 = 新的重试预算（看门狗的重发只针对「这一次 sync」）。
        closeRetriesLeft = 1
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
                // 后退之后想变长：pushState 会被判成 back-trap，只记账，等手势。
                if (afterBack) {
                    holdAfterBack()
                    return
                }
                pushUpTo(desired)
            }
        }
    }

    /**
     * 一次压好差额的条目：`historyDepth+1 .. target`，每条带自己的 `{vade}`。
     *
     * **加长历史只有这一条路径**（不变式 1）：没有「抬当前条目」的写法，也就不可能造出
     * 两条 vade 相同的条目。补条目（HOLD 之后的欠账）走的也是这里。
     */
    private fun pushUpTo(target: Int) {
        while (historyDepth < target) {
            val d = historyDepth + 1
            if (!attempt("pushState($d)") { port.push(d) }) return
            historyDepth = d
        }
    }

    /**
     * 关层收条目：`go(-k)`，k 按**身后条目数的下界**夹（[clampSteps]）。
     *
     * 夹过之后走不满 target 是正常的：期望落点记成「退 steps 条之后」的深度而不是 target，
     * 否则 popstate 回来会被判成「踏空」→ RESYNC（评审 P2-5）。剩下那截由落地后的
     * [reconcile] 接着收。
     */
    private fun closeTo(target: Int) {
        val wanted = historyDepth - target
        val steps = clampSteps(wanted, entriesBehind())
        if (steps <= 0) {
            // 一步也退不了：此时浏览器只会是「站在基座」（length = 1），按 0 记账，
            // 不发一个注定落空的 go（它会白等一个看门狗周期）。
            historyDepth = 0
            warnOnce(
                "close-no-entries",
                "vade: 想收回 $wanted 条历史条目，但 history 里只有基座" +
                    "—— 后退仍可关层，只是历史不再镜像",
            )
            return
        }
        if (!attempt("history.go(-$steps)") { port.goBack(steps) }) return
        // 注意不预先改 historyDepth：真正的位置等 popstate 回来再对。
        pendingClose = historyDepth - steps
        armCloseWatchdog()
    }

    /**
     * 身后到底有多少条历史条目 —— 取**下界**。
     *
     * 两个已知量都只是上界：账目说 [historyDepth] 条（自己的估计，可能偏大），
     * 浏览器说最多 `length - 1` 条（`history.length` 还含前进条目与进入本页之前的条目，
     * 它从来**不是**「身后条目数」，评审 P2-1）。取小的那个，宁可少退一次。
     */
    private fun entriesBehind(): Int = minOf(historyDepth, port.length - 1).coerceAtLeast(0)

    /**
     * popstate：**只记账**，不动栈、不派发。
     *
     * [eventVade] = 事件里那份 state（入口写的），[currentVade] = 此刻的 `history.state`
     * （浏览器认的）。两份对不上说明模型不可信，[decide] 会判 RESYNC。
     * 「两处都读不出」不算不可信：**基座条目本来就没有 state**，退到它上面自然是 -1，
     * 判成不可信会让每次退到基座都被吞掉（浮层永远关不掉）。
     */
    fun onPopState(eventVade: Int, currentVade: Int) {
        if (unavailable) return
        val landed = when {
            eventVade >= 0 && currentVade == eventVade -> eventVade
            eventVade < 0 && currentVade < 0 -> 0
            else -> -1
        }

        // previous / expected 必须在改写账目之前取：decide 要的是「这一跳之前」的我们。
        val previous = historyDepth
        val expected = pendingClose
        pendingDispatch = landed
        pendingPrevious = previous
        pendingExpected = expected
        pendingClose = null
        watchdog++ // 在途看门狗作废：这次 go 有回音了
        closeRetriesLeft = 1 // 位置真的动了：重发预算重新给
        historyDepth = if (landed >= 0) landed else 0
        dispatchedThisBack = false
        scheduleDecision()
    }

    /** 合并同一帧的多次 popstate：只排一次帧回调。 */
    private fun scheduleDecision() {
        if (decisionScheduled) return
        decisionScheduled = true
        frames {
            decisionScheduled = false
            onDecisionFrame()
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
                val actual = port.currentVade()
                historyDepth = actual.coerceAtLeast(0)
                afterBack = actual.coerceAtLeast(0) < previous
                reconcile()
            }
            // landed < 0 已经在上面被 decide 判成 RESYNC，走不到这一支。
            BackAction.HOLD -> holdAfterBack()
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
     * 后退已经发生、历史却不能加长（back-trap 窗口）：**只记账，不动历史**。
     *
     * 物理位置就停在浏览器说的那一条（[historyDepth] 已经等于 landed），欠多少条由
     * [owedEntries] 现算 —— 之后再开一层，欠账跟着涨，不需要第二本账。补条目统一由
     * [onUserGesture] → [pushUpTo] 走台阶，不存在「抬当前条目」那条会把 vade 记重的路。
     */
    private fun holdAfterBack() {
        afterBack = true
        warnOnce(
            "hold-after-back",
            "vade: 后退之后历史不再加长（back-trap）—— 按规则不 push，" +
                "补条目推迟到下一次用户手势（欠 $owedEntries 条）",
        )
    }

    /**
     * 用户手势到达 = back-trap 窗口过去：把欠的物理条目按台阶（`historyDepth+1 .. desired`）
     * 一次补齐，恢复正常镜像。
     *
     * 监听是常驻的、**行为**是一次性的：`afterBack` 归零后这里只剩 [reconcile]。
     */
    fun onUserGesture() {
        if (unavailable) return
        afterBack = false
        reconcile()
    }

    /**
     * 刷新后把「state 里写着、内存里却没有」的条目退回去。
     *
     * `pushState` 的 state 是浏览器持久化的，内存里的栈却随刷新清零：`history.state.vade = 3`
     * 而 App 里 0 层 —— 用户按一次后退会被当成「关层」，白白吃掉一次（要按 3 次才真正
     * 离开页面）。期望落点是基座，所以用 `pendingClose = 落点` 认领这次回程。
     */
    private fun discardStaleEntries() {
        val stale = port.currentVade()
        if (stale <= 0) {
            historyDepth = 0
            return
        }
        historyDepth = stale
        val steps = clampSteps(stale, entriesBehind())
        if (steps <= 0) {
            // history 里没有更早的条目可退：按 0 起算，宁可少拦一层也不发无效 go。
            historyDepth = 0
            warnOnce(
                "stale-no-entries",
                "vade: 刷新残留的 vade=$stale 收不回去（history 只有基座）—— 按 0 层起算",
            )
            return
        }
        if (!attempt("history.go(-$steps)") { port.goBack(steps) }) {
            // 真抛异常已经降级；把自己按 0 起算，好歹别让一次刷新把后退全吞掉。
            historyDepth = 0
            return
        }
        pendingClose = historyDepth - steps
        armCloseWatchdog()
    }

    /**
     * `go(-k)` 的看门狗：两帧之内没等到 popstate 就判定「这次 go 没有落地」。
     *
     * 见不变式 3 —— 超时**不是**降级信号，所以这里只清在途状态，处理在 [onCloseWatchdog]。
     */
    private fun armCloseWatchdog() {
        val token = ++watchdog
        frames { onCloseWatchdog(token) }
    }

    /**
     * 看门狗超时（评审 P1-1）：`go` 没落地 ≠ 历史不可用。
     *
     * 旧实现在这里直接 `degrade()` —— 一次没落地的 go 就把整座桥永久关掉：之后开层不压
     * 条目、关层不收条目，浏览器后退**静默**退回「离开整个应用」。但没落地的原因往往只是
     * 这一次 go 越界 / 被嵌入策略吞掉 / 页面正在卸载，历史本身仍然好好地在。
     *
     * 现在：清 [pendingClose]（否则 [reconcile] 永久短路）→ 按 `history.state.vade` 重建
     * 账目（浏览器此刻站在哪条就记哪条）→ 用剩余预算**重发一次** go。第二次还不落地就
     * 停在「历史不再收缩、但后退照旧派发」的诚实状态，等下一次 [sync] 重新给预算。
     */
    private fun onCloseWatchdog(token: Int) {
        if (token != watchdog) return // 期间落地了 / 又发了新的 go
        if (pendingClose == null) return
        pendingClose = null
        // 重建账目：go 没落地 = 位置没动，按浏览器此刻说的那条重记（基座读不出 state 按 0）。
        historyDepth = port.currentVade().coerceAtLeast(0)
        if (closeRetriesLeft > 0) {
            closeRetriesLeft--
            warnOnce(
                "go-not-landed",
                "vade: history.go 没有落地 —— 按当前位置（vade=$historyDepth）重建账目后重发一次",
            )
            reconcile()
            return
        }
        warnOnce(
            "go-stuck",
            "vade: history.go 连续两次没有落地 —— 停止收缩历史；后退仍由栈处理，Esc 照旧",
        )
    }

    /** 实时栈深：读不到就退回上次 sync 的值（孤立子树 / 已 dispose）。 */
    private fun liveDepth(): Int = runCatching { depthNow?.invoke() ?: desired }.getOrDefault(desired)

    /** 执行一次历史副作用；**真抛异常**才降级（静默无效走看门狗）。 */
    private fun attempt(what: String, block: () -> Unit): Boolean {
        val failure = runCatching(block).exceptionOrNull() ?: return true
        degrade(what, failure)
        return false
    }

    private fun warnOnce(key: String, message: String) {
        if (!warnedKeys.add(key)) return
        runCatching { warn(message) }
    }

    private fun degrade(reason: String, cause: Throwable? = null) {
        if (unavailable) return
        unavailable = true
        pendingClose = null
        pendingDispatch = null
        afterBack = false
        val detail = cause?.message?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""
        warnOnce(
            "history-degraded",
            "vade: 浏览器历史账目不可信（$reason$detail）" +
                "—— 后退不再被浮层拦截，Esc 仍可关层",
        )
    }
}
