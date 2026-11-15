package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeException
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A4-1 · 新建会话的帧级契约。
 *
 * `RealSessionSource.createSession()` 必须是**恰好一次** `ui/attach`，params **逐字**
 * `{"session_id":null}` —— 显式 null 就是「创建」请求（core `manager.attach()` 开头
 * `if session_id is None: create_session()`，同一次调用为该会话注册 sink）；返回的 id
 * 必须成为随后 `ui/submit` 的 `session_id`。客户端已跟踪另一个会话时，创建帧也不得被
 * 塞进那个会话的 `last_seq`（seq 替换只作用于同会话 re-attach）。错误帧以 C-04 原码
 * 穿透（[VadeException]）。
 */
class RealSessionCreateTest {

    private suspend fun connectedSource(factory: FakeTransportFactory): Pair<RealSessionSource, FakeTransport> {
        val source = RealSessionSource(dataTestConfig(), factory)
        source.connect()
        waitUntil { source.state.value == RealSessionState.Connected }
        return source to factory.transports[0]
    }

    /** A fresh-session attach result body (core answers a create with the snapshot path). */
    private fun createSnapshot(id: String): String =
        """{"session_id":"$id","from_seq":0,"snapshot":{"from_seq":0,"session":{"id":"$id"},"messages":[],"turns":[],"queue":{"running":null,"pending":[]},"tasks":[]}}"""

    @Test
    fun createSessionSendsExactlyOneAttachWithVerbatimNullParamsAndReturnsCoreId() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            val deferred = async(Dispatchers.Default) { runCatching { source.createSession() } }
            waitUntil { transport.record.size >= 1 }
            val frame = transport.record[0]
            assertEquals("ui/attach", requestOf(frame).first)
            assertEquals(
                """{"session_id":null}""",
                requestOf(frame).second,
                "create params verbatim: explicit null session_id, nothing else",
            )
            transport.push(responseFrame(idOf(frame), createSnapshot("srv-new-1")))
            val newId = deferred.await().getOrThrow()
            assertEquals("srv-new-1", newId)
            // 恰好一次：整条记录里只有这一帧 —— 不是 0（没建出去），也不是 2（双订阅）。
            assertEquals(1, transport.record.size, "create must be exactly ONE wire request")

            // 返回的 id 成为当前会话 id：紧接着的 ui/submit 带的就是它。
            val submit = async(Dispatchers.Default) { runCatching { source.submit(sessionId = newId, text = "你好") } }
            waitUntil { transport.record.size >= 2 }
            val submitFrame = transport.record[1]
            assertEquals("ui/submit", requestOf(submitFrame).first)
            val submitParams = json(requestOf(submitFrame).second)
            assertEquals(
                "srv-new-1",
                submitParams["session_id"]?.toString()?.trim('"'),
                "the submit after a create carries the created id",
            )
            assertTrue(
                submitParams["content"]?.toString()?.contains("你好") == true,
                "the submit content is the typed text",
            )
            transport.push(responseFrame(idOf(submitFrame), """{"turn_id":"t1","queue_pos":0}"""))
            submit.await().getOrThrow()
            source.disconnect()
        }
    }

    @Test
    fun createFrameStaysExactlyNullSessionIdEvenWhenAnotherSessionIsTracked() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            // 先 attach 到已有会话 s-1 —— 客户端开始跟踪 s-1（lastSeq=12）。
            val first = async(Dispatchers.Default) { runCatching { source.attach(sessionId = "s-1") } }
            waitUntil { transport.record.size >= 1 }
            assertEquals("ui/attach", requestOf(transport.record[0]).first)
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"session_id":"s-1","from_seq":12,"snapshot":{"from_seq":12,"session":{"id":"s-1"},"messages":[],"turns":[],"queue":{"running":null,"pending":[]},"tasks":[]}}""",
                ),
            )
            first.await().getOrThrow()

            // 创建：同连接上已跟踪 s-1 —— 创建帧必须仍是逐字 {"session_id":null}，
            // 不得被塞进 s-1 的 last_seq（那是另一个会话的重放窗口）。
            val create = async(Dispatchers.Default) { runCatching { source.createSession() } }
            waitUntil { transport.record.size >= 2 }
            val createFrame = transport.record[1]
            assertEquals("ui/attach", requestOf(createFrame).first)
            assertEquals(
                """{"session_id":null}""",
                requestOf(createFrame).second,
                "the CREATE form must not inherit the tracked session's last_seq",
            )
            transport.push(responseFrame(idOf(createFrame), createSnapshot("srv-new-2")))
            assertEquals("srv-new-2", create.await().getOrThrow())
            source.disconnect()
        }
    }

    @Test
    fun createSessionErrorReplySurfacesAsVadeExceptionWithTheWireCode() {
        val factory = FakeTransportFactory()
        runBlocking {
            val (source, transport) = connectedSource(factory)
            // runCatching INSIDE the async child: a failing async would cancel the
            // runBlocking parent before the test could await the result.
            val deferred = async(Dispatchers.Default) { runCatching { source.createSession() } }
            waitUntil { transport.record.size >= 1 }
            transport.push(errorFrame(idOf(transport.record[0]), "UNAVAILABLE", "core is draining"))
            val thrown = deferred.await().exceptionOrNull()
            assertIs<VadeException>(thrown)
            assertEquals("UNAVAILABLE", thrown.code, "the wire error code passes through verbatim (四类文案的输入)")
            source.disconnect()
        }
    }

    @Test
    fun createSessionFailsFastWithUnavailableWhenDisconnected() {
        val factory = FakeTransportFactory()
        runBlocking {
            val source = RealSessionSource(dataTestConfig(), factory)
            val thrown = runCatching { source.createSession() }.exceptionOrNull()
            assertIs<VadeException>(thrown)
            assertEquals("UNAVAILABLE", thrown.code)
        }
    }
}
