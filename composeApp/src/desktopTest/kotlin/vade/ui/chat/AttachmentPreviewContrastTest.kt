// G5 · 暗色下附件预览浮层几乎全透明（审计 shots/g-audit-gaps f25：背后红字/审批卡整行
// 透过卡片可读；遮罩 24% 黑在暗画布上不可感知）。
//
// 修复：SkinColors 新增 previewCardBase / previewScrim 两个 token —— 暗色卡底为**全实底**
// surface 色、暗色遮罩 ≥48%；亮色默认值 = 修复前的表达式（surfaceGlass@88% / 黑 24%），
// 亮色像素逐字节不变。
//
// 本文件用**渲染级像素断言**钉住两个方向（对照 ComposerShadowShapeTest 的 harness：
// runComposeUiTest + onRoot().captureToImage().toPixelMap()，密度 1 = 1dp/px）：
//
//   1. 暗色（缺陷方向）：预览卡打开时，卡底旁带（ring，卡左缘内 2..10px，落在 mock 图
//      一侧的基色区）每个像素 = 皮肤 previewCardBase 本色 ±2 —— 半透明底在这里是
//      base@0.686 过画布（Δ≈4/255），且 mock 图右下角网格的**振幅** ≤6：半透明底会让
//      背后正文以 0.78 的可见度漏进 mock 的透明端（振幅 ≈55/255）。
//   2. 亮色（回归方向）：同一 ring 的每个像素 = surfaceGlass@0.739 过 canvas 的解析
//      复合色 ±3 —— 这是「亮色 ≤3/255」的测试化；mock 网格振幅 ≤70 如实记录既有
//      半透明观感（0.206×230≈47，修复前后同一表达式）。
//   3. 遮罩行（卡顶上方 16px，横穿背后正文）：暗色 max ≤112/255（0.44×237=104；改前
//      0.76×237=180 或带玻璃 tint 119 —— 两种实现都过不了这条）；亮色 max ≤200 / min ≤75。
//      min/spread 双断言保证该行确实压在正文上（非空转对照）。
//
// 所有几何由 mock 图检测推导（标题栏 accent 色带 → mock 外接矩形 → 卡边距 16dp），
// 不写死像素坐标。夹具背后垫满长文本行，保证卡后与遮罩行下都有正文可漏。

package vade.ui.chat

import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.protocol.VadeEvent
import vade.ui.skin.LocalSkin
import vade.ui.skin.SkinColors
import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.material.MaterialTokens
import vade.ui.skin.miuix.MiuixSkin
import vade.ui.skin.miuix.MiuixTokens

@OptIn(ExperimentalTestApi::class)
class AttachmentPreviewContrastTest {

    // ---- harness ------------------------------------------------------------------

    private class RealGateway(private val channel: ChatTranscriptSource?) : ChatTurnGateway {
        private val writes = InMemoryTurnGateway()
        override fun transcript(): ChatTranscriptSource? = channel
        override suspend fun editResubmit(
            sessionId: String,
            messageId: String,
            content: List<ContentPart>,
            clientMsgId: String,
        ): EditResubmitReceipt = writes.editResubmit(sessionId, messageId, content, clientMsgId)

        override suspend fun regenerate(
            sessionId: String,
            turnId: String,
            clientMsgId: String,
        ): RegenerateReceipt = writes.regenerate(sessionId, turnId, clientMsgId)

        override suspend fun submit(
            sessionId: String,
            parts: List<ContentPart>,
            clientMsgId: String,
        ): SubmitReceipt = writes.submit(sessionId, parts, clientMsgId)

        override suspend fun interrupt(sessionId: String, turnId: String) {
            writes.interrupt(sessionId, turnId)
        }
    }

    /** 整屏长正文：预览卡与遮罩行后面永远压着可漏的背景文字。 */
    private class PackedTranscript : ChatTranscriptSource {
        private val rows: List<ChatMessage> = List(40) { i ->
            ChatMessage(
                id = "g5-$i",
                role = ChatRole.Assistant,
                parts = listOf(
                    ContentPart.Text(
                        "背景对照正文第 $i 行 ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789 " +
                            "暗色下这些字不许透过预览卡读出来——这就是 G5 的验收句。",
                    ),
                ),
            )
        }

        override suspend fun history(sessionId: String): List<ChatMessage> = rows
        override fun events(): Flow<VadeEvent> = emptyFlow()
    }

