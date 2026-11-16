package vade.ui.module

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import vade.ui.UiText
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.AttachmentState
import vade.ui.skin.AttachmentView
import vade.ui.skin.BtnStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.ToolCardState
import vade.ui.skin.ToolCallView
import vade.ui.shell.BackLayer

/**
 * C-19 模块页面的原生渲染层：解析校验（[ModulePageParser]，fail-closed）→ 绑定解析
 * （[ModulePageResolver]，三根只读绑定）→ 本文件的机械映射 ResolvedNode → Compose。
 *
 * 硬红线：
 *  - **没有 WebView**——全部是 Compose 原生组件 + `Skin` 的 C-09 白名单组件；
 *  - 4 个宿主组件（MessageBubble / InputBar / ApprovalDialog / BottomBar）在解析期整页拒渲，
 *    渲染层根本拿不到它们；
 *  - `Switch` 宿主不本地翻转（checked 只来自绑定，翻转只发动作）；`TextField` 只写本地
 *    `$input` 内存（N2：不做双向绑定）；动作参数只有字面量 / bind / input 三种来源。
 *
 * 视觉（docs/visual-language.md §12「模块页面」行）：页面外壳 `xl` 圆角 + 发丝线 +
 * 极浅分层阴影；模块卡片 `lg`；绑定值缺失显示空值不崩（§5.4）。
 */

/** C-19 页面图标枚举 → 语义矢量；`None` 不画图标（§4.2）。 */
private fun PageIcon.vectorOrNull(): ImageVector? = when (this) {
    PageIcon.List -> VadeIcons.PageList
    PageIcon.Chart -> VadeIcons.PageChart
    PageIcon.Settings -> VadeIcons.PageSettings
    PageIcon.Tool -> VadeIcons.PageTool
    PageIcon.File -> VadeIcons.PageFile
    PageIcon.None -> null
}

// ---------------------------------------------------------------------------
// 入口列表（能力页「模块页面」区块的内容）
// ---------------------------------------------------------------------------

/**
 * 能力页的入口列表：每个**声明了 `ui.pages[]`** 的模块一张 `lg` 卡，卡内每页一行
 * （模块 + 页面标题，§8.1）；版本门控的页面只给一行「需要更新的客户端」说明。
 * 调用方（`CapabilitiesScreen`）在 [ModuleEntryIndex.isEmpty] 时**不渲染本区块**。
 */
