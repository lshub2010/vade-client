package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import vade.ui.chat.AttachmentUploader
import vade.ui.chat.ChatPreset
import vade.ui.chat.ChatTurnGateway
import vade.ui.chat.EditResubmitReceipt
import vade.ui.chat.PendingAttachment
import vade.ui.chat.RegenerateReceipt
import vade.ui.data.AdapterHost
import vade.ui.data.AdapterProvider
import vade.ui.data.FakeSessionSource
import vade.ui.data.FakeSessions
import vade.ui.data.SessionSource
import vade.ui.destinations.Destination
import vade.ui.destinations.ProfileScreen
import vade.ui.destinations.SessionDetailScreen
import vade.ui.meta.FakeMetaActions
import vade.ui.meta.SessionMetaActions
import vade.ui.model.ModelActions
import vade.ui.module.ModuleDeclaration
import vade.ui.module.ModulePageSource
import vade.ui.module.ModuleStatus
import vade.ui.protocol.ModelEntry
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import vade.ui.testutil.IsolatedPreferencesTest

/**
 * g-activate · `AppShell` actually **hands the adapter bundle down**.
 *
 * `g-wire-screens` gave the four screens an `adapters: AdapterProvider? = null` parameter with
 * a three-step fallback (parameter → `adapters` → in-memory fixture) but deliberately left
 * `AppShell` alone, so the parameter was permanently `null` in production. This file pins the
 * missing half:
 *
 *  1. **The fixtures host nothing**: `FakeSessionSource` does not implement [AdapterHost], so
 *     [adaptersOf] is `null` and every screen keeps rendering exactly what it rendered before
 *     the wiring (fixture rows, fixture default model, demo module pages). A connected source
 *     that cleared its bundle (`adapters = null` after `disconnect()`) reads the same way.
 *  2. **A hosting source reaches all five call sites**: the pushed detail ([ShellBody]), the
 *     two-pane detail pane and the single-pane detail fallback ([DestinationHost]), plus
 *     `CapabilitiesScreen` and `ProfileScreen` — each proven by an observable effect, not by
 *     reading the source.
 *  3. `SessionDetailScreen` resolves **both** chat channels (`turns` + `uploader`) and the
 *     model seam from the one bundle; `ProfileScreen` asks for `modelDefault()` and never
 *     `model("")`; `CapabilitiesScreen` drops `ModulePageSources.demo()`.
 *  4. **Source policy**: all five call sites in `AppShell.kt` pass `adapters = adapters,` —
 *     including the `else if (selectedSessionId != null)` fallback branch, which a rendered
 *     test cannot reach (it needs `selectedSessionId` set while `selectedSession` is null).
 */
@OptIn(ExperimentalTestApi::class)
class AppShellAdapterWiringTest : IsolatedPreferencesTest() {

    // ------------------------------------------------------------------ test doubles

    private val sessionId = "s-rfc0004"

    /** A model seam whose display name exists nowhere in the fixtures. */
    private class ProbeModelActions : ModelActions {
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

        override val defaultModelId: String? = PROBE_MODEL_ID

        override fun sessionOverride(sessionId: String): String? = PROBE_MODEL_ID

        override fun setSessionModel(sessionId: String, modelId: String?) = Unit

        override fun setDefaultModel(modelId: String?) = Unit
    }

    private class RecordingGateway : ChatTurnGateway {
        override suspend fun editResubmit(
            sessionId: String,
            messageId: String,
            content: List<vade.ui.chat.ContentPart>,
            clientMsgId: String,
        ): EditResubmitReceipt = EditResubmitReceipt("t", "m", "t-old", "t", 2)

        override suspend fun regenerate(
            sessionId: String,
            turnId: String,
            clientMsgId: String,
        ): RegenerateReceipt = RegenerateReceipt("t", turnId, 2, turnId)
    }

    private class RecordingUploader : AttachmentUploader {
        override suspend fun upload(
            request: PendingAttachment,
            uploadId: String,
            onProgress: (Float) -> Unit,
        ): String = PROBE_BLOB_ID
    }

