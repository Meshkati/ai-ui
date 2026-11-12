package dev.aiui.data

import dev.aiui.llm.ToolCall
import dev.aiui.llm.ToolOutput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryToolsTest {

    private fun remember(text: String, replaces: Long? = null) = ToolCall(
        "call_1",
        "remember",
        buildJsonObject {
            put("text", text)
            replaces?.let { put("replaces", it) }
        },
    )

    private fun forget(id: String) = ToolCall("call_1", "forget", buildJsonObject { put("id", id) })

    private suspend fun MemoryRepository.texts() = memories.first().map { it.text }

    @Test
    fun modelRemembersAFact_itIsSavedAndTheModelGetsItsId() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao())

        // when
        val output = memory.run(remember("Prefers metric units"))

        // then
        assertEquals(ToolOutput("Saved as #1."), output)
        assertEquals(listOf("Prefers metric units"), memory.texts())
    }

    @Test
    fun rememberWithReplaces_correctsTheSavedMemory() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao("Lives in Berlin"))

        // when
        memory.run(remember("Lives in Hamburg", replaces = 1))

        // then
        assertEquals(listOf("Lives in Hamburg"), memory.texts())
    }

    @Test
    fun replacesAnUnknownId_isAnErrorAndNothingIsAdded() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao("Lives in Berlin"))

        // when
        val output = memory.run(remember("Lives in Hamburg", replaces = 7))

        // then
        assertTrue(output.isError)
        assertEquals(listOf("Lives in Berlin"), memory.texts())
    }

    @Test
    fun memoryIsFull_newFactIsRefused() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao(*Array(MemoryRepository.MAX_MEMORIES) { "Fact $it" }))

        // when
        val output = memory.run(remember("One more"))

        // then
        assertTrue(output.isError)
        assertEquals(MemoryRepository.MAX_MEMORIES, memory.texts().size)
    }

    @Test
    fun factOverTheLengthLimit_isRefused() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao())

        // when
        val output = memory.run(remember("x".repeat(MemoryRepository.MAX_LENGTH + 1)))

        // then
        assertTrue(output.isError)
        assertTrue(memory.texts().isEmpty())
    }

    @Test
    fun forgetWithAnIdWrittenAsHashString_deletesTheMemory() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao("Lives in Berlin", "Works in Kotlin"))

        // when
        memory.run(forget("#1"))

        // then
        assertEquals(listOf("Works in Kotlin"), memory.texts())
    }

    @Test
    fun forgetAnUnknownId_isAnError() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao("Lives in Berlin"))

        // when
        val output = memory.run(forget("4"))

        // then
        assertTrue(output.isError)
    }

    @Test
    fun toolTheAppDoesNotHave_isAnError() = runTest {
        // given
        val memory = MemoryRepository(InMemoryMemoryDao())

        // when
        val output = memory.run(ToolCall("call_1", "search_web", buildJsonObject {}))

        // then
        assertTrue(output.isError)
    }
}
