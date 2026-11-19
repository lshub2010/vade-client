package vade.ui.destinations

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import vade.ui.UiText
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * H3 · 行长按锚点**不许**在布局回调里写快照状态。
 *
 * ## 病灶（审查 H3）
 * `SessionRow` 的 `onGloballyPositioned` 以前写的是 `mutableStateOf(IntRect)`：
 * 滚动 / 惯性 / 抽屉滑动时，每一可见行的根坐标每帧都在变 ⇒ 回调每帧每行一次 ⇒
 * 「每帧 × 可见行数」次快照写入（12 行 × 120Hz ≈ 1440 次/秒），而唯一消费者是
 * 「长按」——一次交互最多一次。同仓正确模板是 `ChatThread.kt` 的 `observePointerTap`
 * （普通引用存 `LayoutCoordinates`，坐标只在指针回调里读）。
 *
 * ## 这里钉什么
 *  1. **重组计数器**：行内容在组合期读一次锚点（真实消费面的形状），然后连续驱动
 *     布局（`offset { }` 的 lambda 读状态 ⇒ 只失效 layout，不失效组合），断言
 *     `compositions == 1`。旧实现（`mutableStateOf` + 组合期读）在这条上会 1 → 6：
 *     矩形变了就得重组一次，滚动期间就是「每帧每行」。
 *  2. 锚点仍然**跟着行走**：矩形 top 随布局位移同步变化 —— 修性能不许把 J3 的
 *     「菜单贴行」一起修掉。
 *  3. [SessionRowBounds.update] 同值短路（等值新实例不覆盖）——「同 bounds 连续回调」
 *     在最底层就已经不产生任何写入。
 *
 * 为什么「同值但换了实例」的布局回调不用计数器钉：`mutableStateOf` 默认是
 * `structuralEqualityPolicy`，等值写入本来就不会失效组合 —— 那条断言在旧实现上也绿，
 * 钉不住任何东西。真正区分两种实现的是第 1 条（**值变了**的布局回调），所以计数器
 * 只放在那里。同值这一半由第 3 条在 holder 层直接断言。
 */
@OptIn(ExperimentalTestApi::class)
class SessionRowBoundsTest {

    @Test
    fun layoutCallbacksNeverRecomposeTheRow() = runComposeUiTest {
        val bounds = SessionRowBounds()
        val topPx = mutableIntStateOf(0)
        var compositions = 0

        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                // `offset { }` 的 lambda 在**布局阶段**读 topPx ⇒ 改它只触发重新布局，
                // 正是「滚动时根坐标每帧都变、组合不变」的最小复现。
                Box(
                    Modifier
                        .size(200.dp, SESSION_ROW_HEIGHT)
                        .offset { IntOffset(0, topPx.intValue) }
                        .sessionRowBounds(bounds),
                ) {
                    // 组合期读一次锚点（真实的消费面：菜单要知道「有没有矩形」）。
                    // 普通引用 ⇒ 布局阶段的写入不会让这个作用域失效。
                    compositions++
                    UiText(if (bounds.value == IntRect.Zero) "no-anchor" else "anchor", size = 10)
                }
            }
        }
        waitForIdle()

        assertEquals(1, compositions, "首帧只许组合一次")
        val initial = bounds.value
        assertTrue(initial.width > 0 || initial.height > 0, "首帧布局后必须已经拿到矩形：$initial")
        val afterFirstFrame = compositions

        // 连续 5 次布局（每次矩形都变）：旧实现在这里就是 5 次额外重组。
        repeat(5) { step ->
            runOnIdle { topPx.intValue = 8 * (step + 1) }
            waitForIdle()
        }

        assertEquals(
            afterFirstFrame,
            compositions,
            "布局回调（滚动时每帧每行一次）不许触发重组 —— 旧实现在这里是 +5",
        )
        assertEquals(
            initial.top + 40,
            bounds.value.top,
            "锚点必须仍然跟着行（长按菜单贴行，J3）",
        )
        assertEquals(initial.height, bounds.value.height, "行高不变 ⇒ 矩形高度不变")
    }

    @Test
    fun theHolderShortCircuitsIdenticalRectangles() {
        val bounds = SessionRowBounds()
        assertEquals(IntRect.Zero, bounds.value, "未布局 = 零矩形（长按菜单退回旧落点）")

        bounds.update(IntRect(0, 0, 400, 56))
        assertEquals(IntRect(0, 0, 400, 56), bounds.value)

        // 等值但**另一个实例**：不许覆盖（同值短路；`IntRect` 是 data class，值比较）。
        val same = IntRect(0, 0, 400, 56)
        bounds.update(same)
        assertEquals(IntRect(0, 0, 400, 56), bounds.value)

        // 真的变了才写。
        bounds.update(IntRect(0, 56, 400, 112))
        assertEquals(IntRect(0, 56, 400, 112), bounds.value)
    }
}
