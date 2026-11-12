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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SpeechApiTest {
    private val server = MockWebServer()
    private val adapter = OpenAiCompatAdapter(buildHttpClient())
    private val recording = File.createTempFile("voice", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() {
        server.close()
        recording.delete()
    }

    private fun endpoint() = Endpoint(ApiFormat.OPENAI, server.url("/v1").toString(), apiKey = "sk-test")

    private fun json(body: String) = MockResponse.Builder().addHeader("content-type", "application/json").body(body).build()

    @Test
    fun transcriptionReply_returnsTheTrimmedText() = runBlocking {
        // given
        server.enqueue(json("""{"text":" Hello there. "}"""))

        // when
        val text = adapter.transcribe(endpoint(), "whisper-1", recording)

        // then
        assertEquals("Hello there.", text)
    }

    @Test
    fun transcriptionRequest_uploadsTheRecordingWithTheModelToTranscriptions() = runBlocking {
        // given
        server.enqueue(json("""{"text":"Hello"}"""))

        // when
        adapter.transcribe(endpoint(), "whisper-1", recording)

        // then
        val recorded = server.takeRequest()
        val body = recorded.body!!.utf8()
        assertEquals("/v1/audio/transcriptions", recorded.url.encodedPath)
        assertTrue(body.contains("whisper-1") && body.contains("filename=\"${recording.name}\""))
    }

    @Test
    fun rejectedKey_failsAsAnAuthError() = runBlocking {
        // given
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"Invalid key"}}""").build())

        // when
        val error = runCatching { adapter.transcribe(endpoint(), "whisper-1", recording) }.exceptionOrNull()

        // then
        assertEquals(ErrorKind.AUTH, (error as LlmException).kind)
    }

    @Test
    fun speechReply_returnsTheAudioBytes() = runBlocking {
        // given
        val mp3 = byteArrayOf(0x49, 0x44, 0x33, 0x04)
        server.enqueue(MockResponse.Builder().addHeader("content-type", "audio/mpeg").body(Buffer().write(mp3)).build())

        // when
        val audio = adapter.synthesizeSpeech(endpoint(), "tts-1", "Hello")

        // then
        assertArrayEquals(mp3, audio)
    }

    @Test
    fun speechRequest_sendsTheTextAndAVoiceToSpeech() = runBlocking {
        // given
        server.enqueue(MockResponse.Builder().body(Buffer().write(byteArrayOf(1))).build())

        // when
        adapter.synthesizeSpeech(endpoint(), "tts-1", "Hello")

        // then
        val recorded = server.takeRequest()
        val body = LenientJson.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("/v1/audio/speech", recorded.url.encodedPath)
        assertEquals("Hello" to "alloy", body["input"]!!.jsonPrimitive.content to body["voice"]!!.jsonPrimitive.content)
    }

    @Test
    fun whisperModel_looksLikeTranscription() {
        // when
        val matches = looksLikeTranscriptionModel("whisper-large-v3")

        // then
        assertTrue(matches)
    }

    @Test
    fun ttsModel_looksLikeSpeech() {
        // when
        val matches = looksLikeSpeechModel("gpt-4o-mini-tts")

        // then
        assertTrue(matches)
    }

    @Test
    fun chatModel_looksLikeNeitherSpeechModel() {
        // when
        val matches = looksLikeTranscriptionModel("gpt-4.1") || looksLikeSpeechModel("gpt-4.1")

        // then
        assertFalse(matches)
    }
}
