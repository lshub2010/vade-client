package vade.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.data.AdapterHost
import vade.ui.data.AdapterProvider
import vade.ui.data.ModuleCatalogProvider
import vade.ui.destinations.ActivityScreen
import vade.ui.destinations.CapabilitiesScreen
import vade.ui.destinations.Destination
import vade.ui.destinations.ProfileScreen
import vade.ui.destinations.SessionDetailScreen
import vade.ui.destinations.SessionsScreen
import vade.ui.glass.GlassScope
import vade.ui.glass.asGlassSource
import vade.ui.glass.rememberGlassState
import vade.ui.icon.ShellGlyphs
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.search.SearchHost
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
 *    [bottomBarZone] as *content* insets (not container padding), so rows really scroll
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

/**
 * Top-bar zone: [TopBarTopGap] + TopBar's 56dp glass capsule + 8dp breathing gap = 72dp.
 * Scrollables inset by this — as **content** padding (internal so TopGlassGuardTest can
 * pin the arithmetic).
 *
 * The status-bar inset is NOT a term of this number and must never be added here: the
 * root `statusBarsPadding()` in androidMain `MainActivity` consumes it exactly once
 * before this composition runs (desktop/wasm read 0 — desktopTest `InsetsGuardTest`),
 * so the glass bar's geometry is these constants alone on every platform. A shell-side
 * status-bar term would double-pad Android and add a phantom band on desktop/wasm.
 */
internal val TOP_BAR_ZONE = 72.dp

/** Window top → glass capsule gap. The status-bar inset above it is consumed at the root. */
internal val TopBarTopGap = 8.dp

/**
 * Bottom-bar zone **per form**. The two forms do not share a footprint, so they must not
 * share one formula:
 *
 *  - **Floating** ([floatingBarZone]): [FloatingBarPillHeight] 64 dp + the pill's own
 *    `floatingBarBottomMargin(inset)` (`8 + inset`, or KernelSU's 28 dp when the inset is
 *    0) + [BOTTOM_BAR_DOCK_PAD] 12 dp → **104 / 108 / 132 dp** at inset 0 / 24 (gesture) /
 *    48 (3-button). This is the P1-3 fix: the last row scrolls clear of the capsule.
 *  - **Fixed** ([fixedBarZone]): the docked bar's *own* row ([FixedBarRowHeight] 64 dp)
 *    behind its 1 dp top hairline, extended through the navigation inset (the same
 *    `navInset` term `FixedBottomBar` adds to its glass height) → **65 / 89 / 113 dp**.
 *    No pill term: the docked bar has no capsule and no pill margin.
 *
 * g-zone-split: before this split `bottomBarZone` served both forms, so the Fixed bar
 * reserved a 64 dp pill it does not render — measured on desktop as a **39 dp blank tail**
 * (104 reserve − 65 rendered; the pre-P1-3 88 dp constant left 23 dp). Deriving each form
 * from its own terms keeps the capsule fix and drops the phantom padding.
 *
 * Both rows happen to be 64 dp (the same KernelSU 56 + 2×4 geometry), which is exactly why
 * this can drift silently: [FixedBarRowHeight] is written as the Fixed form's own literal
 * term — never as [FloatingBarPillHeight] — the rendered Fixed bar is measured against it
 * (`BarStyleZoneSplitTest`), and `fixedZoneExpressionNeverMentionsThePillHeight` makes the
 * re-coupling fail instead of shrinking the docked bar's reserve.
 *
 * The Floating zone is not monotonic in the inset at the 0 → ε step, by construction: the
 * KernelSU fallback swaps 28 dp for `8 + ε`, and that is the pill's real margin too. The
 * invariant that matters holds for every value:
 * `floatingBarZone(i) − (pill + margin(i)) == 12 dp`. Pinned by `AppShellBottomZoneTest`
 * and `BarStyleZoneSplitTest`.
 */
internal fun floatingBarZone(navInset: Dp): Dp =
    FloatingBarPillHeight + floatingBarBottomMargin(navInset) + BOTTOM_BAR_DOCK_PAD

/**
 * Historical name of [floatingBarZone] — the single shared zone the shell used before
 * g-zone-split. Kept (same numbers 104 / 108 / 132) so the tests written against it stay
 * the regression pin for the capsule form.
 */
internal fun bottomBarZone(navInset: Dp): Dp = floatingBarZone(navInset)

