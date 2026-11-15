package vade.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Android actual：真正的系统返回键 / 返回手势挂点。
 *
 * `androidx.activity.compose.BackHandler` 挂靠 `ComponentActivity` 的
 * `OnBackPressedDispatcher`（`MainActivity` 是 `ComponentActivity`，`setContent`
 * 已把 owner 放进组合）—— `enabled = true` 时拦截并回调，`false` 时不注册、
 * 返回走系统默认（允许退出 App）。
 *
 * 为什么不用 CMP 的 `androidx.compose.ui.backhandler.BackHandler`：其 artifact
 * `org.jetbrains.compose.ui:ui-backhandler` 不在当前依赖闭包里，加依赖要动
 * build.gradle.kts（g-back-handler 禁改）；activity-compose 已在用，零新增依赖。
 */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
