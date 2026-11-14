package vade.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import vade.ui.demo.MotionProbe
import vade.ui.demo.MotionProbeConfig
import vade.ui.demo.ProbeContent
import vade.ui.skin.glass.MotionBlurKernel

/**
 * Wasm entry point. ComposeViewport creates its own canvas inside the given DOM node, so
 * `index.html` only needs a body (same approach as spike/miuix/composeApp/src/wasmJsMain).
 *
 * §11 probe switch: `index.html?motion-probe&freeze=10` renders [MotionProbe] instead of the
 * app shell, mirroring the desktop flags (`--motion-probe --freeze-blur=10`). With no query
 * string the wasm build is the ordinary app, so the existing deployment target is unchanged.
 *
 * Query keys: `freeze=<dp>` (pins the radius), `blur=off`, `autoscroll`, `fps`,
 * `kernel=box|triangle|gaussian` (pins the kernel for the A/B; default is the shipped one),
 * `content=real|pathological` (list fixture; default `real`).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val params = window.location.search.removePrefix("?")
        .split('&')
        .filter { it.isNotEmpty() }
    val probe = params.contains("motion-probe")

    fun value(key: String): String? = params
        .firstOrNull { it == key || it.startsWith("$key=") }
        ?.let { if (it == key) "" else it.substringAfter('=') }

    if (probe) {
        MotionProbeConfig.freezeBlurDp = value("freeze")?.toFloatOrNull()
        MotionProbeConfig.blurEnabled = value("blur")?.equals("off", ignoreCase = true) != true
        MotionProbeConfig.autoScroll = params.contains("autoscroll")
        MotionProbeConfig.measureFps = params.contains("fps")
        // Only the documented names switch the kernel; a typo keeps the shipped default.
        when (value("kernel")?.lowercase()) {
            "box" -> MotionProbeConfig.kernel = MotionBlurKernel.Box
            "triangle" -> MotionProbeConfig.kernel = MotionBlurKernel.Triangle
            "gaussian" -> MotionProbeConfig.kernel = MotionBlurKernel.Gaussian
        }
        when (value("content")?.lowercase()) {
            "pathological" -> MotionProbeConfig.content = ProbeContent.Pathological
            "real" -> MotionProbeConfig.content = ProbeContent.Real
        }
    }

    ComposeViewport(document.body!!) {
        if (probe) MotionProbe() else App()
    }
}
