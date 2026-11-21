package vade.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import vade.ui.UiText
import vade.ui.component.liquid.VadeShadows
import vade.ui.component.liquid.layeredShadow
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.protocol.InputKind
import vade.ui.protocol.InputOption
import vade.ui.skin.BtnStyle
import vade.ui.skin.LocalSkin

/**
 * RFC-0008 · ask-user 的**模态问答卡**。
 *
 * 它住在 [ChatThread] 自己的层叠里（不是 `androidx.compose.ui.window.Dialog`）：
 *  - 平台 Dialog 是独立 window，会连外壳的玻璃顶栏一起盖住 —— 任务要求**不遮挡顶栏**；
 *    这里的遮罩只覆盖聊天内容链，表头 / 玻璃顶栏是它的兄弟且画在它之后，天然在它上面；
 *  - 遮罩是一层独立的点击吞噬层（`detectTapGestures {}`），卡片是它上面的兄弟 ——
 *    点在卡片外不会漏到消息行里，也不会误触发送/停止。
 *
 * 键盘：整张卡拿到焦点后 `Esc` 取消、选择类 `Enter` 提交、自由文本 `⌘/Ctrl+Enter`
 * 提交（裸 Enter 换行）。读写屏：卡根带一句可读的 `contentDescription`（问题原文），
 * 标题是 heading，选项行是 `selectable`（RadioButton / Checkbox 角色 + selected 态），
 * 输入框有自己的名字 —— 读屏不会只念一个「编辑框」。
 *
 * 倒计时只认 `expires_at`：到点本地摘卡并给状态行；**默认值不会变成答案**（timeout =
 * cancel，core 的 `turn.input_resolved(status=timeout)` 才是最终事实）。
 */
internal const val AskUserDialogTag = "vade.chat.input.dialog"

/** 自由文本输入框（`performTextInput` / 读屏定位用）。 */
internal const val AskUserInputFieldTag = "vade.chat.input.field"

/** 一个选项行；id 是 core 的 `option.id`（测试点它，不点易变的文案）。 */
internal fun askUserOptionTag(optionId: String): String = "vade.chat.input.option.$optionId"

/** 倒计时刷新间隔（只影响读数，不影响到点判定 —— 判定每次都用新的 `nowMs()`）。 */
private const val COUNTDOWN_TICK_MS = 250L

/** 卡片内容高度上限：选项多时卡内滚动，不把整页顶出屏幕。 */
private val DialogMaxHeight = 360.dp

/** 键盘语义（纯函数，测试直接驱动真实 handler，不必模拟焦点）。 */
internal enum class InputKeyAction { None, Submit, Cancel }

/**
 * 卡片的键位策略：
 *  - `Esc` → 取消（`turn/input.cancel`，回合继续）；
 *  - 自由文本：`⌘/Ctrl+Enter` 提交，裸 Enter 留给换行；
 *  - 单选 / 多选：`Enter` 提交（没有文本框会和它抢）；
 *  - confirm：Enter 不绑定（是/否由两键决定，避免方向靠猜）。
 */
internal fun handleInputKeys(event: KeyEvent, input: PendingInput): InputKeyAction {
    if (event.type != KeyEventType.KeyDown) return InputKeyAction.None
    return inputKeyAction(
        kind = input.kind,
        escape = event.key == Key.Escape,
        enter = event.key == Key.Enter || event.key == Key.NumPadEnter,
        primary = event.isMetaPressed || event.isCtrlPressed,
    )
}

/**
 * [handleInputKeys] 的纯决策核：去掉 `KeyEvent` 的平台构造之后，测试可以直接钉住
 * 「哪个键在哪种 kind 下等于提交 / 取消」，不必模拟焦点或造 AWT 事件。
 */
internal fun inputKeyAction(
    kind: InputKind,
    escape: Boolean,
    enter: Boolean,
    primary: Boolean,
): InputKeyAction {
    if (escape) return InputKeyAction.Cancel
    if (!enter) return InputKeyAction.None
    return when (kind) {
        InputKind.FREE_TEXT -> if (primary) InputKeyAction.Submit else InputKeyAction.None
        InputKind.SINGLE_CHOICE, InputKind.MULTI_CHOICE -> InputKeyAction.Submit
        InputKind.CONFIRM, InputKind.UNKNOWN -> InputKeyAction.None
    }
}

