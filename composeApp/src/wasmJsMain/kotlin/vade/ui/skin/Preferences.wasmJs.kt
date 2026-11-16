package vade.ui.skin

import kotlinx.browser.localStorage
import org.w3c.dom.Storage

/**
 * I1 · Web(Wasm) actual：浏览器 `localStorage`。
 *
 * 键带 `vade.` 前缀，同一个 origin 上的其它应用不会撞名。浏览器可能**拒绝**访问 storage
 * （无痕模式 / 站点数据被拦 / 配额满），`getItem` / `setItem` 会直接抛——所以本实现的
 * 兜底不是「返回默认值」而是「当这次读写没发生」：调用方（[Preferences]）已经有一层
 * `runCatching`，这里再包一层，是为了连 `localStorage` 这个全局本身取不到的情况也覆盖。
 */
actual fun platformPreferenceStore(): PreferenceStore = WebPreferenceStore()

private class WebPreferenceStore : PreferenceStore {

    private fun storageOrNull(): Storage? = runCatching { localStorage }.getOrNull()

    override fun read(key: String): String? {
        val storage = storageOrNull() ?: return null
        return runCatching { storage.getItem(key) }.getOrNull()
    }

    override fun write(key: String, value: String?) {
        val storage = storageOrNull() ?: return
        runCatching {
            if (value == null) {
                storage.removeItem(key)
            } else {
                storage.setItem(key, value)
            }
        }
    }
}
