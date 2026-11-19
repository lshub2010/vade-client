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
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import vade.ui.UiText
import vade.ui.data.AdapterHost
import vade.ui.data.AdapterProvider
import vade.ui.data.FakeSessionSource
import vade.ui.data.ModuleCatalogProvider
import vade.ui.data.RawSourceStrings
import vade.ui.data.SourceConfig
import vade.ui.data.SourceKeys
import vade.ui.data.VadeErrorCategory
import vade.ui.data.rawSourceStrings
import vade.ui.destinations.ActivityScreen
import vade.ui.destinations.CapabilitiesScreen
import vade.ui.destinations.Destination
import vade.ui.destinations.ListStatus
import vade.ui.destinations.LocalSessionListOpener
import vade.ui.destinations.ProfileScreen
import vade.ui.destinations.SessionDetailScreen
import vade.ui.destinations.SessionsScreen
import vade.ui.destinations.settledListStatus
import vade.ui.glass.GlassScope
import vade.ui.glass.asGlassSource
import vade.ui.glass.rememberGlassState
import vade.ui.icon.MetaIcons
import vade.ui.icon.ShellGlyphs
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.meta.SessionMetaOverlayHost
import vade.ui.meta.rememberSessionMetaOverlay
import vade.ui.search.SearchHost
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.NavItem
import vade.ui.skin.glass.LocalGlassHazeState
import vade.ui.component.insets.composerNavClearance
import vade.ui.component.liquid.captureGlassBackdrop
import vade.ui.component.liquid.rememberGlassBackdrop

/**
 * The whole shell: four destinations, four breakpoints, a glass top bar and (on phones) a
 * glass bottom bar.
 *
 * Layout rules (task book §2.1 / FR-8.76, with the v2 glass structure of §5):
 *  - `<600dp`  bottom bar, single pane; opening a session keeps the bar composed — the bar is
 *              **常驻** on phones (m-bar 口径变更：会话详情页不再整屏 push 掉底栏). 详情态的下留白
 *              走**内容链**（[SessionDetailScreen] 的 `bottomBarInset` → 消息列表 + composer），
 *              **绝不容器留白**：容器一垫，详情子树里的抽屉 / 模型面板 / 图片预览（都是
 *              `fillMaxSize`）会一起被抬离屏底，遮罩在 104–132 dp 处硬切一刀（审查 P1-2）。
 *  - `600–839` NavigationRail 80 dp; from 720 dp the list stays beside the detail.
 *  - `840–1199` rail 72 dp + 300 dp list + detail.
 *  - `≥1200`   the same two panes plus a collapsible right panel (default collapsed).
 *
 * v2 glass structure (docs/visual-language.md §5; haze 2.1.0 semantics):
 *  - One shared `HazeState` at the root ([GlassScope] / [rememberGlassState]); the **haze
 *    source is each page's own content layer** (g-haze-structure: ChatThread's scroll
 *    column, the per-page scroll containers, the rail and the right panel), and every
 *    `GlassSurface` (top bar, bottom bar, search overlay — and any page-level glass such
 *    as the composer / model picker / image preview) reads the same ambient state
 *    (`LocalGlassHazeState`). It used to sit on the whole ShellBody Row, which made every
 *    page-level glass a descendant of its own backdrop — haze then refused to blur it.
 *  - **Glass layers are siblings of the backdrop sources, never descendants** — haze 2.1.0
 *    does not blur an effect under its own source subtree (verbatim `HazeEffectNode`
 *    contract). That is why the bars are aligned over the content from *outside* the
 *    content Row, and why in-page overlays (composer, preview sheet) are stacked as
 *    siblings of the scroll column inside each page.
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
    // g-back-handler: Esc routes through the back stack first (topmost overlay closes).
    val backDispatcher = LocalBackDispatcher.current

    // v2 §5: one shared HazeState per window — backdrop and all glass layers sample it.
    val glass = rememberGlassState()
    GlassScope(state = glass) {
        // g-nav-stack：Esc 与系统返回同源 —— `handleShellKeys` 唯一的分支是「先问返回栈」，
        // 栈里装的是每一层自己登记的那一层（抽屉 / 详情 / 模块页 / 关于 / 浮层）。
        // 这里不再读断点：返回语义不再按 twoPane 分叉（旧实现为那条分叉才包了一层
        // BoxWithConstraints，已随分叉一起删除）。
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event -> handleShellKeys(event, state, backDispatcher = backDispatcher) }
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
 * g-haze-structure · haze **source** registration tags（运行时守卫用）。结构规则：玻璃层
 * （顶栏胶囊/带、搜索浮层、以及页内任何 `GlassSurface`）必须是 source 的**兄弟**，绝不
 * 是后代 —— haze 2.1.0 对「effect 画在自己 source 子树里」不模糊。守卫测试用这些 tag
 * 在真实组合里断言浮层节点不在任何 source 节点的语义子树内；源码扫描守卫同时钉住注册
 * 点白名单（`GlassStructureGuardTest`）。
 */
