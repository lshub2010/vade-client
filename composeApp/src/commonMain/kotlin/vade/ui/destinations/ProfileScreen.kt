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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.buildconfig.VadeBuildConfig
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
) {
    val skin = LocalSkin.current
    val modelActionsResolved = modelActions ?: remember { InMemoryModelActions() }
    val modelState = remember(modelActionsResolved) { ModelState.forDefault(modelActionsResolved) }
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
                    SkinChip("material", skinId, onSkinChange)
                    SkinChip("miuix", skinId, onSkinChange)
                    SkinChip("glass", skinId, onSkinChange)
                }
                UiText(
                    text = "miuix / glass 是占位实现（返回 material token + 未安装角标），" +
                        "U2/U3 注册真实现后自动可用。",
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
                "· 假数据：未接 core，接真数据只需实现 vade.ui.model.ModelActions",
        )

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

@Composable
private fun SkinChip(id: String, current: String, onSelect: (String) -> Unit) {
    // w-g-icons：选中标记不再往文字里塞 `●` 字符（v2 §3 禁字符图标）——选中态已由
    // BtnStyle.Primary（accent 实底）与 Secondary 的对比表达，重复标记只会加噪音。
    LocalSkin.current.Button(
        text = id,
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
