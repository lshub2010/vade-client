package vade.ui.subagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow
import vade.ui.destinations.EmptyStateCard
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.LocalSkin

/**
 * C-Activity · 子代理 panel — one card per `SubagentRunView` snapshot.
 *
 * Every card carries: the state badge (vector icon + label, colour-coded), the contract
 * `duration_ms`, the `result_preview` summary, and the **mandatory desensitization note**
 * [PREVIEW_ONLY_NOTE] — RFC-0012 §5.3 guarantees this surface never receives the full
 * result text (only `result_digest` + `result_chars` + the ≤500-char read-time
 * `result_preview`), so the card must tell the user where the full text actually lives
 * instead of implying it is absent by accident.
 */
@Composable
fun SubagentPanel(
    runs: List<SubagentRunView>?,
    modifier: Modifier = Modifier,
) {
    when {
        // `null` = the data layer has not been wired yet — honest empty, not fake runs.
        runs == null -> EmptyStateCard(
            icon = VadeIcons.Empty,
            title = "暂无数据",
            body = "接入 subagent/list 与 subagent.get 后，这里显示真实的子代理运行记录；" +
                "未接线前不构造任何运行卡片。",
            modifier = modifier,
        )
        runs.isEmpty() -> EmptyStateCard(
            icon = VadeIcons.Spawned,
            title = "当前没有子代理",
            body = "本会话尚未派生任何子代理运行；出现后将按状态显示徽标、耗时与结果预览。",
            modifier = modifier,
        )
        else -> Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (run in runs) {
                SubagentCard(run)
            }
        }
    }
}

@Composable
fun SubagentCard(run: SubagentRunView, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    val colors = skin.colors
    val accent = SubagentBadges.accentColorFor(run.state, colors)
    val shape = RoundedCornerShape(skin.shapes.md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .layeredShadow(VadeShadows.card, shape, dark = colors.isDark)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.hairline, shape)
            .accentEdge(accent, skin.shapes.md, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- badge row -----------------------------------------------------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            VadeIcon(
                SubagentBadges.iconFor(run.state),
                contentDescription = SubagentBadges.descriptionFor(run.state),
                size = 20.dp,
                tint = accent,
            )
            Spacer(Modifier.width(8.dp))
            UiText(
                run.label.ifBlank { "未命名子代理" },
                size = 15,
                weight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            UiText(
                SubagentBadges.labelFor(run.state),
                size = 12,
                weight = FontWeight.SemiBold,
                color = accent,
            )
        }
        UiText(durationText(run), size = 12, color = colors.onSurfaceVariant)

        // ---- result preview (read-time projection, ≤500 chars) --------------------
        val preview = previewCell(run.resultPreview, run.resultChars)
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(skin.shapes.xs))
                .background(colors.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            UiText(
                preview.display(),
                size = 13,
                color = if (preview is PreviewCell.Text) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The desensitization note is ALWAYS shown — this card never has the full text.
        Row(verticalAlignment = Alignment.CenterVertically) {
            VadeIcon(
                VadeIcons.Waiting,
                contentDescription = "结果说明",
                size = 14.dp,
                tint = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            UiText(PREVIEW_ONLY_NOTE, size = 11, color = colors.onSurfaceVariant)
        }

        // ---- meta: fingerprint / char count / error code --------------------------
        MetaRow("结果指纹", digestText(run.resultDigest))
        MetaRow("全文长度", charsCell(run.resultChars))
        errorCell(run)?.let { UiText(it, size = 12, color = colors.error) }
        run.childSessionId?.let { MetaRow("子会话", it) }
        UiText(run.runId, size = 11, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    val colors = LocalSkin.current.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        UiText(label, size = 12, color = colors.onSurfaceVariant, modifier = Modifier.width(64.dp))
        UiText(value, size = 12, color = colors.onSurfaceVariant)
    }
}
