package vade.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import vade.ui.data.AutoConnectingSessionSource
import vade.ui.data.ConnectionStateHost
import vade.ui.data.FakeSessionSource
import vade.ui.data.RealSessionSource
import vade.ui.data.RealSessionState

/**
 * T1 · 连接态的**纯映射**层（无 Compose）：
 *
 *  - 协议四态（Disconnected / Connecting / Connected / Reconnecting）各自投影成一条可读文案；
 *  - 重连态必须带次数与指数退避等待（`500ms → 30s`），否则「界面像停住」；
 *  - **重连 / 离线 / 连接中都不许出现「已连接」**（读屏与视觉都按这一条验收）；
 *  - [ConnectionStateHost] 暴露的是 [RealSessionSource] 自己那条流，不是第二份状态；
 *    夹具源没有传输 ⇒ 不实现接口 ⇒ 顶栏不画徽标（绝不冒充已连接）。
 */
class ConnectionStatusTest {

    // ------------------------------------------------------------- 三态映射

    @Test
    fun connectedMapsToTheOnlyHealthyState() {
        val status = connectionStatusOf(RealSessionState.Connected)
        assertEquals(ConnectionPhase.Connected, status.phase)
        assertEquals("已连接", status.label)
        assertNull(status.detail, "健康态没有需要解释的第二行")
        assertTrue(status.isConnected)
        assertEquals("已连接 core", status.description)
    }

    @Test
    fun reconnectingCarriesAttemptAndExponentialBackoff() {
        val status = connectionStatusOf(RealSessionState.Reconnecting(attempt = 3, delayMs = 2_000))
        assertEquals(ConnectionPhase.Reconnecting, status.phase)
        assertEquals("重连中 2s", status.label)
        assertEquals("第 3 次尝试失败，2s 后自动重试", status.detail)
        assertTrue(status.description.contains("第 3 次尝试失败"), "读屏必须听到次数")
        assertTrue(status.description.contains("2s 后自动重试"), "读屏必须听到退避等待")
        assertFalse(status.isConnected)
    }

    @Test
    fun disconnectedMapsToOffline() {
        val status = connectionStatusOf(RealSessionState.Disconnected)
        assertEquals(ConnectionPhase.Offline, status.phase)
        assertEquals("离线", status.label)
        assertEquals("未连接 core", status.detail)
        assertFalse(status.isConnected)
    }

    @Test
    fun connectingIsNeitherClaimedConnectedNorCalledAFailure() {
        val status = connectionStatusOf(RealSessionState.Connecting)
        assertEquals(ConnectionPhase.Connecting, status.phase)
        assertEquals("连接中", status.label)
        assertFalse(status.isConnected)
    }

    /** 三态 + 瞬态：重连 / 离线 / 连接中的**任何**文案都不许出现「已连接」。 */
    @Test
    fun noNonHealthyStateEverClaimsConnected() {
        val nonHealthy = listOf(
            RealSessionState.Disconnected,
            RealSessionState.Connecting,
            RealSessionState.Reconnecting(attempt = 1, delayMs = 500),
            RealSessionState.Reconnecting(attempt = 8, delayMs = 30_000),
        )
        for (state in nonHealthy) {
            val status = connectionStatusOf(state)
            assertFalse(status.isConnected, "$state must not be healthy")
            assertFalse(status.label.contains("已连接"), "$state label lies: ${status.label}")
            assertFalse(status.description.contains("已连接"), "$state description lies: ${status.description}")
            assertTrue(status.detail == null || !status.detail.contains("已连接"))
        }
    }

    // ------------------------------------------------------- 退避文案（纯函数）

    @Test
    fun backoffLabelsStayShortAndHonestAtEveryStepOfTheRamp() {
        assertEquals("500ms", backoffLabel(500), "首跳是毫秒级")
        assertEquals("1s", backoffLabel(1_000))
        assertEquals("1.5s", backoffLabel(1_500), "非整秒保留一位小数")
        assertEquals("2s", backoffLabel(2_000))
        assertEquals("30s", backoffLabel(30_000), "上限 30s 不许写成 30.0s")
        assertEquals("0ms", backoffLabel(-1), "坏值不渲染负数")
    }

    @Test
    fun retryAttemptZeroIsClampedForDisplay() {
        // 协议层不会发 0，但展示层不能把坏值渲染成「第 0 次尝试」。
        val status = connectionStatusOf(RealSessionState.Reconnecting(attempt = 0, delayMs = 500))
        assertTrue(status.detail!!.startsWith("第 1 次尝试失败"), "actual=${status.detail}")
    }

    // -------------------------------------------------------- 状态流接线（复用而非新造）

    @Test
    fun realSourceExposesItsExistingFlowAsConnectionTruth() {
        val source = RealSessionSource(host = "127.0.0.1", port = 1)
        assertSame(
            source.state,
            source.connectionState,
            "ConnectionStateHost 必须复用 RealSessionSource.state，不能另建第二套状态",
        )
        assertEquals(RealSessionState.Disconnected, source.connectionState.value)
    }

    @Test
    fun productionShimAlsoExposesTheRealSourcesFlow() {
        // 生产装配 `SourceConfig.realAutoConnecting` 返回的**就是**本 shim（内部类，测试同模块）。
        // 它必须透传，否则 AppShell 的 `state.source as? ConnectionStateHost` 在真机上恒 null。
        val real = RealSessionSource(host = "127.0.0.1", port = 1)
        val shim = AutoConnectingSessionSource(real, "127.0.0.1", 1)
        assertSame(
            real.connectionState,
            shim.connectionState,
            "生产 shim 必须透传真源的连接流，不能另建一份",
        )
    }

    @Test
    fun fixtureSourceHasNoConnectionTruthToShow() {
        assertNull(
            FakeSessionSource() as? ConnectionStateHost,
            "夹具源没有传输：顶栏/会话头部不许因此画「已连接」",
        )
    }
}
