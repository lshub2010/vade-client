package vade.ui.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import vade.ui.data.ModelActionsReal
import vade.ui.data.VadeErrorCategory
import vade.ui.protocol.FakeTransport
import vade.ui.protocol.FakeTransportFactory
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.errorFrame
import vade.ui.protocol.idOf
import vade.ui.protocol.requestOf
import vade.ui.protocol.responseFrame
import vade.ui.protocol.testClient
import vade.ui.protocol.waitUntil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * g-activate · `ModelState.forSession` / `forDefault` handed a **real** `ModelActionsReal`
 * (which is exactly what an `AdapterProvider.model(...)` / `modelDefault()` returns once
 * `AppShell` wires the bundle down).
 *
 * ## The defect these tests exist for
 * Both factories used to build the state with `real = null`, i.e. the in-memory seam path.
 * Against a real adapter that is wrong in two independent ways, and this file pins both:
 *
 *  1. **`refresh()` returned immediately** (`real ?: return`), while `ModelActionsReal` starts
 *     at `models = null` and only fills it from its suspend `refreshModels()`. The picker
 *     therefore rendered 「无可用模型」 forever — a claim about the core that was never asked.
 *     Fixed by recognizing the adapter (`ModelState.Companion.wired`) and by splitting the
 *     `models == null` cases: not-pulled-yet ⇒ [ModelPickerContent.Loading], pull failed ⇒
 *     [ModelPickerContent.LoadFailed], seam has no surface / core has none ⇒
 *     [ModelPickerContent.NoModels] (unchanged).
 *  2. **Writes went through the synchronous seam call**, whose fire-and-forget adapter launch
 *     keeps the failure in `ModelActionsReal.lastWriteError`; `ModelState.writeFailure` stayed
 *     `null`, nothing was reverted, and the card showed no error at all.
 *
 * ## The other half: the seam must not move a byte
 * The last three tests drive `InMemoryModelActions` — the fixture path every screen falls back
 * to when `adapters == null` — and assert the pre-change behaviour: `refresh()` a no-op,
 * synchronous writes, `NoModels` for a seam without a catalog, never `Loading`/`LoadFailed`.
 */
class ModelStateSeamPathTest {

    // ------------------------------------------------------------------ harness

    /**
     * A connected client + its transport + the scope the state's pull and writes ride. The
     * block's receiver is the `runBlocking` scope so `async` resolves to
     * `CoroutineScope.async` (the top-level overload is deprecated/error).
     */
    private fun harness(block: suspend CoroutineScope.(VadeClient, FakeTransport, CoroutineScope) -> Unit) {
        val factory = FakeTransportFactory()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        runBlocking {
            val client = testClient(factory)
            client.connect()
            assertTrue(client.awaitConnected(), "client must be connected before the picker pulls")
            try {
                block(this, client, factory.transports[0], scope)
            } finally {
                client.close()
                scope.cancel()
            }
        }
    }

    private fun entryJson(id: String, provider: String, displayName: String, priority: Int): String =
        """{"id":"$id","provider":"$provider","display_name":"$displayName",""" +
            """"input_modalities":["text"],"max_image_bytes":null,"max_image_px":null,""" +
            """"max_images_per_turn":null,"context_window":32768,"supports_tools":null,""" +
            """"priority":$priority,"available":true}"""

    private fun listResult(vararg entries: String, defaultId: String = "claude-sonnet"): String =
        """{"revision":3,"not_modified":false,"models":[""" + entries.joinToString(",") +
            """],"default":{"model_id":"$defaultId","source":"config","resolved":true}}"""

    private val noOverrideJson: String =
        """{"model_id":null,"source":"default","resolved":true,"global_default":"claude-sonnet"}"""

    /** A real adapter over [client], sharing [scope] with the state (writes + pull). */
    private fun realSession(client: VadeClient, scope: CoroutineScope, autoRefresh: Boolean = false): ModelState =
        ModelState.forSession(
            actions = ModelActionsReal(client, scope),
            sessionId = "s-1",
            scope = scope,
            autoRefresh = autoRefresh,
        )

    /** Drives one full session-scope pull: `model/list` then `session/model.get`. */
    private suspend fun CoroutineScope.pullSession(
        state: ModelState,
        transport: FakeTransport,
        listJson: String,
        overrideJson: String = noOverrideJson,
    ) {
        val start = transport.record.size
        val pull = async(Dispatchers.Default) { state.refresh() }
        waitUntil { transport.record.size >= start + 1 }
        assertEquals("model/list", requestOf(transport.record[start]).first)
        transport.push(responseFrame(idOf(transport.record[start]), listJson))
        waitUntil { transport.record.size >= start + 2 }
        assertEquals("session/model.get", requestOf(transport.record[start + 1]).first)
        transport.push(responseFrame(idOf(transport.record[start + 1]), overrideJson))
        pull.await()
    }

    private val twoModels: String get() = listResult(
        entryJson("claude-sonnet", "anthropic", "Claude Sonnet", 10),
        entryJson("local-qwen", "ollama", "Qwen2.5 32B", 20),
    )

