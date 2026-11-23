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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import vade.ui.data.AdapterProvider
import vade.ui.data.FakeSessionSource
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.data.UnpairedSessionSource
import vade.ui.destinations.SessionDetailScreen
import vade.ui.destinations.SessionsScreen
import vade.ui.destinations.settledListStatus
import vade.ui.meta.MetaGroup
import vade.ui.skin.LocalReduceMotion
import vade.ui.skin.LocalSkin
import vade.ui.skin.ReduceMotion
import vade.ui.skin.SkinMotion

/**
 * g-route-transition · 手机单栏（<600dp）会话页的「抽屉模型」转场（2026-11-15 口径变更，
 * 覆盖此前任务书里的 push 方案）：
 *
 *  - **会话详情是常驻底层**：一旦有选中会话就持续组合，抽屉开合期间**绝不重建**——
 *    聊天流的滚动位置、每会话的 `ChatState` 都跨开合存活（「拉开抽屉看一眼列表」不等于
 *    退出详情页；真正退出详情是离开会话页，见下一条）。
 *  - **会话列表是盖在上面的一层抽屉**：**满宽**（n1-nav 口径：手机单栏与未选中时的列表
 *    同宽，没有 peek —— 0.8 的 peek 只属于「列表与详情并排」的双栏），从**左侧**滑出/收回；
 *    抽屉与详情之间是遮罩（内容被压暗），点遮罩 = 收抽屉；
 *  - 点一条会话 → 抽屉收回（`UiState.selectSession` 里自动收）、详情露出来；
 *  - **返回只有一条路**（g-nav-stack，见下方 [SessionsDrawerHost] 里**唯一**的一层 `BackLayer`）：
 *    系统返回键 / 返回手势 / Esc / 顶栏返回全部由返回栈派发。n1-nav 口径 + 用户 2026-11-18
 *    拍板：详情（抽屉收）→ 返回 = **打开抽屉，选中保留**（`back(drawerModel = true)` 的前半）；
 *    列表（抽屉开）→ **不登记任何层** ⇒ `dispatch()` 返回 false，返回交还系统、App 退出
 *    （手机抽屉模型下它是最后一层），抽屉与选中一条都不改。会话页列表根（无选中）同样 0 层。
 *    旧实现是一条独立的 Android `BackHandler`（`DrawerSystemBackHandler`）+ `if/else` 开关，
 *    按返回在「列表/详情」之间 toggle、永远退不出也永远清不掉选中；那条路已删除。
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
 *    所以抽屉**第一次打开之后**列表就一直留在组合里（收起时只是平移出屏 + `invisibleToUser`）
 *    ——这正是「列表滚动位置在开合后保留」的机制：`SessionsScreen` 的 `LazyColumn` 状态
 *    从未离开组合，自然不会重置。**首次打开之前**列表不组合（H11：冷启动 / 深链直进详情
 *    时别为看不见的列表付组合 + 测量；见 [SessionsDrawerHost] 里 `if (expanded ||
 *    drawerEverOpened.value)` 的注释）。锁存是单向的，所以动画期间组合结构不抖动；
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
/**
 * 手机单栏抽屉宽度 = **满宽（1f）**。
 *
 * n1-nav（真机反馈 #1）：未选中会话时列表是满宽的一页，选中后再退回列表却缩成 80% ——
 * 同一个页面两种宽度。手机单栏没有「露出底下的详情」这回事（详情被完全盖住了才有意义），
 * 所以抽屉恒 1f，与未选中时的列表同宽。
 *
 * 0.8 的 **peek** 只在「列表与详情并排」的双栏形态里才是设计意图（列表不用盖满，右缘
 * 圆角露出详情的边）；那种形态**不经过本宿主**（`ShellBody` 只在 `!useRail` 时组合它），
 * 见 [DRAWER_PEEK_FRACTION]。
 */
internal const val DRAWER_WIDTH_FRACTION = 1f

