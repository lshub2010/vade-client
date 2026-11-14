package vade.ui.subagent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import vade.ui.UiText
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow
import vade.ui.data.VadeErrorCategory
import vade.ui.data.loadErrorCategoryOf
import vade.ui.data.loadErrorCodeOf
import vade.ui.destinations.EmptyStateCard
import vade.ui.destinations.GroupCard
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.protocol.SubagentListParams
import vade.ui.protocol.SubagentRunView as ProtocolRunView
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
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
    /**
     * G · which slice the list covers — only the empty copy uses it: `true` = 本会话,
     * `false` = 全部会话 (`subagent/list` 省略 session_id)。文案必须跟着范围走，
     * 否则「本会话尚未派生」在全局视图里就是假话。
     */
    sessionScoped: Boolean = true,
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
            body = if (sessionScoped) {
                "本会话尚未派生任何子代理运行；出现后将按状态显示徽标、耗时与结果预览。"
            } else {
                "全部会话都还没有派生任何子代理运行；出现后将按状态显示徽标、耗时与结果预览。"
            },
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
        // 父会话是契约必填字段：跨会话列表（session_id 省略）里它是唯一能说明
        // 「这条运行属于哪个会话」的字段，所以卡片必须画出来，不能只画子会话。
        MetaRow("父会话", run.parentSessionId)
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

// ==========================================================================
// G · 读取层：`subagent/list` 的三态、重试、跨会话范围
// ==========================================================================

/**
 * G · `subagent/list` 的窄口（RFC-0012 §6.1）。
 *
 * **为什么是 pull 不是事件流**：契约原文 ——「The run's LIFETIME truth is the
 * `subagent_runs` table — the events are increments, this is the pull」。`subagent.spawned`
 * / `subagent.status` / `subagent.result` 只是增量：拿增量拼列表，掉线/重连后就会留下永久
 * 空洞（事件没有 replay），而这正是本批要消灭的「零件齐但没人喂数据」。所以列表以本接口
 * 为唯一真相，事件留给「列表已到达后做增量刷新」的后续批次。
 *
 * [sessionId] = `null` 表示**跨会话**，这是契约允许的（`session_id` 省略 = across
 * sessions，且明确 **不是** shared execution）；每行自带 `parent_session_id`，客户端
 * 不做任何聚合，只把服务端原样返回的行画出来。
 */
interface SubagentSource {
    /** Throws on every failure — an empty list is never a stand-in for a failed read. */
    suspend fun list(sessionId: String?): SubagentPage
}

/**
 * One page of `subagent/list`. [nextCursor] is the contract's opaque keyset cursor,
 * passed through verbatim (clients MUST NOT parse it); non-null means the page was full
 * and more runs exist.
 */
data class SubagentPage(
    val runs: List<SubagentRunView>,
    val nextCursor: String? = null,
)

/**
 * The real [SubagentSource]: one typed RPC against [VadeClient], mapped 1:1 onto the UI
 * snapshot type. Pure adapter — owns no connection and no state.
 *
 * ## Invariants re-checked on the reply
 * The codec already requires `runs` + `run_id` / `parent_session_id` / `label` /
 * `state` / `created_at` / `duration_ms`; two contract rules are re-checked here because
 * violating them would paint a wrong page:
 *  1. a **scoped** read must only contain runs whose `parent_session_id` is the requested
 *     session (the param is a filter, not a hint) → `PROTOCOL_MISMATCH`;
 *  2. `run_id` must be the contract's `run_<32hex>` → `PROTOCOL_MISMATCH` (malformed ids
 *     are exactly what `subagent/get` refuses with INVALID_ARGUMENT, so a list carrying
 *     one is a broken core, not a run).
 *
 * A blank (but non-null) `session_id` is refused BEFORE any frame is built
 * (`INVALID_ARGUMENT`), same discipline as `UsageSourceReal`.
 */
class SubagentSourceReal(private val client: VadeClient) : SubagentSource {

    override suspend fun list(sessionId: String?): SubagentPage {
        if (sessionId != null && sessionId.isBlank()) {
            throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.session_id must be non-blank when present",
            )
        }
        val result = client.subagentList(
            SubagentListParams(sessionId = sessionId, limit = PAGE_LIMIT),
        )
        for (run in result.runs) {
            if (!RUN_ID.matches(run.runId)) {
                throw VadeException(
                    VadeErrorCodes.PROTOCOL_MISMATCH,
                    "subagent/list returned run_id=\"${run.runId}\" (contract: run_<32hex>)",
                )
            }
            if (sessionId != null && run.parentSessionId != sessionId) {
                throw VadeException(
                    VadeErrorCodes.PROTOCOL_MISMATCH,
                    "subagent/list filtered on session_id=\"$sessionId\" but returned " +
                        "run ${run.runId} of parent_session_id=\"${run.parentSessionId}\"",
                )
            }
        }
        return SubagentPage(result.runs.map { it.toRunView() }, result.nextCursor)
    }

    private companion object {
        /** Server default is 50; pinning it keeps the page size out of the UI. */
        const val PAGE_LIMIT = 50

        val RUN_ID = Regex("run_[0-9a-fA-F]{32}")
    }
}

