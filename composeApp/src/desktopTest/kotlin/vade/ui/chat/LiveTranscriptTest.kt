package vade.ui.chat

import vade.ui.protocol.MessageEdited
import vade.ui.protocol.MessageEditedPayload
import vade.ui.protocol.ModuleStatus
import vade.ui.protocol.ModuleStatusPayload
import vade.ui.protocol.QueueUpdated
import vade.ui.protocol.QueueUpdatedPayload
import vade.ui.protocol.SessionUpdated
import vade.ui.protocol.SessionUpdatedPayload
import vade.ui.protocol.TaskProgress
import vade.ui.protocol.TaskProgressPayload
import vade.ui.protocol.TurnCompleted
import vade.ui.protocol.TurnCompletedPayload
import vade.ui.protocol.TurnDelta
import vade.ui.protocol.TurnDeltaPayload
import vade.ui.protocol.TurnFailed
import vade.ui.protocol.TurnFailedPayload
import vade.ui.protocol.TurnInterrupted
import vade.ui.protocol.TurnInterruptedPayload
import vade.ui.protocol.TurnQueued
import vade.ui.protocol.TurnQueuedPayload
import vade.ui.protocol.TurnStarted
import vade.ui.protocol.TurnStartedPayload
import vade.ui.protocol.TurnSuperseded
import vade.ui.protocol.TurnSupersededPayload
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeEvent
import vade.ui.protocol.VadeUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * g-chatlive · `LiveTranscript` — the protocol-events → chat-rows projector, pinned
 * event by event. All inputs are hand-built [VadeEvent]s (no JSON, no socket, no
 * clock); the completed-turn usage mirrors the real wire numbers captured in
 * `shots/w-e2e/RESULT.md` (prompt 9 / completion 12 / total 21, source=provider).
 */
class LiveTranscriptTest {

    // --- builders (wire shapes from VadeEvents.kt, defaults = documented values) ----

    private fun usage(
        prompt: Long = 9,
        completion: Long = 12,
        total: Long = 21,
    ) = VadeUsage(prompt, completion, total, null, null, "provider", 1, 0, 0)

    private fun delta(
        seq: Long,
        turn: String,
        text: String,
        partIndex: Int = 0,
        kind: String = "text",
    ) = TurnDelta(seq, coalesced = false, payload = TurnDeltaPayload(turn, text, partIndex, kind))

    private fun completed(
        seq: Long,
        turn: String,
        usage: VadeUsage = usage(),
        stopped: String? = "stop",
    ) = TurnCompleted(seq, coalesced = false, payload = TurnCompletedPayload(turn, "completed", usage, stopped))

    private fun failed(
        seq: Long,
        turn: String,
        code: String = VadeErrorCodes.TIMEOUT,
        message: String = "上游模型超时",
        usage: VadeUsage? = null,
    ) = TurnFailed(seq, coalesced = false, payload = TurnFailedPayload(turn, "failed", code, message, usage, null))

    private fun interrupted(
        seq: Long,
        turn: String,
        partialChars: Int? = null,
        usage: VadeUsage? = null,
    ) = TurnInterrupted(seq, coalesced = false, payload = TurnInterruptedPayload(turn, "interrupted", "user", usage, null, partialChars))

    private fun edited(
        seq: Long,
        messageId: String,
        text: String,
        supersededBy: String = "msg-new-$seq",
    ) = MessageEdited(seq, coalesced = false, payload = MessageEditedPayload(messageId, "s-live", supersededBy, "turn-new-$seq", text, 0L))

    private fun superseded(seq: Long, turn: String, by: String = "turn-b") =
        TurnSuperseded(seq, coalesced = false, payload = TurnSupersededPayload(turn, by, "regenerate", null, 2))

    private fun queued(seq: Long, turn: String) = TurnQueued(
        seq, coalesced = false,
        payload = TurnQueuedPayload(turn, "queued", null, turn, 1, null, null, "submit"),
    )

    private fun started(seq: Long, turn: String) = TurnStarted(
        seq, coalesced = false,
        payload = TurnStartedPayload(turn, "running", null, null),
    )

