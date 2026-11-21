package vade.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import vade.ui.chat.ApprovalOutcome
import vade.ui.icon.VadeIcons
import vade.ui.shell.BackLayer
import vade.ui.skin.AttachmentState
import vade.ui.skin.LocalReduceMotion
import vade.ui.skin.LocalSkin
import vade.ui.skin.MessageView
import vade.ui.skin.ReduceMotion
import vade.ui.component.insets.composerNavClearance
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow
import vade.ui.skin.cardAccentBar
import vade.ui.skin.glass.GlassSurface
import vade.ui.skin.glass.LocalGlassHazeState
import vade.ui.glass.asGlassSource
import androidx.compose.ui.platform.testTag

/**
 * g-haze-structure · 本页 haze **source**（消息滚动列）的 testTag —— `GlassStructureGuardTest`
 * 用它在真实组合里断言浮层（composer / 附件预览）不在该节点的语义子树内。结构规则见
 * `shell/AppShell.kt` 的类 KDoc：玻璃层必须是 source 的兄弟。
 */
internal const val GlassSourceThreadTag = "vade.glass.source.chatThread"

/**
 * U1-b → v2 · 会话详情 — floating composer, out-of-frame send ball, icon-only controls,
 * inline approval card and the §9 floating sheet.
 *
 * v2 structure (docs/visual-language.md §1/§2/§3/§4/§9/§10):
 *  - the composer is an **independent floating layer**: gutter 16dp (desktop 24dp), 16dp
 *    above the bottom edge, `xl` 28dp corners, glass base + float shadow — all of it drawn
 *    by `Skin.InputBar` (C-09 component 11) since RFC-0011; this page only owns the row
 *    layout, the 16/24dp gutter and the list's bottom padding = measured composer height
 *    + 24dp, so the newest row is never covered when pinned to bottom;
 *  - send is `Skin.SendOrb` (C-09 component 12, RFC-0011) — the 48dp ball OUTSIDE the
 *    input frame (12dp gap, vertically centred): accent solid + glow when armed (the page
 *    derives `mode` from `ChatState.inputMode`), glass + hairline when empty, the same
 *    slot turns into the ■ stop control while streaming; press scales 0.92 over 180ms —
 *    every pixel of the ball is the skin's, not this page's;
 *  - **no text buttons anywhere** — every control is a drawn glyph inside a ≥44dp touch
 *    target with a full `contentDescription`;
 *  - approvals are **inline timeline cards** (卡头 需要批准 + risk badge / 卡体 args 预览 /
 *    卡尾 ✓ ✕ ⏷), evolving in place 待批准 → 已允许/已拒绝/已超时 without adding a row;
 *    the skin's `ApprovalDialog` stays unused here (module pages / admin keep it);
 *  - the fullscreen image preview is a **§9 floating sheet**: 12dp gaps on all sides,
 *    `sheet` 32dp corners, grabber 36×4dp, ✕ close — never an edge-to-edge dialog;
 *  - motion: composer height 200ms, ball press 180ms, card insert fade + 12dp slide 180ms,
 *    all on `Skin.motion.easing`. No motion blur on this page (§11 dropped — plain scroll).
 *
 * **双行 composer**（g-composer-2row，默认路径不变）：`modelRow` + `expanded` 都不传 /
 * `expanded = false` 时，composer 就是上面那一行，与今天逐字节一致。两者同时成立时，
 * 输入面板内部多出**第二行**（`Skin.InputBar(secondRow = …)` 追加槽）：输入行（附件 /
 * 输入 / 语音）在上，页面控件（模型 chip 等）在下，**共用同一块面板底与圆角** —— 不是
 * 面板外另起一层 surface。展开 / 收起由页面持有（`expanded` 是纯形参），页面也只能通过
 * 指针近似焦点 —— 见 [observePointerTap]。
 *
 * Every control goes through `Skin.*` tokens or `skin/glass/GlassSurface`; the page
 * composes them from Compose-foundation primitives only (same escape hatch as `UiText`,
 * `StatusPill` and the markdown renderer) — no toolkit widgets, no direct toolkit colours.
 */
/** m-bar：composer 浮层的测试锚点（底边 = 窗口底 − 栏区 − 16dp）。 */
internal const val ComposerOverlayTag = "vade.chat.composer.overlay"

