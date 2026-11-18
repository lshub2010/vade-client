package vade.ui.destinations

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.chat.ChatPreset
import vade.ui.chat.ChatState
import vade.ui.chat.ChatThread
import vade.ui.chat.InMemoryAttachmentUploader
import vade.ui.chat.InMemoryTurnGateway
import vade.ui.chat.StatusPill
import vade.ui.chat.Tone
import vade.ui.chat.observePointerTap
import vade.ui.data.AdapterProvider
import vade.ui.data.SessionSummary
import vade.ui.model.InMemoryModelActions
import vade.ui.model.ModelActions
import vade.ui.model.ModelComposerChip
import vade.ui.model.ModelPickerSheet
import vade.ui.model.ModelState
import vade.ui.skin.LocalSkin

/**
 * 单栏空态的「打开会话列表」出口，由**布局宿主**供给（审查 P1-11）。
 *
 * 为什么是 CompositionLocal，而不是只加形参：手机（<600dp）的空态**不是** `AppShell`
 * 直接组合出来的 —— 它躺在 `SessionsDrawerHost` 的常驻底层里（`shell/RouteTransition.kt`），
 * 那一层拿不到 `AppShell` 手里的 `UiState`；而「列表」恰好就在同一个宿主里（盖在详情
 * 之上的抽屉）。所以「打开列表」只能由**那个布局层**提供动作。`AppShell` 的手机分支
 * provide 一次，底层那份详情（含 `DrawerDetailBase`）自动拿到，不必在两处各写一遍
 * 动作、更不必让屏幕去猜自己跑在哪种布局里（J4 的教训）。
 *
 * 解析顺序：[SessionDetailScreen] 的 `onOpenList` 形参 > 本 local > 无。
 * 没人 provide ⇒ 空态**不画**按钮：点了没反应的按钮比没有按钮更糟（§8.5）。
 */
internal val LocalSessionListOpener = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * 「消息列表 + composer」这条**内容链**的 testTag（m-bar · 审查 P1-1）。
 *
 * 底栏的下留白只加在这一层上（[SessionDetailScreen] 的 `bottomBarInset`），它是容器满高与
 * 「内容让栏」两件事的分界线：测试量它的底边 = 窗口底 − (barZone − composer 的导航条余量)，
 * 同时量抽屉 / 遮罩 / 模型面板仍然是**满高**（它们的底边 == 窗口底）。
 */
internal const val DetailContentInsetTag = "vade.detail.content.inset"

/**
 * 会话 · 详情 (U1-b).
 *
 * The screen owns nothing but a [ChatState] (one per session, dropped with the screen) and the
 * reading column. The shell already supplies the D88 half of the contract — on a phone this
 * screen is pushed full screen and the bottom bar is not composed at all — so what is left here
 * is the header, the thread ([ChatThread]) and the composer it hosts.
 *
 * [preset] / [animate] exist for the real-window screenshot loop and for tests; `Live` is what
 * the app uses, and it is the only mode where the stream advances by itself.
 *
 * U1-model → g-model-chip · the model switcher moved OUT of the page flow: the header model
 * line is plain information again (what would answer, message count, degraded reason), the
 * TRIGGER is the [ModelComposerChip] the composer hosts in a **second row** (see
 * g-composer-2row below), and tapping it opens the [ModelPickerSheet] — the §9 floating
 * sheet card — over the whole screen. Nothing pushes the transcript anymore. All model
 * reads/writes go through the injected [modelActions] seam; when the shell has not wired
 * one yet (this round), an in-memory fixture implementation keeps the picker fully
 * interactive and honestly labelled.
 *
 * g-composer-2row · the second composer row is **collapsed by default** and only appears
 * once the reader touches the composer: the page owns the `composerExpanded` flag (reset on
 * every session entry / switch), `ChatThread` reports a completed tap inside the composer and
 * the page root reports taps outside it — both by observing a pointer tap (press + release
 * within touch slop) in the Initial pass without consuming (the frozen `Skin.InputBar` has no
 * focus/modifier slot, so a real focus signal is not available; see
 * `vade.ui.chat.observePointerTap` for the approximation and its limits).
 */
