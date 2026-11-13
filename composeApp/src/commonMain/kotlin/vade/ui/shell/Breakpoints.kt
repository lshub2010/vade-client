package vade.ui.shell

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The four breakpoints of FR-8.76 / task book §2.1. Widths are *container* widths in dp
 * (`BoxWithConstraints.maxWidth`), not physical pixels — on desktop a 1280 px window at
 * 1.0 density is 1280 dp; at 1.5 it is ~853 dp.
 *
 * | width        | container                                  | panes            |
 * |--------------|--------------------------------------------|------------------|
 * | < 600 dp     | bottom bar, 4 tabs                         | single           |
 * | 600–839 dp   | rail 80 dp (≥720 dp may do list+detail)    | single / two     |
 * | 840–1199 dp  | rail 72 dp + list 300 dp                   | two              |
 * | ≥ 1200 dp    | two panes + collapsible right panel        | two + panel      |
 *
 * `miuix + floating` keeps the bottom bar at 600–839 dp on purpose (FR-8.20 exception,
 * U2 acceptance); U1 does not change that semantic and its `material` skin does move
 * to the rail.
 */
enum class Tier(val label: String) {
    Compact("<600dp · 底栏单栏"),
    Medium("600–839dp · NavigationRail 80dp"),
    Expanded("840–1199dp · rail 72 + 列表 300 双栏"),
    Large("≥1200dp · 双栏 + 右面板"),
}

@Immutable
data class ShellMetrics(
    val tier: Tier,
    val useRail: Boolean,
    val railWidth: Dp,
    val twoPane: Boolean,
    val listWidth: Dp,
    val hasRightPanel: Boolean,
) {
    val containerLabel: String
        get() = when {
            !useRail -> "BottomBar · 单栏"
            twoPane && hasRightPanel -> "NavigationRail ${railWidth.value.toInt()}dp · 双栏 + 右面板"
            twoPane -> "NavigationRail ${railWidth.value.toInt()}dp · 双栏"
            else -> "NavigationRail ${railWidth.value.toInt()}dp · 单栏"
        }
}

fun metricsFor(width: Dp): ShellMetrics = when {
    width < 600.dp -> ShellMetrics(Tier.Compact, useRail = false, railWidth = 0.dp, twoPane = false, listWidth = 0.dp, hasRightPanel = false)
    width < 720.dp -> ShellMetrics(Tier.Medium, useRail = true, railWidth = 80.dp, twoPane = false, listWidth = 0.dp, hasRightPanel = false)
    width < 840.dp -> ShellMetrics(Tier.Medium, useRail = true, railWidth = 80.dp, twoPane = true, listWidth = 280.dp, hasRightPanel = false)
    width < 1200.dp -> ShellMetrics(Tier.Expanded, useRail = true, railWidth = 72.dp, twoPane = true, listWidth = 300.dp, hasRightPanel = false)
    else -> ShellMetrics(Tier.Large, useRail = true, railWidth = 72.dp, twoPane = true, listWidth = 300.dp, hasRightPanel = true)
}
