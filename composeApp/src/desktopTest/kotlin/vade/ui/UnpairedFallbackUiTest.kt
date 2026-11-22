package vade.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import vade.ui.data.PairingRuntime
import vade.ui.data.PairingRuntimeState
import vade.ui.data.SessionRevocation
import vade.ui.data.UnpairedSessionSource
import vade.ui.destinations.UNPAIRED_NOTE
import vade.ui.destinations.UNPAIRED_NOTE_TAG
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeTransport
import vade.ui.protocol.VadeTransportFactory

/**
 * P1-6 · 桥从 `Paired` 退回 `Unpaired` 之后，列表**不许**回落到启动时的夹具源。
 *
 * 走的是真组合根（`App()` 的默认参数 = 生产默认启动：`source = FakeSessionSource`）：
 * 这一条只有在整树里才成立 —— 单看 `App` 的 `effectiveSource` 无法证明「屏幕上没有假会话」。
 * 触发点取审查列出的那种：运行期 401 → `noteCredentialRejected()`（core 重启的常见后果）。
 */
@OptIn(ExperimentalTestApi::class)
class UnpairedFallbackUiTest {

    @BeforeTest
    fun resetBefore() = PairingRuntime.clearForTests()

    @AfterTest
    fun resetAfter() = PairingRuntime.clearForTests()

    @Test
    fun aLostPairingShowsAnEmptyListAndAClassifiedBannerInsteadOfFixtures() = runComposeUiTest {
        // 入口声明过本次启动（默认启动就是这条），随后运行期 401 把桥打回 Unpaired。
        PairingRuntime.noteBooted()
        PairingRuntime.noteCredentialRejected()

        setContent { App() }
        waitForIdle()

        assertEquals(
            0,
            onAllNodesWithText(FIXTURE_ROW).fetchSemanticsNodes().size,
            "配对失效之后，列表里一个夹具会话都不许出现（P1-6：真模式显示假数据）",
        )
        // 空列表 + 分类横幅：空不是「0 个会话」，也不是「假数据」。
        onNodeWithTag(UNPAIRED_NOTE_TAG).assertExists()
        onNodeWithText(UNPAIRED_NOTE).assertExists()
        assertTrue(
            onAllNodesWithText("假数据").fetchSemanticsNodes().isEmpty(),
            "没有数据源时不许用「假数据」解释一个空列表",
        )
    }

    @Test
    fun anExplicitDisconnectAlsoStopsServingFixtures() = runComposeUiTest {
        // unpair 之后桥是 Unpaired(disconnected = true)：用户亲手断开了 core，屏幕上却还挂着
        // 假会话，是最容易被当成「数据还在」的那类谎。
        PairingRuntime.noteBooted()
        PairingRuntime.install(
            object : VadeTransportFactory {
                override suspend fun connect(): VadeTransport =
                    throw IllegalStateException("这个用例不需要连接")
            },
            "core.local",
            8443,
        )

        assertEquals(SessionRevocation.Revoked, PairingRuntime.unpair())
        val unpaired = PairingRuntime.state as PairingRuntimeState.Unpaired
        assertTrue(unpaired.disconnected, "unpair 必须留下 disconnected 标记")

        setContent { App() }
        waitForIdle()

        assertEquals(0, onAllNodesWithText(FIXTURE_ROW).fetchSemanticsNodes().size)
        onNodeWithText(UNPAIRED_NOTE).assertExists()
    }

    @Test
    fun firstRunStillShowsTheScaffoldingFixtures() = runComposeUiTest {
        // 回归线：首次运行（裸 Unpaired()：没配过、也没丢过）仍然是「夹具 + 配对卡」。
        // P1-6 修的是「真数据丢过」，不是把首屏改成空白。
        PairingRuntime.noteBooted()

        setContent { App() }
        waitForIdle()

        onNodeWithText(FIXTURE_ROW).assertExists()
        assertTrue(
            onAllNodesWithText(UNPAIRED_NOTE).fetchSemanticsNodes().isEmpty(),
            "首次运行不是「没有数据源」：不该出现那条横幅",
        )
    }

    // ------------------------------------------------------------------ 判定与空源本身

    @Test
    fun lostRealDataOnlyCoversTheStatesThatHadRealData() {
        assertFalse(PairingRuntimeState.Unpaired().lostRealData, "首次运行不算")
        assertFalse(PairingRuntimeState.Unpaired(codeRejected = true).lostRealData, "码被拒不改变「有没有过数据」")
        assertTrue(PairingRuntimeState.Unpaired(credentialRejected = true).lostRealData)
        assertTrue(PairingRuntimeState.Unpaired(storedCredentialKept = true).lostRealData)
        assertTrue(PairingRuntimeState.Unpaired(disconnected = true).lostRealData)
    }

    @Test
    fun theEmptySourceNeverPretendsToHaveData() = runBlocking {
        val source = UnpairedSessionSource()
        assertTrue(source.sessions().isEmpty(), "没有连接 = 空列表（不是假数据）")
        assertTrue(source.messages("s-1").isEmpty())

        val create = assertFailsWith<VadeException> { source.createSession() }
        assertEquals(VadeErrorCodes.UNAVAILABLE, create.code, "没有连接时新建必须响亮失败，不许伪造本地会话")
    }

    private companion object {
        /** `FakeSessions` 里的一行（`SourceSwitchUiTest` 用同一行钉「默认 = 夹具」）。 */
        const val FIXTURE_ROW = "RFC-0004 附件与多模态评审"
    }
}
