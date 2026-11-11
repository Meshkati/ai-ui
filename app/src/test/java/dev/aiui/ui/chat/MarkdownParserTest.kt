package dev.aiui.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun unclosedFenceWhileStreaming_rendersAsCodeSoFar() {
        // when
        val blocks = MarkdownParser.parse("Try this:\n```kotlin\nval x = 1\n")

        // then
        assertEquals(MdBlock.Code("kotlin", "val x = 1\n"), blocks.last())
    }

    @Test
    fun closedFence_endsTheCodeBlock() {
        // when
        val blocks = MarkdownParser.parse("```\nls -la\n```\nDone.")

        // then
        assertEquals(listOf(MdBlock.Code("", "ls -la"), MdBlock.Paragraph("Done.")), blocks)
    }

    @Test
    fun pipeTable_isParsedIntoHeaderAndRows() {
        // when
        val blocks = MarkdownParser.parse("| Model | Context |\n| --- | --- |\n| Opus | 1M |")

        // then
        assertEquals(MdBlock.Table(listOf("Model", "Context"), listOf(listOf("Opus", "1M"))), blocks.single())
    }

    @Test
    fun nestedBullets_keepTheirDepth() {
        // when
        val blocks = MarkdownParser.parse("- one\n  - two")

        // then
        assertEquals(
            MdBlock.ListItems(listOf(MdBlock.Item("-", 0, "one"), MdBlock.Item("-", 1, "two"))),
            blocks.single(),
        )
    }
}
