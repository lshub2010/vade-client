package vade.ui.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import vade.ui.module.ModuleDeclaration
import vade.ui.protocol.PayloadDecodeException
import vade.ui.protocol.boolStrict
import vade.ui.protocol.lngStrict
import vade.ui.protocol.mustArr
import vade.ui.protocol.mustStr
import vade.ui.protocol.strStrict
import kotlin.concurrent.Volatile

/**
 * 模块清单（`ui/modules`）在客户端侧的**窄口**：一次拉取的三种结果，加两个动作。
 *
 * ## 它解决的是「能力页的模块区块没有真数据」这条断链
 *
 * 现状（本棒开工时实测）：
 *  - `ModuleRegistry` 已能把 manifest 收成快照，但**没有 wire 通道**给它喂数据 ——
 *    它只吃 `module.register` 的 manifest 原文，而那是 module→core 的 RPC，UI 侧拿不到；
 *  - `capabilitiesScreen` 于是只能读 `ModulePageSources.demo()` 那棵示例树；
 *  - `AdapterProvider.modulePages()` 是页面/状态通道（`ui/page.get` / `ui/state.get`），
 *    **不是清单通道**——它连「有几个模块」都答不出来。
 *
 * 本类补齐「清单」这一跳：[state] 是**可订阅**的一份快照（Compose `mutableStateOf`），
 * 于是能力页读它一次就订阅了后续变化；`revision` 变化 → 新的 [ModuleCatalogState.Ready]
 * 实例 → 组合被无效化 → 入口列表重建。
 *
 * ## 三态（**不许塌成一张空卡**）
 *
 * | 状态 | 含义 | 页面必须显示 |
 * |---|---|---|
 * | [ModuleCatalogState.Loading] | 一次 `ui/modules` 在飞（也含首次请求之前的阶段） | 「正在拉取」+ 明确说明「还没有结果」 |
 * | [ModuleCatalogState.Failed] | 拉取失败（C-04 分类 + code + 可重试） | 分类文案 + code + 详情 + 重试入口 |
 * | [ModuleCatalogState.Ready] | 成功返回；`modules` 可能为空 | `isEmpty` = **真没模块**（成功且 0 个，不是错误）；否则模块卡片 |
 *
 * `Ready(empty)` 与 `Failed` 的区别是「core 说没有」和「我们没问到」的区别；把两者
 * 渲染成同一张「暂无模块」等于用一句谎话盖住一次失败（这是本仓反复记录的 bug 类）。
 *
 * ## 线程 / 生命周期
 *
 * 实现（[ModulesLoader]）内部持一个 `@Volatile` 快照引用，写只发生在拉取协程里，
 * 读来自任意线程（UI 组合）。[dispose] 之后任何晚到的回包都不再写状态。
 */
interface ModuleCatalog {

    /** 当前快照。**在组合里读它就是订阅**（`mutableStateOf`）。 */
    val state: ModuleCatalogState

    /**
     * **首次进入时的幂等启动**：只有本实例的**第一次**调用会发起一次 `ui/modules`，
     * 之后（含上一次拉取失败后的再次进入）什么都不做——重试是 [retry] 的事。
     *
     * 这是「谁负责发起第一次拉取」的唯一入口，调用点只有一个：能力页的
     * `LaunchedEffect(moduleCatalog)`。为什么幂等性长在 loader 上、而不是调用点的
     * `remember` 里：`remember` 会随屏幕离开组合而丢弃，用户「进来—出去—再进来」
     * 每次都会重拉一次；而 loader 与连接同生命周期（`connect()` 建、`disconnect()`
     * 释放），把「已经拉过」记在它身上，反复进出就自然不重复拉。失效仍只走
     * [invalidate]（`module.status`）。
     */
    fun ensureLoaded()

    /** 失败卡上的重试入口：重发一次 `ui/modules`（首次拉取前调用也合法 = 开始拉取）。 */
    fun retry()

    /**
     * 失效边：**「清单可能变了，再拉一次」**。
     *
     * 这是本仓唯一允许的失效信号来源 —— `events.json` 冻结的模块 topic 只有
     * `module.status`（`notes`: *Emitted from registry changes*；`session.list_changed`
     * 的 notes 明说它是「module.status precedent」：客户端**重拉**而不是维护 payload 副本）。
     * 所以 [onEvent] 收到 `module.status` 就 [invalidate]，**不新增事件 topic、不拿 payload 当清单**。
     *
     * 抖动保护有两层：① 一次拉取在飞时再来的失效只记一个「待补一次」（[ModulesLoader]），
     * 不会并发发帧；② 回包的 `revision` 没变就**不重建注册表**（[ModuleRegistry] 快照原样），
     * 于是 registry 的健康抖动不会引发 UI 重建风暴。
     */
    fun invalidate()

