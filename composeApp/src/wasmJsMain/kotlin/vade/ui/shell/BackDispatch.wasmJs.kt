package vade.ui.shell

import androidx.compose.runtime.Composable

/**
 * Wasm actual：浏览器里没有系统返回 —— 桥为空实现。
 *
 * 返回行为由 Esc 驱动：`AppShell.handleShellKeys` 的「Esc 优先交给返回栈」路由
 * （栈顶浮层先关，栈空回落既有语义）。这里故意不吞任何事件。
 */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // no system back in the browser
}
