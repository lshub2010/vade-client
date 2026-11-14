package vade.ui.module

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import vade.ui.data.VadeErrorCategory
import vade.ui.data.VadeErrorMapping
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeClientConfig
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [RealModulePageSource] 的接线测试（w-modulewire）。全部走**真 [VadeClient] + 假
 * transport**（照 `VadeSessionListTypedCallTest` 的用法：真帧出去、真响应回来、真 id
 * 匹配），不 mock 协议层——方法字串与参数是 `VadeMethod.UI_PAGE_GET` / `UI_STATE_GET`
 * 路由出来的真实 wire 形态；解析拒绝复用 `ModulePageParser` 的现有白名单路径。
 */
class RealModulePageSourceTest {

    // ---- 夹具 ---------------------------------------------------------------

    /** 最小合法页：column + 1 text = 2 节点 / 0 动作。 */
    private val simplePage = """{"type":"column","children":[{"type":"text","props":{"text":"hi"}}]}"""

    /**
     * 照 `ModulePageSources.demo()` 的 `download/queue` 形状：column + 3 text +
     * list(恰好 1 个模板 ListItem) + Button(tools/call) = **7 节点 / 1 动作**，深度 3
     * ——成功路径的计数锚点。
     */
    private val queuePage =
        """{"type":"column","props":{"gap":"m"},"children":[""" +
            """{"type":"text","data_ref":"page.state.queue_len","props":{"style":"title","format":"int"}},""" +
            """{"type":"text","data_ref":"page.state.summary","props":{"style":"caption"}},""" +
            """{"type":"text","props":{"text":"任务明细"},"visible":{"bind":"module.status.online"}},""" +
            """{"type":"list","data_ref":"page.state.items","props":{"empty_text":"队列为空"},""" +
            """"children":[{"type":"component","component":"ListItem","props":""" +
            """{"title":{"bind":"item.name"},"subtitle":{"bind":"item.progress","format":"percent"},""" +
            """"trailing_text":{"bind":"item.size","format":"bytes"}}}]},""" +
            """{"type":"component","component":"Button","props":{"text":"刷新","style":"primary"},""" +
            """"on":{"click":{"kind":"tools/call","tool":"download.refresh","args":{}}}}]}"""

    private fun obj(json: String): JsonObject = VadeJson.default.parseToJsonElement(json) as JsonObject

    /** 连接一个真客户端 + 本源，返回（client, source, transport）。 */
    private fun connect(mutate: (VadeClientConfig) -> VadeClientConfig = { it }): Triple<VadeClient, RealModulePageSource, FakeTransport> {
        val factory = FakeTransportFactory()
        val client = testClient(factory, mutate)
        val source = RealModulePageSource(client)
        runBlocking {
            client.connect()
            assertTrue(client.awaitConnected(5000), "client must reach Connected")
        }
        return Triple(client, source, factory.transports.single())
    }

    /** 嵌套 column 树，总深度 = [depth]（叶子是 text）。 */
    private fun nestedColumns(depth: Int): String {
        var json = """{"type":"text","props":{"text":"leaf"}}"""
        repeat(depth - 1) { json = """{"type":"column","children":[$json]}""" }
        return json
    }

    // ---- 诚实的空实现（无对应 RPC，不臆造） -----------------------------------

    @Test
    fun declarationsAndStatusAreHonestEmptyWithoutAnyRpc() {
        val (client, source, _) = connect()
        assertTrue(source.declarations().isEmpty(), "no module-manifest RPC exists — empty, not invented")
        assertEquals(ModuleStatus(online = false, version = null), source.status("download"))
        assertEquals(ModuleStatus(online = false, version = null), source.status("no-such-module"))
        // 空声明推出空入口集：能力页整个模块页区块不渲染（FR-8.79），不是占位。
        assertTrue(ModulePageEntryRules.build(source.declarations()).isEmpty)
    }

    @Test
    fun loadPageAndLoadStateReturnNullBeforeFirstFetchNeverEmptyTree() {
        val (_, source, _) = connect()
        assertNull(source.loadPage("m1", "p1"), "not fetched yet → null（不可用），绝不是空树")
        assertNull(source.loadState("m1", "i1", "p1"), "not fetched yet → null，绝不是空对象")
    }

    // ---- 真 RPC 路径：方法字串 / 参数 / 成功缓存 -----------------------------

    @Test
    fun fetchPageSendsTheExactMethodStringAndParams() {
        val (client, source, transport) = connect()
        runBlocking {
            val call = async { source.fetchPage("m1", "i1", "p1") }
            val frame = transport.awaitSent()
            assertEquals("ui/page.get", requestOf(frame).first, "must route through VadeMethod.UI_PAGE_GET")
            assertEquals(
                """{"module_id":"m1","instance_id":"i1","page_id":"p1"}""",
                requestOf(frame).second,
            )
            transport.push(responseFrame(idOf(frame), """{"schema":$simplePage,"sha256":"abc123"}"""))
            call.await()
            client.close()
        }
    }

