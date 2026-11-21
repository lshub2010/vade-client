package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vade.ui.UiText
import vade.ui.data.CoreSwitchOutcome
import vade.ui.data.PairingAttempt
import vade.ui.data.PairingFailureKind
import vade.ui.data.PairingRuntime
import vade.ui.data.PairingRuntimeState
import vade.ui.data.PairingStores
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
import vade.ui.shell.BackLayer
import vade.ui.skin.BtnStyle
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
    /**
     * 提交动作：默认 = [firstRunPairingAttempt]（现有连接链的一次尝试）。
     *
     * 第三个参数 = 「用户刚刚在**自签证书警告**上点过仍然连接」。只有它为真时
     * `allowInsecureTls` 才会打开（P0-6）；普通 HTTPS 一律走系统信任链 —— 合法 CA 的正常
     * 校验不会因为「开了 HTTPS 开关」而被跳过。
     */
    onSubmit: suspend (RawSourceStrings, String, Boolean) -> PairingAttempt = { rawStrings, enteredCode, insecure ->
        firstRunPairingAttempt(rawStrings, enteredCode, allowInsecureTls = insecure)
    },
) {
    // 出现条件：未配对 且 本次启动没有可用凭据。不满足就一个节点都不组合（空 item 不占
    // 行高，与 ListStatus.Connected → Unit 同一模式）。
    val rawNow = raw()
    if (PairingRuntime.pairedFactory != null || !needsFirstRunPairing(rawNow)) return

    val skin = LocalSkin.current
    // P0-1 · 「用已保存的配对重连」：core 只是暂时不可达时，凭据还在磁盘上，让用户
    // 一个按钮就重试 —— 而不是逼他去 core 的终端再捞一个一次性码。
    val retryScope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    val unpaired = PairingRuntime.state as? PairingRuntimeState.Unpaired
    val saved = PairingStores.cached
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
            if (unpaired?.credentialRejected == true) {
                // 上次的配对凭据被 core 拒了（401 / 握手失败 / core 重启）：已删除，必须
                // 重新配对。说清原因，别让用户以为是网络问题（P0-2）。
                UiText(
                    text = PAIRING_CREDENTIAL_REJECTED,
                    size = 11,
                    color = skin.colors.error,
                )
            }
            if (unpaired?.storedCredentialKept == true && saved != null) {
                UiText(
                    text = PAIRING_SAVED_KEPT,
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
                skin.Button(
                    text = if (retrying) PAIRING_SAVED_RETRYING else PAIRING_SAVED_RETRY,
                    onClick = {
                        if (retrying) return@Button
                        retrying = true
                        retryScope.launch {
                            runCatching { PairingRuntime.retryStoredCredential() }
                            retrying = false
                        }
                    },
                    enabled = !retrying,
                )
            }
            PairingForm(
                initialHost = rawNow.host?.trim()?.takeIf { it.isNotEmpty() } ?: SourceConfig.DEFAULT_HOST,
                initialPort = rawNow.port?.trim()?.takeIf { it.isNotEmpty() }
                    ?: SourceConfig.DEFAULT_PORT.toString(),
                submitLabel = PAIRING_BUTTON_LABEL,
                submittingLabel = PAIRING_SUBMITTING,
                // 卡片的注入缝仍然是「(原始配置, 码) -> 尝试」：表单只负责把输入装成
                // RawSourceStrings，连接链一条都没变（测试注入的替身收到的参数逐字不变）。
                // P0-6 · `tls` 只是协议；第四个参数才是「用户确认过自签证书」。
                onSubmit = { host, port, enteredCode, tls, insecure ->
                    onSubmit(
                        RawSourceStrings(
                            source = SourceConfig.REAL,
                            host = host,
                            port = port,
                            pairCode = null,
                            tls = tls,
                        ),
                        enteredCode,
                        insecure,
                    )
                },
                onConnected = { connected ->
                    // P0-1 · 配对成功 = 立刻落盘（{host, port, tls, trustMode} + cookie）；
                    // P0-6 · 信任档位原样进桥 —— 不许从 `tls` 推。
                    PairingRuntime.install(
                        factory = connected.factory,
                        host = connected.host,
                        port = connected.port,
                        tls = connected.tls,
                        trustMode = connected.trustMode,
                        persist = true,
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
 *
 * ## r5-tls · HTTPS 开关 + 自签证书警告（**证书失败之后**的闸门，P0-6）
 * 表单里有一个默认关的 HTTPS 开关（[PAIRING_TLS_LABEL]）。打开它之后点提交，表单**先按
 * 系统信任链试一次**（`TlsTrustMode.SystemChain`）：证书由正规 CA 签发就直接成功 ——
 * 不再有「只要 HTTPS 就 trust-all」这一条。
 *
 * 只有**证书不被系统信任**（自签 / 名字不匹配 / 链不完整）时才弹 [TlsWarningDialog]：
 * 自签证书无法验证 core 的身份、可能被中间人，只有「仍然连接」才会以
 * `TlsTrustMode.InsecureConfirmed` **重试一次**；「取消」什么都不发、也**不烧**配对码
 * （码原样留在输入框里，第一次尝试在 TLS 握手就失败了，请求根本没发出去）。
 * 首配卡与「更换 core」共用本表单，所以两条路自动遵守同一条闸门 —— 没有第二份确认逻辑。
 *
 * **P1-4 · 确认绑定到 endpoint**：证书失败那一刻把 `(host, port, tls, code)` 快照进弹层，
 * 「仍然连接」重试的是这份快照；弹层显示期间表单的**任何**变更（地址 / 端口 / 码 / HTTPS
 * 开关）都会把弹层撤掉。否则「对 A 的证书失败 → 把地址改成 B → 点确认」会把 trust-all
 * 打到 B，或者关掉 HTTPS 后静默退回明文提交。
 *
 * ## 提交态与作用域都**可从宿主注入**（R3）
 * 「换 core」的宿主要拿两样东西，所以它们从表单内部提升成形参、默认值保持旧行为：
 *  - [submittingState] —— 宿主据它禁用顶栏返回与系统返回（提交中离开 = 停在「旧已吊销、
 *    新未装」的中间态）；
 *  - [submitScope] —— 宿主给一个**不挂在组合上**的作用域，面板先离开组合也不取消提交。
 */
@Composable
internal fun PairingForm(
    initialHost: String,
    initialPort: String,
    submitLabel: String,
    submittingLabel: String,
    /** 一次提交：host / port 已校验，code 原样（不 trim —— core 的码允许中间带分隔符）。 */
    onSubmit: suspend (host: String, port: String, code: String, tls: Boolean, insecure: Boolean) -> PairingAttempt,
    /**
     * P0-6 · 「用户刚刚对**自签证书**点过仍然连接」的那一次重试。
     *
     * 只有证书失败的确认弹层会调用它；普通提交永远走 [onSubmit]（= SystemChain，合法 CA
     * 正常校验）。`null`（默认）= 退回 [onSubmit]（测试替身 / 不支持二次确认的调用点）。
     */
    onSubmitSelfSigned: (suspend (host: String, port: String, code: String, tls: Boolean) -> PairingAttempt)? = null,
    /** 成功后的落地动作（首配 = install；换 core 已在 [onSubmit] 里装好，这里是空实现）。 */
    onConnected: (PairingAttempt.Connected) -> Unit = {},
    /**
     * 提交中的真源。宿主传自己 held 的一份来观察；不传 = 表单自持（首配卡就是这样，
     * 行为与提升之前逐字节相同）。「换 core」传的是进程级的 [CoreSwitchNotice.submitting]：
     * 面板会被切走 / 重建，而提交仍在跑 —— 状态不能跟着面板一起归零（P2）。
     */
    submittingState: MutableState<Boolean> = remember { mutableStateOf(false) },
    /** 初始协议状态：生产恒为 false（每次打开都必须重新做一次决定）；测试用它直接进 HTTPS 路径。 */
    initialTls: Boolean = false,
    /**
     * 跑 [onSubmit] 的作用域。默认 = 组合作用域（页面在，提交就在）；「换 core」宿主要传一个
     * 长生命周期的，否则面板一离开组合提交就被取消（见 [coreSwitchScope]）。
     */
    submitScope: CoroutineScope = rememberCoroutineScope(),
) {
    val skin = LocalSkin.current
    var submitting by submittingState

    // 输入值只在这里 —— 组合内存。不写盘、不进 URL、不进日志（见 KDoc 与守卫测试）。
    var host by remember(initialHost) { mutableStateOf(initialHost) }
    var port by remember(initialPort) { mutableStateOf(initialPort) }
    var code by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<PairingFailureKind?>(null) }
    var failureDetail by remember { mutableStateOf<String?>(null) }
    // r5-tls · HTTPS 开关（默认关）与「自签证书警告弹层是否挂着」。两者都只活在组合内存，
    // 不进任何持久化字段 —— 每次打开面板都从「明文」开始，用户必须重新做一次决定。
    var tls by remember { mutableStateOf(initialTls) }
    /**
     * P1-4 · 非 null = 正在为**这一个**目标显示自签确认。
     *
     * 以前这里只是一个 Boolean，点「仍然连接」时才去读实时表单 —— 用户可以对着 A 的证书
     * 失败、把地址改成 B、再确认，于是 trust-all 打到 B；或者关掉 HTTPS 开关再确认，
     * `allowInsecureTls && submittedTls` 为假，一次性码静默走**明文**。现在确认绑定到
     * 失败那一刻的 `(host, port, tls, code)` 快照，并且表单一旦变更就作废（见
     * [invalidateTlsWarning]）。
     */
    var tlsWarning by remember { mutableStateOf<PairingTarget?>(null) }
    /** 上一次提交的目标：失败文案指向它，而不是用户之后改成的新地址。 */
    var lastTarget by remember { mutableStateOf<PairingTarget?>(null) }

    /**
     * 表单被编辑 → 警告所针对的那个目标已经不存在了，确认必须作废。
     *
     * 这是「确认的是 A、trust-all 给了 B」的唯一防线：弹层不是真模态（同包其它弹层都是
     * in-tree 的 `Column`），底下的 host / port / 码 / HTTPS 开关仍然可编辑。
     */
    fun invalidateTlsWarning() {
        if (tlsWarning != null) tlsWarning = null
    }

    if (!canBootstrapPairing()) {
        // H4 · 浏览器：不发请求、不给输入框，只给可执行的下一步。
        UiText(
            text = PAIRING_UNAVAILABLE_HERE,
            size = 11,
            color = skin.colors.onSurfaceVariant,
        )
        return
    }

    /**
     * r5-tls · 真正的一次提交（本地校验 → [onSubmit]）。
     *
     * [allowInsecureTls] 是**唯一的** trust-all 入口：只有证书失败的确认弹层会以 `true`
     * 调它（并因此走 [onSubmitSelfSigned]）。提交按钮永远传 `false` —— 普通 HTTPS 先按
     * 系统信任链试一次，合法 CA 直接就成功了（P0-6）。
     *
     * [retryOf] 非 null = 这是对某个**快照目标**的确认重试：地址 / 协议 / 码一律用快照，
     * 不再读实时表单（P1-4）。普通提交传 `null`，由当前表单做一次快照。
     */
    fun submitNow(allowInsecureTls: Boolean, retryOf: PairingTarget? = null) {
        if (submitting) return
        val target = retryOf ?: PairingTarget(
            host = host.trim().ifEmpty { SourceConfig.DEFAULT_HOST },
            port = port.trim(),
            code = code,
            tls = tls,
        )
        failure = null
        failureDetail = null
        lastTarget = target
        // 端口先本地校验：越界/非数字不发网络请求，直接在卡里说清楚
        // （P1-4 的同一条口径：配置错误不许把应用掀掉）。
        val portOk = target.port.isEmpty() ||
            target.port.toIntOrNull()?.let { it in 1..65535 } == true
        if (!portOk) {
            failure = PairingFailureKind.BadConfig
            failureDetail = PAIRING_PORT_INVALID
            return
        }
        submitting = true
        submitScope.launch {
            try {
                val attempt = if (allowInsecureTls && target.tls) {
                    onSubmitSelfSigned?.invoke(target.host, target.port, target.code, target.tls)
                        ?: onSubmit(target.host, target.port, target.code, target.tls, true)
                } else {
                    onSubmit(target.host, target.port, target.code, target.tls, false)
                }
                when (attempt) {
                    is PairingAttempt.Connected -> onConnected(attempt)
                    is PairingAttempt.Rejected -> {
                        if (attempt.kind == PairingFailureKind.CertificateUntrusted &&
                            target.tls &&
                            !allowInsecureTls
                        ) {
                            // P0-6 · 证书不被系统信任。这一次**一个字节的请求都还没发出去**
                            // （TLS 在 HTTP 之前失败，一次性码没被烧）→ 把「是否信任这张
                            // 证书」交给用户；确认后以 InsecureConfirmed 重试**同一个目标**。
                            tlsWarning = target
                        } else {
                            failure = attempt.kind
                            // 码被拒过（错码/过期码）→ 桥记住，卡不会因为别处的状态刷新而
                            // 消失，用户随时能重试（H2）。
                            if (attempt.kind == PairingFailureKind.BadCode) {
                                PairingRuntime.noteCodeRejected()
                            }
                            // 传输原话，截短到卡里能读的长度（原文已 ≤300 字符）。
                            failureDetail = attempt.cause.message?.take(160)
                        }
                    }
                }
            } finally {
                submitting = false
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // skin.TextField 是 C-09 冻结组件，没有 modifier 形参 —— 由外层 Box 吃 weight。
        // r3-a11y · host / port 的值永远非空（初值就是默认地址），占位永不显示 —— 这两个框
        // 此前在语义树上只是「可输入文本」，没有任何名字。label 给它们可视小字 + 可访问名。
        Box(modifier = Modifier.weight(2f).testTag(PAIRING_HOST_FIELD_TAG)) {
            skin.TextField(
                value = host,
                onValueChange = {
                    host = it
                    failure = null
                    failureDetail = null
                    // P1-4 · 地址变了，正在显示的自签确认所针对的目标就不存在了。
                    invalidateTlsWarning()
                },
                placeholder = PAIRING_HOST_PLACEHOLDER,
                enabled = !submitting,
                label = PAIRING_HOST_LABEL,
            )
        }
        Box(modifier = Modifier.weight(1f).testTag(PAIRING_PORT_FIELD_TAG)) {
            skin.TextField(
                value = port,
                onValueChange = {
                    port = it
                    failure = null
                    failureDetail = null
                    invalidateTlsWarning()
                },
                placeholder = PAIRING_PORT_PLACEHOLDER,
                enabled = !submitting,
                label = PAIRING_PORT_LABEL,
            )
        }
    }
    // r5-tls · HTTPS 开关。默认关 = 今天的明文路径。整行可点（44dp 触摸目标 + 读屏把
    // 「HTTPS（自签证书）」念成一个开关），`skin.Switch` 自己那份 onCheckedChange 管直接
    // 点开关本体 —— 两个 clickable 是父子关系，命中测试只会走内层那个，不会翻两次。
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = tls,
                enabled = !submitting,
                role = Role.Switch,
                onValueChange = {
                    if (tls != it) {
                        tls = it
                        // P1-4 · 协议变了：A 的自签确认不许静默变成一次明文提交（或反之）。
                        invalidateTlsWarning()
                    }
                },
            )
            .testTag(PAIRING_TLS_SWITCH_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        UiText(
            text = PAIRING_TLS_LABEL,
            modifier = Modifier.weight(1f),
            size = 12,
            color = if (tls) skin.colors.onSurface else skin.colors.onSurfaceVariant,
        )
        skin.Switch(
            checked = tls,
            onCheckedChange = {
                if (tls != it) {
                    tls = it
                    invalidateTlsWarning()
                }
            },
            enabled = !submitting,
        )
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
                    // P1-4 · 换了一个码 = 另一次尝试；上一张证书的确认不沿用。
                    invalidateTlsWarning()
                },
                placeholder = PAIRING_PLACEHOLDER,
                enabled = !submitting,
            )
        }
        skin.Button(
            text = submitLabel,
            onClick = {
                if (submitting || code.isBlank()) return@Button
                // P0-6 · 普通提交 = SystemChain：开了 HTTPS 也**先按系统信任链试一次**，
                // 合法 CA 直接就成功，trust-all 一个字节都不打开。只有证书不被信任时
                // 才会挂确认弹层（见 submitNow 的 CertificateUntrusted 分支）。
                submitNow(allowInsecureTls = false)
            },
            enabled = !submitting && code.isNotBlank(),
        )
    }
    if (submitting) {
        UiText(submittingLabel, size = 11, color = skin.colors.onSurfaceVariant)
    }
    // The warning is about HTTPS; turning TLS off makes it moot even if the
    // form's invalidation callback is missed.
    if (tls) tlsWarning?.let { pending ->
        // 唯一以 InsecureConfirmed 重试的入口。取消 = 什么都不发、码不动（码没被烧）。
        // P1-4 · 确认重试的是**快照**（弹层显示的那一个 host/port/协议/码），不是实时表单。
        TlsWarningDialog(
            endpoint = pending.endpoint,
            onCancel = { tlsWarning = null },
            onConfirm = {
                tlsWarning = null
                submitNow(allowInsecureTls = true, retryOf = pending)
            },
        )
    }
    failure?.let { kind ->
        // 失败文案必须指向**提交过**的地址：用户之后改了表单也不该让「core 没有在 X 上运行」
        // 指到一个从未被尝试过的 X。
        val shown = lastTarget ?: PairingTarget(host, port, code, tls)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UiText(
                text = pairingHeadline(kind, submittedEndpoint(shown.host, shown.port)),
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
                text = pairingNextStep(kind, submittedEndpoint(shown.host, shown.port)),
                size = 11,
                color = skin.colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * P1-4 · 一次提交的不可变目标。
 *
 * 自签证书的确认必须绑定到「哪台 core、哪个端口、什么协议、哪个一次性码」：弹层不是真
 * 模态，它显示期间表单仍可编辑。`submitNow` 在**点击提交那一刻**做快照，确认重试只读
 * 这份快照；表单任何变更都会作废弹层（`invalidateTlsWarning`）。
 */
internal data class PairingTarget(
    val host: String,
    val port: String,
    val code: String,
    val tls: Boolean,
) {
    /** 展示用 endpoint：端口为空 = 默认端口（与 [SourceConfig.parse] 同一条规则）。 */
    val endpoint: String
        get() = "$host:${port.ifBlank { SourceConfig.DEFAULT_PORT.toString() }}"
}

/**
 * r5-tls · 自签证书的**风险确认弹层** —— 提交前唯一的闸门（[PairingForm] 在系统信任链
 * 拒绝之后才挂它，未确认不调用 `onSubmitSelfSigned`，因此一个请求都不发）。
 *
 * ## 为什么不是 `material3.AlertDialog`
 *  1. C-09 禁止 `destinations/` / `shell/` import material3（`MaterialImportGuardTest`
 *     逐文件扫描），业务页只能走 `Skin`；
 *  2. material3 的 AlertDialog 在 desktop 是**独立 AWT 窗口**，`runComposeUiTest` 的语义树
 *     看不见它 —— 「确认前零请求 / 取消零请求 / 确认后恰好一次」这三条就没法测。
 *
 * 所以这里是设置页弹层同一套 §9 形态的**内联卡片**（圆角 `sheet`、发丝线、标题 + 正文 +
 * 两个按钮）。它**不是**模态：底下的表单仍然可编辑、可再次提交 —— 因此它的含义必须
 * 精确到**这一个 endpoint**（[endpoint] 由调用方在证书失败那一刻快照传入），而调用方
 * 在表单任何变更时都会把它撤掉（`invalidateTlsWarning`）。「仍然连接」
 * ([PAIRING_TLS_CONFIRM_LABEL]) 重试的也是同一个快照目标。
 *
 * 文案只说事实：自签证书无法验证 core 的身份、可能被中间人、仅在你信任此主机时继续。
 */
@Composable
internal fun TlsWarningDialog(
    /** 证书失败那一刻的 `host:port`（快照，不是实时表单）。 */
    endpoint: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.sheet)
    Column(
        modifier = Modifier
                .widthIn(max = 380.dp)
                .clip(shape)
                .background(skin.colors.surface)
                .border(1.dp, skin.colors.hairline, shape)
                .padding(18.dp)
                .testTag(PAIRING_TLS_DIALOG_TAG),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UiText(
                text = PAIRING_TLS_WARNING_TITLE,
                size = 15,
                weight = FontWeight.SemiBold,
                color = skin.colors.error,
            )
            // P1-4 · 确认对象写出来：用户看到的必须是「要信任哪台主机」，而不是一张空白授权。
            UiText(
                text = tlsWarningTargetLine(endpoint),
                size = 12,
                weight = FontWeight.Medium,
                color = skin.colors.onSurface,
                modifier = Modifier.testTag(PAIRING_TLS_TARGET_TAG),
            )
            UiText(
                text = PAIRING_TLS_WARNING_BODY,
                size = 12,
                color = skin.colors.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 取消是**默认**动作（次要样式）：一个手滑的点击不该把 trust-all 打开。
                skin.Button(PAIRING_TLS_CANCEL_LABEL, onCancel, style = BtnStyle.Secondary)
            skin.Button(PAIRING_TLS_CONFIRM_LABEL, onConfirm, style = BtnStyle.Primary)
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
 * ## 换 core 的顺序（[PairingRuntime.switchCore]，P0-4）
 * 1. **先**用新地址 + 新码连新 core（成功才拿到新 cookie）；失败 = 什么都不动，旧连接还在；
 * 2. 新凭据到手**才** `DELETE /ui/session` → 旧 core（best-effort，超时/失败不阻塞）；
 * 3. 一次性切换桥到新 core 并落盘，旧 factory 退休（不会被重连复活）。
 *
 * **结果一律回执**（[CoreSwitchNotice]）：成功、单失败（新 core 被拒 + 旧凭证已吊销 /
 * 无需吊销）、双失败都写一句话。换 core 会重建 `UiState`、用户也可能在提交中切走 tab
 * （`AppShell` 只组合当前 tab）—— 面板是临时组合，回执必须活在它外面，设置页入口那一行
 * 与重新打开的面板才看得见「旧凭证吊销失败 / 切换没有完成：… / 已切换到 …」。
 *
 * ## R3 · 提交中不许被「返回」取消
 * 换 core 是三段式（连新 core → 成功后才吊销旧 → 切换并落盘）。旧实现把 `submitting` 关在 [PairingForm]
 * 里、把提交跑在 `rememberCoroutineScope()` 上，于是面板一离开组合（成功的重建、或用户按
 * 返回）协程就被取消 —— 用户停在「旧已吊销、新未装、零提示」。现在三处一起改：
 *  1. [submittingState] 提升到本面板：顶栏返回在提交中**不挂 clickable**（不是
 *     `enabled=false`：禁用态仍是一个吃掉命中的节点），给读屏也不留「可点」的假语义；
 *  2. 系统返回由本面板再压一层 [BackLayer]，提交中以 no-op 消费掉（[busy]）；
 *  3. 提交跑在 [coreSwitchScope]（不挂在组合上），结果一律回执 [CoreSwitchNotice] ——
 *     面板真的先走了，设置页入口那一行仍然看得见「旧凭证吊销失败 / 切换没有完成 / 已切换到 …」。
 *
 * ## P2 · 「在途」也必须活过面板
 * 提交态同样不归面板所有：真源是 [CoreSwitchNotice.submitting]（与回执同一层）。面板被切走
 * 再打开时读到的是同一个布尔 —— 仍显示「正在…」、输入框/按钮保持禁用、返回继续被 [busy]
 * 拦住；不会出现「重建后 busy=false 又发一次」的并发切换（那会再吊销一次旧 core、再烧一个
 * 一次性码）。
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
    /**
     * 提交动作（默认 = 真的换 core）。形状与 [PairingRuntime.switchCore] 一致，只收它需要
     * 的那两个参数 —— 注入缝只给测试用，生产默认逐字节不变。
     *
     * P0-6 · 普通提交 = `SystemChain`（合法 CA 正常校验）。trust-all 只在
     * [onSwitchSelfSigned] 里发生，而它唯一的调用点是「证书不被信任 → 用户确认」那一次重试。
     */
    onSwitch: suspend (RawSourceStrings, String) -> CoreSwitchOutcome = { raw, code ->
        PairingRuntime.switchCore(raw, code, allowInsecureTls = false)
    },
    /**
     * P0-6 · 「用户确认过自签证书」的那一次换 core（`InsecureConfirmed`）。
     *
     * 默认就是真实的 `switchCore(..., allowInsecureTls = true)`；注入缝留给测试。生产里
     * 只有 [TlsWarningDialog] 的「仍然连接」能走到这里 —— 普通提交永远不打开 trust-all。
     */
    onSwitchSelfSigned: suspend (RawSourceStrings, String) -> CoreSwitchOutcome = { raw, code ->
        PairingRuntime.switchCore(raw, code, allowInsecureTls = true)
    },
    /** 断开动作（默认 = [PairingRuntime.unpair]，同样是最小形状的注入缝）。 */
    onDisconnect: suspend () -> SessionRevocation = { PairingRuntime.unpair() },
) {
    val skin = LocalSkin.current
    val paired = PairingRuntime.state as? PairingRuntimeState.Paired
    val endpoint = PairingRuntime.pairedEndpoint
    // 回执只有一份真源：[CoreSwitchNotice.text]（进程内存）。面板**不**再复制一份
    // `remember { mutableStateOf(...) }` —— 复制品既会让「面板先销毁、结果后落地」丢提示，
    // 也会让重新打开的面板看不到仍在跑的那次提交的结果。读的是 compose state，落地即刷新。
    val notice = CoreSwitchNotice.text
    // 断开在途（与 submitting 分开：一个是换 core，一个是只断开）。
    var pending by remember { mutableStateOf(false) }
    // **提交态提升到面板之外**（P2）：面板销毁重开读到同一个布尔，见 KDoc 的 P2 一节。
    val submittingState = CoreSwitchNotice.submitting
    val submitting = submittingState.value
    // 提交过的目标地址。换 core 成功后桥会先退回 Unpaired，`paired` 随即变 null；
    // 若表单直接以 `paired?.host` 为初值，重组会把用户刚填的地址重置成默认值，
    // 失败文案指错主机、重试还会把同一个一次性码打到默认地址（可能把码烧掉）。
    var lastTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 「断开当前 core」的两步确认：同卡内的闸门，不是弹层。
    val disconnectConfirm = remember { TwoStepConfirm() }
    val busy = submitting || pending
    // 回执的视口锚（[BringIntoViewRequester]）：见下面那一块的注释。
    val noticeViewport = remember { BringIntoViewRequester() }

    // 两步确认的窗口：窗口内不再点一次就自动回退（3 秒）。
    LaunchedEffect(disconnectConfirm.armed) {
        if (!disconnectConfirm.armed) return@LaunchedEffect
        delay(disconnectConfirm.window)
        disconnectConfirm.expire()
    }

    // 换 core / 断开在途：**系统返回也关不掉本页**。本层压在宿主那一层之上（同一 Box 里
    // 后组成的在上），把返回消费成 no-op —— 否则一次返回就让提交协程随组合离开被取消，
    // 留下「旧已吊销、新未装、零提示」。
    BackLayer(enabled = busy, onBack = {})

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
                    .then(
                        // 提交中**完全不挂 clickable**（不是 `enabled = false`：那仍是一个
                        // 命中测试节点，会从兄弟节点手里吃掉点击，语义树上还留着 OnClick）。
                        if (busy) {
                            Modifier
                        } else {
                            Modifier.clickable(onClickLabel = "返回") { onBack() }
                        },
                    ),
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
                        // 两步确认：同一个按钮换文案，第一次点只开闸门（不执行、不发请求）。
                        text = if (disconnectConfirm.armed) {
                            CORE_SWITCH_DISCONNECT_CONFIRM
                        } else {
                            CORE_SWITCH_DISCONNECT
                        },
                        onClick = {
                            if (busy) return@Button
                            if (!disconnectConfirm.tap()) return@Button
                            CoreSwitchNotice.text = null
                            pending = true
                            coreSwitchScope.launch {
                                val result = onDisconnect()
                                CoreSwitchNotice.text = revocationNotice(result, CORE_SWITCH_DISCONNECTED)
                                pending = false
                            }
                        },
                        enabled = !busy,
                    )
                    if (disconnectConfirm.armed) {
                        UiText(
                            text = CORE_SWITCH_DISCONNECT_HINT,
                            size = 11,
                            color = skin.colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SectionLabel("连接到另一台 core")
        // R3 · 回执（「旧凭证吊销失败」/「切换没有完成」/「已切换到 …」）放在**表单正上方**。
        // 旧位置是页尾：双失败时表单里的失败文案在下面、吊销提示隔着一整张卡在最后，
        // 用户只看得见一条 —— 「双失败必须可见」在长屏上就是空话。两条保障：
        //  - liveRegion = Assertive：这是用户动作的**结果**，读屏必须立刻播报（polite 会
        //    排在页面其它播报之后）；
        //  - bringIntoView：面板是滚动列，提示与失败文案可能分处视口上下两半，谁都不许
        //    停在屏外（真的滚不动也不阻塞提示本身 —— 见下面的 catch）。
        notice?.let { text ->
            LaunchedEffect(text) {
                try {
                    noticeViewport.bringIntoView()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // 没有可接的滚动容器（孤立组合 / 预览）：提示本身照常渲染。
                }
            }
            UiText(
                text = text,
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(noticeViewport)
                    .semantics { liveRegion = LiveRegionMode.Assertive }
                    .testTag(CORE_SWITCH_NOTICE_TAG),
                size = 12,
                weight = FontWeight.Medium,
                color = if (text.startsWith(REVOKE_FAILED_NOTICE)) {
                    skin.colors.error
                } else {
                    skin.colors.onSurfaceVariant
                },
            )
        }
        GroupCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UiText(
                    text = "填入另一台 core 的地址与配对码。先连上新 core（成功才拿到新凭证），之后才吊销旧 core 上的本机凭证；新 core 连不上时旧连接原样保留。",
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
                PairingForm(
                    initialHost = lastTarget?.first ?: paired?.host ?: SourceConfig.DEFAULT_HOST,
                    initialPort = lastTarget?.second
                        ?: (paired?.port ?: SourceConfig.DEFAULT_PORT).toString(),
                    submitLabel = SWITCH_BUTTON_LABEL,
                    submittingLabel = SWITCH_SUBMITTING,
                    submittingState = submittingState,
                    submitScope = coreSwitchScope,
                    onSubmit = { host, enteredPort, enteredCode, tls, _ ->
                        // 快照本次目标：之后桥退回 Unpaired 也不让表单回落到默认地址。
                        lastTarget = host to enteredPort
                        val outcome = onSwitch(
                            RawSourceStrings(
                                source = SourceConfig.REAL,
                                host = host,
                                port = enteredPort,
                                pairCode = null,
                                // P0-6 · `tls` 只是协议；trust-all 的确认走下面的
                                // onSubmitSelfSigned 那一条（证书失败之后才可能发生）。
                                tls = tls,
                            ),
                            enteredCode,
                        )
                        // P1 · **所有**终态都在这里回执（成功 / 单失败 / 双失败）：
                        // [PairingForm] 的 onConnected 只在成功时被调用，失败文案又只活在
                        // 表单内存里 —— 提交中切走 tab 或面板先离开组合时，只写那一支就是零提示。
                        // 回执写进程级的 [CoreSwitchNotice]，重新打开的面板 / 设置页都读得到。
                        CoreSwitchNotice.text = coreSwitchReceipt(
                            revocation = outcome.revocation,
                            attempt = outcome.attempt,
                            target = submittedEndpoint(host, enteredPort),
                        )
                        outcome.attempt
                    },
                    onSubmitSelfSigned = { host, enteredPort, enteredCode, tls ->
                        lastTarget = host to enteredPort
                        val outcome = onSwitchSelfSigned(
                            RawSourceStrings(
                                source = SourceConfig.REAL,
                                host = host,
                                port = enteredPort,
                                pairCode = null,
                                tls = tls,
                            ),
                            enteredCode,
                        )
                        CoreSwitchNotice.text = coreSwitchReceipt(
                            revocation = outcome.revocation,
                            attempt = outcome.attempt,
                            target = submittedEndpoint(host, enteredPort),
                        )
                        outcome.attempt
                    },
                )
            }
        }
    }
}

/**
 * 「换 core / 断开」的提交作用域：**不挂在组合上**。
 *
 * `rememberCoroutineScope()` 会随面板离开组合被取消，而换 core 是三段式
 * （连新 core → 成功后才吊销旧 → 切换并落盘）。面板先走一步（成功会重建 `UiState`；用户也可能按了返回），
 * 协程就停在「旧已吊销、新未装」的中间态，回执一句都写不出来 —— 用户什么都看不到。
 * 这个作用域活到进程结束；结果一律写进 [CoreSwitchNotice]，设置页入口那一行读得到。
 *
 * `Dispatchers.Unconfined` 与 `ChatState` 的出网作用域同一口径：提交是挂起式的网络往返，
 * 不会在调用线程上阻塞。
 */
private val coreSwitchScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

/**
 * 同一张卡里的「再点一次才执行」闸门（不引入弹层）。
 *
 * 第一次 [tap] 只把闸门打开（按钮换文案 + 一句说明），[window] 之内的第二次才返回 true
 * （调用方执行动作）；窗口到期由组合里的 `LaunchedEffect` + `delay` 调 [expire] 回退。
 *
 * 抽成纯逻辑是为了让「第一次不执行 / 第二次执行 / 超时回退」三条都能直接推，
 * 不必为了一个 3 秒的等待去驱动组合时钟。
 */
internal class TwoStepConfirm(private val windowMs: Long = DISCONNECT_CONFIRM_WINDOW_MS) {

    /** 闸门是否已打开（按钮文案与 `LaunchedEffect` 的键都读它）。 */
    var armed: Boolean by mutableStateOf(false)
        private set

    /** 窗口长度（组合里的 `delay` 用它）。 */
    val window: Long get() = windowMs

    /** @return true = 窗口内的第二次点击，调用方执行动作（闸门随即复位）。 */
    fun tap(): Boolean {
        if (armed) {
            armed = false
            return true
        }
        armed = true
        return false
    }

    /** 窗口到期：自动回退，下一次点击重新从头确认。 */
    fun expire() {
        armed = false
    }
}

/**
 * 「换 core / 断开」的**跨面板 UI 状态**：上一次的结果回执 + 这一次是否在途。
 *
 * 为什么不能只放在 [CoreSwitchPanel] 里：换 core 成功会让 `App` 按新的
 * [PairingRuntimeState.Paired] 重建 `UiState`（App.kt 的 remember key），面板可能就此离开
 * 组合；用户也可能在提交中切走 tab（`AppShell` 只组合当前 tab）。面板是临时组合，
 * `remember` 的一切都归零 —— 这两样必须活过它：
 *  - [text]：设置页入口那一行才敢说「上次切换：旧凭证吊销失败 / 切换没有完成 / 已切换到 …」，
 *    重新打开的面板也读同一句；
 *  - [submitting]：重建的面板仍显示「正在…」、输入框/按钮保持禁用、返回继续被拦，
 *    不允许并发第二次 `switchCore`（那会再吊销一次旧 core、再烧一个一次性码）。
 */
internal object CoreSwitchNotice {

    var text: String? by mutableStateOf(null)

    /** 换 core 在途的真源；[PairingForm] 的 `submittingState` 读写的就是这个实例。 */
    val submitting: MutableState<Boolean> = mutableStateOf(false)
}

/**
 * 换 core 的**终态回执**（P1）：任何结局都必须留下这一句。
 *
 * 旧实现只在「成功」与「双失败」两支写 [CoreSwitchNotice] —— 单失败（新 core 被拒、旧凭证
 * 已经吊销或本来就无需吊销）时文案只活在面板内存。用户在提交中切走 tab（`AppShell` 只组合
 * 当前 tab）或面板因重建先离开组合，回来就只剩一个「未连接」的配对入口，没有任何解释。
 * 现在四支全覆盖：
 *  - [PairingAttempt.Connected]：`已切换到 host:port`（吊销失败时前面拼「旧凭证吊销失败：…」）；
 *  - [PairingAttempt.Rejected] + [SessionRevocation.Failed]：双失败，保留原句
 *    「旧 core 的会话可能仍然有效…」；
 *  - [PairingAttempt.Rejected] + [SessionRevocation.Revoked]：旧凭证确实吊销了，本机已断开，
 *    但新 core 没连上；
 *  - [PairingAttempt.Rejected] + [SessionRevocation.NotNeeded]：本地就没发吊销（配置错误等），
 *    旧配置原样，新 core 没连上。
 *
 * [target] 只用于 [pairingHeadline] 的 endpoint 文案（用户填的地址，不是启动时的旧地址）。
 */
internal fun coreSwitchReceipt(
    revocation: SessionRevocation,
    attempt: PairingAttempt,
    target: RawSourceStrings,
): String = when (attempt) {
    is PairingAttempt.Connected ->
        revocationNotice(revocation, "已切换到 ${attempt.host}:${attempt.port}")
    is PairingAttempt.Rejected -> {
        val headline = pairingHeadline(attempt.kind, target)
        when (revocation) {
            is SessionRevocation.Failed -> revocationNotice(
                revocation,
                "旧 core 的会话可能仍然有效，请手动断开或等它过期",
            )
            is SessionRevocation.Revoked ->
                "旧 core 的凭证已吊销，本机已断开；切换没有完成：$headline"
            is SessionRevocation.NotNeeded ->
                "没有吊销任何旧凭证（本地配置未动）；切换没有完成：$headline"
        }
    }
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
internal const val SWITCH_SUBMITTING = "正在连接新 core（成功后吊销旧凭证）…"

/** 「吊销失败」提示的前缀 —— UI 契约：失败必须说这几个字。 */
internal const val REVOKE_FAILED_NOTICE = "旧凭证吊销失败"

/** 当前连接卡的说明：把顺序说清，用户才知道旧 core 会发生什么。 */
internal const val CORE_SWITCH_HOW =
    "换 core 时先用新地址与配对码连上新 core；拿到新凭证之后，才向旧 core 发 DELETE /ui/session 吊销本机的 UI 凭证。新 core 连不上时旧连接原样保留。"

/** 断开按钮。 */
internal const val CORE_SWITCH_DISCONNECT = "断开当前 core（吊销本机凭证）"

/**
 * 两步确认的第二步文案（R3）：同一个按钮换句话，**不是**弹层、不是第二个按钮。
 * 第一次点只到这里（一个请求都不发）；[DISCONNECT_CONFIRM_WINDOW_MS] 内再点一次才真的断开。
 */
internal const val CORE_SWITCH_DISCONNECT_CONFIRM = "再点一次确认断开"

/** 第一步之后的说明：把「窗口」与「自动取消」讲清楚，用户才知道自己还剩多少时间。 */
internal const val CORE_SWITCH_DISCONNECT_HINT = "3 秒内再点一次才会断开；超过 3 秒自动取消。"

/** 两步确认的窗口（与旧 core 吊销预算同一量级：够点第二下，又不至于挂着一个待执行的破坏性动作）。 */
internal const val DISCONNECT_CONFIRM_WINDOW_MS: Long = 3_000L

/** 换 core / 断开回执的测试 tag（断言「在表单上方」与 liveRegion 用）。 */
internal const val CORE_SWITCH_NOTICE_TAG = "core-switch-notice"

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

/** P1-4 · 地址 / 端口输入框的 tag：组合测试要能改地址，验证「警告随表单变更作废」。 */
internal const val PAIRING_HOST_FIELD_TAG = "pairing-host-field"
internal const val PAIRING_PORT_FIELD_TAG = "pairing-port-field"


// ---------------------------------------------------------------- r5-tls · HTTPS

/**
 * HTTPS 开关的文案。它是**协议**选项，不再等于「我接受自签证书」：打开它先按系统信任链
 * 连接，只有证书不被信任时才会弹确认（P0-6 之后两者是两件事）。
 */
internal const val PAIRING_TLS_LABEL = "HTTPS"

/** 开关整行的测试 tag（开关本体没有 modifier，测试点这一行 → 命中它的 clickable）。 */
internal const val PAIRING_TLS_SWITCH_TAG = "pairing-tls-switch"

/** 警告弹层的测试 tag（断言「弹层出现前零请求 / 取消后零请求」）。 */
internal const val PAIRING_TLS_DIALOG_TAG = "pairing-tls-dialog"

/** 弹层里那句「确认对象」的原文（P1-4：快照 endpoint 必须写出来）。 */
internal fun tlsWarningTargetLine(endpoint: String): String =
    "即将连接 $endpoint · HTTPS（证书未通过系统校验）"

/** 确认对象那一行的测试 tag。 */
internal const val PAIRING_TLS_TARGET_TAG = "pairing-tls-target"

/** 弹层标题：一句话说清危险在哪。 */
internal const val PAIRING_TLS_WARNING_TITLE = "自签证书无法验证 core 的身份"


/**
 * 弹层正文：三件事一字不能少 —— 无法验证身份、可能被中间人、只在你信任此主机时继续。
 * 不写「高级选项」「测试用」这类会把风险说小的词。
 */
internal const val PAIRING_TLS_WARNING_BODY =
    "继续连接时，客户端无法确认对端就是你要连的那台 core：同一网络里的任何人（或中间人）" +
        "都可以冒充它，也可以读到这次通信的内容。仅在你信任这台主机（自己的局域网机器、" +
        "或本机）时继续；公共网络里请改用带可信证书的地址。"

/** 弹层的「继续」按钮：动作 + 后果都在字面上（不是「确定」）。 */
internal const val PAIRING_TLS_CONFIRM_LABEL = "仍然连接"

/** 弹层的「取消」按钮：不发请求、不烧配对码。 */
internal const val PAIRING_TLS_CANCEL_LABEL = "取消"

/** host / port 输入框占位（默认值本身就是值，占位只在清空后提示）。 */
internal const val PAIRING_HOST_PLACEHOLDER = "host（默认 127.0.0.1）"
internal const val PAIRING_PORT_PLACEHOLDER = "port（默认 8443）"

/**
 * r3-a11y · host / port 的**可访问名**（可视小字 + semantics contentDescription）。
 * 占位文案不能当名字：值非空时占位根本不渲染（默认启动就非空），读屏只会念「可输入文本」。
 */
internal const val PAIRING_HOST_LABEL = "地址"
internal const val PAIRING_PORT_LABEL = "端口"

/** 提交按钮。 */
internal const val PAIRING_BUTTON_LABEL = "配对"

/** 尝试进行中的提示。 */
internal const val PAIRING_SUBMITTING = "正在配对…"

/** 端口本地校验失败的说明（不发网络请求）。 */
internal const val PAIRING_PORT_INVALID = "端口必须是 1..65535 的整数"

// ---------------------------------------------------------------- P0-1 · 持久化凭据

/**
 * 上次的配对凭据被 core 拒了（401 / 握手失败 / core 重启后会话表清空）。
 * 必须点明「已失效、已清除」，否则用户会把它当成网络故障反复重试。
 */
internal const val PAIRING_CREDENTIAL_REJECTED =
    "上次保存的配对已失效（core 重启过，或凭证被吊销）。已清除，请重新填一个新码。"

/** 凭据还在、只是这次没连上（core 没起来 / 网络不通 / 证书变了）。 */
internal const val PAIRING_SAVED_KEPT =
    "上次配对的凭据还在本机。core 恢复后可以直接重连，不必再取新码。"

/** 「用已保存的配对重连」按钮。 */
internal const val PAIRING_SAVED_RETRY = "用已保存的配对重连"

/** 重连在途时的同一个按钮。 */
internal const val PAIRING_SAVED_RETRYING = "正在重连已保存的配对…"

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
    PairingFailureKind.CertificateUntrusted -> "core 的证书不被信任：${pairingEndpointOf(raw)}"
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
    PairingFailureKind.CertificateUntrusted ->
        "下一步：如果这台 core 用的是自签证书（自己的局域网机器 / 本机），确认「仍然连接」后" +
            "会再试一次；否则请换成带可信证书的地址，或先在系统里信任那张证书。"
}