@Composable
fun ChatThread(
    state: ChatState,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    /**
     * Page-level control rendered inside the composer panel's bottom row through the
     * additive `Skin.InputBar(leading = …)` slot (g-model-chip): the session model chip
     * shares the send orb's visual line without adding composer height. Null = the frozen
     * composer row, byte-for-byte (AppShell callers that pass nothing are untouched).
     *
     * g-composer-2row：会话页已改走 [modelRow]（默认收起、点输入区才出现的第二行），
     * 这条槽位保留 —— 它描述的仍是「输入框底行」那个位置，其它调用点按需使用。
     */
    inputLeading: (@Composable () -> Unit)? = null,
    /**
     * m-bar 审查 P1-2/P1-3：底栏区在 composer 之外还要让出的高度（默认 0 = 旧行为）。
     *
     * 只加在 composer 浮层的底部 padding 上，**不**加在整个页面上：图片预览是 ChatThread
     * 内部的 fillMaxSize 兄弟，垫页面会把它一起抬离屏底。宿主（`AppShell`）传的是
     * [vade.ui.shell.barZoneFor] 的**整条栏区**，不扣任何 clearance（审查 P1-1）。
     */
    bottomBarInset: Dp = 0.dp,
    /**
     * g-composer-2row · 第二行的页面控件（模型 chip 等），经 `Skin.InputBar(secondRow = …)`
     * 画在**输入面板内部**、输入行下方。与 [expanded] 同时成立才组成；null = 完全不组成
     * 第二行（没有 Spacer、没有节点），composer 与今天逐字节一致（默认路径）。
     */
    modelRow: (@Composable () -> Unit)? = null,
    /**
     * g-composer-2row · 第二行是否展开。**页面的状态**（形参，不是本组件的内部记忆）：
     * 进会话 / 切会话由上层的 remember key 重建成 `false`，发送后保持 `true`。
     */
    expanded: Boolean = false,
    /**
     * g-composer-2row · 「用户点了 composer」的信号 —— 页面据此把 [expanded] 置 true。
     *
     * 现在只会收到 `true`（点在 composer 区域内）。`false` 是留给将来接真焦点的位置：
     * 冻结的 `Skin.InputBar` 没有 `onFocusChanged`，本组件只能从指针近似（见
     * [observePointerTap]），「点外部」由页面根的同一个观察者负责。null = 不观察指针，
     * 事件处理器一个都不挂（默认路径无副作用）。
     */
    onComposerFocusChange: ((Boolean) -> Unit)? = null,
) {
    val skin = LocalSkin.current

    // P2-2 · 视图状态**按会话整份重建**：滚动位置、跟随基线（items 数 / 末条长度 /
    // 两路实测高度）全在 [ChatViewport] 里，`state.sessionId` 是 remember key。
    // 切会话 = 换一份 0 基线的实例 —— 新会话从**最新一行**打开，既不继承上一个会话的
    // `scroll.value`（打开在历史中段），也不继承它的「有新内容」胶囊。同一会话的重组
    // （会话 id 与 density 都没变）拿回同一实例，阅读位置原地保留。
    val density = LocalDensity.current
    val nearBottomPx = with(density) { FollowThreshold.roundToPx() }
    val viewport = rememberChatViewport(state.sessionId, nearBottomPx)
    val scroll = viewport.scroll
    val follower = viewport.follower

    // Streaming: one chunk per frame-ish tick. `animate = false` freezes the state so the
    // screenshot loop captures a deterministic frame.
    // H2 · 两个 ticker 各自沉进**只含 effect、没有 UI 子节点**的叶子（[StreamTicker] /
    // [UploadTicker]）：键从 state 的派生标量来（[ChatState.streamingTickerKey] /
    // [ChatState.uploadTickerKey]）；页面作用域只剩 [ChatState.inputMode] 这一个派生标量读点
    // （它不会随 delta 通知）。其余对 `items` / `staged` 的读都在叶子或派生计算里。
    StreamTicker(state, animate)
    UploadTicker(state, animate)

    // B2 · 真源的两个挂载点：先开事件收集（不丢加载期间到达的 delta），再拉历史快照。
    // 假源的读通道是 null，整段短路（fixtures 就是内容）。`animate` **不参与 key**：
    // 截图（animate=false）看到的也必须是真内容，而不是永远停在「加载中」。
    LaunchedEffect(state) {
        if (!state.isRealSource) return@LaunchedEffect
        launch { state.collectLiveEvents() }
        state.loadHistory()
    }

    // K4 · 跟随滚动（FR-8.35）：**只在贴近底部时**新内容才把视图带到最新一行。
    //
    // `reverseScrolling = true` 让 offset 0 就是内容末端 —— 最新一行从第一帧起就在屏上，
    // 且 `scroll.value` 天然等于「离底距离」。所以：
    //  - 贴底（≤ [FollowThreshold]）时内容 / 列表底部内边距变了 → 钉回 0，最新行保持可见；
    //  - 用户上滚（> 阈值）→ **一次都不许拽回**，也**一个像素都不许漂**：内容在底部长了
    //    多少，就把 `scroll.value` 平移多少（[FollowScrollGate.onHeightChanged]），
    //    `maxValue - value` 不变 → 屏幕上读的那段原地不动（j1-scroll P1-9）；
    //  - 新内容只在**上滚**时举一枚底部胶囊，点它才回底。
    //
    // 观测拆成两条，**不能合并**（j1-scroll P1-10）：
    //  1. 位置：消息列 / composer 的实测高度。composer 变高会抬高列表底部内边距，同样
    //     会把文字上推，所以要补偿 —— 但它只是布局；
    //  2. 内容：items 数 + 末条正文长度。只有它算「有新内容落地」，才允许举胶囊。
    // 旧实现把 `composerHeightPx` 塞进同一个 `snapshotFlow`，上滚时输入框一变高就误报。
    //
    // 用 `snapshotFlow` 而不是 keyed `LaunchedEffect`：内容长度与高度按帧变化，keyed effect
    // 会在每次 key 变更时**取消在途滚动**，写永远落不了地。
    //
    // P2-2 · 三个收集器的 key 都带上 [viewport]：切换会话时它们必须跟着换到新实例上，
    // 否则闭包里攥着上一个会话的 `ScrollState` / gate，补偿会写进一个已经不在屏上的对象。
    val reduceMotion = LocalReduceMotion.current || ReduceMotion.enabled
    val capsuleMs = skin.motion.durationMs(skin.motion.quickMs, reduceMotion)

    LaunchedEffect(animate, viewport) {
        if (!animate) return@LaunchedEffect
        snapshotFlow { viewport.messagesHeightPx to viewport.composerHeightPx }
            .collect { (messages, composer) ->
                // 两列各记各的账；同一帧里都变了就各补各的增量，总和 = 内容总增长。
                scroll.applyFollow(follower.onHeightChanged(FollowHeightKind.Messages, messages, scroll.value))
                scroll.applyFollow(follower.onHeightChanged(FollowHeightKind.Composer, composer, scroll.value))
            }
    }

    LaunchedEffect(animate, viewport) {
        if (!animate) return@LaunchedEffect
        snapshotFlow {
            state.items.size to state.lastMessageBodyLength
        }.collect { (items, lastBodyLength) ->
            follower.onContentChanged(items, lastBodyLength, scroll.value)
        }
    }

    // 用户自己回到底部（滚轮 / 触摸）→ 胶囊收起、跟随恢复。
    //
    // H7 · 观测的是「在不在贴底区间」这个**布尔边沿**，不是像素值。`scroll.value` 是像素级
    // 状态：一次 800px 的惯性滑动会发 ~800 次，而 [FollowScrollGate.onScroll] 只在贴底且
    // 举着胶囊时写一次状态 —— 99% 的唤醒是纯空转（每次一个 flow 发射 + 一次协程恢复）。
    // 现在一次跨越只醒一次；`snapshotFlow` 本来就按值去重，`distinctUntilChanged` 把
    // 「只认边沿」这件事写在脸上。
    LaunchedEffect(animate, viewport) {
        if (!animate) return@LaunchedEffect
        snapshotFlow { follower.isAtBottom(scroll.value) }
            .distinctUntilChanged()
            .collect { atBottom ->
                // onScroll 只认「在不在贴底区间」这一个 bit：贴底给 0（离底距离 0，
                // 区间内），不贴底给阈值 + 1（区间外的最小值）。对它的判据
                // `valuePx <= nearBottomPx` 来说，两者与逐像素调用完全等价。
                follower.onScroll(if (atBottom) 0 else nearBottomPx + 1)
            }
    }

    val pageScope = rememberCoroutineScope()

    BoxWithConstraints(modifier.fillMaxSize().background(skin.colors.canvas)) {
        // 页面左右边距：手机 16 / 桌面 24（视觉规范 §4）。
        val gutter = if (maxWidth >= 600.dp) 24.dp else 16.dp

        // The scroll column runs the *full* height; the composer floats above it and the
        // trailing spacer keeps the newest row clear of it (§1: 高度 + 24dp).
        //
        // g-haze-structure: this column IS the page's haze **source** — registered here,
        // on the actual scrollable content, so the page's own floating glass layers (the
        // composer panel below and the §9 image-preview sheet, both siblings of this
        // column in the BoxWithConstraints stack) blur it instead of degrading to
        // near-solid. The source used to sit on the whole ShellBody Row in `AppShell`,
        // which made every in-page glass a descendant of its own backdrop — haze 2.1.0
        // does not blur an effect under its own source subtree.
        //
        // `reverseScrolling = true` is what makes this a chat log: offset 0 is the content
        // END, so the newest row is on screen from the **first frame** — no post-launch
        // frames needed. This matters because the capture harness (and any occluded window)
        // stops the frame clock a few hundred ms after launch: a "scroll to bottom" effect
        // that only runs on later frames never lands. The pin below keeps the same contract
        // (new content wins, FR-8.35) for the frames that do run.
        Column(
            modifier = Modifier
                .asGlassSource(LocalGlassHazeState.current)
                .fillMaxSize()
                .testTag(GlassSourceThreadTag)
                .verticalScroll(scroll, reverseScrolling = true)
                .padding(start = gutter, end = gutter, top = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    // j1-scroll (P1-9)：消息列实高 = 位置补偿的观测量。它只覆盖时间线
                    // 本体（下面的 composer 内边距是兄弟节点），所以 composer 与内容
                    // 各自记账、互不冒充 —— 胶囊只认内容那一路。
                    .onSizeChanged { viewport.messagesHeightPx = it.height },
                // 消息之间 20dp（视觉规范 §4）。
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                ThreadPhaseRow(state)
                // H1（**本轮不改，故意留着**）：这里是 `Column(verticalScroll)` + `forEach`
                // —— 没有虚拟化、没有 `key`。N 行就组合 N 行、测量 N 行，且 `items` 是单一
                // `SnapshotStateList`（写一个元素 = 读过它的作用域全部失效）。收益最大的一条，
                // 但这一轮不动它的理由是三件**语义**问题必须先想清楚，不是「改不动」：
                //  1. **跟随滚动**：跟随基线读 `items.size` 与末条正文长度、补偿读消息列实测
                //     高度（`FollowScrollGate` 的整本账）。`LazyColumn` 只有「已组合的行」，
                //     未组合的行不测量 ⇒ 上滚时的 `ShiftBy` 补偿会连续多帧误判「内容增长」；
                //     钉底也要从 `scrollTo(0)` 换成 `animateScrollToItem/requestScrollToItem`，
                //     与 `reverseScrolling` 的组合语义重写一遍（H2 的 ticker 也依赖它）。
                //  2. **选中与复制**：正文选区（`SelectionContainer` / `BasicText` 的
                //     selection）跨越未组合的行会断；`rememberCoalescedStreamText` 的合帧也
                //     以「这一行一直在组合」为前提。
                //  3. **内联编辑 / 工具卡展开**：`InlineEditCard`、`ApprovalCardRow`、
                //     `ToolRow` 的展开态与 hover/长按都是行内 `remember`。`LazyColumn` 换
                //     `key` 之后这些状态必须上提（否则滑出屏幕再滑回来会丢焦点/丢展开态），
                //     那是另一次结构改动，不该和性能修混在一个 commit 里。
                // 下轮单独做 H1：先补 `key(item.id)`（正确性，零成本），再按上面的顺序把
                // 跟随/选中/展开态搬进 `LazyColumn` 的 `LazyListState`。本轮 H2/H4/H7/H8
                // 已经把那 N 行的**每次重组的常数**降下来了（键不再抖、每行 O(1)），
                // 所以 H1 的收益仍然在、但不再是「每帧 N²」的量级。
                state.items.forEach { item ->
                    when (item) {
                        is ChatItem.Message -> MessageRow(state, item.message)
                        is ChatItem.Tool -> {
                            val call = item.call
                            if (isApprovalCard(call, state.approvalOutcome(call.id))) {
                                ApprovalCardRow(state, call)
                            } else {
                                ToolRow(state, call)
                            }
                        }
                    }
                }
                state.lastNotice?.let { notice ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        StatusPill(notice)
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
            // v2 §1：列表底部内边距 = composer 实测高度 + 24dp（滚到底时最后一条不被遮）。
            // 实测高度含 composer 自身的 padding —— 前提是 `onSizeChanged` 挂在链首
            // （见 [ComposerOverlay]；c6-insets 把原先挂在 `.padding` 后面的量法修了），
            // 所以 g-inset2 给 composer 底部叠的导航条 clearance 也 1:1 算进来：最后一条
            // 始终滚出 composer + 导航条区域。公式提成 [composerListSpacer] 供测试钉住。
            //
            // IME 性能（用户 2026-11-18「弹输入法卡」）：这个 Spacer **必须**单独成为
            // 一个组合作用域（[ComposerTailSpacer]）。以前 `viewport.composerHeightPx`
            // 是在 `ChatThread` 自己的内容 lambda 里读的 —— 键盘动画每帧都会推高
            // composer（`imePadding` + clearance），每次写入都会让**整个消息列**
            // （items.forEach { MessageRow(…) }）重组一遍。下沉之后每帧只重组这一个
            // Spacer，消息列表一帧都不重组。
            ComposerTailSpacer(viewport)
        }

        // 底栏**界限之外**的渐变模糊（用户 2026-11-18 第 2 条）：贴在底栏上沿、向上一路
        // 递减到输入框上沿 +8dp。只读本函数内部的 composer 高度，作用域不外溢；桌面 /
        // rail（没有底栏，`bottomBarInset == 0`）根本不组合。画在 composer **之前**：
        // 它是 composer 的背景，不能盖住输入热区。
        ComposerBottomBlurBand(viewport = viewport, bottomBarInset = bottomBarInset)

        ComposerOverlay(
            state = state,
            gutter = gutter,
            animate = animate,
            onHeightChanged = { viewport.composerHeightPx = it },
            inputLeading = inputLeading,
            bottomBarInset = bottomBarInset,
            modelRow = modelRow,
            expanded = expanded,
            // 只把「点了 composer」这一路送上去；页面自己的根节点负责「点外部」。
            onComposerTap = onComposerFocusChange?.let { report -> { report(true) } },
            // 键盘/读屏进入不了「点输入区」这条指针路径；给 composer 一个无障碍动作，
            // 否则模型切换退化成仅指针可达（审查 P1-2）。
            onExpandRequest = onComposerFocusChange?.takeIf { modelRow != null && !expanded }
                ?.let { report -> { report(true) } },
        )

        // K4 · 「新内容」胶囊：只在**上滚且有新内容落地**时出现，贴在输入区上沿。入场是
        // 淡入 + 0.92 缩放（不是位移 —— reduced-motion 下位移类一律改纯淡入，K1）。
        //
        // IME 性能（n3-blur）：它读 composer 实测高度（键盘动画每帧变化），所以和
        // [ComposerTailSpacer] 一样必须自成一个组合作用域 —— 否则这一读会把整棵消息列
        // 一起拖进每帧重组。
        NewContentCapsuleHost(
            viewport = viewport,
            follower = follower,
            capsuleMs = capsuleMs,
            reduceMotion = reduceMotion,
            scroll = scroll,
            pageScope = pageScope,
        )

        // Fullscreen preview is an overlay of the page, not a platform dialog: the skin must
        // not decide windowing (same reasoning as the approval card above).
        state.preview?.let { image ->
            // g-back-handler: the preview owns a back layer while it exists — system back
            // (and desktop Esc, routed through the stack first) closes the preview, never
            // the app. Leaving composition (preview → null) pops the layer automatically.
            BackLayer(enabled = true, onBack = state::closePreview)
            ImagePreviewSheet(image, onClose = state::closePreview)
        }
    }
}

// --- H2 · ticker 叶子（只含 effect，没有 UI 子节点） ---------------------------

/**
 * H2 · 流式 ticker —— 独立成一个**没有 UI 子节点**的组合作用域（与 [ComposerTailSpacer]
 * 同一套路，仓库既有先例）。
 *
 * 为什么必须下沉：`LaunchedEffect` 的 key 在**调用方**作用域里求值。原先它写在
 * [ChatThread] 主体里、key 是 `state.isStreaming`，而它在真源上要扫 `items`
 * （[ChatState.realRunningTurnId] 的 `lastOrNull`）—— 于是每条 `turn.delta` 的 items 写入
 * 都让**整页**（`Column(verticalScroll)` 的 content lambda + `items.forEach`）重组一次。
 *
 * 两道防线：
 *  1. key 换成派生标量 [ChatState.streamingTickerKey]（`derivedStateOf`）：items 写入只让
 *     派生记录失效，布尔值不翻转就不通知读者 —— delta 一次都不重组、一次都不重启协程；
 *  2. 这个作用域只读那一个布尔，就算将来有人往这里加读点，代价也止步于「没有子节点的
 *     函数」而不是整棵消息列。
 *
 * 循环体与原来逐字相同（`while (isStreaming) { delay(45); tick(5) }`）；`tick` 返回 false
 * 表示「没有可写的行」—— 原来会让这个 45ms 循环空转到回合结束，现在直接退出（同一帧
 * 的屏上结果不变，少一条无意义的长活协程）。
 */
@Composable
private fun StreamTicker(state: ChatState, animate: Boolean) {
    val streaming = state.streamingTickerKey
    LaunchedEffect(animate, streaming) {
        if (!animate || !streaming) return@LaunchedEffect
        while (state.isStreaming) {
            delay(45)
            if (!state.tick(chunk = 5)) break
        }
    }
}

/**
 * H2 · 上传 ticker —— 同上，只含 effect。
 *
 * 键是 [ChatState.uploadTickerKey]（= chip 数量）：**取值与旧 key `staged.size` 逐字相同**，
 * 所以「加一个 chip 就开 tick、chip 清空就停」的时序没有变；变的只是读法 —— 进度写入
 * （`staged[i] = …`，每 160ms 一次，size 不变）不再让整页失效，也不再重建协程。
 *
 * 没有这条，chip 会永远停在「上传中 0%」：`tickUploads` 存在但只有测试驱动它。
 */
@Composable
private fun UploadTicker(state: ChatState, animate: Boolean) {
    val chips = state.uploadTickerKey
    LaunchedEffect(animate, chips) {
        if (!animate) return@LaunchedEffect
        while (state.tickUploads()) delay(160)
    }
}

// --- 三态行 (B2) --------------------------------------------------------------

/**
 * B2 · 加载中 / 空 / 失败（可重试）—— **三态各有各的文案与颜色**，不会都渲染成同一张
 * 空卡：加载中是中性提示、空态是「还没有消息」+ 怎么开始、失败是错误色 + 原因 + 「重试」。
 * 就绪且有消息时本行不组成任何东西（返回空），因此假源（fixtures 非空）的呈现不变。
 */
@Composable
private fun ThreadPhaseRow(state: ChatState) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    when {
        state.phase == ChatPhase.Loading -> PhaseRow(
            title = ChatState.LOADING_TEXT,
            detail = null,
            fg = skin.colors.onSurfaceVariant,
        )

        state.phase == ChatPhase.Failed -> PhaseRow(
            title = ChatState.FAILED_TEXT,
            detail = state.loadError,
            fg = skin.colors.error,
            retry = { scope.launch { state.retryHistory() } },
        )

        state.items.isEmpty() -> PhaseRow(
            title = ChatState.EMPTY_TEXT,
            detail = ChatState.EMPTY_HINT,
            fg = skin.colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhaseRow(
    title: String,
    detail: String?,
    fg: Color,
    retry: (() -> Unit)? = null,
) {
    val skin = LocalSkin.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(text = title, style = skin.typography.body.copy(color = fg))
        if (detail != null) {
            BasicText(
                text = detail,
                style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
            )
        }
        if (retry != null) {
            Box(Modifier.clip(RoundedCornerShape(skin.shapes.pill)).clickable(onClick = retry)) {
                StatusPill(ChatState.RETRY_TEXT, Tone.Accent)
            }
        }
    }
}

// --- one message -------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(state: ChatState, message: ChatMessage) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    val view: MessageView = message.toView()
    val isUser = message.role == ChatRole.User
    // K3 · 流式回合的正文按 16ms 合帧：一帧只取一次最新值，delta 的中间态不再各画一遍
    // （见 [rememberCoalescedStreamText]）。收尾后的行一个字都不多付。
    val bodySource =
        if (message.isStreaming) rememberCoalescedStreamText(message.bodyText) else message.bodyText

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        // Queue badge (FR-8.36) sits on the user bubble it belongs to, not in a global list.
        // Cancel is a drawn ✕ in a ≥44dp target — no text button (v2 §3).
        val position = state.queuePosition(message.id)
        if (position > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                StatusPill("排队中 #$position", Tone.Accent)
                ChatIconButton(
                    icon = ChatIcon.Close,
                    contentDescription = "取消这条排队",
                    onClick = {
                        state.queue.firstOrNull { it.messageId == message.id }?.let {
                            state.cancelQueued(it.clientMsgId)
                        }
                    },
                    boxSize = 44.dp,
                    iconSize = 14.dp,
                    tint = skin.colors.onSurfaceVariant,
                )
            }
        }

        // Attachments first, then the text bubble — the order the parts[] carry (FR-8.41).
        val attachments = message.attachments
        if (attachments.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            ) {
                attachments.forEach { part ->
                    val like = part.toAttachmentLike()
                    AttachmentRow(
                        like = like,
                        onOpen = { like.image?.let(state::openPreview) },
                        onRemove = null,
                        onRetry = null,
                    )
                }
            }
        }

        if (state.editingId == message.id) {
            // 编辑重发（v1.1）：内联编辑卡**替换**气泡本身 — 不是弹窗（任务书 #1）。
            InlineEditCard(state)
        } else {
            // 编辑入口只挂在可编辑的行上（最后一条用户消息，见 canEditMessage）。
            // 两条入口，覆盖两种输入形态（c2-nav）：
            //  - 桌面：hover 显示铅笔图标按钮；长按（按住鼠标）同一个动作；
            //  - 触屏：没有 hover —— 铅笔**常显**（`hovered || isTouchPlatform`），长按消息
            //    是主入口。旧实现只认 hover，编辑重发在手机上永远出不来。
            // 外层 combinedClickable 的 onClick 是空动作：只借它的长按通道，不给整行加点击语义。
            val editable = isUser && state.canEditMessage(message.id)
            val hoverInteraction = remember { MutableInteractionSource() }
            val hovered by hoverInteraction.collectIsHoveredAsState()
            // 旧版本灰化保留（turn/regenerate）：同一行、降透明度、不删除。
            Row(
                modifier = Modifier
                    .then(if (editable) Modifier.hoverable(hoverInteraction) else Modifier)
                    .then(
                        if (editable) {
                            Modifier.combinedClickable(
                                onClick = {},
                                onLongClick = { state.startEdit(message.id) },
                            )
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editable) {
                    androidx.compose.animation.AnimatedVisibility(visible = hovered || isTouchPlatform) {
                        ChatVectorIconButton(
                            vector = VadeIcons.Compose,
                            contentDescription = "编辑这条消息并重发",
                            onClick = { state.startEdit(message.id) },
                            tint = skin.colors.onSurfaceVariant,
                        )
                    }
                }
                Box(Modifier.alpha(if (message.superseded) 0.55f else 1f)) {
                    // One component, two chrome treatments: assistant = no bubble, user =
                    // bubble (D92). `body` carries the markdown renderer so bold / code /
                    // fences survive the trip.
                    skin.MessageBubble(
                        message = view,
                        role = message.role.toBubbleRole(),
                        maxWidth = 760.dp,
                        body = {
                            MarkdownText(
                                source = bodySource,
                                color = if (message.state == MessageState.Failed) skin.colors.error else skin.colors.onSurface,
                            )
                        },
                    )
                }
            }
        }

        // 重新生成（v1.1）：只在最后一条助手消息、且已收尾、且空闲时出现（canRegenerate）。
        // g-chat-send · turn.failed 的行上这就是「重试」：红柄 + 重试文案，点了走
        // turn/regenerate（旧失败行保留并标旧版本，新回合由事件流上屏）。
        if (message.role == ChatRole.Assistant && state.canRegenerate(message.id)) {
            val retrying = message.state == MessageState.Failed
            Row(Modifier.padding(top = 2.dp)) {
                ChatVectorIconButton(
                    vector = VadeIcons.Refresh,
                    contentDescription = if (retrying) "重试这条回复" else "重新生成这条回复",
                    onClick = { scope.launch { state.regenerate(message.id) } },
                    tint = if (retrying) skin.colors.error else skin.colors.onSurfaceVariant,
                )
            }
        }

        if (message.isStreaming) {
            Row(Modifier.padding(top = 4.dp)) {
                StatusPill("正在生成 · 已 ${message.bodyText.length} 字", Tone.Accent)
            }
        }
    }
}

