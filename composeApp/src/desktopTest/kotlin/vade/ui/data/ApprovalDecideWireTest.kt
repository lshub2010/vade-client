package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.ApprovalDecision
import vade.ui.protocol.ApprovalScope
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B6 · `approval/decide` 的出网与回包 —— 帧级断言。判据（任务书原文）：
 * 「点批准 → 恰好一次 approval/decide，params 逐字 {request_id, decision:"approve", scope:"once"}」。
 *
 * 三条 seam 各自钉住：
 *  - [RealSessionSource.decideApproval]（SessionSource 口，req 1+2）→ 帧逐字 + 错误原样穿透；
 *  - [TurnGatewayReal.decideApproval]（chat 的 turns 窄缝，生产链路 ChatState → gateway）→
 *    组合根注入的通道被恰好调用一次、参数逐字；未注入时响亮失败（不许静默）；
 *  - [FakeSessions.decideApproval]（假源，req 1）→ 本地投影 no-op：记录调用、永不抛、
 *    **结构上没有传输层**（对象里不存在 client/transport 字段）——夹具路径出网 0 次。
 */
class ApprovalDecideWireTest {

    // --- SessionSource 口：RealSessionSource → 帧 --------------------------------------------

    private suspend fun connectedSource(factory: FakeTransportFactory): Pair<RealSessionSource, vade.ui.protocol.FakeTransport> {
        val source = RealSessionSource(dataTestConfig(), factory)
        source.connect()
        waitUntil { source.state.value == RealSessionState.Connected }
        return source to factory.transports[0]
    }

