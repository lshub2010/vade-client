package vade.ui.destinations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vade.ui.UiText
import vade.ui.data.PairingAttempt
import vade.ui.data.PairingFailureKind
import vade.ui.data.PairingRuntime
import vade.ui.data.RawSourceStrings
import vade.ui.data.firstRunPairingAttempt
import vade.ui.data.needsFirstRunPairing
import vade.ui.data.pairingEndpointOf
import vade.ui.data.rawSourceStrings
import vade.ui.skin.LocalSkin

/**
 * 首次运行引导卡（g-pair-first-run）：会话列表页顶部的一张卡 —— 不是弹窗、不遮会话，
 * 出现时机 = **源配置为真源且缺配对码**（[needsFirstRunPairing]），且本次运行尚未配对
 * 成功（[PairingRuntime] 里还没有 factory）。
 *
 * 提交走**现有连接链**：[firstRunPairingAttempt] —— 语法仍是 `SourceConfig.parse`，构造
 * 仍是 `VadeClientConfig` + `BrowserPairing`，传输仍是 `KtorTransportFactory`（runner 每次重连的
 * 同一入口）；一次调用 = 恰好一次 `POST /ui/session` + 一次 WS 升级。成功后把已换到 cookie
 * 的 factory 装进 [PairingRuntime]：卡片消失（本组件读桥）、入口处的源构造重读桥、列表
 * 开始拉真数据 —— 没有第二条连接路径。
 *
 * **码的边界**：输入值只活在 [code] 这个组合内存 state 与 [firstRunPairingAttempt] 的函数
 * 参数里 —— 不写 DataStore / 不拼进 URL / 不进日志（守卫测试扫描持久化与 URL sink）。
 * 配对成功码即被消费，卡片随之出组合，state 一并消失。
 *
 * 三种失败各有文案与下一步（[pairingHeadline] / [pairingNextStep]），失败后输入框与
 * 「配对」按钮保持可用 —— 重试就是再点一次，不另造重试状态机。
 *
 * [raw] / [onSubmit] 是注入缝：默认值 = 平台真实通道 + 真实尝试（生产逐字节不变）；
 * 测试注入替身。可见性判定在**卡内**完成（`rawSourceStrings` 是数据层的公共通道，
 * `AppShell` 被禁改，卡片自足），所以 `SessionsScreen` 只多了一个 item 槽位。
 */
