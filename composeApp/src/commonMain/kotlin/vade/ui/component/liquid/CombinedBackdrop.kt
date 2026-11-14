// Ported from KernelSU (https://github.com/tiann/KernelSU),
// manager/app/src/main/java/me/weishu/kernelsu/ui/component/liquid/CombinedBackdrop.kt,
// Copyright (c) 2023-2026 weishu & contributors, Apache License 2.0.
// Original: Kyant0/AndroidLiquidGlass via the compose-miuix-ui example
// `IosLiquidGlassNavigationBar`. Same Apache License 2.0 terms; see README.md.

package vade.ui.component.liquid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * Two backdrops drawn into the same surface: the pill's indicator samples the
 * app content backdrop *and* the accent-tinted tab copy, so the refraction
 * under the finger smears both.
 */
@Stable
class CombinedBackdrop(
    val first: Backdrop,
    val second: Backdrop,
) : Backdrop {

    override val isCoordinatesDependent: Boolean = first.isCoordinatesDependent || second.isCoordinatesDependent

    override val offsetResidualX: Float get() = first.offsetResidualX
    override val offsetResidualY: Float get() = first.offsetResidualY

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        with(first) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
        with(second) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}

@Composable
fun rememberCombinedBackdrop(first: Backdrop, second: Backdrop): Backdrop =
    remember(first, second) { CombinedBackdrop(first, second) }
