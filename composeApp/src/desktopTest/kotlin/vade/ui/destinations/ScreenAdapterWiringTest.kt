package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.serialization.json.JsonObject
import vade.ui.chat.AttachmentUploader
import vade.ui.chat.ChatPreset
import vade.ui.chat.ChatRole
import vade.ui.chat.ChatTurnGateway
import vade.ui.chat.EditResubmitReceipt
import vade.ui.chat.PendingAttachment
import vade.ui.chat.RegenerateReceipt
import vade.ui.data.AdapterProvider
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.meta.FakeMetaActions
import vade.ui.meta.SessionMetaActions
import vade.ui.model.InMemoryModelActions
import vade.ui.model.ModelActions
import vade.ui.module.ModuleDeclaration
import vade.ui.module.ModulePageSource
import vade.ui.module.ModulePageSources
import vade.ui.module.ModuleStatus
import vade.ui.protocol.ModelEntry
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import java.lang.reflect.Method
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vade.ui.testutil.IsolatedPreferencesTest

/**
 * g-wire-screens · 四个屏幕的「适配器三段回退」测试。
 *
 * 守卫的是**接线语义**，不是外观：
 *  1. **形参默认 null ⇒ 落回内存假实现**（`AppShell` 这一轮没接线，所以线上行为必须与
 *     接线前逐字节相同）；
 *  2. **传入假 [AdapterProvider] 时屏幕真的用它**：查找方法被调过、`sessionId` 原样传进去、
 *     返回的对象就是状态机持有的那一个；
 *  3. **形参优先于 adapters**（两者都给时用形参，适配器一次都不查）；
 *  4. `ChatState` 的 `turns` **和** `uploader` 都来自同一个适配器包（不许只接一半）；
 *  5. 设置页取 `modelDefault()`，**不是** `model("")`；
 *  6. 能力页拿到 `modulePages()` 后不再用 `ModulePageSources.demo()`；
 *  7. 空白 `sessionId` 原样透传（查找不校验，校验在 RPC 边界）；适配器查找抛异常 ⇒
 *     退回假实现 + **可读的退化原因**（不崩、不静默吞）；
 *  8. **反向守卫**：`AdapterProvider` 的返回类型仍是六件窄接口之一、四个屏幕的公开签名里
 *     不出现任何 `vade.ui.protocol` 类型（把返回类型换成 `VadeClient` 会立刻红）。
 */
class ScreenAdapterWiringTest : IsolatedPreferencesTest() {

    // ------------------------------------------------------------------ 测试替身

    private val sessionId = "s-wire-1"

    private fun session(model: String? = null): SessionSummary = SessionSummary(
        id = sessionId,
        title = "接线测试会话",
        preview = "g-wire-screens",
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000,
        updatedLabel = "刚刚",
        model = model,
    )

    /** 探针模型：`displayName` 只在适配器给的表里出现过，fixture 拿不到它。 */
    private class ProbeModelActions(
        override val defaultModelId: String? = PROBE_MODEL_ID,
    ) : ModelActions {
        override val models: List<ModelEntry> = listOf(
            ModelEntry(
                id = PROBE_MODEL_ID,
                provider = "probe",
                displayName = PROBE_MODEL_NAME,
                inputModalities = listOf("text"),
                maxImageBytes = null,
                maxImagePx = null,
                maxImagesPerTurn = null,
                contextWindow = 200_000,
                supportsTools = true,
                priority = 1,
                available = true,
            ),
        )

        val writes = mutableListOf<Pair<String, String?>>()

        override fun sessionOverride(sessionId: String): String? = PROBE_MODEL_ID

        override fun setSessionModel(sessionId: String, modelId: String?) {
            writes += sessionId to modelId
        }

        override fun setDefaultModel(modelId: String?) {
            writes += "" to modelId
        }
    }

    /** 记录调用的 turns 通道（`message/edit_resubmit`）。 */
    private class RecordingGateway : ChatTurnGateway {
        val edits = mutableListOf<Pair<String, String>>()
        val regenerates = mutableListOf<Pair<String, String>>()

