package vade.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * Wasm actual（c7-wasm）：浏览器「后退」= 系统返回。
 *
 * 浏览器里没有系统返回键，此前这端是空实现 —— 唯一的返回入口是 Esc
 * （`AppShell.handleShellKeys` 里「Esc 优先交给返回栈」）。于是**开了浮层按浏览器后退
 * = 直接离开整个应用**（桌面/移动端都是「先关浮层」），这是三端里最刺眼的一条不一致。
 *
 * 做法：把「有没有浮层」映射成浏览器历史里**恰好一条我们自己的条目**，后退键落在这一条
 * 上时只派发返回栈、不离开页面；浮层关干净后把这条自己 pop 掉，用户的下一次后退仍然是
 * 离开页面（栈空不拦截，与 Android `enabled = false` 同义）。
 *
 * 为什么不按栈深 push N 条：(1) 一次关掉多层（如从深层浮层直接回首页）需要
 * `history.go(-N)`，而 `go(-N)` 只投递**一个** popstate，按深度记账的「自己发起」计数器
 * 会永久多记 N-1 次，把用户之后的真实后退吞掉；(2) 浏览器对同一帧内连发的 `back()`
 * 会合并/丢弃。所以这里**恒定只持有一条**，用户每按一次后退就补一条回去 —— 三层浮层
 * 仍然是「按三次后退各自关一层」，语义与 Android 一致，但账目是 0/1，不会错乱。
 *
 * 自己发起的 pop 用 [BrowserBack.selfPops] 计数屏蔽：UI 主动关层时我们会 `history.back()`
 * 收回那条条目，浏览器随后送来的 popstate 属于**我们自己**，若不屏蔽就会被当成用户后退
 * 再派发一次 → 连关两层（双重消费）。
 */

/**
 * 浏览器历史与返回栈之间的桥。单例：整个页面只有一条历史条目归我们所有，
 * 也只有一份 popstate 监听（多份监听 = 一次后退被派发多次）。
 */
private object BrowserBack {

    /** popstate 监听只挂一次；页面生命周期内不再摘（[onBack] 置空即失效）。 */
    private var installed = false

    /** 历史栈里当前有没有「我们自己的那一条」。 */
    private var ownsEntry = false

    /** 已发起、尚未收到 popstate 的自发回退次数（用于把它和用户后退区分开）。 */
    private var selfPops = 0

    /** 派发目标：`BackHost` 传进来的 `dispatcher.dispatch()`。 */
    var onBack: (() -> Unit)? = null

    fun install() {
        if (installed) return
        installed = true
        window.addEventListener("popstate", { _: Event -> handlePopState() })
    }

    private fun handlePopState() {
        // 我们自己 back() 出来的事件：抵消掉，不派发。
        if (selfPops > 0) {
            selfPops--
            return
        }
        // 不是我们压的那一条（首次进入 / 栈空 / 页面里别的 pushState）→ 不拦截。
        if (!ownsEntry) return
        ownsEntry = false
        onBack?.invoke()
    }

    /**
     * 让「我们自己的历史条目」与返回栈状态一致：有层 → 恰好一条；无层 → 零条。
     *
     * `pushState` 在 `file://` 等场景会抛 SecurityError（不透明来源），所以整段用
     * [runCatching] 兜住：抛了就当这一步没发生（返回栈照旧能用 Esc，浏览器后退照旧
     * 离开页面），绝不把异常抛到组合里把首帧打白。
     *
     * `pushState` 在 wasm 目标上标了 `ExperimentalWasmJsInterop`（只有这一个成员要 opt-in，
     * `back()` / `addEventListener` 都不用），所以只给本函数加注，不扩大到整个文件。
     */
    @OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
    fun sync(hasLayers: Boolean) {
        if (hasLayers) {
            if (ownsEntry) return
            ownsEntry = runCatching { window.history.pushState(null, "", null) }.isSuccess
        } else if (ownsEntry) {
            ownsEntry = false
            selfPops++
            val popped = runCatching { window.history.back() }.isSuccess
            // back() 失败（极少）就别留着计数，不然会吞掉用户的下一次真实后退。
            if (!popped) selfPops--
        }
    }
}

/**
 * 浏览器的「后退」挂点（actual）。
 *
 * `enabled` 由 [BackHost] 传（= `dispatcher.isHandling`），但真正的驱动量是**栈深**：
 * 0 → 1 要多压一条历史，1 → 0 要把那条收回来。`depth` 是 `SnapshotStateList.size`，
 * 在组合期读它就是快照读，层入栈/出栈会重算本函数并重启 [LaunchedEffect]。
 *
 * 自己发起的 pop 会立刻 `history.back()` 收回条目，随即到来的 popstate 被计数屏蔽；
 * 之后无论层是否真的关掉，都靠 `resync` 让同步再跑一遍（层的 `onBack` 完全可能不关层，
 * 比如只做了一次「回到列表根」），这样「还有层 → 一定还有一条历史可拦」始终成立。
 */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val dispatcher = LocalBackDispatcher.current
    // 没有 provider 的孤立子树（组件预览）里退化成 0/1 的布尔语义。
    val depth = dispatcher?.depth ?: if (enabled) 1 else 0
    val latestOnBack by rememberUpdatedState(onBack)
    // 派发后逼一次重同步：层的 onBack 可能不关层（只退一层页面），历史条目却已经被吃掉。
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
        BrowserBack.sync(hasLayers = depth > 0)
    }
}
