package vade.ui.data

import vade.ui.chat.AttachmentUploader
import vade.ui.chat.ChatTranscriptSource
import vade.ui.chat.ChatTurnGateway
import vade.ui.chat.PendingAttachment
import vade.ui.meta.SessionMetaActions
import vade.ui.model.ModelActions
import vade.ui.module.ModulePageSource
import vade.ui.module.RealModulePageSource
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException

/**
 * 组合根交给 UI 的**适配器包**：六件窄接口一次交齐，`VadeClient` 一步都不外泄。
 *
 * ## 为什么需要这一层（w-adapt / g-meta-wire / g-model-wire / g-modulewire 的结构结）
 * 五套真实现（[MetaActionsReal] / [ModelActionsReal] / [TurnGatewayReal] /
 * [BlobUploaderReal] / [RealModulePageSource]）都必须持 `VadeClient`，而 `VadeClient`
 * 只由 [RealSessionSource] 以 `internal` 持有（见 `RealSessionSource.client`）；UI 侧的
 * 构造点（`SessionDetailScreen` / `ProfileScreen` / `SessionMetaState` /
 * `ModelState.forSession` / `ChatState`）拿不到 client —— 工厂造得出来，接不上线。
 *
 * 结构上的解法**不是**把 client 暴露出去（那会让 UI 直接耦合协议客户端，正是项目
 * 一开始用窄接口要避免的事），而是：本文件提供一个只吐窄接口的包，由组合根
 * （[RealSessionSource]，即唯一持有 client 的地方）通过 [AdapterHost] 交给 UI。
 * UI 只看到 `vade.ui.meta` / `vade.ui.model` / `vade.ui.chat` / `vade.ui.module`
 * 的接口和 `vade.ui.data` 的两个新接口 —— **`AdapterProvider` 的方法签名里没有任何
 * `vade.ui.protocol` 类型**（`VadeAdaptersTest` 用反射守着这条）。
 *
 * ## 谁该实现 / 谁该调用
 * - 实现者：只有 [RealSessionSource]（`data/RealAdapters.kt` 的 [RealAdapters]）。
 *   假数据源**不实现** [AdapterHost]，于是 `(source as? AdapterHost)?.adapters` 为
 *   `null` —— 假数据不会拿到假适配器（`SessionSource` 接口本身一个字未改）。
 * - 调用者：三端入口把同一个 `SessionSource` 交给 UI 后，页面用
 *   `(source as? AdapterHost)?.adapters?.model(session.id) ?: <现有假实现>` 取用；
 *   取不到就退回本轮的内存 fixtures，行为与接线前完全一致。
 *
 * ## 生命周期契约（重要）
 * [AdapterHost.adapters] **随连接存在**：`RealSessionSource.connect()` 建 client 时
 * 一并建出适配器，`disconnect()` 时清空（协议客户端 close 后不可重启，适配器必须跟着
 * 换）。因此：
 *  - 未连接 / 断开后 → `null`（调用方退回假实现，与"没有真数据源"同义）；
 *  - 连接中 / 已连接 → 非空，但 RPC 在 socket 真正 up 之前会以 `UNAVAILABLE` 拒绝
 *    （协议层的既有语义，本层不吞、不改名）。
 *  UI 若在组合期读到 `null`，应把 `source.state`（`StateFlow<RealSessionState>`）作为
 * 重算键，或用 `?: 假实现` 兜底 —— 后者永远安全。
 */
interface AdapterProvider {

    /**
     * `session/rename` 等九个写方法与 `session/group.*`（[SessionMetaActions]）。
     *
     * [sessionId] 是**前瞻签名**：当前的真实现（[MetaActionsReal]）无状态、每次调用自带
     * session_id，因此这个参数不使用；保留它是为了让将来的按会话缓存实现能换进来而不改
     * 调用点。**不做空白校验**——本方法是查找，不是校验；真正的参数校验留在 RPC 边界
     * （空白 id 在发帧前以 `INVALID_ARGUMENT` 拒绝，见 `MetaActionsReal`）。
     */
    fun meta(sessionId: String): SessionMetaActions

    /** 会话级模型选择（`model/list` + `session/model.get` + `session/model.set`）。 */
    fun model(sessionId: String): ModelActions

    /**
     * 全局默认模型视图（设置页）。与 [model] 返回**同一个**对象：`ModelActionsReal`
     * 内部以 session_id 为键缓存覆盖值，`models` / `defaultModelId` / `readOnly` 是
     * 全局面，两个作用域共用一份拉取结果才不会互相打脸（一次 `model/list`，两处刷新）。
     */
    fun modelDefault(): ModelActions

    /** `message/edit_resubmit` + `turn/regenerate`（[ChatTurnGateway]）。 */
    fun turns(sessionId: String): ChatTurnGateway

    /**
     * `/ui/blob*` 分块上传（[AttachmentUploader]）。
     *
     * 字节通道不属于 JSON-RPC（RFC-0004 §4.1），凭据由 shell 持有（设备
     * `Authorization` 头 / 浏览器 `__Host-ac_ui` cookie + `x-vade-csrf`），所以
     * [RealAdapters] 允许注入 `BlobHttpChannel` / `BlobByteSource`。**没注入时返回的
     * 仍是真正的 [BlobUploaderReal]**，只是每次交换都以 `UNAVAILABLE` 明确拒绝
     * —— 绝不假装上传成功。
     */
    fun uploader(): AttachmentUploader

    /**
     * 模块页宿主（`ui/page.get` / `ui/state.get`）。
     *
     * 返回的 [vade.ui.module.ModulePageSource] 接口是同步缓存读语义；真 RPC 的
     * 挂起入口是具体实现 `RealModulePageSource.fetchPage/fetchState`，宿主需要拉取时
     * 由组合根持有具体类型调用（见 `shots/g-adapter-bundle/RESULT.md` §遗留）。
     */
    fun modulePages(): ModulePageSource
}

