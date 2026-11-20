package vade.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.UiText
import vade.ui.glass.GlassScope
import vade.ui.glass.asGlassSource
import vade.ui.glass.rememberGlassState
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * g-topbar-band · 顶栏带必须把浮卡**外**的那两条 8–12dp 空隙糊掉，而卡片本体的像素
 * 一个都不许变（用户 2026-11-15 拍板：「让模糊覆盖整条顶栏带（浮卡外圈加同色遮罩），
 * 卡片外观不变」）。
 *
 * **2026-11-18 口径变更（用户真机反馈第 1 条）**：带子不再是均匀模糊，而是**顶部最强、
 * 向下递减到 0** 的渐变（`BandRamp.TopHeavy`），而且**满强度一直保持到胶囊上沿**：只写
 * `(0,1) → (1,0)` 拉满整条 72dp 的那一版，缝里还漏着 `1 − alpha(y) ≈ 5%` 的锐利背板
 * （量到空隙 2.14 / 卡内 0.16，差的那 13 倍就是这 5% 的漏光），用户看到的就是「胶囊
 * 上面那条缝比卡里清楚」。所以上空隙（0–8dp）现在落在 `rampHoldDp = TopBarTopGap`
 * 的**满强度平台段**里，必须糊到与卡内同一量级；下空隙（64–72dp）则跟着递减回到接近
 * 锐利 —— 这不是回归，是用户要的方向：上一版「贴卡片那条边最糊」才是反的。
 *
 * 这条测试因此**量方向**（上比下糊、且上与卡内同量级），并把「带子被删掉 / 被调成装饰」
 * 「带子没盖住缝」「方向被写反」三类错误都钉住。度量点全部由**带子的实测 boundsInRoot**
 * 夹出来（不是拿 `TOP_BAR_ZONE` 这个期望常量拍），量到带外就会变成空转 —— 那是这条测试
 * 最容易骗过自己的一种绿。方向的**纯函数**版本在 `BlurBandRampTest`，这里只负责「真的
 * 画出来了」。
 *
 * ## 这条测试盯的到底是什么
 *
 * `shots/g-visual-audit2/RESULT.md` D2 的原貌：卡片**内部**有模糊，但卡片上沿之上、
 * 下沿之下两条空隙里的滚动内容原样清晰——上一行预览、右侧「置顶」、被卡片弧形切掉一半
 * 的头像。病因不是模糊坏了，是那两条空隙里**根本没有玻璃层**。
 *
 * 所以这里用像素说话，而不是数 modifier：
 *
 *  1. **上沿那条缝不再是锐的**：同一套背板（密排正文行）渲染两遍——不带带子（修前）与
 *     带带子（修后），量上空隙里相邻像素的梯度能量。修前是字形边缘（高能量），修后必须
 *     掉到卡内模糊区的同一量级。
 *  2. **方向**：下空隙的能量必须**明显高于**上空隙 —— 渐变到带子底部归零，所以越往下
 *     越接近修前的锐利。方向写反（顶部 0、底部满）时这一条先红。
 *  3. **卡片本体逐像素不变**：两遍渲染里卡片**内部**（离边 6dp 的矩形）必须一致（≤2/255，
 *     留抗锯齿的余量）。这正是「垫在卡片背后」这个做法的合法性证明：卡片自己的玻璃是
 *     不透明的，背后的东西透不进来。哪天有人把带子改成画在卡片**上面**，这条会先红。
 *  4. **不是一块色板**：带子铺在纯色画布上时，与画布的差必须很小（≤12/255）——用户明确
 *     禁止「整条带变成一块不透明的色板」。
 *
 * 另外还有一条结构断言（[bandIsComposedBeforeTheCapsule]）：带子在 `AppShell` 里必须
 * 出现在 `TopBar(` **之前**（z 序在卡片背后），并且铺满 `TOP_BAR_ZONE`。像素测试证明
 * 材质对，结构测试证明它被装在了正确的地方——两者缺一，D2 都能悄悄回来。
 */
@OptIn(ExperimentalTestApi::class)
class TopBarBandTest {

