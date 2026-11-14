package vade.ui.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.protocol.waitUntil
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ModulesLoader] —— 三态、revision 去重、在飞不重发、generation 守卫、`module.status` 失效边。
 *
 * 装置是一个照稿件口的 [ModuleCatalogSource]：每次 `fetch()` 取一条脚本文本，可以挂在
 * `CompletableDeferred` 上（这样「在飞」是可观测的，不是靠 sleep 猜）。没有 socket、没有
 * 真 core —— 真 core 那条路径本棒走不了（契约里没有 `ui/modules`，见 `ModuleCatalog.kt`
 * 的阻塞一节），所以这里断言的每一条都是**这一侧的**语义。
 */
class ModuleCatalogLoaderTest {

    // ------------------------------------------------------------------ 装置

    /** 一个模块的 wire 形状（`ui/modules.modules[]` 的一条）。 */
    private fun moduleJson(id: String, pageId: String? = null): String {
        val pages = if (pageId == null) {
            ""
        } else {
            ""","pages":[{"id":"$pageId","title":"页面 $pageId","icon":"list","version":1}]"""
        }
        return """{"module_id":"$id","instance_id":"i-$id","display_name":"模块 $id",""" +
            """"version":"1.0.0","online":true$pages}"""
    }

    private fun reply(revision: Long, vararg moduleIds: String): JsonObject =
        VadeJson.default.parseToJsonElement(
            """{"revision":$revision,"modules":[${moduleIds.joinToString(",") { moduleJson(it) }}]}""",
        ) as JsonObject

    /** 脚本文本源：第 N 次 `fetch()` 用第 N 条（多出来的重复最后一条）。 */
    private class ScriptedCatalogSource : ModuleCatalogSource {
        private val lock = Any()
        private val answers = mutableListOf<() -> JsonObject>()
        private val gates = mutableListOf<CompletableDeferred<Unit>?>()
        val calls = AtomicInteger()

        fun step(answer: () -> JsonObject, gate: CompletableDeferred<Unit>? = null) {
            synchronized(lock) {
                answers += answer
                gates += gate
            }
        }

        override suspend fun fetch(): JsonObject {
            val index = calls.getAndIncrement()
            val gate = synchronized(lock) { gates.getOrNull(index) }
            gate?.await()
            val answer = synchronized(lock) { answers.getOrNull(index) ?: answers.last() }
            return answer()
        }
    }

    private fun newScope() = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun declarationsOf(loader: ModulesLoader) =
        (loader.state as ModuleCatalogState.Ready).declarations.map { it.moduleId }

    // ------------------------------------------------------------------ 三态

    @Test
    fun firstPullIsLoadingThenReadyWithTheWireContent() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        val gate = CompletableDeferred<Unit>()
        source.step({ reply(1, "alpha") }, gate)
        val loader = ModulesLoader(source, scope)

        // 首次请求之前没有结果 —— 这是 Loading，不是「没有模块」。
        assertEquals(ModuleCatalogState.Loading, loader.state)
        loader.load()
        waitUntil { source.calls.get() == 1 }
        assertEquals(ModuleCatalogState.Loading, loader.state, "回包之前一直是 Loading")

