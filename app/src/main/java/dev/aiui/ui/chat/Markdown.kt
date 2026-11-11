package dev.aiui.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.aiui.ui.theme.CodeTextStyle
import dev.aiui.ui.theme.MonoFamily

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class ListItems(val items: List<Item>) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data class Image(val alt: String, val url: String) : MdBlock
    data object Rule : MdBlock

    data class Item(val marker: String, val depth: Int, val text: String)
}

object MarkdownParser {
    private val fence = Regex("^\\s{0,3}(```+|~~~+)\\s*([\\w+#.-]*).*$")
    private val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val listItem = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val image = Regex("^\\s*!\\[([^]]*)]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)\\s*$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    /** Tolerates unfinished input, so a reply can be rendered while it streams. */
    fun parse(markdown: String): List<MdBlock> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val blocks = mutableListOf<MdBlock>()
        val paragraph = mutableListOf<String>()
        var i = 0

        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MdBlock.Paragraph(paragraph.joinToString("\n"))
                paragraph.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val fenceMatch = fence.matchEntire(line)
            when {
                fenceMatch != null -> {
                    flushParagraph()
                    val marker = fenceMatch.groupValues[1]
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !isClosingFence(lines[i], marker)) {
                        code += lines[i]
                        i++
                    }
                    blocks += MdBlock.Code(fenceMatch.groupValues[2], code.joinToString("\n"))
                    i++
                    continue
                }
                line.isBlank() -> flushParagraph()
                heading.matches(line) -> {
                    flushParagraph()
                    val match = heading.matchEntire(line)!!
                    blocks += MdBlock.Heading(match.groupValues[1].length, match.groupValues[2])
                }
                rule.matches(line) -> {
                    flushParagraph()
                    blocks += MdBlock.Rule
                }
                image.matches(line) -> {
                    flushParagraph()
                    val match = image.matchEntire(line)!!
                    blocks += MdBlock.Image(match.groupValues[1], match.groupValues[2])
                }
                line.trimStart().startsWith(">") -> {
                    flushParagraph()
                    val quote = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        quote += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                        i++
                    }
                    blocks += MdBlock.Quote(quote.joinToString("\n"))
                    continue
                }
                line.trimStart().startsWith("|") && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) -> {
                    flushParagraph()
                    val header = cells(line)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        rows += cells(lines[i])
                        i++
                    }
                    blocks += MdBlock.Table(header, rows)
                    continue
                }
                listItem.matches(line) -> {
                    flushParagraph()
                    val items = mutableListOf<MdBlock.Item>()
                    while (i < lines.size) {
                        val current = lines[i]
                        val match = listItem.matchEntire(current)
                        if (match != null) {
                            val indent = match.groupValues[1].replace("\t", "    ").length
                            items += MdBlock.Item(match.groupValues[2], indent / 2, match.groupValues[3])
                        } else if (current.isNotBlank() && current.startsWith("  ") && items.isNotEmpty()) {
                            val last = items.removeAt(items.lastIndex)
                            items += last.copy(text = last.text + "\n" + current.trim())
                        } else if (current.isBlank() && i + 1 < lines.size && listItem.matches(lines[i + 1])) {
                            // A blank line between items keeps the list going.
                        } else {
                            break
                        }
                        i++
                    }
                    blocks += MdBlock.ListItems(items)
                    continue
                }
                else -> paragraph += line
            }
            i++
        }
        flushParagraph()
        return blocks
    }

    private fun isClosingFence(line: String, opening: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= opening.length && trimmed.all { it == opening[0] }
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}

/** Inline markdown: code, bold, italic, strikethrough, links and bare URLs. */
fun inlineMarkdown(text: String, codeBackground: Color, linkColor: Color): AnnotatedString {
    val linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    val codeStyle = SpanStyle(fontFamily = MonoFamily, background = codeBackground, fontSize = 14.sp)
    return buildAnnotatedString { appendInline(text, codeStyle, linkStyles) }
}