/**
 * The Fixed (docked) bar's row: the label row `FixedBottomBar` renders. **Its own term,
 * not the pill's** — the docked bar has no capsule, no pill margin and no shell dock pad,
 * so nothing here may be derived from [FloatingBarPillHeight] (that coupling is the
 * phantom-blank bug g-zone-split removes). It reads 64 dp only because both forms use the
 * KernelSU 56 + 2×4 row; the rendered-geometry test holds it to that.
 */
internal val FixedBarRowHeight: Dp = 64.dp

/** The Fixed bar's 1 dp top hairline — part of its footprint, so part of its zone. */
internal val FixedBarTopRule: Dp = 1.dp

/**
 * The docked bar's zone: its own row behind its hairline, extended through the navigation
 * inset (the term `FixedBottomBar` also adds to its glass height, so labels sit above the
 * system bar while the glass covers it). 65 / 89 / 113 dp. **No pill term** — see
 * [FixedBarRowHeight].
 */
internal fun fixedBarZone(navInset: Dp): Dp =
    FixedBarTopRule + FixedBarRowHeight + navInset

/** The zone of the form actually composed: `Fixed` is docked, both Floating styles are pills. */
internal fun barZoneFor(style: BottomBarStyle, navInset: Dp): Dp = when (style) {
    BottomBarStyle.Fixed -> fixedBarZone(navInset)
    BottomBarStyle.Floating, BottomBarStyle.FloatingBlur -> floatingBarZone(navInset)
}

/**
 * What scrollable content must reserve below itself: the zone of the composed form while
 * the bar is shown, nothing when it is not (pushed detail / rail breakpoints — D88: the
 * bar is not composed there, so no zone may be reserved).
 *
 * `style` defaults to the floating form so pure-arithmetic callers keep their meaning;
 * the shell always passes the state's real [BottomBarStyle].
 */
internal fun contentBottomInset(
    showBar: Boolean,
    navInset: Dp,
    style: BottomBarStyle = BottomBarStyle.Floating,
): Dp = if (showBar) barZoneFor(style, navInset) else 0.dp

/** The shell's own dock padding under the floating pill (`barInsets` in [ShellBody]). */
internal val BOTTOM_BAR_DOCK_PAD = 12.dp

/**
 * The adapter bundle this shell hands to every screen — `null` whenever the state's source
 * cannot host one (the fixtures do not implement [AdapterHost], and a connected source clears
 * its bundle on disconnect), which is exactly the pre-wiring state: screens fall back to their
 * in-memory seams and every one of their `adapters` defaults stays `null`.
 *
 * One place, two callers ([ShellBody] for the pushed detail, [DestinationHost] for the rest):
 * a per-call `(state.source as? AdapterHost)?.adapters` would drift the moment one of the five
 * call sites is edited.
 */
