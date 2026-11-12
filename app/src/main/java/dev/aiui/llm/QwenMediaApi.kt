package dev.aiui.llm

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64

/** True for QwenCloud, whose image, speech and transcription models aren't served on its OpenAI-compatible API. */
fun isQwenCloudHost(baseUrl: String): Boolean = runCatching {
    val host = java.net.URI(baseUrl.trim()).host?.lowercase().orEmpty()
    host == "qwencloudapi.com" || host.endsWith(".qwencloudapi.com")
}.getOrDefault(false)

/**
 * QwenCloud's native API, on the same host and key as the profile's compatible-mode base URL.
 * Chat stays on the OpenAI-compatible API; only images, transcription and speech come here.
 */
class QwenMediaApi(private val http: OkHttpClient) {

    suspend fun generateImages(endpoint: Endpoint, model: String, prompt: String): List<GeneratedImage> {
        val body = buildJsonObject {
            put("model", model)
            putJsonObject("input") { putUserMessage { addJsonObject { put("text", prompt) } } }
        }
        val reply = post(endpoint, GENERATION_PATH, body)
        val urls = reply.obj("output")?.get("choices").orEmptyArray().flatMap { choice ->
            (choice as? JsonObject)?.obj("message")?.get("content").orEmptyArray().mapNotNull { (it as? JsonObject)?.str("image") }
        }
        if (urls.isEmpty()) throw LlmException(ErrorKind.STREAM, "The server replied without an image.")
        return urls.map { url -> http.download(endpoint, url).let { GeneratedImage(imageMimeType(it), it) } }
    }

    suspend fun transcribe(endpoint: Endpoint, model: String, audio: File): String {
        val data = "data:${audioMimeType(audio.name)};base64,${Base64.getEncoder().encodeToString(audio.readBytes())}"
        val body = buildJsonObject {
            put("model", model)
            putJsonObject("input") {
                putUserMessage {
                    addJsonObject {
                        put("type", "input_audio")
                        putJsonObject("input_audio") { put("data", data) }
                    }
                }
            }
            putJsonObject("parameters") { put("format", audio.extension.lowercase()) }
        }
        val text = post(endpoint, GENERATION_PATH, body).obj("output")?.str("text")
            ?: throw LlmException(ErrorKind.STREAM, "The server replied without a transcript.")
        return text.trim()
    }

    suspend fun synthesizeSpeech(endpoint: Endpoint, model: String, text: String): ByteArray {
        val body = buildJsonObject {
            put("model", model)
            putJsonObject("input") {
                put("text", text)
                put("voice", voiceFor(model))
                put("format", "mp3")
            }
        }
        val url = post(endpoint, SPEECH_PATH, body).obj("output")?.obj("audio")?.str("url")
            ?: throw LlmException(ErrorKind.STREAM, "The server replied without audio.")
        return http.download(endpoint, url)
    }

    private suspend fun post(endpoint: Endpoint, path: String, body: JsonObject): JsonObject {
        val url = endpoint.baseUrl.trim().toHttpUrl().newBuilder().encodedPath(path).query(null).build().toString()
        val request = bearerRequest(endpoint, url).post(body.toString().toRequestBody(JSON)).build()
        return http.fetch(request) { LenientJson.parseToJsonElement(it.body.string()).jsonObject }
    }

    private fun JsonObjectBuilder.putUserMessage(content: JsonArrayBuilder.() -> Unit) = putJsonArray("messages") {
        addJsonObject {
            put("role", "user")
            putJsonArray("content", content)
        }
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val GENERATION_PATH = "/api/v1/services/aigc/multimodal-generation/generation"
        private const val SPEECH_PATH = "/api/v1/services/audio/tts/SpeechSynthesizer"

        /** Each Qwen speech generation has its own voices; Long An Huan speaks Mandarin and English in both. */
        fun voiceFor(model: String): String = if ("3.1" in model) "longanhuan_v3.1" else "longanhuan_v3.6"
    }
}
