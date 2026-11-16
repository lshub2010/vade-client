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
import vade.ui.data.SourceConfig
import vade.ui.data.canBootstrapPairing
import vade.ui.data.firstRunPairingAttempt
import vade.ui.data.needsFirstRunPairing
import vade.ui.data.pairingEndpointOf
import vade.ui.data.rawSourceStrings
import vade.ui.skin.LocalSkin

/**
 * 首次运行引导卡：会话列表页顶部的一张卡 —— 不是弹窗、不遮会话。
 *
 * ## 出现时机（H1）
 * **未配对**就该出现，而不再是「真源且缺码」：默认启动（什么都不传）同样是首次运行，
 * 用户必须有一个能填地址/配对的地方（以前默认启动是纯夹具，卡永不出现，设置页的
 * 「管理此 Core」是灰的 —— 全应用没有任何入口，新用户 100% 卡死）。判据在
 * [needsFirstRunPairing]，卡片只读它。
 *
 * ## 卡里有什么
 *  - **host / port 输入**（默认 `127.0.0.1:8443`，与 core 的默认监听一致）—— 手机连桌面
 *    的 core、或 core 换了端口，都不需要重装应用或改 intent extras；
 *  - **一次性配对码**输入 + 「配对」按钮。
 *
 * 提交走**现有连接链**：[firstRunPairingAttempt] —— 语法仍是 `SourceConfig.parse`，构造
 * 仍是 `VadeClientConfig` + `BrowserPairing`，传输仍是 `KtorTransportFactory`（runner 每次重连
 * 的同一入口）；一次调用 = 恰好一次 `POST /ui/session` + 一次 WS 升级。成功后把已换到 cookie
 * 的 factory + 实际 endpoint 装进 [PairingRuntime]：卡片消失（本组件读桥）、入口处的源构造
 * 重读桥（`App`）、列表开始拉真数据 —— 没有第二条连接路径。
 *
 * ## H4 · 浏览器/Was**不发起任何请求**
 * Wasm 读不到 `Set-Cookie`，真发 POST 只会把用户的一次性码烧掉。所以
 * [canBootstrapPairing] 为 false 时卡片**不渲染输入框**，只给「请用桌面/手机配对」的
 * 可行动文案；`firstRunPairingAttempt` 内部还有第二道闸（防别的调用点绕过卡片）。
 *
 * **码的边界**：输入值只活在 [code] 这个组合内存 state 与 [firstRunPairingAttempt] 的函数
 * 参数里 —— 不写 DataStore / 不拼进 URL / 不进日志（守卫测试扫描持久化与 URL sink）。
 * 配对成功码即被消费，卡片随之出组合，state 一并消失。
 *
 * 每种失败都有文案与下一步（[pairingHeadline] / [pairingNextStep]），失败后输入框与
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
    // 出现条件：未配对 且 本次启动没有可用凭据。不满足就一个节点都不组合（空 item 不占
    // 行高，与 ListStatus.Connected → Unit 同一模式）。
    val rawNow = raw()
    if (PairingRuntime.pairedFactory != null || !needsFirstRunPairing(rawNow)) return

    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()

    // 输入值只在这里 —— 组合内存。不写盘、不进 URL、不进日志（见类 KDoc 与守卫测试）。
    var host by remember(rawNow.host) {
        mutableStateOf(rawNow.host?.trim()?.takeIf { it.isNotEmpty() } ?: SourceConfig.DEFAULT_HOST)
    }
    var port by remember(rawNow.port) {
        mutableStateOf(rawNow.port?.trim()?.takeIf { it.isNotEmpty() } ?: SourceConfig.DEFAULT_PORT.toString())
    }
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
            if (!canBootstrapPairing()) {
                // H4 · 浏览器：不发请求、不给输入框，只给可执行的下一步。
                UiText(
                    text = PAIRING_UNAVAILABLE_HERE,
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
                return@Column
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // skin.TextField 是 C-09 冻结组件，没有 modifier 形参 —— 由外层 Box 吃 weight。
                Box(modifier = Modifier.weight(2f)) {
                    skin.TextField(
                        value = host,
                        onValueChange = {
                            host = it
                            failure = null
                            failureDetail = null
                        },
                        placeholder = PAIRING_HOST_PLACEHOLDER,
                        enabled = !submitting,
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    skin.TextField(
                        value = port,
                        onValueChange = {
                            port = it
                            failure = null
                            failureDetail = null
                        },
                        placeholder = PAIRING_PORT_PLACEHOLDER,
                        enabled = !submitting,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                            // 端口先本地校验：越界/非数字不发网络请求，直接在卡里说清楚
                            // （P1-4 的同一条口径：配置错误不许把应用掀掉）。
                            val enteredHost = host.trim().ifEmpty { SourceConfig.DEFAULT_HOST }
                            val enteredPort = port.trim()
                            val portOk = enteredPort.isEmpty() ||
                                enteredPort.toIntOrNull()?.let { it in 1..65535 } == true
                            if (!portOk) {
                                failure = PairingFailureKind.BadConfig
                                failureDetail = PAIRING_PORT_INVALID
                                submitting = false
                                return@launch
                            }
                            try {
                                val submitted = RawSourceStrings(
                                    source = SourceConfig.REAL,
                                    host = enteredHost,
                                    port = enteredPort,
                                    pairCode = null,
                                )
                                when (val attempt = onSubmit(submitted, code)) {
                                    is PairingAttempt.Connected -> PairingRuntime.install(
                                        factory = attempt.factory,
                                        host = attempt.host,
                                        port = attempt.port,
                                    )
                                    is PairingAttempt.Rejected -> {
                                        failure = attempt.kind
                                        // 码被拒过（错码/过期码）→ 桥记住，卡不会因为别处的
                                        // 状态刷新而消失，用户随时能重试（H2）。
                                        if (attempt.kind == PairingFailureKind.BadCode) {
                                            PairingRuntime.noteCodeRejected()
                                        }
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
                        text = pairingHeadline(kind, submittedEndpoint(host, port)),
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
                        text = pairingNextStep(kind, submittedEndpoint(host, port)),
                        size = 11,
                        color = skin.colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 卡片里当前填的 endpoint 投影成 [RawSourceStrings]，只为了让 [pairingEndpointOf] 的
 * 文案说**用户填的**地址，而不是启动时的旧地址（零网络、零持久化）。
 */
