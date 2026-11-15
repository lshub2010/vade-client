package vade.ui.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
import vade.ui.module.ModuleDeclaration
import vade.ui.module.ModuleRegistryView
import vade.ui.module.ModuleStatus as ModuleOnlineStatus
import vade.ui.module.PageDeclaration
import vade.ui.module.PageIcon
import vade.ui.protocol.ModuleStatus as ModuleStatusEvent
import vade.ui.protocol.PayloadDecodeException
import vade.ui.protocol.VadeEvent as WireEvent
import vade.ui.protocol.el
import vade.ui.protocol.mustInt
import vade.ui.protocol.mustStr
import vade.ui.protocol.strStrict

/**
 * 客户端侧的**模块注册表**：唯一职责是把协议上真实存在的两条模块数据收进一份不可变快照，
 * 供能力页入口（[vade.ui.module.ModulePageEntryRules]）与模块页宿主消费。
 *
 * ## 两条数据各来自哪里（**不臆造**是这一类的第一原则）
 *
 * 1. **身份与在线态** —— `module.status` 事件（events.json v2.0.0：`module_id` /
 *    `instance_id` / `online` / `version`，四字段全解）。这是冻结契约里**唯一**的模块事件，
 *    也是本类 [onEvent] 唯一消费的事件类型；其余 topic 一律返回 `false` 且不改状态。
 * 2. **显示名与页面声明** —— manifest 的 `display_name` + `ui.pages[]`（C-03 冻结 schema，
 *    RFC-0009 §9.2 append）。**当前没有任何 wire 通道把它送到 UI**：
 *    `ui_to_core` 的 34 个方法里没有模块清单拉取，`ui/attach` 快照（core
 *    `session/manager.py::snapshot()`）不含 modules 键，events.json 里也没有
 *    `module.register` / `module.unregister` 这两个 topic（`module/register` 是
 *    **module→core 的 RPC**，不是事件）。所以本类只提供 [declare] 这个**注入口**：
 *    等 RFC 补上通道（`ui/modules` 或 `snapshot.modules[]`），调用方把 manifest 原样喂进来即可，
 *    本类与入口规则一行都不用改。**没有任何代码会凭空构造 manifest**。
 *
 * 没有 [declare] 过 manifest 的模块只出现在 [nodes] / [status] 里，**不会**出现在
 * [declarations] 中——因为标题和页面无从得知，填 `module_id` 当标题、或把 `pages` 编出来，
 * 都是替模块担保它从未声明过的东西。能力页于是按 FR-8.79 fail-closed：无声明 → 无入口。
 *
 * ## 并发与不可变性
 *
 * 写只发生在事件收集协程（单写者），读可以来自任意线程（UI）。每次写入**整体替换**
 * [snapshot] 引用（copy-on-write，节点本身全不可变），读侧不需要锁，也不会看到半更新的表。
 * `@Volatile` 用 `kotlin.concurrent.Volatile`（commonMain 可移植，同 `RealModulePageSource`）。
 *
 * 身份键是 **`(module_id, instance_id)`** 而非 `module_id`：`module.status` 的每条都带
 * `instance_id`，同一模块可以在 pool 模式起多个实例（core 的 `module_id#instance_id` 就是
 * 连接身份）。[status] 按 `module_id` 聚合（任一实例在线即在线），因为 `ModulePageSource.status`
 * 只有 `moduleId` 一个参数。
 */
class ModuleRegistry : ModuleRegistryView {

    /**
     * 一个已登记的模块实例。字段全部来自 wire，没有派生值：
     * - [online] / [version]：`module.status`（未收到过事件时 `false` / `null`，不假装在线）；
     * - [displayName] / [pages]：manifest（未注入时 `null` / `emptyList()`）。
     */
    data class Node(
        val moduleId: String,
        val instanceId: String,
        val online: Boolean,
        val version: String?,
        val displayName: String?,
        val pages: List<PageDeclaration>,
    ) {
        /** 已注入 manifest 且声明了显示名——只有这种节点能进 [declarations]。 */
        val isDeclared: Boolean get() = displayName != null
    }

    /** 节点表的不可变快照；整体替换，绝不原地改。 */
    private data class Snapshot(val nodes: List<Node>)

    @Volatile
    private var snapshot: Snapshot = Snapshot(emptyList())

    // ------------------------------------------------------------------ 事件入口

    /**
     * 吃一条 core→UI 事件；返回 `true` 表示这条事件被本类消费（`module.status`），
     * `false` 表示与本类无关（调用方据此决定要不要通知 UI 重组）。
     *
     * payload 里 `module_id` / `instance_id` 为空白的 `module.status` 不建节点、返回 `false`：
     * 契约要求这两个键是 string，空白串是 core 侧 bug，客户端不替它编一个身份。
     */
    fun onEvent(event: WireEvent): Boolean {
        val status = event as? ModuleStatusEvent ?: return false
        val payload = status.payload
        return applyStatus(payload.moduleId, payload.instanceId, payload.online, payload.version)
    }