        /** 探针是假通道：留在 fixtures 形态（真通道下 ChatState 不会渲染预设消息）。 */
        override val synthetic: Boolean get() = true

        override suspend fun editResubmit(
            sessionId: String,
            messageId: String,
            content: List<vade.ui.chat.ContentPart>,
            clientMsgId: String,
        ): EditResubmitReceipt {
            edits += sessionId to messageId
            return EditResubmitReceipt(
                turnId = "t-probe",
                newMessageId = "m-probe",
                supersedesTurnId = "t-old",
                rootTurnId = "t-probe",
                version = 2,
            )
        }

        override suspend fun regenerate(
            sessionId: String,
            turnId: String,
            clientMsgId: String,
        ): RegenerateReceipt {
            regenerates += sessionId to turnId
            return RegenerateReceipt(
                turnId = "t-probe",
                rootTurnId = turnId,
                version = 2,
                supersedesTurnId = turnId,
            )
        }
    }

    /** 记录调用的上传通道：返回的 blob id 一眼能认出是探针给的。 */
    private class RecordingUploader : AttachmentUploader {
        val uploads = mutableListOf<String>()

        override suspend fun upload(
            request: PendingAttachment,
            uploadId: String,
            onProgress: (Float) -> Unit,
        ): String {
            uploads += uploadId
            onProgress(1f)
            return PROBE_BLOB_ID
        }
    }

    /** 记录调用的适配器包；每个查找都能单独替换成抛异常的版本。 */
    private class FakeAdapterProvider(
        private val modelFor: (String) -> ModelActions = { ProbeModelActions() },
        private val defaultModel: () -> ModelActions = { ProbeModelActions() },
        private val turnsFor: (String) -> ChatTurnGateway = { RecordingGateway() },
        private val uploaderSeam: () -> AttachmentUploader = { RecordingUploader() },
        private val metaFor: (String) -> SessionMetaActions = { FakeMetaActions() },
        private val pages: () -> ModulePageSource = { ModulePageSources.demo() },
    ) : AdapterProvider {

        val modelIds = mutableListOf<String>()
        val turnIds = mutableListOf<String>()
        val metaIds = mutableListOf<String>()
        var modelDefaultCalls = 0
            private set
        var uploaderCalls = 0
            private set
        var modulePagesCalls = 0
            private set

        override fun meta(sessionId: String): SessionMetaActions {
            metaIds += sessionId
            return metaFor(sessionId)
        }

        override fun model(sessionId: String): ModelActions {
            modelIds += sessionId
            return modelFor(sessionId)
        }

        override fun modelDefault(): ModelActions {
            modelDefaultCalls += 1
            return defaultModel()
        }

        override fun turns(sessionId: String): ChatTurnGateway {
            turnIds += sessionId
            return turnsFor(sessionId)
        }

        override fun uploader(): AttachmentUploader {
            uploaderCalls += 1
            return uploaderSeam()
        }

        override fun modulePages(): ModulePageSource {
            modulePagesCalls += 1
            return pages()
        }
    }

    /** 每个查找都抛同一个 `Exception`（组合期真崩的模拟）。 */
    private class ExplodingProvider(private val reason: String = "probe-boom") : AdapterProvider {
        override fun meta(sessionId: String): SessionMetaActions = throw IllegalStateException(reason)
        override fun model(sessionId: String): ModelActions = throw IllegalStateException(reason)
        override fun modelDefault(): ModelActions = throw IllegalStateException(reason)
        override fun turns(sessionId: String): ChatTurnGateway = throw IllegalStateException(reason)
        override fun uploader(): AttachmentUploader = throw IllegalStateException(reason)
        override fun modulePages(): ModulePageSource = throw IllegalStateException(reason)
    }

    /** 一个**没有** `ui.pages` 声明的页面源：区块必须整块消失（与 demo 树可区分）。 */
    private object NoPagesSource : ModulePageSource {
        override fun declarations(): List<ModuleDeclaration> = emptyList()

        override fun status(moduleId: String): ModuleStatus = ModuleStatus(online = false, version = null)

        override fun loadPage(moduleId: String, pageId: String): JsonObject? = null

        override fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject? = null
    }

