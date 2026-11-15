package vade.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import vade.ui.destinations.Destination
import vade.ui.chat.ChatPreset
import vade.ui.data.FakeSessionSource
import vade.ui.data.SessionSource
import vade.ui.shell.AppShell
import vade.ui.shell.BackDispatcher
import vade.ui.shell.BackHost
import vade.ui.shell.LocalBackDispatcher
import vade.ui.shell.UiState
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.SkinRegistry
import vade.ui.skin.Skins

/**
 * Composition root, shared by all three targets.
 *
 * The skin is injected through `LocalSkin` (CompositionLocal), so switching it swaps one
 * provider value and never rebuilds the platform container — the requirement behind
 * "皮肤切换运行时生效" (task book §T5). [initialBar] / [initialSkinId] / [initialDest] exist
 * so the desktop screenshot loop can launch straight into a given state without a UI
 * driver; the Android and Wasm entry points call `App()` with defaults.
 */
@Composable
fun App(
    initialBar: BottomBarStyle = BottomBarStyle.Fixed,
    initialSkinId: String = "material",
    initialDest: Destination = Destination.Sessions,
    initialPanelOpen: Boolean = false,
    initialSearchQuery: String? = null,
    /** U1-b: open a session on launch (`--open=`), used by the screenshot loop. */
    initialSessionId: String? = null,
    /** U1-b: seed the detail screen's derived state (`--chat=`); `Live` for the real app. */
    chatPreset: ChatPreset = ChatPreset.Live,
    /**
     * v2 Wave 0 · state-source injection point, threaded straight into [UiState]. The
     * default keeps today's fixture behaviour; a later batch injects the transport-backed
     * source the same way the skin is injected here.
     */
    source: SessionSource = FakeSessionSource(),
) {
    Skins.installBuiltIns()
    // g-pair-first-run：`remember` 以 [source] 为 key —— 首次运行引导卡配对成功后，
    // 入口处（`sessionSourceFromEnvironment()`）的源构造会重读运行期配对桥并交出**带上
    // 配对的新源**，UiState 因此随源重建、loader 重新拉列表（真数据上屏）。桥默认空 =
    // 源永远不变 = 这一行与旧 `remember {}` 行为逐字节相同。
    // DisposableEffect：随源重建时把旧 UiState 的 loader/连接停掉 —— 没配上对的旧源
    // 否则会在后台无限重试。
    val state = remember(source) {
        UiState(
            initialBar = initialBar,
            initialSkinId = initialSkinId,
            initialDest = initialDest,
            initialPanelOpen = initialPanelOpen,
            initialSessionId = initialSessionId,
            chatPreset = chatPreset,
            source = source,
        ).also { created ->
            // U1-c screenshot / deep-link hook: same path as tapping 搜索 in the top bar.
            if (initialSearchQuery != null) {
                created.openSearch()
                created.updateSearchQuery(initialSearchQuery)
            }
        }
    }
    DisposableEffect(source) {
        onDispose { state.dispose() }
    }
    val skin = remember(state.skinId) { SkinRegistry.get(state.skinId) }
    // g-back-handler: the extensible back stack lives at the composition root — one
    // provider for every overlay (search / preview / drawer / model picker to come).
    val backDispatcher = remember { BackDispatcher() }

    CompositionLocalProvider(
        LocalSkin provides skin,
        LocalBackDispatcher provides backDispatcher,
    ) {
        BackHost(backDispatcher) // system-back bridge; stack empty ⇒ not intercepting
        AppShell(state)
    }
}
