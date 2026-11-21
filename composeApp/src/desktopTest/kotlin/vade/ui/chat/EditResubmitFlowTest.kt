package vade.ui.chat

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.1 · 编辑重发 (`message/edit_resubmit`) 的验收项 — 全部直接驱动 [ChatState]，无窗口、
 * 无时钟（与 ChatFlowTest 同一模式）。通道是测试里的 [RecordingTurnGateway]，断言的是
 * 真实状态代码发给它** exactly what the task pins**:
 * ① message_id 是原消息 id ② content 与输入框一致 ③ 「已编辑」标记 ④ 空内容拒绝。
 */
@OptIn(InternalComposeUiApi::class)
class EditResubmitFlowTest {

    private fun keyDown(key: Key, ctrl: Boolean = false): KeyEvent =
        KeyEvent(key = key, type = KeyEventType.KeyDown, isCtrlPressed = ctrl)

    private fun lastUserId(chat: ChatState): String =
        chat.messages.last { it.role == ChatRole.User }.id

    private fun byId(chat: ChatState, id: String): ChatMessage =
        chat.messages.first { it.id == id }

    // ① ② — the gateway call carries the ORIGINAL message id and the editor's text
    @Test
    fun resubmitPassesTheOriginalMessageIdAndDraftContent(): Unit = runBlocking {
        val gateway = RecordingTurnGateway()
        val chat = ChatState("s-edit", turns = gateway)
        val originalId = lastUserId(chat)

        assertTrue(chat.startEdit(originalId))
        assertEquals(originalId, chat.editingId, "the editor opens on the original row")
        chat.updateEditDraft("改完之后的新问题")
        assertTrue(chat.confirmEdit())

        val call = gateway.edits.single()
        assertEquals("s-edit", call.sessionId)
        assertEquals(originalId, call.messageId, "① 原消息 id，不是重发产物的新 id")
        assertEquals(
            listOf(ContentPart.Text("改完之后的新问题")),
            call.content,
            "② content 与输入框一致",
        )
        assertTrue(call.clientMsgId.startsWith("ui-"), "edit resubmits mint a ui-<ms>-<seq> key")
        assertNotEquals(originalId, call.clientMsgId, "the idempotency key is not the original message id")
    }

    // ③ — the row is projected in place: new text + 「已编辑」, attachments carried over
    @Test
    fun editedRowWearsTheBadgeWithTheNewTextAndKeepsItsAttachments(): Unit = runBlocking {
        val chat = ChatState("s-edit")
        val originalId = lastUserId(chat)
        val originalAttachments = byId(chat, originalId).attachments.map { it.javaClass.simpleName }

        chat.startEdit(originalId)
        chat.updateEditDraft("新文案")
        assertTrue(chat.confirmEdit())

        val edited = byId(chat, originalId)
        assertEquals("新文案", edited.bodyText, "bubble text becomes the new text (message.edited projection)")
        assertTrue(edited.edited)
        assertTrue(edited.toView().badges.contains("已编辑"), "③ 「已编辑」标记出现")
        assertEquals(
            originalAttachments,
            edited.attachments.map { it.javaClass.simpleName },
            "editing replaces the text; attachments ride along",
        )
        assertTrue(
            chat.messages.none { it.id != originalId && it.role == ChatRole.User && it.bodyText == "新文案" },
            "no second user row is invented — the edit is in place",
        )
    }

    // ④ — blank content must not reach the gateway at all
    @Test
    fun blankDraftCannotBeResubmitted(): Unit = runBlocking {
        val gateway = RecordingTurnGateway()
        val chat = ChatState("s-edit", turns = gateway)
        val originalId = lastUserId(chat)

        assertTrue(chat.startEdit(originalId))
        assertTrue(chat.canConfirmEdit, "the editor prefills the row's current text — confirmable until blanked")
        chat.updateEditDraft("   \n ")
        assertFalse(chat.canConfirmEdit, "④ whitespace is not content")
        assertFalse(chat.confirmEdit())
        assertTrue(gateway.edits.isEmpty(), "the gateway is never called on empty content")
        assertFalse(byId(chat, originalId).edited)
        assertEquals(originalId, chat.editingId, "the editor stays open for a real edit")
    }

    @Test
    fun onlyTheLastUserMessageIsEditable() {
        val chat = ChatState("s-edit")
        val users = chat.messages.filter { it.role == ChatRole.User }
        val assistants = chat.messages.filter { it.role == ChatRole.Assistant }

        assertFalse(chat.canEditMessage(users.first().id), "an older user row is not editable")
        assertFalse(chat.startEdit(users.first().id))
        assertNull(chat.editingId)
        assertFalse(chat.canEditMessage("nope"))
        assertFalse(chat.canEditMessage(assistants.last().id), "assistant rows are never edit targets")
        assertTrue(chat.canEditMessage(users.last().id))
        assertTrue(chat.startEdit(users.last().id))
        assertEquals("顺便看下这张图和附件里的 spec。", chat.editDraft, "the editor prefills the current text")
    }