    /**
     * 更新一个实例的身份/在线态（`module.status` 的字段级入口，事件之外也可直接喂）。
     *
     * 幂等：同一个 `(moduleId, instanceId)` 重复任意多次仍只有一个节点，位置停在首次出现的
     * 位置（= 注册顺序），只有 [Node.online] / [Node.version] 被覆盖。已注入的 manifest 声明
     * 原样保留——在线态变化不该抹掉标题与页面。
     *
     * @return `true` 当且仅当建了或更新了节点（空白 id 一律 `false`）。
     */
    fun applyStatus(moduleId: String, instanceId: String, online: Boolean, version: String?): Boolean {
        if (moduleId.isBlank() || instanceId.isBlank()) return false
        snapshot = mutate(moduleId, instanceId) { existing ->
            Node(
                moduleId = moduleId,
                instanceId = instanceId,
                online = online,
                version = version,
                displayName = existing?.displayName,
                pages = existing?.pages.orEmpty(),
            )
        }
        return true
    }

    // ------------------------------------------------------------------ 声明注入

    /**
     * 注入一份 **C-03 manifest 原文**（`module/register` 的 params 形状），只取入口需要的
     * 四个顶层键（`module_id` / `instance_id` / `display_name` / `version`）与
     * `ui.pages[]`（RFC-0009 §9.2：`id` / `title` / `icon` / `version` / `requires`）。
     * `ui.pages` 缺失或为空 = 这个模块**没有入口**（FR-8.79），节点照样登记（标题可用于
     * 「模块卡片」，只是不产生 entry）。
     *
     * 严格解码（**不静默**）：
     *  - 缺 `module_id` / `instance_id` / `display_name` → [PayloadDecodeException]；
     *  - `ui` / `ui.pages` 形状不对，或某个 page 缺 `id` / `title` / `version` 或类型不符
     *    → [PayloadDecodeException]（整份拒绝）。core 注册期已经把坏页剔除并把 warning 写进
     *    `Manifest.ui_warnings`，还能到客户端的这份里出现坏页，说明投影本身说谎；此时静默跳过
     *    那一页，等于让「入口少了一个」无从解释。与页面渲染器「整页拒渲」同一姿态（fail-closed）。
     *  - 未知键一律忽略（C-03 `additionalProperties: true`，前向兼容）；未知 `icon` 名按
     *    schema 默认值降级为 [PageIcon.None]（图标是装饰，不是语义）。
     *  - `requires` 里出现 `"session"` → [PageDeclaration.requiresSession]；其它值忽略
     *    （schema 只允许 `session`）。
     *
     * 幂等：同一 `(module_id, instance_id)` 重复注入只替换声明，在线态与位置保留。
     */
    fun declare(manifest: JsonObject) {
        val moduleId = manifest.mustStr("module_id")
        val instanceId = manifest.mustStr("instance_id")
        // C-03 的 `display_name` 是**可选**字段，C-05 `ui/modules` 明写 `string|null`。
        // 缺/空时回退到 `module_id`：界面必须有个标题，而「用 id 当标题」比「编一个名字」
        // 或「整份拒绝」都诚实。回退值不落盘到 manifest，只进 [Node.displayName]。
        val displayName = manifest.strStrict("display_name")?.takeIf { it.isNotBlank() }
            ?: moduleId
        val version = manifest.strStrict("version")
        val pages = decodePages(manifest)
        if (moduleId.isBlank() || instanceId.isBlank()) {
            throw PayloadDecodeException("manifest module_id/instance_id must be non-blank")
        }
        snapshot = mutate(moduleId, instanceId) { existing ->
            Node(
                moduleId = moduleId,
                instanceId = instanceId,
                online = existing?.online ?: false,
                version = existing?.version ?: version,
                displayName = displayName,
                pages = pages,
            )
        }
    }

    /**
     * `manifest.ui.pages[]` → [PageDeclaration]，**manifest 顺序原样**。缺失 / 空数组都返回
     * 空列表；`ui` 或 `pages` 形状非法抛 [PayloadDecodeException]。
     */
    private fun decodePages(manifest: JsonObject): List<PageDeclaration> {
        val uiElement = manifest.el("ui") ?: return emptyList()
        val ui = uiElement as? JsonObject
            ?: throw PayloadDecodeException("manifest.ui must be an object, got ${uiElement.kind()}")
        val pagesElement = ui.el("pages") ?: return emptyList()
        val pages = pagesElement as? JsonArray
            ?: throw PayloadDecodeException("manifest.ui.pages must be an array, got ${pagesElement.kind()}")
        return pages.map { raw ->
            val page = raw as? JsonObject
                ?: throw PayloadDecodeException("manifest.ui.pages[] entry must be an object, got ${raw.kind()}")
            val id = page.mustStr("id")
            val title = page.mustStr("title")
            if (id.isBlank()) throw PayloadDecodeException("manifest.ui.pages[].id must be non-blank")
            PageDeclaration(
                pageId = id,
                title = title,
                icon = pageIcon(page.strStrict("icon")),
                version = page.mustInt("version"),
                requiresSession = page.requiresSession(),
            )
        }
    }

