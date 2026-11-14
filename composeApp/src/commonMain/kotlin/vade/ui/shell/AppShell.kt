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
import vade.ui.data.FakeSessionSource
import vade.ui.data.ModuleCatalogProvider
import vade.ui.data.RawSourceStrings
import vade.ui.data.SourceConfig
import vade.ui.data.VadeErrorCategory
import vade.ui.data.rawSourceStrings
import vade.ui.destinations.ActivityScreen
import vade.ui.destinations.CapabilitiesScreen
import vade.ui.destinations.Destination
import vade.ui.destinations.ListStatus
import vade.ui.destinations.ProfileScreen
import vade.ui.destinations.SessionDetailScreen
import vade.ui.destinations.SessionsScreen
import vade.ui.destinations.settledListStatus
import vade.ui.glass.GlassScope
import vade.ui.glass.asGlassSource
import vade.ui.glass.rememberGlassState
import vade.ui.icon.ShellGlyphs
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.meta.MetaGroup
import vade.ui.meta.SessionMetaState
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

/**
 * 会话列表行的「组色点 + 组名」要查的组名字表 —— B3 适配器接线的最后一行（g-meta-groups）。
 *
 * ## 为什么需要它
 * `SessionsScreen` 早就接受 `metaGroups` 并且**真的**用它渲染组名（`sessionGroupTag`
 * 的 KDocs 三条规则都有测试），`SessionRowMapping` 也早就把 `groupId` 投影到了
 * `SessionSummary` 上 —— 缺的只是组合根那一行赋值。没有它，行上显示的是裸 id（`g-1`）：
 * 不崩、不空白，所以没人发现「分类」在界面上其实没接通（本仓同类事故已发生七次）。
 *
 * ## 数据从哪来
 * `session/group.list`，经组合根的适配器包（[AdapterProvider.meta]）取得；**不是**
 * `UiState` 上的字段 —— `UiState` 只管会话行/桶，分组是元数据通道，两者刻意分开。
 * [adapters] 由调用方从 [adaptersOf] 取好再传进来，而不是在这里重新探一次 `state.source`：
 * 组合根的推导只有一处（`AppShellAdapterWiringTest` 用正则钉住 `adaptersOf(state)` 恰好
 * 出现两次），第三个探针就是漂移的开始。
 * 用 [SessionMetaState.forAdapters] 而不是直接调 `listGroups()`：那是本仓既有的三段回退
 * 工厂（形参 → 适配器 → 内存假实现），失败分类、`wiringDegradation` 都在里面，这里再抄
 * 一遍等于造第二个真相。
 *
 * ## 什么时候**不**拉表（三条，都是为了不发明组名）
 *  1. `adapters == null`（假源 / 已断开）：假数据没有分组权威，表留空，行上继续显示裸 id；
 *  2. `forAdapters` 退化过（`wiringDegradation != null`，即 `adapters.meta()` 抛了）：
 *     此时它跑在 [vade.ui.meta.FakeMetaActions] 上，`loadGroups()` 会吐出 fixture 的
 *     「项目/灵感」—— 把假数据当 core 的组名画到真行上，比裸 id 更糟；
 *  3. 拉取失败：`SessionMetaState` 保留上一次快照（首次为空），行上照样退化成裸 id。
 *     裸 id 难看，但它是「组名没接上」唯一可见的信号（`sessionGroupTag` 规则 3），
 *     绝不编一个组名糊过去。失败原因留在 `groupsLoadError` 里，等分组面板挂载后渲染。
 *
 * `sessionId` 只作 [AdapterProvider.meta] 的查找参数（真适配器无状态，不看它），因此
 * 不参与 `remember` 键：组表是全局的，不该随选中会话重新拉一次。
 */
@Composable
internal fun metaGroupsOf(adapters: AdapterProvider?, sessionId: String): List<MetaGroup> {
    val meta = remember(adapters) {
        SessionMetaState.forAdapters(adapters, sessionId)
    }
    LaunchedEffect(meta) {
        // 见 KDoc「什么时候不拉表」① ②：二者都没有分组权威。
        if (adapters != null && meta.wiringDegradation == null) meta.loadGroups()
    }
    return meta.groups
}

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

