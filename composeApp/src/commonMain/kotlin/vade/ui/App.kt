package vade.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import vade.ui.destinations.Destination
import vade.ui.chat.ChatPreset
import vade.ui.data.FakeSessionSource
import vade.ui.data.PairingRuntime
import vade.ui.data.PairingRuntimeState
import vade.ui.data.SessionSource
import vade.ui.data.sessionSourceFromEnvironment
import vade.ui.shell.AppShell
import vade.ui.shell.BackDispatcher
import vade.ui.shell.BackHost
import vade.ui.shell.LocalBackDispatcher
import vade.ui.shell.UiState
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.SkinRegistry
import vade.ui.skin.Skins

/**
 * Composition root, shared by all three targets.
 *
 * The skin is injected through `LocalSkin` (CompositionLocal), so switching it swaps one
 * provider value and never rebuilds the platform container — the requirement behind
 * "皮肤切换运行时生效" (task book §T5). [initialBar] / [initialSkinId] / [initialDest] exist
 * so the desktop screenshot loop can launch straight into a given state without a UI
 * driver; the Android and Wasm entry points call `App()` with defaults.
 */
@Composable
fun App(
    initialBar: BottomBarStyle = Skins.bootBottomBar(),
    /**
     * I1 · 启动皮肤。null（默认）= 组合时读本地偏好（[Skins.bootSkinId]），而不是在调用点
     * 就把偏好冻成实参 —— 测试在组合前换掉存储，读到的就是那份存储。
     *
     * 显式值（`--skin=` / `VADE_SKIN`）同样**不保证**是装得上的 id：组合前经
     * [Skins.resolveSkinId] 收敛，`glass` 这种没注册的 id 退回上次选过的皮肤，不许在组合期抛。
     */
    initialSkinId: String? = null,
    initialDest: Destination = Destination.Sessions,
    initialPanelOpen: Boolean = false,
    initialSearchQuery: String? = null,
    /** U1-b: open a session on launch (`--open=`), used by the screenshot loop. */
    initialSessionId: String? = null,
    /** U1-b: seed the detail screen's derived state (`--chat=`); `Live` for the real app. */
    chatPreset: ChatPreset = ChatPreset.Live,
    /**
     * v2 Wave 0 · state-source injection point, threaded straight into [UiState]. The
     * default keeps today's fixture behaviour; a later batch injects the transport-backed
     * source the same way the skin is injected here.
     */
    source: SessionSource = FakeSessionSource(),
) {
    Skins.installBuiltIns()
    // H1 · 首次运行配对成功后，**入口重读配对桥**并交出带上 cookie 的新源：UiState 随源
    // 重建、loader 重新拉列表（真数据上屏）。默认启动（无参数）= 夹具源 + 列表顶部的
    // 引导卡；配对成功那一刻 [PairingRuntime] 从 Unpaired 变 Paired，这一行读出变化、
    // 重新走 `sessionSourceFromEnvironment()`（桥在 Fake 分支里被消费）。
    // 未配对时 `effectiveSource` 恒等于传进来的 [source] —— 与旧行为逐字节相同。
    val paired = PairingRuntime.state as? PairingRuntimeState.Paired
    // P0-1 · 启动恢复：平台 KV 里可能存着上次配对成功留下的 `{host, port, tls, trustMode}`
    // 与那张 `__Host-ac_ui`。**先用它探测一次**（一次 WS 升级 + 一帧请求）：
    //  - 成功 → 桥进 Paired，下面这行 `remember` 换源、列表开始拉真数据；
    //  - 401 / 握手失败 → 桥退回 Unpaired（凭据已删），引导卡出现 —— 不再僵尸重连；
    //  - core 暂时不可达 → 凭据留着，卡片带「用已保存的配对重连」。
    // 只在入口声明过本次启动（`Unpaired`）时跑：UI 测试直构的组合（`NotBooted`）一个字节
    // 都不读，夹具路径的行为逐字不变。
    LaunchedEffect(Unit) {
        if (PairingRuntime.state is PairingRuntimeState.Unpaired) {
            runCatching { PairingRuntime.restoreStoredCredential() }
        }
    }
    val effectiveSource = remember(source, paired) {
        // P1-4 · 配对桥里的 endpoint 是用户填过的合法值，但**环境通道**可能仍然是坏的
        // （例如应用启动后 `VADE_PORT` 被改成了 `8443x`）。这里再解析一次时不能把
        // 组合炸掉：解析失败就沿用入口传进来的源，错误屏已经在入口渲染过了。
        if (paired != null) runCatching { sessionSourceFromEnvironment() }.getOrElse { source } else source
    }
    // g-pair-first-run：`remember` 以 [effectiveSource] 为 key —— 源换了，UiState 一起换。
    // DisposableEffect：随源重建时把旧 UiState 的 loader/连接停掉 —— 没配上对的旧源
    // 否则会在后台无限重试。
    //
    // P1-13 · 但「重建 UiState」不等于「用户的外观选择也重来」：皮肤 / 底栏是本轮的用户
    // 选择（设置页点了立刻生效），不是启动快照。重建时若继续拿 [initialSkinId] /
    // [initialBar] 这些**启动实参**，配对成功换源那一刻就会把刚选的外观静默打回出厂值
    // ——用户看到的是「选了又自己弹回去」。所以跨重建带上一轮的值（[carriedAppearance]
    // 每帧由下面的 SideEffect 同步），重建时优先用它，其次才是启动实参 / 本地偏好。
    val carriedAppearance = remember { CarriedAppearance() }
    val state = remember(effectiveSource) {
        UiState(
            initialBar = carriedAppearance.bar ?: initialBar,
            initialSkinId = appearanceOnRebuild(carriedAppearance.skinId, initialSkinId),
            initialDest = initialDest,
            initialPanelOpen = initialPanelOpen,
            initialSessionId = initialSessionId,
            chatPreset = chatPreset,
            source = effectiveSource,
        ).also { created ->
            // U1-c screenshot / deep-link hook: same path as tapping 搜索 in the top bar.
            if (initialSearchQuery != null) {
                created.openSearch()
                created.updateSearchQuery(initialSearchQuery)
            }
        }
    }
    // 把这一轮的选择记进 carry（组合成功之后跑，不在组合期写状态）：
    // 下一次 `effectiveSource` 变化重建 UiState 时，`remember` 块读到的就是最新值。
    SideEffect {
        carriedAppearance.skinId = state.skinId
        carriedAppearance.bar = state.barStyle
    }
    DisposableEffect(effectiveSource) {
        onDispose { state.dispose() }
    }
    // g-back-handler: the extensible back stack lives at the composition root — one
    // provider for every overlay (search / preview / drawer / model picker to come).
    val backDispatcher = remember { BackDispatcher() }
    // g-dark-follow: 主题变化只换 LocalSkin 的值，不重建整棵树；`currentDark()` 必须在
    // 组合期同步读出当 key（皮肤工厂读的是同一个 plain var，晚一帧就会拿旧值建皮肤）。
    // P1-5 · 再收敛一次：`state.skinId` 正常来自 [appearanceOnRebuild]，但
    // `UiState.setSkin(id)` 是公开入口，拿一个未注册的 id 进来不该把组合炸掉 ——
    // `SkinRegistry.get` 对未知 id 是 `error(...)`，而这里就是唯一的调用点。
    val effectiveSkinId = Skins.resolveSkinId(state.skinId)
    val skin =
        remember(effectiveSkinId, vade.ui.skin.ThemeController.currentDark()) {
            SkinRegistry.get(effectiveSkinId)
        }

    CompositionLocalProvider(
        LocalSkin provides skin,
        LocalBackDispatcher provides backDispatcher,
    ) {
        BackHost(backDispatcher) // system-back bridge; stack empty ⇒ not intercepting
        AppShell(state)
    }
}

/**
 * P1-13 · 重建 [UiState]（`effectiveSource` 变了）时该用哪个皮肤 id。
 *
 * 顺序：**本轮已选** [current]（且现在还装得上）> 启动链 [initial] → 本地偏好 → 出厂。
 * 结果一律落在已安装的 id 上：装不上的 id 不许进 `SkinRegistry.get`。
 * 纯函数（不碰组合），由 `AppAppearanceRebuildTest` 直接钉住「选了 Miuix，重建后还是 Miuix」。
 */
internal fun appearanceOnRebuild(current: String?, initial: String?): String {
    Skins.installBuiltIns()
    val carried = current?.trim()?.takeIf { it.isNotEmpty() }
    if (carried != null && carried in SkinRegistry.installed()) return carried
    return Skins.resolveSkinId(initial)
}

/**
 * P1-13 · 跨 [UiState] 重建携带的外观选择。
 *
 * 普通 var 而不是 Compose 状态：它只在 `remember` 块重建时被读一次，写它不该触发重组
 * （每次重组都写一遍，走快照状态会白白多一轮失效）。
 */
private class CarriedAppearance {
    var skinId: String? = null
    var bar: BottomBarStyle? = null
}
