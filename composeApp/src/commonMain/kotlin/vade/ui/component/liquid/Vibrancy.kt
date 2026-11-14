// Ported from KernelSU (https://github.com/tiann/KernelSU),
// manager/app/src/main/java/me/weishu/kernelsu/ui/component/liquid/Vibrancy.kt,
// Copyright (c) 2023-2026 weishu & contributors, Apache License 2.0.
// Original: Kyant0/AndroidLiquidGlass via the compose-miuix-ui example
// `IosLiquidGlassNavigationBar`. Same Apache License 2.0 terms; see README.md.

package vade.ui.component.liquid

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

/**
 * "Vibrancy" — colour purity boost applied to the blurred backdrop so the glass
 * does not wash the content out. Implemented purely with miuix-blur
 * [colorControls] (saturation 1.5×), which is cross-platform on every target
 * miuix-blur ships; on platforms without runtime shader support `colorControls`
 * no-ops and the surface simply reads as unboosted blur.
 */
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