/**
 * Protocol row → UI snapshot, field for field. Nothing is defaulted: an absent optional
 * stays `null` and the card renders「暂无数据」, never a fabricated value.
 */
fun ProtocolRunView.toRunView(): SubagentRunView = SubagentRunView(
    runId = runId,
    parentSessionId = parentSessionId,
    parentTurnId = parentTurnId,
    childSessionId = childSessionId,
    label = label,
    state = SubagentState.fromRaw(state),
    createdAt = createdAt,
    finishedAt = finishedAt,
    resultDigest = resultDigest,
    resultChars = resultChars,
    errorCode = errorCode,
    resultPreview = resultPreview,
    durationMs = durationMs,
)

/** The three states of one `subagent/list` read. There is no fourth, silent "empty". */
sealed interface SubagentLoadState {
    /** A `subagent/list` is in flight (also the panel's pre-first-request phase). */
    data object Loading : SubagentLoadState

    /** The contract reply is on screen. */
    data class Ready(val page: SubagentPage) : SubagentLoadState

    /** The read failed; [error] carries the C-04 bucket and the retry affordance text. */
    data class Failed(val error: SubagentLoadError) : SubagentLoadState
}

/** One failed subagent read, classified for the UI. Never converted into an empty Ready. */
data class SubagentLoadError(
    val category: VadeErrorCategory,
    val code: String?,
    val detail: String,
) {
    /** 分类文案 + code + 详情 — one line, always carries the category word. */
    val label: String
        get() = if (code.isNullOrBlank()) "${category.label}：$detail" else "${category.label}（$code）：$detail"

    /** What the retry button promises for this bucket. */
    val retryLabel: String
        get() = when (category) {
            VadeErrorCategory.Retryable -> "重试"
            VadeErrorCategory.NeedsReconnect -> "重连后重试"
            VadeErrorCategory.NeedsUserAction -> "修正后重试"
            VadeErrorCategory.Fatal -> "重试（通常无效）"
        }

    /** Why it failed and what the user can do — never a bare stack trace. */
    val hint: String
        get() = when (category) {
            VadeErrorCategory.Retryable ->
                "请求超时或服务繁忙；重试会重新拉取 subagent/list，已显示的运行不会残留。"
            VadeErrorCategory.NeedsReconnect ->
                "连接已断开或会话视图已失效；请恢复连接（重连 / 重新 attach）后再重试。"
            VadeErrorCategory.NeedsUserAction ->
                "参数或权限被拒绝；请按提示修正后再试（同一请求原样重试通常无效）。"
            VadeErrorCategory.Fatal ->
                "客户端与服务端版本或安全状态不一致；更新客户端或重新配对后才能恢复。"
        }
}

internal fun subagentLoadErrorOf(t: Throwable): SubagentLoadError = SubagentLoadError(
    category = loadErrorCategoryOf(t),
    code = loadErrorCodeOf(t),
    detail = t.message ?: t::class.simpleName ?: "unknown error",
)

/** Short state word for the panel header / tests: 加载中 / 已加载 / 加载失败. */
fun subagentStateLabel(state: SubagentLoadState): String = when (state) {
    SubagentLoadState.Loading -> "加载中"
    is SubagentLoadState.Ready -> "已加载"
    is SubagentLoadState.Failed -> "加载失败"
}

/**
 * Drives one `subagent/list` at a time with the same discipline as `UsageLoader`:
 *  - three states, an outcome is always Loading → Ready or Loading → Failed;
 *  - Failed is observable, never laundered into an empty Ready;
 *  - generation guard: a reply/failure from an older scope is dropped before any state
 *    write, so switching 全部会话 ↔ 某会话 fast can never show the wrong slice;
 *  - [retry] re-issues the last scope (no-op before the first [load]);
 *  - [dispose] stops all writes.
 */
