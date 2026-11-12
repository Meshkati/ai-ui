package dev.aiui.ui.chat

import dev.aiui.ui.chat.ChatViewModel.Companion.appendDictation
import org.junit.Assert.assertEquals
import org.junit.Test

class AppendDictationTest {

    @Test
    fun emptyComposer_takesTheDictatedText() {
        // when
        val text = appendDictation("", "Hello there.")

        // then
        assertEquals("Hello there.", text)
    }

    @Test
    fun typedText_isFollowedByTheDictatedTextAfterOneSpace() {
        // when
        val text = appendDictation("Quick question: ", "what time is it?")

        // then
        assertEquals("Quick question: what time is it?", text)
    }
}
