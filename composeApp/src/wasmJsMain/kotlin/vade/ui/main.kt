package vade.ui

import androidx.compose.runtime.key
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import vade.ui.demo.MotionProbe
import vade.ui.demo.MotionProbeConfig
import vade.ui.demo.ProbeContent
import vade.ui.skin.VadeFonts
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
 * `content=real|pathological` (list fixture; default `real`), and `font=off|<path>`
 * (skip / override the bundled CJK subset — the regression and failure-path switch,
 * see [installVadeSans]).
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

    // 字体：先把随包的 CJK 子集装进 typography，再建 viewport —— 首帧就有字形，
    // 不需要等 gstatic（断网也正常）。`?font=off` 跳过安装（回归/对照用，等于修复前），
    // `?font=<path>` 换一个 URL（验证失败回退路径用）。详见 VadeFontWasm.kt。
    val fontSwitch = value("font")
    val startApp: () -> Unit = {
        ComposeViewport(document.body!!) {
            // 读 generation：字体若晚到，整棵树（含 remember 出来的 skin）被重建，
            // 因为 CMP 不会仅凭字形字节到齐就自己重绘。
            key(VadeFonts.generation) {
                // g-live: same uniform switch as desktop/Android — `?source=real&host=...&
                // port=...&pair_code=...` wires the real core through the shared parser
                // (vade.ui.data.SourceConfig); no query keeps the fixture path byte-for-byte.
                if (probe) MotionProbe() else App(source = vade.ui.data.sessionSourceFromEnvironment())
            }
        }
    }

    when {
        fontSwitch.equals("off", ignoreCase = true) -> startApp()
        fontSwitch.isNullOrEmpty() -> installVadeSans(VADE_SANS_URL, startApp)
        else -> installVadeSans(fontSwitch, startApp)
    }
}
