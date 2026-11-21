package vade.ui.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Android actual of [platformPairingStore]: 应用私有 `SharedPreferences`（`MODE_PRIVATE`）。
 *
 * `Context` 拿不到在 commonMain，所以入口 `MainActivity.onCreate` 在 `setContent` 之前调一次
 * [PairingStoreContext.install]（与外观偏好的 `VadePreferenceContext` 同一个时机、同一个
 * `applicationContext`）。在那之前（理论上不该发生：恢复发生在组合之后）读写一律当
 * 「没有凭据 / 没存上」，绝不抛。
 *
 * 用 `commit()` 而不是 `apply()`：这张 cookie 是**唯一**凭据，配对成功那一刻必须已经落盘 ——
 * 进程被回收时 `apply()` 的异步写可能刚好还没刷出去，那就是又一次「重启即失联」。
 * 写入发生在一次网络往返之后的后台协程上，几百字节的同步写可以接受。
 */
actual fun platformPairingStore(): PairingStore = object : StringPairingStore(
    read = { androidPairingPrefs()?.getString(PAIRING_PREF_KEY, null) },
    write = { text -> androidPairingPrefs()?.edit()?.putString(PAIRING_PREF_KEY, text)?.commit() },
    erase = { androidPairingPrefs()?.edit()?.remove(PAIRING_PREF_KEY)?.commit() },
) {}

/** `MainActivity` 在组合之前安装应用 `Context`（见 KDoc）。 */
object PairingStoreContext {

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext ?: context
    }

    internal fun current(): Context? = appContext
}

private const val PAIRING_PREF_FILE: String = "vade.pairing"
private const val PAIRING_PREF_KEY: String = "credential"

private fun androidPairingPrefs(): SharedPreferences? =
    PairingStoreContext.current()?.getSharedPreferences(PAIRING_PREF_FILE, Context.MODE_PRIVATE)