/**
 * 双栏（peek）抽屉宽度口径 —— 保留常量是为了让「peek 只属于双栏」这条裁定可断言
 * （`drawerShape` / `drawerElevation` 的 peek 分支），手机单栏恒 [DRAWER_WIDTH_FRACTION]。
 */
internal const val DRAWER_PEEK_FRACTION = 0.8f

/** 抽屉右缘的两个大圆角（口径变更：约 28dp）——只在 peek（<满宽）时出现。 */
internal val DRAWER_END_CORNER = 28.dp

/** 抽屉浮层阴影（「列表层自带浮层阴影」）——满宽时没有「浮」可言，取 0。 */
internal val DRAWER_ELEVATION = 16.dp

/** 遮罩的最终透明度（内容被压暗到这个程度）。 */
internal const val DRAWER_SCRIM_ALPHA = 0.45f

internal const val DRAWER_TAG = "sessions-drawer"
internal const val DRAWER_SCRIM_TAG = "sessions-drawer-scrim"

/** 抽屉滑行时长（口径 260ms；reduced-motion 下位移归零，见 [sessionsDrawerSpec]）。 */
internal const val DRAWER_SLIDE_MS = 260

/**
 * 抽屉的「位移需求」。它整层横移（满宽，见 `translationX`），所以这里问的是
 * [SkinMotion.travelPx] 这个**开关**而不是某个具体像素：reduced 下归零 = 不许位移。
 */
private const val DRAWER_TRAVEL_PX = 1

/**
 * 抽屉转场的可断言配置。测试直接调 [sessionsDrawerSpec] 钉住：
 *  - `animate = true` → 时长落在 220–300ms、缓动**非线性**（FastOutSlowIn）；
 *  - `animate = false` → 时长 = 0（「开关关闭时转场时长为 0，内容立即到位」）；
 *  - `reduceMotion = true` → 时长 = 0（K1：这是一段**纯位移**转场，`travelPx` 归零就等于
 *    「没有位移」，再留 100ms 滑行反而是前庭敏感读者唯一会看到的东西 —— 所以直接 snap，
 *    与 `animate = false` 走同一条 0ms 分支，状态机与组合结构不变）；
 *  - 本文件没有「双栏分支」——双栏根本不组合本宿主，位移/遮罩在那里不存在，
 *    这一点由 `ShellBody` 的分支条件与 UI 测试共同钉住。
 *
 * 时长与位移都过 [SkinMotion] 的 K1 门（`durationMs` / `travelPx`），本文件不再留裸数字。
 * `motion` 默认是 [SkinMotion] 的默认 token 集（`Skin.motion` 的默认实现值），
 * 调用点传 `LocalSkin.current.motion` 即可随皮肤走。
 */
internal data class SessionsDrawerSpec(
    val durationMillis: Int,
    val scrimAlpha: Float,
    val easing: Easing,
)

