package vade.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vade.ui.skin.AttachmentState
import vade.ui.skin.Decision
import vade.ui.skin.InputMode
import vade.ui.chat.ApprovalOutcome
import vade.ui.protocol.TurnCancelled
import vade.ui.protocol.TurnCompleted
import vade.ui.protocol.TurnFailed
import vade.ui.protocol.TurnInterrupted
import vade.ui.protocol.TurnStarted
import vade.ui.protocol.VadeEvent
import vade.ui.data.VadeErrorCategory
import vade.ui.data.VadeErrorMapping
import vade.ui.data.loadErrorCategoryOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * U1-b → v1.1 · chat state — **derived, disposable, client-only** (D98).
 *
 * There is no live core in this slice: the thread starts from [ChatFixtures] and every
 * transition below is the one the RPC will drive later, written against RFC-0004 (parts)
 * and RFC-0006 (turn states, queue, interrupt) so the view layer does not change when the
 * source does. The three transitions that carry acceptance criteria are deliberately *pure*
 * and time-free ([tick] advances one stream chunk; [stop] ends a turn; [submit] queues),
 * which is why `ChatFlowTest` can assert them without a window or a clock.
 *
 * v1.1 additions (this slice) hang off two **injectable narrow interfaces**:
 *  - [AttachmentUploader] — the blob upload channel ([stageUpload] / [runUpload] /
 *    [cancelUpload] / [retryUpload]); the default is the in-memory fake, the real
 *    `/ui/blob` channel is one implementation away (RESULT.md · 未接线清单);
 *  - [ChatTurnGateway] — `message/edit_resubmit` + `turn/regenerate` ([confirmEdit] /
 *    [regenerate]); same deal, [InMemoryTurnGateway] stands in this round.
 *
 * Nothing here is persisted, so dropping the object costs nothing that matters.
 */
