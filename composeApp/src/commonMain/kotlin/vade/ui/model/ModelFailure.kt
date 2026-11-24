// Model picker — failing-RPC classification and copy (c-data §4 buckets).
//
// The picker can fail in two places: the `model/list` pull (the list never arrives) and the
// `session/model.set` write (the switch did not land). Both must be SHOWN, never swallowed:
// the write side additionally reverts the optimistic selection in `ModelState`, and this file
// decides what the reader is told — the C-04 code, the UI bucket, and copy that says what
// actually happened (a closed session is not a client bug, an old core is not a retry).
//
// Rules pinned by `ModelRealWiringTest`:
//  - `READ_ONLY_SESSION` has its own copy and is NEVER fatal (a closed/archived session is a
//    user decision, not a broken install) — its bucket is whichever the frozen table says;
//  - `-32601` on a write is NOT retryable: the method does not exist on that core, so the copy
//    says so instead of inviting a pointless retry;
//  - every other failure keeps the frozen `VadeErrorMapping` bucket through
//    `loadErrorCategoryOf`, so this file never invents a second classification table;
//  - the raw detail string rides along — the banner shows core's own words after the label.

package vade.ui.model

import vade.ui.data.VadeErrorCategory
import vade.ui.data.loadErrorCategoryOf
import vade.ui.data.loadErrorCodeOf
import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import vade.ui.protocol.isMethodMissing

/**
 * One failed model RPC, classified for the picker.
 *
 * [category] is the c-data §4 bucket the banner leads with; [headline]/[body] are the honest
 * one-line explanation; [code] is the C-04 name when the failure came through the protocol
 * seam (a transport IOException has none).
 */
data class ModelFailure(
    val category: VadeErrorCategory,
    val code: String?,
    val headline: String,
    val body: String,
) {
    /** True for `READ_ONLY_SESSION` — the closed-session refusal with its own copy. */
    val isReadOnlySession: Boolean get() = code == VadeErrorCodes.READ_ONLY_SESSION

    /** 致命 only when the C-04 bucket says so; a read-only session is never fatal. */
    val fatal: Boolean get() = category == VadeErrorCategory.Fatal

    /** Single line for the card: 标题（分类）：正文. */
    val label: String get() = "$headline（${category.label}）：$body"
}

/** One failed `session/model.set` (or a refused default-scope write) → displayable copy. */
fun modelWriteFailureOf(t: Throwable): ModelFailure = modelFailureOf(t, write = true)

/** One failed `model/list` / `session/model.get` pull → displayable copy. */
fun modelLoadFailureOf(t: Throwable): ModelFailure = modelFailureOf(t, write = false)

private fun modelFailureOf(t: Throwable, write: Boolean): ModelFailure {
    val code = loadErrorCodeOf(t)
    val bucket = loadErrorCategoryOf(t)
    val detail = t.message?.takeIf { it.isNotBlank() } ?: (t::class.simpleName ?: "未知错误")
    val failed = if (write) "模型切换未生效" else "模型列表未能加载"

    return when {
        // The core has no such method. Retrying cannot help, so the bucket is upgraded from
        // the table's Retryable default to 需要用户操作 — the copy says "not a network fault".
        t is VadeException && t.isMethodMissing() -> ModelFailure(
            category = VadeErrorCategory.NeedsUserAction,
            code = code,
            headline = if (write) "此 core 不支持模型写" else "此 core 不支持模型列表",
            body = "core 没有注册对应方法（$detail）；客户端降级为只读，重试无意义，升级 core 才会恢复。$failed。",
        )

        // Contract 1.5.0 has no global-default write (schema/rpc.json §directions) and the
        // adapter raises UNSUPPORTED locally — an explicit refusal, never a silent no-op.
        code == VadeErrorCodes.UNSUPPORTED -> ModelFailure(
            category = bucket,
            code = code,
            headline = "core 契约没有这个写方法",
            body = "契约 1.5.0 的 ui_to_core 方法里没有全局默认模型的写入（model/list 只读、session/model.set 仅会话级）；" +
                "默认模型跟随 core 配置。$failed。",
        )

        // A closed/archived session: the user must switch sessions — not a broken client, and
        // explicitly NOT 致命 (a closed session does not poison the install).
        code == VadeErrorCodes.READ_ONLY_SESSION -> ModelFailure(
            category = bucket,
            code = code,
            headline = "该会话已关闭（只读）",
            body = "会话已归档或关闭，core 拒绝任何写入；换到活跃会话即可重新选择模型。$failed。",
        )

        code == VadeErrorCodes.INVALID_ARGUMENT -> ModelFailure(
            category = bucket,
            code = code,
            headline = "core 不接受这次写入",
            body = "参数被拒（$detail）；选择已回滚到原值。$failed。",
        )

        code == VadeErrorCodes.PERMISSION_DENIED -> ModelFailure(
            category = bucket,
            code = code,
            headline = "没有修改该会话模型的权限",
            body = "core 以权限为由拒绝（$detail）。选择已回滚。$failed。",
        )

        code == VadeErrorCodes.NOT_FOUND -> ModelFailure(
            category = bucket,
            code = code,
            headline = "会话或模型不存在",
            body = "本地视图可能已过期（$detail）；重新同步后再试。$failed。",
        )

        write -> ModelFailure(
            category = bucket,
            code = code,
            headline = bucketHeadline(bucket, write),
            body = "$detail；选择已回滚，可重试。",
        )

        else -> ModelFailure(
            category = bucket,
            code = code,
            headline = bucketHeadline(bucket, write),
            body = detail,
        )
    }
}

private fun bucketHeadline(bucket: VadeErrorCategory, write: Boolean): String = when (bucket) {
    VadeErrorCategory.Retryable -> if (write) "暂时失败，可重试" else "暂时加载失败，可重试"
    VadeErrorCategory.NeedsReconnect -> "连接或本地视图已过期"
    VadeErrorCategory.NeedsUserAction -> if (write) "写入被拒绝" else "加载被拒绝"
    VadeErrorCategory.Fatal -> "致命：协议或版本不匹配"
}

/** The JSON-RPC reserved code an unregistered method answers with (also mapped by the core). */
internal const val METHOD_NOT_FOUND_CODE = "-32601"