@Composable
internal fun FirstRunPairingCard(
    modifier: Modifier = Modifier,
    /** 原始配置通道：默认平台的真实通道（桌面 env / Android extras / Wasm query）。 */
    raw: () -> RawSourceStrings = ::rawSourceStrings,
    /** 提交动作：默认 = [firstRunPairingAttempt]（现有连接链的一次尝试）。 */
    onSubmit: suspend (RawSourceStrings, String) -> PairingAttempt =
        { rawStrings, enteredCode -> firstRunPairingAttempt(rawStrings, enteredCode) },
) {
    // 出现条件：未配对成功 且 真源且缺码。不满足就一个节点都不组合（空 item 不占行高，
    // 与 ListStatus.Connected → Unit 同一模式）。
    val paired = PairingRuntime.pairedFactory != null
    if (paired || !needsFirstRunPairing(raw())) return

    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()

    // 输入值只在这里 —— 组合内存。不写盘、不进 URL、不进日志（见类 KDoc 与守卫测试）。
    var code by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<PairingFailureKind?>(null) }
    var failureDetail by remember { mutableStateOf<String?>(null) }

    skin.Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UiText(PAIRING_TITLE, size = 13, weight = FontWeight.SemiBold)
            UiText(
                text = PAIRING_WHERE,
                size = 11,
                color = skin.colors.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // skin.TextField 是 C-09 冻结组件，没有 modifier 形参 —— 由外层 Box 吃 weight。
                Box(modifier = Modifier.weight(1f)) {
                    skin.TextField(
                        value = code,
                        onValueChange = {
                            code = it
                            failure = null
                            failureDetail = null
                        },
                        placeholder = PAIRING_PLACEHOLDER,
                        enabled = !submitting,
                    )
                }
                skin.Button(
                    text = PAIRING_BUTTON_LABEL,
                    onClick = {
                        if (submitting || code.isBlank()) return@Button
                        submitting = true
                        failure = null
                        failureDetail = null
                        scope.launch {
                            try {
                                when (val attempt = onSubmit(raw(), code)) {
                                    is PairingAttempt.Connected -> PairingRuntime.install(attempt.factory)
                                    is PairingAttempt.Rejected -> {
                                        failure = attempt.kind
                                        // 传输原话，截短到卡里能读的长度（原文已 ≤300 字符）。
                                        failureDetail = attempt.cause.message?.take(160)
                                    }
                                }
                            } finally {
                                submitting = false
                            }
                        }
                    },
                    enabled = !submitting && code.isNotBlank(),
                )
            }
            if (submitting) {
                UiText(PAIRING_SUBMITTING, size = 11, color = skin.colors.onSurfaceVariant)
            }
            failure?.let { kind ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    UiText(
                        text = pairingHeadline(kind, raw()),
                        size = 12,
                        weight = FontWeight.SemiBold,
                        color = skin.colors.error,
                    )
                    failureDetail?.let { detail ->
                        UiText(
                            text = detail,
                            size = 10,
                            color = skin.colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    UiText(
                        text = pairingNextStep(kind, raw()),
                        size = 11,
                        color = skin.colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 卡标题。 */
internal const val PAIRING_TITLE = "首次运行 · 连接你的 core"

/**
 * 「码在哪里找」一句（照 `docs/PAIRING.md` §二的口径）：core 启动后 stdout 里
 * `pair_code=` 那一行，形如「双字-6 位」，10 分钟有效、只能用一次。
 */
internal const val PAIRING_WHERE =
    "配对码在 core 启动后的终端输出里：pair_code= 开头的那一行（形如 青松-XXXXXX，" +
        "10 分钟内有效、只能用一次）。"

/** 输入框占位。 */
internal const val PAIRING_PLACEHOLDER = "一次性配对码"

/** 提交按钮。 */
internal const val PAIRING_BUTTON_LABEL = "配对"

/** 尝试进行中的提示。 */
internal const val PAIRING_SUBMITTING = "正在配对…"

/**
 * 三种失败的标题 —— 互不可替换（测试逐条断言相等 + 两两不等）。
 * [ServerDown] 带 endpoint：用户要知道「该把 core 起在哪」。
 */
internal fun pairingHeadline(kind: PairingFailureKind, raw: RawSourceStrings): String = when (kind) {
    PairingFailureKind.BadCode -> "配对码不对：可能已过期、已被用掉，或者抄错了"
    PairingFailureKind.ServerDown -> "core 没有在 ${pairingEndpointOf(raw)} 上运行（连接被拒绝）"
    PairingFailureKind.Network -> "网络不通：连不上 core"
}

/**
 * 三种失败的下一步 —— 每种都必须有可执行的动作。
 * [ServerDown] 的启动命令照 `docs/PAIRING.md` §一口径（Windows 分隔符 `;`，Linux/macOS `:`）。
 */
internal fun pairingNextStep(kind: PairingFailureKind, raw: RawSourceStrings): String = when (kind) {
    PairingFailureKind.BadCode ->
        "下一步：回 core 的终端重新取一个新码（每次重启 core 都会铸新码），再填一次。"
    PairingFailureKind.ServerDown ->
        "下一步：先把 core 跑起来再重试。在 Vade 仓库根目录执行：" +
            "PYTHONPATH=\"core;protocol/core/python\" python -m vade" +
            "（Windows 分隔符 ; ，Linux/macOS 用 : ，详见 docs/PAIRING.md §一）。"
    PairingFailureKind.Network ->
        "下一步：检查地址与端口是否正确（当前目标 ${pairingEndpointOf(raw)}），稍后再点一次「配对」重试。"
}
