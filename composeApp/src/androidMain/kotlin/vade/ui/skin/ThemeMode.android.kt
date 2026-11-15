package vade.ui.skin

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * Android 的系统暗色观测（g-dark-follow）：`isSystemInDarkTheme()` 读
 * `LocalConfiguration` 的 uiMode 夜间掩码——即「监听 uiMode 变化
 * （Configuration.UI_MODE_NIGHT_YES）」的 Compose 形态。
 *
 * 即时生效不重建的另一半在 manifest：activity 已声明
 * `android:configChanges="…|uiMode|…"`，所以 `cmd uimode night yes` 走的是
 * `onConfigurationChanged` → Compose 更新 `LocalConfiguration` → 这里返回值翻转
 * → `ThemeController.currentDark()` 的 remember key 变化 → 只换 LocalSkin 的值。
 * 没有 uiMode 声明的应用在同样场景会被系统重建 Activity，整棵树（当前会话、
 * 滚动位置）跟着丢——那是本批要避免的路径。
 */
@Composable
actual fun systemDarkTheme(): Boolean = isSystemInDarkTheme()
