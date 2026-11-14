package vade.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.destinations.ActivityScreen
import vade.ui.destinations.CapabilitiesScreen
import vade.ui.destinations.Destination
import vade.ui.destinations.ProfileScreen
import vade.ui.destinations.SessionDetailScreen
import vade.ui.destinations.SessionsScreen
import vade.ui.glass.GlassScope
import vade.ui.glass.asGlassSource
import vade.ui.glass.rememberGlassState
import vade.ui.search.SearchHost
import vade.ui.skin.BtnStyle
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.NavItem
import vade.ui.skin.glass.LocalGlassHazeState
import vade.ui.component.liquid.captureGlassBackdrop
import vade.ui.component.liquid.rememberGlassBackdrop

/**
 * The whole shell: four destinations, four breakpoints, a glass top bar and (on phones) a
 * glass bottom bar.
 *
 * Layout rules (task book §2.1 / FR-8.76, with the v2 glass structure of §5):
 *  - `<600dp`  bottom bar, single pane; opening a session pushes it full screen and the
 *              bar is **not composed** (D88).
 *  - `600–839` NavigationRail 80 dp; from 720 dp the list stays beside the detail.
 *  - `840–1199` rail 72 dp + 300 dp list + detail.
 *  - `≥1200`   the same two panes plus a collapsible right panel (default collapsed).
 *
 * v2 glass structure (docs/visual-language.md §5; haze 2.1.0 semantics):
 *  - One shared `HazeState` at the root ([GlassScope] / [rememberGlassState]); the backdrop
 *    Row registers on it via [asGlassSource], and every `GlassSurface` (top bar, bottom
 *    bar, search overlay) reads the same ambient state (`LocalGlassHazeState`).
 *  - **Glass layers are siblings of the backdrop source, never descendants** — haze 2.1.0
 *    refuses "effect node drawing an ancestor source" (verbatim `HazeEffectNode` error).
 *    That is why the bars are aligned over the content from *outside* the source Row
 *    instead of living inside the content Column.
 *  - The floating bars are edge-to-edge glass: scrollable screens take [TOP_BAR_ZONE] /
 *    [BOTTOM_BAR_ZONE] as *content* insets (not container padding), so rows really scroll
 *    behind the glass — without that there is nothing behind the bar to blur.
 *  - `⌘K` / `Ctrl+K` opens the search palette; `↑ ↓ Enter Esc` drive it. The right panel
 *    toggle is a button so the shell is usable (and screenshottable) without U1-g.
 */
@Composable
fun AppShell(state: UiState) {
    // The whole shortcut story is one root preview handler: it sees keys before any child
    // (the search field included), so `⌘K` opens the palette even while typing elsewhere.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    // v2 §5: one shared HazeState per window — backdrop and all glass layers sample it.
    val glass = rememberGlassState()
    GlassScope(state = glass) {
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event -> handleShellKeys(event, state) }
                .focusRequester(rootFocus)
                .focusable(),
        ) {
            ShellBody(state)
            // U1-c: phone glass overlay card below 600dp, ⌘K palette above it. Also a
            // glass layer — sibling of the backdrop source.
            SearchHost(state)
        }
    }
}

/** Top-bar zone: 8dp top inset + 56dp glass bar + 8dp gap. Scrollables inset by this. */
private val TOP_BAR_ZONE = 72.dp

/** Bottom-bar zone: 64dp glass bar + 12dp bottom inset + 12dp gap (floating form). */
private val BOTTOM_BAR_ZONE = 88.dp