    // ------------------------------------------------------- 1. null ⇒ 内存假实现

    @Test
    fun sessionDetailWithoutAdaptersFallsBackToTheInMemoryModelFixture() {
        val seam = resolveSessionModelActions(
            explicit = null,
            adapters = null,
            sessionId = sessionId,
            seedModel = "m6-echo/sonnet",
        )

        val actions = assertIs<InMemoryModelActions>(seam.value, "没接线时必须落回内存假实现")
        assertNull(seam.degraded, "回退到内存假实现不是「退化」，不该有原因文字")
        // 假实现仍按会话播种：picker 与 header 读的是同一条覆盖值。
        assertEquals("m6-echo/sonnet", actions.sessionOverride(sessionId))
    }

    @Test
    fun profileWithoutAdaptersFallsBackToTheInMemoryModelFixture() {
        val seam = resolveProfileModelActions(explicit = null, adapters = null)

        val actions = assertIs<InMemoryModelActions>(seam.value)
        assertNull(seam.degraded)
        // 默认层要有值：否则设置页的卡片会显示「按 priority 兜底」而不是 fixture 的默认模型。
        assertNotNull(actions.defaultModelId, "内存假实现的默认模型不该被接线改动")
    }

    @Test
    fun capabilitiesWithoutAdaptersKeepsTheDemoPageSource() {
        val seam = resolveCapabilitiesPageSource(explicit = null, adapters = null)

        assertSame(ModulePageSources.demo(), seam.value, "没接线时必须还是那棵示例树（同一个单例）")
        assertNull(seam.degraded)
    }

    // --------------------------------------------- 2. 传入的 provider 真被调用

    @Test
    fun sessionDetailAsksTheProviderForThisSession() {
        val probe = ProbeModelActions()
        val provider = FakeAdapterProvider(modelFor = { probe })

        val seam = resolveSessionModelActions(null, provider, sessionId)

        assertSame(probe, seam.value, "屏幕拿到的必须就是适配器交出的那个对象")
        assertEquals(listOf(sessionId), provider.modelIds, "model(sessionId) 必须被调一次且 id 原样")
        assertNull(seam.degraded)
    }

    @Test
    fun profileAsksForTheGlobalDefaultAndNeverForASession() {
        val probe = ProbeModelActions()
        val provider = FakeAdapterProvider(defaultModel = { probe })

        val seam = resolveProfileModelActions(null, provider)

        assertSame(probe, seam.value)
        assertEquals(1, provider.modelDefaultCalls, "设置页必须走 modelDefault()")
        assertEquals(emptyList(), provider.modelIds, "设置页不得调用 model(sessionId)——包括 model(\"\")")
    }

    @Test
    fun capabilitiesTakesTheProviderPagesAndDropsTheDemo() {
        val provider = FakeAdapterProvider(pages = { NoPagesSource })

        val seam = resolveCapabilitiesPageSource(null, provider)

        assertSame(NoPagesSource, seam.value, "拿到适配器的页面源后必须用它")
        assertNotSame(ModulePageSources.demo(), seam.value, "适配器接线后不得再回 demo 树")
        assertEquals(1, provider.modulePagesCalls)
        assertEquals(emptyList(), seam.value.declarations(), "这就是与 demo 树可观察的差别")
    }

    // ------------------------------------------------------- 3. 形参优先

    @Test
    fun explicitModelActionsWinOverTheProviderInSessionDetail() {
        val explicit = ProbeModelActions(defaultModelId = "explicit-wins")
        val provider = FakeAdapterProvider()

        val seam = resolveSessionModelActions(explicit, provider, sessionId)

        assertSame(explicit, seam.value, "形参必须赢过适配器")
        assertEquals(emptyList(), provider.modelIds, "形参给了就一次都不该查适配器")
    }

    @Test
    fun explicitModelActionsWinOverTheProviderInProfile() {
        val explicit = ProbeModelActions(defaultModelId = "explicit-wins")
        val provider = FakeAdapterProvider()

        val seam = resolveProfileModelActions(explicit, provider)

        assertSame(explicit, seam.value)
        assertEquals(0, provider.modelDefaultCalls)
    }

