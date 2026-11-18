// g-inline-second-row · `Skin.InputBar(secondRow = …)` 的接口契约验收（compose UI）。
//
// 用户真机反馈：模型切换控件要**嵌在输入面板的第二行**（同一个玻璃/表面面板内，输入行
// 在下方的第二行），不是面板外另起一条独立行。这个文件只钉皮肤这一层的事实：
//
//  1. `secondRow = null`（默认值）时，皮肤**什么都不画** —— 没有第二行节点；把第二行
//     撤掉（null）后，面板高度回到与最初**完全相等**的那一个像素值；
//  2. `secondRow != null` 时，第二行在**同一块面板内部**、输入行**下方**：它的矩形被
//     面板矩形包含，面板只长高「间距 + 内容」那一截（8dp + 20dp），不另起一层 surface；
//  3. 三个皮肤（material / miuix / glass）走同一套断言 —— 接口是 C-09 的，任何一家漏实现
//     都会在这里红。
//
// 面板矩形怎么量：皮肤不给 `Modifier` 槽，所以测试在 InputBar 外面包一个 wrap-content 的
// Box（InputBar 是 `fillMaxWidth`、无外边距，RFC-0011 D5）—— 那个 Box 的 bounds 就是面板
// 的 bounds。

package vade.ui.skin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import vade.ui.skin.glass.GlassSkin
import vade.ui.skin.material.MaterialSkin
import vade.ui.skin.miuix.MiuixSkin

private const val PanelProbeTag = "vade.test.inputbar.panel"
private const val SecondRowProbeTag = "vade.test.inputbar.secondRow"

@OptIn(ExperimentalTestApi::class)
class InputBarSecondRowTest {

    /** 三个皮肤各一份实例：`MaterialSkin()` / `MiuixSkin()` / `GlassSkin()` 都能独立组合。 */
    private val skins: List<Pair<String, Skin>> = listOf(
        "material" to MaterialSkin(),
        "miuix" to MiuixSkin(),
        "glass" to GlassSkin(),
    )

    /**
     * 默认路径 + 第二行：null 不画任何东西；给了就画在面板内部、输入行下方，且只长高
     * 8dp（间距）+ 20dp（探针内容）。
     */
    @Test
    fun secondRowLivesInsideThePanelAndNullDrawsNothing() {
        skins.forEach { (name, skin) ->
            runComposeUiTest {
                var secondRowOn by mutableStateOf(false)
                // 面板内部的密度：miuix 在桌面会放大 1.18×（MiuixScope），所以「28dp 是多少
                // 像素」必须在**第二行自己的组合里**读，不能用外层的密度算。
                var rowDensity: Density = Density(1f)
                setContent {
                    CompositionLocalProvider(LocalSkin provides skin) {
                        Box(Modifier.testTag(PanelProbeTag)) {
                            skin.InputBar(
                                value = "",
                                onValueChange = {},
                                mode = InputMode.Idle,
                                secondRow = if (secondRowOn) {
                                    {
                                        rowDensity = LocalDensity.current
                                        Box(
                                            Modifier
                                                .size(20.dp)
                                                .testTag(SecondRowProbeTag),
                                        )
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
                waitForIdle()

                // ① 默认（null）：没有第二行节点，面板就是冻结的单行面板。
                onNodeWithTag(SecondRowProbeTag).assertDoesNotExist()
                val collapsed = onNodeWithTag(PanelProbeTag).fetchSemanticsNode().boundsInRoot

                // ② 给第二行：面板内部多出一行。
                runOnIdle { secondRowOn = true }
                mainClock.advanceTimeBy(600) // 面板高度是 200ms glide（animateContentSize）
                waitForIdle()

                val expanded = onNodeWithTag(PanelProbeTag).fetchSemanticsNode().boundsInRoot
                val row = onNodeWithTag(SecondRowProbeTag).fetchSemanticsNode().boundsInRoot
                val field = onNodeWithText("输入消息…").fetchSemanticsNode().boundsInRoot

                val expectedDelta = with(rowDensity) { 28.dp.toPx() } // 8dp 间距 + 20dp 内容
                assertEquals(
                    expectedDelta,
                    expanded.height - collapsed.height,
                    1f, // 两个维度各自取整，允许 1px 的舍入差
                    "$name：面板只该长高「间距 + 第二行」这一截（20dp + 8dp）",
                )
                assertTrue(
                    row.top >= field.bottom,
                    "$name：第二行必须在输入行之下（row.top=${row.top} < field.bottom=${field.bottom}）",
                )
                assertTrue(
                    row.top >= expanded.top && row.bottom <= expanded.bottom &&
                        row.left >= expanded.left && row.right <= expanded.right,
                    "$name：第二行必须被输入面板包含（panel=$expanded, row=$row）",
                )

                // ③ 再撤掉第二行：面板回到**逐像素相同**的高度（null 分支没有残留）。
                runOnIdle { secondRowOn = false }
                mainClock.advanceTimeBy(600)
                waitForIdle()

                onNodeWithTag(SecondRowProbeTag).assertDoesNotExist()
                assertEquals(
                    collapsed.height,
                    onNodeWithTag(PanelProbeTag).fetchSemanticsNode().boundsInRoot.height,
                    "$name：secondRow = null 必须回到冻结的单行面板高度（一个像素都不多）",
                )
            }
        }
    }
}
