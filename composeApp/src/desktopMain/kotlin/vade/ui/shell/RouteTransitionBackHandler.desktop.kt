package vade.ui.shell

import androidx.compose.runtime.Composable

/**
 * Desktop actual：桌面没有系统返回键/手势（Esc 与顶栏返回见
 * `AppShell.handleShellKeys` / `sessionDetailBack`），此接缝为空实现。
 */
@Composable
internal actual fun DrawerSystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // 桌面没有系统返回：不注册任何东西（参数仅满足 expect 签名）。
    @Suppress("UNUSED_EXPRESSION") enabled
    @Suppress("UNUSED_EXPRESSION") onBack
}
