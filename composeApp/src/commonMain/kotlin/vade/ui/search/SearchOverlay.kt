package vade.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import vade.ui.UiText
import vade.ui.shell.TopBar
import vade.ui.shell.TopBarButton
import vade.ui.shell.UiState
import vade.ui.skin.BtnStyle
import vade.ui.skin.LocalSkin

/**
 * U1-c — session search, two shapes over one state object ([SearchState]):
 *
 *  - `<600dp`: a **full-screen overlay** pushed over the list, with a back affordance in the
 *    top bar, the input, and the result list (task book §T5 会话·搜索; FR-8.27 "搜索是列表内
 *    的覆盖层，不是一级 tab").
 *  - `≥600dp`: a **command palette** floating over the shell (`⌘K` / `Ctrl+K`), arrow-key
 *    navigation, `Enter` to open, `Esc` to close (FR-8.74).
 *
 * Why a local index: C-05 (`schema/rpc.json`, frozen 2026-11-13) ships no `session/…` at all,
 * so there is no `session/search` to call. [LocalSessionIndex] scans the sessions this client
 * already holds and every state of this overlay prints 「范围：已缓存」 — a degraded search
 * that hides its scope is the defect FR-8.27 calls out. The seam for the real method is
 * [SessionSearchSource] (see the TODO there).
 *
 * C-09 discipline: only `Skin.TextField` / `Skin.Card` / `Skin.Button` plus layout
 * containers and `UiText`. No new Skin member, no `material3`, no skin-private token.
 */
@Composable
fun SearchHost(state: UiState) {
    val search = state.search
    if (!search.open) return

    val sessions = state.cachedSessions()

    // FR-8.27: debounce ≤200 ms. Keyed on the query and on the cached corpus, so a new
    // draft row also refreshes the results.
    LaunchedEffect(search.query, sessions) {
        when (search.phase) {
            SearchPhase.Ready -> {
                delay(SEARCH_DEBOUNCE_MS)
                search.applyResults(LocalSessionIndex(sessions).search(search.query))
            }
            else -> search.clearResults(sessions.size)
        }
    }

    val onPick: (SearchHit) -> Unit = { state.openFromSearch(it) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < 600.dp) {
            CompactSearchOverlay(state = state, onPick = onPick)
        } else {
            CommandPalette(state = state, onPick = onPick)
        }
    }
}

// --- phone: full-screen overlay ------------------------------------------------

@Composable
private fun CompactSearchOverlay(state: UiState, onPick: (SearchHit) -> Unit) {
    val skin = LocalSkin.current
    val search = state.search
    Column(
        Modifier
            .fillMaxSize()
            .background(skin.colors.surface)
            // The overlay is the only interactive surface while open: taps on empty space
            // must not fall through to the list / bottom bar underneath.
            .swallowTaps(),
    ) {
        TopBar(
            title = "搜索会话",
            subtitle = "列表内覆盖层 · 不占一级目的地",
            leading = { TopBarButton("←") { state.closeSearch() } },
            actions = {
                if (search.query.isNotEmpty()) {
                    TopBarButton("清空") { search.updateQuery("") }
                }
            },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            SearchInput(search)
            Spacer(Modifier.height(8.dp))
            ScopeLine(search)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.outline.copy(alpha = 0.30f)))
        SearchBody(search = search, onPick = onPick, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

// --- desktop: ⌘K / Ctrl+K command palette --------------------------------------

@Composable
private fun CommandPalette(state: UiState, onPick: (SearchHit) -> Unit) {
    val skin = LocalSkin.current
    val search = state.search
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(skin.colors.onSurface.copy(alpha = 0.30f))
            .pointerInput(Unit) { detectTapGestures { state.closeSearch() } },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 76.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            skin.Card(Modifier.widthIn(max = 680.dp).fillMaxWidth().swallowTaps()) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UiText("命令面板", size = 13, weight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        UiText("会话搜索", size = 10, color = skin.colors.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        skin.Button("Esc 关闭", onClick = { state.closeSearch() }, style = BtnStyle.Text)
                    }
                    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                        SearchInput(search)
                        Spacer(Modifier.height(8.dp))
                        ScopeLine(search)
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.outline.copy(alpha = 0.30f)))
                    SearchBody(
                        search = search,
                        onPick = onPick,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.outline.copy(alpha = 0.30f)))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UiText("↑ ↓ 选择 · Enter 进入 · Esc 关闭 · ⌘K / Ctrl+K 再按一次也关", size = 10, color = skin.colors.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        if (search.phase == SearchPhase.Ready && search.executed) {
                            UiText("${search.results.size} 个结果", size = 10, color = skin.colors.primary)
                        }
                    }
                }
            }
        }
    }
}

// --- shared pieces --------------------------------------------------------------

