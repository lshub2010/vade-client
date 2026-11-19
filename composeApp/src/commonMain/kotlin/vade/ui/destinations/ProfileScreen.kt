package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vade.ui.UiText
import vade.ui.buildconfig.VadeBuildConfig
import vade.ui.data.AdapterProvider
import vade.ui.data.PairingRuntime
import vade.ui.glass.asGlassSource
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.model.DefaultModelActions
import vade.ui.model.ModelActions
import vade.ui.model.ModelPickerCard
import vade.ui.model.ModelState
import vade.ui.skin.AppearancePrefs
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.BtnStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.SkinRegistry
import vade.ui.skin.Skins
import vade.ui.skin.ThemeController
import vade.ui.skin.ThemeMode
import vade.ui.skin.glass.LocalGlassHazeState
import vade.ui.shell.BackLayer

/**
 * g-haze-structure · 我的页（主滚动列 + 关于子页滚动列）的 haze source tag ——
 * 守卫见 `GlassStructureGuardTest`。两个滚动列互斥组合，共用 tag 不产生歧义。
 */
internal const val GlassSourceProfileTag = "vade.glass.source.profile"

/**
 * 我的 (U1-a skeleton), v2 form — appearance is the only interactive block, because it is
 * the one thing this slice must be able to demonstrate at runtime: skin × bottom-bar form
 * swaps go through `CompositionLocal`, never through a rebuilt Activity/View tree.
 *
 * Group cards are [GroupCard] (`md` + hairline + soft shadow); the 其它 block keeps the
 * disabled `Skin.ListItem` rows, labelled not faked (devices / manage-core stay U1-f/T5b).
 *
 * I4 · 外观卡的文案是**给用户看的**，不是实现文档：三项各一句人话，不出现注册表主键、
 * 组件编号、模糊半径/染色百分比这类内部参数。以前那一大段实现说明把三项设置全推到了首屏
 * 之外，用户为了改个主题要先读三百字的架构注解。
 *
 * I1 · 三项（皮肤 / 主题 / 底栏）都记得住：写入走 [AppearancePrefs]（三端各自的本地存储），
 * 读取在启动时（皮肤 / 底栏见 [Skins.bootSkinId] / [Skins.bootBottomBar]，主题见
 * [ThemeController] 的初始化）。所以 chip 点了不只是这一帧生效，重启回来还在。
 *
 * I2 · 玻璃皮肤本轮**不可选**：chip 写「玻璃（待安装）」并置灰，不再拿 material 换 id 冒充。
 * 可选项与标签都由 [SkinRegistry.installed] 推导 —— U3 真注册了 glass，chip 自己就变回可选。
 *
 * U1-model · the old disabled 「模型」 placeholder row is retired: the 默认模型 section below
 * hosts the real picker (global-default layer of D101). Reads/writes go through the injected
 * [modelActions] seam — unwired (this round), the process-wide in-memory fixture
 * ([DefaultModelActions]) keeps it interactive, labelled, and stable across tab switches (I3).
 * A5 · the 关于 row is wired: it opens [AboutPage] as a full-screen subpage (D91) showing
 * the injected version / build time / licence list.
 *
 * [contentPadding] is the shell's floating glass bar zones (see `shell/AppShell.kt`).
 */
