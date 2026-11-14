package vade.ui.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ui/modules` 回包的解码契约（C-05 1.6.0 / RFC-0015）。
 *
 * 这些断言全部照着 `contracts/vade/schema/rpc.json` 的字段串写，重点钉四处**容易按直觉写错**
 * 的地方：页面的键是 `id`（不是 `page_id`）、`display_name`/`version` 可空、`icon`/`entry`
 * 有枚举默认值、`requires` 可能缺。
 */
class UiModulesCodecTest {

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    private val fullFrame = """
        {
          "revision": 7,
          "modules": [
            {
              "module_id": "com.example.echo",
              "instance_id": "main",
              "display_name": "回声模块",
              "version": "1.0.0",
              "online": true,
              "pages": [
                {
                  "id": "p1",
                  "title": "回声",
                  "icon": "list",
                  "entry": "capabilities",
                  "version": 1,
                  "requires": ["session"],
                  "refresh_s": 30
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun aFullFrameDecodesFieldByField() {
        val r = UiModulesResult.fromJson(obj(fullFrame))
        assertEquals(7L, r.revision)
        assertEquals(1, r.modules.size)

        val m = r.modules[0]
        assertEquals("com.example.echo", m.moduleId)
        assertEquals("main", m.instanceId)
        assertEquals("回声模块", m.displayName)
        assertEquals("1.0.0", m.version)
        assertTrue(m.online)

        val p = m.pages[0]
        assertEquals("p1", p.id)
        assertEquals("回声", p.title)
        assertEquals("list", p.icon)
        assertEquals("capabilities", p.entry)
        assertEquals(1, p.version)
        assertEquals(listOf("session"), p.requires)
        assertEquals(30, p.refreshS)
    }

    /** C-03 里 `display_name` 是可选的、`version` 可能为 null → 两者都必须能解成 null。 */
    @Test
    fun nullableDisplayNameAndVersionStayNull() {
        val r = UiModulesResult.fromJson(
            obj("""{"revision":1,"modules":[{"module_id":"m","instance_id":"i",
                     "display_name":null,"version":null,"online":false,"pages":[]}]}"""),
        )
        assertNull(r.modules[0].displayName)
        assertNull(r.modules[0].version)
        assertEquals(0, r.modules[0].pages.size)
    }

    /**
     * **陷阱**：契约的页面键是 `id`（= C-03 `$defs.page.id`），不是 `page_id`。
     * 一个只带 `page_id` 的帧必须解出**空 id** —— 证明我们读的是 `id`，而不是碰巧两者都读。
     */
    @Test
    fun thePageKeyIsIdNotPageId() {
        val r = UiModulesResult.fromJson(
            obj("""{"revision":1,"modules":[{"module_id":"m","instance_id":"i","online":true,
                     "pages":[{"page_id":"WRONG","title":"t","version":1}]}]}"""),
        )
        assertEquals("", r.modules[0].pages[0].id, "页面键必须是 id；读到 page_id 说明字段名写错了")
    }

    /** `icon` / `entry` 的契约默认值是 `none` / `auto`，缺键时要落到默认而不是空串。 */
    @Test
    fun iconAndEntryFallBackToTheirDeclaredDefaults() {
        val r = UiModulesResult.fromJson(
            obj("""{"revision":1,"modules":[{"module_id":"m","instance_id":"i","online":true,
                     "pages":[{"id":"p","title":"t","version":1}]}]}"""),
        )
        val p = r.modules[0].pages[0]
        assertEquals("none", p.icon)
        assertEquals("auto", p.entry)
        assertEquals(emptyList(), p.requires)
        assertEquals(0, p.refreshS)
    }

    /** 空清单是**合法答案**（core 报告「一个模块都没有」），不是错误、不是 null。 */
    @Test
    fun anEmptyModuleListIsALegalAnswer() {
        val r = UiModulesResult.fromJson(obj("""{"revision":3,"modules":[]}"""))
        assertEquals(3L, r.revision)
        assertEquals(0, r.modules.size)
    }

    /** 缺 `modules` 键时按空清单处理，不抛；`revision` 缺失落 0。 */
    @Test
    fun aFrameWithoutModulesOrRevisionDoesNotThrow() {
        val r = UiModulesResult.fromJson(obj("""{}"""))
        assertEquals(0L, r.revision)
        assertEquals(0, r.modules.size)
    }
}
