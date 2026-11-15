package vade.ui.module

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile

/**
 * `ui/state.get` 的**客户端驱动器**（g-module-state）：把「绑定页需要状态」变成
 * 「一帧 + 三态」。纪律与 [ModulePageBodyLoader] 完全同一套，不另立一套：
 *
 *  - **三态**：`Loading → Ready` 或 `Loading → Failed`，没有第四种；`Loading` 也覆盖
 *    「请求已发、回包未到」——那时画空值 + 「正在取回」，不是「取不到」；
 *  - **一页一帧**：每次 [load] 恰好发一帧；节奏由宿主的 `LaunchedEffect` 键控制
 *    （进入页面 / 点重试 / `module.status` 失效各一代），实现方不防抖；
 *  - **generation 守卫**：每次 [load] 代数 +1 并捕获；晚到的回包在写状态前比对代数，
 *    不等就丢弃——「先开 A 页（慢）、再开 B 页（快）」时 A 的迟到回包不可能盖掉 B；
 *  - **失败不洗白**：失败带着 C-04 名与传输原话进 [ModulePageStatePhase.Failed]，
 *    宿主据此画可见的降级横幅 + 重试，绝不默默把绑定字段画成空；
 *  - **dispose 之后一切写入停止**（宿主离开组合）。
 *
 * 线程：写只发生在 [load] 的协程与其调用线程，读来自组合；`@Volatile` 用
 * `kotlin.concurrent.Volatile`（commonMain 可移植）。
 */
internal class ModulePageStateLoader(
    private val fetch: ModulePageStateFetcher,
    private val scope: CoroutineScope,
) {

    /** 当前 phase；`null` = 还没 load 过（宿主的第一帧走这里）。 */
    private var phaseRef: ModulePageStatePhase? by mutableStateOf(null)

    /** 在组合里读它就是订阅。 */
    val phase: ModulePageStatePhase? get() = phaseRef

    /** 尝试代数：只有最新一代的回包能写状态。 */
    @Volatile
    private var generation = 0L

    @Volatile
    private var disposed = false

    /** 发一帧 `ui/state.get`：同步进 `Loading`，然后挂起等回包。 */
    fun load(key: ModulePageKey) {
        if (disposed) return
        generation += 1
        val gen = generation
        phaseRef = ModulePageStatePhase.Loading(key)
        scope.launch {
            val next: ModulePageStatePhase? = try {
                val state = fetch.fetchState(key.moduleId, key.instanceId, key.pageId)
                if (disposed || gen != generation) null else ModulePageStatePhase.Ready(key, state)
            } catch (e: CancellationException) {
                null // 协程被取消（宿主离开组合）：不写状态，绝不编一个失败
            } catch (t: Throwable) {
                if (disposed || gen != generation) null else ModulePageStatePhase.Failed(key, modulePageBodyFailureOf(t))
            }
            if (disposed || gen != generation || next == null) return@launch
            phaseRef = next
        }
    }

    /** 停止所有后续写入；幂等。 */
    fun dispose() {
        disposed = true
    }
}

/**
 * 一次 `ui/state.get` 的三态；每个状态都带它属于哪一页（与 [ModulePageBodyState] 同一防串页
 * 形状）。复用 [ModulePageBodyFailure]：`ui/state.get` 的失败处置与页面体完全一致
 * （重试 / 返回），不需要另一张失败模型。
 */
sealed interface ModulePageStatePhase {
    val key: ModulePageKey

    /** 一帧 `ui/state.get` 在飞（绑定值此时如实为空，横幅说明「正在取回」）。 */
    data class Loading(override val key: ModulePageKey) : ModulePageStatePhase

    /**
     * 成功：`result.state` 原文。**空对象也是合法状态**（模块没有状态 ≠ 取不到），
     * 照实上屏——绑定值缺失按 §5.4 显示空值，不画任何「状态不可用」。
     */
    data class Ready(override val key: ModulePageKey, val state: JsonObject) : ModulePageStatePhase

    /** 失败：C-04 名 + 传输层原话（NOT_FOUND / UNSUPPORTED / UNAVAILABLE / TIMEOUT…）。 */
    data class Failed(override val key: ModulePageKey, val failure: ModulePageBodyFailure) : ModulePageStatePhase
}