    @Test
    fun explicitPageSourceWinsOverTheProviderInCapabilities() {
        val provider = FakeAdapterProvider(pages = { NoPagesSource })

        val seam = resolveCapabilitiesPageSource(ModulePageSources.demo(), provider)

        assertSame(ModulePageSources.demo(), seam.value)
        assertEquals(0, provider.modulePagesCalls, "形参给了就不该查适配器")
    }

    // ---------------------------------------- 4. ChatState 两条通道同源

    @Test
    fun chatStateTakesTurnsAndUploaderFromTheSameProvider() {
        val gateway = RecordingGateway()
        val uploader = RecordingUploader()
        val provider = FakeAdapterProvider(turnsFor = { gateway }, uploaderSeam = { uploader })

        val chat = resolveChatState(sessionId, ChatPreset.Live, provider).value

        // 上游通道：真的走 provider 交出的 uploader（blob id 是探针给的，不是内存假实现的）。
        val uploadId = assertNotNull(
            chat.stageUpload(PendingAttachment(name = "wire.txt", mime = "text/plain", sizeBytes = 12)),
        )
        runBlocking { chat.runUpload(uploadId) }
        assertEquals(listOf(uploadId), uploader.uploads, "uploader 通道没接上")
        assertEquals(PROBE_BLOB_ID, chat.staged.single().attachment.fileId)

        // 下行通道：真的走 provider 交出的 turns（edit_resubmit）。
        val lastUserId = chat.messages.last { it.role == ChatRole.User }.id
        assertTrue(chat.startEdit(lastUserId), "最后一条用户消息应可编辑")
        chat.updateEditDraft("改一条再发")
        assertTrue(runBlocking { chat.confirmEdit() }, "确认编辑应成功")
        assertEquals(listOf(sessionId to lastUserId), gateway.edits, "turns 通道没接上")

        // 两条通道都必须经过查找：只接一条的写法会在这里露馅。
        assertEquals(listOf(sessionId), provider.turnIds)
        assertEquals(1, provider.uploaderCalls)
    }

    @Test
    fun chatStateWithoutAdaptersKeepsBothInMemorySeams() {
        val seam = resolveChatState(sessionId, ChatPreset.Live, null)
        val chat = seam.value
        assertNull(seam.degraded)

        val uploadId = assertNotNull(
            chat.stageUpload(PendingAttachment(name = "fake.txt", mime = "text/plain", sizeBytes = 9)),
        )
        runBlocking { chat.runUpload(uploadId) }
        // 内存假实现自己的 blob id 前缀：证明两个通道都还在旧行为上。
        assertTrue(
            chat.staged.single().attachment.fileId.startsWith("blb_mem_"),
            "没接线时上传必须落在 InMemoryAttachmentUploader 上",
        )

        val lastUserId = chat.messages.last { it.role == ChatRole.User }.id
        assertTrue(chat.startEdit(lastUserId))
        chat.updateEditDraft("还是假的")
        assertTrue(runBlocking { chat.confirmEdit() })
    }

    // ----------------------------------- 7. 空白 id / 抛异常的可预期行为

    @Test
    fun blankSessionIdIsPassedThroughVerbatim() {
        val provider = FakeAdapterProvider()

        val seam = resolveSessionModelActions(null, provider, "")

        // 查找不校验：空白 id 原样进查询，真正的拒绝在 RPC 边界（INVALID_ARGUMENT）。
        assertEquals(listOf(""), provider.modelIds)
        assertNull(seam.degraded, "空白 id 不是适配器故障，不该被当成退化")
        assertIs<ProbeModelActions>(seam.value)
    }

