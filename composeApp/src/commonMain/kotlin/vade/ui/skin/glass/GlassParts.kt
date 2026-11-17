package vade.ui.skin.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vade.ui.component.liquid.LayeredShadow
import vade.ui.component.liquid.VadeShadows
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.ApprovalRequest
import vade.ui.skin.AttachmentState
import vade.ui.skin.AttachmentView
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.Decision
import vade.ui.skin.InputMode
import vade.ui.skin.NavItem
import vade.ui.skin.Scope
import vade.ui.skin.Skin
import vade.ui.skin.SkinColors
import vade.ui.skin.SkinShapes
import vade.ui.skin.SkinTypography
import vade.ui.skin.ToolCallView
import vade.ui.skin.ToolCardState
import vade.ui.skin.cardAccentBar

/**
 * `glass` 皮肤的组件实现 —— T2 的 7 个（`ListItem` / `Switch` / `BottomBar` /
 * `ApprovalDialog` / `AttachmentChip` / `ToolCallCard` / `SendOrb`）。
 *
 * 为什么单独一个文件（照 `material/MaterialParts.kt` 的先例）：`GlassSkin.kt` 里 T1 的
 * 四个组件已经占满一屏，再塞 7 个，「哪几个是真玻璃」就会被淹没在 1000 行里。**这里没有
 * 任何新的模糊管线**：每个组件的底座都是一次 `GlassSurface(...)`（haze / 染色 / 描边 /
 * 高光 / 阴影只有那一份实现），每个承载文字或图标的层都垫同一个 [GlassContentBase]。
 *
 * ## 两条贯穿全部 7 个组件的规则
 *
 * 1. **玻璃只做边缘与质感，正文永远在近不透明的内容底上**。这不是审美取舍：玻璃背后
 *    可以是深色图片、红字工具卡、视频帧，而 `tint` 最厚只能到 §5 的 45% —— 只有 tint 时
 *    亮色正文在纯黑背后 3.4:1、暗色在纯白背后 2.4:1（`GlassTokensContrastTest` 的
 *    `tintOnlyWouldNotSurviveAnArbitraryBackdrop` 就是这两个数）。垫上
 *    [GlassContentBase]（`surface @ 0.88`）之后，正文在纯黑/纯白两种极端下最低 12.87:1，
 *    所以「背后恰好是什么」不再影响可读性。
 * 2. **状态色在玻璃底上要换一支墨**。`colors.error` 压在 0.88 底上只有 3.89:1、warning
 *    3.63:1、success 4.28:1（都是亮色）—— 任务书要求「错误态不允许再出现 <4.5」，所以
 *    状态字一律走 [statusInk]（亮色压深 0.86）/[errorInk]。数字由
 *    `GlassComponentContrastTest` 逐条钉住。
 *
 * ## 各组件为什么是现在这个形状
 *
 *  - [GlassListItem]：**静止态透明**，只有 hover / 选中才出现玻璃。任务书 §T2 的原话是
 *    「行内透明，整块列表共享一层玻璃（不要每行一层，性能会崩）」—— 一屏 20 行如果各带
 *    一个 haze 节点，滚动帧率会比「不够玻璃」严重得多。玻璃层用 `matchParentSize` 垫在
 *    行**背后**，行本身（含 clickable / hoverable）在层级上保持稳定，不会因为「玻璃出现」
 *    而被重建（重建会让 hover 抖动：出现玻璃 → 节点换掉 → hover 丢失 → 玻璃消失）。
 *  - [GlassSwitch]：轨道在 off 态是真玻璃；on 态用**实心** accent —— 与 `Button` 的
 *    Primary 同一条规则（半透明 accent 会被背后的浅底提亮，白滑块在开态只剩 2.3:1，
 *    见审查 P1-2）。滑块是实心 + 1dp 玻璃描边，与 material 的 24dp 圆钮同尺寸。
 *  - [GlassBottomBar]：三种形态共用一次 [GlassBarPanel]（同一处 `GlassSurface` + 同一份
 *    内容底），差异只有 shape / 是否采样背景 / 阴影 / 外边距 —— 也就是 §9.3 的那三行。
 *    尺寸逐像素沿用 material（行高 64dp、悬浮 18/10dp、胶囊 pill），换皮肤不让底栏长高。
 *  - [GlassApprovalDialog]：遮罩比 §9 的 24% 更深（[dialogScrim]），面板高不透明度
 *    （内容底 0.88）—— 审批是安全关键路径，不能为了好看牺牲可读性（任务书 §T2 原话）。
 *  - [GlassAttachmentChip]：`md` 圆角玻璃芯片，28dp 缩略图 / 13sp 名称沿用 material；
 *    失败态的描边与状态字都换成 [errorInk]（不是 60% 透明度的弱红）。
 *  - [GlassToolCallCard]：在 `Card` 的玻璃 + 内容底之上补 `cardAccentBar` 状态条、参数/
 *    结果代码块（不透明 `surfaceVariant` 底）与 meta 槽；状态字走 [statusInk]。
 *  - [GlassSendOrb]：球面染色**就是**内容底（48dp 的球放不下一层独立内容底），所以玻璃
 *    质感由描边 + 高光关闭 + 浮层阴影 + 按压缩放承担；开态实心 accent，流式只把图标与
 *    1.5dp 环染成 [errorInk]（§2：球本身不变红）。
 */

