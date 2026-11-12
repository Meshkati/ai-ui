package dev.aiui.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakableTextTest {

    @Test
    fun codeBlock_isLeftOut() {
        // when
        val text = speakableText("Run this:\n```bash\nls -la\n```\nThen check the output.")

        // then
        assertEquals("Run this:\n\nThen check the output.", text)
    }

    @Test
    fun emphasisAndInlineCode_areReadWithoutTheirMarkers() {
        // when
        val text = speakableText("This is **very** *important*: call `run()`.")

        // then
        assertEquals("This is very important: call run().", text)
    }

    @Test
    fun link_isReadAsItsText() {
        // when
        val text = speakableText("See [the docs](https://example.com/docs) for more.")

        // then
        assertEquals("See the docs for more.", text)
    }

    @Test
    fun underscoresInsideWords_areKept() {
        // when
        val text = speakableText("Set max_tokens higher.")

        // then
        assertEquals("Set max_tokens higher.", text)
    }
}
