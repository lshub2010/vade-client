package vade.ui.skin.glass

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vade.ui.component.liquid.VadeShadows
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.skin.ApprovalRequest
import vade.ui.skin.AttachmentView
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.BtnStyle
import vade.ui.skin.BubbleRole
import vade.ui.skin.Decision
import vade.ui.skin.InputMode
import vade.ui.skin.MessageAction
import vade.ui.skin.MessageView
import vade.ui.skin.NavItem
import vade.ui.skin.Scope
import vade.ui.skin.Skin
import vade.ui.skin.SkinColors
import vade.ui.skin.SkinGlass
import vade.ui.skin.SkinMotion
import vade.ui.skin.SkinOrb
import vade.ui.skin.SkinRegistry
import vade.ui.skin.SkinShapes
import vade.ui.skin.SkinTypography
import vade.ui.skin.ToolCallView
import vade.ui.skin.ToolCardState
import vade.ui.skin.material.MaterialSkin

/**
 * `glass` 皮肤 —— 任务书 52-U3（D93 / D22 / FR-8.23）。
 *
 * 本批（T1 + T2）交付的是**玻璃材质本身**，不是"另一套配色"：
 *
 *  1. 十一个组件真做玻璃：[Button]（胶囊）、[Card]（玻璃边缘 + 近不透明内容底）、
 *     [InputBar]（悬浮 composer 面板 + 内容底）、[MessageBubble]（用户气泡是玻璃 + 内容底，
 *     助手/系统按 §5 无气泡），以及 T2 的七个 —— [ListItem]（hover/选中态玻璃行）、
 *     [Switch]（玻璃轨道 + 实心滑块）、[BottomBar]（玻璃底栏三态）、[ApprovalDialog]
 *     （深遮罩 + 高不透明面板）、[AttachmentChip]、[ToolCallCard]、[SendOrb]（玻璃球 +
 *     按压缩放）。它们全部走 [GlassSurface]，所以模糊/染色/描边/高光只有一份实现；
 *     每一个承载文字或图标的层共用 [GlassContentBase]，正文在任何背景下都 ≥4.5:1。
 *  2. 十二个里**只剩 [TextField] 仍转发 `material`**。它是页内表单输入（不是 composer），
 *     玻璃版要先解决「玻璃底上的光标 / 内描边 / 错误行」三件事，留给下一批；其余的显式
 *     委托在 [GlassParts.kt] 里，本文件的 override 只剩一行转发调用。
 *  3. 配色**不换**（[GlassTokens.colorsFrom] 只改写玻璃相关 token）：glass 与 material 的
 *     区别必须是「半透明/模糊」，验收里那条盲测要指出的正是它。换一套紫色 accent 只会让
 *     盲测变成"颜色不一样"。
 *
 * 跨端强度不是这里决定的，而是 [GlassCapability] 决定的：Desktop / Wasm 是 `Blur`（模糊 +
 * 描边 + 高光，**没有折射**，D22）；Android **API 31（12）及以上**才是 `Blur`，24–30 降到
 * `TintStroke`（`Modifier.blur` / `RenderEffect` 在那之前不可用，谎报模糊就是审查 P1-1）；
 * Android 13+ 的 AGSL 折射属 T3；采不到背景时也降到 `TintStroke`，仍然是完整可读的一层。
 * 这条阶梯让"不崩、不白屏"成为结构事实，而不是每个组件的 try/catch。
 *
 * 注册：本类**不自动启用**。[GlassSkinEntry.register] 必须由宿主显式调用
 * （与 U2 的 `MiuixSkinEntry` 同一手法），`Skins.installBuiltIns()` 不碰 `glass`。
 */