    @Test
    fun fetchStateSendsTheExactMethodStringAndParams() {
        val (client, source, transport) = connect()
        runBlocking {
            val call = async { source.fetchState("m1", "i1", "p1") }
            val frame = transport.awaitSent()
            assertEquals("ui/state.get", requestOf(frame).first, "must route through VadeMethod.UI_STATE_GET")
            assertEquals(
                """{"module_id":"m1","instance_id":"i1","page_id":"p1"}""",
                requestOf(frame).second,
            )
            transport.push(
                responseFrame(idOf(frame), """{"rev":7,"state":{"queue_len":3},"fetched_at":123,"unchanged":false}"""),
            )
            val state = call.await()
            assertEquals(3, state["queue_len"]?.jsonPrimitive?.intOrNull)
            client.close()
        }
    }

    @Test
    fun fetchPageSuccessKeepsExactNodeCountAndCachesTheRawSchema() {
        val (client, source, transport) = connect()
        runBlocking {
            val call = async { source.fetchPage("m1", "i1", "queue") }
            val frame = transport.awaitSent()
            transport.push(responseFrame(idOf(frame), """{"schema":$queuePage,"sha256":"deadbeef"}"""))
            val ok = call.await()
            assertEquals(7, ok.nodeCount, "column+3 text+list+ListItem+Button = 7 nodes")
            assertEquals(1, ok.actionCount, "exactly one tools/call action")
            assertEquals(obj(queuePage), source.loadPage("m1", "queue"), "原样 schema 进缓存")
            client.close()
        }
    }

    @Test
    fun fetchStateSuccessCachesTheStateObject() {
        val (client, source, transport) = connect()
        runBlocking {
            val call = async { source.fetchState("m1", "i1", "p1") }
            val frame = transport.awaitSent()
            transport.push(
                responseFrame(idOf(frame), """{"rev":1,"state":{"auto_start":true,"speed":"8.2 MB/s"},"fetched_at":9}"""),
            )
            val state = call.await()
            assertEquals(obj("""{"auto_start":true,"speed":"8.2 MB/s"}"""), state)
            assertEquals(state, source.loadState("m1", "i1", "p1"))
            client.close()
        }
    }

    // ---- 发包前参数校验（空 pageId 零帧） ------------------------------------

    @Test
    fun blankParamsAreRefusedBeforeAnyFrameIsSent() {
        val (client, source, transport) = connect()
        runBlocking {
            val pageBlank = assertFailsWith<VadeException> { source.fetchPage("m1", "i1", "") }
            assertEquals(VadeErrorCodes.INVALID_ARGUMENT, pageBlank.code)
            assertEquals(VadeErrorCategory.NeedsUserAction, VadeErrorMapping.categoryOf(pageBlank.code))
            val moduleBlank = assertFailsWith<VadeException> { source.fetchPage("", "i1", "p1") }
            assertEquals(VadeErrorCodes.INVALID_ARGUMENT, moduleBlank.code)
            val stateBlank = assertFailsWith<VadeException> { source.fetchState("m1", "i1", "") }
            assertEquals(VadeErrorCodes.INVALID_ARGUMENT, stateBlank.code)
            val stateInstanceBlank = assertFailsWith<VadeException> { source.fetchState("m1", "", "p1") }
            assertEquals(VadeErrorCodes.INVALID_ARGUMENT, stateInstanceBlank.code)
            assertEquals(0, transport.record.size, "零帧：参数校验发生在发包前")
            client.close()
        }
    }

    // ---- 解析拒绝：复用 ModulePageParser 白名单路径，拒穿到调用方 --------------

    @Test
    fun scriptKeyPageIsRejectedAsFatalAndNothingIsCached() {
        val (client, source, transport) = connect()
        runBlocking {
            // runCatching INSIDE the async: a failing async child would cancel the
            // runBlocking parent (structured concurrency) before the assert below could run
            //（照 VadeClientBehaviorTest 的既有习惯）。
            val call = async { runCatching { source.fetchPage("m1", "i1", "p1") } }
            val frame = transport.awaitSent()
            transport.push(
                responseFrame(idOf(frame), """{"schema":{"type":"column","on_load":"steal()"},"sha256":"x"}"""),
            )
            val e = call.await().exceptionOrNull() as? VadeException
                ?: fail("fetchPage must fail with VadeException")
            assertEquals(VadeErrorCodes.PROTOCOL_MISMATCH, e.code, "解析拒绝 = 客户端契约被违反")
            assertEquals(VadeErrorCategory.Fatal, VadeErrorMapping.categoryOf(e.code))
            assertTrue(e.message!!.contains("on_load"), "解析器的拒渲理由必须穿到调用方")
            assertNull(source.loadPage("m1", "p1"), "被拒页面绝不进缓存")
            client.close()
        }
    }