@Composable
private fun ShellBody(state: UiState) {
    val skin = LocalSkin.current
    val glass = LocalGlassHazeState.current
    // w2-bottombar: the app-content capture the floating pill blurs/refracts (miuix-blur).
    // Two pipelines coexist on purpose: haze drives the glass top bar + search overlay,
    // miuix-blur drives the KSU-style pill (it has lens/refraction/highlight that haze
    // lacks). The content area is the single capture surface for the pill.
    val contentBackdrop = rememberGlassBackdrop()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = metricsFor(maxWidth)
        val session = state.selectedSession
        val detailOpen = state.dest == Destination.Sessions && session != null && !metrics.twoPane

        // v2 bottom bar: compact single-pane only, never while a detail is pushed (D88).
        val showBar = !metrics.useRail && !detailOpen
        val contentInsets = PaddingValues(
            top = TOP_BAR_ZONE,
            bottom = if (showBar) BOTTOM_BAR_ZONE else 0.dp,
        )

        // --- backdrop: the ONLY haze source. Everything blurrable lives in here. ---
        Row(
            Modifier
                .fillMaxSize()
                .background(skin.colors.canvas)
                .asGlassSource(glass),
        ) {
            if (metrics.useRail) {
                NavRail(
                    width = metrics.railWidth,
                    selected = state.dest,
                    badgeOf = state::badgeOf,
                    onSelect = state::select,
                )
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (detailOpen) {
                    // Pushed detail: the glass top bar (title + back) floats above; the page
                    // starts below the bar zone. Its own composer reaches the bottom edge.
                    Box(Modifier.fillMaxSize().padding(top = TOP_BAR_ZONE)) {
                        SessionDetailScreen(
                            session = session,
                            preset = state.chatPreset,
                            animate = state.chatPreset == vade.ui.chat.ChatPreset.Live,
                            showTitle = false,
                        )
                    }
                } else {
                    DestinationHost(metrics = metrics, state = state, contentInsets = contentInsets)
                }
            }

            if (metrics.hasRightPanel && state.panelOpen) {
                VerticalRule()
                RightPanel(onClose = state::togglePanel)
            }
        }

        // --- glass top bar: sibling of the source (haze cannot capture its own layer). ---
        // Inset start by the rail so the bar floats over the content column only, and end
        // by the right panel when it is open (the panel is a full-height backdrop column).
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(
                    start = if (metrics.useRail) metrics.railWidth + 12.dp else 12.dp,
                    end = if (metrics.hasRightPanel && state.panelOpen) 12.dp + 320.dp + 1.dp else 12.dp,
                    top = 8.dp,
                ),
        ) {
            if (detailOpen) {
                TopBar(
                    title = session.title,
                    subtitle = "会话详情 · 消息流 / 工具卡为假数据",
                    leading = { TopBarButton("←", onClick = { state.back() }, contentDescription = "返回") },
                )
            } else {
                TopBar(
                    title = state.dest.label,
                    subtitle = buildString {
                        append(metrics.tier.label)
                        if (state.dest == Destination.Sessions) append(" · ${state.totalSessions} 个会话")
                    },
                    actions = {
                        if (state.dest == Destination.Sessions) {
                            // U1-c: phones reach search here; desktop also has ⌘K.
                            TopBarButton("⌕", onClick = { state.openSearch() }, contentDescription = "搜索")
                            TopBarButton("✎", onClick = { state.newSession() }, contentDescription = "新建会话")
                        }
                        if (metrics.hasRightPanel) {
                            TopBarButton(
                                glyph = if (state.panelOpen) "▸" else "◂",
                                onClick = { state.togglePanel() },
                                contentDescription = if (state.panelOpen) "收起右面板" else "展开右面板",
                            )
                        }
                    },
                )
            }
        }

        // --- glass bottom bar: same sibling rule; floating capsule or docked by style. ---
        if (showBar) {
            val floating = state.barStyle != BottomBarStyle.Fixed
            val barInsets = if (floating) {
                PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp)
            } else {
                PaddingValues(0.dp)
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(barInsets),
            ) {
                GlassNavBar(
                    items = navItems(),
                    selected = state.dest.ordinal,
                    onSelect = { index -> state.select(Destination.entries[index]) },
                    style = state.barStyle,
                )
            }
        }
    }
}

