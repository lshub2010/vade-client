package vade.ui.data

import android.os.Bundle
import kotlin.concurrent.Volatile

/**
 * Android-only bridge (no expect/actual: only androidMain touches it).
 * `MainActivity` drops its `intent.extras` here BEFORE composition; the platform
 * actual of [rawSourceStrings] reads it — a top-level function has no Activity
 * context, so this object carries it across.
 */
object AndroidSourceEnv {

    @Volatile
    var intentExtras: Any? = null
}

/**
 * Android actual of the raw-source channel: intent extras FIRST — with the BARE keys
 * (`source=real`, `host`, `port`, `pair_code` — e.g.
 * `adb shell am start ... --es source real --es host 10.0.0.5 --es port 18744`),
 * the same env vars as desktop as the fallback. Per-key precedence is
 * [mergeRawStrings]; parsing stays in the shared [SourceConfig]. The keys come from
 * [SourceKeys] — the ONE table the wasm query grammar and the user-facing copy also
 * derive from (g-rescue-text: a hand-written `vade.`-prefixed copy used to drift
 * from exactly these reads).
 */
actual fun rawSourceStrings(): RawSourceStrings {
    val extras = AndroidSourceEnv.intentExtras as? Bundle
    fun extra(key: String): String? = extras?.getString(key)
    val fromExtras = RawSourceStrings(
        source = extra(SourceKeys.SOURCE),
        host = extra(SourceKeys.HOST),
        port = extra(SourceKeys.PORT),
        pairCode = extra(SourceKeys.PAIR_CODE),
    )
    val fromEnv = RawSourceStrings(
        source = System.getenv("VADE_SOURCE"),
        host = System.getenv("VADE_HOST"),
        port = System.getenv("VADE_PORT"),
        pairCode = System.getenv("VADE_PAIR_CODE"),
    )
    return mergeRawStrings(fromExtras, fromEnv)
}
