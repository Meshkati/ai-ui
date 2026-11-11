package dev.aiui.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

enum class ApiFormat(val id: String, val label: String) {
    ANTHROPIC("anthropic", "Anthropic"),
    OPENAI("openai", "OpenAI-compatible");

    companion object {
        fun fromId(id: String): ApiFormat = entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

/** Everything an adapter needs to reach one profile's server. */
data class Endpoint(
    val format: ApiFormat,
    val baseUrl: String,
    val apiKey: String?,
    val extraHeaders: Map<String, String> = emptyMap(),
    val extraBody: JsonObject = JsonObject(emptyMap()),
    val allowInsecure: Boolean = false,
)

enum class Role { USER, ASSISTANT }

sealed interface LlmPart {
    data class Text(val text: String) : LlmPart
    data class Image(val mimeType: String, val base64: String) : LlmPart
    data class Document(val fileName: String, val mimeType: String, val base64: String) : LlmPart
}

/**
 * One turn of history. [rawAnthropicBlocks] is the assistant's content array exactly as
 * the Anthropic API returned it; when present it is replayed verbatim so thinking
 * blocks stay valid.
 */
data class LlmMessage(
    val role: Role,
    val parts: List<LlmPart>,
    val rawAnthropicBlocks: JsonArray? = null,
)

data class LlmRequest(
    val model: String,
    val system: String?,
    val messages: List<LlmMessage>,
    val maxOutputTokens: Int,
) {
    fun withoutRawBlocks(): LlmRequest = copy(messages = messages.map { it.copy(rawAnthropicBlocks = null) })
}

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class Usage(val inputTokens: Int?, val outputTokens: Int?) : StreamEvent
    data class Done(val stopReason: String?, val rawBlocks: JsonArray?) : StreamEvent
}

enum class ErrorKind(val retryable: Boolean) {
    AUTH(false),
    NOT_FOUND(false),
    BAD_REQUEST(false),
    RATE_LIMIT(true),
    SERVER(true),
    NETWORK(true),
    BLOCKED(false),
    STREAM(false),
}

class LlmException(
    val kind: ErrorKind,
    message: String,
    val httpCode: Int? = null,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** Anthropic rejects replayed thinking blocks whose conversation changed; stripping them once recovers. */
    val isThinkingSignatureError: Boolean
        get() = httpCode == 400 && message.orEmpty().let {
            it.contains("signature", ignoreCase = true) && it.contains("thinking", ignoreCase = true)
        }
}

data class RemoteModel(
    val id: String,
    val displayName: String?,
    val maxOutputTokens: Int?,
)

interface LlmClient {
    /** Streams one reply. Cancelling the collector cancels the HTTP call. */
    fun stream(endpoint: Endpoint, request: LlmRequest): Flow<StreamEvent>

    suspend fun listModels(endpoint: Endpoint): List<RemoteModel>
}
