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
 *  3. **合并后卡内仍然在糊里**：`TopBar` 的胶囊从 P0-5 起 `blurBackdrop = false` —— 它的模糊
 *     由这条带子提供（带子的平台段覆盖到胶囊底沿），两层合并成**一个** haze 节点。所以
 *     「卡片本体逐字节不变」这条旧断言不再成立也不再是目标（带子的 veil 会透到卡内），改成
 *     钉两件更本质的事：① 卡内没有锐利字形、与缝内同量级；② 胶囊的补偿染色让**合成浓度**
 *     仍等于 §5 的单层 26 %/34 %（`capsuleTintOverBand` 是纯函数，单独断言）。
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
        // 方向（2026-11-18 口径 + P0-5 合并）：顶部最强，带子底部归零。合并之后满强度平台段
        // 覆盖到**胶囊底沿（64dp）**，所以「下空隙」整体是过渡段；方向仍然可测：量整条下空隙
        // 与最外沿（最后 4dp，ramp 上 alpha ≤ 0.2 的那一段）。
        val tailT = (bandBottom - (4 * d).toInt()).coerceAtLeast(botT)
        val afterBleedTail = after.px.energy(left, tailT, right, botB)
        val beforeBleedTail = before.px.energy(left, tailT, right, botB)
        assertTrue(
            afterTopGap * 2f <= afterBottomGap,
            "渐变方向不对：上空隙 ${afterTopGap} 必须明显比下空隙 ${afterBottomGap} 更糊" +
                "（顶部最强、向下递减到 0）—— 两者反过来就是用户看到的「模糊方向反了」。",
        )
        assertTrue(
            afterTopGap * 2f <= afterBleedTail,
            "渐变方向不对：上空隙 ${afterTopGap} 必须明显比带子底沿 ${afterBleedTail} 更糊" +
                "（顶部最强、向下递减到 0）。",
        )
        // 归零点真的归零：最外沿要回到接近修前的锐利（允许一半的残余染色/抗锯齿余量）。
        assertTrue(
            afterBleedTail >= beforeBleedTail * 0.5f,
            "带子底沿 ${afterBleedTail} 仍被糊住了（修前 ${beforeBleedTail}）—— 渐变没有在带子底部归零。",
        )

        // 卡内：合并之后胶囊自己的模糊由**这条带子**提供（`blurBackdrop = false`）。但卡片
        // 的标题/副标题是**内容** —— haze 从不过滤内容，它们必须保持锐利。所以「卡内背景还
        // 在糊里」只能在没有文字的右半区量；拿整张卡量会把标题字形的锐边当成漏光（旧断言
        // 就是这么红的：标题越清晰，能量越高）。
        val dy = (14 * d).toInt()
        val bodyRefL = (right - 80 * d).toInt()
        val bodyRefR = (right - 10 * d).toInt()
        val afterBody = after.px.energy(bodyRefL, top + dy, bodyRefR, bottom - dy)
        val beforeBody = before.px.energy(bodyRefL, top + dy, bodyRefR, bottom - dy)
        assertTrue(
            afterBody <= afterTopGap * 3f + 1f,
            "卡内无文字区能量 ${afterBody} 与缝内 ${afterTopGap} 不同量级 —— 卡内背景出现锐利" +
                "字形，说明胶囊退成了透明层（它的模糊必须由带子提供）",
        )
        assertTrue(
            afterBody <= beforeBody * 1.5f + 1f,
            "卡内无文字区在合并后变锐了（修前 ${beforeBody} → 修后 ${afterBody}）—— 带子没有覆盖到卡内",
        )
        // 内容不糊：标题仍是实心深字（haze 只过滤 backdrop）。min/max 双断言防止「卡内变成
        // 一块没有文字的玻璃」这种假绿。
        var titleMin = 255f
        var titleMax = 0f
        val titleL = left + (16 * d).toInt()
        val titleR = left + (96 * d).toInt()
        val titleT = top + (14 * d).toInt()
        val titleB = top + (34 * d).toInt()
        for (y in titleT..titleB) for (x in titleL..titleR) {
            val l = after.px.luma(x, y)
            if (l < titleMin) titleMin = l
            if (l > titleMax) titleMax = l
        }
        assertTrue(
            titleMin <= 120f && titleMax - titleMin >= 80f,
            "卡片标题不再是实心深字（min=$titleMin max=$titleMax）—— 内容被玻璃糊掉了，" +
                "或卡片变成了空白玻璃板",
        )
    }

    /**
     * 合并的**染色补偿**：带子先压 `TopBarBandTintScale` 那一份，胶囊只补差额，
     * 合成后必须回到 §5 的单层浓度（26 % 亮 / 34 % 暗）且 ≤45 % 上限。
     *
     * 这条是"合并两层模糊但不改卡片外观"的全部依据 —— 合成浓度一旦漂了，浮卡就会从
     * "同一块料"变成"两层叠加的灰罩"。
     */
    @Test
    fun capsuleTintCompensatesTheBandSoTheCompositeStaysAtTheSection5Density() {
        val light = MaterialSkin().glass.tintLight
        val dark = MaterialSkin(dark = true).glass.tintDark
        for (tint in listOf(light, dark)) {
            val own = capsuleTintOverBand(tint)
            val composite = 1f - (1f - tint.alpha * TopBarBandTintScale) * (1f - own.alpha)
            assertEquals(
                tint.alpha,
                composite,
                0.01f,
                "补偿后合成浓度必须仍是 §5 的单层值（目标 ${tint.alpha}，实际 $composite）",
            )
            assertTrue(composite <= 0.45f, "§5 上限 45 %：合成 $composite")
            assertTrue(
                own.alpha < tint.alpha,
                "补偿后的胶囊必须比单层更淡（${own.alpha} vs ${tint.alpha}），否则合成必然超标",
            )
        }
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
        // energy() 是全区域方差，10% 的渐变摊在 84dp 上只有零点几；直接比采样点的明度差。
        // 2026-11-21 几何：满强度平台段从窗口顶一直到**胶囊底沿**（8+56=64dp），之后
        // 20dp 才线性归零（带子 84dp）。所以 60dp 仍在平台段里 —— 旧的「10 vs 60 要有差」
        // 是在旧的 8dp 平台几何下写的，改版后必然平。这里改成量三段真实几何：
        //   ① 平台段内（10dp vs 56dp）平；
        //   ② 平台末尾 vs 渐变尾部（56dp vs 80dp）必须有可见差；
        //   ③ 带子底部（82dp）回到接近带外画布 —— 归零真的归零。
        val plateauTop = px.luma(px.width / 2, (10 * d).toInt())
        val plateauMid = px.luma(px.width / 2, (56 * d).toInt())
        val rampLow = px.luma(px.width / 2, (80 * d).toInt())
        val edge = px.luma(px.width / 2, (82 * d).toInt())
        println(
            "g-topbar-band 纯画布: 带内=$inBand 带外=$canvas | 平台 10dp=$plateauTop 56dp=$plateauMid " +
                "尾部 80dp=$rampLow 82dp=$edge 带内梯度=$band",
        )
        assertTrue(
            abs(plateauTop - plateauMid) <= 1.5f,
            "带子的满强度平台段不平（10dp=$plateauTop / 56dp=$plateauMid）—— rampHoldDp 应覆盖" +
                " [0, TopBarTopGap+TopBarHeight]；平台段一旦被 ramp 切出坡度，胶囊上下的缝就会" +
                " 比卡内清楚",
        )
        assertTrue(
            abs(plateauMid - rampLow) >= 3f,
            "带子从平台段到尾部没有可见的渐变（56dp=$plateauMid / 80dp=$rampLow）—— " +
                "ramp 没有在胶囊底沿之后开始递减，或 veil 把 ramp 盖死了",
        )
        assertTrue(
            abs(edge - canvas) <= 8f,
            "带子底沿（82dp=$edge）没有回到带外画布（$canvas）附近 —— 渐变没有归零，" +
                "等于把一条有色带子硬截在栏区底",
        )
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