@Composable
fun ModulePageEntryList(
    index: ModuleEntryIndex,
    onOpen: (ModulePageEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (group in index.groups) {
            ModulePageCard(shape = RoundedCornerShape(skin.shapes.lg)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    UiText(
                        group.moduleTitle,
                        size = 15,
                        weight = FontWeight.SemiBold,
                        color = skin.colors.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    for (entry in group.entries) {
                        skin.ListItem(
                            title = entry.pageTitle,
                            subtitle = null,
                            leading = entry.icon.vectorOrNull()?.let { vector ->
                                {
                                    VadeIcon(
                                        vector,
                                        contentDescription = null,
                                        size = 20.dp,
                                        tint = skin.colors.onSurfaceVariant,
                                    )
                                }
                            },
                            trailing = {
                                VadeIcon(
                                    VadeIcons.ChevronRight,
                                    contentDescription = null,
                                    size = 20.dp,
                                    tint = skin.colors.onSurfaceVariant,
                                )
                            },
                            onClick = { onOpen(entry) },
                        )
                    }
                }
            }
        }
        if (index.hidden.isNotEmpty()) {
            ModulePageCard(shape = RoundedCornerShape(skin.shapes.lg)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (note in index.hidden) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            VadeIcon(
                                VadeIcons.Waiting,
                                contentDescription = null,
                                size = 16.dp,
                                tint = skin.colors.onSurfaceVariant,
                            )
                            Spacer(Modifier.size(8.dp))
                            UiText(
                                "${note.moduleTitle} › ${note.pageTitle}：此页面需要更新的客户端（v${note.version}）",
                                size = 13,
                                color = skin.colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 模块卡片容器：`lg` 圆角 + 1px 发丝线 + §8 row-1 极浅分层阴影（shadow0）。 */
@Composable
private fun ModulePageCard(shape: RoundedCornerShape, content: @Composable () -> Unit) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .layeredShadow(VadeShadows.card, shape, dark = skin.colors.isDark)
            .clip(shape)
            .background(skin.colors.surface)
            .border(1.dp, skin.colors.hairline, shape),
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// 页面宿主
// ---------------------------------------------------------------------------

/**
 * 模块页宿主：面包屑 + 返回、离线/状态不可用/需要会话三种 banner、错误卡
 * （MANIFEST_INVALID，最多 3 条定位，§7.2）、正文（外壳 `xl` + 发丝线）。
 *
 * 页内 `navigate`（module:&lt;page_id&gt;）在宿主内推进面包屑栈（§8.3：上限 3 层，
 * 第 4 次替换栈顶）；其余 navigate 目标与 `tools/call` / `ui/submit` 通过回调交给
 * 接线方。
 *
 * ## 页面体：同步缓存 vs 异步取回（g-modulepage-wire）；页面状态：绑定页一帧（g-module-state）
 *
 * 过去本宿主只有同步值语义：`source.loadPage(...)` 命中就渲染，没命中就画
 * 「页面不可用」——而 `ui/page.get` 是挂起调用，`commonMain` 里没人发它，于是**每一页**
 * 都命中不了（`shots/g-module-page/RESULT.md` 缺口 2）。
 *
 * 现在多一条路：传了 [pageFetch]（真实现 = `RealModulePageSource`，它本身也是
 * [ModulePageBodyFetcher]）就走**异步**——进入页面 / 页内导航 / 点重试时发**一帧**
 * `ui/page.get`，由 [ModulePageBodyLoader] 收成 `Loading → Ready/Failed`。
 * 没传（示例树、假数据源、既有测试）时行为与接线前**逐字节相同**：只读
 * [ModulePageSource.loadPage] 的缓存。
 *
 * 页面状态走同一条纪律（g-module-state）：[pageFetch] 若也是 [ModulePageStateFetcher]
 * （真实现就是），宿主在**解析过的树里真的存在 `page.state.*` 绑定**（
 * [referencesPageState] 判据）时发**一帧** `ui/state.get`——静态页（echo 三行文字）
 * 零帧、也不画任何「状态不可用」；绑定页失败画可见的降级横幅 + 重试，不把绑定字段
 * 默默画成空；`module.status`/声明快照变化把已取状态标记为过期，下一次重组补一帧
 * （不自动轮询）。`ModulePageStateLoader` 与 [ModulePageBodyLoader] 共用同一套
 * generation 守卫与三态形状。
 *
 * 「不能每帧都发」的保证在两处：请求由 `LaunchedEffect(key, attempt)` 触发（键变化才发，
 * 不是重组就发），以及 loader 里的 generation 守卫（切页后旧回包直接丢弃）。
 */
@Composable
fun ModulePageHost(
    entry: ModulePageEntry,
    source: ModulePageSource,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    hasActiveSession: Boolean = false,
    /**
     * 页面体的异步后备（`ui/page.get`）。`null` = 只读 [ModulePageSource.loadPage] 的缓存
     * ——示例树与假数据源就是这一路，行为不变。
     */
    pageFetch: ModulePageBodyFetcher? = null,
    onToolCall: (PageAction.ToolCall, kotlinx.serialization.json.JsonObject) -> Unit = { _, _ -> },
    onSubmit: (List<String>) -> Unit = {},
    onNavigateOut: (NavigateTarget) -> Unit = {},
) {
    val skin = LocalSkin.current
    val declaredPages = remember(source, entry.moduleId) { declaredPageIds(source, entry.moduleId) }

    // 面包屑栈：深度 1 = 页面标题；navigate 到同模块另一页入栈（≤3 层，第 4 次替换栈顶）。
    // 键 = 调用方交来的那一页：宿主被复用到另一页时必须**重置**栈，否则面包屑与正文会
    // 停在上一页（`remember` 不随形参变化）。页内 navigate 只改这个 list，形参不变 → 不重置。
    val stack = remember(entry.moduleId, entry.instanceId, entry.pageId) { mutableStateListOf(entry) }
    val current = stack.last()
    var refresh by remember(current.pageId) { mutableIntStateOf(0) }

    /**
     * g-nav-stack（c2-nav）· 模块页是返回栈里的一层：Esc / 系统返回**退一层**——
     * 面包屑深度 > 1 时退回上一页，深度 1 时退出模块页回能力页；与顶栏返回按钮
     * （[HostHeader]）完全同一个动作，不会穿过能力页直接退出 App。
     *
     * 本宿主只在页面打开时组合 ⇒ `enabled = true`；页面关掉后组合离开，层自动出栈。
     * 放在页面内容之前：栈序 = 组合序，页面层必须先于它内部的浮层入栈。
     */
    val goBack: () -> Unit = {
        if (stack.size > 1) stack.removeAt(stack.lastIndex) else onBack()
    }
    BackLayer(enabled = true, onBack = goBack)

    // `$input` 只存客户端内存（§5.3）：不进快照、不同步、不落盘，页面销毁即丢。
    val inputs = remember(current.pageId) { mutableStateMapOf<String, String>() }
    val tabSelections = remember(current.pageId) { mutableStateMapOf<String, Int>() }

    // 在线态**不 memo**：`ModuleRegistry` 的快照是整体替换的不可变值，只有每次重读才看得见
    // `module.status` 带来的变化。整表就几个节点，按帧线性扫一遍比「记住一个过期快照」便宜。
    val status = source.status(current.moduleId)

    // 页面体：绑了异步后备就只认那一帧的结果，否则（示例树/假源）退回同步缓存。
    val bodyScope = rememberCoroutineScope()
    val bodies = remember(pageFetch) { pageFetch?.let { ModulePageBodyLoader(it, bodyScope) } }
    DisposableEffect(bodies) { onDispose { bodies?.dispose() } }

    // 页面状态（g-module-state）：同一个异步后备若也是 [ModulePageStateFetcher]（真实现
    // `RealModulePageSource` 就是），绑定页才真发 `ui/state.get`；没实现（示例树/既有假源）
    // 时只读 [ModulePageSource.loadState] 的同步缓存，行为与接线前逐字节相同。
    val stateLoader = remember(pageFetch) {
        (pageFetch as? ModulePageStateFetcher)?.let { ModulePageStateLoader(it, bodyScope) }
    }
    DisposableEffect(stateLoader) { onDispose { stateLoader?.dispose() } }

    // 每次进入页面 / 每次点重试 = 一代；key 变化（换页）也是新一代。
    var attempt by remember(current.moduleId, current.pageId) { mutableIntStateOf(0) }
    val key = ModulePageKey(current.moduleId, current.instanceId, current.pageId)
    LaunchedEffect(bodies, key, attempt) {
        // 一帧 ui/page.get：不是每帧都发（键不变就不重跑），也不是永远不发。
        if (bodies == null) return@LaunchedEffect
        bodies.load(key, declaredPages)
    }

    val bodyState = bodies?.state?.takeIf { it.key == key }
    val cached = remember(current.pageId, refresh) { source.loadPage(current.moduleId, current.pageId) }
    val raw = when {
        bodies == null -> cached
        else -> (bodyState as? ModulePageBodyState.Ready)?.schema
    }
    val parseResult = remember(raw, declaredPages) {
        raw?.let { ModulePageParser.parse(it, declaredPages) }
    }

    // 发帧判据（g-module-state）：**解析过的**树里真的存在 page.state.* 绑定才需要状态帧。
    // module.status.* / item.* / 字面量都不算——echo 那种纯文本页得 false，一帧不发，
    // 也不画任何「状态不可用」。
    val needsState = (parseResult as? ParseResult.Ok)?.root?.let { referencesPageState(it) } == true

    // 页面状态：真 RPC 路只认本帧 loader 的结果（Loading/失败时如实为空，绝不拿旧缓存
    // 冒充）；同步路（示例树/假源）保留 loadState 缓存读。
    val statePhase = stateLoader?.phase?.takeIf { it.key == key }
    val state = when (stateLoader) {
        null -> remember(current.pageId, refresh) { source.loadState(current.moduleId, current.instanceId, current.pageId) }
        else -> (statePhase as? ModulePageStatePhase.Ready)?.state
    }

    // 绑定页发一帧 ui/state.get 的节奏（与页面体同一套，不是每帧重组都发）：
    //  ① 进入页面 / 点重试（key 或 attempt 变化）且正文解析出状态引用 → 一帧；
    //  ② `module.status`/声明快照变化把已取状态标记为过期（stateStale，见下）→ 补一帧；
    //  phase 的 Loading→Ready 迁移**不在键里**——回包到达不重触发，天然无重发循环。
    LaunchedEffect(stateLoader, key, attempt, needsState) {
        if (stateLoader == null || !needsState) return@LaunchedEffect
        stateLoader.load(key)
    }

    // `module.status` 失效联动（g-module-state）：状态是在哪个模块快照（在线态/版本/页面
    // 声明）下取的记成「章」；快照变了（重新上线 / 版本变化 / 清单 revision 变化）就把已取
    // 状态标记为可能过期，在下一次重组（任意交互都会带来重组）里补一帧。不自动轮询——
    // 触发是 module.status 这个失效边本身，每次变化至多补一帧。
    val moduleSnapshot = source.declarations().firstOrNull { it.moduleId == current.moduleId }
    val stateStamp = remember(statePhase) { moduleSnapshot }
    val stateStale = statePhase != null && stateStamp != moduleSnapshot
    LaunchedEffect(stateLoader, key, stateStale, status.online) {
        if (stateLoader == null || !stateStale) return@LaunchedEffect
        if (!status.online) return@LaunchedEffect // 模块离线时补帧必然失败，等重新上线这代重组再发
        if (statePhase is ModulePageStatePhase.Loading) return@LaunchedEffect // 在飞不重发
        stateLoader.load(key)
    }

    // 重试 = 重发一帧页面体（异步路）+ 重读同步缓存（示例树路）；两条路都走同一颗按钮。
    val retry: () -> Unit = {
        refresh += 1
        attempt += 1
    }

    // 顶部横幅（一处决定，BannerRow 只管画）：离线 > 状态（**仅绑定页**） > 需要会话。
    // 静态页（needsState=false）永远拿不到状态行——echo 那种纯文本页不再出现
    // 「模块状态不可用」；绑定页则把 ui/state.get 的三态如实画出来（§5.4）。
    val stateBanner = when {
        needsState && stateLoader != null -> when (statePhase) {
            null -> null // 正文刚解析出绑定、effect 还没跑：还不知道能不能取到，不画
            is ModulePageStatePhase.Loading -> ModulePageBanner("正在取回模块状态…", retryable = false)
            is ModulePageStatePhase.Failed ->
                ModulePageBanner("模块状态不可用——${statePhase.failure.label}，可重试", retryable = true)
            is ModulePageStatePhase.Ready ->
                if (stateStale) ModulePageBanner("模块状态已更新——重试获取最新状态", retryable = true) else null
        }
        needsState && state == null ->
            // 同步路（示例树/假源，无状态后备）：保留接线前的语义——绑定页状态缺失才提示。
            ModulePageBanner("模块状态不可用——绑定值显示为空，可重试", retryable = true)
        else -> null
    }
    val banner = when {
        !status.online -> ModulePageBanner("模块离线——显示最后一次成功渲染的结构，动作已禁用", retryable = true)
        stateBanner != null -> stateBanner
        current.requiresSession && !hasActiveSession ->
            ModulePageBanner("需要先打开会话——此页面的提交动作已禁用", retryable = false)
        else -> null
    }
    val env = PageEnv(
        state = state,
        moduleOnline = status.online,
        moduleVersion = status.version,
        inputs = inputs.toMap(),
        hasSession = hasActiveSession,
    )
    val report = PageResolveReport()
    val resolved = (parseResult as? ParseResult.Ok)?.root?.let {
        ModulePageResolver.resolve(it, env, report)
    }

    val dispatch: (PageAction) -> Unit = { action ->
        when (action) {
            is PageAction.Navigate -> when (val target = action.target) {
                is NavigateTarget.ModulePage -> {
                    val module = source.declarations().firstOrNull { it.moduleId == current.moduleId }
                    val page = module?.pages?.firstOrNull { it.pageId == target.pageId }
                    if (module != null && page != null) {
                        val next = ModulePageEntry(
                            moduleId = module.moduleId,
                            instanceId = module.instanceId,
                            moduleTitle = module.moduleTitle,
                            pageId = page.pageId,
                            pageTitle = page.title,
                            icon = page.icon,
                            version = page.version,
                            requiresSession = page.requiresSession,
                        )
                        // §8.3：上限 3 层，第 4 次 navigate 替换栈顶（防「浏览器历史」化）。
                        if (stack.size >= 3) stack[stack.lastIndex] = next else stack.add(next)
                    }
                }
                else -> onNavigateOut(target)
            }
            is PageAction.ToolCall -> {
                if (ModulePageResolver.actionEnabled(action, env)) {
                    val args = ModulePageResolver.resolveArgs(action.args, env, report)
                    if (args != null) onToolCall(action, args)
                }
            }
            is PageAction.UiSubmit -> {
                if (ModulePageResolver.actionEnabled(action, env)) {
                    onSubmit(ModulePageResolver.resolveSubmitTexts(action.parts, env, report))
                }
            }
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(skin.colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HostHeader(stack, onBack = goBack)
        BannerRow(status, banner, onRetry = retry)
        when {
            // 异步路的三态：失败 → 带 C-04 名的可重试卡；还没结果 → 「正在取回」卡。
            bodies != null && bodyState is ModulePageBodyState.Failed -> UnavailableCard(
                detail = bodyState.failure.label,
                onRetry = retry,
                onBack = onBack,
            )
            bodies != null && raw == null -> PageBodyLoadingCard()
            raw == null -> UnavailableCard(onRetry = retry, onBack = onBack)
            parseResult is ParseResult.Rejected -> ModulePageErrorCard(
                moduleTitle = current.moduleTitle,
                pageTitle = current.pageTitle,
                reasons = parseResult.reasons,
                onBack = onBack,
                onRetry = retry,
            )
            resolved != null -> PageShell {
                RenderResolved(
                    node = resolved,
                    path = "root",
                    env = env,
                    inputs = inputs,
                    tabSelections = tabSelections,
                    dispatch = dispatch,
                )
            }
            else -> UnavailableCard(onRetry = retry, onBack = onBack)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** 顶栏：返回（图标按钮，触摸区 44dp）+ 面包屑「模块 › 页 A › 页 B」（§8.3）。 */
@Composable
private fun HostHeader(stack: SnapshotStateList<ModulePageEntry>, onBack: () -> Unit) {
    val skin = LocalSkin.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            VadeIcon(
                VadeIcons.Back,
                contentDescription = if (stack.size > 1) "返回上一页" else "返回能力页",
                size = 20.dp,
                tint = skin.colors.onSurface,
            )
        }
        Spacer(Modifier.size(8.dp))
        val breadcrumb = (listOf(stack.first().moduleTitle) + stack.map { it.pageTitle })
            .joinToString(" › ")
        UiText(breadcrumb, size = 15, weight = FontWeight.SemiBold, color = skin.colors.onSurface)
    }
}

/** 顶部横幅的一行决定：文案 + 是否带重试。决定在宿主算好，本类型只承载结果。 */
private data class ModulePageBanner(val message: String, val retryable: Boolean)

/**
 * 顶部横幅（§5.4 / §7.4 / §4.2）：离线、状态不可用（可重试）、需要会话。
 * 优先级与文案都由宿主算好（离线 > 状态 > 会话），本函数只管画：图标按在线态，
 * 重试按钮按 [ModulePageBanner.retryable]。
 */
@Composable
private fun BannerRow(
    status: ModuleStatus,
    banner: ModulePageBanner?,
    onRetry: () -> Unit,
) {
    if (banner == null) return
    val skin = LocalSkin.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(skin.shapes.xs))
            .background(skin.colors.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VadeIcon(
            if (status.online) VadeIcons.Waiting else VadeIcons.Failed,
            contentDescription = null,
            size = 16.dp,
            tint = skin.colors.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        UiText(banner.message, size = 13, color = skin.colors.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (banner.retryable) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                VadeIcon(VadeIcons.Refresh, contentDescription = "重试", size = 18.dp, tint = skin.colors.onSurface)
            }
        }
    }
}

/** 渲染期拒渲的错误卡（§7.2）：模块名、页面标题、MANIFEST_INVALID、最多 3 条定位。 */
@Composable
private fun ModulePageErrorCard(
    moduleTitle: String,
    pageTitle: String,
    reasons: List<String>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    val skin = LocalSkin.current
    ModulePageCard(shape = RoundedCornerShape(skin.shapes.xl)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            UiText("页面无法渲染", size = 20, weight = FontWeight.SemiBold, color = skin.colors.onSurface)
            UiText(
                "$moduleTitle › $pageTitle · MANIFEST_INVALID",
                size = 13,
                color = skin.colors.onSurfaceVariant,
            )
            for (reason in reasons.take(3)) {
                UiText("· $reason", size = 13, color = skin.colors.onSurfaceVariant)
            }
            if (reasons.size > 3) {
                UiText("……共 ${reasons.size} 条问题", size = 13, color = skin.colors.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                skin.Button(text = "重试", onClick = onRetry, style = BtnStyle.Secondary)
                skin.Button(text = "返回", onClick = onBack, style = BtnStyle.Primary)
            }
        }
    }
}

/**
 * `ui/page.get` 不可用（NOT_FOUND / UNAVAILABLE / 传输失败）→ 卡片 + 重试，不白屏（§7.4）。
 *
 * [detail] 是失败那一行的原话（C-04 名 + 传输层消息，见 [ModulePageBodyFailure.label]）；
 * 为 `null` 时退回事先写好的通用说明——同步缓存路（示例树）没有具体失败可报。
 */
@Composable
private fun UnavailableCard(detail: String? = null, onRetry: () -> Unit, onBack: () -> Unit) {
    val skin = LocalSkin.current
    ModulePageCard(shape = RoundedCornerShape(skin.shapes.xl)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            UiText("页面不可用", size = 20, weight = FontWeight.SemiBold, color = skin.colors.onSurface)
            UiText(
                detail ?: "页面暂时取不到，可重试或返回。",
                size = 13,
                color = skin.colors.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                skin.Button(text = "重试", onClick = onRetry, style = BtnStyle.Secondary)
                skin.Button(text = "返回", onClick = onBack, style = BtnStyle.Primary)
            }
        }
    }
}

/**
 * 一帧 `ui/page.get` 还在飞（含「进入页面后的第一帧，请求刚发出」）。
 *
 * 刻意**不**画成 [UnavailableCard]：那时我们还不知道能不能取到，画「不可用」就是替 core
 * 说一句它还没说的话——与 `ModuleCatalogState.Loading` 不复用空态卡是同一条理由。
 */
@Composable
private fun PageBodyLoadingCard() {
    val skin = LocalSkin.current
    ModulePageCard(shape = RoundedCornerShape(skin.shapes.xl)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VadeIcon(
                    VadeIcons.Waiting,
                    contentDescription = "加载中",
                    size = 16.dp,
                    tint = skin.colors.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                UiText("正在取回页面", size = 15, weight = FontWeight.Medium, color = skin.colors.onSurface)
            }
            UiText(
                "ui/page.get 已发出，等模块返回这一页的结构。",
                size = 13,
                color = skin.colors.onSurfaceVariant,
            )
        }
    }
}

/** 页面外壳：`xl` 圆角 + 发丝线 + shadow0（visual language §12「模块页面」行）。 */
@Composable
private fun PageShell(content: @Composable () -> Unit) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.xl)
    Column(
        Modifier
            .fillMaxWidth()
            .layeredShadow(VadeShadows.card, shape, dark = skin.colors.isDark)
            .clip(shape)
            .background(skin.colors.surface)
            .border(1.dp, skin.colors.hairline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
    }
}

// ---------------------------------------------------------------------------
// ResolvedNode → Compose 的递归映射（不做任何决策）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenderResolved(
    node: ResolvedNode,
    path: String,
    env: PageEnv,
    inputs: SnapshotStateMap<String, String>,
    tabSelections: SnapshotStateMap<String, Int>,
    dispatch: (PageAction) -> Unit,
) {
    when (node) {
        is ResolvedNode.Column -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(node.gap.dpSize.dp),
        ) {
            node.children.forEachIndexed { index, child ->
                RenderResolved(child, "$path.$index", env, inputs, tabSelections, dispatch)
            }
        }

        is ResolvedNode.Row -> {
            val arrangement = Arrangement.spacedBy(node.gap.dpSize.dp)
            val alignment = when (node.align) {
                RowAlign.Start -> Alignment.Top
                RowAlign.Center -> Alignment.CenterVertically
                RowAlign.End -> Alignment.Bottom
            }
            if (node.wrap) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = arrangement,
                    verticalArrangement = arrangement,
                ) {
                    node.children.forEachIndexed { index, child ->
                        RenderResolved(child, "$path.$index", env, inputs, tabSelections, dispatch)
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = arrangement,
                    verticalAlignment = alignment,
                ) {
                    node.children.forEachIndexed { index, child ->
                        RenderResolved(child, "$path.$index", env, inputs, tabSelections, dispatch)
                    }
                }
            }
        }

        is ResolvedNode.Tabs -> {
            val selected = tabSelections[path] ?: node.selectedIndex
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    node.tabs.forEachIndexed { index, tab ->
                        LocalSkin.current.Button(
                            text = tab.title,
                            onClick = { tabSelections[path] = index },
                            style = if (index == selected) BtnStyle.Primary else BtnStyle.Secondary,
                        )
                    }
                }
                val tab = node.tabs.getOrNull(selected) ?: node.tabs.first()
                RenderResolved(tab.child, "$path.tab", env, inputs, tabSelections, dispatch)
            }
        }

        is ResolvedNode.ListBlock -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (node.rows.isEmpty()) {
                UiText(node.emptyText, size = 13, color = LocalSkin.current.colors.onSurfaceVariant)
            } else {
                node.rows.forEachIndexed { index, row ->
                    RenderResolved(row, "$path.$index", env, inputs, tabSelections, dispatch)
                }
            }
            if (node.total > node.shown) {
                UiText(
                    "共 ${node.total} 项，每屏最多显示 ${node.shown} 项",
                    size = 13,
                    color = LocalSkin.current.colors.onSurfaceVariant,
                )
            }
        }

        is ResolvedNode.TextLeaf -> {
            if (node.text.isNotEmpty()) RenderTextLeaf(node)
        }

        is ResolvedNode.Component -> RenderComponent(
            node = node,
            path = path,
            env = env,
            inputs = inputs,
            tabSelections = tabSelections,
            dispatch = dispatch,
        )
    }
}