@Composable
fun SessionDetailScreen(
    session: SessionSummary?,
    modifier: Modifier = Modifier,
    preset: ChatPreset = ChatPreset.Live,
    animate: Boolean = true,
    /**
     * `false` when the shell's top bar already shows this session's title (the `<600dp`
     * pushed-detail case): the page then draws only the model/count line, so the title is
     * not printed twice within 80dp. Two-pane keeps it — there the top bar reads 会话.
     */
    showTitle: Boolean = true,
    /**
     * The model seam (RFC-0005): `model/list` / `session/model.get` / `session/model.set`.
     * Null = use the in-memory fixture (假数据) — see `vade.ui.model.ModelActions` for the
     * one-object wiring path.
     */
    modelActions: ModelActions? = null,
    /**
     * 组合根交下来的适配器包（`data/AdapterProvider`，见 `VadeAdapters.kt`）。
     *
     * **默认 null 是有意的**：`AppShell` 这一轮还没接线（它正被另一棒改），所以四处调用点
     * 都取默认值 ⇒ 模型 actions、turns、uploader 三件仍落在内存假实现上，行为与接线前逐字节相同。
     * 三段回退：[modelActions]（形参）> [adapters] > 内存假实现，见 [pickScreenSeam]；
     * 适配器查找抛异常时退回假实现并在 header 里显示一行可读原因（不静默吞）。
     */
    adapters: AdapterProvider? = null,
    /**
     * The shell's floating glass top-bar zone (see `shell/AppShell.kt`), applied as
     * **content** padding: the thread runs edge-to-edge from the very top of the window so
     * messages really scroll behind the glass bar (visual language §5 — a blur with nothing
     * behind it reads as a solid strip, which is exactly the 「看不出有模糊」 complaint).
     * Container padding would crop the viewport below the bar and no row could ever pass
     * under it.
     */
    contentPadding: PaddingValues = PaddingValues(0.dp),
    /**
     * 消息列表 + composer 这条**内容链**的下内边距（m-bar · 审查 P1-1/P1-2）。
     *
     * 为什么是形参而不是 `contentPadding.bottom`：composer 是贴屏底的浮层，它自己已经加了
     * `16.dp + composerNavClearance()`；把栏区塞进 `contentPadding` 既到不了 composer
     * （本屏只读 `contentPadding.top`），也会让 `ModelPickerSheet` / 图片预览这些 `fillMaxSize`
     * 的兄弟跟着一起被抬离屏底。所以留白只加在**装着 ChatThread 的那一层**上，浮层与遮罩
     * 留在它外面 ⇒ 永远贴屏底。
     *
     * 期望值 = `barZone − composer 自己的导航条余量`（导航条只算一次，见
     * `shell/AppShell.kt` 的 `detailContentBottomInset`）；默认 0 dp = 不垫（双栏 / rail /
     * 抽屉展开态）。
     */
    bottomBarInset: Dp = 0.dp,
    /**
     * 本屏有**模态浮层**（模型面板 / 图片预览）开着时上报 `true`（m-bar · 审查 P1-2）。
     *
     * 浮层的状态在页面内部（`modelPickerOpen`）与 ChatState 里（`chat.preview`），布局宿主
     * 看不到；底栏却是宿主的兄弟、画在整条内容链之上 —— 不上报的话面板开着还能点底栏切页，
     * 把面板连人一起端掉（模态泄漏）。宿主据此把内容链抬到底栏之上，遮罩才真的盖住底栏。
     */
    onOverlayChange: ((Boolean) -> Unit)? = null,
    /**
     * Shell-owned failure banner (g-error-retry) for the **message-thread** read. A slot, so
     * the screen keeps knowing nothing about the loader; the retry inside it calls
     * `UiState.retryMessages()`, never the list retry.
     */
    banner: (@Composable () -> Unit)? = null,
    /**
     * 空态形状（审查 P1-5）。`true` = 单栏 / 手机抽屉宿主（没有「左侧」，列表是盖在详情
     * 之上的抽屉）；`false` = 左侧真有列表的双栏，文案沿用「在左侧选择一个会话」。
     *
     * 默认 `null` = 不额外接线也跟着既有事实走：`showTitle == false` 的调用点（手机
     * pushed detail、`SessionsDrawerHost` 的常驻详情）全都不是双栏，双栏那一路保留标题。
     * 宿主想钉死形状时显式传值即可。
     *
     * 为什么**不按本屏宽度**猜：双栏时这里拿到的 `maxWidth` 是**详情 pane** 的宽度
     * （720dp 窗口下只剩 360dp），任何阈值都会把双栏误判成单栏 —— 而双栏的空态是桌面上
     * 真能看见的那一个。判据只能是「本屏有没有左侧列表」。
     *
     * J4 的教训是「别拿布局参数判**数据真假**」；这里判的正是布局本身（本屏有没有左侧
     * 列表），`showTitle` 在这两条路径上的取值也不是巧合：双栏保留标题、单栏路径都把
     * 标题让给外壳顶栏。
     */
    compactEmpty: Boolean? = null,
    /**
     * 单栏空态的唯一出口：滑出会话列表。`null` = 用布局宿主提供的
     * [LocalSessionListOpener]（手机抽屉宿主就走这条）；两者都没有 ⇒ 不画按钮：
     * 一个点了没反应的按钮比没有按钮更糟（§8.5）。
     */
    onOpenList: (() -> Unit)? = null,
) {
    if (session == null) {
        EmptyDetail(
            modifier = modifier.fillMaxSize(),
            compact = compactEmpty ?: !showTitle,
            // 形参（显式注入，测试/特殊宿主）> 布局宿主（`AppShell` 手机分支 provide）。
            onOpenList = onOpenList ?: LocalSessionListOpener.current,
        )
        return
    }

    // ④ turns / uploader：两条通道同源（同一个 AdapterProvider），一起换或一起退回内存实现。
    // 适配器变化时重建（连接建立/断开后旧 client 已 close，通道不能跨连接复用）。
    val chatSeam = remember(session.id, preset, adapters) {
        resolveChatState(session.id, preset, adapters)
    }
    val chat = chatSeam.value
    val modelSeam = remember(session.id, modelActions, adapters) {
        resolveSessionModelActions(modelActions, adapters, session.id, session.model)
    }
    val actions = modelSeam.value
    val modelState = remember(actions, session.id) { ModelState.forSession(actions, session.id) }
    var modelPickerOpen by remember(session.id) { mutableStateOf(false) }
    // g-composer-2row · 第二行（模型 chip）的展开态，**本页持有**：
    //  - 进会话默认收起（`remember(session.id)` 的初始值就是 false）；
    //  - 切会话收起（key 换了 = 新一份记忆，不会把上一个会话的展开态带过去）；
    //  - 发送后保持展开（本页只在「点外部」与「切会话」两处写 false）；
    //  - 点输入区展开：由 `ChatThread(onComposerFocusChange = …)` 从指针近似焦点报上来。
    var composerExpanded by remember(session.id) { mutableStateOf(false) }

    // J4（alpha 审查）· 「假数据」只由**数据来源**决定，与布局无关：
    //  - 没有适配器包 = 三段回退落到内存假实现（`pickScreenSeam`）；
    //  - 适配器查找抛过异常 = 已退回假实现（`degraded` 非 null）。
    // 以前这句话挂在 `showTitle` 上（手机 pushed detail 传 false）→ 真源也显示、
    // 手机反而不显示，正好与事实相反。现在标题仍受 showTitle 控制，事实行不受。
    val fixtureData = adapters == null || modelSeam.degraded != null || chatSeam.degraded != null

    // Full-bleed stack: the thread spans the whole window (scrolling under glass bar and
    // header alike); the header floats *below* the glass bar zone as the later sibling and
    // stays opaque, so its own content remains readable while rows slide under it.
    // g-model-chip: the model sheet is the TOPMOST sibling — an overlay over everything
    // (header, thread, composer), never an inline block in the header column.
    //
    // m-bar（审查 P1-2）：下留白只加在**装着 ChatThread 的那一层**（消息列表 + composer 的
    // 内容链）上。表头、模型面板、图片预览都是它的**兄弟**，所以浮层永远是满屏的 —— 容器
    // 留白时代它们会跟着被抬离屏底 104–132 dp（遮罩在屏底上方硬切一刀）。
    val overlayOpen = modelPickerOpen || chat.preview != null
    val latestOnOverlayChange = rememberUpdatedState(onOverlayChange)
    // 上报给布局宿主（底栏据此让路）。`chat.preview` 是 ChatState 的 snapshot 字段，读出即观察。
    LaunchedEffect(overlayOpen) { latestOnOverlayChange.value?.invoke(overlayOpen) }
    // 离开组合（切会话 / 退出详情）时把宿主的状态收回 false：否则底栏会永久被压在下面。
    DisposableEffect(Unit) { onDispose { latestOnOverlayChange.value?.invoke(false) } }

    Box(
        modifier
            .fillMaxSize()
            // g-composer-2row · 「点 composer 外部收回」。与 composer 内部用的是同一个
            // 观察者（Initial、观察一次点击、不消费），差别只在位置：这里是页面根，因此
            // **任何**一次点击都会先把展开态置 false —— 同一次手势里 composer 自己随后
            // 再置 true（父 → 子的 Initial 顺序），于是「点输入区」= 展开、「点别处」= 收起。
            // 不包 `clickable`：那会吃掉 header / 消息列表自己的点击。
            //
            // 例外：模型浮层（[ModelPickerSheet]）打开期间不收回。那张卡是模态的，点它
            // 是在**用浮层**，不是在把 composer 让出去；否则关掉浮层后模型行无声消失。
            .observePointerTap { if (!modelPickerOpen) composerExpanded = false },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .testTag(DetailContentInsetTag),
        ) {
            ChatThread(
                state = chat,
                modifier = Modifier.fillMaxSize(),
                animate = animate,
                bottomBarInset = bottomBarInset,
                // g-composer-2row：模型 chip 从 `inputLeading`（面板底行）搬进第二行。
                // 第二行默认收起，点输入区才出现 —— 会话页一进来就是干净的一行 composer。
                modelRow = {
                    ModelComposerChip(
                        state = modelState,
                        expanded = modelPickerOpen,
                        onClick = { modelPickerOpen = true },
                    )
                },
                expanded = composerExpanded,
                onComposerFocusChange = { focused -> if (focused) composerExpanded = true },
            )
        }
        Column(Modifier.padding(top = contentPadding.calculateTopPadding())) {
            DetailHeader(
                session = session,
                chat = chat,
                showTitle = showTitle,
                fixtureData = fixtureData,
                modelState = modelState,
                degraded = modelSeam.degraded ?: chatSeam.degraded,
            )
            // Load-failure banner sits right under the header, above the thread: the reader
            // sees *why* the thread is empty before the (kept) old messages.
            banner?.invoke()
        }
        if (modelPickerOpen) {
            ModelPickerSheet(
                state = modelState,
                title = "会话模型",
                onClose = { modelPickerOpen = false },
                footerNote = "仅本会话生效，下一轮对话起使用（D101：会话级 > 全局默认 > priority）" +
                    (
                        // 假数据这句话只在**真的**用内存假实现时才成立；接上适配器后
                        // 模型面来自 core，继续说「未接 core」就是撒谎（§8.5）。
                        if (adapters == null || modelSeam.degraded != null) {
                            "· 假数据：未接 core，接真数据只需实现 vade.ui.model.ModelActions"
                        } else {
                            "· 模型面来自 core（AdapterProvider），写入走 session/model.set"
                        }
                        ) +
                    modelSeam.degraded?.let { " · $it" }.orEmpty(),
            )
        }
    }
}

