package vade.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import vade.ui.data.FakeSessionSource
import vade.ui.destinations.Destination
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.LocalSkin
import vade.ui.skin.material.MaterialSkin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 目的地切换必须**每一次**都重画内容区（g-first-run-fix 的回归钉子）。
 *
 * ## 为什么需要这个文件
 *
 * `AppShellAdapterWiringTest.theFixtureShellStillRendersFixtureContentOnEveryDestination`
 * 只走了 `Me → Capabilities` 一次跳转，抓到的是症状；真正的病灶是**分发器这个
 * recompose scope 从此不再响应 `state.dest` 的写入**。病灶一旦成立，`waitForIdle()`
 * 会「成功」返回（没有待办重组），于是任何一次跳转之后的断言都只能靠内容标记来辨认，
 * 而且**第一次跳转之后必须再跳几次**才能把「只丢一次失效」与「作用域彻底坏掉」区分开。
 *
 * 所以这里做两件事：
 *  1. 连跳五次（含一次回到访问过的目的地），每一跳都断言**该目的地自己的内容标记**在屏上；
 *  2. 断言上一屏的标记**消失**——导航说到了、内容还留在旧页面，正是本回归的原貌。
 *
 * 标记都取「只有那一屏会画的字符串」（fixture 路径：会话行标题、活动页的空态分节、
 * 能力页的示例树入口、设置页的设备与通知分节）。只做正向断言，所以别处偶然的同名
 * 文案不会造成假红。
 */
@OptIn(ExperimentalTestApi::class)
class AppShellDestinationDispatchTest {

    private fun state() = UiState(
        initialBar = BottomBarStyle.Floating,
        initialSkinId = "material",
        initialDest = Destination.Sessions,
        source = FakeSessionSource(),
        loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    @Test
    fun everyDestinationSwitchRepaintsTheContentArea() = runComposeUiTest {
        val ui = state()
        setContent {
            CompositionLocalProvider(LocalSkin provides MaterialSkin()) {
                // 与失败用例同构的宽度：≥840dp 走 rail + 双栏，DestinationHost 的
                // twoPane 分支（本回归的现场）。
                Box(Modifier.size(1000.dp, 900.dp)) { AppShell(ui) }
            }
        }

        waitUntil(timeoutMillis = 10_000) { onScreen(SESSIONS_MARKER) }

        // 五跳：每一跳都要求内容区跟上导航，而不是只更新导航。
        for ((dest, marker) in HOPS) {
            runOnIdle { ui.select(dest) }
            waitUntil(timeoutMillis = 5_000) {
                onScreen(marker)
            }
        }

        // 最后一跳的现场：从设置页跳到能力页。分发器坏掉时屏上留的正是设置页。
        assertTrue(
            !onScreen(PROFILE_MARKER),
            "已经选到能力页，设置页的内容还在屏上——内容区渲染了上一帧的旧页面",
        )
    }

    private fun androidx.compose.ui.test.ComposeUiTest.onScreen(text: String): Boolean =
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        /** fixture 会话行标题：只有会话列表画。 */
        const val SESSIONS_MARKER = "RFC-0004 附件与多模态评审"

        /** 活动页的空态分节标题：只有活动页画。 */
        const val ACTIVITY_MARKER = "后台任务 · 子代理"

        /** 能力页示例树（`ModulePageSources.demo()`）的入口：只有能力页画。 */
        const val CAPABILITIES_MARKER = "模块页面"

        /** 设置页（我的）的分节标题：只有设置页画。 */
        const val PROFILE_MARKER = "设备与通知"

        /**
         * 访问顺序刻意包含「回跳」：会话 → 活动 → 能力 → 我的 → 会话 → 能力。
         * 只坏一次的实现会在第 2 跳之后开始全错；彻底坏掉的分发器第一跳就错。
         */
        val HOPS = listOf(
            Destination.Activity to ACTIVITY_MARKER,
            Destination.Capabilities to CAPABILITIES_MARKER,
            Destination.Me to PROFILE_MARKER,
            Destination.Sessions to SESSIONS_MARKER,
            Destination.Capabilities to CAPABILITIES_MARKER,
        )
    }
}
