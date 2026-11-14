package vade.ui.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import vade.ui.motion.DEFAULT_MOTION_BLUR_KERNEL
import vade.ui.motion.MOTION_BLUR_DECAY_MS
import vade.ui.motion.MOTION_BLUR_FULL_VELOCITY_DP_PER_SEC
import vade.ui.motion.MOTION_BLUR_MAX_RADIUS_DP
import vade.ui.motion.MOTION_BLUR_SIGMA_X_PER_RADIUS
import vade.ui.motion.MOTION_BLUR_SIGMA_Y_PER_RADIUS
import vade.ui.motion.MOTION_BLUR_START_VELOCITY_DP_PER_SEC
import vade.ui.motion.MotionBlurKernel
import vade.ui.motion.blurForVelocity
import vade.ui.motion.boxKernelTaps
import vade.ui.motion.motionBlur
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Launch-time switches for the probe, set by the platform entry point *before* composition.
 *
 * A plain object rather than a CompositionLocal or expect/actual: desktop `Main.kt` parses
 * `--freeze-blur=` from argv, wasm could read a query param, Android has no flags at all — the
 * shared screen only needs the resulting values.
 */
object MotionProbeConfig {
    /** Non-null pins the radius (§11: lets a screenshot show blur without animating). */
    var freezeBlurDp: Float? = null

    /** §11 requires wasm to be able to turn motion blur off; `--blur=off` exercises that. */
    var blurEnabled: Boolean = true

    /**
     * Drive the list itself (sawtooth) so the *live* velocity path can be photographed — a
     * screenshot tool cannot scroll with a finger, and a frozen radius would not prove the
     * velocity→radius loop works.
     */
    var autoScroll: Boolean = false

    /** `--fps`: run the 5 s frame-time measurement, print one result line, then stop scrolling. */
    var measureFps: Boolean = false

    /**
     * Kernel under test. Defaults to the shipped default; `--kernel=box|triangle|gaussian` pins
     * it so the A/B in RESULT.md can be reproduced against the same build.
     */
    var kernel: MotionBlurKernel = DEFAULT_MOTION_BLUR_KERNEL

    /**
     * Which list content the probe paints.
     *
     * [ProbeContent.PATHOLOGICAL] is the original 8×-identical `MOTION BLUR` pattern: 34 sp
     * monospace, equal length, equal pitch. It is the *worst case* for a box kernel — the glyph
     * cross-bar pitch (4–7 px) beats against the 13-tap kernel and every letter comes back as
     * 3–4 evenly spaced echoes. Kept because it is the pattern that *shows* the beat, so it is
     * the right fixture for the kernel A/B.
     *
     * [ProbeContent.REAL] is what a shipping list actually looks like: Chinese/English mixed,
     * 3–60 characters, three type sizes (13/15/20 sp), one row with a code block, one with an
     * icon. This is the pattern that decides whether the echo matters in practice — a kernel
     * that only wins on the pathological fixture has not won.
     */
    var content: ProbeContent = ProbeContent.Real
}

/** See [MotionProbeConfig.content]. */
enum class ProbeContent {
    Pathological,
    Real,
}

private const val ITEM_COUNT = 30
private val ITEM_HEIGHT = 88.dp
private val ITEM_SPACING = 8.dp

/** Row pitch: `LazyListState` offsets are measured against this, not [ITEM_HEIGHT] alone. */
private val ITEM_STRIDE = ITEM_HEIGHT + ITEM_SPACING

/**
 * §11 motion-blur probe — the artefact that proves `Modifier.motionBlur` actually paints on
 * each target.
 *
 * Why a scrolling list and not a synthetic shader test: §11's first trigger is
 * "列表快速滚动（velocity > 1200 dp/s）", so the probe measures exactly that — real
 * `LazyListState` offset deltas → dp/s → radius → pixels.
 *
 * The HUD prints the live velocity next to the radius it produced, so a screenshot is
 * self-verifying: the number that *drove* the blur sits beside the blurred pixels.
 */