    /** 事件入口：目前只有 `module.status` 会让它 [invalidate]；其余类型显式忽略。 */
    fun onEvent(event: VadeEvent)
}

/**
 * 「我能交出模块清单通道」的能力接口 —— **只由真数据源实现**（`RealSessionSource` 与
 * 它的组合根 shim `AutoConnectingSessionSource`）。
 *
 * 为什么是单独的能力接口、而不是加在 `SessionSource` 或 `AdapterProvider` 上（与
 * `AdapterHost` 同一套理由）：
 *  - `SessionSource` 有多个实现与几百条测试依赖，加方法会打断实现，而清单通道是**可选能力**；
 *  - `AdapterProvider` 的方法集合被 `ScreenAdapterWiringTest` 逐名钉住（「窄接口集合不得
 *    悄悄增宽」），而且它的 `modulePages()` 已经在 `VadeAdaptersTest` 里被断言成
 *    `RealModulePageSource` —— 往里加 `moduleCatalog()` 或换返回类型都会撞既有钉子。
 *
 * `(source as? ModuleCatalogProvider)?.moduleCatalog` 的可空探测正好表达「这个源没有
 * 清单通道」；假数据源不实现它 → `null` → 能力页保持既有占位（行为不变）。
 */
interface ModuleCatalogProvider {
    /** 连接期非空（契约落地后）；未接线 / 假数据源 → `null`。 */
    val moduleCatalog: ModuleCatalog?
}

/** 模块清单的一次结果。 */
sealed interface ModuleCatalogState {
    /** 一次 `ui/modules` 在飞（也含首次请求之前：还没有任何结果，不是「没有模块」）。 */
    data object Loading : ModuleCatalogState

    /**
     * 成功返回。[revision] 是 core 的清单版本号；[declarations] 是**已解码**的声明快照，
     * 类型与 `ModulePageSource.declarations()` 逐字段一致（`ModuleDeclaration`）。
     *
     * [isEmpty] = core 明确说「当前没有模块」——这不是错误，也不是加载中。
     */
    data class Ready(
        val revision: Long,
        val declarations: List<ModuleDeclaration>,
    ) : ModuleCatalogState {
        val isEmpty: Boolean get() = declarations.isEmpty()

        /** 模块实例数（= core `modules[]` 的条数）。 */
        val moduleCount: Int get() = declarations.size

        /** 声明的页面总数（无 `ui.pages` 的模块计 0）。 */
        val pageCount: Int get() = declarations.sumOf { it.pages.size }
    }

    /** 拉取失败；[error] 带 C-04 分类、code 与重试文案。**绝不** laundered 成空的 Ready。 */
    data class Failed(val error: ModuleLoadError) : ModuleCatalogState
}

/**
 * 一次失败的模块清单拉取，按 UI 能动作的四个桶分类（复用 [VadeErrorCategory]，
 * 与 `UsageLoadError` / `SubagentLoadError` 同一表、同一措辞习惯）。
 */
data class ModuleLoadError(
    /** C-04 的 UI 桶（可重试 / 需要重连 / 需要用户操作 / 致命）——横幅以它打头。 */
    val category: VadeErrorCategory,
    /** 走协议窄口来的 C-04 名；否则 `null`。 */
    val code: String?,
    /** 传输层原话，跟在分类标签之后。 */
    val detail: String,
) {
    /** 分类文案 + code + 详情 —— 一行，永远带分类词。 */
    val label: String
        get() = if (code.isNullOrBlank()) "${category.label}：$detail" else "${category.label}（$code）：$detail"

    /** 重试按钮承诺的事。 */
    val retryLabel: String
        get() = when (category) {
            VadeErrorCategory.Retryable -> "重试"
            VadeErrorCategory.NeedsReconnect -> "重连后重试"
            VadeErrorCategory.NeedsUserAction -> "修正后重试"
            VadeErrorCategory.Fatal -> "重试（通常无效）"
        }

    /** 为什么失败、用户能做什么 —— 不是裸的堆栈。 */
    val hint: String
        get() = when (category) {
            VadeErrorCategory.Retryable ->
                "请求超时或服务繁忙；重试会重新拉取 ui/modules，已显示的模块不会残留半份。"
            VadeErrorCategory.NeedsReconnect ->
                "连接已断开或会话视图已失效；请恢复连接（重连 / 重新 attach）后再重试。"
            VadeErrorCategory.NeedsUserAction ->
                "参数或权限被拒绝；请按提示修正后再试（同一请求原样重试通常无效）。"
            VadeErrorCategory.Fatal ->
                "客户端与服务端版本或安全状态不一致；更新客户端或重新配对后才能恢复。"
        }
}

