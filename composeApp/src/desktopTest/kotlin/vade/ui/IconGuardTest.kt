package vade.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import vade.ui.icon.GlyphLiteralScan

/**
 * w2-icons 防回退守卫（docs/visual-language.md §3：图标必须是矢量 —— 不许字符字形、
 * 不许汉字当图标、不许 emoji）。
 *
 * 这条守卫**两次失效，两次都还是绿的**，所以现在的写法刻意做了两件事：
 *  - **扫描域**：整个 `commonMain/kotlin`（当前只有 `vade/ui` 一棵子树），不排除任何子目录。
 *    第一次失效就是把 `icon|shell|skin` 排除掉了 —— 被排除的目录等于没有保护。
 *  - **匹配形态**：不再手写「一条正则匹配 `Text("X")`」。字面量由
 *    [GlyphLiteralScan] 词法扫描摘出（剔注释、按行号定位），再由运行期拼出来的
 *    `^[<词表>]{1,4}$` 判类，于是 `skin.Button("×")`、
 *    `glyph = if (..) "▸" else "◂"`、`text = item.glyph ?: "•"`、跨行 `Text(\n "←"\n)`
 *    一视同仁。匹配器自身有独立自测（`GlyphLiteralScanTest`），把它改弱必红。
 *
 * 白名单（[allowed]）只在「本棒无权改、且另一棒正在改该文件」时使用，**必须写理由**；
 * 条目若对不上任何 offender 会被报成 stale，强制删除（白名单不许永久静默）。
 */
class IconGuardTest {

    /** 扫描域根：全部 commonMain 源码。 */
    private fun commonMainDir() = File("src/commonMain/kotlin")

    /** Module dir: Gradle runs desktopTest with the composeApp module as working dir. */
    private fun uiDir() = File(commonMainDir(), "vade/ui")

    /**
     * 行内白名单。
     *
     * g-glyph-clear：4 处符号存量（`AppShell` 的 `▸`/`◂`/`×`、`FloatingBottomBar` 的 `•`）
     * 与 4 处汉字图标（`Destination.glyph` 的 `话`/`活`/`能`/`我`）**都已从源码删除**，
     * 所以符号那条清单是空的。
     *
     * 剩下的 6 条全是**单汉字字面量但确实不是图标**的展示页/探针文案：每条写清它渲在
     * 哪里、为什么不是图标、以及什么时候该复查。删掉文案或行号漂移 → 报 stale → 强制清理。
     */
    private val allowed: List<GlyphLiteralScan.Allow> = listOf(
        // miuix 展示页：四种 BtnStyle 的演示按钮下方 11sp 的**样式名缩写**（主/次/危/文），
        // 不是图标也不是按钮内容——按钮本身的文字是「发送/添加/删除/更多」（多字，不在扫描范围）。
        GlyphLiteralScan.Allow(
            "vade/ui/skin/miuix/MiuixShowcase.kt", 208, "主",
            "演示页按钮下方的样式名缩写（主按钮），11sp 文字标签；g-glyph-clear 已核，不渲染在任何图标槽",
        ),
        GlyphLiteralScan.Allow(
            "vade/ui/skin/miuix/MiuixShowcase.kt", 209, "次",
            "演示页按钮下方的样式名缩写（次按钮），11sp 文字标签；同上",
        ),
        GlyphLiteralScan.Allow(
            "vade/ui/skin/miuix/MiuixShowcase.kt", 210, "危",
            "演示页按钮下方的样式名缩写（危险按钮），11sp 文字标签；同上",
        ),
        GlyphLiteralScan.Allow(
            "vade/ui/skin/miuix/MiuixShowcase.kt", 211, "文",
            "演示页按钮下方的样式名缩写（文字按钮），11sp 文字标签；同上",
        ),
        // 同一行那枚 disabled 按钮的说明文字（"禁" = 禁用态）。
        GlyphLiteralScan.Allow(
            "vade/ui/skin/miuix/MiuixShowcase.kt", 220, "禁",
            "禁用态演示按钮的说明文字（禁），11sp 文字标签；g-glyph-clear 已核",
        ),
        // 动效探针的假会话行标题：`RealRow("好", meta = "2 分钟前")` 是一行**会话标题**，
        // 15sp 行首文字（和它上面那句「为什么」同一类），不是图标槽。
        GlyphLiteralScan.Allow(
            "vade/ui/demo/MotionProbe.kt", 312, "好",
            "探针夹具的会话标题（15sp 行首文字，隔壁行是「为什么」），演示用假数据；不是图标槽",
        ),
        // g-topbar-usage：用量副标题的量级后缀（「12.3万 tokens · 12 轮」的单位词），是
        // 格式化纯函数 scaledTokenUnit 的字符串拼装参数，渲染在顶栏副标题文字里，不是图标槽。
        GlyphLiteralScan.Allow(
            "vade/ui/shell/TopBarUsage.kt", 70, "万",
            "用量副标题的量级后缀（万 tokens 的单位词），格式化纯函数的拼装参数；不是图标槽",
        ),
    )