    @Test
    fun throwingProviderDegradesToTheFixtureWithAVisibleReason() {
        val boom = ExplodingProvider()

        val model = resolveSessionModelActions(null, boom, sessionId)
        assertIs<InMemoryModelActions>(model.value, "查找抛异常时必须退回内存假实现，而不是掀掉组合")
        assertNotNull(model.degraded)
        assertTrue(model.degraded.contains(SEAM_FALLBACK_NOTE), "退化原因必须点明「已退回内存假数据」")
        assertTrue(model.degraded.contains("probe-boom"), "必须带上适配器自己的原因：${model.degraded}")

        val profile = resolveProfileModelActions(null, boom)
        assertIs<InMemoryModelActions>(profile.value)
        assertNotNull(profile.degraded)

        val pages = resolveCapabilitiesPageSource(null, boom)
        assertSame(ModulePageSources.demo(), pages.value)
        assertNotNull(pages.degraded)

        val chatSeam = resolveChatState(sessionId, ChatPreset.Live, boom)
        assertNotNull(chatSeam.degraded, "turns/uploader 查找失败也必须留下可读原因")
        // 退回的 ChatState 仍然可用（两条通道都在内存实现上）。
        val uploadId = assertNotNull(
            chatSeam.value.stageUpload(PendingAttachment("boom.txt", "text/plain", 3)),
        )
        runBlocking { chatSeam.value.runUpload(uploadId) }
        assertTrue(chatSeam.value.staged.single().attachment.fileId.startsWith("blb_mem_"))
    }

    // ------------------------------------------- 8. 反向守卫（编译/反射边界）

    @Test
    fun adapterProviderStillHandsOutOnlyTheSixNarrowInterfaces() {
        val allowed = setOf(
            SessionMetaActions::class.java,
            ModelActions::class.java,
            ChatTurnGateway::class.java,
            AttachmentUploader::class.java,
            ModulePageSource::class.java,
        )
        val declared = AdapterProvider::class.java.declaredMethods
            .filter { !it.isSynthetic }
            .filter { it.name != "equals" && it.name != "hashCode" && it.name != "toString" }

        assertEquals(
            setOf("meta", "model", "modelDefault", "turns", "uploader", "modulePages"),
            declared.map { it.name }.toSet(),
            "窄接口集合不得悄悄增宽",
        )
        declared.forEach { method ->
            assertTrue(
                method.returnType in allowed,
                "${method.name}() 的返回类型变成了 ${method.returnType.name}：" +
                    "把 VadeClient（或任何 vade.ui.protocol 类型）交出来就等于把 UI 焊死在协议层上",
            )
        }
        val surface = declared.flatMap { it.surfaceTypes() }
        assertTrue(surface.isNotEmpty())
        assertTrue(
            surface.none { it.typeName.startsWith("vade.ui.protocol") || it.typeName.contains("VadeClient") },
            "AdapterProvider 的公开签名里出现了协议类型：${surface.map { it.typeName }}",
        )
    }