// --- inline edit card (v1.1 · message/edit_resubmit) ---------------------------

/**
 * 内联编辑卡 — replaces the user bubble while editing (NOT a dialog): `lg`(24) 圆角 +
 * 发丝线 + float 阴影（§8.2 shadow2, `Modifier.layeredShadow`），多行 foundation 输入域
 * （冻结的 `Skin.TextField` 是 singleLine，页面用与 composer 相同的 foundation 先例），
 * ✓ 确认重发 / ✕ 取消，均为 44dp 图标按钮。确认 = ⌘/Ctrl+Enter，取消 = Esc。
 */
@Composable
private fun InlineEditCard(state: ChatState) {
    val skin = LocalSkin.current
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(skin.shapes.lg)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(state.editingId) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .layeredShadow(shadow = VadeShadows.float, shape = shape, dark = skin.colors.isDark)
            .clip(shape)
            .background(skin.colors.surface)
            .border(1.dp, skin.colors.hairline, shape)
            .onPreviewKeyEvent { event ->
                when (handleEditKeys(event, state)) {
                    EditKeyAction.Confirm -> {
                        scope.launch { state.confirmEdit() }
                        true
                    }
                    EditKeyAction.Cancel -> {
                        state.cancelEdit()
                        true
                    }
                    EditKeyAction.None -> false
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BasicTextField(
            value = state.editDraft,
            onValueChange = state::updateEditDraft,
            enabled = true,
            textStyle = skin.typography.body.copy(color = skin.colors.onSurface),
            cursorBrush = SolidColor(skin.colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .focusRequester(focusRequester),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                // 键盘提示只在有硬件键盘的平台成立：触屏上没有 Esc / Ctrl+Enter，
                // 照抄桌面文案会指向不存在的操作，所以按平台分支（c2-nav）。
                text = if (isTouchPlatform) {
                    "✓ 重发 · ✕ 取消"
                } else {
                    "⌘/Ctrl+Enter 重发 · Esc 取消"
                },
                style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                modifier = Modifier.weight(1f),
            )
            ChatVectorIconButton(
                vector = VadeIcons.Check,
                contentDescription = if (state.canConfirmEdit) "确认重发" else "内容为空，不能重发",
                enabled = state.canConfirmEdit,
                onClick = { scope.launch { state.confirmEdit() } },
                tint = skin.colors.success,
            )
            ChatVectorIconButton(
                vector = VadeIcons.Close,
                contentDescription = "取消编辑",
                onClick = { state.cancelEdit() },
                tint = skin.colors.onSurfaceVariant,
            )
        }
    }
}

/** The inline editor's key policy, pure so the flow test drives the real handler. */
internal enum class EditKeyAction { None, Confirm, Cancel }

internal fun handleEditKeys(
    event: androidx.compose.ui.input.key.KeyEvent,
    state: ChatState,
): EditKeyAction {
    if (event.type != KeyEventType.KeyDown) return EditKeyAction.None
    if (event.key == Key.Escape) return EditKeyAction.Cancel
    val primary = event.isMetaPressed || event.isCtrlPressed
    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
    if (!primary || !enter) return EditKeyAction.None
    // Consumed even on an empty draft — a stray newline must not land in the editor.
    return EditKeyAction.Confirm
}

/**
 * Chip + thumbnail as one unit: `AttachmentChip` owns name / size / state / ×, the page
 * owns the bitmap and the tap — both through the optional `leading` slot added after the
 * C-09 freeze (the frozen signature had no `onClick`; the chip row is the tap target).
 */
@Composable
private fun AttachmentRow(
    like: AttachmentLike,
    onOpen: () -> Unit,
    onRemove: (() -> Unit)?,
    onRetry: (() -> Unit)?,
) {
    val skin = LocalSkin.current
    Box(Modifier.clip(RoundedCornerShape(skin.shapes.small)).clickable(enabled = like.image != null, onClick = onOpen)) {
        skin.AttachmentChip(
            attachment = like.view,
            onRetry = onRetry,
            onRemove = onRemove,
            leading = like.image?.let { { MockImage(Modifier.fillMaxSize()) } },
        )
    }
}

// --- one tool card -----------------------------------------------------------

@Composable
private fun ToolRow(state: ChatState, call: ToolCall) {
    val skin = LocalSkin.current
    val cardState = state.cardState(call)
    val collapsible = toolCardCollapsible(call)
    val expanded = cardState != vade.ui.skin.ToolCardState.Collapsed

    Column(Modifier.fillMaxWidth()) {
        skin.ToolCallCard(
            call = call.toView(),
            state = cardState,
            onToggle = { if (collapsible) state.toggleToolCard(call.id) },
            // The frozen view has no `duration_ms` / `batch_id` / `error_code` fields, and
            // FR-8.51 says a missing field is *omitted*, never rendered as a placeholder —
            // so the rows come from `toolMetaRows` and simply disappear when the event lacked
            // them. They live *inside* the card (the meta slot), not as a floating stripe.
            meta = if (!expanded) {
                null
            } else {
                {
                    val rows = toolMetaRows(call, state.batchSize(call.batchId))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        rows.forEach { meta ->
                            BasicText(
                                text = "${meta.label} · ${meta.value}",
                                style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                            )
                        }
                        if (!collapsible) {
                            BasicText(
                                text = "risk=high · 高风险卡永远展开（D94）",
                                style = skin.typography.caption.copy(color = skin.colors.warning),
                            )
                        }
                    }
                }
            },
        )
    }
}

