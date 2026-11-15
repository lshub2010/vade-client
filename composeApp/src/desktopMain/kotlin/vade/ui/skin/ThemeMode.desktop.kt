package vade.ui.skin

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/**
 * 桌面的系统暗色观测（g-dark-follow）：委托 CMP 自带的
 * `isSystemInDarkTheme()`（foundation-desktop 的 `_isSystemInDarkTheme` →
 * ui-desktop 的 `SystemTheme` 探测）——能取到就跟系统，取不到返回 false，
 * 保持「亮色」这一既有行为（任务书原文）。
 *
 * 启动期先验不变：`--dark` / `VADE_DARK=1` 由 desktopMain 的入口改写成
 * `ThemeController.override = Dark`（手选暗），不再直写皮肤工厂的 plain var——
 * 那两个 var 现在由 `ThemeController.currentDark()` 在组合期统一同步。
 *
 * 「取不到保持现行为」的兜底不在这里做（Compose 编译器禁止 runCatching 包住
 * @Composable 调用，ILLEGAL_RUN_CATCHING_AROUND_COMPOSABLE）：CMP 的桌面探测在
 * 取不到时返回 false，等价于亮色现行为；若未来某环境抛错，那是一个真实的
 * 探测缺陷，应该暴露出来而不是静默吞掉。
 */
@Composable
actual fun systemDarkTheme(): Boolean = isSystemInDarkTheme()
