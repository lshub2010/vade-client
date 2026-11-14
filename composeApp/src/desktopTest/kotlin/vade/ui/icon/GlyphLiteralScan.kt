package vade.ui.icon

import java.io.File

/**
 * 字符图标守卫的扫描器（`IconGuardTest` 的承重件）。
 *
 * 为什么不是「一条正则扫源码」：旧实现把**调用形态**写死在正则里
 * （`\bText\(\s*"X"` / `UiText\(\s*"X"`），只有「字面量紧贴左括号」这一种写法能命中。
 * 实测它在修前树上 26 处字符图标里只命中 1 处（`Text("×")`，而那个文件当时还在扫描域外），
 * 也就是说守卫一直是绿的。原因**不是**换行——`\s*` 本来就跨行——而是：
 *   - `TopBarButton("←")` / `skin.Button("×")`：别的调用名；
 *   - `glyph = if (open) "▸" else "◂"` / `text = if (e) "▾" else "▸"`：命名字参 + 表达式；
 *   - `text = item.glyph ?: "•"`：字面量前面还隔着 `?:`；
 *   - `listOf("↑" to BtnStyle.Primary, ...)`：裸字面量。
 *
 * 所以改成两步，两步都是纯函数，测试可以直接喂源码字符串：
 *   1. [literals]：词法扫一遍，剔掉行注释、块注释（Kotlin 块注释可嵌套）、字符字面量，
 *      交出每个字符串字面量（普通 + raw `"""`）及其**起始行号**；
 *   2. [isIconLiteral]：判类正则 `^[<VOCAB>]{1,4}$` 在**运行期**由 [VOCAB] 拼出来
 *      （不再用四引号 raw string 拼 pattern——那正是旧实现把尾引号写进 pattern 的地方），
 *      只认「整串都是图标字符」的字面量，所以长正文、键位提示、`"2048×1536"` 一律不误报。
 *
 * 扫描域 = 传入根目录下的全部 `*.kt`（调用方给 `src/commonMain/kotlin`，不排除任何子目录）。
 */
internal object GlyphLiteralScan {

    /** 禁用的单字形图标词表（shell 旧字符图标字母表，外加 × ◂ ▸ ⧗）。 */
    const val VOCAB: String = "↑↓←→▲▼■□●○◆＋…✕✓▾▸‹⌕✎•×◂▸⧗"

    /**
     * 判类正则：整串（去首尾空白后）只由 [VOCAB] 里的字符组成，长度 1..4。
     *
     * 运行期拼接是本件的关键：pattern 的构造不经过任何字面量引号，`VOCAB` 的改动会直接
     * 反映到 pattern 上，且测试能拿 [VOCAB] 复算出同一个串来比对（见 `GlyphLiteralScanTest`）。
     */
    val ICON_LITERAL: Regex = Regex("^[" + VOCAB + "]{1,4}$")

    /**
     * 散文闸门：≥6 个连续汉字即当正文。
     *
     * 对「纯词表字面量」规则它是冗余闸门（汉字不在 [VOCAB] 里）；留在这里是为了把
     * 「不要把规则放宽成『含图标字符即违规』」这层意图钉成测试：
     * `"正在生成 · 点 ■ 停止"`、`"↑ ↓ 选择 · Enter 进入"` 这类正文永远不会成为 offender。
     */
    val PROSE: Regex = Regex("[\\u4e00-\\u9fff]{6,}")

    /** 源码里的一个字符串字面量：[line] 是起始行，[text] 是内容（`\uXXXX` 已还原）。 */
    data class Literal(val line: Int, val text: String, val raw: Boolean)

    /** 一处 offender：[path] 相对扫描根目录，全 `/` 分隔。 */
    data class Offender(val path: String, val line: Int, val glyph: String, val snippet: String) {
        fun render(): String = "$path:$line  字面量 \"$glyph\"   ← ${snippet.trim()}"
    }

    /**
     * 行内白名单：文件 + 行号 + 字面量 + 理由。
     *
     * 理由不允许为空——白名单是守卫最容易腐烂的地方（「一直是绿的」就是这么来的），
     * 所以每条放行都必须写清「谁在改 / 什么时候删」。行号对不上时该条目会被报成 stale，
     * 而不是继续静默放行。
     */
    data class Allow(val path: String, val line: Int, val glyph: String, val reason: String) {
        init {
            require(reason.isNotBlank()) { "白名单必须写理由：$path:$line \"$glyph\"" }
        }
    }

    /** 扫描结果：未放行的 offender + 已失效（对不上任何 offender）的白名单项。 */
    data class ScanResult(val offenders: List<Offender>, val staleAllows: List<Allow>)

    /** 判类：整串都是图标字符（去首尾空白后 1..4 个）才算字符图标。 */
    fun isIconLiteral(text: String): Boolean {
        if (PROSE.containsMatchIn(text)) return false
        return ICON_LITERAL.matches(text.trim())
    }

