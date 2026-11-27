package vade.ui.chat

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import vade.ui.skin.AttachmentState
import vade.ui.skin.Decision
import vade.ui.skin.InputMode
import vade.ui.chat.ApprovalOutcome
import vade.ui.protocol.ApprovalDecision
import vade.ui.protocol.ApprovalScope
import vade.ui.protocol.InputKind
import vade.ui.protocol.InputValue
import vade.ui.protocol.TurnApprovalRequired
import vade.ui.protocol.TurnApprovalRequiredPayload
import vade.ui.protocol.TurnApprovalResolved
import vade.ui.protocol.TurnApprovalResolvedPayload
import vade.ui.protocol.TurnCancelled
import vade.ui.protocol.TurnCompleted
import vade.ui.protocol.TurnFailed
import vade.ui.protocol.TurnInputRequired
import vade.ui.protocol.TurnInputRequiredPayload
import vade.ui.protocol.TurnInputResolved
import vade.ui.protocol.TurnInputResolvedPayload
import vade.ui.protocol.TurnInterrupted
import vade.ui.protocol.TurnQueued
import vade.ui.protocol.TurnQueuedPayload
import vade.ui.protocol.TurnStarted
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeEvent
import vade.ui.protocol.VadeException
import vade.ui.data.VadeErrorCategory
import vade.ui.data.VadeErrorMapping
import vade.ui.data.epochNowMs
import vade.ui.data.loadErrorCategoryOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
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
 * Nothing else here is persisted, so dropping the object costs nothing that matters. The one
 * exception is the composer's [draft]: it is handed to [DraftStore] on every edit (see
 * [updateDraft]) so that leaving the page — a bottom-tab switch, a rebuilt screen — does not
 * eat the half-written message.
 */
