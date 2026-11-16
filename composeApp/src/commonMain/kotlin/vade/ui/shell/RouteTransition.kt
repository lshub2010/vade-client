package vade.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.data.AdapterProvider
import vade.ui.data.FakeSessionSource
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.destinations.SessionDetailScreen
import vade.ui.destinations.SessionsScreen
import vade.ui.destinations.settledListStatus
import vade.ui.meta.MetaGroup
import vade.ui.skin.LocalSkin

/**
 * g-route-transition · 手机单栏（<600dp）会话页的「抽屉模型」转场（2026-11-15 口径变更，
 * 覆盖此前任务书里的 push 方案）：
 *
 *  - **会话详情是常驻底层**：一旦有选中会话就持续组合，抽屉开合期间**绝不重建**——
 *    聊天流的滚动位置、每会话的 `ChatState` 都跨开合存活（「拉开抽屉看一眼列表」不等于
 *    退出详情页；真正退出详情是返回 → `state.back()`，见下一条）。
 *  - **会话列表是盖在上面的一层抽屉**：宽 80%、右缘 28dp 两个大圆角、自带浮层阴影，
 *    从**左侧**滑出/收回；抽屉与详情之间是遮罩（内容被压暗），点遮罩 = 收抽屉；
 *  - 点一条会话 → 抽屉收回（`UiState.selectSession` 里自动收）、详情露出来；
 *  - **返回只有一条路**（g-nav-stack，见下方 [SessionsDrawerHost] 里的 `BackLayer`）：
 *    系统返回键 / 返回手势 / Esc / 顶栏返回全部由返回栈派发，本页只登记一层 ——
 *    抽屉开着 → 收回（露出底下完好的详情）；抽屉收着 → `state.back()` 清选中回到列表根；
 *    列表根（没有选中会话）不登记层，返回交还系统（允许退出 App）。
 *    旧实现是一条独立的 Android `BackHandler`（`DrawerSystemBackHandler`）+ `if/else` 开关，
 *    按返回在「列表/详情」之间来回 toggle、永远退不出也永远清不掉选中；那条路已删除。
 *  - **双栏（≥600dp）与 600–719（rail 单栏）完全不经过本文件**：`ShellBody` 只在
 *    `!useRail && dest == Sessions` 时组合本宿主，其余断点维持原有行为——不加位移、
 *    不加遮罩（由 `ShellMetrics.useRail` 判定，测试钉死）。
 *
 * ## 「动画期间内容不得重复组合」是怎么保证的
 *
 * 抽屉与详情是**同一个组合里的两个常驻兄弟**，开合只改 [progress]：
 *  - 位移与遮罩透明度都在 `graphicsLayer {}` 块内**延迟读取** `progress.value`——
 *    动画每一帧只走 draw 阶段，宿主（及其两层内容）**一次重组都不会发生**；
 *  - 两层内容没有 `AnimatedContent`/`AnimatedVisibility` 那类「出场即销毁」的包装，
 *    所以抽屉收起后列表仍在组合里（只是平移出屏 + `invisibleToUser`）——这正是
 *    「列表滚动位置在开合后保留」的机制：`SessionsScreen` 的 `LazyColumn` 状态从未
 *    离开组合，自然不会重置；
 *  - `animate = false`（截图/测试开关，同一族 `ChatThread(animate = …)` 的取值）时
 *    [sessionsDrawerSpec] 给出 0ms：开合即时到位，状态机与组合结构完全不变。
 *
 * ## 无障碍
 *  - 抽屉收起后整层 `invisibleToUser`：读屏与 UI 测试都看不到出屏的列表，焦点也不会
 *    落在看不见的行上；
 *  - 抽屉重新滑出 → 焦点回到**原来那一行**（[FocusRequester] 由 `SessionsScreen` 挂在
 *    选中行上；行被滚出视口/尚未选中时安静放弃）；
 *  - 抽屉收起 → 焦点交给详情容器（一个可聚焦的包装 Box），不丢到无处。
 */
internal const val DRAWER_WIDTH_FRACTION = 0.8f

/** 抽屉右缘的两个大圆角（口径变更：约 28dp）。 */
internal val DRAWER_END_CORNER = 28.dp

/** 抽屉浮层阴影（「列表层自带浮层阴影」）。 */
internal val DRAWER_ELEVATION = 16.dp

/** 遮罩的最终透明度（内容被压暗到这个程度）。 */
internal const val DRAWER_SCRIM_ALPHA = 0.45f

internal const val DRAWER_TAG = "sessions-drawer"
internal const val DRAWER_SCRIM_TAG = "sessions-drawer-scrim"

/**
 * 抽屉转场的可断言配置。测试直接调 [sessionsDrawerSpec] 钉住：
 *  - `animate = true` → 时长落在 220–300ms、缓动**非线性**（FastOutSlowIn）；
 *  - `animate = false` → 时长 = 0（「开关关闭时转场时长为 0，内容立即到位」）；
 *  - 本文件没有「双栏分支」——双栏根本不组合本宿主，位移/遮罩在那里不存在，
 *    这一点由 `ShellBody` 的分支条件与 UI 测试共同钉住。
 */
