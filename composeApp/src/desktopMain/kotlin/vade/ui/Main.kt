package vade.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import vade.ui.chat.ChatPreset
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle

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
 *
 * Default size is deliberately phone-shaped: the U1-a acceptance screenshots are the three
 * bottom-bar forms at 466×893, and a phone-shaped window is the only way to see the
 * `<600dp` container on a desktop display.
 */
fun main(args: Array<String>) {
    val bar = args.flag("--bar=")?.let(::parseBar) ?: BottomBarStyle.Fixed
    val dest = args.flag("--dest=")?.let { Destination.fromId(it) } ?: Destination.Sessions
    val (width, height) = args.flag("--size=")?.let(::parseSize) ?: (466 to 893)
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
            title = "Vade · UI 骨架",
            state = rememberWindowState(width = width.dp, height = height.dp),
        ) {
            App(
                initialBar = bar,
                initialDest = dest,
                initialPanelOpen = panelOpen,
                initialSearchQuery = searchPreset,
                initialSessionId = sessionId,
                chatPreset = chatPreset,
            )
        }
    }
}

private fun parseChatPreset(raw: String): ChatPreset = when (raw.lowercase()) {
    "tools", "expanded", "expandedtools" -> ChatPreset.ExpandedTools
    "queue" -> ChatPreset.Queue
    "compose", "composer" -> ChatPreset.Composer
    "preview", "image" -> ChatPreset.Preview
    "stopped", "interrupted" -> ChatPreset.Interrupted
    else -> ChatPreset.Live
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
