package vade.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import vade.ui.data.AdapterHost
import vade.ui.data.SessionSource
import vade.ui.data.TurnCompletedEvent
import vade.ui.data.VadeEvent
import vade.ui.destinations.activitySourcesOf
import vade.ui.protocol.ChatUsage
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.usage.UsageLoadState
import vade.ui.usage.UsageLoader
import vade.ui.usage.UsageSource

/**
 * g-topbar-usage · 会话顶栏的用量副标题：把「零调用点的 `session/usage.get`」接到
 * 详情顶栏上（`RealSessionSource.usage` 的第一个生产调用点）。
 *
 * ## 数据通路（与 `UiState` 的 loader/夹具二分同构）
 *
 *  - **真源**（实现 `AdapterHost` 的源，生产形态是 `AutoConnectingSessionSource`）：窄口复用
 *    `destinations` 的 [activitySourcesOf]（晚绑定 client —— 断线重连换 client 不换对象），经
 *    `usage/UsageLoader`（g-usage-wire 交付的三态 + 代数守卫 + retry + dispose）拉取；
 *    事件流收集 `turn.completed` 作**失效信号**（见 [SessionUsageBarLoader.onTurnCompleted]
 *    的契约引文：事件 usage 是单轮的，不能拿来改写会话总量）。
 *  - **假源**（`FakeSessionSource`）：零协程、同步 [UsageLoadState.Ready]，数字由
 *    [fixtureUsageResultOf] 按 session id 确定性派生 —— 这是任务书点名要的
 *    「假源显示假源数字（不报错）」，与整个夹具生态同一性质（标题、消息都是假数据）。
 *  - **加载中 / 失败 / 无数据**：一律不显示副标题 —— 绝不编数字；失败态改为
 *    「点按重试」入口（[UsageSubtitleLine.Retry]）。
 *
 * ## 数字格式化是纯函数
 *
 * [formatTokenCount] / [sessionUsageSubtitleText] 无状态、无平台 API，单独可测
 * （`TopBarUsageFormatTest` 钉 0 / 999 / 1.2k / 万 / null 五种）。
 *
 * ## 纪律（与 `ModulePageBodyLoader` 同款，不另立一套）
 *
 *  - **一帧一请求**：[SessionUsageBarLoader.start] 是唯一的上游节奏来源（由
 *    `LaunchedEffect(sessionId)` 驱动），随手重组不重发；
 *  - **状态带 key**：[SessionUsageBarLoader.sessionId] 先于请求写入，切换会话后的
 *    第一帧读到的是旧 key ⇒ 组合层直接显示 None，绝不让旧会话的数字串进新顶栏；
 *  - **dispose 之后一切写入停止**：组合离开（返回列表）即 [SessionUsageBarLoader.dispose]。
 */

// ---------------------------------------------------------------- 数字格式化（纯函数）

/**
 * 令牌数 → 量级词：`<1k` 原样（`0` / `999`）；`<1万` 一位小数 `k`（`1200` → `1.2k`，
 * 整数档去掉尾巴：`1000` → `1k`）；`≥1万` 一位小数 `万`（`123456` → `12.3万`）。
 * 四舍五入用整数十分位（half-up），不经 Double 字符串化 —— 三端逐字节一致。
 */
internal fun formatTokenCount(tokens: Long): String = when {
    tokens < 1_000L -> tokens.toString()
    tokens < 10_000L -> scaledTokenUnit(tokens, divisor = 1_000L, suffix = "k")
    else -> scaledTokenUnit(tokens, divisor = 10_000L, suffix = "万")
}

/** 十分位整数化：先 round-half-up 到 0.1 步进，再去掉整档的 `.0` 尾巴。 */
private fun scaledTokenUnit(tokens: Long, divisor: Long, suffix: String): String {
    val tenths = (tokens * 10 + divisor / 2) / divisor // round-half-up（tokens ≥ 0）
    val whole = tenths / 10
    val frac = tenths % 10
    return if (frac == 0L) "$whole$suffix" else "$whole.$frac$suffix"
}

/**
 * `session/usage.get` 回包 → 顶栏副标题一句话：`«量级» tokens · «轮» 轮`。
 *
 *  - 只取 **chat 视图**（会话对话本身的 tokens 与 `chat.turns`）：`aux` 是审计行的
 *    开销，不挂在对话顶栏上（与「轮次取 chat.turns」同一口径）；
 *  - `chat == null`（真源无数据）→ `null` —— 组合层据此**不显示副标题**，绝不编 `0`。
 */
internal fun sessionUsageSubtitleText(result: SessionUsageGetResult): String? =
    result.chat?.let { chat -> "${formatTokenCount(chat.totalTokens)} tokens · ${chat.turns} 轮" }

// ---------------------------------------------------------------- 假源数字（夹具路径）

