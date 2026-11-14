package vade.ui.usage

import kotlinx.serialization.json.JsonObject
import vade.ui.data.VadeErrorCategory
import vade.ui.protocol.BudgetUsage
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wired 用量 page's decision layer: `budget.ratio == null` (limit=0, state=off) must
 * render「未启用」and never 0%; the four `budget.state` values must be distinguishable;
 * `by_model` rows are ordered by tokens descending; `total_tokens` is the server's number
 * and never re-derived; and the three load states carry honest labels.
 *
 * Every DTO here is decoded from a JSON literal with the protocol's own `fromJson`, so a
 * projection bug cannot hide behind hand-built objects.
 */
class UsageWiringTest {

    private val json = VadeJson.default

    private fun resultOf(payload: String): SessionUsageGetResult =
        SessionUsageGetResult.fromJson(json.parseToJsonElement(payload) as JsonObject)

    // --------------------------------------------------- budget: ratio == null ≠ 0%

    @Test
    fun zeroLimitBudgetReadsNotEnabledNotZeroPercent() {
        // rpc.json §session/usage.get: limit=0 ⇒ ratio=null, state=off.
        val result = resultOf(
            """{"session_id":"s1","chat":{"prompt_tokens":1000,"completion_tokens":234,"total_tokens":1234,"turns":3,"partial_turns":0},
                "aux":{"total_tokens":0,"calls":0},"total_tokens":1234,"by_model":[],
                "day":null,"budget":{"scope":"session","limit":0,"used":1234,"ratio":null,"state":"off","enforced":false}}""",
        )
        val budget = projectBudget(result.budget)!!
        assertFalse(budget.enabled, "limit=0 时预算未启用")
        assertNull(budget.fraction, "未启用不画进度条")
        assertIs<TokenCell.NotReported>(budget.percent, "ratio=null 时没有百分比")
        val caption = budgetCaption(budget)
        assertTrue(caption.contains("未启用"), caption)
        assertFalse(caption.contains("%"), "绝不能把 ratio=null 当成 0%: $caption")
        assertTrue(caption.contains("1,234"), "已用数字仍然要显示: $caption")
        assertTrue(caption.contains("off"), "state 原文可见: $caption")
    }

    @Test
    fun fourBudgetStatesAreAllVisibleAndDistinct() {
        val labels = listOf("off", "ok", "warn", "exceeded").map { budgetStateText(it) }
        assertEquals(4, labels.toSet().size, "四态文案必须各不相同: $labels")
        listOf("off", "ok", "warn", "exceeded").forEachIndexed { i, raw ->
            assertTrue(labels[i].contains(raw), "状态原文必须可见: ${labels[i]}")
        }
        assertEquals("未启用（off）", labels[0])
        assertEquals("正常（ok）", labels[1])
        assertEquals("警告（warn）", labels[2])
        assertEquals("已超限（exceeded）", labels[3])
        // 未知原文照抄，不编造语义；缺失则是「暂无数据」。
        assertEquals("tilt", budgetStateText("tilt"))
        assertEquals("暂无数据", budgetStateText(null as String?))
    }

    @Test
    fun exceededBudgetCarriesTheOverTheCapCopy() {
        val rows = projectBudget(
            BudgetUsage(scope = "session", limit = 1000, used = 1200, ratio = 1.2, state = "exceeded", enforced = true),
        )!!
        assertEquals(BudgetLevel.EXCEEDED, rows.level)
        assertTrue(rows.enabled)
        val caption = budgetCaption(rows)
        assertTrue(caption.contains("已超限"), caption)
        assertTrue(caption.contains("120%"), "超限时百分比要如实超过 100%: $caption")
        assertTrue(caption.contains("超出即阻断"), caption)
        assertTrue(rows.fraction!! <= 1f, "进度条仍夹在 0..1")
    }

    @Test
    fun warnedBudgetKeepsTheRawStateAndPercent() {
        val rows = projectBudget(
            BudgetUsage(scope = "session", limit = 20_000, used = 17_000, ratio = 0.85, state = "warn", enforced = false),
        )!!
        assertEquals(BudgetLevel.WARNING, rows.level)
        val caption = budgetCaption(rows)
        assertTrue(caption.contains("警告（warn）"), caption)
        assertTrue(caption.contains("85%"), caption)
        assertTrue(caption.contains("仅警告"), caption)
    }

    // --------------------------------------------------- by_model ordering / empty

    @Test
    fun byModelRowsAreSortedByTokensDescending() {
        val result = resultOf(
            """{"session_id":"s1","chat":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":1200,"turns":3,"partial_turns":0},
                "aux":null,"total_tokens":1200,
                "by_model":[{"model_id":"small","total_tokens":100,"turns":1},
                            {"model_id":"big","total_tokens":900,"turns":1},
                            {"model_id":"mid","total_tokens":200,"turns":1}],
                "day":null,"budget":null}""",
        )
        val rows = projectSessionUsage(result)!!
        assertEquals(
            listOf("big", "mid", "small"),
            rows.models.map { (it.name as TokenCell.Value).text },
            "多行 by_model 必须按 toks 降序",
        )
        assertEquals(listOf("900", "200", "100"), rows.models.map { (it.total as TokenCell.Value).text })
    }

