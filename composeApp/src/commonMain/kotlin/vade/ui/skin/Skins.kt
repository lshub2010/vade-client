package vade.ui.skin

import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.material.MaterialSkinEntry
import vade.ui.skin.miuix.MiuixSkinEntry

/**
 * Built-in skin installation. Called once from [vade.ui.App] before the first
 * composition.
 *
 * `material` and `miuix` (U2) are real implementations; `glass` (U3) is still a *placeholder*
 * that reuses material's tokens and shows an "[glass 未安装]" tag, exactly as the task book
 * §T2 requires — the appearance selector can list it as "待安装" instead of crashing, and U3
 * replaces it by calling `SkinRegistry.register("glass") { … }` from its own package.
 */
object Skins {
    private var installed = false

    fun installBuiltIns() {
        if (installed) return
        installed = true
        // Same seam as miuix below: the factory closes over MaterialSkinEntry.dark so the
        // host entry point (`--dark` / `VADE_DARK=1`) selects the dark token set for BOTH
        // built-in skins, not just miuix.
        MaterialSkinEntry.register()
        // U2: the real miuix skin replaces the U1 placeholder. `SkinRegistry.get` prefers
        // `impls` over `placeholders`, so registration is the whole seam — no caller (App,
        // Android, Wasm, desktop) needs to know `miuix` exists, and U1's placeholder entry
        // for `miuix` is gone rather than shadowed, so `placeholderIds()` stays truthful.
        MiuixSkinEntry.register()
        SkinRegistry.registerPlaceholder("glass", MaterialSkin("glass", "[glass 未安装]"))
    }
}
