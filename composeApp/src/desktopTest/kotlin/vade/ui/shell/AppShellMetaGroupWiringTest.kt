package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import vade.ui.chat.AttachmentUploader
import vade.ui.chat.ChatTurnGateway
import vade.ui.chat.EditResubmitReceipt
import vade.ui.chat.PendingAttachment
import vade.ui.chat.RegenerateReceipt
import vade.ui.data.AdapterHost
import vade.ui.data.AdapterProvider
import vade.ui.data.FakeSessionSource
import vade.ui.data.FakeSessions
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.destinations.Destination
import vade.ui.destinations.SessionsScreen
import vade.ui.meta.FakeMetaActions
import vade.ui.meta.MetaGroup
import vade.ui.meta.SessionMetaActions
import vade.ui.model.ModelActions
import vade.ui.module.ModuleDeclaration
import vade.ui.module.ModulePageSource
import vade.ui.module.ModuleStatus
import vade.ui.protocol.ModelEntry
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * g-meta-groups · B3 适配器接线的最后一行：会话行上的「组色点 + 组名」终于拿到**真组名**。
 *
 * 缺陷形状（本仓第七次同类）：`SessionsScreen` 早就接受 `metaGroups` 并且真的用它渲染组名
 * （`SessionRowArchiveUiTest` 钉住了 `sessionGroupTag` 的三条规则），`SessionRowMapping`
 * 也早就投影出 `SessionSummary.groupId` —— 唯独组合根没给这个形参赋值，于是线上每一条
 * 有组的行都显示裸 id（`g-1`）。不崩、不空白，所以没人发现。
 *
 * 本文件钉的是**接线**，不是规则（规则在 `SessionRowArchiveUiTest`，不重复）：
 *  1. **两个调用点都要传**：宽屏两栏（≥840dp，`DestinationHost` 的 `metrics.twoPane`
 *     分支）与窄屏单栏（<600dp 的 else 分支）各一条 —— 只传宽屏那处正是本族缺陷最常见
 *     的复发形态，所以两条都必须独立存在；
 *  2. **屏上是组名，不是 id**：`g-1` → 「项目」后，`项目` 出现在屏上、裸 `g-1` 一个都没有；
 *  3. **未分组的行不多画**：传了非空组表，未分组行的整棵语义树文本与空表时**逐字相等**
 *     （多一行空组名/色点也算回归）；
 *  4. **没有分组权威时不发明组名**：假源（`adapters == null`）与「适配器查找抛异常」
 *     （`forAdapters` 退化到 `FakeMetaActions`）两种情形，屏上必须继续是裸 `g-1`，
 *     绝不能冒出 fixture 的「项目/灵感」——那等于把假数据当 core 的组名。
 *
 * 组名从 `session/group.list` 来（[AdapterProvider.meta] → [SessionMetaActions.listGroups]），
 * 由 [metaGroupsOf] 取：测试走的正是生产那条路，没有任何只为测试存在的形参或开关。
 */
@OptIn(ExperimentalTestApi::class)
class AppShellMetaGroupWiringTest {

    // ------------------------------------------------------------------ 测试数据

    private val groupedRow = SessionSummary(
        id = "s-grouped",
        title = "有分组的会话",
        preview = "这条属于 g-1",
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000L,
        updatedLabel = "12:00",
        groupId = GROUP_ID,
    )

    private val ungroupedRow = SessionSummary(
        id = "s-loose",
        title = "未分组的会话",
        preview = "这条不属于任何组",
        bucket = TimeBucket.Today,
        updatedAt = 1_699_999_000_000L,
        updatedLabel = "11:00",
        groupId = null,
    )

    // ------------------------------------------------------------ 适配器包的替身

    private object NoModels : ModelActions {
        override val models: List<ModelEntry>? = null
        override val defaultModelId: String? = null
        override fun sessionOverride(sessionId: String): String? = null
        override fun setSessionModel(sessionId: String, modelId: String?) = Unit
        override fun setDefaultModel(modelId: String?) = Unit
    }

    private object NoTurns : ChatTurnGateway {
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

    private object NoUploader : AttachmentUploader {
        override suspend fun upload(
            request: PendingAttachment,
            uploadId: String,
            onProgress: (Float) -> Unit,
        ): String = "blob-probe"
    }

