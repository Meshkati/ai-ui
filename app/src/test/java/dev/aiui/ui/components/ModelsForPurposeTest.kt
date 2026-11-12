package dev.aiui.ui.components

import dev.aiui.data.ProfileWithModels
import dev.aiui.data.db.ModelEntity
import dev.aiui.data.db.ProfileEntity
import dev.aiui.llm.ApiFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsForPurposeTest {
    private val profile = ProfileWithModels(
        ProfileEntity(id = 1, name = "OpenAI", format = ApiFormat.OPENAI.id, baseUrl = "https://api.openai.com/v1", apiKeyEncrypted = null, createdAt = 0),
        listOf(
            ModelEntity(profileId = 1, modelId = "gpt-4.1", enabled = true),
            ModelEntity(profileId = 1, modelId = "tts-1", enabled = false),
            ModelEntity(profileId = 1, modelId = "whisper-1", enabled = false),
        ),
    )

    @Test
    fun speechToText_offersEveryModelWithTranscriptionModelsFirst() {
        // when
        val models = profile.modelsFor(ModelPurpose.SPEECH_TO_TEXT).map { it.modelId }

        // then
        assertEquals(listOf("whisper-1", "gpt-4.1", "tts-1"), models)
    }

    @Test
    fun chat_offersOnlySwitchedOnModels() {
        // when
        val models = profile.modelsFor(ModelPurpose.CHAT).map { it.modelId }

        // then
        assertEquals(listOf("gpt-4.1"), models)
    }
}
