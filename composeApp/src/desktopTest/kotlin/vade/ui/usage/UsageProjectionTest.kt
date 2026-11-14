package vade.ui.usage

import kotlinx.serialization.json.JsonObject
import vade.ui.protocol.SessionUsageGetResult
import vade.ui.protocol.VadeJson
import vade.ui.protocol.VadeUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C-Activity · the 用量 panel's honesty contract, pinned against **real contract-shaped
 * DTOs**: every test decodes a JSON literal copied from rpc.json / events.json through
 * the protocol's own `fromJson` parsers, then projects it. A fake number can only sneak
 * past this suite by changing the projection itself — which is exactly what these tests
 * guard.
 */
class UsageProjectionTest {

    private val json = VadeJson.default

    private fun usageOf(payload: String): VadeUsage =
        VadeUsage.fromJson(json.parseToJsonElement(payload) as JsonObject)

    // ---------------------------------------------- ④ cached_tokens 缺失 ≠ 0

    @Test
    fun cachedTokensMissingRendersNotReportedNeverZero() {
        // events.json turn.completed.usage: cached_tokens is an OPTIONAL append key.
        val usage = usageOf(
            """{"prompt_tokens":120,"completion_tokens":456,"total_tokens":576}""",
        )
        val rows = projectTurnUsage(usage)!!
        assertIs<TokenCell.NotReported>(rows.cached)
        assertEquals("暂无数据", rows.cached.display())
        assertNotEquals("0", rows.cached.display(), "缺失的 cached_tokens 绝不能渲染成 0")
    }

    @Test
    fun cachedTokensZeroIsAnExplicitZeroAndDiffersFromMissing() {
        val zero = projectTurnUsage(
            usageOf("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2,"cached_tokens":0}"""),
        )!!
        val missing = projectTurnUsage(
            usageOf("""{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}"""),
        )!!

        val zeroCell = assertIs<TokenCell.Value>(zero.cached)
        assertEquals("0", zeroCell.text)
        assertIs<TokenCell.NotReported>(missing.cached)
        // The two facts must not collide — 缺失 ≠ 0.
        assertNotEquals<TokenCell>(zero.cached, missing.cached)
        assertNotEquals(zero.cached.display(), missing.cached.display())
    }

    // ---------------------------------------------- ⑤ 无数据 → 空态，不是 0

    @Test
    fun noInputYieldsTheEmptyStateNeverZeros() {
        val projection = projectUsage(null, null, null)
        assertFalse(projection.hasAnyData, "没喂任何 DTO 时不得声称有数据")
        assertNull(projection.turn)
        assertNull(projection.session)
        assertNull(projection.budget)
        assertNull(projection.notice)
        assertNull(projectTurnUsage(null))
        assertNull(projectSessionUsage(null))
        assertNull(projectBudget(null))
    }

    @Test
    fun partiallyWiredInputKeepsMissingBlocksEmpty() {
        // session/usage.get wired, turn.completed not yet: the turn block must stay 空态.
        val session = SessionUsageGetResult.fromJson(
            json.parseToJsonElement(
                """{"session_id":"s1","chat":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30,"turns":2,"partial_turns":1},"aux":{"total_tokens":5,"calls":1},"total_tokens":35,"by_model":[],"budget":{"scope":"session","limit":1000,"used":100,"ratio":0.1,"state":"ok","enforced":true}}""",
            ) as JsonObject,
        )
        val projection = projectUsage(session, lastTurn = null, notice = null)
        assertTrue(projection.hasAnyData)
        assertNull(projection.turn, "lastTurnUsage 缺失时本轮块保持空态")
        val turnRows = projectTurnUsage(null)
        assertNull(turnRows)
    }

    // ---------------------------------------------- ③ source=none → degraded 标识

    @Test
    fun sourceNoneFlagsTheDegradedBanner() {
        val degraded = projectTurnUsage(
            usageOf(
                """{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0,"source":"none","steps":4}""",
            ),
        )!!
        assertTrue(degraded.degraded, "usage.source=none 必须打降级标识（明确告知，不许隐藏）")
        assertEquals(TokenCell.Value("none"), degraded.source)

        val normal = projectTurnUsage(
            usageOf("""{"prompt_tokens":9,"completion_tokens":9,"total_tokens":18,"source":"session"}"""),
        )!!
        assertFalse(normal.degraded)
    }

