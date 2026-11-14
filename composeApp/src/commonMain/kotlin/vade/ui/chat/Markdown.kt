package vade.ui.chat

/**
 * U1-b · a **deliberately small** markdown subset, no third-party parser.
 *
 * The task book asks for 粗体 / 斜体 / 行内代码 / 代码块 / 列表 / 链接 and — the part that
 * actually matters for a chat client — *incomplete syntax must not break the bubble*. A
 * streaming reply spends most of its life mid-`**`/mid-fence, so every unterminated marker
 * below degrades to literal text instead of throwing or swallowing the rest of the message.
 *
 * What is **not** supported (documented, not faked): tables, images, nested lists, block
 * quotes beyond a leading `>`, setext headings, HTML, reference links. Anything outside the
 * subset renders as plain text, which is the correct failure mode for a chat bubble.
 *
 * Pure Kotlin on purpose: `parse` is unit-tested without a composition (`MarkdownTest`).
 */
object Markdown {

    /** Recursion cap for `**a *b* c**` — deep nesting must not spin. */
    const val MAX_DEPTH = 4

    private val HEADING = Regex("^(#{1,4})\\s+(.*)$")
    private val LIST_ITEM = Regex("^\\s*([-*+]|(\\d{1,3})[.)])\\s+(.*)$")

