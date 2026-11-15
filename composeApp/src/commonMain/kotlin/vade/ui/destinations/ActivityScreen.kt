package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.glass.asGlassSource
import vade.ui.skin.glass.LocalGlassHazeState
import vade.ui.data.RealSessionSource
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.UsageBudgetPayload
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeUsage
import vade.ui.skin.LocalSkin
import vade.ui.subagent.SubagentLoadState
import vade.ui.subagent.SubagentLoader
import vade.ui.subagent.SubagentPanel
import vade.ui.subagent.SubagentRunView
import vade.ui.subagent.SubagentSection
import vade.ui.subagent.SubagentSource
import vade.ui.subagent.SubagentSourceReal
import vade.ui.usage.UsageLoadState
import vade.ui.usage.UsageLoader
import vade.ui.usage.UsagePanel
import vade.ui.usage.UsageSection
import vade.ui.usage.UsageSource
import vade.ui.usage.UsageSourceReal

/** g-haze-structure · 活动页滚动列的 haze source tag（守卫见 `GlassStructureGuardTest`）。 */
internal const val GlassSourceActivityTag = "vade.glass.source.activity"

/**
 * 活动 (C-Activity), D87 blocks + the two real data panels.
 *
 * The three U1-a placeholder blocks (运行中 / 待审批 / 最近失败) stay empty until U1-d
 * wires turn events — nothing here invents an event stream. The two data panels are now
 * **wired end to end**: 后台任务·子代理 reads `subagent/list`, 用量 reads
 * `session/usage.get`, both with the three honest states (加载中 / 加载失败+重试 / 已加载)
 * **plus the fourth face**: a core that answers 方法不存在（-32601 / NOT_FOUND unknown method）
 * gets [ActivityMethodMissingCard] — 不支持 is not a failure (retrying can never succeed)
 * and not an empty list; see [isMethodMissingAnswer].
 *
 * ## 为什么需要「查看范围」（本批的核心侦察结论）
 * 用量与子代理在契约里**不是同一种东西**：
 *  - `session/usage.get` 的 `session_id` 是**必填**（`schema/rpc.json`）。契约只提供
 *    「按会话读」这一种读法；`day` 是跨会话的**汇总**，没有明细。所以**跨会话用量无法
 *    在客户端拼**——把 N 个会话的数字相加就是编数据（预算、partial_turns、by_model 都
 *    会对不上）。这里不臆造聚合，改成「选一个会话 → 看该会话用量」。
 *  - `subagent/list` 的 `session_id` 可以**省略**，语义是 across sessions
 *    （契约原文：`omitted = across sessions; NOT shared execution`）。所以子代理可以
 *    按全局列，每行自带 `parent_session_id`，客户端只画服务端原样返回的行。
 *
 * 于是页面有一个 [ActivityScope]：`All`（全局）或 `Session(id)`。子代理两种范围都能读；
 * 用量只在 `Session` 范围下读，`All` 时给一张**明确说明契约限制**的卡（不是「暂无数据」,
 * 也不是假聚合）。
 *
 * ## 注入 / 回落
 * - 传 [source]（组合根唯一持有 `VadeClient` 的地方）→ 自动折成两个窄口（[activitySourcesOf]）；
 * - 或直接传 [usageSource] / [subagentSource]（测试用假源）；
 * - 都不传 → 与接线前**逐字节一致**：用 [usage] / [lastTurnUsage] / [budgetNotice] /
 *   [subagentRuns] 这四份 DTO 快照渲染，全 `null` 时就是原来的空态。
 *
 * [contentPadding] is the shell's floating glass bar zones: passed into the scroll so the
 * page content scrolls behind the glass instead of stopping short of it.
 */
