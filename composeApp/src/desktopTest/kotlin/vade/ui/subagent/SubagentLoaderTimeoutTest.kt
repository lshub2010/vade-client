// g-model-refresh · SubagentLoader 的超时形取消回归：`subagent/list` 超时（裸 CE 形状到达
// 加载器）必须落 Failed（可重试），绝不卡死在 Loading——旧代码 `catch (CancellationException)
// { return@launch }` 把超时当真取消，弱网下面板永远「加载中」，连重试入口都不给。
package vade.ui.subagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import vade.ui.data.VadeErrorCategory
import vade.ui.protocol.harvestedTimeoutCancellationException
import vade.ui.protocol.waitUntil
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SubagentLoaderTimeoutTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /** 只喂超时的假源：`list` 一被调就抛真 TCE（经真实 withTimeout 收割，无公开构造器）。 */
    private class TimeoutSource : SubagentSource {
        val calls = java.util.concurrent.atomic.AtomicInteger()

        override suspend fun list(sessionId: String?): SubagentPage {
            calls.incrementAndGet()
            throw harvestedTimeoutCancellationException()
        }
    }

    @Test
    fun timeoutShapedCancellationIsAFailedStateNeverAStuckLoading() = runBlocking {
        val source = TimeoutSource()
        val loader = SubagentLoader(source, scope)

        loader.load(null) // 全部会话：一次 load 恰好一帧 subagent/list
        assertEquals(SubagentLoadState.Loading, loader.state, "同步进 Loading")
        waitUntil { loader.state is SubagentLoadState.Failed }

        val failed = assertIs<SubagentLoadState.Failed>(loader.state)
        assertEquals(VadeErrorCategory.Retryable, failed.error.category, "超时归「可重试」")
        assertTrue(
            failed.error.retryLabel == "重试",
            "可重试桶的重试文案必须是「重试」（无重试出口的面板等于永久加载中）",
        )

        // 重试入口真的重发（次数 1→2）：同样的超时再次落 Failed，而不是静默吞掉。
        loader.retry()
        waitUntil { source.calls.get() >= 2 }
        waitUntil { loader.state is SubagentLoadState.Failed }
    }
}