class SubagentLoader(
    private val source: SubagentSource,
    private val scope: CoroutineScope,
) {
    /** The latest attempt's state. Starts [SubagentLoadState.Loading]: nothing answered yet. */
    var state: SubagentLoadState by mutableStateOf<SubagentLoadState>(SubagentLoadState.Loading)
        private set

    /** The scope of the current state: `null` = 全部会话 (no request before the first [load]). */
    var sessionId: String? by mutableStateOf<String?>(null)
        private set

    @Volatile
    private var generation = 0L

    @Volatile
    private var disposed = false

    @Volatile
    private var everLoaded = false

    /** (Re)loads [sessionId] (`null` = 跨会话). */
    fun load(sessionId: String?) {
        if (disposed) return
        generation += 1
        val gen = generation
        this.sessionId = sessionId
        everLoaded = true
        state = SubagentLoadState.Loading
        scope.launch {
            val next = try {
                SubagentLoadState.Ready(source.list(sessionId))
            } catch (e: CancellationException) {
                return@launch // disposed/cancelled: no state write, never a fake failure
            } catch (t: Throwable) {
                SubagentLoadState.Failed(subagentLoadErrorOf(t))
            }
            if (disposed || gen != generation) return@launch // stale slice: drop silently
            state = next
        }
    }

    /** The Failed card's retry entry: re-issue the last scope (no-op before any load). */
    fun retry() {
        if (!everLoaded) return
        load(sessionId)
    }

    /** Stops all further writes; idempotent. */
    fun dispose() {
        disposed = true
    }
}

/**
 * G · the wired 子代理 section — Loading / Failed(+retry) / Ready over a live list.
 *
 * [sessionScoped] only changes the empty-state wording (本会话 vs 全部会话); the list
 * itself is whatever the source answered for the scope it was asked.
 */
@Composable
fun SubagentSection(
    state: SubagentLoadState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    sessionScoped: Boolean = true,
) {
    when (state) {
        SubagentLoadState.Loading -> SubagentLoadingCard(modifier)
        is SubagentLoadState.Failed -> SubagentFailedCard(state.error, onRetry, modifier)
        is SubagentLoadState.Ready -> {
            SubagentPanel(
                runs = state.page.runs,
                modifier = modifier,
                sessionScoped = sessionScoped,
            )
            if (state.page.nextCursor != null) {
                UiText(
                    "还有更多子代理运行（本页已满；分页游标未取完，后续批次接入「加载更多」）",
                    size = 12,
                    color = LocalSkin.current.colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Self-driving variant: owns a [SubagentLoader] over [source] and (re)loads whenever
 * [sessionId] changes. `null` is a **legitimate scope** here (全部会话) — unlike usage,
 * the contract supports it — so this never shows a「尚未选择会话」dead end.
 */
@Composable
fun SubagentSectionLive(
    source: SubagentSource,
    sessionId: String?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val loader = remember(source) { SubagentLoader(source, scope) }
    DisposableEffect(loader) { onDispose { loader.dispose() } }
    LaunchedEffect(loader, sessionId) { loader.load(sessionId) }
    SubagentSection(
        state = loader.state,
        onRetry = loader::retry,
        modifier = modifier,
        sessionScoped = sessionId != null,
    )
}

/** Loading: an honest placeholder —「加载中」, no rows of any kind. */
@Composable
private fun SubagentLoadingCard(modifier: Modifier = Modifier) {
    val colors = LocalSkin.current.colors
    GroupCard(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VadeIcon(
                    VadeIcons.Waiting,
                    contentDescription = "加载中",
                    size = 16.dp,
                    tint = colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                UiText("加载中…", size = 13, weight = FontWeight.Medium, color = colors.onSurface)
            }
            UiText(
                "正在请求 subagent/list（${subagentStateLabel(SubagentLoadState.Loading)}），" +
                    "返回前不构造任何运行卡片。",
                size = 13,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** Failed: category-first banner + the exact C-04 code + a retry row. */
@Composable
private fun SubagentFailedCard(
    error: SubagentLoadError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSkin.current.colors
    GroupCard(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VadeIcon(
                    VadeIcons.Failed,
                    contentDescription = "加载失败",
                    size = 16.dp,
                    tint = colors.error,
                )
                Spacer(Modifier.width(8.dp))
                UiText("子代理列表加载失败", size = 13, weight = FontWeight.SemiBold, color = colors.error)
            }
            UiText(error.label, size = 13, color = colors.onSurface)
            UiText(error.hint, size = 13, color = colors.onSurfaceVariant)
            Row(
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.surfaceVariant)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VadeIcon(VadeIcons.Refresh, contentDescription = "重试", size = 16.dp, tint = colors.onSurface)
                Spacer(Modifier.width(6.dp))
                UiText(error.retryLabel, size = 12, weight = FontWeight.Medium, color = colors.onSurface)
            }
        }
    }
}
