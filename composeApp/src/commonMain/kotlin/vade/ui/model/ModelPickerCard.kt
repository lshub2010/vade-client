// Model picker — the drawing side.
//
// Composables only; every decision (badges, accent, selectability, empty state, the
// read-only degrade, the failure copy) was made in `ModelOption.kt` / `ModelState.kt` /
// `ModelFailure.kt` and is tested there. Visual recipe is the `GroupCard` one from
// `destinations/GroupCards.kt` — md corners, 1dp hairline, the §8 `shadow0` layered shadow
// via [layeredShadow] — rebuilt here rather than imported so the model package stays
// independent of the destinations package.
//
// Hard lines from the visual language / guards:
//  - vectors only (IconGuardTest scans this package) — no glyph characters anywhere;
//  - corners from the skin scale only (md card 20 / sm rows 16 / xs chips 12 = 3 per screen);
//  - `layeredShadow(VadeShadows.card)` for the static card, never `Modifier.shadow`;
//  - touch targets ≥44dp (header row and every model row are padded past that);
//  - icons carry a contentDescription, or are decorative inside an already-labeled row.
//
// What the card must SHOW (not merely compute): the 「只读」 chip + reason when the core has
// no model surface (FR-8.46), the classified error of a failed pull / write, and why the
// global-default scope is read-only (contract 1.5.0 has no default write method).

package vade.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.LocalSkin

/**
 * The expandable model picker card. Collapsed: header with title + the current model line
 * (always visible, so the reader sees what would answer without expanding) + the 「只读」
 * chip on the FR-8.46 degrade. Expanded: the read-only / 「无可用模型」 states when there is
 * nothing to list, otherwise one [ModelOptionRow] per entry plus — session scope only — the
 * 「跟随全局默认」 row that clears the override.
 *
 * [footerNote] is where the honest provenance line goes (fixture vs real adapter).
 */
@Composable
fun ModelPickerCard(
    state: ModelState,
    title: String,
    modifier: Modifier = Modifier,
    footerNote: String? = null,
) {
    val skin = LocalSkin.current
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(skin.shapes.md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .layeredShadow(VadeShadows.card, shape, dark = skin.colors.isDark)
            .clip(shape)
            .background(skin.colors.surface)
            .border(1.dp, skin.colors.hairline, shape),
    ) {
        // Header toggle — the ≥44dp rule is met by the two text lines plus padding.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VadeIcon(
                VadeIcons.Model,
                contentDescription = null, // decorative: the title text names the row
                size = 20.dp,
                tint = skin.colors.primary,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                UiText(title, size = 13, weight = FontWeight.SemiBold)
                UiText(
                    text = state.currentLabel(),
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // FR-8.46 must be visible WITHOUT expanding: an old core is not "no models".
            if (state.readOnly) {
                ReadOnlyChip()
                Spacer(Modifier.width(8.dp))
            }
            VadeIcon(
                VadeIcons.ChevronDown,
                contentDescription = if (expanded) "收起模型列表" else "展开模型列表",
                size = 20.dp,
                tint = skin.colors.onSurfaceVariant,
            )
        }
        // Honest state above the fold: the degrade reason, a blocked write, a failed pull and
        // a failed write all show here, so nobody has to expand the card to learn the outcome.
        state.readOnlyReason?.let { NoticeLine(it) }
        if (!state.readOnly) state.writeBlockedReason?.let { NoticeLine(it) }
        state.loadFailure?.let { FailureNotice(it) }
        state.writeFailure?.let { FailureNotice(it) }
        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.hairline))
            when (val content = state.content()) {
                is ModelPickerContent.ReadOnly -> PickerEmptyState(content.title, content.body)
                is ModelPickerContent.NoModels -> PickerEmptyState(content.title, content.body)
                is ModelPickerContent.Rows -> PickerRows(state, content)
            }
            footerNote?.let {
                UiText(
                    text = it,
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 「只读」 badge — the core has no model surface (or cannot take model writes). */
@Composable
private fun ReadOnlyChip() {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.xs)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(skin.colors.surfaceVariant)
            .border(1.dp, skin.colors.outline, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        UiText("只读", size = 11, color = skin.colors.onSurfaceVariant, maxLines = 1)
    }
}

/** One explanation line (the degrade reason / why a scope cannot be written). */
@Composable
private fun NoticeLine(text: String) {
    val skin = LocalSkin.current
    UiText(
        text = text,
        size = 11,
        color = skin.colors.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** A failed model RPC: 分类 + headline, then core's own words. Never silent. */
@Composable
private fun FailureNotice(failure: ModelFailure) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        UiText(
            text = "${failure.headline}（${failure.category.label}）",
            size = 12,
            weight = FontWeight.SemiBold,
            color = skin.colors.error,
        )
        UiText(text = failure.body, size = 11, color = skin.colors.onSurfaceVariant)
    }
}

/** The read-only / 「无可用模型」 body — a real state with an explanation, never a bare list. */
@Composable
private fun PickerEmptyState(title: String, body: String) {
    val skin = LocalSkin.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            VadeIcon(
                VadeIcons.Empty,
                contentDescription = null, // decorative; the title below is the label
                size = 24.dp,
                tint = skin.colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        UiText(title, size = 15, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        UiText(body, size = 12, color = skin.colors.onSurfaceVariant)
    }
}

@Composable
private fun PickerRows(state: ModelState, content: ModelPickerContent.Rows) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.scope is ModelScope.Session) {
            FollowDefaultRow(
                selected = state.selectedId == null,
                enabled = content.interactive && state.canClearOverride && !state.writePending,
            ) {
                state.followGlobalDefault()
            }
        }
        content.rows.forEach { row ->
            ModelOptionRow(
                row = row,
                enabled = row.clickable && !state.writePending,
                onClick = { state.select(row.option.id) },
            )
        }
    }
}

