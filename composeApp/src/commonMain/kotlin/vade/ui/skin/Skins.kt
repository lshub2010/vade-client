package vade.ui.skin

import vade.ui.skin.material.MaterialSkinEntry
import vade.ui.skin.miuix.MiuixSkinEntry

/**
 * Built-in skin installation. Called once from [vade.ui.App] before the first
 * composition (and again, harmlessly, from the entries that need the registry to be
 * populated before composition starts — the guard is idempotent).
 *
 * `material` and `miuix` (U2) are real implementations. **`glass` is not installed** and
 * deliberately has no entry here at all:
 *
 *  - I2 · it used to be registered as a placeholder backed by `MaterialSkin`, i.e. selecting
 *    「玻璃」 repainted nothing (same pixels, different id) and could fall back to the light
 *    token set in dark mode. That is a skin that lies about itself. The appearance selector
 *    now lists it as 「玻璃（待安装）」 and refuses the selection until U3 registers the real
 *    thing via `SkinRegistry.register("glass") { … }` — at which point the chip becomes
 *    selectable by itself (its label and enabled state are derived from [SkinRegistry]).
 *  - Nothing else may stand in for it: a placeholder would keep the "material 换 id" defect
 *    alive for anyone who reached the id through a flag or a stale preference.
 */
object Skins {

    /** 出厂皮肤：`SkinRegistry` 主键，也是所有「存的值不可用」路径的收敛点。 */
    const val DEFAULT_SKIN: String = "material"

    private var installed = false

    fun installBuiltIns() {
        if (installed) return
        installed = true
        // Same seam as miuix below: the factory closes over MaterialSkinEntry.dark so the
        // host entry point (`--dark` / `VADE_DARK=1`) selects the dark token set for BOTH
        // built-in skins, not just miuix.
        MaterialSkinEntry.register()
        // U2: the real miuix skin replaces the U1 placeholder. `SkinRegistry.get` prefers
        // `impls`, and there is no `miuix` placeholder left to shadow, so `installed()`
        // stays the single truthful answer to 「这个皮肤能选吗」.
        MiuixSkinEntry.register()
    }

    /**
     * I1 · 启动时要用的皮肤：上次选过、**且真的装在注册表里**才采纳。
     *
     * 两个失效条件都收敛到 [DEFAULT_SKIN]，而不是让它们抛：老版本存过 `glass`（那时它有
     * 占位实现）、手改过偏好文件、或命令行给了个不存在的 id —— 这三种情况都不该让应用
     * 起不来。
     *
     * 先 `installBuiltIns()` 再查注册表：这个函数会被用作 `App()` 的**默认实参**，而默认
     * 实参在函数体之前求值——不在这里装，注册表此刻还是空的，任何记忆值都会被误判成
     * 「不可用」。
     */
    fun bootSkinId(): String {
        installBuiltIns()
        val remembered = AppearancePrefs.skinId() ?: return DEFAULT_SKIN
        return remembered.takeIf { it in SkinRegistry.installed() } ?: DEFAULT_SKIN
    }

    /** I1 · 启动时的底栏形态；没存过 = 贴底（与 `App()` 的历史默认一致）。 */
    fun bootBottomBar(): BottomBarStyle {
        installBuiltIns()
        return AppearancePrefs.bottomBar() ?: BottomBarStyle.Fixed
    }

    /** 测试 / 截图环钩子：让下一次 [installBuiltIns] 重新注册（注册表 `clear()` 之后用）。 */
    internal fun resetInstallFlagForTest() {
        installed = false
    }
}