/**
 * 详情空态（审查 P1-5）—— 两种形状说两种**事实**，不共用一句话：
 *
 *  - 双栏（`compact = false`）：列表真的在左边，所以「在左侧选择一个会话」是准确的指路；
 *  - 单栏（`compact = true`，手机抽屉宿主）：屏幕上没有「左侧」，列表是盖在详情之上的
 *    抽屉。文案换成「选一个会话开始」，并在宿主给了动作时给出唯一出口
 *    「打开会话列表」—— 否则用户面对的是一句指不到任何东西的提示（审查原话）。
 */
@Composable
private fun EmptyDetail(
    modifier: Modifier,
    compact: Boolean,
    onOpenList: (() -> Unit)?,
) {
    val skin = LocalSkin.current
    Box(modifier.background(skin.colors.surface), contentAlignment = Alignment.Center) {
        if (!compact) {
            UiText("在左侧选择一个会话", size = 12, color = skin.colors.onSurfaceVariant)
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                UiText("选一个会话开始", size = 15, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                UiText(
                    text = "会话列表盖在这一层上面，挑一条就能开始。",
                    size = 12,
                    color = skin.colors.onSurfaceVariant,
                )
                if (onOpenList != null) {
                    Spacer(Modifier.height(14.dp))
                    skin.Button("打开会话列表", onOpenList)
                }
            }
        }
    }
}

