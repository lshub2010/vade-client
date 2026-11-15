package vade.ui.meta

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.pressKey
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
import vade.ui.data.FakeSessions
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.destinations.Destination
import vade.ui.model.ModelActions
import vade.ui.module.ModuleDeclaration
import vade.ui.module.ModulePageSource
import vade.ui.module.ModuleStatus
import vade.ui.protocol.ModelEntry
import vade.ui.shell.AppShell
import vade.ui.shell.UiState
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B2（s-meta-host）· 生产壳层（AppShell）里的**真挂载**端到端：行长按 → 菜单 →
 * 动作 → 适配器 → 乐观投影上屏 / 失败回滚 + 横条。全部走生产路径（`AppShell` +
 * `rememberSessionMetaOverlay` + `SessionMetaOverlayHost`），适配器包是测试替身，
 * 断言的是**用户看得见的东西**（语义树文本）与**适配器确实被调了**（FakeMetaActions.calls）。
 *
 * 钉住的验收（任务书【测试】）：
 *  1. 长按一行 → 菜单出现且列出 5 个动作；重命名 → 适配器恰一次 `session/rename` 且参数正确；
 *  2. 失败注入（适配器抛错）→ 行标题回到原位 + 可见失败提示（横条）；
 *  3. 真源零会话 → 不出现任何菜单入口；
 *  4. Esc 关闭长按菜单（无障碍）。
 */
@OptIn(ExperimentalTestApi::class)
class SessionMetaLongPressUiTest {

    // ------------------------------------------------------------------ 测试数据

    /**
     * 失败注入：只有 `renameSession` 抛错，其余照常委托（FakeMetaActions 是 final，
     * 用接口委托而不是继承 —— 生产类不为测试开门）。
     */
    private class ExplodingRenameFake(
        private val inner: FakeMetaActions = FakeMetaActions(),
    ) : SessionMetaActions by inner {
        override suspend fun renameSession(sessionId: String, title: String) {
            throw IllegalStateException("core 断开（注入）")
        }
    }

    private val ungroupedRow = SessionSummary(
        id = "s-4",
        title = "随手记",
        preview = "",
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000L - 120_000,
        updatedLabel = "2 分钟前",
        groupId = null,
    )

    private val groupedRow = SessionSummary(
        id = "s-2",
        title = "客户端 UI 拆棒",
        preview = "",
        bucket = TimeBucket.Today,
        updatedAt = 1_700_000_000_000L - 60_000,
        updatedLabel = "1 分钟前",
        groupId = "g-1",
    )

    private fun rows(): List<SessionSummary> = listOf(groupedRow, ungroupedRow)

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

    /** `meta()` 永远交出同一个 fake —— 测试才能断言它的 calls。 */
    private class SameFakeProvider(private val fake: SessionMetaActions) : AdapterProvider {
        override fun meta(sessionId: String): SessionMetaActions = fake
        override fun model(sessionId: String): ModelActions = NoModels
        override fun modelDefault(): ModelActions = NoModels
        override fun turns(sessionId: String): ChatTurnGateway = NoTurns
        override fun uploader(): AttachmentUploader = NoUploader
        override fun modulePages(): ModulePageSource = NoPages
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

    // -------------------------------- 1 · 长按 → 5 动作菜单 → 重命名恰一次真调

    @Test
    fun longPressShowsTheFiveActionsAndRenameHitsTheAdapterExactlyOnce() = runComposeUiTest {
        val fake = FakeMetaActions()
        val ui = state(HostSource(SameFakeProvider(fake), rows()))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(ungroupedRow.title).fetchSemanticsNodes().isNotEmpty()
        }

        // 长按未分组行 → 菜单出现，5 个动作全部在列。
        onNodeWithText(ungroupedRow.title).performTouchInput { longClick() }
        waitForIdle()
        listOf(
            MetaCopy.MENU_RENAME,
            MetaCopy.MENU_PIN, // s-4 未置顶 → 「置顶」
            MetaCopy.MENU_ARCHIVE, // s-4 未归档 → 「归档」
            MetaCopy.MENU_MOVE_TO_GROUP,
            MetaCopy.MENU_DELETE,
        ).forEach { label ->
            assertTrue(
                onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty(),
                "长按菜单必须列出「$label」",
            )
        }

        // 重命名 → 输入 → 保存：适配器恰一次、参数正确，乐观标题上屏。
        onNodeWithText(MetaCopy.MENU_RENAME).performClick()
        waitForIdle()
        onNode(hasSetTextAction() and hasText(ungroupedRow.title))
            .performTextReplacement("真接上了的名字")
        onNodeWithText(MetaCopy.GROUP_SAVE).performClick()
        waitUntil(timeoutMillis = 10_000) {
            fake.calls.any { it.op == "session/rename" }
        }
        waitForIdle()

