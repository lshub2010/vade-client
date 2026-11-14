package vade.ui.module

import kotlinx.serialization.json.JsonObject
import vade.ui.protocol.PageGetParams
import vade.ui.protocol.StateGetParams
import vade.ui.protocol.VadeClient
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import kotlin.concurrent.Volatile

/**
 * [ModulePageSource] 的真实现（w-modulewire）：持 [VadeClient]，把 C-19 模块页宿主从
 * 内存示例树（`ModulePageSources.demo()`）接到 `ui/page.get` / `ui/state.get` 两条真实
 * RPC 上。不改接口、不改协议层——协议层的类型化包装（[VadeClient.uiPageGet] /
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
class RealModulePageSource(private val client: VadeClient) : ModulePageSource {

    /**
     * 模块与页面声明快照：**返回空列表，这不是偷懒，而是没有真实数据源**。
     *
     * 声明只随 core→UI 的 `module.register` 事件到达（`manifest.ui.pages[]`），而
     * `ui_to_core` 的 34 个方法（`VadeMethod` dispatch 表）里**没有任何**返回模块清单
     * 的 RPC 可以拉取它；同时协议层 `VadeEventParser` 尚未解码 `module.register`
     * （不在 `VADE_EVENT_TYPES` 清单里），事件流上也没有现成数据可订阅。没有 RPC、
     * 没有可解码的事件，任何非空返回都只能是臆造。空声明经
     * `ModulePageEntryRules.build` 推出空入口集：能力页整个「模块页面」区块不渲染
     * （FR-8.79：无声明 → 无入口，不是空列表占位）——这是 fail-closed 的正确形态。
     * 接线点：协议层解码 `module.register` 后由宿主收集事件喂给本类（建议签名见
     * `shots/g-modulewire/RESULT.md`）。
     */
    override fun declarations(): List<ModuleDeclaration> = emptyList()

    /**
     * 模块在线状态：**返回离线，同样是诚实的「无数据源」**。
     *
     * `module.status` 事件协议层**已**解码（`client.events` 上有 `ModuleStatus`），
     * 但它没有对应的拉取式 RPC，而本接口是同步值语义——SharedFlow 无法被非挂起方法
     * 消费，收集事件需要一个宿主提供的协程作用域，那是宿主接线的一部分，不是本类
     * 能自作主张的。在事件接线落地前返回离线（`online=false`）：宿主按 §7.4 显示
     * 「模块离线」并禁用动作，绝不假装在线——把未知状态报成在线等于替模块担保。
     */
    override fun status(moduleId: String): ModuleStatus = ModuleStatus(online = false, version = null)

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
    ): ParseResult.Ok {
        requireParams(moduleId, instanceId, pageId)
        val result = client.uiPageGet(PageGetParams(moduleId, instanceId, pageId))
        val parsed = ModulePageParser.parse(result.schema, declaredPageIds)
        return when (parsed) {
            is ParseResult.Ok -> {
                // Ok 蕴含 root 是 JsonObject（解析器第一道门），此处强转安全。
                pages = pages + ("$moduleId/$pageId" to (result.schema as JsonObject))
                parsed
            }
            is ParseResult.Rejected -> throw pageRejected(moduleId, pageId, parsed.reasons)
        }
    }

    /**
     * `ui/state.get` → `result.state`。[loadState] 的真实后备。
     *
     * state 是合法的扁平对象，**空对象也是合法状态**（模块可以合法地没有状态）——
     * 空对象照实缓存，这不是「伪装成功」；「取不到」在同步接口上表达为 [loadState]
     * 返回 null（未 fetch），两者是不同的信号，不混用。
     */
    suspend fun fetchState(moduleId: String, instanceId: String, pageId: String): JsonObject {
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
