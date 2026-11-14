package vade.ui.subagent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import vade.ui.icon.VadeIcons
import vade.ui.skin.SkinColors

/**
 * C-Activity · the sub-agent state badge mapping (icon + label + accent colour).
 *
 * Five contract states → five **distinct** vector icons (docs/visual-language.md §3:
 * badges are ImageVectors, never glyphs; the exhaustiveness of this table is pinned by
 * `SubagentBadgeTest`, which fails the moment two states share an icon or one state is
 * missing). The accent colour drives both the badge tint and the card's §13 left edge.
 *
 * Mapping table (also in shots/c-activity/RESULT.md):
 *
 * | state     | VadeIcons        | Tabler outline | label   | accent token     |
 * |-----------|------------------|----------------|---------|------------------|
 * | SPAWNED   | `Spawned` (new)  | rocket         | 已生成   | `primary`        |
 * | RUNNING   | `Running` (new)  | player-play    | 运行中   | `primary`        |
 * | COMPLETED | `Check`          | check          | 已完成   | `success`        |
 * | FAILED    | `Failed`         | ban            | 失败     | `error`          |
 * | CANCELLED | `Close`          | x              | 已取消   | `onSurfaceVariant`|
 * | UNKNOWN   | `Waiting`        | hourglass      | 未知状态 | `onSurfaceVariant`|
 *
 * [SubagentState.UNKNOWN] is not one of the five contract values — it is the
 * forward-compat bucket and renders neutral (icon + muted ink) without asserting any
 * activity the client cannot know.
 */
object SubagentBadges {

    fun iconFor(state: SubagentState): ImageVector = when (state) {
        SubagentState.SPAWNED -> VadeIcons.Spawned
        SubagentState.RUNNING -> VadeIcons.Running
        SubagentState.COMPLETED -> VadeIcons.Check
        SubagentState.FAILED -> VadeIcons.Failed
        SubagentState.CANCELLED -> VadeIcons.Close
        SubagentState.UNKNOWN -> VadeIcons.Waiting
    }

    /** The visible badge word — five contract states get five distinct, non-blank words. */
    fun labelFor(state: SubagentState): String = when (state) {
        SubagentState.SPAWNED -> "已生成"
        SubagentState.RUNNING -> "运行中"
        SubagentState.COMPLETED -> "已完成"
        SubagentState.FAILED -> "失败"
        SubagentState.CANCELLED -> "已取消"
        SubagentState.UNKNOWN -> "未知状态"
    }

    /**
     * contentDescription of the badge icon (visual language §3.4: informative icons are
     * labelled in Chinese) — kept distinct from [labelFor] by the「状态：」prefix.
     */
    fun descriptionFor(state: SubagentState): String = "子代理状态：${labelFor(state)}"

    /**
     * §13 left-edge + badge tint colour, resolved from public skin tokens. Active states
     * (spawned/running) take the accent; terminal states follow the tool-card rule
     * (§五: 成功 success / 失败 danger); cancelled/unknown stay muted.
     */
    fun accentColorFor(state: SubagentState, colors: SkinColors): Color = when (state) {
        SubagentState.SPAWNED, SubagentState.RUNNING -> colors.primary
        SubagentState.COMPLETED -> colors.success
        SubagentState.FAILED -> colors.error
        SubagentState.CANCELLED, SubagentState.UNKNOWN -> colors.onSurfaceVariant
    }
}
