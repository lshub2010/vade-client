package vade.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray
import org.w3c.fetch.Response
import vade.ui.skin.VadeFonts

/**
 * Web font bootstrap: read the bundled CJK subset and install it as the typography family
 * **before** the first composition.
 *
 * Why this exists (root cause: `shots/g-font-recon/RECON.md`):
 *  - Web has no system fonts, so `FontFamily.Default` resolves to no typeface at all; every
 *    glyph has to be handed to Skia as bytes.
 *  - CMP's own fallback (`androidx.compose.ui.platform.NotoFontDownloader`) fetches Noto
 *    subsets from `https://fonts.gstatic.com/s/` at runtime, one request per 25 KB subset,
 *    101 subsets for Noto Sans SC. It is `internal` and its base URL is `internal` too: no
 *    flag, no `CompositionLocal`, no public constructor reaches it.
 *  - Consequence: cold start paints tofu until the CDN answers (~1 s of tofu measured here),
 *    and **offline it is tofu forever** — the download throws and is retried, never satisfied.
 *
 * So the only supported lever is "the codepoints already have a family": we hand Compose the
 * bytes ourselves. The subset is GB2312 (7 445 codepoints, ~1.0 MB woff2) and ships next to
 * `index.html`; see `composeApp/src/wasmJsMain/resources/fonts/README.md` for provenance.
 *
 * Ordering matters: [installVadeSans] is called **before** `ComposeViewport` exists, so the
 * very first frame already has glyphs. CMP does *not* repaint when glyph bytes merely arrive
 * later (measured: 30 s of byte-identical tofu frames in `shots/g-font-bundle2/RESULT.md` §5),
 * which is why the entry point also keys the tree on [VadeFonts.generation].
 *
 * Failure is not fatal: if the file is missing the app still starts (with `FontFamily.Default`,
 * i.e. exactly the pre-fix behaviour) and says so on the console — a broken font must never
 * leave a blank page.
 */

/** Bundled subset, resolved relative to `index.html` (the webpack output root). */
internal const val VADE_SANS_URL = "fonts/vade-sans-sc.woff2"

/** Identity of the loaded face inside Compose's font cache (the family *inside* the file is
 *  `Vade Sans SC`; this string only has to be unique and stable). */
internal const val VADE_SANS_FAMILY = "Vade Sans SC"

/**
 * Fetch [url] (browser-relative), then install it and call [onDone] — from the XHR callback,
 * never synchronously, so the caller can always build the same "start the app" closure.
 *
 * [onDone] runs exactly once, on success and on every failure path.
 */
internal fun installVadeSans(
    url: String = VADE_SANS_URL,
    onDone: () -> Unit,
) {
    var finished = false
    fun finish(installed: Boolean, why: String = "") {
        if (finished) return
        finished = true
        if (!installed) {
            println(
                "[vade-font] FAILED to install '$url'$why — falling back to FontFamily.Default " +
                    "(CJK will go through CMP's gstatic fallback, i.e. tofu when offline)",
            )
        }
        onDone()
    }

    fun install(buffer: ArrayBuffer) {
        try {
            val bytes = Int8Array(buffer).toByteArray()
            VadeFonts.installSans(FontFamily(Font(VADE_SANS_FAMILY, bytes)))
            println("[vade-font] installed '$url' (${bytes.size} B, family $VADE_SANS_FAMILY)")
            finish(true)
        } catch (t: Throwable) {
            finish(false, ": parse error $t")
        }
    }

    try {
        // A browser-relative fetch: same origin as index.html, so this works offline too.
        // `Promise.then` is typed `<S : JsAny?>` on wasm, hence the explicit `null` returns.
        window.fetch(url).then { response: Response ->
            if (response.ok) {
                response.arrayBuffer().then { buffer: ArrayBuffer ->
                    install(buffer)
                    null
                }
            } else {
                finish(false, ": HTTP ${response.status}")
            }
            null
        }
    } catch (t: Throwable) {
        finish(false, ": $t")
    }
}
