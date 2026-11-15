package vade.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * Android actual：抽屉的返回键/返回手势接缝。
 *
 * activity-compose 的 `BackHandler` 注册进 `OnBackPressedDispatcher`，同时覆盖
 * predictive back（targetSdk 33+）与旧的 KEYCODE_BACK 键路径——手机上：
 * 抽屉开着（有选中会话）→ 返回收抽屉、露出底下完好的详情；抽屉收着 → 返回滑出列表。
 */
@Composable
internal actual fun DrawerSystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
