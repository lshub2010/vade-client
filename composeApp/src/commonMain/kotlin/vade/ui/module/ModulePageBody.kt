package vade.ui.module

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import vade.ui.protocol.VadeException
import kotlin.concurrent.Volatile

/**
 * `ui/page.get` 的**客户端驱动器**：把「点开一页」变成「一帧 + 三态」。
 *
 * ## 它补的是哪条断链
 *
 * [ModulePageSource] 是同步值语义（`loadPage` 只读缓存），而 `ui/page.get` 必须挂起。
 * 在此之前挂起入口 `RealModulePageSource.fetchPage(...)` 在 `commonMain` 里**零调用**
 * （`shots/g-module-page/RESULT.md` 缺口 2 的实测），于是「入口出现了」也不等于
 * 「页面上有字」：宿主拿到 null，画「页面不可用」。本类就是那个调用点。
 *
 * ## 纪律（与 `data/ModulesLoader` 同一套，不另立一套）
 *
 *  - **三态**：`Loading → Ready` 或 `Loading → Failed`，没有第四种；`Loading` 也覆盖
 *    「首次请求之前」——那时我们还不知道能不能取到，画空态就是替 core 编结论。
 *  - **一页一帧**：每次 [load] 恰好发一帧（不由 `loadPage` 每帧重读来触发），
 *    `LaunchedEffect(key, attempt)` 是本类唯一的上游节奏来源。
 *  - **generation 守卫**：每次 [load] 让代数 +1 并捕获；晚到的回包（或失败）在写状态前
 *    比对代数，不等就丢弃。于是「先点 A 页（慢）、再点 B 页（快）」时 A 的迟到回包
 *    **不可能**盖掉 B 的画面——这是「连续点两页不串」的那道闸。
 *  - **失败可重试**：[load] 再调一次就是重发（`retry` 不是另一条路，就是同一个动作）。
 *  - **dispose 之后一切写入停止**：宿主离开组合（页面关掉）后，迟到回包不再改 UI 状态。
 *
 * ## 状态带 key
 *
 * 每个状态都带 [ModulePageBodyState.key]：宿主据此丢弃「属于上一页的状态」。没有这个
 * 字段，切页后的**第一帧**会用上一页的 `Ready` 去配新的面包屑——串页只在这一帧里发生，
 * 肉眼几乎看不见，但它是真的。
 *
 * 线程：写只发生在 [load] 的协程与其调用线程（组合线程），读来自组合；[generation] /
 * [disposed] 用 `kotlin.concurrent.Volatile`（commonMain 可移植）。
 */
internal class ModulePageBodyLoader(
    private val fetch: ModulePageBodyFetcher,
    private val scope: CoroutineScope,
) {

    /** 当前状态；`null` = 还没 open 过任何页面（宿主的第一帧就走这里）。 */
    private var stateRef: ModulePageBodyState? by mutableStateOf(null)

    /** 在组合里读它就是订阅。 */
    val state: ModulePageBodyState? get() = stateRef

    /** 尝试代数：只有最新一代的回包能写状态。 */
    @Volatile
    private var generation = 0L

    @Volatile
    private var disposed = false

    /**
     * 打开（或重试）[key] 这一页：同步进 `Loading`，然后发**一帧**。
     *
     * [declaredPageIds] 原样交给 [ModulePageBodyFetcher]（`navigate: module:<page_id>` 的
     * 目标校验要用；缺省 = 未声明 = 拒渲，fail-closed）。
     */
    fun load(key: ModulePageKey, declaredPageIds: Set<String>) {
        if (disposed) return
        generation += 1
        val gen = generation
        // 同步进 Loading：切页后的第一帧不能还挂着上一页的 Ready（串页就发生在这里）。
        stateRef = ModulePageBodyState.Loading(key)
        scope.launch {
            val next: ModulePageBodyState? = try {
                val schema = fetch.fetchBody(key.moduleId, key.instanceId, key.pageId, declaredPageIds)
                if (disposed || gen != generation) null else ModulePageBodyState.Ready(key, schema)
            } catch (e: CancellationException) {
                // Same split as ModulePageStateLoader: a timeout-shaped cancellation is a real
                // (retryable) failure, our own scope dying is a real cancel that writes nothing.
                currentCoroutineContext().ensureActive()
                ModulePageBodyState.Failed(key, modulePageBodyFailureOf(e))
            } catch (t: Throwable) {
                if (disposed || gen != generation) null else ModulePageBodyState.Failed(key, modulePageBodyFailureOf(t))
            }
            if (disposed || gen != generation || next == null) return@launch
            stateRef = next
        }
    }

    /** 停止所有后续写入；幂等。 */
    fun dispose() {
        disposed = true
    }
}

/** 一次页面体的目标身份（= 宿主面包屑栈顶端那一页）。 */
data class ModulePageKey(
    val moduleId: String,
    val instanceId: String,
    val pageId: String,
)

/**
 * 页面体的三态；每个状态都带它属于哪一页（见 [ModulePageBodyLoader] 的「状态带 key」）。
 */
sealed interface ModulePageBodyState {
    val key: ModulePageKey

    /** 一帧 `ui/page.get` 在飞（也含首次请求之前：还没有结果，不是「取不到」）。 */
    data class Loading(override val key: ModulePageKey) : ModulePageBodyState

    /** 成功：schema 原文（渲染期仍由 [ModulePageParser] fail-closed 复核）。 */
    data class Ready(override val key: ModulePageKey, val schema: JsonObject) : ModulePageBodyState

    /** 失败：C-04 名 + 传输层原话。**不** laundered 成空树。 */
    data class Failed(
        override val key: ModulePageKey,
        val failure: ModulePageBodyFailure,
    ) : ModulePageBodyState
}

/**
 * 一次失败的 `ui/page.get`。刻意**不带** `data.VadeErrorCategory`：`ui/module` 不依赖
 * `ui/data`（依赖方向相反，见 [ModuleRegistryView]），而页面体失败的处置只有
 * 「重试 / 返回」两种，不需要那张 C-04 分类表。code 原样保留，出问题时能对得上 core。
 */
data class ModulePageBodyFailure(
    /** 走协议窄口来的 C-04 名；客户端侧失败（如解析拒绝前的参数校验）也可能是它。 */
    val code: String?,
    /** 传输层原话。 */
    val detail: String,
) {
    /** 一行可读：分类名（若有）+ 原话。 */
    val label: String
        get() = if (code.isNullOrBlank()) detail else "$code：$detail"
}

/** 失败 → UI 可读的一行（不吞 code，不编分类）。 */
internal fun modulePageBodyFailureOf(t: Throwable): ModulePageBodyFailure = ModulePageBodyFailure(
    code = (t as? VadeException)?.code,
    detail = t.message ?: t::class.simpleName ?: "unknown error",
)
