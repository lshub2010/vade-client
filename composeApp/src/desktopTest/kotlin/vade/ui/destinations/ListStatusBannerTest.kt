package vade.ui.destinations

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import vade.ui.App
import vade.ui.chat.ChatMessage
import vade.ui.data.FakeSessions
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TimeBucket
import vade.ui.data.VadeEvent
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * g-first-run · P1/P2 的渲染侧：列表头那条横幅在**真组合里**说的是真话。
 *
 * 三条断言线，正对着首次真实验收的两张截图：
 *
 *  1. **假源仍然被标注**（回归）：`02-paired-sessions.png` 之前的假数据列表必须还写着「假数据」，
 *     否则修 P1 就变成「把诚实的提示也删掉」。
 *  2. **真源连上以后不许再说「假数据 / 未连接 core」**（P1）：验收时真 core 造出的
 *     `E2E-cad91adc` 已经上屏，横幅却还在骗人。
 *  3. **真源连不上时必须留下文字**（P2）：`04-real-no-paircode.png` 是 `0 个会话` + 假数据提示 +
 *     22 秒零反馈。现在同样的启动方式必须看到分类错误和一条可照抄的自救命令。
 *
 * 屏幕级用 [SessionsScreen] 直连（判定层在 `shell/ListStatusOfTest` 单独钉），整树级用
 * `App(source = …)` ——后者证明的是「`AppShell` 真的把 loader 的状态接过来了」，也就是 P2
 * 的接线本身。
 */
@OptIn(ExperimentalTestApi::class)
class ListStatusBannerTest {

    // ------------------------------------------------------------------ 测试替身

    /** 非假源，一行真数据；标题在 fixtures 里不存在。 */
    private class OneRowSource : SessionSource {
        override suspend fun sessions(): List<SessionSummary> = listOf(
            SessionSummary(
                id = "ui-assert-real",
                title = REAL_ROW_TITLE,
                preview = "g-first-run · 注入源的一行",
                bucket = TimeBucket.Today,
                updatedAt = 1_700_000_000_000,
                updatedLabel = "刚刚",
            ),
        )

        override suspend fun messages(sessionId: String): List<ChatMessage> = emptyList()

        override fun observeEvents(): Flow<VadeEvent> = emptyFlow()
    }

    /** 非假源，读列表失败——正是「real 但没有有效配对码」在 loader 里的结果。 */
    private class FailingSource : SessionSource {
        override suspend fun sessions(): List<SessionSummary> = throw VadeException(
            VadeErrorCodes.UNAVAILABLE,
            "core at 127.0.0.1:18755 not reachable within 15000ms (auto-connect)",
        )

        override suspend fun messages(sessionId: String): List<ChatMessage> = emptyList()

        override fun observeEvents(): Flow<VadeEvent> = emptyFlow()
    }

    /**
     * 非假源，等到 [gate] 放行才答——用来把「首读在飞」那一刻钉住。
     *
     * 为什么需要它：源若在首帧组合里就答完，那次组合看不到 `phase=Ready`（组合是 MVCC
     * 快照，见 `listStatusOf` 的 KDoc），于是「连接中 → 已连接」的**失效**路径根本不会被
     * 走到。真 core 的握手永远晚于首帧，闸门复刻的就是这个正常时序。
     */
    private class GatedSource(private val gate: CompletableDeferred<Unit>) : SessionSource {
        override suspend fun sessions(): List<SessionSummary> {
            gate.await()
            return OneRowSource().sessions()
        }

        override suspend fun messages(sessionId: String): List<ChatMessage> = emptyList()

        override fun observeEvents(): Flow<VadeEvent> = emptyFlow()
    }

    /** 屏上有几个含 [text] 的文本节点（0 = 界面上根本没有这句话）。 */
    private fun androidx.compose.ui.test.ComposeUiTest.countContaining(text: String): Int =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size

