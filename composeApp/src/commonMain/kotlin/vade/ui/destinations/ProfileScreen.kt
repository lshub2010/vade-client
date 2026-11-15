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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vade.ui.UiText
import vade.ui.buildconfig.VadeBuildConfig
import vade.ui.data.AdapterProvider
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.model.InMemoryModelActions
import vade.ui.model.ModelActions
import vade.ui.model.ModelPickerCard
import vade.ui.model.ModelState
import vade.ui.skin.BtnStyle
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin

/**
 * 我的 (U1-a skeleton), v2 form — appearance is the only interactive block, because it is
 * the one thing this slice must be able to demonstrate at runtime: skin × bottom-bar form
 * swaps go through `CompositionLocal`, never through a rebuilt Activity/View tree.
 *
 * Group cards are [GroupCard] (`md` + hairline + soft shadow); the 其它 block keeps the
 * disabled `Skin.ListItem` rows, labelled not faked (devices / manage-core stay U1-f/T5b).
 *
 * U1-model · the old disabled 「模型」 placeholder row is retired: the 默认模型 section below
 * hosts the real picker (global-default layer of D101). Reads/writes go through the injected
 * [modelActions] seam — unwired (this round), an in-memory fixture implementation keeps it
 * interactive and labelled.
 * A5 · the 关于 row is wired: it opens [AboutPage] as a full-screen subpage (D91) showing
 * the injected version / build time / licence list.
 *
 * The 底栏 copy is updated for v2: the shell no longer calls the skin's gradient BottomBar
 * at all — all three forms are real `GlassSurface` glass; the style picks docking vs the
 * floating capsule (see `shell/GlassNavBar.kt`).
 *
 * [contentPadding] is the shell's floating glass bar zones (see `shell/AppShell.kt`).
 */
@Composable
fun ProfileScreen(
    skinId: String,
    barStyle: BottomBarStyle,
    containerLabel: String,
    onSkinChange: (String) -> Unit,
    onBarChange: (BottomBarStyle) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /**
     * The model seam (RFC-0005): `model/list` / `session/model.get` / `session/model.set`.
     * Null = use the in-memory fixture (假数据) — see `vade.ui.model.ModelActions` for the
     * one-object wiring path.
     */
    modelActions: ModelActions? = null,
    /**
     * 组合根交下来的适配器包（`data/AdapterProvider`）。默认 null ⇒ 走到内存假实现，
     * 行为与接线前逐字节相同（`AppShell` 这一轮还没接线）。
     *
     * 三段回退：[modelActions]（形参）> [adapters] > `InMemoryModelActions()`。
     * 设置页要的是**全局默认**视图，所以取的是 `modelDefault()` 而**不是** `model("")`：
     * `AdapterProvider` 把两者刻意分成两个方法，用错一个就会拿到「没有会话上下文」的
     * 会话级适配器（`model(sessionId)` 的语义是某个会话的覆盖值）。
     */
    adapters: AdapterProvider? = null,
) {
    val skin = LocalSkin.current
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
    Box(
        modifier
            .fillMaxSize()
            .background(skin.colors.canvas),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        SectionLabel("外观")
        GroupCard {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                UiText("皮肤", size = 13, weight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkinChip("material", "经典", skinId, onSkinChange)
                    SkinChip("miuix", "Miuix", skinId, onSkinChange)
                    SkinChip("glass", "玻璃", skinId, onSkinChange)
                }
                // 用户可读名在按钮上，内部 id 退到下一行次级位置（SkinRegistry 的主键是
                // material / miuix / glass —— 开发要看得到，用户不该被这串小写英文拦下）。
                UiText(
                    text = "miuix 是完整实现：自有 token、真字体缝（VadeFonts，Web 也走同一族）" +
                        "与 12 个 C-09 组件，不是 material 换色；glass 仍是占位（复用 material token，" +
                        "行内标「glass 未安装」），U3 注册真实现后自动可用。",
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
                UiText(
                    text = "内部 id：material · miuix · glass（皮肤注册表主键；当前 $skinId）",
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                UiText("底栏", size = 13, weight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BarChip("fixed", BottomBarStyle.Fixed, barStyle, onBarChange)
                    BarChip("floating", BottomBarStyle.Floating, barStyle, onBarChange)
                    BarChip("floating+blur", BottomBarStyle.FloatingBlur, barStyle, onBarChange)
                }
                UiText(
                    text = "v2：底栏由 shell 直接渲染为 GlassSurface 真模糊（haze 24dp + 26%/34% 染色），" +
                        "样式只切换停靠 / 悬浮胶囊形态；旧「半透明渐变假模糊」已弃用。",
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                UiText("当前容器：$containerLabel", size = 11, color = skin.colors.onSurfaceVariant)
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
                skin.ListItem(title = "管理此 Core", subtitle = "内置 WebView，只加载本地资源（T5b）", enabled = false)
                // A5 · was a disabled U1-f placeholder; now opens the full-screen 关于 subpage.
                skin.ListItem(
                    title = "关于",
                    subtitle = "版本 ${VadeBuildConfig.VERSION} · 许可与构建信息",
                    onClick = { showAbout = true },
                )
            }
        }
            UiText(
                text = "设置项打开为全屏子页（D91），不做弹层——关于已落地（A5），其余随 U1-f。",
                size = 11,
                color = skin.colors.onSurfaceVariant,
            )
            Spacer(Modifier.padding(bottom = 8.dp))
        }
        if (showAbout) {
            AboutPage(onBack = { showAbout = false }, contentPadding = contentPadding)
        }
    }
}

/**
 * ② 设置页的模型 seam：**形参 > `adapters.modelDefault()` > 内存假实现**。
 *
 * 设置页要的是**全局默认**视图，所以取 `modelDefault()` 而**不是** `model("")`：
 * `AdapterProvider` 刻意把两者分成两个方法，`model(sessionId)` 的语义是「某个会话的覆盖值」，
 * 拿它冒充全局默认会让设置页显示一个不属于任何会话的覆盖值。独立成函数是为了让屏幕与
 * 测试跑同一段代码（把这一行改成 `model("")`，`profileAsksForTheGlobalDefault…` 必红）。
 */
internal fun resolveProfileModelActions(
    explicit: ModelActions?,
    adapters: AdapterProvider?,
): ScreenSeam<ModelActions> = pickScreenSeam(
    explicit = explicit,
    adapters = adapters,
    lookup = { modelDefault() },
) { InMemoryModelActions() }

@Composable
private fun SkinChip(
    id: String,
    label: String,
    current: String,
    onSelect: (String) -> Unit,
) {
    // w-g-icons：选中标记不再往文字里塞 `●` 字符（v2 §3 禁字符图标）——选中态已由
    // BtnStyle.Primary（accent 实底）与 Secondary 的对比表达，重复标记只会加噪音。
    // [label] 是用户可读名（经典 / Miuix / 玻璃），[id] 是 SkinRegistry 主键；列表里只
    // 显示前者，后者退到下面那行 11sp 的「内部 id」。
    LocalSkin.current.Button(
        text = label,
        onClick = { onSelect(id) },
        style = if (id == current) BtnStyle.Primary else BtnStyle.Secondary,
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
    Column(
        modifier
            .fillMaxSize()
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
                    .size(32.dp)
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