    private object NoPages : ModulePageSource {
        override fun declarations(): List<ModuleDeclaration> = emptyList()
        override fun status(moduleId: String): ModuleStatus = ModuleStatus(online = false, version = null)
        override fun loadPage(moduleId: String, pageId: String): JsonObject? = null
        override fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject? = null
    }

    /**
     * 适配器包：只有 `meta()` 有内容，其余五个照契约返回空实现。
     *
     * [boomOnMeta] 模拟「适配器查不到」——`SessionMetaState.forAdapters` 会退化成
     * `FakeMetaActions` 并把原因放进 `wiringDegradation`；此时若还去 `loadGroups()`，
     * 屏上就会冒出 fixture 的组名。这条路径必须有测试（见第 4 组）。
     */
    private class MetaOnlyProvider(
        private val groups: List<MetaGroup>,
        private val boomOnMeta: Boolean = false,
        private val failLoad: Boolean = false,
    ) : AdapterProvider {
        var metaCalls = 0
            private set

        override fun meta(sessionId: String): SessionMetaActions {
            metaCalls += 1
            if (boomOnMeta) throw IllegalStateException("适配器查找失败（测试）")
            return if (failLoad) FailingGroups() else FakeMetaActions(seedGroups = groups)
        }

        override fun model(sessionId: String): ModelActions = NoModels
        override fun modelDefault(): ModelActions = NoModels
        override fun turns(sessionId: String): ChatTurnGateway = NoTurns
        override fun uploader(): AttachmentUploader = NoUploader
        override fun modulePages(): ModulePageSource = NoPages
    }

    /** `session/group.list` 以 `UNAVAILABLE` 失败：表留空，屏上必须继续是裸 id。 */
    private class FailingGroups : SessionMetaActions by FakeMetaActions() {
        override suspend fun listGroups(): List<MetaGroup> =
            throw IllegalStateException("UNAVAILABLE（测试）")
    }

    /** 能交出适配器包的数据源；行由测试给定，其余委托给 fixture。 */
    private class HostSource(
        private val provider: AdapterProvider,
        private val rows: List<SessionSummary>,
    ) : SessionSource by FakeSessions, AdapterHost {
        override val adapters: AdapterProvider get() = provider
        override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> = rows
    }

    private fun state(source: SessionSource): UiState = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
        source = source,
        loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    // ------------------------------------------ 1 · 宽屏两栏：组名在、裸 id 不在

    @Test
    fun theTwoPaneListShowsTheGroupNameInsteadOfTheRawId() = runComposeUiTest {
        val ui = state(HostSource(MetaOnlyProvider(GROUPS), listOf(groupedRow, ungroupedRow)))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                // ≥840dp：rail 72 + 300 列表 + 详情 —— `DestinationHost` 的 `metrics.twoPane`
                // 那一支（与 `AppShellAdapterWiringTest` 同一套窗口尺寸驱动方式）。
                Box(Modifier.size(1000.dp, 900.dp)) { AppShell(ui) }
            }
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(groupedRow.title).fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(GROUP_NAME).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText(GROUP_NAME).assertExists()
        assertEquals(
            0,
            onAllNodesWithText(GROUP_ID).fetchSemanticsNodes().size,
            "组名已接上就不该再出现裸 id：屏上出现 $GROUP_ID 说明这一处的 metaGroups 没传",
        )
    }

    // ------------------------------------------ 2 · 窄屏单栏：同样要接上（不是只接宽屏）

    @Test
    fun theSinglePaneListShowsTheGroupNameInsteadOfTheRawId() = runComposeUiTest {
        val ui = state(HostSource(MetaOnlyProvider(GROUPS), listOf(groupedRow, ungroupedRow)))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                // <600dp：底栏 + 单栏 —— `DestinationHost` 的 else 分支（第二个调用点）。
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(groupedRow.title).fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(GROUP_NAME).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText(GROUP_NAME).assertExists()
        assertEquals(
            0,
            onAllNodesWithText(GROUP_ID).fetchSemanticsNodes().size,
            "单栏那处也必须传 metaGroups：裸 $GROUP_ID 出现在屏上就是只接了宽屏那处",
        )
    }

