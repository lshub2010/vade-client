package vade.ui

import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import vade.ui.data.AndroidSourceEnv
import vade.ui.data.sessionSourceFromEnvironment
import vade.ui.skin.ReduceMotion

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
 *
 * K1 · reduced motion: `onCreate` (and `onResume`, so a change in Developer options lands
 * without a restart) installs [ReduceMotion] from
 * `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` — the scale the system's own animations
 * read. It is installed BEFORE `setContent` so the first frame is already degraded; see
 * `skin/Skin.kt` for the `durationMs` / `travelPx` gate every animation goes through.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        vade.ui.skin.VadePreferenceContext.install(applicationContext)
        super.onCreate(savedInstanceState)
        // K1 · reduced-motion 的三端入口之一：系统「动画程序时长缩放 = 0」⇒ 降级。
        // 必须在 `setContent` **之前**安装，首帧就按降级后的时长走（`ReduceMotion.enabled`
        // 是可观察状态，晚到的安装会先播一帧满速动画）。
        ReduceMotion.install(systemReduceMotion())
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
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
            //
            // P1-3 · 必须 `remember`：源（真源 = 一条 WS + 重连循环）只能建**一次**。
            // 裸调时每次重组都新建一个 `RealSessionSource`，上一个的连接没人关 ——
            // 双连接 + 旧连接在后台无限重试。
            val booted = remember { runCatching { sessionSourceFromEnvironment() } }
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

    /**
     * 每次回到前台重读一次：用户可能刚在开发者选项里把「动画程序时长缩放」关掉再切回来。
     * 只读启动值会让整个进程停在旧判断上（`ReduceMotion` 状态一变，读它的动画立刻降级）。
     */
    override fun onResume() {
        super.onResume()
        ReduceMotion.install(systemReduceMotion())
    }

    /**
     * K1 · Android 的系统级「减少动效」判据：`Settings.Global.ANIMATOR_DURATION_SCALE == 0f`
     * （开发者选项「动画程序时长缩放」拉到关闭）。它不是无障碍里的独立开关，而是系统动画
     * 真正读的那个缩放值 —— 为 0 时系统自己也什么都不播，与桌面 `--reduce-motion`、wasm
     * `prefers-reduced-motion` 是同一个语义。
     *
     * 读失败（受限的定制 ROM、被 hook 的 ContentResolver）按**不降级**处理：默认值就是 1f，
     * 不能因为一次读不到就让整机动画静默消失。
     */
    private fun systemReduceMotion(): Boolean = runCatching {
        Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)
}