    @Test
    fun theFourScreensExposeTheAdaptersSeamWithoutProtocolTypes() {
        val screens = mapOf(
            "SessionDetailScreen" to Class.forName("vade.ui.destinations.SessionDetailScreenKt"),
            "ProfileScreen" to Class.forName("vade.ui.destinations.ProfileScreenKt"),
            "CapabilitiesScreen" to Class.forName("vade.ui.destinations.CapabilitiesScreenKt"),
        )

        screens.forEach { (name, ktClass) ->
            val methods = ktClass.declaredMethods
                // 签名里出现值类（如 Dp）时 Kotlin 会给 JVM 名加后缀（`Foo-xxxx`）；
                // 只要还是这个入口就算命中，不能因为加了一个 Dp 形参就判「入口没了」。
                .filter { !it.isSynthetic && (it.name == name || it.name.startsWith("$name-")) }
                .filter { method -> method.parameterTypes.none { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" } }
            assertTrue(methods.isNotEmpty(), "$name 的 JVM 入口没找到")

            methods.forEach { method ->
                assertTrue(
                    AdapterProvider::class.java in method.parameterTypes,
                    "$name 的公开签名里没有 adapters: AdapterProvider? —— 接线点丢了",
                )
                val offenders = (method.parameterTypes.toList() + method.returnType)
                    .map { it.name }
                    .filter { it.startsWith("vade.ui.protocol") || it.contains("VadeClient") }
                assertTrue(
                    offenders.isEmpty(),
                    "$name 的公开签名出现了协议类型 $offenders：屏幕只能认窄接口",
                )
            }
        }
    }

    /** 方法的参数/返回值 + 一层泛型实参（与 `VadeAdaptersTest` 同一口径）。 */
    private fun Method.surfaceTypes(): List<java.lang.reflect.Type> {
        val roots = listOf(genericReturnType) + genericParameterTypes.toList()
        return roots.flatMap { type ->
            val args = (type as? java.lang.reflect.ParameterizedType)?.actualTypeArguments.orEmpty().toList()
            listOf(type) + args
        }
    }

    // ---------------------------------------------------------------- 渲染证据

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun capabilitiesWithProviderDeclarationsDropsTheDemoEntries() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CapabilitiesScreen(adapters = FakeAdapterProvider(pages = { NoPagesSource }))
            }
        }
        // demo 树的两个入口（模块标题 + 页面标题）都不该出现：示例源真的被换掉了。
        onNodeWithText("模块页面").assertDoesNotExist()
        onNodeWithText("下载管理").assertDoesNotExist()
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun capabilitiesWithoutAdaptersStillShowsTheDemoEntries() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                CapabilitiesScreen()
            }
        }
        // 接线前的那棵示例树仍在：这是「行为零变化」的可见侧。
        onNodeWithText("模块页面").assertExists()
        onNodeWithText("下载管理").assertExists()
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun profileRendersTheModelProvidedByTheAdapter() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ProfileScreen(
                    skinId = "material",
                    barStyle = vade.ui.skin.BottomBarStyle.Fixed,
                    containerLabel = "test",
                    onSkinChange = {},
                    onBarChange = {},
                    adapters = FakeAdapterProvider(defaultModel = { ProbeModelActions() }),
                )
            }
        }
        // 折叠态卡片显示 currentLabel()：适配器给的名字出现 ⇒ 屏幕真的用了它。
        onNodeWithText(PROBE_MODEL_NAME).assertExists()
    }

    /**
     * g-header-model-line：会话详情上模型名**只由 composer 内的 chip 承载**（表头那行
     * 「模型 …」已删除）。chip 默认收起，所以先点输入区 —— 这也是产品里唯一的手势入口。
     */
    @Test
    @OptIn(ExperimentalTestApi::class)
    fun sessionDetailChipShowsTheProvidedModel() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                SessionDetailScreen(
                    session = session(),
                    preset = ChatPreset.Live,
                    animate = false,
                    adapters = FakeAdapterProvider(modelFor = { ProbeModelActions() }),
                )
            }
        }
        // 表头不在拼模型名：收起态下一个字都不出现（正向断言在下面，防止「哪儿都没有」）。
        assertTrue(
            onAllNodesWithText(PROBE_MODEL_NAME, substring = true).fetchSemanticsNodes().isEmpty(),
            "表头模型行已删除：composer 收起时模型名不该出现",
        )

        // 展开 composer → chip 的文案就是适配器给出的显示名。
        onNodeWithText("输入消息…").performClick()
        assertTrue(
            onAllNodesWithText(PROBE_MODEL_NAME, substring = true).fetchSemanticsNodes().isNotEmpty(),
            "适配器给出的模型名必须出现在 composer 的模型 chip 上",
        )
        onNodeWithText(SEAM_FALLBACK_NOTE, substring = true).assertDoesNotExist()
    }

    @Test
    @OptIn(ExperimentalTestApi::class)
    fun sessionDetailShowsTheDegradationNoteWhenTheAdapterLookupThrows() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                SessionDetailScreen(
                    session = session(),
                    preset = ChatPreset.Live,
                    animate = false,
                    adapters = ExplodingProvider(),
                )
            }
        }
        // 不崩、也不静默：退化原因现在由**模型面板的脚注**给出（表头模型行已随
        // g-header-model-line 删除）—— 展开 composer、点开 chip 才能读到那句诚实正文。
        onNodeWithText("输入消息…").performClick()
        onNodeWithContentDescription("更改会话模型").performClick()
        onNodeWithText(SEAM_FALLBACK_NOTE, substring = true).assertExists()
    }
}

private const val PROBE_MODEL_ID: String = "probe/wire-model"
private const val PROBE_MODEL_NAME: String = "接线探针模型"
private const val PROBE_BLOB_ID: String = "blb_probe_wire"
