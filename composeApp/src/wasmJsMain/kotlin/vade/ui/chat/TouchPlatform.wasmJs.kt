package vade.ui.chat

import kotlinx.browser.window

/**
 * WasmJs actual：浏览器不区分编译目标，按指针形态判定 —— `(pointer: coarse)` = 触屏
 * （手机/平板浏览器为真），桌面浏览器为假。与 `ThemeMode.wasmJs.kt` 的
 * `prefers-color-scheme` 媒体查询同一套写法；取值在首次访问时定一次。
 */
internal actual val isTouchPlatform: Boolean =
    window.matchMedia("(pointer: coarse)").matches