/**
 * 不投阴影的一档 —— 平面控件（开关轨道、贴底底栏）用。
 *
 * `downwardShelf` 对空层列表是显式的「只画内容」分支（`layers.isEmpty()` → 直接
 * `drawContent()`），所以这不是「假装没有阴影」，而是真的不画。
 */
private val NoShelfShadow = LayeredShadow(emptyList())

/**
 * 玻璃层上的近不透明内容底 —— Card / InputBar / 用户气泡 / 行 / 底栏 / 弹层 / 芯片 /
 * 工具卡**共用这一个实现**（本批把 T1 的三处也从 `GlassSkin.kt` 的私有副本收到这里）。
 *
 * `matchParentSize()` 跟着玻璃的最终尺寸走、不参与测量（不会撑大组件），再 inset 1dp
 * 露出玻璃的一圈边。铺满整层而不是「按内容宽度」是必须的：短文本 + fillMaxWidth 的层
 * 右侧会剩一片纯玻璃 —— 那不是「玻璃只做边缘」。
 */
@Composable
internal fun BoxScope.GlassContentBase(colors: SkinColors, inner: Shape) {
    Box(
        Modifier
            .matchParentSize()
            .padding(GlassTokens.strokeWidth)
            .clip(inner)
            .background(contentBase(colors), inner),
    )
}

/** 内容底的内层形状：外层形状收进 1dp 描边（玻璃的那一圈边就露在这里）。 */
private fun innerShapeOf(outerRadius: Dp) =
    RoundedCornerShape(outerRadius - GlassTokens.strokeWidth)

// =============================== 4 · ListItem ===============================

/**
 * 玻璃行（C-09 组件 4）。
 *
 * 状态与 material 同义，只是「选中」的呈现从 `accentSoft` 色块变成**一层真玻璃**：
 *  - 静止：透明（性能，见文件头）；
 *  - hover：玻璃 + `raised` 阴影 + 内容底 —— 桌面上的「被指着」；
 *  - selected：玻璃 + 1dp accent 描边 + 左侧 3dp 竖条 + accent 标题（与 §12 会话列表一致）；
 *  - disabled：整行 0.45（与 material 同值），且**不出现玻璃**（一个不能点的行不该有浮起感）。
 *
 * 标题 / 副标题 / 图标全部压在 [GlassContentBase] 上：玻璃行背后可能是列表级玻璃或
 * 深色封面，行内文字不能因此掉到 4.5:1 以下。
 */