internal fun moduleLoadErrorOf(t: Throwable): ModuleLoadError = ModuleLoadError(
    category = loadErrorCategoryOf(t),
    code = loadErrorCodeOf(t),
    detail = t.message ?: t::class.simpleName ?: "unknown error",
)

/** 只有这些状态下的重试按钮才有意义（Loading 时没有失败的请求可重发）。 */
fun moduleCatalogCanRetry(state: ModuleCatalogState): Boolean = state is ModuleCatalogState.Failed

// ------------------------------------------------------------------ wire 解码

/**
 * `ui/modules` 的 `result` → [ModuleRegistry.Node] 列表（**纯函数、原子**）。
 *
 * ## 形状来源（诚实标注）
 *
 * 取自 core 已合入的实现（`ui/modules` → `{revision, modules:[{module_id, instance_id,
 * display_name, version, online, pages:[{id,title,icon,entry,version,requires,refresh_s}]}]}`），
 * **不是** `contracts/vade/schema/rpc.json` —— 本棒开工时那个文件里 `grep -c 'ui/modules'`
 * = **0**（submodule `320384a`，模块相关只有 `module.status` 事件）。所以本解码器是
 * 「按 core 实现形状严格解码」，不是「按冻结契约解码」：契约落地后必须逐字段对一遍
 * `schema/rpc.json` 再信任它，契约若改了字段名，这里会以 `PROTOCOL_MISMATCH` 明说，
 * 不会静默按旧名取值。
 *
 * ## 严格度（与 `ModuleRegistry.declare` 同一条规则，不另立一套）
 *
 * 声明部分（`display_name` + `pages`）**直接复用 `ModuleRegistry.declare` 的解码**：
 * 把 `pages` 搬到 C-03 的 `ui.pages` 位置喂进去，于是「坏页整份拒绝」「未知 icon 降级
 * 为 None」「`requires:["session"]`」「未知键忽略」四条规则只有一处实现。为此用一份
 * **临时注册表**做解码底座，成功后把不可变 `Node` 列表整体交回 —— 半份数据永远不会
 * 落到调用方的注册表里（先解码，后替换）。
 *
 * 于是：
 *  - 缺 `revision` / `modules` / `module_id` / `instance_id` / `display_name` / `online`
 *    → [PayloadDecodeException]（整份拒绝，不是跳过一个模块）；
 *  - `pages` 缺失或为 null → 该模块**没有入口**（FR-8.79），模块本身照样在清单里；
 *  - `entry` / `refresh_s` 等本层用不到的键一律忽略（前向兼容）。
 */
internal object ModuleCatalogWire {

    /** `result.revision`（必填数字；字符串 `"3"` 是错的形状，抛而不是转）。 */
    fun revision(result: JsonObject): Long =
        result.lngStrict("revision")
            ?: throw PayloadDecodeException("ui/modules result misses required number 'revision'")

