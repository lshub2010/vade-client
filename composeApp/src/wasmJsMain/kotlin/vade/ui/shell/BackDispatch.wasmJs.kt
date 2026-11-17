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
 * Wasm actual（c7-wasm）：浏览器「后退」= 系统返回。
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
 * 那是上一版的写法，被 Chromium 的 history intervention 判成 back-trap：同一文档
 * 后退之后又 `pushState` 出来的条目会被打上 `should_skip_on_back_forward_ui_`，
 * 后退按钮**整条跳过**它、连 popstate 都不投递 —— 表现是「按两次后退只关一层，
 * 或者直接离开页面」。开层时一次压好 N 条不触发这条启发式：压的时候没有发生过后退。
 *
 * ### 为什么 `go(-k)` 只投递一个 popstate，我们仍然不会错账
 *
 * 多步 `history.go(-k)` 只派发**一次** popstate（落在终点条目上），所以「按深度记账的
 * 自发 pop 计数器」会永久多记 k-1 次，把用户之后的真实后退吞掉 —— 这是上一版放弃镜像
 * 的理由。现在不再数次数，而是**认 state**：`go(-k)` 之前记下期望落点
 * [BrowserBack.pendingClose] = 关完之后的深度，popstate 里读回 `state.vade`：
 *
 *  - 命中期望值 → 是我们自己收的条目，只对账（同步 [BrowserBack.historyDepth]），不派发。
 *    连续关多层也只会有一次 go、一次 popstate，账目自洽；
 *  - 没命中（用户后退 / 前进 / 真的跳到了别处）→ 派发一次返回栈，并把历史深度对齐到
 *    state 里的值。之后 `sync` 会把「历史深度」和「栈深」重新收敛（层的 `onBack` 完全
 *    可能不关层，比如只退到列表根）。
 *
 * ### 刷新后的残局
 *
 * `pushState` 的 state 是浏览器持久化的，内存里的栈却随刷新清零。刷新后
 * `history.state.vade = 3` 而 App 里 0 层 —— 用户按一次后退，我们会把它当成「关层」
 * 白白吃掉一次（要按 3 次才真正离开页面）。所以 [BrowserBack.install] 在启动时主动
 * `go(-vade)` 把多出来的条目退回去，这次回程同样用 `pendingClose = 0` 认领，不派发。
 *
 * 历史 API 不可用时（`file://` 等不透明来源会抛 SecurityError、被嵌入策略拦掉同理）
 * 整段 [runCatching] 兜住并**一次性** `console.warn`：降级成上一版的空实现（Esc 照旧、
 * 后退照旧离开页面），绝不让异常冒进组合把首帧打白。
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

/** `kotlinx.browser` 只绑了 window/document/storage，没有 console。 */
@JsFun("(m) => { console.warn(m); }")
private external fun jsWarn(message: String)

/** 一次性告警：同一条原因只喊一次，避免 popstate 每次都刷控制台。 */
private val warnedKeys = HashSet<String>()

private fun warnOnce(key: String, message: String) {
    if (!warnedKeys.add(key)) return
    runCatching { jsWarn(message) }
}

/** 当前历史位置对应的栈深；0 = 基座条目（不是我们压的）。 */
private fun readVade(state: JsAny?): Int = jsReadVade(state).coerceAtLeast(0)

/**
 * 浏览器历史与返回栈之间的桥。单例：整个页面只有一份 popstate 监听（多份监听 =
 * 一次后退被派发多次），也只有一份深度账目。
 */
private object BrowserBack {

    /** popstate 监听只挂一次；页面生命周期内不再摘（[onBack] 置空即失效）。 */
    private var installed = false

    /** 派发目标：`BackHost` 传进来的 `dispatcher.dispatch()`。 */
    var onBack: (() -> Unit)? = null

    /** 应用想要的深度（`dispatcher.depth` 的最新值，由 `sync` 推进）。 */
    private var desired = 0

    /** 历史位置对应的深度 = 当前位置条目的 `vade`（基座条目 0）。 */
    private var historyDepth = 0

    /** UI 主动关层发起、尚未落地的 `go(-k)` 期望落点；非空时不再动历史。 */
    private var pendingClose: Int? = null