    @Test
    fun offWhitelistComponentIsRejectedThroughTheParserPath() {
        val (client, source, transport) = connect()
        runBlocking {
            val call = async { runCatching { source.fetchPage("m1", "i1", "p1") } }
            val frame = transport.awaitSent()
            transport.push(
                responseFrame(idOf(frame), """{"schema":{"type":"component","component":"WebView"},"sha256":"x"}"""),
            )
            val e = call.await().exceptionOrNull() as? VadeException
                ?: fail("fetchPage must fail with VadeException")
            assertEquals(VadeErrorCodes.PROTOCOL_MISMATCH, e.code)
            assertEquals(VadeErrorCategory.Fatal, VadeErrorMapping.categoryOf(e.code))
            assertTrue(e.message!!.contains("WebView"), "白名单外组件点名拒绝")
            client.close()
        }
    }

    @Test
    fun depthNinePageIsRejectedAndSurfacesToTheCaller() {
        val (client, source, transport) = connect()
        runBlocking {
            val call = async { runCatching { source.fetchPage("m1", "i1", "p1") } }
            val frame = transport.awaitSent()
            transport.push(
                responseFrame(idOf(frame), """{"schema":${nestedColumns(9)},"sha256":"x"}"""),
            )
            val e = call.await().exceptionOrNull() as? VadeException
                ?: fail("fetchPage must fail with VadeException")
            assertEquals(VadeErrorCodes.PROTOCOL_MISMATCH, e.code)
            assertEquals(VadeErrorCategory.Fatal, VadeErrorMapping.categoryOf(e.code))
            assertTrue(e.message!!.contains("深度 9"), "深度超限的理由穿到调用方")
            client.close()
        }
    }

    @Test
    fun nonObjectSchemaIsRejectedAsFatal() {
        val (client, source, transport) = connect()
        runBlocking {
            val call = async { runCatching { source.fetchPage("m1", "i1", "p1") } }
            val frame = transport.awaitSent()
            transport.push(responseFrame(idOf(frame), """{"schema":42,"sha256":"x"}"""))
            val e = call.await().exceptionOrNull() as? VadeException
                ?: fail("fetchPage must fail with VadeException")
            assertEquals(VadeErrorCodes.PROTOCOL_MISMATCH, e.code)
            assertEquals(VadeErrorCategory.Fatal, VadeErrorMapping.categoryOf(e.code))
            assertNull(source.loadPage("m1", "p1"))
            client.close()
        }
    }

    // ---- 错误码分类（401 / 500 / 超时 / 传输） -------------------------------

    @Test
    fun authFailedErrorKeepsItsC04NameAndClassifiesAsNeedsReconnect() {
        val (client, source, transport) = connect()
        runBlocking {
            val call = async { runCatching { source.fetchPage("m1", "i1", "p1") } }
            val frame = transport.awaitSent()
            transport.push(errorFrame(idOf(frame), "AUTH_FAILED", "bad device key"))
            val e = call.await().exceptionOrNull() as? VadeException
                ?: fail("fetchPage must fail with VadeException")
            assertEquals("AUTH_FAILED", e.code, "401 类：C-04 名原样保留")
            assertEquals(VadeErrorCategory.NeedsReconnect, VadeErrorMapping.categoryOf(e.code))
            client.close()
        }
    }

    @Test
    fun internalErrorKeepsItsC04NameAndClassifiesAsNeedsReconnect() {
        val (client, source, transport) = connect()
        runBlocking {
            val call = async { runCatching { source.fetchPage("m1", "i1", "p1") } }
            val frame = transport.awaitSent()
            transport.push(errorFrame(idOf(frame), "INTERNAL", "boom"))
            val e = call.await().exceptionOrNull() as? VadeException
                ?: fail("fetchPage must fail with VadeException")
            assertEquals("INTERNAL", e.code, "500 类：C-04 名原样保留")
            assertEquals(VadeErrorCategory.NeedsReconnect, VadeErrorMapping.categoryOf(e.code))
            client.close()
        }
    }

    @Test
    fun timeoutIsClassifiedAsRetryable() {
        // 不回帧：客户端超时 → VadeException(TIMEOUT) → Retryable（重试是正确动作）。
        val (client, source, transport) = connect { it.copy(requestTimeoutMs = 100) }
        runBlocking {
            val e = assertFailsWith<VadeException> { source.fetchPage("m1", "i1", "p1") }
            assertEquals(VadeErrorCodes.TIMEOUT, e.code)
            assertEquals(VadeErrorCategory.Retryable, VadeErrorMapping.categoryOf(e.code))
            assertEquals(1, transport.record.size, "请求帧确实发出去了，只是没有回音")
            client.close()
        }
    }

    @Test
    fun transportNeverConnectedIsClassifiedAsNeedsReconnect() {
        val factory = FakeTransportFactory()
        val client = testClient(factory)
        val source = RealModulePageSource(client)
        runBlocking {
            val e = assertFailsWith<VadeException> { source.fetchPage("m1", "i1", "p1") }
            assertEquals(VadeErrorCodes.UNAVAILABLE, e.code, "传输层不可用 = TRANSPORT_CLOSED_CODE")
            assertEquals(VadeErrorCategory.NeedsReconnect, VadeErrorMapping.categoryOf(e.code))
            assertTrue(factory.transports.isEmpty(), "未连接：零 transport 零帧")
            client.close()
        }
    }
}
