package vade.ui.destinations

import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.data.RawSourceStrings
import vade.ui.data.SessionGroup
import vade.ui.data.SessionSummary
import vade.ui.data.rawSourceStrings
import vade.ui.glass.asGlassSource
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.meta.MetaColors
import vade.ui.meta.MetaGroup
import vade.ui.skin.LocalSkin
import vade.ui.skin.SkinColors
import vade.ui.skin.glass.LocalGlassHazeState
import kotlin.math.roundToInt

/**
 * g-haze-structure · 本页 haze **source**（会话 LazyColumn）的 testTag ——
 * `GlassStructureGuardTest` 在真实组合里断言搜索浮层不在该节点的语义子树内。
 */
internal const val GlassSourceSessionsTag = "vade.glass.source.sessionsList"

/**
 * 会话 · 列表 (U1-a)。
 *
 * ## 行形态（alpha 审查 J2 裁决，2026-11-16）
 *
 * 单行条目，**行高 56dp**（[SESSION_ROW_HEIGHT]），信息顺序固定为
 * **未读/置顶 > 标题 > 时间**（[SessionRow]，`SessionRowInfoUiTest` 逐项量 x 坐标）：
 *  - 组色点（24dp 槽位，让有组/无组行的标题起点对齐；组名在色点的读屏语义里）；
 *  - 置顶 → 13dp `primary` [VadeIcons.Pin] + 「已置顶」语义；
 *  - 已归档 → 13dp muted [VadeIcons.Archive] + [ARCHIVED_BADGE_LABEL]（绝不用字符当图标）；
 *  - 未读 → 6dp `primary` 圆点 + [UNREAD_BADGE_LABEL] 语义；
 *  - 标题（单行省略，吃掉剩余宽度）；
 *  - 时间 = `SessionSummary.updatedLabel`（右对齐，11sp muted；空串不占位 —— 真源缺
 *    `updated_at` 时不编一个时间出来，见 `SessionRowMapping`）。
 *
 * 预览行与行模型列仍不画：`session/list` 行不带 preview/messages（"content belongs to
 * ui/attach"，core models.py），`SessionRowMapping` 把它投影成空串 —— 画一条空白预览就是
 * 凭空造内容。
 *
 * 选中态 = 带圆角的强调色块（[SELECTED_BLOCK_CORNER] 一组常量），左右各内缩 8dp，
 * 不是通栏满矩形、也没有 §12 旧口径的 3dp 左条。列表靠间距分层、没有分隔线。
 *
 * ## 行内文字色（n1-nav · 深色模式真机反馈）
 *
 * 列表是**唯一**一处「黑字/暗底」事故最容易漏的地方：行里的文本没有一处写死颜色，
 * 全部经 [sessionRowInk] 从当前皮肤取：
 *
 *  - 标题 = `onSurface`（深色 `#EDEDF0` / 亮色 `#17171A`，`MaterialTokens` 两套都已核对）；
 *  - 时间 = `onSurfaceVariant`（深色 `#9A9AA3` / 亮色 `#6B6B73`）；
 *  - 分组头（标签 + 计数）= `onSurfaceVariant`；
 *  - 空态卡（标题 `onSurface` / 正文 `onSurfaceVariant`，见 [EmptyStateCard]）；
 *  - 列表横幅（[ListStatusBanner] 的三条分支全部显式取 token）。
 *
 * 标题此前只靠 `UiText` 的默认参数（恰好也是 `onSurface`），读代码时看不出它跟深色
 * 主题有关，也就没有人能在评审里核对；现在它和其余几处一样**显式**写出来，并由
 * `SessionsScreenDarkInkTest` 在深色皮肤下逐像素断言（深色必须出现亮字形、亮色必须
 * 出现暗字形），「深色下标题/副标题是黑的」这类回归因此会红在测试里而不是真机上。
 *
 * ## 空态（alpha 审查 J1）
 *
 * `groups.isEmpty()` 且**不在加载中**（`status !is ListStatus.Connecting`）时插一张
 * [EmptyStateCard]：标题 [SESSIONS_EMPTY_TITLE] + 唯一主行动 [SESSIONS_EMPTY_ACTION]
 * （回调 [onNewSession]，与顶栏 ✎ 同一个动作）。同一包里的空态卡早就存在，缺的只是接线。
 * 失败态（[ListStatus.Failed]）同样保留这张卡：上面的失败横幅说真话（为什么没读到），
 * 卡上的新建按钮是用户此刻唯一能做的事 —— 比整页只剩一条错误更像一个能操作的界面。
 *
 * The list sits on `canvas`, edge-to-edge: [contentPadding] (the shell's floating glass bar
 * zones) is passed straight into the `LazyColumn`, so rows really scroll behind the glass
 * bars instead of stopping short of them.
 *
 * In scope here: grouping + ordering, the row's information shape, the empty state, and
 * opening a session (the ✎ button lives in the shell top bar; `⌘K` search is U1-c). Out of
 * scope: message tree, streaming — nothing here talks to core.
 *
 * g-first-run: 顶部那行不再硬编码「假数据 · 未连接 core」。屏幕不知道 `SessionSource`，
 * 它只画调用方给的 [status]（判定在 `vade.ui.shell.listStatusOf`）：假源 → 「假数据」，
 * 真源已连上 → 什么都没有，真源失败 → 真实错误 + 自救命令。见 [ListStatus]。
 *
 * ## 分类 / 归档在行上可见（g-archive-ui）
 * 数据层早已把 `SessionSummary.archived` / `groupId` 投影出来（`SessionRowMapping`），
 * 但界面上一个都看不见 —— 分类等于不存在。这里只做**展示**：
 *  - 归档行：标题前一枚 13dp、`onSurfaceVariant` 的 [VadeIcons.Archive] + [ARCHIVED_BADGE_LABEL]。
 *    刻意用最小尺寸与 muted 色：明确可辨，但不跟标题抢注意力。
 *  - 有组的行：组色点（颜色只认合法 `#RRGGBB`；缺省/非法 → muted，不编颜色）。
 *  - 未归档且未分组：**一行都不多**（两个新块都在 `if` 里，不占位、不留空白）。
 *
 * [metaGroups] 的默认空表是刻意的最小侵入：测试与任何新调用点可以只画「裸 id」这一层
 * （`sessionGroupTag` 规则 3）。**生产调用点两个都必须显式传** —— `shell/AppShell.kt` 的
 * 宽屏两栏与窄屏单栏各一处，值来自 `metaGroupsOf(state)`（`session/group.list` 经适配器包）。
 *
 * [banner] is the shell's load-failure slot (g-error-retry): the session-list read failing
 * must be visible *here*, above the rows, together with its retry entry. It is a slot and
 * not a `LoadError?` parameter so this screen keeps knowing nothing about the loader.
 */
