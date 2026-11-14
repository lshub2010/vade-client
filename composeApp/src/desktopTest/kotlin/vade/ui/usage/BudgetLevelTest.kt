package vade.ui.usage

import androidx.compose.ui.graphics.Color
import vade.ui.protocol.BudgetState
import vade.ui.protocol.BudgetUsage
import vade.ui.protocol.UsageBudgetPayload
import vade.ui.skin.SkinColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C-Activity · budget bar decisions: the 79% / 80% / 100% / 超限 tier table, contract
 * state precedence, the skin-token colour mapping, `enforce=block` surfacing and the
 * `usage.budget` notification banner.
 */
class BudgetLevelTest {

    /** Every status token a distinct sentinel — the mapping must name the RIGHT token. */
    private val colors = SkinColors(
        primary = Color(0xFF000001),
        onPrimary = Color(0xFF000002),
        surface = Color(0xFF000003),
        surfaceVariant = Color(0xFF000004),
        onSurface = Color(0xFF000005),
        onSurfaceVariant = Color(0xFF000006),
        outline = Color(0xFF000007),
        error = Color(0xFF000008),
        onError = Color(0xFF000009),
        warning = Color(0xFF00000A),
        success = Color(0xFF00000B),
    )

    // ---------------------------------------------- ② 79% / 80% / 100% / 超限 四档

    @Test
    fun fourTiersAt79_80_100_andOverLimit() {
        // ratio path (contract `budget.ratio`)
        assertEquals(BudgetLevel.NORMAL, budgetLevel(null, 790, 1_000, 0.79))
        assertEquals(BudgetLevel.WARNING, budgetLevel(null, 800, 1_000, 0.80))
        // exact used==limit is CRITICAL even if a rounding hair makes the ratio 0.9999…
        assertEquals(BudgetLevel.CRITICAL, budgetLevel(null, 1_000, 1_000, 0.999999))
        assertEquals(BudgetLevel.EXCEEDED, budgetLevel(null, 1_500, 1_000, 1.5))
        // ratio-null fallback computes from used/limit
        assertEquals(BudgetLevel.NORMAL, budgetLevel(null, 790, 1_000, null))
        assertEquals(BudgetLevel.WARNING, budgetLevel(null, 800, 1_000, null))
    }

    @Test
    fun contractStateWinsOverLocalThresholds() {
        // The server's budget.state is authoritative (rpc.json notes): warn at 50% stays WARNING.
        assertEquals(BudgetLevel.WARNING, budgetLevel(BudgetState.WARN, 500, 1_000, 0.5))
        assertEquals(BudgetLevel.EXCEEDED, budgetLevel(BudgetState.EXCEEDED, 500, 1_000, 0.5))
        // ok stays level-driven (below the 80% UI line).
        assertEquals(BudgetLevel.NORMAL, budgetLevel(BudgetState.OK, 500, 1_000, 0.5))
    }

    @Test
    fun offOrZeroLimitMeansNoBarAtAll() {
        assertEquals(BudgetLevel.NONE, budgetLevel(BudgetState.OFF, 0, 0, null))
        assertEquals(BudgetLevel.NONE, budgetLevel(null, 100, 0, 1.0), "limit=0 无预算，不画条")
        assertNull(budgetFraction(100, 0, null))
        assertEquals(BudgetLevel.NONE, budgetLevel(BudgetState.UNKNOWN, 0, 0, null))
    }

    // ---------------------------------------------- ② 四档颜色

    @Test
    fun tierColorsResolveFromTheRightSkinTokens() {
        assertEquals(colors.primary, budgetColor(BudgetLevel.NORMAL, colors), "<80% 用强调色")
        assertEquals(colors.warning, budgetColor(BudgetLevel.WARNING, colors), "≥80% 用警告色")
        assertEquals(colors.error, budgetColor(BudgetLevel.CRITICAL, colors), "达上限用危险色")
        assertEquals(colors.error, budgetColor(BudgetLevel.EXCEEDED, colors), "超限用危险色")
        assertEquals(colors.onSurfaceVariant, budgetColor(BudgetLevel.NONE, colors))
        // Guard the sentinel itself: the four tiers really exercise three distinct hues
        // (CRITICAL/EXCEEDED deliberately share danger — the visual language bans a
        // fourth invented status colour; the tier is told by the「已超限」label).
        assertNotEquals(colors.primary, colors.warning)
        assertNotEquals(colors.warning, colors.error)
    }

    @Test
    fun fractionClampsIntoTheTrack() {
        assertEquals(0.79f, budgetFraction(790, 1_000, 0.79)!!, 1e-5f)
        assertEquals(1f, budgetFraction(1_500, 1_000, 1.5)!!, 1e-5f, "超限时条画满，不溢出轨道")
        assertEquals(0f, budgetFraction(0, 1_000, 0.0)!!, 1e-5f)
        assertEquals(0.5f, budgetFraction(500, 1_000, null)!!, 1e-5f, "ratio 缺失时由 used/limit 计算")
    }

    // ---------------------------------------------- enforce=block 的诚实呈现

    @Test
    fun enforcedBlockSemanticsSurfaceVerbatim() {
        val enforced = projectBudget(budget(enforced = true))!!
        val blocked = assertIs<TokenCell.Value>(enforced.enforced)
        assertTrue(blocked.text.contains("阻断"), blocked.text)
        assertTrue(blocked.text.contains("enforce=block"), blocked.text)

        val warnOnly = projectBudget(budget(enforced = false))!!
        val soft = assertIs<TokenCell.Value>(warnOnly.enforced)
        assertTrue(soft.text.contains("不阻断") || soft.text.contains("仅警告"), soft.text)

        val unknown = projectBudget(budget(enforced = null))!!
        assertIs<TokenCell.NotReported>(unknown.enforced)
        assertEquals("暂无数据", unknown.enforced.display())
    }

