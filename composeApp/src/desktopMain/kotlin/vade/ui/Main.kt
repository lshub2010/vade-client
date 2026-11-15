package vade.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import vade.ui.chat.ChatPreset
import vade.ui.demo.MotionProbe
import vade.ui.demo.MotionProbeConfig
import vade.ui.demo.ProbeContent
import vade.ui.icon.IconGallery
import vade.ui.skin.glass.MotionBlurKernel
import vade.ui.destinations.Destination
import vade.ui.data.sessionSourceFromEnvironment
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
 *   --open=<sessionId>             U1-b: open this session's detail (e.g. `s-rfc0004`),
 *                                  the same path as tapping a list row.
 *   --chat=live|tools|queue|compose|preview|stopped
 *                                  U1-b: seed the detail screen's *derived* state so a still
 *                                  frame can show streaming / expanded cards / multi-line
 *                                  composer / the fullscreen image preview / an interrupted
 *                                  turn. Only derived state changes; the RPC shapes do not.
 *
 * Sound only with Gradle 9.6.1 (README build matrix); `vade-env.sh` puts 8.10.2 first, so
 * prefix `PATH=/d/Hermes/tools/gradle-9.6.1/bin:$PATH` when building.
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
 *   --gallery                      w2-icons: the IconGallery grid (every VadeIcons vector on
 *                                  the §2 dark canvas) instead of the app shell. `--light`
 *                                  flips it to the §2 light canvas. Screenshot-only branch.
 *   --light                        with --gallery: use the light token set.
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
    // w2-icons: IconGallery acceptance page (--gallery), dark §2 canvas unless --light.
    val gallery = args.has("--gallery")
    val galleryLight = args.has("--light")
    // U2: skin selection. `--skin=miuix` (or `VADE_SKIN=miuix`, so a shell can pin it without
    // touching the app) selects the Miuix skin; `--dark` / `VADE_DARK=1` picks its dark tokens.
    // The flag starts the real `App()` — not the showcase — so the shots are the product.
    val skinId = args.flag("--skin=") ?: System.getenv("VADE_SKIN") ?: "material"
    val dark = args.has("--dark") || System.getenv("VADE_DARK") == "1"
    // g-dark-follow · the `--dark` prior becomes a ThemeController override (手选暗) instead of
    // writing the skin factories' plain vars directly: without `--dark` the app now follows the
    // system theme via the desktop actual (能取到就跟，取不到=false 保持亮色现行为). The factory
    // vars (MiuixSkinEntry.dark / MaterialSkinEntry.dark) are synced by ThemeController in
    // composition (App.kt's remember key) — single source, same seam as the settings page.
    vade.ui.skin.ThemeController.override =
        if (dark) vade.ui.skin.ThemeMode.Dark else vade.ui.skin.ThemeMode.FollowSystem
    // Kept out of `App()` on purpose: U1-b has no chat page yet, so the message-bubble /
    // tool-card / input-bar shots come from the skin's own page (see MiuixShowcase.kt).
    val showcase = args.has("--showcase")
    val panelOpen = args.flag("--panel=")?.equals("open", ignoreCase = true) == true
    // U1-c: `--search=AGP` opens the overlay with a preset query; bare `--search` opens it
    // empty. Empty string is meaningful here, so this does not go through `flag()`.
    val searchPreset: String? = args
        .firstOrNull { it == "--search" || it.startsWith("--search=") }
        ?.let { if (it == "--search") "" else it.removePrefix("--search=") }
    // U1-b: session detail + derived-state seed for the still frames.
    val sessionId = args.flag("--open=")
    val chatPreset = args.flag("--chat=")?.let(::parseChatPreset) ?: ChatPreset.Live

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = WINDOW_TITLE,
            state = rememberWindowState(width = width.dp, height = height.dp),
        ) {
            if (gallery) {
                // w2-icons: the icon vocabulary page. Colours are the documented §2 tokens
                // (dark 0x0E0E10/0xEDEDF0/0x9A9AA3, light 0xF7F7F8/0x17171A/0x6B6B73) — the
                // gallery is a shape-verification page, not a product surface, so it does not
                // pull a skin. See shots/w2-icons/RESULT.md.
                IconGallery(
                    tint = if (galleryLight) Color(0xFF17171A) else Color(0xFFEDEDF0),
                    labelColor = if (galleryLight) Color(0xFF6B6B73) else Color(0xFF9A9AA3),
                    background = if (galleryLight) Color(0xFFF7F7F8) else Color(0xFF0E0E10),
                )
            } else if (showcase) {
                vade.ui.skin.miuix.MiuixShowcasePage(
                    skin = vade.ui.skin.miuix.MiuixSkin(dark),
                    bar = bar,
                    showApproval = args.has("--approval"),
                )
            } else {
                App(
                    initialBar = bar,
                    initialSkinId = skinId,
                    initialDest = dest,
                    initialPanelOpen = panelOpen,
                    initialSearchQuery = searchPreset,
                    initialSessionId = sessionId,
                    chatPreset = chatPreset,
                    // g-live: the source switch is UNIFORM across the three targets —
                    // this entry reads VADE_SOURCE/VADE_HOST/VADE_PORT(+VADE_PAIR_CODE),
                    // Android reads intent extras first (same names + vade.* extras),
                    // wasm reads ?source=real&host=...&port=...; one shared parser
                    // (vade.ui.data.SourceConfig) builds the same SourceSpec everywhere.
                    // The UI DOES read the source (w-wire): a real spec drives the
                    // loader's async path — Loading/Ready/Failed instead of fixtures,
                    // real core rows when the pair-code bootstrap connects, a
                    // classified failure banner when it cannot (see
                    // shots/g-live/PLAYBOOK.md). The fake path stays byte-for-byte.
                    source = sessionSourceFromEnvironment(),
                )
            }
        }
    }
}

/**
 * 桌面窗口标题。
 *
 * g-first-run · P5：首次真实验收抓到它在窗口上写的是「Vade · UI 骨架」（`capture-window.ps1`
 * 的匹配行原文），那是脚手架期的占位符，Alpha 用户看到的是「骨架」两个字。产品名按仓库
 * README 第 3 行：Vade Agent Runtime（中文「偕行」），所以标题是 `Vade · 偕行`。
 *
 * 提成常量是为了可被测试钉住（`WindowTitleTest`）：标题一变，「不再出现『UI 骨架』」这条
 * 回归才有唯一的断言点。`tools/capture-window.ps1` 按**窗口类 + 尺寸**匹配（`SunAwt*`、
 * >300×300），不按标题，所以改标题不会打断截图链路。
 */
internal const val WINDOW_TITLE: String = "Vade · 偕行"

private fun Array<String>.has(flag: String): Boolean = any { it == flag }

private fun parseChatPreset(raw: String): ChatPreset = when (raw.lowercase()) {
    "tools", "expanded", "expandedtools" -> ChatPreset.ExpandedTools
    "queue" -> ChatPreset.Queue
    "compose", "composer" -> ChatPreset.Composer
    "preview", "image" -> ChatPreset.Preview
    "stopped", "interrupted" -> ChatPreset.Interrupted
    else -> ChatPreset.Live
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
    }}

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
