package vade.ui.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.component.insets.composerNavClearance
import vade.ui.skin.BottomBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * m-bar · 审查 P1-1 的**纯算术**钉子：详情内容链的下留白怎么算。
 *
 * 为什么必须有一条不走 UI 的用例：桌面 `navigationBarBottomInset()` 的 actual 恒为 0
 * （`InsetsGuardTest` 在真窗口里量过），所以任何用 `AppShell` 组合出来的用例都只能看到
 * `nav = 0` 那一档 —— 而「胶囊与 composer 之间多出 24/48 dp 白边」这个缺陷**只在 Android
 * 上存在**，桌面用例结构上不可能发现它。这里直接喂 0/24/48 三档给纯函数。
 *
 * 三条不变式：
 *
 *  1. **导航条只算一次**：`inset + 16 + clearance == barZone + 16`。左边是 composer 胶囊底边
 *     到窗口底的距离（`inset` 由容器链给，`16 + clearance` 由 composer 自己加），右边是
 *     栏区上沿再退 16 dp 的设计间距。两者相等 ⇒ 导航条没有被两边各算一遍。
 *  2. **预留严格小于栏区**（只要 `nav > 0`）：旧实现直接给整条 [barZoneFor]（含 nav），于是
 *     `inset + nav` 被算了两次 —— 这一条就是那个回归的探针。
 *  3. **键盘开合同样只算一次**：`clearance = max(0, nav − ime)`，IME 弹起时归零（根 `imePadding`
 *     已抬升整页），预留回到整条栏区，不再与 IME 相加。
 *
 * 数字本身（浮动态 104 / 84 / 84，Fixed 65 / 65 / 65）也钉住 —— 光有不变式不够：两边同时
 * 错一个常数，不变式仍然成立。
 */
class DetailBarInsetTest {

    private val navSteps = listOf(0.dp, 24.dp, 48.dp)
    private val styles = BottomBarStyle.entries.toList()

    /** 键盘收起时的 composer 余量：`max(0, nav − 0) = nav`。 */
    private fun restClearance(navInset: Dp): Dp =
        composerNavClearance(imeBottom = 0.dp, navInset = navInset)

    /** composer 胶囊底边到窗口底的距离（`ChatThread` 的 composer 浮层自己那两段）。 */
    private fun composerBottomFromWindow(inset: Dp, clearance: Dp): Dp = inset + 16.dp + clearance

    @Test
    fun theNavInsetIsCountedExactlyOnceAtRest() {
        for (style in styles) {
            for (nav in navSteps) {
                val clearance = restClearance(nav)
                val inset = detailContentBottomInset(style, nav, clearance)
                assertEquals(
                    barZoneFor(style, nav) + 16.dp,
                    composerBottomFromWindow(inset, clearance),
                    "「$style / nav=$nav」：composer 底边必须恒在栏区上沿之上 16 dp；" +
                        "不等就说明导航条被容器和 composer 各算了一遍（P1-1 的白边）",
                )
            }
        }
    }

    @Test
    fun theReserveDropsTheComposersOwnNavTerm() {
        for (style in styles) {
            for (nav in navSteps) {
                val inset = detailContentBottomInset(style, nav, restClearance(nav))
                assertEquals(
                    barZoneFor(style, nav) - nav,
                    inset,
                    "「$style / nav=$nav」：留白 = 栏区 − composer 自带的导航条余量（= nav）",
                )
                assertTrue(
                    inset >= 0.dp,
                    "「$style / nav=$nav」：留白不得为负（负值会让 padding 抛异常）",
                )
                if (nav > 0.dp) {
                    assertTrue(
                        inset < barZoneFor(style, nav),
                        "「$style / nav=$nav」：nav > 0 时留白必须**小于**栏区 —— 相等就是旧实现，" +
                            "Android 上胶囊与 composer 之间会永久多出一条 nav 宽的空白",
                    )
                }
            }
        }
    }

    @Test
    fun theRestGeometryIsPinnedPerForm() {
        // 浮动态：64 pill + (8 + nav | 28) + 12 dock pad − nav ⇒ 104 / 84 / 84。
        assertEquals(104.dp, detailContentBottomInset(BottomBarStyle.Floating, 0.dp, restClearance(0.dp)))
        assertEquals(84.dp, detailContentBottomInset(BottomBarStyle.Floating, 24.dp, restClearance(24.dp)))
        assertEquals(84.dp, detailContentBottomInset(BottomBarStyle.Floating, 48.dp, restClearance(48.dp)))
        // 浮动态（模糊）同一套几何：同一个 composable，只差不透明度。
        assertEquals(104.dp, detailContentBottomInset(BottomBarStyle.FloatingBlur, 0.dp, restClearance(0.dp)))
        assertEquals(84.dp, detailContentBottomInset(BottomBarStyle.FloatingBlur, 24.dp, restClearance(24.dp)))
        // 吸底态：1 + 64 + nav − nav ⇒ 65 恒等（栏区自己就带着 nav，composer 也带着）。
        for (nav in navSteps) {
            assertEquals(
                65.dp,
                detailContentBottomInset(BottomBarStyle.Fixed, nav, restClearance(nav)),
                "「Fixed / nav=$nav」：吸底栏的留白与导航条无关（两边各带一项）",
            )
        }
    }

    @Test
    fun theKeyboardOpenGeometryUsesTheWholeZone() {
        // 键盘弹起：IME 盖住导航条 ⇒ clearance = 0 ⇒ 预留就是整条栏区。
        // 这条钉住「不与 IME 相加」：旧写法会把 nav 再减一次，胶囊与输入框在键盘上方重叠。
        for (style in styles) {
            for (nav in navSteps) {
                val clearance = composerNavClearance(imeBottom = nav, navInset = nav)
                assertEquals(0.dp, clearance, "「nav=$nav」：IME 弹起时余量必须归零")
                assertEquals(
                    barZoneFor(style, nav),
                    detailContentBottomInset(style, nav, clearance),
                    "「$style / nav=$nav」：键盘弹起时预留 = 整条栏区",
                )
                assertEquals(
                    barZoneFor(style, nav) + 16.dp,
                    composerBottomFromWindow(detailContentBottomInset(style, nav, clearance), clearance),
                    "键盘弹起时 composer 底边仍在栏区上沿之上 16 dp（导航条没有第二项）",
                )
            }
        }
    }
}
