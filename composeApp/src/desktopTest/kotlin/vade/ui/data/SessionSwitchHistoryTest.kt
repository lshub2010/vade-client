package vade.ui.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P0-1 · 切会话/重进会话，历史整段空白。
 *
 * 生产链路是 `ChatState.loadHistory` → `RealSessionSource.messages(id)` →
 * `VadeClient.attach`。修之前 `attach` 只判断「有 tracked session 且有 tracked
 * lastSeq」就把**上一个会话**的游标塞进新会话的帧里；core 对任何 `last_seq` 走
 * replay 分支（`replay.covers()` 对尚短的会话恒真）→ `snapshot = null` →
 * `snapshotToMessages(null)` 是空表 → 界面「还没有消息」。A→B→A 里 B 与第二次 A
 * 都会中招，而「重进同一会话」也是同一条路（同会话也 re-attach）。
 *
 * 这个文件里的小 core 复刻那条规则：**带 last_seq 就答 replay（snapshot=null），不带
 * 才答快照**。于是「帧里多了 last_seq」不再只是断言参数，而是直接变成「历史为空」。
 */
class SessionSwitchHistoryTest {

    /** core 的回答：快照（full thread）或 replay（snapshot=null）。 */
    private fun snapshotReply(sessionId: String, fromSeq: Long, userText: String): String =
        """{"session_id":"$sessionId","from_seq":$fromSeq,"snapshot":{"from_seq":$fromSeq,""" +
            """"session":{"id":"$sessionId"},"messages":[{"id":"m-$sessionId","role":"user","content":"$userText"}],""" +
            """"turns":[],"queue":{"running":null,"pending":[]}}}"""

    private fun replayReply(sessionId: String, fromSeq: Long): String =
        """{"session_id":"$sessionId","from_seq":$fromSeq,"snapshot":null}"""

    @Test
    fun switchingSessionsNeverInheritsThePreviousSessionsCursor() = runBlocking {
        val factory = FakeTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)
        try {
            source.connect()
            source.state.awaitState("the source to become Connected") { it == RealSessionState.Connected }
            val transport = factory.transports.single()

            // A：首次 attach，没有可继承的游标 —— 必须拿快照
            val a = async { source.messages("s-A") }
            val frameA = transport.awaitSent()
            assertEquals("""{"session_id":"s-A"}""", requestOf(frameA).second)
            transport.push(responseFrame(idOf(frameA), snapshotReply("s-A", 5, "A 的第一条")))
            assertEquals(listOf("A 的第一条"), a.await().map { it.bodyText })

            // A → B：切会话。带 A 的 last_seq=5 时，core 会答 replay、B 的历史整段空白
            val b = async { source.messages("s-B") }
            val frameB = transport.awaitSent()
            assertEquals(
                """{"session_id":"s-B"}""",
                requestOf(frameB).second,
                "切到 B 的 attach 不许继承 A 的 last_seq（core 走 replay 分支 → snapshot=null → 历史空白）",
            )
            transport.push(responseFrame(idOf(frameB), snapshotReply("s-B", 9, "B 的唯一一条")))
            assertEquals(listOf("B 的唯一一条"), b.await().map { it.bodyText }, "B 的历史必须真的渲染出来")

            // B → A：回到 A，同样必须是快照，而不是 B 的游标
            val backA = async { source.messages("s-A") }
            val frameBackA = transport.awaitSent()
            assertEquals(
                """{"session_id":"s-A"}""",
                requestOf(frameBackA).second,
                "回到 A 的 attach 不许继承 B 的 last_seq",
            )
            transport.push(responseFrame(idOf(frameBackA), snapshotReply("s-A", 5, "A 的第一条")))
            assertEquals(listOf("A 的第一条"), backA.await().map { it.bodyText }, "回到 A 历史必须在")
        } finally {
            source.disconnect()
        }
    }

    /**
     * 重进**同一个**会话（页面重建后 `loadHistory` 再跑一次）：`messages()` 是历史读，
     * 必须显式要快照。同会话 re-attach 带 tracked 游标时 core 只 replay、没有快照，
     * 线程同样会空 —— 这条是 P0-1 的另一半，不能靠「切会话才修」含糊过去。
     */
    @Test
    fun reEnteringTheSameSessionStillAsksForTheSnapshot() = runBlocking {
        val factory = FakeTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)
        try {
            source.connect()
            source.state.awaitState("the source to become Connected") { it == RealSessionState.Connected }
            val transport = factory.transports.single()

            val first = async { source.messages("s-A") }
            val frameOne = transport.awaitSent()
            transport.push(responseFrame(idOf(frameOne), snapshotReply("s-A", 5, "A 的第一条")))
            assertEquals(listOf("A 的第一条"), first.await().map { it.bodyText })
            assertEquals(5L, source.client?.lastSeq, "快照的 from_seq 成为 tracked 游标")

            // 重进同一会话（新的 ChatState 再 loadHistory 一次）
            val again = async { source.messages("s-A") }
            val frameTwo = transport.awaitSent()
            assertEquals(
                """{"session_id":"s-A"}""",
                requestOf(frameTwo).second,
                "历史读不得 resume：同会话带 last_seq 时 core 只回 replay，快照为空 → 重进即空白",
            )
            transport.push(responseFrame(idOf(frameTwo), snapshotReply("s-A", 5, "A 的第一条")))
            assertEquals(
                listOf("A 的第一条"),
                again.await().map { it.bodyText },
                "重进同一会话，历史必须在（P0-1 的另一半）",
            )
        } finally {
            source.disconnect()
        }
    }

    /**
     * 反向钉子：**重连**（`VadeClient` 自己的 re-attach）必须继续带 tracked 游标 ——
     * 历史读的「不 resume」不能把重连的增量语义顺手关掉。
     */
    @Test
    fun reconnectStillResumesFromTheTrackedCursor() = runBlocking {
        val factory = FakeTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)
        try {
            source.connect()
            source.state.awaitState("the source to become Connected") { it == RealSessionState.Connected }
            val first = factory.transports.single()

            val call = async { source.messages("s-A") }
            val frame = first.awaitSent()
            first.push(responseFrame(idOf(frame), snapshotReply("s-A", 5, "A 的第一条")))
            call.await()

            // 断链：runner 重连后自己发 ui/attach，必须带 last_seq=5
            first.close()
            val deadline = System.currentTimeMillis() + 5_000
            while (factory.transports.size < 2 && System.currentTimeMillis() < deadline) {
                delay(10)
            }
            assertTrue(factory.transports.size >= 2, "断链后必须重连")
            val second = factory.transports[1]
            val reattach = second.awaitSent()
            val (method, params) = requestOf(reattach)
            assertEquals("ui/attach", method)
            assertEquals(
                """{"session_id":"s-A","last_seq":5}""",
                params,
                "重连的 re-attach 必须带 tracked last_seq（只补缺口，不重拉快照）",
            )
            second.push(responseFrame(idOf(reattach), replayReply("s-A", 5)))
        } finally {
            source.disconnect()
        }
    }
}
