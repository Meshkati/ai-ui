package dev.aiui.data

import dev.aiui.data.db.ProfileEntity
import dev.aiui.llm.ApiFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioProfileTest {
    private fun profile(id: Long, format: ApiFormat) = ProfileWithModels(
        ProfileEntity(id = id, name = "P$id", format = format.id, baseUrl = "https://example.com/v1", apiKeyEncrypted = null, createdAt = 0),
        emptyList(),
    )

    @Test
    fun choiceOnOpenAiProfile_resolvesToThatProfile() {
        // given
        val profiles = listOf(profile(1, ApiFormat.OPENAI))

        // when
        val resolved = profiles.audioProfileFor(ModelChoice(1, "whisper-1"))

        // then
        assertEquals(1L, resolved?.profile?.id)
    }

    @Test
    fun choiceOnDeletedProfile_resolvesToNothing() {
        // given
        val profiles = listOf(profile(1, ApiFormat.OPENAI))

        // when
        val resolved = profiles.audioProfileFor(ModelChoice(2, "whisper-1"))

        // then
        assertNull(resolved)
    }

    @Test
    fun choiceOnAnthropicProfile_resolvesToNothing() {
        // given
        val profiles = listOf(profile(1, ApiFormat.ANTHROPIC))

        // when
        val resolved = profiles.audioProfileFor(ModelChoice(1, "claude-voice"))

        // then
        assertNull(resolved)
    }
}
