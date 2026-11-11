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