    // --- 1-2 · delta accumulation -------------------------------------------------

    @Test
    fun twoDeltasOnOnePartAccumulateInsteadOfOverwriting() {
        val t = LiveTranscript()
        t.apply(delta(1, "turn-1", "数据流"))
        val afterFirst = t.messages.single().bodyText
        t.apply(delta(2, "turn-1", "拼接"))
        val row = t.messages.single()
        assertEquals("数据流拼接", row.bodyText, "second delta must append, not replace ($afterFirst would be overwritten)")
        assertEquals(afterFirst + "拼接", row.bodyText)
        assertEquals(1, row.parts.size, "same part_index = one part")
        assertEquals(MessageState.Running, row.state)
    }

    @Test
    fun deltasReplayTheRealE2eWireSentenceAndUsage() {
        // Exact sequence from shots/w-e2e/RESULT.md §2: seq 6,7 = deltas, seq 8 = completed.
        val turn = "ddd4cb8f37034ce7b127e52fa8a5d13c"
        val t = LiveTranscript()
        t.apply(delta(6, turn, "E2E echo: scripted provider reply."))
        t.apply(delta(7, turn, " -- done."))
        t.apply(completed(8, turn))
        val row = t.messages.single()
        assertEquals("E2E echo: scripted provider reply. -- done.", row.bodyText)
        assertEquals(MessageState.Completed, row.state)
        val used = t.usageOf(turn)
        assertEquals(9L, used?.promptTokens)
        assertEquals(12L, used?.completionTokens)
        assertEquals(21L, used?.totalTokens)
        assertEquals("provider", used?.source)
        assertNull(t.failureOf(turn), "a completed turn carries no failure")
    }

    // --- 3-4 · idempotency (same seq must not append) -------------------------------

    @Test
    fun replayingTheSameSeqDoesNotAppend() {
        val t = LiveTranscript()
        val event = delta(1, "turn-1", "只此一份")
        t.apply(event)
        t.apply(event)
        assertEquals("只此一份", t.messages.single().bodyText, "same seq must not double the text")
        assertEquals(1, t.messages.size)
        assertEquals(1, t.duplicates)
        assertEquals(1L, t.lastSeq)
    }

    @Test
    fun replayingTheWholeSequenceIsIdempotent() {
        val sequence = listOf(
            queued(1, "t"),
            started(2, "t"),
            delta(3, "t", "E2E echo: "),
            delta(4, "t", "scripted reply."),
            completed(5, "t"),
        )
        val t = LiveTranscript()
        sequence.forEach { t.apply(it) }
        val firstPass = t.messages
        val duplicatesBefore = t.duplicates
        sequence.forEach { t.apply(it) }
        assertEquals(firstPass, t.messages, "a full replay must produce the identical transcript")
        assertEquals(sequence.size, t.duplicates - duplicatesBefore, "every replayed frame is counted")
        assertTrue(t.anomalies.isEmpty(), "a replay is expected traffic, not an anomaly")
    }

    // --- 5-8 · seq discipline (out-of-order / late / gap / bootstrap) ---------------

    @Test
    fun outOfOrderDeltasBufferUntilTheGapFills() {
        val t = LiveTranscript()
        t.apply(queued(1, "t"))
        t.apply(delta(4, "t", "ld"))
        assertTrue(t.messages.isEmpty(), "a gap event must not project ahead of the missing frame")
        t.apply(delta(3, "t", " wor"))
        assertTrue(t.messages.isEmpty())
        t.apply(started(2, "t")) // fills the hole → drains 3 then 4
        val row = t.messages.single()
        assertEquals(" world", row.bodyText, "buffered deltas project in seq order, not arrival order")
        assertEquals(4L, t.lastSeq)
        assertTrue(t.anomalies.isEmpty(), "buffered events are held, not dropped")
    }

