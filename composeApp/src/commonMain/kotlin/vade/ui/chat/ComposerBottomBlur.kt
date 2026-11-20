package vade.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.component.liquid.BAND_FADE_GAP
import vade.ui.component.liquid.BandRamp
import vade.ui.component.liquid.GradientHazeBand
import vade.ui.skin.LocalSkin
import vade.ui.skin.glass.LocalGlassHazeState

/**
 * 底栏**界限之外**那条渐变模糊带的测试锚点（`ComposerBottomBlurTest` 用它量几何）。
 */
internal const val ComposerBottomBlurTag = "vade.chat.composer.blurBand"

/**
 * 这条带子的几何 —— 两个数就够，且都能从**已经存在的 token** 推出来。
 *
 * @param bottomInset 带子下沿离窗口底的距离 = **0**：用户 2026-11-19 的口径是
 *   「从屏幕底下模糊最高」，所以带子一直铺到窗口底（底栏玻璃压在它上面，栏区内的
 *   文字仍由底栏自己的玻璃负责，不会被这条带子直接画花）。
 * @param height 带子高度。它的上沿就是**归零点**，位于输入框上沿再往上
 *   [BAND_FADE_GAP]（见 [composerBottomBandGeometry]）。
 */
internal data class ComposerBottomBandGeometry(
    val bottomInset: Dp,
    val height: Dp,
) {
    /** 带子下沿（窗口坐标）—— 屏幕底（`bottomInset = 0`）。 */
    fun bottomY(windowHeight: Dp): Dp = windowHeight - bottomInset

    /** 带子上沿（窗口坐标）—— 模糊在这里**归零**。 */
    fun zeroY(windowHeight: Dp): Dp = windowHeight - bottomInset - height
}

/**
 * 会话页底栏带几何（纯函数，`ComposerBottomBlurTest` 逐项钉住）。
 *
 * ## 两个端点是量出来的，不是拍的
 *
 * composer 浮层的实测高度 [composerHeight] **含它自己的底部 padding**（`onSizeChanged`
 * 挂在 modifier 链首 —— 见 `ComposerOverlay` 的 KDoc）。它的底边 = `窗口底 − (16dp +
 * barZone)`，而宿主传的 `bottomBarInset` **就是** `barZone`（审查 P1-1：不再扣
 * clearance，消费者也不再加回来），所以：
 *
 * ```text
 * composer 底边 = 窗口底 − barZone − 16dp      // 胶囊底边恒在栏区上沿之上 16dp
 * composer 上沿 = 窗口底 − composerHeight      // 实测高度含 padding
 * 带子下沿      = 窗口底                         // 用户口径：屏幕底下模糊最高
 * 带子上沿      = composer 上沿 − fadeGap      // 输入框上沿再往上一点点 → 归零
 * 带子高度      = composerHeight + fadeGap
 * ```
 *
 * 因此高度**不是**一个新的魔法数：它是「实测 composer 高度（已含 16dp + 栏区 padding）
 * + 一个 8dp 刻度」。键盘弹起时 composer 变高（`imePadding`），带子自己跟着长/缩，
 * 归零点始终留在输入框上沿之上 8dp —— 那条不变式由测试钉住：
 *
 * ```text
 * zeroY(窗口高) == 窗口高 − composerHeight − fadeGap
 * bottomY(窗口高) == 窗口高
 * ```
 *
 * ## 参数的符号（审查 P1-1 之后只剩一种口径）
 *
 * 宿主（`AppShell`）传进来的 `bottomBarInset` **就是栏区本身** —— `barZoneFor(style,
 * navInset)`，只依赖 navigationBars，**不读 IME**。这里因此不做任何加减：
 *
 *  - 以前宿主传 `barZone − composerNavClearance()`、这里 `＋ clearance` 还原，净值和
 *    现在完全一样（所以像素不变），但那条 `WindowInsets.ime` 读会把这条带子绑到键盘
 *    动画上 —— 每帧一次小分配 + 每帧一次重组，且和 composer 那份 clearance 必须永远
 *    同向才能不偏；现在没有任何一项需要「两边同时改对」；
 *  - 基准是**窗口底**：`bottomY = 窗口高`，向上一直到 composer 上沿 − 8dp。
 *
 * 桌面 / Wasm 上 `WindowInsets.navigationBars` 读到 0（`InsetsGuardTest` 钉住），
 * 手机详情态宿主仍会传真实的栏区，用来判断「有没有底栏」——只是它不再参与几何。
 *
 * 夹到 0：桌面 / rail 上 `bottomBarInset = 0`（没有底栏，调用方本来也不组合这条带子），
 * 以及极窄屏上 composer 高度为 0 的病态输入，都只让带子退化，绝不给负高度。
 */
internal fun composerBottomBandGeometry(
    composerHeight: Dp,
    fadeGap: Dp = BAND_FADE_GAP,
): ComposerBottomBandGeometry = ComposerBottomBandGeometry(
    bottomInset = 0.dp,
    // 还没量到 composer（0 / 负数）时不画：否则会只剩一条 8dp 的染色带坐在屏幕底。
    height = if (composerHeight <= 0.dp) 0.dp else composerHeight + fadeGap,
)

