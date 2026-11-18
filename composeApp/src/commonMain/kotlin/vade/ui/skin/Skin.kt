package vade.ui.skin

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
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
 * OWNER: U1 (`u1-ui`). FROZEN on delivery of slice U1-a; AMENDED by RFC-0011 (2026-11-14).
 *
 * The set is exactly TWELVE components (AMEND-22 / D93 froze 1–11; RFC-0011 added 12):
 *
 *   1 Button          2 TextField       3 Card            4 ListItem
 *   5 Switch          6 BottomBar       7 ApprovalDialog  8 MessageBubble
 *   9 AttachmentChip 10 ToolCallCard   11 InputBar       12 SendOrb
 *
 * Rules that hold after the freeze:
 *  - No 13th component. A genuinely new need is an RFC, not an edit here
 *    (docs/agents/50-U1-ui-skeleton.md §十; RFC-0011 is the precedent for how the send
 *    ball was split out of InputBar). The top bar is deliberately NOT a C-09 member: it
 *    is assembled from [Card] + [Button] in `shell/TopBar.kt`.
 *  - Only *additive* changes are allowed: new parameters must carry defaults. Removing
 *    parameters (as RFC-0011 did to InputBar) is breaking and needs an RFC + version bump.
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

    /**
     * Motion tokens (v2 §六/§11). Default-implemented so a skin is not forced to carry
     * them; overriding skins (material/miuix) keep working unchanged.
     */
    val motion: SkinMotion get() = SkinMotion()

    /**
     * Glass tokens (v2 §5/§9: backdrop blur radii, layer tint, hairline on glass).
     * Default-implemented for the same reason as [motion] — the values below are the
     * probe-measured defaults from docs/visual-language.md §5, so a skin without a
     * glass look still exposes a usable token set to `skin/glass/GlassSurface`.
     */
    val glass: SkinGlass get() = SkinGlass()

    /**
     * Send-ball metrics (RFC-0011, visual language v2 §2). Default-implemented like
     * [motion]/[glass] so a skin that predates the orb keeps compiling; the defaults are
     * the §2 numbers and a skin overrides the whole object, not individual fields.
     */
    val orb: SkinOrb get() = SkinOrb()

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
     * 8 · MessageBubble (D92/D92a): assistant full width capped at [maxWidth] and
     * **without a bubble** (visual language §5: content first, no chat-bubble wall),
     * user right-aligned bubble with a 6dp tail corner, system centred notice.
     * [actions] is the per-message action row (copy / retry / branch …); the page
     * decides which exist.
     *
     * [body] is the optional rich-content slot (added after the C-09 freeze, 2026-11-14):
     * when non-null the skin renders it instead of [MessageView.text], so a page can pass
     * its markdown renderer without the skin knowing how markdown works. Null keeps the
     * frozen behaviour byte-for-byte.
     */
    @Composable
    fun MessageBubble(
        message: MessageView,
        role: BubbleRole,
        maxWidth: Dp = 760.dp,
        actions: List<MessageAction> = emptyList(),
        onAction: (MessageAction) -> Unit = {},
        body: (@Composable () -> Unit)? = null,
    )

    /**
     * 9 · AttachmentChip (D95/D96): file_id + mime + upload state. Copy must say
     * "已压缩，原图未保留" — never imply the original is downloadable.
     *
     * [leading] is the optional 28×28 thumbnail slot (added after the C-09 freeze,
     * 2026-11-14): the page owns the bitmap, the chip owns the frame. Null keeps the
     * old mime-letter placeholder.
     */
    @Composable
    fun AttachmentChip(
        attachment: AttachmentView,
        onRetry: (() -> Unit)? = null,
        onRemove: (() -> Unit)? = null,
        leading: (@Composable () -> Unit)? = null,
    )

    /**
     * 10 · ToolCallCard (D94): success collapses, failure auto-expands,
     * `risk=high` approval stays expanded. The page picks [state]; the skin does
     * not decide policy.
     *
     * [meta] is the optional footer slot (added after the C-09 freeze, 2026-11-14): the
     * page passes `duration_ms` / `batch_id` / error-code rows, which the frozen
     * [ToolCallView] has no field for (RESULT · 需要 RFC 的那一条已由这个槽位关闭)。
     * The skin draws it only while the card is expanded; null renders nothing.
     */
    @Composable
    fun ToolCallCard(
        call: ToolCallView,
        state: ToolCardState,
        onToggle: () -> Unit = {},
        meta: (@Composable () -> Unit)? = null,
    )

    /**
     * 11 · InputBar (D88, RFC-0011): multi-line growth, attachment slot, and the voice
     * button — which a platform hides entirely when it has no on-device STT (D97), so
     * [voiceAvailable] false means "do not draw", not "grey it out".
     *
     * RFC-0011 (2026-11-14) removed the send/stop responsibility from this component —
     * **breaking change**: `onSend` / `onStop` / `sendEnabled` are gone, and the send
     * control is the separate [SendOrb] the page places OUTSIDE the frame (visual
     * language v2 §1/§2). The panel is the float's *shape*: glass base + hairline +
     * float shadow + `xl` corners; the 16dp/24dp float margins are page layout (§4.4/D5
     * of RFC-0011) so a page can compose `[＋] [InputBar] [12dp] [SendOrb]` in one row
     * without margin stacking. [maxLines] caps growth (six lines, then internal scroll).
     *
     * [leading] is the optional bottom-row slot (added after the C-09 freeze, 2026-11-15,
     * same additive rule as `MessageBubble.body` / `AttachmentChip.leading`): the page
     * passes a compact control that must share the panel's existing bottom row — the SAME
     * visual line the send orb sits on — without adding a line of height. The skin renders
     * it at the row START (left of the text field, before the voice button); null keeps
     * the frozen bottom row byte-for-byte.
     *
     * [secondRow] is the optional **second line inside the panel** (added after the C-09
     * freeze, 2026-11-18, same additive rule): the page passes the controls it used to
     * stack in its own row *below* the panel — the session model chip — and the skin
     * renders them under the input row **inside the same panel**, sharing its base,
     * hairline and `xl` corners (no second surface, no extra frame). Null draws nothing at
     * all: no spacer, no node, the frozen panel byte-for-byte.
     */
    @Composable
    fun InputBar(
        value: String,
        onValueChange: (String) -> Unit,
        mode: InputMode,
        attachments: (@Composable () -> Unit)? = null,
        enabled: Boolean = true,
        voiceAvailable: Boolean = false,
        maxLines: Int = 6,
        leading: (@Composable () -> Unit)? = null,
        secondRow: (@Composable () -> Unit)? = null,
    )

    /**
     * 12 · SendOrb (RFC-0011, visual language v2 §2): the send/stop ball that lives
     * **outside** the input frame — a 48dp circle (metrics in [orb]) the page composes
     * beside [InputBar]. One control, three states, driven by [mode]:
     *
     *  - [InputMode.Idle]      → empty: glass + 1dp hairline, muted glyph; still
     *                            clickable (§2 触发提示 — the page decides what a tap
     *                            on an empty orb means);
     *  - [InputMode.Composing] → armed: accent solid + a [SkinOrb.glowRadius] accent
     *                            glow, on-accent glyph (§2 有内容态);
     *  - [InputMode.Streaming] → stop: the same ball position turns into ■, and only
     *                            the glyph + a 1.5dp stroke take the error colour —
     *                            the ball itself never turns red (§2).
     *
     * The page owns policy: it derives [mode] (content present ⇒ Composing) and wires
     * [onClick] to send-or-stop. The skin owns every pixel: no text inside the ball,
     * the ball never fuses with the input frame (§2 禁止项), press = [SkinOrb.pressScale]
     * over [SkinOrb.pressDurationMs] on the one v2 curve.
     */
    @Composable
    fun SendOrb(
        mode: InputMode,
        enabled: Boolean = true,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
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
    // --- optional tokens added after the C-09 freeze (2026-11-14) --------------
    // Each one falls back to a token that already existed, so a skin that does not
    // implement the visual-language palette keeps rendering exactly as before.

    /** App backdrop behind cards (`#F7F7F8` light / `#0E0E10` dark in the visual language). */
    val canvas: Color = surface,

    /** 1px separator colour. Cards use it *instead of* a shadow (visual language §1.2). */
    val hairline: Color = outline,

    /** Tool-card status bar: 成功 `#2F7D5A`. */
    val success: Color = primary,

    /** Tool-card status bar: 审批 / warn `#B26A00`. */
    val warning: Color = error,

    // --- v2 glass / shadow tokens (visual language §5/§8, Wave 0 2026-11-14) ------
    // Every one falls back to a token that already existed, so a skin that does not
    // implement the v2 glass look keeps rendering exactly as before.

    /** Tinted base colour a glass layer reads as (visual language §5: 极薄染色, ≤45%). */
    val surfaceGlass: Color = surface,

    /** Soft shadow for in-page static cards (§8 row 1: `0 1 2 rgba(0,0,0,.06)`). */
    val shadowSoft: Color = outline,

    /** Float-layer shadow — composer / sheet / drawer / GlassSurface (§8 row 2). */
    val shadowFloat: Color = outline,

    /** Accent glow for the primary action ball, NOT a shadow (§8 row 3). */
    val glowAccent: Color = primary,

    /** Foreground text drawn directly on a glass layer. */
    val textOnGlass: Color = onSurface,

    // --- G5 · attachment preview overlay (2026-11-15) -----------------------------
    // The preview sheet composites over the thread content; both tokens below must keep
    // the backdrop TEXT from reading through the card. Defaults preserve the shipped
    // light behaviour byte-for-byte (the light look is screenshot-baselined); dark skins
    // override with an opaque card base and a ≥48% scrim (24% black is invisible on the
    // #0E0E10 canvas).

    /**
     * Full-screen scrim behind the attachment preview sheet (§9). Light ships 24% black;
     * dark needs ≥48% or the dimming cannot be perceived against the dark canvas.
     */
    val previewScrim: Color = Color.Black.copy(alpha = 0.24f),

    /**
     * Readability floor the preview card paints when the blur is not wired (the sheet's
     * `glassFallback` layer). Light keeps the shipped `surfaceGlass`@88% composite; dark
     * skins must override with an **opaque** surface tone — the audit measured backdrop
     * body text reading through the translucent base line-for-line (G5).
     */
    val previewCardBase: Color = surfaceGlass.copy(alpha = 0.88f),
)