internal fun sessionsDrawerSpec(
    animate: Boolean,
    reduceMotion: Boolean = false,
    motion: SkinMotion = SkinMotion(),
): SessionsDrawerSpec {
    // reduced 下 travelPx(requested) = 0 ⇒ 「这次转场没有位移」，等价于立即到位。
    val travels = motion.travelPx(DRAWER_TRAVEL_PX, reduceMotion) > 0
    val duration =
        if (animate && travels) motion.durationMs(DRAWER_SLIDE_MS, reduceMotion) else 0
    return SessionsDrawerSpec(
        durationMillis = duration,
        scrimAlpha = DRAWER_SCRIM_ALPHA,
        easing = if (duration == 0) LinearEasing else FastOutSlowInEasing,
    )
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
     * 常驻详情**内容链**（消息列表 + composer）的下内边距（m-bar · 审查 P1-1/P1-2）。
     *
     * 由 `AppShell` 算好传下来（`detailContentBottomInset`：栏区 − composer 自带的导航条
     * 余量，导航条只算一次）。**不是容器留白**：抽屉与遮罩是本宿主的 `fillMaxSize` 兄弟，
     * 永远贴屏底；只有详情内部那条链让位给底栏。抽屉展开时传 0 dp（屏上是列表，详情被盖住，
     * 且列表自己用 [contentInsets] 让行滚到胶囊后）。
     */
    detailBottomInset: Dp = 0.dp,
    /**
     * 详情里的模态浮层（模型面板 / 图片预览）开合上报（m-bar · 审查 P1-2），由 `AppShell`
     * 用来把内容链压到底栏之上。默认 null = 不上报（测试与旧调用点行为不变）。
     */
    onDetailOverlayChange: ((Boolean) -> Unit)? = null,
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
    /**
     * 行**长按**入口（alpha J3 起带锚点）：第二个参数 = 该行此刻在窗口里的矩形，菜单
     * 贴行渲染而不是钉在窗格右上角。默认空回调 = 无长按（测试与旧调用点行为不变）。
     */
    onLongPress: (SessionSummary, IntRect) -> Unit = { _, _ -> },
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
    val skin = LocalSkin.current
    val expanded = state.sessionsDrawerOpen
    // K1 · 动画门：宿主级偏好（桌面 `--reduce-motion` / Android 动画缩放 0 / wasm
    // `prefers-reduced-motion`）与应用内设置开关取 **OR** —— 任一为 true 就降级
    // （Skin.kt 里 `ReduceMotion` 的既定读法，不能只读 CompositionLocal）。
    val reduceMotion = LocalReduceMotion.current || ReduceMotion.enabled
    val spec = sessionsDrawerSpec(
        animate = animate,
        reduceMotion = reduceMotion,
        motion = skin.motion,
    )

    // g-nav-stack · 「能关几次 = 注册几层」：本宿主**最多只有一层**，且它只在「详情在屏、
    // 抽屉收起」时存在 —— 层数恒等于此刻可退的层级数，不多也不少。
    //  - 详情层：有选中会话且抽屉收着时登记，返回 = `back(drawerModel = true)` ——
    //    **滑出列表抽屉、选中保留**（旧实现在这里是 `back()` 清选中，真机反馈 #2 的原缺陷）；
    //  - 抽屉开着 ⇒ **这一层自己出栈**（`enabled` 落 false ⇒ depth 0）。列表是本页的最后一层：
    //    用户 2026-11-18 拍板「列表返回 = 退应用」，`dispatch()` 返回 false ⇒ 系统返回交还
    //    系统（Android 退出 App），抽屉与选中一条都不改。旧实现的第二层（抽屉层，onBack 只收
    //    抽屉）已删除 —— 它让「列表返回」永远退不出也永远吃不掉最后一次返回。
    //  - 列表根（没有选中会话）：0 层，同样交还系统。
    //
    // 关键：`enabled` 必须**随 `sessionsDrawerOpen` 变化重新登记/出栈**（`BackLayer` 的
    // `DisposableEffect(dispatcher, enabled)` 键里带 enabled）。抽屉一开就得 depth = 0，
    // 否则栈顶那层会把返回吃掉、`dispatch()` 返回 true，「退应用」变成「按了没反应」。
    //
    // 位置在 Box **之前**很重要：栈序 = 组合序，页面层必须先于它内部的浮层（模型选择 /
    // 附件预览 / 搜索）入栈，那些浮层才能是 topmost 先被消费。
    BackLayer(
        // 只在「详情在屏、抽屉收起」时登记：返回 = 滑出列表（选中保留）。
        // 抽屉已经打开时不登记 —— 列表是这一栈的最后一层，系统返回直接退应用
        // （用户 2026-11-18 拍板：列表返回退应用）。
        enabled = state.selectedSessionId != null && !state.sessionsDrawerOpen,
        onBack = { state.back(drawerModel = true) },
    )

    // 转场进度：1 = 抽屉全开，0 = 收起。初值取当前状态，深链直进详情不会播一段开场动画。
    // `spec.durationMillis` 进 key：运行中把 reduced-motion 打开（系统设置 / 设置页开关）
    // 会以 0ms 重新落位，不会把已在跑的滑行留在半路。
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    LaunchedEffect(expanded, spec.durationMillis) {
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
        //
        // `LocalDetailCovered`：抽屉展开时详情还在这棵树里，但屏上是列表 —— 详情内部的
        // 模态（问答卡）据此让出 Esc（见 `BackDispatch.kt` 的 KDoc）。
        CompositionLocalProvider(LocalDetailCovered provides expanded) {
            Box(
                Modifier
                    .fillMaxSize()
                    .focusRequester(baseFocus)
                    .focusable(),
            ) {
                base?.invoke() ?: DrawerDetailBase(
                    state = state,
                    adapters = adapters,
                    animate = animate,
                    bottomInset = detailBottomInset,
                    onOverlayChange = onDetailOverlayChange,
                )
            }
        }

        // --- 遮罩：常驻但收起后 alpha=0 且不可点；点它 = 收抽屉。 ---
        // alpha 在 graphicsLayer 块里延迟读取 progress——动画期间这棵子树零重组。
        //
        // J5：可点判据带 `selectedSessionId != null` —— 底下没有详情时点遮罩只会把抽屉收起，
        // 露出手机上没有出路的「在左侧选择一个会话」（无左侧、列表也收走了）。那种状态下
        // 遮罩保持纯视觉压暗，不做「关掉唯一入口」的动作；返回同样不登记层（见上面两层
        // BackLayer 的 enabled：抽屉层要求底层有详情）。
        Box(
            Modifier
                .fillMaxSize()
                .testTag(DRAWER_SCRIM_TAG)
                .graphicsLayer { alpha = spec.scrimAlpha * progress.value }
                .background(Color.Black)
                // 收起时**完全不挂 pointerInput**：`clickable(enabled=false)` 仍是一个
                // 命中测试节点，会挡住底下的空态按钮（真机/真组合里就是「点了没反应」）。
                // 只有展开且底层有详情时才可点；其余时候这层是纯视觉压暗。
                .then(
                    if (expanded && state.selectedSessionId != null) {
                        Modifier.clickable(onClick = state::closeSessionsDrawer)
                    } else {
                        Modifier
                    },
                )
                .semantics { if (!expanded) invisibleToUser() },
        )

        // --- 列表抽屉：**满宽**（n1-nav 口径）、从左侧滑出。 ---
        // 满宽 ⇒ 没有「浮起的抽屉」可言：peek 的右缘圆角与浮层阴影只属于 <满宽的形态
        // （`drawerShape(fraction)` / `drawerElevation(fraction)`），否则会在屏右缘露出
        // 两条详情颜色的缝。
        //
        // H11 · 抽屉**收起且从未打开过**时，列表层不组合（见下面 `if` 的注释）。
        // 锁存用普通 `mutableStateOf` + `SideEffect`：`expanded` 只增不减地记「开过」，动画期间
        // 条件不会抖动（`expanded` 为真的那一帧起本层已经在组合里）。
        val drawerEverOpened = remember { mutableStateOf(expanded) }
        SideEffect { if (expanded) drawerEverOpened.value = true }
        val widthFraction = DRAWER_WIDTH_FRACTION
        val shape = drawerShape(widthFraction)
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(widthFraction)
                // 位移同样延迟读取：1 - p × 自身宽。p=0 完全出屏（配合宿主 clipToBounds）。
                .graphicsLayer { translationX = -(1f - progress.value) * size.width }
                .testTag(DRAWER_TAG)
                .shadow(elevation = drawerElevation(widthFraction), shape = shape)
                .clip(shape)
                .background(skin.colors.surface)
                .semantics { if (!expanded) invisibleToUser() },
        ) {
            // H11：**开过一次之后常驻**，之前不组合。
            //
            //  - 为什么开过之后不能拆：列表滚动位置靠 `SessionsScreen` 内部的
            //    `rememberLazyListState()` 不离开组合来保留（文件头 KDoc：这正是
            //    「开合后回到原来那一行」的机制）。拆掉 = 每次收起都把位置清零。
            //  - 为什么打开前能省：收起时整层被 `translationX` 推出屏，但 `LazyColumn`
            //    照样组合 + 测量全部可见行（手机约 12 行 + 组头），`groups` 每变一次
            //    再测量一遍 —— 冷启动 / 深链直进详情 / 截图环都在付这笔账，而用户
            //    在这之前看不到列表。
            //  - 为什么条件不是 `progress.value > 0f`：那会在动画期间每帧翻转组合结构
            //    （入场时反复拆装列表），比现在的代价大得多。锁存是**单向**的。
            if (expanded || drawerEverOpened.value) {
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
}

/**
 * 抽屉层形状：**peek（<满宽）** 时只有右缘两个大圆角（28dp）、左缘贴屏幕直角；
 * 满宽（手机单栏，[DRAWER_WIDTH_FRACTION]）时是直角矩形 —— 屏幕边缘没有可露出的底层，
 * 留圆角只会在右上/右下露出两条详情颜色的缝。
 */
internal fun drawerShape(widthFraction: Float = DRAWER_WIDTH_FRACTION): RoundedCornerShape =
    if (widthFraction >= 1f) {
        RoundedCornerShape(0.dp)
    } else {
        RoundedCornerShape(topEnd = DRAWER_END_CORNER, bottomEnd = DRAWER_END_CORNER)
    }

/**
 * 抽屉的浮层阴影：只有 peek（<满宽）才「浮」在详情上；满宽时列表就是一页，
 * 阴影取 0（阴影落在屏幕边缘外，只会给右缘描一条脏边）。
 */
internal fun drawerElevation(widthFraction: Float = DRAWER_WIDTH_FRACTION): Dp =
    if (widthFraction >= 1f) 0.dp else DRAWER_ELEVATION

/**
 * 常驻底层的详情接线（从 `ShellBody` 的 pushed-detail 分支原样搬来）：full-bleed、`TOP_BAR_ZONE`
 * 作为 content padding、顶栏画标题所以这里 `showTitle = false`。
 *
 * m-bar（审查 P1-2）：底栏的下留白经 [bottomInset] 只落在详情的内容链上（composer / 消息
 * 列表），宿主的抽屉与遮罩仍是满高；[onOverlayChange] 让页面把「模态浮层开着」报回
 * `AppShell`（底栏让路）。
 */
@Composable
private fun DrawerDetailBase(
    state: UiState,
    adapters: AdapterProvider?,
    animate: Boolean,
    bottomInset: Dp,
    onOverlayChange: ((Boolean) -> Unit)?,
) {
    SessionDetailScreen(
        session = state.selectedSession,
        preset = state.chatPreset,
        animate = animate,
        // 顶栏（列表 chrome 或详情 chrome）由 ShellBody 画，这里永远不重复画标题。
        showTitle = false,
        adapters = adapters,
        contentPadding = PaddingValues(top = TOP_BAR_ZONE),
        bottomBarInset = bottomInset,
        onOverlayChange = onOverlayChange,
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
    onLongPress: (SessionSummary, IntRect) -> Unit,
    groups: List<SessionGroup>?,
) {
    // 与 DestinationHost 共用同一个构造函数（审查 P1-2）：抽屉这份曾只挂列表横幅，
    // 手机 <600dp 上「新建会话」失败 / 创建中没有 UI 消费（点了没反应）。
    val sessionListBanner: (@Composable () -> Unit)? = sessionListBannersOf(state)
    val status = settledListStatus {
        listStatusOf(
            fixture = state.source is FakeSessionSource,
            phase = state.sessionsPhase,
            error = state.sessionsError,
            hasRows = state.sessionRows.isNotEmpty(),
            unpaired = state.source is UnpairedSessionSource,
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
        // J1 · 空态卡上的唯一主行动 —— 与顶栏 ✎ 同一个入口。
        onNewSession = { state.newSession() },
        banner = sessionListBanner,
        status = status,
        // 抽屉重新滑出时焦点回到原来那一行（无障碍口径）。
        selectedRowFocus = rowFocus,
    )
}
