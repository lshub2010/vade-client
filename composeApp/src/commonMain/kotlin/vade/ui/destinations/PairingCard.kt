package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vade.ui.UiText
import vade.ui.data.PairingAttempt
import vade.ui.data.PairingFailureKind
import vade.ui.data.PairingRuntime
import vade.ui.data.PairingRuntimeState
import vade.ui.data.RawSourceStrings
import vade.ui.data.SessionRevocation
import vade.ui.data.SourceConfig
import vade.ui.data.canBootstrapPairing
import vade.ui.data.firstRunPairingAttempt
import vade.ui.data.needsFirstRunPairing
import vade.ui.data.pairingEndpointOf
import vade.ui.data.rawSourceStrings
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.LocalSkin

/**
 * 首次运行引导卡：会话列表页顶部的一张卡 —— 不是弹窗、不遮会话。
 *
 * ## 出现时机（H1）
 * **未配对**就该出现，而不再是「真源且缺码」：默认启动（什么都不传）同样是首次运行，
 * 用户必须有一个能填地址/配对的地方。判据在 [needsFirstRunPairing]，卡片只读它。
 *
 * ## 卡里有什么
 * 地址 / 端口 / 一次性配对码 —— 表单本体在 [PairingForm]（与设置页的「更换 core」
 * 共用同一份输入与失败文案，不是第二套）。
 *
 * 提交走**现有连接链**：[firstRunPairingAttempt] —— 语法仍是 `SourceConfig.parse`，构造
 * 仍是 `VadeClientConfig` + `BrowserPairing`，传输仍是 `KtorTransportFactory`（runner 每次重连
 * 的同一入口）；一次调用 = 恰好一次 `POST /ui/session` + 一次 WS 升级。成功后把已换到 cookie
 * 的 factory + 实际 endpoint 装进 [PairingRuntime]：卡片消失（本组件读桥）、入口处的源构造
 * 重读桥（`App`）、列表开始拉真数据 —— 没有第二条连接路径。
 *
 * ## H4 · 浏览器/Was**不发起任何请求**
 * Wasm 读不到 `Set-Cookie`，真发 POST 只会把用户的一次性码烧掉。所以
 * [canBootstrapPairing] 为 false 时表单**不渲染输入框**，只给「请用桌面/手机配对」的
 * 可行动文案；`firstRunPairingAttempt` 内部还有第二道闸（防别的调用点绕过卡片）。
 *
 * **码的边界**：输入值只活在 [PairingForm] 的组合内存 state 与 [firstRunPairingAttempt] 的
 * 函数参数里 —— 不写 DataStore / 不拼进 URL / 不进日志（守卫测试扫描持久化与 URL sink）。
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
            PairingForm(
                initialHost = rawNow.host?.trim()?.takeIf { it.isNotEmpty() } ?: SourceConfig.DEFAULT_HOST,
                initialPort = rawNow.port?.trim()?.takeIf { it.isNotEmpty() }
                    ?: SourceConfig.DEFAULT_PORT.toString(),
                submitLabel = PAIRING_BUTTON_LABEL,
                submittingLabel = PAIRING_SUBMITTING,
                // 卡片的注入缝仍然是「(原始配置, 码) -> 尝试」：表单只负责把输入装成
                // RawSourceStrings，连接链一条都没变（测试注入的替身收到的参数逐字不变）。
                onSubmit = { host, port, enteredCode ->
                    onSubmit(
                        RawSourceStrings(
                            source = SourceConfig.REAL,
                            host = host,
                            port = port,
                            pairCode = null,
                        ),
                        enteredCode,
                    )
                },
                onConnected = { connected ->
                    PairingRuntime.install(
                        factory = connected.factory,
                        host = connected.host,
                        port = connected.port,
                    )
                },
            )
        }
    }
}

/**
 * 地址 / 端口 / 一次性配对码 + 提交按钮 + 失败文案。**首配卡与「更换 core」共用**：
 * 两条路都走同一条连接链，输入校验、失败分类（[pairingHeadline] / [pairingNextStep]）
 * 与「码被拒过」的记忆只能有一份实现，否则两条路的说法迟早会分叉。
 *
 * 提交回调拿到的 host / port 已经过本地校验（端口必须是 1..65535 的整数；越界不发请求，
 * 直接在 [showFailure] 里给出 [PAIRING_PORT_INVALID]）。成功时调用 [onConnected]，
 * 由调用方决定「装进桥」还是「换成新 core」。
 */
