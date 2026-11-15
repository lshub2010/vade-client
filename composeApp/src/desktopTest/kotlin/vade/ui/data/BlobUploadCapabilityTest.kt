package vade.ui.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import vade.ui.chat.AttachmentUploader
import vade.ui.chat.PendingAttachment
import vade.ui.chat.UploadFailedException
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.isMethodMissing
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.strStrict
import vade.ui.protocol.waitUntil
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 附件上传的**能力缺失降级**（统一判定 `vade.ui.protocol.isMethodMissing` 的第二处
 * 消费方）：`ui/blob.token` 未注册 → 上传明说「该 core 不支持附件上传」（可见提示），
 * 字节通道一次都不碰；真故障（TIMEOUT / UNAVAILABLE / 资源缺失 NOT_FOUND）原样穿透，
 * **绝不**被降级成「不支持」。
 *
 * 三层都钉住：
 *  1. [BlobUploadGate]（探测结论缓存：不支持粘住、真故障不缓存、token 保持 null）；
 *  2. [CapabilityGatedUploader]（降级文案 + 零字节触碰 + 放行委托）；
 *  3. `VadeClient.blobTokenProbe` 的**线上形态**（发出的方法名逐字 = `ui/blob.token`，
 *     错误帧两种形态都能到 [isMethodMissing]）。
 */
class BlobUploadCapabilityTest {

    // ---------------------------------------------------------------- 装置

    private val clients = mutableListOf<VadeClient>()

    private fun attachment(): PendingAttachment =
        PendingAttachment(name = "notes.txt", mime = "text/plain", sizeBytes = 40L)

    /** 字节通道：记录每一次交换；任何被碰到的交换都算「闸门没拦住」。 */
    private class CountingChannel : BlobHttpChannel {
        val calls = mutableListOf<String>()
        override suspend fun exchange(request: BlobHttpRequest): BlobHttpResponse {
            calls += "${request.method} ${request.path}"
            return BlobHttpResponse(500, "{}".toByteArray())
        }
    }

    /** 委托用假上传器：记录调用并返回一个 file id。 */
    private class RecordingUploader : AttachmentUploader {
        val calls = mutableListOf<String>()
        override suspend fun upload(
            request: PendingAttachment,
            uploadId: String,
            onProgress: (Float) -> Unit,
        ): String {
            calls += uploadId
            return "file-1"
        }
    }

    private suspend fun connectedClient(factory: FakeTransportFactory): Pair<VadeClient, FakeTransport> {
        val client = VadeClient(dataTestConfig(), factory)
        clients += client
        client.connect()
        assertTrue(client.awaitConnected())
        return client to factory.transports[0]
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { runBlocking { it.close() } }
    }

    // ------------------------------------------------- 1. 闸门：降级与粘住

    @Test
    fun shippedCoreShapeMarksUploadUnsupported() = runBlocking {
        val gate = BlobUploadGate {
            throw VadeException(VadeErrorCodes.NOT_FOUND, "unknown method 'ui/blob.token'")
        }
        gate.ensureProbed()
        val support = assertIs<BlobUploadSupport.Unsupported>(gate.support)
        assertTrue(
            support.reason.contains("unknown method"),
            "结论里保留 core 原话（凭什么说不支持）：${support.reason}",
        )
        assertNull(gate.token, "不支持 = 没有凭据（null），绝不编一个假 token")
    }

    @Test
    fun jsonRpc32601ShapeAlsoMarksUnsupported() = runBlocking {
        val gate = BlobUploadGate { throw VadeException("-32601", "method not found: ui/blob.token") }
        gate.ensureProbed()
        assertIs<BlobUploadSupport.Unsupported>(gate.support)
        assertNull(gate.token)
    }