/**
 * 「我能交出适配器包」的能力接口 —— 只由真数据源实现。
 *
 * 单独一个接口而不是加在 [SessionSource] 上：`SessionSource` 有别的实现与 574 条测试
 * 依赖，改它会打断所有实现；而适配器是**可选能力**，`(source as? AdapterHost)` 的
 * 可空探测正好表达了"这个源没有真适配器"。假数据源不实现它 → `adapters == null`。
 */
interface AdapterHost {
    /** 连接期间非空；未连接 / 假数据源 → `null`（调用方退回内存 fixtures）。 */
    val adapters: AdapterProvider?
}

/**
 * [AdapterProvider] 的唯一真实现：内部持 [VadeClient]，对外只给窄接口。
 *
 * `internal` 是故意的 —— 组合根之外的任何代码都拿不到它，于是"UI 层要拿到适配器就
 * 必须先有 `VadeClient`"这条泄漏路径在编译期就不存在。UI / 三端入口只认
 * [AdapterProvider]。
 *
 * 五个适配器**各建一次、全程共享**（`by lazy`）：
 *  - [MetaActionsReal] / [TurnGatewayReal] 无状态，session_id 每次随调用传入；
 *  - [ModelActionsReal] 内部按 session_id 缓存覆盖值和最近一次 `model/list`，
 *    多建实例会把同一份服务端状态拆成互不可见的副本；
 *  - [RealModulePageSource] 持页面/状态缓存，重建即丢缓存；
 *  - [BlobUploaderReal] 无状态，但重建没有意义。
 *
 * 线程安全：`by lazy` 默认 SYNCHRONIZED；本类不持连接、不做 IO，所有失败原样穿透
 * 带 C-04 名的 [VadeException]。
 */
internal class RealAdapters(
    private val client: VadeClient,
    /** 字节通道的 HTTP 执行器（shell 注入；null = 未接线，上传明确拒绝）。 */
    private val blobChannel: BlobHttpChannel? = null,
    /** 附件字节来源（shell 注入；null = 未接线，上传明确拒绝）。 */
    private val blobByteSource: BlobByteSource? = null,
    /**
     * B2 · 聊天的**读**通道（历史 + 事件）。`null` = 未接线 —— 聊天页会明说「拿不到历史」
     * 并可重试，**不回退 fixtures**。组合根（[RealSessionSource]）传 `of(this::messages,
     * this::observeEvents)`，两个方法引用的签名与 [ChatTranscriptSource.of] 逐字相同。
     */
    private val chatTranscript: ChatTranscriptSource? = null,
    /**
     * 附件上传的**能力闸门**（`ui/blob.token` 探测，见 `BlobUploadCapability.kt`）。
     * `null` = 不设闸（测试直构通道机制用例时用）；组合根传
     * `BlobUploadGate { client.blobTokenProbe() }` —— 今天的 core 未注册该方法，
     * 闸门会把上传降级成「该 core 不支持附件上传」而不是 UNAVAILABLE 故障。
     */
    private val blobGate: BlobUploadGate? = null,
) : AdapterProvider {

    private val metaActions: SessionMetaActions by lazy { MetaActionsReal(client) }

    private val modelActions: ModelActions by lazy { ModelActionsReal(client) }

    private val turnGateway: ChatTurnGateway by lazy { TurnGatewayReal(client, chatTranscript) }

    private val pageSource: ModulePageSource by lazy { RealModulePageSource(client) }

    private val blobUploader: AttachmentUploader by lazy {
        val real = BlobUploaderReal(
            channel = blobChannel ?: UnwiredBlobChannel,
            byteSource = blobByteSource ?: UnwiredBlobByteSource,
        )
        // 闸门在真通道**外面**：探测「方法不存在」→ 直接明说不支持，字节通道一次都不碰。
        if (blobGate == null) real else CapabilityGatedUploader(blobGate, real)
    }

    override fun meta(sessionId: String): SessionMetaActions = metaActions

    override fun model(sessionId: String): ModelActions = modelActions

    override fun modelDefault(): ModelActions = modelActions

    override fun turns(sessionId: String): ChatTurnGateway = turnGateway

    override fun uploader(): AttachmentUploader = blobUploader

    override fun modulePages(): ModulePageSource = pageSource
}

/** 未接线时上传拒绝的统一文案（`uploader()` 的 KDoc 引用同一条）。 */
internal const val UNWIRED_BLOB_MESSAGE: String =
    "no blob HTTP channel is wired into RealAdapters: the shell must supply a BlobHttpChannel " +
        "and BlobByteSource (device Authorization header, or __Host-ac_ui cookie + x-vade-csrf) — " +
        "see shots/g-adapter-bundle/RESULT.md"

/**
 * 未注入 HTTP 执行器时的通道：`UNAVAILABLE`（可重试）而不是零字节假成功。
 * 上传链路会把它包成 `UploadFailedException`（`ChatState.runUpload` 据此显示可重试
 * 的失败态），语义上等价于"shell 还没接 blob 通道"。
 */
private object UnwiredBlobChannel : BlobHttpChannel {
    override suspend fun exchange(request: BlobHttpRequest): BlobHttpResponse =
        throw VadeException(VadeErrorCodes.UNAVAILABLE, UNWIRED_BLOB_MESSAGE)
}

/** 未注入字节来源时的来源：同上，明确拒绝。 */
private object UnwiredBlobByteSource : BlobByteSource {
    override suspend fun bytes(request: PendingAttachment): ByteArray =
        throw VadeException(VadeErrorCodes.UNAVAILABLE, UNWIRED_BLOB_MESSAGE)
}
