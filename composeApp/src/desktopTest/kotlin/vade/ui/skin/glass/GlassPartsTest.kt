package vade.ui.skin.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import vade.ui.skin.ApprovalRequest
import vade.ui.skin.AttachmentState
import vade.ui.skin.AttachmentView
import vade.ui.skin.BottomBarStyle
import vade.ui.skin.InputMode
import vade.ui.skin.LocalSkin
import vade.ui.skin.NavItem
import vade.ui.skin.Skin
import vade.ui.skin.SkinOrb
import vade.ui.skin.SkinRegistry
import vade.ui.skin.Skins
import vade.ui.skin.ToolCallView
import vade.ui.skin.ToolCardState

/**
 * 52-U3 · T2 的验收：7 个组件（`ListItem` / `Switch` / `BottomBar` / `ApprovalDialog` /
 * `AttachmentChip` / `ToolCallCard` / `SendOrb`）**真的画出了东西**，而且三态/按压这些
 * 状态不是靠注释声明的。
 *
 * 分三类断言，各有各的理由：
 *
 *  1. **屏上**（`runComposeUiTest` + 语义树）—— 一个皮肤最容易坏的方式是「实现了、但什么
 *     都没画」。7 个组件一次性组合，逐条认领各自的文案（列表行、弹层的审批文案、芯片的
 *     「已压缩，原图未保留」、工具卡的参数/结果、底栏三形态的标签）。
 *  2. **状态**（语义树里的 `ToggleableState` / `Disabled`）—— `Switch` 的三态如果只靠
 *     `alpha` 表达，读屏软件看到的是三个「开关」，看不出哪个禁用。`toggleable(Role.Switch)`
 *     让这条可断言，也让无障碍正确。
 *  3. **源码守卫** —— 7 个组件**没有一个**还在 `= base.Xxx(...)` 转发 material；`SendOrb`
 *     的按压缩放真的走 [orbPressScale] 与 `orb.pressDurationMs`（像素级缩放没有夹具，
 *     这一批只能把「接线」钉住，报告里也这么说）。
 *
 * **注册表是全进程单例**：`@BeforeTest` 注册 `glass`，`@AfterTest` 必须还原成出厂状态，
 * 否则 `SkinsBootTest.staleGlassPreferenceConvergesInsteadOfCrashing` 会在本类之后变红
 * （与 `GlassSkinTest` 同一处理，原因见那里）。
 */
@OptIn(ExperimentalTestApi::class)
class GlassPartsTest {

    @BeforeTest
    fun registerGlass() {
        GlassSkinEntry.dark = false
        GlassSkinEntry.register()
    }

    @AfterTest
    fun restoreRegistry() {
        SkinRegistry.clear()
        Skins.resetInstallFlagForTest()
        Skins.installBuiltIns()
        GlassSkinEntry.dark = false
    }

    // --- 数据 ---------------------------------------------------------------------

    private val navItems = listOf(
        NavItem(id = "sessions", label = "会话"),
        NavItem(id = "capabilities", label = "能力", badge = "2"),
        NavItem(id = "settings", label = "设置"),
    )

    private val approval = ApprovalRequest(
        id = "a1",
        toolName = "fs.write",
        arguments = "{\"path\":\"/tmp/report.md\",\"mode\":\"append\"}",
        risk = "high",
    )

    private val toolCall = ToolCallView(
        id = "t1",
        name = "read_file",
        args = "{\"path\":\"/etc/hosts\"}",
        result = "127.0.0.1 localhost",
        risk = "low",
    )

