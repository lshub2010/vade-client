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
import vade.ui.protocol.lng
import vade.ui.protocol.strStrict
import kotlin.concurrent.Volatile

/**
 * 附件上传能力的**探测与降级** —— 统一判定 `vade.ui.protocol.isMethodMissing` 的第二处
 * 消费方（第一处是 `ModuleCatalog` 的 `ui/modules`）。
 *
 * ## 凭据从哪来、为什么仍叫「探测」
 *
 * 附件上传的走线是数据面 HTTP（`POST /ui/blob` …，RFC-0004 §4.1），原生（Noise 配对）
 * 设备对它的唯一合法凭据来自 **`ui/blob.token`**（RFC-0016 §4：从已认证 ws 会话为设备
 * 铸 Bearer；契约 **1.7.0** 已把它登记进 `directions.ui_to_core`，客户端
 * `VadeMethod.BLOB_TOKEN` 同批登记 —— 探测走 `VadeClient.blobTokenProbe` 的**类型化
 * 普通路径**，曾经的裸帧绕行是契约还钉在 1.6.0 时的临时手段，已收掉）。老 core
 * （RFC-0016 之前）没有这个方法，所以：
 *
 *  - 探测答「方法不存在」（真实形态是 `NOT_FOUND` + `unknown method 'ui/blob.token'`，
 *    实跑钉住；老实现可能是 `-32601`）→ **该 core 不支持附件上传**，
 *    上传明说这一点（[BLOB_UPLOAD_UNSUPPORTED_MESSAGE]），绝不包装成 UNAVAILABLE 故障；
 *  - 探测成功 → core 会铸 token（[BlobUploadGate.token]，缓存至 `expires_at` 前 30 s），
 *    上传委托真通道；
 *  - 探测本身失败（TIMEOUT / UNAVAILABLE / …）→ **真故障原样穿透**，探测结论不被改写
 *    —— 绝不当「不支持」（真故障与能力缺失是两个世界，各有各的专门用例钉着）。
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
 * token 缓存的**新鲜余量**：只在 `now < expires_at - 30s` 时复用已铸的 token，
 * 越过余量就在下一次上传前重铸 —— 不在失效边缘上传（一次上传是 begin + N parts +
 * complete 的链路，贴边的 token 中途失效会把上传切成 401）。
 */
internal const val BLOB_TOKEN_REFRESH_MARGIN_MS: Long = 30_000L

/**
 * `ui/blob.token` 类型化往返的**结论与凭据缓存**（每连接一份 —— [RealAdapters] 随 client
 * 生灭）。
 *
 *  - **单飞**：并发首次上传只在锁内发一帧，其余等结论；
 *  - **降级是结论**：`isMethodMissing` 命中后 [support] 置「不支持」并**粘住** ——
 *    之后每次上传立刻明说，不再发帧（core 升级要走重连：新 client → 新 gate → 重新探测）；
 *  - **新鲜窗口**：成功结论按 `expires_at` 缓存 —— `now < expires_at - 30s` 时复用、
 *    零帧返回；越过余量（或回包缺 `expires_at`，无法证明新鲜 —— fail-safe）→ 下一次
 *    上传**重铸**。重铸撞上真故障 → 原样抛出、已有结论不被改写（可重试）；重铸撞上
 *    方法缺失 → 「不支持」粘住（core 在两次铸发之间被降级也算老 core）。
 *  - **真故障不缓存**：探测 TIMEOUT/UNAVAILABLE 时原样抛出、结论不被改写，
 *    下一次上传重新探测（可重试语义原样保留）。
 */
internal class BlobUploadGate(
    /**
     * 墙钟（epoch ms）—— 新鲜窗口判定用，注入以便测试钉住 30 s 边界。
     * 生产取 `epochNowMs()`（kotlin.time.Clock，commonMain 无平台分叉）。
     * 排在**第一位**且带默认值：让既有调用点的尾随 lambda 依旧绑定 [probe]
     * （`BlobUploadGate { client.blobTokenProbe() }` 一字不改）。
     */
    private val nowMs: () -> Long = ::epochNowMs,
    /** 唯一依赖：一次 `ui/blob.token` 类型化往返。生产是 `{ client.blobTokenProbe() }`。 */
    private val probe: suspend () -> JsonObject,
) {

    var support: BlobUploadSupport by mutableStateOf(BlobUploadSupport.Unknown)
        private set

    /**
     * 只在**新鲜窗口内**交出（`now < expires_at - 30s`）；Unknown / 不支持 / 已过期 /
     * 临近过期 / 回包缺 `expires_at` 一律 `null` —— 绝不编一个假 token，也绝不把可能
     * 已失效的凭据交出去（重铸由下一次 [ensureProbed] 触发）。
     */
    val token: String?
        get() {
            val reply = (support as? BlobUploadSupport.Available)?.reply ?: return null
            if (!replyFresh(reply)) return null
            return reply.strStrict("token")
        }

    /** 结论已定（「不支持」粘住 / 成功已缓存）：true = 探测这一步不必再走。 */
    @Volatile
    private var settled = false

    private val mutex = Mutex()

    /** 探测/取凭据（幂等）：结论已定且回包新鲜时**零帧**返回；真故障以 [VadeException] 原样穿透。 */
    suspend fun ensureProbed(): BlobUploadSupport {
        if (settledAndFresh()) return support
        return mutex.withLock {
            if (settledAndFresh()) {
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
                settled = true
                support
            }
        }
    }

    /** 不再发帧的两个条件同时成立：结论已定，且（成功时）回包仍在新鲜窗口内。 */
    private fun settledAndFresh(): Boolean {
        if (!settled) return false
        return when (val s = support) {
            is BlobUploadSupport.Unsupported -> true // 「不支持」是终局结论，粘住
            is BlobUploadSupport.Available -> replyFresh(s.reply)
            BlobUploadSupport.Unknown -> false // 防御：settled 不该停在 Unknown；宁可再探测一次
        }
    }

    /** `expires_at` 缺失/不可读 = 无法证明新鲜（fail-safe：每次重铸，绝不无限持有旧凭据）。 */
    private fun replyFresh(reply: JsonObject): Boolean {
        val expiresAt = reply.lng("expires_at") ?: return false
        return nowMs() < expiresAt - BLOB_TOKEN_REFRESH_MARGIN_MS
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
