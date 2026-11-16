package vade.ui.chat

/**
 * Desktop actual：鼠标平台有 hover —— 编辑铅笔悬停出现，长按（按住鼠标）也可进入编辑。
 * 触屏常显的那条分支在这里不渲染（见 commonMain [isTouchPlatform] 的 KDoc）。
 */
internal actual val isTouchPlatform: Boolean = false