/** 夹具 id 的确定性种子：手写的 31 进制折叠（不用 `String.hashCode` —— 三端算法不同）。 */
private fun fixtureSeedOf(sessionId: String): Int {
    var h = 0
    for (c in sessionId) h = h * 31 + c.code
    return if (h < 0) -(h + 1) else h // abs 且避开 Int.MIN_VALUE 的溢出
}

/**
 * 夹具路径的确定性用量。**这不是 core 的数字**：真源永远走 `session/usage.get`，
 * 这里只服务假源生态（任务书：「假源：显示假源数字（不报错）」）。
 * tokens 落在 990..17 369 —— 刻意横跨 1k 与 1万 两档，切会话换数（截图任务可见）。
 */
internal fun fixtureUsageResultOf(sessionId: String): SessionUsageGetResult {
    val seed = fixtureSeedOf(sessionId)
    val tokens = 990L + (seed and 0x3FFF)
    val turns = 1 + ((seed ushr 14) and 0x3F)
    return SessionUsageGetResult(
        sessionId = sessionId,
        chat = ChatUsage(
            promptTokens = tokens / 3,
            completionTokens = tokens - tokens / 3,
            totalTokens = tokens,
            turns = turns,
            partialTurns = 0,
        ),
        aux = null,
        totalTokens = tokens,
        byModel = emptyList(),
        day = null,
        budget = null,
    )
}

// ---------------------------------------------------------------- 副标题一行的三态

/** 顶栏副标题行：数字 / 失败重试入口 / 无（加载中、无数据、非当前会话）。 */
internal sealed interface UsageSubtitleLine {
    /** 已就绪的用量一句话。 */
    data class Text(val text: String) : UsageSubtitleLine

    /**
     * 失败态的重试入口：数字**不显示**（绝不残留旧数字），副标题位换成这句可点文案。
     * 任务书验收：「可点一次则请求次数 1→2」。
     */
    data class Retry(val label: String, val onRetry: () -> Unit) : UsageSubtitleLine

    /** 不显示任何副标题。 */
    data object None : UsageSubtitleLine
}

/** 失败态的副标题位文案（internal：wiring 测试引用同一份字面量）。 */
internal const val USAGE_RETRY_LABEL = "用量加载失败 · 点按重试"

// ---------------------------------------------------------------- loader 持有

/**
 * 顶栏用量行的持有者：真源走 [UsageLoader]（三态/代数守卫/retry/dispose 都是它交付的，
 * 本类不重复实现），假源走同步夹具数字（零协程，与 `UiState` 夹具路径同一纪律）。
 */
internal class SessionUsageBarLoader(
    source: SessionSource,
    /**
     * 真源路径的执行作用域。夹具路径传 `null` —— 该路径零协程、零事件订阅；
     * 真源路径必须给（组合层自建，测试注入 TestDispatcher）。
     */
    private val scope: CoroutineScope?,
    /** 测试注入的窄口覆盖；生产恒为 `null`（真源窄口由 [activitySourcesOf] 推导）。 */
    seamOverride: UsageSource? = null,
) {
    /**
     * 真源窄口（晚绑定 client：断线重连换 client 不换对象 —— `activitySourcesOf`
     * 的既有语义）；`null` = 夹具路径。
     *
     * A3 · 判据是 [AdapterHost]（能力接口），不是 `source is RealSessionSource`：
     * 真源在生产形态下是 `AutoConnectingSessionSource`，具体类型判断恒 false —— 顶栏于是
     * 退回夹具数字（编造的 token 数）。凡是 AdapterHost，`activitySourcesOf` **一定**交出
     * 非空窄口（真 seam，或读取即 UNAVAILABLE 的诚实占位），夹具数字永远出不来。
     */
    private val realSeam: UsageSource? = seamOverride
        ?: (source as? AdapterHost)?.let { activitySourcesOf(source).usage }

    private val loader: UsageLoader? = realSeam?.let {
        UsageLoader(it, requireNotNull(scope) { "真源路径必须提供执行作用域（只有夹具路径允许 null）" })
    }

    /** 事件流：真源才有（`turn.completed` 是唯一的失效信号）；夹具路径空流、不订阅。 */
    private val events: Flow<VadeEvent> = if (loader != null) source.observeEvents() else emptyFlow()

    private var eventsJob: Job? = null

    /** 当前状态归属的会话（**先于请求写入** —— key 纪律的判据）。 */
    var sessionId: String? by mutableStateOf<String?>(null)
        private set

    /** 夹具路径的同步 Ready 存放处；真源路径不用它（透传 [UsageLoader.state]）。 */
    private var fixtureState: UsageLoadState? by mutableStateOf<UsageLoadState?>(null)

    /**
     * 三态；`null` = 还没 start 过（组合第一帧）。真源路径**透传** [UsageLoader.state]
     * （它自己就是可观察 state，写发生在它自己的协程里）——不另设副本，否则就是两个
     * 各自为政的 state（实测：副本永远停在 null，界面永远画不出真源的数字）。
     */
    val state: UsageLoadState?
        get() = loader?.state ?: fixtureState

    /** 进入（或切换到）[sessionId]：发**一帧**；夹具路径同步置 Ready。 */
    fun start(sessionId: String) {
        this.sessionId = sessionId
        val ldr = loader
        if (ldr == null) {
            fixtureState = UsageLoadState.Ready(fixtureUsageResultOf(sessionId))
            return
        }
        ldr.load(sessionId)
        if (eventsJob == null) {
            eventsJob = requireNotNull(scope) { "真源路径必须提供执行作用域" }.launch {
                events.collect { onEvent(it) }
            }
        }
    }

    /** 失败态的重试入口：原样重发上一帧（复用 [UsageLoader.retry]）。 */
    fun retry() {
        loader?.retry()
    }

    /**
     * `turn.completed` 到达：失效并重拉一次。契约原文（`events.json` turn.completed）：
     * `usage` 是**该轮**的 prompt/completion/total —— 不是会话总量；会话总量与轮次只在
     * `session/usage.get` 的 chat 视图里，所以事件只能当失效信号，不能拿来改写数字
     * （任务书「事件里带 usage 就用它」的前提与契约不符，见 RESULT 的偏差记录）。
     *
     * 事件 payload 没有 session_id（契约原文），无法按会话过滤 —— 任何 turn.completed
     * 都重拉**当前显示的**会话一次；并发重拉由 [UsageLoader] 的代数守卫兜住（晚到回包
     * 直接丢弃，最后写入获胜），最坏情况只是一次多余请求，不会串数字。
     */
    fun onTurnCompleted() {
        val ldr = loader ?: return
        ldr.sessionId?.let { ldr.load(it) }
    }

    private fun onEvent(event: VadeEvent) {
        if (event is TurnCompletedEvent) onTurnCompleted()
    }

    /** 停止事件订阅与一切后续写入；幂等。 */
    fun dispose() {
        eventsJob?.cancel()
        eventsJob = null
        loader?.dispose()
    }
}