/**
 * 宿主：有 [ChatState.currentInput] 就画一张卡。放在 [ChatThread] 层叠的**最后一层**
 * （预览之上），因此问答到时不与消息 / composer / 预览抢层级。
 */
@Composable
internal fun AskUserInputDialogHost(state: ChatState) {
    val input = state.currentInput ?: return
    AskUserInputDialog(state, input)
}

@Composable
private fun AskUserInputDialog(state: ChatState, input: PendingInput) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.sheet)
    val focusRequester = remember { FocusRequester() }
    var now by remember(input.inputId) { mutableStateOf(state.nowMs()) }

    // 倒计时：每次 tick 重新读时钟再判定（不把「剩余 N 秒」当状态推进，时钟才是真值）。
    LaunchedEffect(input.inputId) {
        val expiresAt = input.request.expiresAt ?: return@LaunchedEffect
        now = state.nowMs()
        while (true) {
            if (state.nowMs() >= expiresAt) {
                state.timeoutInput(input.inputId)
                break
            }
            delay(COUNTDOWN_TICK_MS)
            now = state.nowMs()
        }
    }

    // 卡一出现就拿焦点：Esc / Enter 立刻可用，读屏也从标题开始念。
    LaunchedEffect(input.inputId) { runCatching { focusRequester.requestFocus() } }

    val remaining = input.remainingSeconds(now)
    val kindLabel = inputKindLabel(input.kind)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(skin.colors.previewScrim)
            .testTag(AskUserDialogTag),
        contentAlignment = Alignment.Center,
    ) {
        // 遮罩独立成一层：只吞点击，不参与卡片布局（卡片是它上面的兄弟）。
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { /* 模态：点外面不关闭，也不透传 */ } },
        )
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .fillMaxWidth()
                    .layeredShadow(shadow = VadeShadows.float, shape = shape, dark = skin.colors.isDark)
                    .clip(shape)
                    .background(skin.colors.surface)
                    .border(1.dp, skin.colors.hairline, shape)
                    .semantics {
                        contentDescription = "问答对话框：${input.request.prompt}。$kindLabel"
                    }
                    .focusRequester(focusRequester)
                    // Esc 走 preview：焦点在任何子节点（输入框 / 选项）里都能取消。
                    .onPreviewKeyEvent { event ->
                        if (handleInputKeys(event, input) == InputKeyAction.Cancel) {
                            state.cancelInput(input.inputId)
                            true
                        } else {
                            false
                        }
                    }
                    // 提交走冒泡：焦点还在卡片本身时 Enter 才提交；一旦 Tab 进选项 / 输入框，
                    // Enter 属于那个孩子（选项用它勾选、输入框用它换行），不会被卡片抢走。
                    .onKeyEvent { event ->
                        if (handleInputKeys(event, input) == InputKeyAction.Submit) {
                            state.submitInput(input.inputId)
                            true
                        } else {
                            false
                        }
                    }
                    .focusable()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DialogHeader(kindLabel = kindLabel, remaining = remaining)

                input.request.prompt.let { prompt ->
                    UiText(
                        text = prompt,
                        size = 16,
                        weight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                input.request.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                    UiText(text = detail, size = 12, color = skin.colors.onSurfaceVariant)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = DialogMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (input.kind) {
                        InputKind.FREE_TEXT -> FreeTextAnswer(state, input)
                        InputKind.SINGLE_CHOICE, InputKind.MULTI_CHOICE ->
                            input.options.forEach { option -> OptionRow(state, input, option) }
                        InputKind.CONFIRM, InputKind.UNKNOWN -> Unit
                    }
                }

                if (input.kind != InputKind.CONFIRM) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        skin.Button(
                            text = "取消",
                            onClick = { state.cancelInput(input.inputId) },
                            style = BtnStyle.Secondary,
                        )
                        Spacer(Modifier.width(8.dp))
                        skin.Button(
                            text = "提交",
                            onClick = { state.submitInput(input.inputId) },
                            enabled = state.canSubmitInput(input.inputId),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        skin.Button(
                            text = "否，不继续",
                            onClick = { state.submitConfirm(input.inputId, false) },
                            style = BtnStyle.Secondary,
                        )
                        Spacer(Modifier.width(8.dp))
                        skin.Button(
                            text = "是，继续",
                            onClick = { state.submitConfirm(input.inputId, true) },
                        )
                    }
                }

                UiText(
                    text = keyboardHint(input.kind),
                    size = 11,
                    color = skin.colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** 卡头：一问一答的「这是什么」+ 倒计时（只读 `expires_at`）。 */
@Composable
private fun DialogHeader(kindLabel: String, remaining: Int?) {
    val skin = LocalSkin.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            UiText(
                text = "需要你的回答",
                size = 11,
                weight = FontWeight.Medium,
                color = skin.colors.warning,
            )
            UiText(text = kindLabel, size = 11, color = skin.colors.onSurfaceVariant)
        }
        if (remaining != null) {
            UiText(
                // 卡头只放读数；「超时怎么办」写在底部提示里。
                text = "剩余 ${remaining}s",
                size = 11,
                color = if (remaining <= 10) skin.colors.warning else skin.colors.onSurfaceVariant,
            )
        }
    }
}