@Immutable
data class SkinShapes(
    // --- v1 names kept as aliases (they must not disappear: MaterialParts.kt /
    //     MiuixParts.kt and the business pages read them) — v2 points them at the
    //     new scale by name: small→sm, medium→md, large→lg (§10 raised every step).
    /** Alias of [sm] · 16dp — v2 list rows / secondary buttons. */
    val small: Dp = 16.dp,

    /** Alias of [md] · 20dp — v2 cards, inputs, tool cards. */
    val medium: Dp = 20.dp,

    /** Alias of [lg] · 24dp — v2 user bubbles, image thumbs, module cards. */
    val large: Dp = 24.dp,

    val pill: Dp = 999.dp,

    // --- v2 six-step scale (visual language §10, replaces the v1 8/12/16/20/28 ladder) ---
    // Adjacent steps differ by ≥4dp; nested cards keep outer ≥ inner + 4dp.

    /** xs · 12dp — chips, badges, small buttons, inline code fragments. */
    val xs: Dp = 12.dp,

    /** sm · 16dp — list rows, secondary buttons, input inner layer. */
    val sm: Dp = 16.dp,

    /** md · 20dp — tool cards, attachment cards, search result rows. */
    val md: Dp = 20.dp,

    /** lg · 24dp — user message bubbles, image thumbs, module cards. */
    val lg: Dp = 24.dp,

    /** xl · 28dp — floating composer, primary cards, global sheet (§9). */
    val xl: Dp = 28.dp,

    /** sheet · 32dp — global floating sheet card, all four corners equal (§9/§10). */
    val sheet: Dp = 32.dp,
)

