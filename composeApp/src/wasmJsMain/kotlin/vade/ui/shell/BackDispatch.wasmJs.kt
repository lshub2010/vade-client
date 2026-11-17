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
 * Wasm actual（c7-wasm + i2-bridge + k1-bridge）：浏览器「后退」= 系统返回。
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
 * 所以「后退之后还想让历史变长」只剩一条合法出路，由 [HistoryMirror.onUserGesture] 走：
 * 等下一次**用户手势**（`pointerdown` / `keydown`）之后按台阶 `1..desired` 补回整摞。
 * 此前还有第二条（`replaceState` 抬当前条目 + 手势后补一条同深度的条目），
 * 那会在历史里造出两条 vade 相同的条目 —— 评审 P2-1 已删，见 [HistoryMirror] 的不变式 1。
 *
 * ### 一次 `go(-k)` 只投递一个 popstate，我们不会错账
 *
 * 多步 `history.go(-k)` 只派发**一次** popstate（落在终点条目上），所以「按深度记账的自发
 * pop 计数器」会永久多记 k-1 次，把用户之后的真实后退吞掉。现在不数次数，而是**认 state**：
 * `go(-k)` 之前记下期望落点 `pendingClose`，popstate 里读回 `state.vade`：命中 → 自己收的
 * 条目，只对账；没命中 → 有别人动过历史，按浏览器说的重建。
 *
 * ### 本文件只剩「读事件 + 执行命令」
 *
 * 全部判断与账目都在 commonMain 的 [HistoryMirror] 里（`decide` / `clampSteps` /
 * 在途 `go` / 欠账 / 看门狗 / 降级），本文件只提供三样东西：
 *  - [WasmJsHistory]：`HistoryPort` 的浏览器实现（`window.history` 四个动作）；
 *  - [frames]：`rAF×2` —— 帧决策与看门狗的「等两帧」；
 *  - 三个事件监听（popstate / pointerdown / keydown）到 [HistoryMirror] 的三次调用。
 *
 * 为什么这样拆：这套记账此前整段埋在 actual 里，desktopTest 摸不到 `window`，只能靠读
 * 代码确认 —— 「看门狗一超时就永久降级」（评审 P1-1）与「HOLD 造出同 vade 双条目」
 * （评审 P2-1）都是这么漏过去的。现在同样的代码路径能在 JVM 上跑（`HistoryMirrorAccountingTest`）。
 *
 * 历史 API 不可用时（`file://` 等不透明来源会抛 SecurityError、被嵌入策略拦掉同理）
 * 引擎捕获异常并**一次性** `console.warn` 后降级成空实现（Esc 照旧、后退照旧离开页面），
 * 绝不让异常冒进组合把首帧打白。
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
 * 引擎只用它算「身后条目数的上界」（`length - 1`，还含前进条目与进入本页之前的条目），
 * 再与自己的账目取**下界**去夹 `go(-k)`：注定落不了地的 go 不发。
 * 读不到时按 1 处理 —— 宁可当「退不了」，也不要发一个落空的 go。
 */
@JsFun("() => window.history.length")
private external fun jsHistoryLength(): Int

/** `kotlinx.browser` 只绑了 window/document/storage，没有 console。 */
@JsFun("(m) => { console.warn(m); }")
private external fun jsWarn(message: String)

/** `window.history` 的四个动作（[HistoryPort] 的浏览器实现）。 */
private object WasmJsHistory : HistoryPort {

    override val length: Int get() = runCatching { jsHistoryLength() }.getOrDefault(1)

    override fun currentVade(): Int = runCatching { jsReadVade(window.history.state) }.getOrDefault(-1)

    override fun push(vade: Int) {
        // 不 runCatching：真抛（不透明来源 / 被策略拦）要交给引擎降级，不能在这里吞掉。
        window.history.pushState(jsVadeState(vade), "", null)
    }

    override fun goBack(steps: Int) {
        window.history.go(-steps)
    }
}

/**
 * 两帧之后：一次 rAF 只保证「这一帧画完」，同一帧里先后到达的 popstate 得并进同一批判定
 * （见 [HistoryMirror] 的帧决策），看门狗也要给 popstate 留出投递窗口。
 */
private val frames: (() -> Unit) -> Unit = { block ->
    window.requestAnimationFrame {
        window.requestAnimationFrame {
            block()
        }
    }
}

/** 整个页面**唯一**的一份账目：多份监听 = 一次后退被派发多次，多份账目 = 位置算错。 */
private val mirror = HistoryMirror(
    port = WasmJsHistory,
    frames = frames,
    warn = { message -> runCatching { jsWarn(message) } },
)

/** 事件监听只挂一次；页面生命周期内不再摘（[HistoryMirror.onBack] 置空即失效）。 */
private var installed = false

private fun readRawVade(state: JsAny?): Int = runCatching { jsReadVade(state) }.getOrDefault(-1)

private fun install() {
    if (installed) return
    installed = true
    window.addEventListener("popstate", { event: Event ->
        // 两处 state 分开读：事件里那份是「到这儿的时候入口写的」，`history.state` 是
        // 「浏览器此刻认为的当前位置」；对不对得上是引擎的事（对不上走 RESYNC）。
        mirror.onPopState(
            eventVade = readRawVade((event as? PopStateEvent)?.state),
            currentVade = WasmJsHistory.currentVade(),
        )
    })
    // 用户手势 = back-trap 窗口结束的信号，补条目只能在这之后（见文件头）。
    window.addEventListener("pointerdown", { _: Event -> mirror.onUserGesture() })
    window.addEventListener("keydown", { _: Event -> mirror.onUserGesture() })
    // 启动即同步：App 里 0 层（刷新后的残局清理在引擎的 discardStaleEntries）。
    mirror.start()
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
        mirror.onBack = {
            latestOnBack()
            resync++
        }
        mirror.depthNow = { dispatcher?.depth ?: 0 }
        install()
        onDispose {
            mirror.onBack = null
            mirror.depthNow = null
        }
    }

    LaunchedEffect(depth, resync) {
        mirror.sync(depth)
    }
}