    @Test
    fun anOldSeqReplayIsAnIdempotentDropNotAMutation() {
        val t = LiveTranscript()
        t.apply(delta(1, "t", "a"))
        t.apply(delta(2, "t", "b"))
        t.apply(delta(1, "t", "迟到的"))
        assertEquals("ab", t.messages.single().bodyText, "an old frame must not mutate the row")
        assertEquals(2L, t.lastSeq)
        assertEquals(1, t.duplicates)
        assertTrue(t.anomalies.isEmpty())
        assertTrue(t.changedIds.isEmpty(), "a dropped event changes nothing")
    }

    @Test
    fun coalescedGapJumpsForwardAsSelfContained() {
        val t = LiveTranscript()
        t.apply(delta(1, "t", "a"))
        // RFC-0007 §5.5 R5: a gap made only of droppable (coalesced) frames is not a hole.
        t.apply(TurnDelta(5, coalesced = true, payload = TurnDeltaPayload("t", "b", 0, "text")))
        assertEquals(5L, t.lastSeq)
        assertEquals("ab", t.messages.single().bodyText)
        assertTrue(t.anomalies.isEmpty(), "a sanctioned coalesced jump is not an anomaly")
    }

    @Test
    fun midStreamFirstEventBootstrapsInsteadOfDeadlocking() {
        val t = LiveTranscript()
        val result = t.apply(delta(42, "t", "中途接入"))
        assertEquals(1, result.size)
        assertEquals("中途接入", t.messages.single().bodyText)
        assertEquals(42L, t.lastSeq, "the first event defines the attach point; waiting for 1..41 would hang")
        assertEquals(TranscriptAnomalyKind.BootstrapGap, t.anomalies.single().kind)
    }

    // --- 9-11 · message.edited ------------------------------------------------------

    @Test
    fun editedReplacesTheRowInPlaceWithoutAddingOne() {
        val seeded = ChatMessage(
            id = "msg-user-1",
            role = ChatRole.User,
            parts = listOf(ContentPart.Text("原问题"), ContentPart.Image("f1", "图.png", 10, 1, 1)),
        )
        val t = LiveTranscript(seed = listOf(seeded))
        val result = t.apply(edited(1, "msg-user-1", "改后的问题"))
        assertEquals(1, result.size, "edited must replace the row, never add one")
        val row = result.single()
        assertEquals("msg-user-1", row.id, "same id → same list position")
        assertEquals("改后的问题", row.bodyText)
        assertTrue(row.edited)
        assertEquals(1, row.attachments.size, "attachments are carried over the edit")
        assertEquals(setOf("msg-user-1"), t.changedIds)
    }

    @Test
    fun editedForUnknownRowIsRecordedNotSilent() {
        val t = LiveTranscript()
        val result = t.apply(edited(1, "msg-ghost", "无处可去"))
        assertTrue(result.isEmpty())
        assertEquals(TranscriptAnomalyKind.UnknownEditTarget, t.anomalies.single().kind)
    }

    @Test
    fun duplicateEditSupersededByIsIgnored() {
        val seeded = ChatMessage(id = "msg-u", role = ChatRole.User, parts = listOf(ContentPart.Text("原")))
        val t = LiveTranscript(seed = listOf(seeded))
        t.apply(edited(1, "msg-u", "第一版", supersededBy = "msg-e1"))
        t.apply(edited(2, "msg-u", "第二版", supersededBy = "msg-e1"))
        assertEquals("第一版", t.messages.single().bodyText, "the same edit replayed under a new seq must not re-apply")
        assertEquals(TranscriptAnomalyKind.DuplicateEdit, t.anomalies.single().kind)
    }

    // --- 12-13 · interrupt keeps partial text ---------------------------------------

    @Test
    fun interruptKeepsPartialTextAndCountsCodePoints() {
        val t = LiveTranscript()
        t.apply(delta(1, "t", "好"))
        t.apply(delta(2, "t", "👍"))
        t.apply(interrupted(3, "t", partialChars = null))
        val row = t.messages.single()
        assertEquals(MessageState.Interrupted, row.state)
        assertEquals("好👍", row.bodyText, "interrupt keeps what streamed")
        assertEquals(2, row.partialChars, "partial_chars counts code points (👍 = 1), never UTF-16 units (3)")
        assertEquals(0, row.badges.size, "interrupt is a status, not an error")
    }

