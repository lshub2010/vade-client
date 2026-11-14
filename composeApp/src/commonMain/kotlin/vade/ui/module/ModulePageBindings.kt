package vade.ui.module

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 只读绑定解析（§5）——**没有表达式求值器**。绑定 = 路径字符串 + 可选 format；
 * 「条件显示 / 条件文本」由模块在 `page.state` 里算成布尔 / 格式化好的字段。
 *
 * 三个根（§5.1），没有第四个：`module.status.*` / `page.state.*` / `item.*`。
 * 会话正文、审计、其他模块状态一律不可绑定：越界显示空值 + 一次性提示，
 * 不发任何 RPC、不回显任何 core 数据（FR-8.78 判据）。
 */
enum class BindRoot {
    ModuleStatus,
    PageState,
    Item,
    /** 形式合法但根不在三个根内 → 渲染期空值 + 一次性提示（§5.4）。 */
    OutOfRoot,
}

fun PageBinding.rootKind(): BindRoot = when {
    segments.size >= 2 && segments[0] == "module" && segments[1] == "status" -> BindRoot.ModuleStatus
    segments.size >= 2 && segments[0] == "page" && segments[1] == "state" -> BindRoot.PageState
    segments.size >= 2 && segments[0] == "item" -> BindRoot.Item
    else -> BindRoot.OutOfRoot
}

/**
 * 渲染期绑定环境。`state` 是 `ui/state.get` 的 `state` 对象（模块自报，不可信，
 * 纯文本输出）；`inputs` 是本地 `$input` 内存（随页面销毁而丢，N2）。
 */
data class PageEnv(
    val state: JsonObject?,
    val moduleOnline: Boolean,
    val moduleVersion: String?,
    val inputs: Map<String, String> = emptyMap(),
    val hasSession: Boolean = false,
)

/** 一次性提示收集器（§5.4：越界绑定 → 空值 + 提示，不崩、不发 RPC）。 */
class PageResolveReport {
    val notices = mutableListOf<String>()

    fun notice(message: String) {
        if (notices.none { it == message }) notices += message
    }
}

/**
 * 按绑定取值。返回 null = 值缺失（正常状态，如 page.state 首次返回前，§5.4 无提示）；
 * 越界根 = 返回 null + 报告一次性提示。**永不抛异常**——绑定值缺失不崩是验收项 ⑧。
 */
fun resolveBinding(binding: PageBinding, env: PageEnv, item: JsonObject?, report: PageResolveReport?): JsonElement? {
    return when (binding.rootKind()) {
        BindRoot.ModuleStatus -> when (binding.segments.getOrNull(2)) {
            "online" -> JsonPrimitive(env.moduleOnline)
            "version" -> env.moduleVersion?.let { JsonPrimitive(it) }
            else -> null // 缺失 = 空值，无提示
        }
        BindRoot.PageState -> {
            val key = binding.segments.getOrNull(2) ?: return null
            // state 是扁平对象：第 4 段（page.state.a.b）没有第二层，按缺失处理。
            if (binding.segments.size > 3) return null
            env.state?.get(key)
        }
        BindRoot.Item -> {
            if (item == null) {
                report?.notice("此页面引用了不可用数据（item.* 只在列表模板内可用）")
                return null
            }
            // item.* 是两段路径（item.<field>）：字段在第 2 段。
            val field = binding.segments.getOrNull(1) ?: return null
            if (binding.segments.size > 2) return null
            item[field]
        }
        BindRoot.OutOfRoot -> {
            report?.notice("此页面引用了不可用数据：绑定 \"${binding.raw}\" 不在三个根内，不发任何 RPC")
            null
        }
    }
}

/** §5.2 真值判定：falsy / 空数组 / 空串 / false / null 为假；缺失(null)为假。 */
fun isTruthy(value: JsonElement?): Boolean = when (value) {
    null, is JsonNull -> false
    is JsonPrimitive -> when {
        value.booleanOrNull != null -> value.booleanOrNull!!
        value.isString -> value.content.isNotEmpty()
        else -> value.content != "0"
    }
    is JsonArray -> value.isNotEmpty()
    is JsonObject -> true
}

