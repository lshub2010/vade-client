package vade.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vade.ui.skin.LocalSkin

/**
 * Renders the [Markdown] subset with `BasicText` + `AnnotatedString`.
 *
 * Why not `Skin.MessageBubble`: that component's frozen signature takes a `String` and the
 * `material` skin draws it with one `Text`, so bold / code / links cannot survive the trip.
 * Pages may not import `material3`, so the rich path is built here from `BasicText` and the
 * **public** skin tokens (`colors` / `typography`) — exactly the same escape hatch `UiText`
 * uses. It is a page-level renderer, not a 12th C-09 component; the request for a slot on
 * `MessageBubble` is recorded in `shots/u1b/RESULT.md` · 需要 RFC.
 */
@Composable
fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    color: Color = LocalSkin.current.colors.onSurface,
    baseSize: Int = 15,
) {
    // K3 · 解析结果按正文缓存。流式时整列每帧都会重组（一个 delta 就换一次 `items`），
    // 没有这一层的话**每一行**每次重组都把全文重新 parse 一遍 —— O(全文) × 行数 × 帧率，
    // 这正是「流式抖动」的算法根因。source 没变就复用同一份 blocks。
    val blocks = remember(source) { Markdown.parse(source) }
    MarkdownBlocks(blocks, modifier, color, baseSize)
}

@Composable
fun MarkdownBlocks(
    blocks: List<MdBlock>,
    modifier: Modifier = Modifier,
    color: Color = LocalSkin.current.colors.onSurface,
    baseSize: Int = 15,
) {
    val skin = LocalSkin.current
    // 视觉规范 §4：正文 15sp / 行高 1.55。行高按正文尺寸现算，避免小字号继承大行高。
    val body = skin.typography.body.copy(fontSize = baseSize.sp, lineHeight = (baseSize * 1.55f).sp)
    // K3 · 这三个 token 每帧都一样，取一次；`rememberRuns` 拿它们当 remember 的 key。
    val mono = skin.typography.mono.fontFamily ?: FontFamily.Monospace
    val codeBackground = skin.colors.surfaceVariant
    val linkColor = skin.colors.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> BasicText(
                    text = rememberRuns(block.runs, color, baseSize, mono, codeBackground, linkColor),
                    style = body,
                )

                is MdBlock.Heading -> BasicText(
                    text = rememberRuns(
                        runs = block.runs,
                        color = color,
                        size = baseSize + (5 - block.level).coerceAtLeast(1),
                        mono = mono,
                        codeBackground = codeBackground,
                        linkColor = linkColor,
                    ),
                    style = skin.typography.title.copy(
                        fontSize = (baseSize + (5 - block.level).coerceAtLeast(1)).sp,
                        lineHeight = (baseSize * 1.35f).sp,
                        color = color,
                    ),
                )

                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    block.items.forEachIndexed { index, runs ->
                        Row {
                            BasicText(
                                text = if (block.ordered) "${index + 1}." else "·",
                                style = body.copy(color = color),
                                modifier = Modifier.width(22.dp),
                            )
                            BasicText(
                                text = rememberRuns(runs, color, baseSize, mono, codeBackground, linkColor),
                                style = body,
                            )
                        }
                    }
                }

                is MdBlock.Code -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // v2 §6/§10：代码块凹陷底色 + xs(12dp) 圆角 + 顶部语言标签。
                        // 皮肤冻结色板上没有独立 sunken token，surfaceVariant 即凹陷层；
                        // 「surfaceSunken」命名留待下一次可加可选 token 的机会。
                        .clip(RoundedCornerShape(skin.shapes.xs))
                        .background(skin.colors.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 28.dp)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicText(
                            text = block.lang.ifEmpty { "text" },
                            style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant),
                        )
                        Spacer(Modifier.weight(1f))
                        BasicText(
                            text = "代码",
                            style = skin.typography.caption.copy(color = skin.colors.onSurfaceVariant.copy(alpha = 0.7f)),
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(skin.colors.hairline))
                    BasicText(
                        text = block.code,
                        style = skin.typography.mono.copy(color = color),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

/**
 * K3 · 一份 [AnnotatedString] 的缓存层：块的 runs / 颜色 / 字号 / 皮肤 token 都没变时复用
 * 上一次构造的那一份。
 *
 * 为什么不只缓存 `parse`：`buildAnnotatedString` 也是 O(全文)（每个 run 一段 SpanStyle），
 * 流式时整列每次重组都会为**每一行**重建一遍。缓存键是这些真实输入，皮肤换主题（mono /
 * surfaceVariant / primary 变了）会自然失效，不需要手动清。
 */
@Composable
private fun rememberRuns(
    runs: List<MdRun>,
    color: Color,
    size: Int,
    mono: FontFamily,
    codeBackground: Color,
    linkColor: Color,
): AnnotatedString = remember(runs, color, size, mono, codeBackground, linkColor) {
    annotated(runs, color, size, mono, codeBackground, linkColor)
}

/**
 * The inline subset → `AnnotatedString`. Pure (no composition reads): the skin tokens it
 * needs are passed in, which is what lets [rememberRuns] cache the result.
 */
internal fun annotated(
    runs: List<MdRun>,
    color: Color,
    size: Int,
    mono: FontFamily,
    codeBackground: Color,
    linkColor: Color,
): AnnotatedString = buildAnnotatedString {
    // 基色必须包住整段：助手消息没有气泡 Surface，BasicText 会回落到
    // LocalContentColor（深色皮肤下是黑色）。链接/代码的 SpanStyle 在里层，
    // 仍然覆盖这里的颜色。
    withStyle(SpanStyle(color = color)) {
    runs.forEach { run ->
        val start = length
        append(run.text)
        val end = length
        if (start == end) return@forEach
        val styles = mutableListOf<SpanStyle>()
        if (run.bold) styles += SpanStyle(fontWeight = FontWeight.SemiBold)
        if (run.italic) styles += SpanStyle(fontStyle = FontStyle.Italic)
        if (run.code) {
            styles += SpanStyle(
                fontFamily = mono,
                fontSize = (size - 1).sp,
                background = codeBackground,
            )
        }
        if (run.link != null) {
            styles += SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
        }
        styles.forEach { addStyle(it, start, end) }
    }
    }
}

/** A tap target that only exists to prove the link colour/underline; nothing is opened. */
@Composable
fun MarkdownLinkHint(text: String, onClick: () -> Unit = {}) {
    val skin = LocalSkin.current
    Box(Modifier.clickable { onClick() }) {
        BasicText(
            text = text,
            style = skin.typography.caption.copy(
                color = skin.colors.primary,
                textDecoration = TextDecoration.Underline,
            ),
        )
    }
}

/** Vertical breathing room between thread rows; keeps `ChatThread` readable. */
@Composable
fun RowSpacer(height: Int = 12) {
    Spacer(Modifier.height(height.dp))
    Spacer(Modifier.width(0.dp))
}

/** Small pill used for 「排队中 #1」 / 「已停止 · 已生成 N 字」 — page-level, not C-09. */
@Composable
fun StatusPill(text: String, tone: Tone = Tone.Neutral) {
    val skin = LocalSkin.current
    val fg = when (tone) {
        Tone.Neutral -> skin.colors.onSurfaceVariant
        Tone.Accent -> skin.colors.primary
        Tone.Danger -> skin.colors.error
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(skin.shapes.pill))
            .background(fg.copy(alpha = 0.10f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(text = text, style = skin.typography.caption.copy(color = fg))
    }
}

enum class Tone { Neutral, Accent, Danger }