    /** 从源码里摘出全部字符串字面量（注释与字符字面量已剔除）。 */
    fun literals(source: String): List<Literal> = lex(source)

    /** 源码里全部字符图标字面量。 */
    fun hits(source: String): List<Literal> = lex(source).filter { isIconLiteral(it.text) }

    /**
     * 行内白名单过滤：只放过 path + line + glyph 三者都对得上的那一处。
     * 返回的 `staleAllows` 是「写了但一处也没放过」的条目——调用方必须断言它为空。
     */
    fun applyAllow(offenders: List<Offender>, allow: List<Allow>): ScanResult {
        val matched = BooleanArray(allow.size)
        val kept = offenders.filter { offender ->
            val index = allow.indexOfFirst {
                it.path == offender.path && it.line == offender.line && it.glyph == offender.glyph
            }
            if (index >= 0) {
                matched[index] = true
                false
            } else {
                true
            }
        }
        val stale = allow.filterIndexed { index, _ -> !matched[index] }
        return ScanResult(kept, stale)
    }

    /** 扫描目录下全部 `*.kt`（不排除任何子目录），再套白名单。 */
    fun scan(root: File, allow: List<Allow> = emptyList()): ScanResult {
        val files = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .toList()
        val offenders = ArrayList<Offender>()
        for (file in files) {
            val path = file.relativeTo(root).invariantSeparatorsPath
            val source = file.readText()
            val lines = source.lines()
            for (hit in hits(source)) {
                offenders += Offender(
                    path = path,
                    line = hit.line,
                    glyph = hit.text.trim(),
                    snippet = lines.getOrElse(hit.line - 1) { "" },
                )
            }
        }
        return applyAllow(offenders, allow)
    }

    // --- 词法 ------------------------------------------------------------------

    private fun lex(source: String): List<Literal> {
        val out = ArrayList<Literal>()
        val n = source.length
        var i = 0
        var line = 1
        while (i < n) {
            val c = source[i]
            when {
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    while (i < n && source[i] != '\n') i++
                }

                c == '/' && i + 1 < n && source[i + 1] == '*' -> {
                    // Kotlin 块注释可嵌套：`/* 外层 /* 内层 */ 仍是注释 */` 是一整个注释。
                    var depth = 1
                    i += 2
                    while (i < n && depth > 0) {
                        if (source[i] == '\n') {
                            line++
                            i++
                            continue
                        }
                        if (source.startsWith("/*", i)) {
                            depth++
                            i += 2
                            continue
                        }
                        if (source.startsWith("*/", i)) {
                            depth--
                            i += 2
                            continue
                        }
                        i++
                    }
                }

                source.startsWith("\"\"\"", i) -> {
                    val startLine = line
                    i += 3
                    val sb = StringBuilder()
                    while (i < n) {
                        if (source.startsWith("\"\"\"", i)) {
                            // Kotlin：一串 >3 个引号里，最后 3 个是终结符，前面的属于内容。
                            var j = i
                            while (j < n && source[j] == '"') j++
                            repeat(j - i - 3) { sb.append('"') }
                            i = j
                            break
                        }
                        if (source[i] == '\n') line++
                        sb.append(source[i])
                        i++
                    }
                    out += Literal(startLine, sb.toString(), raw = true)
                }

                c == '"' -> {
                    val startLine = line
                    i++
                    val sb = StringBuilder()
                    while (i < n) {
                        val d = source[i]
                        if (d == '\\' && i + 1 < n) {
                            sb.append(d).append(source[i + 1])
                            i += 2
                            continue
                        }
                        if (d == '"') {
                            i++
                            break
                        }
                        if (d == '\n') line++
                        sb.append(d)
                        i++
                    }
                    out += Literal(startLine, sb.toString(), raw = false)
                }

                c == '\'' -> {
                    i++
                    while (i < n) {
                        if (source[i] == '\\') {
                            i += 2
                            continue
                        }
                        if (source[i] == '\'') {
                            i++
                            break
                        }
                        i++
                    }
                }

                c == '\n' -> {
                    line++
                    i++
                }

                else -> i++
            }
        }
        return out.map { Literal(it.line, decodeEscapes(it.text), it.raw) }
    }

    /**
     * 把普通串里的 `\uXXXX` 还原成字符，其余转义换成 U+0001（不在 [VOCAB] 里，必然判不成图标）。
     * 这样 `"\u2190"` 这种写法不会成为绕过守卫的暗道。
     */
    private fun decodeEscapes(text: String): String {
        if (!text.contains('\\')) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 5 < text.length && (text[i + 1] == 'u' || text[i + 1] == 'U')) {
                val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 6
                    continue
                }
            }
            if (c == '\\') {
                sb.append('\u0001')
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