    @Test
    fun interruptPrefersTheWirePartialChars() {
        val t = LiveTranscript()
        t.apply(delta(1, "t", "abcdef"))
        t.apply(interrupted(2, "t", partialChars = 7))
        assertEquals(7, t.messages.single().partialChars, "the wire value wins over a local recount")
    }

    // --- 14-15 · turn.failed --------------------------------------------------------

    @Test
    fun failedWithoutDeltasProducesAnErrorRowWithTheC04Code() {
        val t = LiveTranscript()
        val result = t.apply(failed(1, "t", code = VadeErrorCodes.TIMEOUT, message = "上游模型超时"))
        val row = result.single()
        assertEquals(MessageState.Failed, row.state)
        assertEquals("上游模型超时", row.bodyText, "the error row must say something")
        assertEquals(listOf(VadeErrorCodes.TIMEOUT), row.badges)
        val failure = t.failureOf("t")
        assertEquals(VadeErrorCodes.TIMEOUT, failure?.code)
        assertEquals("上游模型超时", failure?.message)
        assertNull(t.usageOf("t"), "a failed turn without usage reports none")
    }

    @Test
    fun failedAfterPartialTextKeepsThePartialText() {
        val t = LiveTranscript()
        t.apply(delta(1, "t", "部分文本"))
        t.apply(failed(2, "t", code = VadeErrorCodes.INTERNAL, message = "工具爆炸"))
        val row = t.messages.single()
        assertEquals("部分文本", row.bodyText, "the streamed text is not replaced by the error message")
        assertEquals(MessageState.Failed, row.state)
        assertContains(row.badges, VadeErrorCodes.INTERNAL)
        assertEquals("工具爆炸", t.failureOf("t")?.message)
    }

    // --- 16-17 · delta edge cases ----------------------------------------------------

    @Test
    fun emptyDeltaIsHarmless() {
        val t = LiveTranscript()
        t.apply(delta(1, "t", "a"))
        val result = t.apply(delta(2, "t", ""))
        assertEquals("a", result.single().bodyText)
        assertEquals(2L, t.lastSeq)
    }

    @Test
    fun nonTextDeltaKindsNeverJoinTheBody() {
        val t = LiveTranscript()
        t.apply(delta(1, "t", "内心戏", kind = "reasoning"))
        assertTrue(t.messages.isEmpty(), "a reasoning-only turn has no bubble body")
        t.apply(delta(2, "t", "正文"))
        t.apply(delta(3, "t", "{\"x\":1}", kind = "tool_args"))
        t.apply(completed(4, "t"))
        assertEquals("正文", t.messages.single().bodyText)
    }

    // --- 18 · multiple turns ---------------------------------------------------------

    @Test
    fun twoTurnsRenderInOrderWithSeparateRows() {
        val t = LiveTranscript()
        t.apply(delta(1, "turn-a", "第一轮"))
        t.apply(completed(2, "turn-a"))
        t.apply(delta(3, "turn-b", "第二轮"))
        t.apply(completed(4, "turn-b"))
        val rows = t.messages
        assertEquals(2, rows.size)
        assertEquals(LiveTranscript.liveRowId("turn-a"), rows[0].id)
        assertEquals("第一轮", rows[0].bodyText)
        assertEquals(LiveTranscript.liveRowId("turn-b"), rows[1].id)
        assertEquals("第二轮", rows[1].bodyText)
        assertTrue(rows.all { it.state == MessageState.Completed })
    }

    // --- 19 · lastSeq monotonicity ----------------------------------------------------