/**
 * 列表头横幅的**唯一判定点**：把「源是什么 / 读到哪一步 / 失败成什么样 / 屏上有没有行」
 * 投影成一条 [ListStatus]。渲染在 `destinations/ListStatusBanner.kt`，屏幕本身不碰
 * `SessionSource`。
 *
 * ## 每一条判据都是可观测状态，不是猜的
 *
 * - **假源** = `state.source is FakeSessionSource`。这是唯一允许说「假数据」的条件
 *   （P1 的修法：真源连着真 core 时那条横幅必须消失，因为它当时说的是假话）。
 * - **失败** = `sessionsPhase == Failed` **或** `sessionsError != null`。两个条件取并集是
 *   故意的：`SessionsLoader` 分两次 state 写发布这一对（先 phase、后 error），组合读到
 *   `Failed + error == null` 的半发布窗口是合法的，那时也必须显示错误——空白的失败态
 *   正是本棒要消灭的东西。反过来 `Ready` 不会带着非 null 的 error（每次尝试开头清空）。
 * - **连接中** = `Loading` **且屏上一行都没有**。两个条件都要：这行字的职责是解释
 *   「你为什么看到一张空列表」，列表里已经有真行时它就没有解释对象了。
 * - **已连接** = 其余（`Ready`，或已有行）。不画横幅。
 *
 * ## 为什么「连接中」要加 `hasRows` 这一条（本棒实测出来的）
 *
 * 组合是一次 MVCC 快照：**在首次组合进行中**才落盘的 `mutableStateOf` 写入，那次组合
 * 看不见，而且（在本环境实测）也不会再收到一次失效通知。loader 的协程恰好就在这个窗口
 * 里完成（本地 core 的握手 ≪ 整棵 shell 的首帧），于是 `rows` 先落盘（后续读取看得到）、
 * `phase=Ready` 后落盘（读取看不到）——屏上是真会话，横幅却停在「正在连接 core …」。
 *
 * 也就是说：**任何只由 `phase` 推出来的字都可能一辈子是错的**。把「连接中」限制在
 * 「一行都没有」之后就变成了 fail-safe：
 *  - 有行 → 静默（列表已经是真的，不需要解释，也不会说谎）；
 *  - 没行 → 说「正在连接」/说真实错误（这两句都只在没有数据时出现，正是它们该出现的时候）。
 *
 * 这不是理论推演：`ListStatusBannerTest.appWithConnectedRealSourceNeverClaimsFixtures`
 * 用「首帧就答完的源」把这个时序钉成了确定性用例。
 *
 * [raw] 是**惰性**的：只有 Failed / Connecting 分支才会去读原始配置（桌面是环境变量），
 * 假源与「有行」路径连读都不读——fixtures 路径必须保持逐字节无副作用。
 */
internal fun listStatusOf(
    fixture: Boolean,
    phase: LoadPhase,
    error: LoadError?,
    hasRows: Boolean,
    raw: () -> RawSourceStrings = ::rawSourceStrings,
): ListStatus {
    if (fixture) return ListStatus.Fixtures

    if (phase == LoadPhase.Failed || error != null) {
        val headline = when (error?.category) {
            // 传输断了 / 需要重连 / 没有分类信息 → 用户视角就是「连不上」。UNAVAILABLE 是
            // 真实无码启动时走的那条（AutoConnectingSessionSource 15 s 超时），AUTH/CODE_*
            // 也在 NeedsReconnect / NeedsUserAction 里，文案都落到这一句 + 分类详情。
            null, VadeErrorCategory.NeedsReconnect -> "无法连接 core"
            else -> "无法读取会话列表"
        }
        return ListStatus.Failed(
            headline = headline,
            // 分类文案 + C-04 code + 传输原话。三者都是既有字段（LoadError），不在这里
            // 重新翻译错误——分类表是 data 层的，UI 只显示。
            detail = error?.let { e ->
                buildString {
                    append(e.category.label)
                    if (e.code != null) append("（").append(e.code).append("）")
                    append("：").append(e.detail)
                }
            } ?: "读取失败，但没有留下具体原因（上一轮尝试没有分类错误）",
            remedy = selfRescueRemedy(raw()),
        )
    }

    if (phase == LoadPhase.Loading && !hasRows) {
        val r = raw()
        return ListStatus.Connecting(endpoint = "${hostOf(r)}:${portOf(r)}")
    }
    return ListStatus.Connected
}

/** `host` 原始值为空/缺失时的**同一个**默认值（`SourceConfig.DEFAULT_HOST`）。 */
internal fun hostOf(raw: RawSourceStrings): String =
    raw.host?.trim()?.takeIf { it.isNotEmpty() } ?: SourceConfig.DEFAULT_HOST