    @Test
    fun usedLimitPercentStateCarryRealNumbers() {
        val rows = projectBudget(
            BudgetUsage(
                scope = "session", limit = 1_000, used = 790,
                ratio = 0.79, state = "ok", enforced = true,
            ),
        )!!
        assertEquals(BudgetLevel.NORMAL, rows.level)
        assertEquals("790", (rows.used as TokenCell.Value).text)
        assertEquals("1,000", (rows.limit as TokenCell.Value).text)
        assertEquals("79%", (rows.percent as TokenCell.Value).text)
        assertEquals("ok", (rows.state as TokenCell.Value).text)
        assertEquals(0.79f, rows.fraction!!, 1e-5f)
    }

    @Test
    fun percentFallsBackToUsedOverLimitWhenRatioMissing() {
        val rows = projectBudget(
            BudgetUsage(scope = "session", limit = 1_000, used = 623, ratio = null, state = "ok", enforced = null),
        )!!
        assertEquals("62%", (rows.percent as TokenCell.Value).text)
        // limit=0 → 无百分比（不除零），显示暂无数据；但 state="off" 是真实数据，
        // 所以预算块并不整体为空 —— 卡片会显示「状态 off」的诚实说明。
        val off = projectBudget(
            BudgetUsage(scope = "session", limit = 0, used = 0, ratio = null, state = "off", enforced = null),
        )!!
        assertIs<TokenCell.NotReported>(off.percent)
        assertEquals(BudgetLevel.NONE, off.level)
        assertEquals("off", (off.state as TokenCell.Value).text)
    }

    // ---------------------------------------------- usage.budget 事件横幅

    @Test
    fun budgetNoticeWarnIsWarnColoured() {
        val notice = budgetNotice(
            UsageBudgetPayload("session", "warn", used = 17_000, limit = 20_000, ratio = 0.85, degraded = false, sessionId = "s1"),
        )!!
        assertFalse(notice.danger)
        assertTrue(notice.text.contains("警告线"), notice.text)
        assertTrue(notice.text.contains("17,000"), notice.text)
        assertTrue(notice.text.contains("session/usage.get"), "必须说明通知不是权威真相: ${notice.text}")
    }

    @Test
    fun budgetNoticeExceededSaysBlockRefusal() {
        val notice = budgetNotice(
            UsageBudgetPayload("session", "exceeded", used = 21_000, limit = 20_000, ratio = 1.05, degraded = false, sessionId = "s1"),
        )!!
        assertTrue(notice.danger)
        assertTrue(notice.text.contains("BUDGET_EXCEEDED"), notice.text)
    }

    @Test
    fun budgetNoticeDegradedExplainsTheDowngradedRefusal() {
        // events.json §usage.budget: degraded=true means enforce=block WOULD have refused
        // but the ledger is untrustworthy (source=none) — the UI must say exactly that.
        val notice = budgetNotice(
            UsageBudgetPayload("session", "exceeded", used = 21_000, limit = 20_000, ratio = 1.05, degraded = true, sessionId = null),
        )!!
        assertTrue(notice.danger)
        assertTrue(notice.text.contains("账本不可信"), notice.text)
        assertTrue(notice.text.contains("降级为警告"), notice.text)
        assertTrue(notice.text.contains("source=none"), notice.text)
    }

    @Test
    fun budgetNoticeOkOffNeverNotifyAndUnknownShowsVerbatim() {
        assertNull(budgetNotice(UsageBudgetPayload("session", "ok", 1, 10, 0.1, false, "s1")))
        assertNull(budgetNotice(UsageBudgetPayload("session", "off", 1, 0, 0.0, false, "s1")))
        val odd = budgetNotice(UsageBudgetPayload("session", "tilt", 1, 10, 0.1, false, "s1"))!!
        assertTrue(odd.text.contains("tilt"), "未知状态原文呈现，不编造语义: ${odd.text}")
        assertFalse(odd.danger)
    }

    // ---------------------------------------------- projectUsage 的 notice 通路

    @Test
    fun projectUsageSurfacesTheNoticeAndTheAuthoritativeBudget() {
        val session = budgetSession(state = "ok", limit = 20_000, used = 10_000, ratio = 0.5, enforced = true)
        val noticePayload = UsageBudgetPayload("session", "warn", 17_000, 20_000, 0.85, false, "s1")
        val projection = projectUsage(session, null, noticePayload)
        assertTrue(projection.hasAnyData)
        assertEquals(BudgetLevel.NORMAL, projection.budget?.level, "权威 budget.state=ok, 50% → NORMAL")
        val banner = projection.notice!!
        assertTrue(banner.text.contains("警告线"))
        assertFalse(banner.danger)
    }

    // ---- fixtures ---------------------------------------------------------------

    private fun budget(enforced: Boolean?): BudgetUsage =
        BudgetUsage(scope = "session", limit = 1_000, used = 500, ratio = 0.5, state = "ok", enforced = enforced)

    private fun budgetSession(state: String, limit: Long, used: Long, ratio: Double, enforced: Boolean) =
        vade.ui.protocol.SessionUsageGetResult(
            sessionId = "s1",
            chat = null,
            aux = null,
            totalTokens = null,
            byModel = emptyList(),
            day = null,
            budget = BudgetUsage("session", limit, used, ratio, state, enforced),
        )
}
