package vade.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import vade.ui.UiText
import vade.ui.shell.TopBarButton
import vade.ui.shell.UiState
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.LocalSkin
import vade.ui.skin.glass.GlassSurface

/**
 * U1-c — session search, two shapes over one state object ([SearchState]):
 *
 *  - `<600dp`: a **glass overlay card** floating over the list (12dp side margins, `xl`
 *    corners), back affordance + input + result list inside (task book §T5 会话·搜索;
 *    FR-8.27 "搜索是列表内的覆盖层，不是一级 tab").
 *  - `≥600dp`: the **command palette** floating over the shell (`⌘K` / `Ctrl+K`), arrow-key
 *    navigation, `Enter` to open, `Esc` to close (FR-8.74).
 *
 * Both shapes are v2 glass (visual language §12「搜索浮层」row): [GlassSurface] (real haze
 * blur + §5 tint + hairline + float shadow), `xl` corners, result rows `md`, and matched
 * fragments in bold `accent` **with no background fill** — the old row-tint selection is
 * replaced by a 3dp accent bar, the same selected language as the session list. The cards
 * sit outside the haze backdrop source (sibling of `AppShell`'s content), so haze never
 * captures its own layer.
 *
 * Why a local index: C-05 (`schema/rpc.json`, frozen 2026-11-13) ships no `session/…` at all,
 * so there is no `session/search` to call. [LocalSessionIndex] scans the sessions this client
 * already holds and every state of this overlay prints 「范围：已缓存」 — a degraded search
 * that hides its scope is the defect FR-8.27 calls out. The seam for the real method is
 * [SessionSearchSource] (see the TODO there).
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

// --- phone: glass overlay card --------------------------------------------------

@Composable
private fun CompactSearchOverlay(state: UiState, onPick: (SearchHit) -> Unit) {
    val skin = LocalSkin.current
    val search = state.search
    Box(
        Modifier
            .fillMaxSize()
            // §9 scrim: rgba(0,0,0,.24) — the session list behind stays 隐约可见. Blur on
            // the scrim itself would be a fourth glass layer (§5 caps 3), so it stays dim-only.
            .background(skin.colors.onSurface.copy(alpha = 0.24f))
            // Scrim tap closes (§9); the card swallows its own leftover taps below.
            .pointerInput(Unit) { detectTapGestures { state.closeSearch() } },
    ) {
        // 12dp side/bottom insets (§9 gap discipline); top clears the floating shell top bar.
        Box(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 80.dp, bottom = 12.dp)) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                shape = RoundedCornerShape(skin.shapes.xl),
                skin = skin,
            ) {
                Column(Modifier.fillMaxSize().swallowTaps()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TopBarButton("←", onClick = { state.closeSearch() }, contentDescription = "关闭搜索")
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            UiText("搜索会话", size = 15, weight = FontWeight.SemiBold)
                            UiText("列表内覆盖层 · 不占一级目的地", size = 10, color = skin.colors.onSurfaceVariant)
                        }
                        if (search.query.isNotEmpty()) {
                            TopBarButton("✕", onClick = { search.updateQuery("") }, contentDescription = "清空输入")
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        SearchInput(search)
                        Spacer(Modifier.height(8.dp))
                        ScopeLine(search)
                    }
                    SearchBody(search = search, onPick = onPick, modifier = Modifier.fillMaxWidth().weight(1f))
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
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
            .background(skin.colors.onSurface.copy(alpha = 0.24f))
            .pointerInput(Unit) { detectTapGestures { state.closeSearch() } },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(start = 24.dp, end = 24.dp, top = 84.dp)
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .swallowTaps(),
        ) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(skin.shapes.xl),
                skin = skin,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            UiText("命令面板", size = 15, weight = FontWeight.SemiBold)
                            UiText("会话搜索", size = 10, color = skin.colors.onSurfaceVariant)
                        }
                        TopBarButton("✕", onClick = { state.closeSearch() }, contentDescription = "关闭面板")
                    }
                    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                        SearchInput(search)
                        Spacer(Modifier.height(8.dp))
                        ScopeLine(search)
                    }
                    SearchBody(
                        search = search,
                        onPick = onPick,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UiText(
                            "↑ ↓ 选择 · Enter 进入 · Esc 关闭 · ⌘K / Ctrl+K 再按一次也关",
                            size = 10,
                            color = skin.colors.onSurfaceVariant,
                        )
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
            UiText(search.scope.label, size = 9, weight = FontWeight.SemiBold, color = skin.colors.primary)
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

/**
 * v2 §12 空态 spec: centered, 48dp icon, 20sp title, 14sp muted body — used for every
 * non-results phase of the search card.
 */
@Composable
private fun SearchNotice(title: String, body: String) {
    val skin = LocalSkin.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            VadeIcon(VadeIcons.Search, contentDescription = null, size = 26.dp, tint = skin.colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        UiText(
            text = title,
            style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            color = skin.colors.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        UiText(
            text = body,
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center),
            color = skin.colors.onSurfaceVariant,
        )
    }
}

/**
 * One hit row — v2 form: `md` corners, **no background tint** (the old selected-row tint is
 * replaced by a 3dp accent bar, matching the session list's selected language), matched
 * fragments bold `accent` without any background fill.
 *
 * `Skin.ListItem` takes `title: String`, so it cannot carry the highlighted title — and
 * C-09 is frozen (no `AnnotatedString` overload without an RFC). The row is therefore
 * assembled from layout + `UiText(AnnotatedString)`, exactly like `shell/TopBar.kt`
 * assembles the top bar: composition of frozen primitives, no twelfth component.
 */
@Composable
private fun ResultRow(hit: SearchHit, selected: Boolean, onClick: () -> Unit) {
    val skin = LocalSkin.current
    val accent = skin.colors.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(skin.shapes.md))
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选中态：3dp accent 条（与会话列表同一语言），没有行底色块。
        Box(Modifier.width(3.dp).height(40.dp).background(if (selected) accent else Color.Transparent))
        Spacer(Modifier.width(9.dp))
        // 头像：hairline 描边 + accent 字母——玻璃上不再垫 accent 底色。
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(skin.shapes.sm))
                .border(1.dp, skin.colors.hairline, RoundedCornerShape(skin.shapes.sm)),
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
                // 标题恒用 onSurface：命中片段的 accent 高亮不能被标题同色淹没；
                // 选中态靠 3dp 条 + 字重区分，不加行底色。
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
                // v2 §12：命中片段 = accent 加粗，**不加底色**。
                addStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold), start, end)
            }
        }
    }

/**
 * Swallow pointer events that reach this node. Children are hit first (Main pass runs
 * inside-out), so buttons and rows still work; whatever they leave unconsumed dies here
 * instead of closing the card through the scrim behind it.
 */
private fun Modifier.swallowTaps(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        }
    }
}
