package vade.ui.destinations

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonObject
import vade.ui.data.FakeSessionSource
import vade.ui.data.RealSessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.VadeErrorCategory
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import vade.ui.shell.UiState
import vade.ui.subagent.SubagentLoadError
import vade.ui.subagent.SubagentLoadState
import vade.ui.subagent.SubagentLoader
import vade.ui.subagent.SubagentPage
import vade.ui.subagent.SubagentRunView
import vade.ui.subagent.SubagentSection
import vade.ui.subagent.SubagentSectionLive
import vade.ui.subagent.SubagentSource
import vade.ui.subagent.SubagentState
import vade.ui.subagent.subagentStateLabel
import vade.ui.usage.UsageLoadError
import vade.ui.usage.UsageLoadState
import vade.ui.usage.UsagePanel
import vade.ui.usage.UsageSection
import vade.ui.usage.UsageSectionLive
import vade.ui.usage.UsageSource
import vade.ui.usage.usageStateLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * G · 活动页的两个面板**真的被喂了数据**。
 *
 * 本批修的是「零件齐但没人喂数据」：`SubagentCard` / `UsageCard` 早就写好、测好、阴影也修
 * 好了，但 `ActivityScreen` 从来没传过 `subagentRuns` / `usage`，于是页面永远只有
 * EmptyStateCard。这些测试因此不是再测一遍渲染细节，而是钉住三件此前无人验证的事：
 *
 *  1. **有数据时页面画的是数据**（不是空态，也不是「未接线」的托辞）；
 *  2. **加载中 / 加载失败 / 真没数据** 三种「什么都没有」用不同文案区分，失败态有重试出口；
 *  3. 读取口**真的被调到**，而且带的是用户选中的那个 `session_id`（不是列表第一行）。
 *
 * 另外钉住一条结构性事实：活动页在 Shell 里是**全局**页（`UiState.select` 会清空
 * `selectedSessionId`），所以用量必须先用选择器选会话；`subagent/list` 的 `session_id`
 * 可以省略（契约：across sessions），于是它按全局列。
 */
class ActivityWiringTest {

    // ---------------------------------------------------------------- fixtures

    private val json = VadeJson.default

    /** 真 codec 解码出来的 `session/usage.get` 回包 —— 不手搓对象，避免绕过 fromJson。 */
    private fun usageDto(
        sessionId: String,
        chatTotal: Long = 360,
        prompt: Long = 120,
        completion: Long = 240,
        aux: Long = 30,
    ): SessionUsageGetResult = SessionUsageGetResult.fromJson(
        json.parseToJsonElement(
            """{"session_id":"$sessionId",
                "chat":{"prompt_tokens":$prompt,"completion_tokens":$completion,"total_tokens":$chatTotal,
                        "turns":2,"partial_turns":0},
                "aux":{"total_tokens":$aux,"calls":1},
                "total_tokens":${chatTotal + aux},
                "by_model":[],
                "day":null,
                "budget":{"scope":"session","limit":4000,"used":${chatTotal + aux},
                          "ratio":0.1,"state":"ok","enforced":false}}""",
        ) as JsonObject,
    )

    /** 一份「服务端答了，但一个可显示字段都没有」的回包（真没数据的最小形态）。 */
    private fun emptyUsageDto(sessionId: String): SessionUsageGetResult =
        SessionUsageGetResult.fromJson(
            json.parseToJsonElement("""{"session_id":"$sessionId"}""") as JsonObject,
        )

    private fun runView(
        runId: String = "run_0123456789abcdef0123456789abcdef",
        parent: String = "s-a",
        label: String = "检索助手",
        state: SubagentState = SubagentState.COMPLETED,
    ): SubagentRunView = SubagentRunView(
        runId = runId,
        parentSessionId = parent,
        parentTurnId = "t-1",
        childSessionId = "s-child",
        label = label,
        state = state,
        createdAt = 1_700_000_000_000,
        finishedAt = 1_700_000_012_300,
        resultDigest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        resultChars = 4123,
        errorCode = null,
        resultPreview = "子代理已检索 3 个文件并汇总要点。",
        durationMs = 12_300,
    )

    private fun session(id: String, title: String) = SessionSummary(
        id = id,
        title = title,
        preview = "……",
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000,
        updatedLabel = "刚刚",
    )