    /** 7 个组件一遍（底栏三形态、开关三态、芯片两态、球三态都进来）。 */
    @Composable
    private fun SevenComponents(skin: Skin) {
        Column(Modifier.width(560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 4 · ListItem —— 静止 / hover（测试里没有指针，等价于「未 hover」）/ 选中 / 禁用
            skin.ListItem(title = "会话一行", subtitle = "副标题一行", onClick = {})
            skin.ListItem(title = "被选中的行", selected = true, onClick = {})
            skin.ListItem(title = "禁用的一行", enabled = false, onClick = {})
            // 5 · Switch —— 开 / 关 / 禁用
            skin.Switch(checked = true, onCheckedChange = {})
            skin.Switch(checked = false, onCheckedChange = {})
            skin.Switch(checked = false, onCheckedChange = {}, enabled = false)
            // 6 · BottomBar —— 三形态
            skin.BottomBar(items = navItems, selected = 0, onSelect = {}, style = BottomBarStyle.Fixed)
            skin.BottomBar(items = navItems, selected = 1, onSelect = {}, style = BottomBarStyle.Floating)
            skin.BottomBar(items = navItems, selected = 2, onSelect = {}, style = BottomBarStyle.FloatingBlur)
            // 7 · ApprovalDialog —— 需要一个有界的容器，它自己 fillMaxSize
            Box(Modifier.size(360.dp, 420.dp)) {
                skin.ApprovalDialog(request = approval, remainingSec = 30, onDecide = { _, _ -> })
            }
            // 9 · AttachmentChip —— 成功 / 失败
            skin.AttachmentChip(
                attachment = AttachmentView(
                    fileId = "f1",
                    fileName = "photo.jpg",
                    mime = "image/jpeg",
                    state = AttachmentState.Uploaded,
                ),
                onRemove = {},
            )
            skin.AttachmentChip(
                attachment = AttachmentView(
                    fileId = "f2",
                    fileName = "broken.png",
                    mime = "image/png",
                    state = AttachmentState.Failed,
                    detail = "上传失败：连接中断",
                ),
                onRetry = {},
                onRemove = {},
            )
            // 10 · ToolCallCard —— 展开态（参数 + 结果都上屏）
            skin.ToolCallCard(call = toolCall, state = ToolCardState.Expanded, onToggle = {})
            // 12 · SendOrb —— 三态
            skin.SendOrb(mode = InputMode.Idle, onClick = {})
            skin.SendOrb(mode = InputMode.Composing, onClick = {})
            skin.SendOrb(mode = InputMode.Streaming, enabled = false, onClick = {})
        }
    }

    @Test
    fun sevenComponentsComposeWithoutABackdrop() {
        // 没有 GlassScope = 采不到背景 = capability 降到 TintStroke。这既是「三端可组合」的
        // 最小证明，也是降级路径的正面用例：玻璃层仍然画出内容。
        val skin: Skin = GlassSkin()
        var seen: List<String> = emptyList()
        runComposeUiTest {
            setContent { CompositionLocalProvider(LocalSkin provides skin) { SevenComponents(skin) } }
            waitForIdle()
            seen = screenTexts()
        }

        listOf(
            "会话一行", "副标题一行", "被选中的行", "禁用的一行",
            "会话", "能力", "设置",
            "需要批准", "fs.write", "仅本次", "本会话", "拒绝", "剩余 30s · 超时按拒绝处理",
            "photo.jpg", "已上传 · 已压缩，原图未保留",
            "broken.png", "上传失败：连接中断",
            "read_file", "工具", "已完成", "参数", "ARGS", "结果", "RESULT",
        ).forEach { expected ->
            assertTrue(
                seen.any { it == expected },
                "玻璃组件没把「$expected」画上屏 —— 空面板比不实现更糟。整屏文本：\n${seen.report()}",
            )
        }
    }

    @Test
    fun darkSkinComposesTheSameComponents() {
        val skin: Skin = GlassSkin(dark = true)
        var seen: List<String> = emptyList()
        runComposeUiTest {
            setContent { CompositionLocalProvider(LocalSkin provides skin) { SevenComponents(skin) } }
            waitForIdle()
            seen = screenTexts()
        }
        listOf("会话一行", "fs.write", "photo.jpg", "read_file", "仅本次").forEach { expected ->
            assertTrue(
                seen.any { it == expected },
                "暗色 token 下「$expected」丢了 —— 暗色路径不能只是能编译：\n${seen.report()}",
            )
        }
    }

    // --- Switch 三态 ---------------------------------------------------------------

    /**
     * 三态必须是**语义**，不是三个一模一样的长相：
     *  - `ToggleableState.On` / `Off` 各一个；
     *  - 禁用那个同时带 `Disabled`，且 `enabled=false` 时 `toggleable` 不派发点击。
     */
    @Test
    fun switchExposesOnOffAndDisabledStates() {
        val skin: Skin = GlassSkin()
        var toggles = 0
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSkin provides skin) {
                    Column {
                        skin.Switch(checked = true, onCheckedChange = { toggles++ })
                        skin.Switch(checked = false, onCheckedChange = { toggles++ })
                        skin.Switch(checked = false, onCheckedChange = { toggles++ }, enabled = false)
                    }
                }
            }
            waitForIdle()
            val states = toggleableStates()
            assertEquals(3, states.size, "三个开关必须都是可切换语义节点：$states")
            assertTrue(states.any { it.state == ToggleableState.On }, "缺「开」态：$states")
            assertTrue(
                states.count { it.state == ToggleableState.Off } == 2,
                "缺「关」态（未禁用 + 禁用两个）：$states",
            )
            assertTrue(states.any { it.disabled }, "禁用态必须真的带 Disabled 语义：$states")
            assertEquals(1, states.count { it.disabled }, "只有第三个开关是禁用的：$states")
        }
        assertEquals(0, toggles, "没有点击就不许有回调")
    }

    // --- SendOrb 点击 / 按压 --------------------------------------------------------

    @Test
    fun sendOrbFollowsEnabledExceptStreaming() {
        val skin: Skin = GlassSkin()
        var clicks = 0
        var mode by mutableStateOf(InputMode.Idle)
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalSkin provides skin) {
                    // 与 material/miuix 同一语义：enabled=false 时 Idle 不响应；
                    // Streaming 恒可点（中断生成永远可用）。空球可点由页面传 enabled=true 决定
                    // （ChatThread 恒传 true，草稿为空时 submit() 自己 return）。
                    Box(Modifier.size(80.dp)) {
                        skin.SendOrb(mode = mode, enabled = false, onClick = { clicks++ })
                    }
                }
            }
            waitForIdle()
            onNodeWithContentDescription("发送消息").assertIsNotEnabled()
            // clickable(enabled=false) 仍会留一个命中节点（只是标记 Disabled），所以先断言
            // 语义禁用，再真点一次确认回调不触发。
            runCatching { onNodeWithContentDescription("发送消息").performClick() }
            assertEquals(0, clicks, "enabled=false 的 Idle 球不该还能点")

            runOnIdle { mode = InputMode.Streaming }
            waitForIdle()
            onNodeWithContentDescription("停止生成").performClick()
            assertEquals(1, clicks, "流式停止不受 enabled 影响 —— 中断生成永远可用（§2）")
        }
    }

    /**
     * 按压缩放：`pressed → orb.pressScale`，否则 1f。
     *
     * 缩放画在 `graphicsLayer` 上，不改布局尺寸，桌面测试拿不到像素；所以这里钉住**接线**
     * （纯函数 + 源码里的调用形态），并在 `batch-results/u3b-components.md` 里写清楚这一点。
     */
    @Test
    fun sendOrbPressScaleIsWiredToTheOrbToken() {
        assertEquals(1f, orbPressScale(pressed = false, pressScale = 0.92f), "未按下必须是 1f")
        assertEquals(0.92f, orbPressScale(pressed = true, pressScale = 0.92f), "按下必须是 pressScale")
        assertEquals(
            0.92f,
            SkinOrb().pressScale,
            "§2/§7 的按压比是 0.92 —— 皮肤不许自带第二个数",
        )

        val parts = codeOf(File("src/commonMain/kotlin/vade/ui/skin/glass/GlassParts.kt"))
        assertEquals(
            1,
            count(parts, """targetValue = orbPressScale\(pressed, orb\.pressScale\)"""),
            "SendOrb 的 animateFloatAsState 必须走 orbPressScale(pressed, orb.pressScale)",
        )
        assertEquals(
            1,
            count(parts, """collectIsPressedAsState\(\)"""),
            "按压来源必须是 pressed 交互态（不是自造的手势）",
        )
        assertEquals(
            1,
            count(parts, """animationSpec = tween\(durationMillis = orb\.pressDurationMs, easing = motion\.easing\)"""),
            "按压时长/曲线必须继续取 orb.pressDurationMs + motion.easing（本批不动动效门）",
        )
    }

    // --- 源码守卫：7 个组件不许再转发 -----------------------------------------------

    /**
     * 本批的**交付定义**：7 个组件不再是 `= base.Xxx(...)`。
     *
     * 这条守卫是给「以后有人为了省事把它们改回 material」准备的：回退会让 CMake 一样过、
     * 单测一样绿（material 也画得出来），只有皮肤不再玻璃 —— 那种回退必须红。
     * 十二个里恰好只剩 `TextField` 一条转发（见 `GlassSkin` 类头 KDoc）。
     */
    @Test
    fun noComponentForwardsToMaterialAnymore() {
        val skinSrc = codeOf(File("src/commonMain/kotlin/vade/ui/skin/glass/GlassSkin.kt"))
        listOf("ListItem", "Switch", "BottomBar", "ApprovalDialog", "AttachmentChip", "ToolCallCard", "SendOrb")
            .forEach { name ->
                assertEquals(
                    0,
                    count(skinSrc, """= base\.$name\("""),
                    "$name 又变回 `= base.$name(...)` 了 —— 玻璃皮肤转发 material 就等于没做（I2）",
                )
            }
        assertEquals(
            1,
            count(skinSrc, """= base\.TextField\("""),
            "只剩 TextField 一条转发；多一条说明有人偷偷把组件退回 material",
        )
    }

    // --- harness ------------------------------------------------------------------

    private data class ToggleState(val state: ToggleableState, val disabled: Boolean)

    /** 语义树里所有可切换节点的（状态, 是否禁用）。 */
    private fun ComposeUiTest.toggleableStates(): List<ToggleState> {
        val out = mutableListOf<ToggleState>()

        fun walk(node: SemanticsNode) {
            val state = node.config.getOrNull(SemanticsProperties.ToggleableState)
            if (state != null) {
                out += ToggleState(state, node.config.getOrNull(SemanticsProperties.Disabled) != null)
            }
            node.children.forEach { child -> walk(child) }
        }

        walk(onRoot().fetchSemanticsNode())
        return out
    }

    /** 语义树里所有文本，按遍历顺序。 */
    private fun ComposeUiTest.screenTexts(): List<String> {
        val out = mutableListOf<String>()

        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { out += it.text }
            node.children.forEach { child -> walk(child) }
        }

        walk(onRoot().fetchSemanticsNode())
        return out
    }

    private fun List<String>.report(): String = joinToString("\n") { "    · $it" }

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun codeOf(file: File): String {
        assertTrue(file.isFile, "guard target missing: ${file.path}")
        val noBlock = file.readText().replace(Regex("(?s)/\\*.*?\\*/"), "")
        return noBlock.split('\n').joinToString("\n") { it.substringBefore("//") }
    }

    private fun count(code: String, pattern: String): Int =
        Regex(pattern).findAll(code).count()
}