/**
 * Session scope only: 「跟随全局默认」 — clearing the explicit override. Selected when no
 * override exists, so exactly one row of the whole picker is highlighted at any time. The
 * clear frame carries an explicit `"model_id": null` (required-with-null since `p-nullkey`),
 * which is what core's missing-key gate needs — so this row is a real, working write.
 */
@Composable
private fun FollowDefaultRow(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val skin = LocalSkin.current
    val style = modelRowStyle(skin.colors, selected, selectable = true)
    val shape = RoundedCornerShape(skin.shapes.sm)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(style.background ?: Color.Transparent)
            .border(1.dp, style.border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                VadeIcon(VadeIcons.Check, contentDescription = "已选中", size = 18.dp, tint = skin.colors.primary)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column {
            UiText(
                text = "跟随全局默认",
                size = 14,
                weight = FontWeight.Normal,
                color = if (!enabled && !selected) skin.colors.onSurfaceVariant else style.titleColor,
            )
            UiText(
                text = "清除本会话覆盖；按 全局默认 > priority 兜底",
                size = 11,
                color = style.metaColor,
            )
        }
    }
}

/** One model row: check slot, title + provider line, the three badges, detail when selected. */
@Composable
private fun ModelOptionRow(row: ModelRow, enabled: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val style = modelRowStyle(skin.colors, row.selected, row.option.selectable)
    val shape = RoundedCornerShape(skin.shapes.sm)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(style.background ?: Color.Transparent)
            .border(1.dp, style.border, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Fixed 24dp slot: every row's title aligns whether or not the check shows.
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (row.selected) {
                    VadeIcon(VadeIcons.Check, contentDescription = "已选中", size = 18.dp, tint = skin.colors.primary)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                UiText(
                    text = row.option.title(),
                    size = 14,
                    weight = if (row.selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = style.titleColor,
                    maxLines = 1,
                )
                UiText(
                    text = row.option.metaLine(),
                    size = 11,
                    color = style.metaColor,
                    maxLines = 1,
                )
            }
            if (!row.option.selectable) {
                OfflineChip()
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.option.badges().forEach { badge -> CapabilityChip(badge) }
        }
        if (row.option.imageLimitSummary != null) {
            Spacer(Modifier.height(4.dp))
            UiText(row.option.imageLimitSummary, size = 11, color = skin.colors.onSurfaceVariant)
        }
        if (row.selected) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.hairline))
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                row.detail.forEach { entry ->
                    Row {
                        UiText(
                            text = entry.label,
                            size = 11,
                            color = skin.colors.onSurfaceVariant,
                            modifier = Modifier.width(92.dp),
                        )
                        UiText(text = entry.value, size = 11, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 三态徽标 chip: 支持 = accent wash, 不支持 = outlined grey, 未声明 = filled grey. */
@Composable
private fun CapabilityChip(badge: ModelBadge) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.xs)
    val background: Color = when (badge.support) {
        CapSupport.Supported -> skin.colors.primary.copy(alpha = 0.08f)
        CapSupport.Unsupported -> Color.Transparent
        CapSupport.Unknown -> skin.colors.surfaceVariant
    }
    val foreground: Color = when (badge.support) {
        CapSupport.Supported -> skin.colors.primary
        else -> skin.colors.onSurfaceVariant
    }
    val border: Color = when (badge.support) {
        CapSupport.Supported -> skin.colors.primary.copy(alpha = 0.30f)
        else -> skin.colors.hairline
    }
    Box(
        modifier = Modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        UiText(badge.text, size = 11, color = foreground, maxLines = 1)
    }
}

/** 离线 marker on rows whose `available` is false — explains why the row refuses clicks. */
@Composable
private fun OfflineChip() {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.xs)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(skin.colors.surfaceVariant)
            .border(1.dp, skin.colors.hairline, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        UiText("离线 · 实例不可用", size = 11, color = skin.colors.onSurfaceVariant, maxLines = 1)
    }
}