/** `port` 原始值为空/缺失时的**同一个**默认值（`SourceConfig.DEFAULT_PORT`）。 */
internal fun portOf(raw: RawSourceStrings): String =
    raw.port?.trim()?.takeIf { it.isNotEmpty() } ?: SourceConfig.DEFAULT_PORT.toString()

/**
 * 连不上时那张「自救卡」的正文：一行**可照抄**的启动命令 + 配对码从哪取。
 *
 * 首次真实验收的结论是「第一次用的人连不上」——唯一的通道是 `VADE_SOURCE` /
 * `VADE_PAIR_CODE` 环境变量，而任何用户文档都没写过（D3/D4）。运行期改配对码在架构上
 * 不存在（`sessionSourceFrom()` 在 `Main.kt` 里一次性建好 `BrowserPairing`，`VadeClientConfig`
 * 不可变，`vade.ui.data` 也没有运行期改动入口），所以这里给的是**能自救**的东西：
 *
 *  - host/port **取本次运行的原始配置**，不是猜的默认值——否则用户照抄一条指向 8443 的
 *    命令去连 18755 的 core，等于把同一个失败重演一遍；
 *  - 配对码只出现为占位符 `<配对码>`（**绝不回显**：它是一次性凭证，`LoadError.detail`
 *    里也可能带码，所以这里不拼 detail 进命令）；
 *  - 已经给过码仍失败 → 明说「一次性码可能已过期」，这是最常见的原因；
 *  - Android / Wasm 的通道与桌面不同，一句带过，不在桌面命令里假装统一。
 */
internal fun selfRescueRemedy(raw: RawSourceStrings): String {
    val command = "VADE_SOURCE=real VADE_HOST=${hostOf(raw)} VADE_PORT=${portOf(raw)} " +
        "VADE_PAIR_CODE=<配对码> gradle :composeApp:run"
    val expired = if (raw.pairCode != null) {
        "已经给了配对码仍连不上：一次性码可能已用或过期，回 core 重新取一个。\n"
    } else {
        ""
    }
    return expired +
        "用环境变量重连（桌面照抄这一行）：\n" +
        command + "\n" +
        "配对码 = core 启动后 stdout 里 `pair_code=` 那一行。Android 用 " +
        "`--es vade.source real --es vade.pair_code <码>`，Wasm 用 URL query " +
        "`?source=real&pair_code=<码>`。"
}

@Composable
private fun DestinationHost(
    metrics: ShellMetrics,
    state: UiState,
    contentInsets: PaddingValues,
) {
    // 接线（g-activate）：与 [ShellBody] 同一处推导，四处屏幕共用这一个值。
    val adapters = adaptersOf(state)
    // B3 最后一行（g-meta-groups）：会话行的「组名」表。**两个**列表调用点（宽屏两栏 /
    // 窄屏单栏）共用这一个值 —— 只给其中一处赋值正是这族缺陷的复发形态。
    val metaGroups = metaGroupsOf(adapters = adapters, sessionId = state.selectedSessionId.orEmpty())
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
            // g-first-run · P1/P2：列表头横幅按**真实**连接状态画（假源才说「假数据」，
            // 连不上就说真实错误）。`hasRows` 是 fail-safe 的第二个判据（见 listStatusOf 的
            // KDoc）：屏上已经有真行时横幅一律静默，所以即使 loader 的写入落在首帧组合的
            // 快照窗口里（实测会丢），界面也不会顶着一句过期的状态。
            // 这就是「错误可见」的全部接线；重试入口留给 g-error-retry。
            val status = settledListStatus {
                listStatusOf(
                    fixture = state.source is FakeSessionSource,
                    phase = state.sessionsPhase,
                    error = state.sessionsError,
                    hasRows = state.sessionRows.isNotEmpty(),
                )
            }
            if (metrics.twoPane) {
                Row(Modifier.fillMaxSize()) {
                    SessionsScreen(
                        groups = state.groups(),
                        selectedId = state.selectedSessionId,
                        twoPane = true,
                        onOpen = { state.open(it.id) },
                        modifier = Modifier.width(metrics.listWidth),
                        contentPadding = PaddingValues(top = TOP_BAR_ZONE),
                        metaGroups = metaGroups,
                        banner = sessionListBanner,
                        status = status,
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
                    metaGroups = metaGroups,
                    banner = sessionListBanner,
                    status = status,
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
