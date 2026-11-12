package dev.aiui.data

import dev.aiui.data.SpeechRepository.Companion.speechChunks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunksTest {

    @Test
    fun textWithinTheLimit_isOneChunk() {
        // when
        val chunks = speechChunks("One. Two.", maxChars = 20)

        // then
        assertEquals(listOf("One. Two."), chunks)
    }

    @Test
    fun longText_splitsAtTheLastSentenceEndThatFits() {
        // when
        val chunks = speechChunks("First sentence. Second one here. Third.", maxChars = 34)

        // then
        assertEquals(listOf("First sentence. Second one here.", "Third."), chunks)
    }

    @Test
    fun sentenceLongerThanTheLimit_splitsBetweenWords() {
        // when
        val chunks = speechChunks("alpha beta gamma delta", maxChars = 12)

        // then
        assertEquals(listOf("alpha beta", "gamma delta"), chunks)
    }

    @Test
    fun textWithNoBreaks_neverExceedsTheLimit() {
        // when
        val chunks = speechChunks("x".repeat(25), maxChars = 10)

        // then
        assertTrue(chunks.size == 3 && chunks.all { it.length <= 10 })
    }

    @Test
    fun decimalPoint_isNotTakenForASentenceEnd() {
        // when
        val chunks = speechChunks("Pi is 3.14 roughly so", maxChars = 14)

        // then
        assertEquals(listOf("Pi is 3.14", "roughly so"), chunks)
    }
}
