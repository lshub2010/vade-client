package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import vade.ui.chat.ChatTranscriptSource
import vade.ui.chat.InMemoryAttachmentUploader
import vade.ui.chat.InMemoryTurnGateway
import vade.ui.chat.PendingAttachment
import vade.ui.meta.FakeMetaActions
import vade.ui.model.InMemoryModelActions
import vade.ui.module.ModulePageSources
import vade.ui.module.RealModulePageSource
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.protocol.waitUntil
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [AdapterProvider] / [RealAdapters] / [AdapterHost] 的结构测试。
 *
 * 守三件事：
 *  1. **交出来的都是真类型** —— 六件窄接口各自 `is` 到真实的 RPC 适配器，一件都不许
 *     落回 `Fake*` / `InMemory*` / `DemoSource`；
 *  2. **假数据源不给假适配器** —— 假源不是 [AdapterHost]，`(source as? AdapterHost)`
 *     恒为 null；真源未连接也是 null（与"没有真数据源"同义），连接后才是真包，断开后
 *     又回到 null（协议客户端 close 后不可重启，适配器必须跟着换）；
 *  3. **不泄漏 `VadeClient`** —— 反射扫 [AdapterProvider] 的方法签名：参数、返回值和
 *     泛型实参里**一个 `vade.ui.protocol` 类型都不能出现**，方法集合恰好是约定的六个
 *     （将来任何人往接口上加 `client()`，这条会红）。
 *
 * 另外覆盖：空 sessionId 的明确行为（查找不崩、RPC 边界以 `INVALID_ARGUMENT` 拒绝且
 * 零帧）、适配器绑的是**活 client**（连上后 rename 真的发帧）、未接线 blob 通道用
 * `UNAVAILABLE` 拒绝而不是假成功、注入通道后走真分块上传。
 */
class VadeAdaptersTest {

    // ---------------------------------------------------------------- helpers

    /** 未连接的 client 就足够做类型/身份断言；不建 socket。 */
    private fun idleClient(): VadeClient = VadeClient(dataTestConfig(), FakeTransportFactory())

    /** 连上 FakeTransport 的 client + 那条 transport（真帧断言用）。 */
    private suspend fun connectedClient(factory: FakeTransportFactory): VadeClient {
        val client = testClient(factory)
        client.connect()
        assertTrue(client.awaitConnected())
        return client
    }

    private fun attachment(
        name: String = "notes.txt",
        mime: String = "text/plain",
        size: Long = 40L,
    ): PendingAttachment = PendingAttachment(name = name, mime = mime, sizeBytes = size)

    // ------------------------------------------------------- 1. 真类型 / 非假实现

    @Test
    fun everyLookupHandsOutTheRealAdapterType() {
        val client = idleClient()
        val provider: AdapterProvider = RealAdapters(client)

        assertIs<MetaActionsReal>(provider.meta("s-1"), "meta() 必须是 MetaActionsReal")
        assertIs<ModelActionsReal>(provider.model("s-1"), "model() 必须是 ModelActionsReal")
        assertIs<ModelActionsReal>(provider.modelDefault(), "modelDefault() 必须是 ModelActionsReal")
        assertIs<TurnGatewayReal>(provider.turns("s-1"), "turns() 必须是 TurnGatewayReal")
        assertIs<BlobUploaderReal>(provider.uploader(), "uploader() 必须是 BlobUploaderReal")
        assertIs<RealModulePageSource>(provider.modulePages(), "modulePages() 必须是 RealModulePageSource")
        runBlocking { client.close() }
    }

