package vade.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier

/**
 * Android entry point — same `App()` as desktop and wasm; only the host container differs.
 *
 * Safe areas (c-inset): API 35+ forces edge-to-edge — `setDecorFitsSystemWindows(true)` is
 * ignored there, the window draws under the system bars, and nothing pads the content, so
 * the gesture pill covered the fixed bottom bar's labels (real-device finding, 2026-11-14).
 * `enableEdgeToEdge()` opts in on every API level, which makes the insets uniform and puts
 * the app in charge of them:
 *
 *  - the whole app is padded below the status bar ([statusBarsPadding]) and above the IME
 *    ([imePadding]) HERE. This file only runs on Android, so the desktop and wasm layout is
 *    untouched by construction;
 *  - the bottom bars read `WindowInsets.navigationBars` themselves in `BottomBar.kt` — the
 *    multiplatform read is 0 on desktop/wasm, so their geometry is unchanged too.
 *
 * Both system bars are forced to the LIGHT style (dark icons): the manifest theme and the
 * default material skin are both light, while `SystemBarStyle.auto` follows the SYSTEM's
 * night mode and would put light icons over the light canvas on a dark-mode device.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding(),
            ) {
                App()
            }
        }
    }
}
