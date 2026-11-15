package vade.ui.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import vade.ui.chat.AttachmentUploader
import vade.ui.chat.PendingAttachment
import vade.ui.chat.UploadFailedException
import vade.ui.protocol.VadeException
import vade.ui.protocol.isMethodMissing
import vade.ui.protocol.strStrict
import kotlin.concurrent.Volatile

/**
 * 附件上传能力的**探测与降级** —— 统一判定 `vade.ui.protocol.isMethodMissing` 的第二处
 * 消费方（第一处是 `ModuleCatalog` 的 `ui/modules`）。
 *
 * ## 探针为什么是 `ui/blob.token`
 *
 * 附件上传的走线是数据面 HTTP（`POST /ui/blob` …，RFC-0004 §4.1），原生（Noise 配对）
 * 设备对它的唯一合法凭据来自 **`ui/blob.token`**（RFC-0004 §4.5：从已认证 ws 会话铸
 * 600 s 设备 Bearer；见 `contracts/vade/docs/rfc/DRAFT-blob-token.md`）。这个方法
 * **今天的 core 还没实现**（`core/vade/blob/RESULT.md` 明记），所以：
 *
 *  - 探测答「方法不存在」（今天的真实形态是 `NOT_FOUND` + `unknown method
 *    'ui/blob.token'`，实跑钉住；老实现可能是 `-32601`）→ **该 core 不支持附件上传**，
 *    上传明说这一点（[BLOB_UPLOAD_UNSUPPORTED_MESSAGE]），绝不包装成 UNAVAILABLE 故障；
 *  - 探测成功 → core 会铸 token（[BlobUploadGate.token]），上传委托真通道；
 *  - 探测本身失败（TIMEOUT / UNAVAILABLE / …）→ **真故障原样穿透**，[BlobUploadGate.support]
 *    保持 [BlobUploadSupport.Unknown] —— 绝不当「不支持」（真故障与能力缺失是两个世界，
 *    各有各的专门用例钉着）。
 */
sealed interface BlobUploadSupport {

    /** 还没探测（首次上传前）。探测失败（真故障）不改变这里。 */
    data object Unknown : BlobUploadSupport

    /** 探测通过：core 会铸 token；[reply] 是 `{token, expires_at, max_bytes, endpoints}` 原文。 */
    data class Available(val reply: JsonObject) : BlobUploadSupport

    /**
     * core 没有这个方法（两种形态之一）→ **不支持附件上传**。[reason] 保留 core 原话
     * （`unknown method 'ui/blob.token'`），让「凭什么说不支持」可以核对。
     */
    data class Unsupported(val reason: String) : BlobUploadSupport
}

/** 探测确认「方法不存在」后，上传一律以此明说 —— 是可见提示，不是故障弹错。 */
internal const val BLOB_UPLOAD_UNSUPPORTED_MESSAGE: String =
    "该 core 不支持附件上传：core 版本过旧，没有 ui/blob.token 方法" +
        "（能力缺失，不是网络或服务故障）；升级 core 后可发附件。"

/**
 * `ui/blob.token` 探测的**结论缓存**（每连接一份 —— [RealAdapters] 随 client 生灭）。
 *
 *  - **单飞**：并发首次上传只在锁内发一帧，其余等结论；
 *  - **降级是结论**：`isMethodMissing` 命中后 [probed] 置 true，「不支持」粘住 ——
 *    之后每次上传立刻明说，不再发帧（core 升级要走重连：新 client → 新 gate → 重新探测）；
 *  - **真故障不缓存**：探测 TIMEOUT/UNAVAILABLE 时原样抛出、[probed] 保持 false，
 *    下一次上传重新探测（可重试语义原样保留）。
 */
internal class BlobUploadGate(
    /** 唯一依赖：一次 `ui/blob.token` 裸往返。生产是 `{ client.blobTokenProbe() }`。 */
    private val probe: suspend () -> JsonObject,
) {

    var support: BlobUploadSupport by mutableStateOf(BlobUploadSupport.Unknown)
        private set

    /**
     * 探测成功且回包带 `token` 才非空；[BlobUploadSupport.Unknown] / 不支持时保持 `null`
     * —— 绝不编一个假 token（「集合保持 null」的那一格，测试钉住）。
     */
    val token: String?
        get() = (support as? BlobUploadSupport.Available)?.reply?.strStrict("token")

    @Volatile
    private var probed = false

    private val mutex = Mutex()

    /** 探测（幂等）：返回终态；真故障以 [VadeException] 原样穿透。 */
    suspend fun ensureProbed(): BlobUploadSupport {
        if (probed) return support
        return mutex.withLock {
            if (probed) {
                support
            } else {
                try {
                    support = BlobUploadSupport.Available(probe())
                } catch (e: VadeException) {
                    if (e.isMethodMissing()) {
                        support = BlobUploadSupport.Unsupported(e.message.orEmpty().ifBlank { e.code })
                    } else {
                        throw e
                    }
                }
                probed = true
                support
            }
        }
    }
}

/**
 * [AttachmentUploader] 的**能力闸门**：先探测、后委托。「方法不存在」→ 抛
 * [UploadFailedException]（消息 = [BLOB_UPLOAD_UNSUPPORTED_MESSAGE]，聊天页把它显示在
 * 附件 chip 的 detail 上 —— 可见提示）；探测通过 → 一字不差地委托真通道，进度与字节
 * 语义零改动。
 */
internal class CapabilityGatedUploader(
    private val gate: BlobUploadGate,
    private val delegate: AttachmentUploader,
) : AttachmentUploader {

    /** 闸门内的真通道（`VadeAdaptersTest` 钉住它必须是 [BlobUploaderReal]）。 */
    internal val realUploader: AttachmentUploader
        get() = delegate

    override suspend fun upload(
        request: PendingAttachment,
        uploadId: String,
        onProgress: (Float) -> Unit,
    ): String {
        val support = gate.ensureProbed()
        if (support is BlobUploadSupport.Unsupported) {
            throw UploadFailedException(BLOB_UPLOAD_UNSUPPORTED_MESSAGE)
        }
        return delegate.upload(request, uploadId, onProgress)
    }
}
