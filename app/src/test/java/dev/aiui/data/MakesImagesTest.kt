package dev.aiui.data

import dev.aiui.data.db.ModelEntity
import dev.aiui.llm.ApiFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MakesImagesTest {

    @Test
    fun openAiModelNamedLikeAnImageModel_makesImages() {
        // given
        val model = ModelEntity(1, "black-forest-labs/flux-schnell")

        // when
        val makesImages = model.makesImages(ApiFormat.OPENAI)

        // then
        assertTrue(makesImages)
    }

    @Test
    fun wanImageModel_makesImages() {
        // given
        val model = ModelEntity(1, "wan2.7-image-pro")

        // when
        val makesImages = model.makesImages(ApiFormat.OPENAI)

        // then
        assertTrue(makesImages)
    }

    @Test
    fun wanVideoModel_doesNotMakeImages() {
        // given
        val model = ModelEntity(1, "wan2.7-t2v")

        // when
        val makesImages = model.makesImages(ApiFormat.OPENAI)

        // then
        assertFalse(makesImages)
    }

    @Test
    fun chatModel_doesNotMakeImages() {
        // given
        val model = ModelEntity(1, "gpt-5.1")

        // when
        val makesImages = model.makesImages(ApiFormat.OPENAI)

        // then
        assertFalse(makesImages)
    }

    @Test
    fun modelTheUserMarkedAsImageModel_makesImagesWhateverItsId() {
        // given
        val model = ModelEntity(1, "my-local-diffusion", generatesImages = true)

        // when
        val makesImages = model.makesImages(ApiFormat.OPENAI)

        // then
        assertTrue(makesImages)
    }

    @Test
    fun imageModelTheUserSwitchedOff_isTreatedAsChat() {
        // given
        val model = ModelEntity(1, "gpt-image-1", generatesImages = false)

        // when
        val makesImages = model.makesImages(ApiFormat.OPENAI)

        // then
        assertFalse(makesImages)
    }

    @Test
    fun anthropicProfile_neverMakesImages() {
        // given
        val model = ModelEntity(1, "imagen-4", generatesImages = true)

        // when
        val makesImages = model.makesImages(ApiFormat.ANTHROPIC)

        // then
        assertFalse(makesImages)
    }
}