    // ---------------------------------- 3 · 未分组的行不多画一行（哪怕传了非空表）

    @Test
    fun anUngroupedRowDrawsNoGroupLineEvenWhenTheTableIsPassed() {
        val baseline = textsOf(listOf(ungroupedRow), metaGroups = emptyList())
        val withTable = textsOf(listOf(ungroupedRow), metaGroups = GROUPS)

        assertTrue(baseline.isNotEmpty(), "语义树里连标题都没有，测试自身失效")
        assertTrue(baseline.contains(ungroupedRow.title), "基线必须画出了这一行")
        assertEquals(
            baseline,
            withTable,
            "未分组的行不许因为「传了组表」多出任何一行（空组名、孤立色点都算回归）",
        )
    }

    // -------------------------- 4 · 没有分组权威时不发明组名（假源 / 适配器退化 / 拉取失败）

    @Test
    fun theFixturePathKeepsShowingTheRawIdAndNeverAFabricatedName() = runComposeUiTest {
        // 假源不实现 AdapterHost ⇒ adapters == null ⇒ 没有分组权威。fixture 行的 groupId
        // 仍是 g-1，所以屏上继续显示裸 id —— 这是**接线前**的行为，必须逐字不变。
        val ui = state(FakeSessionSource())
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(GROUP_ID).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            0,
            onAllNodesWithText(GROUP_NAME).fetchSemanticsNodes().size,
            "假源没有分组权威：不许把 fixture 的组名当成真的画上去",
        )
    }

    @Test
    fun aFailedAdapterLookupFallsBackToTheRawIdInsteadOfFixtureGroupNames() = runComposeUiTest {
        // `adapters.meta()` 抛 ⇒ `forAdapters` 退化到 FakeMetaActions（wiringDegradation 非 null）。
        // 若此时仍 loadGroups()，屏上就会出现 fixture 的「项目」——比裸 id 更糟的谎。
        val ui = state(HostSource(MetaOnlyProvider(GROUPS, boomOnMeta = true), listOf(groupedRow)))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(1000.dp, 900.dp)) { AppShell(ui) }
            }
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(GROUP_ID).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            0,
            onAllNodesWithText(GROUP_NAME).fetchSemanticsNodes().size,
            "适配器查找失败时跑的是内存假实现，它的组名绝不能上屏",
        )
    }

    @Test
    fun aFailedGroupListKeepsTheRawId() = runComposeUiTest {
        // `session/group.list` 失败 ⇒ 表留空（保留上一次快照，首次为空）⇒ 裸 id 仍是那条
        // 可见的「组名没接上」信号；不许编一个组名糊过去，也不许崩。
        val ui = state(HostSource(MetaOnlyProvider(GROUPS, failLoad = true), listOf(groupedRow)))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }

        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(GROUP_ID).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(
            0,
            onAllNodesWithText(GROUP_NAME).fetchSemanticsNodes().size,
            "读失败时没有组名可显示，只能退化成裸 id",
        )
    }

    // ------------------------------------------------------------------ 工具

    /** 直接渲 `SessionsScreen`（不经 shell），用来做「整棵语义树逐字比较」。 */
    private fun textsOf(rows: List<SessionSummary>, metaGroups: List<MetaGroup>): List<String> {
        var texts: List<String> = emptyList()
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                    SessionsScreen(
                        groups = vade.ui.data.sessionGroups(rows),
                        selectedId = null,
                        twoPane = false,
                        onOpen = {},
                        metaGroups = metaGroups,
                    )
                }
            }
            waitForIdle()
            texts = collectTexts()
        }
        return texts
    }

    private fun ComposeUiTest.collectTexts(): List<String> {
        val out = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach { child -> walk(child) }
        }
        walk(onRoot().fetchSemanticsNode())
        return out
    }

    private companion object {
        const val GROUP_ID = "g-1"
        const val GROUP_NAME = "项目"

        /** 服务端会返回的组名字表：`g-1` → 「项目」（一个具体中文名，不是 id 的回声）。 */
        val GROUPS = listOf(MetaGroup(GROUP_ID, GROUP_NAME, "briefcase", "#3B82F6"))
    }
}
