package vade.ui.skin

import java.util.prefs.Preferences as JvmPreferences

/**
 * I1 · 桌面 actual：用户级偏好节点（`java.util.prefs`）。
 *
 * 选它而不是自己写文件：Windows / macOS / Linux 各自落在系统认为「用户偏好」的位置，
 * 不需要本仓发明第二条路径，也不需要管目录创建与并发。节点名带产品前缀，卸载后清理
 * 也认得出来。
 *
 * 每个键一次 `put`/`remove`，随后 `flush()`：prefs 的写默认是异步落盘的，用户点完
 * chip 立刻关窗口再开，异步那一路可能还没写到磁盘——三个键的 flush 代价可以忽略，
 * 「选了没记住」却是本批要修的缺陷本身。
 */
actual fun platformPreferenceStore(): PreferenceStore = DesktopPreferenceStore()

private class DesktopPreferenceStore : PreferenceStore {

    private fun node(): JvmPreferences = JvmPreferences.userRoot().node(NODE)

    override fun read(key: String): String? = node().get(key, null)

    override fun write(key: String, value: String?) {
        val prefs = node()
        if (value == null) prefs.remove(key) else prefs.put(key, value)
        prefs.flush()
    }

    private companion object {
        const val NODE: String = "vade-ui/client"
    }
}