internal const val GlassSourceRailTag = "vade.glass.source.rail"

/** 右面板的 haze source tag（与 [GlassSourceRailTag] 同一契约）。 */
internal const val GlassSourceRightPanelTag = "vade.glass.source.rightPanel"

/**
 * 手机单栏内容层的 testTag（m-bar）。这一层是**容器**：只负责铺满窗口底（列表根的行靠
 * `contentInsets` 滚到栏后），**不再**把底栏区垫在自己身上 —— 审查 P1-2 的根因就是容器
 * 留白把详情子树里的抽屉 / 模型面板 / 图片预览一起抬离屏底。
 *
 * 详情态的下留白现在走内容链：`SessionDetailScreen` 的 `bottomBarInset` 只把
 * [detailContentBottomInset] 加到「消息列表 + composer」那一层上（`RouteTransition` 的
 * `DrawerDetailBase` 把值传下去），浮层与抽屉仍是 `fillMaxSize` 的兄弟 ⇒ 贴屏底。
 *
 * 测试（`AppShellSessionDetailBarTest`）用它钉「容器满高」：底边 == 窗口底。
 */
internal const val PhoneContentStageTag = "vade.shell.phone.content"

/**
 * 详情内容链（消息列表 + composer）的下内边距 —— 手机单栏详情态唯一的一处「给内容让栏」
 * （审查 P1-1）。
 *
 * 为什么必须减掉 `clearance`：composer 自己已经加了一层 `16.dp + composerNavClearance()`
 * （`ChatThread.kt` 的 composer 浮层）。若这里直接给整条 [barZoneFor]（含导航条），
 * Android 上手势 24 / 三键 48 的导航条就被算**两遍** —— 胶囊与 composer 之间永久多出
 * 24/48 dp 白边（桌面 `navInset` 恒 0，所以桌面用例看不见）。
 *
 * 不变式（`DetailBarInsetTest` 纯算术钉 0/24/48 三档）：
 * ```
 * inset + 16 + clearance == barZone + 16
 * ```
 * 即 composer 的胶囊底边恒在栏区上沿之上 16 dp，与导航条形态无关；导航条只出现一次。
 *
 * [clearance] 由调用点用 `composerNavClearance()`（与 composer 同一个公式：`max(0, nav − ime)`）
 * 传进来 —— 键盘收起 = nav，键盘弹起 = 0（IME 已由根部 `imePadding` 抬起整页），所以
 * 键盘开合两种几何各只有一项导航条，不会相加。
 */
internal fun detailContentBottomInset(
    style: BottomBarStyle,
    navInset: Dp,
    clearance: Dp,
): Dp = (barZoneFor(style, navInset) - clearance).coerceAtLeast(0.dp)

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
 * What **scrollable pages** must reserve below themselves: the zone of the composed form
 * while the bar is shown, nothing when it is not.
 *
 * m-bar 口径（审查 P2-1，旧注释这里还写着「pushed detail 不组合底栏 ⇒ 不得留白」）：
 * `showBar = !metrics.useRail`，手机单栏**任何**状态（列表根 / 详情 / 抽屉展开）都组合底栏，
 * 所以详情态也照样有这一条 zone —— 只是它**不走容器**，而是经
 * [detailContentBottomInset] 落到详情的内容链上；rail / 双栏（`useRail = true`）没有底栏，
 * 这里返回 0。
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
 *
 * P1-1 · 这一行是**组合期的 snapshot state 读**：[RealSessionSource.adapters] 由
 * `mutableStateOf` 承载，冷启动「先组合、后连上」时值从 `null` 变成适配器包会推一次
 * 重组 —— 五个屏幕因此真的拿到真适配器（`@Volatile` 时代只有第一次读，之后永远是 null）。
 * 调用方必须**直接**在组合期调用它：不要把它塞进无 key 的 `remember`/普通字段缓存，
 * 那会把「可观察」重新变回「一次性快照」。
 */
internal fun adaptersOf(state: UiState): AdapterProvider? = (state.source as? AdapterHost)?.adapters

