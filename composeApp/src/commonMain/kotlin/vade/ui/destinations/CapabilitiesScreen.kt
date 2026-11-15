package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import vade.ui.UiText
import vade.ui.data.AdapterProvider
import vade.ui.data.ModuleCatalog
import vade.ui.data.ModuleCatalogState
import vade.ui.data.ModuleLoadError
import vade.ui.data.VadeEvent
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.module.ModuleDeclaration
import vade.ui.module.ModulePageBodyFetcher
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
 *
 * ## g-modules-wire：模块区块与入口列表都改成订阅「模块清单」
 *
 * 传入 [moduleCatalog] 后，本屏**不再**从 `pageSource.declarations()` 猜模块：
 *
 *  - **模块区块**按 [ModuleCatalogState] 三态渲染，且三态各不相同（加载中 / 失败+重试 /
 *    成功但 0 个模块）——**不是**同一张「暂无模块」空卡；
 *  - **模块页面入口**由 `Ready.declarations` 推出（`ModulePageEntryRules.build`），
 *    `remember(moduleCatalog, ready, source)` 的键里带 `ready`，所以 **`revision` 变化
 *    必然重新组合**并重建入口列表（只在首次组合读一次的实现过不了
 *    `CapabilitiesModulesTest.revisionChangeRecomposesTheEntryList`）；
 *  - [events] 是 `module.status` 的失效边：收到就 `catalog.onEvent`（→ 一次 `ui/modules`
 *    重拉）。**不新增任何事件 topic**，用冻结契约里唯一的模块事件当「清单可能变了」的信号。
 *
 * `moduleCatalog == null`（假数据源 / 本棒契约未同步的生产路径）时，行为与本轮接线前
 * **逐字节相同**：模块区块是既有的「core 未连接」占位，入口来自 `pageSource`（示例树）。
 *
 * [contentPadding] is the shell's floating glass bar zones (see `shell/AppShell.kt`).
 */