    @Test
    fun noLookupFallsBackToAFixtureImplementation() {
        val client = idleClient()
        val provider: AdapterProvider = RealAdapters(client)

        assertFalse(provider.meta("s-1") is FakeMetaActions, "假适配器混进真包：meta")
        assertFalse(provider.model("s-1") is InMemoryModelActions, "假适配器混进真包：model")
        assertFalse(provider.turns("s-1") is InMemoryTurnGateway, "假适配器混进真包：turns")
        assertFalse(provider.uploader() is InMemoryAttachmentUploader, "假适配器混进真包：uploader")
        assertNotSame(ModulePageSources.demo(), provider.modulePages(), "假适配器混进真包：modulePages")

        // 真类型的可观察差异：真 model 适配器尚未拉取 → models 为 null（"无模型面"），
        // 而 InMemoryModelActions 默认给空列表/固定表。
        assertNull(provider.model("s-1").models, "未拉取前 models 必须是 null（不可用），不是空列表")
        runBlocking { client.close() }
    }

    // ------------------------------------------------------- 2. 同一会话可重复取用

    @Test
    fun repeatedLookupsForTheSameSessionAreTheSameUsableInstance() {
        val client = idleClient()
        val provider: AdapterProvider = RealAdapters(client)

        assertSame(provider.meta("s-1"), provider.meta("s-1"))
        assertSame(provider.model("s-1"), provider.model("s-1"))
        assertSame(provider.turns("s-1"), provider.turns("s-1"))
        assertSame(provider.uploader(), provider.uploader())
        assertSame(provider.modulePages(), provider.modulePages())

        // "可用"不只是身份：再取一次仍然回答（无状态读取 + 空缓存语义）。
        assertEquals(false, provider.model("s-1").readOnly)
        assertNull(provider.model("s-1").sessionOverride("s-1"))
        assertNull(provider.modulePages().loadPage("m", "p"))
        assertNull(provider.modulePages().loadState("m", "i", "p"))
        runBlocking { client.close() }
    }

    @Test
    fun differentSessionsShareOneStatelessAdapterPerSeam() {
        val client = idleClient()
        val provider: AdapterProvider = RealAdapters(client)

        // 五个适配器都与 session_id 无关（覆盖值由 ModelActionsReal 内部按 id 缓存），
        // 所以不同会话取到的必须是同一份——多建实例会把同一份服务端状态拆成互不可见的副本。
        assertSame(provider.meta("s-1"), provider.meta("s-2"))
        assertSame(provider.model("s-1"), provider.model("s-2"))
        assertSame(provider.turns("s-1"), provider.turns("s-2"))
        runBlocking { client.close() }
    }

    @Test
    fun bothModelScopesAreServedByOneAdapter() {
        val client = idleClient()
        val provider: AdapterProvider = RealAdapters(client)
        // 会话级与全局默认共用同一个 ModelActionsReal：一次 model/list 两处刷新，
        // defaultModelId / models / readOnly 不会互相打脸。
        assertSame(provider.model("s-1"), provider.modelDefault())
        runBlocking { client.close() }
    }

    // ------------------------------------------------------- 3. 空 sessionId

    @Test
    fun blankSessionIdIsNotRejectedByTheLookupItself() {
        val client = idleClient()
        val provider: AdapterProvider = RealAdapters(client)
        // 查找不是校验：provider 永不抛（它常被组合期直接调用）。
        assertNotNull(provider.meta(""))
        assertNotNull(provider.model(""))
        assertNotNull(provider.turns(""))
        runBlocking { client.close() }
    }

    @Test
    fun blankSessionIdIsRefusedAtTheRpcBoundaryWithZeroFrames() {
        val factory = FakeTransportFactory()
        runBlocking {
            val client = connectedClient(factory)
            val meta = RealAdapters(client).meta("")
            val failure = assertFailsWith<VadeException> { meta.renameSession("", "标题") }
            assertEquals(VadeErrorCodes.INVALID_ARGUMENT, failure.code)
            assertEquals(0, factory.transports[0].record.size, "空 session_id 绝不能发出帧")
            client.close()
        }
    }

    // ------------------------------------------------------- 4. AdapterHost