/*
 * B2（s-meta-host）：原 `metaGroupsOf`（g-meta-groups 的组名表 + 三条「什么时候不拉表」
 * 规则）已整体平移到 `meta/SessionMetaOverlay.kt` 的 `rememberSessionMetaOverlay` ——
 * 同一个状态机现在还喂长按菜单与分组管理，不再有两份组表各自漂移的口子。
 * `adaptersOf(state)` 仍然只有 ShellBody / DestinationHost 两处推导（AppShellAdapterWiringTest
 * 用正则钉住）。
 */

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
        // g-route-transition：详情「真正可见」= 详情开着 且（无抽屉断点 或 抽屉已收回）。
        // 顶栏 chrome 跟着这个走：抽屉开着时屏上是列表 → 列表 chrome；抽屉收回时屏上是
        // 详情 → 详情 chrome（底栏**不再**跟着它显隐，m-bar 起手机单栏常驻）。
        val detailVisible = detailOpen && (metrics.useRail || !state.sessionsDrawerOpen)

        // 页面切换动画（用户 2026-11-18：「页面切换动画缺失」）：**一个** key 覆盖
        // 四个 tab 与「列表 ⇄ 会话」两种切换 —— 断点决定 key 里要不要带会话 id：
        //  - rail / 双栏：会话在右栏里换页，`selectedSessionId` 参与 key；
        //  - 手机单栏：列表是抽屉、详情常驻，切会话**不换页**（换的是抽屉进度），
        //    所以 key 只认目的地 —— 否则拉开抽屉看列表也会重播一次进场。
        // 挂在下面 Column 的 modifier 上（一条 `graphicsLayer`，动画期间零重组），
        // 不是覆盖层，也不碰任何点击热区。
        val pageKey: Any = if (metrics.useRail) {
            state.dest to state.selectedSessionId
        } else {
            state.dest
        }

        // m-bar：手机单栏的底栏**常驻**——会话详情开着也照样组合。旧 D88 的「详情 push 时
        // 整条栏不组合」已废弃：详情页要能一键切去别的 tab，底栏不在就没有这个出口。
        // rail / 双栏断点仍然没有底栏（切换由 `NavRail` / 左列承担，`metrics.useRail`）。
        val showBar = !metrics.useRail
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
        // 详情页是这条规则的**唯一**例外形态（它的 composer 是贴屏底的浮层）：那里走
        // [detailContentBottomInset] + `SessionDetailScreen` 的 `bottomBarInset`，仍然不是
        // 容器留白 —— 容器一垫，抽屉 / 遮罩 / 面板就一起离屏（审查 P1-2）。
        val contentInsets = PaddingValues(
            top = TOP_BAR_ZONE,
            bottom = contentBottomInset(showBar, navInset, state.barStyle),
        )
        // m-bar P1-2：详情里的**模态浮层**（模型面板 / 图片预览）开着时，内容链整体抬到底栏
        // 之上 —— 遮罩因此真的盖住底栏（变暗 + 吃掉点按），而不是让胶囊漂在遮罩外面继续被
        // 点到（旧行为：面板开着还能点底栏切页，把面板连人一起端掉）。
        // 由详情自己上报（`SessionDetailScreen` 的 `onOverlayChange`）：两个浮层的状态都在
        // 页面内部（`modelPickerOpen`）与 ChatState 里（`chat.preview`），ShellBody 看不到。
        // 抽屉（`SessionsDrawerHost` 自带遮罩）**不**走这条：抽屉满宽（n1-nav），抬上来
        // 会把底栏整个盖住；它的遮罩已经铺满窗口，底栏留在最上层才仍能从抽屉里一键切
        // tab（m-bar 的常驻语义）。
        val detailOverlayOpen = remember { mutableStateOf(false) }
        val contentZIndex = if (detailOverlayOpen.value) 1f else 0f

        // --- backdrop (g-haze-structure): miuix-blur capture only. The HAZE source used
        // to sit HERE — on the whole ShellBody — which made every page-level glass layer
        // (the composer panel, the model picker, the image preview: all composed INSIDE
        // the pages) a DESCENDANT of the source. Haze 2.1.0 does not blur for an effect
        // under its own source subtree, so those layers silently degraded to near-solid.
        // The source now lives on each page's own content layer (ChatThread's scroll
        // column, the per-page scroll containers in `destinations/`, the rail and the
        // right panel below) — every floating glass layer is a SIBLING of what it blurs.
        // Only the miuix capture stays on the Row: the KSU pill samples the whole content
        // area through its own layerBackdrop pipeline, no haze node involved.
        Row(
            Modifier
                .fillMaxSize()
                .background(skin.colors.canvas)
                // 模态浮层开着 ⇒ 整条内容链压到底栏之上（见 [detailOverlayOpen] 的注释）。
                .zIndex(contentZIndex)
                .captureGlassBackdrop(contentBackdrop),
        ) {
            if (metrics.useRail) {
                // The rail is backdrop content for the search overlay / ⌘K palette, so it
                // registers on the shared HazeState too (wrapped here because NavRail has
                // no modifier parameter and is outside this stick's edit scope).
                Box(Modifier.asGlassSource(glass).testTag(GlassSourceRailTag)) {
                    NavRail(
                        width = metrics.railWidth,
                        selected = state.dest,
                        badgeOf = state::badgeOf,
                        // P1-12：`metrics.twoPane` 是「列表就在详情左边」这个事实的**唯一**
                        // 来源（`ShellMetrics`），交给 state 的重复点 tab 判定 —— 双栏里
                        // 再点一次当前 tab 不再 `back()` 清掉用户正在读的会话。
                        onSelect = { state.select(it, listBesideDetail = metrics.twoPane) },
                    )
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 页面进场：淡入 + 8dp 上浮，≤220ms，reduced-motion 下退化成纯淡入
                    // （见 `PageTransition.kt`）。挂在内容列自己身上 —— 顶栏/底栏不跟着动。
                    .pageEnter(key = pageKey, animate = state.chatPreset == vade.ui.chat.ChatPreset.Live),
            ) {
                if (!metrics.useRail && state.dest == Destination.Sessions) {
                    // g-route-transition（口径变更 2026-11-15）：手机单栏（<600dp）改抽屉模型
                    // —— 详情常驻底层、列表是盖在上面的抽屉（n1-nav：满宽 + 遮罩）。
                    // 双栏与 600–719 rail 单栏仍走 DestinationHost，行为不变。
                    // B2（s-meta-host）：抽屉这条路**不经过** `DestinationHost`，而元数据宿主
                    // 原本只在那里创建 ⇒ 手机上分组/长按会整块丢失。这里在分支内再取一份：
                    // 两条分支互斥（抽屉 / DestinationHost 二选一），不会重复拉 `group.list`。
                    val drawerMetaOverlay = rememberSessionMetaOverlay(
                        adapters = adapters,
                        sessionId = state.selectedSessionId.orEmpty(),
                        onRefreshList = state::retrySessions,
                    )
                    // P1-11：手机空态的「打开会话列表」出口在这里 provide 一次 —— 本分支
                    // 就是**列表抽屉**的宿主，也只有它知道「列表在哪」（`<600dp` 抽屉 vs
                    // 双栏的左侧列表）。下层那份详情由 `SessionsDrawerHost` 自己组合
                    // （`DrawerDetailBase`），它拿不到 `UiState`，所以动作只能由这一层给
                    // （见 `LocalSessionListOpener` 的 KDoc）。
                    // m-bar（审查 P1-1/P1-2/P1-3）：底栏的下留白**不再垫在这个容器上**。
                    // 容器一垫，整棵「详情 + 抽屉」都被切在栏区上沿：抽屉 / 遮罩 / 模型面板 /
                    // 图片预览（全是 `fillMaxSize` 的兄弟）一起被抬离屏底 104–132 dp，遮罩在
                    // 屏底上方硬切一刀，面板开着时底栏还浮在遮罩外面能点。
                    // 现在留白只走内容链：`SessionDetailScreen` 的 `bottomBarInset` 把
                    // [detailContentBottomInset]（= 栏区 − composer 自带的导航条余量，导航条
                    // 只算一次）加在「消息列表 + composer」那一层上；抽屉与浮层留在外面，
                    // 因此永远贴屏底。抽屉展开（屏上是列表）时不垫：它自己的列表把完整
                    // `contentInsets` 当内容留白，行照旧能滚到胶囊后。
                    val detailBarInset = if (detailOpen && !state.sessionsDrawerOpen) {
                        detailContentBottomInset(
                            style = state.barStyle,
                            navInset = navInset,
                            // 与 composer 同一个公式（`ChatThread` 的 composer 浮层用的就是它）：
                            // 键盘收起 = nav，键盘弹起 = 0。同一个数只出现一次。
                            clearance = composerNavClearance(),
                        )
                    } else {
                        0.dp
                    }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(PhoneContentStageTag),
                    ) {
                        CompositionLocalProvider(
                            LocalSessionListOpener provides state::openSessionsDrawer,
                        ) {
                            SessionsDrawerHost(
                                state = state,
                                adapters = adapters,
                                // 抽屉列表一律拿**完整**的 contentInsets（顶栏 + 底栏区）：
                                // 容器不再垫底（见上），末行要靠内容留白自己滚到胶囊之后，
                                // 中段滚动时行穿过栏区 —— 玻璃才有东西可糊（审查 P1-3）。
                                contentInsets = contentInsets,
                                // 底栏的下留白只给详情的内容链（消息列表 + composer）；
                                // 抽屉与遮罩是宿主的兄弟，仍是满高。
                                detailBottomInset = detailBarInset,
                                // 模型面板 / 图片预览开着 ⇒ ShellBody 把内容链抬到底栏之上。
                                onDetailOverlayChange = { detailOverlayOpen.value = it },
                                animate = state.chatPreset == vade.ui.chat.ChatPreset.Live,
                                // B2：分组真相在 state 机（含乐观投影），长按开菜单。
                                metaGroups = drawerMetaOverlay.meta.groups,
                                onLongPress = { session, anchor -> drawerMetaOverlay.openSessionMenu(session, anchor) },
                                groups = drawerMetaOverlay.projectGroups(state.groups()),
                                overlayHost = {
                                    SessionMetaOverlayHost(
                                        overlay = drawerMetaOverlay,
                                        rows = state.groups().flatMap { it.sessions },
                                    )
                                },
                            )
                        }
                    }
                } else {
                    DestinationHost(metrics = metrics, state = state, contentInsets = contentInsets)
                }
            }

            if (metrics.hasRightPanel && state.panelOpen) {
                VerticalRule()
                // Same registration as the rail: the panel is backdrop content for the
                // search overlay (its own surface is opaque; the glass above it blurs it).
                Box(Modifier.asGlassSource(glass).testTag(GlassSourceRightPanelTag)) {
                    RightPanel(onClose = state::togglePanel)
                }
            }
        }

        // --- glass top bar: sibling of the source (haze cannot capture its own layer). ---
        // Inset start by the rail so the bar floats over the content column only, and end
        // by the right panel when it is open (the panel is a full-height backdrop column).
        // The two extents are derived **once** (g-topbar-band): the band below and the
        // capsule share them, so "where the shelf ends" and "where the card is inset"
        // cannot drift apart — a 12dp divergence at the panel edge would leave a sharp
        // sliver of panel content beside the glass.
        val topBarStart = if (metrics.useRail) metrics.railWidth else 0.dp
        val topBarEnd = if (metrics.hasRightPanel && state.panelOpen) 320.dp + 1.dp else 0.dp

        // --- top-bar band (g-topbar-band): the glass shelf the floating capsule sits on. ---
        // Covers the WHOLE top-bar zone — the 8dp above the card and the 8dp below it are
        // the two strips where scrolled content used to show up sharp and readable (D2).
        // Drawn **before** the capsule so it is the card's backdrop, never an overlay: the
        // card's own glass is opaque, so its pixels stay identical to the pre-fix frame.
        // Edge-to-edge on the content column (no 12dp inset, no corner radius): the side
        // margins and the four 28dp corner slivers are part of the leak too.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start = topBarStart, end = topBarEnd)
                .height(TOP_BAR_ZONE),
        ) {
            TopBarBand(Modifier.fillMaxSize())
        }

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(
                    start = topBarStart + 12.dp,
                    end = topBarEnd + 12.dp,
                    top = TopBarTopGap,
                ),
        ) {
            // g-topbar-usage：顶栏统一成**一个** TopBar 调用点。if/else 的两个分支是两个
            // 组合组，detailOpen 翻转会销毁整个 TopBar 实例（AnimatedContent 的过渡状态
            // 随之丢失）——「列表⇄详情、切会话三种情形走同一段淡换」只有单调用点能做到。
            // 标题/副标题/leading/actions 用表达式择值，不引入第二个组合组。
            val listSubtitle = buildString {
                append(metrics.tier.label)
                if (state.dest == Destination.Sessions) append(" · ${state.totalSessions} 个会话")

            }
            // 顶栏跟随 **detailVisible**（不是 detailOpen）：手机上详情被抽屉盖住时，顶栏必须回到
            // 列表 chrome —— 否则顶栏标题与列表里那一行同文案，读屏与测试都会撞成「两个同名节点」，
            // 用户也会看到「抽屉盖着详情、顶栏却写着详情标题」的错位。
            // 用量副标题（g-topbar-usage）：详情态才持有 loader；草稿没有服务端用量，
            // 也不发注定失败的请求 —— 不显示副标题。
            val usageLine: UsageSubtitleLine = when {
                !detailVisible || session == null || session.isDraft -> UsageSubtitleLine.None
                else -> rememberSessionUsageSubtitleLine(state.source, session.id)
            }
            val detailLeading: (@Composable () -> Unit)? = if (detailVisible) {
                // n1-nav：返回 = 手机抽屉模型下「滑出列表、选中保留」（`back(drawerModel = true)`）；
                // 600–719 rail 没有抽屉，仍是清选中回列表根。与系统返回 / 返回手势 / Esc
                // 走同一个 UiState.back —— 四个入口不是一个按钮一套语义。
                {
                    TopBarButton(
                        VadeIcons.Back,
                        onClick = { state.back(drawerModel = !metrics.useRail) },
                        contentDescription = "返回",
                    )
                }
            } else null
            // m-bar：动作栏收敛成**一个** lambda（不再 if/else 两个组合组）。
            //  - 「主页」：不在会话列表根时出现（会话详情 / 其他三个 tab），回到会话 tab；
            //    手机单栏的详情里它就是「离开会话回列表」的第二出口（第一个是返回）。
            //  - 「用量」按钮**刻意不画**：仓里唯一显示用量的是 `ActivityScreen` 里那一段
            //    内嵌的 `UsagePanel` / `UsageSectionLive`（还带会话范围选择器），既没有独立
            //    路由、也没有能从顶栏打开的悬浮宿主。接它要么新造一个面板、要么把「用量」
            //    伪装成「活动」页入口 —— 两样都不该在本棒做，所以按钮不存在（缺的是
            //    「用量」页 + 一个可复用宿主，不是一个假面板）。
            // 审查 P2-2：判据用 **detailVisible**（不是 detailOpen）—— 抽屉展开时屏上是列表，
            // 这时再画「主页」就成了一个点了看不见任何变化、却把抽屉背后那个会话的选中清掉的
            // 静默破坏性按钮。detailVisible 已经含「抽屉收回」这一项，三种形态（详情可见 /
            // 列表根 / 抽屉展开）因此各只有一个正确答案。
            val showHome = state.dest != Destination.Sessions || detailVisible
            val actions: (@Composable RowScope.() -> Unit) = {
                if (showHome) {
                    TopBarButton(
                        HomeIcon,
                        onClick = {
                            switchDestination(
                                state,
                                Destination.Sessions,
                                listBesideDetail = metrics.twoPane,
                                phoneDrawer = !metrics.useRail,
                            )
                        },
                        contentDescription = "主页",
                    )
                }
                if (state.dest == Destination.Sessions && !detailVisible) {
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
            }
            TopBar(
                title = if (detailVisible && session != null) session.title else state.dest.label,
                subtitle = when {
                    !detailVisible -> listSubtitle
                    usageLine is UsageSubtitleLine.Text -> usageLine.text
                    usageLine is UsageSubtitleLine.Retry -> usageLine.label
                    else -> null // 加载中 / 无数据 / 草稿：不显示副标题（绝不编数字）
                },
                onSubtitleClick = (usageLine as? UsageSubtitleLine.Retry)?.onRetry,
                leading = detailLeading,
                actions = actions,
            )
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
                    // m-bar：底栏在手机单栏**常驻**（会话详情也在），所以点 tab 必须先离开
                    // 会话 —— 走的是既有导航路径（`switchDestination` → `state.select`，换
                    // 目的地清选中、重复点会话 tab 由 `select` 自己 `back()`），不弹确认框，
                    // 也不碰任何 `ChatState`（草稿由 ChatState 侧负责，这里只切选中 id）。
                    // `listBesideDetail` 仍显式传同一条判据（P1-12）：本栏只在单栏出现，
                    // 恒为 false，但日后放进双栏时不会悄悄退回「再点一次就关掉会话」。
                    onSelect = { index ->
                        switchDestination(
                            state,
                            Destination.entries[index],
                            listBesideDetail = metrics.twoPane,
                            // 手机抽屉模型：重复点会话 tab = 滑出列表、选中保留。
                            phoneDrawer = !metrics.useRail,
                        )
                    },
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
 * 连不上时那张「自救卡」的正文：每端一条**可整行照抄**的启动命令/示例 + 配对码从哪取。
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
 *  - Android / Wasm 的通道与桌面不同，各给一条自己的命令/示例，不在桌面命令里假装统一。
 *
 * g-rescue-text（根治键名漂移）：下面三条命令里的**键名与模式词全部派生自单一真相**——
 * 裸键取 [SourceKeys]（Android actual 逐键读它、wasm query 语法用它，同一张表），模式词
 * 取 [SourceConfig.REAL]（[SourceConfig.parse] 只认这个词）。之前 Android 那句是手写的
 * `--es vade.source real --es vade.pair_code <码>`，而解析器读的是裸键 `source` /
 * `pair_code`——用户照抄 → extras 里根本没有解析器要找的键 → **静默回落假数据**（审计
 * G4，两次复现）。键名从此不再手写；守卫测试 `SelfRescueRemedyGuardTest` 钉住
 * 「文案里的键集合 == [SourceKeys.acceptedKeys]」与「照抄命令 → parse 得真源」。
 */
internal fun selfRescueRemedy(raw: RawSourceStrings): String {
    val host = hostOf(raw)
    val port = portOf(raw)
    val desktop = "VADE_SOURCE=${SourceConfig.REAL} VADE_HOST=$host VADE_PORT=$port " +
        "VADE_PAIR_CODE=<配对码> gradle :composeApp:run"
    val android = "adb shell am start -n vade.ui/.MainActivity " +
        "--es ${SourceKeys.SOURCE} ${SourceConfig.REAL} " +
        "--es ${SourceKeys.HOST} $host --es ${SourceKeys.PORT} $port " +
        "--es ${SourceKeys.PAIR_CODE} <配对码>"
    val wasm = "?${SourceKeys.SOURCE}=${SourceConfig.REAL}&${SourceKeys.HOST}=$host" +
        "&${SourceKeys.PORT}=$port&${SourceKeys.PAIR_CODE}=<配对码>"
    val expired = if (raw.pairCode != null) {
        "已经给了配对码仍连不上：一次性码可能已用或过期，回 core 重新取一个。\n"
    } else {
        ""
    }
    return expired +
        "用环境变量重连（桌面照抄这一行）：\n" +
        desktop + "\n" +
        "配对码 = core 启动后 stdout 里 `pair_code=` 那一行。\n" +
        "Android 照抄这一行：\n" +
        android + "\n" +
        "Wasm 照抄这段 query（拼在页面 URL 后）：\n" +
        wasm
}

@Composable
private fun DestinationHost(
    metrics: ShellMetrics,
    state: UiState,
    contentInsets: PaddingValues,
) {
    // 接线（g-activate）：与 [ShellBody] 同一处推导，四处屏幕共用这一个值。
    val adapters = adaptersOf(state)
    // g-nav-stack · ≥600dp 的详情也是一层返回（G1：页面级 push 在 Android 上按返回会直接
    // 退出应用）：双栏右栏与 600–719 rail 单栏的详情都由这里登记，返回 = `state.back()`
    // 清选中 —— 与手机单栏抽屉里那一层、与顶栏「返回」按钮完全同一个动作。
    // 手机单栏（<600dp）走 `SessionsDrawerHost`，不经过本函数，那一层由它自己登记。
    // 放在 when 之前：栈序 = 组合序，页面层必须先于详情屏内部的浮层（模型选择）入栈。
    BackLayer(
        enabled = state.dest == Destination.Sessions && state.selectedSessionId != null,
        // 双栏 / rail 没有抽屉：`back()` 的默认档（清选中回列表根）。
        onBack = { state.back() },
    )
    // B2（s-meta-host）：一个元数据宿主喂两处 —— 会话行上的「组名」（g-meta-groups）
    // 与行**长按**菜单 / 分组管理（本棒挂载）。构造与「什么时候不拉表」的规则在
    // meta/SessionMetaOverlay.kt；动作成功后经 `retrySessions` 重拉权威列表收敛。
    val metaOverlay = rememberSessionMetaOverlay(
        adapters = adapters,
        sessionId = state.selectedSessionId.orEmpty(),
        onRefreshList = state::retrySessions,
    )
    // g-error-retry: one slot per READ (list vs thread), each with its own retry entry.
    // The list slot only exists while that read is not Ready — otherwise the 假数据 note in
    // SessionsScreen (fixture-path copy) would be hidden for no reason.
    // A4-1：新建会话的失败横幅折进同一个列表槽（夹具路径四个条件全 Ready/null ⇒ 槽仍为
    // null，行为逐字节不变；真源路径失败时两条横幅各自渲染、各自有重试钮）。
    val sessionListBanner: (@Composable () -> Unit)? =
        if (state.sessionsPhase == LoadPhase.Ready && state.sessionsError == null &&
            state.newSessionPhase == LoadPhase.Ready && state.newSessionError == null
        ) {
            null
        } else {
            {
                SessionListLoadBanner(
                    phase = state.sessionsPhase,
                    error = state.sessionsError,
                    onRetry = state::retrySessions,
                )
                NewSessionLoadBanner(
                    phase = state.newSessionPhase,
                    error = state.newSessionError,
                    onRetry = { state.newSession() },
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
            // B2（s-meta-host）：权威分组 + 元数据投影 —— 状态机的乐观值（标题/置顶/归档/
            // 归属）覆盖在权威行上，失败由状态机回滚、投影跟着回原位；成功后 retrySessions 收敛。
            val authoritativeGroups = state.groups()
            val visibleGroups = metaOverlay.projectGroups(authoritativeGroups)
            val authoritativeRows = authoritativeGroups.flatMap { it.sessions }
            if (metrics.twoPane) {
                Row(Modifier.fillMaxSize()) {
                    // 列表 pane 包一层 Box：悬浮面（菜单/弹层/横条）的 Popup 锚定在**列表**这一侧，
                    // 不飘到详情 pane 上去。
                    Box(Modifier.width(metrics.listWidth)) {
                        SessionsScreen(
                            groups = visibleGroups,
                            selectedId = state.selectedSessionId,
                            twoPane = true,
                            onOpen = { state.open(it.id) },
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = TOP_BAR_ZONE),
                            metaGroups = metaOverlay.meta.groups,
                            onLongPress = { session, anchor -> metaOverlay.openSessionMenu(session, anchor) },
                            onNewSession = { state.newSession() },
                            banner = sessionListBanner,
                            status = status,
                        )
                        SessionMetaOverlayHost(overlay = metaOverlay, rows = authoritativeRows)
                    }
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
                        // P1-11/`compactEmpty`：这一格**左边真有列表**，空态文案是
                        // 「在左侧选择一个会话」。也因此**不传** `onOpenList` —— 双栏没有
                        // 抽屉，给一个「打开会话列表」按钮只会点了没反应。
                        compactEmpty = false,
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
                    // 600–719 的 rail 单栏：没有抽屉（`sessionsDrawerOpen` 在这个断点是
                    // 死状态，见 `UiState` 的 KDoc），列表是 rail 切换到的**根页** ——
                    // 所以这里不 provide/不传任何「打开列表」动作；空态（只可能出现在
                    // 选中 id 已不在列表里的悬挂态）不画一个点了没反应的按钮。
                    compactEmpty = true,
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    SessionsScreen(
                        groups = visibleGroups,
                        selectedId = null,
                        twoPane = false,
                        onOpen = { state.open(it.id) },
                        contentPadding = contentInsets,
                        metaGroups = metaOverlay.meta.groups,
                        onLongPress = { session, anchor -> metaOverlay.openSessionMenu(session, anchor) },
                        onNewSession = { state.newSession() },
                        banner = sessionListBanner,
                        status = status,
                    )
                    SessionMetaOverlayHost(overlay = metaOverlay, rows = authoritativeRows)
                }
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

/**
 * 顶栏「主页」按钮的矢量（m-bar）。
 *
 * `VadeIcons` 里没有 home 语义名，本棒又只许改 AppShell/TopBar（`vade/ui/icon/` 下的两个
 * 文件不在范围内），同时业务代码不许直接 import Tabler
 * （`IconGuardTest.businessCodeDoesNotImportTablerDirectly`）。[MetaIcons] 是仓里唯一已
 * 登记 `home` 的公开缝（服务端图标白名单 39 项之一），于是从这里取；万一哪天白名单收窄，
 * 退回会话 tab 的矢量而不是崩掉。
 */
private val HomeIcon: ImageVector get() = MetaIcons["home"] ?: VadeIcons.Chat

/**
 * 底栏与顶栏「主页」共用的切页入口（m-bar）。
 *
 * 会话详情里点别的 tab 的语义是「**直接离开**会话」：不弹确认框、不在详情里等二次确认。
 * 离开走的是既有导航路径 —— [`UiState.select`] 自己的两条分支已经覆盖全部情形：
 *  - 换目的地 ⇒ 清选中（`destination != Sessions` 时 `selectedSessionId = null`）；
 *  - 点回**会话 tab 本身** ⇒ 它内部就调 `back()`（单栏「退一层」；双栏 `listBesideDetail`
 *    时为 no-op）。
 *
 * 所以这里**不再**显式 `state.back()`（审查 P2-3）：那是纯粹冗余的一步，而且在
 * `listBesideDetail = true` 时反而**先**清掉了选中 —— 把 KDoc 承诺的双栏 no-op 破坏掉。
 * 今天底栏只在单栏组合、这条分支不可达，但 `internal` + 错误 KDoc 就是下一棒的雷。
 *
 * **不碰 ChatState**：本函数只读写 `selectedSessionId` / `dest` / 抽屉开关。草稿、滚动
 * 位置、待发附件都属于 ChatState；`ChatState` 的存活周期由 `SessionDetailScreen` 的
 * `remember(session.id, …)` 决定（草稿跨屏幕存活靠 `DraftStore`，另一棒），本函数既不清空
 * 也不重建它。
 *
 * [listBesideDetail] / [phoneDrawer] 由布局宿主传入（`ShellMetrics`）：双栏里重复点会话
 * tab 是 no-op，不该把用户正在读的会话关掉（P1-12）；手机抽屉里重复点会话 tab =
 * **滑出列表、选中保留**（n1-nav 口径：退回列表不再销毁原来那个会话）。
 */
internal fun switchDestination(
    state: UiState,
    target: Destination,
    listBesideDetail: Boolean,
    phoneDrawer: Boolean = false,
) {
    state.select(target, listBesideDetail = listBesideDetail, phoneDrawer = phoneDrawer)
}

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
 *  - `Esc`: 「返回」的桌面形态。g-nav-stack：**先交给返回栈**（[BackDispatcher.dispatch]），
 *    栈顶那一层消费它（浮层 / 模块页 / 关于 / 详情）。**有返回栈时栈空 = 不消费**：那一层
 *    「没有可退的页面」，返回交还系统（Android 退出 App）—— 手机抽屉开着就是这形态
 *    （用户 2026-11-18 拍板「列表返回 = 退应用」），此时绝不许把 Esc 偷换成 `state.back()`
 *    去清选中。兜底分支只服务没有 dispatcher 的孤立调用（组件预览 / 纯函数测试）。
 *    这也意味着 Esc 不再按断点分叉（旧的「双栏退详情 / 单栏 toggle 抽屉」已删除）。
 *
 * `⌘N`, `⌘1–4`, `⌘B`, `⌘,`, `⌘\` deliberately stay with U1-g: this handler does not touch
 * them, and they still do nothing.
 */
internal fun handleShellKeys(
    event: KeyEvent,
    state: UiState,
    backDispatcher: BackDispatcher? = null,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val search = state.search
    val primary = event.isCtrlPressed || event.isMetaPressed

    if (primary && event.key == Key.K) {
        state.toggleSearch()
        return true
    }

    // g-back-handler: Esc is the desktop shape of "back" — give the back stack first
    // claim. The topmost overlay (search / attachment preview / model picker / a page)
    // closes; when the stack is empty (dispatch() == false) the fallback below stays.
    if (event.key == Key.Escape && backDispatcher?.dispatch() == true) {
        return true
    }

    if (!search.open) {
        // 栈空的兜底**只服务没有返回栈的孤立调用**（`backDispatcher == null`：组件预览、
        // 单独的纯函数测试）。有 dispatcher 时「栈空」是一个确定语义：这一层没有可退的
        // 页面 ⇒ 返回交还系统（Android 允许退出 App）。用户 2026-11-18 拍板「列表返回 =
        // 退应用」正是这个形态（手机抽屉开着 + 有选中 = 0 层）：这条兜底若还认 Esc，就会
        // 把「退应用」偷换成「悄悄清选中」—— 抽屉与选中都不许动，所以这里必须返回 false。
        if (backDispatcher == null && event.key == Key.Escape && state.selectedSessionId != null) {
            // 只有孤立调用才到这里：本函数不知道布局，取「没有抽屉」那一档语义
            // （清选中回列表根）。生产树上详情 / 浮层都由各自登记的层处理。
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
