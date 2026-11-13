package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.skin.LocalSkin

/**
 * 能力 (U1-a skeleton): modules / skills / memories, plus the mobile "模块页面" entry
 * point that D105 puts in this tab.
 *
 * FR-8.79: when no module declares `ui.pages[]`, the 模块页面 entry must **not** appear —
 * no empty tab, no placeholder row. Since C-19 is not frozen (U1-h), this screen shows
 * exactly that: the sections exist, the entry is absent, and the copy explains why.
 */
@Composable
fun CapabilitiesScreen(modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    Column(
        modifier.fillMaxSize().background(skin.colors.surface).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        UiText("模块", size = 11, weight = FontWeight.SemiBold, color = skin.colors.onSurfaceVariant)
        SectionCard(
            title = "core 未连接",
            body = "U1-e 接 module.status 后，这里显示模块卡片（名称 / 版本 / 权限 / 状态）。" +
                "本切片没有任何真实模块，所以不给假卡片。",
        )

        UiText("技能", size = 11, weight = FontWeight.SemiBold, color = skin.colors.onSurfaceVariant)
        SectionCard(
            title = "技能页是原生页（D99）",
            body = "U1-e 复用 FR-9.40 三步；此处只留入口位置。",
        )

        UiText("记忆", size = 11, weight = FontWeight.SemiBold, color = skin.colors.onSurfaceVariant)
        SectionCard(
            title = "暂无记忆条目",
            body = "删除需二次确认（U1-e）。",
        )

        UiText("模块页面", size = 11, weight = FontWeight.SemiBold, color = skin.colors.onSurfaceVariant)
        SectionCard(
            title = "入口不显示",
            body = "没有声明 ui.pages[] 的模块 → 按 FR-8.79 不显示入口（不是空列表）。" +
                "C-19 冻结后 U1-h 在这里列出「模块 + 页面标题」。",
        )
    }
}

@Composable
private fun SectionCard(title: String, body: String) {
    val skin = LocalSkin.current
    skin.Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UiText(title, size = 12, weight = FontWeight.SemiBold)
            UiText(body, size = 11, color = skin.colors.onSurfaceVariant)
        }
    }
}