    /** 宽幅 8:3 mock —— 卡片在测试视口内完整可见。 */
    private val wideImage = ContentPart.Image(
        fileId = "blb_g5_preview",
        name = "IMG_0042.jpg",
        size = 1_834_211,
        width = 2048,
        height = 768,
        mime = "image/jpeg",
    )

    private fun ComposeUiTest.shot(skin: vade.ui.skin.Skin): Frame {
        val chat = ChatState("s-g5-preview", turns = RealGateway(PackedTranscript()))
        setContent {
            CompositionLocalProvider(LocalSkin provides skin) {
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.background(skin.colors.canvas),
                ) {
                    ChatThread(state = chat, animate = false)
                }
            }
        }
        waitForIdle()
        chat.openPreview(wideImage)
        waitForIdle()
        return Frame(onRoot().captureToImage().toPixelMap(), skin.colors)
    }

    private class Frame(val pm: PixelMap, val colors: SkinColors)

    // ---- geometry ------------------------------------------------------------------

    private class CardGeometry(
        val mockLeft: Int,
        val mockRight: Int,
        val mockTop: Int,
        val mockBottom: Int,
        val cardTop: Int,
        val cardBottom: Int,
    )

    /** mock 图标题栏 = accent@0.55 的实色带：蓝分量显著高于红/绿（亮暗两套 token 都成立）。 */
    private fun PixelMap.isAccentBand(x: Int, y: Int): Boolean {
        val p = this[x, y]
        val r = p.red * 255f
        val g = p.green * 255f
        val b = p.blue * 255f
        return b > r + 25f && b > g + 25f && b > 100f
    }

    private fun Frame.geometry(imageW: Int, imageH: Int): CardGeometry {
        val w = pm.width
        var bandTop = -1
        var bandLeft = w
        var bandRight = -1
        var y = 0
        while (y < pm.height) {
            var left = w
            var right = -1
            var count = 0
            var x = 0
            while (x < w) {
                if (pm.isAccentBand(x, y)) {
                    if (x < left) left = x
                    if (x > right) right = x
                    count++
                }
                x += 2
            }
            val rowIsBand = count > w / 12 && right - left > w / 6
            if (rowIsBand) {
                if (bandTop == -1) bandTop = y
                if (left < bandLeft) bandLeft = left
                if (right > bandRight) bandRight = right
                y += 2
            } else if (bandTop != -1) {
                y = pm.height // band 连续段结束：mock 的圆角只影响首尾行，取整段 min/max
            } else {
                y += 2
            }
        }
        assertTrue(bandTop != -1, "mock 图未渲染：整帧没有 accent 色带（预览卡没打开或 mock 缺失）")

        val mockWidth = bandRight - bandLeft
        val mockHeight = mockWidth * imageH / imageW
        val mockBottom = bandTop + mockHeight

        // 卡边 = mock 边 ± 16dp（内容内边距；密度 1 → 1dp = 1px）。
        val cardLeft = bandLeft - 16
        // ring 列：卡左缘内 2..10px —— 卡内自身内容（标题/图片/说明都有 16dp 内边距）不进这列。
        val ringX = (cardLeft + 2..cardLeft + 10).toList()
        // 走查列：mock 宽 1/4 处 —— 避开标题文字（左侧 ~250px）、手柄（居中）、说明行（居中）
        // 与四角圆弧，上下走查只穿卡底本色。
        val walkX = (bandLeft + mockWidth / 4..bandLeft + mockWidth / 4 + 8).toList()
        fun columnColorAt(xs: List<Int>, yy: Int): Triple<Float, Float, Float> {
            val r = xs.map { pm[it, yy].red * 255f }.average().toFloat()
            val g = xs.map { pm[it, yy].green * 255f }.average().toFloat()
            val b = xs.map { pm[it, yy].blue * 255f }.average().toFloat()
            return Triple(r, g, b)
        }

        val base = columnColorAt(ringX, bandTop + 8)
        // 从 mock 上沿向上走出卡顶：走查列持续偏离基色即到卡外（发丝线/遮罩区）。
        var cardTop = bandTop
        var yy = bandTop - 1
        var miss = 0
        while (yy > 0 && miss < 3) {
            val c = columnColorAt(walkX, yy)
            val dev = maxOf(abs(c.first - base.first), abs(c.second - base.second), abs(c.third - base.third))
            if (dev > 14f) miss++ else miss = 0
            if (miss == 0) cardTop = yy
            yy--
        }
        var cardBottom = mockBottom
        yy = mockBottom + 1
        miss = 0
        while (yy < pm.height - 1 && miss < 3) {
            val c = columnColorAt(walkX, yy)
            val dev = maxOf(abs(c.first - base.first), abs(c.second - base.second), abs(c.third - base.third))
            if (dev > 14f) miss++ else miss = 0
            if (miss == 0) cardBottom = yy
            yy++
        }
        assertTrue(cardTop < bandTop - 8 && cardBottom > mockBottom + 8,
            "卡边检测失败：cardTop=$cardTop bandTop=$bandTop mockBottom=$mockBottom cardBottom=$cardBottom " +
                "mockLeft=$bandLeft mockRight=$bandRight ringX=$ringX walkX=$walkX base=$base")
        return CardGeometry(bandLeft, bandRight, bandTop, mockBottom, cardTop, cardBottom)
    }

    // ---- assertions ----------------------------------------------------------------

    private fun PixelMap.channelMaxDeviation(
        xs: List<Int>,
        ys: List<Int>,
        expected: Triple<Float, Float, Float>,
    ): Float {
        var worst = 0f
        for (yy in ys) for (x in xs) {
            val p = this[x, yy]
            val d = maxOf(
                abs(p.red * 255f - expected.first),
                abs(p.green * 255f - expected.second),
                abs(p.blue * 255f - expected.third),
            )
            if (d > worst) worst = d
        }
        return worst
    }

    private fun PixelMap.gridAmplitude(xs: List<Int>, ys: List<Int>): Float {
        var lo = 999f
        var hi = -999f
        for (yy in ys) for (x in xs) {
            val p = this[x, yy]
            val luma = (0.2126f * p.red + 0.7152f * p.green + 0.0722f * p.blue) * 255f
            if (luma < lo) lo = luma
            if (luma > hi) hi = luma
        }
        return hi - lo
    }

    /** 一行（或一个 y 采样点）的 luma (min, max)，/255 域，步进 8px 扫过夹到帧内的 [x1, x2]。 */
    private fun PixelMap.rowStats(y: Int, x1: Int, x2: Int): Pair<Float, Float> {
        // 采样框可能落在帧外：暗色下预览卡几乎占满视口，`cardTop − 120` 会变成负 y
        // （真 bug：裸 `this[x, y]` 直接 ArrayIndexOutOfBounds）。夹到像素范围内，
        // 并保证 x 区间非空 —— 调用方量到的始终是帧内真实像素。
        val yy = y.coerceIn(0, height - 1)
        val xa = x1.coerceIn(0, width - 1)
        val xb = x2.coerceIn(xa, width - 1)
        var lo = 999f
        var hi = -999f
        var x = xa
        while (x <= xb) {
            val p = this[x, yy]
            val luma = (0.2126f * p.red + 0.7152f * p.green + 0.0722f * p.blue) * 255f
            if (luma < lo) lo = luma
            if (luma > hi) hi = luma
            x += 8
        }
        return lo to hi
    }

    /**
     * 遮罩行选取：在卡顶上方的带状区里挑**最均匀**的一行 —— 背后只有画布（纯色对照层），
     * 没有文字/卡片混入。遮罩 alpha 直接从像素反推：alpha = 1 − 行均值 / canvas 亮度。
     * 注意夹具正文走 MarkdownText，暗色下普通文本段按默认黑渲染（见 RESULT 的相邻缺陷
     * 记录），不能当「亮背景文字」用 —— 画布才是可识别的纯色对照。
     */
    private fun PixelMap.uniformScrimRow(
        yFrom: Int,
        yTo: Int,
        x1: Int,
        x2: Int,
    ): Triple<Int, Float, Float> {
        // 同上：先把候选行夹进帧内（暗色视口里 cardTop − 120 可能是负的）。
        val y0 = yFrom.coerceIn(0, height - 1)
        val y1 = yTo.coerceIn(y0, height - 1)
        var bestY = y0
        var bestMean = 0f
        var bestSpread = Float.MAX_VALUE
        var y = y0
        while (y <= y1) {
            val (lo, hi) = rowStats(y, x1, x2)
            val spread = hi - lo
            if (spread < bestSpread) {
                bestSpread = spread
                bestMean = (lo + hi) / 2f
                bestY = y
            }
            y += 4
        }
        return Triple(bestY, bestMean, bestSpread)
    }

    private fun rgb(c: Color) = Triple(c.red * 255f, c.green * 255f, c.blue * 255f)

    /** source-over：[fg]（按自身 alpha）压在不透明 [bg] 上的 0..255 通道值。 */
    private fun over(fg: Color, bg: Triple<Float, Float, Float>): Triple<Float, Float, Float> {
        val a = fg.alpha
        return Triple(
            fg.red * 255f * a + bg.first * (1f - a),
            fg.green * 255f * a + bg.second * (1f - a),
            fg.blue * 255f * a + bg.third * (1f - a),
        )
    }

    private fun luma(c: Triple<Float, Float, Float>): Float =
        0.2126f * c.first + 0.7152f * c.second + 0.0722f * c.third

    /**
     * 亮色预览卡在**没有 backdrop**（单测 / 预览 / 页面未接 GlassScope）时的解析复合栈：
     *
     * ```text
     * canvas → glass tint → previewScrim → glass tint → previewCardBase
     * ```
     *
     * 两层 tint 分别是遮罩 GlassSurface 与卡片 GlassSurface 的退化底（§5「采不到背景 =
     * 染色 + 描边 + 高光」），卡片内容再压 previewCardBase@88%。本批把「恒建 haze 节点」
     * 换成真正的 TintStroke 退化后，这条栈比旧像素多了一层卡片 tint —— 这是**刻意的**
     * 新基线，所以测试从 token 逐层算出期望（而不是继续写死某一个实测数）。
     */
    private fun lightCardComposite(colors: SkinColors, tint: Color): Triple<Float, Float, Float> {
        val canvas = rgb(colors.canvas)
        val scrim = over(colors.previewScrim, over(tint, canvas))
        return over(colors.previewCardBase, over(tint, scrim))
    }

    // ---- 1 · dark：卡底不透明（缺陷方向） ------------------------------------------

    @Test
    fun darkMaterialCardBlocksBackdropText() = runComposeUiTest {
        val colors = MaterialTokens.darkColors
        assertDarkTokens(colors)
        val g = shot(MaterialSkin(dark = true))
        val geo = g.geometry(wideImage.width, wideImage.height)
        val base = Triple(
            colors.previewCardBase.red * 255f,
            colors.previewCardBase.green * 255f,
            colors.previewCardBase.blue * 255f,
        )
        val xs = (geo.mockLeft - 14..geo.mockLeft - 8).toList()
        val ys = (geo.mockTop + 10..geo.mockBottom - 10 step 2).toList()
        val dev = g.pm.channelMaxDeviation(xs, ys, base)
        assertTrue(
            dev <= 2f,
            "暗色卡底旁带偏离 token 本色 $dev/255 —— 底座不透明被破坏，背后内容在漏",
        )
    }

    @Test
    fun darkMiuixCardBlocksBackdropText() = runComposeUiTest {
        val colors = MiuixTokens.darkColors
        assertDarkTokens(colors)
        val g = shot(MiuixSkin(true))
        val geo = g.geometry(wideImage.width, wideImage.height)
        val base = Triple(
            colors.previewCardBase.red * 255f,
            colors.previewCardBase.green * 255f,
            colors.previewCardBase.blue * 255f,
        )
        val xs = (geo.mockLeft - 14..geo.mockLeft - 8).toList()
        val ys = (geo.mockTop + 10..geo.mockBottom - 10 step 2).toList()
        val dev = g.pm.channelMaxDeviation(xs, ys, base)
        assertTrue(dev <= 2f, "miuix 暗色卡底旁带偏离本色 $dev/255")
    }

    @Test
    fun darkMaterialMockCornerShowsNoBackdropBleed() = runComposeUiTest {
        val colors = MaterialTokens.darkColors
        val g = shot(MaterialSkin(dark = true))
        val geo = g.geometry(wideImage.width, wideImage.height)
        // mock 右下角网格（离开 24dp 圆角与抗锯齿带）：渐变端 alpha≈0.21，背后若有正文
        // 漏出，振幅会到 ~55/255；修复后只剩渐变自身的 ~3/255 起伏。
        val xs = (geo.mockRight - geo.mockRight / 8 until geo.mockRight - 8 step 4).toList()
        val ys = (geo.mockBottom - geo.mockBottom / 8 until geo.mockBottom - 32 step 4).toList()
        val amp = g.pm.gridAmplitude(xs, ys)
        assertTrue(
            amp <= 6f,
            "暗色 mock 透明端振幅 $amp/255 —— 背后正文透过卡底漏进 mock（G5 缺陷本体）",
        )
        // 角点解析复合：mock 渐变 lerp(paper, accent@0.22, t)（含 alpha 通道）再叠卡底。
        // t 由渐变几何给出：t = ((x−mockLeft)/w + (y−mockTop)/h) / 2。
        val accent = colors.primary
        val paper = colors.surfaceVariant
        val base = rgb(colors.previewCardBase)
        val sx = (geo.mockRight - 4 - geo.mockLeft).toFloat() / (geo.mockRight - geo.mockLeft)
        val sy = (geo.mockBottom - 30 - geo.mockTop).toFloat() / (geo.mockBottom - geo.mockTop)
        val t = (sx + sy) / 2f
        val gradR = paper.red * 255f + (accent.red * 255f - paper.red * 255f) * t
        val gradG = paper.green * 255f + (accent.green * 255f - paper.green * 255f) * t
        val gradB = paper.blue * 255f + (accent.blue * 255f - paper.blue * 255f) * t
        val gradA = 0.22f * t
        val expected = Triple(
            gradR * gradA + base.first * (1f - gradA),
            gradG * gradA + base.second * (1f - gradA),
            gradB * gradA + base.third * (1f - gradA),
        )
        val cornerDev = g.pm.channelMaxDeviation(
            listOf(geo.mockRight - 4),
            listOf(geo.mockBottom - 30),
            expected,
        )
        assertTrue(
            cornerDev <= 6f,
            "mock 角区像素偏离解析复合 $cornerDev/255（expected=$expected t=$t）",
        )
    }

    // ---- 2 · dark：遮罩 ≥48% -------------------------------------------------------

    @Test
    fun darkMaterialScrimMutesBackdropText() = runComposeUiTest {
        val colors = MaterialTokens.darkColors
        val g = shot(MaterialSkin(dark = true))
        val geo = g.geometry(wideImage.width, wideImage.height)
        val (rowY, mean, spread) = g.pm.uniformScrimRow(
            geo.cardTop - 120,
            geo.cardTop - 16,
            geo.mockLeft - 8,
            geo.mockRight + 8,
        )
        // 画布 = 纯色对照：遮罩 alpha = 1 − 遮罩后画布亮度 / 画布亮度。
        val canvasLuma = rgb(colors.canvas).let { 0.2126f * it.first + 0.7152f * it.second + 0.0722f * it.third }
        val alpha = 1f - mean / canvasLuma
        assertTrue(
            spread <= 3f,
            "暗色遮罩行(y=$rowY)不均匀（spread=$spread）—— 对照失效，该行压到了内容",
        )
        assertTrue(
            alpha >= 0.48f,
            "暗色遮罩行(y=$rowY)实测 alpha=$alpha（mean=$mean / canvas=$canvasLuma）—— 遮罩必须 ≥48%",
        )
    }

    // ---- 3 · light：像素回归 ≤3/255（解析复合钉死既有表达式） -----------------------

    @Test
    fun lightMaterialCardBaseStaysOnTheShippedComposite() = runComposeUiTest {
        val colors = MaterialTokens.lightColors
        assertLightTokensUnchanged(colors)
        val skin = MaterialSkin()
        val g = shot(skin)
        val geo = g.geometry(wideImage.width, wideImage.height)
        val xs = (geo.mockLeft - 14..geo.mockLeft - 8).toList()
        val ys = (geo.mockTop + 10..geo.mockBottom - 10 step 2).toList()
        // 亮色回归钉（2026-11-21 口径）：期望值不再写死某一版实测数，而是从 token 逐层
        // over 出「canvas → tint → scrim → tint → previewCardBase」的解析值。退化档从
        // 「恒建 haze 节点」换成 TintStroke 后，卡片多了一层 glass tint —— light 像素
        // 因此上移约 +4/255，这是设计审查 P0 要的形态，不是漂移。
        val expected = luma(lightCardComposite(colors, skin.glass.tintLight))
        var sum = 0f
        var worstSpread = 0f
        var n = 0
        for (yy in ys) for (x in xs) {
            val p = g.pm[x, yy]
            val luma = (0.2126f * p.red + 0.7152f * p.green + 0.0722f * p.blue) * 255f
            sum += luma
            n++
        }
        val mean = sum / n
        for (yy in ys) for (x in xs) {
            val p = g.pm[x, yy]
            val luma = (0.2126f * p.red + 0.7152f * p.green + 0.0722f * p.blue) * 255f
            if (abs(luma - mean) > worstSpread) worstSpread = abs(luma - mean)
        }
        println("中栏附件亮色(material): mean=$mean spread=$worstSpread expected=$expected")
        assertTrue(
            abs(mean - expected) <= 3f && worstSpread <= 3f,
            "亮色卡底旁带 mean=$mean spread=$worstSpread —— 偏离 token 合成栈解析值 " +
                "$expected±3/255（canvas→tint→scrim→tint→previewCardBase）",
        )
    }

    @Test
    fun lightMiuixCardBaseStaysOnTheShippedComposite() = runComposeUiTest {
        val colors = MiuixTokens.lightColors
        assertLightTokensUnchanged(colors)
        val skin = MiuixSkin(false)
        val g = shot(skin)
        val geo = g.geometry(wideImage.width, wideImage.height)
        val xs = (geo.mockLeft - 14..geo.mockLeft - 8).toList()
        val ys = (geo.mockTop + 10..geo.mockBottom - 10 step 2).toList()
        // 同 material：期望值 = token 合成栈的解析值（miuix 的画布更暖，见 lightCardComposite）。
        val expected = luma(lightCardComposite(colors, skin.glass.tintLight))
        var sum = 0f
        var n = 0
        for (yy in ys) for (x in xs) {
            val p = g.pm[x, yy]
            sum += (0.2126f * p.red + 0.7152f * p.green + 0.0722f * p.blue) * 255f
            n++
        }
        val mean = sum / n
        println("中栏附件亮色(miuix): mean=$mean expected=$expected")
        assertTrue(
            abs(mean - expected) <= 3f,
            "miuix 亮色卡底旁带 mean=$mean —— 偏离 token 合成栈解析值 $expected±3/255",
        )
    }

    @Test
    fun lightMockTranslucencyIsDocumentedNotWorsened() = runComposeUiTest {
        val g = shot(MaterialSkin())
        val geo = g.geometry(wideImage.width, wideImage.height)
        val xs = (geo.mockRight - geo.mockRight / 8 until geo.mockRight - 8 step 4).toList()
        val ys = (geo.mockBottom - geo.mockBottom / 8 until geo.mockBottom - 32 step 4).toList()
        val amp = g.pm.gridAmplitude(xs, ys)
        assertTrue(
            amp <= 70f,
            "亮色 mock 透明端振幅 $amp/255 —— 超出既有半透明观感的记录带（≈47±背景密度），亮色被改差",
        )
    }

    @Test
    fun lightScrimStaysOn24Percent() = runComposeUiTest {
        val colors = MaterialTokens.lightColors
        val g = shot(MaterialSkin())
        val geo = g.geometry(wideImage.width, wideImage.height)
        val (rowY, mean, spread) = g.pm.uniformScrimRow(
            geo.cardTop - 120,
            geo.cardTop - 16,
            geo.mockLeft - 8,
            geo.mockRight + 8,
        )
        val canvasLuma = rgb(colors.canvas).let { 0.2126f * it.first + 0.7152f * it.second + 0.0722f * it.third }
        val alpha = 1f - mean / canvasLuma
        assertTrue(
            spread <= 3f,
            "亮色遮罩行(y=$rowY)不均匀（spread=$spread）—— 对照失效",
        )
        assertTrue(
            alpha in 0.21f..0.27f,
            "亮色遮罩行(y=$rowY)实测 alpha=$alpha（mean=$mean / canvas=$canvasLuma）—— 必须保持 24%",
        )
    }

    // ---- 4 · token 级钉子 -----------------------------------------------------------

    private fun assertDarkTokens(colors: SkinColors) {
        assertTrue(
            colors.previewCardBase.alpha == 1f,
            "暗色 previewCardBase 必须**全不透明**（alpha=${colors.previewCardBase.alpha}）——" +
                "半透明底座是 G5 缺陷本体",
        )
        assertTrue(
            colors.previewScrim.alpha >= 0.48f,
            "暗色 previewScrim 必须 ≥48%（alpha=${colors.previewScrim.alpha}）——24% 黑在暗画布上不可感知",
        )
    }

    private fun assertLightTokensUnchanged(colors: SkinColors) {
        assertEquals(
            Color.Black.copy(alpha = 0.24f),
            colors.previewScrim,
            "亮色 previewScrim 必须保持既有 24% 黑",
        )
        assertEquals(
            colors.surfaceGlass.copy(alpha = 0.88f),
            colors.previewCardBase,
            "亮色 previewCardBase 必须保持既有 surfaceGlass@88%",
        )
    }
}
