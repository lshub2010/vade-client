package vade.ui.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.waitUntil
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ui/modules` 的**能力缺失降级**（统一判定 `vade.ui.protocol.isMethodMissing` 的第一处
 * 消费方）：core 未注册 `ui/modules` 时，[ModulesLoader] 的状态是「该 core 不支持模块
 * 页面」——**不是故障**；注册表与成功快照一个字节不动，**集合保持 `null`**（绝不
 * fabricate 空列表）。
 *
 * 对照组（同一文件钉住，防止把真故障也降级了）：
 *  - `NOT_FOUND` 但 message 不含 `unknown method`（如 `blob not found`）→ 普通故障；
 *  - TIMEOUT / UNAVAILABLE → 普通故障；
 *  - `-32601`（老实现 / 标准码）→ 与本仓形态同判「不支持」。
 */
class ModuleCatalogCapabilityTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /** 脚本源：第 N 次 `fetch()` 执行第 N 步（可以是**抛错**——错误也是答案的一种）。 */
    private class ScriptedSource(private val steps: List<suspend () -> JsonObject>) : ModuleCatalogSource {
        private var index = 0
        override suspend fun fetch(): JsonObject {
            val step = steps[minOf(index, steps.size - 1)]
            index += 1
            return step()
        }
    }

    private fun newLoader(vararg steps: suspend () -> JsonObject): ModulesLoader =
        ModulesLoader(ScriptedSource(steps.toList()), scope)

    private suspend fun loaderUnsupported(loader: ModulesLoader): ModuleLoadError {
        waitUntil { loader.state !is ModuleCatalogState.Loading }
        val failed = assertIs<ModuleCatalogState.Failed>(loader.state)
        return failed.error
    }

    // ------------------------------------------------------- 降级（两形态）

    @Test
    fun shippedCoreShapeDegradesToUnsupportedNotFault() = runBlocking {
        val loader = newLoader(
            { throw VadeException(VadeErrorCodes.NOT_FOUND, "unknown method 'ui/modules'") },
        )
        loader.load()
        val error = loaderUnsupported(loader)

        assertTrue(error.capabilityMissing, "统一判定命中 → 能力缺失，不是故障")
        assertTrue(
            error.label.contains("该 core 不支持模块页面"),
            "UI 文案必须说「不支持」而不是故障分类：${error.label}",
        )
        assertEquals("core 升级后重试", error.retryLabel, "重试按钮必须诚实：core 升级前重试无效")
        assertTrue(error.hint.contains("能力缺失"), "hint 必须解释「为什么不是网络/服务故障」")

        // 「集合保持 null」：没有成功快照，更不许 Ready(emptyList) 兜底。
        assertNull(loader.declarations, "不支持 = 没有清单（null），绝不是空列表")
        assertFalse(loader.state is ModuleCatalogState.Ready, "绝不允许用 Ready(emptyList) 冒充结果")
    }

    @Test
    fun jsonRpc32601ShapeAlsoDegrades() = runBlocking {
        val loader = newLoader(
            { throw VadeException("-32601", "method not found: ui/modules") },
        )
        loader.load()
        val error = loaderUnsupported(loader)
        assertTrue(error.capabilityMissing, "-32601（标准码）同样判「不支持」")
        assertNull(loader.declarations)
    }

    @Test
    fun degradationIsStickyAndRegistryStaysUntouched() = runBlocking {
        val loader = newLoader(
            { throw VadeException(VadeErrorCodes.NOT_FOUND, "unknown method 'ui/modules'") },
            { throw VadeException(VadeErrorCodes.NOT_FOUND, "unknown method 'ui/modules'") },
        )
        loader.load()
        loaderUnsupported(loader)
        assertEquals(null, loader.declarations)
        // 重试（core 升级前）再拉一次，结论不变 —— 仍是「不支持」，不是新故障。
        loader.retry()
        val again = loaderUnsupported(loader)
        assertTrue(again.capabilityMissing)
        assertNull(loader.declarations, "重试成功与否都不该凭空生出一份清单")
    }

    // ------------------------------------------------------- 对照组：真故障不降级

    @Test
    fun notFoundWithOtherMessageIsAFaultNotUnsupported() = runBlocking {
        val loader = newLoader(
            { throw VadeException(VadeErrorCodes.NOT_FOUND, "blob not found") },
        )
        loader.load()
        val error = loaderUnsupported(loader)
        assertFalse(
            error.capabilityMissing,
            "NOT_FOUND + 「blob not found」是资源缺失（真故障），不得降级成不支持",
        )
        assertFalse(error.label.contains("不支持模块页面"), "故障卡的文案不是「不支持」：${error.label}")
        assertTrue(error.label.contains(VadeErrorCodes.NOT_FOUND), "故障卡保留 C-04 分类与 code：${error.label}")
    }

    @Test
    fun timeoutAndUnavailableAreFaultsNotUnsupported() = runBlocking {
        for (code in listOf(VadeErrorCodes.TIMEOUT, VadeErrorCodes.UNAVAILABLE)) {
            val loader = newLoader(
                { throw VadeException(code, "transport blew up") },
            )
            loader.load()
            val error = loaderUnsupported(loader)
            assertFalse(error.capabilityMissing, "$code 是真故障，不是「不支持」")
            assertNull(loader.declarations)
        }
    }
}
