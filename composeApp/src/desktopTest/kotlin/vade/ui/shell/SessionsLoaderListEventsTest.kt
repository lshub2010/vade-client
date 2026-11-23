package vade.ui.shell

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import vade.ui.data.FakeSessions
import vade.ui.data.SessionListChangedEvent
import vade.ui.data.SessionSource
import vade.ui.data.SessionSummary
import vade.ui.data.TurnCompletedEvent
import vade.ui.data.VadeEvent
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1-3 · 会话列表的实时刷新必须走**列表级**事件缝，而不是被聊天投影门控的
 * `observeEvents()`：列表根没有可见会话时，别的会话的 `session.*` / `turn.*` 帧也要
 * 能触发一次 `session/list` 重拉。
 */
class SessionsLoaderListEventsTest {

    private class ProbeSource(
        private val listEvents: MutableSharedFlow<VadeEvent>,
        private val subscribed: CompletableDeferred<Unit>,
    ) : SessionSource by FakeSessions {
        var sessionsCalls = 0
            private set

        /** 聊天投影缝：故意永不发帧 —— 列表若还接错这条，刷新就不会发生。 */
        override fun observeEvents(): Flow<VadeEvent> = emptyFlow()

        override fun observeListEvents(): Flow<VadeEvent> =
            listEvents.onSubscription { subscribed.complete(Unit) }

        override suspend fun sessions(includeArchived: Boolean): List<SessionSummary> {
            sessionsCalls += 1
            return emptyList()
        }
    }

    @Test
    fun aTurnEventOnTheListSeamRefreshesTheListEvenWithoutAVisibleSession() = runBlocking {
        val listEvents = MutableSharedFlow<VadeEvent>(extraBufferCapacity = 8)
        val subscribed = CompletableDeferred<Unit>()
        val source = ProbeSource(listEvents, subscribed)
        val loader = SessionsLoader(source, this)
        try {
            loader.start()
            waitUntil { source.sessionsCalls >= 1 }
            subscribed.await()

            // 别的会话（列表根没有 attach）发生回合结束：列表必须重拉一次。
            listEvents.emit(
                TurnCompletedEvent(seq = 1, turnId = "t-elsewhere", totalTokens = 3, stoppedReason = "stop"),
            )
            waitUntil { source.sessionsCalls >= 2 }
            assertEquals(LoadPhase.Ready, loader.sessionsPhase)

            // 契约指定的失效边同样要重拉（别的设备建/删了会话）。
            listEvents.emit(
                SessionListChangedEvent(seq = 2, changedSessionId = "s-new", change = "added", by = "device"),
            )
            waitUntil { source.sessionsCalls >= 3 }
            assertTrue(source.sessionsCalls >= 3, "列表刷新不能饿死")
        } finally {
            loader.dispose()
        }
    }
}