    @Test
    fun cancelEditLeavesTheRowUntouched(): Unit = runBlocking {
        val gateway = RecordingTurnGateway()
        val chat = ChatState("s-edit", turns = gateway)
        val originalId = lastUserId(chat)
        val originalText = byId(chat, originalId).bodyText

        chat.startEdit(originalId)
        chat.updateEditDraft("改了一半就放弃")
        chat.cancelEdit()

        assertNull(chat.editingId)
        assertEquals("", chat.editDraft)
        assertEquals(originalText, byId(chat, originalId).bodyText)
        assertFalse(byId(chat, originalId).edited)
        assertTrue(gateway.edits.isEmpty())
    }

    @Test
    fun failedResubmitKeepsTheEditorOpenWithTheOriginalIntact(): Unit = runBlocking {
        val gateway = RecordingTurnGateway(failEdits = 1)
        val chat = ChatState("s-edit", turns = gateway)
        val originalId = lastUserId(chat)
        val originalText = byId(chat, originalId).bodyText

        chat.startEdit(originalId)
        chat.updateEditDraft("新内容")
        assertFalse(chat.confirmEdit(), "a failed channel call is not a success")
        assertEquals(1, gateway.edits.size, "the gateway WAS called exactly once")
        assertNotNull(chat.editingId, "the editor stays open — the user can retry")
        assertEquals("新内容", chat.editDraft, "the draft survives the failure")
        assertEquals(originalText, byId(chat, originalId).bodyText)
        assertFalse(byId(chat, originalId).edited)
        assertNotNull(chat.lastNotice)
        assertTrue(chat.lastNotice!!.contains("重发失败"))
    }

    @Test
    fun resubmitWhileStreamingQueuesWithTheEditReasonAndSurvivesCancel(): Unit = runBlocking {
        val chat = ChatState("s-edit") // the fixture ends mid-stream
        assertTrue(chat.isStreaming)
        val originalId = lastUserId(chat)

        chat.startEdit(originalId)
        chat.updateEditDraft("排队重发")
        assertTrue(chat.confirmEdit())

        val entry = chat.queue.single()
        assertEquals("edit_resubmit", entry.reason, "the queue carries the RFC reason")
        assertEquals(originalId, entry.messageId)

        // Cancelling a queued EDIT drops only the rerun — the edited row must stay.
        assertTrue(chat.cancelQueued(entry.clientMsgId))
        assertTrue(chat.queue.isEmpty())
        assertNotNull(chat.messages.firstOrNull { it.id == originalId }, "原行不被取消队列删除")
        assertEquals("排队重发", byId(chat, originalId).bodyText)
    }

    @Test
    fun confirmWhenIdleStartsANewStreamingTurn(): Unit = runBlocking {
        val chat = ChatState("s-edit")
        chat.stop() // idle
        val rowsBefore = chat.items.size
        val originalId = lastUserId(chat)

        chat.startEdit(originalId)
        chat.updateEditDraft("改完立刻重跑")
        assertTrue(chat.confirmEdit())

        val fresh = chat.messages.last()
        assertEquals(ChatRole.Assistant, fresh.role)
        assertEquals(MessageState.Running, fresh.state, "the rerun turn streams immediately")
        assertEquals(rowsBefore + 1, chat.items.size, "one new assistant row, nothing else")
        assertTrue(chat.isStreaming)
    }

    @Test
    fun editorKeysConfirmWithCtrlEnterAndCancelWithEscape(): Unit = runBlocking {
        val gateway = RecordingTurnGateway()
        val chat = ChatState("s-edit", turns = gateway)
        val originalId = lastUserId(chat)

        chat.startEdit(originalId)
        chat.updateEditDraft("键盘重发")
        assertEquals(EditKeyAction.Confirm, handleEditKeys(keyDown(Key.Enter, ctrl = true), chat))
        assertTrue(chat.confirmEdit(), "the page launches confirm on the Confirm action")
        assertTrue(gateway.edits.size == 1)

        chat.startEdit(originalId) // re-open on the (now edited) row
        assertEquals(EditKeyAction.Cancel, handleEditKeys(keyDown(Key.Escape), chat))
        assertNull(chat.editingId)
        assertEquals(EditKeyAction.None, handleEditKeys(keyDown(Key.A), chat), "plain keys pass through")
        assertEquals(EditKeyAction.None, handleEditKeys(keyDown(Key.Enter), chat), "plain Enter edits, never confirms")

        // An empty draft still consumes Ctrl+Enter (no stray newline) but sends nothing.
        chat.startEdit(originalId)
        chat.updateEditDraft("  ")
        assertEquals(EditKeyAction.Confirm, handleEditKeys(keyDown(Key.NumPadEnter, ctrl = true), chat))
        assertFalse(chat.confirmEdit())
        assertTrue(gateway.edits.size == 1, "④ 空内容不重发")
    }
}
