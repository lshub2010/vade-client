package vade.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.data.SourceKeys
import vade.ui.skin.LocalSkin
import vade.ui.skin.SkinRegistry
import vade.ui.skin.Skins

/**
 * P1-4 · 启动配置不合法时的**可读错误屏**（三端共用的唯一实现）。
 *
 * 之前 `sessionSourceFromEnvironment()` 在入口里裸调：`VADE_PORT=8443x`（或 Android 的
 * `--es port 84.43`、Wasm 的 `?port=0`）让 `SourceConfig.parse` 抛
 * `IllegalArgumentException` —— 桌面是窗口里的未捕获异常、Android 是启动即闪退、
 * 浏览器是一片白。参数写错不该是「应用坏了」，更不该让用户猜。
 *
 * 这里做两件事，都只读**已有的**事实：
 *  1. 原样显示 `IllegalArgumentException` 的那句话（解析器已经把非法值和合法区间写进
 *     message，见 `SourceConfig.parse`）——不二次改写、不吞掉；
 *  2. 给出可照抄的修法：解析器接受的键从 [SourceKeys.acceptedKeys] 派生（与
 *     `SelfRescueRemedyGuardTest` 同一条口径，不手写第二份键名表），三端各一条示例。
 *
 * 渲染只依赖 material 的内置 token（这里的皮肤选择与 `skinId` 无关：配置都读不出来时
 * 没有「用户选的皮肤」这件事），并自带 `LocalSkin` provider —— 它在 `App()` 之外渲染，
 * 不能假设组合树里已经有皮肤。
 */
@Composable
fun ConfigErrorScreen(
    cause: Throwable,
    modifier: Modifier = Modifier,
) {
    // `App()` 通常负责注册内置皮肤；这条路径不走 App()，所以这里自己装（幂等）。
    Skins.installBuiltIns()
    val skin = SkinRegistry.get("material")
    CompositionLocalProvider(LocalSkin provides skin) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(skin.colors.canvas),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                UiText(
                    text = CONFIG_ERROR_TITLE,
                    size = 18,
                    weight = FontWeight.SemiBold,
                    color = skin.colors.error,
                )
                UiText(
                    text = CONFIG_ERROR_LEAD,
                    size = 12,
                    color = skin.colors.onSurfaceVariant,
                )
                // 具体原因：解析器的原话（非法值 + 合法区间）。空 message 时退回类型名，
                // 绝不显示一张「出错了」却不说为什么的屏。
                UiText(
                    text = configErrorReason(cause),
                    size = 12,
                    weight = FontWeight.SemiBold,
                    color = skin.colors.onSurface,
                )
                UiText(
                    text = CONFIG_ERROR_KEYS,
                    size = 12,
                    color = skin.colors.onSurfaceVariant,
                )
                UiText(
                    text = CONFIG_ERROR_EXAMPLES,
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 错误屏正文：`类型: message`。message 为空时只报类型 —— 调用方保证屏上一定有文字。
 * 纯函数，便于桌面测试钉住「原因不被吞掉」（`ConfigErrorScreenTest`）。
 */
internal fun configErrorReason(cause: Throwable): String {
    val type = cause::class.simpleName?.takeIf { it.isNotBlank() } ?: "启动失败"
    val message = cause.message?.trim()?.takeIf { it.isNotEmpty() } ?: return type
    return "$type: $message"
}

/** 错误屏标题。 */
internal const val CONFIG_ERROR_TITLE = "配置有误，应用无法启动"

/** 一句话说明「哪一类配置」坏了。 */
internal const val CONFIG_ERROR_LEAD =
    "启动参数里的来源/地址/端口没能解析成可用的连接目标。具体原因："

/**
 * 可用的键 —— 由 [SourceKeys.acceptedKeys] 拼出来，不手写第二份列表
 * （g-rescue-text 修掉的正是「文案里的键名与解析器不一致」这类漂移）。
 */
internal val CONFIG_ERROR_KEYS: String =
    "可用的键：" + SourceKeys.acceptedKeys.joinToString(" / ") { it } +
        "（source 只有写 real 才连真 core；留空 = 示例数据）"

/** 三端各自的修法示例（键名全部来自 [SourceKeys]，与真实解析路径一致）。 */
internal val CONFIG_ERROR_EXAMPLES: String = buildString {
    appendLine(CONFIG_ERROR_HOW_TO_FIX)
    appendLine(
        "桌面：VADE_${SourceKeys.SOURCE.uppercase()}=real VADE_${SourceKeys.HOST.uppercase()}=127.0.0.1 " +
            "VADE_${SourceKeys.PORT.uppercase()}=8443",
    )
    appendLine(
        "Android：adb shell am start -n <包名>/.MainActivity --es ${SourceKeys.SOURCE} real " +
            "--es ${SourceKeys.PORT} 8443",
    )
    append(
        "浏览器：index.html?${SourceKeys.SOURCE}=real&${SourceKeys.HOST}=127.0.0.1&" +
            "${SourceKeys.PORT}=8443",
    )
}

/** 修法导语。 */
internal const val CONFIG_ERROR_HOW_TO_FIX =
    "怎么修：把出错的键改成合法值后重启应用（端口是 1..65535 的整数，地址留空即 127.0.0.1）。"