/**
 * The font seam for every typography token.
 *
 * `typography` below resolves its family through this holder instead of hard-coding
 * `FontFamily.Default`, because Web **has no system fonts**: Skiko/CanvasKit can only draw
 * glyphs whose bytes it was handed, and CMP's own fallback (`NotoFontDownloader`, `internal`,
 * no configuration) fetches Noto subsets from `fonts.gstatic.com` at runtime — so a Chinese
 * screen is tofu until the CDN answers, and forever tofu offline
 * (`shots/g-font-recon/RECON.md` §1). The wasm entry point therefore installs the bundled
 * subset **before the first composition** (`wasmJsMain/vade/ui/VadeFontWasm.kt`); every other
 * target leaves the default in place, so nothing re-flows there.
 *
 * [generation] is bumped on every install because CMP does **not** repaint when glyph bytes
 * merely arrive (measured: 30 s of identical tofu frames, `shots/g-font-bundle2/RESULT.md`).
 * Read it inside composition (a `key(...)`) and the tree is invalidated on install.
 */
object VadeFonts {
    /** Family used by `title`/`body`/`caption`; `FontFamily.Default` unless a host installs one. */
    var sans: FontFamily = FontFamily.Default
        private set

    /** Increments each time [sans] is replaced — read it in composition to be invalidated. */
    var generation by mutableStateOf(0)
        private set