    @Test
    fun realFaultsAreNotCachedAndNotUnsupported() = runBlocking {
        for (case in listOf(
            VadeErrorCodes.TIMEOUT to "no reply within 5000ms for ui/blob.token",
            VadeErrorCodes.UNAVAILABLE to "send failed for ui/blob.token",
            VadeErrorCodes.NOT_FOUND to "blob not found", // 资源缺失 ≠ 方法不存在
        )) {
            val gate = BlobUploadGate { throw VadeException(case.first, case.second) }
            val failure = assertFailsWith<VadeException> { runBlocking { gate.ensureProbed() } }
            assertEquals(case.first, failure.code, "${case.first} 必须原样穿透")
            assertIs<BlobUploadSupport.Unknown>(gate.support, "${case.first} 不得改写探测结论")
            assertNull(gate.token)
        }
    }

    @Test
    fun availableGateExtractsTheToken() = runBlocking {
        val gate = BlobUploadGate {
            buildJsonObject {
                put("token", "tok-abc123def")
                put("expires_at", 1_700_000_600_000L)
                put("max_bytes", 10_485_760L)
            }
        }
        assertIs<BlobUploadSupport.Available>(gate.ensureProbed())
        assertEquals("tok-abc123def", gate.token)
    }

    // ------------------------------------------------- 2. 闸门上传器：可见提示 + 零触碰

    @Test
    fun unsupportedUploadShowsTheVisibleHintAndTouchesNoBytes() = runBlocking {
        val channel = CountingChannel()
        val gate = BlobUploadGate {
            throw VadeException(VadeErrorCodes.NOT_FOUND, "unknown method 'ui/blob.token'")
        }
        val uploader = CapabilityGatedUploader(
            gate = gate,
            delegate = BlobUploaderReal(channel = channel, byteSource = { ByteArray(40) }),
        )
        val failure = assertFailsWith<UploadFailedException> {
            runBlocking { uploader.upload(attachment(), "u-1") { } }
        }
        assertTrue(
            failure.message.orEmpty().contains("该 core 不支持附件上传"),
            "chip 上必须出现「不支持附件上传」的可见提示：${failure.message}",
        )
        assertTrue(
            failure.message.orEmpty().contains("能力缺失"),
            "提示必须说明「这不是网络或服务故障」：${failure.message}",
        )
        assertTrue(
            channel.calls.isEmpty(),
            "降级必须发生在字节通道之前 —— 一次交换都不许有（不是上传失败，是不支持）：${channel.calls}",
        )
        assertNull(gate.token)
    }

    @Test
    fun unsupportedIsStickyPerConnectionNoProbeStorm() = runBlocking {
        var probes = 0
        val gate = BlobUploadGate {
            probes += 1
            throw VadeException(VadeErrorCodes.NOT_FOUND, "unknown method 'ui/blob.token'")
        }
        val uploader = CapabilityGatedUploader(gate, RecordingUploader())
        assertFailsWith<UploadFailedException> { runBlocking { uploader.upload(attachment(), "u-1") { } } }
        assertFailsWith<UploadFailedException> { runBlocking { uploader.upload(attachment(), "u-2") { } } }
        assertEquals(1, probes, "「不支持」是结论，粘住 —— 每连接只探测一次，不发探测风暴")
    }

    @Test
    fun realFaultUploadPropagatesInsteadOfUnsupportedHint() = runBlocking {
        val recorder = RecordingUploader()
        val gate = BlobUploadGate {
            throw VadeException(VadeErrorCodes.TIMEOUT, "no reply within 5000ms for ui/blob.token")
        }
        val uploader = CapabilityGatedUploader(gate, recorder)
        val failure = assertFailsWith<VadeException> { runBlocking { uploader.upload(attachment(), "u-1") { } } }
        assertEquals(VadeErrorCodes.TIMEOUT, failure.code, "真故障原样穿透（可重试语义不变）")
        assertFalse(
            failure.message.orEmpty().contains("该 core 不支持附件上传"),
            "TIMEOUT 绝不许被说成「不支持」",
        )
        assertTrue(recorder.calls.isEmpty(), "探测失败时不得委托上传")
    }

