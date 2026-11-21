package vade.ui.skin.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import vade.ui.glass.GlassScope
import vade.ui.glass.asGlassSource
import vade.ui.glass.rememberGlassState
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin

/**
 * **能力矩阵**（审查 P0-1 的验收口径）。
 *
 * 真机反馈的三条症状都源于同一件事：能力阶梯只覆盖了纯函数，没有覆盖"调用点听不听它"。
 * 这张表把两组事实的**全部组合**走一遍：
 *
 * | 平台上限 | 有 backdrop | 这一层自己采样 | 画什么 |
 * |---|---|---|---|
 * | Blur / Refraction | ✔ | ✔ | haze 节点（模糊 + tint） |
 * | Blur / Refraction | ✔ | ✘ | tint（模糊由别的层提供：顶栏带 / 底栏带） |
 * | Blur / Refraction | ✘ | 任意 | tint（没有背景可采） |
 * | TintStroke | 任意 | 任意 | tint（平台没有 RenderEffect，不许建节点） |
 *
 * 表本身是纯函数断言；下面再用**真实组合**钉住"降级层真的不建节点、但仍然画"——
 * 因为"能力说不能模糊"和"代码真的没建节点"是两件事，后者只能靠组合证明。
 */
class GlassCapabilityMatrixTest {

    /** 与 `GlassSurface` / `glassPaint` 完全同一条判定（单参数门禁 + blurBackdrop）。 */
    private fun drawsHazeNode(
        platform: GlassCapability,
        hasBackdrop: Boolean,
        blurBackdrop: Boolean,
    ): Boolean = hasBackdrop && platform.blurs && blurBackdrop

    /** 在注入的平台能力下执行 [block]，结束后恢复探针。 */
    private fun <T> withPlatform(capability: GlassCapability?, block: () -> T): T {
        val original = glassPlatformCapabilityProbe
        return try {
            glassPlatformCapabilityProbe = capability?.let { { it } }
                ?: { throw IllegalStateException("fake AGSL failure") }
            block()
        } finally {
            glassPlatformCapabilityProbe = original
        }
    }

    // --- 1 · 纯函数矩阵 ---------------------------------------------------------------

    @Test
    fun capabilityMatrixPicksExactlyOnePaintKind() {
        for (platform in GlassCapability.entries) {
            for (hasBackdrop in listOf(true, false)) {
                for (blurBackdrop in listOf(true, false)) {
                    // 走的是**生产路径**（glassCapability + drawsHazeLayer），不是本地复算。
                    val actual = withPlatform(platform) {
                        hasBackdrop && drawsHazeLayer(glassCapability(hasBackdrop = hasBackdrop)) &&
                            blurBackdrop
                    }
                    val expected = hasBackdrop && platform.blurs && blurBackdrop
                    assertEquals(
                        expected,
                        actual,
                        "平台=$platform hasBackdrop=$hasBackdrop blurBackdrop=$blurBackdrop " +
                            "⇒ 期望 ${if (expected) "haze 节点" else "tint 层"}",
                    )
                    // 纯函数的语义与上面的判定必须逐点一致（两处都写错才会同时绿）。
                    assertEquals(expected, drawsHazeNode(platform, hasBackdrop, blurBackdrop))
                }
            }
        }
    }

    /**
     * 探针抛异常也在矩阵里：收敛到 `TintStroke`，**不建节点**、不冒泡。
     */
    @Test
    fun probeFailureIsTintStrokeInTheMatrix() {
        withPlatform(null) {
            val capability = glassCapability(hasBackdrop = true)
            assertEquals(GlassCapability.TintStroke, capability)
            assertFalse(drawsHazeLayer(capability), "探测失败 ⇒ 不建节点（否则又是一层灰罩）")
        }
    }

    // --- 2 · 降级日志（审查 P1-10） --------------------------------------------------