@Composable
internal fun PairingForm(
    initialHost: String,
    initialPort: String,
    submitLabel: String,
    submittingLabel: String,
    /** 一次提交：host / port 已校验，code 原样（不 trim —— core 的码允许中间带分隔符）。 */
    onSubmit: suspend (host: String, port: String, code: String) -> PairingAttempt,
    /** 成功后的落地动作（首配 = install；换 core 已在 [onSubmit] 里装好，这里是空实现）。 */
    onConnected: (PairingAttempt.Connected) -> Unit = {},
) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()

    // 输入值只在这里 —— 组合内存。不写盘、不进 URL、不进日志（见 KDoc 与守卫测试）。
    var host by remember(initialHost) { mutableStateOf(initialHost) }
    var port by remember(initialPort) { mutableStateOf(initialPort) }
    var code by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<PairingFailureKind?>(null) }
    var failureDetail by remember { mutableStateOf<String?>(null) }

    if (!canBootstrapPairing()) {
        // H4 · 浏览器：不发请求、不给输入框，只给可执行的下一步。
        UiText(
            text = PAIRING_UNAVAILABLE_HERE,
            size = 11,
            color = skin.colors.onSurfaceVariant,
        )
        return
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
        Box(
            modifier = Modifier
                .weight(1f)
                // 三个输入框（host / port / 码）在语义树上都只表现为「可输入文本」，
                // 没有可区分的文案（前两个的占位永远不显示：值非空）——测试无法只
                // 凭 `hasSetTextAction()` 选中码框。给码框一个稳定的 tag，测试用
                // `hasAnyAncestor(hasTestTag(...))` 定位；生产行为零变化。
                .testTag(PAIRING_CODE_FIELD_TAG),
        ) {
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
            text = submitLabel,
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
                        when (val attempt = onSubmit(enteredHost, enteredPort, code)) {
                            is PairingAttempt.Connected -> onConnected(attempt)
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
        UiText(submittingLabel, size = 11, color = skin.colors.onSurfaceVariant)
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

/**
 * 设置页的「连接 / 更换 Core」子页（D91：设置项打开为全屏子页，不做弹层）。
 *
 * ## 为什么已配对也要能进来
 * 以前「管理此 Core」是一行灰的占位（`enabled = false`）—— 全应用**没有任何**入口能换
 * core：用户搬了机器、core 换了端口、或者想从 A 机切到 B 机，只能重装应用。现在这一行
 * 永远可点：未配对时它就是配对入口，已配对时它是「换一台」。
 *
 * ## 换 core 的顺序（[PairingRuntime.switchCore]）
 * 1. `DELETE /ui/session` → 旧 core（best-effort，超时/失败不阻塞）；
 * 2. 清本地旧配置与 cookie；
 * 3. 用新地址 + 新码连新 core，成功才装。
 *
 * 「旧凭证吊销失败」必须让用户看见：它在面板里显示，也写进 [CoreSwitchNotice]，换 core
 * 成功会重建 `UiState`（面板可能随之离开组合），设置页入口的副标题仍然读得到。
 *
 * ## 玻璃
 * 本页是**不透明**的全屏子页（与关于页同一形态），自身没有 GlassSurface，因此不注册
 * haze source —— 注册点白名单（`GlassStructureGuardTest`）保持「每页内容层一处」不变。
 */
@Composable
internal fun CoreSwitchPanel(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    val paired = PairingRuntime.state as? PairingRuntimeState.Paired
    val endpoint = PairingRuntime.pairedEndpoint
    var notice by remember { mutableStateOf(CoreSwitchNotice.text) }
    var pending by remember { mutableStateOf(false) }
    // 本次提交里旧 core 的吊销结果 —— [PairingForm] 的 onSubmit 写入、onConnected 读出。
    var revocation by remember { mutableStateOf<SessionRevocation?>(null) }
    // 提交过的目标地址。换 core 成功后桥会先退回 Unpaired，`paired` 随即变 null；
    // 若表单直接以 `paired?.host` 为初值，重组会把用户刚填的地址重置成默认值，
    // 失败文案指错主机、重试还会把同一个一次性码打到默认地址（可能把码烧掉）。
    var lastTarget by remember { mutableStateOf<Pair<String, String>?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(skin.colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp)
            .testTag(CORE_SWITCH_PANEL_TAG),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    // c2-nav：返回热区 ≥44dp（v2 §3 触摸目标），图标仍 20dp 居中。
                    .size(44.dp)
                    .clickable(onClickLabel = "返回") { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                VadeIcon(
                    VadeIcons.Back,
                    contentDescription = "返回",
                    size = 20.dp,
                    tint = skin.colors.onSurface,
                )
            }
            UiText(CORE_SWITCH_TITLE, size = 17, weight = FontWeight.SemiBold)
        }

        SectionLabel("当前连接")
        GroupCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UiText(
                    text = endpoint?.let { "已连接 $it" } ?: "还没有连接 core",
                    size = 14,
                    weight = FontWeight.Medium,
                )
                UiText(
                    text = CORE_SWITCH_HOW,
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
                if (endpoint != null) {
                    skin.Button(
                        text = CORE_SWITCH_DISCONNECT,
                        onClick = {
                            if (pending) return@Button
                            pending = true
                            notice = null
                            scope.launch {
                                val result = PairingRuntime.unpair()
                                val text = revocationNotice(result, CORE_SWITCH_DISCONNECTED)
                                CoreSwitchNotice.text = text
                                notice = text
                                pending = false
                            }
                        },
                        enabled = !pending,
                    )
                }
            }
        }

        SectionLabel("连接到另一台 core")
        GroupCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UiText(
                    text = "填入另一台 core 的地址与配对码。旧 core 上的本机凭证会先被吊销，再连新的。",
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
                PairingForm(
                    initialHost = lastTarget?.first ?: paired?.host ?: SourceConfig.DEFAULT_HOST,
                    initialPort = lastTarget?.second
                        ?: (paired?.port ?: SourceConfig.DEFAULT_PORT).toString(),
                    submitLabel = SWITCH_BUTTON_LABEL,
                    submittingLabel = SWITCH_SUBMITTING,
                    onSubmit = { host, enteredPort, enteredCode ->
                        // 快照本次目标：之后桥退回 Unpaired 也不让表单回落到默认地址。
                        lastTarget = host to enteredPort
                        revocation = null
                        val outcome = PairingRuntime.switchCore(
                            RawSourceStrings(
                                source = SourceConfig.REAL,
                                host = host,
                                port = enteredPort,
                                pairCode = null,
                            ),
                            enteredCode,
                        )
                        revocation = outcome.revocation
                        // 双失败：新 core 被拒 + 旧凭证吊销也失败时，onConnected 永远不会被调用，
                        // 若只在那里写 notice，「旧凭证吊销失败」会被静默吞掉。这里先落地。
                        if (outcome.attempt !is PairingAttempt.Connected &&
                            outcome.revocation is SessionRevocation.Failed
                        ) {
                            val text = revocationNotice(
                                outcome.revocation,
                                "旧 core 的会话可能仍然有效，请手动断开或等它过期",
                            )
                            CoreSwitchNotice.text = text
                            notice = text
                        }
                        outcome.attempt
                    },
                    onConnected = { connected ->
                        // switchCore 已经把新配置装进桥；这里只负责把「旧凭证吊销失败」
                        // 这类必须让用户知道的结果留在 UI 上（面板 + 设置页入口）。
                        val text = revocationNotice(
                            revocation,
                            "已切换到 ${connected.host}:${connected.port}",
                        )
                        CoreSwitchNotice.text = text
                        notice = text
                    },
                )
            }
        }

        notice?.let { text ->
            UiText(
                text = text,
                size = 12,
                weight = FontWeight.Medium,
                color = if (text.startsWith(REVOKE_FAILED_NOTICE)) {
                    skin.colors.error
                } else {
                    skin.colors.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 上一次「换 core / 断开」的结果提示 —— 进程内存（不落盘，与配对桥同一口径）。
 *
 * 为什么不能只放在 [CoreSwitchPanel] 里：换 core 成功会让 `App` 按新的
 * [PairingRuntimeState.Paired] 重建 `UiState`（App.kt 的 remember key），面板可能就此离开
 * 组合。提示存在这里，设置页入口那一行才敢说「上次切换：旧凭证吊销失败」。
 */
internal object CoreSwitchNotice {

    var text: String? by mutableStateOf(null)
}

/**
 * 吊销结果 → 一句用户话。[done] 是这次动作成功的部分（「已切换到 …」/「已断开」），
 * 失败时拼在前面 —— 「旧凭证吊销失败」这几个字是契约（UI 必须提示），测试按它断言。
 */
internal fun revocationNotice(revocation: SessionRevocation?, done: String): String = when (revocation) {
    is SessionRevocation.Failed -> {
        val detail = revocation.cause.message?.take(120)?.takeIf { it.isNotBlank() }
            ?: revocation.cause::class.simpleName
            ?: "未知原因"
        "$REVOKE_FAILED_NOTICE：$detail。$done"
    }
    // 成功 / 无需吊销（未配对、device 会话、本平台不能配对）都只有「已经做完」这一句。
    is SessionRevocation.Revoked, is SessionRevocation.NotNeeded, null -> done
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

/** 设置页入口（已配对时也可见，点开就是「换一台」）。 */
internal const val CORE_SWITCH_TITLE = "连接 / 更换 Core"

/** 换 core 子页的测试 tag（入口可点 → 子页真的打开，UI 测试用它定位）。 */
internal const val CORE_SWITCH_PANEL_TAG = "core-switch-panel"

/** 换 core 的提交按钮（与首配卡「配对」区分：这一步会先吊销旧凭证）。 */
internal const val SWITCH_BUTTON_LABEL = "配对并切换"

/** 换 core 进行中的提示（吊销 + 配对两段都在里面）。 */
internal const val SWITCH_SUBMITTING = "正在吊销旧凭证并连接新 core…"

/** 「吊销失败」提示的前缀 —— UI 契约：失败必须说这几个字。 */
internal const val REVOKE_FAILED_NOTICE = "旧凭证吊销失败"

/** 当前连接卡的说明：把顺序说清，用户才知道旧 core 会发生什么。 */
internal const val CORE_SWITCH_HOW =
    "换 core 时先向旧 core 发 DELETE /ui/session 吊销本机的 UI 凭证，再清掉本地配置，最后才连新的。"

/** 断开按钮。 */
internal const val CORE_SWITCH_DISCONNECT = "断开当前 core（吊销本机凭证）"

/** 断开成功的回执。 */
internal const val CORE_SWITCH_DISCONNECTED = "已断开当前 core。"

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

/** 配对码输入框的测试 tag（三个输入框里唯一可区分的一个；见组合处的注释）。 */
internal const val PAIRING_CODE_FIELD_TAG = "pairing-code-field"

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