class GlassSkin(
    private val dark: Boolean = false,
    override val id: String = "glass",
) : Skin {

    /**
     * 回退实现。持有它而不是 `by MaterialSkin(...)`：Kotlin 的接口委托**不会**把
     * `@Composable` 复制到生成的转发方法上，Compose 编译器会报"在非 composable 上下文调用"。
     * 显式逐条转发多写几十行，但编译期就能确定，不会在某个目标上才炸。
     */
    private val base: Skin = MaterialSkin(skinId = id, dark = dark)

    override val colors: SkinColors = GlassTokens.colorsFrom(base.colors)
    override val shapes: SkinShapes = base.shapes
    override val typography: SkinTypography = base.typography
    override val motion: SkinMotion = base.motion
    override val orb: SkinOrb = base.orb

    /** 本皮肤的玻璃 token 投影回冻结的 [SkinGlass]（单一真相，见 [GlassTokens.skinGlass]）。 */
    override val glass: SkinGlass = GlassTokens.skinGlass(dark)

    // --- 1 · Button ------------------------------------------------------------

    /**
     * 胶囊玻璃按钮。
     *
     * 三种 intent 的底座不同，因为"玻璃"对它们的影响不一样：
     *
     *  - `Primary` —— **实心** accent（[actionFill]）。玻璃不该把主行动洗淡，而且半透明的
     *    accent 会被背后的浅卡**提亮**：白字从 5.37:1 跌到 4.24:1（审查 P1-2 实测 4.29）。
     *  - `Danger` —— **实心**红（[actionFill]，亮色再压深 10%）。半透明 92% 时白字只有
     *    3.88:1；白 tint 更会把 `colors.error` 冲成粉色，危险操作就失去了警告的含义。
     *  - `Secondary` —— 纯玻璃（`SkinGlass` 的 tint）+ 1dp 描边 + 顶部高光，
     *    是"玻璃"最本色的形态。
     *  - `Text` —— 不画玻璃，只留文字（与 material 同义：无底的第三级操作）。
     *
     * **Primary / Danger 不画顶部高光**：实心底座上还有一层 34% 白的话，白字在 label 上沿
     * 那一带会再掉 ~0.7 个比值（[actionLabelContrast] 能把这个假设算出来）。高光是
     * 「玻璃」的语言，实心按钮不是玻璃。
     *
     * 尺寸沿用 material 的语义：44dp 见方最小点击区，单字形按 20dp 级图标渲染（§3）。
     *
     * **点击在 `clip` 之内**（审查 P1 顺手项）：`clickable` 必须挂在一个已经被裁成胶囊的
     * 节点上，否则水波纹是矩形 —— Compose 的 indication 画在它所在的那一层，链上更晚的
     * `clip` 裁不到它。这里把 `clickable` 放进玻璃的内容里（内容本来就被 GlassSurface
     * `clip(shape)`），而不是留在外层 modifier 链的最前面。
     */
    @Composable
    override fun Button(text: String, onClick: () -> Unit, style: BtnStyle, enabled: Boolean) {
        val isGlyph = text.length == 1
        val pill = RoundedCornerShape(shapes.pill)
        val labelColor = when {
            !enabled -> colors.onSurfaceVariant
            style == BtnStyle.Primary -> colors.onPrimary
            style == BtnStyle.Danger -> colors.onError
            style == BtnStyle.Text -> colors.primary
            else -> colors.textOnGlass
        }
        // null = 用皮肤自己的 tint（GlassSurface 按亮/暗取 GlassTokens.tintLight/Dark）。
        val tintForStyle = when (style) {
            BtnStyle.Primary -> actionFill(colors, danger = false)
            BtnStyle.Danger -> actionFill(colors, danger = true)
            BtnStyle.Secondary, BtnStyle.Text -> null
        }
        val frame = Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = if (isGlyph) 44.dp else 64.dp)
            .alpha(if (enabled) 1f else 0.5f)

        val label: @Composable () -> Unit = {
            Text(
                text = text,
                color = labelColor,
                fontSize = if (isGlyph) 18.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = if (isGlyph) 8.dp else 18.dp),
            )
        }

        if (style == BtnStyle.Text) {
            // 无底按钮同样要裁一次：水波纹是唯一的反馈，矩形波纹看着像 bug。
            Box(
                frame.clip(pill).clickable(enabled = enabled, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { label() }
        } else {
            GlassSurface(
                backdropPolicy = GlassBackdropPolicy.DegradeWithoutBackdrop,
                modifier = frame,
                shape = pill,
                skin = this@GlassSkin,
                tintOverride = tintForStyle,
                highlight = if (style == BtnStyle.Secondary && enabled) GlassTokens.highlight else null,
                contentAlignment = Alignment.Center,
                interactionModifier = Modifier.clickable(enabled = enabled, onClick = onClick),
            ) {
                // 内容直接参与测量：标签决定胶囊宽度，clickable 由 GlassSurface 在
                // clip 之后挂上（interactionModifier），水波纹仍被胶囊裁切。
                label()
            }
        }
    }

    // --- 3 · Card --------------------------------------------------------------

    /**
     * 玻璃卡：**玻璃只做边缘，内容区近不透明**（任务书 §T2 的可读性硬指标）。
     *
     * 内层 [GlassContentBase]（`surface @ 0.88`）不是装饰：玻璃背后的内容可能是深色图片或
     * 红字工具卡，正文压在半透明底上就必然掉到 4.5:1 以下。0.88 让两种极端（背后纯黑 /
     * 纯白）都有余量 —— 亮色 15.31:1 / 17.89:1、暗色 15.84:1 / 12.87:1，由
     * [inkOnGlassBase] 计算、`GlassComponentContrastTest` 断言。1dp 的 inset 正好露出玻璃
     * 的一圈边 —— 玻璃在这里是"材质提示"，不是背景。
     *
     * 阴影用 `VadeShadows.card`（§8 静态卡 hairline-first，阴影极浅）而不是浮层 shadow2：
     * 一页里会有多张卡，每张都带浮层阴影会把页面压成"一堆悬浮窗"。
     */
    @Composable
    override fun Card(modifier: Modifier, content: @Composable () -> Unit) {
        val outer = RoundedCornerShape(shapes.md)
        val inner = RoundedCornerShape(shapes.sm)
        GlassSurface(
            backdropPolicy = GlassBackdropPolicy.DegradeWithoutBackdrop,
            modifier = modifier,
            shape = outer,
            skin = this@GlassSkin,
            highlight = GlassTokens.highlight,
            shadow = VadeShadows.card,
        ) {
            GlassContentBase(colors, inner)
            content()
        }
    }

    // --- 11 · InputBar ---------------------------------------------------------

    /**
     * 悬浮 composer 面板：玻璃底 + 描边 + 顶部高光 + 浮层阴影（§1/§8 row 2）。
     *
     * 与 material 版的差别只有底座：`surfaceGlass + layeredShadow` 换成 [GlassSurface]，
     * 于是面板在接了 `GlassScope` 的页面上会**真的**模糊背后的消息列表 —— RFC-0011 §7.2
     * 要求的正是这个。聚焦时描边换成 2dp accent（与 material 的聚焦语义一致），因为玻璃
     * 上的 1dp 白描边太弱，不足以表达"我现在在输入"。
     *
     * 面板**不带外边距**：`[＋] [InputBar] [12dp] [SendOrb]` 的 16dp/24dp 浮层边距是页面
     * 布局（RFC-0011 §4.1/D5），组件内加边距会让它们叠成两倍。
     *
     * [mode] 在这里不参与绘制：发送/停止已经移出框外成 `SendOrb`（RFC-0011），面板的形状
     * 与状态无关。保留形参是为了满足冻结的 C-09 签名。
     *
     * **内容底**（审查 P1-4）：输入的文字是这一屏里最需要稳定的东西，而面板本身是玻璃。
     * 与 Card 同一个 [ContentBase]（`surface @ 0.88`）压在内容之下 —— 没有它，输入的文字
     * 直接压在 `tint ⊕ 背后消息列表` 上：亮色白底 17:1 看着没问题，但背后是深色图片时
     * 会掉到 3:1 以下（玻璃背后的内容不受皮肤控制）。[worstCaseContentContrast] 钉的是
     * 黑底与白底两种极端都 ≥4.5:1。
     */
    @Composable
    override fun InputBar(
        value: String,
        onValueChange: (String) -> Unit,
        mode: InputMode,
        attachments: (@Composable () -> Unit)?,
        enabled: Boolean,
        voiceAvailable: Boolean,
        maxLines: Int,
        leading: (@Composable () -> Unit)?,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val focused by interaction.collectIsFocusedAsState()
        val panelShape = RoundedCornerShape(shapes.xl)
        GlassSurface(
            backdropPolicy = GlassBackdropPolicy.DegradeWithoutBackdrop,
            modifier = Modifier.fillMaxWidth(),
            shape = panelShape,
            skin = this@GlassSkin,
            stroke = if (focused) colors.primary else null,
            strokeWidth = if (focused) 2.dp else GlassTokens.strokeWidth,
            highlight = GlassTokens.highlight,
        ) {
            GlassContentBase(colors, RoundedCornerShape(shapes.xl - GlassTokens.strokeWidth))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 多行高度跟随必须是 200ms glide，且画在内容上（g-shadow-android：包在
                    // 面板外层会连带裁掉面板外的下行阴影）。
                    .animateContentSize(
                        animationSpec = tween(durationMillis = 200, easing = motion.easing),
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (attachments != null) {
                    attachments()
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    if (leading != null) {
                        leading()
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp, max = (maxLines * 22 + 24).dp)
                            .alpha(if (enabled) 1f else 0.5f),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty()) {
                            Text("输入消息…", style = typography.body, color = colors.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            enabled = enabled,
                            maxLines = maxLines,
                            interactionSource = interaction,
                            textStyle = typography.body.copy(color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // D97: 无端侧 STT 时不画麦克风（不是画一个禁用的）。
                    if (voiceAvailable) {
                        Spacer(Modifier.width(10.dp))
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(colors.surface.copy(alpha = 0.55f))
                                .clickable { },
                            contentAlignment = Alignment.Center,
                        ) {
                            VadeIcon(
                                VadeIcons.Mic,
                                contentDescription = "语音输入",
                                size = 16.dp,
                                tint = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    // --- 8 · MessageBubble -----------------------------------------------------

    /**
     * 气泡（D92/D92a + §5）：
     *
     *  - 助手：**无气泡**、全宽 ≤ [maxWidth]，玻璃只出现在用户侧 —— 助手回复是长文本，
     *    底下垫一层半透明会被读成"引用块"，而且一屏十几条模糊层会直接压垮滚动帧率；
     *  - 系统：居中弱化提示，无玻璃；
     *  - 用户：玻璃气泡（lg 圆角 + 右下 6dp 尾），这是玻璃皮肤与 material 视觉差异最明显
     *    的一处 —— 同一句话在 material 下是实心 surface，在这里能看见背后的列表在动。
     *
     * [body] 槽必须被消费（C-09 冻结后的追加）：页面把 Markdown 渲染器交进来，皮肤不能
     * 用 `Text(message.text)` 把它顶掉，否则字面的 `**` 和三反引号会直接上屏（D3）。
     */
    @Composable
    override fun MessageBubble(
        message: MessageView,
        role: BubbleRole,
        maxWidth: Dp,
        actions: List<MessageAction>,
        onAction: (MessageAction) -> Unit,
        body: (@Composable () -> Unit)?,
    ) {
        val isUser = role == BubbleRole.User
        val isSystem = role == BubbleRole.System
        val textColor = when {
            message.isError -> colors.error
            isSystem -> colors.onSurfaceVariant
            else -> colors.onSurface
        }
        val content: @Composable () -> Unit = {
            if (body != null) {
                body()
            } else {
                Text(text = message.text, style = typography.body, color = textColor)
            }
        }
        val bubbleShape = RoundedCornerShape(
            topStart = shapes.lg,
            topEnd = shapes.lg,
            bottomEnd = 6.dp,
            bottomStart = shapes.lg,
        )

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 视觉规范 §5：用户气泡最大 78%，助手全宽 ≤760。
            val container = this.maxWidth
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = when {
                    isUser -> Alignment.End
                    isSystem -> Alignment.CenterHorizontally
                    else -> Alignment.Start
                },
            ) {
                when {
                    !isUser && !isSystem -> Box(
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = maxWidth)
                            .padding(vertical = 2.dp),
                    ) { content() }

                    isSystem -> Box(
                        Modifier
                            .widthIn(max = minOf(container * 0.82f, maxWidth))
                            .padding(vertical = 2.dp),
                    ) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            content()
                        }
                    }

                    else -> GlassSurface(
                        backdropPolicy = GlassBackdropPolicy.DegradeWithoutBackdrop,
                        modifier = Modifier.widthIn(max = minOf(container * 0.78f, maxWidth)),
                        shape = bubbleShape,
                        skin = this@GlassSkin,
                        highlight = GlassTokens.highlight,
                    ) {
                        // 用户气泡与 Card / InputBar 共用同一个内容底（审查 P1-4）：气泡里是
                        // 用户自己打的正文，不能因为背后恰好是一张图就读不清。
                        GlassContentBase(colors, bubbleShape)
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
                    }
                }

                if (message.badges.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.badges.forEach { badge ->
                            Text(
                                text = badge,
                                fontSize = 11.sp,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(shapes.pill))
                                    .background(colors.surface.copy(alpha = 0.72f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                if (actions.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        actions.forEach { action ->
                            Text(
                                text = action.label,
                                fontSize = 12.sp,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier
                                    .heightIn(min = 44.dp)
                                    .clickable { onAction(action) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // --- 2 · TextField（唯一仍转发 material 的组件）------------------------------
    // 页内表单输入（不是 composer）：玻璃底上的光标、内描边、错误行要先各自定一套语义，
    // 留给下一批。其余 11 个组件都是真玻璃。

    @Composable
    override fun TextField(
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String?,
        error: String?,
        enabled: Boolean,
    ) = base.TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        error = error,
        enabled = enabled,
    )

    // --- 4/5/6/7/9/10/12 · T2 的七个真玻璃组件 ----------------------------------
    // 实现全在 GlassParts.kt（与 material 把 7–11 放进 MaterialParts.kt 同一手法）：
    // 这里只留签名转发，好处是「哪几个组件是玻璃」在本文件里一眼可数，而玻璃的画法
    // （GlassSurface + GlassContentBase + statusInk）只有 GlassParts.kt 那一份。

    /** 4 · [ListItem] —— 玻璃只在 hover / 选中时出现（静止态透明，见 GlassParts.kt 文件头）。 */
    @Composable
    override fun ListItem(
        title: String,
        leading: (@Composable () -> Unit)?,
        subtitle: String?,
        trailing: (@Composable () -> Unit)?,
        onClick: (() -> Unit)?,
        enabled: Boolean,
        selected: Boolean,
    ) = GlassListItem(
        skin = this@GlassSkin,
        title = title,
        leading = leading,
        subtitle = subtitle,
        trailing = trailing,
        onClick = onClick,
        enabled = enabled,
        selected = selected,
    )

    /** 5 · [Switch] —— 玻璃轨道（关）/ 实心 accent 轨道（开）+ 实心滑块。 */
    @Composable
    override fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean) =
        GlassSwitch(
            skin = this@GlassSkin,
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )

    /** 6 · [BottomBar] —— 三态共用一层玻璃，尺寸与 material 逐像素一致。 */
    @Composable
    override fun BottomBar(
        items: List<NavItem>,
        selected: Int,
        onSelect: (Int) -> Unit,
        style: BottomBarStyle,
    ) = GlassBottomBar(
        skin = this@GlassSkin,
        items = items,
        selected = selected,
        onSelect = onSelect,
        style = style,
    )

    /** 7 · [ApprovalDialog] —— 深遮罩 + 高不透明面板（安全关键路径）。 */
    @Composable
    override fun ApprovalDialog(
        request: ApprovalRequest,
        remainingSec: Int,
        onDecide: (Decision, Scope) -> Unit,
    ) = GlassApprovalDialog(
        skin = this@GlassSkin,
        request = request,
        remainingSec = remainingSec,
        onDecide = onDecide,
    )

    /** 9 · [AttachmentChip] —— 玻璃芯片；失败态用 [errorInk]（不是 60% 弱红）。 */
    @Composable
    override fun AttachmentChip(
        attachment: AttachmentView,
        onRetry: (() -> Unit)?,
        onRemove: (() -> Unit)?,
        leading: (@Composable () -> Unit)?,
    ) = GlassAttachmentChip(
        skin = this@GlassSkin,
        attachment = attachment,
        onRetry = onRetry,
        onRemove = onRemove,
        leading = leading,
    )

    /** 10 · [ToolCallCard] —— 玻璃静态卡 + 内容底 + 状态条 + 不透明代码块。 */
    @Composable
    override fun ToolCallCard(
        call: ToolCallView,
        state: ToolCardState,
        onToggle: () -> Unit,
        meta: (@Composable () -> Unit)?,
    ) = GlassToolCallCard(
        skin = this@GlassSkin,
        call = call,
        state = state,
        onToggle = onToggle,
        meta = meta,
    )

    /** 12 · [SendOrb] —— 玻璃球（染色即内容底）+ 按压缩放，动效门与 material 逐字段一致。 */
    @Composable
    override fun SendOrb(
        mode: InputMode,
        enabled: Boolean,
        onClick: () -> Unit,
        modifier: Modifier,
    ) = GlassSendOrb(
        skin = this@GlassSkin,
        mode = mode,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * 注册缝 —— 与 `MiuixSkinEntry` / `MaterialSkinEntry` 完全同一手法。
 *
 * **不自动启用**：`Skins.installBuiltIns()` 不调用它，所以外观选择器里 `glass` 仍然显示
 * 「待安装」，直到宿主显式 `GlassSkinEntry.register()`。这是 I2 的决定：上一版把 glass 注册成
 * "material 换一个 id" 的占位实现，选「玻璃」像素不变 —— 一个对自己撒谎的皮肤。
 *
 * [dark] 与 material 的入口旗标同源（桌面 `--dark` / `VADE_DARK=1`），由宿主在**第一次**
 * `SkinRegistry.get("glass")` 之前写入；放在 `() -> Skin` 签名之外，C-09 的注册契约不动。
 */
object GlassSkinEntry {
    /** 下一次工厂调用产出暗色 token；测试/外观页可重置。 */
    var dark: Boolean = false

    fun register() = SkinRegistry.register("glass") { GlassSkin(dark = dark) }
}
