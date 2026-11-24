package app.centsible.feature.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.centsible.core.designsystem.theme.CentsibleTheme

/** Markdown as the assistant writes it, split into blocks. */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullets(val items: List<Pair<Int, String>>, val ordered: Boolean) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Code(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

internal object Md {
    private val bullet = Regex("""^(\s*)([-*•]|\d+[.)])\s+(.*)$""")
    private val tableRow = Regex("""^\s*\|.*\|\s*$""")
    private val tableSep = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$""")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r", "").lines()
        val out = mutableListOf<MdBlock>()
        var i = 0
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code.appendLine(lines[i++])
                    out += MdBlock.Code(code.toString().trimEnd())
                }
                t.isEmpty() -> flush()
                t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> { flush(); out += MdBlock.Rule }
                t.startsWith("#") -> {
                    flush()
                    val level = t.takeWhile { it == '#' }.length.coerceAtMost(6)
                    out += MdBlock.Heading(level, t.drop(level).trim())
                }
                t.startsWith(">") -> {
                    flush()
                    val q = StringBuilder()
                    while (i < lines.size && lines[i].trim().startsWith(">")) q.appendLine(lines[i++].trim().removePrefix(">").trim())
                    out += MdBlock.Quote(q.toString().trim())
                    continue
                }
                tableRow.matches(line) && i + 1 < lines.size && tableSep.matches(lines[i + 1]) -> {
                    flush()
                    fun cells(l: String) = l.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
                    val header = cells(line)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && tableRow.matches(lines[i])) rows += cells(lines[i++])
                    out += MdBlock.Table(header, rows)
                    continue
                }
                bullet.matches(line) -> {
                    flush()
                    val items = mutableListOf<Pair<Int, String>>()
                    val ordered = bullet.find(line)!!.groupValues[2].first().isDigit()
                    while (i < lines.size) {
                        val m = bullet.find(lines[i])
                        if (m != null) {
                            items += (m.groupValues[1].length / 2) to m.groupValues[3]
                        } else if (lines[i].isNotBlank() && lines[i].startsWith("  ") && items.isNotEmpty()) {
                            items[items.lastIndex] = items.last().first to items.last().second + " " + lines[i].trim()
                        } else {
                            break
                        }
                        i++
                    }
                    out += MdBlock.Bullets(items, ordered)
                    continue
                }
                else -> para.append(if (para.isEmpty()) t else " $t")
            }
            i++
        }
        flush()
        return out
    }

    /** Inline: **bold**, *italic*, `code`, [links](url). */
    fun inline(text: String, code: SpanStyle, link: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) && text.indexOf("**", i + 2) > i + 2 -> {
                    val end = text.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(inline(text.substring(i + 2, end), code, link)) }
                    i = end + 2
                }
                (text[i] == '*' || text[i] == '_') && i + 1 < text.length && text[i + 1] != ' ' && text.indexOf(text[i], i + 1) > i + 1 &&
                    (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                    val end = text.indexOf(text[i], i + 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
                text[i] == '`' && text.indexOf('`', i + 1) > i -> {
                    val end = text.indexOf('`', i + 1)
                    withStyle(code) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
                text[i] == '[' && text.indexOf("](", i) > i && text.indexOf(')', text.indexOf("](", i)) > 0 -> {
                    val mid = text.indexOf("](", i)
                    val end = text.indexOf(')', mid)
                    val url = text.substring(mid + 2, end)
                    if (url.startsWith("https://")) {
                        withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) { append(text.substring(i + 1, mid)) }
                    } else {
                        append(text.substring(i + 1, mid))
                    }
                    i = end + 1
                }
                else -> append(text[i++])
            }
        }
    }
}

/** Renders the assistant's Markdown with the app's type and colors; the text can be selected and copied. */
@Composable
internal fun Markdown(text: String, modifier: Modifier = Modifier, color: Color = CentsibleTheme.colors.textPrimary) {
    val colors = CentsibleTheme.colors
    val blocks = remember(text) { Md.parse(text) }
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = colors.cardMuted)
    val body = MaterialTheme.typography.bodyLarge.copy(color = color)
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.Heading -> Text(
                        Md.inline(b.text, code, colors.accent),
                        style = (if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall).copy(color = color),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    is MdBlock.Paragraph -> Text(Md.inline(b.text, code, colors.accent), style = body)
                    is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        var n = 0
                        b.items.forEach { (depth, item) ->
                            if (depth == 0) n++
                            Row(Modifier.padding(start = (depth * 16).dp)) {
                                Text(if (b.ordered && depth == 0) "$n." else "•", style = body, modifier = Modifier.width(if (b.ordered) 24.dp else 16.dp))
                                Text(Md.inline(item, code, colors.accent), style = body)
                            }
                        }
                    }
                    is MdBlock.Table -> MdTable(b, code)
                    is MdBlock.Code -> Text(
                        b.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = color),
                        modifier = Modifier.fillMaxWidth().background(colors.cardMuted, RoundedCornerShape(8.dp)).horizontalScroll(rememberScrollState()).padding(10.dp),
                    )
                    is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                        androidx.compose.foundation.layout.Box(Modifier.width(3.dp).fillMaxHeight().background(colors.border))
                        Text(Md.inline(b.text, code, colors.accent), style = body.copy(color = colors.textSecondary), modifier = Modifier.padding(start = 10.dp))
                    }
                    MdBlock.Rule -> HorizontalDivider(color = colors.border)
                }
            }
        }
    }
}

@Composable
private fun MdTable(t: MdBlock.Table, code: SpanStyle) {
    val colors = CentsibleTheme.colors
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    // Numbers line up on the right, like a statement.
    val numeric = (0 until cols).map { c -> t.rows.isNotEmpty() && t.rows.all { r -> r.getOrNull(c).orEmpty().matches(Regex("""^[-+−(]?[$€£¥]?[\d.,]+%?\)?$|^—?$""")) } }
    Column(
        Modifier.border(1.dp, colors.border, RoundedCornerShape(10.dp)).horizontalScroll(rememberScrollState()),
    ) {
        listOf(t.header).plus(t.rows).forEachIndexed { r, row ->
            if (r > 0) HorizontalDivider(color = colors.border)
            Row(Modifier.background(if (r == 0) colors.cardMuted else Color.Transparent)) {
                (0 until cols).forEach { c ->
                    Text(
                        Md.inline(row.getOrElse(c) { "" }, code, colors.accent),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                            fontFeatureSettings = "tnum",
                            color = colors.textPrimary,
                            textAlign = if (numeric[c] && r > 0) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
                        ),
                        modifier = Modifier.widthIn(min = 72.dp, max = 180.dp).padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