@Composable
fun CapabilitiesScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /**
     * 显式页面源（形参优先于 [adapters]）。null = 让下一段决定：
     * [adapters] 的 `modulePages()`，再不行才是内存示例树 [ModulePageSources.demo]。
     * 默认值从 `demo()` 改成 null 是这次接线的形状（三段回退），调用方传值的写法不变。
     */
    pageSource: ModulePageSource? = null,
    /**
     * 组合根交下来的适配器包（`data/AdapterProvider`）。默认 null ⇒ 仍用示例树，
     * 行为与接线前逐字节相同。
     */
    adapters: AdapterProvider? = null,
    /**
     * 模块清单通道（`data/ModuleCatalog`，一次 `ui/modules` 的订阅口）。默认 null ⇒
     * 保持既有占位与 `pageSource` 的声明快照，行为不变。
     *
     * 组合根的交法：`(source as? ModuleCatalogProvider)?.moduleCatalog`。本棒那里是
     * `null`（契约缺 `ui/modules`，见 `data/ModuleCatalog.kt` 的阻塞一节）。
     */
    moduleCatalog: ModuleCatalog? = null,
    /**
     * `module.status` 等 UI 侧事件的流（`SessionSource.observeEvents()`）。与
     * [moduleCatalog] 配对才有意义：没有清单通道就没有可失效的拉取。
     */
    events: Flow<VadeEvent>? = null,
) {
    val skin = LocalSkin.current
    // ⑤ 形参 > adapters.modulePages() > 示例树；适配器查找异常时退回示例树并显示原因。
    val seam = remember(pageSource, adapters) {
        resolveCapabilitiesPageSource(pageSource, adapters)
    }
    val source = seam.value

    // 在组合期读 catalog.state 就是**订阅**：`ModulesLoader.state` 是 mutableStateOf，
    // 于是 revision 变化 → 新 Ready 实例 → 本组合被无效化 → 下面的 entryIndex 重建。
    val catalogState = moduleCatalog?.state
    val ready = catalogState as? ModuleCatalogState.Ready
    val entryIndex = remember(moduleCatalog, ready, source) {
        if (moduleCatalog == null) {
            // 没有清单通道：维持原判据（页面源自己的声明快照，示例树就在这一路）。
            ModulePageEntryRules.build(source.declarations())
        } else {
            // 有清单通道：入口只认清单（Loading / Failed 时没有可显示的真入口）。
            ModulePageEntryRules.build(ready?.declarations.orEmpty())
        }
    }

    var openEntry by remember { mutableStateOf<ModulePageEntry?>(null) }

    /**
     * **清单的第一次拉取**（g-modulepage-wire 缺口 1 的接线）：单一负责方 = 本屏的首次进入。
     *
     * 为什么是这里、而不是 `RealSessionSource.connect()`：
     *  - `connect()` 的契约是「runner 已启动」，**不等** `Connected`；在那里 kick 会把
     *    一帧 `ui/modules` 打在一个可能还没建好的 socket 上，于是「一切正常」的连接会
     *    先闪一张**失败卡**（而且那张卡会一直留着，因为 `Failed` 不会自己好）。
     *  - 清单只有这一屏消费：懒拉取 = 用户从不开能力页就一帧不花；「清单过期」由
     *    `module.status` → [ModuleCatalog.invalidate] 负责，与「第一次拉取」是两件事。
     *  - 「恰好一次」的幂等性长在 loader 上（[ModuleCatalog.ensureLoaded]），不在本屏的
     *    `remember` 里：屏幕离开组合时 `remember` 会丢，而 loader 与连接同生命周期，
     *    所以反复进出本屏只拉一次。
     */
    LaunchedEffect(moduleCatalog) {
        moduleCatalog?.ensureLoaded()
    }

    // 失活边：module.status（events.json 唯一的模块 topic）→ 一次 ui/modules 重拉。
    // 不新增 topic、不把 payload 当清单用；抖动由 loader 的「在飞不重发 + revision 去重」吸收。
    LaunchedEffect(moduleCatalog, events) {
        val target = moduleCatalog ?: return@LaunchedEffect
        events?.collect { event -> target.onEvent(event) }
    }

    val opened = openEntry
    if (opened != null) {
        ModulePageHost(
            entry = opened,
            source = source,
            // 异步页面体：真页面源（`RealModulePageSource`）就是 [ModulePageBodyFetcher]，
            // 宿主于是会真发 `ui/page.get`；示例树/假源不实现它 → 只读缓存，行为不变。
            pageFetch = source as? ModulePageBodyFetcher,
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
        // 适配器查找失败时先说明白：示例树不是 core 的页面（不静默吞）。
        seam.degraded?.let { note ->
            UiText(note, size = 11, color = skin.colors.onSurfaceVariant)
        }

        SectionLabel("模块")
        when (catalogState) {
            // 没有清单通道（假源 / 契约未同步）→ 既有占位，一个字都没改。
            null -> EmptyStateCard(
                title = "core 未连接",
                body = "U1-e 接 module.status 后，这里显示模块卡片（名称 / 版本 / 权限 / 状态）。" +
                    "本切片没有任何真实模块，所以不给假卡片。",
            )
            ModuleCatalogState.Loading -> ModuleCatalogLoadingCard()
            is ModuleCatalogState.Failed -> ModuleCatalogFailedCard(
                error = catalogState.error,
                onRetry = { moduleCatalog.retry() },
            )
            is ModuleCatalogState.Ready ->
                if (catalogState.isEmpty) {
                    ModuleCatalogEmptyCard(catalogState.revision)
                } else {
                    ModuleCatalogListCard(catalogState)
                }
        }

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

// ---------------------------------------------------------------------- 三态卡片

/**
 * 加载中：一句「正在拉取」+ 明确说明「还没有结果」。
 *
 * 刻意**不**复用 [EmptyStateCard] 的「暂无」语气：`ui/modules` 在飞的时候我们并不知道
 * 有没有模块，把它画成空态就是替 core 背书一个它还没说的结论。
 */
@Composable
private fun ModuleCatalogLoadingCard(modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    GroupCard(modifier) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VadeIcon(
                    VadeIcons.Waiting,
                    contentDescription = "加载中",
                    size = 16.dp,
                    tint = skin.colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                UiText("正在拉取模块清单", size = 13, weight = FontWeight.Medium, color = skin.colors.onSurface)
            }
            UiText(
                "ui/modules 返回前不显示任何模块占位行 ——「还没有结果」不是「没有模块」。",
                size = 12,
                color = skin.colors.onSurfaceVariant,
            )
        }
    }
}

/** 失败：分类打头 + 原样 C-04 code + 详情 + hint + 一个真的会重发请求的重试按钮。 */
@Composable
private fun ModuleCatalogFailedCard(
    error: ModuleLoadError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    GroupCard(modifier) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VadeIcon(
                    VadeIcons.Failed,
                    contentDescription = "加载失败",
                    size = 16.dp,
                    tint = skin.colors.error,
                )
                Spacer(Modifier.width(8.dp))
                UiText("模块清单加载失败", size = 13, weight = FontWeight.SemiBold, color = skin.colors.error)
            }
            UiText(error.label, size = 13, color = skin.colors.onSurface)
            UiText(error.hint, size = 12, color = skin.colors.onSurfaceVariant)
            Row(
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(skin.colors.surfaceVariant)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VadeIcon(VadeIcons.Refresh, contentDescription = "重试", size = 16.dp, tint = skin.colors.onSurface)
                Spacer(Modifier.width(6.dp))
                UiText(error.retryLabel, size = 12, weight = FontWeight.Medium, color = skin.colors.onSurface)
            }
        }
    }
}

