package dev.aiui.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/** OpenAI Chat Completions, the format OpenAI, Nebius, Ollama, LM Studio, llama.cpp and vLLM all speak. */
class OpenAiCompatAdapter(private val http: OkHttpClient) : LlmClient {

    override fun stream(endpoint: Endpoint, request: LlmRequest): Flow<StreamEvent> {
        val body = buildBody(request, endpoint.extraBody)
        val httpRequest = baseRequest(endpoint, joinUrl(endpoint.baseUrl, "/chat/completions"))
            .post(body.toString().toRequestBody(JSON))
            .build()
        var finishReason: String? = null
        val toolCalls = StreamedToolCalls()
        return sseFlow(
            http.newCall(httpRequest),
            onEvent = { event, emit ->
                val data = event.data.trim()
                if (data == "[DONE]") {
                    emit(StreamEvent.Done(finishReason, null, toolCalls.finished()))
                    return@sseFlow true
                }
                val json = runCatching { LenientJson.parseToJsonElement(data).jsonObject }.getOrNull()
                    ?: return@sseFlow false
                (json["error"] as? JsonObject)?.let { error ->
                    throw LlmException(ErrorKind.SERVER, error.str("message") ?: data)
                }
                val choice = (json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
                val delta = choice?.get("delta") as? JsonObject
                delta?.str("content")?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.TextDelta(it)) }
                (delta?.get("tool_calls") as? JsonArray)?.let(toolCalls::add)
                choice?.str("finish_reason")?.let { finishReason = it }
                (json["usage"] as? JsonObject)?.let { usage ->
                    emit(StreamEvent.Usage(usage.int("prompt_tokens"), usage.int("completion_tokens")))
                }
                false
            },
            onEnd = { emit ->
                // Some servers close the stream after the last chunk without sending [DONE].
                if (finishReason != null) {
                    emit(StreamEvent.Done(finishReason, null, toolCalls.finished()))
                } else {
                    throw LlmException(ErrorKind.STREAM, "The connection closed before the reply finished.")
                }
            },
        )
    }

    /** Tool calls arrive in pieces keyed by index: the id and name first, then the arguments a few characters at a time. */
    private class StreamedToolCalls {
        private class Partial(var id: String? = null, var name: String? = null, val arguments: StringBuilder = StringBuilder())

        private val calls = sortedMapOf<Int, Partial>()

        fun add(deltas: JsonArray) {
            deltas.forEachIndexed { position, element ->
                val delta = element as? JsonObject ?: return@forEachIndexed
                val call = calls.getOrPut(delta.int("index") ?: position) { Partial() }
                delta.str("id")?.let { call.id = it }
                val function = delta["function"] as? JsonObject ?: return@forEachIndexed
                function.str("name")?.let { call.name = it }
                when (val arguments = function["arguments"]) {
                    is JsonObject -> call.arguments.append(arguments.toString())
                    is JsonPrimitive -> arguments.contentOrNull?.let { call.arguments.append(it) }
                    else -> Unit
                }
            }
        }

        fun finished(): List<ToolCall> = calls.mapNotNull { (index, call) ->
            val name = call.name ?: return@mapNotNull null
            val arguments = call.arguments.takeIf { it.isNotBlank() }
                ?.let { runCatching { LenientJson.parseToJsonElement(it.toString()).jsonObject }.getOrNull() }
                ?: JsonObject(emptyMap())
            ToolCall(call.id ?: "call_$index", name, arguments)
        }
    }

    override suspend fun listModels(endpoint: Endpoint): List<RemoteModel> {
        val request = baseRequest(endpoint, joinUrl(endpoint.baseUrl, "/models")).get().build()
        val response = try {
            http.newCall(request).await()
        } catch (e: Throwable) {
            throw mapThrowable(e)
        }
        response.use {
            if (!it.isSuccessful) throw httpError(it)
            val root = LenientJson.parseToJsonElement(it.body.string()).jsonObject
            return root["data"]?.jsonArray.orEmpty().mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                RemoteModel(id = id, displayName = null, maxOutputTokens = null)
            }.sortedBy { model -> model.id }
        }
    }

    /** OpenAI Images API. Not retried: a request that timed out may still have been generated and billed. */
    suspend fun generateImages(endpoint: Endpoint, model: String, prompt: String): List<GeneratedImage> {
        val body = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("n", 1)
        }
        val request = baseRequest(endpoint, joinUrl(endpoint.baseUrl, "/images/generations"))
            .post(body.toString().toRequestBody(JSON))
            .build()
        val data = fetch(request) { LenientJson.parseToJsonElement(it.body.string()).jsonObject["data"].orEmptyArray() }
        val images = data.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val bytes = item.str("b64_json")?.let { Base64.getDecoder().decode(it) }
                ?: item.str("url")?.let { downloadImage(endpoint, it) }
                ?: return@mapNotNull null
            GeneratedImage(imageMimeType(bytes), bytes)
        }
        if (images.isEmpty()) throw LlmException(ErrorKind.STREAM, "The server replied without an image.")
        return images
    }

    /** OpenAI audio transcriptions; [audio] is uploaded as one file. */
    suspend fun transcribe(endpoint: Endpoint, model: String, audio: File): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", audio.name, audio.asRequestBody(audioMimeType(audio.name).toMediaType()))
            .build()
        val request = authorizedRequest(endpoint, joinUrl(endpoint.baseUrl, "/audio/transcriptions")).post(body).build()
        val text = fetch(request) { LenientJson.parseToJsonElement(it.body.string()).jsonObject.str("text") }
            ?: throw LlmException(ErrorKind.STREAM, "The server replied without a transcript.")
        return text.trim()
    }

    /** OpenAI audio speech: the whole of [text] read aloud as one MP3. */
    suspend fun synthesizeSpeech(endpoint: Endpoint, model: String, text: String): ByteArray {
        val body = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", DEFAULT_VOICE)
            put("response_format", "mp3")
        }
        val request = baseRequest(endpoint, joinUrl(endpoint.baseUrl, "/audio/speech"))
            .post(body.toString().toRequestBody(JSON))
            .build()
        return fetch(request) { it.body.bytes() }
    }

    /** Image URLs usually point at a storage host, so the API key is not sent with them. */
    private suspend fun downloadImage(endpoint: Endpoint, url: String): ByteArray {
        val request = Request.Builder().url(url).get()
        if (endpoint.allowInsecure) request.tag(InsecureAllowed::class.java, InsecureAllowed)
        return fetch(request.build()) { it.body.bytes() }
    }

    private suspend fun <T> fetch(request: Request, read: (okhttp3.Response) -> T): T {
        val response = try {
            slowHttp.newCall(request).await()
        } catch (e: Throwable) {
            throw mapThrowable(e)
        }
        response.use {
            if (!it.isSuccessful) throw httpError(it)
            return read(it)
        }
    }

    // Large images and long recordings can take minutes, with nothing sent until the result is done.
    private val slowHttp by lazy { http.newBuilder().readTimeout(5, TimeUnit.MINUTES).build() }

    private fun baseRequest(endpoint: Endpoint, url: String): Request.Builder =
        authorizedRequest(endpoint, url).header("content-type", "application/json")

    private fun authorizedRequest(endpoint: Endpoint, url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return builder.applyEndpoint(endpoint)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** OpenAI's voice names; most compatible servers map them to one of their own voices. */
        const val DEFAULT_VOICE = "alloy"

        fun buildBody(request: LlmRequest, extraBody: JsonObject): JsonObject = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            if (request.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    request.tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            }
                        }
                    }
                }
            }
            putJsonArray("messages") {
                request.system?.takeIf { it.isNotBlank() }?.let { system ->
                    addJsonObject {
                        put("role", "system")
                        put("content", system)
                    }
                }
                request.messages.forEach { message -> messagesJson(message).forEach { add(it) } }
            }
            extraBody.forEach { (key, value) -> put(key, value) }
        }

        /** Usually one message; tool results become one `tool` message each. */
        private fun messagesJson(message: LlmMessage): List<JsonObject> {
            val results = message.parts.filterIsInstance<LlmPart.ToolResult>()
            if (results.isNotEmpty()) {
                return results.map { result ->
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", result.callId)
                        put("content", result.content)
                    }
                }
            }
            val calls = message.parts.filterIsInstance<LlmPart.ToolUse>()
            val onlyText = message.parts.all { it is LlmPart.Text }
            val text = message.parts.filterIsInstance<LlmPart.Text>().joinToString("\n\n") { it.text }
            if (onlyText && text.isBlank()) return emptyList()
            return listOf(
                buildJsonObject {
                    put("role", if (message.role == Role.USER) "user" else "assistant")
                    if (onlyText || message.role == Role.ASSISTANT) {
                        put("content", text)
                    } else {
                        putJsonArray("content") {
                            message.parts.forEach { part ->
                                when (part) {
                                    is LlmPart.Text -> if (part.text.isNotBlank()) addJsonObject {
                                        put("type", "text")
                                        put("text", part.text)
                                    }
                                    is LlmPart.Image -> addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", "data:${part.mimeType};base64,${part.base64}")
                                        }
                                    }
                                    is LlmPart.Document -> addJsonObject {
                                        put("type", "file")
                                        putJsonObject("file") {
                                            put("filename", part.fileName)
                                            put("file_data", "data:${part.mimeType};base64,${part.base64}")
                                        }
                                    }
                                    is LlmPart.ToolUse, is LlmPart.ToolResult -> Unit
                                }
                            }
                        }
                    }
                    if (calls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            calls.forEach { use ->
                                addJsonObject {
                                    put("id", use.call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", use.call.name)
                                        put("arguments", use.call.arguments.toString())
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

/** True when the base URL is OpenAI's own API, the only Chat Completions server known to accept PDF file parts. */
fun isOpenAiHost(baseUrl: String): Boolean =
    runCatching { java.net.URI(baseUrl.trim()).host?.lowercase() == "api.openai.com" }.getOrDefault(false)

private val ImageModelMarkers = listOf(
    "dall-e", "gpt-image", "imagen", "flux", "stable-diffusion", "sdxl", "sd3", "stable-image",
    "recraft", "ideogram", "seedream", "hidream", "qwen-image",
)

/** Guesses from the ID whether a model makes images through the Images API rather than chatting. */
fun looksLikeImageModel(modelId: String): Boolean = modelId.lowercase().let { id -> ImageModelMarkers.any { it in id } }

private fun audioMimeType(fileName: String): String = when (fileName.substringAfterLast('.').lowercase()) {
    "m4a", "mp4" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "ogg" -> "audio/ogg"
    "webm" -> "audio/webm"
    else -> "application/octet-stream"
}

private val TranscriptionModelMarkers = listOf("whisper", "transcribe", "stt", "asr", "parakeet")
private val SpeechModelMarkers = listOf("tts", "speech", "kokoro", "piper", "orpheus")

/** Guesses from the ID whether a model turns audio into text. */
fun looksLikeTranscriptionModel(modelId: String): Boolean = modelId.lowercase().let { id -> TranscriptionModelMarkers.any { it in id } }

/** Guesses from the ID whether a model turns text into speech. */
fun looksLikeSpeechModel(modelId: String): Boolean = modelId.lowercase().let { id -> SpeechModelMarkers.any { it in id } }

/** Reads the format from the file's first bytes; servers rarely say which one they sent. */
fun imageMimeType(bytes: ByteArray): String {
    fun startsWith(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
    return when {
        startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
        startsWith(0x52, 0x49, 0x46, 0x46) && bytes.size >= 12 && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        startsWith(0x47, 0x49, 0x46) -> "image/gif"
        else -> "image/png"
    }
}
