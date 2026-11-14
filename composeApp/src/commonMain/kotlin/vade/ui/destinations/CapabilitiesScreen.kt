package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vade.ui.skin.LocalSkin

/**
 * 能力 (U1-a skeleton), v2 form: group cards `md` + hairline + soft shadow, empty states
 * via [EmptyStateCard] (centered, 48dp icon, 20sp title, 14sp muted body, no invented
 * data). Modules / skills / memories, plus the mobile "模块页面" entry point that D105
 * puts in this tab.
 *
 * FR-8.79: when no module declares `ui.pages[]`, the 模块页面 entry must **not** appear —
 * no empty tab, no placeholder row. Since C-19 is not frozen (U1-h), this screen shows
 * exactly that: the sections exist, the entry is absent, and the copy explains why.
 *
 * [contentPadding] is the shell's floating glass bar zones (see `shell/AppShell.kt`).
 */
@Composable
fun CapabilitiesScreen(
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
        SectionLabel("模块")
        EmptyStateCard(
            title = "core 未连接",
            body = "U1-e 接 module.status 后，这里显示模块卡片（名称 / 版本 / 权限 / 状态）。" +
                "本切片没有任何真实模块，所以不给假卡片。",
        )

        SectionLabel("技能")
        EmptyStateCard(
            title = "技能页是原生页（D99）",
            body = "U1-e 复用 FR-9.40 三步；此处只留入口位置。",
        )

        SectionLabel("记忆")
        EmptyStateCard(
            title = "暂无记忆条目",
            body = "删除需二次确认（U1-e）。",
        )

        SectionLabel("模块页面")
        EmptyStateCard(
            title = "入口不显示",
            body = "没有声明 ui.pages[] 的模块 → 按 FR-8.79 不显示入口（不是空列表）。" +
                "C-19 冻结后 U1-h 在这里列出「模块 + 页面标题」。",
        )
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}
