package vade.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import vade.ui.destinations.Destination
import vade.ui.chat.ChatPreset
import vade.ui.shell.AppShell
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
) {
    Skins.installBuiltIns()
    val state = remember {
        UiState(
            initialBar = initialBar,
            initialSkinId = initialSkinId,
            initialDest = initialDest,
            initialPanelOpen = initialPanelOpen,
            initialSessionId = initialSessionId,
            chatPreset = chatPreset,
        ).also { created ->
            // U1-c screenshot / deep-link hook: same path as tapping 搜索 in the top bar.
            if (initialSearchQuery != null) {
                created.openSearch()
                created.updateSearchQuery(initialSearchQuery)
            }
        }
    }
    val skin = remember(state.skinId) { SkinRegistry.get(state.skinId) }

    CompositionLocalProvider(LocalSkin provides skin) {
        AppShell(state)
    }
}