@Composable
fun MotionProbe(
    freezeBlurDp: Float? = MotionProbeConfig.freezeBlurDp,
    blurEnabled: Boolean = MotionProbeConfig.blurEnabled,
    autoScroll: Boolean = MotionProbeConfig.autoScroll,
    measureFps: Boolean = MotionProbeConfig.measureFps,
    kernel: MotionBlurKernel = MotionProbeConfig.kernel,
    content: ProbeContent = MotionProbeConfig.content,
    onMeasurementDone: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    var radiusDp by remember { mutableStateOf(freezeBlurDp ?: 0f) }
    var velocityDpPerSec by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val itemStridePx = with(density) { ITEM_STRIDE.toPx() }

    val userScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
    var autoScrolling by remember { mutableStateOf(false) }
    val tracking = userScrolling || autoScrolling

    // --- sawtooth driver (`--autoscroll`, `--fps`) -------------------------------------
    // 30 items × 96 dp is only ~2.9 kdp of content, so a straight 5 s run at >1200 dp/s eats
    // the whole list in under a second. Wrapping back to the top keeps |velocity| above the
    // trigger for the entire window, and the radius uses the magnitude, so the blur never
    // dips mid-measurement.
    //
    // Paced by `withFrameNanos`, not a bare loop: `scrollBy` must land between frames or the
    // measured velocity is garbage (it would measure the coroutine, not the compositor), and a
    // hidden/occluded window then stalls the driver instead of silently spinning — which is
    // exactly the bug that made the first live screenshot read IDLE.
    LaunchedEffect(autoScroll, measureFps) {
        if (!autoScroll && !measureFps) return@LaunchedEffect
        delay(900) // first frames + font loading would pollute the sample
        autoScrolling = true
        while (true) {
            withFrameNanos { }
            if (listState.firstVisibleItemIndex >= ITEM_COUNT - 8) {
                listState.scrollToItem(0)
            } else {
                listState.scrollBy(itemStridePx * 0.62f)
            }
        }
    }

    // --- velocity → radius -------------------------------------------------------------
    // Sample the continuous scroll offset once per frame and turn the delta into dp/s. When
    // the scroll ends §11 wants the radius back at 0 *within 120 ms*, linearly — a timed ramp,
    // not an exponential tail that would leave a faint residue on a still screen.
    LaunchedEffect(tracking, blurEnabled, freezeBlurDp) {
        if (freezeBlurDp != null) {
            radiusDp = freezeBlurDp
            velocityDpPerSec = 0f
            return@LaunchedEffect
        }
        if (!tracking) {
            val from = radiusDp
            velocityDpPerSec = 0f
            if (from <= 0f) return@LaunchedEffect
            var last = withFrameNanos { it }
            var current = from
            val dpPerMs = MOTION_BLUR_MAX_RADIUS_DP / MOTION_BLUR_DECAY_MS.toFloat()
            while (current > 0f) {
                val now = withFrameNanos { it }
                current -= dpPerMs * ((now - last) / 1_000_000f)
                last = now
                if (current < 0f) current = 0f
                radiusDp = current
            }
            return@LaunchedEffect
        }

        var prevOffset = listState.scrollOffsetPx(itemStridePx)
        var prevNanos = withFrameNanos { it }
        var ema = 0f
        while (true) {
            val now = withFrameNanos { it }
            val offset = listState.scrollOffsetPx(itemStridePx)
            val dtSec = ((now - prevNanos) / 1_000_000_000.0).toFloat().coerceAtLeast(0.001f)
            val rawDpPerSec = with(density) { ((offset - prevOffset) / dtSec).toDp().value }
            // Light smoothing: per-frame deltas are spiky because frame spacing is uneven, and
            // an unsmoothed radius flickers between 0 and the cap on a real fling.
            ema = ema + (rawDpPerSec - ema) * 0.4f
            prevOffset = offset
            prevNanos = now
            velocityDpPerSec = ema
            radiusDp = if (blurEnabled) blurForVelocity(ema) else 0f
        }
    }

    // --- 5 s frame-time measurement (`--fps`) ------------------------------------------
    LaunchedEffect(measureFps) {
        if (!measureFps) return@LaunchedEffect
        delay(900)
        val deltas = ArrayList<Long>(512)
        var last = withFrameNanos { it }
        var elapsedNs = 0L
        while (elapsedNs < 5_000_000_000L) {
            val now = withFrameNanos { it }
            val dt = now - last
            deltas += dt
            elapsedNs += dt
            last = now
        }
        autoScrolling = false
        val sorted = deltas.sorted()
        val avgMs = sorted.average() / 1_000_000.0
        val p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)] / 1_000_000.0
        val maxMs = sorted.last() / 1_000_000.0
        val line = "FPS_RESULT blur=${if (blurEnabled) "on" else "off"} kernel=${kernel.name.lowercase()} " +
            "frames=${sorted.size} " +
            "avgMs=${fmt(avgMs, 2)} p95Ms=${fmt(p95, 2)} maxMs=${fmt(maxMs, 2)} " +
            "fps=${fmt(1000.0 / avgMs, 1)}"
        println(line)
        onMeasurementDone(line)
    }

    Row(Modifier.fillMaxSize().background(Color(0xFF101114))) {
        Box(Modifier.weight(1f).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .motionBlur(radiusDp, kernel),
                verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(List(ITEM_COUNT) { it }) { index -> RowItem(index, content) }
            }
        }
        Hud(
            radiusDp = radiusDp,
            velocityDpPerSec = velocityDpPerSec,
            blurEnabled = blurEnabled,
            frozen = freezeBlurDp != null,
            tracking = tracking,
            kernel = kernel,
        )
    }
}

