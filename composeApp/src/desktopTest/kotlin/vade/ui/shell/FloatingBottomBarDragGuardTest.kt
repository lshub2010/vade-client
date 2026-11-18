package vade.ui.shell

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * m-bar · 审查 P2-4 的**纯算术**钉子：浮动胶囊松手时「这次算点击还是算拖动」。
 *
 * 为什么必须有一条不走 UI 的用例：`onDragStopped` 拿不到手势参数（只有一条
 * `DampedDragAnimation.() -> Unit`），判定只能靠组合里累计的位移 + 平台 `touchSlop`；
 * 真正的触摸注入（`AppShellSessionDetailBarTest.draggingThePillBackToItsOwnTabDoesNotLeaveTheSession`）
 * 只能覆盖其中一档（拖走又拖回），边界值在这里钉。
 *
 * 语义（用户可见后果）：详情页上「点当前 tab」= 退一层（`UiState.select` 的
 * `destination == Sessions && selectedSessionId != null -> back()`）。所以**误判成点击 =
 * 不弹确认就退掉会话**，这正是旧实现无条件上报时的情况。
 */
class FloatingBottomBarDragGuardTest {

    /** 桌面 `ViewConfiguration.touchSlop` 的量级（判定只看大小关系，不看具体值）。 */
    private val slop = 18f

    @Test
    fun aStationaryReleaseOnTheSameTabIsAClick() {
        assertTrue(
            shouldReportTabRelease(targetIndex = 0, currentIndex = 0, travelPx = 0f, touchSlopPx = slop),
            "手指没动 = 点击当前 tab：必须上报（会话页再点一次 = 退一层）",
        )
    }

    @Test
    fun aSubSlopWobbleOnTheSameTabIsStillAClick() {
        assertTrue(
            shouldReportTabRelease(targetIndex = 2, currentIndex = 2, travelPx = slop - 0.1f, touchSlopPx = slop),
            "抖动小于 touchSlop 仍算点击（真机上按一下总有几个像素的位移）",
        )
    }

    @Test
    fun aDragBackToTheSameTabIsNotAClick() {
        assertFalse(
            shouldReportTabRelease(targetIndex = 1, currentIndex = 1, travelPx = slop, touchSlopPx = slop),
            "位移到 touchSlop 就不再是点击（边界取「< slop = 点击」）",
        )
        assertFalse(
            shouldReportTabRelease(targetIndex = 1, currentIndex = 1, travelPx = 80f, touchSlopPx = slop),
            "拖走又拖回原位（净位移 0、累计位移 80）只能是拖动：不许退会话",
        )
    }

    @Test
    fun aReleaseOnAnotherTabAlwaysReportsEvenWithoutTravel() {
        assertTrue(
            shouldReportTabRelease(targetIndex = 3, currentIndex = 0, travelPx = 0f, touchSlopPx = slop),
            "落在别的格子就是一次拖动切换：必须上报（手势切 tab 不许被这道门吞掉）",
        )
        assertTrue(
            shouldReportTabRelease(targetIndex = 0, currentIndex = 3, travelPx = slop * 4, touchSlopPx = slop),
            "反向拖动同理",
        )
    }

    @Test
    fun aZeroTouchSlopStillOnlyTreatsAZeroTravelAsAClick() {
        // 平台不报 touchSlop（或报 0）时，判定退化成「只有完全没动才算点击」——
        // 比旧行为（无条件上报）安全，绝不会把一次拖动当成退会话。
        assertFalse(
            shouldReportTabRelease(targetIndex = 0, currentIndex = 0, travelPx = 1f, touchSlopPx = 0f),
            "slop = 0 时任何位移都不是点击",
        )
        assertTrue(
            shouldReportTabRelease(targetIndex = 0, currentIndex = 0, travelPx = 0f, touchSlopPx = 0f),
            "slop = 0 且完全没动 = 点击",
        )
    }
}
