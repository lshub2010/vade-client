package vade.ui.shell

import androidx.compose.runtime.Composable

/**
 * Wasm actual：浏览器没有系统返回接缝（返回语义由 Esc / 顶栏返回承担，见
 * `AppShell.handleShellKeys`），此接缝为空实现。
 */
@Composable
internal actual fun DrawerSystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // 浏览器没有系统返回接缝：不注册任何东西（参数仅满足 expect 签名）。
    @Suppress("UNUSED_EXPRESSION") enabled
    @Suppress("UNUSED_EXPRESSION") onBack
}