// ---------------------------------------------------------------- 组合层接线

/**
 * 会话详情顶栏的用量副标题（唯一的组合层入口；`AppShell` 详情分支调用）。
 *
 * 持有者 `remember(source)`：同一真源下切会话不重建（事件订阅不重启、loader 不丢），
 * `LaunchedEffect(sessionId)` 是唯一的节奏来源 —— 进入会话拉一次，随手重组不发帧。
 * 离开组合（返回列表）即 dispose + 取消自建作用域。
 *
 * **key 纪律**：holder 的状态必须属于 [sessionId] —— 切换会话后的第一帧 key 还没换
 * （`start` 在 effect 阶段才跑），组合层直接显示 None，绝不让旧会话的数字串进新顶栏。
 *
 * **source 引用必须稳定**（生产恒真：`UiState.source` 是 val）：holder 以 [source] 为
 * remember 键，调用方若每帧构造新 source 实例，holder 会被反复重建而 [LaunchedEffect]
 * 不重启（键只有 sessionId）→ 副标题永远 None —— 测试里要先把 source 提升到组合外。
 */
@Composable
internal fun rememberSessionUsageSubtitleLine(
    source: SessionSource,
    sessionId: String,
    /** 测试注入；生产恒为 null。 */
    scope: CoroutineScope? = null,
    /** 测试注入的窄口覆盖；生产恒为 null。 */
    seamOverride: UsageSource? = null,
): UsageSubtitleLine {
    val scopeIsOwned = scope == null // 注入的（测试）作用域归调用方所有，dispose 时不取消
    val ownedScope = remember(source) { scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val holder = remember(source) { SessionUsageBarLoader(source, ownedScope, seamOverride) }
    DisposableEffect(holder, ownedScope) {
        onDispose {
            holder.dispose()
            if (scopeIsOwned) ownedScope.cancel()
        }
    }
    // 键 = sessionId：进入/切会话各 start 一次。**前提：source 引用稳定** —— 生产传
    // `state.source`（UiState 的 val，实例恒定）；若调用方每帧造新 source 实例，
    // `remember(source)` 会换 holder 而这里不重启 → 永远 None（探针实测踩过，KDoc 写明）。
    LaunchedEffect(sessionId) { holder.start(sessionId) }

    val key = holder.sessionId
    val current: UsageLoadState? = holder.state
    return when {
        key != sessionId -> UsageSubtitleLine.None // key 纪律：旧会话的状态不进新顶栏
        current == null -> UsageSubtitleLine.None
        current is UsageLoadState.Loading -> UsageSubtitleLine.None // 加载中不显示（绝不编数字）
        current is UsageLoadState.Ready ->
            sessionUsageSubtitleText(current.usage)?.let(UsageSubtitleLine::Text) ?: UsageSubtitleLine.None
        current is UsageLoadState.Failed -> UsageSubtitleLine.Retry(USAGE_RETRY_LABEL, holder::retry)
        else -> UsageSubtitleLine.None
    }
}
