package vade.ui.module

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.VadeJson
import vade.ui.protocol.waitUntil
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [ModulePageBodyLoader] —— 页面体的三态、一页一帧、generation 守卫、dispose。
 *
 * 这一层用**纯 JVM** 钉语义（不组合 Compose），所以「先点 A（慢）、再点 B（快）」这种
 * 只发生在几帧之间的竞态可以被**确定地**复现：A 的回包由测试自己放行，放行时 B 早就
 * Ready 了——如果状态被 A 盖掉，断言立刻红。
 *
 * 装置是一个照接口的 [ModulePageBodyFetcher]：每次调用可取一条答案、可挂在一个
 * `CompletableDeferred` 上（「在飞」因此可观测，不靠 sleep 猜）。没有 socket。
 */
class ModulePageBodyLoaderTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    // ------------------------------------------------------------------ 装置

    private fun schema(text: String): JsonObject = VadeJson.default.parseToJsonElement(
        """{"type":"column","children":[{"type":"text","props":{"text":"$text"}}]}""",
    ) as JsonObject

    private fun key(pageId: String, moduleId: String = "com.example.echo") =
        ModulePageKey(moduleId = moduleId, instanceId = "main", pageId = pageId)

    /** 脚本文本的可数 fetcher：第 N 次调用用第 N 条答案 / 第 N 个闸门。 */
    private class ScriptedFetcher : ModulePageBodyFetcher {
        private val lock = Any()
        private val gates = mutableListOf<CompletableDeferred<Unit>?>()
        private val answers = mutableListOf<(ModulePageKey) -> JsonObject>()
        val calls = AtomicInteger()
        val requests: MutableList<ModulePageKey> = Collections.synchronizedList(mutableListOf())

        fun step(answer: (ModulePageKey) -> JsonObject, gate: CompletableDeferred<Unit>? = null) {
            synchronized(lock) {
                answers += answer
                gates += gate
            }
        }

        override suspend fun fetchBody(
            moduleId: String,
            instanceId: String,
            pageId: String,
            declaredPageIds: Set<String>,
        ): JsonObject {
            val index = calls.getAndIncrement()
            requests += ModulePageKey(moduleId, instanceId, pageId)
            synchronized(lock) { gates.getOrNull(index) }?.await()
            val answer = synchronized(lock) { answers.getOrNull(index) ?: answers.last() }
            return answer(ModulePageKey(moduleId, instanceId, pageId))
        }
    }

    // ------------------------------------------------------------------ 一条打开 = 一帧

    @Test
    fun oneOpenSendsOneFrameAndEndsReady() = runBlocking {
        val fetcher = ScriptedFetcher()
        fetcher.step({ schema("页 A") })
        val loader = ModulePageBodyLoader(fetcher, scope)

        assertEquals(null, loader.state, "还没 open 过：没有状态（宿主此时不该画任何东西）")
        loader.load(key("echo"), setOf("echo"))

        // 同步进 Loading（切页后的第一帧不能挂着上一页的内容）。
        assertEquals(ModulePageBodyState.Loading(key("echo")), loader.state)
        waitUntil { loader.state is ModulePageBodyState.Ready }

        val ready = assertIs<ModulePageBodyState.Ready>(loader.state)
        assertEquals(key("echo"), ready.key)
        assertEquals(schema("页 A"), ready.schema, "交给宿主的就是 core 的原样 schema")
        assertEquals(1, fetcher.calls.get(), "打开一页 = 恰好一帧 ui/page.get")
        assertEquals(listOf(key("echo")), fetcher.requests)

        loader.dispose()
    }

    // ------------------------------------------------------------------ 失败 → 重试真的重发

    @Test
    fun aFailedFetchIsObservableAndLoadingAgainResendsTheFrame() = runBlocking {
        val fetcher = ScriptedFetcher()
        fetcher.step({ throw VadeException(VadeErrorCodes.NOT_FOUND, "page echo not found") })
        fetcher.step({ schema("第二次") })
        val loader = ModulePageBodyLoader(fetcher, scope)

        loader.load(key("echo"), setOf("echo"))
        waitUntil { loader.state is ModulePageBodyState.Failed }
        val failed = assertIs<ModulePageBodyState.Failed>(loader.state)
        assertEquals(VadeErrorCodes.NOT_FOUND, failed.failure.code, "C-04 名原样带出来，不吞")
        assertTrue(failed.failure.label.contains(VadeErrorCodes.NOT_FOUND), "一行可读：${failed.failure.label}")

        // 重试 = 同一个动作再调一次（宿主把这颗按钮接到 load 上）。
        loader.load(key("echo"), setOf("echo"))
        assertEquals(ModulePageBodyState.Loading(key("echo")), loader.state, "重试先回到 Loading，不装已经好了")
        waitUntil { loader.state is ModulePageBodyState.Ready }
        assertEquals(2, fetcher.calls.get(), "重试必须真重发一帧")
        assertEquals(schema("第二次"), (loader.state as ModulePageBodyState.Ready).schema)

        loader.dispose()
    }

    // ------------------------------------------------------------------ 两页不串（generation 守卫）

    @Test
    fun aLateReplyForThePreviousPageCannotOverwriteTheNextOne() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val fetcher = ScriptedFetcher()
        fetcher.step({ schema("页 A（慢）") }, slow)
        fetcher.step({ schema("页 B（快）") })
        val loader = ModulePageBodyLoader(fetcher, scope)

        loader.load(key("a"), setOf("a"))
        waitUntil { fetcher.calls.get() == 1 }

        // 用户立刻点了另一页（A 的回包还在路上）。
        loader.load(key("b"), setOf("b"))
        assertEquals(
            ModulePageBodyState.Loading(key("b")),
            loader.state,
            "换页的第一帧必须是新页的 Loading —— 带着上一页的 Ready 就是串页",
        )
        waitUntil { loader.state is ModulePageBodyState.Ready }
        assertEquals(key("b"), (loader.state as ModulePageBodyState.Ready).key)
        assertEquals(schema("页 B（快）"), (loader.state as ModulePageBodyState.Ready).schema)

        // A 的迟到回包现在才到：它必须被丢弃，而不是盖掉 B。
        slow.complete(Unit)
        Thread.sleep(150)
        val after = assertIs<ModulePageBodyState.Ready>(loader.state, "A 的迟到回包不得改写状态")
        assertEquals(key("b"), after.key)
        assertEquals(schema("页 B（快）"), after.schema)
        assertEquals(2, fetcher.calls.get(), "两页各一帧，没有第三次请求")

        loader.dispose()
    }

    @Test
    fun aLateFailureForThePreviousPageIsDroppedToo() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val fetcher = ScriptedFetcher()
        fetcher.step({ throw VadeException(VadeErrorCodes.UNAVAILABLE, "transport died") }, slow)
        fetcher.step({ schema("页 B") })
        val loader = ModulePageBodyLoader(fetcher, scope)

        loader.load(key("a"), setOf("a"))
        waitUntil { fetcher.calls.get() == 1 }
        loader.load(key("b"), setOf("b"))
        waitUntil { loader.state is ModulePageBodyState.Ready }

        slow.complete(Unit) // A 的失败回包迟到
        Thread.sleep(150)
        assertIs<ModulePageBodyState.Ready>(loader.state, "旧代失败不得把新页打成 Failed")

        loader.dispose()
    }

    // ------------------------------------------------------------------ dispose

    @Test
    fun disposeDropsTheLateReplyInsteadOfWritingUiState() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val fetcher = ScriptedFetcher()
        fetcher.step({ schema("页 A") }, slow)
        val loader = ModulePageBodyLoader(fetcher, scope)

        loader.load(key("echo"), setOf("echo"))
        waitUntil { fetcher.calls.get() == 1 }
        loader.dispose()
        slow.complete(Unit)
        Thread.sleep(150)
        assertEquals(
            ModulePageBodyState.Loading(key("echo")),
            loader.state,
            "宿主侧已 dispose：迟到回包不许再写状态",
        )
    }
}