    /** Records every lookup so a missing call site is an assertion, not a guess. */
    private class RecordingProvider : AdapterProvider {
        val modelIds = mutableListOf<String>()
        val turnIds = mutableListOf<String>()
        var modelDefaultCalls = 0
            private set
        var uploaderCalls = 0
            private set
        var modulePagesCalls = 0
            private set

        val probeModel = ProbeModelActions()
        val probePages: ModulePageSource = NoPagesSource

        override fun meta(sessionId: String): SessionMetaActions = FakeMetaActions()

        override fun model(sessionId: String): ModelActions {
            modelIds += sessionId
            return probeModel
        }

        override fun modelDefault(): ModelActions {
            modelDefaultCalls += 1
            return probeModel
        }

        override fun turns(sessionId: String): ChatTurnGateway {
            turnIds += sessionId
            return RecordingGateway()
        }

        override fun uploader(): AttachmentUploader {
            uploaderCalls += 1
            return RecordingUploader()
        }

        override fun modulePages(): ModulePageSource {
            modulePagesCalls += 1
            return probePages
        }
    }

    /**
     * A source that CAN host adapters. [FakeSessionSource] is final and deliberately not an
     * [AdapterHost], so the fixture path and this one stay distinguishable by type — which is
     * exactly the `(source as? AdapterHost)` probe under test.
     */
    private class HostSource(private val provider: AdapterProvider?) : SessionSource by FakeSessions, AdapterHost {
        override val adapters: AdapterProvider? get() = provider
    }

    /** A page source with no `ui.pages` declarations: the demo tree is observably gone. */
    private object NoPagesSource : ModulePageSource {
        override fun declarations(): List<ModuleDeclaration> = emptyList()

        override fun status(moduleId: String): ModuleStatus = ModuleStatus(online = false, version = null)

        override fun loadPage(moduleId: String, pageId: String): JsonObject? = null

        override fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject? = null
    }

    private fun state(
        source: SessionSource,
        dest: Destination = Destination.Sessions,
    ): UiState = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = dest,
        source = source,
        loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    // ------------------------------------------------ 1 · the bundle is derived per source

    @Test
    fun theFixtureSourceDoesNotHostAdapters() {
        val ui = state(FakeSessionSource())
        assertFalse(ui.source is AdapterHost, "假源实现 AdapterHost 就等于给假数据发真适配器")
        assertNull(adaptersOf(ui), "没接 core ⇒ 五个调用点都该拿到 null")
    }

    @Test
    fun aHostingSourceWithAClearedBundleAlsoHandsNull() {
        // `RealSessionSource.disconnect()` clears the bundle — "connected but nothing to give"
        // must read exactly like "no host at all", not like an empty provider.
        val ui = state(HostSource(null))
        assertNull(adaptersOf(ui))
    }

    @Test
    fun aHostingSourceHandsItsExactProviderDown() {
        val provider = RecordingProvider()
        val ui = state(HostSource(provider))
        assertSame(provider, adaptersOf(ui), "必须是组合根交出的那一个对象，不得包一层")
    }

    // ------------------------------------- 2 · fixtures: every destination is unchanged

