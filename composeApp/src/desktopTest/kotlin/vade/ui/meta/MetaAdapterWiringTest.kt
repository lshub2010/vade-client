package vade.ui.meta

import kotlinx.coroutines.runBlocking
import vade.ui.chat.AttachmentUploader
import vade.ui.chat.ChatTurnGateway
import vade.ui.data.AdapterProvider
import vade.ui.model.ModelActions
import vade.ui.module.ModulePageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * g-wire-screens · ③ `SessionMetaState.forAdapters` 的三段回退测试。
 *
 * **形参 `actions` > `adapters.meta(sessionId)` > `FakeMetaActions`**，三条都必须在
 * 可观察的行为上分得开：
 *  - 走适配器时，状态机的写动作真的落到适配器交出的那个对象上（用 `FakeMetaActions`
 *    自己的调用台账验证，而不是靠字段相等）；
 *  - `sessionId` 既是查找参数也是 `focusSessionId`，原样传入；
 *  - 没接线 / 形参给了 ⇒ 一次都不查适配器；
 *  - 查找抛异常 ⇒ 退回假实现 + `wiringDegradation` 有可读原因（不崩、不静默吞）。
 */
class MetaAdapterWiringTest {

    private val sessionId = "s-meta-wire"

    private fun rows(): List<MetaSession> = listOf(
        MetaSession(id = sessionId, title = "待置顶", pinned = false),
        MetaSession(id = "s-other", title = "别的会话", pinned = false),
    )

    /** 只记录 `meta(sessionId)` 的适配器包；其余查找在 meta 用例里用不到。 */
    private class MetaOnlyProvider(private val actions: SessionMetaActions) : AdapterProvider {
        val metaIds = mutableListOf<String>()

        override fun meta(sessionId: String): SessionMetaActions {
            metaIds += sessionId
            return actions
        }

        override fun model(sessionId: String): ModelActions = error("meta 用例不该查 model")
        override fun modelDefault(): ModelActions = error("meta 用例不该查 modelDefault")
        override fun turns(sessionId: String): ChatTurnGateway = error("meta 用例不该查 turns")
        override fun uploader(): AttachmentUploader = error("meta 用例不该查 uploader")
        override fun modulePages(): ModulePageSource = error("meta 用例不该查 modulePages")
    }

    /** 查找就抛的适配器包。 */
    private class ExplodingMetaProvider(private val reason: String = "meta-probe-boom") : AdapterProvider {
        override fun meta(sessionId: String): SessionMetaActions = throw IllegalStateException(reason)
        override fun model(sessionId: String): ModelActions = throw IllegalStateException(reason)
        override fun modelDefault(): ModelActions = throw IllegalStateException(reason)
        override fun turns(sessionId: String): ChatTurnGateway = throw IllegalStateException(reason)
        override fun uploader(): AttachmentUploader = throw IllegalStateException(reason)
        override fun modulePages(): ModulePageSource = throw IllegalStateException(reason)
    }

    // ------------------------------------------------------------ 2. 真用了适配器

    @Test
    fun metaWiringUsesTheProviderActionsForThisSession() {
        val provided = FakeMetaActions()
        val provider = MetaOnlyProvider(provided)

        val state = SessionMetaState.forAdapters(
            adapters = provider,
            sessionId = sessionId,
            sessions = rows(),
        )

        assertEquals(listOf(sessionId), provider.metaIds, "meta(sessionId) 必须被调一次且 id 原样")
        assertEquals(sessionId, state.focusSessionId, "sessionId 同时是聚焦会话")
        assertNull(state.wiringDegradation, "顺利拿到适配器不该记退化")

        // 写动作真的落到适配器交出的对象上（台账是那个对象的，不是状态机的）。
        runBlocking { state.togglePin(sessionId) }
        assertTrue(
            provided.calls.any { it.op == "session/pin" && it.detail == "$sessionId:true" },
            "置顶没有落到适配器交出的 actions 上：${provided.calls}",
        )
        // 三态能力跟着注入的实现走：FakeMetaActions 实现了 SessionMetaGroupEdit。
        assertTrue(state.canClearGroupIconColor, "注入的 actions 有三态能力时，状态机必须认出来")
    }

    @Test
    fun metaWiringPrefersExplicitActionsOverTheProvider() {
        val explicit = FakeMetaActions()
        val provider = MetaOnlyProvider(FakeMetaActions())

        val state = SessionMetaState.forAdapters(
            adapters = provider,
            sessionId = sessionId,
            sessions = rows(),
            actions = explicit,
        )

        assertEquals(emptyList(), provider.metaIds, "形参给了 actions 就一次都不该查适配器")
        runBlocking { state.togglePin(sessionId) }
        assertTrue(explicit.calls.any { it.op == "session/pin" }, "形参的 actions 必须是被用的那一份")
    }

    // -------------------------------------------------------- 1. null ⇒ 内存假实现

    @Test
    fun metaWiringWithoutAdaptersKeepsFakeMetaActions() {
        val state = SessionMetaState.forAdapters(
            adapters = null,
            sessionId = sessionId,
            sessions = rows(),
        )

        assertNull(state.wiringDegradation)
        assertEquals(sessionId, state.focusSessionId)
        // 状态机仍然完全可用：假实现把置顶写进自己的台账与状态流。
        runBlocking { state.togglePin(sessionId) }
        assertTrue(state.sessions.first { it.id == sessionId }.pinned, "内存假实现上置顶必须生效")
    }

    // ----------------------------------------------------------- 7. 异常可预期

    @Test
    fun metaWiringDegradesWithAVisibleReasonWhenTheProviderThrows() {
        val state = SessionMetaState.forAdapters(
            adapters = ExplodingMetaProvider(),
            sessionId = sessionId,
            sessions = rows(),
        )

        val note = assertNotNull(state.wiringDegradation, "查找抛异常必须留下可读原因")
        assertTrue(note.contains(SessionMetaState.ADAPTER_FALLBACK_NOTE), "原因必须点明已退回内存假数据：$note")
        assertTrue(note.contains("meta-probe-boom"), "必须带上适配器自己的原因：$note")

        // 退化之后状态机仍可用（不崩），而且功能是假实现的功能。
        runBlocking { state.togglePin(sessionId) }
        assertTrue(state.sessions.first { it.id == sessionId }.pinned)
        assertTrue(state.canClearGroupIconColor, "退回的 FakeMetaActions 自带三态能力")
    }
}