/** Continuous scroll position in px — the item index alone jumps by a whole row at a time. */
private fun LazyListState.scrollOffsetPx(stridePx: Float): Float =
    firstVisibleItemIndex * stridePx + firstVisibleItemScrollOffset

/**
 * One row of the **real** fixture.
 *
 * The shapes here are deliberately the ones that break the pathological pattern's symmetry: a
 * title/meta pair (20 sp + 13 sp on the same row), a long Chinese sentence that wraps to the full
 * width (15 sp), a monospace code line (13 sp), and an icon + label row. Nothing repeats at a
 * fixed pitch, which is the point — a kernel can only look periodic on content that *is* periodic.
 */
private data class RealRow(
    val title: String,
    val meta: String? = null,
    val body: String? = null,
    val code: String? = null,
    val icon: String? = null,
    val titleSize: Int = 20,
    val bodySize: Int = 15,
)

private val REAL_ROWS = listOf(
    RealRow("新会话", "18:42 · 3 条消息"),
    RealRow(
        title = "迁移 §11 运动模糊到 box 核",
        body = "把方向性高斯换成一维矩形核后，拖影两端变成硬截断，",
    ),
    RealRow("gradle :composeApp:desktopTest", code = "--rerun-tasks", titleSize = 15),
    RealRow("★ 收藏", "12 个", titleSize = 15),
    RealRow(
        title = "构建产物",
        body = "desktopMainClasses 通过，43 个用例全绿，wasm 编译也过了。",
        titleSize = 20,
    ),
    RealRow("为什么", meta = "昨天", body = "半径上限从 10dp 降到 6dp 是因为 34sp 的字会被抹成色块。", bodySize = 15),
    RealRow("KernelShape", code = "Box · Triangle · Gaussian", titleSize = 15),
    RealRow(
        title = "搜索结果：AGP",
        body = "在 composeApp/build.gradle.kts 里找到 3 处引用，版本 8.7.2。",
    ),
    RealRow("好", meta = "2 分钟前"),
    RealRow(
        title = "拍频",
        body = "核长 13px 与字母横笔间距 4–7px 会产生等距回音，这是采样定理的必然结果，不是实现错误。",
    ),
    RealRow("makeMatrixConvolution", code = "kernelW = 1, kernelH = taps", titleSize = 13),
    RealRow("● 运行中", "CPU 12%", titleSize = 15),
    RealRow(
        title = "下一步",
        body = "把三角核设为默认，box 保留为选项，然后在真实内容上再拍一组对比。",
    ),
    RealRow("OK", meta = "周一"),
    RealRow("σ", "0.55 × radius", titleSize = 20),
    RealRow(
        title = "拖影剖面",
        body = "剖面的一阶差分就是核本身 —— profile = step ⊛ kernel，所以 d(profile)/dy = kernel。",
    ),
    RealRow("vade.ui.motion", code = "enum class MotionBlurKernel", titleSize = 13),
    RealRow("◆ 已归档", "2026-11-14", titleSize = 15),
    RealRow(
        title = "真实滚动",
        body = "5.7 dp 半径对应 5763 dp/s，帧率 98.5%，与冻结帧几乎一致。",
    ),
    RealRow("A/B", meta = "6dp"),
)

/** The pathological fixture: 8+ identical rows, which is what makes the beat visible. */
private val PATHOLOGICAL_TITLE = "MOTION BLUR"

@Composable
private fun RowItem(index: Int, content: ProbeContent) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ITEM_HEIGHT)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF1B1D22))
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = twoDigits(index + 1),
            color = Color(0xFF6E7681),
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(44.dp),
        )
        when (content) {
            ProbeContent.Pathological -> Text(
                text = PATHOLOGICAL_TITLE,
                color = Color(0xFFF2F3F5),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            ProbeContent.Real -> RealRowContent(REAL_ROWS[index % REAL_ROWS.size])
        }
    }
}

/**
 * Real-content row body. Three type sizes, mixed CJK/Latin, an optional monospace code block and
 * an optional glyph "icon" — i.e. every property the pathological pattern holds constant is
 * varied here, which is what makes it a fair test of the kernel rather than of the fixture.
 */