    fun installSans(fontFamily: FontFamily) {
        sans = fontFamily
        generation++
    }
}

@Immutable
data class SkinTypography(
    val title: TextStyle =
        TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, fontFamily = VadeFonts.sans),
    /**
     * Body default. The chat reading column overrides this to the visual-language
     * 15sp / 1.55 inside `MarkdownText`; every other page passes an explicit size to
     * `UiText`, so this stays 14sp and no existing screen re-flows.
     */
    val body: TextStyle = TextStyle(fontSize = 14.sp, fontFamily = VadeFonts.sans),
    val caption: TextStyle = TextStyle(fontSize = 11.sp, fontFamily = VadeFonts.sans),
    val mono: TextStyle = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
)

@Immutable
data class SkinMotion(
    val fastMs: Int = 90,
    val normalMs: Int = 180,
    val emphasizedMs: Int = 260,
    // --- v2 motion tokens (visual language §六: 150–220ms, §11: motion blur) ------
    // The blur constants below MUST stay in sync with skin/glass/MotionBlur.kt
    // (MOTION_BLUR_* constants, probe-measured and pinned by MotionBlurMathTest);
    // both sides record the same §11 numbers and neither re-measures alone.

    /** v2 quick step · 150ms — hovers, small in-place transitions. */
    val quickMs: Int = 150,

    /** v2 standard step · 180ms — the default v2 duration (send-ball press, etc.). */
    val standardMs: Int = 180,

    /** v2 slow step · 220ms — panels, sheets, page transitions. */
    val slowMs: Int = 220,

    /** The one v2 curve: `cubic-bezier(0.2, 0, 0, 1)` (§六). */
    val easing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),

    /** Motion-blur radius cap · 6dp (§11: 10dp smeared glyphs into colour blocks). */
    val motionBlurMaxRadiusDp: Float = 6f,

    /** Below this scroll velocity (dp/s) nothing smears (§11). */
    val motionBlurStartVelocity: Float = 1200f,

    /** Velocity (dp/s) at which the radius reaches [motionBlurMaxRadiusDp] (§11). */
    val motionBlurFullVelocity: Float = 6000f,
) {
    /**
     * K1 · 这套时长在 reduced-motion 下的**实际值**：封顶 [REDUCE_MOTION_MAX_MS]，不是归零。
     *
     * 归零会让状态变化失去因果感（卡片「凭空出现」、按压缩放跳变），压到 100ms 既尊重
     * 「减少动效」也保留「我点到了」的反馈。所有读 `motion.*Ms` 的动画都应该经过这里，
     * 而不是直接 `tween(durationMillis = motion.standardMs)`。
     */
    fun durationMs(requestedMs: Int, reduceMotion: Boolean): Int =
        reducedDurationMs(requestedMs, reduceMotion)

    /**
     * K1 · 位移量：reduced-motion 下位移类动画改成**纯淡入**（返回 0）。
     *
     * 前庭敏感的读者被「滑动」冒犯，而不是被「淡入」冒犯 —— 所以淡入保留，位移归零。
     */
    fun travelPx(requestedPx: Int, reduceMotion: Boolean): Int =
        reducedTravelPx(requestedPx, reduceMotion)
}

// --- reduced motion (K1) ------------------------------------------------------

