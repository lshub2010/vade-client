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
import vade.ui.chat.InMemoryAttachmentUploader
import vade.ui.chat.InMemoryTurnGateway
import vade.ui.chat.StatusPill
import vade.ui.chat.Tone
import vade.ui.data.AdapterProvider
import vade.ui.data.SessionSummary
import vade.ui.model.InMemoryModelActions
import vade.ui.model.ModelActions
import vade.ui.model.ModelComposerChip
import vade.ui.model.ModelPickerSheet
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
 * U1-model → g-model-chip · the model switcher moved OUT of the page flow: the header model
 * line is plain information again (what would answer, message count, degraded reason), the
 * TRIGGER is the [ModelComposerChip] inside the composer's bottom row (same line as the send
 * orb, via `ChatThread(inputLeading = …)` → `Skin.InputBar(leading = …)`), and tapping it
 * opens the [ModelPickerSheet] — the §9 floating sheet card — over the whole screen. Nothing
 * pushes the transcript anymore. All model reads/writes go through the injected [modelActions]
 * seam; when the shell has not wired one yet (this round), an in-memory fixture implementation
 * keeps the picker fully interactive and honestly labelled.
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
     * Shell-owned failure banner (g-error-retry) for the **message-thread** read. A slot, so
     * the screen keeps knowing nothing about the loader; the retry inside it calls
     * `UiState.retryMessages()`, never the list retry.
     */
    banner: (@Composable () -> Unit)? = null,
) {
    val skin = LocalSkin.current
    if (session == null) {
        Box(modifier.fillMaxSize().background(skin.colors.surface), contentAlignment = Alignment.Center) {
            UiText("在左侧选择一个会话", size = 12, color = skin.colors.onSurfaceVariant)
        }
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
    Box(modifier.fillMaxSize()) {
        ChatThread(
            state = chat,
            modifier = Modifier.fillMaxSize(),
            animate = animate,
            inputLeading = {
                ModelComposerChip(
                    state = modelState,
                    expanded = modelPickerOpen,
                    onClick = { modelPickerOpen = true },
                )
            },
        )
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
