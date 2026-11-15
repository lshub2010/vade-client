package vade.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * g-dark-follow · commonMain 平台 API 守卫（本仓红线的自动化形态）：
 *
 * 剥掉注释与字符串字面量后，`commonMain` 的每个 .kt 文件不得出现
 * `java.*` / `javax.*` / `android.*`（平台包坐标）。平台能力一律走 expect/actual
 * ——本批新增的 `ThemeMode.kt`（expect `systemDarkTheme`）与三端 actual 是唯一新缝。
 *
 * 两个已知的假阳性源必须剥掉，否则守卫绿着失效的反面——红着瞎叫：
 *
 *  1. **注释**：基线注释里本来就有「android.graphics.BlurMaskFilter」「无
 *     java.time」「java.security.MessageDigest is JVM-only」这类说明文字
 *     （HaloBlur.kt / ModelOption.kt / ModulePageBindings.kt / BlobUploaderReal.kt）；
 *  2. **字符串字面量**：用户可见文案与测试名里可能出现平台词。
 *
 * `androidx.compose.*` 是 Compose Multiplatform 的公共坐标，不在禁止列——
 * `\bandroid\.` 不会匹配 `androidx.`（android 后面是 x 不是点）。
 * `isSystemInDarkTheme` 是 androidx 坐标，同样天然不在命中面。
 */
class CommonMainPlatformGuardTest {

    private val banned = Regex("""\b(?:java|javax|android)\.""")

    @Test
    fun commonMainHasNoPlatformPackagesOutsideCommentsAndStrings() {
        val root = File("src/commonMain/kotlin")
        assertTrue(root.isDirectory, "test must run from the composeApp module dir, got ${File(".").absolutePath}")
        val offenders = mutableListOf<String>()
        var scanned = 0
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            scanned++
            val stripped = stripCommentsAndStrings(file.readText())
            for (m in banned.findAll(stripped)) {
                val line = stripped.take(m.range.first).count { it == '\n' } + 1
                val context = stripped
                    .substring(maxOf(0, m.range.first - 40), (m.range.last + 1).coerceAtMost(stripped.length))
                    .replace('\n', ' ')
                offenders += "${file.relativeTo(File(".")).path}:$line …$context…"
            }
        }
        assertEquals(
            expected = 0,
            actual = offenders.size,
            message = "commonMain 出现平台包（剥注释/字符串后）——平台能力必须走 expect/actual：$offenders",
        )
        assertTrue(scanned > 100, "扫描域不能退化（应覆盖整个 commonMain，实际扫了 $scanned 个文件）")
    }

    /**
     * 手动覆盖的默认值钉在源码上：对象级初值没法在运行时重取（测试顺序会污染它），
     * 与 `MaterialImportGuardTest` 的文件钉法同一形态。
     */
    @Test
    fun themeOverrideDefaultsToFollowSystem() {
        val src = File("src/commonMain/kotlin/vade/ui/skin/ThemeMode.kt").readText()
        assertTrue(
            src.contains("var override by mutableStateOf(ThemeMode.FollowSystem)"),
            "手动覆盖的默认值必须保持「跟随系统」——这是本批的需求默认态",
        )
    }

    /**
     * Kotlin 注释/字符串剥离（块注释按 Kotlin 语义**可嵌套**）。守卫自身先过这一关，
     * 才有资格谈「扫描域里没有平台 API」（skill §十四：不剥注释的守卫会把自己扫红）。
     */
    private fun stripCommentsAndStrings(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        var blockDepth = 0
        while (i < src.length) {
            val c = src[i]
            val n = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                blockDepth > 0 -> when {
                    c == '*' && n == '/' -> {
                        blockDepth--
                        i += 2
                        out.append(' ')
                    }
                    c == '/' && n == '*' -> {
                        blockDepth++
                        i += 2
                    }
                    else -> i++
                }
                c == '/' && n == '*' -> {
                    blockDepth = 1
                    i += 2
                }
                c == '/' && n == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                    out.append('\n')
                }
                c == '"' && src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3)
                    i = if (end < 0) src.length else end + 3
                    out.append("\"RS\"")
                }
                c == '"' -> {
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i++
                    out.append("\"S\"")
                }
                c == '\'' -> {
                    i++
                    while (i < src.length && src[i] != '\'') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i++
                    out.append("'C'")
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }
}