    /** 记录每次调用的 session_id；[outcomes] 逐次消费，用完最后一条后一直复用。 */
    private class ScriptedUsageSource(
        private val outcomes: MutableList<Result<SessionUsageGetResult>>,
    ) : UsageSource {
        val calls = mutableListOf<String>()

        override suspend fun load(sessionId: String): SessionUsageGetResult {
            calls += sessionId
            val next = if (outcomes.size > 1) outcomes.removeAt(0) else outcomes.first()
            return next.getOrThrow()
        }
    }

    private class ScriptedSubagentSource(
        private val outcomes: MutableList<Result<SubagentPage>>,
    ) : SubagentSource {
        val calls = mutableListOf<String?>()

        override suspend fun list(sessionId: String?): SubagentPage {
            calls += sessionId
            val next = if (outcomes.size > 1) outcomes.removeAt(0) else outcomes.first()
            return next.getOrThrow()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.setActivity(content: @Composable () -> Unit) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) { content() }
        }
    }

    /** 等到某个文本真的出现在语义树上（比「等调用次数」更贴近「用户看到了什么」）。 */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.awaitText(text: String, substring: Boolean = false) {
        waitUntil(timeoutMillis = TIMEOUT) {
            onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ============================================================ 1 · 有数据真渲染

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun liveUsageRendersTheRealNumbersInsteadOfAnEmptyState() = runComposeUiTest {
        val source = ScriptedUsageSource(mutableListOf(Result.success(usageDto("s-a"))))
        setActivity {
            ActivityScreen(
                usageSource = source,
                initialScope = ActivityScope.Session("s-a"),
            )
        }
        waitUntil(timeoutMillis = TIMEOUT) { source.calls.isNotEmpty() }
        awaitText("390")

        // 页面画的是服务端数字（chat 合计 360 / total = chat+aux = 390），不是空态。
        onNodeWithText("总 tokens（chat + aux）").assertExists()
        onNodeWithText("360").assertExists()
        // 三种「什么都没有」的措辞一个都不许出现：这是「已加载」。
        onNodeWithText("本会话暂无用量").assertDoesNotExist()
        onNodeWithText("加载中…").assertDoesNotExist()
        onNodeWithText("用量加载失败").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun snapshotPathStillRendersRealContentWhenDtoSnapshotsArePassed() = runComposeUiTest {
        // 回归：不接读取口、直接给快照的旧用法必须仍然画内容（接线前它是空的，
        // 因为没人传；现在传了就得出数字）。
        setActivity {
            ActivityScreen(
                usage = usageDto("s-a"),
                subagentRuns = listOf(runView()),
            )
        }
        onNodeWithText("360").assertExists()
        onNodeWithText("检索助手").assertExists()
        onNodeWithText("暂无数据").assertDoesNotExist()
    }

    // ============================================================ 2 · 三态可辨

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun usageLoadingCardSaysLoadingAndShowsNoNumbers() = runComposeUiTest {
        setActivity { UsageSection(UsageLoadState.Loading, onRetry = {}) }
        onNodeWithText("加载中…").assertExists()
        onNodeWithText("本会话暂无用量").assertDoesNotExist()
        onNodeWithText("用量加载失败").assertDoesNotExist()
        onNodeWithText("暂无数据").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun usageFailedCardCarriesItsCategoryCodeAndRetryLabel() = runComposeUiTest {
        var retried = 0
        setActivity {
            UsageSection(
                state = UsageLoadState.Failed(
                    UsageLoadError(VadeErrorCategory.Retryable, VadeErrorCodes.TIMEOUT, "read timed out"),
                ),
                onRetry = { retried += 1 },
            )
        }
        onNodeWithText("用量加载失败").assertExists()
        onNodeWithText("可重试（TIMEOUT）：read timed out").assertExists()
        onNodeWithText("重试").performClick()
        assertEquals(1, retried, "失败卡的「重试」必须真的接到重试入口")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun wiredEmptyUsageIsNotTheUnwiredCopy() = runComposeUiTest {
        // 服务端答了，但没有任何可显示字段 → 「真没数据」，文案必须说读取成功了。
        setActivity { UsageSection(UsageLoadState.Ready(emptyUsageDto("s-a")), onRetry = {}) }
        onNodeWithText("本会话暂无用量").assertExists()
        onNodeWithText("已成功返回", substring = true).assertExists()
        // 「未接线」的说法在这里是假的，不许出现。
        onNodeWithText("接入 session/usage.get 与 turn.completed.usage 后", substring = true)
            .assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun unwiredEmptyUsageKeepsTheOriginalCopy() = runComposeUiTest {
        // 回归：一个读取口都没有时，空态仍是原来那句（页面对接线前的调用方逐字节不变）。
        setActivity { UsagePanel(session = null, lastTurn = null, budgetNotice = null) }
        onNodeWithText("接入 session/usage.get 与 turn.completed.usage 后", substring = true).assertExists()
        onNodeWithText("本会话暂无用量").assertDoesNotExist()
    }

    @Test
    fun theThreeStateWordsAreAllDifferent() {
        val usageWords = listOf(
            usageStateLabel(UsageLoadState.Loading),
            usageStateLabel(UsageLoadState.Ready(emptyUsageDto("s"))),
            usageStateLabel(UsageLoadState.Failed(UsageLoadError(VadeErrorCategory.Retryable, null, "x"))),
        )
        assertEquals(3, usageWords.toSet().size, "用量三态要有三个不同的词: $usageWords")
        assertEquals("加载中", usageWords[0])

        val subagentWords = listOf(
            subagentStateLabel(SubagentLoadState.Loading),
            subagentStateLabel(SubagentLoadState.Ready(SubagentPage(emptyList()))),
            subagentStateLabel(
                SubagentLoadState.Failed(SubagentLoadError(VadeErrorCategory.Retryable, null, "x")),
            ),
        )
        assertEquals(3, subagentWords.toSet().size, "子代理三态要有三个不同的词: $subagentWords")
        assertEquals("加载中", subagentWords[0])
    }

    // ============================================================ 3 · 选择会话 → 读该会话

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun globalScopeNeverFiresAUsageRequestAndSaysWhy() = runComposeUiTest {
        val source = ScriptedUsageSource(mutableListOf(Result.success(usageDto("s-a"))))
        setActivity { ActivityScreen(usageSource = source) }

        assertEquals(emptyList(), source.calls, "全局范围不许为用量发请求（契约没有跨会话读法）")
        onNodeWithText("全局用量需要按会话读取").assertExists()
        onNodeWithText("查看范围").assertExists()
        // 这不是「空数据」也不是「加载中」，更不是假聚合。
        onNodeWithText("本会话暂无用量").assertDoesNotExist()
        onNodeWithText("加载中…").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pickingASessionLoadsExactlyThatSessionsUsage() = runComposeUiTest {
        val source = ScriptedUsageSource(
            mutableListOf(Result.success(usageDto("s-b", chatTotal = 222))),
        )
        setActivity {
            ActivityScreen(
                usageSource = source,
                sessions = listOf(session("s-a", "会话甲"), session("s-b", "会话乙")),
            )
        }
        // 默认全局：一次请求都没有。
        assertEquals(emptyList(), source.calls)

        onNodeWithText("会话乙").performScrollTo().performClick()
        waitUntil(timeoutMillis = TIMEOUT) { source.calls.isNotEmpty() }
        awaitText("252") // 222 + aux 30

        assertEquals(listOf("s-b"), source.calls, "必须按用户点的那一个会话读，且只读它")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun switchingSessionsLoadsTheNewOneAndDropsTheOldOnesNumbers() = runComposeUiTest {
        val source = ScriptedUsageSource(
            mutableListOf(
                Result.success(usageDto("s-a", chatTotal = 111)),
                Result.success(usageDto("s-b", chatTotal = 222)),
            ),
        )
        setActivity {
            ActivityScreen(
                usageSource = source,
                sessions = listOf(session("s-a", "会话甲"), session("s-b", "会话乙")),
            )
        }
        onNodeWithText("会话甲").performScrollTo().performClick()
        awaitText("141") // 111 + 30

        onNodeWithText("会话乙").performScrollTo().performClick()
        awaitText("252") // 222 + 30

        assertEquals(listOf("s-a", "s-b"), source.calls)
        onNodeWithText("141").assertDoesNotExist() // 旧会话的数字不许残留
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun aFailedUsageReadCanBeRetriedIntoTheRealNumbers() = runComposeUiTest {
        val source = ScriptedUsageSource(
            mutableListOf(
                Result.failure(VadeException(VadeErrorCodes.TIMEOUT, "read timed out")),
                Result.success(usageDto("s-a")),
            ),
        )
        setActivity { UsageSectionLive(source = source, sessionId = "s-a") }
        awaitText("用量加载失败")
        onNodeWithText("可重试（TIMEOUT）：read timed out").assertExists()

        onNodeWithText("重试").performClick()
        awaitText("390")

        assertEquals(listOf("s-a", "s-a"), source.calls, "重试必须复用同一个 session_id")
        onNodeWithText("用量加载失败").assertDoesNotExist()
    }

    // ============================================================ 4 · 子代理

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun subagentListRunsGloballyWhenNoSessionIsPicked() = runComposeUiTest {
        val source = ScriptedSubagentSource(mutableListOf(Result.success(SubagentPage(listOf(runView())))))
        setActivity { ActivityScreen(subagentSource = source) }
        awaitText("检索助手")

        assertEquals(listOf<String?>(null), source.calls, "活动页是全局页 → subagent/list 省略 session_id")
        onNodeWithText("s-a").assertExists() // 父会话：跨会话列表里唯一能说明归属的字段
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun emptyGlobalSubagentListUsesTheGlobalCopy() = runComposeUiTest {
        val source = ScriptedSubagentSource(mutableListOf(Result.success(SubagentPage(emptyList()))))
        setActivity { ActivityScreen(subagentSource = source) }
        awaitText("当前没有子代理")

        onNodeWithText("全部会话都还没有派生任何子代理运行", substring = true).assertExists()
        onNodeWithText("本会话尚未派生任何子代理运行", substring = true).assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun pickingASessionReScopesTheSubagentListAndItsEmptyCopy() = runComposeUiTest {
        val source = ScriptedSubagentSource(mutableListOf(Result.success(SubagentPage(emptyList()))))
        setActivity {
            ActivityScreen(
                subagentSource = source,
                sessions = listOf(session("s-a", "会话甲")),
            )
        }
        awaitText("全部会话都还没有派生任何子代理运行", substring = true)

        onNodeWithText("会话甲").performScrollTo().performClick()
        waitUntil(timeoutMillis = TIMEOUT) { source.calls.size == 2 }
        awaitText("本会话尚未派生任何子代理运行", substring = true)

        assertEquals(listOf<String?>(null, "s-a"), source.calls)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun failedSubagentListShowsItsCategoryAndRetriesTheSameScope() = runComposeUiTest {
        val source = ScriptedSubagentSource(
            mutableListOf(
                Result.failure(VadeException(VadeErrorCodes.UNAVAILABLE, "transport closed")),
                Result.success(SubagentPage(listOf(runView()))),
            ),
        )
        setActivity { SubagentSectionLive(source = source, sessionId = null) }
        awaitText("子代理列表加载失败")
        onNodeWithText("需要重连（UNAVAILABLE）：transport closed").assertExists()

        onNodeWithText("重连后重试").performClick()
        awaitText("检索助手")

        assertEquals(listOf<String?>(null, null), source.calls, "重试必须复用同一个范围")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun subagentLoadingCardSaysLoadingAndBuildsNoCards() = runComposeUiTest {
        setActivity { SubagentSection(SubagentLoadState.Loading, onRetry = {}) }
        onNodeWithText("加载中…").assertExists()
        onNodeWithText("正在请求 subagent/list", substring = true).assertExists()
        onNodeWithText("当前没有子代理").assertDoesNotExist()
        onNodeWithText("子代理列表加载失败").assertDoesNotExist()
    }

    // ============================================================ 5 · 回归 + 结构

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun noSourceAtAllIsStillThePreWiringPage() = runComposeUiTest {
        setActivity { ActivityScreen() }
        // D87 三个占位块保持原样。
        onNodeWithText("暂无运行中的 turn").assertExists()
        onNodeWithText("暂无待审批").assertExists()
        onNodeWithText("暂无失败记录").assertExists()
        // 两个面板仍是「未接线」空态（文案逐字不变）。
        onNodeWithText("接入 session/usage.get 与 turn.completed.usage 后", substring = true).assertExists()
        onNodeWithText("接入 subagent/list 与 subagent.get 后", substring = true).assertExists()
        // 没有读取口 → 连选择器都不出现（页面此时与接线前一致）。
        onNodeWithText("查看范围").assertDoesNotExist()
        onNodeWithText("全局用量需要按会话读取").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theRecommendedShellCallSiteCompilesAndStaysNeutralOnTheFakeSource() = runComposeUiTest {
        // 这就是 `shell/AppShell.kt:406` 该写的样子（本轮不许改 AppShell，但这里把那几行
        // 逐字钉住：一旦签名变了，这条测试先红，而不是等别人接线时才发现）。
        val state = UiState(
            initialBar = BottomBarStyle.Floating,
            initialSkinId = "material",
            initialDest = Destination.Activity,
        )
        setActivity {
            ActivityScreen(
                contentPadding = PaddingValues(0.dp),
                sessions = state.groups().flatMap { it.sessions },
                source = state.source,
            )
        }
        // 默认源是 FakeSessionSource → 两个窄口都是 null → 页面与接线前一致：
        // 既不会冒出假数字，也不会冒出一个空的选择器。
        onNodeWithText("接入 session/usage.get 与 turn.completed.usage 后", substring = true).assertExists()
        onNodeWithText("查看范围").assertDoesNotExist()
    }

    @Test
    fun fakeSourceGetsNoLiveSeamsAndRealSourceGetsBoth() {
        // 假数据源绝不拿到假读取口（与 AdapterHost 同一条规矩）。
        val fake = activitySourcesOf(FakeSessionSource())
        assertNull(fake.usage, "假源不交用量读取口")
        assertNull(fake.subagents, "假源不交子代理读取口")

        // 真源即使还没连上也要交出窄口：读取时以 UNAVAILABLE 明确失败（可重试），
        // 而不是让页面假装「没有数据」。
        val real = activitySourcesOf(RealSessionSource(host = "127.0.0.1", port = 1))
        assertNotNull(real.usage)
        assertNotNull(real.subagents)
    }

    // ============================================================ 6 · loader 纪律

    @Test
    fun subagentLoaderDropsTheStaleScopeReply() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateA = CompletableDeferred<SubagentPage>()
        val gateAll = CompletableDeferred<SubagentPage>()
        val gates = mapOf<String?, CompletableDeferred<SubagentPage>>("s-a" to gateA, null to gateAll)
        val source = object : SubagentSource {
            override suspend fun list(sessionId: String?) = gates.getValue(sessionId).await()
        }
        val loader = SubagentLoader(source, scope)
        try {
            loader.load("s-a")
            loader.load(null)
            gateAll.complete(SubagentPage(listOf(runView(label = "全局的"))))
            awaitLoader(
                loader = loader,
                predicate = {
                    (it as? SubagentLoadState.Ready)?.page?.runs?.singleOrNull()?.label == "全局的"
                },
            )

            // 旧范围的迟到回包不许覆盖新范围。
            gateA.complete(SubagentPage(listOf(runView(label = "迟到的"))))
            Thread.sleep(120)
            val ready = loader.state as SubagentLoadState.Ready
            assertEquals("全局的", ready.page.runs.single().label)
            assertNull(loader.sessionId, "当前范围仍是全局")
        } finally {
            loader.dispose()
            scope.cancel()
        }
    }

    @Test
    fun subagentLoaderRetryBeforeAnyLoadIsANoOp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var calls = 0
        val source = object : SubagentSource {
            override suspend fun list(sessionId: String?): SubagentPage {
                calls += 1
                return SubagentPage(emptyList())
            }
        }
        val loader = SubagentLoader(source, scope)
        try {
            loader.retry()
            Thread.sleep(60)
            assertEquals(0, calls, "第一次 load 之前的 retry 不该发请求")
            assertNull(loader.sessionId)
            assertEquals(SubagentLoadState.Loading, loader.state)
        } finally {
            loader.dispose()
            scope.cancel()
        }
    }

    @Test
    fun scopeLabelsAndNullsAreNotConflated() {
        assertEquals("全部会话", activityScopeLabel(ActivityScope.All))
        assertEquals("会话甲", activityScopeLabel(ActivityScope.Session("s-a"), "会话甲"))
        // 空标题退回 id：绝不给一个空白药丸。
        assertEquals("s-a", activityScopeLabel(ActivityScope.Session("s-a"), "   "))
        assertNull(ActivityScope.All.sessionIdOrNull())
        assertEquals("s-a", ActivityScope.Session("s-a").sessionIdOrNull())
    }

    private fun awaitLoader(
        loader: SubagentLoader,
        predicate: (SubagentLoadState) -> Boolean,
        timeoutMs: Long = 3_000,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(loader.state)) return
            Thread.sleep(10)
        }
        throw AssertionError("等待 loader 状态超时，当前：${loader.state}")
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
