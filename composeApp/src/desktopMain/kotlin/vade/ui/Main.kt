package vade.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import vade.ui.demo.MotionProbe
import vade.ui.demo.MotionProbeConfig
import vade.ui.demo.ProbeContent
import vade.ui.motion.MotionBlurKernel
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import kotlin.system.exitProcess

/**
 * Desktop entry point.
 *
 * Flags (used by `ui/tools/capture-window.ps1`, harmless otherwise):
 *   --bar=fixed|floating|blur      bottom-bar form to start in
 *   --dest=sessions|activity|capabilities|me
 *   --size=466x893                 window size in dp (default 466×893, phone-ish)
 *   --panel=open                   start with the ≥1200dp right panel expanded
 *   --search=<query>               U1-c: open the search overlay/palette with this query
 *                                  (bare `--search` opens it empty). Same code path as the
 *                                  top-bar 搜索 button, so screenshots show a real overlay.
 *   --motion-probe                 §11 motion-blur probe (vade.ui.demo.MotionProbe) instead of
 *                                  the app shell. Runs as a *separate* branch so none of the
 *                                  flags above change behaviour.
 *   --freeze-blur=<dp>             with --motion-probe: pin the blur radius at <dp> instead of
 *                                  deriving it from scroll velocity. This is what makes a
 *                                  static screenshot of a *moving* effect honest.
 *   --blur=off                     with --motion-probe: force radius 0 (the §11 wasm-off path,
 *                                  and the baseline half of the frame-time comparison).
 *   --autoscroll                   with --motion-probe: drive the list with a sawtooth so the
 *                                  live velocity→radius loop can be photographed.
 *   --kernel=box|triangle|gaussian with --motion-probe: pin the blur kernel. Default is the
 *                                  shipped one (see MotionBlur.kt); this flag exists so the
 *                                  A/B in shots/motion/RESULT.md is reproducible from one build.
 *   --content=real|pathological    with --motion-probe: list fixture. Default is `real` (the
 *                                  shipping-like mixed content); `pathological` is the original
 *                                  8×-identical MOTION BLUR pattern that makes the beat visible.
 *   --fps                          with --motion-probe: measure 5 s of frame times, print
 *                                  `FPS_RESULT …`, then exit. Exit code 0.
 *
 * Default size is deliberately phone-shaped: the U1-a acceptance screenshots are the three
 * bottom-bar forms at 466×893, and a phone-shaped window is the only way to see the
 * `<600dp` container on a desktop display.
 */
fun main(args: Array<String>) {
    // §11 probe branch. Kept above everything else so the existing flag parsing is untouched.
    if (args.contains("--motion-probe")) {
        runMotionProbe(args)
        return
    }

    val bar = args.flag("--bar=")?.let(::parseBar) ?: BottomBarStyle.Fixed
    val dest = args.flag("--dest=")?.let { Destination.fromId(it) } ?: Destination.Sessions
    val (width, height) = args.flag("--size=")?.let(::parseSize) ?: (466 to 893)
    val panelOpen = args.flag("--panel=")?.equals("open", ignoreCase = true) == true
    // U1-c: `--search=AGP` opens the overlay with a preset query; bare `--search` opens it
    // empty. Empty string is meaningful here, so this does not go through `flag()`.
    val searchPreset: String? = args
        .firstOrNull { it == "--search" || it.startsWith("--search=") }
        ?.let { if (it == "--search") "" else it.removePrefix("--search=") }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Vade · UI 骨架",
            state = rememberWindowState(width = width.dp, height = height.dp),
        ) {
            App(
                initialBar = bar,
                initialDest = dest,
                initialPanelOpen = panelOpen,
                initialSearchQuery = searchPreset,
            )
        }
    }
}

/**
 * `--motion-probe` window. Default 900×760: the list needs room for several 88 dp rows *and*
 * the HUD, otherwise a 10 dp blur has nothing left to smear.
 */
private fun runMotionProbe(args: Array<String>) {
    MotionProbeConfig.freezeBlurDp = args.flag("--freeze-blur=")?.toFloatOrNull()
    MotionProbeConfig.blurEnabled = args.flag("--blur=")?.equals("off", ignoreCase = true) != true
    MotionProbeConfig.autoScroll = args.contains("--autoscroll")
    MotionProbeConfig.measureFps = args.contains("--fps")
    // Only the three documented names switch the kernel; anything else (including a typo) keeps
    // the shipped default, so a screenshot can never silently prove the wrong kernel.
    when (args.flag("--kernel=")?.lowercase()) {
        "box" -> MotionProbeConfig.kernel = MotionBlurKernel.Box
        "triangle" -> MotionProbeConfig.kernel = MotionBlurKernel.Triangle
        "gaussian" -> MotionProbeConfig.kernel = MotionBlurKernel.Gaussian
    }
    when (args.flag("--content=")?.lowercase()) {
        "pathological" -> MotionProbeConfig.content = ProbeContent.Pathological
        "real" -> MotionProbeConfig.content = ProbeContent.Real
    }
    val (width, height) = args.flag("--size=")?.let(::parseSize) ?: (900 to 760)

    println(
        "MOTION_PROBE freeze=${MotionProbeConfig.freezeBlurDp ?: "-"} " +
            "blur=${if (MotionProbeConfig.blurEnabled) "on" else "off"} " +
            "kernel=${MotionProbeConfig.kernel.name.lowercase()} " +
            "autoscroll=${MotionProbeConfig.autoScroll} fps=${MotionProbeConfig.measureFps} " +
            "size=${width}x$height",
    )

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Vade · §11 motion blur probe",
            state = rememberWindowState(width = width.dp, height = height.dp),
        ) {
            MotionProbe(
                onMeasurementDone = { line ->
                    println("DONE $line")
                    // `--fps` is a batch mode: the caller wants stdout, not a window to close.
                    // Exiting from the composition thread is fine here — it is a probe process.
                    Thread {
                        Thread.sleep(250)
                        exitProcess(0)
                    }.start()
                },
            )
        }
    }
}

private fun Array<String>.flag(prefix: String): String? =
    firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()?.takeIf { it.isNotEmpty() }

private fun parseBar(raw: String): BottomBarStyle = when (raw.lowercase()) {
    "fixed" -> BottomBarStyle.Fixed
    "floating" -> BottomBarStyle.Floating
    "blur", "floatingblur", "floating+blur" -> BottomBarStyle.FloatingBlur
    else -> BottomBarStyle.Fixed
}

private fun parseSize(raw: String): Pair<Int, Int>? {
    val parts = raw.lowercase().split('x')
    if (parts.size != 2) return null
    val w = parts[0].trim().toIntOrNull() ?: return null
    val h = parts[1].trim().toIntOrNull() ?: return null
    if (w < 320 || h < 320) return null
    return w to h
}