    /** `requires: ["session"]` → true；缺失、空数组或别的东西 → false（schema 只允许 session）。 */
    private fun JsonObject.requiresSession(): Boolean {
        val element = el("requires") ?: return false
        val array = element as? JsonArray
            ?: throw PayloadDecodeException("manifest.ui.pages[].requires must be an array, got ${element.kind()}")
        return array.any { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content == "session" }
    }

    /** schema 的 `icon` 枚举（list|chart|settings|tool|file|none），未知值 → [PageIcon.None]。 */
    private fun pageIcon(raw: String?): PageIcon = when (raw) {
        "list" -> PageIcon.List
        "chart" -> PageIcon.Chart
        "settings" -> PageIcon.Settings
        "tool" -> PageIcon.Tool
        "file" -> PageIcon.File
        else -> PageIcon.None
    }

    // ------------------------------------------------------------------ 读

    /**
     * 声明快照，类型与 `ModulePageSource.declarations()` **逐字段一致**（`vade.ui.module.ModuleDeclaration`），
     * 所以接线就是一行 `override fun declarations() = registry.declarations()`。
     *
     * 只返回**已注入 manifest** 的实例（[Node.isDeclared]）。仅收到过 `module.status` 的模块
     * 不在这里：没有显示名就没有入口标题，编一个等于替模块起名。顺序 = 首次出现顺序。
     */
    override fun declarations(): List<ModuleDeclaration> = snapshot.nodes
        .filter { it.isDeclared }
        .map { node ->
            ModuleDeclaration(
                moduleId = node.moduleId,
                instanceId = node.instanceId,
                moduleTitle = node.displayName.orEmpty(),
                online = node.online,
                moduleVersion = node.version,
                pages = node.pages,
            )
        }

    /**
     * 按 `module_id` 聚合的在线态（`ModulePageSource.status` 只有一个参数）。任一实例在线
     * → 在线；版本取第一个在线实例的，否则取第一个实例的。**未知模块返回离线**，不返回
     * null、不崩：宿主据 §7.4 显示「模块离线」并禁用动作。
     */
    override fun status(moduleId: String): ModuleOnlineStatus {
        val matching = snapshot.nodes.filter { it.moduleId == moduleId }
        if (matching.isEmpty()) return ModuleOnlineStatus(online = false, version = null)
        val online = matching.firstOrNull { it.online }
        return ModuleOnlineStatus(
            online = online != null,
            version = (online ?: matching.first()).version,
        )
    }

    /** 全部已登记实例（含只有 `module.status` 的），首次出现顺序。 */
    fun nodes(): List<Node> = snapshot.nodes

    /**
     * **整表替换**（`ui/modules` 一次拉取的落点，见 `data/ModuleCatalog.kt`）：
     * [nodes] 成为新快照，顺序原样保留，旧的声明/在线态整体丢弃。
     *
     * 为什么是「整表替换」而不是逐个 [declare]：清单拉取是一次**全量快照**，core 已经
     * 把「现在有哪些模块实例」算完了；按条合并会让已下线的模块残留在表里，而注册表
     * 没有任何办法知道「这个实例这次不在清单里」是下线还是漏发。整表替换把这个问题
     * 消掉：清单说了算。
     *
     * 与其它写入口一致的单写者 copy-on-write：引用最后一次性换新，读侧永远看到自洽列表。
     * 传入的列表被复制一份（防御调用方后续修改）。
     */
    fun replaceAll(nodes: List<Node>) {
        snapshot = Snapshot(nodes.toList())
    }

    /** 一个实例；未注册 → null（不抛）。 */
    fun node(moduleId: String, instanceId: String): Node? =
        snapshot.nodes.firstOrNull { it.moduleId == moduleId && it.instanceId == instanceId }

    /** `module_id` 对应的第一个实例（便捷查询）；未注册 → null。 */
    fun node(moduleId: String): Node? = snapshot.nodes.firstOrNull { it.moduleId == moduleId }

    /** 清空（换连接/断开时用）；幂等。 */
    fun clear() {
        snapshot = Snapshot(emptyList())
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 单写者 copy-on-write：命中就原位替换（保序），否则追加。快照引用最后整体换新，
     * 读侧永远看到一份自洽的列表。
     */
    private fun mutate(
        moduleId: String,
        instanceId: String,
        build: (existing: Node?) -> Node,
    ): Snapshot {
        val current = snapshot.nodes
        val index = current.indexOfFirst { it.moduleId == moduleId && it.instanceId == instanceId }
        val next = if (index >= 0) {
            current.toMutableList().also { it[index] = build(current[index]) }
        } else {
            current + build(null)
        }
        return Snapshot(next)
    }

    /** 错误信息里的类型名（不用 `::class.simpleName`：wasm 上不保证可用）。 */
    private fun kotlinx.serialization.json.JsonElement.kind(): String = when (this) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        is JsonPrimitive -> if (isString) "string" else "number/bool"
    }
}