    /** `result.modules[]` → 不可变节点列表；任何一条形状不对 → 整份拒绝。 */
    fun nodes(result: JsonObject): List<ModuleRegistry.Node> {
        val modules = result.mustArr("modules")
        val scratch = ModuleRegistry()
        for (raw in modules) {
            val module = raw as? JsonObject
                ?: throw PayloadDecodeException("ui/modules modules[] entry is not an object")
            val moduleId = module.mustStr("module_id")
            val instanceId = module.mustStr("instance_id")
            val online = module.boolStrict("online")
                ?: throw PayloadDecodeException("ui/modules modules[].online is required (bool)")
            // 声明走 declare（display_name + pages 的严格解码都在那里）。
            scratch.declare(
                buildJsonObject {
                    put("module_id", moduleId)
                    put("instance_id", instanceId)
                    // 契约是 `display_name: string|null`（C-03 里它是可选字段）。
                    // 缺/空时**不写这个键**，由 ModuleRegistry.declare 落到 module_id ——
                    // 早先这里用 mustStr 当必填，会让「没有 display_name 的模块」整份被拒。
                    module.strStrict("display_name")?.takeIf { it.isNotBlank() }?.let { put("display_name", it) }
                    module["version"]?.let { put("version", it) }
                    module["pages"]?.let { pages -> put("ui", buildJsonObject { put("pages", pages) }) }
                },
            )
            // 在线态/版本走 status 通道（declare 不动 online；applyStatus 不动声明）。
            scratch.applyStatus(moduleId, instanceId, online, module.strStrict("version"))
        }
        return scratch.nodes()
    }
}

/**
 * 一次 `ui/modules` 往返的**唯一**依赖：返回 `result` 对象原文，失败以 `VadeException`
 * 抛出（C-04 code 原样，别包别改）。
 *
 * 就这一条方法，真实现是「发一帧 + 拿回 result」：
 * ```kotlin
 * internal class WireModuleCatalogSource(private val call: suspend () -> JsonObject) : ModuleCatalogSource {
 *     override suspend fun fetch(): JsonObject = call()   // 例如 rawRpc("ui/modules", buildJsonObject {})
 * }
 * ```
 * 本棒**没有**建出这个真实现 —— 见 [ModulesLoader] 的 KDoc「阻塞」一节。
 */
internal interface ModuleCatalogSource {
    suspend fun fetch(): JsonObject
}

/**
 * 真实的 [ModuleCatalogSource]：一次 `ui/modules` 的原始回包。
 *
 * 只是一层函数包装，不持连接、不做重试——重试与去重都在 [ModulesLoader] 里。
 * 组合根交进来的是 `{ client.uiModulesRaw() }`（`RealSessionSource.connect()`）。
 */
internal class WireModuleCatalogSource(
    private val call: suspend () -> JsonObject,
) : ModuleCatalogSource {
    override suspend fun fetch(): JsonObject = call()
}

// ------------------------------------------------------------------ loader

/**
 * `ui/modules` 的驱动器，纪律与 `UsageLoader` / `SubagentLoader` 逐条对齐：
 *  - **三态**，结果只会是 `Loading → Ready` 或 `Loading → Failed`；
 *  - **Failed 可观测**，绝不洗成空的 Ready；
 *  - **generation 守卫**：每次拉取 +1 并捕获，晚到的回包（或失败）在写状态前丢弃，
 *    所以「先失败重试、旧回包后到」不会把新结果覆盖掉；
 *  - **[retry]** 重发（失败卡的入口），**[dispose]** 之后一切写入停止。
 *
 * 在此之上多两条本棒专有的纪律：
 *  - **revision 去重**：回包 `revision` == 上一次成功的 revision → **不重建注册表**，
 *    直接把现有声明快照重新发一遍（新的 `Ready` 实例，值相同）。`module.status` 抖动
 *    引发的重拉因此是「一次网络往返 + 零 UI 重建」。
 *  - **在飞不重发**：一次拉取在飞时 [invalidate] 只记 `pullAgain`，不并发发帧；完成后
 *    若期间来过失效，**补一次**（不是 N 次）—— 这就是「抖动不发帧风暴」的那道闸。
 *
 * ## 接线（g-modulepage-wire 起已全部到位）
 *
 *  - 通道：`RealSessionSource.connect()` 里 `ModulesLoader(WireModuleCatalogSource { created.uiModulesRaw() }, s)`，
 *    经 [ModuleCatalogProvider] 交给能力页（`AppShell` → `CapabilitiesScreen`）；
 *  - **第一次拉取**：能力页的 `LaunchedEffect(moduleCatalog)` → [ensureLoaded]（幂等，
 *    只发一次）；之后的变化只由 `module.status` → [invalidate] 驱动；
 *  - **页面/在线态**：同一个 loader 的 [registry] 就是 `ui/modules` 聚合出来的快照，
 *    组合根把它绑给 `RealModulePageSource`（`module/ModulePageSource.kt` 的
 *    `ModuleRegistryView`），于是页面宿主的 `status()` 不再恒报离线。
 *
 * 早先这里写着「没有生产接线 / 组合根交 null」——那是本仓 `ui/modules` 尚未进
 * `VadeMethod` 表时的状态，现已不成立。
 */
