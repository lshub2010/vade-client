package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import vade.ui.data.TimeBucket
import vade.ui.data.SessionSummary
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import vade.ui.subagent.SubagentPage
import vade.ui.subagent.SubagentRunView
import vade.ui.subagent.SubagentSource
import vade.ui.subagent.SubagentState
import vade.ui.usage.UsageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * g-state-coverage · 活动页的 **Loading / Empty / Failed** 三态底线 + 「不支持」第四面。
 *
 * 与 `ActivityWiringTest` 的分工：那边主要钉「两个读数区真的被喂了数据」与选择器语义；
 * 这里钉**整屏**在每种状态下的可见面 —— 有占位（不是空白）、文案互相区分、失败有重试、
 * 方法不存在是独立的第三态（不渲染成失败脸，因为重试永不成功）。
 *
 * 装置：两个照稿读取口（`UsageSource` / `SubagentSource` 的可数假件），范围固定为
 * `Session("s-a")`（活动页是全局页，用量必须选会话；子代理两种范围都能读）。
 */
@OptIn(ExperimentalTestApi::class)
class ActivityThreeStateTest {

    // ------------------------------------------------------------------ 装置

    private val json = VadeJson.default

    /** 真 codec 解码出来的 `session/usage.get` 回包（避免绕过 fromJson 手搓对象）。 */
    private fun usageDto(sessionId: String, chatTotal: Long = 360): SessionUsageGetResult =
        SessionUsageGetResult.fromJson(
            json.parseToJsonElement(
                """{"session_id":"$sessionId",
                "chat":{"prompt_tokens":120,"completion_tokens":240,"total_tokens":$chatTotal,
                        "turns":2,"partial_turns":0},
                "aux":{"total_tokens":30,"calls":1},
                "total_tokens":${chatTotal + 30},
                "by_model":[],
                "day":null,
                "budget":null}""",
            ) as JsonObject,
        )

    /** 一份「服务端答了，但没有任何可显示字段」的回包（真没数据的最小形态）。 */
    private fun emptyUsageDto(sessionId: String): SessionUsageGetResult =
        SessionUsageGetResult.fromJson(
            json.parseToJsonElement("""{"session_id":"$sessionId"}""") as JsonObject,
        )

    private fun runView(): SubagentRunView = SubagentRunView(
        runId = "run_0123456789abcdef0123456789abcdef",
        parentSessionId = "s-a",
        parentTurnId = "t-1",
        childSessionId = "s-child",
        label = "检索助手",
        state = SubagentState.COMPLETED,
        createdAt = 1_700_000_000_000,
        finishedAt = 1_700_000_012_300,
        resultDigest = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        resultChars = 4123,
        errorCode = null,
        resultPreview = "子代理已检索 3 个文件并汇总要点。",
        durationMs = 12_300,
    )

    /** 照稿用量口：先挂起（Loading 可观测），放行后逐次消费 [outcomes]。 */
    private class ScriptedUsageSource(
        private val outcomes: MutableList<Result<SessionUsageGetResult>>,
    ) : UsageSource {
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(sessionId: String): SessionUsageGetResult {
            calls += sessionId
            gate?.await()
            val next = if (outcomes.size > 1) outcomes.removeAt(0) else outcomes.first()
            return next.getOrThrow()
        }
    }

    /** 照稿子代理口：同上，`sessionId = null` 也是合法范围。 */
    private class ScriptedSubagentSource(
        private val outcomes: MutableList<Result<SubagentPage>>,
    ) : SubagentSource {
        val calls = mutableListOf<String?>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun list(sessionId: String?): SubagentPage {
            calls += sessionId
            gate?.await()
            val next = if (outcomes.size > 1) outcomes.removeAt(0) else outcomes.first()
            return next.getOrThrow()
        }
    }

    private fun ComposeUiTest.nonBlankTexts(): List<String> {
        val out = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { text ->
                if (text.text.isNotBlank()) out += text.text
            }
            node.children.forEach { walk(it) }
        }