// --- inline approval card (v2 §4) --------------------------------------------

/**
 * 审批卡插在会话时间线里触发它的位置 — 卡头「需要批准」+ 风险徽标、卡体脱敏 `args_preview`、
 * 卡尾 ✓ / ✕ / ⏷ 图标按钮。状态在**同一张卡上**演进（待批准 → 已允许 / 已拒绝 / 已超时，
 * 附经过时间），不新增任何消息行（FR-8.53 / v2 §4；`ApprovalDialog` 留给模块页面）。
 *
 * Static card per §8 row 4: hairline + very light shadow + the 3dp status bar, **no** red
 * glow. Insert animation: fade + 12dp up-slide over 180ms on `Skin.motion.easing` (§7);
 * resolving only transitions colour, never position.
 *
 * `internal` (not `private`) only so the pixel test `CardAccentBarTest` can render this exact
 * card standalone and read the accent strip off the frame — the seam is module-scoped, so no
 * public API is added.
 */
@Composable
internal fun ApprovalCardRow(state: ChatState, call: ToolCall) {
    val skin = LocalSkin.current
    val outcome = state.approvalOutcome(call.id)
    val shape = RoundedCornerShape(skin.shapes.md)
    val statusColor = when (outcome) {
        ApprovalOutcome.Pending -> skin.colors.warning
        ApprovalOutcome.Allowed -> skin.colors.success
        ApprovalOutcome.Denied -> skin.colors.error
        ApprovalOutcome.TimedOut -> skin.colors.onSurfaceVariant
    }

    // §7：fade + 12dp 上滑，180ms。进场只跑一次；结果态不改位移。
    // K1 · reduced-motion：位移归零（纯淡入）、时长封顶 100ms —— 淡入保留因果感，位移去掉。
    val density = LocalDensity.current
    val reduceMotion = LocalReduceMotion.current || ReduceMotion.enabled
    val enterMs = skin.motion.durationMs(skin.motion.standardMs, reduceMotion)
    val travelPx = skin.motion.travelPx(with(density) { 12.dp.roundToPx() }, reduceMotion)
    val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(durationMillis = enterMs, easing = skin.motion.easing)) +
            slideInVertically(
                animationSpec = tween(durationMillis = enterMs, easing = skin.motion.easing),
            ) { travelPx },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                // §8 row-4 static card: layered + directional `shadow0`, NOT
                // `Modifier.shadow` (Material's single symmetric elevation silhouette —
                // banned by §8.1「单层半透明色块」and §8.2「上方不得有阴影」; measured on
                // the emulator it put 13/255 below and 3/255 ABOVE the card, a rim).
                .layeredShadow(VadeShadows.card, shape, dark = skin.colors.isDark)
                .clip(shape)
                // 状态条挂在 `.background` / `.border` 之前：`Modifier.border` 画在内容之后，
                // 竖条若挂在最后，左侧 1dp 会被发丝线盖住（实宽 3dp → 2dp 并留缝）。
                // 顶端沿 md 圆弧走、3dp 收细到 0、65% 高度处收尖；半径取卡片的形状 token。
                .cardAccentBar(statusColor, cornerRadius = skin.shapes.md)
                .background(skin.colors.surface)
                .border(1.dp, skin.colors.hairline, shape),
        ) {
            Column(
                // 原来是「3dp 竖条 + 13dp 内边距」= 16dp 起排；竖条挪到卡片背景上之后，
                // 内边距直接站在 16dp（版式逐像素不变）。
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            ) {
                // 卡头：「需要批准」+ 风险徽标；resolved 时右侧变结果态 + 时间戳。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = "需要批准",
                        style = skin.typography.body.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    RiskBadge(call.risk)
                    Spacer(Modifier.weight(1f))
                    if (outcome == ApprovalOutcome.Pending) {
                        BasicText(
                            text = call.name,
                            style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                            maxLines = 1,
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.End) {
                            BasicText(
                                text = outcome.label,
                                style = skin.typography.caption.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = statusColor,
                                ),
                            )
                            state.approvalDecidedLabel(call.id)?.let { stamp ->
                                BasicText(
                                    text = stamp,
                                    style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                // 卡体：脱敏 args_preview，凹陷 mono 块（xs 圆角）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(skin.shapes.xs))
                        .background(skin.colors.surfaceVariant)
                        .padding(10.dp),
                ) {
                    BasicText(
                        text = call.argsPreview ?: "（本轮事件无 args_preview）",
                        style = skin.typography.mono.copy(color = skin.colors.onSurface),
                    )
                }

                if (state.approvalDetailOpen(call.id)) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        toolMetaRows(call, state.batchSize(call.batchId)).forEach { row ->
                            BasicText(
                                text = "${row.label} · ${row.value}",
                                style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                            )
                        }
                    }
                }

                if (outcome == ApprovalOutcome.Pending) {
                    Spacer(Modifier.height(6.dp))
                    // 卡尾：✓ 允许 / ✕ 拒绝 / ⏷ 详情 — 全图标，44dp 触摸区。
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ChatIconButton(
                            icon = ChatIcon.Check,
                            contentDescription = "允许 ${call.name}",
                            onClick = { state.decideApproval(call.id, vade.ui.skin.Decision.Approve) },
                            tint = skin.colors.success,
                        )
                        ChatIconButton(
                            icon = ChatIcon.Close,
                            contentDescription = "拒绝 ${call.name}",
                            onClick = { state.decideApproval(call.id, vade.ui.skin.Decision.Deny) },
                            tint = skin.colors.error,
                        )
                        ChatIconButton(
                            icon = ChatIcon.ChevronDown,
                            contentDescription = if (state.approvalDetailOpen(call.id)) "收起详情" else "展开详情",
                            onClick = { state.toggleApprovalDetail(call.id) },
                            tint = skin.colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 风险徽标：`xs` 圆角小 pill，色随风险（high → danger / medium → warn / low → muted）。 */
@Composable
private fun RiskBadge(risk: String) {
    val skin = LocalSkin.current
    val color = when (risk) {
        "high" -> skin.colors.error
        "medium" -> skin.colors.warning
        else -> skin.colors.onSurfaceVariant
    }
    BasicText(
        text = "risk=$risk",
        style = skin.typography.caption.copy(color = color),
        modifier = Modifier
            .clip(RoundedCornerShape(skin.shapes.xs))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// --- floating composer (v2 §1) ------------------------------------------------

/**
 * Glass 退化兜底：shell 还没有把 `hazeSource` 注册进 [LocalGlassHazeState] 时，
 * `GlassSurface` 的模糊层是空的，只剩薄染色 — 背后的内容会**整段透过**，完全不可读
 * （§5 的反面判据）。这里给玻璃层**内容**垫一层底色保住可读性；shell 接上真 blur 后
 * `hasBackdrop` 变 true，兜底自动消失，玻璃语义不变。
 *
 * 底色取 [SkinColors.previewCardBase]（G5，2026-11-15）：预览卡的用户验收是「背后的
 * 正文读不出来」——亮色维持既有 `surfaceGlass`@88% 半透明观感，暗色 token 是**全实底**
 * 的 surface 色调，底座本身隔绝背后内容。AttachmentPreviewContrastTest 用纯色对照层
 * 钉住这两个方向的像素行为。
 *
 * 注意是给内容的 modifier，不是往 GlassSurface 里塞 `fillMaxSize` 的子盒子 —
 * 那会把 wrap-content 的玻璃层撑满整个可用高度。
 *
 * **它不是 composer 圆角缺陷的原因**（g-composer-shape 实测纠正）：那条缺陷是
 * `Modifier.shadow` 在 `.clip` 外层挖出的矩形洞，与本函数无关。但这里仍然要按
 * [shape] 裁一次——矩形背景铺在圆角容器里会露方角，只是 3/255 的浅染不容易看见。
 */
@Composable
private fun Modifier.glassFallback(skin: vade.ui.skin.Skin, shape: Shape = RectangleShape): Modifier {
    if (LocalGlassHazeState.current != null) return this
    return background(skin.colors.previewCardBase, shape)
}

/**
 * v2 §1 content-side formula, extracted so `ComposerInsetsTest` can pin it: the
 * list pads by the composer's MEASURED height (its own padding included) + 24 dp,
 * so the newest message scrolls clear of the composer — and, since g-inset2, of
 * the navigation-bar zone the composer now clears.
 */
internal fun composerListSpacer(composerHeight: Dp): Dp = composerHeight + 24.dp

/**
 * 消息列尾部的让位块（`composerListSpacer` 的唯一读取点）。
 *
 * **为什么要单独一个 composable**：composer 在键盘动画期间每帧都会变高
 * （`imePadding` + `composerNavClearance()` 每帧变化 → `onSizeChanged` 每帧写
 * `viewport.composerHeightPx`）。这个 snapshot state 之前是在 `ChatThread` 的内容
 * lambda 里读的，于是它的每一次写入都会让**整棵消息列**（`items.forEach { MessageRow }`
 * + 每条 Markdown 的布局）重组一遍 —— 这就是用户反馈的「点输入框弹键盘卡」里最贵的
 * 那一段（证据链见 `ChatThread` 尾部的 [ComposerTailSpacer] 注释与 `n3-blur` 报告）。
 *
 * 把读点下沉到这一个叶子节点之后：键盘动画每帧只让 8dp 高的一个 Spacer 重组，消息列
 * 的重组次数归零。**行为逐像素不变**：高度值仍是 `composerListSpacer(实测高度)`。
 */
@Composable
private fun ComposerTailSpacer(viewport: ChatViewport) {
    val height = with(LocalDensity.current) { composerListSpacer(viewport.composerHeightPx.toDp()) }
    Spacer(Modifier.height(height))
}

/**
 * The independent floating layer: queue strip + the skin's InputBar + the out-of-frame
 * SendOrb (both C-09 components — RFC-0011 removed the page-built composer).
 * Measures itself into [onHeightChanged] so the list can pad by 高度 + 24dp (§1).
 *
 * Owns the upload runner: every staged uploader chip gets exactly one job running
 * `ChatState.runUpload`; the page (not the state) owns the scope, so 取消上传 = cancel the
 * job + drop the chip. A retry flips the chip back to 上传中 and the snapshotFlow re-fires.
 */
@Composable
private fun BoxScope.ComposerOverlay(
    state: ChatState,
    gutter: Dp,
    animate: Boolean,
    onHeightChanged: (Int) -> Unit,
    /** g-model-chip：经 `Skin.InputBar(leading = …)` 进面板底行的页面控件（模型 chip）。 */
    inputLeading: (@Composable () -> Unit)? = null,
    /** m-bar：底栏区在 composer 之外的高度（见 [ChatThread] 的同名参数）。 */
    bottomBarInset: Dp = 0.dp,
    /** g-composer-2row：展开时的第二行页面控件（模型 chip 等），传给 `InputBar(secondRow)`。 */
    modelRow: (@Composable () -> Unit)? = null,
    /** g-composer-2row：第二行是否展开（页面状态）。 */
    expanded: Boolean = false,
    /** g-composer-2row：在浮层里点一下（抬起）→ 页面把 [expanded] 置 true；null = 不观察指针。 */
    onComposerTap: (() -> Unit)? = null,
    /** 非指针入口（读屏自定义动作）：展开第二行；null = 已展开 / 没有第二行。 */
    onExpandRequest: (() -> Unit)? = null,
) {
    val skin = LocalSkin.current
    val uploadScope = rememberCoroutineScope()
    val uploadJobs = remember { mutableMapOf<String, Job>() }

    LaunchedEffect(state, animate) {
        if (!animate) return@LaunchedEffect
        snapshotFlow { state.staged.map { it.uploadId to it.attachment.state } }.collect { chips ->
            chips.forEach { (id, chipState) ->
                if (id != null && chipState == AttachmentState.Uploading && uploadJobs[id]?.isActive != true) {
                    uploadJobs[id] = uploadScope.launch { state.runUpload(id) }
                }
            }
            val liveIds = chips.mapNotNull { it.first }.toSet()
            uploadJobs.keys.toList().forEach { id -> if (id !in liveIds) uploadJobs.remove(id) }
        }
    }

    fun cancelUpload(uploadId: String) {
        uploadJobs.remove(uploadId)?.cancel()
        state.cancelUpload(uploadId)
    }

    // 审查 P1-1：宿主给的**栏区**（`barZoneFor`，与 IME 无关）优先，直接加；
    // rail / 双栏没有底栏（`SessionDetailScreen` 默认 0dp，仍是导航条会盖住胶囊的地方），
    // 只有那里才回落到 composer 自己的导航条余量。`if` 是**短路**的：手机详情态的
    // 栏区恒 > 0 ⇒ `composerNavClearance()`（读 `WindowInsets.ime`）根本不执行，
    // 键盘动画因此不再让这个组合作用域每帧重组。
    val bottomReserve: Dp =
        if (bottomBarInset > 0.dp) bottomBarInset else composerNavClearance()

    Column(
        modifier = Modifier
            .testTag(ComposerOverlayTag)
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            // 非指针入口：读屏用户可以把「显示模型选项」当自定义动作触发，
            // 与点输入区展开走同一个回调（审查 P1-2）。
            .semantics {
                val action = onExpandRequest
                if (action != null) {
                    customActions = listOf(
                        CustomAccessibilityAction("显示模型选项") {
                            action()
                            true
                        },
                    )
                }
            }
            // g-composer-2row：整块浮层（队列条 / 输入行 / 第二行 / 底部留白）都算
            // 「composer 区域」。Initial 观察一次点击（按下+抬起、位移在 slop 内）、不消费
            // ⇒ 输入框焦点与点击不受影响；
            // 默认路径（onComposerTap == null）连这个节点都不挂。
            .then(
                if (onComposerTap != null) {
                    Modifier.observePointerTap(onComposerTap)
                } else {
                    Modifier
                },
            )
            // c6-insets (P1-3)：高度必须在 padding 的**外侧**量。`onSizeChanged` 报的是
            // 它之后那条 modifier 链的尺寸——挂在 `.padding` 后面只能拿到内容高度，
            // 列表尾距因此少了 `16dp + 导航条 clearance`，最后一条被胶囊压住。
            // 放在链首后测得的是浮层整高（含 imePadding 与底部 padding），
            // `composerListSpacer()` 的「实测高度 + 24dp」才真的把整块浮层让出来。
            .onSizeChanged { onHeightChanged(it.height) }
            // 键盘弹出时浮层整体上移（§1；桌面 inset 为 0，此 modifier 无副作用）。
            // g-inset2 (P1-1)：键盘收起时胶囊底部叠上导航条 clearance——真机 API 30 三键条
            // 直接画在胶囊上、输入热区被吞。桌面/Wasm 两个 inset 都读到 0 → 布局与原来
            // 逐像素一致；键盘弹出时 IME 盖住导航条且根部 imePadding 已抬升整页，
            // clearance = max(0, nav − ime) = 0，与 ime 不重复计数（不变式由
            // ComposerInsetsTest 钉住）。
            .imePadding()
            .onPreviewKeyEvent { event -> handleComposerKeys(event, state) }
            // m-bar（审查 P1-1）：宿主现在传的是**整条栏区**（`barZoneFor`，只依赖
            // navigationBars，不读 IME）⇒ 手机详情态直接 `16.dp + 栏区`，composer 底边
            // 恒在栏区上沿之上 16dp，与改前的 `16 + clearance + (barZone − clearance)`
            // 逐像素相同。
            //
            // rail / 双栏**没有底栏**（`SessionDetailScreen` 的默认 0dp），那里仍要
            // composer 自己的导航条余量（Android 三键条会盖住胶囊）—— 见上面的
            // `bottomReserve`：条件求值，手机详情态不会读到 `WindowInsets.ime`。
            .padding(
                start = gutter,
                end = gutter,
                bottom = 16.dp + bottomReserve,
            ),
    ) {
        Column(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 队列徽标 (FR-8.35)：一行、可整队取消；取消是 ✕ 图标，不是文字按钮。
            if (state.queue.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill("${state.queue.size} 条排队", Tone.Accent)
                    Spacer(Modifier.width(8.dp))
                    BasicText(
                        text = state.queue.mapIndexed { i, q -> "#${i + 1} ${q.text.take(12)}" }
                            .joinToString(" · "),
                        style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    ChatIconButton(
                        icon = ChatIcon.Close,
                        contentDescription = "取消全部排队",
                        onClick = { state.queue.map { it.clientMsgId }.forEach { state.cancelQueued(it) } },
                        tint = skin.colors.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 回形针附件（v1.1）：本轮没有文件选择器（shell 层职责），点击把一个内置
                // 样例文件送进可注入的 AttachmentUploader 通道——chip、进度、取消/重试全是
                // 真流程；接真上传 = 换掉 uploader 实现 + 传真 PendingAttachment。
                ChatVectorIconButton(
                    vector = VadeIcons.Attach,
                    contentDescription = "添加附件",
                    onClick = { state.stageUpload(ChatFixtures.nextPending(state.staged.size)) },
                    tint = skin.colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        // g-composer-2row · 面板锚点：只在真的组成第二行时挂。测试用它断言
                        // chip 的矩形被**这块面板**的矩形包含（第二行在面板内，不是面板外
                        // 另起一层）。默认路径（不收 / 没传 modelRow）一个节点都不多。
                        .then(
                            if (expanded && modelRow != null) {
                                Modifier.testTag(ComposerPanelTag)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    // 输入区 = C-09 组件 11（RFC-0011 后为纯输入区）：玻璃面板、hairline、
                    // 浮层阴影、多行封顶都在皮肤里；页面只传语义状态。已暂存附件走
                    // attachments 槽（面板内、文本域上方）。
                    val stagedSlot: (@Composable () -> Unit)? = if (state.staged.isNotEmpty()) {
                        {
                            StagedAttachments(
                                state = state,
                                onCancelUpload = { uploadId -> cancelUpload(uploadId) },
                                onRetryUpload = { uploadId -> state.retryUpload(uploadId) },
                            )
                        }
                    } else {
                        null
                    }
                    // g-composer-2row · 第二行走 `secondRow` 追加槽，由**皮肤**画在面板内部、
                    // 输入行下方：同一块玻璃底 / 发丝线 / 圆角，不再有面板外那一层 surface。
                    // 条件仍由页面决定（`expanded && modelRow != null`）；不成立时传 null，
                    // 皮肤整段不组成 ⇒ 冻结的单行 composer 逐像素不变。
                    val row = modelRow
                    val secondRowSlot: (@Composable () -> Unit)? =
                        if (expanded && row != null) {
                            {
                                // testTag 挂在**面板内**这一层：测试照旧按 tag 找「第二行在
                                // 不在」，几何断言再拿 [ComposerPanelTag] 的矩形验包含关系。
                                Box(Modifier.fillMaxWidth().testTag(ComposerModelRowTag)) {
                                    row()
                                }
                            }
                        } else {
                            null
                        }
                    skin.InputBar(
                        value = state.draft,
                        onValueChange = state::updateDraft,
                        mode = state.inputMode,
                        attachments = stagedSlot,
                        enabled = true,
                        voiceAvailable = false,
                        maxLines = 6,
                        // g-model-chip：composer 底行槽位 —— 模型 chip（页面经此形参传入）。
                        leading = inputLeading,
                        // g-composer-2row：第二行（模型 chip）在面板内部、输入行之下。
                        secondRow = secondRowSlot,
                    )
                }
                // 发送球在**框外**（v2 §2 / RFC-0011）：与输入框间距 12dp、垂直居中的独立
                // 元素 = C-09 组件 12。三态由 inputMode 驱动；send-or-stop 的 policy 在页面。
                Spacer(Modifier.width(12.dp))
                skin.SendOrb(
                    mode = state.inputMode,
                    enabled = true,
                    onClick = { if (state.isStreaming) state.stop() else state.submit() },
                )
            }
        }
    }
}

/**
 * g-composer-2row · composer **面板**的测试锚点（只在第二行真的组成时挂）。
 *
 * 面板是皮肤画的（`Skin.InputBar`），页面只在它外面那一层 Box 上挂 tag —— 那一层与面板
 * 同宽同高（面板无外边距，RFC-0011 D5），所以测试可以拿它当「面板矩形」，断言第二行
 * 被面板**包含**、且在输入行下方，而不是原来那层面板外的独立 surface。
 */
internal const val ComposerPanelTag = "vade.chat.composer.panel"

/**
 * g-composer-2row · composer 第二行（面板内）的 testTag —— 测试用它断言「模型行在 / 不在」，
 * 而不是去数 chip 的文案（文案会随模型名变）。节点现在位于输入面板**内部**。
 */
internal const val ComposerModelRowTag = "vade.chat.composerModelRow"

/**
 * g-composer-2row · 「点在这里」的**近似焦点**信号（一次**点击**，不是按下）。
 *
 * 为什么是近似：`Skin.InputBar` 是 C-09 冻结接口（只有 value / onValueChange / mode /
 * attachments / enabled / voiceAvailable / maxLines / leading），页面既不能给它挂
 * `Modifier` 也拿不到 `onFocusChanged` —— 输入域的真焦点事件到不了页面。
 *
 * 所以从指针上读：在 [PointerEventPass.Initial] 阶段等一次**按下 + 抬起且位移在
 * touch slop 内**，**不消费**（一次 `consume()` 都不调）—— 输入框的焦点、点击、拖选、
 * 长按全部照旧；外层也不许包 `clickable`（那会抢走输入框的焦点）。
 *
 * 为什么等抬起而不是按下：展开会把输入行整体上移一行（约 44dp）。在按下那一刻就改布局
 * 等于在同一个手势里把被点的节点搬走 —— 手指随后的几个像素抖动会命中 tap 判定的
 * 「越界即取消」，第一次点输入框可能只展开、不给焦点（键盘不弹）。抬起时再展开，则是
 * **这一次点击已经判定完**才改布局：焦点 / 发送 / 拖选都不受影响。
 *
 * 为什么还要量位移：同一套观察者挂在页面根上，而消息列表的滚动、圈选也是「按下-抬起」。
 * 位移超过 touch slop 的一律不算点击 —— 滚列表不会把 composer 收起来。
 *
 * 为什么 Initial 能分辨内外：同一手势里父节点先于子节点收到 Initial（Main 才是
 * 子 → 父）。页面根先把「展开」关掉、composer 自己再打开 ⇒ 点输入区 = 展开；点别处 =
 * 只有根那一下 ⇒ 收起。两个观察者都不消费，谁也拦不住底下的交互。
 */
@Composable
internal fun Modifier.observePointerTap(onTap: () -> Unit): Modifier {
    // 回调每帧都可能是新 lambda：用 rememberUpdatedState 钉住，pointerInput 不因此重启
    // （重启会丢掉进行中的手势状态）。
    val latest by rememberUpdatedState(onTap)
    // 位移必须在**窗口坐标**里比：Android 上 IME 弹起时整个 composer 会随根布局上移，
    // 局部坐标里同一根手指的 Press/Release 会差出一个 IME 高度，被判成拖拽 —— 首点
    // 输入框时「键盘弹了但第二行没出来」正是这条（审查 P0）。多指手势一律作废。
    //
    // n3-blur 性能：坐标只在**指针回调**里读，从不参与组合，所以这里用一个普通引用而不是
    // `mutableStateOf`。后者会让每一次布局（键盘动画期间每帧一次）都写一次快照状态 ——
    // 一个 observer 都没有的写入仍然要走一次快照 apply，纯属白烧 CPU。
    val coordinates = remember { LayoutCoordinatesRef() }
    return this
        .onGloballyPositioned { coordinates.value = it }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                var downPosition: Offset? = null
                fun windowOf(local: Offset): Offset =
                    coordinates.value?.localToWindow(local) ?: local
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.singleOrNull()
                    if (change == null) {
                        downPosition = null
                        continue
                    }
                    when (event.type) {
                        PointerEventType.Press -> downPosition = windowOf(change.position)
                        PointerEventType.Release -> {
                            val down = downPosition
                            downPosition = null
                            // 位移 ≤ touch slop 才算「点在这里」；更大 = 拖拽（滚动 / 圈选）。
                            if (down != null &&
                                (windowOf(change.position) - down).getDistance() <= viewConfiguration.touchSlop
                            ) {
                                latest()
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
}

/**
 * [observePointerTap] 的坐标持有者：**布局期写、指针期读**，两个阶段都不需要快照。
 *
 * 用 `mutableStateOf` 存它时，键盘动画的每一下布局都会写一次快照状态（observer 数量为 0），
 * 那是纯粹的白烧；换成一个普通引用之后，每帧少一次 snapshot apply。
 */
private class LayoutCoordinatesRef {
    var value: LayoutCoordinates? = null
}

/**
 * 已暂存附件行 —— 传给 `Skin.InputBar` 的 `attachments` 槽（面板内、文本域上方）：
 * 点击开预览 / ✕ 移除·取消 / 失败 ↻ 重试；超出两个折叠成一行提示（单轮上限 RFC-0004 §6.1）。
 * Uploader chips（uploadId != null）的 ✕ = 取消上传（job + chip 一起没），↻ = 原位重试；
 * 遗留 fixture chips 走原来的 remove+re-attach 路径。
 */
@Composable
private fun StagedAttachments(
    state: ChatState,
    onCancelUpload: (String) -> Unit,
    onRetryUpload: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.staged.take(2).forEach { staged ->
                val uploadId = staged.uploadId
                AttachmentRow(
                    like = AttachmentLike(
                        staged.attachment,
                        (staged.part as? ContentPart.Image),
                    ),
                    onOpen = {
                        (staged.part as? ContentPart.Image)?.let(state::openPreview)
                    },
                    onRemove = if (uploadId != null) {
                        { onCancelUpload(uploadId) }
                    } else {
                        { state.removeStaged(staged.attachment.fileId) }
                    },
                    onRetry = when {
                        uploadId != null && staged.attachment.state == AttachmentState.Failed ->
                            ({ onRetryUpload(uploadId) })

                        uploadId == null && staged.attachment.state == AttachmentState.Failed -> {
                            {
                                state.removeStaged(staged.attachment.fileId)
                                state.attach(staged)
                            }
                        }

                        else -> null
                    },
                )
            }
        }
        if (state.staged.size > 2) {
            BasicText(
                text = "还有 ${state.staged.size - 2} 个附件（单轮上限 ${ChatState.MAX_PER_TURN}）",
                style = LocalSkin.current.typography.caption.copy(
                    color = LocalSkin.current.colors.onSurfaceVariant,
                ),
                maxLines = 1,
            )
        }
    }
}

// --- send ball → moved to the skin (RFC-0011) ----------------------------------

// The page-drawn 48dp ball was deleted by RFC-0011: the send/stop ball is C-09 component
// 12 (`Skin.SendOrb`) now, implemented per skin (`MaterialSendOrb` / `MiuixSendOrb`) and
// composed OUTSIDE the frame by `ComposerOverlay` above. This page keeps only the policy:
// what "armed" means (`ChatState.inputMode`) and what a click does (send-or-stop).

// --- keyboard ----------------------------------------------------------------

/**
 * U1-b desktop key contract: `⌘Enter` / `Ctrl+Enter` sends, plain `Enter` stays a newline so
 * the composer is genuinely multi-line. Kept pure (and `internal`) so `ChatFlowTest` can drive
 * the *real* handler with real `KeyEvent`s instead of asserting on a copy of the logic.
 *
 * Consumed even when the composer is empty — otherwise the newline would still be inserted and
 * an empty ⌘Enter would look like the send was ignored for the wrong reason.
 */
internal fun handleComposerKeys(
    event: androidx.compose.ui.input.key.KeyEvent,
    state: ChatState,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val primary = event.isMetaPressed || event.isCtrlPressed
    val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
    if (!primary || !enter) return false
    state.submit()
    return true
}

// --- K3 · 流式合帧 -------------------------------------------------------------

/**
 * K3 · 合帧窗口 = 60Hz 一帧（16ms）。
 *
 * 为什么是「按窗口取样」而不是 debounce：debounce 会在**每一条**新 delta 到来时重置计时器，
 * 持续流式时就永远不输出 —— 正文会卡在开头不动。取样是「窗口到点就取当前值」，延迟上界
 * 恒为 16ms，与流有多快无关。
 */
internal const val StreamCoalesceMs = 16L

/**
 * K3 · 流式正文的合帧器。
 *
 * 一条回复在流式期间每几十毫秒换一次全文；core 的 coalesced 帧还能在一帧里塞进十几条
 * `turn.delta`，而每条 delta 都会就地替换同一行 → 页面在一帧里要把**同一段全文**画十几遍
 * （每遍一次 `Markdown.parse(全文)` + 一份 `AnnotatedString`），这是「流式抖动」的成本来源。
 *
 * 这里只让**最新值**过帧：窗口内被覆盖的中间态直接跳过（它们本来就是要被下一条替换掉的
 * 半成品）。与 `MarkdownText` 的 `remember(source)` 配合之后，一帧最多解析一次全文。
 *
 * 诚实边界：`items` 的写入仍由 [ChatState] 逐条完成（本批的改动范围不含它，见
 * `batch-results/c5-motion.md`）；合帧发生在**渲染写入口**，也就是成本真正产生的地方。
 *
 * 只给 [ChatMessage.isStreaming] 的行用；收尾后由调用方直接取原文，最后一个 token 不会
 * 留在窗口里丢掉。
 */
@Composable
private fun rememberCoalescedStreamText(raw: String): String {
    val latest by rememberUpdatedState(raw)
    var framed by remember { mutableStateOf(raw) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(StreamCoalesceMs)
            // 引用比较：流式文本只会追加，同一份 String 实例没被换过就什么都不做。
            if (framed !== latest) framed = latest
        }
    }
    return framed
}

// --- K4 · 跟随滚动 -------------------------------------------------------------
//
// 决策（阈值 / 补偿 / 胶囊）全在 `FollowScroll.kt` 的 [FollowScrollGate]：纯状态机，
// 逐帧推进即可测。本文件只留「落到 ScrollState 上」的那一步和胶囊本身。

/** K4 · 「新内容」胶囊的 testTag（`ChatFollowScrollGateTest` / 渲染测试用它断言出现 / 消失）。 */
internal const val NewContentCapsuleTag = "vade.chat.newContentCapsule"

/**
 * P2-2 · 一个会话的滚动视图状态 —— **位置 + 跟随基线**（两路实测高度在 [ChatViewport]；
 * items 数 / 末条正文长度在 [follower] 的私有账本里）。
 *
 * 为什么必须「一份会话一份」：`rememberScrollState()` 和 `remember { FollowScrollGate() }`
 * 只认组合位置、不认数据。`ChatThread` 切会话时留在**同一个组合槽位**，于是新会话继承了
 * 上一个会话的 `scroll.value`（打开在历史中段）、继承 `unseenBelow`（凭空举着「新内容」
 * 胶囊），跟随基线里还躺着上一个会话的 items / 高度（切过去的第一帧会被当成「内容增长」
 * 补偿一次，或者把「上滚」的旧位置继续当作阅读位置）。
 *
 * 身份 = 会话 id + 近底阈值（阈值随 density 变）。两者任一变了就整份重建
 * （[rememberChatViewport]），同一会话的重组拿回同一实例、位置原地保留 —— 修
 * 「切会话不重置」不能顺手把「每次重组都重置」引进来，那是另一个更糟的缺陷。
 */
internal class ChatViewport(
    /** 这一份视图状态属于哪个会话。 */
    val sessionId: String,
    /** 近底阈值（px）—— 与 density 绑定，所以它也是实例身份的一部分。 */
    val nearBottomPx: Int,
    /**
     * `reverseScrolling` 下的滚动位置：0 = 贴底（最新一行），单位 px。
     *
     * 由 [rememberChatViewport] 用 `rememberSaveable(sessionId, saver = ScrollState.Saver)`
     * 建出来（切会话重置为 0，同会话旋转不丢）；纯测试可直接用默认值。
     */
    val scroll: ScrollState = ScrollState(0),
) {
    /** 跟随决策状态机。基线（items / 末条长度）私有在它里面，随实例一起清零。 */
    val follower: FollowScrollGate = FollowScrollGate(nearBottomPx)

    /**
     * 消息列（时间线本体）实测高度；0 = 这个会话还没量过第一帧。
     *
     * H8 · `mutableIntStateOf` 而不是 `mutableStateOf(0)`：IME 动画期间 composer 高度
     * **每帧写一次**、三处每帧各读一次（[ComposerTailSpacer] / `NewContentCapsuleHost` /
     * `ComposerBottomBlurBand`），装箱的读写是无意义的开销。
     */
    var messagesHeightPx by mutableIntStateOf(0)

    /** composer 浮层实测高度（只影响列表底部内边距）；0 = 还没量过。同上，H8。 */
    var composerHeightPx by mutableIntStateOf(0)
}

/**
 * P2-2 · `ChatThread` 取视图状态的唯一入口。
 *
 * key 是**会话 id + 近底阈值**：切会话时整份重建（滚动回 0、基线清空、胶囊不举），
 * 同一会话重组时复用同一实例（阅读位置不被打断）。`ChatSessionScrollResetTest`
 * 在真实组合里驱动这个函数，而不是另抄一份判据。
 *
 * 滚动位置仍走 `rememberSaveable` 的 [ScrollState.Saver]（与 `rememberScrollState()`
 * 同一个 saver，旋转 / 进程重建不丢位置），只是把 `sessionId` 加进 inputs：**同一个
 * saver，换了会话就按初始值 0 重建** —— 这正是 P2-2 的修复点，恢复语义没有丢。
 */
@Composable
internal fun rememberChatViewport(sessionId: String, nearBottomPx: Int): ChatViewport {
    val scroll = rememberSaveable(sessionId, saver = ScrollState.Saver) { ScrollState(0) }
    return remember(sessionId, nearBottomPx) { ChatViewport(sessionId, nearBottomPx, scroll) }
}

/**
 * K4 · 把 [FollowScrollDirective] 落到滚动状态上。
 *
 * 用 [ScrollState.dispatchRawDelta] 而不是 `scrollTo`：补偿按帧发生，`scrollTo` 会去抢
 * 滚动互斥量（`MutatePriority.Default`）—— 用户正在拖拽 / 惯性滑动时这次调用会被取消，
 * 而 `CancellationException` 会把收集 `snapshotFlow` 的协程一起带走，跟随滚动从此失效。
 * `dispatchRawDelta` 是同步、可由 `ScrollState` 按 `[0, maxValue]` 截断的纯位移，
 * 正好只做「抵消布局增量」这一件事。
 *
 * 唯一的例外是 [FollowScrollDirective.PinToBottom]：用户手正在拖 / 惯性还在跑时**不钉**。
 * 钉底是「替用户回底」，手指明确在往上走的时刻每帧把他按回 0 会变成拔河（旧实现靠
 * `scrollTo` 抢不到互斥量才碰巧避开这一点）。补偿不受此限：它抵消的是布局增量，
 * 手指下的内容因此不会被流式增长偷走。
 */
private fun ScrollState.applyFollow(directive: FollowScrollDirective) {
    when (directive) {
        FollowScrollDirective.None -> Unit
        FollowScrollDirective.PinToBottom -> if (value != 0 && !isScrollInProgress) {
            dispatchRawDelta(-value.toFloat())
        }
        is FollowScrollDirective.ShiftBy -> if (directive.deltaPx != 0) {
            dispatchRawDelta(directive.deltaPx.toFloat())
        }
    }
}

/**
 * K4 · 「新内容」胶囊的宿主（`AnimatedVisibility` + 贴输入区上沿的定位）。
 *
 * 与 [ComposerTailSpacer] 同一条理由独立成函数：它读 `viewport.composerHeightPx`
 * （键盘动画每帧变化），读点必须自成一个组合作用域，否则整棵消息列会跟着每帧重组。
 * 行为与原来逐字段一致：`fadeIn + scaleIn(0.92)` 进场、`fadeOut` 退场，时长
 * [capsuleMs]（已过 K1 门），点击回底（reduced-motion 下一帧到位）。
 */
@Composable
private fun BoxScope.NewContentCapsuleHost(
    viewport: ChatViewport,
    follower: FollowScrollGate,
    capsuleMs: Int,
    reduceMotion: Boolean,
    scroll: ScrollState,
    pageScope: CoroutineScope,
) {
    val skin = LocalSkin.current
    val bottomPad = with(LocalDensity.current) { viewport.composerHeightPx.toDp() } + 8.dp
    AnimatedVisibility(
        visible = follower.unseenBelow,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomPad),
        enter = fadeIn(tween(durationMillis = capsuleMs, easing = skin.motion.easing)) +
            scaleIn(
                initialScale = 0.92f,
                animationSpec = tween(durationMillis = capsuleMs, easing = skin.motion.easing),
            ),
        exit = fadeOut(tween(durationMillis = capsuleMs, easing = skin.motion.easing)),
    ) {
        NewContentCapsule(
            onClick = {
                follower.onJumpToBottom()
                // reduced-motion 下不做「飞回去」的滚动动画：一帧到位。
                pageScope.launch {
                    if (reduceMotion) scroll.scrollTo(0) else scroll.animateScrollTo(0)
                }
            },
        )
    }
}

/**
 * K4 · 「新内容」胶囊 —— 贴在上滚期间的底部，点一下回到最新一行。
 *
 * `↓` 不用字符字形（v2 §3.1 与 `IconGuardTest` 都禁止字符图标）：用页面自己的
 * [ChatIcon.ChevronDown] 描边，与卡尾 ⏷ 同一套线宽。按压反馈与图标按钮同源（0.92 scale，
 * 见 [iconPressSpec]），时长读 [vade.ui.skin.SkinMotion] 并受 reduced-motion 封顶。
 */
@Composable
private fun NewContentCapsule(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.pill)
    val reduceMotion = LocalReduceMotion.current || ReduceMotion.enabled
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) skin.orb.pressScale else 1f,
        animationSpec = tween(
            durationMillis = skin.motion.durationMs(skin.motion.quickMs, reduceMotion),
            easing = skin.motion.easing,
        ),
        label = "newContentCapsulePress",
    )
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .layeredShadow(shadow = VadeShadows.float, shape = shape, dark = skin.colors.isDark)
            .clip(shape)
            .background(skin.colors.surface)
            .border(1.dp, skin.colors.hairline, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { contentDescription = "跳到最新内容" }
            .testTag(NewContentCapsuleTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatIconGlyph(icon = ChatIcon.ChevronDown, tint = skin.colors.primary, iconSize = 14.dp)
        Spacer(Modifier.width(4.dp))
        BasicText(
            text = "新内容",
            style = skin.typography.caption.copy(
                color = skin.colors.primary,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

// --- §9 floating image-preview sheet ------------------------------------------

/**
 * 附件预览 = §9 悬浮卡片，不是贴边全屏：遮罩取 [SkinColors.previewScrim]（亮色 24% 黑，
 * 暗色 ≥48% —— G5：24% 黑在暗画布上不可感知）**纯色**压暗（不是玻璃层：全屏玻璃在降级档
 * 会被补一条 34 % 顶部高光，遮罩就变成「先提亮再压暗」—— 审查 F6-P1-1），卡片四周 12dp
 * 空隙、`sheet` 32dp 同圆角、玻璃底（退化时垫 [SkinColors.previewCardBase]，暗色为全实底）
 * + 浮层阴影 + 发丝线，顶部中央 36×4dp 白条手柄（亮色 黑 24% / 暗色 白 70%），关闭是 ✕
 * 图标。同一时刻只有这一个弹层。
 */
@Composable
private fun ImagePreviewSheet(image: ContentPart.Image, onClose: () -> Unit) {
    val skin = LocalSkin.current
    val sheetShape = RoundedCornerShape(skin.shapes.sheet)
    val grabber = if (skin.colors.isDark) Color.White.copy(alpha = 0.70f) else Color.Black.copy(alpha = 0.24f)

    Box(Modifier.fillMaxSize()) {
        // 遮罩：**纯色**压暗层（`previewScrim`：亮 24% 黑，暗 ≥48%），点它关闭。
        //
        // 它曾经是一整块 `GlassSurface(fillMaxSize)`：全屏尺寸下那一层在降级档会补一条
        // 34 % 的顶部高光 —— 遮罩变成「先提亮再压暗」（暗色模式屏幕顶比画布还亮，审查
        // F6-P1-1），而且它永远占一层 §5 的模糊预算。遮罩自己不需要模糊：背后就是被它
        // 压暗的页面。所以这里只画一个不透明度的纯色矩形。
        Box(
            Modifier
                .fillMaxSize()
                .background(skin.colors.previewScrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )

        // 卡片：`blurBackdrop = false` —— 遮罩已把整屏压暗，卡内又垫了 `glassFallback`
        // 的近实心 content base（暗色全实底），再采一次背景看不出来，却让「预览打开态」
        // 多一层模糊（审查 F6-P1-2）。省掉它，打开态仍然是 3 层。
        GlassSurface(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .padding(12.dp),
            shape = sheetShape,
            skin = skin,
            blurBackdrop = false,
            // 能力门禁只看真实背景 + 平台阶梯（API<31 没有 RenderEffect ⇒ tint+hairstroke+高光）。
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // blur 未接线时的可读性兜底（见 glassFallback 的 KDoc）。
                    .glassFallback(skin, sheetShape),
            ) {
                // 白条手柄：36×4dp、2dp 圆角、距卡顶 8dp，只说明"可下拉关闭"。
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 8.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(grabber),
                )

                // 标题行：文件名 + ✕ 关闭（图标）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicText(
                        text = image.name,
                        style = skin.typography.title.copy(color = skin.colors.textOnGlass),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    ChatIconButton(
                        icon = ChatIcon.Close,
                        contentDescription = "关闭预览",
                        onClick = onClose,
                        tint = skin.colors.textOnGlass,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MockImage(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(image.width.toFloat() / image.height.toFloat())
                            .clip(RoundedCornerShape(skin.shapes.large)),
                    )
                    BasicText(
                        text = "${formatBytes(image.size)} · ${image.width}×${image.height} · $COMPRESSED_NOTE（D96）",
                        style = skin.typography.caption.copy(color = skin.colors.textOnGlass.copy(alpha = 0.78f)),
                    )
                }
            }
        }
    }
}

// --- the "photo" -------------------------------------------------------------

/**
 * A deterministic stand-in for a compressed screenshot: gradient + fake log lines, drawn from
 * the image's own dimensions so the aspect ratio on screen is the real one.
 *
 * **It is a placeholder, not a decoded image.** U1-b reads no file bytes (no picker, no
 * compression, no `POST /ui/blob`); claiming otherwise would be the "假上传显示假成功" the task
 * book forbids. The dimensions and byte count shown next to it come from the `image` part.
 */
@Composable
fun MockImage(modifier: Modifier = Modifier) {
    val skin = LocalSkin.current
    Canvas(modifier) {
        drawMockScreenshot(skin.colors.primary, skin.colors.surfaceVariant, skin.colors.onSurfaceVariant)
    }
}

private fun DrawScope.drawMockScreenshot(accent: Color, paper: Color, ink: Color) {
    val w = size.width
    val h = size.height
    drawRect(
        brush = Brush.linearGradient(
            listOf(paper, accent.copy(alpha = 0.22f)),
            start = Offset(0f, 0f),
            end = Offset(w, h),
        ),
    )
    // Title bar + a few text lines: enough to read as "a screenshot", no more.
    drawRect(accent.copy(alpha = 0.55f), size = Size(w, h * 0.12f))
    val line = h * 0.045f
    var y = h * 0.22f
    val widths = listOf(0.72f, 0.55f, 0.84f, 0.40f, 0.66f, 0.30f)
    var i = 0
    while (y < h * 0.92f) {
        val frac = widths[i % widths.size]
        drawRect(
            color = ink.copy(alpha = if (i % 3 == 0) 0.42f else 0.20f),
            topLeft = Offset(w * 0.08f, y),
            size = Size(w * frac * 0.86f, line * 0.55f),
        )
        y += line * 1.6f
        i++
    }
}
