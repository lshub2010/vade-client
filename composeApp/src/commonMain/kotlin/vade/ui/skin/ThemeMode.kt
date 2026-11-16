package vade.ui.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vade.ui.skin.material.MaterialSkinEntry
import vade.ui.skin.miuix.MiuixSkinEntry

/**
 * g-dark-follow · 暗色主题的系统入口与手动覆盖。
 *
 * 修复的缺口（审计 `shots/g-audit-gaps`，2026-11-15）：暗色 token 早就在
 * `MaterialTokens.darkColors` / `MiuixTokens.darkColors` 里，但 Android 上
 * `cmd uimode night yes` 后应用区逐像素无变化——暗色在 Android **没有入口**
 * （桌面靠 `--dark` 启动期先验，Web 无任何路径）。本文件把它接上：
 *
 *  - **跟随系统（默认）**：系统观测是平台事——`systemDarkTheme()` 是 expect，
 *    各端 actual 负责（Android 读 uiMode / Web 读 prefers-color-scheme / 桌面读
 *    系统主题）。commonMain 不出现任何平台 API，这是本仓红线。
 *  - **手动覆盖**：跟随系统 / 亮 / 暗三选一，入口在设置页「我的 → 外观 → 主题」，
 *    手选优先于系统。覆盖值与皮肤选择同级，是**进程内状态 + 本地偏好**：选完立刻生效，
 *    同时写进 [AppearancePrefs]（I1），重启后由本对象初始化时恢复；存储不可用就退回
 *    「重启回到跟随系统」这一旧行为，界面不会因此报错。
 *
 * ## 切换为什么「即时生效且不重建整棵树」
 *
 * `App.kt` 的唯一接线点是一行：
 *
 * ```
 * val skin = remember(state.skinId, ThemeController.currentDark()) { SkinRegistry.get(state.skinId) }
 * ```
 *
 * `currentDark()` 在组合期被调用：它读 [override]（Compose 状态）与平台观测值，
 * 把解析结果**先写进两个皮肤工厂闭包读的 plain var**（[MiuixSkinEntry.dark] /
 * [MaterialSkinEntry.dark]——参数求值先于 remember 计算，同一帧内工厂拿到的就是新值），
 * 并返回解析结果作 remember key。主题一变 → key 变 → remember 重算 → `LocalSkin`
 * 换一个 Skin 实例——与「皮肤切换运行时生效」完全同一条缝（任务书 §T5），整棵树
 * 只是重组而不是重建：`UiState`（当前会话 id）、滚动位置、搜索词全部是 `remember`
 * 出来的对象，原样保留。全程没有 Activity/View 重建；Android manifest 早已声明
 * `configChanges=…|uiMode`，所以 `cmd uimode night yes` 也不会走系统重建路径。
 *
 * [systemDark] 是普通 var 而非 Compose 状态：它只在组合期被 [currentDark] 读，
 * 平台 actual 也不需要把它做成可观察源（观测值本身就是组合期的读取结果）。
 */
enum class ThemeMode(val label: String) {
    /** 跟随系统（默认）：Android=uiMode，桌面=系统主题，Web=prefers-color-scheme。 */
    FollowSystem("跟随系统"),

    /** 手选亮色：覆盖系统。 */
    Light("亮色"),

    /** 手选暗色：覆盖系统。 */
    Dark("暗色"),
}

object ThemeController {

    /**
     * 手动覆盖（设置页 · 外观 → 主题写入）。默认 [ThemeMode.FollowSystem]——
     * 这个默认值由 `CommonMainPlatformGuardTest.themeOverrideDefaultsToFollowSystem`
     * 钉住（对象级初值没法在运行时重取，只能钉源码）。
     *
     * I1 · 初值之后紧跟一次「记忆恢复」：上次在设置页选过亮 / 暗的，本对象初始化时就从
     * 本地偏好里取回来（[AppearancePrefs.theme]），所以**重启后第一帧**就是用户选的那一档，
     * 不会先亮一下再翻暗。取不到 / 存的值不认识 ⇒ 保持跟随系统。
     *
     * 直接给这个 var 赋值只改本轮进程；要「选了还记得」，走 [setOverride]。
     */
    var override by mutableStateOf(ThemeMode.FollowSystem)

    init {
        AppearancePrefs.theme()?.let { remembered -> override = remembered }
    }

    /**
     * 设置页 chip 的唯一写入口：改本轮状态 + 落盘。
     *
     * 两件事分开做而不是塞进 setter：`override` 还有个「启动期先验」的用法
     * （桌面入口的 `--dark` 直接赋值），那条路径不该把命令行旗标写进用户偏好里——
     * 截图环跑一次 `--dark` 就永久改掉用户主题是不可接受的。
     */
    fun setOverride(mode: ThemeMode) {
        override = mode
        AppearancePrefs.storeTheme(mode)
    }

    /** 系统暗色观测值：由 [systemDarkTheme] 的各端 actual 在组合期喂进来。 */
    var systemDark: Boolean = false

    /**
     * 纯解析函数（单测面，不依赖组合）：手选优先，未手选跟系统。
     * 桌面测试用它可以确定性验证三态规则，不碰平台探测。
     */
    fun resolveDark(): Boolean = when (override) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.FollowSystem -> systemDark
    }

    /**
     * App.kt 的唯一接线点（见类 KDoc）。@Composable 因为要读平台观测；
     * 两个皮肤工厂闭包读的 plain var 在这里同步——写的是普通 var，不产生快照
     * 失效，组合不会因此循环。
     */
    @Composable
    fun currentDark(): Boolean {
        systemDark = systemDarkTheme()
        val dark = resolveDark()
        MiuixSkinEntry.dark = dark
        MaterialSkinEntry.dark = dark
        return dark
    }
}

/**
 * 平台观测：系统当前是否暗色。expect/actual 是本仓红线的落地方式
 * （平台专有 API 不进 commonMain）：
 *
 *  - Android actual：读 uiMode（`Configuration.UI_MODE_NIGHT_YES` 掩码），
 *    manifest 的 `configChanges` 含 uiMode，所以 `cmd uimode night yes` 即时生效
 *    且不重建 Activity；
 *  - desktop actual：读系统主题，取不到（探测失败/未知平台）返回 false——
 *    保持「亮色」这一既有行为；
 *  - wasmJs actual：读 `prefers-color-scheme`，带 `change` 监听即时生效。
 */
@Composable
expect fun systemDarkTheme(): Boolean
