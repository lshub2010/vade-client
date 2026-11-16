package vade.ui.skin

/**
 * I1 · 外观三项（皮肤 / 主题 / 底栏）的本地持久化窄口。
 *
 * 三端各一个 [platformPreferenceStore] actual：桌面写用户级偏好节点、Android 写应用私有
 * SharedPreferences、Web 写 localStorage。三端只回答「某个键的字符串值」，**不认识**皮肤 /
 * 主题 / 底栏这些概念——键名与编解码集中在 [AppearancePrefs] 一处，三端不可能各自漂移
 * （这正是 `data/SourceConfig.kt` 对「一次解析、三端共享」的同一纪律）。
 *
 * 存储层不承诺成功：读失败 / 写失败一律按「没有存过 / 没存上」处理。外观偏好丢了最多回到
 * 出厂外观，不该让启动路径崩掉——所以 [Preferences] 里那两处 `runCatching` 是刻意的，
 * 不是偷懒。写入失败也不会回滚界面状态：用户这一轮的选择照样生效，只是下次启动不记得。
 */
interface PreferenceStore {

    /** 该键当前的值；没存过 = null。 */
    fun read(key: String): String?

    /** 写入该键；传 null = 删除。 */
    fun write(key: String, value: String?)
}

/** 平台存储。由各端 actual 提供，commonMain 只认这个接口。 */
expect fun platformPreferenceStore(): PreferenceStore

/**
 * 进程内实现：测试注入用，也是平台存储不可用时的兜底。
 *
 * `snapshot()` 让测试能直接断言「真的写了哪几对键值」，而不是只能读回来猜。
 */
class InMemoryPreferenceStore(initial: Map<String, String> = emptyMap()) : PreferenceStore {
    private val values = initial.toMutableMap()

    fun snapshot(): Map<String, String> = values.toMap()

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/**
 * 当前生效的存储。生产 = [platformPreferenceStore]；测试在组合屏幕之前换成
 * [InMemoryPreferenceStore]，免得把断言写进开发机的真实偏好文件里。
 */
object Preferences {

    var store: PreferenceStore = platformPreferenceStore()
        internal set

    fun read(key: String): String? = runCatching { store.read(key) }.getOrNull()

    fun write(key: String, value: String?) {
        runCatching { store.write(key, value) }
    }
}

/**
 * 外观三项的键与编解码（唯一一处）。
 *
 * 存储里只放稳定的短标识（`miuix` / `dark` / `floating-blur`），不放用户可见文案：文案会改，
 * 键不会。解码遇到不认识的值（旧版本写的、手改的）一律当「没存过」——绝不猜一个近似值，
 * 那样用户会看到自己没选过的外观。
 */
object AppearancePrefs {

    const val KEY_SKIN: String = "vade.appearance.skin"
    const val KEY_THEME: String = "vade.appearance.theme"
    const val KEY_BOTTOM_BAR: String = "vade.appearance.bottomBar"

    private const val THEME_FOLLOW = "follow"
    private const val THEME_LIGHT = "light"
    private const val THEME_DARK = "dark"

    private const val BAR_FIXED = "fixed"
    private const val BAR_FLOATING = "floating"
    private const val BAR_FLOATING_BLUR = "floating-blur"

    /** 上次选的皮肤 id（`SkinRegistry` 主键）；没存过 = null。是否可选用由调用方判定。 */
    fun skinId(): String? = Preferences.read(KEY_SKIN)?.trim()?.takeIf { it.isNotEmpty() }

    fun storeSkin(id: String) {
        Preferences.write(KEY_SKIN, id)
    }

    fun theme(): ThemeMode? = when (Preferences.read(KEY_THEME)) {
        THEME_FOLLOW -> ThemeMode.FollowSystem
        THEME_LIGHT -> ThemeMode.Light
        THEME_DARK -> ThemeMode.Dark
        else -> null
    }

    fun storeTheme(mode: ThemeMode) {
        Preferences.write(
            KEY_THEME,
            when (mode) {
                ThemeMode.FollowSystem -> THEME_FOLLOW
                ThemeMode.Light -> THEME_LIGHT
                ThemeMode.Dark -> THEME_DARK
            },
        )
    }

    fun bottomBar(): BottomBarStyle? = when (Preferences.read(KEY_BOTTOM_BAR)) {
        BAR_FIXED -> BottomBarStyle.Fixed
        BAR_FLOATING -> BottomBarStyle.Floating
        BAR_FLOATING_BLUR -> BottomBarStyle.FloatingBlur
        else -> null
    }

    fun storeBottomBar(style: BottomBarStyle) {
        Preferences.write(
            KEY_BOTTOM_BAR,
            when (style) {
                BottomBarStyle.Fixed -> BAR_FIXED
                BottomBarStyle.Floating -> BAR_FLOATING
                BottomBarStyle.FloatingBlur -> BAR_FLOATING_BLUR
            },
        )
    }
}
