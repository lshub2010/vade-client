package vade.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import vade.ui.search.SearchHost
import vade.ui.skin.BtnStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.NavItem

/**
 * The whole shell: four destinations, four breakpoints, three bottom-bar forms.
 *
 * Layout rules implemented here (task book §2.1 / FR-8.76):
 *  - `<600dp`  bottom bar, single pane; opening a session pushes it full screen and the
 *              bar is **not composed** (D88 — this is why the detail screenshot shows no
 *              bar at all rather than a hidden one).
 *  - `600–839` NavigationRail 80 dp; from 720 dp the list stays beside the detail.
 *  - `840–1199` rail 72 dp + 300 dp list + detail.
 *  - `≥1200`   the same two panes plus a collapsible right panel (default collapsed).
 *
 * Not here yet: the remaining FR-8.74 shortcuts (`⌘N/⌘1–4/⌘B/⌘,`), IME insets, `⌘B` list
 * collapse, and panel width presets — those are U1-g / U1-h. `⌘K` / `Ctrl+K` **is** here:
 * it belongs to U1-c's search, together with `↑ ↓ Enter Esc` for the palette. The panel
 * toggle is a button so the shell is usable (and screenshottable) without a keyboard story.
 */
@Composable
fun AppShell(state: UiState) {
    // The whole shortcut story is one root preview handler: it sees keys before any child
    // (the search field included), so `⌘K` opens the palette even while typing elsewhere.
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event -> handleShellKeys(event, state) }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        ShellBody(state)
        // U1-c: full-screen overlay below 600dp, ⌘K palette above it. No-op while closed.
        SearchHost(state)
    }
}

@Composable
private fun ShellBody(state: UiState) {
    val skin = LocalSkin.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val metrics = metricsFor(maxWidth)
        val session = state.selectedSession
        val detailOpen = state.dest == Destination.Sessions && session != null && !metrics.twoPane

        Row(Modifier.fillMaxSize().background(skin.colors.surfaceVariant)) {
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
                    TopBar(
                        title = session?.title ?: "",
                        subtitle = "会话详情 · 消息流由 U1-b 承载",
                        floating = metrics.tier == Tier.Compact,
                        leading = { TopBarButton("←") { state.back() } },
                    )
                } else {
                    TopBar(
                        title = state.dest.label,
                        subtitle = buildString {
                            append(metrics.tier.label)
                            if (state.dest == Destination.Sessions) append(" · ${state.totalSessions} 个会话")
                        },
                        floating = metrics.tier == Tier.Compact,
                        actions = {
                            if (state.dest == Destination.Sessions) {
                                // U1-c: phones reach search from here; desktop also has ⌘K.
                                TopBarButton("搜索") { state.openSearch() }
                                TopBarButton("✎") { state.newSession() }
                            }
                            if (metrics.hasRightPanel) {
                                TopBarButton(if (state.panelOpen) "▸" else "◂") { state.togglePanel() }
                            }
                        },
                    )
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // Floating / floating+blur bars overlay the content (that is the whole
                    // point of the form: the list scrolls behind a detached pill). Fixed
                    // bars take layout space below instead.
                    val floats = !metrics.useRail && !detailOpen && state.barStyle != vade.ui.skin.BottomBarStyle.Fixed
                    Box(Modifier.fillMaxSize().padding(bottom = if (floats) 78.dp else 0.dp)) {
                        DestinationHost(metrics = metrics, state = state)
                    }
                    if (floats) {
                        Box(Modifier.align(Alignment.BottomCenter)) {
                            skin.BottomBar(
                                items = navItems(),
                                selected = state.dest.ordinal,
                                onSelect = { index -> state.select(Destination.entries[index]) },
                                style = state.barStyle,
                            )
                        }
                    }
                }

                if (!metrics.useRail && !detailOpen && state.barStyle == vade.ui.skin.BottomBarStyle.Fixed) {
                    skin.BottomBar(
                        items = navItems(),
                        selected = state.dest.ordinal,
                        onSelect = { index -> state.select(Destination.entries[index]) },
                        style = state.barStyle,
                    )
                }
            }

            if (metrics.hasRightPanel && state.panelOpen) {
                VerticalRule()
                RightPanel(onClose = state::togglePanel)
            }
        }
    }
}

@Composable
private fun DestinationHost(metrics: ShellMetrics, state: UiState) {
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
                    )
                    VerticalRule()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        SessionDetailScreen(state.selectedSession)
                    }
                }
            } else if (state.selectedSessionId != null) {
                SessionDetailScreen(state.selectedSession)
            } else {
                SessionsScreen(
                    groups = state.groups(),
                    selectedId = null,
                    twoPane = false,
                    onOpen = { state.open(it.id) },
                )
            }
        }

        Destination.Activity -> ActivityScreen()
        Destination.Capabilities -> CapabilitiesScreen()
        Destination.Me -> ProfileScreen(
            skinId = state.skinId,
            barStyle = state.barStyle,
            containerLabel = metrics.containerLabel,
            onSkinChange = state::setSkin,
            onBarChange = state::setBar,
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
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.outline.copy(alpha = 0.30f)))
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
    Box(Modifier.width(1.dp).fillMaxHeight().background(LocalSkin.current.colors.outline.copy(alpha = 0.28f)))
}

/** Bottom-bar rows: the four destinations, in contract order (FR-8.77). */
private fun navItems(): List<NavItem> = Destination.entries.map {
    NavItem(id = it.id, label = it.label, glyph = it.glyph)
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