internal data class SessionsDrawerSpec(
    val durationMillis: Int,
    val scrimAlpha: Float,
    val easing: Easing,
)

internal fun sessionsDrawerSpec(animate: Boolean): SessionsDrawerSpec =
    if (animate) {
        SessionsDrawerSpec(durationMillis = 260, scrimAlpha = DRAWER_SCRIM_ALPHA, easing = FastOutSlowInEasing)
    } else {
        SessionsDrawerSpec(durationMillis = 0, scrimAlpha = DRAWER_SCRIM_ALPHA, easing = LinearEasing)
    }

/**
 * 手机单栏会话页宿主：常驻详情（底层）+ 遮罩 + 左滑列表抽屉（顶层）。
 *
 * 参数就是 `ShellBody` 手里已有的三样东西，宿主内部自行完成列表/详情的接线
 * （见 [DrawerSessionList]）：`ShellBody` 的改动被压到一处分支调用。
 */
@Composable
internal fun SessionsDrawerHost(
    state: UiState,
    adapters: AdapterProvider?,
    contentInsets: PaddingValues,
    animate: Boolean,
    /**
     * 测试注入口（同 `UiState.loaderScope` 的先例）：非 null 时替代真实详情层，
     * 供「内容只组合一次」探针使用。生产调用点一律不传。
     */
    base: (@Composable () -> Unit)? = null,
    /** 同 [base]，替代真实列表层。 */
    drawer: (@Composable () -> Unit)? = null,
    /**
     * B2（s-meta-host）：分组表与长按入口由组合根注入 —— 分组真相在
     * `rememberSessionMetaOverlay` 的状态机里（含乐观投影），抽屉层不再自己算。
     * 默认空表 + 空回调 = 只画裸 id、无长按（测试与旧调用点行为不变）。
     */
    metaGroups: List<MetaGroup> = emptyList(),
    onLongPress: (SessionSummary) -> Unit = {},
    /** 同上：列表要画的是**投影后**的分组（乐观改名/置顶/归档立刻可见）。 */
    groups: List<SessionGroup>? = null,
    /**
     * B2：元数据悬浮面（长按菜单 / 分组编辑 / 错误横条）的宿主槽。
     *
     * 必须渲染在**抽屉层内部**：Popup 会以最近的窗口为参照，放进抽屉才能锚在列表那一侧；
     * 挂在整屏上会飘到详情 pane。null = 无宿主（测试注入路径不变）。
     */
    overlayHost: (@Composable () -> Unit)? = null,
) {
    val spec = sessionsDrawerSpec(animate)
    val expanded = state.sessionsDrawerOpen
    val skin = LocalSkin.current

    // g-nav-stack · 本页在返回栈里**恰好一层**，三端一条路（系统返回 / 手势 / Esc / 顶栏返回
    // 都落到这里；不再有第二条 `BackHandler`）：
    //  - 有选中会话（详情在底层）时登记：
    //      抽屉开着 → 收抽屉，详情原样露出来（不销毁）；
    //      抽屉收着 → `state.back()` 清选中，回到列表根（与顶栏「返回」同一动作）。
    //  - 没有选中会话（列表根）→ 不登记：`dispatch()` 返回 false，返回交还系统（可退出 App）。
    //    旧实现在这里会 toggle 抽屉，把用户关进「无详情 + 抽屉收起」的死路（G2）。
    //
    // 位置在 Box **之前**很重要：栈序 = 组合序，页面层必须先于它内部的浮层（模型选择 /
    // 附件预览 / 搜索）入栈，那些浮层才能是 topmost 先被消费。
    BackLayer(enabled = state.selectedSessionId != null) {
        if (state.sessionsDrawerOpen) state.closeSessionsDrawer() else state.back()
    }

    // 转场进度：1 = 抽屉全开，0 = 收起。初值取当前状态，深链直进详情不会播一段开场动画。
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded) {
        val target = if (expanded) 1f else 0f
        if (spec.durationMillis == 0) {
            progress.snapTo(target)
        } else {
            progress.animateTo(target, tween(durationMillis = spec.durationMillis, easing = spec.easing))
        }
    }

    // 无障碍焦点（口径：进出抽屉后焦点能回到原来那一行 / 不丢到无处）。
    val rowFocus = remember { FocusRequester() }
    val baseFocus = remember { FocusRequester() }
    var focusPrimed = remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        // 首次组合不动焦点：根部 rootFocus 的请求是既有行为，不跟它抢。
        if (!focusPrimed.value) {
            focusPrimed.value = true
            return@LaunchedEffect
        }
        if (expanded) {
            // 选中行上挂着 rowFocus（SessionsScreen 的 selectedRowFocus）；无选中或行被滚出
            // LazyColumn 视口时节点不存在，requestFocus 会抛——安静放弃即可。
            runCatching { rowFocus.requestFocus() }
        } else {
            runCatching { baseFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().clipToBounds()) {
        // --- 常驻底层：详情。抽屉开合全程保持组合（数据层/聊天滚动不重建）。 ---
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(baseFocus)
                .focusable(),
        ) {
            base?.invoke() ?: DrawerDetailBase(state = state, adapters = adapters, animate = animate)
        }

        // --- 遮罩：常驻但收起后 alpha=0 且不可点；点它 = 收抽屉。 ---
        // alpha 在 graphicsLayer 块里延迟读取 progress——动画期间这棵子树零重组。
        //
        // J5：可点判据带 `selectedSessionId != null` —— 底下没有详情时点遮罩只会把抽屉收起，
        // 露出手机上没有出路的「在左侧选择一个会话」（无左侧、列表也收走了）。那种状态下
        // 遮罩保持纯视觉压暗，不做「关掉唯一入口」的动作；返回同样不登记层（见上面的 BackLayer）。
        Box(
            Modifier
                .fillMaxSize()
                .testTag(DRAWER_SCRIM_TAG)
                .graphicsLayer { alpha = spec.scrimAlpha * progress.value }
                .background(Color.Black)
                .clickable(
                    enabled = expanded && state.selectedSessionId != null,
                    onClick = state::closeSessionsDrawer,
                )
                .semantics { if (!expanded) invisibleToUser() },
        )

        // --- 列表抽屉：80% 宽、右缘 28dp 圆角、浮层阴影，从左侧滑出。 ---
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(DRAWER_WIDTH_FRACTION)
                // 位移同样延迟读取：1 - p × 自身宽。p=0 完全出屏（配合宿主 clipToBounds）。
                .graphicsLayer { translationX = -(1f - progress.value) * size.width }
                .testTag(DRAWER_TAG)
                .shadow(elevation = DRAWER_ELEVATION, shape = drawerShape())
                .clip(drawerShape())
                .background(skin.colors.surface)
                .semantics { if (!expanded) invisibleToUser() },
        ) {
            drawer?.invoke() ?: DrawerSessionList(
                state = state,
                adapters = adapters,
                contentInsets = contentInsets,
                rowFocus = rowFocus,
                metaGroups = metaGroups,
                onLongPress = onLongPress,
                groups = groups,
            )
            // B2（s-meta-host）：菜单/分组编辑/回滚横条的宿主必须在抽屉层里，
            // 否则长按后状态机变了却没有东西渲染弹出面（本棒合并时踩到的半个洞）。
            overlayHost?.invoke()
        }
    }
}