    // ------------------------------------- 1 · the real adapter is recognized + pulled

    @Test
    fun forSessionWithTheRealAdapterPullsOnConstructionAndRendersTheRows() = harness { client, transport, scope ->
        // Production shape: no autoRefresh argument — the factory must start the pull itself,
        // because nothing else in the UI ever calls refresh() (the screens are not allowed to).
        val state = realSession(client, scope, autoRefresh = true)

        // Mid-flight: the request is out and no answer has been handled. This is exactly the
        // window that used to fall through to 「无可用模型」.
        waitUntil { transport.record.size >= 1 }
        assertIs<ModelPickerContent.Loading>(
            state.content(),
            "an unanswered pull is 「正在加载」, never 「无可用模型」 (got ${state.content()})",
        )

        transport.push(responseFrame(idOf(transport.record[0]), twoModels))
        waitUntil { transport.record.size >= 2 }
        transport.push(responseFrame(idOf(transport.record[1]), noOverrideJson))
        // g-flake-ui-timing · 等确定终态，不等中间态：`models` 在 refresh() 的 try 里赋值、
        // `loadInFlight = false` 在 finally 里——只等 `models != null` 时，断言
        // 「the answer was handled」正好落在两行之间的观测窗口里（单跑绿、全量红的旧
        // flake）。两个条件**同一次观测**成立 = finally 已跑完 = refresh 到了终态；
        // 下面的 assertFalse(loadInFlight) 一字未动。
        // g-model-refresh 也独立踩到同一个窗口（它写成两句顺序 wait，本解更严：一次观测）。
        waitUntil { state.models != null && !state.loadInFlight }

        assertEquals(listOf("claude-sonnet", "local-qwen"), state.models.orEmpty().map { it.id }, "server order")
        assertTrue(state.loadAttempted)
        assertFalse(state.loadInFlight, "the answer was handled")
        assertNull(state.loadFailure)
        val content = assertIs<ModelPickerContent.Rows>(state.content(), "pulled list must render: ${state.content()}")
        assertTrue(content.interactive, "a live core is writable")
        assertEquals(2, state.rows().size)
    }

    @Test
    fun firstFrameBeforeTheAnswerIsLoadingNotNullAnEmptyCore() = harness { client, _, scope ->
        val state = realSession(client, scope, autoRefresh = false)

        val content = state.content()
        assertIs<ModelPickerContent.Loading>(
            content,
            "models == null before the pull means 「还没问过」, not 「没有模型」",
        )
        assertNotEquals("无可用模型", content.title, "the loading copy must not borrow the empty-state title")
        assertNull(state.models, "still null — never turned into an empty list")
        assertNull(state.loadFailure)
        assertFalse(state.loadAttempted)
        assertFalse(state.loadInFlight)
        assertEquals(emptyList(), state.rows(), "no rows by construction")
    }

    // --------------------------------------------------- 2 · a failed pull is a failure

    @Test
    fun failedPullIsAFailureStateNotNullAnEmptyList() = harness { client, transport, scope ->
        val state = realSession(client, scope, autoRefresh = false)
        val start = transport.record.size
        val pull = async(Dispatchers.Default) { state.refresh() }
        waitUntil { transport.record.size >= start + 1 }
        transport.push(errorFrame(idOf(transport.record[start]), "UNAVAILABLE", "socket is down"))
        // refresh() goes on to the session override pull (the list failure is not a degrade);
        // answering it keeps the test off the 2 s request timeout.
        waitUntil { transport.record.size >= start + 2 }
        transport.push(responseFrame(idOf(transport.record[start + 1]), noOverrideJson))
        pull.await()

        val failure = assertNotNull(state.loadFailure, "a failed pull must be recorded, not swallowed")
        assertEquals(VadeErrorCodes.UNAVAILABLE, failure.code)
        // The frozen C-04 table says UNAVAILABLE = needs-reconnect (the transport died before
        // the reply); the picker must not invent its own gentler bucket.
        assertEquals(VadeErrorCategory.NeedsReconnect, failure.category)
        assertNull(state.models, "a failed pull leaves the surface UNKNOWN — never an empty list")
        assertTrue(state.loadAttempted)
        assertFalse(state.loadInFlight, "the pull finished (badly)")

        val content = state.content()
        assertIs<ModelPickerContent.LoadFailed>(
            content,
            "a failure is its own state; 「无可用模型」 would blame the core for our network: $content",
        )
        assertNotEquals("无可用模型", content.title)
        assertTrue(content.title.contains("失败"), "the title must say the pull failed: ${content.title}")
        assertEquals(emptyList(), state.rows())
        assertFalse(state.readOnly, "a transport failure is NOT the FR-8.46 degrade")
    }

    // ------------------------------------------- 3 · write failures reach writeFailure