    @Test
    fun theFixtureShellStillRendersFixtureContentOnEveryDestination() = runComposeUiTest {
        val ui = state(FakeSessionSource())
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(1000.dp, 900.dp)) { AppShell(ui) }
            }
        }

        // 会话：fixture 行还在（接线前的那棵列表）。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(FIXTURE_SESSION_TITLE).fetchSemanticsNodes().isNotEmpty()
        }

        // 我的：内存假实现的默认模型（ModelFixtures.claudeSonnet 的显示名）。
        runOnIdle { ui.select(Destination.Me) }
        waitForIdle()
        onNodeWithText(FIXTURE_MODEL_NAME, substring = true).assertExists()

        // 能力：示例树（demo）仍在——没接线就不该换成 core 的页面。
        runOnIdle { ui.select(Destination.Capabilities) }
        waitForIdle()
        onNodeWithText("模块页面").assertExists()
        onNodeWithText("下载管理").assertExists()
    }

    // --------------------------------- 3 · a hosting source reaches each of the screens

    @Test
    fun thePushedDetailReceivesTheBundle() = runComposeUiTest {
        val provider = RecordingProvider()
        val ui = state(HostSource(provider))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                // <600dp: a selected session is pushed full screen ⇒ `ShellBody`'s own
                // SessionDetailScreen is the only detail composed.
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }

        waitUntil(timeoutMillis = 10_000) { ui.sessionRows.isNotEmpty() }
        runOnIdle { ui.open(sessionId) }
        waitForIdle()

        // 分支证据 1（同步、不依赖网络状态）：详情顶栏的 leading 是「返回」——列表 chrome
        // 没有这个按钮（列表只有搜索/新建），所以它的出现就证明 `ShellBody` 的 pushed-detail
        // 分支真的组合了（`DestinationHost` 的详情不画顶栏）。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithContentDescription("返回").fetchSemanticsNodes().isNotEmpty()
        }
        // 分支证据 2：只有 pushed-detail 分支会给这个会话配用量副标题（列表顶栏的副标题是
        // 层级标签「会话 · N 个会话」）。A3 之后这条证据的形状变了：这个宿主源**没有接活动
        // 读取口**（`HostSource` 只实现 `AdapterHost`），`ActivitySeams.unavailable()` 让
        // `session/usage.get` 立刻 UNAVAILABLE ⇒ 副标题是「点按重试」而不是编造的 token 数字。
        // 旧断言等 "tokens" 就是等一个按契约**不该出现**的东西（假数字正是 A3 修的 bug）。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(USAGE_RETRY_LABEL).fetchSemanticsNodes().isNotEmpty()
        }
        onAllNodesWithText("tokens", substring = true).assertCountEquals(0)
        // Collapsed picker header prints currentLabel(): the probe name only exists if the
        // pushed detail got the provider.
        // g-model-chip：会话详情上模型名由 header 行与 composer chip 共同承载（设置页仍只有
        // 卡片一处）—— 统一断言「出现」，不再断言「唯一」。
        // n2-composer：会话页的模型信息栏已删除，模型名只在 composer 第二行的 chip 上；
        // chip 默认收起，必须先点输入区展开再断言。
        onNodeWithText("输入消息…").performClick()
        waitForIdle()
        assertTrue(
            onAllNodesWithText(PROBE_MODEL_NAME, substring = true).fetchSemanticsNodes().isNotEmpty(),
            "接线束提供的模型名必须出现在会话详情上",
        )
        assertEquals(listOf(sessionId), provider.modelIds, "model(sessionId) must be asked on the detail page")
    }

    @Test
    fun theTwoPaneDetailPaneReceivesTheBundle() = runComposeUiTest {
        val provider = RecordingProvider()
        val ui = state(HostSource(provider))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                // ≥840dp: rail + list + detail — `DestinationHost`'s two-pane branch.
                Box(Modifier.size(1000.dp, 900.dp)) { AppShell(ui) }
            }
        }

        waitUntil(timeoutMillis = 10_000) { ui.sessionRows.isNotEmpty() }
        runOnIdle { ui.open(sessionId) }
        waitForIdle()

        // Not the pushed branch ⇒ the detail here is `DestinationHost`'s two-pane
        // SessionDetailScreen. 原先这条钉的是那句假话（「消息流 / 工具卡为假数据」），
        // g-topbar-usage 已把句子与常量一起删掉 ⇒ 断言失去对象；改为钉新的可证伪事实：
        // 双栏详情不组成 pushed-detail 顶栏，所以任何顶栏都不该有 tokens 节点。
        onAllNodesWithText("tokens", substring = true).assertCountEquals(0)
        // g-model-chip：会话详情上模型名由 header 行与 composer chip 共同承载（设置页仍只有
        // 卡片一处）—— 统一断言「出现」，不再断言「唯一」。
        // n2-composer：会话页的模型信息栏已删除，模型名只在 composer 第二行的 chip 上；
        // chip 默认收起，必须先点输入区展开再断言。
        onNodeWithText("输入消息…").performClick()
        waitForIdle()
        assertTrue(
            onAllNodesWithText(PROBE_MODEL_NAME, substring = true).fetchSemanticsNodes().isNotEmpty(),
            "接线束提供的模型名必须出现在会话详情上",
        )
        assertEquals(listOf(sessionId), provider.modelIds, "the two-pane detail must ask model(sessionId)")
    }

    @Test
    fun theProfileDestinationReceivesTheBundleAndAsksForTheGlobalDefault() = runComposeUiTest {
        val provider = RecordingProvider()
        val ui = state(HostSource(provider))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }

        runOnIdle { ui.select(Destination.Me) }
        waitForIdle()

        // g-model-chip：会话详情上模型名由 header 行与 composer chip 共同承载（设置页仍只有
        // 卡片一处）—— 统一断言「出现」，不再断言「唯一」。
        // n2-composer：会话页的模型信息栏已删除，模型名只在 composer 第二行的 chip 上；
        // chip 默认收起，必须先点输入区展开再断言。
        onNodeWithText("输入消息…").performClick()
        waitForIdle()
        assertTrue(
            onAllNodesWithText(PROBE_MODEL_NAME, substring = true).fetchSemanticsNodes().isNotEmpty(),
            "接线束提供的模型名必须出现在会话详情上",
        )
        assertEquals(1, provider.modelDefaultCalls, "设置页必须走 modelDefault()")
        assertEquals(emptyList(), provider.modelIds, "设置页不得调用 model(...)——包括 model(\"\")")
    }

    @Test
    fun theCapabilitiesDestinationReceivesTheBundleAndDropsTheDemo() = runComposeUiTest {
        val provider = RecordingProvider()
        val ui = state(HostSource(provider))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }

        runOnIdle { ui.select(Destination.Capabilities) }
        waitForIdle()

        assertEquals(1, provider.modulePagesCalls, "能力页必须查 modulePages()")
        // 示例树的两个入口都不该出现：适配器交出的页面源真的替换了 demo。
        onNodeWithText("模块页面").assertDoesNotExist()
        onNodeWithText("下载管理").assertDoesNotExist()
    }

    // ---------------------------------------------------- 4 · per-screen seam contracts

    @Test
    fun theDetailScreenTakesTurnsAndUploaderFromTheSameBundle() = runComposeUiTest {
        val provider = RecordingProvider()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                SessionDetailScreen(
                    session = FakeSessions.all.first { it.id == sessionId },
                    preset = ChatPreset.Live,
                    animate = false,
                    adapters = provider,
                )
            }
        }
        waitForIdle()

        // Both chat channels are resolved at composition: wiring only one of them leaves the
        // other list empty, which is exactly the half-wired state this pins.
        assertEquals(listOf(sessionId), provider.turnIds, "turns 通道没接上")
        assertEquals(1, provider.uploaderCalls, "uploader 通道没接上")
        assertEquals(listOf(sessionId), provider.modelIds, "model 通道没接上")
    }

    @Test
    fun theProfileScreenAsksForTheGlobalDefaultAndNeverForASession() = runComposeUiTest {
        val provider = RecordingProvider()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                ProfileScreen(
                    skinId = "material",
                    barStyle = BottomBarStyle.Fixed,
                    containerLabel = "test",
                    onSkinChange = {},
                    onBarChange = {},
                    adapters = provider,
                )
            }
        }
        waitForIdle()

        assertEquals(1, provider.modelDefaultCalls)
        assertEquals(emptyList(), provider.modelIds, "model(\"\") 会拿到会话级适配器——这不是设置页要的")
        // g-model-chip：会话详情上模型名由 header 行与 composer chip 共同承载（设置页仍只有
        // 卡片一处）—— 统一断言「出现」，不再断言「唯一」。
        // n2-composer：会话页的模型信息栏已删除，模型名只在 composer 第二行的 chip 上；
        // chip 默认收起，必须先点输入区展开再断言。
        onNodeWithText("输入消息…").performClick()
        waitForIdle()
        assertTrue(
            onAllNodesWithText(PROBE_MODEL_NAME, substring = true).fetchSemanticsNodes().isNotEmpty(),
            "接线束提供的模型名必须出现在会话详情上",
        )
    }

    // -------------------------------------------------------- 5 · source policy (all five)

    @Test
    fun allFiveAdapterCallSitesInTheShellPassTheBundle() {
        val src = File(SHELL_SOURCE).readText()

        assertEquals(
            2,
            Regex("""val adapters = adaptersOf\(state\)""").findAll(src).count(),
            "推导必须只在 ShellBody / DestinationHost 各一次（共用一个 adaptersOf）",
        )

        val detail = callBlocks(src, "SessionDetailScreen")
        assertEquals(
            2,
            detail.size,
            "AppShell 的 SessionDetailScreen 应剩 2 个调用点（双栏详情 / 600–719 rail 单栏兜底）；" +
                "手机单栏抽屉的详情底层已随 g-route-transition 搬进 RouteTransition.kt",
        )
        detail.forEachIndexed { index, block ->
            assertTrue(
                block.contains("adapters = adapters"),
                "第 ${index + 1} 个 SessionDetailScreen 调用点没传 adapters:\n$block",
            )
        }

        val drawerSrc = File(ROUTE_TRANSITION_SOURCE).readText()
        val drawerDetail = callBlocks(drawerSrc, "SessionDetailScreen")
        assertEquals(1, drawerDetail.size, "RouteTransition（抽屉详情底层）应有且只有 1 个 SessionDetailScreen 调用点")
        assertTrue(
            drawerDetail.single().contains("adapters = adapters"),
            "抽屉的 SessionDetailScreen 调用点没传 adapters:\n${drawerDetail.single()}",
        )

        val capabilities = callBlocks(src, "CapabilitiesScreen")
        assertEquals(1, capabilities.size)
        assertTrue(capabilities.single().contains("adapters = adapters"), "能力页调用点没传 adapters")

        val profile = callBlocks(src, "ProfileScreen")
        assertEquals(1, profile.size)
        assertTrue(profile.single().contains("adapters = adapters"), "设置页调用点没传 adapters")
    }

    /** The `(`…`)` argument block of every `name(` call in [src], brace-balanced. */
    private fun callBlocks(src: String, name: String): List<String> {
        val blocks = mutableListOf<String>()
        var idx = src.indexOf("$name(")
        while (idx >= 0) {
            var depth = 0
            var i = idx + name.length
            val start = i
            while (i < src.length) {
                when (src[i]) {
                    '(' -> depth += 1
                    ')' -> {
                        depth -= 1
                        if (depth == 0) {
                            blocks += src.substring(start, i)
                            break
                        }
                    }
                }
                i += 1
            }
            idx = src.indexOf("$name(", idx + 1)
        }
        return blocks
    }

    private companion object {
        const val PROBE_MODEL_ID = "probe/shell-model"
        const val PROBE_MODEL_NAME = "壳层探针模型"
        const val PROBE_BLOB_ID = "blb_probe_shell"
        const val SHELL_SOURCE = "src/commonMain/kotlin/vade/ui/shell/AppShell.kt"
        const val ROUTE_TRANSITION_SOURCE = "src/commonMain/kotlin/vade/ui/shell/RouteTransition.kt"

        /** The first fake session's title — a string no other screen prints. */
        const val FIXTURE_SESSION_TITLE = "RFC-0004 附件与多模态评审"

        /** `ModelFixtures.claudeSonnet.displayName`: what the in-memory seam shows by default. */
        const val FIXTURE_MODEL_NAME = "Claude Sonnet"
    }
}