@Composable
internal fun GlassListItem(
    skin: Skin,
    title: String,
    leading: (@Composable () -> Unit)?,
    subtitle: String?,
    trailing: (@Composable () -> Unit)?,
    onClick: (() -> Unit)?,
    enabled: Boolean,
    selected: Boolean,
) {
    val colors = skin.colors
    val shapes = skin.shapes
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(shapes.sm)
    val tappable = onClick != null && enabled
    val showGlass = enabled && (hovered || selected)

    Box(Modifier.fillMaxWidth()) {
        // 玻璃垫在行**背后**（matchParentSize）：行的 modifier 链因此不随状态变化，
        // hover 不会因为「玻璃出现」而丢一次 enter。
        if (showGlass) {
            GlassSurface(
                backdropPolicy = GlassBackdropPolicy.DegradeWithoutBackdrop,
                modifier = Modifier.matchParentSize(),
                shape = shape,
                skin = skin,
                stroke = if (selected) colors.primary else null,
                highlight = GlassTokens.highlight,
                shadow = VadeShadows.raised,
            ) {
                GlassContentBase(colors, shape)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (tappable) Modifier.hoverable(interaction) else Modifier)
                .then(if (tappable) Modifier.clickable { onClick?.invoke() } else Modifier)
                .alpha(if (enabled) 1f else 0.45f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Box(Modifier.width(3.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(colors.primary))
                Spacer(Modifier.width(10.dp))
            }
            if (leading != null) {
                leading()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (selected) colors.primary else colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = colors.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

// ================================ 5 · Switch ================================

/**
 * 玻璃轨道开关（C-09 组件 5）—— 52×32 轨道 + 24dp 实心滑块，整行 ≥44dp 可点。
 *
 * 三态：
 *  - **关**：轨道是皮肤的真玻璃（tint + 1dp 描边 + 顶部高光 + 模糊），滑块 `surface` 实心、
 *    1dp [GlassTokens.stroke] 勾边（与 material 的 hairline 滑块同一语义：发丝线把白钮从
 *    近白的玻璃上分出来）；
 *  - **开**：轨道换成**实心** accent（[actionFill]），滑块 `onPrimary` —— 5.37:1（亮）/
 *    6.44:1（暗）。半透明 accent 在浅底上会把白钮洗到 2.3:1，与按钮 Primary 同一条规则；
 *  - **禁用**：整行 0.45，状态可读但不可点。
 *
 * 无障碍：`toggleable` + `Role.Switch`，语义树里带 `ToggleableState` —— 这也是测试能
 * 不靠 testTag 断言「开/关/禁用」三态的原因。
 */
@Composable
internal fun GlassSwitch(
    skin: Skin,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    val colors = skin.colors
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassSurface(
            backdropPolicy = GlassBackdropPolicy.DegradeWithoutBackdrop,
            modifier = Modifier.width(52.dp).height(32.dp),
            shape = CircleShape,
            skin = skin,
            // null = 皮肤自己的玻璃 tint（关态）。
            tintOverride = if (checked) actionFill(colors) else null,
            // 实心底座不画高光（同 Button Primary/Danger）：白纱会把滑块边缘洗淡。
            highlight = if (checked) null else GlassTokens.highlight,
            // 开关是平面控件：浮层阴影会把它读成「悬浮的胶囊」。
            shadow = NoShelfShadow,
            // 实心染色的后面再模糊一次只是白花 GPU。
            blurBackdrop = !checked,
        ) {
            Box(
                Modifier.fillMaxSize().padding(4.dp),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (checked) colors.onPrimary else colors.surface)
                        .border(1.dp, GlassTokens.stroke(colors.isDark), CircleShape),
                )
            }
        }
    }
}

// =============================== 6 · BottomBar ==============================

/**
 * 底栏的三种形态共用的一层玻璃。
 *
 * 抽出来只为一件事：三种形态**必须**是同一种材质（同一处 `GlassSurface` + 同一份内容底），
 * 差别只能是 shape / 是否采样背景 / 阴影 / 外边距。分开写三份 `GlassSurface(...)` 是这类
 * 组件最容易漂移的写法（改一处忘两处）。
 */
@Composable
private fun GlassBarPanel(
    skin: Skin,
    shape: Shape,
    blurBackdrop: Boolean,
    shadow: LayeredShadow,
    stroke: Color,
    drawsHighlight: Boolean,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        backdropPolicy = GlassBackdropPolicy.DegradeWithoutBackdrop,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        skin = skin,
        stroke = stroke,
        highlight = if (drawsHighlight) GlassTokens.highlight else null,
        shadow = shadow,
        blurBackdrop = blurBackdrop,
    ) {
        GlassContentBase(skin.colors, shape)
        content()
    }
}

/**
 * 玻璃底栏（C-09 组件 6）—— §9.3 的三态，尺寸逐像素沿用 material：
 * 行高 64dp、悬浮态四周 18/10dp、胶囊 `pill`。
 *
 * | 形态 | 底座 | 采样背景 | 阴影 | 描边 |
 * |---|---|---|---|---|
 * | `Fixed` | 贴底玻璃，`RectangleShape` | ✓ | 无（贴底不需要浮起） | 无（改画顶部 1dp
 *   `hairline`，与 material 的分隔线同一语义） |
 * | `Floating` | 悬浮胶囊玻璃 | ✗（§9.3 `floating`） | `float` | 玻璃发丝线 |
 * | `FloatingBlur` | 悬浮胶囊玻璃 | ✓（§9.3 `floating+blur`） | `float` | 玻璃发丝线 |
 *
 * 导航标签压在 [GlassContentBase] 上：底栏背后是滚动内容（`TopGlassGuardTest` 钉的就是
 * 「内容滚进玻璃底下」），标签不能直接压在半透明层上。选中色 `primary`（4.60:1 亮 /
 * 6.21:1 暗）、未选中 `onSurfaceVariant`（4.52 / 6.63）—— 两种都过 4.5。
 */
@Composable
internal fun GlassBottomBar(
    skin: Skin,
    items: List<NavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    style: BottomBarStyle,
) {
    val colors = skin.colors
    val shapes = skin.shapes
    val pill = RoundedCornerShape(shapes.pill)

    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        // 图标槽只出矢量（与 material 同一映射：未知 id 落中性 Circle）。
                        VadeIcon(
                            vector = VadeIcons.navIconOrNull(item.id) ?: VadeIcons.Circle,
                            contentDescription = null,
                            size = 20.dp,
                            tint = if (isSelected) colors.primary else colors.onSurfaceVariant,
                        )
                        if (item.badge != null) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    // 徽标是 8sp 的字，红底比 token 红再深一档（4.55 → 6.0 级）。
                                    .background(errorInk(colors)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(item.badge, fontSize = 8.sp, color = colors.onError)
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = item.label,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) colors.primary else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }

    when (style) {
        BottomBarStyle.Fixed -> Column(Modifier.fillMaxWidth()) {
            GlassBarPanel(
                skin = skin,
                shape = RectangleShape,
                blurBackdrop = true,
                shadow = NoShelfShadow,
                stroke = Color.Transparent,
                drawsHighlight = false,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    // 与 material 同一条分隔线（1dp hairline），只是现在它压在玻璃顶边上。
                    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.hairline))
                    row()
                }
            }
        }

        BottomBarStyle.Floating -> Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            GlassBarPanel(
                skin = skin,
                shape = pill,
                // §9.3 `floating` = 悬浮但**不**采样背景（只有染色 + 描边 + 高光）。
                blurBackdrop = false,
                shadow = VadeShadows.float,
                stroke = GlassTokens.stroke(colors.isDark),
                drawsHighlight = true,
            ) { row() }
        }

        BottomBarStyle.FloatingBlur -> Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
            GlassBarPanel(
                skin = skin,
                shape = pill,
                blurBackdrop = true,
                shadow = VadeShadows.float,
                stroke = GlassTokens.stroke(colors.isDark),
                drawsHighlight = true,
            ) { row() }
        }
    }
}