/**
 * 成功但 `modules` 为空：**这是 core 的结论，不是错误**。
 *
 * 文案带上 `revision`，让「拉到了第几版清单」这件事可核对 —— 与加载中、失败两种情况
 * 在字面上完全不同（三态可辨的那条判据）。
 */
@Composable
private fun ModuleCatalogEmptyCard(revision: Long, modifier: Modifier = Modifier) {
    EmptyStateCard(
        icon = VadeIcons.Empty,
        title = "core 报告没有模块",
        body = "ui/modules 已成功返回（revision $revision），modules 是空数组 —— " +
            "这不是加载失败。装上模块后这里会出现卡片。",
        modifier = modifier,
    )
}

/** 成功且有模块：每个实例一行（显示名 / 身份 / 版本 / 在线态 / 页数）。 */
@Composable
private fun ModuleCatalogListCard(state: ModuleCatalogState.Ready, modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    GroupCard(modifier) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            UiText(
                "模块（${state.moduleCount}）· revision ${state.revision}",
                size = 11,
                weight = FontWeight.SemiBold,
                color = skin.colors.onSurfaceVariant,
            )
            for (module in state.declarations) {
                ModuleRow(module)
            }
        }
    }
}

@Composable
private fun ModuleRow(module: ModuleDeclaration) {
    val skin = LocalSkin.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            UiText(module.moduleTitle, size = 14, weight = FontWeight.Medium, color = skin.colors.onSurface)
            UiText(moduleSubtitle(module), size = 11, color = skin.colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        UiText(
            text = if (module.online) "在线" else "离线",
            size = 11,
            weight = FontWeight.Medium,
            color = if (module.online) skin.colors.onSurface else skin.colors.error,
        )
    }
}

/** 身份 + 版本 + 页数；缺失的版本写「版本未报」（不编一个版本号）。 */
internal fun moduleSubtitle(module: ModuleDeclaration): String {
    val version = module.moduleVersion?.let { "v$it" } ?: "版本未报"
    return "${module.moduleId} · ${module.instanceId} · $version · ${module.pages.size} 页"
}

/**
 * ⑤ 能力页的页面源：**形参 > `adapters.modulePages()` > 内存示例树**。
 *
 * 三段里最容易写错的是「拿到适配器还用示例树」——那时用户看到的 `下载管理` / `图表`
 * 是 fixture 编的模块，而 core 可能一个页面都没声明。独立成函数，屏幕与测试跑同一段代码；
 * 把 `modulePages()` 改回 `ModulePageSources.demo()` 会让
 * `capabilitiesTakesTheProviderPagesAndDropsTheDemo` 立刻红。
 */
internal fun resolveCapabilitiesPageSource(
    explicit: ModulePageSource?,
    adapters: AdapterProvider?,
): ScreenSeam<ModulePageSource> = pickScreenSeam(
    explicit = explicit,
    adapters = adapters,
    lookup = { modulePages() },
) { ModulePageSources.demo() }
