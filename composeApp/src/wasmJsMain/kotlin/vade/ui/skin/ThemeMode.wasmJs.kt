package vade.ui.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.browser.window
import org.w3c.dom.MediaQueryList
import org.w3c.dom.events.Event

/**
 * Web(Wasm) 的系统暗色观测（g-dark-follow）：读 `prefers-color-scheme`。
 *
 * CMP 1.12 的 foundation-wasm-js 没有实现 `isSystemInDarkTheme`（klib 里 0 命中，
 * 2026-11-15 实查），所以这端必须自己接 `matchMedia`。三个要点：
 *
 *  - `media` 与 [prefersDark] 都是顶层初始化：首次访问本文件时同步读一次
 *    `matchMedia(...).matches`，因此**首帧**拿到的是真实系统值——不会先亮一帧再翻暗；
 *  - 监听走 `MediaQueryList` 的 `change` 事件（组合期 [DisposableEffect] 挂上，
 *    离开组合摘掉），系统主题在运行中切换时 `prefersDark` 状态翻转 →
 *    `ThemeController.currentDark()` 的 remember key 变化 → 只换 LocalSkin；
 *  - 状态初值来自初始化而非组合期写入，避免了「写状态发生在组合中」的坏形态。
 */
private val media: MediaQueryList = window.matchMedia("(prefers-color-scheme: dark)")

private var prefersDark by mutableStateOf(media.matches)

@Composable
actual fun systemDarkTheme(): Boolean {
    DisposableEffect(media) {
        val listener: (Event) -> Unit = { prefersDark = media.matches }
        media.addEventListener("change", listener)
        onDispose { media.removeEventListener("change", listener) }
    }
    return prefersDark
}