/**
 * `text` 叶的 format（§4.3）。未知 format 在解析期已降级为 text（§7.2 装饰性降级）。
 * 非 number 值按原字符串输出；缺失 → 空串。纯文本输出，不解释 Markdown / HTML（§10.3）。
 */
object PageFormatter {
    fun format(value: JsonElement?, format: PageTextFormat): String {
        if (value == null || value is JsonNull) return ""
        val primitive = value as? JsonPrimitive ?: return "[object]"
        return when (format) {
            PageTextFormat.Text -> primitive.content
            PageTextFormat.Int -> primitive.longOrNull?.toString()
                ?: primitive.doubleOrNull?.toLong()?.toString()
                ?: primitive.content
            PageTextFormat.Decimal -> primitive.doubleOrNull?.let { trim(it) } ?: primitive.content
            PageTextFormat.Percent -> {
                val d = primitive.doubleOrNull ?: return primitive.content
                trim(d * 100) + "%"
            }
            PageTextFormat.Bytes -> {
                val n = primitive.longOrNull ?: return primitive.content
                humanBytes(n)
            }
            PageTextFormat.Datetime -> {
                val epochSec = primitive.longOrNull ?: return primitive.content
                formatUtc(epochSec)
            }
            PageTextFormat.Relative -> {
                // 演示数据用固定「现在」基准，避免测试与时钟耦合。
                val seconds = primitive.doubleOrNull ?: return primitive.content
                relative(seconds)
            }
        }
    }

    private fun trim(d: Double): String {
        val rounded = kotlin.math.round(d * 100) / 100
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    fun humanBytes(n: Long): String = when {
        n >= 1 shl 30 -> trim(n.toDouble() / (1 shl 30)) + " GB"
        n >= 1 shl 20 -> trim(n.toDouble() / (1 shl 20)) + " MB"
        n >= 1 shl 10 -> trim(n.toDouble() / (1 shl 10)) + " KB"
        else -> "$n B"
    }

    /** epoch 秒 → "yyyy-MM-dd HH:mm"（UTC，演示足够；带时区的格式化随协议接线一起做）。 */
    fun formatUtc(epochSec: Long): String {
        val days = epochSec / 86400
        val rem = epochSec % 86400
        val (y, m, d) = civilFromDays(days)
        // commonMain 禁 JVM 专有 API：不用 String.format，手工 padStart 拼。
        return buildString {
            append(y.toString().padStart(4, '0'))
            append('-').append(m.toString().padStart(2, '0'))
            append('-').append(d.toString().padStart(2, '0'))
            append(' ')
            append((rem / 3600).toString().padStart(2, '0'))
            append(':')
            append(((rem % 3600) / 60).toString().padStart(2, '0'))
        }
    }

    fun relative(secondsAgo: Double): String = when {
        secondsAgo < 0 -> "刚刚"
        secondsAgo < 60 -> "刚刚"
        secondsAgo < 3600 -> "${(secondsAgo / 60).toInt()} 分钟前"
        secondsAgo < 86400 -> "${(secondsAgo / 3600).toInt()} 小时前"
        else -> "${(secondsAgo / 86400).toInt()} 天前"
    }

    // Howard Hinnant 的 civil_from_days：纯整数历法，commonMain 可用（无 java.time）。
    private fun civilFromDays(z: Long): Triple<Long, Int, Int> {
        val shifted = z + 719468
        val era = (if (shifted >= 0) shifted else shifted - 146096) / 146097
        val doe = shifted - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple(if (m <= 2) y + 1 else y, m.toInt(), d.toInt())
    }
}

/** `$input` 严格整数（§5.3：`^[0-9]{1,9}$`，失败 → 动作不发出）。 */
val STRICT_INT = Regex("^[0-9]{1,9}$")

/**
 * 解析 `$input` 引用。`as:int` 严格解析，失败返回 null（调用方不发出动作并就地提示）。
 */
fun resolveInput(ref: InputRef, asInt: Boolean, env: PageEnv, report: PageResolveReport?): JsonElement? {
    val raw = env.inputs[ref.inputId] ?: ""
    if (asInt) {
        if (!STRICT_INT.matches(raw)) {
            report?.notice("输入 \"$raw\" 不是有效整数，动作未发出")
            return null
        }
        return JsonPrimitive(raw.toLong())
    }
    return JsonPrimitive(raw)
}
