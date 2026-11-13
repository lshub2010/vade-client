package vade.ui.skin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * C-09 · `Skin` — the component-factory contract between business pages and skins.
 *
 * OWNER: U1 (`u1-ui`). FROZEN on delivery of slice U1-a.
 *
 * The set is exactly ELEVEN components (AMEND-22 / D93, docs/requirements.md §8.22.4):
 *
 *   1 Button          2 TextField       3 Card            4 ListItem
 *   5 Switch          6 BottomBar       7 ApprovalDialog  8 MessageBubble
 *   9 AttachmentChip 10 ToolCallCard   11 InputBar
 *
 * Rules that hold after the freeze:
 *  - No 12th component. A genuinely new need is an RFC, not an edit here
 *    (docs/agents/50-U1-ui-skeleton.md §十). The top bar is deliberately NOT a
 *    C-09 member: it is assembled from [Card] + [Button] in `shell/TopBar.kt`.
 *  - Only *additive* changes are allowed: new parameters must carry defaults.
 *  - Business pages (`vade.ui.destinations`, later `vade.ui.core.*`) may only touch
 *    this interface — never `androidx.compose.material3`, `miuix` or `glass`
 *    (enforced by `MaterialImportGuardTest`).
 *  - No skin-private parameter may appear in this file (colours, blur radii,
 *    elevation, animation curves, icon packs): those live in the [Skin] impl.
 *    Everything here is semantic state the page actually owns.
 *
 * State coverage required in U1: every component renders **default / disabled /
 * error** (inputs) or its documented state enum. The full state matrix (hover,
 * pressed, loading, 200 % font, dark-theme per-item) belongs to U2/U3.
 */
interface Skin {
    /** "material" | "miuix" | "glass" — the id [SkinRegistry] keys on. */
    val id: String

    val colors: SkinColors
    val shapes: SkinShapes
    val typography: SkinTypography
    val motion: SkinMotion

    // --- 1-7: the original frozen set -----------------------------------------

    /** 1 · Button. States: default / disabled; [BtnStyle] picks the intent. */
    @Composable
    fun Button(
        text: String,
        onClick: () -> Unit,
        style: BtnStyle = BtnStyle.Primary,
        enabled: Boolean = true,
    )

    /** 2 · TextField. States: default / disabled / error ([error] non-null). */
    @Composable
    fun TextField(
        value: String,
        onValueChange: (String) -> Unit,
        placeholder: String? = null,
        error: String? = null,
        enabled: Boolean = true,
    )

    /** 3 · Card — a plain container; the page owns the content. */
    @Composable
    fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit)

    /** 4 · ListItem. States: default / disabled ([onClick] null or [enabled] false) / selected. */
    @Composable
    fun ListItem(
        title: String,
        leading: (@Composable () -> Unit)? = null,
        subtitle: String? = null,
        trailing: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        enabled: Boolean = true,
        selected: Boolean = false,
    )

    /** 5 · Switch. States: default / disabled. */
    @Composable
    fun Switch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true)

    /** 6 · BottomBar. Three forms: fixed / floating / floating+blur (D87, FR-8.20). */
    @Composable
    fun BottomBar(
        items: List<NavItem>,
        selected: Int,
        onSelect: (Int) -> Unit,
        style: BottomBarStyle = BottomBarStyle.Fixed,
    )

    /**
     * 7 · ApprovalDialog (FR-8.53). The countdown is driven by the page
     * (`remainingSec`); the skin only renders it — closing is decided by
     * `turn.approval_resolved`, never by the skin.
     */
    @Composable
    fun ApprovalDialog(
        request: ApprovalRequest,
        remainingSec: Int,
        onDecide: (Decision, Scope) -> Unit,
    )

    // --- 8-11: AMEND-22 / D93 additions ---------------------------------------

    /**
     * 8 · MessageBubble (D92): assistant full width capped at [maxWidth], user
     * right-aligned bubble, system centred notice. [actions] is the per-message
     * action row (copy / retry / branch …); the page decides which exist.
     */
    @Composable
    fun MessageBubble(
        message: MessageView,
        role: BubbleRole,
        maxWidth: Dp = 760.dp,
        actions: List<MessageAction> = emptyList(),
        onAction: (MessageAction) -> Unit = {},
    )

    /**
     * 9 · AttachmentChip (D95/D96): file_id + mime + upload state. Copy must say
     * "已压缩，原图未保留" — never imply the original is downloadable.
     */
    @Composable
    fun AttachmentChip(
        attachment: AttachmentView,
        onRetry: (() -> Unit)? = null,
        onRemove: (() -> Unit)? = null,
    )

    /**
     * 10 · ToolCallCard (D94): success collapses, failure auto-expands,
     * `risk=high` approval stays expanded. The page picks [state]; the skin does
     * not decide policy.
     */
    @Composable
    fun ToolCallCard(call: ToolCallView, state: ToolCardState, onToggle: () -> Unit = {})

    /**
     * 11 · InputBar (D88): multi-line growth, send / stop, attachment slot, and
     * the voice button — which a platform hides entirely when it has no on-device
     * STT (D97), so [voiceAvailable] false means "do not draw", not "grey it out".
     */
    @Composable
    fun InputBar(
        value: String,
        onValueChange: (String) -> Unit,
        mode: InputMode,
        onSend: () -> Unit,
        onStop: () -> Unit,
        attachments: (@Composable () -> Unit)? = null,
        enabled: Boolean = true,
        voiceAvailable: Boolean = false,
    )
}