@Composable
private fun DestinationHost(
    metrics: ShellMetrics,
    state: UiState,
    contentInsets: PaddingValues,
) {
    when (state.dest) {
        Destination.Sessions -> {
            if (metrics.twoPane) {
                Row(Modifier.fillMaxSize()) {
                    SessionsScreen(
                        groups = state.groups(),
                        selectedId = state.selectedSessionId,
                        twoPane = true,
                        onOpen = { state.open(it.id) },
                        modifier = Modifier.width(metrics.listWidth),
                        contentPadding = PaddingValues(top = TOP_BAR_ZONE),
                    )
                    VerticalRule()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.fillMaxSize().padding(top = TOP_BAR_ZONE)) {
                            SessionDetailScreen(
                                session = state.selectedSession,
                                preset = state.chatPreset,
                                animate = state.chatPreset == vade.ui.chat.ChatPreset.Live,
                            )
                        }
                    }
                }
            } else if (state.selectedSessionId != null) {
                SessionDetailScreen(
                    session = state.selectedSession,
                    preset = state.chatPreset,
                    animate = state.chatPreset == vade.ui.chat.ChatPreset.Live,
                    // The pushed-detail top bar above already carries this title.
                    showTitle = false,
                )
            } else {
                SessionsScreen(
                    groups = state.groups(),
                    selectedId = null,
                    twoPane = false,
                    onOpen = { state.open(it.id) },
                    contentPadding = contentInsets,
                )
            }
        }

        Destination.Activity -> ActivityScreen(contentPadding = contentInsets)
        Destination.Capabilities -> CapabilitiesScreen(contentPadding = contentInsets)
        Destination.Me -> ProfileScreen(
            skinId = state.skinId,
            barStyle = state.barStyle,
            containerLabel = metrics.containerLabel,
            onSkinChange = state::setSkin,
            onBarChange = state::setBar,
            contentPadding = contentInsets,
        )
    }
}

@Composable
private fun RightPanel(onClose: () -> Unit) {
    val skin = LocalSkin.current
    Column(Modifier.width(320.dp).fillMaxHeight().background(skin.colors.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                UiText("右面板", size = 13, weight = FontWeight.SemiBold)
                UiText("模块页面宿主（U1-h · 等 C-19）", size = 10, color = skin.colors.onSurfaceVariant)
            }
            skin.Button("×", onClick = onClose, style = BtnStyle.Text)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.hairline))
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UiText("暂无模块页面", size = 12, weight = FontWeight.SemiBold)
            UiText(
                text = "C-19 冻结后，模块的 ui.pages[] 会在这里逐条列出；无页面则不显示入口（FR-8.79）。",
                size = 11,
                color = skin.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            UiText(
                text = "默认收起；本切片用顶栏 ◂ 按钮开合，⌘\\ 属 U1-g。开合不改变会话滚动位置。",
                size = 10,
                color = skin.colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VerticalRule() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(LocalSkin.current.colors.hairline))
}

/** Bottom-bar rows: the four destinations, in contract order (FR-8.77). */
private fun navItems(): List<BottomBarItem> = Destination.entries.map {
    BottomBarItem(id = it.id, label = it.label, icon = null, glyph = it.glyph)
}

/**
 * U1-c keyboard routing. One handler on the root preview pass, so it wins over whatever
 * child currently holds focus (the search field included) without stealing ordinary
 * characters — unhandled keys return `false` and reach the focused field normally.
 *
 *  - `⌘K` / `Ctrl+K`: toggle the command palette (desktop shape of search).
 *  - `↑` / `↓`: move the selection (wraps), `Enter`: open the selected session.
 *  - `Esc`: close the palette; when it is closed, leave an open session detail (FR-8.72's
 *    back layering, the part that overlaps U1-c).
 *
 * `⌘N`, `⌘1–4`, `⌘B`, `⌘,`, `⌘\` deliberately stay with U1-g: this handler does not touch
 * them, and they still do nothing.
 */
internal fun handleShellKeys(event: KeyEvent, state: UiState): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val search = state.search
    val primary = event.isCtrlPressed || event.isMetaPressed

    if (primary && event.key == Key.K) {
        state.toggleSearch()
        return true
    }

    if (!search.open) {
        if (event.key == Key.Escape && state.selectedSessionId != null) {
            state.back()
            return true
        }
        return false
    }

    return when (event.key) {
        Key.Escape -> {
            state.closeSearch()
            true
        }

        Key.DirectionDown -> {
            search.moveSelection(1)
            true
        }

        Key.DirectionUp -> {
            search.moveSelection(-1)
            true
        }

        Key.Enter, Key.NumPadEnter -> {
            val hit = search.currentHit()
            if (hit == null) {
                false
            } else {
                state.openFromSearch(hit)
                true
            }
        }

        else -> false
    }
}