    private fun androidx.compose.ui.test.ComposeUiTest.screen(status: ListStatus) {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                SessionsScreen(
                    groups = FakeSessions.groups(),
                    selectedId = null,
                    twoPane = false,
                    onOpen = {},
                    status = status,
                )
            }
        }
    }

    // ------------------------------------------------- 1 · 屏幕级：四种状态各自的画面

    @Test
    fun sessionsScreenKeepsTheFixtureNoteWhenHandedFixtures() = runComposeUiTest {
        screen(ListStatus.Fixtures)
        onNodeWithText(FIXTURE_NOTE).assertExists()
    }

    @Test
    fun sessionsScreenDefaultStatusIsThePreChangeBehaviour() = runComposeUiTest {
        // 不传 status 的调用点必须与接线前逐字节相同（默认值 = Fixtures），这条钉的是
        // 「本棒没有顺手改掉任何别的调用点的画面」。
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                SessionsScreen(
                    groups = FakeSessions.groups(),
                    selectedId = null,
                    twoPane = false,
                    onOpen = {},
                )
            }
        }
        onNodeWithText(FIXTURE_NOTE).assertExists()
    }

    @Test
    fun sessionsScreenDrawsNoBannerAtAllWhenConnected() = runComposeUiTest {
        screen(ListStatus.Connected)
        // 真源已连上：既没有「假数据」，也没有「未连接 core」——一个节点都不许有
        // （不是渲染成空字符串：空文本仍占行高，列表顶部会凭空多一段空白）。
        assertTrue(countContaining("假数据") == 0, "已连接的列表上不该再出现「假数据」")
        assertTrue(countContaining("未连接 core") == 0, "已连接的列表上不该再出现「未连接 core」")
        // 行本身还在（证明这条断言不是靠「整屏空白」通过的）。
        onNodeWithText("RFC-0004 附件与多模态评审").assertExists()
    }

    @Test
    fun sessionsScreenRendersTheErrorAndTheSelfRescueCommandWhenFailed() = runComposeUiTest {
        screen(
            ListStatus.Failed(
                headline = "无法连接 core",
                detail = "需要重连（UNAVAILABLE）：core at 127.0.0.1:18755 not reachable within 15000ms (auto-connect)",
                remedy = "VADE_SOURCE=real VADE_HOST=127.0.0.1 VADE_PORT=18755 " +
                    "VADE_PAIR_CODE=<配对码> gradle :composeApp:run",
            ),
        )
        onNodeWithText("无法连接 core").assertExists()
        assertTrue(
            countContaining("UNAVAILABLE") == 1,
            "分类错误必须可见（P2 的反面：连不上时界面不是空白）",
        )
        // 自救命令整体是一个文本节点：用户要能一次选中整行复制。
        assertTrue(countContaining("VADE_SOURCE=real") == 1, "自救命令要可见")
        assertTrue(countContaining("VADE_PAIR_CODE=") == 1, "自救命令里必须有配对码占位")
        assertTrue(countContaining("假数据") == 0, "失败态不许拿「假数据」当解释")
    }

    // --------------------------------------- 2 · 整树级：AppShell 真的接上了 loader 状态

    @Test
    fun appWithFixtureSourceStillLabelsTheListAsFixtures() = runComposeUiTest {
        // 默认源 = FakeSessionSource → 「假数据」必须还在（这是本棒的回归线）。
        setContent { App() }
        onNodeWithText(FIXTURE_NOTE).assertExists()
    }

    @Test
    fun appWithConnectedRealSourceNeverClaimsFixtures() = runComposeUiTest {
        // 这个源在**首帧组合进行中**就答完（本棒实测的时序：组合是 MVCC 快照，落在这个窗口
        // 里的 `phase=Ready` 那次组合看不见，也不会再失效）。列表里已经有真行，横幅就必须
        // 静默——`listStatusOf` 的 `hasRows` 判据就是为这个时序设的 fail-safe。
        setContent { App(source = OneRowSource()) }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(REAL_ROW_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(countContaining("假数据") == 0, "真源已连上，界面仍在说「假数据」")
        assertTrue(countContaining("未连接 core") == 0, "真源已连上，界面仍在说「未连接 core」")
        assertTrue(countContaining("正在连接") == 0, "已连上就不该还挂着「正在连接」")
    }

    @Test
    fun appShowsConnectingWhileTheFirstPageIsInFlightThenGoesQuiet() = runComposeUiTest {
        // 正常时序的另一半（写入落在组合之后，会正常失效）：首屏没有行时必须说「正在连接
        // core <endpoint>」，第一批行到了以后那句话要消失。用一道闸门把「在飞」这一刻钉住，
        // 否则这条测试只能靠运气碰上。
        val gate = CompletableDeferred<Unit>()
        setContent { App(source = GatedSource(gate)) }
        waitUntil(timeoutMillis = 10_000) { countContaining("正在连接 core") == 1 }

        gate.complete(Unit)
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(REAL_ROW_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        waitUntil(timeoutMillis = 10_000) { countContaining("正在连接") == 0 }
        assertTrue(countContaining("假数据") == 0, "真源不许出现「假数据」")
    }

    @Test
    fun appWithFailingRealSourceShowsTheErrorInsteadOfSilence() = runComposeUiTest {
        setContent { App(source = FailingSource()) }
        waitUntil(timeoutMillis = 10_000) { countContaining("无法连接 core") == 1 }

        onNodeWithText("无法连接 core").assertExists()
        // 分类文案 + 传输原话（`04-real-no-paircode.png` 那一刻什么都不显示）。
        assertTrue(countContaining("需要重连") == 1, "分类错误不可见")
        assertTrue(countContaining("not reachable within 15000ms") == 1, "传输原话不可见")
        // 自救通道：唯一能连上的环境变量必须出现在界面上，且带配对码从哪取。
        assertTrue(countContaining("VADE_SOURCE=real") == 1, "自救命令不可见")
        assertTrue(countContaining("VADE_PAIR_CODE=") == 1, "自救命令没带配对码")
        assertTrue(countContaining("pair_code=") == 1, "没说配对码从哪取")
        // 失败态不能再顶着「假数据」——那会把「连不上」误报成「这是示例数据」。
        assertTrue(countContaining("假数据") == 0, "失败态仍在说「假数据」")
    }

    private companion object {
        const val REAL_ROW_TITLE = "G1 断言·注入源的一行"
    }
}