// --- tokens ------------------------------------------------------------------

/**
 * Named `Skin*` instead of the doc's bare `ColorScheme` / `ShapeScheme` so they can
 * never be confused with `androidx.compose.material3.ColorScheme` inside a skin.
 * Values are skin-private: pages read them only through [Skin] components.
 */
@Immutable
data class SkinColors(
    val primary: Color,
    val onPrimary: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val error: Color,
    val onError: Color,
    val isDark: Boolean = false,
)

@Immutable
data class SkinShapes(
    val small: Dp = 8.dp,
    val medium: Dp = 14.dp,
    val large: Dp = 22.dp,
    val pill: Dp = 999.dp,
)

@Immutable
data class SkinTypography(
    val title: TextStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    val body: TextStyle = TextStyle(fontSize = 14.sp),
    val caption: TextStyle = TextStyle(fontSize = 11.sp),
    val mono: TextStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
)

@Immutable
data class SkinMotion(
    val fastMs: Int = 90,
    val normalMs: Int = 180,
    val emphasizedMs: Int = 260,
)

// --- semantic state ----------------------------------------------------------

enum class BtnStyle { Primary, Secondary, Danger, Text }

/** D87/FR-8.20: the three bottom-bar forms. `FloatingBlur` is blur-in-name-only
 *  until the U2 pass proves a real backdrop filter on all three platforms. */
enum class BottomBarStyle { Fixed, Floating, FloatingBlur }

enum class BubbleRole { User, Assistant, System }

enum class InputMode { Idle, Composing, Streaming }

enum class Decision { Approve, Deny }

/** Approval scope (FR-8.53): once / this session. */
enum class Scope { Once, Session }

enum class ToolCardState { Collapsed, Expanded, PendingApproval }

@Immutable
data class NavItem(
    val id: String,
    val label: String,
    /** Single-glyph icon; skins may replace it with their own icon pack. */
    val glyph: String? = null,
    val badge: String? = null,
)

@Immutable
data class MessageView(
    val id: String,
    val text: String,
    /** Corner badges, e.g. "已压缩" / "模型未读取" (D92/D96). */
    val badges: List<String> = emptyList(),
    val isError: Boolean = false,
)

@Immutable
data class MessageAction(val id: String, val label: String)

enum class AttachmentState { Validating, Uploading, Uploaded, Failed }

@Immutable
data class AttachmentView(
    val fileId: String,
    val fileName: String,
    val mime: String,
    val state: AttachmentState,
    /** 0f..1f, only meaningful while [state] is [AttachmentState.Uploading]. */
    val progress: Float = 0f,
    val detail: String? = null,
)

@Immutable
data class ToolCallView(
    val id: String,
    val name: String,
    /** Redacted argument text. */
    val args: String,
    val result: String? = null,
    /** "low" | "medium" | "high" (FR-8.51). */
    val risk: String = "low",
    val isError: Boolean = false,
)

@Immutable
data class ApprovalRequest(
    val id: String,
    val toolName: String,
    /** Full, redacted argument text — monospace and scrollable (FR-8.53). */
    val arguments: String,
    val risk: String = "high",
)
