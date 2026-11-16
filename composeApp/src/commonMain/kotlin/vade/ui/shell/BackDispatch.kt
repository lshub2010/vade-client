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
