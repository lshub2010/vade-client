package vade.ui.testutil

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import vade.ui.skin.InMemoryPreferenceStore
import vade.ui.skin.PreferenceStore
import vade.ui.skin.Preferences

/**
 * P1-3 · 桌面测试一律跑在内存偏好上。
 *
 * `Preferences.store` 的出厂值是 `platformPreferenceStore()`；桌面上那是
 * `java.util.prefs` 的用户级真实节点（`vade-ui/client`）。以前测试直构 `App()` /
 * `ProfileScreen()`，点一下设置页的皮肤 / 底栏 / 主题 chip 就把**断言写进了这台开发机**，
 * 而且上一次跑留下的值会让下一次的「出厂外观」断言先红后绿 —— 是脏读，不是抖动。
 *
 * 继承本类的测试类在**每个** @Test 前换一份新的 [InMemoryPreferenceStore]，@AfterTest
 * 再把进入测试时看到的实现还回去（同一个 JVM 里的其它类不受影响）。断言「真的写了哪几对
 * 键值」用 `(Preferences.store as InMemoryPreferenceStore).snapshot()`，不读回真机。
 *
 * 限制：对象级初值（[vade.ui.skin.ThemeController] 的 `init` 里恢复主题）发生在类加载时，
 * 早于 @BeforeTest —— 需要确定性主题基线的测试自己显式赋值 `ThemeController.override`。
 */
abstract class IsolatedPreferencesTest {

    /** 进入测试时的实现；@AfterTest 原样放回。 */
    private var saved: PreferenceStore? = null

    @BeforeTest
    fun useInMemoryPreferences() {
        saved = Preferences.store
        Preferences.store = InMemoryPreferenceStore()
    }

    @AfterTest
    fun restorePreferences() {
        saved?.let { Preferences.store = it }
        saved = null
    }
}