    @Test
    fun resourceNotFoundProbeIsAFaultNotUnsupported() = runBlocking {
        val recorder = RecordingUploader()
        val gate = BlobUploadGate { throw VadeException(VadeErrorCodes.NOT_FOUND, "blob not found") }
        val uploader = CapabilityGatedUploader(gate, recorder)
        val failure = assertFailsWith<VadeException> { runBlocking { uploader.upload(attachment(), "u-1") { } } }
        assertEquals(VadeErrorCodes.NOT_FOUND, failure.code)
        assertIs<BlobUploadSupport.Unknown>(gate.support)
        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun supportedCoreDelegatesTheRealUpload() = runBlocking {
        val recorder = RecordingUploader()
        val gate = BlobUploadGate {
            buildJsonObject { put("token", "tok-abc123def") }
        }
        val uploader = CapabilityGatedUploader(gate, recorder)
        assertEquals("file-1", uploader.upload(attachment(), "u-1") { }, "放行后一字不差委托真通道")
        assertEquals(listOf("u-1"), recorder.calls)
    }

    // ------------------------------------------------- 3. 线上形态：blobTokenProbe

    @Test
    fun blobTokenProbeRidesTheExactMethodNameAndSurfacesTheShippedShape() = runBlocking {
        val factory = FakeTransportFactory()
        val (client, transport) = connectedClient(factory)
        // runCatching 放进 async（与 ModelActionsRealTest 同一纪律）：async 子协程自己
        // 不失败，异常以 Result 交付 —— 否则父 runBlocking 收尾会二次上报，把这条
        // VadeException 泼到下一个跑的用例头上（实测污染过 gate 用例）。
        val deferred = async(Dispatchers.Default) { runCatching { client.blobTokenProbe() } }
        waitUntil { transport.record.size >= 1 }
        assertEquals(
            "ui/blob.token",
            requestOf(transport.record[0]).first,
            "探测线上方法名必须逐字是 ui/blob.token（RFC-0004 §4.5 / DRAFT-blob-token）",
        )
        assertEquals("{}", requestOf(transport.record[0]).second, "params 固定 {}（ttl 省略 = 默认 600 s）")
        transport.push(
            errorFrame(idOf(transport.record[0]), VadeErrorCodes.NOT_FOUND, "unknown method 'ui/blob.token'"),
        )
        val failure = assertIs<VadeException>(
            deferred.await().exceptionOrNull(),
            "探测必须以 VadeException 失败（NOT_FOUND 形态）",
        )
        assertEquals(VadeErrorCodes.NOT_FOUND, failure.code)
        assertTrue(
            failure.isMethodMissing(),
            "今天的 core 真实形态（NOT_FOUND + unknown method）必须被统一判定识别",
        )
    }

    @Test
    fun blobTokenProbeSurfacesTheJsonRpc32601ShapeToo() = runBlocking {
        val factory = FakeTransportFactory()
        val (client, transport) = connectedClient(factory)
        val deferred = async(Dispatchers.Default) { runCatching { client.blobTokenProbe() } }
        waitUntil { transport.record.size >= 1 }
        transport.push(errorFrame(idOf(transport.record[0]), "-32601", "method not found: ui/blob.token"))
        val failure = assertIs<VadeException>(
            deferred.await().exceptionOrNull(),
            "探测必须以 VadeException 失败（-32601 形态）",
        )
        assertTrue(failure.isMethodMissing(), "-32601（老实现形态）同样被识别")
    }

    @Test
    fun blobTokenProbeReturnsTheRawReplyObjectOnSuccess() = runBlocking {
        val factory = FakeTransportFactory()
        val (client, transport) = connectedClient(factory)
        val deferred = async(Dispatchers.Default) { client.blobTokenProbe() }
        waitUntil { transport.record.size >= 1 }
        transport.push(
            responseFrame(
                idOf(transport.record[0]),
                """{"token":"tok-abc123def","expires_at":1700000600000,"max_bytes":10485760,"endpoints":{}}""",
            ),
        )
        val reply: JsonObject = deferred.await()
        assertEquals("tok-abc123def", reply.strStrict("token"), "成功回包原文上交（本层不解码）")
    }
}