private fun AnnotatedString.Builder.appendInline(s: String, codeStyle: SpanStyle, linkStyles: TextLinkStyles) {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) {
            append(s[i + 1])
            i += 2
            continue
        }
        if (c == '`') {
            val ticks = s.drop(i).takeWhile { it == '`' }.length
            val close = s.indexOf("`".repeat(ticks), i + ticks)
            if (close != -1) {
                withStyle(codeStyle) { append(s.substring(i + ticks, close).trim()) }
                i = close + ticks
                continue
            }
        }
        val strong = when {
            s.startsWith("**", i) -> "**"
            s.startsWith("__", i) && (i == 0 || !s[i - 1].isLetterOrDigit()) -> "__"
            else -> null
        }
        if (strong != null) {
            val close = s.indexOf(strong, i + 2)
            if (close > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                    appendInline(s.substring(i + 2, close), codeStyle, linkStyles)
                }
                i = close + 2
                continue
            }
        }
        if (s.startsWith("~~", i)) {
            val close = s.indexOf("~~", i + 2)
            if (close > i + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInline(s.substring(i + 2, close), codeStyle, linkStyles)
                }
                i = close + 2
                continue
            }
        }
        if ((c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() &&
            (c == '*' || i == 0 || !s[i - 1].isLetterOrDigit())
        ) {
            var close = s.indexOf(c, i + 1)
            while (close != -1 && (s[close - 1].isWhitespace() || (c == '_' && close + 1 < s.length && s[close + 1].isLetterOrDigit()))) {
                close = s.indexOf(c, close + 1)
            }
            if (close > i + 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInline(s.substring(i + 1, close), codeStyle, linkStyles)
                }
                i = close + 1
                continue
            }
        }
        if (c == '[') {
            val labelEnd = s.indexOf("](", i)
            val urlEnd = if (labelEnd != -1) s.indexOf(')', labelEnd + 2) else -1
            if (labelEnd != -1 && urlEnd != -1 && !s.substring(i + 1, labelEnd).contains('\n')) {
                val url = s.substring(labelEnd + 2, urlEnd).trim().substringBefore(' ')
                withLink(LinkAnnotation.Url(url, linkStyles)) {
                    appendInline(s.substring(i + 1, labelEnd), codeStyle, linkStyles)
                }
                i = urlEnd + 1
                continue
            }
        }
        if (s.startsWith("https://", i) || s.startsWith("http://", i)) {
            var end = i
            while (end < s.length && !s[end].isWhitespace() && s[end] != '<' && s[end] != '>') end++
            while (end > i && s[end - 1] in ".,;:!?)\"'") end--
            val url = s.substring(i, end)
            withLink(LinkAnnotation.Url(url, linkStyles)) { append(url) }
            i = end
            continue
        }
        append(c)
        i++
    }
}

private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"

@Composable
fun MarkdownContent(
    markdown: String,
    onCopyCode: (String) -> Unit,
    onSaveCode: (language: String, code: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    val colors = MaterialTheme.colorScheme
    val codeBackground = colors.surfaceContainerHigh
    val inline = { text: String -> inlineMarkdown(text, codeBackground, colors.primary) }
    val body = MaterialTheme.typography.bodyLarge
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(inline(block.text), style = body)
                is MdBlock.Heading -> Text(
                    inline(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
                is MdBlock.Code -> CodeBlock(block, onCopyCode, onSaveCode)
                is MdBlock.ListItems -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    var number = 0
                    block.items.forEach { item ->
                        val ordered = item.marker.first().isDigit()
                        number = if (ordered) item.marker.dropLast(1).toIntOrNull() ?: (number + 1) else number
                        Row(Modifier.padding(start = (item.depth * 16).dp)) {
                            Text(
                                if (ordered) "$number." else "•",
                                style = body,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.width(if (ordered) 28.dp else 18.dp),
                            )
                            Text(inline(item.text), style = body)
                        }
                    }
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(colors.outlineVariant, RoundedCornerShape(2.dp)),
                    )
                    Text(
                        inline(block.text),
                        style = body,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                is MdBlock.Table -> TableBlock(block, inline)
                is MdBlock.Image -> AsyncImage(
                    model = block.url,
                    contentDescription = block.alt.ifBlank { null },
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                )
                MdBlock.Rule -> HorizontalDivider(color = colors.outlineVariant)
            }
        }
    }
}

@Composable
private fun CodeBlock(
    block: MdBlock.Code,
    onCopy: (String) -> Unit,
    onSave: (String, String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 2.dp),
            ) {
                Text(
                    block.language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onCopy(block.code) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Copy")
                }
                TextButton(onClick = { onSave(block.language, block.code) }) {
                    Icon(Icons.Outlined.SaveAlt, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text("Save")
                }
            }
            Text(
                block.code,
                style = CodeTextStyle,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun TableBlock(block: MdBlock.Table, inline: (String) -> AnnotatedString) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        Column {
            val all = listOf(block.header) + block.rows
            all.forEachIndexed { rowIndex, row ->
                Row(Modifier.padding(horizontal = 4.dp)) {
                    block.header.indices.forEach { col ->
                        Text(
                            inline(row.getOrElse(col) { "" }),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (rowIndex == 0) FontWeight.SemiBold else null,
                            modifier = Modifier
                                .widthIn(min = 64.dp, max = 240.dp)
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
                if (rowIndex < all.lastIndex) HorizontalDivider(color = colors.outlineVariant)
            }
        }
    }
}

/** File extension for a code block's language label. */
fun extensionFor(language: String): String = when (language.lowercase()) {
    "kotlin", "kt" -> "kt"
    "kts" -> "kts"
    "java" -> "java"
    "python", "py" -> "py"
    "javascript", "js" -> "js"
    "typescript", "ts" -> "ts"
    "tsx" -> "tsx"
    "jsx" -> "jsx"
    "json" -> "json"
    "xml" -> "xml"
    "html" -> "html"
    "css" -> "css"
    "bash", "sh", "shell", "zsh" -> "sh"
    "sql" -> "sql"
    "yaml", "yml" -> "yml"
    "toml" -> "toml"
    "markdown", "md" -> "md"
    "c" -> "c"
    "cpp", "c++" -> "cpp"
    "csharp", "cs" -> "cs"
    "go" -> "go"
    "rust", "rs" -> "rs"
    "swift" -> "swift"
    "ruby", "rb" -> "rb"
    "php" -> "php"
    "dart" -> "dart"
    "csv" -> "csv"
    else -> "txt"
}