class ChatState(
    val sessionId: String,
    private val script: List<String> = ChatFixtures.streamScript,
    preset: ChatPreset = ChatPreset.Live,
    /** Blob upload channel (injectable; in-memory fake this round). */
    private val uploader: AttachmentUploader = InMemoryAttachmentUploader(),
    /** `message/edit_resubmit` + `turn/regenerate` channel (injectable; fake this round). */
    private val turns: ChatTurnGateway = InMemoryTurnGateway(),
    /**
     * B2 · 真源的**读**通道（历史快照 + 事件流）。默认取自 [turns] 自己的能力：内存假实现
     * 返回 `null`（演示路径），真适配器带上就自动接上 —— 构造点不必为它多写一行。
     * 测试可直接注入一个假的读通道来驱动三态。
     */
    private val transcript: ChatTranscriptSource? = turns.transcript(),
) {
    /**
     * B2 · 真源模式：拿到真通道（[ChatTurnGateway.synthetic] == false）或显式给了读通道。
     *
     * **真源模式下一个预设消息都不渲染。** fixtures 是演示数据，曾经被当成 core 的内容
     * 上屏（真会话 `E2E-cad91adc` 里 core 只有 1 条、界面却有 6 条）——这条分界线就是修复
     * 本身：宁可空态/失败态，也不许显示不存在的消息。
     */
    val isRealSource: Boolean = !turns.synthetic || transcript != null

    /**
     * 三态（B2）：[ChatPhase.Loading] 加载中 / [ChatPhase.Ready] 就绪（**就绪含「空」**）/
     * [ChatPhase.Failed] 失败（[loadError] + [loadHistory] 重试）。三态各有各的文案与语义，
     * 不能都渲染成同一张空卡。假源开局即就绪（fixtures 就是内容）。
     */
    var phase: ChatPhase by mutableStateOf(if (isRealSource) ChatPhase.Loading else ChatPhase.Ready)
        private set

    /** 失败原因；[phase] 不是 [ChatPhase.Failed] 时为 null。 */
    var loadError: String? by mutableStateOf(null)
        private set

    /** 事件投影器 —— 与 `g-chatlive` 交付的是同一个 [LiveTranscript]，本类只负责喂。 */
    private val live = LiveTranscript()

    private val seed: List<ChatItem> =
        if (isRealSource) emptyList() else ChatFixtures.threadFor(sessionId).items

    val items = mutableStateListOf<ChatItem>().apply { addAll(seed) }

    /** The composer's text. Drafts are the textbook example of throwaway state (D98). */
    var draft by mutableStateOf("")
        private set

    /** Attachments staged in the composer but not yet "sent". Fake bytes; never read. */
    val staged = mutableStateListOf<StagedAttachment>()

    /** RFC-0006 `turn.queued` rows, in submission order. */
    val queue = mutableStateListOf<QueuedTurn>()

    /** Tool cards the reader expanded by hand (D94: only success starts collapsed). */
    private val opened = mutableStateListOf<String>()

    /** Non-null while the fullscreen image preview is up. Images only — files get `null`. */
    var preview by mutableStateOf<ContentPart.Image?>(null)
        private set

    /** 「已停止 · 已生成 N 字」 — a status line, not an error (FR-8.35). */
    var lastNotice by mutableStateOf<String?>(null)
        private set

    /** Text of the turn currently streaming; null when idle. */
    private var runningId: String? = null
    private var streamTarget: String = ""
    private var streamPos: Int = 0
    private var scriptCursor: Int = 0

    // --- g-chat-send · 真源出网的本地簿记 ----------------------------------------
    //
    // submit/interrupt 是同步入口（composer 键盘、预设态都要调），RPC 从 [wireScope]
    // 发射出去；回包与 turn.* 事件共同落进同一份状态。三个键把两边对上：
    //  - [clientMsgIdByTurnId]：ui/submit 回包的 turn_id ↔ 屏上气泡的 client_msg_id；
    //  - [runningTurnId]：最近一个 turn.started 的回合 —— ui/interrupt 的目标；
    //  - [interruptInFlight]：中断在途去重（重复点 ■ 不再发第二次）。

    /** 真源 RPC 的发射作用域：Unconfined 让假 gateway 的记录在 submit() 返回前确定（测试可用）。 */
    private val wireScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val clientMsgIdByTurnId = HashMap<String, String>()
    private var runningTurnId: String? = null
    private var interruptInFlight: String? = null

    val isStreaming: Boolean get() = runningId != null || (isRealSource && realRunningTurnId() != null)

    /**
     * 真源下正在运行的回合 id：优先 [runningTurnId]（turn.started 事件），退而求其次取
     * 最后一条 Running 的 live 行（`live:<turn_id>`——前缀剥离即 turn id；快照行没有
     * 前缀，剥不动就返回 null，不为它编 id）。
     */
    private fun realRunningTurnId(): String? {
        runningTurnId?.let { return it }
        val row = items.lastOrNull {
            it is ChatItem.Message && it.message.role == ChatRole.Assistant && it.message.state == MessageState.Running
        } as? ChatItem.Message ?: return null
        val turnId = row.message.id.removePrefix(LiveTranscript.LIVE_ID_PREFIX)
        return turnId.takeIf { it != row.message.id }
    }

    /**
     * Sendable = text, or at least one **finished** upload. An in-flight / failed chip is
     * not content (上传中点发送不带未完成附件); it stays in the composer and keeps moving.
     */
    val canSend: Boolean
        get() = draft.isNotBlank() ||
            staged.any { it.attachment.state == AttachmentState.Uploaded }

    val inputMode: InputMode
        get() = when {
            isStreaming -> InputMode.Streaming
            canSend -> InputMode.Composing
            else -> InputMode.Idle
        }

    /** `Skin.InputBar` has no per-state send enable, so the composer shows this instead. */
    val sendHint: String
        get() = when {
            isStreaming -> "正在生成 · 点 ■ 停止"
            else -> "输入消息 · ⌘Enter / Ctrl+Enter 发送"
        }

    // --- 真源：历史与实时事件（B2） ---------------------------------------------

    /**
     * 拉历史快照并渲染。真源模式下这是**唯一**的内容来源：
     *  - 拉到 N 条 → `items` 恰好是这 N 条（外加同一时刻已投影的 live 行），[phase] = Ready；
     *  - 拉到 0 条 → 同样 Ready，`items` 为空 —— 页面显示「还没有消息」空态；
     *  - 抛异常 → [phase] = Failed + [loadError]，页面给重试；
     *  - **任何情况下都不回退 [ChatFixtures]**（那正是 B2 的 bug）。
     *
     * 幂等：重复调用（重试 / 重进）按 id 重建，不会叠加重复行。[LiveTranscript.seed] 让
     * 快照行对后续 `message.edited` / `turn.superseded` 可解析。
     *
     * @return 是否成功进入 Ready（假源直接返回 false：它没有读通道，也不需要）。
     */
    suspend fun loadHistory(): Boolean {
        if (!isRealSource) return false
        val channel = transcript
        if (channel == null) {
            phase = ChatPhase.Failed
            loadError = NO_CHANNEL_MESSAGE
            return false
        }
        phase = ChatPhase.Loading
        loadError = null
        return try {
            val rows = channel.history(sessionId)
            live.seed(rows)
            // 用投影器的行序重建：快照行 + 加载期间已经到达的 live 行，一次到位、不叠行。
            items.clear()
            live.messages.forEach { items += ChatItem.Message(it) }
            phase = ChatPhase.Ready
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            phase = ChatPhase.Failed
            loadError = error.message?.takeIf { it.isNotBlank() } ?: LOAD_FAILED_FALLBACK
            false
        }
    }

    /** 失败态重试入口（页面按钮与测试共用同一条路径）。 */
    suspend fun retryHistory(): Boolean = loadHistory()

    /**
     * 投影一条 core→UI 事件。**不另写投影** —— 直接复用 `g-chatlive` 的
     * [vade.ui.chat.applyLive]：`items` 按 id upsert（就地替换 / 新行追加），seq 缺口、
     * 重复、溢出全部由 [LiveTranscript] 裁决（[liveAnomalies] 零静默）。
     *
     * 投影之后做真源回合的本地簿记（[projectTurnLifecycle]）：排队位、运行中的回合、
     * 失败行的可读化 —— 全部幂等，未知的 turn 是 no-op，假源一行都不动。
     */
    fun applyLiveEvent(event: VadeEvent): List<ChatMessage> {
        val snapshot = this.applyLive(live, event)
        projectTurnLifecycle(event)
        return snapshot
    }

    /**
     * turn.* 事件 → 本地回合状态的收尾。只服务真源：
     *  - `turn.started` — 记下运行中的回合（ui/interrupt 的目标），把它的排队条目摘掉
     *    （#N 徽标消失、气泡从 Queued 回到 Completed）；
     *  - `turn.completed` / `turn.interrupted` / `turn.cancelled` — 同样摘条目、清运行态；
     *  - `turn.failed` — 摘条目之外，把失败行按四类错误口径改成可读文案（[makeFailureReadable]）。
     */
    private fun projectTurnLifecycle(event: VadeEvent) {
        if (!isRealSource) return
        when (event) {
            is TurnStarted -> {
                runningTurnId = event.payload.turnId
                dequeueTurn(event.payload.turnId)
            }
            is TurnCompleted -> {
                if (runningTurnId == event.payload.turnId) runningTurnId = null
                dequeueTurn(event.payload.turnId)
            }
            is TurnInterrupted -> {
                if (runningTurnId == event.payload.turnId) runningTurnId = null
                if (interruptInFlight == event.payload.turnId) interruptInFlight = null
                dequeueTurn(event.payload.turnId)
            }
            is TurnCancelled -> {
                if (runningTurnId == event.payload.turnId) runningTurnId = null
                dequeueTurn(event.payload.turnId)
            }
            is TurnFailed -> {
                if (runningTurnId == event.payload.turnId) runningTurnId = null
                if (interruptInFlight == event.payload.turnId) interruptInFlight = null
                dequeueTurn(event.payload.turnId)
                makeFailureReadable(event.payload.turnId)
            }
            else -> Unit
        }
    }

    /** 回合收尾：排队条目摘掉，气泡若还穿着 Queued 就回到 Completed（消息本身是送达的）。 */
    private fun dequeueTurn(turnId: String) {
        val clientMsgId = clientMsgIdByTurnId[turnId] ?: return
        val entry = queue.firstOrNull { it.clientMsgId == clientMsgId } ?: return
        queue.remove(entry)
        setBubbleState(entry.messageId, from = MessageState.Queued, to = MessageState.Completed)
    }

    /**
     * `turn.failed` 上屏：把投影行的原始 C-04 码徽标换成四类口径的可读文案
     * （[VadeErrorMapping] 的冻结表 —— 与 `ModelFailure` 同一分类、同一 label）。
     * 行本体（正文 / Failed 状态）由投影器负责，这里只换徽标。
     */
    private fun makeFailureReadable(turnId: String) {
        val failure = live.failureOf(turnId) ?: return
        val rowId = LiveTranscript.liveRowId(turnId)
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == rowId }
        if (index < 0) return
        val row = (items[index] as ChatItem.Message).message
        items[index] = ChatItem.Message(row.copy(badges = listOf(turnFailureBadge(failure))))
    }

    /** 行上保留已追加的其他徽标，只把失败文案追加进去（不覆盖「已压缩」这类事实）。 */
    private fun setBadge(messageId: String, badge: String) {
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == messageId }
        if (index < 0) return
        val row = (items[index] as ChatItem.Message).message
        if (badge in row.badges) return
        items[index] = ChatItem.Message(row.copy(badges = row.badges + badge))
    }

    /** [from] == null 表示不设前置条件；行不存在时静默（簿记不许比事件还响）。 */
    private fun setBubbleState(messageId: String, from: MessageState?, to: MessageState) {
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == messageId }
        if (index < 0) return
        val row = (items[index] as ChatItem.Message).message
        if (from != null && row.state != from) return
        items[index] = ChatItem.Message(row.copy(state = to))
    }

    private fun bubbleText(messageId: String): String =
        (items.firstOrNull { it is ChatItem.Message && it.message.id == messageId } as? ChatItem.Message)
            ?.message?.bodyText ?: ""

    /**
     * 收集事件流直到协程被取消（页面在 `LaunchedEffect` 里调它）。读通道缺失时立即返回。
     * 真源模式下事件与历史**并行**接：先开收集、再拉历史，加载期间到达的 delta 不会丢
     * （[loadHistory] 重建时会把 live 行带上）。
     */
    suspend fun collectLiveEvents() {
        val channel = transcript ?: return
        try {
            channel.events().collect { event -> applyLiveEvent(event) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 事件流断了 ≠ 历史丢了：已投影的行留在原地，写一条可读的状态行而不是让
            // LaunchedEffect 把异常抛进重组器（那会直接崩页面）。
            lastNotice = "实时事件流中断：${error.message ?: "通道异常"}"
        }
    }

    /** 投影器的非静默决策（seq 缺口 / 丢弃 / 重复），供测试与调试 UI 读。 */
    val liveAnomalies: List<TranscriptAnomaly> get() = live.anomalies

    /** `seq <= lastSeq` 的幂等丢弃次数（重连重放是正常流量，不算 anomaly）。 */
    val liveDuplicates: Int get() = live.duplicates

    // --- composer -------------------------------------------------------------

    fun updateDraft(text: String) {
        draft = text
        if (lastNotice != null && text.isNotEmpty()) lastNotice = null
    }

    /**
     * Attach a fixture. U1-b reads **no file bytes** (no picker, no compression): the chip is
     * driven by [ChatFixtures.stagedAttachments], and [tickUploads] advances the fake progress
     * so the 上传中 state is real UI, not a hard-coded string.
     */
    fun attach(attachment: StagedAttachment = ChatFixtures.nextStaged(staged.size)) {
        if (staged.size >= MAX_PER_TURN) {
            lastNotice = "单轮最多 $MAX_PER_TURN 个附件（RFC-0004 §6.1）"
            return
        }
        staged += attachment
    }

    fun removeStaged(fileId: String) {
        // A removed uploader chip must also drop its request, so a still-running transfer
        // cannot resurrect it (same bookkeeping as cancelUpload).
        staged.firstOrNull { it.attachment.fileId == fileId }?.uploadId?.let { id ->
            uploadRequests.remove(id)
            cancelledUploads += id
        }
        staged.removeAll { it.attachment.fileId == fileId }
    }

    /** One progress step for every uploading chip. Returns true while any is still moving. */
    fun tickUploads(step: Float = 0.18f): Boolean {
        var moving = false
        for (index in staged.indices) {
            val current = staged[index]
            if (current.attachment.state != AttachmentState.Uploading) continue
            // Uploader-driven chips move only via their own channel events (runUpload);
            // tickUploads drives the legacy fixture chips (uploadId == null) alone.
            if (current.uploadId != null) continue
            val next = (current.attachment.progress + step).coerceAtMost(1f)
            val done = next >= 1f
            staged[index] = current.copy(
                attachment = current.attachment.copy(
                    progress = if (done) 1f else next,
                    state = if (done) AttachmentState.Uploaded else AttachmentState.Uploading,
                    detail = if (done) current.uploadedDetail else null,
                ),
            )
            if (!done) moving = true
        }
        return moving
    }

    // --- edit & resubmit (v1.1 · message/edit_resubmit) --------------------------

    /**
     * The message being edited inline (its **original** id), or null. The editor replaces
     * the bubble in the thread — it is an inline card, never a dialog.
     */
    var editingId by mutableStateOf<String?>(null)
        private set

    /** The inline editor's content — what [confirmEdit] sends, trimmed. */
    var editDraft by mutableStateOf("")
        private set

    /** 空内容禁止重发：the confirm control stays disabled on whitespace-only drafts. */
    val canConfirmEdit: Boolean
        get() = editingId != null && editDraft.isNotBlank()

    /**
     * Only the **last** user message is editable (`edit_resubmit` notes: "only the last
     * user message is editable"), and never a queued row (its turn does not exist yet).
     */
    fun canEditMessage(messageId: String): Boolean {
        if (queuePosition(messageId) > 0) return false
        val lastUserId = messages.lastOrNull { it.role == ChatRole.User }?.id ?: return false
        return messageId == lastUserId
    }

    /** Opens the inline editor prefilled with the row's current text. */
    fun startEdit(messageId: String): Boolean {
        if (!canEditMessage(messageId)) return false
        if (editingId != null && editingId != messageId) return false // one editor at a time
        editingId = messageId
        editDraft = messages.firstOrNull { it.id == messageId }?.bodyText ?: ""
        return true
    }

    fun updateEditDraft(text: String) {
        if (editingId == null) return
        editDraft = text
    }

    fun cancelEdit() {
        editingId = null
        editDraft = ""
    }

    /**
     * `message/edit_resubmit`: sends the trimmed editor text with the **original message
     * id** and, on success, projects the edit in place — the row's text becomes the new
     * text (the `message.edited` projection) and wears 「已编辑」; its attachments are
     * carried over. The rerun turn streams like a `submit` (queued while busy, with
     * `reason = edit_resubmit`). Failure keeps the editor open with the draft intact.
     */
    suspend fun confirmEdit(): Boolean {
        val messageId = editingId ?: return false
        val text = editDraft.trim()
        if (text.isEmpty()) return false
        val original = messages.firstOrNull { it.id == messageId } ?: return false
        val clientMsgId = "c-e-${items.count { it is ChatItem.Message } + 1}"
        val receipt = try {
            turns.editResubmit(sessionId, messageId, listOf(ContentPart.Text(text)), clientMsgId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastNotice = "重发失败：${error.message ?: "通道异常"}（原文未变，可重试）"
            return false
        }
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == messageId }
        if (index >= 0) {
            items[index] = ChatItem.Message(
                original.copy(
                    parts = listOf(ContentPart.Text(text)) + original.attachments,
                    edited = true,
                ),
            )
        }
        editingId = null
        editDraft = ""
        if (isRealSource) {
            // g-chat-send · 真源的排队位来自回包：queue_pos > 0 才建条目（#N = 真值），
            // queue_pos == 0 表示 core 已立即开始 —— 不建本地条目，事件流接管。
            clientMsgIdByTurnId[receipt.turnId] = clientMsgId
            if (receipt.queuePos > 0) {
                queue += QueuedTurn(
                    messageId = messageId,
                    clientMsgId = clientMsgId,
                    text = text,
                    reason = "edit_resubmit",
                    queuePos = receipt.queuePos,
                )
            }
        } else if (isStreaming) {
            queue += QueuedTurn(
                messageId = messageId,
                clientMsgId = clientMsgId,
                text = text,
                reason = "edit_resubmit",
            )
        } else {
            startAssistantTurn(messageId)
        }
        return true
    }

    // --- regenerate (v1.1 · turn/regenerate) --------------------------------------

    /**
     * Only the **last** assistant message, only in a terminal state, only while idle
     * (`turn/regenerate` data.reason carries `turn_active|not_tail|not_head`; the client
     * refuses those up front instead of waiting for the error).
     */
    fun canRegenerate(messageId: String): Boolean {
        if (isStreaming || queue.isNotEmpty()) return false
        val lastAssistantId = messages.lastOrNull { it.role == ChatRole.Assistant }?.id ?: return false
        if (messageId != lastAssistantId) return false
        val message = messages.firstOrNull { it.id == messageId } ?: return false
        return !message.superseded && when (message.state) {
            MessageState.Completed, MessageState.Interrupted, MessageState.Failed, MessageState.Cancelled -> true
            MessageState.Running, MessageState.Queued -> false
        }
    }

    /**
     * `turn/regenerate`: a new turn for the same user message. The old assistant row is
     * **kept** — marked 「旧版本」 and greyed out by the page — never deleted
     * (`turn.superseded` projection); the fresh turn streams below it.
     */
    suspend fun regenerate(messageId: String): Boolean {
        if (!canRegenerate(messageId)) return false
        val clientMsgId = "c-r-${items.count { it is ChatItem.Message } + 1}"
        // g-chat-send · 真源的行 id 是 `live:<turn_id>`：上帧必须还原成 core 的 turn_id
        //（快照行没有前缀，原样透传 —— 假源的合成 id 同样透传）。
        val turnId = messageId.removePrefix(LiveTranscript.LIVE_ID_PREFIX)
        val receipt = try {
            turns.regenerate(sessionId, turnId, clientMsgId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastNotice = "重新生成失败：${error.message ?: "通道异常"}"
            return false
        }
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == messageId }
        if (index >= 0) {
            val old = (items[index] as ChatItem.Message).message
            items[index] = ChatItem.Message(old.copy(superseded = true))
        }
        if (isRealSource) {
            // g-chat-send · 重生成同样落真 queue_pos：回合被 core 排队时，#N 挂在它服务的
            // 那条用户消息上（assistant 行之前的最近一条 User）。
            clientMsgIdByTurnId[receipt.turnId] = clientMsgId
            if (receipt.queuePos > 0) {
                val assistantIndex = messages.indexOfFirst { it.id == messageId }
                val owner = messages.take(assistantIndex).lastOrNull { it.role == ChatRole.User }
                if (owner != null && queue.none { it.clientMsgId == clientMsgId }) {
                    queue += QueuedTurn(
                        messageId = owner.id,
                        clientMsgId = clientMsgId,
                        text = owner.bodyText,
                        reason = "regenerate",
                        queuePos = receipt.queuePos,
                    )
                }
            }
        }
        startAssistantTurn(messageId)
        return true
    }

    // --- attachment uploads (injectable AttachmentUploader channel) ----------------

    private var uploadSeq = 0
    private val uploadRequests = mutableMapOf<String, PendingAttachment>()
    private val cancelledUploads = mutableSetOf<String>()

    /** Uploader chips currently transferring (a11y hint / tests). */
    val uploadingCount: Int
        get() = staged.count { it.uploadId != null && it.attachment.state == AttachmentState.Uploading }

    /**
     * Adds the chip (上传中 0%) and registers the pending transfer. Returns the upload id
     * the page runs with [runUpload], or null when the RFC cap is hit or the same pick is
     * already staged un-finished (同一文件不重复加 chip; a finished chip does not block a
     * fresh pick).
     */
    fun stageUpload(request: PendingAttachment): String? {
        if (staged.size >= MAX_PER_TURN) {
            lastNotice = "单轮最多 $MAX_PER_TURN 个附件（RFC-0004 §6.1）"
            return null
        }
        val duplicate = staged.any {
            it.attachment.state != AttachmentState.Uploaded && it.pending?.identity == request.identity
        }
        if (duplicate) {
            lastNotice = "同一文件只保留一个上传（失败用 ↻ 重试）"
            return null
        }
        uploadSeq += 1
        val uploadId = "u-$uploadSeq"
        uploadRequests[uploadId] = request
        staged += StagedAttachment(
            attachment = vade.ui.skin.AttachmentView(
                fileId = uploadId,
                fileName = request.name,
                mime = request.mime,
                state = AttachmentState.Uploading,
                progress = 0f,
            ),
            part = ContentPart.File(uploadId, request.name, request.sizeBytes, request.mime),
            uploadedDetail = pendingUploadedDetail(request),
            uploadId = uploadId,
            pending = request,
        )
        return uploadId
    }

    /**
     * Runs the transfer for [uploadId] to completion. **The caller owns the coroutine** —
     * cancelling it is the real abort (上传中可取消); [cancelUpload] is the UI-side removal
     * that also turns any late event into a no-op, so a cancelled chip can never resurrect.
     */
    suspend fun runUpload(uploadId: String) {
        val request = uploadRequests[uploadId] ?: return
        try {
            val blobId = uploader.upload(request, uploadId) { progress ->
                if (uploadId !in cancelledUploads) updateUploadProgress(uploadId, progress)
            }
            if (uploadId in cancelledUploads) return // late completion is dropped
            completeUpload(uploadId, blobId, request)
        } catch (error: CancellationException) {
            throw error // structured cancellation propagates; the chip is already gone
        } catch (error: Exception) {
            if (uploadId in cancelledUploads) return
            failUpload(uploadId, error.message?.takeIf { it.isNotBlank() } ?: "上传失败")
        }
    }

    /** Chip removal + abort bookkeeping. The caller cancels the in-flight job itself. */
    fun cancelUpload(uploadId: String): Boolean {
        val index = staged.indexOfFirst { it.uploadId == uploadId }
        if (index < 0) return false
        uploadRequests.remove(uploadId)
        cancelledUploads += uploadId
        staged.removeAt(index)
        return true
    }

    /** 失败重试：the SAME chip flips back to 上传中 0% — never a second chip. */
    fun retryUpload(uploadId: String): Boolean {
        val index = staged.indexOfFirst { it.uploadId == uploadId }
        if (index < 0) return false
        val current = staged[index]
        if (current.attachment.state != AttachmentState.Failed) return false
        if (!uploadRequests.containsKey(uploadId)) return false
        staged[index] = current.copy(
            attachment = current.attachment.copy(
                state = AttachmentState.Uploading,
                progress = 0f,
                detail = null,
            ),
        )
        return true
    }

    private fun updateUploadProgress(uploadId: String, progress: Float) {
        val index = staged.indexOfFirst { it.uploadId == uploadId }
        if (index < 0) return
        val current = staged[index]
        staged[index] = current.copy(
            attachment = current.attachment.copy(
                progress = progress.coerceIn(0f, 1f),
                state = AttachmentState.Uploading,
            ),
        )
    }

    private fun completeUpload(uploadId: String, blobId: String, request: PendingAttachment) {
        val index = staged.indexOfFirst { it.uploadId == uploadId }
        if (index < 0) return
        val current = staged[index]
        val part = if (request.width != null && request.height != null) {
            ContentPart.Image(
                fileId = blobId,
                name = request.name,
                size = request.sizeBytes,
                width = request.width ?: 0,
                height = request.height ?: 0,
                mime = request.mime,
            )
        } else {
            ContentPart.File(blobId, request.name, request.sizeBytes, request.mime)
        }
        staged[index] = current.copy(
            attachment = current.attachment.copy(
                fileId = blobId,
                state = AttachmentState.Uploaded,
                progress = 1f,
                detail = current.uploadedDetail,
            ),
            part = part,
        )
        uploadRequests.remove(uploadId)
    }

    private fun failUpload(uploadId: String, message: String) {
        val index = staged.indexOfFirst { it.uploadId == uploadId }
        if (index < 0) return
        val current = staged[index]
        staged[index] = current.copy(
            attachment = current.attachment.copy(
                state = AttachmentState.Failed,
                progress = 0f,
                detail = message,
            ),
        )
    }

    private fun pendingUploadedDetail(request: PendingAttachment): String =
        if (request.width != null && request.height != null) {
            "${formatBytes(request.sizeBytes)} · ${request.width}×${request.height} · $COMPRESSED_NOTE"
        } else {
            formatBytes(request.sizeBytes)
        }

    // --- turns ----------------------------------------------------------------

    /**
     * `ui/submit` (FR-8.35 / RFC-0006 §5.4): one user bubble + one turn. While another turn
     * runs the new one is queued (`turn.queued`) and wears 「排队中 #N」 instead of starting.
     *
     * Only **finished** uploads ride along (上传中点发送不带未完成附件): in-flight and
     * failed chips stay staged and keep moving. Returns the new `client_msg_id`, or null
     * when neither text nor a finished attachment is present (empty must not be sendable).
     *
     * g-chat-send · 真源模式下本地回显之外**必须出网**：`turns.submit`（即 `ui/submit`）
     * 恰好一次，回包的 `turn_id` / `queue_pos` 落进 [queue] 与气泡状态（#N 用 core 的
     * queue_pos）；失败落在气泡上（四类口径），绝不静默。假源路径逐字节不变。
     */
    fun submit(): String? {
        val text = draft.trim()
        val ready = staged.filter { it.attachment.state == AttachmentState.Uploaded }
        if (text.isEmpty() && ready.isEmpty()) return null
        val clientMsgId = "c-${items.count { it is ChatItem.Message } + 1}"
        val messageId = "m-${sessionId}-${clientMsgId}"
        val parts = buildList {
            if (text.isNotEmpty()) add(ContentPart.Text(text))
            ready.forEach { add(it.part) }
        }
        val busy = isStreaming
        items += ChatItem.Message(
            ChatMessage(
                id = messageId,
                role = ChatRole.User,
                parts = parts,
                state = if (busy) MessageState.Queued else MessageState.Completed,
                badges = if (ready.any { it.part is ContentPart.Image }) listOf(COMPRESSED_NOTE) else emptyList(),
                clientMsgId = clientMsgId,
            ),
        )
        staged.removeAll { it.attachment.state == AttachmentState.Uploaded }
        draft = ""
        lastNotice = null

        if (isRealSource) {
            // 真源：出网。同步入口不动本地回合语义（startAssistantTurn 在真源下本就 no-op），
            // 排队与否由 core 的回包说了算。
            wireScope.launch { submitOverWire(messageId, clientMsgId, parts) }
            return clientMsgId
        }
        if (busy) {
            queue += QueuedTurn(messageId = messageId, clientMsgId = clientMsgId, text = text)
        } else {
            startAssistantTurn(messageId)
        }
        return clientMsgId
    }

    /**
     * 真源 `ui/submit` 的出网与回包落地。回包规则：
     *  - `queue_pos > 0` 且该回合还没有可见的 live 行 → 建排队条目（#N = 真 queue_pos），
     *    气泡穿 Queued；
     *  - `queue_pos == 0` → core 立即开始：清掉本地乐观置队的痕迹，交给事件流；
     *  - 回包晚于回合的可见行（事件跑赢了 RPC 回包）→ **不补**排队痕迹，只把可能存在的
     *    乐观 Queued 纠正回去 —— 顺序反转不许在屏上撒谎。
     */
    private suspend fun submitOverWire(messageId: String, clientMsgId: String, parts: List<ContentPart>) {
        val receipt = try {
            turns.submit(sessionId, parts, clientMsgId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            markSubmitFailed(messageId, error)
            return
        }
        clientMsgIdByTurnId[receipt.turnId] = clientMsgId
        val row = live.messageById(LiveTranscript.liveRowId(receipt.turnId))
        if (receipt.queuePos > 0 && row == null) {
            val existing = queue.firstOrNull { it.clientMsgId == clientMsgId }
            if (existing == null) {
                queue += QueuedTurn(
                    messageId = messageId,
                    clientMsgId = clientMsgId,
                    text = bubbleText(messageId),
                    queuePos = receipt.queuePos,
                )
            } else if (existing.queuePos != receipt.queuePos) {
                queue[queue.indexOf(existing)] = existing.copy(queuePos = receipt.queuePos)
            }
            setBubbleState(messageId, from = null, to = MessageState.Queued)
        } else {
            queue.removeAll { it.clientMsgId == clientMsgId }
            setBubbleState(messageId, from = MessageState.Queued, to = MessageState.Completed)
        }
    }

    /** 发送失败不能吞：气泡就地 Failed + 四类口径的可读文案（正文仍是用户自己的话）。 */
    private fun markSubmitFailed(messageId: String, error: Exception) {
        setBadge(messageId, wireFailureBadge("发送失败", error))
        setBubbleState(messageId, from = null, to = MessageState.Failed)
    }

    /** `ui/queue.cancel` (FR-8.36): the row disappears and the rest shift up immediately. */
    fun cancelQueued(clientMsgId: String): Boolean {
        val removed = queue.firstOrNull { it.clientMsgId == clientMsgId } ?: return false
        queue.remove(removed)
        // A `submit` entry owns the bubble it created — cancelling deletes it. An
        // `edit_resubmit` entry only queues the rerun of an EXISTING row: cancelling
        // drops the queued turn and the (edited) row stays.
        if (removed.reason == "submit") {
            items.removeAll { it is ChatItem.Message && it.message.id == removed.messageId }
        }
        return true
    }

    /**
     * 1-based position shown on the badge; 0 when not queued.
     * g-chat-send · 真源条目带 core 的 `queue_pos`（[QueuedTurn.queuePos] > 0）时**只认真值**
     * —— 本地计数只在假源条目（queuePos == 0）上兜底，行为与接线前逐字节一致。
     */
    fun queuePosition(messageId: String): Int {
        val entry = queue.firstOrNull { it.messageId == messageId } ?: return 0
        if (entry.queuePos > 0) return entry.queuePos
        return queue.indexOfFirst { it.messageId == messageId }.let { if (it < 0) 0 else it + 1 }
    }

    /**
     * `ui/interrupt` → `turn.interrupted` (FR-8.28 / RFC-0006 §4). The generated text is
     * **kept**, the turn becomes `interrupted` with `partial_chars`, and the next queued turn
     * starts. No red error state: an interrupt is a normal outcome.
     *
     * g-chat-send · 真源模式下停止**必须出网**：`turns.interrupt`（即 `ui/interrupt`）恰好
     * 一次，本地不编 stop 语义 —— `turn.interrupted` 事件负责收尾上屏（部分文本、partial_chars、
     * 下一个排队回合都由事件驱动）。在途去重：同一回合的重复 ■ 不发第二次。
     */
    fun stop(): Boolean {
        if (isRealSource) {
            val turnId = realRunningTurnId() ?: return false
            if (interruptInFlight == turnId) return false
            interruptInFlight = turnId
            wireScope.launch { interruptOverWire(turnId) }
            return true
        }
        val id = runningId ?: return false
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        if (index >= 0) {
            val message = (items[index] as ChatItem.Message).message
            items[index] = ChatItem.Message(
                message.copy(
                    state = MessageState.Interrupted,
                    partialChars = message.bodyText.length,
                ),
            )
        }
        runningId = null
        streamTarget = ""
        streamPos = 0
        val chars = (items.firstOrNull { it is ChatItem.Message && it.message.id == id }
            as? ChatItem.Message)?.message?.partialChars ?: 0
        lastNotice = "已停止 · 已生成 $chars 字"
        startNextQueued()
        return true
    }

    /**
     * 真源中断的出网与失败可见化。成功时什么都不做（`turn.interrupted` 事件是唯一上屏者）；
     * 失败时回合还在跑，状态行必须明说，不许让 ■ 假装已经停下。
     */
    private suspend fun interruptOverWire(turnId: String) {
        try {
            turns.interrupt(sessionId, turnId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            interruptInFlight = null // 允许用户再点一次
            val row = live.messageById(LiveTranscript.liveRowId(turnId))
            if (row != null && row.state != MessageState.Running) return // 事件已收尾：报错就是噪音
            lastNotice = "${wireFailureBadge("中断失败", error)}，可再试一次"
        }
    }

    /**
     * One stream chunk (`turn.delta`). Returns true when the thread changed, so the UI can
     * drive this from a `LaunchedEffect` and a test can drive it in a loop.
     */
    fun tick(chunk: Int = 6): Boolean {
        val id = runningId ?: return false
        if (streamPos >= streamTarget.length) {
            finishRunning()
            return true
        }
        val next = (streamPos + chunk).coerceAtMost(streamTarget.length)
        streamPos = next
        val partial = streamTarget.substring(0, next)
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == id }
        if (index < 0) return false
        val message = (items[index] as ChatItem.Message).message
        items[index] = ChatItem.Message(
            message.copy(
                parts = listOf(ContentPart.Text(partial)),
                state = if (next >= streamTarget.length) MessageState.Completed else MessageState.Running,
            ),
        )
        if (next >= streamTarget.length) {
            runningId = null
            streamTarget = ""
            streamPos = 0
            startNextQueued()
        }
        return true
    }

    /** Whole-reply fast-forward — used by the tests and by the `--chat=done` screenshot state. */
    fun finishStream(): Boolean {
        if (!isStreaming) return false
        tick(chunk = Int.MAX_VALUE / 2)
        return true
    }

    private fun startNextQueued() {
        val next = queue.firstOrNull() ?: return
        queue.removeAt(0)
        val index = items.indexOfFirst { it is ChatItem.Message && it.message.id == next.messageId }
        if (index >= 0) {
            val message = (items[index] as ChatItem.Message).message
            items[index] = ChatItem.Message(message.copy(state = MessageState.Completed))
        }
        startAssistantTurn(next.messageId)
    }

    private fun startAssistantTurn(userMessageId: String) {
        // B2 · 真源模式下本地脚本一行都不许编：回复由 core 的 turn.delta 事件投影，
        // 拿 ChatFixtures.streamScript 造一条「助手回复」就是同一个假数据病。
        if (isRealSource) return
        val reply = script[scriptCursor % script.size]
        scriptCursor++
        val assistantId = "a-$userMessageId-${items.size}"
        items += ChatItem.Message(
            ChatMessage(
                id = assistantId,
                role = ChatRole.Assistant,
                parts = listOf(ContentPart.Text("")),
                state = MessageState.Running,
            ),
        )
        runningId = assistantId
        streamTarget = reply
        streamPos = 0
    }

    private fun finishRunning() {
        runningId = null
        streamTarget = ""
        streamPos = 0
    }

    // --- tool cards (D94) -----------------------------------------------------

    /** `batch_id` → how many calls share it. The card shows 「同批 N 个」, never "parallel". */
    fun batchSize(batchId: String?): Int {
        if (batchId == null) return 0
        return items.count { it is ChatItem.Tool && it.call.batchId == batchId }
    }

    fun toggleToolCard(callId: String) {
        if (opened.contains(callId)) opened.remove(callId) else opened += callId
    }

    fun cardState(call: ToolCall): vade.ui.skin.ToolCardState =
        toolCardState(call, openedByUser = opened.contains(call.id))

    // --- inline approvals (v2 §4) ------------------------------------------------

    /**
     * The client-side projection of `turn.approval_resolved`. The card evolves **in place**
     * (待批准 → 已允许 / 已拒绝 / 已超时) and **never adds a message row** — the [items]
     * list is untouched by every method below, which is what the tests pin.
     *
     * No clock: the resolved card's 「时间戳」 is honest elapsed-since-screen-open (`T+Ns`,
     * monotonic). A real socket swap replaces it with the event's own timestamp — a source
     * change, not a view change (D98).
     */
    private val approvals = mutableStateMapOf<String, ApprovalOutcome>()
    private val approvalDetailRows = mutableStateListOf<String>()
    private val approvalDecidedAt = mutableMapOf<String, String>()
    private val screenOpenMark = TimeSource.Monotonic.markNow()

    /** Outcome of the inline approval card for [callId]; [ApprovalOutcome.Pending] until decided. */
    fun approvalOutcome(callId: String): ApprovalOutcome =
        approvals[callId] ?: ApprovalOutcome.Pending

    /** The elapsed label rendered next to a resolved outcome; null while pending. */
    fun approvalDecidedLabel(callId: String): String? = approvalDecidedAt[callId]

    /** `⏷` detail expansion — tool name / batch / started-at rows under the args preview. */
    fun approvalDetailOpen(callId: String): Boolean = callId in approvalDetailRows

    fun toggleApprovalDetail(callId: String) {
        if (callId in approvalDetailRows) approvalDetailRows.remove(callId) else approvalDetailRows += callId
    }

    /**
     * ✓ 允许 / ✕ 拒绝. Succeeds only for a real `PendingApproval` tool row that is still
     * undecided — deciding twice is a no-op (`turn.approval_resolved` arrives once).
     */
    fun decideApproval(callId: String, decision: Decision): Boolean {
        val call = toolCallById(callId) ?: return false
        if (call.state != ToolRunState.PendingApproval) return false
        if (approvalOutcome(callId) != ApprovalOutcome.Pending) return false
        approvals[callId] =
            if (decision == Decision.Approve) ApprovalOutcome.Allowed else ApprovalOutcome.Denied
        approvalDecidedAt[callId] = "T+${screenOpenMark.elapsedNow().inWholeSeconds}s"
        return true
    }

    /** Timeout projection: the same card flips to 「已超时」 without a user decision. */
    fun expireApproval(callId: String): Boolean {
        val call = toolCallById(callId) ?: return false
        if (call.state != ToolRunState.PendingApproval) return false
        if (approvalOutcome(callId) != ApprovalOutcome.Pending) return false
        approvals[callId] = ApprovalOutcome.TimedOut
        approvalDecidedAt[callId] = "T+${screenOpenMark.elapsedNow().inWholeSeconds}s"
        return true
    }

    /** True while any timeline approval card is still waiting (list badge / a11y hint). */
    val hasPendingApproval: Boolean
        get() = items.any {
            it is ChatItem.Tool &&
                it.call.state == ToolRunState.PendingApproval &&
                approvalOutcome(it.call.id) == ApprovalOutcome.Pending
        }

    private fun toolCallById(callId: String): ToolCall? =
        (items.firstOrNull { it is ChatItem.Tool && it.call.id == callId } as? ChatItem.Tool)?.call

    // --- preview --------------------------------------------------------------

    fun openPreview(image: ContentPart.Image) {
        preview = image
    }

    fun closePreview() {
        preview = null
    }

    /** Messages only — tool rows are not part of the 「消息 → 输入区」 reading order. */
    val messages: List<ChatMessage>
        get() = items.mapNotNull { (it as? ChatItem.Message)?.message }

    init {
        // The fixture ends mid-stream: adopt it as the live turn so `tick` continues the same
        // message instead of appending a second bubble (`turn.delta` never creates a row).
        // 真源模式没有 fixtures，也就没有「续写中的预设回合」可接管（B2）。
        val last = (items.lastOrNull() as? ChatItem.Message)?.message
        if (!isRealSource && last != null && last.state == MessageState.Running && streamTarget.isEmpty()) {
            val target = ChatFixtures.streamingReply
            val visible = last.bodyText
            if (target.startsWith(visible)) {
                runningId = last.id
                streamTarget = target
                streamPos = visible.length
            }
        }
        // 预置态（截图/测试用）本身就是 fixture 形状的派生状态：真源下一条都不套用，
        // 否则 `--chat=queue` 之类会把假的草稿/气泡塞进真会话。
        if (!isRealSource) applyPreset(preset)
    }

    /**
     * States the screenshot loop and the tests need, reachable without a UI driver.
     * `Live` is the shipping default; everything else only pre-sets derived state.
     */
    @Suppress("UNUSED_EXPRESSION")
    private fun applyPreset(preset: ChatPreset) {
        when (preset) {
            ChatPreset.Live -> Unit

            ChatPreset.ExpandedTools -> {
                items.forEach { item ->
                    if (item is ChatItem.Tool && toolCardCollapsible(item.call)) opened += item.call.id
                }
            }

            ChatPreset.Queue -> {
                draft = "先别改 gradle.properties，等我贴一下 daemon 日志。"
                submit()
                draft = "顺手把 build/ 的锁文件删掉。"
                submit()
                draft = ""
            }

            ChatPreset.Composer -> {
                // 多行输入的截图要的是「上膛」的发送球（accent + 辉光 + ↑）；流式中同一
                // 球位是 ■ 停止键，所以先把夹具遗留的流式回合收尾（正常中断，不报错）。
                if (isStreaming) stop()
                updateDraft("第一行：把 132dp 的高度上限再核一遍。\n" +
                    "第二行：⌘Enter 与 Enter 的行为要分开写进注释。\n" +
                    "第三行：附件条最多显示 2 个，其余折叠成计数。\n" +
                    "第四行：停止后必须保留已生成文本。\n" +
                    "第五行：队列 #1/#2/#3 的编号要随取消前移。")
                attach()
                attach()
                // A still frame has no ticker (`animate = false`), so advance the two chips to a
                // realistic mid-upload value here — otherwise the capture shows 「上传中 0%」,
                // a state the live app never rests in.
                repeat(3) { tickUploads() }
            }

            ChatPreset.Preview -> preview = ChatFixtures.previewImage

            ChatPreset.Interrupted -> {
                items.indexOfFirst { it is ChatItem.Message && it.message.isStreaming }
                    .takeIf { it >= 0 }
                    ?.let { stop() }
            }
        }
    }

    // --- g-chat-send · 失败文案（四类口径） ----------------------------------------

    /**
     * `turn.failed` 的可读徽标：`回合失败（<四类>）：<core 原话>` —— 分类与措辞都复用
     * [VadeErrorMapping] 的冻结表（与 `ModelFailure` 的 label 口径同构：标题（分类）：正文）。
     */
    private fun turnFailureBadge(failure: TurnFailure): String =
        failureBadge("回合失败", VadeErrorMapping.categoryOf(failure.code), failure.message.ifBlank { failure.code })

    /** 通道/回包异常的徽标：分类走 [loadErrorCategoryOf]（非协议异常落 Retryable）。 */
    private fun wireFailureBadge(headline: String, error: Throwable): String =
        failureBadge(
            headline,
            loadErrorCategoryOf(error),
            error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "通道异常",
        )

    private fun failureBadge(headline: String, category: VadeErrorCategory, detail: String): String =
        "$headline（${category.label}）：$detail"

    companion object {
        /** RFC-0004 §6.1: `attach.max_per_turn` default 10, hard cap 20. */
        const val MAX_PER_TURN = 10

        // --- B2 · 三态文案（页面与测试共用同一份字面量，避免两处漂移） ---------------

        /** 加载中（[ChatPhase.Loading]）。 */
        const val LOADING_TEXT: String = "正在加载消息…"

        /** 空态（[ChatPhase.Ready] 且没有一条消息）。 */
        const val EMPTY_TEXT: String = "还没有消息"

        /** 空态的副行：告诉读者「这里是空的」而不是「加载坏了」。 */
        const val EMPTY_HINT: String = "在下面输入第一条消息"

        /** 失败态前缀；后面接 [loadError]。 */
        const val FAILED_TEXT: String = "消息加载失败"

        /** 失败态的按钮。 */
        const val RETRY_TEXT: String = "重试"

        /** 真 gateway 但没有读通道（`ChatTurnGateway.transcript()` 为 null）时的原因。 */
        const val NO_CHANNEL_MESSAGE: String =
            "没有读历史的通道（turns 已接真源，缺 ChatTranscriptSource）"

        /** 异常没带 message 时的兜底原因。 */
        const val LOAD_FAILED_FALLBACK: String = "历史快照读取异常"
    }
}

/** B2 · 详情页对话的三态。[ChatPhase.Ready] 同时覆盖「有消息」与「真空」两种呈现。 */
enum class ChatPhase { Loading, Ready, Failed }

/** Screenshot / test seeds. Only client-derived state changes — the RPC shapes do not. */
enum class ChatPreset { Live, ExpandedTools, Queue, Composer, Preview, Interrupted }

/** A composer attachment: the C-09 chip view plus the part it would become on submit. */
@androidx.compose.runtime.Immutable
data class StagedAttachment(
    val attachment: vade.ui.skin.AttachmentView,
    val part: ContentPart,
    val uploadedDetail: String,
    /**
     * Non-null when this chip rides the injectable [AttachmentUploader] channel (its
     * progress arrives from the channel's events via `runUpload`). Null = legacy fixture
     * chip, driven by `tickUploads` (the no-channel demo path).
     */
    val uploadId: String? = null,
    /** The pick behind the chip — identity used to refuse duplicate staging. */
    val pending: PendingAttachment? = null,
)