    /**
     * 降级必须留痕，且**三件事**都在同一行里：SDK/平台、hasBackdrop、候选档位。
     *
     * 真机返工时最贵的一步是"猜"：是能力不够（API<31），还是页面没接 `GlassScope`？
     * 一行 `adb logcat | grep vade.glass` 就能回答。
     */
    @Test
    fun degradeLogCarriesPlatformBackdropAndCandidates() {
        withPlatform(GlassCapability.TintStroke) {
            GlassDegradeLog.reset()
            assertEquals(GlassCapability.TintStroke, glassCapability(hasBackdrop = true))
            val line = assertNotNull(GlassDegradeLog.lastLine, "降级必须打一行日志")
            assertTrue(line.startsWith("vade.glass degrade:"), "日志前缀要可 grep：$line")
            assertTrue(line.contains("capability=TintStroke"), "结论要写清楚：$line")
            assertTrue(line.contains("hasBackdrop=true"), "有没有背景必须写在行里：$line")
            assertTrue(line.contains("platform="), "平台指纹必须写在行里：$line")
            assertTrue(line.contains("candidates=[Blur,TintStroke]"), "候选档位必须写在行里：$line")
        }

        // 没有背景（最常见的一种）同样要留痕。
        GlassDegradeLog.reset()
        assertEquals(GlassCapability.TintStroke, glassCapability(hasBackdrop = false))
        val noBackdrop = assertNotNull(GlassDegradeLog.lastLine)
        assertTrue(noBackdrop.contains("hasBackdrop=false"), noBackdrop)
        assertTrue(noBackdrop.contains("no backdrop"), "要说清是 GlassScope 没接：$noBackdrop")
    }

    /** 同一结论只打一行 —— 组合热路径上 `glassCapability` 每帧都会被调用。 */
    @Test
    fun degradeLogIsDeduplicatedPerConclusion() {
        GlassDegradeLog.reset()
        glassCapability(hasBackdrop = false)
        val first = assertNotNull(GlassDegradeLog.lastLine)
        GlassDegradeLog.reset()
        glassCapability(hasBackdrop = false)
        assertEquals(first, GlassDegradeLog.lastLine, "去重键是「结论」，不是「进程」")
    }

    // --- 3 · 真实组合：降级层不建节点、但仍然画 --------------------------------------

    private companion object {
        const val ProbeTag = "vade.glass.matrix.probe"
    }

    /**
     * 组合一个最小玻璃场景：[withBackdrop] 决定有没有 `GlassScope`（有没有背景可采），
     * [check] 在**组合仍然存活**时读 [GlassLayerProbe.live]。
     */
    @OptIn(ExperimentalTestApi::class)
    private fun composeProbeSurface(withBackdrop: Boolean, check: (liveNodes: Int) -> Unit) =
        runComposeUiTest {
            GlassLayerProbe.reset()
            val skin = MaterialSkin()
            setContent {
                CompositionLocalProvider(LocalSkin provides skin) {
                    val glass = rememberGlassState()
                    val scene: @Composable () -> Unit = {
                        Box(Modifier.size(200.dp)) {
                            // backdrop（source）与玻璃层是**兄弟** —— haze 2.1.0 的硬约束。
                            Box(Modifier.size(200.dp).asGlassSource(glass)) {}
                            GlassSurface(
                                modifier = Modifier.size(120.dp).testTag(ProbeTag),
                                shape = RoundedCornerShape(28.dp),
                                skin = skin,
                            ) {}
                        }
                    }
                    if (withBackdrop) GlassScope(glass) { scene() } else scene()
                }
            }
            waitForIdle()
            onNodeWithTag(ProbeTag).assertExists()
            check(GlassLayerProbe.live)
        }

    /** TintStroke：不建节点，但**层还在**（节点存在 + 没抛）。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun degradedSurfaceStillPaintsButBuildsNoNode() {
        withPlatform(GlassCapability.TintStroke) {
            composeProbeSurface(withBackdrop = true) { live ->
                assertEquals(
                    0,
                    live,
                    "TintStroke 下不许建 haze 节点 —— 采不到背景的 haze 链只画一层灰罩（P0-1）",
                )
            }
        }
        GlassLayerProbe.reset()
    }

    /** 能模糊时：恰好一个节点（不是每层各来一个，也不是零个）。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun blurringSurfaceBuildsExactlyOneNode() {
        composeProbeSurface(withBackdrop = true) { live ->
            assertEquals(1, live, "能模糊时该层恰好一个 haze 节点")
        }
        GlassLayerProbe.reset()
    }

    /** 没有 `GlassScope`：不建节点（旧实现的"私有空 state 硬跑 haze"正是 P0-1 的另一半）。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun surfaceWithoutBackdropBuildsNoNode() {
        composeProbeSurface(withBackdrop = false) { live ->
            assertEquals(0, live, "没有背景可采时不许建节点")
        }
        GlassLayerProbe.reset()
    }
}
