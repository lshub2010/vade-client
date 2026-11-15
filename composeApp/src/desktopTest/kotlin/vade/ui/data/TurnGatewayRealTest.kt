package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import vade.ui.chat.ContentPart
import vade.ui.chat.EditResubmitReceipt
import vade.ui.chat.RegenerateReceipt
import vade.ui.chat.SubmitReceipt
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeException
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [TurnGatewayReal] against the frozen wire. The load-bearing assertion is the FIRST
 * one: `message/edit_resubmit` receives the ORIGINAL message id verbatim — the adapter
 * never rewrites, re-derives, or pre-increments it (core appends the new message and
 * returns its id in `message_id`).
 */
class TurnGatewayRealTest {

    private suspend fun connectedAdapter(
        factory: FakeTransportFactory,
    ): Triple<vade.ui.protocol.VadeClient, vade.ui.protocol.FakeTransport, TurnGatewayReal> {
        val client = testClient(factory)
        client.connect()
        assertTrue(client.awaitConnected())
        val adapter = TurnGatewayReal(client)
        return Triple(client, factory.transports[0], adapter)
    }

    @Test
    fun editResubmitSendsOriginalMessageIdInExactFrame() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connectedAdapter(factory)
            val deferred = async(Dispatchers.Default) {
                adapter.editResubmit(
                    sessionId = "s-1",
                    messageId = "m-original",
                    content = listOf(ContentPart.Text("改过的提示词")),
                    clientMsgId = "cm-1",
                )
            }
            waitUntil { transport.record.size >= 1 }
            assertEquals("message/edit_resubmit", requestOf(transport.record[0]).first)
            // 逐字: the id travels UNCHANGED; parts carry the wire discriminator `type`.
            assertEquals(
                """{"session_id":"s-1","message_id":"m-original",""" +
                    """"content":[{"type":"text","text":"改过的提示词"}],"client_msg_id":"cm-1"}""",
                requestOf(transport.record[0]).second,
            )
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"turn_id":"t-2","message_id":"m-new","supersedes_turn_id":"t-1",""" +
                        """"root_turn_id":"t-1","version":2,"queue_pos":0}""",
                ),
            )
            val receipt = deferred.await()
            assertEquals(
                EditResubmitReceipt(
                    turnId = "t-2",
                    newMessageId = "m-new",
                    supersedesTurnId = "t-1",
                    rootTurnId = "t-1",
                    version = 2,
                    queuePos = 0,
                ),
                receipt,
                "receipt fields are the wire receipts 1:1 (newMessageId = result.message_id)",
            )
            client.close()
        }
    }

    @Test
    fun editResubmitMapsImageAndFileParts() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connectedAdapter(factory)
            val deferred = async(Dispatchers.Default) {
                adapter.editResubmit(
                    sessionId = "s-1",
                    messageId = "m-1",
                    content = listOf(
                        ContentPart.Image(
                            fileId = "blb_img",
                            name = "cat.png",
                            size = 64L,
                            width = 8,
                            height = 8,
                            mime = "image/png",
                            sha256 = "aa".repeat(32),
                        ),
                        ContentPart.File(fileId = "blb_doc", name = "notes.txt", size = 16L, mime = "text/plain"),
                    ),
                    clientMsgId = "cm-2",
                )
            }
            waitUntil { transport.record.size >= 1 }
            assertEquals(
                """{"session_id":"s-1","message_id":"m-1","content":[""" +
                    """{"type":"image","file_id":"blb_img","mime":"image/png","name":"cat.png","size":64,""" +
                    """"width":8,"height":8,"sha256":${jsonString("aa".repeat(32))}},""" +
                    """{"type":"file","file_id":"blb_doc","mime":"text/plain","name":"notes.txt","size":16}],"client_msg_id":"cm-2"}""",
                requestOf(transport.record[0]).second,
            )
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"turn_id":"t-3","message_id":"m-2","supersedes_turn_id":"t-2","root_turn_id":"t-1","version":3,"queue_pos":0}""",
                ),
            )
            deferred.await()
            client.close()
        }
    }

    @Test
    fun editResubmitRefusesBlankArgsOrEmptyContentWithoutSending() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connectedAdapter(factory)
            assertFailsWith<VadeException> { adapter.editResubmit(" ", "m-1", listOf(ContentPart.Text("x")), "cm-1") }
            assertFailsWith<VadeException> { adapter.editResubmit("s-1", " ", listOf(ContentPart.Text("x")), "cm-1") }
            assertFailsWith<VadeException> { adapter.editResubmit("s-1", "m-1", listOf(ContentPart.Text("x")), " ") }
            assertFailsWith<VadeException> { adapter.editResubmit("s-1", "m-1", emptyList(), "cm-1") }
            assertEquals(0, transport.record.size, "malformed requests must never produce a frame")
            client.close()
        }
    }

    @Test
    fun regenerateSendsExactFrameAndMapsReceipt() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connectedAdapter(factory)
            val deferred = async(Dispatchers.Default) { adapter.regenerate("s-1", "t-1", "cm-9") }
            waitUntil { transport.record.size >= 1 }
            assertEquals("turn/regenerate", requestOf(transport.record[0]).first)
            assertEquals(
                """{"session_id":"s-1","turn_id":"t-1","client_msg_id":"cm-9"}""",
                requestOf(transport.record[0]).second,
            )
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"turn_id":"t-2","root_turn_id":"t-1","version":2,"supersedes_turn_id":"t-1","queue_pos":0}""",
                ),
            )
            assertEquals(
                RegenerateReceipt(turnId = "t-2", rootTurnId = "t-1", version = 2, supersedesTurnId = "t-1", queuePos = 0),
                deferred.await(),
            )
            client.close()
        }
    }

    @Test
    fun regenerateRefusesBlankArgsWithoutSending() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connectedAdapter(factory)
            assertFailsWith<VadeException> { adapter.regenerate(" ", "t-1", "cm-9") }
            assertFailsWith<VadeException> { adapter.regenerate("s-1", " ", "cm-9") }
            assertFailsWith<VadeException> { adapter.regenerate("s-1", "t-1", " ") }
            assertEquals(0, transport.record.size)
            client.close()
        }
    }

    // --- g-chat-send · ui/submit / ui/interrupt --------------------------------------

    @Test
    fun submitSendsTheExactUiSubmitFrameAndMapsTheReceipt() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connectedAdapter(factory)
            val deferred = async(Dispatchers.Default) {
                adapter.submit(
                    sessionId = "s-1",
                    parts = listOf(
                        ContentPart.Text("先跑一遍测试"),
                        ContentPart.Image(
                            fileId = "blb_img", name = "shot.png", size = 8L,
                            width = 4, height = 4, mime = "image/png", sha256 = "bb".repeat(32),
                        ),
                    ),
                    clientMsgId = "cm-1",
                )
            }
            waitUntil { transport.record.size >= 1 }
            assertEquals("ui/submit", requestOf(transport.record[0]).first)
            // 逐字: parts 顺序 = 显示顺序；字段名/顺序与 ContentPart.toJson 的契约一致。
            assertEquals(
                """{"session_id":"s-1","client_msg_id":"cm-1",""" +
                    """"content":[{"type":"text","text":"先跑一遍测试"},""" +
                    """{"type":"image","file_id":"blb_img","mime":"image/png","name":"shot.png","size":8,""" +
                    """"width":4,"height":4,"sha256":${jsonString("bb".repeat(32))}}]}""",
                requestOf(transport.record[0]).second,
            )
            transport.push(responseFrame(idOf(transport.record[0]), """{"turn_id":"t-9","queue_pos":3}"""))
            val receipt = deferred.await()
            assertEquals(
                SubmitReceipt(turnId = "t-9", queuePos = 3),
                receipt,
                "turn_id / queue_pos 逐字落 receipt（排队 #N 用 core 的值）",
            )
            client.close()
        }
    }

    @Test
    fun interruptSendsTheExactUiInterruptFrame() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connectedAdapter(factory)
            val deferred = async(Dispatchers.Default) { adapter.interrupt(sessionId = "s-1", turnId = "t-9") }
            waitUntil { transport.record.size >= 1 }
            assertEquals("ui/interrupt", requestOf(transport.record[0]).first)
            assertEquals(
                """{"session_id":"s-1","turn_id":"t-9"}""",
                requestOf(transport.record[0]).second,
            )
            transport.push(responseFrame(idOf(transport.record[0]), """{"ok":true}"""))
            deferred.await() // 完成即成功；turn.interrupted 事件负责上屏
            client.close()
        }
    }

    @Test
    fun submitAndInterruptRefuseBlankArgsWithoutSending() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connectedAdapter(factory)
            assertFailsWith<VadeException> { adapter.submit(" ", listOf(ContentPart.Text("x")), "cm-1") }
            assertFailsWith<VadeException> { adapter.submit("s-1", listOf(ContentPart.Text("x")), " ") }
            assertFailsWith<VadeException> { adapter.submit("s-1", emptyList(), "cm-1") }
            assertFailsWith<VadeException> { adapter.interrupt(" ", "t-1") }
            assertFailsWith<VadeException> { adapter.interrupt("s-1", " ") }
            assertEquals(0, transport.record.size, "校验失败不发帧")
            client.close()
        }
    }

    @Test
    fun concurrentCallsOnOneGatewayDoNotInterfere() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (client, transport, adapter) = connectedAdapter(factory)
            val edit = async(Dispatchers.Default) {
                adapter.editResubmit("s-1", "m-original", listOf(ContentPart.Text("A")), "cm-a")
            }
            val regen = async(Dispatchers.Default) { adapter.regenerate("s-1", "t-before", "cm-b") }
            waitUntil { transport.record.size >= 2 }
            // Frame order between the two callers is nondeterministic — pair by method,
            // then reply OUT OF ORDER: id matching must pair each reply with its caller.
            val editFrame = transport.record.first { requestOf(it).first == "message/edit_resubmit" }
            val regenFrame = transport.record.first { requestOf(it).first == "turn/regenerate" }
            val editId = idOf(editFrame)
            val regenId = idOf(regenFrame)
            transport.push(
                responseFrame(
                    regenId,
                    """{"turn_id":"t-regen","root_turn_id":"t-before","version":2,"supersedes_turn_id":"t-before","queue_pos":0}""",
                ),
            )
            transport.push(
                responseFrame(
                    editId,
                    """{"turn_id":"t-edit","message_id":"m-edit","supersedes_turn_id":"t-1","root_turn_id":"t-1","version":2,"queue_pos":1}""",
                ),
            )
            val editReceipt = edit.await()
            val regenReceipt = regen.await()
            assertEquals("t-edit", editReceipt.turnId, "the edit must not receive the regenerate's reply")
            assertEquals("m-edit", editReceipt.newMessageId)
            assertEquals("t-regen", regenReceipt.turnId, "the regenerate must not receive the edit's reply")
            assertEquals(0, regenReceipt.queuePos, "the regenerate's own queue_pos from its reply")
            assertEquals(1, editReceipt.queuePos, "the edit's own queue_pos from its reply")
            client.close()
        }
    }

    private fun jsonString(raw: String): String = "\"" + raw + "\""
}
