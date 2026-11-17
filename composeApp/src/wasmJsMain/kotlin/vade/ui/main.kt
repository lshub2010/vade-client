package vade.ui

import androidx.compose.runtime.key
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import vade.ui.demo.MotionProbe
import vade.ui.demo.MotionProbeConfig
import vade.ui.demo.ProbeContent
import vade.ui.skin.ReduceMotion
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
    // K1 · reduced-motion 的三端入口之一：系统 / 浏览器的 `prefers-reduced-motion: reduce`。
    // 在 `ComposeViewport` **之前**安装，首帧就按降级时长渲染（不能等组合期再读：那时
    // 开场动画已经起跑了）。与 `--motion-probe` 分支无关，探针也照常拿到这个值。
    ReduceMotion.install(prefersReducedMotion())

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
    // P1-4 · `?port=8443x` 之类的配置错误 = 一块说清原因的错误屏，
    // 而不是浏览器里一片白（未捕获异常）。解析在入口兜住，`App()` 只接收合法的源。
    val booted = runCatching { vade.ui.data.sessionSourceFromEnvironment() }
    val startApp: () -> Unit = {
        ComposeViewport(document.body!!) {
            // 读 generation：字体若晚到，整棵树（含 remember 出来的 skin）被重建，
            // 因为 CMP 不会仅凭字形字节到齐就自己重绘。
            key(VadeFonts.generation) {
                // g-live: same uniform switch as desktop/Android — `?source=real&host=...&
                // port=...&pair_code=...` wires the real core through the shared parser
                // (vade.ui.data.SourceConfig); no query keeps the fixture path byte-for-byte.
                if (probe) {
                    MotionProbe()
                } else {
                    val source = booted.getOrNull()
                    if (source == null) {
                        ConfigErrorScreen(booted.exceptionOrNull() ?: IllegalStateException())
                    } else {
                        App(source = source)
                    }
                }
            }
        }
    }

    when {
        fontSwitch.equals("off", ignoreCase = true) -> startApp()
        fontSwitch.isNullOrEmpty() -> installVadeSans(VADE_SANS_URL, startApp)
        else -> installVadeSans(fontSwitch, startApp)
    }
}

/**
 * K1 · 系统「减少动效」：`(prefers-reduced-motion: reduce)` ⇒ `true`。
 *
 * 与 `ThemeMode.wasmJs.kt` 读 `prefers-color-scheme` 是同一套 `matchMedia`：浏览器里
 * 系统动画偏好是**媒体查询**，CMP 1.12 的 wasm 端也没有 `isSystemInDarkTheme` 那类
 * 现成入口，所以这端自己读。只读一次、在组合前安装（[ReduceMotion.enabled] 是可观察
 * 状态，首帧即降级）；页面开着时改系统设置需要刷新才生效 —— 这端没有组合期的 listener，
 * 不为一个「重开页面即解决」的场景引入全局事件源。
 *
 * 读失败（老浏览器、被沙箱限制的 media 查询）按**不降级**处理：默认播放动效，不能让
 * 一次读不到就静默关掉所有动画。
 */
private fun prefersReducedMotion(): Boolean =
    runCatching { window.matchMedia("(prefers-reduced-motion: reduce)").matches }
        .getOrDefault(false)
