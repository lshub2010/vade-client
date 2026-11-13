package vade.ui.skin

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * C-09 companion · the registry U2/U3 register into so a skin can actually be
 * selected at runtime. Written by U1, populated by everyone.
 *
 * `impls` holds real implementations; `placeholders` holds token-compatible stand-ins
 * for skins that are not installed yet (U1 registers `miuix` / `glass` this way so the
 * appearance selector can show "安装" instead of crashing). [installed] deliberately
 * reports only the real ones — the settings page lists `installed()` plus the
 * placeholders as "待安装".
 */
object SkinRegistry {
    private val impls = mutableMapOf<String, () -> Skin>()
    private val placeholders = mutableMapOf<String, Skin>()

    fun register(id: String, factory: () -> Skin) {
        impls[id] = factory
    }

    fun registerPlaceholder(id: String, skin: Skin) {
        placeholders[id] = skin
    }

    fun get(id: String): Skin =
        impls[id]?.invoke()
            ?: placeholders[id]
            ?: error("skin '$id' not installed and has no placeholder")

    fun installed(): Set<String> = impls.keys.toSet()

    fun placeholderIds(): Set<String> = placeholders.keys.toSet()

    fun known(): List<String> = (impls.keys + placeholders.keys).sorted()

    /** Test / hot-reload hook: forget everything. */
    fun clear() {
        impls.clear()
        placeholders.clear()
    }
}

/** The current skin. Switching a skin swaps this Local only — never the Activity/
 *  View tree (task book §T5: "切换只换 Local"). */
val LocalSkin = staticCompositionLocalOf<Skin> {
    error("LocalSkin not provided — wrap the tree in CompositionLocalProvider(LocalSkin provides …)")
}
