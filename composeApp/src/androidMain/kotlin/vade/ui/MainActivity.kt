package vade.ui

import android.content.res.Configuration
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import vade.ui.data.AndroidSourceEnv
import vade.ui.data.sessionSourceFromEnvironment
import vade.ui.skin.ThemeController

/**
 * Android entry point — same `App()` as desktop and wasm; only the host container differs.
 *
 * Safe areas (c-inset): API 35+ forces edge-to-edge — `setDecorFitsSystemWindows(true)` is
 * ignored there, the window draws under the system bars, and nothing pads the content, so
 * the gesture pill covered the fixed bottom bar's labels (real-device finding, 2026-11-14).
 * Opting in on every API level makes the insets uniform and puts the app in charge of them:
 *
 *  - the whole app is padded below the status bar ([statusBarsPadding]) and above the IME
 *    ([imePadding]) HERE. This file only runs on Android, so the desktop and wasm layout is
 *    untouched by construction;
 *  - the bottom bars read `WindowInsets.navigationBars` themselves in `BottomBar.kt` — the
 *    multiplatform read is 0 on desktop/wasm, so their geometry is unchanged too.
 *
 * System-bar icons follow the **app** theme (c6-insets · audit L3), not the OS night mode.
 * On a dark theme the icons must be light, on a light theme dark; the old code hard-wired
 * the light style, so a dark device drew near-black status-bar icons on near-black chrome
 * and a hand-picked dark theme kept dark icons over the dark canvas. `SystemBarStyle.auto`
 * is not the answer either — it only reads the system uiMode, so 「设置 → 外观 → 暗色」
 * would keep the OS colours.
 *
 * The single source of truth is `ThemeController.currentDark()` (hand-picked wins, else
 * follow the system). It reads Compose state, so a theme change recomposes and the
 * [LaunchedEffect] re-applies both bars — no Activity rebuild (the manifest already
 * declares `configChanges=…|uiMode`). The pre-composition call in `onCreate` exists only so
 * the **first** frame is already edge-to-edge with the right icon colour (otherwise API < 35
 * would draw one frame non-edge-to-edge and visibly jump).
 *
 * The manifest theme is day/night (`Theme.Vade`, values + values-night), so pre-composition
 * chrome — including the cold-start window background — matches the system: dark devices
 * get dark chrome instead of the white band.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        vade.ui.skin.VadePreferenceContext.install(applicationContext)
        super.onCreate(savedInstanceState)
        // 首帧之前的一次解析：手选覆盖看 ThemeController，系统档读本次配置的 uiMode。
        // 组合期 `currentDark()` 会用 Compose 的观测值覆盖 systemDark，两者同一条规则。
        ThemeController.systemDark = systemNightMode()
        applySystemBars(dark = ThemeController.resolveDark())
        setContent {
            // L3 · theme override 是 Compose 状态：这里读一次即建立重组依赖，
            // 解析后的暗色值作 key ——「跟随系统」下的 uiMode 切换与手选亮/暗都会重设。
            val isDark = ThemeController.currentDark()
            LaunchedEffect(isDark) { applySystemBars(dark = isDark) }
            // g-live: install the launch intent's extras BEFORE composition so the
            // shared switch (vade.ui.data.SourceConfig) can read them — extras beat
            // env vars per key (see SourceConfig.android.kt). Comment below the
            // class kdoc: the switch is the BARE extra keys `source=real` +
            // `host`/`port` (+ `pair_code`) — the same `SourceKeys` table the
            // self-rescue card copy derives from — e.g. from
            // `adb shell am start --es source real ...`.
            AndroidSourceEnv.intentExtras = intent?.extras
            // P1-4 · 配置解析失败（`--es port 8443x` 之类）= 一张说清原因的屏，
            // 不是启动即闪退。这里兜住解析，`App()` 只接收合法的源。
            val booted = runCatching { sessionSourceFromEnvironment() }
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .imePadding(),
            ) {
                // Same uniform switch as desktop/wasm: FakeSessionSource() fixtures
                // unless the intent (or env) says `real` — App()'s own default is
                // untouched, so no intent at all keeps the pre-g-live behaviour.
                val source = booted.getOrNull()
                if (source == null) {
                    ConfigErrorScreen(booted.exceptionOrNull() ?: IllegalStateException())
                } else {
                    App(source = source)
                }
            }
        }
    }

    /** 本次配置的夜间掩码（不依赖组合，可在 `onCreate` 里读）。 */
    private fun systemNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

/**
 * 系统栏的唯一切换点：edge-to-edge + 两条系统栏的图标风格。
 *
 * 暗色主题 → 浅色图标（[SystemBarStyle.dark]），亮色主题 → 深色图标
 * （[SystemBarStyle.light]）。scrim 一律透明：窗口背景由主题的 `windowBackground`
 * （与皮肤 canvas 同色）铺满，系统再垫一层半透明背板只会在暗色下露出更浅的一条带子。
 */
private fun ComponentActivity.applySystemBars(dark: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = vadeSystemBarStyle(dark),
        navigationBarStyle = vadeSystemBarStyle(dark),
    )
}

private fun vadeSystemBarStyle(dark: Boolean): SystemBarStyle =
    if (dark) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    }