@Composable
private fun RealRowContent(row: RealRow) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            row.icon?.let {
                Text(
                    text = it,
                    color = Color(0xFF7AA2FF),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(
                text = row.title,
                color = Color(0xFFF2F3F5),
                fontSize = row.titleSize.sp,
                fontWeight = if (row.titleSize >= 20) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = if (row.code != null) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
            )
            row.meta?.let {
                Text(
                    text = "  $it",
                    color = Color(0xFF6E7681),
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
        }
        row.body?.let {
            Text(
                text = it,
                color = Color(0xFF9AA4B2),
                fontSize = row.bodySize.sp,
                maxLines = 2,
            )
        }
        row.code?.let {
            Text(
                text = it,
                color = Color(0xFF7AA2FF),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Hud(
    radiusDp: Float,
    velocityDpPerSec: Float,
    blurEnabled: Boolean,
    frozen: Boolean,
    tracking: Boolean,
    kernel: MotionBlurKernel,
) {
    val density = LocalDensity.current
    Column(
        Modifier
            .width(214.dp)
            .fillMaxSize()
            .background(Color(0xFF17191E))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("§11 运动模糊", color = Color(0xFFF2F3F5), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("blur radius", color = Color(0xFF6E7681), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(
            text = "${fmt(radiusDp, 1)} dp",
            color = if (radiusDp > 0.05f) Color(0xFF7AA2FF) else Color(0xFF9AA4B2),
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text("velocity", color = Color(0xFF6E7681), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text(
            text = "${velocityDpPerSec.roundToInt()} dp/s",
            color = Color(0xFFF2F3F5),
            fontSize = 22.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "trigger ${MOTION_BLUR_START_VELOCITY_DP_PER_SEC.roundToInt()} dp/s\n" +
                "full ${MOTION_BLUR_FULL_VELOCITY_DP_PER_SEC.roundToInt()} dp/s → " +
                "cap ${fmt(MOTION_BLUR_MAX_RADIUS_DP, 0)} dp",
            color = Color(0xFF5A6270),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                frozen -> "FROZEN @ ${fmt(radiusDp, 1)} dp\n(--freeze-blur)"
                !blurEnabled -> "BLUR OFF\n(--blur=off)"
                tracking -> "SCROLLING · live"
                else -> "IDLE · radius 0"
            },
            color = when {
                frozen -> Color(0xFFE0A458)
                !blurEnabled -> Color(0xFFE06C6C)
                else -> Color(0xFF5FBF7F)
            },
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(10.dp))
        // The same radius on a static pattern: a frozen screenshot then shows the blur both on
        // moving content and in isolation, so "is it actually blurring?" needs no scrolling.
        // A box kernel reads here as a *uniform* vertical streak with hard ends; the Gaussian
        // fallback reads as a soft bell — that difference is what the A/B measures.
        Text("same radius, static", color = Color(0xFF6E7681), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Box(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .motionBlur(radiusDp, kernel)
                .background(Color(0xFF262A31)),
            contentAlignment = Alignment.Center,
        ) {
            Text("REFERENCE", color = Color(0xFFF2F3F5), fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        }
        Text(
            when (kernel) {
                MotionBlurKernel.Box ->
                    "kernel = box 1x${boxKernelTaps(radiusDp, density.density)}\n" +
                        "N = 2*round(r*density)+1, w=1/N"
                MotionBlurKernel.Triangle ->
                    "kernel = triangle 1x${boxKernelTaps(radiusDp, density.density)}\n" +
                        "Bartlett w ∝ half+1-|i-c| (box⊛box)"
                MotionBlurKernel.Gaussian ->
                    "kernel = gaussian (fallback)\n" +
                        "sigmaY = r*density*${fmt(MOTION_BLUR_SIGMA_Y_PER_RADIUS, 2)}\n" +
                        "sigmaX = r*density*${fmt(MOTION_BLUR_SIGMA_X_PER_RADIUS, 2)} (token)"
            },
            color = Color(0xFF4A505B),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * `String.format` is JVM-only, so the HUD and the FPS line format by hand to stay in
 * `commonMain` (the same code runs on wasm).
 */
private fun fmt(value: Double, decimals: Int): String {
    if (!value.isFinite()) return "0"
    val factor = 10.0.pow(decimals)
    val scaled = (value * factor).roundToLong()
    if (decimals == 0) return scaled.toString()
    val sign = if (scaled < 0) "-" else ""
    val digits = abs(scaled).toString().padStart(decimals + 1, '0')
    return sign + digits.dropLast(decimals) + "." + digits.takeLast(decimals)
}

private fun fmt(value: Float, decimals: Int): String = fmt(value.toDouble(), decimals)

private fun twoDigits(n: Int): String = if (n < 10) "0$n" else n.toString()
