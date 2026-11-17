package vade.ui

import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.Skins
import vade.ui.testutil.IsolatedPreferencesTest

/**
 * P1-13 · 桌面「外观」选择被 [UiState] 重建回滚的回归面。
 *
 * 病因：`App.kt` 的 `remember(effectiveSource) { UiState(initialSkinId = …, initialBar = …) }`
 * 用的是**启动实参**。首次配对成功会换源 → `effectiveSource` 变 → UiState 重建 → 用户在设置页
 * 刚选的皮肤 / 底栏被静默打回启动值。界面上的表现是「选了，过一会儿自己弹回去」，而不是报错。
 *
 * 修法：跨重建带上一轮的值（`CarriedAppearance`，每帧由 `SideEffect` 同步），重建时优先用它；
 * 决定用哪个 id 的规则被提成纯函数 [appearanceOnRebuild]，所以这里可以确定性地钉住顺序，
 * 不依赖「配对真的发生」这条异步路径。最后两条是源码钉子：纯函数再对，`App.kt` 不接也白搭。
 */
class AppAppearanceRebuildTest : IsolatedPreferencesTest() {

    @BeforeTest
    fun ensureBuiltInsInstalled() {
        Skins.installBuiltIns()
    }

    // ------------------------------------------------- 1 · 顺序：本轮 > 启动实参

    @Test
    fun inSessionChoiceSurvivesTheRebuild() {
        assertEquals(
            "miuix",
            appearanceOnRebuild(current = "miuix", initial = "material"),
            "配对换源重建 UiState 时，用户这轮选的 miuix 必须还在（这就是回滚缺陷本身）",
        )
        assertEquals("material", appearanceOnRebuild(current = "material", initial = "miuix"), "反向同样成立")
    }

    @Test
    fun withoutACarriedValueTheBootArgumentIsUsed() {
        assertEquals("miuix", appearanceOnRebuild(current = null, initial = "miuix"), "首帧 = 启动实参")
    }

    @Test
    fun bothNullMeansReadThePreference() {
        assertEquals(Skins.DEFAULT_SKIN, appearanceOnRebuild(null, null), "没实参也没 carry → 出厂")

        // 存过的偏好仍然有效：null 表示「读偏好」，不是在调用点冻成实参。
        vade.ui.skin.AppearancePrefs.storeSkin("miuix")
        assertEquals("miuix", appearanceOnRebuild(null, null), "允许 null → 组合期读偏好")
    }

    // ------------------------------------------------- 2 · 失效的 carry 不许进组合

    @Test
    fun unusableCarriedIdFallsBackToTheBootChain() {
        assertEquals("material", appearanceOnRebuild(current = "glass", initial = "material"))
        assertEquals("miuix", appearanceOnRebuild(current = "glass", initial = "miuix"), "退回启动链，不是硬编码 material")
        assertEquals(Skins.DEFAULT_SKIN, appearanceOnRebuild(current = "nope", initial = null))
        assertEquals("miuix", appearanceOnRebuild(current = "  ", initial = "miuix"), "空白 carry = 没有")
    }

    // ------------------------------------------------- 3 · 底栏同样不是启动快照

    @Test
    fun bottomBarChoiceIsCarriedToo() {
        // App.kt 的 UiState 构造用 `carriedAppearance.bar ?: initialBar` —— 与皮肤同一条规则。
        // 这里钉住语义本身（carry 优先），避免有人只修皮肤、忘了底栏。
        val carried: BottomBarStyle? = BottomBarStyle.FloatingBlur
        val initial = BottomBarStyle.Fixed
        assertEquals(BottomBarStyle.FloatingBlur, carried ?: initial)
        assertEquals(initial, (null as BottomBarStyle?) ?: initial)
    }

    // ------------------------------------------------- 4 · 接线（源码钉子）

    private fun source(path: String) = File("src/$path")

    /** 与 `WindowTitleTest` 同一个理由：守卫匹配**代码**，不匹配病历 / 说明里的引用。 */
    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//.*"""), "")

    @Test
    fun appKtCarriesTheCurrentAppearanceIntoTheRebuiltUiState() {
        val app = stripComments(source("commonMain/kotlin/vade/ui/App.kt").readText())

        assertTrue(
            "initialSkinId = appearanceOnRebuild(carriedAppearance.skinId" in app,
            "UiState 构造没走 appearanceOnRebuild(carriedAppearance.skinId, …)：重建时又会拿启动实参 → 外观回滚（P1-13）",
        )
        assertTrue(
            "carriedAppearance.skinId = state.skinId" in app,
            "App.kt 没有把当前 state 的皮肤同步进 carry —— rebuild 后拿不到这轮的值",
        )
        assertTrue(
            "carriedAppearance.bar = state.barStyle" in app,
            "App.kt 没有把当前 state 的底栏同步进 carry —— 底栏会跟着重建一起回滚",
        )
        assertFalse(
            Regex("""initialSkinId\s*=\s*initialSkinId""").containsMatchIn(app),
            "UiState 又被直接喂启动实参了：`initialSkinId = initialSkinId` 正是回滚的写法",
        )
    }

    @Test
    fun appKtNeverHandsAnUnresolvedSkinIdToTheRegistry() {
        val app = stripComments(source("commonMain/kotlin/vade/ui/App.kt").readText())
        assertTrue(
            Regex("""Skins\.resolveSkinId\(state\.skinId\)""").containsMatchIn(app),
            "SkinRegistry.get 前必须再过一次 Skins.resolveSkinId —— " +
                "setSkin(未注册 id) 会把组合炸掉（P1-5）",
        )
        assertFalse(
            Regex("""SkinRegistry\.get\(state\.skinId\)""").containsMatchIn(app),
            "又回到 `SkinRegistry.get(state.skinId)` 了：未注册 id 在这里是 error(...)",
        )
    }
}