/**
 * K1 · 「减少动效」的读取点：`true` = 降级（时长封顶 100ms、位移改纯淡入）。
 *
 * 两个安装点，任一为 `true` 就降级（OR，不是覆盖）：
 *  - 宿主在组合前调 [ReduceMotion.install]（桌面启动参数 / Android
 *    `Settings.Global.ANIMATOR_DURATION_SCALE == 0` / Wasm `prefers-reduced-motion`）；
 *  - 组合里 `CompositionLocalProvider(LocalReduceMotion provides true)`（设置页开关、测试）。
 *
 * 读法：`val reduce = LocalReduceMotion.current || ReduceMotion.enabled`。**不能**只读
 * CompositionLocal：它默认 false，而 `staticCompositionLocalOf` 的默认值不会随宿主开关
 * 失效，无 provider 的宿主会永远看到 false。
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * K1 · 宿主级「减少动效」开关（可观察）。
 *
 * 桌面入口在组合前 `ReduceMotion.install(hasFlag("--reduce-motion"))`；Android 读系统动画
 * 缩放；两者都是「平台告诉我少动」，不是应用内偏好。设置页将来的开关走
 * [LocalReduceMotion] provider，不要写这个对象（系统偏好不该被应用内开关关掉）。
 */
object ReduceMotion {
    var enabled: Boolean by mutableStateOf(false)
        private set

    fun install(enabled: Boolean) {
        this.enabled = enabled
    }
}

/** K1 · 降级后的动画时长上限（ms）。100ms 仍能读出「发生过」的因果，又不构成动效。 */
const val REDUCE_MOTION_MAX_MS = 100

/** K1 · 时长封顶（纯函数，`ChatMotionTest` 直接钉住；见 [SkinMotion.durationMs]）。 */
fun reducedDurationMs(requestedMs: Int, reduceMotion: Boolean): Int =
    if (reduceMotion) requestedMs.coerceAtMost(REDUCE_MOTION_MAX_MS) else requestedMs

/** K1 · 位移归零（纯函数；见 [SkinMotion.travelPx]）。 */
fun reducedTravelPx(requestedPx: Int, reduceMotion: Boolean): Int =
    if (reduceMotion) 0 else requestedPx

/**
 * K2 · 图标按钮的统一按压缩放 —— 与 [SkinOrb.pressScale] 是同一个数（0.92，§2/§7）。
 * 一屏不许出现两种按压比：批准/拒绝/重试/停止/编辑 与发送球一致。
 */
const val ICON_PRESS_SCALE = 0.92f

/**
 * v2 glass tokens (visual language §5/§9) — the numbers a glass layer needs: how hard to
 * blur the backdrop, how thick the tint is per theme, and the hairline that keeps the
 * layer from reading as "pasted on". The tint MUST be applied through
 * `colorEffects(listOf(HazeColorEffect.tint(...)))` — `backgroundColor` paints behind the
 * opaque blurred backdrop and is a no-op (haze probe, RESULT.md §坑1). Defaults are the
 * §5 probe values; skins override per theme.
 */
@Immutable
data class SkinGlass(
    /** Backdrop blur radius for bars/composer/overlay glass (§5: 24dp). */
    val blurRadiusDp: Float = 24f,

    /** Larger radius for full sheets, which move more content (§5: 32dp). */
    val sheetBlurRadiusDp: Float = 32f,

    /** Light-theme tint: white @ 26% — thin enough to keep the backdrop readable. */
    val tintLight: Color = Color.White.copy(alpha = 0.26f),

    /** Dark-theme tint: black @ 34% (§5: 26%/34%, ceiling 45%). */
    val tintDark: Color = Color.Black.copy(alpha = 0.34f),

    /**
     * 1px hairline drawn on the glass edge. Skins deepen this on busy backdrops (§5:
     * 加深一档 or 1.5px); the default is a translucent white that reads on dark canvas.
     */
    val hairlineOnGlass: Color = Color.White.copy(alpha = 0.25f),
)

/**
 * Send-ball metrics (RFC-0011, visual language v2 §2) — the four numbers the orb is
 * pinned to. The glow colour itself is [SkinColors.glowAccent] (a glow, never a shadow);
 * the 12dp gap between frame and ball is page layout (§2), not a token here.
 */
@Immutable
data class SkinOrb(
    /** §2: 48dp 正圆. */
    val size: Dp = 48.dp,

    /** §2: the soft accent halo extends this far beyond the ball (glow, not shadow). */
    val glowRadius: Dp = 12.dp,

    /** §2/§7: press scale — the whole ball (halo included) shrinks to this while pressed. */
    val pressScale: Float = 0.92f,

    /** Press duration in ms; the curve is [SkinMotion.easing] (the one v2 curve). */
    val pressDurationMs: Int = 180,
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