/**
 * Title + the two facts a chat header owes the reader in this slice: which model would answer
 * (D101 — read from [modelState], so a picker switch updates the line immediately) and whether
 * the data is fake. No 「已连接」 badge — U1-b has no socket, and a green dot
 * that means nothing is exactly the kind of lie §8.5 forbids.
 *
 * g-model-chip: the model line is INFORMATION again — the switcher affordance moved into the
 * composer chip (`ModelComposerChip`), so this row is no longer clickable and carries no
 * chevron (a tap affordance on a row that no longer toggles anything would be a lie).
 *
 * J4（alpha 审查）：[fixtureData] **只**由数据来源决定（`adapters == null` 或适配器查找
 * 退回过），与 [showTitle] 无关 —— 布局参数曾经兼职当「假数据」的判据，于是真源也显示、
 * 手机 pushed detail 反而静默。
 */
@Composable
private fun DetailHeader(
    session: SessionSummary,
    chat: ChatState,
    showTitle: Boolean,
    /** 本页是否真的跑在内存假实现上（判据在 [SessionDetailScreen]，不看布局）。 */
    fixtureData: Boolean,
    modelState: ModelState,
    /** 非 null = 适配器查找失败、本页已退回内存假实现；直接拼进模型行（不静默吞）。 */
    degraded: String? = null,
) {
    val skin = LocalSkin.current
    // With the shell top bar already naming the session, a second 15sp copy of the same
    // title 80dp lower reads as a rendering bug — so the compact form keeps the avatar and
    // one metadata line.
    val modelLine = buildString {
        append("模型 ${modelState.currentLabel()}")
        if (fixtureData) append(" · 假数据（未接 core）")
        append(" · ${chat.messages.size} 条消息")
        if (degraded != null) append(" · $degraded")
    }
    Column(Modifier.fillMaxWidth().background(skin.colors.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(skin.shapes.small))
                    .background(skin.colors.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                UiText(
                    text = session.title.take(1),
                    size = 14,
                    weight = FontWeight.SemiBold,
                    color = skin.colors.primary,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (showTitle) {
                    UiText(session.title, size = 15, weight = FontWeight.SemiBold, maxLines = 1)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UiText(
                        text = modelLine,
                        size = 11,
                        color = skin.colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (chat.isStreaming) {
                StatusPill("流式中", Tone.Accent)
            } else if (chat.queue.isNotEmpty()) {
                StatusPill("${chat.queue.size} 条排队", Tone.Accent)
            }
        }
        // 1px 发丝线（视觉规范 §1.2）。
        Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.hairline))
    }
}

// --- 适配器三段回退（g-wire-screens） -------------------------------------------------
//
// 结构结已由 g-adapter-bundle 解开：`data/AdapterProvider` 在组合根（`RealSessionSource`）
// 交出六件窄接口，`VadeClient` 一步都不外泄。这里只解决「屏幕怎么用它」：
//
//   **形参（显式注入） → adapters → 内存假实现**
//
// 三条硬约束决定了下边这两段代码的形状：
//  1. **形参默认 null**：`AppShell` 这一轮不接线（它正被另一棒改），所以四处调用点都取
//     默认值 ⇒ 走到内存假实现 ⇒ 行为与接线前逐字节相同；
//  2. **适配器查找失败不许崩、也不许静默吞**：查找本身是纯函数、正常不会抛，但组合期
//     抛异常会掀掉整棵树。于是 `catch (Exception)` 退回假实现，并把原因作为**可读文字**
//     交回屏幕（[ScreenSeam.degraded]）——各屏幕把这段文字渲染在既有的「假数据」旁边，
//     用户看到的是「已退回内存假数据」，不是把 fixture 当真的用；
//  3. **不 catch `Throwable`**：`Error`（OOM 等）必须继续上抛，只有可恢复的 `Exception`
//     才降级。

/** 三段回退的落点：值 + 退化原因（null = 没退化）。 */
internal data class ScreenSeam<out T>(
    val value: T,
    /** 非 null = 适配器查找抛了异常，[value] 是内存假实现；这段文字由屏幕直接渲染。 */
    val degraded: String? = null,
)

/** 退化文案的前半句（屏幕直接展示；原因附在括号里）。 */
internal const val SEAM_FALLBACK_NOTE: String = "适配器不可用，已退回内存假数据"

/**
 * 三段回退的公共实现：形参 > [adapters] > [fixture]。
 *
 * [lookup] 是**适配器包上的**查找（`model(sessionId)` / `modelDefault()` / `modulePages()`…），
 * 不是形参——形参在 [explicit] 里，且永远优先。
 */
internal fun <T : Any> pickScreenSeam(
    explicit: T?,
    adapters: AdapterProvider?,
    lookup: AdapterProvider.() -> T,
    fixture: () -> T,
): ScreenSeam<T> {
    if (explicit != null) return ScreenSeam(explicit)
    if (adapters == null) return ScreenSeam(fixture())
    return try {
        ScreenSeam(lookup(adapters))
    } catch (error: Exception) {
        ScreenSeam(fixture(), "$SEAM_FALLBACK_NOTE（${error.message ?: "查找异常"}）")
    }
}

/**
 * ④ 聊天两通道：`turns`（`message/edit_resubmit` + `turn/regenerate`）与 `uploader`
 * （`/ui/blob*`）**同源**——要么都来自同一个 [AdapterProvider]，要么都退回内存实现。
 *
 * 不做「一半真一半假」：真的 turns 配内存 uploader 会让附件消失得毫无理由，反之亦然。
 * 任一查找抛异常 ⇒ 两条一起退回内存实现并给出退化原因。
 */
internal fun resolveChatState(
    sessionId: String,
    preset: ChatPreset,
    adapters: AdapterProvider?,
): ScreenSeam<ChatState> {
    if (adapters == null) return ScreenSeam(inMemoryChatState(sessionId, preset))
    return try {
        ScreenSeam(
            ChatState(
                sessionId,
                preset = preset,
                uploader = adapters.uploader(),
                turns = adapters.turns(sessionId),
            ),
        )
    } catch (error: Exception) {
        ScreenSeam(
            inMemoryChatState(sessionId, preset),
            "$SEAM_FALLBACK_NOTE（${error.message ?: "查找异常"}）",
        )
    }
}

/** 接线前的形态：两个通道都是内存假实现（[ChatState] 的默认值也是它们，这里写明意图）。 */
private fun inMemoryChatState(sessionId: String, preset: ChatPreset): ChatState = ChatState(
    sessionId,
    preset = preset,
    uploader = InMemoryAttachmentUploader(),
    turns = InMemoryTurnGateway(),
)

/**
 * ① 会话详情的模型 seam：**形参 > `adapters.model(id)` > 内存假实现**。
 *
 * [seedModel] 是会话行上的假模型名（`SessionSummary.model`）：只在落回内存假实现时用来播种，
 * 让 picker 与 header 读同一条覆盖值。走真适配器时那条假播种**一个字段都不碰**。
 *
 * 独立成函数（而不是内联在 composable 里）有两个原因：屏幕与测试跑的是**同一段代码**
 * （变异掉 `model(id)` 这一行，测试必红），并且它是纯粹值映射、可以脱离组合直接断言。
 */
internal fun resolveSessionModelActions(
    explicit: ModelActions?,
    adapters: AdapterProvider?,
    sessionId: String,
    seedModel: String? = null,
): ScreenSeam<ModelActions> = pickScreenSeam(
    explicit = explicit,
    adapters = adapters,
    lookup = { model(sessionId) },
) {
    InMemoryModelActions(
        initialSessionOverrides = seedModel?.let { mapOf(sessionId to it) }.orEmpty(),
    )
}
