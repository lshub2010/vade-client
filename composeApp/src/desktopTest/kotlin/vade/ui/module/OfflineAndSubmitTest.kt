package vade.ui.module

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §5.4 / §6.3 的动作可用性：模块离线 → **全部动作禁用**（不白屏，保留结构）；
 * `ui/submit` 还要求活跃会话（无会话 → 禁用并提示，不静默丢弃）。TextField 是本地输入，
 * 不是动作，不受离线禁用。
 */
class OfflineAndSubmitTest {

    private val json = Json

    private fun env(online: Boolean, hasSession: Boolean = false, inputs: Map<String, String> = emptyMap()) =
        PageEnv(state = null, moduleOnline = online, moduleVersion = "1.0", inputs = inputs, hasSession = hasSession)

    private val navigate = PageAction.Navigate(NavigateTarget.ModulePage("p1"))
    private val toolCall = PageAction.ToolCall("download.refresh", json.parseToJsonElement("{}").jsonObject, null)
    private val submit = PageAction.UiSubmit(listOf(SubmitPart(SubmitText.Literal("hi"))))

    @Test
    fun offlineDisablesEveryActionKind() {
        val offline = env(online = false)
        assertFalse(ModulePageResolver.actionEnabled(navigate, offline))
        assertFalse(ModulePageResolver.actionEnabled(toolCall, offline))
        assertFalse(ModulePageResolver.actionEnabled(submit, offline))
    }

    @Test
    fun onlineModuleEnablesNavigateAndToolCall() {
        val online = env(online = true)
        assertTrue(ModulePageResolver.actionEnabled(navigate, online))
        assertTrue(ModulePageResolver.actionEnabled(toolCall, online))
    }

    @Test
    fun uiSubmitRequiresActiveSession() {
        val onlineNoSession = env(online = true, hasSession = false)
        assertFalse(ModulePageResolver.actionEnabled(submit, onlineNoSession), "无会话 → 动作禁用并提示")
        val onlineWithSession = env(online = true, hasSession = true)
        assertTrue(ModulePageResolver.actionEnabled(submit, onlineWithSession))
    }

    @Test
    fun offlineSourceStillResolvesStructureWithEmptyBindings() {
        // 离线：保留最后一次成功渲染的结构，仅绑定值置空（§5.4）——这里验证解析
        // 在 state=null 时仍然产出可渲染的树，不抛异常。
        val page = ModulePageParser.parse(
            json.parseToJsonElement(
                """{"type":"column","props":{},"children":[""" +
                    """{"type":"text","data_ref":"page.state.queue_len","props":{}},""" +
                    """{"type":"component","component":"Button","props":{"text":"刷新"},""" +
                    """"on":{"click":{"kind":"tools/call","tool":"refresh","args":{}}}}]}""",
            ),
            setOf("p1"),
        )
        val ok = assertOk(page)
        val resolved = ModulePageResolver.resolve(ok.root, env(online = false), PageResolveReport())
        val column = resolved as ResolvedNode.Column
        assertTrue(column.children.size == 2, "结构照常渲染")
        val text = column.children[0] as ResolvedNode.TextLeaf
        assertTrue(text.text.isEmpty(), "绑定值置空")
        val button = column.children[1] as ResolvedNode.Component
        val payload = button.payload as ResolvedComponent.Button
        assertFalse(payload.enabled, "按钮 enabled=false（离线禁用动作）")
    }

    private fun assertOk(result: ParseResult): ParseResult.Ok {
        kotlin.test.assertIs<ParseResult.Ok>(result)
        return result as ParseResult.Ok
    }
}