class ChatState(
    val sessionId: String,
    private val script: List<String> = ChatFixtures.streamScript,
    private val preset: ChatPreset = ChatPreset.Live,
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
    /**
     * 草稿的存放处（默认进程内单例 [DraftStore.shared]）。会话页离开时 [ChatState] 被销毁，
     * 草稿靠它按 [sessionId] 留住：页面重建 → 构造时 [DraftStore.load]，每次编辑
     * [DraftStore.save]，发送成功/清空输入框 [DraftStore.clear]。
     *
     * 放在参数表末尾且带默认值：已有调用点一行都不用改，测试可注入一份隔离的表。
     */
    private val drafts: DraftStore = DraftStore.shared,
    /**
     * 页面时钟（unix ms）—— **唯一**的时间源，两个消费者共用：
     *  - `client_msg_id` 的铸造（[ClientMsgIds]，见 [newClientMsgId]）：测试用固定值钉住
     *    `ui-<epochMs>-<seq>` 形状与「两次 submit 不相等」；
     *  - ask-user 问答的 `expires_at` 倒计时 / 超时关窗（ask-user 是唯一按墙钟自动关的
     *    对话框，见 [PendingInput.fromEvent]）。
     *
     * 默认系统时钟；测试注入固定值把这两条都变成确定性行为。
     */
    private val clock: () -> Long = { epochNowMs() },
    /**
     * 真源 RPC 的发射调度器。默认 [Dispatchers.Default]（不在调用者线程上跑网络/挂起
     * 逻辑，也不把 UI 线程当协程调度器）；测试注入 `Dispatchers.Unconfined` 让假 gateway
     * 的回包在 `submit()` 返回前落地，断言因此是确定的。
     */
    private val wireDispatcher: CoroutineDispatcher = Dispatchers.Default,
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

    /**
     * The composer's text.
     *
     * 唯一的写入口就是这个 setter，它做两件事：更新 [draftText]（Compose 观察它），并把新值
     * 写回 [drafts]。所以「每次编辑保存」「发送成功清除」「用户清空输入框清除」都是同一条
     * 通路，不会漏掉某个 `draft = …`。构造时先 [DraftStore.load]：离开会话页再回来，半句话还在。
     *
     * 空白草稿按清除处理 —— 切回来看到一串只有空格/换行的输入没有意义。
     */
    private var draftText by mutableStateOf(drafts.load(sessionId))

    var draft: String
        get() = draftText
        private set(value) {
            draftText = value
            // 预设只改屏上夹具，**不得**覆盖/删除该会话的真实草稿：`--chat=composer` 与
            // `--chat=queue` 都是截图/演示态，它们写进单例表会污染真会话（审查 P2-1）。
            if (preset != ChatPreset.Live) return
            if (value.isBlank()) drafts.clear(sessionId) else drafts.save(sessionId, value)
        }

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

    /**
     * Text of the turn currently streaming; null when idle.
     *
     * **必须是 snapshot state**：它是 [isStreaming] 唯一的本地来源，而 [streamingTickerKey]
     * 要靠「开始 / 结束」这两个**边沿**唤醒页面 ticker。早先它是普通 `var`，界面只是因为
     * 同一次调用里顺带写了 `items` 才碰巧察觉 —— 那是巧合，不是契约；没有这个观察点，
     * 派生键永远算不出第二次 `true`（第二条消息发出去也不会再 tick）。
     */
    private var runningId by mutableStateOf<String?>(null)
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

    /**
     * 真源 RPC 的发射作用域。默认跑 [Dispatchers.Default]：网络/挂起逻辑不占调用者线程。
     * 测试注入 `Unconfined` 让假 gateway 的记录在 `submit()` 返回前确定。
     *
     * **必须可销毁**：页面离开组合时 `ChatState.dispose()` 取消这个作用域，在途回包
     * 因此不会再写进一个已经下屏的对象（见 [disposed]）。
     */
    private val wireScope = CoroutineScope(SupervisorJob() + wireDispatcher)

    /** 页面是否已经离开组合。**所有异步回写**都先看这一位，销毁后一个字段都不许改。 */
    @Volatile
    private var disposed = false

    /** 测试 / 宿主可见的销毁态（在途回写被丢弃的判据）。 */
    val isDisposed: Boolean get() = disposed

    /**
     * `client_msg_id` 的**唯一**铸造器：submit / edit_resubmit / regenerate 全部经
     * [newClientMsgId]，绝不从 `items.count { … }` 之类的行数派生（那会让两个动作撞在
     * 同一个幂等键上）。
     */
    private val clientMsgIds = ClientMsgIds(clock)

    private fun newClientMsgId(): String = clientMsgIds.next()

    /**
     * 真源 P1-2 · 状态容器的**写锁**。`wireScope` 跑在 [Dispatchers.Default]，而事件投影
     * 跑在页面的收集协程（Main）；两边都写 [items] / [queue] / [LiveTranscript]，而后者是
     * 普通 `LinkedHashMap`。所有**回包之后的回写**与**事件投影**都在这把锁内进行，网络
     * 调用留在锁外（否则一次慢 RPC 会卡住整条事件流，且测试里的同步回调会自锁）。
     *
     * 锁不是重入的：`submitOverWire` / `cancelQueuedOverWire` 只在 `turns.*` **返回之后**
     * 取锁 —— 假 gateway 在调用里同步投递事件（真实 core 的顺序：先 emit 后回包）时，
     * 事件侧的 `withLock` 仍然拿得到。
     */
    private val stateMutex = Mutex()

    /**
     * P0-2 · `turn_id → client_msg_id`（`ui/submit` 回包与 `turn.queued` 共用）。
     *
     * 用 snapshot map（Compose 的 map 每次操作内部同步）而不是裸 `HashMap`：P1-2 的
     * 跨线程写点是「Default 线程写、Main 线程读」。
     */
    private val clientMsgIdByTurnId = mutableStateMapOf<String, String>()

    /**
     * P0-2 · `client_msg_id → core 的 user_message_id`。`ui/submit` 的**回包不带**
     * message id（只有 turn_id / queue_pos），真 id 只在 `turn.queued.user_message_id` 里；
     * 拿到之前本地回显行的 id 是客户端编的 `m-<session>-<clientMsgId>`，拿它去
     * `message/edit_resubmit` 必然 `NOT_FOUND`。回填后这个映射也用来解析「这条往返消息
     * 当前的真 id」（编辑成功会铸新的 message id）。
     */
    private val realUserMessageIdByClientMsgId = mutableStateMapOf<String, String>()

    /**
     * P0-3 · 已经收到 `turn.cancelled` 的回合。core 在 `queue.cancel` **返回之前**先发这个
     * 事件，所以事件可能跑赢回包：回包落地时必须知道「这条已被取消」，不能补一条排队条目。
     * 有界（每个被取消的回合一个 id，进程内 FIFO 之外的活回合有限）。
     */
    private val cancelledTurnIds = mutableStateMapOf<String, Unit>()

    /** 真源发送在途去重：回包（成功/失败）之前不再接受第二次发送。 */
    private var submitInFlight by mutableStateOf(false)

    /** 真源 `ui/queue.cancel` 在途的 client_msg_id（重复点 ✕ 只发一次）。 */
    private val queueCancelInFlight = mutableStateMapOf<String, Unit>()

    /**
     * 最近一个 `turn.started` 的回合 —— `ui/interrupt` 的目标。
     *
     * **必须是 snapshot state（与 [runningId] 同型）**：它经 [isStreaming] →
     * [streamingTickerKey] / [inputMode] 两个 `derivedStateOf` 被组合读取。早先是普通 `var`，
     * 派生状态**观察不到**它的写入，只能靠「同一事件顺带写了 `items`」碰巧重算：
     *  - `turn.started` 恰恰一个字节都不动 `items`（投影器对 started 是刻意的 no-op），
     *    于是开始回合后 `isStreaming` 仍是 false —— composer 把 ■ 画成 ↑（发不出去也停不下）；
     *  - 收尾事件若没有可见行变化（行已是终态 / 被 else 吞掉），`runningTurnId = null`
     *    同样不会被察觉，界面永久停在 Streaming。
     *
     * 加上 `turn.completed` / `turn.failed` / `turn.interrupted` 的收尾，这条边沿就是
     * 「回到 Idle」的唯一真源（`R3InputModeTest` 用真实组合钉住）。
     */
    private var runningTurnId by mutableStateOf<String?>(null)

    private var interruptInFlight: String? = null

    val isStreaming: Boolean get() = runningId != null || (isRealSource && realRunningTurnId() != null)

    /**
     * H2 · 页面「流式 ticker」的**唯一键** —— 稳定布尔，不是 `items`。
     *
     * `LaunchedEffect` 的 key 是在调用方的组合作用域里求值的。旧实现直接把 [isStreaming]
     * 写进 key，而它在真源上要走 [realRunningTurnId] 的 `items.lastOrNull { … }`：
     * 每一条 `turn.delta`（每 45ms 一次）写一次 `items` ⇒ 读键的那个作用域整页失效 ⇒
     * `Column(verticalScroll)` 的 content lambda + `items.forEach` 重新跑一遍（H1 放大器）。
     *
     * [derivedStateOf] 把那一次扫描关进派生状态的**计算**里：`items` 写入只让派生记录失效，
     * 只有布尔值真的翻转（回合开始 / 结束）才通知读者。读者因此不再随 delta 重组，键也就
     * 不会在 delta 上抖动 —— ticker 协程只在「开始 / 结束」这两个边沿重建。
     */
    val streamingTickerKey: Boolean get() = streamingKey.value

    private val streamingKey: State<Boolean> = derivedStateOf { isStreaming }

    /**
     * H2 · 页面「上传 ticker」的**唯一键** —— chip 数量（Int），取值与旧 key `staged.size`
     * 逐字相同，区别只在读法：旧写法把 `SnapshotStateList` 的读留在调用方作用域里，于是
     * 每条进度写入（`staged[i] = …`，每 160ms 一次）都让整页失效 —— 哪怕 size 一个都没变。
     * 派生状态把读关在计算里，进度写入只让计算失效、不让读者失效。
     */
    val uploadTickerKey: Int get() = uploadKey.value

    private val uploadKey: State<Int> = derivedStateOf { staged.size }

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
        get() = !submitInFlight && (
            draft.isNotBlank() ||
                staged.any { it.attachment.state == AttachmentState.Uploaded }
            )

    /**
     * H2 跟进 · composer 的输入态也是**派生标量**。
     *
     * 旧读法让 `skin.InputBar(mode = state.inputMode)` 直接落在页面组合作用域里，
     * 而 [isStreaming] 在真源上要扫 `items`：每条 delta（45ms）都会让 composer 重组一次。
     * 把计算关进 [derivedStateOf] 后，delta 只让派生记录失效；只有枚举真的翻转
     * （Idle ↔ Composing ↔ Streaming）才通知 InputBar。
     */
    val inputMode: InputMode get() = inputModeState.value

    private val inputModeState = derivedStateOf {
        when {
            isStreaming -> InputMode.Streaming
            canSend -> InputMode.Composing
            else -> InputMode.Idle
        }
    }

    /** H2 跟进 · 最后一条消息的正文长度；不分配 `messages` 整表（每 delta 一次 N 元素 List）。 */
    val lastMessageBodyLength: Int get() = lastBodyLengthState.value

    private val lastBodyLengthState = derivedStateOf {
        items.lastOrNull { it is ChatItem.Message }?.let { (it as ChatItem.Message).message.bodyText.length } ?: 0
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
     * 快照里的 `pending_inputs` 也在这里落地（[mergeSnapshotInputs]）：重进会话 / 断线重连
     * 之后问答卡必须回来，否则回合永远停在 `waiting_input` 而客户端再也拿不到这张卡。
     *
     * @return 是否成功进入 Ready（假源直接返回 false：它没有读通道，也不需要）。
     */
    suspend fun loadHistory(): Boolean {
        if (!isRealSource) return false
        if (disposed) return false
        val channel = transcript
        if (channel == null) {
            phase = ChatPhase.Failed
            loadError = NO_CHANNEL_MESSAGE
            return false
        }
        phase = ChatPhase.Loading
        loadError = null
        return try {
            // 一次读拿全：消息 + 未作答问答（两次 attach = 同连接双 sink，见 ChatHistory）。
            val snapshot = channel.snapshot(sessionId)
            // 快照在途期间页面可能已经销毁（切会话 / 退详情）：一个字段都不许写。
            if (disposed) return false
            stateMutex.withLock {
                if (disposed) return false
                live.seed(snapshot.messages)
                // 用投影器的行序重建：快照行 + 加载期间已经到达的 live 行，一次到位、不叠行。
                // A3 · 已投影的工具卡一并重建（按 call_id 幂等；同回合的 live 行可定位时插回其行后）。
                items.clear()
                live.messages.forEach { items += ChatItem.Message(it) }
                live.toolItems.forEach { upsertToolItem(it.call) }
                // 快照里的 `pending_inputs` 与消息同一份快照落地（f8）：重进会话 / 断线
                // 重连之后问答卡必须回来，否则回合永远停在 `waiting_input` 却再也拿不到卡。
                mergeSnapshotInputs(snapshot.pendingInputs)
                phase = ChatPhase.Ready
            }
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (disposed) return false
            // P1-1 · 被更新的 attach 淘汰的成功回包会以 CANCELLED 抛上来：那次 attach 的
            // core sink 已被摘掉、客户端也没有切过去。绝不能进 Ready（历史可能回得来，
            // 但实时流永远没有帧）；落成 Failed + 重试，让用户把这一屏重新挂上。
            val superseded = (error as? VadeException)?.code == VadeErrorCodes.CANCELLED
            phase = ChatPhase.Failed
            loadError = if (superseded) {
                SUPERSEDED_MESSAGE
            } else {
                error.message?.takeIf { it.isNotBlank() } ?: LOAD_FAILED_FALLBACK
            }
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
     *
     * P1-2 · 本函数**不自带锁**：生产里唯一的调用点是 [collectLiveEvents]，它在
     * [stateMutex] 内调用；测试直接调用时必须单线程（`ChatSendWireTest` 的 Unconfined
     * 正是这个形态）。
     */
    fun applyLiveEvent(event: VadeEvent): List<ChatMessage> {
        if (disposed) return emptyList()
        val snapshot = this.applyLive(live, event)
        projectTurnLifecycle(event)
        projectApprovalLifecycle(event)
        projectInputLifecycle(event)
        return snapshot
    }

    /**
     * turn.* 事件 → 本地回合状态的收尾。只服务真源：
     *  - `turn.queued` — P0-2 · 用 `user_message_id` 回填本地回显行的 id（core 的真 id）；
     *  - `turn.started` — 记下运行中的回合（ui/interrupt 的目标），把它的排队条目摘掉
     *    （#N 徽标消失、气泡从 Queued 回到 Completed）；
     *  - `turn.completed` / `turn.interrupted` — 同样摘条目、清运行态；
     *  - `turn.cancelled` — 摘条目**并删掉这条 submit 的气泡**（[settleCancelled]）；
     *  - `turn.failed` — 摘条目之外，把失败行按四类错误口径改成可读文案（[makeFailureReadable]）。
     */
    private fun projectTurnLifecycle(event: VadeEvent) {
        if (!isRealSource) return
        when (event) {
            is TurnQueued -> adoptRealUserMessageId(event.payload)
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
                // 回合没了，还开着的问答也就没有意义了（resolved 丢了 / 被直接杀回合）。
                dropInputsForTurn(event.payload.turnId, INPUT_TURN_ENDED_NOTICE)
            }
            is TurnCancelled -> {
                if (runningTurnId == event.payload.turnId) runningTurnId = null
                // main · 摘排队条目，并删掉这条 submit 的本地回显气泡（core 先发事件、
                // 后回 queue.cancel，屏上不许留「已送达」的幽灵）。
                settleCancelled(event.payload.turnId)
                // f8 · 回合没了，还开着的问答也就没有意义了（resolved 丢了 / 被直接杀回合）。
                dropInputsForTurn(event.payload.turnId, INPUT_TURN_ENDED_NOTICE)
            }
            is TurnFailed -> {
                if (runningTurnId == event.payload.turnId) runningTurnId = null
                if (interruptInFlight == event.payload.turnId) interruptInFlight = null
                dequeueTurn(event.payload.turnId)
                makeFailureReadable(event.payload.turnId)
                dropInputsForTurn(event.payload.turnId, INPUT_TURN_ENDED_NOTICE)
            }
            else -> Unit
        }
    }

    /**
     * P0-2 · `turn.queued` 到达时用 core 的 `user_message_id` **换掉本地回显行的 id**。
     *
     * 本地回显的气泡 id 是客户端编的（`m-<session>-<clientMsgId>`），core 从没发过它；
     * 拿它去 `message/edit_resubmit` 必带一个不存在的 message id。`ui/submit` 的回包不带
     * message id，真值只在这里，所以按 `client_msg_id` 关联回填。
     *
     * 同时把这一行补种进投影器：`message.edited` 按 core 的 id 定位，不种就只会记一条
     * `UnknownEditTarget` 异常、界面停在旧文案。
     *
     * `edit_resubmit` / `regenerate` 的 `turn.queued` 也带 `user_message_id`，但它们的
     * `client_msg_id` 是**新的**（本地没有这个键的行），因此天然 no-op。
     */
    private fun adoptRealUserMessageId(payload: TurnQueuedPayload) {
        val clientMsgId = payload.clientMsgId ?: return
        val realId = payload.userMessageId?.takeIf { it.isNotBlank() } ?: return
        if (realUserMessageIdByClientMsgId[clientMsgId] == realId) return
        val row = items.firstOrNull {
            it is ChatItem.Message && it.message.role == ChatRole.User && it.message.clientMsgId == clientMsgId
        } as? ChatItem.Message ?: return
        realUserMessageIdByClientMsgId[clientMsgId] = realId
        if (row.message.id == realId) return
        val index = items.indexOf(row)
        if (index < 0) return
        val renamed = row.message.copy(id = realId)
        items[index] = ChatItem.Message(renamed)
        // 插在它自己的助手行之前（见 [LiveTranscript.insertBefore]）；助手行未到时即追加。
        live.insertBefore(renamed, LiveTranscript.liveRowId(payload.turnId))
        // 排队条目与内联编辑器手里可能还攥着旧 id：一起换掉，别让它们指向一个不存在的行。
        for (i in queue.indices) {
            if (queue[i].clientMsgId == clientMsgId && queue[i].messageId != realId) {
                queue[i] = queue[i].copy(messageId = realId)
            }
        }
        if (editingId == row.message.id) editingId = realId
    }

    /**
     * 回合收尾：排队条目摘掉，气泡若还穿着 Queued 就回到 Completed（消息本身是送达的）。
     *
     * 行 id 按 `client_msg_id` 现查（P0-2 的回填会把本地 id 换成 core 的），条目里的
     * `messageId` 只作兜底。
     */
    private fun dequeueTurn(turnId: String) {
        val clientMsgId = clientMsgIdByTurnId[turnId] ?: return
        val entry = queue.firstOrNull { it.clientMsgId == clientMsgId } ?: return
        queue.removeAll { it.clientMsgId == clientMsgId }
        setBubbleState(
            echoRowId(clientMsgId) ?: entry.messageId,
            from = MessageState.Queued,
            to = MessageState.Completed,
        )
    }

    /**
     * P0-3 · `turn.cancelled`：core 在 `queue.cancel` 返回**之前**发这条事件，所以事件
     * 先到、回包后到是**正常时序**，不是竞态的边缘。旧实现事件里只摘条目（气泡改成
     * Completed），回包路径 `if (!queue.remove(entry)) return` 又提前返回 —— 删气泡那两行
     * 永远执行不到，屏上留下一条「已送达」的幽灵。
     *
     * 这里统一收口：记下已取消的回合（回包落地时用）、摘条目、删 submit 的气泡。
     */
    private fun settleCancelled(turnId: String) {
        cancelledTurnIds[turnId] = Unit
        val clientMsgId = clientMsgIdByTurnId[turnId] ?: return
        val wasSubmit = queue.firstOrNull { it.clientMsgId == clientMsgId }?.reason == "submit" ||
            items.any {
                it is ChatItem.Message && it.message.role == ChatRole.User &&
                    it.message.clientMsgId == clientMsgId
            }
        queue.removeAll { it.clientMsgId == clientMsgId }
        if (wasSubmit) dropSubmitEcho(clientMsgId)
    }

    /** 本地回显行**当前**的 id（P0-2 回填后是 core 的 id）；没回填过就是 null。 */
    private fun echoRowId(clientMsgId: String): String? =
        items.firstOrNull {
            it is ChatItem.Message && it.message.role == ChatRole.User && it.message.clientMsgId == clientMsgId
        }?.let { (it as ChatItem.Message).message.id }

    /**
     * 删掉一条 `ui/submit` 的本地回显气泡（按 `client_msg_id`，不按 id —— 回填会换 id）。
     * 只有用户气泡带 `clientMsgId`，快照行不带，所以不会误删 core 的历史。
     */
    private fun dropSubmitEcho(clientMsgId: String) {
        items.removeAll {
            it is ChatItem.Message && it.message.role == ChatRole.User && it.message.clientMsgId == clientMsgId
        }
        realUserMessageIdByClientMsgId.remove(clientMsgId)
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
     *
     * P0-1 / f13-P1-2 · 只投影**本会话**的帧。`VadeEvent.sessionId` 是协议层的归属：
     *  - C-06 core 的每一帧都带信封 `session_id`；
     *  - 老 core（无信封）的帧由协议层在**稳定态**（attached == visible）归到当前
     *    attached 会话，切换/失败窗口内的帧根本不进 `events`；
     *  - 只有非协议源（测试夹具）才会给出 `sessionId == null`，按旧行为接收。
     *
     * attach B 失败后协议层会把可见门回退到旧会话 A 以维持重连/水位，A 的帧因此仍会
     * 出现在共享事件流上 —— 屏上是失败的 B，这一道过滤保证 B 的线程**永远**不吃 A 的
     * 帧（也不会被 A 的 seq 毒化 [LiveTranscript] 的 bootstrap 起点）。
     */
    suspend fun collectLiveEvents() {
        if (disposed) return
        val channel = transcript ?: return
        try {
            channel.events().collect { event ->
                if (disposed) return@collect
                if (event.sessionId != null && event.sessionId != sessionId) return@collect
                // 无归属的帧（只有非协议源、或老 core 尚未 attach 的兜底槽位会给出）无法
                // 证明属于本会话：加载完成前一律不投影 —— 否则一个刚挂上、还没 attach 成功
                // 的 ChatState 会把别的会话的帧吃进自己的 transcript。
                if (event.sessionId == null && phase != ChatPhase.Ready) return@collect
                // P1-2 · 事件投影与 RPC 回包的写回共用一把锁：投影器内部是普通
                // LinkedHashMap，`items`/`queue` 也是「读在 Main、写在 Default」。
                stateMutex.withLock { applyLiveEvent(event) }
            }
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

    // --- H4 · 「每行都要的标量」一次算清 ------------------------------------------
    //
    // [canEditMessage] / [queuePosition] 是**每行**每次重组都要问一次的问题
    // （`ChatThread.kt` 的 `MessageRow` 两处）。旧实现：前者先要 `messages`
    // （`items.mapNotNull` —— 每次访问分配一个 N 元素 List）再 `lastOrNull` 扫一遍，
    // 后者扫两遍 `queue` ⇒ 一次消息列重组 = (N/2) × O(N) 的分配与元素访问（O(N²/2)），
    // 流式期间每秒 22 次；N=200 的长会话就是每帧几十万次元素访问。
    //
    // 改成两个 [derivedStateOf]：`items` / `queue` 一变**重算一次**，之后每行都是一次
    // Map 命中（O(1)）。派生状态只在结果**真的不同**时通知读者，所以同一次重组里 N 行
    // 读到的都是同一份缓存，流式的 delta 也不会因为「顺手问了排队位」而让每行重组。

    /**
     * `messageId → 1-based 排队位`（只含排队中的行）。语义逐字照抄旧的 [queuePosition]：
     *  - 同一 `messageId` 出现多次时**第一条**说了算（旧实现 `firstOrNull` + `indexOfFirst`），
     *    所以这里不能用 `associate`（重复键保留最后一个）；
     *  - [QueuedTurn.queuePos] > 0（core 的真值）优先，否则用本地下标 + 1（假源兜底）。
     */
    private val queuePositionsState: State<Map<String, Int>> = derivedStateOf {
        val positions = HashMap<String, Int>(queue.size)
        queue.forEachIndexed { index, turn ->
            if (!positions.containsKey(turn.messageId)) {
                positions[turn.messageId] = if (turn.queuePos > 0) turn.queuePos else index + 1
            }
        }
        positions
    }

    /**
     * 最后一条用户消息的 id —— **唯一**可编辑的目标（`edit_resubmit` 只许改最后一条）。
     *
     * 一次 `items` 扫描，不建 `messages` 那个中间 List：老实现走 `messages.lastOrNull`，
     * 每次调用都先 `mapNotNull` 出整张表再丢掉。
     */
    private val lastUserMessageIdState: State<String?> = derivedStateOf {
        val row = items.lastOrNull { it is ChatItem.Message && it.message.role == ChatRole.User }
        (row as? ChatItem.Message)?.message?.id
    }

    /**
     * Only the **last** user message is editable (`edit_resubmit` notes: "only the last
     * user message is editable"), and never a queued row (its turn does not exist yet).
     *
     * H4 · 两个判据都来自 [queuePositionsState] / [lastUserMessageIdState] 的缓存，本函数
     * 自己不再扫表、不再建 List —— 它是每行每帧被问一次的，不是用户动作。
     *
     * P0-2 · 真源下**还没拿到 core 的 message id** 的本地回显行不可编辑：拿
     * `m-<session>-<clientMsgId>` 去 `message/edit_resubmit` 只会换回 `NOT_FOUND`。
     * `turn.queued.user_message_id` 一到（[adoptRealUserMessageId]）就换 id，这条随即放开。
     */
    fun canEditMessage(messageId: String): Boolean {
        if ((queuePositionsState.value[messageId] ?: 0) > 0) return false
        if (messageId in unresolvedEchoIdsState.value) return false
        val lastUserId = lastUserMessageIdState.value ?: return false
        return messageId == lastUserId
    }

    /**
     * 还没被 core 命名过的本地回显行 id（`clientMsgId` 有值、真 id 没回填）。
     *
     * 只真源成立：假源的气泡本来就没有 server 侧对应物，「原样透传」是它的语义。
     * 派生状态把整表扫描关进计算里，`canEditMessage` 每行只做一次 Set 命中。
     */
    private val unresolvedEchoIdsState: State<Set<String>> = derivedStateOf {
        if (!isRealSource) return@derivedStateOf emptySet()
        val ids = HashSet<String>()
        items.forEach { item ->
            val message = (item as? ChatItem.Message)?.message ?: return@forEach
            val clientMsgId = message.clientMsgId ?: return@forEach
            if (!realUserMessageIdByClientMsgId.containsKey(clientMsgId)) ids += message.id
        }
        ids
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
        // P0-2 · 真源的行上可能是 P0-2 回填前的本地 id：出网前解析成 core 的真 id
        // （编辑会铸新的 message id，映射在成功后更新）。
        val wireMessageId = original.clientMsgId
            ?.let { realUserMessageIdByClientMsgId[it] }
            ?: messageId
        val clientMsgId = newClientMsgId()
        val receipt = try {
            turns.editResubmit(sessionId, wireMessageId, listOf(ContentPart.Text(text)), clientMsgId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastNotice = "重发失败：${error.message ?: "通道异常"}（原文未变，可重试）"
            return false
        }
        if (isRealSource && original.clientMsgId != null && receipt.newMessageId.isNotBlank()) {
            // core 把旧 message 指向新 id（rows are immutable）：下一次编辑的目标是新的这条，
            // 而屏幕上仍是同一行 —— 只更新解析表，不动列表身份。
            realUserMessageIdByClientMsgId[original.clientMsgId] = receipt.newMessageId
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
                    turnId = receipt.turnId,
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
        val clientMsgId = newClientMsgId()
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
                        turnId = receipt.turnId,
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
                if (uploadId !in cancelledUploads && !disposed) updateUploadProgress(uploadId, progress)
            }
            if (uploadId in cancelledUploads || disposed) return // late completion is dropped
            completeUpload(uploadId, blobId, request)
        } catch (error: CancellationException) {
            throw error // structured cancellation propagates; the chip is already gone
        } catch (error: Exception) {
            if (uploadId in cancelledUploads || disposed) return
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
        if (disposed) return null
        // 回包未归：同一时刻只允许一次 ui/submit 在途，双击发送不会铸出第二个气泡。
        if (submitInFlight) return null
        val text = draft.trim()
        val ready = staged.filter { it.attachment.state == AttachmentState.Uploaded }
        if (text.isEmpty() && ready.isEmpty()) return null
        val clientMsgId = newClientMsgId()
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
        lastNotice = null

        if (isRealSource) {
            // 真源：出网。**草稿留到回包**（成功才清、失败退回）—— 发送失败时用户的话
            // 必须还在输入框里，不能让一次网络抖动吃掉半天的草稿。
            submitInFlight = true
            wireScope.launch { submitOverWire(messageId, clientMsgId, parts, text) }
            return clientMsgId
        }
        // 假源：本地脚本回合没有回包，按「立即成功」处理（行为与接线前逐字节相同）。
        draft = ""
        if (busy) {
            queue += QueuedTurn(messageId = messageId, clientMsgId = clientMsgId, text = text)
        } else {
            startAssistantTurn(messageId)
        }
        return clientMsgId
    }

    /**
     * 真源 `ui/submit` 的出网与回包落地。回包规则：
     *  - `queue_pos > 0` 且该回合还没有可见的 live 行 → 建排队条目（#N = 真 queue_pos，
     *    `turn_id` 留给 `ui/queue.cancel`）;
     *  - `queue_pos == 0` → core 立即开始：清掉本地乐观置队的痕迹，交给事件流；
     *  - 回包晚于回合的可见行（事件跑赢了 RPC 回包）→ **不补**排队痕迹，只把可能存在的
     *    乐观 Queued 纠正回去 —— 顺序反转不许在屏上撒谎。
     *
     * 草稿：**只有成功回包才清空**（且只在用户没有继续编辑时）；失败把正文退回输入框
     * （见 [restoreDraftAfterFailure]），气泡同时挂上可读的失败徽标。
     */
    private suspend fun submitOverWire(
        messageId: String,
        clientMsgId: String,
        parts: List<ContentPart>,
        text: String,
    ) {
        val receipt = try {
            turns.submit(sessionId, parts, clientMsgId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            stateMutex.withLock {
                if (disposed) return
                submitInFlight = false
                markSubmitFailed(echoRowId(clientMsgId) ?: messageId, error)
                restoreDraftAfterFailure(text)
            }
            return
        }
        stateMutex.withLock {
            if (disposed) return
            submitInFlight = false
            clearDraftAfterSuccess(text)
            clientMsgIdByTurnId[receipt.turnId] = clientMsgId
            if (cancelledTurnIds.containsKey(receipt.turnId)) {
                // P0-3 · 取消事件跑赢了回包：这条回合已经不在了，不许补一条「排队中」，
                // 更不许把气泡留在屏上（它永远不会跑）。
                queue.removeAll { it.clientMsgId == clientMsgId }
                dropSubmitEcho(clientMsgId)
                return
            }
            val row = live.messageById(LiveTranscript.liveRowId(receipt.turnId))
            if (receipt.queuePos > 0 && row == null) {
                val existing = queue.firstOrNull { it.clientMsgId == clientMsgId }
                if (existing == null) {
                    queue += QueuedTurn(
                        messageId = echoRowId(clientMsgId) ?: messageId,
                        clientMsgId = clientMsgId,
                        text = bubbleText(echoRowId(clientMsgId) ?: messageId),
                        queuePos = receipt.queuePos,
                        turnId = receipt.turnId,
                    )
                } else if (existing.queuePos != receipt.queuePos) {
                    queue[queue.indexOf(existing)] = existing.copy(
                        queuePos = receipt.queuePos,
                        turnId = receipt.turnId,
                    )
                }
                setBubbleState(echoRowId(clientMsgId) ?: messageId, from = null, to = MessageState.Queued)
            } else {
                queue.removeAll { it.clientMsgId == clientMsgId }
                setBubbleState(
                    echoRowId(clientMsgId) ?: messageId,
                    from = MessageState.Queued,
                    to = MessageState.Completed,
                )
            }
        }
    }

    /**
     * 成功回包后清草稿：只在用户**没有继续编辑**时清。若回包到达前用户已经改了输入框，
     * 那几行新文字是比「清空」更重要的事实，保留它们。
     */
    private fun clearDraftAfterSuccess(sent: String) {
        if (preset != ChatPreset.Live) return
        if (draft.trim() == sent) draft = ""
    }

    /**
     * 失败回写：把这次没发出去的正文退回输入框。
     *
     * 只在输入框为空时退回 —— 失败回包到达前用户可能已经开始写下一句，覆盖那是更糟的
     * 数据丢失。失败的气泡保留正文与重试入口，因此不退回也不算丢字。
     */
    private fun restoreDraftAfterFailure(sent: String) {
        if (preset != ChatPreset.Live) return
        if (sent.isEmpty()) return
        if (draft.isBlank()) draft = sent
    }

    /** 发送失败不能吞：气泡就地 Failed + 四类口径的可读文案（正文仍是用户自己的话）。 */
    private fun markSubmitFailed(messageId: String, error: Exception) {
        setBadge(messageId, wireFailureBadge("发送失败", error))
        setBubbleState(messageId, from = null, to = MessageState.Failed)
    }

    /**
     * FR-8.36 `ui/queue.cancel` — 取消**排队中**的回合。
     *
     * 真源：先出网，**成功才**删本地条目。失败时条目留在队列里（#N 不动），状态行给出
     * 四类口径的可读原因 —— 屏幕上消失、core 里仍在排队的幽灵回合比报错更糟。
     * `turn_id` 取自创建该条目的回包（[QueuedTurn.turnId]）；拿不到就明确拒绝，不编 id。
     *
     * 假源：没有出网回合，保留原来的纯本地语义（删条目，立即前移）。
     *
     * @return 取消是否**已发起**（真源出网是异步的，完成以队列条目消失为准）。
     */
    fun cancelQueued(clientMsgId: String): Boolean {
        val entry = queue.firstOrNull { it.clientMsgId == clientMsgId } ?: return false
        if (isRealSource) {
            if (disposed) return false
            val turnId = entry.turnId
            if (turnId == null) {
                lastNotice = "这条排队还没有 turn_id（等回包后再取消）"
                return false
            }
            if (!queueCancelInFlight.containsKey(clientMsgId)) {
                queueCancelInFlight[clientMsgId] = Unit
            } else {
                return false
            }
            wireScope.launch { cancelQueuedOverWire(entry, turnId) }
            return true
        }
        dropQueuedLocally(entry)
        return true
    }

    /**
     * `ui/queue.cancel` 的出网与成功回写。失败 = 本地条目**保留** + 可见原因；
     * 页面销毁后的晚到回包一个字段都不写。
     *
     * 2026-11 · 失败文案以**事件为准**：`turn.cancelled` 先到（core 处理成功、只是回包丢了）
     * 时条目已经不在队列里，此时再写「这条仍在队列里」就是撒谎。
     */
    private suspend fun cancelQueuedOverWire(entry: QueuedTurn, turnId: String) {
        try {
            turns.cancelQueued(sessionId, turnId)
        } catch (error: CancellationException) {
            queueCancelInFlight.remove(entry.clientMsgId)
            throw error
        } catch (error: Exception) {
            queueCancelInFlight.remove(entry.clientMsgId)
            stateMutex.withLock {
                if (disposed) return
                val stillQueued = queue.any { it.clientMsgId == entry.clientMsgId }
                val badge = wireFailureBadge("取消排队失败", error)
                lastNotice = if (stillQueued) "$badge；这条仍在队列里" else badge
            }
            return
        }
        queueCancelInFlight.remove(entry.clientMsgId)
        stateMutex.withLock {
            if (disposed) return
            dropQueuedLocally(entry)
        }
    }

    /**
     * 本地删条目并同步气泡状态。
     *
     * A `submit` entry owns the bubble it created — cancelling deletes it. An
     * `edit_resubmit` entry only queues the rerun of an EXISTING row: cancelling
     * drops the queued turn and the (edited) row stays.
     *
     * P0-3 · 两步都**不依赖条目还在队列里**：`turn.cancelled` 事件通常先于回包到达，
     * 条目此时已被 [settleCancelled] 摘掉；旧写法 `if (!queue.remove(entry)) return`
     * 会因此跳过删气泡，留下一条看起来「已送达」的幽灵。
     * P2-2 · 按 `client_msg_id` 删，不用 data class 结构相等（任何一次 `.copy()` 都会让它
     * 删不掉）。
     */
    private fun dropQueuedLocally(entry: QueuedTurn) {
        queue.removeAll { it.clientMsgId == entry.clientMsgId }
        if (entry.reason == "submit") dropSubmitEcho(entry.clientMsgId)
    }

    /**
     * 1-based position shown on the badge; 0 when not queued.
     * g-chat-send · 真源条目带 core 的 `queue_pos`（[QueuedTurn.queuePos] > 0）时**只认真值**
     * —— 本地计数只在假源条目（queuePos == 0）上兜底，行为与接线前逐字节一致。
     *
     * H4 · 一次 Map 命中（[queuePositionsState]），不再两遍扫 `queue`：本函数由**每一行**
     * 每次重组调用一次（`MessageRow` 的排队徽标）。
     */
    fun queuePosition(messageId: String): Int = queuePositionsState.value[messageId] ?: 0

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
        if (disposed) return false
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
            if (disposed) return
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
     *
     * B6 · 本节从「纯本地投影」升级为**真出网**的入口：`decideApproval` 对带服务端
     * `request_id` 的卡片走 `turns.decideApproval`（乐观更新 + 失败回滚），没有
     * `request_id` 的卡片保持本地投影（见其 KDoc 的显式选择）。`projectApprovalLifecycle`
     * 消费 `turn.approval_required` / `turn.approval_resolved`（真源事件；假源一行不动）。
     */
    private val approvals = mutableStateMapOf<String, ApprovalOutcome>()
    private val approvalDetailRows = mutableStateListOf<String>()
    private val approvalDecidedAt = mutableMapOf<String, String>()
    private val screenOpenMark = TimeSource.Monotonic.markNow()

    // --- B6 · 审批投影的单一真相（callId → 服务端 request_id / expires_at） ------------

    /**
     * 卡片 id → 服务端 `request_id`。**唯一真相**：只由 `turn.approval_required` 事件写入；
     * `decideApproval` 出网时**只认这里的值**，绝不拿本地 callId 冒充服务端 id。夹具 /
     * 历史回放 / 假源卡片不在表里（`approvalRequestId` == null → 本地投影）。
     */
    private val approvalRequestIds = mutableStateMapOf<String, String>()

    /** 卡片 id → `turn.approval_required.expires_at`（unix ms）。超时 = deny 的读时依据。 */
    private val approvalExpiresAt = mutableStateMapOf<String, Long>()

    /** 本地乐观决定在途的卡片 —— resolved 覆盖或失败回滚后摘除（回滚不许覆盖服务端事实）。 */
    private val optimisticDecisions = mutableSetOf<String>()

    /** 服务端 `request_id` → 卡片 id。确定性：重放幂等，不依赖事件到达顺序。 */
    private fun approvalCallId(requestId: String): String = "approval:$requestId"

    /**
     * A3 · `request_id` → 卡片 id 的**正查表**（resolved / required 都用它）：绑定卡 =
     * 它那条工具卡的 `call_id`；映射不到的降级卡 = `approval:<request_id>`。
     */
    private val requestIdToCallId = HashMap<String, String>()

    /** [callId] 对应的服务端 `request_id`；null = 本地/夹具卡片（没有服务端审批请求）。 */
    fun approvalRequestId(callId: String): String? = approvalRequestIds[callId]

    /** Outcome of the inline approval card for [callId]; [ApprovalOutcome.Pending] until decided. */
    fun approvalOutcome(callId: String): ApprovalOutcome {
        val recorded = approvals[callId]
        if (recorded != null && recorded != ApprovalOutcome.Pending) return recorded
        // B6 · 超时 = deny（fail-closed）：`expires_at` 已过而未决定 → 读作 TimedOut。
        // 读时投影（不需要时钟驱动；resolved 事件晚到仍以服务端为准覆盖）。夹具卡片没有
        // expires_at，行为逐字节不变。
        val expiresAt = approvalExpiresAt[callId]
        if (expiresAt != null && epochNowMs() >= expiresAt) return ApprovalOutcome.TimedOut
        return recorded ?: ApprovalOutcome.Pending
    }

    /** The elapsed label rendered next to a resolved outcome; null while pending. */
    fun approvalDecidedLabel(callId: String): String? = approvalDecidedAt[callId]

    /** `⏷` detail expansion — tool name / batch / started-at rows under the args preview. */
    fun approvalDetailOpen(callId: String): Boolean = callId in approvalDetailRows

    fun toggleApprovalDetail(callId: String) {
        if (callId in approvalDetailRows) approvalDetailRows.remove(callId) else approvalDetailRows += callId
    }

    /**
     * ✓ 允许 / ✕ 拒绝 —— B6 起这是**真出网**的入口。
     *
     * ## 显式选择：没有 request_id 的卡片 = 只做本地投影
     * 夹具 / 历史回放 / 假源卡片没有服务端 `request_id`（[approvalRequestId] == null）：
     * 点下去只写本地投影、立刻返回 true，gateway **一次都不被碰**（出网 0 次，
     * `ApprovalWireTest` 的探针钉住）。这是刻意保留的演示路径 —— 行为与接线前逐字节一致。
     *
     * ## 有 request_id 的卡片 = 乐观更新 + 失败回滚
     * 点下去**先**本地变 Allowed/Denied（交互不等往返），然后经 [turns] 窄缝发
     * `approval/decide`（scope 恒为 [ApprovalScope.ONCE] —— UI 先只发 once，参数位已留）。
     * 失败回滚到 Pending 并给可读提示（[approvalFailureNotice]）：
     *  - `APPROVER_INVALID` → 浏览器会话不能审批，请在设备端操作（**设计行为**，不是 bug）；
     *  - `READ_ONLY_SESSION` → 只读会话；
     *  - `NOT_FOUND` → 请求已过期或不存在；
     *  - 超时/断线 → 可重试（回滚清掉在途标记，允许再点）。
     *
     * 双击/重复决定 = no-op：乐观写完成后 [approvalOutcome] 已不是 Pending，第二次点击在
     * 入口就被拒 —— **不发第二次帧**。`turn.approval_resolved` 到达时以服务端为准覆盖本地。
     */
    fun decideApproval(callId: String, decision: Decision): Boolean {
        val call = toolCallById(callId) ?: return false
        if (call.state != ToolRunState.PendingApproval) return false
        if (approvalOutcome(callId) != ApprovalOutcome.Pending) return false
        val requestId = approvalRequestIds[callId]
        approvals[callId] =
            if (decision == Decision.Approve) ApprovalOutcome.Allowed else ApprovalOutcome.Denied
        approvalDecidedAt[callId] = "T+${screenOpenMark.elapsedNow().inWholeSeconds}s"
        if (requestId == null) return true // 夹具/回放/假源：只做本地投影（KDoc 的显式选择）
        optimisticDecisions += callId
        val wireDecision =
            if (decision == Decision.Approve) ApprovalDecision.APPROVE else ApprovalDecision.DENY
        wireScope.launch { decideApprovalOverWire(callId = callId, requestId = requestId, decision = wireDecision) }
        return true
    }

    /** `approval/decide` 的出网与失败落地。成功时什么都不做（`turn.approval_resolved` 是唯一上屏者）。 */
    private suspend fun decideApprovalOverWire(callId: String, requestId: String, decision: ApprovalDecision) {
        try {
            turns.decideApproval(requestId = requestId, decision = decision, scope = ApprovalScope.ONCE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            rollbackApproval(callId = callId, error = error)
        }
    }

    /**
     * 失败回滚：只有**本地乐观值**还在（[optimisticDecisions] 命中）才回滚 —— 若
     * `turn.approval_resolved` 已先到（服务端已决），本地错误帧只是迟到，覆盖服务端事实
     * 就是撒谎。
     */
    private fun rollbackApproval(callId: String, error: Exception) {
        if (callId !in optimisticDecisions) return
        optimisticDecisions.remove(callId)
        approvals.remove(callId)
        approvalDecidedAt.remove(callId)
        lastNotice = approvalFailureNotice(error)
    }

    /** B6 四类口径：三类语义错误各有指定文案，其余按冻结表分四类给「可重试」。 */
    private fun approvalFailureNotice(error: Exception): String {
        val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "通道异常"
        return when {
            error is VadeException && error.code == VadeErrorCodes.APPROVER_INVALID ->
                "审批未送达：浏览器会话不能审批，请在设备端操作（设计行为，不是故障）"
            error is VadeException && error.code == VadeErrorCodes.READ_ONLY_SESSION ->
                "审批未送达：只读会话不能审批（$detail）"
            error is VadeException && error.code == VadeErrorCodes.NOT_FOUND ->
                "审批未送达：审批请求已过期或不存在，可刷新后重试"
            else -> "审批未送达（${loadErrorCategoryOf(error).label}）：$detail，可重试"
        }
    }

    /**
     * B6 · `turn.approval_required` / `turn.approval_resolved` → 审批卡投影。只服务真源：
     * 假源时间线一行不动（fixtures 自己拥有）。卡片 id 是 `approval:<request_id>`
     * （确定性 → 重放幂等）。**FULL args 不进卡片**：客户端没有脱敏引擎，渲染事件里
     * 的全量参数就是泄漏；卡体沿用 `args_preview` 口径（本轮事件没有它 → 占位行）。
     */
    private fun projectApprovalLifecycle(event: VadeEvent) {
        if (!isRealSource) return
        when (event) {
            is TurnApprovalRequired -> onApprovalRequired(event.payload)
            is TurnApprovalResolved -> onApprovalResolved(event.payload)
            else -> Unit
        }
    }

    /**
     * 审批请求 → 内联卡（待批准）。A3 归位：`turn.approval_required` **没有** `call_id`，
     * 绑定靠 [bindableToolCardId]（同一回合 + 同一工具名 + 尚未绑定请求 + 仍在运行的工具
     * 卡，取最近一条）——找到就把**那条工具卡原地升级**成审批卡（Running → PendingApproval，
     * risk 用事件真值），不另建卡；映射不到时保持 B6 的尾部降级行为，卡 id 仍是
     * `approval:<request_id>`（子代理审批广播在父会话流里、`turn_id` 是子回合，父会话
     * 没有它的工具卡 —— 正是走降级的一类）。FULL args 不进卡片（见 [projectApprovalLifecycle]）。
     */
    private fun onApprovalRequired(p: TurnApprovalRequiredPayload) {
        if (requestIdToCallId.containsKey(p.requestId)) return // 重放/重复帧：幂等，不建第二张卡
        val boundId = bindableToolCardId(p)
        val callId = boundId ?: approvalCallId(p.requestId)
        approvalRequestIds[callId] = p.requestId
        requestIdToCallId[p.requestId] = callId
        approvalExpiresAt[callId] = p.expiresAt
        if (boundId != null) {
            val index = items.indexOfFirst { it is ChatItem.Tool && it.call.id == boundId }
            if (index >= 0) {
                val call = (items[index] as ChatItem.Tool).call
                items[index] = ChatItem.Tool(call.copy(state = ToolRunState.PendingApproval, risk = p.risk))
            }
            return
        }
        items += ChatItem.Tool(
            ToolCall(
                id = callId,
                turnId = p.turnId,
                name = p.tool,
                // FULL args 故意不上屏（见 [projectApprovalLifecycle] KDoc）；
                // args_preview 由 core 在 turn.tool_call 里提供，本轮事件没有它。
                argsPreview = null,
                risk = p.risk,
                state = ToolRunState.PendingApproval,
            ),
        )
    }

    /**
     * A3 · 绑定目标：同一 `turn_id` + 同一工具名 + 还没绑定请求（`approvalRequestIds`
     * 不含它）+ 仍在 Running 的工具卡，取**最早**一条（事件到达序；审批请求按调用序
     * 发出，第 N 个请求对齐第 N 次调用 —— 同回合同工具并行调用时不会两个请求挤到
     * 同一张卡上）。找不到 → null（调用方走尾部降级）。
     */
    private fun bindableToolCardId(p: TurnApprovalRequiredPayload): String? {
        for (index in items.indices) {
            val item = items[index]
            if (item !is ChatItem.Tool) continue
            val call = item.call
            if (call.turnId == p.turnId &&
                sameToolName(call.name, p.tool) &&
                call.state == ToolRunState.Running &&
                !approvalRequestIds.containsKey(call.id)
            ) {
                return call.id
            }
        }
        return null
    }

    /**
     * `turn.tool_call.tool` 是模型侧广告名（`echo__boom`），而
     * `turn.approval_required.tool` 是 core 的 canonical `module_id/tool`
     * （`com.example.echo/boom`）。两者指同一次调用，但字面不相等；只比裸工具名，
     * 且仍在同一 `turn_id` + 未绑定 + Running 的约束内，不会把不同工具串到一起。
     */
    private fun sameToolName(left: String, right: String): Boolean =
        left == right || bareToolName(left) == bareToolName(right)

    private fun bareToolName(name: String): String =
        name.substringAfterLast('/').substringAfterLast("__")

    /**
     * `turn.approval_resolved` 到达 → **服务端为准**：覆盖乐观值，并记下 `by`/`scope`
     * （归属行形如「admin 批准 · 本次会话内有效」——by 与决定方向、scope 都是服务端事实）。
     * A3 · request → 卡片的解析走 [requestIdToCallId]：绑定卡（工具卡的 call_id）与
     * 降级卡（`approval:<request_id>`）同一条路径收口。未知 request（父会话根本没见过它）
     * 不建卡、不崩。
     */
    private fun onApprovalResolved(p: TurnApprovalResolvedPayload) {
        val callId = requestIdToCallId[p.requestId] ?: return
        val outcome = when (p.decision) {
            ApprovalDecision.APPROVE.raw -> ApprovalOutcome.Allowed
            ApprovalDecision.DENY.raw -> ApprovalOutcome.Denied
            else -> return // 契约外的决定值：宁可保留现状，也不编一个终态
        }
        approvals[callId] = outcome
        optimisticDecisions.remove(callId)
        approvalDecidedAt[callId] = approvalResolvedLabel(p)
    }

    /** resolved 的归属行：`by` + 决定方向 + `scope`，全部是服务端事实（整句模板，不产单汉字短字面量）。 */
    private fun approvalResolvedLabel(p: TurnApprovalResolvedPayload): String =
        "${p.by} ${decisionVerb(p.decision)} · ${scopeText(p.scope)}"

    private fun decisionVerb(raw: String): String =
        if (raw == ApprovalDecision.APPROVE.raw) "批准" else "拒绝"

    private fun scopeText(raw: String): String =
        if (raw == ApprovalScope.SESSION.raw) "本次会话内有效" else "仅本次有效"

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

    // --- ask-user 问答（RFC-0008） -------------------------------------------------
    //
    // core 的 `turn.input_required` 是**事件**，不是工具卡：它要求读者回答一个
    // 固定形状的问题（free_text / single_choice / multi_choice / confirm），回合停在
    // `waiting_input` 等这一帧。整条链路只有三件事：
    //  1. 事件进 → [pendingInputs] 多一条，页面弹模态卡（[currentInput] 是最上面那张）；
    //  2. 读者作答 / 取消 → 走 [ChatTurnGateway.answerInput] / [cancelInput]（`turn/input`
    //     与 `turn/input.cancel`），成功后本地摘卡；
    //  3. `turn.input_resolved` 到达 → **无论本地做过什么**，按 `input_id` 关掉那一张
    //     （别处作答 / 超时 / 会话关闭都靠它；这是契约里唯一的关窗信号）；
    //  4. 回合的**终态事件**（cancelled / failed / interrupted）按 `turnId` 清掉还没关的卡
    //     —— resolved 丢了（断线跳 seq / queue.cancel 直接杀回合）时的兜底；
    //  5. 重进会话 / 断线重连：`ui/attach` 快照的 `pending_inputs` 在 [loadHistory] 里
    //     按 `input_id` 幂等合并（[mergeSnapshotInputs]），卡从快照重建，不靠 live 重放。
    //
    // 输入框文字与勾选是纯客户端状态，不进 payload；`multi_choice` 的值按选项显示顺序
    // 归一（见 [PendingInput.selectedInOptionOrder]）。

    /** 等读者作答的请求，先到先答（`turn.input_resolved` 逐条关闭）。 */
    val pendingInputs = mutableStateListOf<PendingInput>()

    /** 当前该弹的那一张卡；null = 没有问答。 */
    val currentInput: PendingInput? get() = pendingInputs.firstOrNull()

    /** 是否有问答在等（列表徽标 / 无障碍提示用）。 */
    val hasPendingInput: Boolean get() = pendingInputs.isNotEmpty()

    /** 在途的作答 / 取消 —— 同一 `input_id` 不重复发帧（core 幂等，客户端也不浪费往返）。 */
    private val inputsInFlight = mutableSetOf<String>()

    /**
     * 已经**在本地收尾**过的 `input_id`（作答 / 取消 / 超时 / `turn.input_resolved` /
     * 回合终态清理）。
     *
     * 为什么需要它：快照是异步读的，读的过程中 live 事件可能已经把某张卡 resolved 掉了。
     * 那份快照里它仍然在 `pending_inputs` 里（快照点更早），如果只按「当前 pending 里有没有」
     * 去重，就会把一张已经关掉的卡**复活**。这张表只存 id、只增不减（会话级，量级 = 本次
     * 会话问过的次数），用来挡住这种迟到重建。
     */
    private val settledInputs = mutableSetOf<String>()

    /** 页面时钟（倒计时 / 超时的唯一来源）；[PendingInput] 的算法全部按它算。 */
    internal fun nowMs(): Long = clock()

    fun updateInputDraft(inputId: String, text: String) {
        val index = pendingInputs.indexOfFirst { it.inputId == inputId }
        if (index < 0) return
        pendingInputs[index] = pendingInputs[index].copy(draft = text)
    }

    /**
     * 勾选一个选项：`single_choice` = 替换（永远只有一个），`multi_choice` = 切换。
     * 其它 kind（free_text / confirm）没有选中态，直接 no-op。
     */
    fun toggleInputOption(inputId: String, optionId: String) {
        val index = pendingInputs.indexOfFirst { it.inputId == inputId }
        if (index < 0) return
        val input = pendingInputs[index]
        if (input.options.none { it.id == optionId }) return
        val next = when (input.kind) {
            InputKind.SINGLE_CHOICE -> listOf(optionId)
            InputKind.MULTI_CHOICE ->
                if (optionId in input.selected) input.selected - optionId else input.selected + optionId
            else -> return
        }
        pendingInputs[index] = input.copy(selected = next)
    }

    /**
     * 按 `kind` 组装 `turn/input.value`（RFC-0008 §7.1）：
     * free_text / single_choice → `string`，multi_choice → `string[]`，confirm → 两键
     * （[submitConfirm]），不在这里猜布尔。未填够 → null（[submitInput] 给出可读提示）。
     */
    fun answerValueOf(input: PendingInput): InputValue? = when (input.kind) {
        InputKind.FREE_TEXT -> input.draft.trim().takeIf { it.isNotEmpty() }?.let { InputValue.Text(it) }
        InputKind.SINGLE_CHOICE -> input.selectedInOptionOrder().firstOrNull()?.let { InputValue.Text(it) }
        InputKind.MULTI_CHOICE -> input.selectedInOptionOrder()
            .takeIf { it.isNotEmpty() }
            ?.let { InputValue.Multi(it) }
        // confirm 的答案是布尔，由两键直接给；不靠选中态推断。
        InputKind.CONFIRM, InputKind.UNKNOWN -> null
    }

    /** 主按钮可用态（空输入 / 未勾选 / confirm 不走这里）。 */
    fun canSubmitInput(inputId: String): Boolean {
        val input = pendingInputs.firstOrNull { it.inputId == inputId } ?: return false
        return answerValueOf(input) != null
    }

    /** 提交按钮 / ⌘Enter：组装值 → `turn/input`。成功后摘卡，失败留在原地可重试。 */
    fun submitInput(inputId: String): Boolean {
        val input = pendingInputs.firstOrNull { it.inputId == inputId } ?: return false
        val value = answerValueOf(input) ?: run {
            lastNotice = when (input.kind) {
                InputKind.FREE_TEXT -> "回答不能为空"
                InputKind.MULTI_CHOICE -> "至少勾选一项"
                InputKind.SINGLE_CHOICE -> "先选一个选项"
                else -> "这张问答卡不能这样提交"
            }
            return false
        }
        return sendAnswer(inputId, value)
    }

    /** confirm 的两键：`true` = 是，`false` = 否（wire 是布尔，不是字符串）。 */
    fun submitConfirm(inputId: String, confirmed: Boolean): Boolean {
        val input = pendingInputs.firstOrNull { it.inputId == inputId } ?: return false
        if (input.kind != InputKind.CONFIRM) return false
        return sendAnswer(inputId, InputValue.Flag(confirmed))
    }

    /**
     * 取消这张卡（`turn/input.cancel`，reason=`user_cancel`）：**只关问答，回合继续**。
     * 成功后摘卡 + 一行状态；失败留在原地（关掉了却没送到 = 屏上撒谎）。
     */
    fun cancelInput(inputId: String): Boolean {
        if (pendingInputs.none { it.inputId == inputId }) return false
        if (inputId in inputsInFlight) return false
        inputsInFlight += inputId
        wireScope.launch { cancelInputOverWire(inputId) }
        return true
    }

    /**
     * 到期（[PendingInput.expiresAtMs] 已过）：本地关窗 + 提示，**并补发一次幂等的**
     * `turn/input.cancel(reason = "timeout")`。
     *
     * 为什么还要发：本地判超时用的是**本机**对期限的解读（时钟偏差已由
     * [PendingInput.fromEvent] 兜底，但 core 的期限仍可能比本机早/晚）。不发这一帧，
     * core 会继续认为问题开着，直到它自己的 60–600s 超时 —— 期间这个回合在 UI 上没有卡，
     * 用户每次提交都被 core 拒绝。cancel 是幂等的：core 已经关掉这张卡时它按 `ok=false`
     * 静默（见 `TurnGatewayReal.cancelInput`），成功后 core 照常发 `turn.input_resolved`。
     *
     * 失败**不**上屏：卡已经按期限摘了，「已超时」才是读者该看到的读数；这一帧只是让 core
     * 提前收尾，送不到也不必再打扰。
     */
    fun timeoutInput(inputId: String): Boolean {
        if (pendingInputs.none { it.inputId == inputId }) return false
        // 已经有一帧在途（作答 / 取消）：那一帧到 core 之后这张卡自然关，不再补一发。
        val alreadyOnTheWire = inputId in inputsInFlight
        closeInput(inputId)
        lastNotice = INPUT_TIMEOUT_NOTICE
        if (alreadyOnTheWire) return true
        inputsInFlight += inputId
        wireScope.launch { cancelInputOverWire(inputId, INPUT_TIMEOUT_CANCEL_REASON, successNotice = null) }
        return true
    }

    private fun sendAnswer(inputId: String, value: InputValue): Boolean {
        if (inputId in inputsInFlight) return false
        inputsInFlight += inputId
        wireScope.launch { sendAnswerOverWire(inputId, value) }
        return true
    }

    private suspend fun sendAnswerOverWire(inputId: String, value: InputValue) {
        try {
            turns.answerInput(sessionId, inputId, value)
            closeInput(inputId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            inputsInFlight.remove(inputId)
            lastNotice = "回答未送达（${loadErrorCategoryOf(error).label}）：" +
                "${error.message?.takeIf { it.isNotBlank() } ?: "通道异常"}，可重试"
        }
    }

    private suspend fun cancelInputOverWire(
        inputId: String,
        reason: String = INPUT_CANCEL_REASON,
        // 成功时上屏的那句话；null = 调用方自己已经给过状态行（超时那条），不要覆盖。
        successNotice: String? = INPUT_CANCELLED_NOTICE,
    ) {
        try {
            turns.cancelInput(sessionId, inputId, reason)
            closeInput(inputId)
            successNotice?.let { lastNotice = it }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            inputsInFlight.remove(inputId)
            // 超时那一发是「顺带让 core 提前收尾」，送不到不覆盖「已超时」的读数。
            if (successNotice != null) {
                lastNotice = "取消未送达（${loadErrorCategoryOf(error).label}）：" +
                    "${error.message?.takeIf { it.isNotBlank() } ?: "通道异常"}，可重试"
            }
        }
    }

    /**
     * 唯一的摘卡路径：本地提交成功、取消成功、超时、`turn.input_resolved`、回合终态清理
     * 都走这里。摘掉的 id 记进 [settledInputs] —— 迟到的快照不许把它复活。
     */
    private fun closeInput(inputId: String) {
        inputsInFlight.remove(inputId)
        pendingInputs.removeAll { it.inputId == inputId }
        settledInputs += inputId
    }

    /**
     * `turn.input_required` → 弹卡；`turn.input_resolved` → 按 `input_id` 关卡。
     * 只服务真源（假源没有 core，也没有 input_id）；重放幂等：同一个 `input_id` 不会
     * 叠出第二张卡，迟到/重复的 resolved 也不会碰别的卡。
     */
    private fun projectInputLifecycle(event: VadeEvent) {
        if (!isRealSource) return
        when (event) {
            is TurnInputRequired -> onInputRequired(event.payload)
            is TurnInputResolved -> closeInput(event.payload.inputId)
            else -> Unit
        }
    }

    private fun onInputRequired(p: TurnInputRequiredPayload) {
        if (pendingInputs.any { it.inputId == p.inputId }) return
        if (p.inputId in settledInputs) return
        // 期限在「卡出现的那一刻」按本机时钟解释一次（时钟偏差 → 相对预算，见 PendingInput）。
        pendingInputs += PendingInput.fromEvent(p, clock())
    }

    /**
     * 把 `ui/attach` 快照里的 `pending_inputs` 合并进来（重进 / 重连 / 切走再切回）。
     *
     * 按 `input_id` 幂等：已经在屏上的不重复弹，**本地已经收过尾的不复活**（[settledInputs]）；
     * 顺序沿用快照（core 的到达顺序 = 先到先答）。快照是在详情页打开时读的，live 事件可能
     * 已经先到了 —— 两路合流后每一张卡都只出现一次。
     */
    private fun mergeSnapshotInputs(pending: List<TurnInputRequiredPayload>) {
        if (pending.isEmpty()) return
        val known = pendingInputs.mapTo(mutableSetOf()) { it.inputId }
        pending.forEach { payload ->
            if (payload.inputId in known) return@forEach
            if (payload.inputId in settledInputs) return@forEach
            // 快照里的卡可能是断线期间真的过期了：照 core 的期限判，过期立刻关（并补发 cancel）。
            pendingInputs += PendingInput.fromSnapshot(payload, clock())
            known += payload.inputId
        }
        // 快照里已经过期的卡不留在屏上：逐张按超时收尾（每张都会补一发幂等 cancel）。
        pendingInputs.filter { it.isExpired(clock()) }.map { it.inputId }.forEach { timeoutInput(it) }
    }

    /**
     * 回合终态（`turn.cancelled` / `turn.failed` / `turn.interrupted`）把该回合还开着的
     * 问答一并收起。
     *
     * 契约说 `turn.input_resolved` 会在终态事件之前到；但如果事件丢了（断线按 seq 跳号）、
     * 或者 `queue.cancel` 直接把回合杀了，卡片会永久停在屏上 —— 读者每次提交都被 core
     * 拒绝，UI 上只表现为「点了提交，卡消失，回合没动」。清理按 `request.turnId` 取值，
     * 所以同一个会话里别的回合的卡不受影响。
     */
    private fun dropInputsForTurn(turnId: String, notice: String) {
        val stale = pendingInputs.filter { it.request.turnId == turnId }
        if (stale.isEmpty()) return
        stale.forEach { closeInput(it.inputId) }
        lastNotice = notice
    }

    // --- preview --------------------------------------------------------------

    fun openPreview(image: ContentPart.Image) {
        preview = image
    }

    fun closePreview() {
        preview = null
    }

    /**
     * Messages only — tool rows are not part of the 「消息 → 输入区」 reading order.
     *
     * **每次访问都是一个新 List**（`items.mapNotNull`）。这是给用户动作路径用的
     * （`startEdit` / `confirmEdit` / `regenerate` / 队列投影，一次点击一次）；**每行每帧**
     * 的热路径走 [canEditMessage] / [queuePosition] 的派生缓存（H4），不许在 `MessageRow`
     * 里读它 —— 那会把一次重组变成 N 次 N 元素分配。
     */
    val messages: List<ChatMessage>
        get() = items.mapNotNull { (it as? ChatItem.Message)?.message }

    // --- lifecycle -------------------------------------------------------------

    /**
     * 页面离开组合（切会话 / 退详情 / 换适配器重建）时**必须**调用 —— `SessionDetailScreen`
     * 的 `DisposableEffect` 负责这一条。
     *
     * 做三件事：
     *  1. 置 [disposed]：所有异步回写（RPC 回包、历史快照、上传进度、事件投影）先看这一位，
     *     销毁后一个字段都不许写 —— 否则一个晚到的 `ui/submit` 回包会往已经下屏的
     *     `ChatState` 里建排队条目，用户再进会话时看到幽灵 #N；
     *  2. 取消 `wireScope`：在途 RPC 不再占着连接，也不再有机会回写；
     *  3. [drafts] 的防抖窗口 **flush**：半句话立刻落盘，而不是等 500ms 后由一个已经没人
     *     持有的协程写完。
     *
     * 幂等：重复调用是 no-op。
     */
    fun dispose() {
        if (disposed) return
        disposed = true
        wireScope.cancel()
        drafts.flush(sessionId)
    }

    /**
     * 输入框失焦 / 页面把焦点让出去时调用：把草稿的防抖窗口立刻落盘。
     * 语义与 [DraftStore.flush] 相同，页面不必知道 `DraftStore` 的存在。
     */
    fun flushDraft() {
        drafts.flush(sessionId)
    }

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

        /**
         * P1-1 · 本次 `ui/attach` 被更新的 attach 淘汰（协议层 CANCELLED）：历史可能拿到了，
         * 但那次 attach 的 sink 已经被摘掉，屏上会是一个永远没有实时帧的 Ready。落成
         * Failed + [RETRY_TEXT]，重试会重新 attach 并把这一屏接回来。
         */
        const val SUPERSEDED_MESSAGE: String = "会话切换已取代本次加载，请重试"

        // --- RFC-0008 · ask-user 问答 -------------------------------------------

        /** `turn/input.cancel` 的 `reason`：读者主动关掉这一张卡（回合继续）。 */
        const val INPUT_CANCEL_REASON: String = "user_cancel"

        /** `turn/input.cancel` 的 `reason`：本机判到期，顺手让 core 提前收尾（幂等）。 */
        const val INPUT_TIMEOUT_CANCEL_REASON: String = "timeout"

        /** 取消成功后的状态行（超时那条不走这里，见 [timeoutInput]）。 */
        const val INPUT_CANCELLED_NOTICE: String = "已取消作答（回合继续）"

        /** `expires_at` 到期后的状态行（超时 = 未作答；`default` 不生效）。 */
        const val INPUT_TIMEOUT_NOTICE: String = "回答超时（未作答），回合继续"

        /** 回合在问答期间被取消 / 失败 / 中断：卡片一并收起（防 `turn.input_resolved` 丢失）。 */
        const val INPUT_TURN_ENDED_NOTICE: String = "回合已结束，问题已收起"
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