@Composable
private fun SearchInput(search: SearchState) {
    val skin = LocalSkin.current
    Column(Modifier.fillMaxWidth()) {
        skin.TextField(
            value = search.query,
            onValueChange = { search.updateQuery(it) },
            placeholder = "搜索会话标题或摘要（至少 $SEARCH_MIN_CHARS 个字符）",
            error = if (search.tooLong) {
                "已输入 ${search.query.length} 字，上限 $SEARCH_MAX_CHARS 字 — 缩短后再搜"
            } else {
                null
            },
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            UiText(
                text = "${search.query.length}/$SEARCH_MAX_CHARS",
                size = 9,
                color = if (search.tooLong) skin.colors.error else skin.colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * The permanent degradation label. It is **not** conditional: FR-8.27 requires the scope to
 * be visible on every state (empty input, no hits, too many characters), which is what stops
 * "cached only" from looking like "searched everything".
 */
@Composable
private fun ScopeLine(search: SearchState) {
    val skin = LocalSkin.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(skin.shapes.pill))
                .background(skin.colors.primary.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            UiText(SearchScope.Cached.label, size = 9, weight = FontWeight.SemiBold, color = skin.colors.primary)
        }
        UiText(
            text = buildString {
                append("本机已缓存 ${search.indexedCount} 个会话的标题与摘要")
                append(" · core 未提供 session/search，跨会话全文检索暂不可用")
            },
            size = 9,
            color = skin.colors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SearchBody(
    search: SearchState,
    onPick: (SearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    // Restored from the state object, so closing the overlay (entering a session) and
    // reopening it lands on the same scroll offset — acceptance criterion U1-c.
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = search.scrollIndex,
        initialFirstVisibleItemScrollOffset = search.scrollOffset,
    )
    DisposableEffect(Unit) {
        onDispose { search.saveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    LaunchedEffect(search.selectionTick) {
        if (search.selectionTick > 0 && search.results.isNotEmpty()) {
            runCatching {
                listState.animateScrollToItem(search.selected.coerceIn(0, search.results.lastIndex))
            }
        }
    }

    Box(modifier) {
        when (search.phase) {
            SearchPhase.Empty -> SearchNotice(
                title = "输入关键词搜索会话",
                body = "至少 $SEARCH_MIN_CHARS 个字符触发；范围见上方标注——只有本机已缓存的会话可被搜到。",
            )

            SearchPhase.TooShort -> SearchNotice(
                title = "还需 ${search.missingChars} 个字符",
                body = "输入满 $SEARCH_MIN_CHARS 个字符才会开始搜索（防抖 ≤${SEARCH_DEBOUNCE_MS}ms）。",
            )

            SearchPhase.TooLong -> SearchNotice(
                title = "搜索词过长",
                body = "上限 $SEARCH_MAX_CHARS 字，当前 ${search.query.length} 字。缩短输入即可继续。",
            )

            SearchPhase.Ready -> {
                if (search.results.isEmpty()) {
                    SearchNotice(
                        title = "没有匹配「${search.trimmedQuery}」的会话",
                        body = "范围：已缓存 · 已检索 ${search.indexedCount} 个会话的标题与摘要。" +
                            "未命中的会话可能不在本机缓存里，或只出现在消息正文中（需 session/search）。",
                    )
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(search.results, key = { _, hit -> hit.session.id }) { index, hit ->
                            ResultRow(
                                hit = hit,
                                selected = index == search.selected,
                                onClick = { onPick(hit) },
                            )
                        }
                        item(key = "tail") { Spacer(Modifier.height(10.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchNotice(title: String, body: String) {
    val skin = LocalSkin.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        UiText(title, size = 13, weight = FontWeight.SemiBold)
        UiText(body, size = 11, color = skin.colors.onSurfaceVariant)
    }
}

/**
 * One hit row.
 *
 * `Skin.ListItem` takes `title: String`, so it cannot carry the highlighted title — and
 * C-09 is frozen (no `AnnotatedString` overload without an RFC). The row is therefore
 * assembled from layout + `UiText(AnnotatedString)`, exactly like `shell/TopBar.kt`
 * assembles the top bar from `Card` + `Button`: composition of frozen primitives, no
 * twelfth component, no skin-private token.
 */
@Composable
private fun ResultRow(hit: SearchHit, selected: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val accent = skin.colors.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(skin.shapes.small))
            .background(if (selected) accent.copy(alpha = 0.10f) else Color.Transparent)
            .pointerInput(hit.session.id) { detectTapGestures { onClick() } }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(skin.shapes.small))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            UiText(hit.session.title.take(1), size = 12, weight = FontWeight.SemiBold, color = accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            UiText(
                text = highlight(hit.session.title, hit.titleRanges, accent),
                size = 14,
                weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                // 标题恒用 onSurface：选中态的高亮色（accent）若同时用于整行标题，
                // 会与命中片段的 accent 高亮同色，等于看不见高亮。选中态靠
                // 行底 tint + 字重 + 左侧头像底色区分，已经足够。
                color = skin.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hit.session.preview.isNotEmpty()) {
                UiText(
                    text = highlight(hit.session.preview, hit.snippetRanges, accent),
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            UiText(hit.session.updatedLabel, size = 9, color = skin.colors.onSurfaceVariant)
            UiText(
                text = if (hit.titleMatched) hit.session.bucket.label else "摘要命中",
                size = 9,
                color = if (hit.titleMatched) accent else skin.colors.onSurfaceVariant,
            )
        }
    }
}

/** Highlight only the matched substrings; everything else keeps the caller's colour. */
private fun highlight(text: String, ranges: List<IntRange>, accent: Color): AnnotatedString =
    buildAnnotatedString {
        append(text)
        ranges.forEach { range ->
            val start = range.first.coerceIn(0, text.length)
            val end = (range.last + 1).coerceIn(start, text.length)
            if (end > start) {
                addStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold), start, end)
            }
        }
    }

/**
 * Swallow pointer events that reach this node. Children are hit first (Main pass runs
 * inside-out), so buttons and rows still work; whatever they leave unconsumed dies here
 * instead of reaching the list / bottom bar behind an overlay.
 */
private fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        }
    }
}
