package dev.aiui.ui.chat

import dev.aiui.data.AppPrefs
import dev.aiui.data.ProfileWithModels
import dev.aiui.data.db.ConversationEntity
import dev.aiui.data.db.ModelEntity
import dev.aiui.data.db.ProfileEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ResolveSelectionTest {
    private fun profile(id: Long, name: String, lastModel: String?, vararg models: Pair<String, Boolean>) = ProfileWithModels(
        ProfileEntity(id = id, name = name, format = "openai", baseUrl = "https://x", apiKeyEncrypted = null, lastModelId = lastModel, createdAt = id),
        models.map { (modelId, enabled) -> ModelEntity(id, modelId, enabled = enabled) },
    )

    private val anthropic = profile(1, "Anthropic", "claude-sonnet-5-5", "claude-opus-5-5" to true, "claude-sonnet-5-5" to true)
    private val macbook = profile(2, "MacBook", null, "qwen" to true)

    @Test
    fun existingChat_usesItsOwnProfileAndModel() {
        // given
        val chat = ConversationEntity(1, "t", profileId = 2, modelId = "qwen", systemPrompt = "", createdAt = 0, updatedAt = 0)

        // when
        val selection = ChatViewModel.resolveSelection(chat, listOf(anthropic, macbook), AppPrefs(defaultProfileId = 1, defaultModelId = "claude-opus-5-5"), null)

        // then
        assertEquals(2L to "qwen", selection!!.profileId to selection.modelId)
    }

    @Test
    fun newChat_usesTheDefaultFromSettings() {
        // when
        val selection = ChatViewModel.resolveSelection(null, listOf(anthropic, macbook), AppPrefs(defaultProfileId = 2, defaultModelId = "qwen"), null)

        // then
        assertEquals("MacBook", selection!!.profileName)
    }

    @Test
    fun newChatWithoutDefault_usesFirstProfilesLastUsedModel() {
        // when
        val selection = ChatViewModel.resolveSelection(null, listOf(anthropic, macbook), AppPrefs(), null)

        // then
        assertEquals("claude-sonnet-5-5", selection!!.modelId)
    }

    @Test
    fun chatWhoseProfileWasDeleted_isMarkedUnavailable() {
        // given
        val chat = ConversationEntity(1, "t", profileId = 9, modelId = "gone", systemPrompt = "", createdAt = 0, updatedAt = 0)

        // when
        val selection = ChatViewModel.resolveSelection(chat, listOf(anthropic), AppPrefs(), null)

        // then
        assertFalse(selection!!.available)
    }
}