// ============================ 7 · ApprovalDialog ============================

/**
 * 玻璃审批弹层（C-09 组件 7，FR-8.53）—— **安全关键路径**，可读性优先：
 *
 *  - 遮罩 [dialogScrim]：亮 0.42 / 暗 0.58，比 §9 的全局弹窗 24% 更深。面板是玻璃（12%
 *    透光），24% 遮罩背后的聊天正文会以可读强度透进面板；
 *  - 面板：`sheet(32)` 圆角玻璃 + 浮层阴影 + 1dp 发丝线，内容底 0.88 —— 任务书 §T2 的
 *    「面板高不透明度」；
 *  - 参数块：不透明 `surfaceVariant` + `xs(12)` 圆角，等宽、可滚（FR-8.53）；
 *  - 风险徽标：不透明 `surfaceVariant` 底 + [errorInk]/`onSurfaceVariant` 字 —— 不用
 *    「error @12% 的半透明底」，那种底压在玻璃上会让红字掉到 4.5 以下；
 *  - 主行动：实心 accent（[actionFill]），其余是发丝线幽灵按钮；「拒绝」用 [errorInk]，
 *    不是 `colors.error`（3.89 → 4.99）。
 *
 * 不复用 `skin.Button` 的原因：三个按钮要 `weight(1f)` 等宽，而 C-09 的 `Button` 签名里
 * 没有 modifier（不能为这个加参数 —— 接口冻结）。这里用局部按钮保留 44dp 触摸区与 13sp。
 */