@Composable
fun SessionsScreen(
    groups: List<SessionGroup>,
    selectedId: String?,
    twoPane: Boolean,
    onOpen: (SessionSummary) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    metaGroups: List<MetaGroup> = emptyList(),
    /**
     * 行**长按**回调（B2 s-meta-host，alpha J3 起带锚点）：第二个参数是**该行此刻在窗口里的
     * 矩形**（根坐标，px），菜单 Popup 用它贴在行下方而不是钉在列表窗格右上角。
     *
     * 默认 no-op 与既有调用点/测试逐字节兼容（不传就没有菜单）。菜单本体在
     * `meta/SessionContextMenu.kt`，状态机在 `meta/SessionMetaOverlay.kt`。
     */
    onLongPress: (SessionSummary, IntRect) -> Unit = { _, _ -> },
    /**
     * 空态卡上的「新建会话」动作（J1）—— 与顶栏 ✎ 同一个入口（`UiState.newSession()`）。
     * 默认 no-op：没有接线能力的调用点（测试 / 预览）不会因此崩，只是按钮什么都不做。
     */
    onNewSession: () -> Unit = {},
    /** Shell-owned failure banner (see `shell/LoadErrorBanner.kt`); null = nothing to say. */
    banner: (@Composable () -> Unit)? = null,
    /**
     * 列表头状态横幅要画什么。默认值 [ListStatus.Fixtures] 是**本棒之前**的行为（永远画
     * 那行假数据提示），保留它是为了让任何没跟上接线的调用点行为逐字节不变；生产调用点
     * （`AppShell`）必须显式传真实状态。
     */
    status: ListStatus = ListStatus.Fixtures,
    /**
     * g-pair-first-run：首次运行引导卡的原始配置通道。默认 = 平台真实通道（生产逐字节
     * 不变 —— 引导卡的可见性判定在卡内做，`AppShell` 的两处调用点一行未动）；测试注入。
     */
    pairingRaw: () -> RawSourceStrings = ::rawSourceStrings,
    /**
     * g-route-transition：挂到**选中行**上的焦点请求器——手机抽屉重新滑出时由宿主请求，
     * 让焦点回到原来那一行（无障碍口径）。null = 无人请求（旧调用点不需要动焦点）。
     */
    selectedRowFocus: FocusRequester? = null,
) {
    val skin = LocalSkin.current
    // 行内 / 分组头的文字墨色只从这里取（见 [sessionRowInk]）；深色两档由
    // `MaterialTokens.darkColors` 提供，测试逐像素核对字形颜色。
    val ink = sessionRowInk(skin.colors)
    LazyColumn(
        // g-haze-structure: this list is the page's haze **source** — rows scroll under the
        // glass top bar / bottom pill and the search overlay (a SIBLING of the source, at
        // the shell root) blurs them. The source used to sit on the whole ShellBody Row.
        modifier = modifier
            .asGlassSource(LocalGlassHazeState.current)
            .fillMaxSize()
            .testTag(GlassSourceSessionsTag)
            .background(skin.colors.canvas),
        contentPadding = contentPadding,
    ) {
        // g-pair-first-run：首次运行引导卡（真源且缺配对码时）。**只加这一块** —— 现有
        // 的 load-error-banner / list-status 两块横幅逻辑一行未动。卡不可见时一个节点都
        // 不组合，不占行高。
        item(key = "first-run-pairing") {
            FirstRunPairingCard(raw = pairingRaw)
        }
        if (banner != null) {
            item(key = "load-error-banner") { banner() }
        }
        // 两种「说明数据现状」的出口，合并裁定（g-first-run × g-error-retry）：
        //  - 已连接 → 整个 item 不存在（空文本仍占 10+20dp 行高，顶部会凭空多一段空白）；
        //  - 假数据 + 有失败横幅 → 不画：两者互相打脸（沿用 g-error-retry 的理由）；
        //  - 失败 → **两条都画**：shell 那条带重试按钮（唯一可操作出口），
        //    [ListStatus.Failed] 带**可照抄的自救命令**（`VADE_SOURCE=real` /
        //    `VADE_PAIR_CODE=` / 码从哪取）—— 后者是「core 根本没起」时唯一有用的东西，
        //    不能被去重掉。代价是失败时两张卡说同一件事的一部分，
        //    记为**已知视觉欠账**：正确形态是把命令并进带重试的那张卡，等一次专门的
        //    `LoadErrorBanner` 改动；在那之前宁可信息重复，也不让用户看不到怎么把 core 起来。
        val drawStatus = status != ListStatus.Connected &&
            !(status is ListStatus.Fixtures && banner != null)
        if (drawStatus) {
            item(key = "list-status") {
                ListStatusBanner(status, compact = banner != null)
            }
        }

        // J1 · 空态：一个会话都没有、且首读不在飞 —— 铺一张卡，给出唯一主行动。
        // 为什么不是「画一行灰字」：空白页面上用户既不知道发生了什么，也没有任何入口
        // （这正是审查里 J1 的原话）。加载中不画：那时说「还没有会话」是假的。
        // P1-6 · 没有数据源（Unpaired）也不画：那张卡的唯一行动是「新建会话」，而空源
        // 新建只会抛 UNAVAILABLE。此处的解释与下一步在 [UNPAIRED_NOTE] 与上方配对卡里。
        if (groups.isEmpty() &&
            status !is ListStatus.Connecting &&
            status !is ListStatus.Unpaired
        ) {
            item(key = "sessions-empty") {
                EmptyStateCard(
                    title = SESSIONS_EMPTY_TITLE,
                    body = SESSIONS_EMPTY_BODY,
                    icon = VadeIcons.Compose,
                    action = { skin.Button(SESSIONS_EMPTY_ACTION, onNewSession) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            }
        }

        groups.forEach { group ->
            // H9：组头与行各自声明 `contentType` —— 两种完全不同的骨架混在同一条 LazyColumn
            // 里，不分开的话滑动时无法复用同一批可组合槽（每滚过一个组界都要重建一次行）。
            item(key = "hdr-${group.bucket.name}", contentType = "session-group-header") {
                // 分组头：只有文字标签 + 数量，没有 1px 规则线——§12 要求列表靠间距分层。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UiText(
                        text = group.bucket.label,
                        size = 11,
                        weight = FontWeight.SemiBold,
                        color = ink.meta,
                    )
                    Spacer(Modifier.width(6.dp))
                    UiText(
                        text = "${group.sessions.size}",
                        size = 11,
                        color = ink.meta,
                    )
                }
            }
            items(group.sessions, key = { it.id }, contentType = { "session-row" }) { session ->
                val selected = session.id == selectedId
                SessionRow(
                    session = session,
                    selected = selected,
                    onClick = { onOpen(session) },
                    groupTag = sessionGroupTag(session.groupId, metaGroups),
                    // g-route-transition：焦点请求器只挂在选中行上（抽屉回开时焦点回到那一行）。
                    modifier = if (selected && selectedRowFocus != null) {
                        Modifier.focusRequester(selectedRowFocus)
                    } else {
                        Modifier
                    },
                    onLongPress = onLongPress,
                )
            }
        }
        item(key = "tail") { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * 单行会话条目（J2 裁决：56dp、未读/置顶 > 标题 > 时间）。
 *
 * 长按把**这一行自己的矩形**（根坐标）交给 [onLongPress]：菜单 Popup 因此跟在行下方，
 * 而不是永远出现在列表窗格右上角（J3 原缺陷：桌面按第 25 行，菜单弹在屏幕顶部）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSummary,
    selected: Boolean,
    onClick: () -> Unit,
    groupTag: SessionGroupTag?,
    modifier: Modifier = Modifier,
    onLongPress: (SessionSummary, IntRect) -> Unit = { _, _ -> },
) {
    val skin = LocalSkin.current
    val ink = sessionRowInk(skin.colors)
    // H3 · 长按瞬间的窗口矩形：`onGloballyPositioned` 在布局后写、长按一定发生在布局之后，
    // 所以取到的就是用户手指/指针按下的那一行。
    //
    // **不写快照状态**（审查 H3）：滚动 / 惯性 / 抽屉滑动时每一可见行的根坐标每帧都在变 ⇒
    // 回调每帧每行一次。以前这里是一个 `mutableStateOf(IntRect)`，于是每帧每行都写一次快照
    // （一个完全没有组合期读者的写入，纯属白烧 CPU + 快照记录分配）。坐标只有一个消费者：
    // 「长按」——一次交互最多一次。所以照 `ChatThread.kt` 的 `observePointerTap` 同一模板，
    // 用一个普通引用持有，只在指针回调里读（见 [SessionRowBounds]）。
    val bounds = remember { SessionRowBounds() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 行块**左右内缩 8dp**——选中态不是通栏满宽矩形，而是一块带圆角的强调色块。
            .padding(horizontal = 8.dp)
            // J2：行高 56dp（单选行摘要规格；Q2-B 的 48dp 已被 alpha 审查裁决覆盖）。
            .height(SESSION_ROW_HEIGHT)
            .sessionRowBounds(bounds)
            .clip(RoundedCornerShape(if (selected) SELECTED_BLOCK_CORNER else skin.shapes.sm))
            // 选中态 = **带圆角的强调色块**（accent 淡染，亮 12% / 暗 16%，都在 10–16% 口径内）。
            .background(
                if (selected) {
                    skin.colors.primary.copy(
                        alpha = if (skin.colors.isDark) SELECTED_BLOCK_ALPHA_DARK else SELECTED_BLOCK_ALPHA_LIGHT,
                    )
                } else {
                    Color.Transparent
                },
            )
            // B2（s-meta-host）：长按 = 同一手势的第二个动作（combinedClickable），
            // onClick 语义/涟漪/选中态全部保持原样；长按带上行矩形（J3 锚点）。
            .combinedClickable(onClick = onClick, onLongClick = { onLongPress(session, bounds.value) })
            // 行的 testTag 挂在**块**上（内边距之前）：行高 / 信息顺序的断言都量这个节点。
            .testTag(sessionRowTag(session.id))
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 组色点槽位：固定宽度让「有组/无组」行的标题起点对齐；点 8dp、查不到合法色时
        // 退化 muted（sessionGroupTag 的既有规则 3/4，不动）。
        Box(
            Modifier.width(24.dp).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            if (groupTag != null) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(groupTag.color ?: skin.colors.onSurfaceVariant)
                        // 组名只走读屏语义（单行化），同时是 UI 测试定位「分组色点仍在」的锚点。
                        .semantics { contentDescription = "分组：${groupTag.label}" },
                )
            }
        }
        // 未读/置顶在标题**之前**（J2 的信息顺序）。
        if (session.pinned) {
            VadeIcon(
                vector = VadeIcons.Pin,
                contentDescription = PINNED_BADGE_LABEL,
                size = 13.dp,
                tint = skin.colors.primary,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (session.archived) {
            // 「已归档」标记（g-archive-ui 原样保留）：矢量图标 13dp + muted 色。
            VadeIcon(
                vector = VadeIcons.Archive,
                contentDescription = ARCHIVED_BADGE_LABEL,
                size = 13.dp,
                tint = skin.colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }
        if (session.unread) {
            Box(
                Modifier
                    .size(UNREAD_DOT_SIZE)
                    .clip(CircleShape)
                    .background(skin.colors.primary)
                    .semantics { contentDescription = UNREAD_BADGE_LABEL },
            )
            Spacer(Modifier.width(6.dp))
        }
        UiText(
            text = session.title,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
            size = 15,
            weight = FontWeight.SemiBold,
            // n1-nav #3：显式取 token（不再借 `UiText` 的默认值）——深色下来自主文本色，
            // 与列表其余文字同一套；`SessionsScreenDarkInkTest` 逐像素钉住。
            color = ink.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 时间在标题**之后**、贴行尾。空串不占位（真源缺 `updated_at` 时映射层给空串，
        // 不编一个「1970 天前」出来）。
        if (session.updatedLabel.isNotEmpty()) {
            UiText(
                text = session.updatedLabel,
                size = 11,
                color = ink.meta,
                maxLines = 1,
            )
        }
    }
}

/**
 * H3 · 行长按锚点的持有者 —— 一个**普通引用**，绝不写快照状态。
 *
 * 为什么不是 `mutableStateOf(IntRect)`（这是本次性能审查 H3 的原缺陷）：滚动 / 惯性 /
 * 抽屉滑动时，每一可见行的根坐标**每帧都在变** ⇒ `onGloballyPositioned` 每帧对每行各回调
 * 一次 ⇒ 若那里写的是快照状态，就是「每帧 × 可见行数」次快照写入（手机 12 行 × 120Hz
 * ≈ 1440 次/秒），而这些写入唯一的消费者是「长按」——一次交互最多一次。
 *
 * 同一仓里的正确模板是 `ChatThread.kt` 的 `observePointerTap`：坐标只在指针回调里读，
 * 从不参与组合，所以用普通引用。本类与 [sessionRowBounds] 就是把它抽出来供测试直接驱动
 * （`SessionRowBoundsTest` 用重组计数器钉住「布局回调不触发重组」）。
 *
 * [update] 里还做了一次同值短路：`IntRect` 是 data class（值比较），矩形没变（例如只是
 * 同一次布局被重放、或像素未变）时连字段都不写。取整也在这里做一次 —— 亚像素位移不会
 * 变成「新矩形」。
 */
internal class SessionRowBounds {
    /** 该行**上一次布局后**的窗口矩形（根坐标，px）。从未布局过 = [IntRect.Zero]。 */
    var value: IntRect = IntRect.Zero
        private set

    /** 布局后回调：同值短路，只有矩形真的变了才更新。 */
    fun update(rect: IntRect) {
        if (rect != value) value = rect
    }
}

/**
 * 布局后把本节点的窗口矩形记进 [bounds]（H3）。
 *
 * 只碰普通引用、不读写任何快照状态，所以**布局阶段不会让任何组合作用域失效**：
 * 滚动路径上这条 modifier 的代价就是一次 `localToRoot` + 4 次取整。
 */
internal fun Modifier.sessionRowBounds(bounds: SessionRowBounds): Modifier =
    onGloballyPositioned { coords ->
        val origin = coords.localToRoot(Offset.Zero)
        bounds.update(
            IntRect(
                left = origin.x.roundToInt(),
                top = origin.y.roundToInt(),
                right = (origin.x + coords.size.width).roundToInt(),
                bottom = (origin.y + coords.size.height).roundToInt(),
            ),
        )
    }

/**
 * 行节点的 testTag —— 测试量「行高 56dp」「未读/置顶在标题左、时间在标题右」时唯一的锚点
 * （按标题找节点会落到合并后的文本语义上，量不到块）。
 */
internal fun sessionRowTag(sessionId: String): String = "vade.sessionRow.$sessionId"

/**
 * 「已归档」标记的无障碍文案 —— 同时是 [VadeIcons.Archive] 的 `contentDescription`
 * 与 UI 测试的定位点（一个字符串只写一次，避免标记和测试各说各话）。
 */
internal const val ARCHIVED_BADGE_LABEL = "已归档"

/** 置顶/未读标记的语义文案（图标与圆点的 `contentDescription`，也是测试定位点）。 */
internal const val PINNED_BADGE_LABEL = "已置顶"
internal const val UNREAD_BADGE_LABEL = "未读"

/** 空态文案（J1）：标题、正文、唯一主行动的按钮文字 —— 测试与画面共用这一份。 */
internal const val SESSIONS_EMPTY_TITLE = "还没有会话"
internal const val SESSIONS_EMPTY_BODY = "新建一个会话，开始第一轮对话"
internal const val SESSIONS_EMPTY_ACTION = "新建会话"

/** 未读点直径（6dp：足够可辨，又不跟置顶图标抢注意力）。 */
internal val UNREAD_DOT_SIZE = 6.dp

/**
 * Q2-B 选中块的**可断言几何/色值常量**（g-route-transition 口径变更）：
 *  - 圆角 [SELECTED_BLOCK_CORNER]，落在口径要求的 12–16dp 里；
 *  - accent 淡染透明度亮/暗两档（[SELECTED_BLOCK_ALPHA_LIGHT] / [SELECTED_BLOCK_ALPHA_DARK]），
 *    都落在口径要求的 10–16% 里，文字对比不受影响（底色只被染了这么一点）；
 *  - 行块左右各内缩 8dp（`SessionRow` 的 `padding(horizontal = 8.dp)`，测试量像素）；
 *  - 「已选中」语义（`selected = true` 的行）没有 §12 的 3dp 左条——那根条已废除，
 *    `SessionRowSelectedBlockTest` 用像素断言接住新形态。
 */
internal val SELECTED_BLOCK_CORNER = 14.dp
internal const val SELECTED_BLOCK_ALPHA_LIGHT = 0.12f
internal const val SELECTED_BLOCK_ALPHA_DARK = 0.16f

/**
 * 会话行高 = **56dp**（alpha 审查 J2 裁决，2026-11-16）。
 *
 * 取代 Q2-B 的 48dp：单行摘要要塞进「未读 + 置顶 + 标题 + 时间」四件事，48dp 下 15sp 标题
 * 与 11sp 时间几乎贴边；56dp 是「一行仍是一行」与「信息不挤」的平衡点。`SessionRowInfoUiTest`
 * 逐像素断言这个高度，改数字必须同时改测试（否则「行高被悄悄改回去」无人接住）。
 */
internal val SESSION_ROW_HEIGHT = 56.dp

/**
 * 会话行的**文字墨色** —— 行内（以及分组头）唯一的取色点。
 *
 * 为什么需要一个具名映射而不是到处写 `skin.colors.onSurface`：深色模式「标题还是黑的」
 * 这类事故，评审能核对的前提是**同一屏上所有文字都能一眼看出取自哪个 token**。
 * 两档色值都来自皮肤的 `SkinColors`（`MaterialTokens` / `MiuixTokens` 的亮暗两套），
 * 这里不发明颜色，也不做任何增亮/压暗：
 *
 *  - [title] = `onSurface`：深色 `#EDEDF0`、亮色 `#17171A`；
 *  - [meta] = `onSurfaceVariant`：深色 `#9A9AA3`、亮色 `#6B6B73`。
 *
 * `SessionsScreenDarkInkTest` 直接用 `MaterialTokens.darkColors` / `lightColors` 比对它，
 * 并在真组合里逐像素断言字形（深色必须出现亮字形、亮色必须出现暗字形）。
 */
internal data class SessionRowInk(val title: Color, val meta: Color)

/** 当前皮肤 → 行的文字墨色（见 [SessionRowInk] 的两档来源）。 */
internal fun sessionRowInk(colors: SkinColors): SessionRowInk =
    SessionRowInk(title = colors.onSurface, meta = colors.onSurfaceVariant)

/**
 * 会话行上的「分类」展示值：组名（or 裸 id）+ 组色。
 *
 * 单独抽成一个小值类型而不是在 Composable 里现查，是为了让「查不到组怎么办」这条规则
 * 可以被纯函数测试钉住（Compose 里再测一遍字符串拼装纯属浪费）。
 *
 * [color] 可空：`MetaGroup.colorHex` 本身可空（契约 1.4.0 起 `color` 可被清除），且还有
 * 非法 `#RRGGBB` 的可能 —— 两种情况都**不编颜色**，由调用方退化成 muted 色。
 */
internal data class SessionGroupTag(val label: String, val color: Color?)

/**
 * `groupId` + 已知分组表 → 行上要显示的 [SessionGroupTag]；`null` = 不该显示任何东西。
 *
 * 规则（每条都有测试）：
 *  1. `groupId` 为 null / 空白 ⇒ **null**（未分组：连空行都不留，否则每行都多一段空白）；
 *  2. 在 [groups] 里查到同名分组 ⇒ 用它的 `name`；`name` 是空白串的畸形组同样退化到 id；
 *  3. 查不到（分组表没加载 / 传了空表 / id 拼错）⇒ **原样显示 `groupId`**。裸 id 不好看，
 *     但它比「悄悄显示成未分组」诚实，也比空着更能提示「组名没接上」。
 *  4. 颜色只认合法的 `#RRGGBB`（[MetaColors.isValid]）；缺省/非法 ⇒ null ⇒ muted 点。
 */
internal fun sessionGroupTag(groupId: String?, groups: List<MetaGroup>): SessionGroupTag? {
    val id = groupId?.trim().orEmpty()
    if (id.isEmpty()) return null
    val group = groups.firstOrNull { it.id == id }
    val label = group?.name?.trim()?.takeIf { it.isNotEmpty() } ?: id
    val color = group?.colorHex?.takeIf { MetaColors.isValid(it) }?.let { MetaColors.parse(it) }
    return SessionGroupTag(label = label, color = color)
}
