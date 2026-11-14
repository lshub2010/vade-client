package vade.ui.chat

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * v1.1 · 重新生成 (`turn/regenerate`) 的验收项 — 直接驱动 [ChatState]，无窗口、无时钟：
 * ⑤ 只有最后一条助手消息可触发 ⑥ 旧版本灰化保留并标记 ⑦（编外）网关收到的 turn_id 正确。
 */
class RegenerateFlowTest {

    private fun lastAssistantId(chat: ChatState): String =
        chat.messages.last { it.role == ChatRole.Assistant }.id

    private fun byId(chat: ChatState, id: String): ChatMessage =
        chat.messages.first { it.id == id }

    // ⑤ — not the tail, still running, or superseded ⇒ refuse (gateway never called)
    @Test
    fun onlyTheLastAssistantMessageCanRegenerate(): Unit = runBlocking {
        val gateway = RecordingTurnGateway()
        val chat = ChatState("s-regen", turns = gateway)
        assertTrue(chat.isStreaming, "the fixture ends mid-stream")
        val assistants = chat.messages.filter { it.role == ChatRole.Assistant }
        val tailId = assistants.last().id

        assertFalse(chat.canRegenerate(tailId), "Running is not a terminal state")
        assertFalse(chat.regenerate(tailId))
        assertTrue(gateway.regenerates.isEmpty(), "refused locally — nothing reaches the channel")

        chat.stop() // tail becomes Interrupted (terminal), idle
        assertFalse(chat.canRegenerate(assistants.first().id), "not the tail")
        assertFalse(chat.regenerate(assistants.first().id))
        assertTrue(gateway.regenerates.isEmpty())
        assertTrue(chat.canRegenerate(tailId))
    }

    @Test
    fun regenerateCallsTheGatewayWithTheTurnId(): Unit = runBlocking {
        val gateway = RecordingTurnGateway()
        val chat = ChatState("s-regen", turns = gateway)
        chat.stop()
        val tailId = lastAssistantId(chat)

        assertTrue(chat.regenerate(tailId))
        val call = gateway.regenerates.single()
        assertEquals("s-regen", call.sessionId)
        assertEquals(tailId, call.turnId, "the channel receives the turn being rerun")
        assertTrue(call.clientMsgId.startsWith("c-r-"), "regenerate gets its own idempotency key")
    }

    // ⑥ — the old version stays in the thread, marked, and a fresh turn streams below it
    @Test
    fun oldVersionIsKeptMarkedSupersededAndReplacedByANewStreamingTurn(): Unit = runBlocking {
        val chat = ChatState("s-regen")
        chat.stop()
        val rowsBefore = chat.items.size
        val tailId = lastAssistantId(chat)
        val oldText = byId(chat, tailId).bodyText

        assertTrue(chat.regenerate(tailId))

        val old = byId(chat, tailId)
        assertTrue(old.superseded)
        assertTrue(old.toView().badges.contains("旧版本"), "⑥ 旧版本标记")
        assertEquals(oldText, old.bodyText, "the old version keeps its own text")
        assertTrue(
            chat.items.any { it is ChatItem.Message && it.message.id == tailId },
            "旧版本保留在列表里，绝不删除",
        )
        assertEquals(rowsBefore + 1, chat.items.size, "exactly one new row: the fresh turn")

        val fresh = chat.messages.last()
        assertEquals(ChatRole.Assistant, fresh.role)
        assertEquals(MessageState.Running, fresh.state)
        assertFalse(fresh.superseded)
        assertFalse(chat.canRegenerate(tailId), "a superseded row is never regenerated again")
        assertFalse(chat.canRegenerate(fresh.id), "the fresh turn is still Running")
    }

    @Test
    fun regeneratedTurnCompletesWhileTheOldVersionStaysUntouched(): Unit = runBlocking {
        val chat = ChatState("s-regen")
        chat.stop()
        val tailId = lastAssistantId(chat)
        val oldText = byId(chat, tailId).bodyText

        assertTrue(chat.regenerate(tailId))
        var guard = 0
        while (chat.isStreaming && guard++ < 500) chat.tick(chunk = 40)
        assertFalse(chat.isStreaming, "the regenerated turn must terminate")

        val fresh = chat.messages.last()
        assertEquals(MessageState.Completed, fresh.state)
        assertTrue(fresh.bodyText.isNotEmpty())
        val old = byId(chat, tailId)
        assertTrue(old.superseded)
        assertEquals(oldText, old.bodyText, "the old version never changes after superseding")
        assertTrue(old.toView().badges.contains("旧版本"))
        assertTrue(chat.canRegenerate(fresh.id), "the new tail is regenerate-able again")
    }

    @Test
    fun failedRegenerateLeavesTheThreadUntouched(): Unit = runBlocking {
        val gateway = RecordingTurnGateway(failRegenerates = 1)
        val chat = ChatState("s-regen", turns = gateway)
        chat.stop()
        val rowsBefore = chat.items.size
        val tailId = lastAssistantId(chat)

        assertFalse(chat.regenerate(tailId))
        assertEquals(1, gateway.regenerates.size, "the gateway WAS called exactly once")
        assertEquals(rowsBefore, chat.items.size)
        assertFalse(byId(chat, tailId).superseded, "no grey-out on a failed call")
        assertTrue(chat.messages.none { it.superseded }, "no row was touched by the failed call")
        assertNotNull(chat.lastNotice)
        assertTrue(chat.lastNotice!!.contains("重新生成失败"))
    }
}