@Composable
internal fun GlassApprovalDialog(
    skin: Skin,
    request: ApprovalRequest,
    remainingSec: Int,
    onDecide: (Decision, Scope) -> Unit,
) {
    val colors = skin.colors
    val shapes = skin.shapes
    val typography = skin.typography
    val cardShape = RoundedCornerShape(shapes.sheet)
    val danger = errorInk(colors)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(dialogScrim(colors.isDark))
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            backdropPolicy = GlassBackdropPolicy.DegradeWithoutBackdrop,
            modifier = Modifier.widthIn(max = 400.dp),
            shape = cardShape,
            skin = skin,
            highlight = GlassTokens.highlight,
            shadow = VadeShadows.float,
        ) {
            GlassContentBase(colors, innerShapeOf(shapes.sheet))
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // grabber：唯一职责是提示「可下拉关闭」（§9）。
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.onSurface.copy(alpha = 0.24f)),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "需要批准",
                            fontSize = 11.sp,
                            color = statusInk(colors, colors.warning),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = request.toolName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val highRisk = request.risk == "high"
                    Text(
                        text = "risk=${request.risk}",
                        fontSize = 11.sp,
                        color = if (highRisk) danger else colors.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(shapes.pill))
                            // 不透明底：半透明 badge 压在玻璃上时红字实测 <4.5。
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                GlassCodeBlock(
                    text = request.arguments,
                    label = "ARGS",
                    colors = colors,
                    shapes = shapes,
                    typography = typography,
                    modifier = Modifier.heightIn(max = 180.dp),
                )
                Text(
                    text = "剩余 ${remainingSec}s · 超时按拒绝处理",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 文案保留文字是 §3 的例外口径：这个对话框只出现在模块页面/管理面，
                    // 「仅本次 / 本会话」是 scope 语义，图标表达不了。
                    GlassDialogButton(
                        label = "仅本次",
                        fill = actionFill(colors),
                        ink = colors.onPrimary,
                        radius = shapes.pill,
                        onClick = { onDecide(Decision.Approve, Scope.Once) },
                    )
                    GlassDialogButton(
                        label = "本会话",
                        fill = Color.Transparent,
                        ink = colors.onSurface,
                        radius = shapes.sm,
                        border = GlassTokens.stroke(colors.isDark),
                        onClick = { onDecide(Decision.Approve, Scope.Session) },
                    )
                    GlassDialogButton(
                        label = "拒绝",
                        fill = Color.Transparent,
                        ink = danger,
                        radius = shapes.sm,
                        border = GlassTokens.stroke(colors.isDark),
                        onClick = { onDecide(Decision.Deny, Scope.Once) },
                    )
                }
            }
        }
    }
}

/**
 * 弹层里的等宽按钮：44dp 触摸区、13sp、`weight(1f)` 等宽。
 *
 * 不挂玻璃：一枚 44dp 高、120dp 宽的按钮上，玻璃只会把字挤在中间那圈发丝线里；主行动
 * 用实心 accent（与 `Button` Primary 同一条对比度推导），其余是幽灵按钮。
 */