        assertEquals(
            listOf("session/rename:s-4:真接上了的名字"),
            fake.calls.filter { it.op == "session/rename" }.map { "${it.op}:${it.detail}" },
            "适配器必须恰一次收到正确参数的 session/rename",
        )
        assertTrue(
            onAllNodesWithText("真接上了的名字").fetchSemanticsNodes().isNotEmpty(),
            "乐观标题必须立即出现在列表上（这就是「点了有反应」）",
        )
    }

    // --------------------------- 2 · 失败注入：标题回原位 + 可见提示（绝不假成功）

    @Test
    fun failingAdapterRollsBackTheTitleAndShowsTheBanner() = runComposeUiTest {
        val fake = ExplodingRenameFake()
        val ui = state(HostSource(SameFakeProvider(fake), rows()))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(ungroupedRow.title).fetchSemanticsNodes().isNotEmpty()
        }

        onNodeWithText(ungroupedRow.title).performTouchInput { longClick() }
        waitForIdle()
        onNodeWithText(MetaCopy.MENU_RENAME).performClick()
        waitForIdle()
        onNode(hasSetTextAction() and hasText(ungroupedRow.title))
            .performTextReplacement("不该生效的名字")
        onNodeWithText(MetaCopy.GROUP_SAVE).performClick()
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("重命名失败", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        // 行标题回到原位：乐观值被回滚，投影跟着回原样。
        assertTrue(
            onAllNodesWithText(ungroupedRow.title).fetchSemanticsNodes().isNotEmpty(),
            "失败后行标题必须回到原位（回滚）",
        )
        // 可见提示：失败横条 + 弹层不关（留在原地带原因，可改了重试）。
        assertTrue(
            onAllNodesWithText("重命名失败", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "失败横条必须可见（绝不静默失败）",
        )
        assertTrue(
            onAllNodesWithText(MetaCopy.GROUP_SAVE).fetchSemanticsNodes().isNotEmpty(),
            "失败后重命名弹层必须留在原地（用户能读到原因并重试）",
        )
    }

    // ------------------------------------- 3 · 真源零会话：不出现任何菜单入口

    @Test
    fun aRealSourceWithZeroSessionsShowsNoMenuEntry() = runComposeUiTest {
        val fake = FakeMetaActions()
        val ui = state(HostSource(SameFakeProvider(fake), emptyList()))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }
        waitUntil(timeoutMillis = 10_000) {
            ui.sessionsPhase == vade.ui.shell.LoadPhase.Ready && ui.sessionRows.isEmpty()
        }
        waitForIdle()

        listOf(
            MetaCopy.MENU_RENAME,
            MetaCopy.MENU_PIN,
            MetaCopy.MENU_ARCHIVE,
            MetaCopy.MENU_MOVE_TO_GROUP,
            MetaCopy.MENU_DELETE,
            MetaCopy.MENU_NEW_GROUP,
        ).forEach { label ->
            assertEquals(
                0,
                onAllNodesWithText(label).fetchSemanticsNodes().size,
                "真源零会话时不许出现菜单入口「$label」",
            )
        }
    }

    // ------------------------------------------------- 4 · Esc 关闭长按菜单（无障碍）

    @Test
    fun escapeClosesTheLongPressMenu() = runComposeUiTest {
        val fake = FakeMetaActions()
        val ui = state(HostSource(SameFakeProvider(fake), rows()))
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                Box(Modifier.size(400.dp, 900.dp)) { AppShell(ui) }
            }
        }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(ungroupedRow.title).fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithText(ungroupedRow.title).performTouchInput { longClick() }
        waitForIdle()
        assertTrue(
            onAllNodesWithText(MetaCopy.MENU_RENAME).fetchSemanticsNodes().isNotEmpty(),
            "前置：菜单必须开着",
        )

        // 按键注入必须指定到弹层内节点：菜单开着时语义树有两个 root（主窗口 + Popup）。
        onNodeWithText(MetaCopy.MENU_RENAME).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(
            0,
            onAllNodesWithText(MetaCopy.MENU_RENAME).fetchSemanticsNodes().size,
            "Esc 必须关闭长按菜单（键盘可操作）",
        )
        assertEquals(
            0,
            fake.calls.count { it.op != "session/group.list" },
            "只是开关菜单，不得产生任何**写**适配器回调（group.list 是挂载时的合法读）",
        )
    }
}