/** 自由文本：单行起、最多五行，空内容时画占位行（`BasicTextField` 本身没有 placeholder）。 */
@Composable
private fun FreeTextAnswer(state: ChatState, input: PendingInput) {
    val skin = LocalSkin.current
    val shape = RoundedCornerShape(skin.shapes.sm)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(skin.colors.surfaceVariant.copy(alpha = 0.45f))
            .border(1.dp, skin.colors.hairline, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (input.draft.isEmpty()) {
            UiText(text = "输入回答…", size = 13, color = skin.colors.onSurfaceVariant)
        }
        BasicTextField(
            value = input.draft,
            onValueChange = { state.updateInputDraft(input.inputId, it) },
            enabled = true,
            maxLines = 5,
            textStyle = skin.typography.body.copy(color = skin.colors.onSurface),
            cursorBrush = SolidColor(skin.colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .testTag(AskUserInputFieldTag)
                .semantics { contentDescription = "回答输入框" },
        )
    }
}

/** 一个选项：单选 = RadioButton 语义，多选 = Checkbox 语义（读屏能念出选中态）。 */
@Composable
private fun OptionRow(state: ChatState, input: PendingInput, option: InputOption) {
    val skin = LocalSkin.current
    val selected = input.isSelected(option.id)
    val shape = RoundedCornerShape(skin.shapes.sm)
    val accent = skin.colors.primary
    val isRadio = input.kind == InputKind.SINGLE_CHOICE
    val indicatorShape = if (isRadio) CircleShape else RoundedCornerShape(5.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.10f) else skin.colors.surface)
            .border(1.dp, if (selected) accent else skin.colors.hairline, shape)
            .selectable(
                selected = selected,
                role = if (isRadio) Role.RadioButton else Role.Checkbox,
                onClick = { state.toggleInputOption(input.inputId, option.id) },
            )
            .testTag(askUserOptionTag(option.id))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(indicatorShape)
                .border(1.5.dp, if (selected) accent else skin.colors.outline, indicatorShape)
                .background(if (selected) accent else skin.colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                VadeIcon(
                    vector = VadeIcons.Check,
                    contentDescription = null,
                    size = 12.dp,
                    tint = skin.colors.onPrimary,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            UiText(
                text = option.label,
                size = 13,
                weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            option.description?.takeIf { it.isNotBlank() }?.let { description ->
                UiText(text = description, size = 11, color = skin.colors.onSurfaceVariant)
            }
        }
    }
}

private fun inputKindLabel(kind: InputKind): String = when (kind) {
    InputKind.FREE_TEXT -> "自由作答"
    InputKind.SINGLE_CHOICE -> "单选"
    InputKind.MULTI_CHOICE -> "多选"
    InputKind.CONFIRM -> "确认"
    InputKind.UNKNOWN -> "问答"
}

/**
 * 键位提示只在有硬件键盘的平台成立：触屏上没有 Esc / Ctrl，照抄桌面文案会指向不存在的
 * 操作（与内联编辑器的 `c2-nav` 同一判据）。
 */
private fun keyboardHint(kind: InputKind): String {
    if (isTouchPlatform) return when (kind) {
        InputKind.CONFIRM -> "选择一个按钮继续"
        else -> "输入或勾选后点「提交」"
    }
    return when (kind) {
        InputKind.FREE_TEXT -> "⌘/Ctrl+Enter 提交 · Esc 取消（超时按未作答处理）"
        InputKind.SINGLE_CHOICE, InputKind.MULTI_CHOICE -> "Enter 提交 · Esc 取消（超时按未作答处理）"
        InputKind.CONFIRM -> "Tab 选择 · Enter 确认 · Esc 取消"
        InputKind.UNKNOWN -> "Esc 取消"
    }
}