internal fun adaptersOf(state: UiState): AdapterProvider? = (state.source as? AdapterHost)?.adapters

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
        // 接线（g-activate）：真数据源的适配器包从这里往下传；假源不实现 AdapterHost ⇒ null。
        val adapters = adaptersOf(state)
        val detailOpen = state.dest == Destination.Sessions && session != null && !metrics.twoPane

        // v2 bottom bar: compact single-pane only, never while a detail is pushed (D88).
        val showBar = !metrics.useRail && !detailOpen
        // P1-3 + g-zone-split: the reserved zone is the **composed form's** measured-in-code
        // footprint, so it tracks the navigation inset (gesture ≈ 24 dp, 3-button ≈ 48 dp)
        // instead of guessing one constant — and the docked form no longer reserves the
        // floating pill it does not render. Reading the inset here — not in the bar — keeps
        // the one allowed `WindowInsets.navigationBars` read in `BottomBar.kt`
        // (TopGlassGuardTest).
        val navInset = navigationBarBottomInset()
        // Every bar form reserves its own bottom zone as *content* padding (never container
        // padding): the last row must be able to scroll clear of the bar, or it stays
        // permanently hidden behind it (the 能力 page's final card was clipped by the
        // window edge). Rows still pass *through* the bar zone mid-scroll, which is what
        // gives the glass something to blur — both behaviours come from content padding.
        val contentInsets = PaddingValues(
            top = TOP_BAR_ZONE,
            bottom = contentBottomInset(showBar, navInset, state.barStyle),
        )

        // --- backdrop: the ONLY haze source. Everything blurrable lives in here. ---
        Row(
            Modifier
                .fillMaxSize()
                .background(skin.colors.canvas)
                // Two independent captures of the SAME content, on purpose:
                //  - `asGlassSource` feeds haze (glass top bar + search overlay);
                //  - `captureGlassBackdrop` feeds miuix-blur (the KSU pill's lens/blur).
                // Dropping the second one is what made the pill's indicator sample an
                // unpainted surface and render as a grey smear — both must stay.
                .asGlassSource(glass)
                .captureGlassBackdrop(contentBackdrop),
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
                    // Pushed detail: the glass top bar (title + back) floats above and the
                    // thread scrolls FULL-BLEED under it — TOP_BAR_ZONE is handed over as
                    // *content* padding (SessionDetailScreen floats its header below the
                    // bar zone and lets ChatThread span the window), never as container
                    // padding: that cropped the viewport below the bar, no row ever passed
                    // under the glass, and the blur had nothing behind it — the
                    // 「看不出有模糊」 user complaint.
                    SessionDetailScreen(
                        session = session,
                        preset = state.chatPreset,
                        animate = state.chatPreset == vade.ui.chat.ChatPreset.Live,
                        showTitle = false,
                        adapters = adapters,
                        contentPadding = PaddingValues(top = TOP_BAR_ZONE),
                    )
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
                    top = TopBarTopGap,
                ),
        ) {
            if (detailOpen) {
                TopBar(
                    title = session.title,
                    subtitle = "会话详情 · 消息流 / 工具卡为假数据",
                    leading = { TopBarButton(VadeIcons.Back, onClick = { state.back() }, contentDescription = "返回") },
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
                            TopBarButton(VadeIcons.Search, onClick = { state.openSearch() }, contentDescription = "搜索")
                            TopBarButton(VadeIcons.Compose, onClick = { state.newSession() }, contentDescription = "新建会话")
                        }
                        if (metrics.hasRightPanel) {
                            // g-glyph-clear: 这处曾是 `glyph = if (open) "▸" else "◂"` —— 源码
                            // 注释自己写着「w2-icons 遗留」：VadeIcons 里没有侧栏语义名，本棒
                            // 又不许往那个文件追加。两态矢量因此落在 vade/ui/icon/ShellGlyphs.kt
                            // （同包，Tabler 命名空间仍只在 icon/ 里）。
                            TopBarButton(
                                icon = panelToggleIcon(state.panelOpen),
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
                PaddingValues(start = 12.dp, end = 12.dp, bottom = BOTTOM_BAR_DOCK_PAD)
            } else {
                PaddingValues(0.dp)
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(barInsets),
            ) {
                VadeBottomBar(
                    items = navItems(),
                    selected = state.dest.ordinal,
                    onSelect = { index -> state.select(Destination.entries[index]) },
                    style = state.barStyle,
                    backdrop = contentBackdrop,
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
    // 接线（g-activate）：与 [ShellBody] 同一处推导，四处屏幕共用这一个值。
    val adapters = adaptersOf(state)
    // g-error-retry: one slot per READ (list vs thread), each with its own retry entry.
    // The list slot only exists while that read is not Ready — otherwise the 假数据 note in
    // SessionsScreen (fixture-path copy) would be hidden for no reason.
    val sessionListBanner: (@Composable () -> Unit)? =
        if (state.sessionsPhase == LoadPhase.Ready && state.sessionsError == null) {
            null
        } else {
            {
                SessionListLoadBanner(
                    phase = state.sessionsPhase,
                    error = state.sessionsError,
                    onRetry = state::retrySessions,
                )
            }
        }
    // The thread banner is passed unconditionally: its in-flight state has to stay composed
    // while a retry runs (it retains the failed classification) — see LoadErrorBanner.kt.
    val messageThreadBanner: @Composable () -> Unit = {
        MessageThreadLoadBanner(
            phase = state.messagesPhase,
            error = state.messagesError,
            onRetry = state::retryMessages,
        )
    }

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
                        banner = sessionListBanner,
                    )
                    VerticalRule()
                    // Two-pane detail: same content-padding contract as the pushed detail —
                    // the thread scrolls under the glass top bar, not below it.
                    SessionDetailScreen(
                        session = state.selectedSession,
                        preset = state.chatPreset,
                        animate = state.chatPreset == vade.ui.chat.ChatPreset.Live,
                        modifier = Modifier.weight(1f),
                        adapters = adapters,
                        contentPadding = PaddingValues(top = TOP_BAR_ZONE),
                        banner = messageThreadBanner,
                    )
                }
            } else if (state.selectedSessionId != null) {
                SessionDetailScreen(
                    session = state.selectedSession,
                    preset = state.chatPreset,
                    animate = state.chatPreset == vade.ui.chat.ChatPreset.Live,
                    // The pushed-detail top bar above already carries this title.
                    showTitle = false,
                    adapters = adapters,
                    banner = messageThreadBanner,
                )
            } else {
                SessionsScreen(
                    groups = state.groups(),
                    selectedId = null,
                    twoPane = false,
                    onOpen = { state.open(it.id) },
                    contentPadding = contentInsets,
                    banner = sessionListBanner,
                )
            }
        }

        // g-activity-wire 交回的接线：页面是全局的，Shell 进活动页时已清空 selectedSessionId，
        // 所以候选从分组里摊平；source 非空 → ActivityScreen 自己推出两个窄口（假源 → 未接线）。
        Destination.Activity -> ActivityScreen(
            contentPadding = contentInsets,
            sessions = state.groups().flatMap { it.sessions },
            source = state.source,
        )
        // g-modules-wire：能力页除适配器包外，还要两样东西 ——
        //  ① 模块清单通道（`ModuleCatalogProvider`，组合根的可选能力；本棒交 null，
        //     因为 contracts/vade/schema/rpc.json 里没有 ui/modules，见 data/ModuleCatalog.kt）；
        //  ② `module.status` 的失效边（`observeEvents()`，冻结契约里唯一的模块 topic）。
        // 两者都不带 vade.ui.protocol 类型上来：清单是 ModuleCatalog 窄口，事件是 data 层 VadeEvent。
        Destination.Capabilities -> CapabilitiesScreen(
            contentPadding = contentInsets,
            adapters = adapters,
            moduleCatalog = (state.source as? ModuleCatalogProvider)?.moduleCatalog,
            events = state.source.observeEvents(),
        )
        Destination.Me -> ProfileScreen(
            skinId = state.skinId,
            barStyle = state.barStyle,
            containerLabel = metrics.containerLabel,
            onSkinChange = state::setSkin,
            onBarChange = state::setBar,
            contentPadding = contentInsets,
            adapters = adapters,
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
            // g-glyph-clear: 原来是 `skin.Button("×", style = BtnStyle.Text)` —— 文本按钮里
            // 塞一个乘号当图标。`Skin.Button` 只有 String 签名（C-09 冻结，本棒不能加
            // ImageVector 重载），所以这里直接画矢量：44dp 触控目标 + 20dp 图标 + 读屏描述。
            // 视觉上仍是同一个「关掉这一栏」的 X，只是不再是一个字。
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(skin.shapes.sm))
                    .semantics { contentDescription = "关闭右面板" }
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                VadeIcon(
                    vector = VadeIcons.Close,
                    contentDescription = null, // the button box above carries the description
                    size = 20.dp,
                    tint = skin.colors.onSurfaceVariant,
                )
            }
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
                text = "默认收起；本切片用顶栏的侧栏按钮开合，⌘\\ 属 U1-g。开合不改变会话滚动位置。",
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

/**
 * Right-panel toggle icon — **one vector per state**, the two-state contract the `◂` / `▸`
 * character pair used to carry:
 *  - panel closed → [ShellGlyphs.PanelExpand]  (点它把面板拉出来)
 *  - panel open   → [ShellGlyphs.PanelCollapse] (点它收回去)
 *
 * Extracted from the call site so a test can pin the pair without standing up a ≥1200 dp
 * window: `ShellMetrics.hasRightPanel` is false below 1200 dp, which makes the button
 * unreachable in the default 1024×768 desktop test window.
 */
internal fun panelToggleIcon(open: Boolean): ImageVector =
    if (open) ShellGlyphs.PanelCollapse else ShellGlyphs.PanelExpand

/** Bottom-bar rows: the four destinations, in contract order (FR-8.77). */
private fun navItems(): List<BottomBarItem> = Destination.entries.map {
    // w2-icons: the Tabler vector is the icon; the CJK glyph is no longer a fallback
    // that ships — `glyph` stays null so a missing vector is visible, not disguised
    // as a correct icon (docs/visual-language.md §3 bans character icons).
    BottomBarItem(id = it.id, label = it.label, icon = it.icon)
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
