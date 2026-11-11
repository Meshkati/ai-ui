package dev.aiui.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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

/** Anthropic Messages API, called over raw HTTP so any compatible base URL works. */
class AnthropicAdapter(private val http: OkHttpClient) : LlmClient {

    override fun stream(endpoint: Endpoint, request: LlmRequest): Flow<StreamEvent> {
        val body = buildBody(request, endpoint.extraBody)
        val httpRequest = baseRequest(endpoint, messagesUrl(endpoint.baseUrl))
            .post(body.toString().toRequestBody(JSON))
            .build()
        val state = StreamState()
        return sseFlow(
            http.newCall(httpRequest),
            onEvent = { event, emit -> state.handle(event, emit) },
            onEnd = { throw LlmException(ErrorKind.STREAM, "The connection closed before the reply finished.") },
        )
    }

    override suspend fun listModels(endpoint: Endpoint): List<RemoteModel> {
        val request = baseRequest(endpoint, modelsUrl(endpoint.baseUrl)).get().build()
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
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                RemoteModel(
                    id = id,
                    displayName = obj["display_name"]?.jsonPrimitive?.contentOrNull,
                    maxOutputTokens = obj["max_tokens"]?.jsonPrimitive?.intOrNull,
                )
            }
        }
    }

    private fun baseRequest(endpoint: Endpoint, url: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("anthropic-version", API_VERSION)
            .header("content-type", "application/json")
        endpoint.apiKey?.takeIf { it.isNotBlank() }?.let { builder.header("x-api-key", it) }
        return builder.applyEndpoint(endpoint)
    }

    /** Accumulates content blocks so the finished reply can be stored and replayed verbatim. */
    private class StreamState {
        private class Block(val start: JsonObject) {
            val text = StringBuilder()
            val thinking = StringBuilder()
            val signature = StringBuilder()
        }

        private val blocks = sortedMapOf<Int, Block>()
        private var stopReason: String? = null

        suspend fun handle(event: SseEvent, emit: suspend (StreamEvent) -> Unit): Boolean {
            val json = runCatching { LenientJson.parseToJsonElement(event.data).jsonObject }.getOrNull() ?: return false
            when (json.str("type") ?: event.event) {
                "message_start" -> {
                    val usage = json["message"]?.jsonObject?.get("usage") as? JsonObject
                    if (usage != null) emit(StreamEvent.Usage(usage.int("input_tokens"), usage.int("output_tokens")))
                }
                "content_block_start" -> {
                    val index = json.int("index") ?: return false
                    val start = json["content_block"] as? JsonObject ?: return false
                    val block = Block(start)
                    blocks[index] = block
                    start.str("text")?.takeIf { it.isNotEmpty() }?.let {
                        block.text.append(it)
                        emit(StreamEvent.TextDelta(it))
                    }
                }
                "content_block_delta" -> {
                    val block = blocks[json.int("index") ?: return false] ?: return false
                    val delta = json["delta"] as? JsonObject ?: return false
                    when (delta.str("type")) {
                        "text_delta" -> delta.str("text")?.let {
                            block.text.append(it)
                            emit(StreamEvent.TextDelta(it))
                        }
                        "thinking_delta" -> delta.str("thinking")?.let { block.thinking.append(it) }
                        "signature_delta" -> delta.str("signature")?.let { block.signature.append(it) }
                    }
                }
                "message_delta" -> {
                    (json["delta"] as? JsonObject)?.str("stop_reason")?.let { stopReason = it }
                    val usage = json["usage"] as? JsonObject
                    if (usage != null) emit(StreamEvent.Usage(usage.int("input_tokens"), usage.int("output_tokens")))
                }
                "message_stop" -> {
                    emit(StreamEvent.Done(stopReason, rawBlocks()))
                    return true
                }
                "error" -> {
                    val error = json["error"] as? JsonObject
                    val type = error?.str("type")
                    val message = error?.str("message") ?: event.data
                    val kind = when (type) {
                        "overloaded_error", "api_error" -> ErrorKind.SERVER
                        "rate_limit_error" -> ErrorKind.RATE_LIMIT
                        "authentication_error", "permission_error" -> ErrorKind.AUTH
                        "not_found_error" -> ErrorKind.NOT_FOUND
                        else -> ErrorKind.BAD_REQUEST
                    }
                    throw LlmException(kind, "${type ?: "error"}: $message")
                }
            }
            return false
        }

        /** Content array as the API would return it non-streamed; only block types the app can replay. */
        fun rawBlocks(): JsonArray = buildJsonArray {
            blocks.values.forEach { block ->
                when (block.start.str("type")) {
                    "text" -> if (block.text.isNotEmpty()) addJsonObject {
                        put("type", "text")
                        put("text", block.text.toString())
                    }
                    "thinking" -> addJsonObject {
                        put("type", "thinking")
                        put("thinking", block.thinking.toString())
                        put("signature", block.signature.toString())
                    }
                    "redacted_thinking" -> add(block.start)
                }
            }
        }
    }

    companion object {
        const val API_VERSION = "2026-10-21"
        const val DEFAULT_MAX_TOKENS = 16_000
        private val JSON = "application/json".toMediaType()

        fun messagesUrl(base: String): String = apiRoot(base) + "/messages"
        fun modelsUrl(base: String): String = apiRoot(base) + "/models?limit=1000"

        /** Accepts both https://api.anthropic.com and https://api.anthropic.com/v1. */
        private fun apiRoot(base: String): String {
            val trimmed = base.trim().trimEnd('/')
            return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
        }

        fun buildBody(request: LlmRequest, extraBody: JsonObject): JsonObject = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxOutputTokens)
            put("stream", true)
            request.system?.takeIf { it.isNotBlank() }?.let { put("system", it) }
            putJsonArray("messages") {
                request.messages.forEach { message -> messageJson(message)?.let { add(it) } }
            }
            extraBody.forEach { (key, value) -> put(key, value) }
        }

        private fun messageJson(message: LlmMessage): JsonObject? {
            val content: JsonArray = if (message.role == Role.ASSISTANT && message.rawAnthropicBlocks != null) {
                message.rawAnthropicBlocks
            } else {
                buildJsonArray {
                    message.parts.forEach { part ->
                        when (part) {
                            is LlmPart.Text -> if (part.text.isNotBlank()) addJsonObject {
                                put("type", "text")
                                put("text", part.text)
                            }
                            is LlmPart.Image -> addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", part.mimeType)
                                    put("data", part.base64)
                                }
                            }
                            is LlmPart.Document -> addJsonObject {
                                put("type", "document")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", part.mimeType)
                                    put("data", part.base64)
                                }
                                put("title", part.fileName)
                            }
                        }
                    }
                }
            }
            if (content.isEmpty()) return null
            return buildJsonObject {
                put("role", if (message.role == Role.USER) "user" else "assistant")
                put("content", content)
            }
        }
    }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonElement?.orEmptyArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
