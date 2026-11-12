package dev.aiui.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptTest {

    @Test
    fun noMemories_chatPromptIsSentUnchanged() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao())

        // when
        val prompt = memory.systemPrompt("Answer briefly.")

        // then
        assertEquals("Answer briefly.", prompt)
    }

    @Test
    fun savedMemories_followTheChatPromptWithTheirIds() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao("Prefers metric units", "Works in Kotlin"))

        // when
        val prompt = memory.systemPrompt("Answer briefly.")

        // then
        assertTrue(prompt.startsWith("Answer briefly.\n\n<memory>"))
        assertTrue(prompt.contains("#1 Prefers metric units\n#2 Works in Kotlin\n</memory>"))
    }

    @Test
    fun chatWithoutAPrompt_getsOnlyTheMemories() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao("Prefers metric units"))

        // when
        val prompt = memory.systemPrompt("")

        // then
        assertTrue(prompt.startsWith("<memory>"))
    }

    @Test
    fun forgottenMemory_isLeftOutOfTheNextPrompt() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao("Prefers metric units", "Lives in Berlin"))

        // when
        memory.delete(2)
        val prompt = memory.systemPrompt("")

        // then
        assertFalse(prompt.contains("Berlin"))
    }
}
