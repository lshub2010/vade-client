package vade.ui.destinations

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.chat.ChatPreset
import vade.ui.chat.ChatState
import vade.ui.chat.ChatThread
import vade.ui.chat.StatusPill
import vade.ui.chat.Tone
import vade.ui.data.SessionSummary
import vade.ui.icon.VadeIcon
import vade.ui.icon.VadeIcons
import vade.ui.model.InMemoryModelActions
import vade.ui.model.ModelActions
import vade.ui.model.ModelPickerCard
import vade.ui.model.ModelState
import vade.ui.skin.LocalSkin

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
 * U1-model · the header's model line is now the session-model switcher entry: tapping it
 * expands a [ModelPickerCard] between header and thread. All model reads/writes go through
 * the injected [modelActions] seam; when the shell has not wired one yet (this round), an
 * in-memory fixture implementation keeps the picker fully interactive and honestly labelled.
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
     * The shell's floating glass top-bar zone (see `shell/AppShell.kt`), applied as
     * **content** padding: the thread runs edge-to-edge from the very top of the window so
     * messages really scroll behind the glass bar (visual language §5 — a blur with nothing
     * behind it reads as a solid strip, which is exactly the 「看不出有模糊」 complaint).
     * Container padding would crop the viewport below the bar and no row could ever pass
     * under it.
     */
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val skin = LocalSkin.current
    if (session == null) {
        Box(modifier.fillMaxSize().background(skin.colors.surface), contentAlignment = Alignment.Center) {
            UiText("在左侧选择一个会话", size = 12, color = skin.colors.onSurfaceVariant)
        }
        return
    }

    val chat = remember(session.id, preset) { ChatState(session.id, preset = preset) }
    val actions = modelActions ?: remember(session.id) {
        // Seed the fixture with the session's fake model so the picker and the header agree.
        InMemoryModelActions(
            initialSessionOverrides = session.model?.let { mapOf(session.id to it) }.orEmpty(),
        )
    }
    val modelState = remember(actions, session.id) { ModelState.forSession(actions, session.id) }
    var modelPickerOpen by remember(session.id) { mutableStateOf(false) }

    // Full-bleed stack: the thread spans the whole window (scrolling under glass bar and
    // header alike); the header — and the model picker it expands — float *below* the glass
    // bar zone as the later sibling and stay opaque, so their own content remains readable
    // while rows slide under them. The header→picker flow stays inside one floating column
    // exactly as the in-flow version arranged it.
    Box(modifier.fillMaxSize()) {
        ChatThread(
            state = chat,
            modifier = Modifier.fillMaxSize(),
            animate = animate,
        )
        Column(Modifier.padding(top = contentPadding.calculateTopPadding())) {
            DetailHeader(session, chat, showTitle, modelState, modelPickerOpen) {
                modelPickerOpen = !modelPickerOpen
            }
            if (modelPickerOpen) {
                ModelPickerCard(
                    state = modelState,
                    title = "会话模型",
                    footerNote = "仅本会话生效，下一轮对话起使用（D101：会话级 > 全局默认 > priority）" +
                        "· 假数据：未接 core，接真数据只需实现 vade.ui.model.ModelActions",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Title + the two facts a chat header owes the reader in this slice: which model would answer
 * (D101 — read from [modelState], so a picker switch updates the line immediately) and that
 * the data is fake. No 「已连接」 badge — U1-b has no socket, and a green dot
 * that means nothing is exactly the kind of lie §8.5 forbids.
 *
 * The model line doubles as the switcher affordance: a full-width tap row (≥44dp including
 * its padding) with a chevron, expanding the picker card below the header.
 */
@Composable
private fun DetailHeader(
    session: SessionSummary,
    chat: ChatState,
    showTitle: Boolean,
    modelState: ModelState,
    pickerOpen: Boolean,
    onToggleModel: () -> Unit,
) {
    val skin = LocalSkin.current
    // With the shell top bar already naming the session, a second 15sp copy of the same
    // title 80dp lower reads as a rendering bug — so the compact form keeps the avatar and
    // one metadata line, and the 「假数据」 fact is stated once (it is in the top bar
    // subtitle there).
    val modelLine = buildString {
        append("模型 ${modelState.currentLabel()}")
        if (showTitle) append(" · 假数据（未接 core）")
        append(" · ${chat.messages.size} 条消息")
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
                        .clickable(onClick = onToggleModel)
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
                    VadeIcon(
                        VadeIcons.ChevronDown,
                        contentDescription = if (pickerOpen) "收起模型选择" else "更改会话模型",
                        size = 16.dp,
                        tint = skin.colors.onSurfaceVariant,
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
