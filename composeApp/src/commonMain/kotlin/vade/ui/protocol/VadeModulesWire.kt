package vade.ui.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A3 · `ui/modules` 的类型化回包（C-05 **1.6.0** / RFC-0015，形状逐字取自
 * `contracts/vade/schema/rpc.json` `directions.ui_to_core["ui/modules"].result`）：
 *
 * ```
 * { revision: int,
 *   modules: [ { module_id, instance_id,
 *                display_name: string|null, version: string|null,
 *                online: bool,
 *                pages: [ { id, title, icon, entry, version: int, requires: ["session"], refresh_s } ] } ] }
 * ```
 *
 * 四处必须照契约、不能凭直觉的地方：
 *  - 页面键是 **`id`**（= C-03 `$defs.page.id`），**不是 `page_id`**；
 *  - `display_name` 与 `version` 都可空 —— C-03 里 `display_name` 本身就是可选字段，
 *    `version` 只在记录没有解析过 manifest 时为 null；
 *  - `icon` / `entry` 是**枚举字符串**（默认 `none` / `auto`），不是自由文本；
 *  - `revision` 是 core **进程内**的单调计数器（与 `module/list` 同一个计数模型），
 *    不是向量时钟也跨不了进程。
 *
 * `params` 恒为 `{}`：模块集合是进程级事实，契约明写它不会长出 `session_id`。
 * 一次拉回**全量**清单（"ONE pull returns the WHOLE list because an entry list has to
 * render on first paint"），所以这里有意的没有分页字段。
 */
data class UiModulesResult(
    val revision: Long,
    val modules: List<UiModuleEntry>,
) {
    companion object {
        fun fromJson(o: JsonObject): UiModulesResult = UiModulesResult(
            revision = o.lngStrict("revision") ?: 0L,
            modules = o.arr("modules").orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(UiModuleEntry::fromJson) },
        )
    }
}

/** 一台在线/离线的模块实例。`online` 与 `module.status` 投递的是**同一个谓词**。 */
data class UiModuleEntry(
    val moduleId: String,
    val instanceId: String,
    val displayName: String?,
    val version: String?,
    val online: Boolean,
    val pages: List<UiModulePage>,
) {
    companion object {
        fun fromJson(o: JsonObject): UiModuleEntry = UiModuleEntry(
            moduleId = o.strStrict("module_id") ?: "",
            instanceId = o.strStrict("instance_id") ?: "",
            displayName = o.strStrict("display_name"),
            version = o.strStrict("version"),
            online = o.boolStrict("online") ?: false,
            pages = o.arr("pages").orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(UiModulePage::fromJson) },
        )
    }
}

/**
 * 一个可挂到 UI 上的模块页声明（C-03 `ui.pages[]` 的线上投影）。
 *
 * `version` 是**页面 schema 的版本**，不是模块 SemVer —— 渲染器按它 fail-closed
 * （高于 `ModulePageParser.SUPPORTED_PAGE_VERSION` 的页面不出现，只留隐藏说明）。
 */
data class UiModulePage(
    val id: String,
    val title: String,
    val icon: String,
    val entry: String,
    val version: Int,
    val requires: List<String>,
    val refreshS: Int,
) {
    companion object {
        fun fromJson(o: JsonObject): UiModulePage = UiModulePage(
            id = o.strStrict("id") ?: "",
            title = o.strStrict("title") ?: "",
            icon = o.strStrict("icon") ?: "none",
            entry = o.strStrict("entry") ?: "auto",
            version = o.intStrict("version") ?: 0,
            requires = o.arr("requires").orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            refreshS = o.intStrict("refresh_s") ?: 0,
        )
    }
}
