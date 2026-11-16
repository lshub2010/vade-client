package vade.ui.skin

import android.content.Context
import android.content.SharedPreferences

/**
 * I1 · Android actual：应用私有 SharedPreferences（`MODE_PRIVATE`，不进备份域）。
 *
 * SharedPreferences 需要 `Context`，而 commonMain 的组合根拿不到它（`App()` 是三端同一个
 * 函数）。所以入口 `MainActivity.onCreate` 在 `setContent` 之前调一次
 * [VadePreferenceContext.install]；在那之前（理论上不该发生）读写一律当「没有存过 / 没存上」，
 * 绝不抛异常。
 *
 * 每次读写重新取 `getSharedPreferences(...)`：它本身就是进程内缓存的单例，重复调用不产生
 * 额外 IO，换来的是「安装时机与首次读取时机解耦」。
 */
object VadePreferenceContext {

    @Volatile
    private var appContext: Context? = null

    fun install(context: Context) {
        appContext = context.applicationContext ?: context
    }

    internal fun current(): Context? = appContext
}

actual fun platformPreferenceStore(): PreferenceStore = AndroidPreferenceStore()

private class AndroidPreferenceStore : PreferenceStore {

    private fun prefs(): SharedPreferences? =
        VadePreferenceContext.current()?.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs()?.getString(key, null)

    override fun write(key: String, value: String?) {
        val prefs = prefs() ?: return
        val editor = prefs.edit()
        if (value == null) editor.remove(key) else editor.putString(key, value)
        // apply()：内存态立即生效，落盘异步——外观偏好不值得阻塞 UI 线程。
        editor.apply()
    }

    private companion object {
        const val FILE_NAME: String = "vade.appearance"
    }
}