    @Test
    fun missingOrUnknownSourceIsNotSilentlyDegraded() {
        val absent = projectTurnUsage(
            usageOf("""{"prompt_tokens":9,"completion_tokens":9,"total_tokens":18}"""),
        )!!
        assertFalse(absent.degraded, "source 缺失不是 source=none —— 不降级，但该格显示暂无数据")
        assertIs<TokenCell.NotReported>(absent.source)
    }

    // ---------------------------------------------- 会话累计 / by_model / day

    @Test
    fun sessionRowsCarryChatAuxModelsAndDay() {
        // rpc.json §session/usage.get result shape, verbatim values.
        val result = SessionUsageGetResult.fromJson(
            json.parseToJsonElement(
                """
                {"session_id":"s1",
                 "chat":{"prompt_tokens":12000,"completion_tokens":345,"total_tokens":12345,"turns":7,"partial_turns":2},
                 "aux":{"total_tokens":456,"calls":3},
                 "total_tokens":12801,
                 "by_model":[{"model_id":"claude-x","total_tokens":9000,"turns":5},{"model_id":null,"total_tokens":3801,"turns":2}],
                 "day":{"day":"2026-11-14","core_total_tokens":34567,"core_limit":100000,"session_tokens":12801,"state":"ok"},
                 "budget":{"scope":"session","limit":20000,"used":10000,"ratio":0.5,"state":"ok","enforced":false}}
                """.trimIndent(),
            ) as JsonObject,
        )
        val rows = projectSessionUsage(result)!!
        assertEquals(TokenCell.Value("12,000"), rows.chatPrompt)
        assertEquals(TokenCell.Value("345"), rows.chatCompletion)
        assertEquals(TokenCell.Value("12,345"), rows.chatTotal)
        assertEquals(TokenCell.Value("7"), rows.turns)
        assertEquals(TokenCell.Value("2"), rows.partialTurns)
        assertEquals(TokenCell.Value("456"), rows.auxTotal)
        assertEquals(2, rows.models.size)
        assertEquals(TokenCell.Value("claude-x"), rows.models[0].name)
        // model_id = null 是契约允许的形状 → 诚实「未知模型」，不是丢弃也不是空串。
        assertEquals(TokenCell.Value("未知模型"), rows.models[1].name)
        assertEquals(TokenCell.Value("9,000"), rows.models[0].total)
        // day: "34,567 / 100,000,ok"
        val day = assertIs<TokenCell.Value>(rows.day)
        assertTrue(day.text.startsWith("34,567 / 100,000"), "day cell: ${day.text}")
        assertTrue(day.text.endsWith("ok"), "day cell: ${day.text}")
    }

    @Test
    fun zeroLimitDayBudgetSaysUnsetInsteadOfDividingByZero() {
        val result = SessionUsageGetResult.fromJson(
            json.parseToJsonElement(
                """{"session_id":"s1","chat":null,"aux":null,"total_tokens":null,"by_model":[],"day":{"day":"2026-11-14","core_total_tokens":500,"core_limit":0,"session_tokens":500,"state":"off"},"budget":null}""",
            ) as JsonObject,
        )
        val rows = projectSessionUsage(result)!!
        val day = assertIs<TokenCell.Value>(rows.day)
        assertTrue(day.text.contains("未设上限"), "limit=0 时说「未设上限」，不打印 x / 0: ${day.text}")
    }

    // ---------------------------------------------- 格式化

    @Test
    fun tokenCountsFormatWithThousandsSeparators() {
        assertEquals("0", formatTokens(0L))
        assertEquals("999", formatTokens(999L))
        assertEquals("1,000", formatTokens(1_000L))
        assertEquals("12,345", formatTokens(12_345L))
        assertEquals("1,234,567", formatTokens(1_234_567L))
    }

    @Test
    fun durationsFormatAcrossScales() {
        assertEquals("0ms", formatDuration(0L))
        assertEquals("850ms", formatDuration(850L))
        assertEquals("12s", formatDuration(12_000L))
        assertEquals("12.3s", formatDuration(12_300L))
        assertEquals("2m05s", formatDuration(125_000L))
        assertEquals("1h01m", formatDuration(3_665_000L))
    }

    // ---------------------------------------------- 守卫：display 的空态措辞

    @Test
    fun notReportedCellAlwaysReadsZanWuShuJu() {
        assertEquals("暂无数据", TokenCell.NotReported.display())
        assertEquals("8", TokenCell.Value("8").display())
    }
}
