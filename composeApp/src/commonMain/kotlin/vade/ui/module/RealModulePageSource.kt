package vade.ui.module

import kotlinx.serialization.json.JsonObject
import vade.ui.protocol.PageGetParams
import vade.ui.protocol.StateGetParams
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import kotlin.concurrent.Volatile

/**
 * [ModulePageSource] 的真实现（w-modulewire / g-modulepage-wire）：持 [VadeClient]，把
 * C-19 模块页宿主从内存示例树（`ModulePageSources.demo()`）接到 `ui/page.get` /
 * `ui/state.get` 两条真实 RPC 上，并把**声明与在线态**接到一次 `ui/modules` 的聚合快照上。
 * 不改接口、不改协议层——协议层的类型化包装（[VadeClient.uiPageGet] /
 * [VadeClient.uiStateGet]，经 `VadeMethod.UI_PAGE_GET` / `UI_STATE_GET` 路由）已经存在，
 * 本类只包一层。
 *
 * ## 同步接口 vs 挂起传输（两层结构）
 * 接口是同步值语义，其 KDoc 明示「真实实现若要走 ws 往返，在实现内部缓存/挂起即可」；
 * 而 ws 往返必须挂起，所以：
 *
 *  - [fetchPage] / [fetchState]（suspend）= **真 RPC 路径**：参数校验在发包前（任一
 *    空白参数直接抛 [VadeException]，一帧都不发）→ 发 `ui/page.get` / `ui/state.get`
 *    → 返回的 schema 交给现有 [ModulePageParser]（fail-closed 解析校验）→ **只有 Ok
 *    才进缓存**。任何失败都抛带 C-04 名的 [VadeException]，调用方经
 *    `vade.ui.data` 的 [VadeErrorMapping / loadErrorCategoryOf] 分类——拒绝路径绝不
 *    返回空树或 null 伪装成功。
 *  - [loadPage] / [loadState]（接口方法）= 只读缓存：没 fetch 过返回 null（接口契约的
 *    「不可用」信号 → 宿主显示卡片不白屏，§7.4），fetch 过返回**原样** schema
 *    JsonObject。宿主（`ModulePageRenderer`）在渲染期还会对 [loadPage] 的返回再 parse
 *    一次——那是防御纵深，不是重复劳动；本类的解析校验保证坏树进不了缓存。
 *
 * ## 失败分类约定（C-04 名 → `VadeErrorCategory`）
 *  - 参数校验失败 → `INVALID_ARGUMENT`（NeedsUserAction）：调用方改参数，重试无用。
 *  - 解析拒绝（脚本键 / 宿主组件 / 白名单外组件 / 深度·节点·键越界 / 结构非法）→
 *    `PROTOCOL_MISMATCH`（Fatal）：core 发来的页面违反客户端解析契约，重试与重连都
 *    无济于事——与 `VadeClient.call` 对「结果不可解码」的归类一致，解析器给出的拒渲
 *    理由原样带进 message。
 *  - 其余失败原样穿透 [VadeException]（core 的 34 个错误名 / 客户端 TIMEOUT /
 *    UNAVAILABLE），本类不吞、不改名、不重试。
 */
