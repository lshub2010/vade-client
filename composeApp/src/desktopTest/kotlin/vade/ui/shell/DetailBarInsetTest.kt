package vade.ui.shell

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import vade.ui.component.insets.composerNavClearance
import vade.ui.skin.BottomBarStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * m-bar · 审查 P1-1 的**纯算术**钉子：详情内容链的下留白怎么算。
 *
 * 为什么必须有一条不走 UI 的用例：桌面 `navigationBarBottomInset()` 的 actual 恒为 0
 * （`InsetsGuardTest` 在真窗口里量过），所以任何用 `AppShell` 组合出来的用例都只能看到
 * `nav = 0` 那一档 —— 而「胶囊与 composer 之间多出 24/48 dp 白边」这个缺陷**只在 Android
 * 上存在**，桌面用例结构上不可能发现它。这里直接喂 0/24/48 三档给纯函数。
 *
 * ## 审查 P1-1 之后的形状
 *
 * 旧实现：`inset = barZone − clearance`，两个消费者各自把 clearance 加回来
 * （composer `16 + clearance + inset`，底栏带 `barZone = inset + clearance`）。净值恒等于
 * `barZone`，但 **AppShell 在组合期读 `WindowInsets.ime`** ⇒ 键盘动画期间整条手机内容链
 * （抽屉宿主 / 列表 / `groups()`）每帧重组。
 *
 * 现在：`inset = barZoneFor(style, navInset)`（只依赖 navigationBars），消费者不再加
 * clearance。所以不变式是两条：
 *
 *  1. **预留就是整条栏区**：`inset == barZoneFor(style, nav)` —— 与 IME 无关；
 *  2. **像素不变**：`inset + 16` 必须逐值等于旧几何 `(barZone − c) + 16 + c`，对
 *     `c ∈ {0, nav}`（键盘开 / 合）都成立。
 *
 * 数字本身（浮动态 104 / 108 / 132，Fixed 65 / 89 / 113）也逐档钉住 —— 光有不变式不够：
 * 两边同时错一个常数，不变式仍然成立。
 */
class DetailBarInsetTest {

    private val navSteps = listOf(0.dp, 24.dp, 48.dp)
    private val styles = BottomBarStyle.entries.toList()

    /** 键盘拉起的高度档：0（收起）/ nav（正好盖住系统栏）/ 800（真键盘）。 */
    private val imeSteps = listOf(0.dp, 24.dp, 800.dp)

    /** 键盘收起时的 composer 余量：`max(0, nav − 0) = nav`。 */
    private fun restClearance(navInset: Dp): Dp =
        composerNavClearance(imeBottom = 0.dp, navInset = navInset)

    /** composer 胶囊底边到窗口底的距离（今天 `ChatThread` 那段 padding）。 */
    private fun composerBottomFromWindow(inset: Dp): Dp = inset + 16.dp

    /** 改前的同一条几何：`inset = barZone − c`，composer 再自己加 `16 + c`。 */
    private fun preFixComposerBottom(style: BottomBarStyle, navInset: Dp, clearance: Dp): Dp =
        (barZoneFor(style, navInset) - clearance).coerceAtLeast(0.dp) + 16.dp + clearance

    @Test
    fun theReserveIsTheWholeZoneForEveryNavForm() {
        for (style in styles) {
            for (nav in navSteps) {
                assertEquals(
                    barZoneFor(style, nav),
                    detailContentBottomInset(style, nav),
                    "「$style / nav=$nav」：留白就是整条栏区 —— 不再扣 composer 的导航条余量",
                )
                assertTrue(
                    detailContentBottomInset(style, nav) >= 0.dp,
                    "「$style / nav=$nav」：留白不得为负（负值会让 padding 抛异常）",
                )
            }
        }
    }

    /**
     * 「像素不变」的那条（审查 P1-1 的验收口径）：新旧两种写法对每一个
     * `(style, nav, ime)` 组合都给出**同一个** composer 底边。
     *
     * `ime` 三档覆盖了 clearance 的两种取值（`max(0, nav − ime)` = nav / 0）：
     *  - `ime = 0`：clearance = nav（键盘收起，导航条露出来）；
     *  - `ime ≥ nav`：clearance = 0（键盘盖住系统栏，根部 `imePadding` 已抬整页）。
     */
    @Test
    fun theComposerBottomIsPixelIdenticalToThePreFixGeometry() {
        for (style in styles) {
            for (nav in navSteps) {
                for (ime in imeSteps) {
                    val clearance = composerNavClearance(imeBottom = ime, navInset = nav)
                    assertEquals(
                        preFixComposerBottom(style, nav, clearance),
                        composerBottomFromWindow(detailContentBottomInset(style, nav)),
                        "「$style / nav=$nav / ime=$ime」：新几何必须与改前逐像素相同" +
                            "（clearance=$clearance）—— 否则这次性能修复顺手改了布局",
                    )
                }
            }
        }
    }