        gate.complete(Unit)
        waitUntil { loader.state is ModuleCatalogState.Ready }
        val ready = assertIs<ModuleCatalogState.Ready>(loader.state)
        assertEquals(1L, ready.revision)
        assertFalse(ready.isEmpty)
        assertEquals(1, ready.moduleCount)
        assertEquals(listOf("alpha"), ready.declarations.map { it.moduleId })
        assertEquals("模块 alpha", ready.declarations.single().moduleTitle)
        loader.dispose()
        scope.cancel()
    }

    @Test
    fun failedPullIsObservableWithItsCategoryAndCode() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        source.step({ throw VadeException(VadeErrorCodes.TIMEOUT, "no reply within 1000ms for ui/modules") })
        val loader = ModulesLoader(source, scope)

        loader.load()
        waitUntil { loader.state is ModuleCatalogState.Failed }
        val failed = assertIs<ModuleCatalogState.Failed>(loader.state)
        assertEquals(VadeErrorCategory.Retryable, failed.error.category)
        assertEquals(VadeErrorCodes.TIMEOUT, failed.error.code)
        assertTrue(failed.error.label.contains("可重试"), "横幅必须以分类打头：${failed.error.label}")
        assertTrue(failed.error.label.contains(VadeErrorCodes.TIMEOUT))
        assertEquals("重试", failed.error.retryLabel)
        assertTrue(moduleCatalogCanRetry(loader.state))
        loader.dispose()
        scope.cancel()
    }

    @Test
    fun emptyModulesIsReadyAndNotEmptyAFailure() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        source.step({ reply(9) })
        val loader = ModulesLoader(source, scope)

        loader.load()
        waitUntil { loader.state is ModuleCatalogState.Ready }
        val ready = assertIs<ModuleCatalogState.Ready>(loader.state)
        assertEquals(9L, ready.revision)
        assertTrue(ready.isEmpty, "成功返回但 modules 为空 ⇒ isEmpty，而不是 Failed")
        assertEquals(0, ready.moduleCount)
        assertEquals(0, ready.pageCount)
        assertEquals(emptyList(), ready.declarations)
        assertFalse(moduleCatalogCanRetry(loader.state), "空清单没有可重试的失败")
        loader.dispose()
        scope.cancel()
    }

    @Test
    fun retryReissuesTheFetchAfterAFailure() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        source.step({ throw VadeException(VadeErrorCodes.OVERLOADED, "draining") })
        source.step({ reply(3, "alpha") })
        val loader = ModulesLoader(source, scope)

        loader.load()
        waitUntil { loader.state is ModuleCatalogState.Failed }
        loader.retry()
        waitUntil { loader.state is ModuleCatalogState.Ready }
        assertEquals(2, source.calls.get(), "retry 必须真的再发一次，不是只改状态")
        assertEquals(listOf("alpha"), declarationsOf(loader))
        assertEquals(3L, (loader.state as ModuleCatalogState.Ready).revision)
        loader.dispose()
        scope.cancel()
    }

    // ------------------------------------------------------------------ revision 去重

    @Test
    fun sameRevisionDoesNotRebuildTheRegistry() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        source.step({ reply(1, "alpha") })
        // 相同的 revision、不同的模块内容：core 违约，但正是「revision 说了算」要挡住的情形。
        source.step({ reply(1, "beta") })
        val loader = ModulesLoader(source, scope)

        loader.load()
        waitUntil { loader.state is ModuleCatalogState.Ready }
        assertEquals(listOf("alpha"), declarationsOf(loader))

        loader.invalidate()
        waitUntil { source.calls.get() == 2 }
        // 相同 revision 的回包**结构相等**，`mutableStateOf` 的 structural-equality 策略会
        // 直接吞掉这次写入（连重组都不会发生）——所以这里不能用「状态对象换了新实例」当信号，
        // 只能等补拉跑完再看注册表内容。
        delay(200)

        assertEquals(
            listOf("alpha"),
            declarationsOf(loader),
            "revision 没变 ⇒ 注册表一个字节都不重建（第二次回包的 beta 不许出现）",
        )
        assertEquals(1L, (loader.state as ModuleCatalogState.Ready).revision)
        assertEquals(2, source.calls.get(), "失效只补一次")
        loader.dispose()
        scope.cancel()
    }

    @Test
    fun revisionChangeRebuildsTheRegistry() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        source.step({ reply(1, "alpha") })
        source.step({ reply(2, "beta") })
        val loader = ModulesLoader(source, scope)

        loader.load()
        waitUntil { loader.state is ModuleCatalogState.Ready }
        val first = loader.state
        assertEquals(listOf("alpha"), declarationsOf(loader))

        loader.invalidate()
        waitUntil { loader.state !== first && loader.state is ModuleCatalogState.Ready }

        val ready = assertIs<ModuleCatalogState.Ready>(loader.state)
        assertEquals(2L, ready.revision)
        assertEquals(listOf("beta"), ready.declarations.map { it.moduleId }, "revision 变了 ⇒ 整表替换")
        loader.dispose()
        scope.cancel()
    }

    // ------------------------------------------------------------------ 在飞不重发

    @Test
    fun invalidationsDuringAFlightCollapseIntoOneFollowUpPull() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        val gate = CompletableDeferred<Unit>()
        source.step({ reply(1, "alpha") }, gate)
        source.step({ reply(2, "beta") })
        val loader = ModulesLoader(source, scope)

        loader.load()
        waitUntil { source.calls.get() == 1 }
        // 健康抖动：一次拉取在飞时来 5 次失效。
        repeat(5) { loader.invalidate() }
        assertEquals(1, source.calls.get(), "在飞时不许并发发帧")

        gate.complete(Unit)
        waitUntil { source.calls.get() == 2 }
        waitUntil { (loader.state as? ModuleCatalogState.Ready)?.revision == 2L }
        delay(150)
        assertEquals(2, source.calls.get(), "5 次失效只补一次，不是 5 次")
        assertEquals(listOf("beta"), declarationsOf(loader))
        loader.dispose()
        scope.cancel()
    }

    // ------------------------------------------------------------------ 失效边

    @Test
    fun moduleStatusTriggersExactlyOneRepullAndOtherEventsAreIgnored() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        source.step({ reply(1, "alpha") })
        source.step({ reply(2, "beta") })
        val loader = ModulesLoader(source, scope)

        // events.json 冻结的模块 topic 只有 module.status —— 它就是失效边。
        loader.onEvent(
            ModuleStatusChangedEvent(seq = 1, moduleId = "alpha", instanceId = "i-alpha", online = false, version = "1.0.0"),
        )
        waitUntil { source.calls.get() == 1 }
        waitUntil { loader.state is ModuleCatalogState.Ready }

        // 其它事件与清单无关：不许触发拉取。
        loader.onEvent(TurnDeltaEvent(seq = 2, turnId = "t-1", text = "x", partIndex = 0, kind = "text"))
        loader.onEvent(TurnStartedEvent(seq = 3, turnId = "t-1", modelId = "m"))
        delay(150)
        assertEquals(1, source.calls.get(), "非模块事件不许引发 ui/modules 拉取")

        loader.onEvent(
            ModuleStatusChangedEvent(seq = 4, moduleId = "beta", instanceId = "i-beta", online = true, version = null),
        )
        waitUntil { source.calls.get() == 2 }
        waitUntil { (loader.state as? ModuleCatalogState.Ready)?.revision == 2L }
        assertEquals(listOf("beta"), declarationsOf(loader))
        loader.dispose()
        scope.cancel()
    }

    // ------------------------------------------------------------------ 守卫与生命周期

    @Test
    fun staleReplyFromAnOlderGenerationIsDropped() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        // 两代同 revision：如果旧代没被丢弃，注册表会被旧代的 alpha 占住，而 revision 去重
        // 又会让新代的 beta 被当成「没变」——最终看到 alpha 就说明守卫失效。
        source.step({ reply(1, "alpha") }, firstGate)
        source.step({ reply(1, "beta") }, secondGate)
        val loader = ModulesLoader(source, scope)

        loader.load()
        waitUntil { source.calls.get() == 1 }
        loader.retry() // 第二代在飞
        waitUntil { source.calls.get() == 2 }

        firstGate.complete(Unit) // 旧代先回
        delay(150)
        assertEquals(ModuleCatalogState.Loading, loader.state, "旧代的回包必须被静默丢弃")

        secondGate.complete(Unit)
        waitUntil { loader.state is ModuleCatalogState.Ready }
        assertEquals(listOf("beta"), declarationsOf(loader), "只有最新一代的结果能落进注册表")
        loader.dispose()
        scope.cancel()
    }

    @Test
    fun disposeStopsEveryFurtherWrite() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        val gate = CompletableDeferred<Unit>()
        source.step({ reply(1, "alpha") }, gate)
        val loader = ModulesLoader(source, scope)

        loader.load()
        waitUntil { source.calls.get() == 1 }
        loader.dispose()
        gate.complete(Unit)
        delay(200)
        assertEquals(ModuleCatalogState.Loading, loader.state, "dispose 之后晚到的回包不许改状态")
        assertEquals(0, (loader.state as? ModuleCatalogState.Ready)?.moduleCount ?: 0)
        scope.cancel()
    }

    @Test
    fun undecodableReplyBecomesAFailedStateInsteadOfCrashing() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        source.step({ VadeJson.default.parseToJsonElement("""{"revision":1,"modules":"nope"}""") as JsonObject })
        val loader = ModulesLoader(source, scope)

        loader.load()
        waitUntil { loader.state is ModuleCatalogState.Failed }
        val failed = assertIs<ModuleCatalogState.Failed>(loader.state)
        assertTrue(failed.error.detail.contains("modules"), "错误要点名坏掉的键：${failed.error.detail}")
        assertNull(failed.error.code, "解码失败没有 C-04 code（不是传输层拒绝，别编一个）")
        loader.dispose()
        scope.cancel()
    }

    @Test
    fun registryIsOnlyReplacedAfterAFullyDecodedReply() = runBlocking {
        val scope = newScope()
        val source = ScriptedCatalogSource()
        source.step({ reply(1, "alpha") })
        // 第二份里第二个模块坏页 ⇒ 整份拒绝；注册表必须保住上一次的 alpha。
        source.step({
            VadeJson.default.parseToJsonElement(
                """{"revision":2,"modules":[
                     ${moduleJson("beta")},
                     {"module_id":"bad","instance_id":"i","display_name":"Bad","online":true,
                      "pages":[{"title":"没有 id","version":1}]}
                   ]}""",
            ) as JsonObject
        })
        val loader = ModulesLoader(source, scope)

        loader.load()
        waitUntil { loader.state is ModuleCatalogState.Ready }
        loader.invalidate()
        waitUntil { loader.state is ModuleCatalogState.Failed }

        assertEquals(
            listOf("alpha"),
            loader.registry.declarations().map { it.moduleId },
            "解码失败不许把注册表改成半份（先解码、后整体替换）",
        )
        loader.dispose()
        scope.cancel()
    }
}