    @Test
    fun failedWriteOnTheRealPathRevertsTheSelectionAndLandsInWriteFailure() = harness { client, transport, scope ->
        val state = realSession(client, scope, autoRefresh = false)
        pullSession(state, transport, twoModels)
        assertNull(state.selectedId, "starts on the global default")

        val start = transport.record.size
        assertTrue(state.select("local-qwen"), "selectable row ⇒ a write goes out")
        waitUntil { transport.record.size >= start + 1 }
        assertEquals("session/model.set", requestOf(transport.record[start]).first)
        transport.push(errorFrame(idOf(transport.record[start]), "READ_ONLY_SESSION", "session is closed"))
        // g-flake-ui-timing · 同一族的观测窗口：`writeFailure` 在 catch 里赋值、
        // `writePending = false` 在 finally 里。等两者同帧成立（= 写流程已终态）再断，
        // 下面的 assertFalse(writePending) 一字未动。
        waitUntil { state.writeFailure != null && !state.writePending }

        val shown = assertNotNull(state.writeFailure, "the card's error line reads this — silence was the bug")
        assertTrue(shown.isReadOnlySession)
        assertEquals(VadeErrorCodes.READ_ONLY_SESSION, shown.code)
        assertNull(state.selectedId, "the optimistic selection must be reverted")
        assertNull(state.lastNotice, "a rolled-back switch is not a successful switch")
        assertFalse(state.writePending)
    }

    // --------------------------------------------- 4 · the default scope on the real path

    @Test
    fun forDefaultWithTheRealAdapterPullsAndRefusesTheWriteWithoutAFrame() = harness { client, transport, scope ->
        val state = ModelState.forDefault(
            actions = ModelActionsReal(client, scope),
            scope = scope,
            autoRefresh = false,
        )

        val start = transport.record.size
        val pull = async(Dispatchers.Default) { state.refresh() }
        waitUntil { transport.record.size >= start + 1 }
        // The default scope pulls model/list only (no session override to chase).
        transport.push(responseFrame(idOf(transport.record[start]), twoModels))
        pull.await()

        assertEquals(listOf("claude-sonnet", "local-qwen"), state.models.orEmpty().map { it.id })
        assertEquals("claude-sonnet", state.selectedId, "no selection yet ⇒ adopt the pulled default")
        assertEquals("claude-sonnet", state.defaultModelId)
        // Contract 1.5.0 has no global-default write: the real path says so, the seam never does.
        val blocked = assertNotNull(state.writeBlockedReason, "the default scope is read-only on a real core")
        assertTrue(blocked.contains("没有全局默认模型的写方法"), "must name the missing method: $blocked")
        assertTrue(blocked.contains("UNSUPPORTED"), "must carry the contract's own word: $blocked")
        assertFalse(state.writesEnabled)

        val before = transport.record.size
        assertFalse(state.select("local-qwen"), "a refused default write reports false, it does not throw")
        assertEquals(before, transport.record.size, "no frame may be sent for a method that does not exist")
        val refused = assertNotNull(state.writeFailure, "the refusal must be visible, not silent")
        assertEquals(VadeErrorCodes.UNSUPPORTED, refused.code)
        assertEquals("claude-sonnet", state.selectedId, "the refused selection is not adopted")
    }

    // ------------------------------------------------- 5 · the seam path is byte-identical

    @Test
    fun seamAdapterKeepsRefreshAsANoOpAndWritesSynchronously() = runBlocking {
        val actions = InMemoryModelActions()
        val state = ModelState.forSession(actions, "s-1")

        assertTrue(state.loadAttempted, "a seam has nothing to wait for — it is never 「正在加载」")
        assertNull(state.loadFailure)
        state.refresh() // must return without touching anything
        assertNull(state.loadFailure, "refresh() cannot fail on the seam path")
        assertEquals(actions.models, state.models, "the seam's list is the truth; refresh must not mirror nothing")
        assertIs<ModelPickerContent.Rows>(state.content())

        assertTrue(state.select("local-qwen"), "the seam write is synchronous and cannot fail")
        assertEquals("local-qwen", actions.sessionOverride("s-1"), "the write landed in the seam immediately")
        assertEquals("local-qwen", state.selectedId)
        assertFalse(state.writePending)
        assertNull(state.writeFailure, "the seam has no failure branch — nothing invented")
        assertNull(state.writeBlockedReason, "session scope on the seam stays writable")
    }

    @Test
    fun seamWithoutACatalogStillRendersNoModelsNeverLoading() = runBlocking {
        val state = ModelState.forDefault(InMemoryModelActions(models = null))

        val content = state.content()
        assertIs<ModelPickerContent.NoModels>(
            content,
            "a fixture that declares no surface is the empty state — the pre-change behaviour",
        )
        assertEquals("无可用模型", content.title)
        state.refresh()
        assertIs<ModelPickerContent.NoModels>(state.content(), "refresh() on the seam changes nothing")
        assertNull(state.loadFailure)
    }

    @Test
    fun seamDefaultScopeStaysWritableAndUsesTheSeamWrite() = runBlocking {
        val actions = InMemoryModelActions()
        val state = ModelState.forDefault(actions)

        assertNull(state.writeBlockedReason, "the 1.5.0 read-only refusal belongs to the REAL path only")
        assertTrue(state.select("local-qwen"))
        assertEquals("local-qwen", actions.defaultModelId)
        assertEquals("local-qwen", state.selectedId)
        assertNull(state.writeFailure)
    }
}