internal class ModulesLoader(
    private val source: ModuleCatalogSource,
    private val scope: CoroutineScope,
) : ModuleCatalog {

    /** 唯一的数据落点：清单解码后整体替换（[ModuleRegistry.replaceAll]）。 */
    internal val registry = ModuleRegistry()

    private var stateRef: ModuleCatalogState by mutableStateOf(ModuleCatalogState.Loading)

    override val state: ModuleCatalogState get() = stateRef

    /** 尝试代数：只有最新一代的回包能写状态（晚到的旧回包静默丢弃）。 */
    @Volatile
    private var generation = 0L

    @Volatile
    private var disposed = false

    /** 「已经发起过拉取」：`ensureLoaded` 的幂等旗标（见该方法的 KDoc）。 */
    @Volatile
    private var everLoaded = false

    /** 一次拉取在飞（[invalidate] 据此决定「直接补拉」还是「记一笔待补」）。 */
    @Volatile
    private var inFlight = false

    /** 在飞期间来过失效：完成后补拉一次（不是 N 次）。 */
    @Volatile
    private var pullAgain = false

    /** 上一次成功回包的 revision；`null` = 还没有成功过。 */
    @Volatile
    private var lastRevision: Long? = null

    /** 开始（或重发）一次拉取；与 [retry] 同义，给组合期直接调用用。 */
    fun load() {
        everLoaded = true
        pull()
    }

    override fun retry() {
        everLoaded = true
        pull()
    }

    /**
     * 幂等首次拉取（[ModuleCatalog.ensureLoaded] 的实现）：记在 **loader 实例**上，
     * 所以能力页反复进出不会重拉；`load()` / `retry()` 也算「已经拉过」，避免
     * 「先手动重试、再进屏幕」时多打一帧。
     *
     * 只从组合线程调用（`LaunchedEffect` 的单一调用点），与 [invalidate] 同一条线程假设。
     */
    override fun ensureLoaded() {
        if (disposed || everLoaded) return
        everLoaded = true
        pull()
    }

    override fun invalidate() {
        if (disposed) return
        if (inFlight) {
            pullAgain = true
            return
        }
        pull()
    }

    override fun onEvent(event: VadeEvent) {
        // events.json 冻结的模块 topic 只有 module.status（registry 变更通知）。
        // 它带的是「哪个实例的在线态变了」，不是清单 —— 清单只能重拉。
        if (event is ModuleStatusChangedEvent) invalidate()
    }

    /** 停止所有后续写入；幂等。 */
    fun dispose() {
        disposed = true
    }

    // ------------------------------------------------------------------ 内部

    private fun pull() {
        if (disposed) return
        generation += 1
        val gen = generation
        inFlight = true
        // 已有成功快照时不退回 Loading：刷新期间列表保持上一份真数据，而不是闪成空卡。
        // 首次拉取（或上一次失败）才进 Loading —— 那是「真的还没有结果」。
        if (stateRef !is ModuleCatalogState.Ready) stateRef = ModuleCatalogState.Loading
        scope.launch {
            val next: ModuleCatalogState? = try {
                val result = source.fetch()
                if (disposed || gen != generation) null else applyReply(result)
            } catch (e: CancellationException) {
                null // 协程被取消（dispose / 作用域结束）：不写状态，绝不编一个失败
            } catch (t: Throwable) {
                if (disposed || gen != generation) null else ModuleCatalogState.Failed(moduleLoadErrorOf(t))
            }
            inFlight = false
            if (disposed || gen != generation || next == null) return@launch
            stateRef = next
            if (pullAgain) {
                pullAgain = false
                pull()
            }
        }
    }

    /** 回包 → 新状态。revision 未变 → 不碰注册表（去重），只把现有快照重新发出。 */
    private fun applyReply(result: JsonObject): ModuleCatalogState {
        val revision = ModuleCatalogWire.revision(result)
        if (lastRevision == revision) {
            return ModuleCatalogState.Ready(revision, registry.declarations())
        }
        // 先解码（可能抛 → 状态进 Failed，注册表一个字节没动），成功后才整体替换。
        val nodes = ModuleCatalogWire.nodes(result)
        registry.replaceAll(nodes)
        lastRevision = revision
        return ModuleCatalogState.Ready(revision, registry.declarations())
    }
}
