package dev.aiui.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        return sseFlow(
            http.newCall(httpRequest),
            onEvent = { event, emit ->
                val data = event.data.trim()
                if (data == "[DONE]") {
                    emit(StreamEvent.Done(finishReason, null))
                    return@sseFlow true
                }
                val json = runCatching { LenientJson.parseToJsonElement(data).jsonObject }.getOrNull()
                    ?: return@sseFlow false
                (json["error"] as? JsonObject)?.let { error ->
                    throw LlmException(ErrorKind.SERVER, error.str("message") ?: data)
                }
                val choice = (json["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull() as? JsonObject
                val delta = choice?.get("delta") as? JsonObject
                delta?.str("content")?.takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.TextDelta(it)) }
                choice?.str("finish_reason")?.let { finishReason = it }
                (json["usage"] as? JsonObject)?.let { usage ->
                    emit(StreamEvent.Usage(usage.int("prompt_tokens"), usage.int("completion_tokens")))
                }
                false
            },
            onEnd = { emit ->
                // Some servers close the stream after the last chunk without sending [DONE].
                if (finishReason != null) {
                    emit(StreamEvent.Done(finishReason, null))
                } else {
                    throw LlmException(ErrorKind.STREAM, "The connection closed before the reply finished.")
                }
            },
        )
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

    /** Image URLs usually point at a storage host, so the API key is not sent with them. */
    private suspend fun downloadImage(endpoint: Endpoint, url: String): ByteArray {
        val request = Request.Builder().url(url).get()
        if (endpoint.allowInsecure) request.tag(InsecureAllowed::class.java, InsecureAllowed)
        return fetch(request.build()) { it.body.bytes() }
    }

    private suspend fun <T> fetch(request: Request, read: (okhttp3.Response) -> T): T {
        val response = try {
            imageHttp.newCall(request).await()
        } catch (e: Throwable) {
            throw mapThrowable(e)
        }
        response.use {
            if (!it.isSuccessful) throw httpError(it)
            return read(it)
        }
    }

    // Large images can take minutes, with nothing sent until the image is done.
    private val imageHttp by lazy { http.newBuilder().readTimeout(5, TimeUnit.MINUTES).build() }

    private fun baseRequest(endpoint: Endpoint, url: String): Request.Builder {
        val builder = Request.Builder().url(url).header("content-type", "application/json")
        endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return builder.applyEndpoint(endpoint)
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun buildBody(request: LlmRequest, extraBody: JsonObject): JsonObject = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            putJsonArray("messages") {
                request.system?.takeIf { it.isNotBlank() }?.let { system ->
                    addJsonObject {
                        put("role", "system")
                        put("content", system)
                    }
                }
                request.messages.forEach { message ->
                    val onlyText = message.parts.all { it is LlmPart.Text }
                    val text = message.parts.filterIsInstance<LlmPart.Text>().joinToString("\n\n") { it.text }
                    if (onlyText && text.isBlank()) return@forEach
                    addJsonObject {
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
                                    }
                                }
                            }
                        }
                    }
                }
            }
            extraBody.forEach { (key, value) -> put(key, value) }
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