        walk(onRoot(useUnmergedTree = true).fetchSemanticsNode())
        return out
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeUiTest.setActivity(
        usage: UsageSource? = null,
        subagents: SubagentSource? = null,
    ) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ActivityScreen(
                    usageSource = usage,
                    subagentSource = subagents,
                    initialScope = ActivityScope.Session("s-a"),
                )
            }
        }
    }

    // ============================================================ 1 · Loading（整页有占位）

    @Test
    fun loadingPageShowsVisiblePlaceholdersAndInventsNoOutcome() = runComposeUiTest {
        val usage = ScriptedUsageSource(mutableListOf(Result.success(usageDto("s-a")))).also { it.gate = CompletableDeferred<Unit>() }
        val subagents = ScriptedSubagentSource(mutableListOf(Result.success(SubagentPage(listOf(runView())))))
            .also { it.gate = CompletableDeferred<Unit>() }
        setActivity(usage, subagents)

        // 两个读数区都在飞：各自的「加载中」占位可见（不是空白，也不提前下结论）。
        waitUntil(timeoutMillis = 10_000) { usage.calls.isNotEmpty() && subagents.calls.isNotEmpty() }
        assertTrue(
            onAllNodesWithText("加载中…").fetchSemanticsNodes().isNotEmpty(),
            "两个读数区在飞时至少要有一张「加载中」卡",
        )
        onNodeWithText("本会话暂无用量").assertDoesNotExist()
        onNodeWithText("当前没有子代理").assertDoesNotExist()
        onNodeWithText("用量加载失败").assertDoesNotExist()
        onNodeWithText("子代理列表加载失败").assertDoesNotExist()
        assertTrue(nonBlankTexts().isNotEmpty(), "Loading 态整页不许裸空白")
    }

    // ============================================================ 2 · Empty（真没数据，可行动）

    @Test
    fun emptyReadsSayWhatServerActuallyAnsweredAndStayActionable() = runComposeUiTest {
        val usage = ScriptedUsageSource(mutableListOf(Result.success(emptyUsageDto("s-a"))))
        val subagents = ScriptedSubagentSource(mutableListOf(Result.success(SubagentPage(emptyList()))))
        setActivity(usage, subagents)

        awaitText("本会话暂无用量")
        awaitText("当前没有子代理")

        // 用量空态说「读取已成功、回包里没有可显示字段」——不是失败，也不是未接线。
        onNodeWithText("已成功返回", substring = true).assertExists()
        // 整页还有 D87 占位块撑着（不是一片空白）。
        onNodeWithText("暂无运行中的 turn").assertExists()
        onNodeWithText("加载中…").assertDoesNotExist()
        onNodeWithText("用量加载失败").assertDoesNotExist()
        assertTrue(nonBlankTexts().isNotEmpty(), "Empty 态整页不许裸空白")
    }

    // ============================================================ 3 · Failed（分类 + 原话 + 可点重试）

    @Test
    fun failedReadsCarryCategoryAndTransportWordsAndRetryReallyResends() = runComposeUiTest {
        val usage = ScriptedUsageSource(
            mutableListOf(
                Result.failure(VadeException(VadeErrorCodes.TIMEOUT, "read timed out")),
                Result.success(usageDto("s-a")),
            ),
        )
        val subagents = ScriptedSubagentSource(
            mutableListOf(
                Result.failure(VadeException(VadeErrorCodes.UNAVAILABLE, "transport closed")),
                Result.success(SubagentPage(listOf(runView()))),
            ),
        )
        setActivity(usage, subagents)

        awaitText("用量加载失败")
        awaitText("子代理列表加载失败")
        // 分类文案 + C-04 code + 传输原话。
        onNodeWithText("可重试（TIMEOUT）：read timed out").assertExists()
        onNodeWithText("需要重连（UNAVAILABLE）：transport closed").assertExists()
        assertEquals(listOf("s-a"), usage.calls)
        assertEquals(listOf<String?>("s-a"), subagents.calls)

        // 两张失败卡各自的重试钮真的重发同一个范围（卡在滚动列里：先滚到可见再点）。
        onNodeWithText("重试").performScrollTo().performClick()
        waitForIdle()
        onNodeWithText("重连后重试").performScrollTo().performClick()
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { usage.calls.size == 2 && subagents.calls.size == 2 }
        assertEquals(listOf("s-a", "s-a"), usage.calls, "用量重试必须复用同一个 session_id")
        assertEquals(listOf<String?>("s-a", "s-a"), subagents.calls, "子代理重试必须复用同一个范围")
        awaitText("390") // 360 + 30：失败卡被真实数字替换，不是被另一张卡洗掉
        awaitText("检索助手")
        onNodeWithText("用量加载失败").assertDoesNotExist()
        onNodeWithText("子代理列表加载失败").assertDoesNotExist()
        assertTrue(nonBlankTexts().isNotEmpty(), "Failed 态整页不许裸空白")
    }

    // ============================================================ 4 · 不支持（第三态）

    @Test
    fun methodMissingReadsAreTheirOwnFaceWithoutARetryButton() = runComposeUiTest {
        val usage = ScriptedUsageSource(
            mutableListOf(Result.failure(VadeException("-32601", "method not found: session/usage.get"))),
        )
        val subagents = ScriptedSubagentSource(
            mutableListOf(
                Result.failure(VadeException(VadeErrorCodes.NOT_FOUND, "unknown method 'subagent/list'")),
            ),
        )
        setActivity(usage, subagents)

        awaitText("此 core 还没有 session/usage.get 方法")
        awaitText("此 core 还没有 subagent/list 方法")

        // 传输原话保留、可核对；但**不是**失败脸：没有重试钮（重试永不成功）。
        onNodeWithText("method not found", substring = true).assertExists()
        onNodeWithText("unknown method", substring = true).assertExists()
        onNodeWithText("用量加载失败").assertDoesNotExist()
        onNodeWithText("子代理列表加载失败").assertDoesNotExist()
        onNodeWithText("重试").assertDoesNotExist()
        // 也不许洗成「真没数据」。
        onNodeWithText("本会话暂无用量").assertDoesNotExist()
        onNodeWithText("当前没有子代理").assertDoesNotExist()
        assertTrue(nonBlankTexts().isNotEmpty(), "不支持态整页不许裸空白")
    }
}