    @Test
    fun emptyByModelDoesNotCrashAndKeepsChatData() {
        val result = resultOf(
            """{"session_id":"s1","chat":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"turns":1,"partial_turns":0},
                "aux":null,"total_tokens":15,"by_model":[],"day":null,"budget":null}""",
        )
        val rows = projectSessionUsage(result)!!
        assertTrue(rows.models.isEmpty())
        assertTrue(rows.hasData)
        assertEquals(TokenCell.Value("15"), rows.chatTotal)
        // 空 by_model 在整页投影里也不炸。
        val projection = projectUsage(result, lastTurn = null, notice = null)
        assertTrue(projection.hasAnyData)
        assertEquals(0, projection.session!!.models.size)
    }

    // --------------------------------------------------- total_tokens invariant

    @Test
    fun totalTokensIsTheServerNumberAndEqualsChatPlusAux() {
        val result = resultOf(
            """{"session_id":"s1","chat":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"turns":1,"partial_turns":0},
                "aux":{"total_tokens":30,"calls":2},"total_tokens":150,"by_model":[],"day":null,"budget":null}""",
        )
        val rows = projectSessionUsage(result)!!
        assertEquals(TokenCell.Value("150"), rows.total, "总 tokens 原样显示服务端 total_tokens")
        assertEquals(true, rows.totalMatches)
        // 不变式：total == chat + aux（同一组数字，两个视图）。
        assertEquals(150L, result.chat!!.totalTokens + result.aux!!.totalTokens)
        assertEquals(result.totalTokens, result.chat.totalTokens + result.aux.totalTokens)
    }

    @Test
    fun contradictoryServerTotalStaysVisibleAndIsNeverRecomputed() {
        val result = resultOf(
            """{"session_id":"s1","chat":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"turns":1,"partial_turns":0},
                "aux":{"total_tokens":30,"calls":2},"total_tokens":999,"by_model":[],"day":null,"budget":null}""",
        )
        val rows = projectSessionUsage(result)!!
        assertEquals(TokenCell.Value("999"), rows.total, "客户端不得改写成 150 —— 原样显示并标记不一致")
        assertEquals(false, rows.totalMatches)
    }

    @Test
    fun missingTotalTokensIsNotReportedNotZero() {
        val result = resultOf("""{"session_id":"s1","chat":null,"aux":null,"total_tokens":null,"by_model":[],"day":null,"budget":null}""")
        val rows = projectSessionUsage(result)!!
        assertIs<TokenCell.NotReported>(rows.total)
        assertNull(rows.totalMatches)
        assertEquals("暂无数据", rows.total.display())
    }

    // --------------------------------------------------- three load states

    @Test
    fun threeLoadStatesHaveDistinctHonestLabels() {
        assertEquals("加载中", usageStateLabel(UsageLoadState.Loading))
        assertEquals("已加载", usageStateLabel(UsageLoadState.Ready(resultOf("""{"session_id":"s1","by_model":[]}"""))))
        assertEquals(
            "加载失败",
            usageStateLabel(
                UsageLoadState.Failed(UsageLoadError(VadeErrorCategory.Retryable, VadeErrorCodes.TIMEOUT, "超时")),
            ),
        )
    }

    @Test
    fun failedStateTextLeadsWithTheCategoryCodeAndAHint() {
        val error = UsageLoadError(VadeErrorCategory.NeedsReconnect, VadeErrorCodes.UNAVAILABLE, "send failed")
        assertTrue(error.label.contains("需要重连"), error.label)
        assertTrue(error.label.contains("UNAVAILABLE"), error.label)
        assertTrue(error.label.contains("send failed"), error.label)
        assertTrue(error.hint.contains("重连"), error.hint)
        assertEquals("重连后重试", error.retryLabel)
        // Fatal 仍然给重试入口，但文案承认通常无效。
        val fatal = UsageLoadError(VadeErrorCategory.Fatal, VadeErrorCodes.PROTOCOL_MISMATCH, "x")
        assertTrue(fatal.retryLabel.contains("重试"), fatal.retryLabel)
        assertTrue(fatal.hint.contains("版本") || fatal.hint.contains("配对"), fatal.hint)
    }

    @Test
    fun readyStateCarriesTheSameDtoTheProjectionRenders() {
        val result = resultOf(
            """{"session_id":"s1","chat":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"turns":1,"partial_turns":0},
                "aux":{"total_tokens":30,"calls":2},"total_tokens":150,"by_model":[],"day":null,
                "budget":{"scope":"session","limit":1000,"used":400,"ratio":0.4,"state":"ok","enforced":false}}""",
        )
        val ready = UsageLoadState.Ready(result)
        val projection = projectUsage(ready.usage, lastTurn = null, notice = null)
        assertTrue(projection.hasAnyData)
        assertEquals(TokenCell.Value("150"), projection.session!!.total)
        assertEquals(BudgetLevel.NORMAL, projection.budget!!.level)
        assertTrue(budgetCaption(projection.budget!!).contains("40%"))
    }
}