@Composable
fun ProfileScreen(
    skinId: String,
    barStyle: BottomBarStyle,
    /**
     * 保留形参：shell 仍按断点算出容器说明交下来，但 I4 之后本页不再把它当用户文案渲染
     * （「NavigationRail 72dp · 双栏」是实现术语，不是用户要知道的事）。删参数要动
     * `shell/AppShell.kt` 与八处调用点，属于另一批的改动面，这里先留在签名上。
     */
    containerLabel: String,
    onSkinChange: (String) -> Unit,
    onBarChange: (BottomBarStyle) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /**
     * The model seam (RFC-0005): `model/list` / `session/model.get` / `session/model.set`.
     * Null = use the process-wide in-memory fixture (假数据) — [DefaultModelActions].
     */
    modelActions: ModelActions? = null,
    /**
     * 组合根交下来的适配器包（`data/AdapterProvider`）。默认 null ⇒ 走到内存假实现，
     * 行为与接线前逐字节相同（`AppShell` 这一轮还没接线）。
     *
     * 三段回退：[modelActions]（形参）> [adapters] > [DefaultModelActions]（I3：进程级单例，
     * 不是每次进页 new 一个 —— 那正是「切 tab 默认模型就回退」的病因）。
     * 设置页要的是**全局默认**视图，所以取的是 `modelDefault()` 而**不是** `model("")`：
     * `AdapterProvider` 把两者刻意分成两个方法，用错一个就会拿到「没有会话上下文」的
     * 会话级适配器（`model(sessionId)` 的语义是某个会话的覆盖值）。
     */
    adapters: AdapterProvider? = null,
) {
    val skin = LocalSkin.current
    // I2 · 「能选哪些皮肤」只有注册表一个答案：装了什么就列什么，没装的一律置灰。
    // 本页可能先于 App 的 installBuiltIns 被单独组合（预览 / 渲染测试），这里补一次幂等安装，
    // 页面就不依赖调用顺序了。
    Skins.installBuiltIns()
    val installedSkins = remember { SkinRegistry.installed() }
    // P1-5 · 传进来的 skinId 可能来自旧偏好 / 命令行给的失效 id。显示层同样收敛一次，
    // 否则三个 chip 全不亮 ——「三项会记住」就成了一句假话。真正进组合的 id 在 App.kt 收敛。
    val currentSkinId = Skins.resolveSkinId(skinId)
    // I1 · chip 干两件事：改本轮状态（shell 的 UiState 随之翻，App 的 remember key 变 →
    // 只换 LocalSkin）+ 落盘（下次启动由 Skins.bootSkinId / Skins.bootBottomBar 恢复）。
    // 没装的皮肤连「点了没反应」都不该发生：chip 已置灰，这里再挡一道程序化调用。
    val selectSkin: (String) -> Unit = { id ->
        if (id in installedSkins) {
            onSkinChange(id)
            AppearancePrefs.storeSkin(id)
        }
    }
    val selectBar: (BottomBarStyle) -> Unit = { style ->
        onBarChange(style)
        AppearancePrefs.storeBottomBar(style)
    }
    // ② 形参 > adapters.modelDefault() > 内存假实现；适配器查找异常时退回假实现并显示原因。
    val seam = remember(modelActions, adapters) {
        resolveProfileModelActions(modelActions, adapters)
    }
    val modelActionsResolved = seam.value
    val modelState = remember(modelActionsResolved) { ModelState.forDefault(modelActionsResolved) }
    // 「重试拉取模型列表」行的协程作用域（refresh() 是 suspend，挂在组合作用域上）。
    val retryScope = rememberCoroutineScope()
    // A5 · 关于 opens as a full-screen subpage over this screen (D91: 设置项打开为全屏子页，
    // 不做弹层). The state stays local to this page — the shell must not learn about
    // settings subpages.
    var showAbout by remember { mutableStateOf(false) }
    // 缺陷⑩ · 「连接 / 更换 Core」也是同一形态的全屏子页。已配对时入口**照样可点**：
    // 它就是「换一台 core」的唯一入口（换 core 的吊销/清理/重连顺序在 PairingRuntime）。
    var showCoreSwitch by remember { mutableStateOf(false) }
    // 读桥：未配对显示「未连接 · 点按配对」，已配对显示实际 endpoint；换 core 后随
    // PairingRuntime.state 刷新（compose state）。
    val coreEndpoint = PairingRuntime.pairedEndpoint
    // 上一次换 core / 断开的回执（含「旧凭证吊销失败」）：换 core 会重建 UiState，
    // 子页可能因此离开组合 —— 所以这里也留一行，回来还看得见。
    val coreSwitchNotice = CoreSwitchNotice.text
    // r3-a11y · 两个子页是同一个 Box 里的**同级覆盖层**：视觉上盖住了本页，但底下的滚动列
    // 仍在语义树里 —— 读屏能遍历到根本没显示在屏幕上的设置行（审计 A11Y-2）。
    // 子页打开时把整列标记 invisibleToUser()（节点连同子树从无障碍树消失），关掉子页就恢复；
    // 判据只有这一个开关本身，没有第二份「子页是否打开」的状态。
    // 注：Compose 1.12 把该函数改了名（hideFromAccessibility()），invisibleToUser() 仍是
    // 同一语义的别名，SemanticsOwner.isHidden 两个 key 都认 —— 这里保留审计原文的名字。
    // 键盘焦点不在这里承诺：invisibleToUser() 只作用于无障碍树，`focusProperties` 挂在一个
    // 非可聚焦的 Column 上也不会关掉子树焦点（P3：宁可不说，也不留一句假承诺）。
    val subpageOpen = showAbout || showCoreSwitch
    Box(
        modifier
            .fillMaxSize()
            .background(skin.colors.canvas),
    ) {
        Column(
            Modifier
                // g-haze-structure: this scroll column is the page's haze **source** (same
                // contract as the sessions list / chat thread). The in-flow ModelPickerCard
                // below stays inside it on purpose — it is an inline section card, not a
                // floating overlay, and carries no glass surface; the floating session
                // picker lives in SessionDetailScreen, OUTSIDE its thread source.
                .asGlassSource(LocalGlassHazeState.current)
                .fillMaxSize()
                .testTag(GlassSourceProfileTag)
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 16.dp)
                .then(
                    if (subpageOpen) {
                        Modifier.semantics { invisibleToUser() }
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        SectionLabel("外观")
        GroupCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                UiText("皮肤", size = 13, weight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkinChip("material", "经典", currentSkinId, installedSkins, selectSkin)
                    SkinChip("miuix", "Miuix", currentSkinId, installedSkins, selectSkin)
                    // I2 · 玻璃本轮只有「待安装」这一种状态：没注册 = 置灰 + 标签带后缀，
                    // 不再拿 material 换 id 冒充（点选像素不变、暗色还会掉回亮色的那个缺陷）。
                    SkinChip("glass", "玻璃", currentSkinId, installedSkins, selectSkin)
                }
                UiText(
                    text = "经典是默认外观；Miuix 更圆更重，接近手机系统。" +
                        (if ("glass" in installedSkins) "" else "玻璃皮肤还没做完，暂时不能选。"),
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                // g-dark-follow · 主题：跟随系统（默认）/ 亮 / 暗。写入 ThemeController.choose；
                // 解析与即时切换的机制见 skin/ThemeMode.kt 的类 KDoc（remember key → 只换 LocalSkin）。
                UiText("主题", size = 13, weight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip(ThemeMode.FollowSystem)
                    ThemeChip(ThemeMode.Light)
                    ThemeChip(ThemeMode.Dark)
                }
                UiText(
                    text = "默认跟随系统；想固定亮色或暗色就手选，手选会覆盖系统设置。",
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                // 底栏文案与实现一致（shell/BottomBar.kt）：Fixed = 贴底整条 + 真模糊；
                // Floating = 悬浮胶囊、不模糊；FloatingBlur = 同一胶囊 + 真模糊。
                UiText("底栏", size = 13, weight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BarChip("停靠", BottomBarStyle.Fixed, barStyle, selectBar)
                    BarChip("悬浮", BottomBarStyle.Floating, barStyle, selectBar)
                    BarChip("悬浮 + 模糊", BottomBarStyle.FloatingBlur, barStyle, selectBar)
                }
                UiText(
                    text = "停靠贴住屏幕底边；悬浮是离底边留空的胶囊；悬浮 + 模糊的胶囊底下会透出模糊的内容。",
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                // I1 · 这三项都真的存下来了，所以敢这么写；存不住就不许写。
                UiText(
                    text = "这三项会记住，下次打开还是这个外观。",
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
            }
        }

        SectionLabel("默认模型")
        ModelPickerCard(
            state = modelState,
            title = "默认模型",
            footerNote = "会话未单独设置模型时使用（D101：会话级 > 全局默认 > priority）" +
                (
                    // 同上：接上适配器后模型面来自 core，继续说「未接 core」就是撒谎（§8.5）。
                    if (adapters == null || seam.degraded != null) {
                        "· 假数据：未接 core，接真数据只需实现 vade.ui.model.ModelActions"
                    } else {
                        "· 模型面来自 core（AdapterProvider）；全局默认当前只读（契约无写入方法）"
                    }
                    ) +
                seam.degraded?.let { " · $it" }.orEmpty(),
        )
        // I3 · 写成功要看得见：卡本身只画选中态，没有一句「已设为…」的确认，用户点完
        // 不知道到底有没有生效。这条提示就是 ModelState.lastNotice（内存路径与真适配器
        // 路径都会填），出现即代表这一笔写入走完了。
        modelState.lastNotice?.let { notice ->
            UiText(notice, size = 11, color = skin.colors.onSurfaceVariant)
        }
        // G · 拉取失败的可重试出口：ModelPickerCard 的 LoadFailed 文案承诺「重试后再判断」，
        // 但卡内没有重试钮（model/ 包本棒不可改），这里补上它。refresh() 就是既有的拉取
        // 入口（不是第二套重试）；点击后 loadFailure 清空、loadInFlight 点亮，卡片回到
        // 「正在加载模型列表」——结果只会是三态之一，绝不洗成空列表。方法不存在（-32601）
        // 走卡的只读降级，不落进这里（readOnly 时 refresh 直接 return，loadFailure 为空）。
        if (modelState.loadFailure != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(percent = 50))
                    .background(skin.colors.surfaceVariant)
                    .clickable { retryScope.launch { modelState.refresh() } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VadeIcon(
                    VadeIcons.Refresh,
                    contentDescription = "重试",
                    size = 16.dp,
                    tint = skin.colors.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                UiText(
                    "重试拉取模型列表",
                    size = 12,
                    weight = FontWeight.Medium,
                    color = skin.colors.onSurface,
                )
            }
        }

        SectionLabel("其它")
        GroupCard {
            Column(Modifier.padding(vertical = 4.dp)) {
                skin.ListItem(title = "设备与通知", subtitle = "设备吊销 FR-8.68（U1-f）", enabled = false)
                // 缺陷⑩ · 不再是灰的占位：未配对 = 配对入口，已配对 = 换一台 core 的入口。
                skin.ListItem(
                    title = CORE_SWITCH_TITLE,
                    subtitle = coreEndpoint?.let { "已连接 $it · 点按更换" } ?: "未连接 · 点按配对",
                    onClick = { showCoreSwitch = true },
                )
                // A5 · was a disabled U1-f placeholder; now opens the full-screen 关于 subpage.
                skin.ListItem(
                    title = "关于",
                    subtitle = "版本 ${VadeBuildConfig.VERSION} · 许可与构建信息",
                    onClick = { showAbout = true },
                )
            }
        }
            coreSwitchNotice?.let { text ->
                UiText(
                    text = text,
                    size = 11,
                    color = if (text.startsWith(REVOKE_FAILED_NOTICE)) {
                        skin.colors.error
                    } else {
                        skin.colors.onSurfaceVariant
                    },
                )
            }
            UiText(
                text = "设置项打开为全屏子页（D91），不做弹层——关于已落地（A5），" +
                    "连接 / 更换 core 已落地（缺陷⑩），其余随 U1-f。",
                size = 11,
                color = skin.colors.onSurfaceVariant,
            )
            Spacer(Modifier.padding(bottom = 8.dp))
        }
        if (showAbout) {
            // g-nav-stack（c2-nav）：关于子页也是返回栈的一层 —— Esc / 系统返回先退子页，
            // 回到我的页，不会退出 App。关掉（showAbout=false）后本层随组合离开自动出栈；
            // 与顶栏「返回」按钮同一个动作。
            BackLayer(enabled = showAbout, onBack = { showAbout = false })
            AboutPage(onBack = { showAbout = false }, contentPadding = contentPadding)
        }
        if (showCoreSwitch) {
            // 缺陷⑩ · 同一个返回栈口径：Esc / 系统返回先退「连接 / 更换 core」子页。
            // 取消（返回）只是关掉子页 —— **一个请求都不发、配置一个字节都不动**；
            // 吊销/清理只发生在用户真的点了「配对并切换」之后（PairingRuntime.switchCore）。
            BackLayer(enabled = showCoreSwitch, onBack = { showCoreSwitch = false })
            CoreSwitchPanel(onBack = { showCoreSwitch = false }, contentPadding = contentPadding)
        }
    }
}

/**
 * ② 设置页的模型 seam：**形参 > `adapters.modelDefault()` > 进程级内存假实现**。
 *
 * 设置页要的是**全局默认**视图，所以取 `modelDefault()` 而**不是** `model("")`：
 * `AdapterProvider` 刻意把两者分成两个方法，`model(sessionId)` 的语义是「某个会话的覆盖值」，
 * 拿它冒充全局默认会让设置页显示一个不属于任何会话的覆盖值。独立成函数是为了让屏幕与
 * 测试跑同一段代码（把这一行改成 `model("")`，`profileAsksForTheGlobalDefault…` 必红）。
 *
 * I3 · 最后一段回退是 [DefaultModelActions] 而不是 `InMemoryModelActions()`：新建的假实现
 * 活不过一次 tab 切换，用户刚选好的默认模型会在下一次进页时静默回退。
 */
internal fun resolveProfileModelActions(
    explicit: ModelActions?,
    adapters: AdapterProvider?,
): ScreenSeam<ModelActions> = pickScreenSeam(
    explicit = explicit,
    adapters = adapters,
    lookup = { modelDefault() },
) { DefaultModelActions.inMemory() }

/**
 * 皮肤 chip。
 *
 * [name] 是用户可读名（经典 / Miuix / 玻璃），[id] 是 `SkinRegistry` 主键——页面上只出现
 * 前者（I4：内部 id 不是用户文案）。**没装**的皮肤一律置灰并把状态写进标签
 * （「玻璃（待安装）」）：能点却什么都不发生，比看不到这个选项更糟（I2）。
 */
@Composable
private fun SkinChip(
    id: String,
    name: String,
    current: String,
    installed: Set<String>,
    onSelect: (String) -> Unit,
) {
    // w-g-icons：选中标记不再往文字里塞 `●` 字符（v2 §3 禁字符图标）——选中态已由
    // BtnStyle.Primary（accent 实底）与 Secondary 的对比表达，重复标记只会加噪音。
    val selectable = id in installed
    LocalSkin.current.Button(
        text = if (selectable) name else "$name（待安装）",
        onClick = { onSelect(id) },
        style = if (id == current) BtnStyle.Primary else BtnStyle.Secondary,
        enabled = selectable,
    )
}

@Composable
private fun BarChip(
    label: String,
    style: BottomBarStyle,
    current: BottomBarStyle,
    onSelect: (BottomBarStyle) -> Unit,
) {
    // w-g-icons：同 SkinChip —— 选中态由样式表达，不用 `●` 字符。
    LocalSkin.current.Button(
        text = label,
        onClick = { onSelect(style) },
        style = if (style == current) BtnStyle.Primary else BtnStyle.Secondary,
    )
}

/**
 * g-dark-follow · 主题三态 chips（跟随系统 / 亮 / 暗），外观分区的手动覆盖入口。
 * 与 SkinChip/BarChip 同一形态：选中态由 BtnStyle 表达。当前值直接读
 * `ThemeController.override`（组合期状态，不进 UiState——主题不是会话状态，D98 口径），
 * 点选即写回，`App.kt` 的 remember key 随之翻转、只换 LocalSkin 的值。
 */
@Composable
private fun ThemeChip(mode: ThemeMode) {
    LocalSkin.current.Button(
        text = mode.label,
        onClick = { ThemeController.choose(mode) },
        style = if (ThemeController.override == mode) BtnStyle.Primary else BtnStyle.Secondary,
    )
}

/** Keeps the row helper import honest if U1-f replaces the placeholder list. */
@Composable
internal fun ProfileRowSpacer() {
    Spacer(Modifier.width(0.dp))
}

@Composable
internal fun ProfileRowCenter(content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) { content() }
}

/**
 * A5 · third-party licence rows on the 关于 subpage. Mirrors the repo README's
 * 「第三方许可与致谢」 section — the page renders what the README declares, word for word;
 * it must not invent licence text. Adding a dependency = add its row here AND in the
 * README. HarmonyOS Sans stays an honest 预留位: the README marks it 待定 (not adopted).
 */
internal data class LicenseEntry(
    val name: String,
    val license: String,
    val copyright: String,
    val adopted: Boolean = true,
)

internal val VadeLicenseEntries: List<LicenseEntry> = listOf(
    LicenseEntry(
        name = "KernelSU",
        license = "Apache License 2.0",
        copyright = "Copyright (c) 2023-2026 weishu & contributors",
    ),
    LicenseEntry(
        name = "Miuix",
        license = "Apache License 2.0",
        // Coordinates are NOT spelled out here on purpose: the MaterialImportGuardTest
        // bans the miuix artifact string anywhere in a business page (even prose), so the
        // row names the artifacts instead. README carries the full coordinate.
        copyright = "Miuix KMP 构件：miuix-ui 与 miuix-blur",
    ),
    LicenseEntry(
        name = "Tabler Icons",
        license = "MIT License",
        copyright = "Copyright (c) 2018-2026 Tabler contributors",
    ),
    LicenseEntry(
        name = "HarmonyOS Sans",
        license = "HarmonyOS Sans Fonts License Agreement",
        copyright = "© Huawei Device Co., Ltd.",
        adopted = false,
    ),
)

/**
 * 关于 full-screen subpage (D91): 版本 / 构建时间 / 许可列表. The version and build time
 * come from the generated [VadeBuildConfig] — single source: `vadeVersion` in the repo-root
 * gradle.properties, stamped at build time; nothing here hardcodes a value that could
 * drift from the build. Licence rows mirror the README verbatim (no invented text).
 */
@Composable
internal fun AboutPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val skin = LocalSkin.current
    // g-haze-structure: 关于子页的滚动列同样登记为本页 haze source（与主滚动列互斥组合）。
    Column(
        modifier
            .asGlassSource(LocalGlassHazeState.current)
            .fillMaxSize()
            .testTag(GlassSourceProfileTag)
            .background(skin.colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
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
            UiText("关于", size = 17, weight = FontWeight.SemiBold)
        }

        SectionLabel("版本")
        GroupCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UiText("Vade 客户端", size = 18, weight = FontWeight.SemiBold)
                UiText("版本 ${VadeBuildConfig.VERSION}", size = 14, weight = FontWeight.Medium)
                UiText("构建时间 ${VadeBuildConfig.BUILD_TIME}", size = 12, color = skin.colors.onSurfaceVariant)
                UiText(
                    text = "版本号单一来源：gradle.properties 的 vadeVersion，构建时注入（A5）。",
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
            }
        }

        SectionLabel("许可证")
        GroupCard {
            Column(Modifier.padding(vertical = 4.dp)) {
                VadeLicenseEntries.forEach { entry ->
                    skin.ListItem(
                        title = entry.name,
                        subtitle = if (entry.adopted) {
                            "${entry.license} · ${entry.copyright}"
                        } else {
                            "${entry.license} · 预留位（README 标注待定，当前未采用）"
                        },
                    )
                }
            }
        }
        UiText(
            text = "完整声明见仓库 README「第三方许可与致谢」。E2E 设备标识随 U1-f 落地。",
            size = 11,
            color = skin.colors.onSurfaceVariant,
        )
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}