    fun parse(source: String): List<MdBlock> {
        val text = source.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return emptyList()
        val lines = text.split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> i++

                // --- fenced code -------------------------------------------------
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                    val lang = trimmed.removePrefix(fence).trim()
                    i++
                    val body = mutableListOf<String>()
                    // Unterminated fence (the normal state while a reply streams) swallows
                    // the rest of the message instead of dropping it.
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                        body += lines[i]
                        i++
                    }
                    if (i < lines.size) i++ // closing fence
                    // A fence that ends at EOF would otherwise carry the split's phantom
                    // trailing line as a newline inside the code block.
                    while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.lastIndex)
                    blocks += MdBlock.Code(lang = lang, code = body.joinToString("\n"))
                }

                // --- heading -----------------------------------------------------
                HEADING.matchEntire(trimmed) != null -> {
                    val match = HEADING.matchEntire(trimmed)!!
                    blocks += MdBlock.Heading(
                        level = match.groupValues[1].length,
                        runs = parseInline(match.groupValues[2]),
                    )
                    i++
                }

                // --- list --------------------------------------------------------
                LIST_ITEM.matchEntire(line) != null -> {
                    val ordered = LIST_ITEM.matchEntire(line)!!.groupValues[2].isNotEmpty()
                    val items = mutableListOf<List<MdRun>>()
                    while (i < lines.size) {
                        val match = LIST_ITEM.matchEntire(lines[i]) ?: break
                        if (match.groupValues[2].isNotEmpty() != ordered) break
                        items += parseInline(match.groupValues[3].trim())
                        i++
                    }
                    blocks += MdBlock.Bullets(ordered = ordered, items = items)
                }

                // --- paragraph ---------------------------------------------------
                else -> {
                    val para = mutableListOf<String>()
                    while (i < lines.size) {
                        val candidate = lines[i]
                        val t = candidate.trim()
                        if (t.isEmpty() ||
                            t.startsWith("```") ||
                            t.startsWith("~~~") ||
                            HEADING.matchEntire(t) != null ||
                            LIST_ITEM.matchEntire(candidate) != null
                        ) {
                            break
                        }
                        // A leading `>` is a quote marker; the subset renders it as a paragraph
                        // rather than pretending to style it.
                        para += t.removePrefix(">").trim()
                        i++
                    }
                    if (para.isNotEmpty()) {
                        blocks += MdBlock.Paragraph(parseInline(para.joinToString("\n")))
                    } else {
                        i++ // defensive: never stall on a line the branches above skipped
                    }
                }
            }
        }
        return blocks
    }

    /**
     * Inline subset. Unmatched markers are emitted as literal characters; the loop always
     * advances, so no input can stall it.
     */
    fun parseInline(line: String, depth: Int = 0): List<MdRun> {
        if (line.isEmpty()) return emptyList()
        if (depth > MAX_DEPTH) return listOf(MdRun(line))
        val runs = mutableListOf<MdRun>()
        val buffer = StringBuilder()
        var i = 0

        fun flush() {
            if (buffer.isNotEmpty()) {
                runs += MdRun(buffer.toString())
                buffer.setLength(0)
            }
        }

        while (i < line.length) {
            val c = line[i]
            when {
                // escape
                c == '\\' && i + 1 < line.length && line[i + 1] in "\\`*_[]()#-+.!" -> {
                    buffer.append(line[i + 1])
                    i += 2
                }

                // inline code — unmatched backtick is literal
                c == '`' -> {
                    val close = line.indexOf('`', i + 1)
                    if (close > i) {
                        flush()
                        runs += MdRun(line.substring(i + 1, close), code = true)
                        i = close + 1
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                // bold
                c == '*' && line.startsWith("**", i) -> {
                    val close = line.indexOf("**", i + 2)
                    if (close > i + 2) {
                        flush()
                        runs += parseInline(line.substring(i + 2, close), depth + 1).map { it.copy(bold = true) }
                        i = close + 2
                    } else {
                        buffer.append(c)
                        i++
                    }
                }
                c == '_' && line.startsWith("__", i) && boundary(line, i) -> {
                    val close = closeOf(line, i + 2, "__")
                    if (close > i + 2) {
                        flush()
                        runs += parseInline(line.substring(i + 2, close), depth + 1).map { it.copy(bold = true) }
                        i = close + 2
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                // italic — `_` may not open inside a word, or `args_preview` turns italic
                (c == '*' || c == '_') && boundary(line, i) -> {
                    val close = closeOf(line, i + 1, c.toString())
                    if (close > i + 1) {
                        flush()
                        runs += parseInline(line.substring(i + 1, close), depth + 1).map { it.copy(italic = true) }
                        i = close + 1
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                // [label](url)
                c == '[' -> {
                    val labelEnd = line.indexOf(']', i + 1)
                    val hasUrl = labelEnd > i + 1 && labelEnd + 1 < line.length && line[labelEnd + 1] == '('
                    val urlEnd = if (hasUrl) line.indexOf(')', labelEnd + 2) else -1
                    if (hasUrl && urlEnd > labelEnd + 2) {
                        flush()
                        val url = line.substring(labelEnd + 2, urlEnd).trim()
                        runs += parseInline(line.substring(i + 1, labelEnd), depth + 1).map { it.copy(link = url) }
                        i = urlEnd + 1
                    } else {
                        buffer.append(c)
                        i++
                    }
                }

                else -> {
                    buffer.append(c)
                    i++
                }
            }
        }
        flush()
        return runs
    }

    /**
     * Would [ChatMessage.bodyText] render identically through `Skin.MessageBubble`'s single
     * `Text`? True for plain text and for the empty body a streaming bubble starts with.
     */
    fun isPlain(source: String): Boolean {
        val blocks = parse(source)
        if (blocks.isEmpty()) return true
        if (blocks.size != 1) return false
        val paragraph = blocks.single() as? MdBlock.Paragraph ?: return false
        if (paragraph.runs.isEmpty()) return true
        return paragraph.runs.none { it.bold || it.italic || it.code || it.link != null }
    }

    /**
     * Emphasis may open at [index] only at a word boundary (`_` inside `file_id` is text).
     *
     * "Word" is ASCII on purpose: `与*斜*` is emphasis in Chinese prose, while `args_preview`
     * is an identifier. `Char.isLetterOrDigit()` is true for CJK, which would break the first
     * case — that is exactly how this bug looked in the first test run.
     */
    private fun boundary(line: String, index: Int): Boolean =
        index == 0 || !isAsciiWordChar(line[index - 1])

    /** Closing marker must not continue a word, otherwise `snake_case` close-pairs wrongly. */
    private fun closeOf(line: String, from: Int, marker: String): Int {
        var index = line.indexOf(marker, from)
        while (index >= 0) {
            val next = index + marker.length
            val opensWord = next < line.length &&
                (isAsciiWordChar(line[next]) || (marker == "_" && line[next] == '_'))
            if (!opensWord) return index
            index = line.indexOf(marker, index + marker.length)
        }
        return -1
    }

    private fun isAsciiWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
}

/** One styled run of an inline line. */
data class MdRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/** One block of the subset. `Bullets` covers ordered lists too ([ordered] = `1.` / `1)`). */
sealed interface MdBlock {
    data class Paragraph(val runs: List<MdRun>) : MdBlock
    data class Heading(val level: Int, val runs: List<MdRun>) : MdBlock
    data class Bullets(val ordered: Boolean, val items: List<List<MdRun>>) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
}
