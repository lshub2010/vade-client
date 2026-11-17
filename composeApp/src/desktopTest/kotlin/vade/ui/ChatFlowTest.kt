package vade.ui

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import vade.ui.chat.ApprovalOutcome
import vade.ui.chat.ChatFixtures
import vade.ui.chat.ChatItem
import vade.ui.chat.ChatMessage
import vade.ui.chat.ChatRole
import vade.ui.chat.ChatPreset
import vade.ui.chat.ChatState
import vade.ui.chat.ContentPart
import vade.ui.chat.DraftStore
import vade.ui.chat.MessageState
import vade.ui.chat.ToolCall
import vade.ui.chat.ToolResult
import vade.ui.chat.ToolRunState
import vade.ui.chat.bodyText
import vade.ui.chat.formatBytes
import vade.ui.chat.formatDuration
import vade.ui.chat.handleComposerKeys
import vade.ui.chat.isApprovalCard
import vade.ui.chat.textProjection
import vade.ui.chat.toolCardCollapsible
import vade.ui.chat.toolCardState
import vade.ui.chat.toolMetaRows
import vade.ui.chat.toAttachmentLike
import vade.ui.skin.Decision
import vade.ui.skin.InputMode
import vade.ui.skin.ToolCardState
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * U1-b · the interaction acceptance items that can be measured without a window
 * (task book §8.2 #1 流式中断 / #2 队列, plus D94's tool-card policy and the RFC field names).
 *
 * The state object is driven directly — no composition, no clock — so "3 秒内停止" and
 * "点停止后不再追加" are facts about [ChatState], not about a timer that happened to fire.
 */
class ChatFlowTest {

    /**
     * 草稿现在按会话留存在进程内单例 [DraftStore] 里（`DraftStoreTest` 覆盖那套语义）。
     * 这一类的用例共用 `s-rfc0004`，每条之前先把表收干净，免得上一条没发出去的半句话
     * （例如 Composer 预设、键盘用例留下的 `x`）被下一条的重建读回来。
     */
    @BeforeTest
    fun resetDrafts() = DraftStore.shared.clearAll()

    private fun state(preset: ChatPreset = ChatPreset.Live) = ChatState("s-rfc0004", preset = preset)

    private fun lastMessage(state: ChatState): ChatMessage =
        state.items.filterIsInstance<ChatItem.Message>().last().message

    // --- interrupt (FR-8.28 / 8.35) -------------------------------------------

    @Test
    fun fixtureStartsMidStreamSoTheStreamingStateIsReal() {
        val chat = state()
        assertTrue(chat.isStreaming, "the fixture ends with a `running` turn")
        val last = lastMessage(chat)
        assertEquals(ChatRole.Assistant, last.role)
        assertEquals(MessageState.Running, last.state)
        assertTrue(last.bodyText.isNotEmpty(), "a mid-stream body is already visible")
        assertTrue(chat.messages.size >= 6, "one of each fixture row: ${chat.messages.size}")
    }

    @Test
    fun stopKeepsGeneratedTextAndReportsChars() {
        val chat = state()
        chat.tick(chunk = 10)
        val before = lastMessage(chat).bodyText
        assertTrue(chat.stop(), "stop() on a running turn must report that it did something")
        val stopped = lastMessage(chat)
        assertEquals(MessageState.Interrupted, stopped.state)
        assertEquals(before, stopped.bodyText, "interrupt keeps what was already generated")
        assertEquals(before.length, stopped.partialChars)
        assertEquals("已停止 · 已生成 ${before.length} 字", chat.lastNotice)
        assertFalse(stopped.state == MessageState.Failed, "an interrupt is not an error (no red bubble)")
        assertFalse(chat.isStreaming)
        assertFalse(chat.stop(), "a second stop is a no-op (turn already terminal)")
    }

    @Test
    fun deltaAfterInterruptIsDropped() {
        val chat = state()
        chat.stop()
        val frozen = lastMessage(chat).bodyText
        repeat(5) { assertFalse(chat.tick(), "no delta may land after `turn.interrupted`") }
        assertEquals(frozen, lastMessage(chat).bodyText)
    }

    @Test
    fun tickingToTheEndCompletesTheTurn() {
        val chat = state()
        var guard = 0
        while (chat.isStreaming && guard++ < 500) chat.tick(chunk = 20)
        assertFalse(chat.isStreaming, "the script must terminate")
        val done = lastMessage(chat)
        assertEquals(MessageState.Completed, done.state)
        assertEquals(ChatFixtures.streamingReply, done.bodyText)
        assertEquals(0, done.partialChars, "a completed turn has no partial count")
    }

    // --- queue (FR-8.35 / 8.36) -----------------------------------------------

    @Test
    fun sendingWhileStreamingQueuesWithPositions() {
        val chat = state()
        val startRows = chat.items.size
        chat.updateDraft("第一条")
        val first = assertNotNull(chat.submit())
        chat.updateDraft("第二条")
        val second = assertNotNull(chat.submit())
        chat.updateDraft("第三条")
        val third = assertNotNull(chat.submit())

        assertEquals(3, chat.queue.size)
        assertEquals(listOf(first, second, third), chat.queue.map { it.clientMsgId })
        assertEquals(3, chat.items.size - startRows, "each queued submit gets exactly one user bubble")
        val queued = chat.messages.filter { it.state == MessageState.Queued }
        assertEquals(listOf(1, 2, 3), queued.map { chat.queuePosition(it.id) })
        assertTrue(chat.isStreaming, "queueing must not start a second turn")
        assertEquals(InputMode.Streaming, chat.inputMode)
    }

    @Test
    fun cancellingTheThirdRemovesItAndShiftsTheRest() {
        val chat = state()
        listOf("一", "二", "三").forEach { chat.updateDraft(it); chat.submit() }
        val third = chat.queue[2]
        val thirdMessageId = third.messageId

        assertTrue(chat.cancelQueued(third.clientMsgId))
        assertEquals(2, chat.queue.size)
        assertEquals(listOf(1, 2), chat.queue.mapIndexed { i, q -> chat.queuePosition(q.messageId) })
        assertTrue(
            chat.items.none { it is ChatItem.Message && it.message.id == thirdMessageId },
            "the cancelled bubble leaves the thread immediately",
        )
        assertFalse(chat.cancelQueued("c-404"), "cancelling an unknown id is a no-op")
    }

    @Test
    fun interruptingTheCurrentTurnStartsTheNextQueuedOne() {
        val chat = state()
        chat.updateDraft("排队的一")
        chat.submit()
        chat.updateDraft("排队的二")
        chat.submit()
        val queuedBefore = chat.queue.size
        assertEquals(2, queuedBefore)

        chat.stop()
        assertEquals(queuedBefore - 1, chat.queue.size, "the head of the queue starts (`turn.queued` → running)")
        assertTrue(chat.isStreaming, "the next turn is now streaming")
        val queuedRows = chat.messages.filter { it.state == MessageState.Queued }
        assertEquals(listOf(1), queuedRows.map { chat.queuePosition(it.id) }, "positions renumber after the shift")
    }

    @Test
    fun queueBadgeShowsTheCountNotAStaleNumber() {
        val chat = state()
        listOf("一", "二", "三").forEach { chat.updateDraft(it); chat.submit() }
        assertEquals(3, chat.queue.size)
        chat.cancelQueued(chat.queue.first().clientMsgId)
        assertEquals(2, chat.queue.size)
        assertEquals(0, chat.queuePosition("nope"), "an unqueued row has no badge")
    }

    // --- submitting from idle -------------------------------------------------

    @Test
    fun blankDraftCannotBeSent() {
        val chat = state()
        chat.stop() // idle
        val rows = chat.items.size
        chat.updateDraft("   \n  ")
        assertFalse(chat.canSend, "whitespace is not content")
        assertNull(chat.submit(), "empty text must not create a turn")
        assertEquals(rows, chat.items.size)
        assertEquals(0, chat.queue.size)
        assertEquals(InputMode.Idle, chat.inputMode)
    }

    @Test
    fun sendFromIdleAppendsUserThenStreamingAssistant() {
        val chat = state()
        chat.stop()
        val rows = chat.items.size
        chat.updateDraft("把 §3 的分片写细一点")
        val clientMsgId = assertNotNull(chat.submit())

        assertEquals(rows + 2, chat.items.size, "one user bubble + one assistant placeholder")
        val user = chat.messages[chat.messages.size - 2]
        assertEquals("把 §3 的分片写细一点", user.bodyText)
        assertEquals(clientMsgId, user.clientMsgId)
        assertEquals(ChatRole.User, user.role)
        val assistant = lastMessage(chat)
        assertEquals(MessageState.Running, assistant.state)
        assertTrue(chat.isStreaming)
        assertEquals("", chat.draft, "the composer clears on submit")
        assertEquals(InputMode.Streaming, chat.inputMode)
    }

    // --- tool cards (D94 / FR-8.51) -------------------------------------------

    private fun tool(
        id: String = "tc-x",
        state: ToolRunState,
        risk: String = "low",
        duration: Long? = 812,
        batch: String? = "bat_7f3a9c01d2b45e88",
        args: String? = "{\"path\":\"a\"}",
    ) = ToolCall(
        id = id,
        turnId = "t-1",
        name = "shell.exec",
        argsPreview = args,
        argsPreviewMode = "redacted",
        batchId = batch,
        risk = risk,
        state = state,
        result = ToolResult(
            ok = state == ToolRunState.Succeeded,
            summary = "exit 0",
            durationMs = duration,
        ),
    )

    @Test
    fun toolCardPolicyFollowsD94() {
        val success = tool(state = ToolRunState.Succeeded)
        assertEquals(ToolCardState.Collapsed, toolCardState(success, openedByUser = false), "success collapses")
        assertEquals(ToolCardState.Expanded, toolCardState(success, openedByUser = true), "the reader may open it")

        val failure = tool(state = ToolRunState.Failed)
        assertEquals(ToolCardState.Expanded, toolCardState(failure, openedByUser = false), "failure auto-expands")
        assertTrue(toolCardCollapsible(failure), "a failure may still be collapsed by hand")

        val approval = tool(state = ToolRunState.PendingApproval, risk = "high")
        assertEquals(
            ToolCardState.PendingApproval,
            toolCardState(approval, openedByUser = false),
            "risk=high approval is always expanded",
        )
        assertEquals(
            ToolCardState.PendingApproval,
            toolCardState(approval, openedByUser = true),
            "and stays expanded whatever the reader toggled",
        )
        assertFalse(toolCardCollapsible(approval), "D94: the approval card may not be collapsed")
    }

    @Test
    fun toolMetaDropsMissingFieldsInsteadOfPrintingDashes() {
        val full = tool(state = ToolRunState.Succeeded)
        val rows = toolMetaRows(full, batchSize = 2).associate { it.label to it.value }
        assertEquals("812 ms", rows["执行耗时"])
        assertTrue(rows.getValue("批次").endsWith("同批 2 个"), "same batch, never \"parallel\": ${rows["批次"]}")
        assertEquals("redacted", rows["参数预览"])

        val sparse = tool(state = ToolRunState.Unknown, duration = null, batch = null, args = null)
        val sparseRows = toolMetaRows(sparse, batchSize = 0).map { it.label }
        assertTrue(sparseRows.isEmpty(), "no duration / no batch / no preview ⇒ no rows: $sparseRows")

        val durationOnly = tool(state = ToolRunState.Succeeded, batch = null, args = null)
        assertEquals(listOf("执行耗时"), toolMetaRows(durationOnly, batchSize = 0).map { it.label })

        val single = toolMetaRows(full, batchSize = 1).associate { it.label to it.value }
        assertEquals("bat_7f3a9c01d2b45e88", single["批次"], "a batch of one prints the id alone")
    }

    @Test
    fun toolCardViewMapsTheRfcFieldsOntoTheFrozenComponent() {
        val failed = tool(state = ToolRunState.Failed)
        val view = failed.toView()
        assertEquals("shell.exec", view.name)
        assertEquals(failed.argsPreview, view.args)
        assertTrue(view.isError)
        assertEquals("812 ms", formatDuration(failed.result!!.durationMs!!))

        val noPreview = tool(state = ToolRunState.Succeeded, args = null).toView()
        assertTrue(
            noPreview.args.contains("无 args_preview"),
            "the frozen card always draws an args block, so the page explains the absence",
        )
    }

    @Test
    fun batchSizeCountsTheSharedBatchId() {
        val chat = state()
        val batch = chat.items.filterIsInstance<ChatItem.Tool>().first().call.batchId
        assertEquals(2, chat.batchSize(batch), "the fixture puts two calls in one batch")
        assertEquals(0, chat.batchSize(null), "no batch id ⇒ no count")
    }

    // --- model / attachments --------------------------------------------------

    @Test
    fun textProjectionFollowsRfc0004() {
        val parts = listOf(
            ContentPart.Text("这张报错是什么意思？"),
            ContentPart.Image(fileId = "b1", name = "IMG_0042.jpg", size = 1, width = 10, height = 10),
            ContentPart.File(fileId = "b2", name = "spec.pdf", size = 2, mime = "application/pdf"),
        )
        assertEquals(
            "这张报错是什么意思？\n[image: IMG_0042.jpg]\n[file: spec.pdf]",
            parts.textProjection(),
        )
        assertEquals("这张报错是什么意思？", parts.bodyText(), "the bubble shows text parts only")
        assertEquals(2, parts.filter { it !is ContentPart.Text }.size)
    }

    @Test
    fun attachmentChipSaysTheOriginalIsNotKept() {
        val image = ContentPart.Image(
            fileId = "b1",
            name = "IMG_0042.jpg",
            size = 1_834_211,
            width = 2048,
            height = 1536,
        )
        val like = image.toAttachmentLike()
        assertNotNull(like.image, "an image part opens the preview")
        assertTrue(like.view.detail!!.contains("已压缩，原图未保留"), "D96 copy: ${like.view.detail}")
        assertTrue(like.view.detail!!.contains("1.7 MB"))

        val file = ContentPart.File(fileId = "b2", name = "spec.pdf", size = 902_144, mime = "application/pdf")
        assertNull(file.toAttachmentLike().image, "only images preview (纯图)")
    }

    @Test
    fun previewOpensAndCloses() {
        val chat = state()
        assertNull(chat.preview)
        chat.openPreview(ChatFixtures.previewImage)
        assertEquals("IMG_0042.jpg", chat.preview?.name)
        chat.closePreview()
        assertNull(chat.preview)
    }

    @Test
    fun stagedAttachmentProgressIsDrivenByTicksNotHardCoded() {
        val chat = state(ChatPreset.Composer)
        assertEquals(2, chat.staged.size, "the composer preset stages two attachments")
        assertTrue(chat.staged.all { it.attachment.state == vade.ui.skin.AttachmentState.Uploading })
        var guard = 0
        while (chat.tickUploads() && guard++ < 20) Unit
        assertTrue(
            chat.staged.take(2).all { it.attachment.state == vade.ui.skin.AttachmentState.Uploaded },
            "progress reaches 1.0 and flips the state",
        )
        chat.removeStaged(chat.staged.first().attachment.fileId)
        assertEquals(1, chat.staged.size, "the chip's × removes it")
    }

    @Test
    fun stagingIsCappedAtTheRfcLimit() {
        val chat = state()
        repeat(ChatState.MAX_PER_TURN + 2) { chat.attach() }
        assertEquals(ChatState.MAX_PER_TURN, chat.staged.size)
        assertTrue(chat.lastNotice!!.contains("最多 ${ChatState.MAX_PER_TURN}"), "the cap is explained: ${chat.lastNotice}")
    }

    // --- formatting -----------------------------------------------------------

    @Test
    fun byteAndDurationFormatting() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.7 MB", formatBytes(1_834_211))
        assertEquals("881.0 KB", formatBytes(902_144))
        assertEquals("812 ms", formatDuration(812))
        assertEquals("1.4 s", formatDuration(1420))
    }

    // --- keyboard (desktop) ---------------------------------------------------

    @OptIn(InternalComposeUiApi::class)
    private fun keyDown(key: Key, ctrl: Boolean = false, meta: Boolean = false): KeyEvent =
        KeyEvent(key = key, type = KeyEventType.KeyDown, isCtrlPressed = ctrl, isMetaPressed = meta)

    @OptIn(InternalComposeUiApi::class)
    @Test
    fun metaEnterSendsAndPlainEnterDoesNot() {
        val chat = state()
        chat.stop()
        val rows = chat.items.size
        chat.updateDraft("用 ⌘Enter 发出去")

        assertFalse(handleComposerKeys(keyDown(Key.Enter), chat), "plain Enter must reach the field (newline)")
        assertEquals(rows, chat.items.size, "plain Enter did not send")
        assertTrue(handleComposerKeys(keyDown(Key.Enter, ctrl = true), chat), "Ctrl+Enter sends")
        assertEquals(rows + 2, chat.items.size, "user bubble + streaming reply")
        assertEquals("", chat.draft)
    }

    @OptIn(InternalComposeUiApi::class)
    @Test
    fun metaEnterOnAnEmptyComposerIsConsumedButSendsNothing() {
        val chat = state()
        chat.stop()
        val rows = chat.items.size
        assertTrue(
            handleComposerKeys(keyDown(Key.NumPadEnter, meta = true), chat),
            "consumed so the shortcut never inserts a stray newline",
        )
        assertEquals(rows, chat.items.size, "an empty composer sends nothing")
    }

    @OptIn(InternalComposeUiApi::class)
    @Test
    fun keyUpAndOtherKeysAreNotConsumed() {
        val chat = state()
        chat.stop()
        chat.updateDraft("x")
        val up = KeyEvent(key = Key.Enter, type = KeyEventType.KeyUp, isCtrlPressed = true)
        assertFalse(handleComposerKeys(up, chat))
        assertFalse(handleComposerKeys(keyDown(Key.A), chat))
    }

    // --- inline approval (v2 §4) ------------------------------------------------

    private fun pendingApproval(state: ChatState): ToolCall =
        state.items.filterIsInstance<ChatItem.Tool>()
            .first { it.call.state == ToolRunState.PendingApproval }.call

    @Test
    fun fixtureCarriesOnePendingApprovalRenderedInline() {
        val chat = state()
        val call = pendingApproval(chat)
        assertEquals("tc-3", call.id)
        assertTrue(isApprovalCard(call, chat.approvalOutcome(call.id)), "pending ⇒ card, not a tool card")
        assertFalse(chat.approvalDetailOpen(call.id), "detail starts closed")
    }

    @Test
    fun allowingEvolvesTheCardInPlaceWithoutNewRows() {
        val chat = state()
        val call = pendingApproval(chat)
        val rowsBefore = chat.items.size
        val messagesBefore = chat.messages.size

        assertTrue(chat.decideApproval(call.id, Decision.Approve))
        assertEquals(ApprovalOutcome.Allowed, chat.approvalOutcome(call.id))
        assertEquals(rowsBefore, chat.items.size, "the decision adds no message / no row")
        assertEquals(messagesBefore, chat.messages.size)
        assertNotNull(chat.approvalDecidedLabel(call.id), "the resolved card carries a timestamp")
        assertTrue(isApprovalCard(call, chat.approvalOutcome(call.id)), "resolved keeps the card form")

        assertFalse(
            chat.decideApproval(call.id, Decision.Deny),
            "`turn.approval_resolved` arrives once — a second decision is a no-op",
        )
        assertEquals(ApprovalOutcome.Allowed, chat.approvalOutcome(call.id))
    }

    @Test
    fun denyAndTimeoutAreDistinctTerminalStates() {
        val denied = state()
        val deniedCall = pendingApproval(denied)
        assertTrue(denied.decideApproval(deniedCall.id, Decision.Deny))
        assertEquals(ApprovalOutcome.Denied, denied.approvalOutcome(deniedCall.id))

        val timedOut = state()
        val timedOutCall = pendingApproval(timedOut)
        assertTrue(timedOut.expireApproval(timedOutCall.id), "timeout resolves without a user decision")
        assertEquals(ApprovalOutcome.TimedOut, timedOut.approvalOutcome(timedOutCall.id))
        assertNotNull(timedOut.approvalDecidedLabel(timedOutCall.id))
        assertEquals(timedOut.items.size, timedOut.messages.size + timedOut.items.count { it is ChatItem.Tool })
    }

    @Test
    fun decisionsOnlyApplyToUndecidedPendingApprovals() {
        val chat = state()
        assertFalse(chat.decideApproval("tc-404", Decision.Approve), "unknown call id")
        assertFalse(
            chat.decideApproval("tc-1", Decision.Approve),
            "tc-1 already succeeded — never an approval target",
        )
        val call = pendingApproval(chat)
        assertTrue(chat.expireApproval(call.id))
        assertFalse(chat.expireApproval(call.id), "already resolved")
        assertFalse(chat.decideApproval(call.id, Decision.Approve), "already resolved")
        assertFalse(chat.hasPendingApproval, "nothing is waiting any more")
    }

    @Test
    fun approvalDetailTogglesOpenAndShut() {
        val chat = state()
        val call = pendingApproval(chat)
        chat.toggleApprovalDetail(call.id)
        assertTrue(chat.approvalDetailOpen(call.id))
        chat.toggleApprovalDetail(call.id)
        assertFalse(chat.approvalDetailOpen(call.id))
    }
}
