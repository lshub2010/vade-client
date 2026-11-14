package vade.ui.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import vade.ui.icon.MetaIcons

/**
 * 测试①：客户端图标词表必须与服务端白名单**完全一致**（数量 + 集合 + 双向映射）。
 *
 * 39 这个数与全部 39 个名字**硬编码拷贝自服务端源码**
 * `core/vade/session/manager.py` L77–85 的 `ICON_NAMES` frozenset（RFC-0010 /
 * C-05 1.3.0，Tabler 白名单）。服务端扩名单时这里先红 —— 这是有意的防回退：
 * 客户端选择器多给一个服务端不收的名字，保存分组就会 INVALID_ARGUMENT。
 * （每个名字对应的 Tabler getter 已在 icons-tabler-outline-cmp-jvm-2.2.1.jar 里
 * 用 javap 核实存在；连接字符 `file-text` / `terminal-2` 为服务端原样拼写。）
 */
class MetaIconWhitelistTest {

    private val serverWhitelist = setOf(
        "archive", "book", "bookmark", "brain", "briefcase", "bulb", "calendar", "chart-bar",
        "chat", "clock", "cloud", "code", "database", "file", "file-text", "flag", "folder",
        "heart", "home", "list", "lock", "mail", "map", "music", "note", "pencil", "phone",
        "photo", "robot", "rocket", "search", "settings", "star", "tag", "terminal-2", "trash",
        "video", "wallet", "world",
    )

    @Test
    fun whitelistCountMatchesServer39() {
        assertEquals(
            expected = 39,
            actual = MetaIcons.names.size,
            message = "MetaIcons.names 必须与服务端 ICON_NAMES 的 39 个一致（manager.py L77–85）",
        )
    }

    @Test
    fun whitelistSetEqualsServerWhitelistExactly() {
        val client = MetaIcons.names.toSet()
        assertEquals(
            expected = serverWhitelist,
            actual = client,
            message = "图标名集合必须与服务端逐字一致（多给少给都会被服务端拒收）",
        )
        // 集合断言之上再钉数量相等，防止"一边多一边少但恰好都是 39"的巧合。
        assertEquals(serverWhitelist.size, client.size)
    }

    @Test
    fun noDuplicateNames() {
        assertEquals(MetaIcons.names.size, MetaIcons.names.toSet().size, "白名单名字不得重复")
    }

    @Test
    fun everyNameResolvesToAVector() {
        MetaIcons.names.forEach { name ->
            assertNotNull(MetaIcons[name], "白名单名 $name 必须解析到矢量（Tabler getter 缺失）")
        }
    }

    @Test
    fun vectorMapHasNoExtrasBeyondWhitelist() {
        assertEquals(
            expected = MetaIcons.names.toSet(),
            actual = MetaIcons.byName.keys,
            message = "MetaIcons.byName 只允许白名单内的 39 个键，一个不多（UI 不能送服务端不收的图标）",
        )
    }

    @Test
    fun unknownNameReturnsNull() {
        assertTrue(MetaIcons["not-in-whitelist"] == null, "白名单外的名字必须返回 null，不得编造图标")
        assertTrue(MetaIcons["File-Text"] == null, "名字大小写敏感（服务端 ICON_NAMES 是精确匹配）")
    }
}