    @Test
    fun fakeSourceNeverOffersAdapters() {
        val fake: SessionSource = FakeSessionSource()
        // 假数据源**不实现** AdapterHost：探测恒为 null，假数据不会拿到假适配器。
        // （SessionSource 接口一个字未改 —— 574 条基线测试的其它实现全部不受影响。）
        assertNull((fake as? AdapterHost)?.adapters)
        assertFalse(fake is AdapterHost, "假源不该冒充 AdapterHost")
    }

    @Test
    fun realSourceIsAnAdapterHostAndIsNullBeforeConnecting() {
        val source = RealSessionSource(dataTestConfig(), FakeTransportFactory())
        assertIs<AdapterHost>(source)
        assertNull(source.adapters, "未连接的 RealSessionSource 不交适配器（没有 client）")
    }

    @Test
    fun adaptersAppearWithTheConnectionAndDisappearWithIt() {
        val factory = FakeTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)
        runBlocking {
            assertNull(source.adapters)
            source.connect()
            waitUntil { source.state.value is RealSessionState.Connected }
            val provider = assertNotNull(source.adapters, "连接后必须交出真适配器包")
            assertIs<MetaActionsReal>(provider.meta("s-1"))
            assertIs<ModelActionsReal>(provider.model("s-1"))
            // 组合根带了能力闸门（ui/blob.token 探测）：上传器是闸门包装，
            // 闸门内的真通道必须仍是 BlobUploaderReal —— 见 BlobUploadCapabilityTest。
            val gated = assertIs<CapabilityGatedUploader>(provider.uploader())
            assertIs<BlobUploaderReal>(gated.realUploader)
            assertIs<RealModulePageSource>(provider.modulePages())
            source.disconnect()
            assertNull(source.adapters, "断开后适配器必须清空（client 已 close，不可重启）")
        }
    }

    @Test
    fun adaptersAreBoundToTheLiveClientAndSendRealFrames() {
        val factory = FakeTransportFactory()
        val source = RealSessionSource(dataTestConfig(), factory)
        runBlocking {
            source.connect()
            waitUntil { source.state.value is RealSessionState.Connected }
            val transport = factory.transports[0]
            val meta = assertNotNull(source.adapters).meta("s-1")
            val call = async(Dispatchers.Default) { meta.renameSession("s-1", "新标题") }
            waitUntil { transport.record.size >= 1 }
            // 这一帧就是"适配器绑的是活 client"的证据：它走的是同一个 socket。
            assertEquals("session/rename", requestOf(transport.record[0]).first)
            assertEquals(
                """{"session_id":"s-1","title":"新标题"}""",
                requestOf(transport.record[0]).second,
            )
            transport.push(
                responseFrame(
                    idOf(transport.record[0]),
                    """{"ok":true,"session":${sessionRow("s-1", 1_700_000_000_000)}}""",
                ),
            )
            call.await()
            source.disconnect()
        }
    }

    // ------------------------------------------------------- 5. 不泄漏 VadeClient

    @Test
    fun adapterProviderSurfaceNeverMentionsTheProtocolClient() {
        val declared = AdapterProvider::class.java.declaredMethods.filter { !it.isSynthetic }
            .filter { it.name != "equals" && it.name != "hashCode" && it.name != "toString" }

        assertEquals(
            setOf("meta", "model", "modelDefault", "turns", "uploader", "modulePages"),
            declared.map { it.name }.toSet(),
            "AdapterProvider 的窄接口集合必须恰好是约定的六个（不得悄悄加宽）",
        )

        val surface = declared.flatMap { method -> method.surfaceTypes() }
        assertTrue(surface.isNotEmpty())
        assertFalse(
            surface.any { it.typeName.contains("VadeClient") },
            "AdapterProvider 的公开签名里出现了 VadeClient：${surface.map { it.typeName }}",
        )
        assertFalse(
            surface.any { it.typeName.startsWith("vade.ui.protocol") },
            "AdapterProvider 不该出现任何 vade.ui.protocol 类型：${surface.map { it.typeName }}",
        )
    }

    /** 方法的参数/返回值 + 一层泛型实参（够用：窄接口只到 `List<ModelEntry>?` 这一层）。 */
    private fun Method.surfaceTypes(): List<Type> {
        val roots = listOf(genericReturnType) + genericParameterTypes.toList()
        return roots.flatMap { type ->
            val args = (type as? ParameterizedType)?.actualTypeArguments.orEmpty().toList()
            listOf(type) + args
        }
    }

    // ------------------------------------------------------- 6. blob 通道

    @Test
    fun uploaderWithoutAnInjectedChannelRefusesWithUnavailable() {
        val client = idleClient()
        val uploader = RealAdapters(client).uploader()
        val failure = assertFailsWith<VadeException> {
            runBlocking { uploader.upload(attachment(), "u-1") { } }
        }
        assertEquals(VadeErrorCodes.UNAVAILABLE, failure.code)
        assertTrue(
            failure.message.orEmpty().contains("no blob HTTP channel is wired"),
            "拒绝理由必须点名缺失的接线：${failure.message}",
        )
        runBlocking { client.close() }
    }

    @Test
    fun uploaderUsesTheInjectedChannelForRealBlobExchanges() {
        val client = idleClient()
        val channel = RecordingChannel()
        channel.handler = {
            BlobHttpResponse(
                200,
                (
                    "{\"file_id\":\"blb_real_1\",\"upload_id\":null,\"dedupe\":true,\"size\":40," +
                        "\"mime\":\"text/plain\",\"pinned\":false,\"expires_at\":1700000000000," +
                        "\"part_size\":65536}"
                    ).toByteArray(),
            )
        }
        val uploader = RealAdapters(
            client = client,
            blobChannel = channel,
            blobByteSource = { ByteArray(40) { 'a'.code.toByte() } },
        ).uploader()

        val fileId = runBlocking { uploader.upload(attachment(), "u-1") { } }
        assertEquals("blb_real_1", fileId)
        // 真 HTTP 交换（不是内存假实现）：一次 POST /ui/blob，带 sha256/size/mime/name。
        assertEquals(1, channel.calls.size)
        assertEquals("POST", channel.calls[0].method)
        assertEquals("/ui/blob", channel.calls[0].path)
        val body = assertNotNull(channel.calls[0].jsonBody)
        assertEquals("text/plain", body["mime"].toString().trim('"'))
        assertEquals("notes.txt", body["name"].toString().trim('"'))
        assertEquals("40", body["size"].toString())
        runBlocking { client.close() }
    }

    // ---- B2 · 聊天读通道（history + events）的装配 -------------------------------

    /**
     * 未注入读通道时 `transcript()` 必须是 **null**，不是一个空流。
     *
     * 空流会让 `ChatState` 以为「接上了，但 core 里真的没消息」——那是对用户的谎；
     * null 才会让它进失败态并明说「没有读历史的通道」。
     */
    @Test
    fun realAdaptersWithoutATranscriptSaySoInsteadOfFakingAnEmptyOne() {
        val client = idleClient()
        val provider: AdapterProvider = RealAdapters(client)
        assertNull(provider.turns("s-1").transcript())
        runBlocking { client.close() }
    }

    /** 注入了就**原样**交出去：同一个实例，不复制、不包装、不降级成 InMemory。 */
    @Test
    fun realAdaptersHandOutTheInjectedTranscriptVerbatim() {
        val client = idleClient()
        val source = ChatTranscriptSource.of(
            history = { emptyList() },
            events = { emptyFlow() },
        )
        val provider: AdapterProvider = RealAdapters(client, chatTranscript = source)
        assertSame(source, provider.turns("s-1").transcript())
        runBlocking { client.close() }
    }

    /** history / events 真的被调到（不是接上之后没人用）。 */
    @Test
    fun injectedTranscriptIsActuallyDelegatedTo() = runBlocking {
        val client = idleClient()
        var askedFor: String? = null
        var eventsCalls = 0
        val source = ChatTranscriptSource.of(
            history = { id -> askedFor = id; emptyList() },
            events = { eventsCalls++; emptyFlow() },
        )
        val transcript = assertNotNull(RealAdapters(client, chatTranscript = source).turns("s-1").transcript())
        assertEquals(emptyList(), transcript.history("s-42"))
        assertEquals("s-42", askedFor)
        transcript.events()
        assertEquals(1, eventsCalls)
        client.close()
    }

    /**
     * 装配守卫：聊天读通道的 `events` 必须是**原始协议流**（`created.events`），
     * 不能是 `observeEvents()`。
     *
     * 坑在于仓里有**两个** `VadeEvent`：
     *  - `vade.ui.protocol.VadeEvent` —— 协议事件（`message.appended` / `message.edited` / `turn.*` 全在）；
     *  - `vade.ui.data.VadeEvent` —— `RealSessionSource.mapEvent` 的**有损投影**，注释原话
     *    「六种核心事件 → UI 事件；**其余全部丢弃**」。
     *
     * 接 `observeEvents()` 会得到一个**永远收不到消息事件**的流：编译通过、历史照常显示、
     * 测试也绿——但聊天永远不会实时。这正是「接上了但没接对」那一类假接线，
     * 没有真传输时只能靠源码把**写了哪个表达式**钉住。
     */
    @Test
    fun chatTranscriptIsWiredToTheProtocolStreamNotTheLossyProjection() {
        val src = File("src/commonMain/kotlin/vade/ui/data/RealSessionSource.kt")
        assertTrue(src.isFile, "找不到 ${src.absolutePath}")
        val text = src.readText()
        val at = text.indexOf("ChatTranscriptSource.ofSnapshot")
        assertTrue(at > 0, "RealSessionSource 里应有一段 ChatTranscriptSource.ofSnapshot 装配")
        val slice = text.substring(at, minOf(text.length, at + 400))
        assertTrue(
            Regex("""snapshot\s*=\s*\{\s*sessionId\s*->\s*chatSnapshot\(sessionId\)\s*\}""").containsMatchIn(slice),
            "snapshot 必须走一次 ui/attach 的 chatSnapshot（消息 + pending_inputs 同一份快照），实际块：\n$slice",
        )
        assertTrue(
            Regex("""events\s*=\s*\{\s*created\.events\s*\}""").containsMatchIn(slice),
            "events 必须接 created.events（原始协议流），实际块：\n$slice",
        )
        assertFalse(
            slice.contains("observeEvents"),
            "不得把 observeEvents() 接给聊天读通道——它是 mapEvent 的有损投影，会丢掉 message.* 事件",
        )
    }

    /**
     * 装配守卫：聊天快照必须把 `snapshot.pending_inputs` 一起带出来。
     *
     * RFC-0008 §8.2 要求未作答的问答从快照重建；字段被解析（`VadeSnapshot.pendingInputs`）
     * 但没人消费，正是 F8 的 P1-1（重进 / 重连后卡永久消失）。这条钉住消费点本身。
     */
    @Test
    fun chatSnapshotCarriesThePendingInputsSoTheDialogCanRebuild() {
        val text = File("src/commonMain/kotlin/vade/ui/data/RealSessionSource.kt").readText()
        assertTrue(
            Regex("""pendingInputs\s*=\s*result\.snapshot\?\.pendingInputs\.orEmpty\(\)""").containsMatchIn(text),
            "chatSnapshot 必须把 snapshot.pending_inputs 带进 ChatHistory（否则重进会话卡不会回来）",
        )
    }

    /** 记录型通道（只有类型是真的，网络是内存——与 `BlobUploaderRealTest` 同款）。 */
    private class RecordingChannel : BlobHttpChannel {
        val calls = mutableListOf<BlobHttpRequest>()
        var handler: suspend (BlobHttpRequest) -> BlobHttpResponse = {
            BlobHttpResponse(500, "".toByteArray())
        }

        override suspend fun exchange(request: BlobHttpRequest): BlobHttpResponse {
            calls.add(request)
            return handler(request)
        }
    }
}