    @Test
    fun lastSeqNeverGoesBackwardsAcrossMixedTraffic() {
        val t = LiveTranscript()
        var floor = 0L
        fun feed(event: VadeEvent) {
            t.apply(event)
            assertTrue(t.lastSeq >= floor, "lastSeq went backwards: ${t.lastSeq} < $floor")
            floor = t.lastSeq
        }
        feed(delta(1, "t", "a"))
        feed(delta(1, "t", "a")) // duplicate → unchanged
        feed(delta(5, "t", "b")) // gap → buffered, unchanged
        feed(delta(3, "t", "c")) // still a gap (2 missing) → buffered, unchanged
        feed(delta(2, "t", "b2")) // fills the hole → drains 3, keeps 5 waiting
        feed(delta(2, "t", "迟到")) // now below lastSeq → idempotent drop
        assertEquals(3L, t.lastSeq)
        assertEquals("ab2c", t.messages.single().bodyText, "seq order: a, b2, c; the late frame never lands")
        assertEquals(2, t.duplicates, "one same-seq duplicate + one late drop")
        assertTrue(t.anomalies.isEmpty(), "duplicates and in-order gaps are counter territory, not anomalies")
    }

    // --- 20 · unrelated topics ---------------------------------------------------------

    @Test
    fun unrelatedKnownEventsAreIgnoredCleanly() {
        val t = LiveTranscript()
        t.apply(QueueUpdated(1, coalesced = false, payload = QueueUpdatedPayload("s", null, null, emptyList())))
        t.apply(ModuleStatus(2, coalesced = false, payload = ModuleStatusPayload("mod", "inst", true, null)))
        t.apply(TaskProgress(3, coalesced = true, payload = TaskProgressPayload("task", 50, 100, null)))
        t.apply(SessionUpdated(4, coalesced = false, payload = SessionUpdatedPayload("s", "user", null, null, null, null, null, null, null, null)))
        assertTrue(t.messages.isEmpty())
        assertTrue(t.anomalies.isEmpty(), "known-but-unrelated topics are deliberate ignores")
        assertEquals(4L, t.lastSeq, "ignored frames still advance the seq")
    }

    // --- 21 · turn.superseded -----------------------------------------------------------

    @Test
    fun turnSupersededMarksTheOldRowWithoutDeletingIt() {
        val t = LiveTranscript()
        t.apply(delta(1, "turn-a", "第一版回答"))
        t.apply(completed(2, "turn-a"))
        t.apply(superseded(3, "turn-a", by = "turn-b"))
        val row = t.messages.single()
        assertTrue(row.superseded, "the old version stays in the thread, badged 旧版本")
        assertEquals(MessageState.Completed, row.state)
        assertEquals("第一版回答", row.bodyText)
    }

    // --- 22 · completed turn without deltas ----------------------------------------------

    @Test
    fun completedTurnWithNoTextStillFinalizesARow() {
        val t = LiveTranscript()
        val result = t.apply(completed(1, "t", usage = usage(total = 33)))
        val row = result.single()
        assertEquals(MessageState.Completed, row.state)
        assertEquals("", row.bodyText)
        assertEquals(33L, t.usageOf("t")?.totalTokens)
    }

    // --- 23 · the ChatState hook (priority 2) ----------------------------------------------

    @Test
    fun applyLiveDrivesChatStateItemsFromTheEventStream() {
        val chat = ChatState("s-live-ext")
        val itemsBefore = chat.items.size
        val t = LiveTranscript()
        chat.applyLive(t, delta(1, "turn-1", "真数据"))
        chat.applyLive(t, delta(2, "turn-1", "管道"))
        chat.applyLive(t, completed(3, "turn-1"))
        val row = chat.messages.last()
        assertEquals(LiveTranscript.liveRowId("turn-1"), row.id)
        assertEquals("真数据管道", row.bodyText)
        assertEquals(MessageState.Completed, row.state)
        assertEquals(itemsBefore + 1, chat.items.size, "one turn = one appended row; fixtures untouched")
        assertEquals(21L, t.usageOf("turn-1")?.totalTokens)
        // A replayed frame changes nothing in the state list either.
        chat.applyLive(t, delta(2, "turn-1", "管道"))
        assertEquals(itemsBefore + 1, chat.items.size)
    }
}