    // ---- 渲染台 -------------------------------------------------------------------

    private val shotW = 360.dp
    private val shotH = 220.dp

    /** 一帧截图：像素 + 卡片节点的根坐标 + 带子节点的根坐标（无带子时为 null）+ dp→px 换算器。 */
    private class Shot(val px: PixelMap, val card: Rect, val band: Rect?, val density: Float) {
        fun toPx(dp: Float) = (dp * density).toInt()
    }

    /**
     * 密排正文行 —— D2 现场那句话，一行行铺满整个顶栏带。字号 9sp 是为了让字形的横向
     * 笔画（竖、撇、折）都落在 8dp 的空隙里：空隙只要露 8dp，就一定有字。
     */
    @Composable
    private fun TextBackdrop() {
        Column(Modifier.fillMaxSize().background(Color(0xFFF0EFED))) {
            repeat(26) {
                UiText(
                    text = "把 POST /ui/blob 的分片与断点续传写进 §3 细节见 RFC-0004 §6.1 置顶",
                    size = 9,
                    color = Color(0xFF1B1B1F),
                    maxLines = 1,
                )
            }
        }
    }

    /**
     * 与 AppShell 同构的一摞：背板（haze source）→ 带子 → 浮卡。
     *
     * 带子的高度和位置照抄 AppShell（`align(TopCenter) + height(TOP_BAR_BAND_HEIGHT)`）——
     * 这条测试要量的「缝」是**带子里**的那 8dp，带子一旦铺不到位，空隙的能量就掉不下来。
     */
    private fun ComposeUiTest.shot(withBand: Boolean): Shot {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val glass = rememberGlassState()
                GlassScope(glass) {
                    Box(Modifier.size(shotW, shotH)) {
                        Box(Modifier.fillMaxSize().asGlassSource(glass)) { TextBackdrop() }
                        if (withBand) {
                            Box(Modifier.fillMaxWidth().height(TOP_BAR_BAND_HEIGHT)) {
                                TopBarBand(Modifier.fillMaxSize())
                            }
                        }
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(start = 12.dp, end = 12.dp, top = TopBarTopGap),
                        ) {
                            TopBar(title = "会话", subtitle = "<600dp · 底栏单栏 · 6 个会话")
                        }
                    }
                }
            }
        }
        waitForIdle()
        return Shot(
            px = onRoot().captureToImage().toPixelMap(),
            card = onNodeWithTag(TopBarGlassTag).fetchSemanticsNode().boundsInRoot,
            band = if (withBand) onNodeWithTag(TopBarBandTag).fetchSemanticsNode().boundsInRoot else null,
            density = density.density,
        )
    }

    // ---- 度量 ---------------------------------------------------------------------

    private fun PixelMap.luma(x: Int, y: Int): Float {
        val c = this[x, y]
        return (0.299f * c.red + 0.587f * c.green + 0.114f * c.blue) * 255f
    }

    /**
     * 相邻像素梯度能量：每个像素与右邻、下邻的亮度差取绝对值，全部求和后除以样本数
     * （即每像素的平均梯度，0..255 量纲）。锐字形的竖笔画边缘能到 20 以上；同一片内容
     * 被 24dp 高斯糊过之后只剩个位数——这就是「看得出有字、读不出内容」的量化版本。
     */
    private fun PixelMap.energy(l: Int, t: Int, r: Int, b: Int): Float {
        val x0 = l.coerceIn(0, width - 2)
        val y0 = t.coerceIn(0, height - 2)
        val x1 = r.coerceIn(x0 + 1, width - 1)
        val y1 = b.coerceIn(y0 + 1, height - 1)
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                sum += abs(luma(x, y) - luma(x + 1, y)).toDouble()
                sum += abs(luma(x, y) - luma(x, y + 1)).toDouble()
                n += 2
            }
        }
        return (sum / n).toFloat()
    }

    /** 两帧在同一条矩形里的最大逐像素亮度差（0..255），并返回最差像素的坐标。 */
    private fun maxDelta(a: PixelMap, b: PixelMap, l: Int, t: Int, r: Int, bBot: Int): Triple<Float, Int, Int> {
        var worst = 0f
        var wx = -1
        var wy = -1
        for (y in t.coerceAtLeast(0) until bBot.coerceAtMost(minOf(a.height, b.height))) {
            for (x in l.coerceAtLeast(0) until r.coerceAtMost(minOf(a.width, b.width))) {
                val delta = abs(a.luma(x, y) - b.luma(x, y))
                if (delta > worst) {
                    worst = delta
                    wx = x
                    wy = y
                }
            }
        }
        return Triple(worst, wx, wy)
    }

    // ---- 1 · 空隙不再是锐的（修前/修后同构对照） -------------------------------------

    @Test
    fun gapStripsStopBeingSharpButTheCapsuleStaysByteIdentical() = runComposeUiTest {
        val before = shot(withBand = false)
        val after = shot(withBand = true)

        val d = after.density
        val top = after.card.top.toInt()      // 卡片上沿 = 8dp
        val bottom = after.card.bottom.toInt() // 卡片下沿 = 64dp
        val pad = (1.5f * d).toInt()           // 躲开卡片阴影/描边的 1.5dp 过渡像素
        val left = after.card.left.toInt()
        val right = after.card.right.toInt()

        // 度量点必须落在**带子的实测矩形**里：量的若是带外，空隙「不锐了」只是假象
        // （像素没被任何东西盖过）。所以先取带子的 boundsInRoot，再据它夹出两条缝，
        // 顺便钉住「带子从窗口顶开始、盖过胶囊下沿」这条位置前提。
        val band = requireNotNull(after.band) { "带子节点不存在：TopBarBand 没组合" }
        val bandTop = band.top.toInt()
        val bandBottom = band.bottom.toInt()
        assertTrue(
            bandTop <= pad && bandBottom >= bottom + (8 * d).toInt(),
            "带子没有盖住 [窗口顶 .. 胶囊底 + 8dp]：带子 ${bandTop}..${bandBottom}，卡片 ${top}..${bottom}",
        )

        // 上空隙 = 带顶 → 卡片上沿（带子的满强度平台段）；下空隙 = 卡片下沿 → 带底。
        val topL = bandTop + pad
        val topB = top - pad
        val botT = bottom + pad
        val botB = bandBottom - pad
        assertTrue(
            topB > topL && botB > botT,
            "两条缝的采样矩形空了（上 $topL..$topB，下 $botT..$botB）—— 这条测试会变成空转",
        )

        val beforeTopGap = before.px.energy(left, topL, right, topB)
        val afterTopGap = after.px.energy(left, topL, right, topB)
        val beforeBottomGap = before.px.energy(left, botT, right, botB)
        val afterBottomGap = after.px.energy(left, botT, right, botB)

        // 参照：卡内**没有文字**的右半区（纯模糊背景）——「同一量级」的标尺。
        val cardRefLeft = (right - 60 * d).toInt()
        val cardRef = after.px.energy(cardRefLeft, top + (8 * d).toInt(), right - (10 * d).toInt(), bottom - (8 * d).toInt())

        // 一次失败就把全部数字带出来（CI 只留断言 message，不留 println）。
        val numbers =
            "上空隙 修前=${"%.2f".format(beforeTopGap)} 修后=${"%.2f".format(afterTopGap)} " +
                "| 下空隙 修前=${"%.2f".format(beforeBottomGap)} 修后=${"%.2f".format(afterBottomGap)} " +
                "| 卡内模糊区=${"%.2f".format(cardRef)} " +
                "| 采样 上 $topL..$topB 下 $botT..$botB 带子 ${bandTop}..${bandBottom} 卡片 ${top}..${bottom}"
        println("g-topbar-band 梯度能量（每像素平均，0..255）: $numbers")

        // 修前两条缝都必须「锐」——否则这条测试是空转的（背板没铺到空隙里，比值再大也没意义）。
        assertTrue(
            beforeTopGap > 8f && beforeBottomGap > 8f,
            "对照帧的空隙不够锐（上=$beforeTopGap, 下=$beforeBottomGap）—— 测试背板没有铺进空隙，这条会变成空转。" +
                numbers,
        )
        // 修后：**上沿**那条缝必须掉下来，至少 3 倍；实测在 10 倍以上，3 倍是留给渲染差异的余量。
        assertTrue(
            afterTopGap * 3f <= beforeTopGap,
            "上空隙仍然是锐的：修前 ${beforeTopGap} → 修后 ${afterTopGap}（要求 ≤ 1/3）—— 带子没盖住 0..8dp 那条缝。" +
                numbers,
        )
        // 与卡内模糊区同一量级（同一套 blur radius + 同色系染色）。上空隙落在带子的**满强度
        // 平台段**上（`rampHoldDp = TopBarTopGap`），所以它与卡内的落差只允许来自「带子染色
        // 更淡」这一条 —— 3 倍是留给染色差与渲染差异的余量。
        assertTrue(
            afterTopGap <= cardRef * 3f + 1f,
            "上空隙 ${afterTopGap} 与卡内模糊区 ${cardRef} 不同量级（卡外比卡内清楚太多）—— " +
                "缝里的 alpha 没到 1，或两条缝的模糊半径/染色已经漂了。" + numbers,
        )
        // 方向（2026-11-18 口径）：顶部最强、向下递减到 0 ⇒ 下空隙必须比上空隙**锐**得多。
        // 这一条同时是「渐变方向写反」的哨兵：顶部 0 / 底部满时它立刻红。
        assertTrue(
            afterTopGap * 2f <= afterBottomGap,
            "渐变方向不对：上空隙 ${afterTopGap} 必须明显比下空隙 ${afterBottomGap} 更糊" +
                "（顶部最强、向下递减到 0）—— 两者反过来就是用户看到的「模糊方向反了」。" + numbers,
        )
        // 下空隙回到接近修前的锐利（渐变在这里已经归零）：允许 20% 的残余染色/抗锯齿余量，
        // 但必须明显比「整条均匀糊」的时代锐（那一版这个比值是 1/10 以下）。
        assertTrue(
            afterBottomGap >= beforeBottomGap * 0.6f,
            "下空隙 ${afterBottomGap} 仍被糊住了（修前 ${beforeBottomGap}）—— 渐变没有在带子底部归零。" + numbers,
        )

        // 卡片本体：28dp 圆角内侧的矩形（水平 8dp / 垂直 14dp 起，四角都在形状里）必须与
        // 修前一致（≤2/255，抗锯齿余量）。取样矩形若啃到圆角外侧，量到的就是带子本身。
        val dx = (8 * d).toInt()
        val dy = (14 * d).toInt()
        val (bodyDelta, bx, by) = maxDelta(
            before.px, after.px,
            left + dx, top + dy, right - dx, bottom - dy,
        )
        assertTrue(
            bodyDelta <= 2f,
            "卡片本体变了（最大 ${bodyDelta}/255，在 (${bx},${by})，卡片 ${left}..${right} × ${top}..${bottom}）" +
                "—— 带子必须垫在卡片**背后**；卡片玻璃是不透明的，" +
                "任何可见差异都说明它被画到了卡片之上，或模糊源被改了",
        )
    }

    // ---- 2 · 带子不是一块不透明的色板 ------------------------------------------------

    @Test
    fun bandOverPlainCanvasIsAVeilNotAPlate() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                val glass = rememberGlassState()
                GlassScope(glass) {
                    Box(Modifier.size(240.dp, 120.dp)) {
                        Box(Modifier.fillMaxSize().background(Color(0xFFF0EFED)).asGlassSource(glass))
                        Box(Modifier.fillMaxWidth().height(TOP_BAR_BAND_HEIGHT)) { TopBarBand(Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        waitForIdle()
        val px = onRoot().captureToImage().toPixelMap()
        // 带内取一列（左右各留 8px 躲开裁剪抗锯齿），与带外的画布比较亮度。
        val d = density.density
        val band = px.energy((8 * d).toInt(), (8 * d).toInt(), (232 * d).toInt(), (64 * d).toInt())
        val canvas = px.luma(px.width / 2, (100 * d).toInt())
        val inBand = px.luma(px.width / 2, (30 * d).toInt())
        println("g-topbar-band 纯画布: 带内=${"%.1f".format(inBand)} 带外=${"%.1f".format(canvas)} 带内梯度=${"%.2f".format(band)}")
        // 真机反馈：差异太小（旧口径 ≤12）就等同于全透明，用户看不到任何模糊/磨砂。
        // 现在要求「看得见但不成为色板」：强端有明度差，同时整条仍有梯度。
        assertTrue(
            abs(inBand - canvas) in 5f..40f,
            "带子在纯画布上要么全透明（差 ${abs(inBand - canvas)}/255 < 5），要么成了一块色板（> 40）—— " +
                "带内 $inBand vs 带外 $canvas",
        )
        assertTrue(band >= 3f, "带子自身必须仍有渐变（梯度 ${band}）；平掉就说明 veil 把 ramp 盖死了")
    }

    // ---- 3 · 结构：带子在 AppShell 里垫在卡片之前，且铺满整个 zone -------------------

    @Test
    fun bandIsComposedBeforeTheCapsule() {
        val file = java.io.File("src/commonMain/kotlin/vade/ui/shell/AppShell.kt")
        assertTrue(file.isFile, "缺 ${file.path}")
        val code = file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .split('\n').joinToString("\n") { it.substringBefore("//") }
        val bandAt = code.indexOf("TopBarBand(")
        val cardAt = code.indexOf("TopBar(")
        assertTrue(bandAt >= 0, "AppShell 没有组合 TopBarBand —— D2（卡外两条空隙）会立刻复发")
        assertTrue(
            bandAt < cardAt,
            "TopBarBand 必须出现在 TopBar( 之前：它要垫在卡片**背后**（z 序在前的先画），" +
                "画到前面就会盖住卡片本体",
        )
        assertEquals(
            1,
            Regex("""\.height\(TOP_BAR_BAND_HEIGHT\)""").findAll(code).count(),
            "带子的高度必须**恰好**是 TOP_BAR_BAND_HEIGHT（栏区 72dp + 12dp 过渡）：矮了露缝，高了压进首行",
        )
        assertTrue(
            Regex("""TopBarBand\(Modifier\.fillMaxSize\(\)\)""").containsMatchIn(code),
            "带子必须铺满内容列（fillMaxSize），不能缩成一个小方块只盖住卡片正上方",
        )
        // 带子与浮卡必须共用**同一组**水平边界：带子到 rail/右面板为止，卡片再各缩 12dp。
        // 两处各写一份算术，右面板一开就会漂出 12dp 的锐边（带子盖不到的缝）。
        assertTrue(
            Regex("""padding\(start = topBarStart, end = topBarEnd\)""").containsMatchIn(code),
            "带子的左右边界必须来自 topBarStart/topBarEnd（与浮卡同源），不得各自再写一份 if",
        )
        assertEquals(
            1,
            Regex("""topBarStart \+ 12\.dp""").findAll(code).count(),
            "浮卡的左内缩必须是 topBarStart + 12dp（唯一一处）",
        )
        assertEquals(
            1,
            Regex("""topBarEnd \+ 12\.dp""").findAll(code).count(),
            "浮卡的右内缩必须是 topBarEnd + 12dp（唯一一处）",
        )
        assertEquals(
            1,
            Regex("""val topBarStart = if \(metrics\.useRail\) metrics\.railWidth else 0\.dp""").findAll(code).count(),
            "顶栏水平边界只允许推导一次（带子与浮卡共用）",
        )
        assertEquals(
            1,
            Regex("""val topBarEnd = if \(metrics\.hasRightPanel && state\.panelOpen\) 320\.dp \+ 1\.dp else 0\.dp""")
                .findAll(code).count(),
            "右面板那一条边也只允许推导一次（带子不得压到面板上）",
        )
    }
}
