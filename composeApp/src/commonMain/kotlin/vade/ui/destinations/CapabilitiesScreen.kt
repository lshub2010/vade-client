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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vade.ui.module.ModulePageEntry
import vade.ui.module.ModulePageEntryList
import vade.ui.module.ModulePageEntryRules
import vade.ui.module.ModulePageHost
import vade.ui.module.ModulePageSource
import vade.ui.module.ModulePageSources
import vade.ui.skin.LocalSkin

/**
 * 能力 (U1-a skeleton), v2 form: group cards `md` + hairline + soft shadow, empty states
 * via [EmptyStateCard] (centered, 48dp icon, 20sp title, 14sp muted body, no invented
 * data). Modules / skills / memories, plus the mobile "模块页面" entry point that D105
 * puts in this tab.
 *
 * FR-8.79 (C-19 已实现)：声明了 `ui.pages[]` 的模块在这里得到「模块 + 页面标题」入口，
 * 点开就地进入 [ModulePageHost]（页内导航，不新增一级目的地——FR-8.77 四个 tab 不变）；
 * 未声明页面的模块**不渲染整个区块**——没有空 tab、空列表、灰掉的占位行（§8.2）。
 * 数据经 [pageSource]（本轮内存示例树驱动；接真协议只需实现该窄接口，见模块包 KDoc）。
 *
 * [contentPadding] is the shell's floating glass bar zones (see `shell/AppShell.kt`).
 */
@Composable
fun CapabilitiesScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    pageSource: ModulePageSource = ModulePageSources.demo(),
) {
    val skin = LocalSkin.current
    val entryIndex = remember(pageSource) { ModulePageEntryRules.build(pageSource.declarations()) }
    var openEntry by remember { mutableStateOf<ModulePageEntry?>(null) }

    val opened = openEntry
    if (opened != null) {
        ModulePageHost(
            entry = opened,
            source = pageSource,
            onBack = { openEntry = null },
            modifier = modifier,
            contentPadding = contentPadding,
        )
        return
    }

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

        // FR-8.79 / C-19 §8.2：没有声明 ui.pages 的模块 → 这里什么都不出现。
        // 区块整体只在入口集合非空时渲染（EntryIndex.isEmpty 覆盖「无分组且无隐藏页」）。
        if (!entryIndex.isEmpty) {
            SectionLabel("模块页面")
            ModulePageEntryList(
                index = entryIndex,
                onOpen = { openEntry = it },
            )
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }
}
