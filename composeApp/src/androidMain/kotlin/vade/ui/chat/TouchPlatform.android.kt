package vade.ui.chat

/**
 * Android actual：手机/平板以触屏为主输入 —— 没有 hover，编辑入口必须常显、
 * 长按消息是主入口（见 commonMain [isTouchPlatform] 的 KDoc）。
 */
internal actual val isTouchPlatform: Boolean = true
