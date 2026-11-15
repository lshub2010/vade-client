package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
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
class AppShellAdapterWiringTest {

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

        // Which code path ran: only the pushed-detail branch gives the top bar this subtitle,
        // so its presence proves `ShellBody`'s own SessionDetailScreen was the one rendered.
        onNodeWithText(PUSHED_DETAIL_SUBTITLE, substring = true).assertExists()
        // Collapsed picker header prints currentLabel(): the probe name only exists if the
        // pushed detail got the provider.
        onNodeWithText(PROBE_MODEL_NAME, substring = true).assertExists()
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

        // Not the pushed branch (that subtitle belongs to `ShellBody` only) ⇒ the detail here
        // is `DestinationHost`'s two-pane SessionDetailScreen.
        onNodeWithText(PUSHED_DETAIL_SUBTITLE, substring = true).assertDoesNotExist()
        onNodeWithText(PROBE_MODEL_NAME, substring = true).assertExists()
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

        onNodeWithText(PROBE_MODEL_NAME, substring = true).assertExists()
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
        onNodeWithText(PROBE_MODEL_NAME, substring = true).assertExists()
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

        /** Only the pushed-detail branch (ShellBody) sets this top-bar subtitle. */
        const val PUSHED_DETAIL_SUBTITLE = "会话详情"

        /** `ModelFixtures.claudeSonnet.displayName`: what the in-memory seam shows by default. */
        const val FIXTURE_MODEL_NAME = "Claude Sonnet"
    }
}