@Composable
fun ActivityScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /** `session/usage.get` result snapshot; null = 未接线 → 空态. */
    usage: SessionUsageGetResult? = null,
    /** The `usage` object of the most recent `turn.completed` (or failed/interrupted). */
    lastTurnUsage: VadeUsage? = null,
    /** The last `usage.budget` event payload (notification, not the authoritative truth). */
    budgetNotice: UsageBudgetPayload? = null,
    /** Sub-agent runs (`subagent/get`-shaped snapshots); null = 未接线, empty = 真实为空. */
    subagentRuns: List<SubagentRunView>? = null,
    // ---- G · 接线（live）---------------------------------------------------
    /** 会话选择器的候选（页面是全局的，Shell 在进活动页时会清空选中会话）。 */
    sessions: List<SessionSummary> = emptyList(),
    /** 初始范围；`All` 是默认（活动页在 Shell 里本来就是全局页）。 */
    initialScope: ActivityScope = ActivityScope.All,
    /** 组合根的数据源；非空 → 自动得到两个窄口（假源 → 未接线，行为不变）。 */
    source: SessionSource? = null,
    /** 直接注入的用量读取口（优先于 [source]）。 */
    usageSource: UsageSource? = null,
    /** 直接注入的子代理读取口（优先于 [source]）。 */
    subagentSource: SubagentSource? = null,
) {
    val skin = LocalSkin.current
    // 窄口按 source 实例记忆：UsageSectionLive/SubagentSectionLive 用 remember(source)
    // 持有各自的 loader，每帧新建 source 会让 loader 反复重建、无限重发请求。
    val fromSource = remember(source) { source?.let(::activitySourcesOf) }
    val usageLive = usageSource ?: fromSource?.usage
    val subagentsLive = subagentSource ?: fromSource?.subagents
    val wired = usageLive != null || subagentsLive != null

    var scope by remember(initialScope) { mutableStateOf(initialScope) }

    // g-haze-structure: this scroll column is the page's haze **source** (same contract as
    // the sessions list / chat thread): content scrolls under the glass bars, and the
    // sibling search overlay blurs it.
    Column(
        modifier
            .asGlassSource(LocalGlassHazeState.current)
            .fillMaxSize()
            .testTag(GlassSourceActivityTag)
            .background(skin.colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel("运行中")
        EmptyStateCard(
            title = "暂无运行中的 turn",
            body = "U1-d 接入 turn 事件后，这里显示进度、已运行时长与停止入口。",
        )

        SectionLabel("待审批")
        EmptyStateCard(
            icon = VadeIcons.Pending,
            title = "暂无待审批",
            body = "审批关闭只认 turn.approval_resolved（FR-8.53）；U1-d 接线前不伪造倒计时，也不插入假的审批卡。",
        )

        SectionLabel("最近失败")
        EmptyStateCard(
            icon = VadeIcons.Failed,
            title = "暂无失败记录",
            body = "失败 turn 与失败工具卡会在这里汇总（U1-d）。",
        )

        // 范围选择只在真的有读取口时出现：未接线时页面保持接线前的样子。
        if (wired) {
            SectionLabel("查看范围")
            ActivityScopePicker(
                scope = scope,
                sessions = sessions,
                onScopeChange = { scope = it },
            )
        }

        SectionLabel("用量")
        when {
            // 全局范围 + 有读取口：契约没有跨会话用量读法 —— 说明清楚，不让它落到空态里。
            usageLive != null && scope is ActivityScope.All -> GlobalUsageNeedsSessionCard()
            usageLive != null -> ActivityUsageLive(
                source = usageLive,
                sessionId = scope.sessionIdOrNull(),
                lastTurn = lastTurnUsage,
                budgetNotice = budgetNotice,
            )
            // 未接线：旧的快照渲染（传 null 时与接线前完全一致）。
            else -> UsagePanel(
                session = usage,
                lastTurn = lastTurnUsage,
                budgetNotice = budgetNotice,
            )
        }

        SectionLabel("后台任务 · 子代理")
        if (subagentsLive != null) {
            ActivitySubagentsLive(
                source = subagentsLive,
                // null = 全部会话；契约允许，且每行自带 parent_session_id。
                sessionId = scope.sessionIdOrNull(),
            )
        } else {
            SubagentPanel(
                runs = subagentRuns,
                sessionScoped = scope is ActivityScope.Session,
            )
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

// ==========================================================================
// G · 范围 / 接线
// ==========================================================================

/**
 * 活动页的查看范围。
 *
 * [All] 是活动页在 Shell 里的真实处境：`UiState.select(destination)` 在离开会话列表时
 * 就把 `selectedSessionId` 清空了，所以活动页没有「当前会话」可用。页面因此提供选择器，
 * 而不是偷偷拿列表第一条当作用户想看的会话。
 */
sealed interface ActivityScope {
    /** 全局：子代理可跨会话列（契约允许）；用量没有跨会话读法（见 [GlobalUsageNeedsSessionCard]）。 */
    data object All : ActivityScope

    /** 某一个会话：用量与子代理都能读。 */
    data class Session(val sessionId: String) : ActivityScope
}

/** 页面要读的会话 id；[ActivityScope.All] → `null`。 */
fun ActivityScope.sessionIdOrNull(): String? = (this as? ActivityScope.Session)?.sessionId

/** 范围在页面上的文案（唯一的措辞来源，测试与 UI 共用）。 */
fun activityScopeLabel(scope: ActivityScope, title: String? = null): String = when (scope) {
    ActivityScope.All -> "全部会话"
    is ActivityScope.Session -> title?.takeIf { it.isNotBlank() } ?: scope.sessionId
}

/** 活动页需要的两个读取窄口；`null` = 该面板未接线（退回快照/空态）。 */
data class ActivitySources(
    val usage: UsageSource? = null,
    val subagents: SubagentSource? = null,
)

/**
 * 组合根粘合：`SessionSource` → 活动页的两个窄口。
 *
 * 只有真的 `RealSessionSource` 才有协议客户端；[vade.ui.data.FakeSessionSource] 不是
 * `RealSessionSource`，于是**两个窄口都是 `null`** —— 假数据源绝不会拿到假读取口，
 * 页面回到接线前的快照路径（`VadeAdaptersTest` 守着同一条规矩）。
 *
 * 客户端是**晚绑定**的（[LateBoundUsageSource] 每次调用才去取 `source.client`）：
 * `RealSessionSource` 断线重连会换一个全新的 client，晚绑定让窄口对象身份保持稳定，
 * 页面 `remember(source)` 持有的 loader 不会被重建，也不会拿着已经 close 的 client 重试。
 *
 * **这层该搬家**：`AdapterProvider`（`data/VadeAdapters.kt`）是项目里「UI 拿窄接口」的
 * 正规入口，加 `fun usage(): UsageSource` / `fun subagents(): SubagentSource` 两个方法
 * （实现就是 `UsageSourceReal(client)` / `SubagentSourceReal(client)`）之后，本函数改成
 * 读 `adapters` 即可，页面与测试一行都不用改。本轮不改 `data/` 目录，所以粘合暂放这里。
 */
fun activitySourcesOf(source: SessionSource): ActivitySources = when (source) {
    is RealSessionSource -> ActivitySources(
        usage = LateBoundUsageSource(source),
        subagents = LateBoundSubagentSource(source),
    )
    else -> ActivitySources()
}

/** 未连接时读取的统一说法（可重试分类由 UNAVAILABLE 决定）。 */
private const val NOT_CONNECTED: String =
    "活动页未连接：RealSessionSource 还没有 client（连接建立后才能读），" +
        "不显示任何占位数字。请恢复连接后重试。"

/** [UsageSource] 的晚绑定实现：每次 `load` 取当前 client，走带强校验的 [UsageSourceReal]。 */
private class LateBoundUsageSource(private val source: RealSessionSource) : UsageSource {
    override suspend fun load(sessionId: String): SessionUsageGetResult {
        val client = source.client
            ?: throw VadeException(VadeErrorCodes.UNAVAILABLE, NOT_CONNECTED)
        return UsageSourceReal(client).load(sessionId)
    }
}

/** [SubagentSource] 的晚绑定实现；`sessionId = null` 就是契约的跨会话列出。 */
private class LateBoundSubagentSource(private val source: RealSessionSource) : SubagentSource {
    override suspend fun list(sessionId: String?) =
        source.client?.let { SubagentSourceReal(it).list(sessionId) }
            ?: throw VadeException(VadeErrorCodes.UNAVAILABLE, NOT_CONNECTED)
}

/**
 * 会话选择器：`全部会话` + 每个会话一枚药丸。
 *
 * 会话可能很多，所以整行横向滚动（不在页面里再开一列）；选中态用 surfaceVariant 底色 +
 * 强调文字，未选中用透明底 + 次要文字。
 */
@Composable
fun ActivityScopePicker(
    scope: ActivityScope,
    sessions: List<SessionSummary>,
    onScopeChange: (ActivityScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ScopeChip(
            label = activityScopeLabel(ActivityScope.All),
            selected = scope is ActivityScope.All,
            onClick = { onScopeChange(ActivityScope.All) },
        )
        for (session in sessions) {
            val target = ActivityScope.Session(session.id)
            ScopeChip(
                label = activityScopeLabel(target, session.title),
                selected = scope == target,
                onClick = { onScopeChange(target) },
            )
        }
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalSkin.current.colors
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) colors.surfaceVariant else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            VadeIcon(
                VadeIcons.Check,
                contentDescription = "已选范围",
                size = 14.dp,
                tint = colors.onSurface,
            )
            Spacer(Modifier.padding(horizontal = 3.dp))
        }
        UiText(
            label,
            size = 12,
            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

/**
 * `All` 范围下的用量卡：**不是**空态，也不是聚合数字，而是把契约限制说清楚。
 *
 * `session/usage.get` 的 `session_id` 必填；`day` 只给跨会话汇总、无明细，`budget` 的
 * scope 写死是 `session`。所以跨会话用量只能逐会话读，客户端把它们相加就是编数据。
 */
@Composable
fun GlobalUsageNeedsSessionCard(modifier: Modifier = Modifier) {
    EmptyStateCard(
        icon = VadeIcons.Empty,
        title = "全局用量需要按会话读取",
        body = "session/usage.get 的 session_id 是必填项，契约只提供「按会话」的读法，" +
            "day 仅给跨会话汇总、没有明细；把多个会话的数字相加就是编数据。" +
            "请在上方「查看范围」里选一个会话 —— 这里随即显示该会话真实的 token 统计与预算进度。",
        modifier = modifier,
    )
}

/** 11sp muted section label above each group card — the only chrome between cards. */
@Composable
internal fun SectionLabel(text: String) {
    UiText(text, size = 11, weight = FontWeight.SemiBold, color = LocalSkin.current.colors.onSurfaceVariant)
}

// ==========================================================================
// G · 两个读数区的屏幕侧壳：同一 loader、同一 retry，外加「不支持」第三态
// ==========================================================================

/**
 * 用量读数的屏幕侧壳。拥有与 `UsageSectionLive` 相同的 [UsageLoader] 接线（同一 loader、
 * 同一 `loader.retry()` 入口 —— 不造第二套重试），渲染委托给 [UsageSection]，但在那之前
 * 先拦一道：core 对 `session/usage.get` 回答**方法不存在**时（[isMethodMissingAnswer]），
 * 那不是失败 —— 重试永不成功 —— 单独画 [ActivityMethodMissingCard]（第三态）。
 */
@Composable
private fun ActivityUsageLive(
    source: UsageSource,
    sessionId: String?,
    modifier: Modifier = Modifier,
    lastTurn: VadeUsage? = null,
    budgetNotice: UsageBudgetPayload? = null,
) {
    val scope = rememberCoroutineScope()
    val loader = remember(source) { UsageLoader(source, scope) }
    DisposableEffect(loader) { onDispose { loader.dispose() } }
    LaunchedEffect(loader, sessionId) {
        if (!sessionId.isNullOrBlank()) loader.load(sessionId)
    }
    if (sessionId.isNullOrBlank()) {
        EmptyStateCard(
            icon = VadeIcons.Empty,
            title = "尚未选择会话",
            body = "打开一个会话后，这里通过 session/usage.get 拉取真实的 token 统计与预算进度。",
            modifier = modifier,
        )
        return
    }
    val state = loader.state
    if (state is UsageLoadState.Failed &&
        isMethodMissingAnswer(state.error.code, state.error.detail)
    ) {
        ActivityMethodMissingCard(
            method = "session/usage.get",
            transportWords = state.error.detail,
            modifier = modifier,
        )
        return
    }
    UsageSection(
        state = state,
        onRetry = loader::retry,
        modifier = modifier,
        lastTurn = lastTurn,
        budgetNotice = budgetNotice,
    )
}

/**
 * 子代理读数的屏幕侧壳：与 [ActivityUsageLive] 同一条纪律。`null` 范围是合法的
 * （`subagent/list` 契约允许跨会话列出），照常拉取；只有**方法不存在**的失败被拦下来
 * 画成第三态，其余失败原样交给 [SubagentSection]（分类 + code + 重试）。
 */
@Composable
private fun ActivitySubagentsLive(
    source: SubagentSource,
    sessionId: String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val loader = remember(source) { SubagentLoader(source, scope) }
    DisposableEffect(loader) { onDispose { loader.dispose() } }
    LaunchedEffect(loader, sessionId) { loader.load(sessionId) }
    val state = loader.state
    if (state is SubagentLoadState.Failed &&
        isMethodMissingAnswer(state.error.code, state.error.detail)
    ) {
        ActivityMethodMissingCard(
            method = "subagent/list",
            transportWords = state.error.detail,
            modifier = modifier,
        )
        return
    }
    SubagentSection(
        state = state,
        onRetry = loader::retry,
        modifier = modifier,
        sessionScoped = sessionId != null,
    )
}

/**
 * 「此 core 还没有 <方法>」——方法不存在的**第三态**，与 Loading / Failed / Empty 在
 * 字面上完全不同。这不是加载失败：重试不可能成功，所以**没有重试按钮**；也不代表没有
 * 数据：读数从未到达。升级 core 后这里自动出现真实数据。传输原话原样保留，可核对。
 */
@Composable
private fun ActivityMethodMissingCard(
    method: String,
    transportWords: String,
    modifier: Modifier = Modifier,
) {
    EmptyStateCard(
        icon = VadeIcons.Empty,
        title = "此 core 还没有 $method 方法",
        body = "core 对 $method 回答方法不存在（$transportWords）——" +
            "这不是加载失败，也不代表没有数据：这个 core 版本还没有这个读数。" +
            "升级 core 后，这里会显示真实数据。",
        modifier = modifier,
    )
}

/** Placeholder kept so the file compiles before U1-d grows real sections. */
@Composable
internal fun ActivitySpacer() {
    Spacer(Modifier.padding(0.dp))
}
