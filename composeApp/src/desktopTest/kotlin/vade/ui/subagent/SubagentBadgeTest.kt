package vade.ui.subagent

import androidx.compose.ui.graphics.Color
import vade.ui.icon.VadeIcons
import vade.ui.skin.SkinColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * C-Activity · the five sub-agent states MUST map to five DISTINCT vector icons — the
 * exhaustiveness here is the anti-fake guard: adding a sixth contract state without a
 * badge entry, or two states sharing an icon, turns this red.
 */
class SubagentBadgeTest {

    /** Distinct sentinel per token (same discipline as BudgetLevelTest). */
    private val colors = SkinColors(
        primary = Color(0xFF010001),
        onPrimary = Color(0xFF010002),
        surface = Color(0xFF010003),
        surfaceVariant = Color(0xFF010004),
        onSurface = Color(0xFF010005),
        onSurfaceVariant = Color(0xFF010006),
        outline = Color(0xFF010007),
        error = Color(0xFF010008),
        onError = Color(0xFF010009),
        warning = Color(0xFF01000A),
        success = Color(0xFF01000B),
    )

    // ---------------------------------------------- ① 五状态 → 五个不同矢量图标

    @Test
    fun fiveStatesMapToFiveDistinctIcons_exhaustive() {
        // Per-state pins — a refactor that silently reuses another icon fails here first.
        assertEquals(VadeIcons.Spawned, SubagentBadges.iconFor(SubagentState.SPAWNED), "spawned → rocket")
        assertEquals(VadeIcons.Running, SubagentBadges.iconFor(SubagentState.RUNNING), "running → player-play")
        assertEquals(VadeIcons.Check, SubagentBadges.iconFor(SubagentState.COMPLETED), "completed → check")
        assertEquals(VadeIcons.Failed, SubagentBadges.iconFor(SubagentState.FAILED), "failed → ban")
        assertEquals(VadeIcons.Close, SubagentBadges.iconFor(SubagentState.CANCELLED), "cancelled → x")

        val five = listOf(
            SubagentState.SPAWNED, SubagentState.RUNNING, SubagentState.COMPLETED,
            SubagentState.FAILED, SubagentState.CANCELLED,
        )
        // Walk ALL values the enum declares: the five contract states must each get an
        // icon, and pairwise-distinct at that (Set size == 5).
        val icons = five.map(SubagentBadges::iconFor)
        assertEquals(5, icons.toSet().size, "五个状态共享了图标：${icons.map { it.name }}")
        // UNKNOWN is the forward-compat bucket, not one of the five — it must exist and
        // render the neutral hourglass, never one of the five terminal/active glyphs.
        assertEquals(VadeIcons.Waiting, SubagentBadges.iconFor(SubagentState.UNKNOWN))
    }

    @Test
    fun unknownRawStateStaysNeutralInsteadOfPretendingToRun() {
        assertEquals(SubagentState.UNKNOWN, SubagentState.fromRaw("alien"), "未知原始值不得映射成 running")
        assertEquals(SubagentState.UNKNOWN, SubagentState.fromRaw(null))
        assertEquals(SubagentState.UNKNOWN, SubagentState.fromRaw("SPAWNED"), "大小写敏感：契约只有小写")
        // And the neutral state must not collide with the running badge.
        assertNotEquals(SubagentBadges.iconFor(SubagentState.UNKNOWN), SubagentBadges.iconFor(SubagentState.RUNNING))
    }

    @Test
    fun fiveStatesMapToFiveDistinctRoundTripRawValues() {
        // fromRaw ∘ raw must be the identity on all five contract values.
        for (state in listOf(
            SubagentState.SPAWNED, SubagentState.RUNNING, SubagentState.COMPLETED,
            SubagentState.FAILED, SubagentState.CANCELLED,
        )) {
            val raw = when (state) {
                SubagentState.SPAWNED -> "spawned"
                SubagentState.RUNNING -> "running"
                SubagentState.COMPLETED -> "completed"
                SubagentState.FAILED -> "failed"
                SubagentState.CANCELLED -> "cancelled"
                SubagentState.UNKNOWN -> error("not a contract value")
            }
            assertEquals(state, SubagentState.fromRaw(raw), "raw=$raw")
        }
    }

    // ---------------------------------------------- 徽标文案与无障碍

    @Test
    fun labelsAreDistinctAndDescriptionsCarryTheChineseWord() {
        val five = listOf(
            SubagentState.SPAWNED, SubagentState.RUNNING, SubagentState.COMPLETED,
            SubagentState.FAILED, SubagentState.CANCELLED,
        )
        val labels = five.map(SubagentBadges::labelFor)
        assertEquals(5, labels.toSet().size, "五个状态的徽标词不得重复：$labels")
        assertTrue(labels.all { it.isNotBlank() })
        for (state in five) {
            assertEquals("子代理状态：${SubagentBadges.labelFor(state)}", SubagentBadges.descriptionFor(state))
            assertTrue(SubagentBadges.descriptionFor(state).isNotBlank(), "contentDescription 不得为空（§3.4）")
        }
    }

    // ---------------------------------------------- ⑦ 左侧强调色竖条随状态变色

    @Test
    fun accentEdgeColorFollowsStateTokens() {
        assertEquals(colors.primary, SubagentBadges.accentColorFor(SubagentState.SPAWNED, colors))
        assertEquals(colors.primary, SubagentBadges.accentColorFor(SubagentState.RUNNING, colors))
        assertEquals(colors.success, SubagentBadges.accentColorFor(SubagentState.COMPLETED, colors))
        assertEquals(colors.error, SubagentBadges.accentColorFor(SubagentState.FAILED, colors))
        assertEquals(colors.onSurfaceVariant, SubagentBadges.accentColorFor(SubagentState.CANCELLED, colors))
        assertEquals(colors.onSurfaceVariant, SubagentBadges.accentColorFor(SubagentState.UNKNOWN, colors))
        // The §13 edge must actually CHANGE with the state — terminal states differ.
        assertNotEquals(
            SubagentBadges.accentColorFor(SubagentState.COMPLETED, colors),
            SubagentBadges.accentColorFor(SubagentState.FAILED, colors),
        )
        assertNotEquals(
            SubagentBadges.accentColorFor(SubagentState.RUNNING, colors),
            SubagentBadges.accentColorFor(SubagentState.CANCELLED, colors),
        )
    }
}