/** `text` 叶的 style → 字号/字重（visual language §四：页面标题 20/600、正文 15、次要 13）。 */
@Composable
private fun RenderTextLeaf(node: ResolvedNode.TextLeaf) {
    val skin = LocalSkin.current
    when (node.style) {
        PageTextStyle.Title -> UiText(
            node.text, size = 20, weight = FontWeight.SemiBold,
            color = skin.colors.onSurface, maxLines = node.maxLines,
        )
        PageTextStyle.Subtitle -> UiText(
            node.text, size = 15, weight = FontWeight.Medium,
            color = skin.colors.onSurface, maxLines = node.maxLines,
        )
        PageTextStyle.Body -> UiText(
            node.text, size = 15,
            color = skin.colors.onSurface, maxLines = node.maxLines,
        )
        PageTextStyle.Caption -> UiText(
            node.text, size = 13,
            color = skin.colors.onSurfaceVariant, maxLines = node.maxLines,
        )
        PageTextStyle.Mono -> UiText(
            node.text,
            style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = skin.colors.onSurface),
            maxLines = node.maxLines,
        )
    }
}

@Composable
private fun RenderComponent(
    node: ResolvedNode.Component,
    path: String,
    env: PageEnv,
    inputs: SnapshotStateMap<String, String>,
    tabSelections: SnapshotStateMap<String, Int>,
    dispatch: (PageAction) -> Unit,
) {
    val skin = LocalSkin.current
    when (val p = node.payload) {
        is ResolvedComponent.Button -> skin.Button(
            text = p.text,
            onClick = { p.action?.let(dispatch) },
            style = p.style,
            enabled = p.enabled,
        )

        is ResolvedComponent.TextField -> skin.TextField(
            value = p.value,
            onValueChange = { newValue ->
                val id = p.inputId ?: return@TextField
                // max_length ≤4096（§4.5）：超长直接不接受，不做隐形截断提示。
                if (p.maxLength <= 0 || newValue.length <= p.maxLength) inputs[id] = newValue
            },
            placeholder = p.placeholder,
            enabled = p.enabled,
        )

        is ResolvedComponent.Card -> skin.Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!p.title.isNullOrEmpty()) {
                    UiText(p.title, size = 15, weight = FontWeight.SemiBold, color = skin.colors.onSurface)
                }
                if (!p.subtitle.isNullOrEmpty()) {
                    UiText(p.subtitle, size = 13, color = skin.colors.onSurfaceVariant)
                }
                p.children.forEachIndexed { index, child ->
                    RenderResolved(child, "$path.$index", env, inputs, tabSelections, dispatch)
                }
            }
        }

        is ResolvedComponent.ListItem -> skin.ListItem(
            title = p.title,
            leading = p.leadingIcon?.vectorOrNull()?.let { vector ->
                {
                    VadeIcon(vector, contentDescription = null, size = 20.dp, tint = skin.colors.onSurfaceVariant)
                }
            },
            subtitle = p.subtitle,
            trailing = when {
                !p.trailingText.isNullOrEmpty() -> {
                    { UiText(p.trailingText, size = 13, color = skin.colors.onSurfaceVariant) }
                }
                p.action != null -> {
                    {
                        VadeIcon(
                            VadeIcons.ChevronRight, contentDescription = null,
                            size = 20.dp, tint = skin.colors.onSurfaceVariant,
                        )
                    }
                }
                else -> null
            },
            onClick = p.action
                ?.takeIf { env.moduleOnline }
                ?.let { action -> { dispatch(action) } },
            enabled = p.action == null || env.moduleOnline,
        )

        // Switch 宿主不本地翻转（§4.5）：checked 只来自绑定，翻转只发动作，
        // 状态由下一次 ui/state.get 反映（本轮协议未接 → 翻转发回调、不翻转 UI）。
        is ResolvedComponent.Switch -> skin.Switch(
            checked = p.checked,
            onCheckedChange = { p.action?.let(dispatch) },
            enabled = p.enabled,
        )

        is ResolvedComponent.ToolCallCard -> skin.ToolCallCard(
            call = ToolCallView(
                id = "module-page-$path",
                name = p.tool,
                args = p.argsPreview,
                result = p.resultSummary,
                risk = "low",
                isError = p.state == ToolNodeState.Failed,
            ),
            state = when (p.state) {
                ToolNodeState.Running -> ToolCardState.Expanded
                ToolNodeState.WaitingApproval -> ToolCardState.PendingApproval
                else -> ToolCardState.Collapsed
            },
            onToggle = {},
        )

        is ResolvedComponent.AttachmentChip -> {
            val view = AttachmentView(
                fileId = p.fileId,
                fileName = p.name,
                mime = p.mime,
                state = when (p.state) {
                    ChipNodeState.Validating -> AttachmentState.Validating
                    ChipNodeState.Uploading -> AttachmentState.Uploading
                    ChipNodeState.Uploaded -> AttachmentState.Uploaded
                    ChipNodeState.Failed -> AttachmentState.Failed
                },
            )
            if (p.action != null && env.moduleOnline) {
                // skin.AttachmentChip 没有点击槽（C-09 冻结签名）；click 只能 navigate（Q4），
                // 所以用一层可点容器承载，chip 本体不换。
                Row(
                    Modifier
                        .clip(RoundedCornerShape(skin.shapes.sm))
                        .clickable { p.action.let(dispatch) },
                ) {
                    skin.AttachmentChip(view)
                }
            } else {
                skin.AttachmentChip(view)
            }
        }
    }
}