private fun submittedEndpoint(host: String, port: String): RawSourceStrings = RawSourceStrings(
    source = SourceConfig.REAL,
    host = host.trim().ifEmpty { SourceConfig.DEFAULT_HOST },
    port = port.trim().ifEmpty { SourceConfig.DEFAULT_PORT.toString() },
)

/** 卡标题。 */
internal const val PAIRING_TITLE = "首次运行 · 连接你的 core"

/**
 * 「码在哪里找」一句（照 `docs/PAIRING.md` §二的口径）：core 启动后 stdout 里
 * `pair_code=` 那一行，形如「双字-6 位」，10 分钟有效、只能用一次。
 * 手机连电脑上 core 的那条路（`adb reverse`）也写在这里 —— Android 的明文策略只放行
 * 回环/模拟器别名（见 `network_security_config.xml`），把 core 映射到本机回环是
 * **唯一**不需要改安全配置的正路。
 */
internal const val PAIRING_WHERE =
    "配对码在 core 启动后的终端输出里：pair_code= 开头的那一行（形如 青松-XXXXXX，" +
        "10 分钟内有效、只能用一次）。手机连电脑上的 core：先跑 " +
        "`adb reverse tcp:8443 tcp:8443`，地址保持 127.0.0.1。"

/** 输入框占位。 */
internal const val PAIRING_PLACEHOLDER = "一次性配对码"

/** host / port 输入框占位（默认值本身就是值，占位只在清空后提示）。 */
internal const val PAIRING_HOST_PLACEHOLDER = "host（默认 127.0.0.1）"
internal const val PAIRING_PORT_PLACEHOLDER = "port（默认 8443）"

/** 提交按钮。 */
internal const val PAIRING_BUTTON_LABEL = "配对"

/** 尝试进行中的提示。 */
internal const val PAIRING_SUBMITTING = "正在配对…"

/** 端口本地校验失败的说明（不发网络请求）。 */
internal const val PAIRING_PORT_INVALID = "端口必须是 1..65535 的整数"

/** H4 · 浏览器/Wasм 的替代路径（不发起 POST，也不消耗码）。 */
internal const val PAIRING_UNAVAILABLE_HERE =
    "浏览器版读不到配对 cookie，所以这里不会发配对请求（你的一次性码不会被用掉）。" +
        "请用桌面应用或手机 App 连接 core；浏览器版等页面壳子接上配对后再开通。"

/**
 * 每种失败的标题 —— 互不可替换（测试逐条断言相等 + 两两不等）。
 * [PairingFailureKind.ServerDown] / [PairingFailureKind.BadConfig] 带 endpoint：
 * 用户要知道「该把 core 起在哪」/「填的地址为什么不合法」。
 */
internal fun pairingHeadline(kind: PairingFailureKind, raw: RawSourceStrings): String = when (kind) {
    PairingFailureKind.BadCode -> "配对码不对：可能已过期、已被用掉，或者抄错了"
    PairingFailureKind.ServerDown -> "core 没有在 ${pairingEndpointOf(raw)} 上运行（连接被拒绝）"
    PairingFailureKind.Network -> "网络不通：连不上 core"
    PairingFailureKind.BadConfig -> "地址填得不对：${pairingEndpointOf(raw)}"
    PairingFailureKind.NotAvailableHere -> "浏览器版不能直接配对"
}

/**
 * 每种失败的下一步 —— 每种都必须有可执行的动作。
 * [PairingFailureKind.ServerDown] 的启动命令照 `docs/PAIRING.md` §一口径
 * （Windows 分隔符 `;`，Linux/macOS `:`）。
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
    PairingFailureKind.BadConfig ->
        "下一步：端口填 1..65535 之间的整数（默认 8443）；地址留空即 127.0.0.1。" +
            "Android 的明文只放行本机回环/模拟器别名（10.0.2.2），连电脑上的 core 请先跑 " +
            "`adb reverse tcp:8443 tcp:8443` 再填 127.0.0.1。改完再点一次「配对」。"
    PairingFailureKind.NotAvailableHere ->
        "下一步：在桌面应用或手机 App 里填同一个地址与配对码完成配对；浏览器版随后用 " +
            "`?source=real&host=…&port=…` 打开即可（已配对的机器上 cookie 由浏览器自己发送）。"
}
