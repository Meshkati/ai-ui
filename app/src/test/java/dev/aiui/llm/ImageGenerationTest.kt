package dev.aiui.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.Base64

class ImageGenerationTest {
    private val server = MockWebServer()
    private val adapter = OpenAiCompatAdapter(buildHttpClient())
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun endpoint() = Endpoint(ApiFormat.OPENAI, server.url("/v1").toString(), apiKey = "sk-test")

    private fun json(body: String) = MockResponse.Builder().addHeader("content-type", "application/json").body(body).build()

    @Test
    fun base64Reply_returnsTheDecodedPng() = runBlocking {
        // given
        server.enqueue(json("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""))

        // when
        val image = adapter.generateImages(endpoint(), "gpt-image-1", "A red bicycle").single()

        // then
        assertEquals("image/png", image.mimeType)
        assertArrayEquals(png, image.bytes)
    }

    @Test
    fun imageRequest_sendsPromptToImagesEndpoint() = runBlocking {
        // given
        server.enqueue(json("""{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png)}"}]}"""))

        // when
        adapter.generateImages(endpoint(), "gpt-image-1", "A red bicycle")

        // then
        val recorded = server.takeRequest()
        val body = LenientJson.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("/v1/images/generations" to "A red bicycle", recorded.url.encodedPath to body["prompt"]!!.jsonPrimitive.content)
    }

    @Test
    fun urlReply_downloadsTheImageWithoutSendingTheApiKey() = runBlocking {
        // given
        server.enqueue(json("""{"data":[{"url":"${server.url("/files/1.png")}"}]}"""))
        server.enqueue(MockResponse.Builder().body(Buffer().write(png)).build())

        // when
        adapter.generateImages(endpoint(), "dall-e-3", "A red bicycle")

        // then
        server.takeRequest()
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun replyWithoutImages_failsWithAClearMessage() = runBlocking {
        // given
        server.enqueue(json("""{"data":[]}"""))

        // when
        val error = runCatching { adapter.generateImages(endpoint(), "dall-e-3", "A red bicycle") }.exceptionOrNull()

        // then
        assertEquals("The server replied without an image.", error?.message)
    }

    @Test
    fun jpegBytes_areRecognisedAsJpeg() {
        // given
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        // when
        val mimeType = imageMimeType(jpeg)

        // then
        assertEquals("image/jpeg", mimeType)
    }
}
