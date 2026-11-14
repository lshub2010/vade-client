package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.skin.BtnStyle
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin

/**
 * 我的 (U1-a skeleton), v2 form — appearance is the only interactive block, because it is
 * the one thing this slice must be able to demonstrate at runtime: skin × bottom-bar form
 * swaps go through `CompositionLocal`, never through a rebuilt Activity/View tree.
 *
 * Group cards are [GroupCard] (`md` + hairline + soft shadow); the 其它 block keeps the
 * disabled `Skin.ListItem` rows, labelled not faked (models / devices / about belong to
 * U1-f and T5b).
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

        SectionLabel("其它")
        GroupCard {
            Column(Modifier.padding(vertical = 4.dp)) {
                skin.ListItem(title = "模型", subtitle = "会话级 > 全局默认 > manifest.priority（U1-f）", enabled = false)
                skin.ListItem(title = "设备与通知", subtitle = "设备吊销 FR-8.68（U1-f）", enabled = false)
                skin.ListItem(title = "管理此 Core", subtitle = "内置 WebView，只加载本地资源（T5b）", enabled = false)
                skin.ListItem(title = "关于", subtitle = "版本 / 许可 / E2E 标识（U1-f）", enabled = false)
            }
        }
        UiText(
            text = "设置项打开为全屏子页（D91），不做弹层——U1-f 落地。",
            size = 11,
            color = skin.colors.onSurfaceVariant,
        )
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun SkinChip(id: String, current: String, onSelect: (String) -> Unit) {
    LocalSkin.current.Button(
        text = if (id == current) "● $id" else id,
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
    LocalSkin.current.Button(
        text = if (style == current) "● $label" else label,
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
