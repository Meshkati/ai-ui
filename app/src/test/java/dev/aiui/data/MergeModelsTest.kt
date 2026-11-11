package dev.aiui.data

import dev.aiui.data.db.ModelEntity
import dev.aiui.llm.RemoteModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeModelsTest {

    @Test
    fun firstFetchOfShortList_switchesEveryModelOn() {
        // given
        val remote = listOf(RemoteModel("a", null, null), RemoteModel("b", null, null))

        // when
        val merged = ProfileRepository.mergeModels(emptyList(), remote, profileId = 1)

        // then
        assertTrue(merged.all { it.enabled })
    }

    @Test
    fun firstFetchOfLongList_leavesModelsOffForTheUserToPick() {
        // given
        val remote = (1..40).map { RemoteModel("model-$it", null, null) }

        // when
        val merged = ProfileRepository.mergeModels(emptyList(), remote, profileId = 1)

        // then
        assertFalse(merged.any { it.enabled })
    }

    @Test
    fun refetch_keepsSwitchesAndTokenLimitsTheUserSet() {
        // given
        val current = listOf(ModelEntity(1, "a", enabled = false, maxOutputTokens = 4_000))
        val remote = listOf(RemoteModel("a", "Model A", 64_000))

        // when
        val merged = ProfileRepository.mergeModels(current, remote, profileId = 1)

        // then
        assertEquals(ModelEntity(1, "a", displayName = "Model A", enabled = false, maxOutputTokens = 4_000), merged.single())
    }

    @Test
    fun refetch_keepsModelIdsAddedByHand() {
        // given
        val current = listOf(ModelEntity(1, "my-finetune", manual = true))
        val remote = listOf(RemoteModel("a", null, null))

        // when
        val merged = ProfileRepository.mergeModels(current, remote, profileId = 1)

        // then
        assertEquals(listOf("a", "my-finetune"), merged.map { it.modelId })
    }
}