    @Test
    fun decideApprovalSendsApprovalDecideWithVerbatimParams() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val deferred = async(Dispatchers.Default) {
                runCatching {
                    source.decideApproval(
                        requestId = "req-1",
                        decision = ApprovalDecision.APPROVE,
                        scope = ApprovalScope.ONCE,
                    )
                }
            }
            waitUntil { transport.record.size >= 1 }
            assertEquals("approval/decide", requestOf(transport.record[0]).first, "方法名逐字")
            // params 逐字：approval/decide 的三个字段，键名与值都按契约原文
            assertEquals(
                """{"request_id":"req-1","decision":"approve","scope":"once"}""",
                requestOf(transport.record[0]).second,
                "params 逐字（rpc.json 1.7.0）",
            )
            transport.push(responseFrame(idOf(transport.record[0]), """{"ok":true}"""))
            deferred.await().getOrThrow() // 成功完成，不抛
            source.disconnect()
        }
    }

    @Test
    fun decideApprovalDenyAndSessionScopeTravelVerbatim() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val deferred = async(Dispatchers.Default) {
                runCatching {
                    source.decideApproval(
                        requestId = "req-2",
                        decision = ApprovalDecision.DENY,
                        scope = ApprovalScope.SESSION,
                    )
                }
            }
            waitUntil { transport.record.size >= 1 }
            assertEquals(
                """{"request_id":"req-2","decision":"deny","scope":"session"}""",
                requestOf(transport.record[0]).second,
                "deny / session 同样逐字",
            )
            transport.push(responseFrame(idOf(transport.record[0]), """{"ok":true}"""))
            deferred.await().getOrThrow()
            source.disconnect()
        }
    }

    @Test
    fun decideApprovalPropagatesWireErrorsWithTheirC04Code() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val deferred = async(Dispatchers.Default) {
                runCatching {
                    source.decideApproval(
                        requestId = "req-1",
                        decision = ApprovalDecision.APPROVE,
                        scope = ApprovalScope.ONCE,
                    )
                }
            }
            waitUntil { transport.record.size >= 1 }
            transport.push(
                errorFrame(idOf(transport.record[0]), "APPROVER_INVALID", "browser sessions cannot approve"),
            )
            val error = deferred.await().exceptionOrNull()
            val vade = error as? VadeException
            assertTrue(vade != null, "错误帧穿透为 VadeException，不是被吞/被包成别的")
            assertEquals(VadeErrorCodes.APPROVER_INVALID, vade.code, "C-04 名原样保留（调用方分得清三类错误）")
            assertEquals("browser sessions cannot approve", vade.message)
            source.disconnect()
        }
    }

    @Test
    fun decideApprovalRefusesBlankRequestIdBeforeAnyFrame() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val error = assertFailsWith<VadeException> {
                source.decideApproval(
                    requestId = "  ",
                    decision = ApprovalDecision.APPROVE,
                    scope = ApprovalScope.ONCE,
                )
            }
            assertEquals(VadeErrorCodes.INVALID_ARGUMENT, error.code)
            assertEquals(0, transport.record.size, "空白 request_id 不上帧")
            source.disconnect()
        }
    }

    // --- chat 的 turns 窄缝：TurnGatewayReal → 注入的通道 ---------------------------------------

    @Test
    fun turnGatewayDelegatesDecideApprovalToTheInjectedChannelVerbatim() = runBlocking {
        val factory = FakeTransportFactory()
        val client = vade.ui.protocol.testClient(factory)
        client.connect()
        assertTrue(client.awaitConnected())

        val calls = mutableListOf<Triple<String, ApprovalDecision, ApprovalScope>>()
        val adapter = TurnGatewayReal(client) { requestId, decision, scope ->
            calls += Triple(requestId, decision, scope)
        }

        adapter.decideApproval(
            requestId = "req-9",
            decision = ApprovalDecision.APPROVE,
            scope = ApprovalScope.ONCE,
        )

        assertEquals(1, calls.size, "恰好一次（去重是 ChatState 的事，gateway 只透传）")
        assertEquals("req-9", calls.single().first)
        assertEquals(ApprovalDecision.APPROVE, calls.single().second)
        assertEquals(ApprovalScope.ONCE, calls.single().third)
        client.close()
    }

    @Test
    fun turnGatewayWithoutAnInjectedChannelFailsLoudly() = runBlocking {
        val factory = FakeTransportFactory()
        val client = vade.ui.protocol.testClient(factory)
        client.connect()
        assertTrue(client.awaitConnected())

        val adapter = TurnGatewayReal(client)
        val error = assertFailsWith<VadeException> {
            adapter.decideApproval(
                requestId = "req-1",
                decision = ApprovalDecision.APPROVE,
                scope = ApprovalScope.ONCE,
            )
        }
        assertEquals(VadeErrorCodes.UNAVAILABLE, error.code, "未接线的 gateway 响亮失败，绝不静默假成功")
        client.close()
    }

    @Test
    fun turnGatewayRefusesBlankRequestId() = runBlocking {
        val factory = FakeTransportFactory()
        val client = vade.ui.protocol.testClient(factory)
        client.connect()
        assertTrue(client.awaitConnected())

        val adapter = TurnGatewayReal(client) { _, _, _ -> }
        val error = assertFailsWith<VadeException> {
            adapter.decideApproval(
                requestId = "",
                decision = ApprovalDecision.APPROVE,
                scope = ApprovalScope.ONCE,
            )
        }
        assertEquals(VadeErrorCodes.INVALID_ARGUMENT, error.code)
        client.close()
    }

    // --- 假源：本地投影 no-op（出网 0 次的探针） --------------------------------------------------

    @Test
    fun fakeSessionsDecideApprovalIsALocalProjectionNoOp() = runBlocking {
        val before = FakeSessions.decideCalls.size
        FakeSessions.decideApproval(
            requestId = "req-fake",
            decision = ApprovalDecision.APPROVE,
            scope = ApprovalScope.ONCE,
        )
        FakeSessions.decideApproval(
            requestId = "req-fake",
            decision = ApprovalDecision.DENY,
            scope = ApprovalScope.SESSION,
        )

        assertEquals(before + 2, FakeSessions.decideCalls.size, "调用被记录（探针），且两次都不抛")
        // 「出网 0 次」是结构事实：FakeSessions 是纯内存 object，没有任何 transport/client。
        // 真要证明「零帧」，靠的是 ApprovalWireTest 的 RecordingTurnGateway 探针（gateway 零调用），
        // 以及这里的不变式 —— 本对象没有可出网的字段。
        assertTrue(FakeSessions.decideCalls.all { it.first.startsWith("req-") }, "记录的参数原样保留")
    }
}