class RealModulePageSource(
    private val client: VadeClient,
    /** 声明与在线态的数据源（`data.ModuleRegistry`）；`null` = 未接线，见类 KDoc。 */
    private val registry: ModuleRegistryView? = null,
) : ModulePageSource, ModulePageBodyFetcher, ModulePageStateFetcher {

    /**
     * 声明快照：接上 [registry] 后它就是 `ui/modules` 一次拉取聚合出来的那份（模块标题 +
     * `ui.pages[]`），**不是**这里编的；没接线时返回空列表（= 没有数据源）。
     *
     * 空声明的影响被限制在「页内 navigate 目标校验」与面包屑的目标页查找这两处，
     * 两者都 fail-closed（带 `module:<page_id>` 的页内导航整页拒渲）——能力页的入口
     * 来自清单通道本身（`ModuleCatalog`），不经过这里。
     */
    override fun declarations(): List<ModuleDeclaration> = registry?.declarations().orEmpty()

    /**
     * 模块在线状态：接上 [registry] 后就是 `ui/modules` 回包里的 `online`
     * （`ModuleRegistry` 按 `module_id` 聚合，任一实例在线即在线）——**这是真值**，
     * 不是常量。页面顶部因此不再在模块真的在线时画「模块离线」。
     *
     * 未接线（`registry == null`）时返回离线：把未知状态报成在线等于替模块担保，
     * 宿主按 §7.4 显示「模块离线」并禁用动作是那时唯一诚实的画法。
     */
    override fun status(moduleId: String): ModuleStatus =
        registry?.status(moduleId) ?: ModuleStatus(online = false, version = null)

    /** 缓存：`moduleId/pageId` → 解析校验通过的原样 schema（不可变 map 整体替换）。 */
    @Volatile
    private var pages: Map<String, JsonObject> = emptyMap()

    /** 缓存：`moduleId/pageId` → 最近一次 `ui/state.get` 的 state 对象。 */
    @Volatile
    private var states: Map<String, JsonObject> = emptyMap()

    override fun loadPage(moduleId: String, pageId: String): JsonObject? {
        // 纯缓存读：空白 id 永远不在缓存里，返回 null（不可用），不抛异常不发帧。
        if (moduleId.isBlank() || pageId.isBlank()) return null
        return pages["$moduleId/$pageId"]
    }

    override fun loadState(moduleId: String, instanceId: String, pageId: String): JsonObject? {
        if (moduleId.isBlank() || pageId.isBlank()) return null
        return states["$moduleId/$pageId"]
    }

    // ----------------------------------------------------------- 真 RPC 路径

    /**
     * `ui/page.get` → `result.schema` → [ModulePageParser]。[loadPage] 的真实后备。
     *
     * [declaredPageIds] 是本模块 `ui.pages[]` 声明的页面 id 集合，用于校验 navigate 的
     * `module:<page_id>` 目标（§7.2：navigate 到未声明页 → 拒渲）。调用方掌握多少就传
     * 多少；**缺省按「未声明」处理（fail-closed）**——带 `module:` 页内导航的页面会被
     * 整页拒渲。不知道声明集就放行，等于替模块担保它从未声明过的导航目标。
     *
     * 成功 = [ParseResult.Ok]（类型化树 + 节点/动作计数），schema 原样进缓存，
     * [loadPage] 随即可取；失败 = [VadeException]（分类见类 KDoc）。
     */
    suspend fun fetchPage(
        moduleId: String,
        instanceId: String,
        pageId: String,
        declaredPageIds: Set<String> = emptySet(),
    ): ParseResult.Ok = pageGet(moduleId, instanceId, pageId, declaredPageIds).parsed

    /**
     * [ModulePageBodyFetcher] 的实现：同样的 `ui/page.get`，把 **schema 原文**交给调用方
     * （宿主 `ModulePageHost` 的异步后备）。
     *
     * 与 [fetchPage] 走同一段代码（同一个参数校验、同一次解析校验、同一份缓存写入），
     * 差别只在返回值：这里要的是「拿来渲染的树」，`fetchPage` 要的是「解析结论」。
     * 解析拒绝照样抛 `PROTOCOL_MISMATCH`——异步不等于放松校验。
     */
    override suspend fun fetchBody(
        moduleId: String,
        instanceId: String,
        pageId: String,
        declaredPageIds: Set<String>,
    ): JsonObject = pageGet(moduleId, instanceId, pageId, declaredPageIds).schema

    /** `ui/page.get` 的一帧：解析校验通过才进缓存，然后两个返回值一起交出去。 */
    private suspend fun pageGet(
        moduleId: String,
        instanceId: String,
        pageId: String,
        declaredPageIds: Set<String>,
    ): PageBody {
        requireParams(moduleId, instanceId, pageId)
        val result = client.uiPageGet(PageGetParams(moduleId, instanceId, pageId))
        val parsed = ModulePageParser.parse(result.schema, declaredPageIds)
        return when (parsed) {
            is ParseResult.Ok -> {
                // Ok 蕴含 root 是 JsonObject（解析器第一道门），此处强转安全。
                val schema = result.schema as JsonObject
                pages = pages + ("$moduleId/$pageId" to schema)
                PageBody(schema = schema, parsed = parsed)
            }
            is ParseResult.Rejected -> throw pageRejected(moduleId, pageId, parsed.reasons)
        }
    }

    /** [pageGet] 的两个返回值：schema 原文（渲染用）+ 解析结论（计数/拒渲理由）。 */
    private class PageBody(val schema: JsonObject, val parsed: ParseResult.Ok)

    /**
     * `ui/state.get` → `result.state`。[loadState] 的真实后备；g-module-state 起也是
     * [ModulePageStateFetcher] 的实现——宿主把「pageFetch 也是状态后备」当作接线信号，
     * 绑定页据此真发这一帧（静态页在 [referencesPageState] 判据下一帧都不发）。
     *
     * state 是合法的扁平对象，**空对象也是合法状态**（模块可以合法地没有状态）——
     * 空对象照实缓存，这不是「伪装成功」；「取不到」在同步接口上表达为 [loadState]
     * 返回 null（未 fetch），两者是不同的信号，不混用。
     */
    override suspend fun fetchState(moduleId: String, instanceId: String, pageId: String): JsonObject {
        requireParams(moduleId, instanceId, pageId)
        val result = client.uiStateGet(StateGetParams(moduleId, instanceId, pageId))
        states = states + ("$moduleId/$pageId" to result.state)
        return result.state
    }

    // ---------------------------------------------------------------- 内部

    /** 发包前参数校验：三个 wire 必填参数任一空白 → INVALID_ARGUMENT，零帧。 */
    private fun requireParams(moduleId: String, instanceId: String, pageId: String) {
        if (moduleId.isBlank()) {
            throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.module_id is required and must be non-blank (refused before any frame)",
            )
        }
        if (instanceId.isBlank()) {
            throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.instance_id is required and must be non-blank (refused before any frame)",
            )
        }
        if (pageId.isBlank()) {
            throw VadeException(
                VadeErrorCodes.INVALID_ARGUMENT,
                "params.page_id is required and must be non-blank (refused before any frame)",
            )
        }
    }

    /** 解析拒绝 → PROTOCOL_MISMATCH（Fatal），解析器的定位理由原样随行。 */
    private fun pageRejected(moduleId: String, pageId: String, reasons: List<String>): VadeException =
        VadeException(
            code = VadeErrorCodes.PROTOCOL_MISMATCH,
            message = "ui/page.get schema rejected by client parser (module=$moduleId page=$pageId): " +
                reasons.joinToString("; ").take(300),
        )
}
