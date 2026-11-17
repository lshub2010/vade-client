package vade.ui.skin

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.testutil.IsolatedPreferencesTest

/**
 * I1 / P1-5 · 启动皮肤 id 的收敛规则：**任何**来源的 id 都不许把组合炸掉。
 *
 * 三条失效路径以前各走各的：
 *  - 旧偏好存过 `glass`（那时它有占位实现，现在没有）→ [Skins.bootSkinId] 收敛到 material；
 *  - 手改偏好 / 老版本写的陌生 id → 同上；
 *  - 命令行 `--skin=glass` / `--skin=<打错的 id>` → 曾经**绕过** boot 直接进
 *    `SkinRegistry.get`，组合期 `error(...)`，桌面启动即崩。
 *
 * 现在入口与偏好走同一个 [Skins.resolveSkinId]。本类钉住四条不变式：
 *  1. 结果永远是**已安装**的 id（`in SkinRegistry.installed()`）；
 *  2. 装不上的显式值退回「上次选过的」（不是覆盖成 material —— 用户没让改偏好）；
 *  3. 空 / 空白 / null = 没给，读偏好；
 *  4. 测试跑在内存偏好上（P1-3），不碰开发机的真 prefs。
 */
class SkinsBootTest : IsolatedPreferencesTest() {

    @BeforeTest
    fun ensureBuiltInsInstalled() {
        // 本类只读注册表；装一次（幂等），免得依赖别的类的执行顺序。
        Skins.installBuiltIns()
    }

    // ------------------------------------------------------------ P1-3 · 隔离

    @Test
    fun preferencesAreInMemoryDuringDesktopTests() {
        assertTrue(
            Preferences.store is InMemoryPreferenceStore,
            "P1-3：桌面测试必须换掉真 prefs（java.util.prefs 的用户节点），" +
                "否则断言会写进开发机；实际实现：${Preferences.store::class.simpleName}",
        )
    }

    // ------------------------------------------------------------ 出厂 / 记忆

    @Test
    fun noPreferenceMeansTheFactorySkin() {
        assertEquals(Skins.DEFAULT_SKIN, Skins.bootSkinId(), "没存过 = 出厂 material")
        assertEquals("material", Skins.DEFAULT_SKIN, "出厂皮肤的 id 不许悄悄换")
    }

    @Test
    fun rememberedInstalledSkinIsRestored() {
        AppearancePrefs.storeSkin("miuix")
        assertEquals("miuix", Skins.bootSkinId(), "上次选过、且装得上 → 原样恢复")
    }

    @Test
    fun staleGlassPreferenceConvergesInsteadOfCrashing() {
        // I2：glass 本轮**故意**没注册（不再拿 material 换 id 冒充）。
        AppearancePrefs.storeSkin("glass")
        assertEquals(
            Skins.DEFAULT_SKIN,
            Skins.bootSkinId(),
            "老版本存过 glass 的用户不该因为换个版本就起不来",
        )
    }

    @Test
    fun unknownStoredIdConvergesToFactory() {
        AppearancePrefs.storeSkin("  ")
        assertEquals(Skins.DEFAULT_SKIN, Skins.bootSkinId(), "空白偏好 = 没存过")
        AppearancePrefs.storeSkin("MATERIAL")
        assertEquals(Skins.DEFAULT_SKIN, Skins.bootSkinId(), "id 大小写敏感，不猜近似值")
    }

    // ------------------------------------------------------------ 显式旗标收敛

    @Test
    fun installedFlagIdsAreAdoptedAndTrimmed() {
        assertEquals("miuix", Skins.resolveSkinId("miuix"))
        assertEquals("miuix", Skins.resolveSkinId("  miuix  "), "命令行/环境变量的空白不该改变含义")
        assertEquals("material", Skins.resolveSkinId("material"))
    }

    @Test
    fun unusableFlagFallsBackToTheRememberedSkinNotToACrash() {
        AppearancePrefs.storeSkin("miuix")
        assertEquals("miuix", Skins.resolveSkinId("glass"), "--skin=glass 不该崩；退回上次选过的")
        assertEquals("miuix", Skins.resolveSkinId("nope"), "打错的 id 同样只是「没生效」")
        assertEquals("miuix", Skins.resolveSkinId(""), "空值 = 没给，读偏好")
        assertEquals("miuix", Skins.resolveSkinId(null))
        assertEquals("miuix", AppearancePrefs.skinId(), "退回不等于替用户改偏好")
    }

    @Test
    fun everyResolvedIdIsActuallyInstalled() {
        val samples = listOf(null, "", "   ", "material", "miuix", "glass", "nope", "MATERIAL", "MIUIX")
        for (requested in samples) {
            val id = Skins.resolveSkinId(requested)
            assertTrue(
                id in SkinRegistry.installed(),
                "resolveSkinId(${requested?.let { "'$it'" }}) = '$id' 没装 —— " +
                    "这个值进 SkinRegistry.get 就是组合期崩溃",
            )
        }
    }
}
