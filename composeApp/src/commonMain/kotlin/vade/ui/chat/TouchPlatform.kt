package vade.ui.chat

/**
 * c2-nav · 「当前平台有没有 hover 通道」的平台判据 —— 编辑重发入口的可达性依赖它。
 *
 * 桌面有鼠标悬停：编辑铅笔只在悬停时出现（不打扰阅读），长按消息是第二个入口。
 * 触屏没有 hover：铅笔必须**常显**，长按消息是主入口 —— 否则编辑重发在手机上不可达
 * （旧实现只认 hover，Android 上那个铅笔永远不出现）。
 *
 * 用 expect/actual 而不是运行时猜：Android = 触屏；desktop = 鼠标；wasmJs 读浏览器的
 * `(pointer: coarse)`（手机浏览器为真、桌面浏览器为假），与 `ThemeMode.wasmJs.kt` 读
 * `prefers-color-scheme` 是同一套写法。wasm 的取值在首次访问时定一次 —— 输入设备形态
 * 在一次会话里不会变。
 */
internal expect val isTouchPlatform: Boolean
