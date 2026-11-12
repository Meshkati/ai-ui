package dev.aiui.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

class QwenMediaApiTest {
    private val server = MockWebServer()
    private val api = QwenMediaApi(buildHttpClient())
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04)
    private val recording = File.createTempFile("voice", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        server.close()
        recording.delete()
    }

    private fun endpoint() = Endpoint(ApiFormat.OPENAI, server.url("/compatible-mode/v1").toString(), apiKey = "sk-sp-test")

    private fun json(body: String) = MockResponse.Builder().addHeader("content-type", "application/json").body(body).build()

    private fun bytes(content: ByteArray) = MockResponse.Builder().body(Buffer().write(content)).build()

    private fun imageReply() = json("""{"output":{"choices":[{"message":{"content":[{"image":"${server.url("/files/1.png")}"}]}}]}}""")

    private fun speechReply() = json("""{"output":{"audio":{"url":"${server.url("/files/1.mp3")}"}}}""")

    private fun recordedJson(): Pair<String, JsonObject> = server.takeRequest().let { recorded ->
        recorded.url.encodedPath to LenientJson.parseToJsonElement(recorded.body!!.utf8()).jsonObject
    }

    @Test
    fun tokenPlanBaseUrl_isQwenCloud() {
        // when
        val isQwen = isQwenCloudHost("https://token-plan.maas.qwencloudapi.com/compatible-mode/v1")

        // then
        assertTrue(isQwen)
    }

    @Test
    fun otherHostsEndingInTheQwenDomainName_areNotQwenCloud() {
        // when
        val isQwen = isQwenCloudHost("https://qwencloudapi.com.example.net/v1") || isQwenCloudHost("https://api.openai.com/v1")

        // then
        assertFalse(isQwen)
    }

    @Test
    fun imageRequest_goesToTheNativeApiWithThePromptAsAMessage() = runBlocking {
        // given
        server.enqueue(imageReply())
        server.enqueue(bytes(png))

        // when
        api.generateImages(endpoint(), "qwen-image-3.0-pro", "A red bicycle")

        // then
        val (path, body) = recordedJson()
        val text = body["input"]!!.jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]
        assertEquals("/api/v1/services/aigc/multimodal-generation/generation" to "A red bicycle", path to text!!.jsonPrimitive.content)
    }

    @Test
    fun imageReply_returnsTheImageDownloadedFromItsUrl() = runBlocking {
        // given
        server.enqueue(imageReply())
        server.enqueue(bytes(png))

        // when
        val image = api.generateImages(endpoint(), "qwen-image-3.0-pro", "A red bicycle").single()

        // then
        assertEquals("image/png", image.mimeType)
        assertArrayEquals(png, image.bytes)
    }

    @Test
    fun imageDownload_doesNotSendTheApiKey() = runBlocking {
        // given
        server.enqueue(imageReply())
        server.enqueue(bytes(png))

        // when
        api.generateImages(endpoint(), "qwen-image-3.0-pro", "A red bicycle")

        // then
        server.takeRequest()
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun imageReplyWithoutAnImage_failsWithAClearMessage() = runBlocking {
        // given
        server.enqueue(json("""{"output":{"choices":[]}}"""))

        // when
        val error = runCatching { api.generateImages(endpoint(), "qwen-image-3.0-pro", "A red bicycle") }.exceptionOrNull()

        // then
        assertEquals("The server replied without an image.", error?.message)
    }

    @Test
    fun transcriptionRequest_sendsTheRecordingAsADataUriWithItsFormat() = runBlocking {
        // given
        server.enqueue(json("""{"output":{"text":"Hello"}}"""))

        // when
        api.transcribe(endpoint(), "qwen-audio-3.0-asr-flash", recording)

        // then
        val (path, body) = recordedJson()
        val content = body["input"]!!.jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        val data = content["input_audio"]!!.jsonObject["data"]!!.jsonPrimitive.content
        val format = body["parameters"]!!.jsonObject["format"]!!.jsonPrimitive.content
        assertEquals("/api/v1/services/aigc/multimodal-generation/generation", path)
        assertEquals("data:audio/mp4;base64,${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}" to "m4a", data to format)
    }

    @Test
    fun transcriptionReply_returnsTheTrimmedText() = runBlocking {
        // given
        server.enqueue(json("""{"output":{"sentence":{"text":"Hello there."},"text":" Hello there. "}}"""))

        // when
        val text = api.transcribe(endpoint(), "qwen-audio-3.0-asr-flash", recording)

        // then
        assertEquals("Hello there.", text)
    }

    @Test
    fun speechRequest_sendsTheTextAndAVoiceToTheSynthesizer() = runBlocking {
        // given
        server.enqueue(speechReply())
        server.enqueue(bytes(mp3))

        // when
        api.synthesizeSpeech(endpoint(), "qwen-audio-3.0-tts-plus", "Hello")

        // then
        val (path, body) = recordedJson()
        val input = body["input"]!!.jsonObject
        assertEquals("/api/v1/services/audio/tts/SpeechSynthesizer", path)
        assertEquals("Hello" to "longanhuan_v3.6", input["text"]!!.jsonPrimitive.content to input["voice"]!!.jsonPrimitive.content)
    }

    @Test
    fun speechReply_returnsTheAudioDownloadedFromItsUrl() = runBlocking {
        // given
        server.enqueue(speechReply())
        server.enqueue(bytes(mp3))

        // when
        val audio = api.synthesizeSpeech(endpoint(), "qwen-audio-3.0-tts-plus", "Hello")

        // then
        assertArrayEquals(mp3, audio)
    }

    @Test
    fun newerSpeechModel_getsAVoiceFromItsOwnGeneration() {
        // when
        val voice = QwenMediaApi.voiceFor("qwen-audio-3.1-tts-plus")

        // then
        assertEquals("longanhuan_v3.1", voice)
    }

    @Test
    fun qwenErrorReply_keepsTheServersMessage() = runBlocking {
        // given
        server.enqueue(MockResponse.Builder().code(400).body("""{"code":"InvalidParameter","message":"Voice not supported"}""").build())

        // when
        val error = runCatching { api.synthesizeSpeech(endpoint(), "qwen-audio-3.0-tts-plus", "Hello") }.exceptionOrNull()

        // then
        assertEquals("Request rejected (400): Voice not supported", error?.message)
    }
}