/**
 * 会话页的**底栏外侧渐变模糊**（用户 2026-11-18 真机反馈第 2 条）。
 *
 * 形态：底栏上沿处最强，向上递减，到「输入框上沿 + [BAND_FADE_GAP]」归零 —— 方向由
 * [BandRamp.BottomHeavy] 单向决定，与顶栏带（[BandRamp.TopHeavy]）是同一条
 * [GradientHazeBand] 的两个方向，不会各自漂移。
 *
 * ## 为什么它在 `ChatThread` 里、而不是 `AppShell` 里
 *
 * 「输入框上沿」只有这一层知道：composer 的高度是**实测**的
 * （`viewport.composerHeightPx`，`onSizeChanged` 在 modifier 链首量整块浮层）。
 * `AppShell` 手里只有栏区，没有 composer 高度 —— 在那里画只能拍一个固定高度，
 * 键盘一弹就错位。放在这里还有一个附带好处：**它天然只存在于会话页**，其它三个
 * tab 的组合里根本没有这个组件。
 *
 * ## 三条硬约束
 *
 *  1. **不压底栏**：`bottomInset = barZone`，带子下沿 = 底栏上沿。底栏自己的玻璃已经
 *     是玻璃，再糊一遍只是白花一次采样。
 *  2. **不遮输入热区**：带子上沿在 composer 上沿**之上** `fadeGap`，与 composer 的
 *     命中区零重叠；它身上也没有任何 `pointerInput` / `clickable`，不是一个命中节点。
 *  3. **不上桌面 / 双栏**：`bottomBarInset <= 0.dp` 直接不组合（那里没有底栏，
 *     「底栏界限之外」这个概念不存在）。
 *
 * ## 性能口径（本棒第 4 条反馈）
 *
 * 这是**一条带子大**的 haze 节点（≈ 88dp 高、内容列宽），不是全屏 `Modifier.blur`：
 *  - 高度随 composer 变化而变，但只在 composer 高度真的变了的那一帧重组（读的是
 *    `viewport.composerHeightPx` 这个 snapshot state，作用域**只到本函数**）；
 *  - 模糊量靠 [GradientHazeBand] 的离屏 `DstIn` 渐变做，**不是** N 条不同半径的带子
 *    → 键盘动画期间只有 1 次额外采样，而不是 4–8 次；
 *  - brush 在 `drawWithCache` 里建一次，每帧只有「采样 + 乘法」。
 */
@Composable
internal fun BoxScope.ComposerBottomBlurBand(
    viewport: ChatViewport,
    bottomBarInset: Dp,
) {
    // 没有底栏就没有「底栏界限之外」—— 桌面 / rail 双栏在这里安静退出。
    if (bottomBarInset <= 0.dp) return

    val skin = LocalSkin.current
    // `bottomBarInset` 只用来判断「这一页有没有底栏」；几何本身从屏幕底量起，
    // 不再拿栏区做锚点（用户 2026-11-19 口径）。它仍然不读 IME，因此这条带子
    // 不会在键盘动画里每帧重组。
    val composerHeight = with(LocalDensity.current) { viewport.composerHeightPx.toDp() }
    val geometry = composerBottomBandGeometry(composerHeight = composerHeight)
    if (geometry.height <= 0.dp) return

    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            // 摆放层：padding 在外、height 在内 —— 整块贴屏底对齐后，内容区（= 带子）
            // 的下沿就是窗口底（`bottomInset = 0`）。
            .padding(bottom = geometry.bottomInset),
    ) {
        GradientHazeBand(
            modifier = Modifier
                // testTag **必须挂在带子自己这一层**，不能挂在上面那个摆放层上：
                // padding 会把节点的外框一直拉到窗口底，`boundsInRoot` 量到的「带子下沿」
                // 就永远是窗口底（实测期望 656.0 / 实际 760.0，几何的基准从「栏区上沿」
                // 漂成「窗口底」）。同一类坑：把 offset 写进**同一个**节点的链里也不行 ——
                // offset 只挪绘制/摆放的内层，不挪这个节点自己的 semantics 矩形。所以摆放
                // 与「谁是带子」分成两层，节点矩形 == 可见带子 ==
                // [窗口底 − height, 窗口底]。
                .testTag(ComposerBottomBlurTag)
                .fillMaxWidth()
                .height(geometry.height),
            hazeState = LocalGlassHazeState.current,
            // 现成 token：`colors.surfaceGlass` 就是本皮肤的玻璃染色
            // （glass 皮肤里它就是 `GlassTokens.tint(isDark)`，见 `colorsFrom`）。
            tint = skin.colors.surfaceGlass,
            blurRadiusDp = skin.glass.blurRadiusDp,
            // 底栏带的方向：屏幕底最强、向上递减到 0。改这一行之前先看 BlurBandRampTest。
            // 这条带子**不需要**「满强度平台段」：它的强端就是屏幕底，那里没有缝。
            ramp = BandRamp.BottomHeavy,
            // 同顶栏：纯模糊在空画布上不可见，用同一比例的 onSurface 明度偏移
            // 把「磨砂渐变」画出来；它随 ramp 一起归零，不会盖死输入区。
            veil = skin.colors.onSurface.copy(alpha = 0.10f),
        )
    }
}
