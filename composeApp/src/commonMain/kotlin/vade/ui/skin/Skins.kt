package vade.ui.skin

import vade.ui.skin.material.MaterialSkin

/**
 * Built-in skin installation. Called once from [vade.ui.App] before the first
 * composition.
 *
 * `material` is a real implementation; `miuix` (U2) and `glass` (U3) are *placeholders*
 * that reuse material's tokens and show an "[… 未安装]" tag, exactly as the task book
 * §T2 requires — the appearance selector can list them as "待安装" instead of crashing,
 * and U2/U3 replace them by calling `SkinRegistry.register("miuix") { MiuixSkin() }`
 * from their own package without touching this file.
 */
object Skins {
    private var installed = false

    fun installBuiltIns() {
        if (installed) return
        installed = true
        SkinRegistry.register("material") { MaterialSkin() }
        SkinRegistry.registerPlaceholder("miuix", MaterialSkin("miuix", "[miuix 未安装]"))
        SkinRegistry.registerPlaceholder("glass", MaterialSkin("glass", "[glass 未安装]"))
    }
}