    @Test
    fun noCharacterIconTextLiterals() {
        val root = commonMainDir()
        assertTrue(root.isDirectory, "commonMain 不在预期位置：${root.absolutePath}")
        val result = GlyphLiteralScan.scan(root, allowed)
        assertTrue(
            result.staleAllows.isEmpty(),
            "白名单已失效（字面量改掉或行号漂移），必须删掉这些条目：${result.staleAllows}",
        )
        assertTrue(
            result.offenders.isEmpty(),
            buildString {
                appendLine("字符/汉字当图标回退（图标必须用 vade.ui.icon.VadeIcons + VadeIcon，docs/visual-language.md §3）：")
                result.offenders.forEach { appendLine("  " + it.render()) }
                appendLine("共 ${result.offenders.size} 处。")
                appendLine("若确是文字标签（展示页说明、按钮文案），在 IconGuardTest.allowed 追加并写理由：")
                result.offenders.forEach {
                    appendLine("    GlyphLiteralScan.Allow(\"${it.path}\", ${it.line}, \"${it.glyph}\", \"为什么它不是图标\"),")
                }
            },
        )
    }

    @Test
    fun businessCodeDoesNotImportTablerDirectly() {
        // The Tabler namespace may only appear inside vade/ui/icon (VadeIcons is the seam).
        // 扫描域同上面一样是整个 commonMain；这里排除 icon/ 是有意的落点，不是漏扫。
        val root = commonMainDir()
        val iconDir = File(uiDir(), "icon")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.startsWith(iconDir) }
            .filter { it.readText().contains("com.composables.icons.tabler") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
        assertTrue(
            offenders.isEmpty(),
            "业务代码不得直接 import Tabler：$offenders（必须经 vade.ui.icon.VadeIcons）",
        )
    }

    @Test
    fun destinationsCarryTablerIcons() {
        val file = File(uiDir(), "destinations/Destinations.kt")
        assertTrue(file.isFile, "Destinations.kt 不在预期位置：${file.path}")
        val text = file.readText()

        assertTrue(
            text.contains("val icon: ImageVector"),
            "Destination 必须声明 icon: ImageVector（w2-icons 把 glyph 换成矢量图标）",
        )

        // The four entries, in FR-8.77 order, mapped to the agreed semantic names.
        val expected = listOf(
            "Sessions" to "VadeIcons.Chat",
            "Activity" to "VadeIcons.Activity",
            "Capabilities" to "VadeIcons.Grid",
            "Me" to "VadeIcons.Person",
        )
        val missing = expected.filter { (entry, icon) ->
            !Regex("""$entry\s*\([^)]*$icon""").containsMatchIn(text)
        }
        assertTrue(
            missing.isEmpty(),
            "目的地图标映射缺失或不符：$missing（Chat/Activity/Grid/Person，FR-8.77 顺序）",
        )

        // g-glyph-clear: the legacy CJK glyph field (`话`/`活`/`能`/`我`) is deleted — its last
        // reader, `shell/NavRail`, draws [icon] now. Pin the deletion: re-adding a `glyph`
        // field here (with or without @Deprecated) is the exact regression this batch removed.
        assertTrue(
            !text.contains(Regex("""\bval glyph\b""")),
            "Destinations.glyph 必须保持删除——它就是四个导航汉字图标（话/活/能/我）的载体；" +
                "导航图标只走 icon: ImageVector + VadeIcon",
        )
    }

    @Test
    fun vadeIconsExposesAtLeast20Vectors() {
        val file = File(uiDir(), "icon/VadeIcons.kt")
        assertTrue(file.isFile, "VadeIcons.kt 不在预期位置：${file.path}")
        val count = Regex("""val \w+: ImageVector""").findAll(file.readText()).count()
        assertTrue(
            count >= 20,
            "VadeIcons 公开矢量不足 20（当前 $count）——图标词表被删减了",
        )
    }

    /**
     * 白名单本体，供 `GlyphClearTest.wholeTreeScanFindsOnlyTheAllowlistedDemoLabels` 复核
     * 「全仓 offender 恰好等于白名单那 6 条」时复用同一份——两份清单一定会漂，守卫就又有缝了。
     */
    internal fun allowList(): List<GlyphLiteralScan.Allow> = allowed
}