    @Test
    fun theRestGeometryIsPinnedPerForm() {
        // 浮动态：64 pill + (8 + nav | 28) + 12 dock pad ⇒ 104 / 108 / 132。
        assertEquals(104.dp, detailContentBottomInset(BottomBarStyle.Floating, 0.dp))
        assertEquals(108.dp, detailContentBottomInset(BottomBarStyle.Floating, 24.dp))
        assertEquals(132.dp, detailContentBottomInset(BottomBarStyle.Floating, 48.dp))
        // 浮动态（模糊）同一套几何：同一个 composable，只差不透明度。
        assertEquals(104.dp, detailContentBottomInset(BottomBarStyle.FloatingBlur, 0.dp))
        assertEquals(108.dp, detailContentBottomInset(BottomBarStyle.FloatingBlur, 24.dp))
        assertEquals(132.dp, detailContentBottomInset(BottomBarStyle.FloatingBlur, 48.dp))
        // 吸底态：1 + 64 + nav ⇒ 65 / 89 / 113。
        for (nav in navSteps) {
            assertEquals(
                65.dp + nav,
                detailContentBottomInset(BottomBarStyle.Fixed, nav),
                "「Fixed / nav=$nav」：栏区 = 顶线 + 行高 + 导航条",
            )
        }
    }

    /**
     * 「三处一起改」的算术证据：旧的预留**不是**栏区（`nav > 0` 时它小了一整个 nav），
     * 而 composer 底边却和现在一样 —— 也就是说这个 `clearance` 只在「宿主扣、消费者加」
     * 这一对里成立。谁只改一边（把 `+ composerNavClearance()` 留在一个消费者里，
     * 或把 `− clearance` 留在宿主里），[theComposerBottomIsPixelIdenticalToThePreFixGeometry]
     * 与这条就会同时红。
     */
    @Test
    fun theOldReserveWasNotTheZoneSoBothSidesHadToMoveTogether() {
        for (style in styles) {
            for (nav in navSteps) {
                val clearance = restClearance(nav)
                val oldReserve = (barZoneFor(style, nav) - clearance).coerceAtLeast(0.dp)
                if (nav > 0.dp) {
                    assertTrue(
                        oldReserve != barZoneFor(style, nav),
                        "「$style / nav=$nav」：旧预留应当小于栏区（这正是「两边各算一遍」的" +
                            "形状）；相等说明这条用例的前提已经不成立",
                    )
                }
                assertEquals(
                    barZoneFor(style, nav),
                    detailContentBottomInset(style, nav),
                    "「$style / nav=$nav」：新预留就是栏区，与 IME / clearance 无关",
                )
            }
        }
    }

    // --- 源码守卫（审查 P1-1 的「组合期不再调用 composerNavClearance」） -------------

    private fun codeOf(path: String): String {
        val file = java.io.File("src/commonMain/kotlin/$path")
        assertTrue(file.isFile, "缺 ${file.path}")
        return file.readText()
            .replace(Regex("(?s)/\\*.*?\\*/"), "")
            .split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    /**
     * 守卫：**AppShell 组合期不得再读 `composerNavClearance()`**。
     *
     * 这是 P1-1 的根因（`WindowInsets.ime` 在 `Column` 的 content lambda 里被读 ⇒ 键盘
     * 动画每帧重组整条内容链），而它不会让任何像素断言变红 —— 只有这条守卫能让它「回来
     * 就红」。注释先剥掉：KDoc 里讨论它是对的，代码里出现才算违例。
     */
    @Test
    fun theShellNeverReadsTheImeForTheDetailInset() {
        val shell = codeOf("vade/ui/shell/AppShell.kt")
        assertFalse(
            shell.contains("composerNavClearance"),
            "AppShell 又出现了 composerNavClearance —— 组合期读 WindowInsets.ime 会" +
                "让键盘动画期间整条手机内容链每帧重组（审查 P1-1）",
        )
    }

    /** 守卫：底栏模糊带不得把 clearance 加回来（宿主现在传的就是栏区）。 */
    @Test
    fun theBottomBlurBandDoesNotAddTheClearanceBack() {
        val band = codeOf("vade/ui/chat/ComposerBottomBlur.kt")
        assertFalse(
            band.contains("composerNavClearance"),
            "ComposerBottomBlur 又把 clearance 加回来了 —— 宿主传的已经是整条栏区，" +
                "加回来就是导航条算两遍（审查 P1-1 的「三处一起改」）",
        )
    }

    /**
     * 守卫：`ChatThread` 的那次读取只允许出现在 **`bottomBarInset <= 0` 的兜底分支**里
     * （rail / 双栏没有底栏，Android 的三键导航条仍会盖住胶囊）。
     *
     * 手机详情态 `bottomBarInset` 恒为整条栏区（> 0），所以那次读取在手机路径上**不会
     * 执行** —— 键盘动画不再拖着重组建。这一条用文本形状钉住这个条件，别让谁把
     * `max(...)` 或无条件相加写回来。
     */
    @Test
    fun theComposerOnlyFallsBackToTheClearanceWithoutABarZone() {
        val chat = codeOf("vade/ui/chat/ChatThread.kt")
        assertTrue(
            Regex(
                """val bottomReserve: Dp\s*=\s*\n\s*if \(bottomBarInset > 0\.dp\) bottomBarInset else composerNavClearance\(\)""",
            ).containsMatchIn(chat),
            "composer 的底部余量必须是「有栏区就用栏区、没有才读 clearance」的形式；" +
                "无条件 `+ composerNavClearance()` 会把 IME 动画重新绑回组合作用域（审查 P1-1）",
        )
        assertTrue(
            chat.contains("bottom = 16.dp + bottomReserve"),
            "composer 的 padding 必须直接加 bottomReserve（不要在那儿再读一遍 IME）",
        )
        assertEquals(
            1,
            Regex("""composerNavClearance\(""").findAll(chat).count(),
            "ChatThread 里 composerNavClearance() 只允许剩兜底那一处",
        )
    }
}
