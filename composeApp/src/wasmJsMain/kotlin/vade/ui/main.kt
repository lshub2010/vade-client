package vade.ui

import androidx.compose.runtime.key
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.NodeList
import org.w3c.dom.events.Event
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
        // c7-wasm：canvas 建好之后立刻把键盘焦点交给它（见 installCanvasFocus 的注释）。
        installCanvasFocus()
    }

    when {
        fontSwitch.equals("off", ignoreCase = true) -> startApp()
        fontSwitch.isNullOrEmpty() -> installVadeSans(VADE_SANS_URL, startApp)
        else -> installVadeSans(fontSwitch, startApp)
    }
}

/**
 * c7-wasm · 把键盘焦点交给 Compose 的 canvas（否则 `⌘K` / `Esc` / `Tab` 一个都到不了应用）。
 *
 * 根因：CMP 1.12 的 `ComposeViewport` 不在 body 里直接放 canvas —— 它先建一个宿主 `<div>`，
 * canvas 在**该 div 的 shadow root 内**：
 *
 * ```
 * body > div > div.shadowRoot > div > canvas
 * ```
 *
 * 于是 `document.querySelector('canvas')` 恒为 null（shadow 边界挡住了选择器），而 Compose
 * 的键鼠监听挂在 canvas 上、canvas 又带 `tabindex=0`；页面加载后**没有人 focus 它**，
 * 焦点留在 `document.body`，keydown 根本到不了 Compose 的焦点树 —— 表现就是「快捷键全哑」，
 * 用户必须先用鼠标点一下画布。
 *
 * 所以先按普通选择器找（未来 CMP 若改回 light DOM，这段仍然对），再穿透 shadow root 找。
 */
private fun findComposeCanvas(): HTMLElement? {
    val body = document.body ?: return null
    (body.querySelector("canvas") as? HTMLElement)?.let { return it }
    val hosts: NodeList = body.querySelectorAll("div")
    for (i in 0 until hosts.length) {
        val host = hosts.item(i) as? Element ?: continue
        val root = host.shadowRoot ?: continue
        (root.querySelector("canvas") as? HTMLElement)?.let { return it }
    }
    return null
}

/** 找到 canvas 并 focus；返回「焦点是否真的落在 canvas 上」。 */
private fun focusComposeCanvas(): Boolean {
    val canvas = findComposeCanvas() ?: return false
    // canvas 自带 tabindex，这里补一次只是为了不被宿主页面改掉（CMP 版本差异的兜底）。
    if (canvas.tabIndex != 0) canvas.tabIndex = 0
    runCatching { canvas.focus() }
    val active = document.activeElement
    // shadow root 里的元素不是 document.activeElement（那是宿主 div），所以再看 :focus。
    return active == canvas || runCatching { canvas.matches(":focus") }.getOrDefault(false)
}

/** 首次指针交互的兜底监听（只挂一次；用完自己摘掉）。 */
private var pointerFocusFallback: ((Event) -> Unit)? = null

/**
 * 排一串帧等 canvas 出现再 focus —— `ComposeViewport` 是异步建 canvas 的（首帧才 attach），
 * 入口处同步调用时它可能还不存在。
 *
 * 为什么还要指针兜底：部分浏览器/嵌入环境下「没有用户手势的 `element.focus()`」会被忽略
 * （焦点不落到 canvas 上），此时用户第一次点画布就该恢复。兜底**只在页面还没有焦点归属时**
 * 才动手（`activeElement` 是 body/html），绝不从输入框手里抢焦点 —— Compose 的文本输入
 * 靠它自己的隐藏输入元素，抢走会让「第一次点输入框就打不了字」。
 */
private fun installCanvasFocus() {
    var frames = 0
    fun attempt() {
        if (focusComposeCanvas()) return
        frames++
        if (frames < 60) window.requestAnimationFrame { attempt() }
    }
    attempt()

    if (pointerFocusFallback != null) return
    val listener: (Event) -> Unit = { _: Event ->
        pointerFocusFallback?.let { document.removeEventListener("pointerdown", it) }
        pointerFocusFallback = null
        val active = document.activeElement
        val idle = active == null || active == document.body || active == document.documentElement
        if (idle) window.requestAnimationFrame { focusComposeCanvas() }
    }
    pointerFocusFallback = listener
    document.addEventListener("pointerdown", listener)
}