    /** 历史 API 已判定不可用：永久降级，不再尝试。 */
    private var unavailable = false

    fun install() {
        if (installed) return
        installed = true
        window.addEventListener("popstate", { event: Event -> handlePopState(event) })
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

        if (desired > historyDepth) {
            for (d in historyDepth + 1..desired) {
                val pushed = runCatching { window.history.pushState(jsVadeState(d), "", null) }
                if (pushed.isFailure) {
                    degrade("pushState($d)", pushed.exceptionOrNull())
                    return
                }
                historyDepth = d
            }
        } else if (desired < historyDepth) {
            val steps = historyDepth - desired
            val moved = runCatching { window.history.go(-steps) }
            if (moved.isFailure) {
                degrade("history.go(-$steps)", moved.exceptionOrNull())
                return
            }
            // 注意不预先改 historyDepth：真正的位置等 popstate 回来再对。
            pendingClose = desired
        }
    }

    private fun handlePopState(event: Event) {
        val landed = readVade((event as? PopStateEvent)?.state)
        val previous = historyDepth
        val expected = pendingClose
        pendingClose = null
        historyDepth = landed

        // UI 主动关层的回程：state 正好就是关完之后的深度 → 只对账，不派发。
        if (expected != null && landed == expected) {
            reconcile()
            return
        }
        // 前进（landed 变深）或原地不动：不关层，交给 sync 收敛。
        if (landed >= previous) {
            reconcile()
            return
        }
        // 用户后退：派发一次，由 resync 触发下一轮 sync 把两边重新对齐。
        // 这里**不**直接 reconcile —— 此刻 desired 还是派发前的旧值，
        // 拿它去补条目会把刚关掉的那层又压回去。
        onBack?.invoke()
    }

    /**
     * 刷新后把「state 里写着、内存里却没有」的条目退回去（见文件头「刷新后的残局」）。
     * 期望落点是基座条目，所以用 `pendingClose = 0` 认领这次回程。
     */
    private fun discardStaleEntries() {
        val stale = readVade(window.history.state)
        if (stale <= 0) return
        historyDepth = stale
        val moved = runCatching { window.history.go(-stale) }
        if (moved.isFailure) {
            warnOnce(
                "stale-cleanup",
                "vade: 清理刷新残留的历史条目失败（${moved.exceptionOrNull()?.message}）" +
                    "—— 后退可能要多按几次才离开页面",
            )
            historyDepth = 0
            pendingClose = null
            return
        }
        pendingClose = 0
    }

    private fun degrade(what: String, cause: Throwable?) {
        if (unavailable) return
        unavailable = true
        pendingClose = null
        warnOnce(
            "history-unavailable",
            "vade: 浏览器历史不可用（$what 失败：${cause?.message}）" +
                "—— 后退不再被浮层拦截，Esc 仍可关层",
        )
    }
}

/**
 * 浏览器的「后退」挂点（actual）。
 *
 * `enabled` 由 [BackHost] 传（= `dispatcher.isHandling`），但真正的驱动量是**栈深**：
 * 栈深 d 要求历史里有 d 条自有条目。`depth` 是 `SnapshotStateList.size`，在组合期读它
 * 就是快照读，层入栈/出栈会重算本函数并重启 [LaunchedEffect]。
 *
 * 派发之后还要 `resync++` 逼一次重同步：层的 `onBack` 完全可能不关层（比如只做了一次
 * 「回到列表根」），那样历史已经少了一条，得把「还有层 → 一定还有条目可拦」补回来。
 */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val dispatcher = LocalBackDispatcher.current
    // 没有 provider 的孤立子树（组件预览）里退化成 0/1 的布尔语义。
    val depth = dispatcher?.depth ?: if (enabled) 1 else 0
    val latestOnBack by rememberUpdatedState(onBack)
    var resync by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        BrowserBack.onBack = {
            latestOnBack()
            resync++
        }
        BrowserBack.install()
        onDispose { BrowserBack.onBack = null }
    }

    LaunchedEffect(depth, resync) {
        BrowserBack.sync(depth)
    }
}