/** 抽屉层形状：只有右缘两个大圆角（28dp），左缘贴屏幕直角。 */
internal fun drawerShape(): RoundedCornerShape =
    RoundedCornerShape(topEnd = DRAWER_END_CORNER, bottomEnd = DRAWER_END_CORNER)

/**
 * 常驻底层的详情接线（从 `ShellBody` 的 pushed-detail 分支原样搬来）：full-bleed、
 * TOP_BAR_ZONE 作为 content padding、顶栏画标题所以这里 `showTitle = false`。
 */
@Composable
private fun DrawerDetailBase(
    state: UiState,
    adapters: AdapterProvider?,
    animate: Boolean,
) {
    SessionDetailScreen(
        session = state.selectedSession,
        preset = state.chatPreset,
        animate = animate,
        // 顶栏（列表 chrome 或详情 chrome）由 ShellBody 画，这里永远不重复画标题。
        showTitle = false,
        adapters = adapters,
        contentPadding = PaddingValues(top = TOP_BAR_ZONE),
    )
}

/**
 * 抽屉里的会话列表。接线与 `DestinationHost` 的列表分支同源同值（metaGroupsOf /
 * listStatusOf / 失败横幅），但这是**抽屉自己的组合上下文**——DestinationHost 的
 * 会话分支仍服务双栏与 600–719 rail 单栏，两处不能合并（合并 = 跨断点改布局）。
 */
@Composable
private fun DrawerSessionList(
    state: UiState,
    adapters: AdapterProvider?,
    contentInsets: PaddingValues,
    rowFocus: FocusRequester,
    metaGroups: List<MetaGroup>,
    onLongPress: (SessionSummary) -> Unit,
    groups: List<SessionGroup>?,
) {
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
    val status = settledListStatus {
        listStatusOf(
            fixture = state.source is FakeSessionSource,
            phase = state.sessionsPhase,
            error = state.sessionsError,
            hasRows = state.sessionRows.isNotEmpty(),
        )
    }
    SessionsScreen(
        groups = groups ?: state.groups(),
        selectedId = state.selectedSessionId,
        twoPane = false,
        onOpen = { state.open(it.id) },
        contentPadding = contentInsets,
        metaGroups = metaGroups,
        onLongPress = onLongPress,
        banner = sessionListBanner,
        status = status,
        // 抽屉重新滑出时焦点回到原来那一行（无障碍口径）。
        selectedRowFocus = rowFocus,
    )
}