@Composable
private fun RowScope.GlassDialogButton(
    label: String,
    fill: Color,
    ink: Color,
    radius: Dp,
    border: Color? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        Modifier
            .weight(1f)
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(fill)
            .then(if (border != null) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// =========================== 9 · AttachmentChip =============================

/**
 * 玻璃附件芯片（C-09 组件 9，D95/D96）—— `md(20)` 圆角，28×28 缩略图、13sp 名称、
 * 11sp 状态行，尺寸全部沿用 material。
 *
 * 失败态（[AttachmentState.Failed]）是本批要求「不允许再出现 <4.5」的两处之一：
 * 描边与状态字都换成 [errorInk]（实色，不是 `error @60%` 的弱红），底仍是 0.88 内容底，
 * 于是红字在纯黑/纯白背后分别 4.99 / 5.83。
 *
 * 文案约束（D95/D96）：上传完成必须说「已压缩，原图未保留」—— 不许暗示原图可取回。
 */
@Composable
internal fun GlassAttachmentChip(
    skin: Skin,
    attachment: AttachmentView,
    onRetry: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    leading: (@Composable () -> Unit)?,
) {
    val colors = skin.colors
    val shapes = skin.shapes
    val failed = attachment.state == AttachmentState.Failed
    val danger = errorInk(colors)
    val status = when (attachment.state) {
        AttachmentState.Validating -> "校验中…"
        AttachmentState.Uploading -> "上传中 ${(attachment.progress * 100).toInt()}%"
        AttachmentState.Uploaded -> attachment.detail ?: "已上传 · 已压缩，原图未保留"
        AttachmentState.Failed -> attachment.detail ?: "上传失败"
    }
    val shape = RoundedCornerShape(shapes.md)

    GlassSurface(
        backdropPolicy = GlassBackdropPolicy.DegradeWithoutBackdrop,
        modifier = Modifier.heightIn(min = 44.dp),
        shape = shape,
        skin = skin,
        stroke = if (failed) danger else null,
        highlight = GlassTokens.highlight,
        shadow = VadeShadows.raised,
    ) {
        GlassContentBase(colors, innerShapeOf(shapes.md))
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(shapes.xs))) { leading() }
            } else {
                Box(
                    Modifier.size(28.dp).clip(RoundedCornerShape(shapes.xs))
                        .background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(attachment.mime.take(1).uppercase(), fontSize = 10.sp, color = colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.widthIn(max = 210.dp)) {
                Text(
                    text = attachment.fileName,
                    fontSize = 13.sp,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = status,
                    fontSize = 11.sp,
                    color = if (failed) danger else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (failed && onRetry != null) {
                Spacer(Modifier.width(4.dp))
                GlassIconTap(onClick = onRetry) {
                    VadeIcon(VadeIcons.Refresh, contentDescription = "重试", size = 16.dp, tint = colors.primary)
                }
            }
            if (onRemove != null) {
                Spacer(Modifier.width(4.dp))
                GlassIconTap(onClick = onRemove) {
                    VadeIcon(VadeIcons.Close, contentDescription = "移除附件", size = 14.dp, tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** 44dp 见方的图标点击区（§3 触摸目标 ≥44；视觉 28dp 级图标居中其中）。 */
@Composable
private fun GlassIconTap(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ============================ 10 · ToolCallCard =============================

/**
 * 玻璃工具卡（C-09 组件 10，D94）—— 折叠/展开/待审批三态由页面给，皮肤只画：
 *
 *  - 玻璃 `md(20)` + `shadow0`（静态卡 hairline-first，§8 row 1）；
 *  - 内容底 0.88：参数/结果旁边还有状态字与耗时，不能压在半透明层上；
 *  - 左侧 `cardAccentBar` 状态条（成功/警告/失败），画在内容底之上、正文之下；
 *  - 代码块用**不透明** `surfaceVariant` + `xs(12)` 圆角（§五/§10），等宽可滚；
 *  - 状态字一律 [statusInk]：`success` 4.28 / `warning` 3.63 在 0.88 底上不达标，
 *    压深后 5.41 / 4.66。
 */
@Composable
internal fun GlassToolCallCard(
    skin: Skin,
    call: ToolCallView,
    state: ToolCardState,
    onToggle: () -> Unit,
    meta: (@Composable () -> Unit)?,
) {
    val colors = skin.colors
    val shapes = skin.shapes
    val typography = skin.typography
    val expanded = state != ToolCardState.Collapsed
    val status = when {
        state == ToolCardState.PendingApproval -> statusInk(colors, colors.warning)
        call.isError -> errorInk(colors)
        else -> statusInk(colors, colors.success)
    }
    val stateLabel = when (state) {
        ToolCardState.PendingApproval -> "等待审批 · risk=${call.risk}"
        ToolCardState.Expanded -> if (call.isError) "失败" else "已完成"
        ToolCardState.Collapsed -> "成功"
    }
    val shape = RoundedCornerShape(shapes.md)

    GlassSurface(
        backdropPolicy = GlassBackdropPolicy.DegradeWithoutBackdrop,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        skin = skin,
        highlight = GlassTokens.highlight,
        shadow = VadeShadows.card,
    ) {
        GlassContentBase(colors, innerShapeOf(shapes.md))
        // 状态条必须在内容底**之后**画（`drawWithContent` 先画条、再画内容），这样它
        // 压在 0.88 的底上，而不是被底盖住；顶端沿 md 圆弧收细，见 CardAccentBar。
        Column(Modifier.fillMaxWidth().cardAccentBar(status, cornerRadius = shapes.md)) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clickable(onClick = onToggle)
                            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = call.name,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("工具", fontSize = 11.sp, color = colors.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text(stateLabel, fontSize = 11.sp, color = status)
                        Spacer(Modifier.width(6.dp))
                        VadeIcon(
                            vector = if (expanded) VadeIcons.ChevronDown else VadeIcons.ChevronRight,
                            contentDescription = null,
                            size = 12.dp,
                            tint = colors.onSurfaceVariant,
                        )
                    }
                    if (expanded) {
                        Column(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 12.dp)) {
                            Text("参数", fontSize = 11.sp, color = colors.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            GlassCodeBlock(
                                text = call.args,
                                label = "ARGS",
                                colors = colors,
                                shapes = shapes,
                                typography = typography,
                            )
                            if (call.result != null) {
                                Spacer(Modifier.height(8.dp))
                                Text("结果", fontSize = 11.sp, color = colors.onSurfaceVariant)
                                Spacer(Modifier.height(4.dp))
                                GlassCodeBlock(
                                    text = call.result,
                                    label = "RESULT",
                                    colors = colors,
                                    shapes = shapes,
                                    typography = typography,
                                    ink = if (call.isError) errorInk(colors) else colors.onSurface,
                                )
                            }
                        }
                    }
                    // meta 槽：页面给的耗时 / 批次 / 错误码，折叠与展开都显示。
                    if (meta != null) {
                        Box(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, bottom = 12.dp),
                        ) { meta() }
                    }
                }
            }
        }
    }
}

/**
 * v2 代码块（§五/§10）：不透明 `surfaceVariant` + xs(12) 圆角 + 顶部小标签行。
 *
 * 与 material 版的唯一差别是**没有**透明标签底（`surface @50%` 压在玻璃上会把 10sp 的
 * 标签洗到 4.5 以下）：标签行直接用 `surfaceVariant`，正文块与标签同底。
 */
@Composable
private fun GlassCodeBlock(
    text: String,
    label: String?,
    colors: SkinColors,
    shapes: SkinShapes,
    typography: SkinTypography,
    ink: Color = colors.onSurface,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(shapes.xs))
            .background(colors.surfaceVariant),
    ) {
        if (label != null) {
            Text(
                text = label,
                fontSize = 10.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Text(
            text = text,
            style = typography.mono,
            color = ink,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        )
    }
}

// ============================== 12 · SendOrb ===============================

/**
 * 按压 → 缩放比。抽成纯函数只为一件事：让「按压缩放」在没有像素夹具的批次里也可断言
 * （`GlassPartsTest` 直接算 1f / pressScale，源码守卫钉住组件真的走了这个函数）。
 */
internal fun orbPressScale(pressed: Boolean, pressScale: Float): Float =
    if (pressed) pressScale else 1f

/**
 * 玻璃发送球（C-09 组件 12，RFC-0011 / §2）—— 48dp 正圆、框外独立元素，三态：
 *
 *  - `Idle`：**玻璃球**。球面染色取 [contentBase]（0.88）—— 48dp 的球放不下一层独立的
 *    内容底，所以让染色本身承担内容底：图标在纯黑/纯白背后分别 13.60 / 17.89（亮）、
 *    15.84 / 10.96（暗），远高于图形要求的 3:1，也高于文字的 4.5；
 *  - `Composing`：实心 accent + 12dp glow（§8 row 3 辉光不是阴影），图标 `onPrimary`
 *    5.37 / 6.44；
 *  - `Streaming`：同一球位变 ■，只把图标与 1.5dp 环染成 [errorInk]（3.89 → 4.99），
 *    球本身**不变红**（§2）；`enabled=false` 时仍然可点 —— 中断生成永远可用。
 *
 * 玻璃质感的三件事：1dp [GlassTokens.stroke] 描边、浮层阴影、以及 12% 的透光。**不画
 * 顶部高光**：球面染色已经是近不透明的底，34% 白纱压在球的上沿会把图标洗淡（与 Button
 * Primary/Danger 同一条规则：高光是玻璃的语言，实心底座上它不是）。
 *
 * 按压 = [orbPressScale] × [SkinOrb.pressScale]，时长 [SkinOrb.pressDurationMs]，曲线
 * [SkinMotion.easing] —— 与 material 版逐字段一致（本批不动动效门）。
 */
@Composable
internal fun GlassSendOrb(
    skin: Skin,
    mode: InputMode,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val colors = skin.colors
    val orb = skin.orb
    val motion = skin.motion
    val streaming = mode == InputMode.Streaming
    val armed = mode == InputMode.Composing
    val stopInk = errorInk(colors)

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = orbPressScale(pressed, orb.pressScale),
        animationSpec = tween(durationMillis = orb.pressDurationMs, easing = motion.easing),
        label = "glassSendOrbPress",
    )

    Box(
        modifier = modifier
            .size(orb.size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (!enabled && !streaming) 0.45f else 1f
            }
            .drawBehind {
                // §8 row 3：辉光不是阴影 — accent 28%，向外 glowRadius 渐隐；只在 armed 画。
                if (armed) {
                    val outer = size.minDimension / 2f + orb.glowRadius.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.62f to colors.glowAccent.copy(alpha = 0.28f),
                            1f to colors.glowAccent.copy(alpha = 0f),
                            center = center,
                            radius = outer,
                        ),
                        radius = outer,
                    )
                }
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                // §2：空球仍可点（触发提示）；只有页面显式禁用时才静止，流式停止不受
                // enabled 影响 —— 中断生成永远可用。
                enabled = streaming || enabled,
            ) { onClick() }
            .semantics { contentDescription = if (streaming) "停止生成" else "发送消息" },
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            backdropPolicy = GlassBackdropPolicy.DegradeWithoutBackdrop,
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            skin = skin,
            // 球面染色即内容底（见 KDoc）：玻璃球上放不下第二层 1dp inset 的底。
            tintOverride = if (armed) actionFill(colors) else contentBase(colors),
            stroke = if (streaming) stopInk else null,
            strokeWidth = if (streaming) 1.5.dp else GlassTokens.strokeWidth,
            highlight = null,
            shadow = VadeShadows.float,
            // 实心 accent 的后面再模糊一次没有意义。
            blurBackdrop = !armed,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(orb.size * 0.42f)) {
                    if (streaming) {
                        paintOrbStop(stopInk)
                    } else {
                        paintOrbSend(if (armed) colors.onPrimary else colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * ↑ 发送箭头 — Canvas 描边（v2 §3.1 矢量、线宽随尺寸缩放）。
 *
 * 与 `material/MaterialParts.kt` 的两枚画笔是同一几何；那两枚是 `private`，而本批的边界
 * 是「不改 material/miuix」，所以复制这 15 行，而不是把它们改成 internal 跨皮肤 import
 * （皮肤之间不该互相依赖内部实现）。
 */
private fun DrawScope.paintOrbSend(color: Color) {
    val s = size.minDimension
    val cx = size.width / 2f
    val stroke = s * 0.10f
    drawLine(color, Offset(cx, s * 0.80f), Offset(cx, s * 0.24f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, Offset(cx, s * 0.24f), Offset(cx - s * 0.24f, s * 0.48f), strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color, Offset(cx, s * 0.24f), Offset(cx + s * 0.24f, s * 0.48f), strokeWidth = stroke, cap = StrokeCap.Round)
}

/** ■ 停止 — 填充圆角方块（§2：方形图标，不是红球）。 */
private fun DrawScope.paintOrbStop(color: Color) {
    val s = size.minDimension
    drawRoundRect(
        color = color,
        topLeft = Offset(s * 0.22f, s * 0.22f),
        size = Size(s * 0.56f, s * 0.56f),
        cornerRadius = CornerRadius(s * 0.09f),
    )
}
