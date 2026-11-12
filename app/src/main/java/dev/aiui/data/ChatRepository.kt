package dev.aiui.data

import dev.aiui.data.db.AppDatabase
import dev.aiui.data.db.AttachmentEntity
import dev.aiui.data.db.AttachmentKind
import dev.aiui.data.db.ConversationEntity
import dev.aiui.data.db.MessageEntity
import dev.aiui.data.db.MessageRole
import dev.aiui.data.db.MessageStatus
import dev.aiui.data.db.MessageWithAttachments
import dev.aiui.llm.AnthropicAdapter
import dev.aiui.llm.ApiFormat
import dev.aiui.llm.LenientJson
import dev.aiui.llm.LlmClient
import dev.aiui.llm.LlmException
import dev.aiui.llm.LlmMessage
import dev.aiui.llm.LlmPart
import dev.aiui.llm.LlmRequest
import dev.aiui.llm.OpenAiCompatAdapter
import dev.aiui.llm.ReplyStream
import dev.aiui.llm.ToolOutput
import dev.aiui.llm.Role
import dev.aiui.llm.isOpenAiHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ChatRepository(
    private val db: AppDatabase,
    private val profiles: ProfileRepository,
    private val prefs: PrefsRepository,
    private val attachments: AttachmentStore,
    private val clients: Map<ApiFormat, LlmClient>,
    private val images: OpenAiCompatAdapter,
    private val scope: CoroutineScope,
) {
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val pdfFallbackCache = ConcurrentHashMap<String, List<LlmPart>>()

    private val _liveText = MutableStateFlow<Map<Long, String>>(emptyMap())

    /** Text of replies still streaming, keyed by message id; ahead of what Room has. */
    val liveText: StateFlow<Map<Long, String>> = _liveText.asStateFlow()

    private val _generating = MutableStateFlow<Set<Long>>(emptySet())

    /** Conversations with a reply in progress. */
    val generating: StateFlow<Set<Long>> = _generating.asStateFlow()

    fun conversations(query: String): Flow<List<ConversationEntity>> =
        if (query.isBlank()) db.conversations().observeAll() else db.conversations().search(query.trim())

    fun observeConversation(id: Long): Flow<ConversationEntity?> = db.conversations().observe(id)

    fun messages(conversationId: Long): Flow<List<MessageWithAttachments>> = db.messages().observe(conversationId)

    suspend fun markInterruptedReplies() = db.messages().markInterrupted()

    suspend fun createConversation(profileId: Long, modelId: String): Long {
        val now = System.currentTimeMillis()
        return db.conversations().insert(
            ConversationEntity(
                title = DEFAULT_TITLE,
                profileId = profileId,
                modelId = modelId,
                systemPrompt = prefs.current().systemPrompt,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun send(conversationId: Long, text: String, pending: List<PendingAttachment>) {
        val conversation = db.conversations().get(conversationId) ?: return
        val now = System.currentTimeMillis()
        val messageId = db.messages().insert(
            MessageEntity(
                conversationId = conversationId,
                role = MessageRole.USER,
                text = text.trim(),
                createdAt = now,
            ),
        )
        if (pending.isNotEmpty()) {
            db.attachments().insertAll(pending.map { it.toEntity(messageId) })
        }
        if (conversation.title == DEFAULT_TITLE) {
            db.conversations().rename(conversationId, titleFor(text, pending))
        }
        db.conversations().touch(conversationId, now)
        startGeneration(conversationId)
    }

    fun stop(conversationId: Long) {
        jobs[conversationId]?.cancel()
    }

    suspend fun regenerate(conversationId: Long, assistantMessageId: Long) {
        stopAndWait(conversationId)
        deleteMessagesFrom(conversationId, assistantMessageId)
        startGeneration(conversationId)
    }

    suspend fun editAndResend(conversationId: Long, userMessageId: Long, newText: String) {
        stopAndWait(conversationId)
        val message = db.messages().get(userMessageId) ?: return
        db.messages().update(message.copy(text = newText.trim()))
        deleteMessagesFrom(conversationId, userMessageId + 1)
        db.conversations().touch(conversationId, System.currentTimeMillis())
        startGeneration(conversationId)
    }

    suspend fun setModel(conversationId: Long, profileId: Long, modelId: String) {
        db.conversations().setModel(conversationId, profileId, modelId)
        profiles.setLastModel(profileId, modelId)
    }

    suspend fun rename(conversationId: Long, title: String) =
        db.conversations().rename(conversationId, title.trim().ifBlank { DEFAULT_TITLE })

    suspend fun delete(conversationId: Long) {
        stopAndWait(conversationId)
        deleteMessagesFrom(conversationId, 0)
        db.conversations().delete(conversationId)
    }

    suspend fun deleteAll() {
        jobs.values.forEach { it.cancel() }
        jobs.values.forEach { it.join() }
        db.conversations().deleteAll()
        attachments.deleteAll()
        pdfFallbackCache.clear()
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val everything = db.conversations().all().map { it to db.messages().all(it.id) }
        val root = buildJsonObject {
            put("app", "AI UI")
            put("exportedAt", Instant.now().toString())
            putJsonArray("conversations") {
                everything.forEach { (conversation, messages) ->
                    addJsonObject {
                        put("title", conversation.title)
                        put("model", conversation.modelId)
                        put("systemPrompt", conversation.systemPrompt)
                        put("createdAt", Instant.ofEpochMilli(conversation.createdAt).toString())
                        putJsonArray("messages") {
                            messages.forEach { item ->
                                val message = item.message
                                addJsonObject {
                                    put("role", message.role)
                                    put("text", message.text)
                                    put("model", message.modelId)
                                    put("status", message.status)
                                    put("createdAt", Instant.ofEpochMilli(message.createdAt).toString())
                                    if (item.attachments.isNotEmpty()) {
                                        putJsonArray("attachments") { item.attachments.forEach { add(it.fileName) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        PrettyJson.encodeToString(JsonElement.serializer(), root)
    }

    fun startGeneration(conversationId: Long) {
        if (jobs[conversationId]?.isActive == true) return
        val job = scope.launch { generate(conversationId) }
        jobs[conversationId] = job
        _generating.update { it + conversationId }
        job.invokeOnCompletion {
            jobs.remove(conversationId, job)
            _generating.update { it - conversationId }
        }
    }

    private suspend fun stopAndWait(conversationId: Long) {
        jobs[conversationId]?.let {
            it.cancel()
            it.join()
        }
    }

    private suspend fun deleteMessagesFrom(conversationId: Long, fromId: Long) {
        db.attachments().forConversationFrom(conversationId, fromId).forEach { attachments.delete(it.localPath) }
        db.messages().deleteFrom(conversationId, fromId)
    }

    private suspend fun generate(conversationId: Long) {
        val conversation = db.conversations().get(conversationId) ?: return
        val history = db.messages().all(conversationId)
        val placeholder = MessageEntity(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            text = "",
            profileId = conversation.profileId,
            modelId = conversation.modelId,
            status = MessageStatus.STREAMING,
            createdAt = System.currentTimeMillis(),
        )
        val assistantId = db.messages().insert(placeholder)
        var message = placeholder.copy(id = assistantId)
        var reply: ReplyStream? = null
        setLive(assistantId, "")
        try {
            val profile = conversation.profileId?.let { profiles.get(it) }
            val modelId = conversation.modelId
            if (profile == null || modelId == null) {
                throw LlmException(
                    dev.aiui.llm.ErrorKind.NOT_FOUND,
                    "This chat's profile was deleted. Pick a profile and model from the top bar, then retry.",
                )
            }
            val endpoint = profiles.endpointFor(profile)
            val model = profiles.model(profile.id, modelId)
            if (model != null && model.makesImages(endpoint.format)) {
                val prompt = history.lastOrNull { it.message.role == MessageRole.USER }?.message?.text.orEmpty()
                if (prompt.isBlank()) {
                    throw LlmException(dev.aiui.llm.ErrorKind.BAD_REQUEST, "$modelId makes images from text. Describe the image you want.")
                }
                val saved = images.generateImages(endpoint, modelId, prompt).map { attachments.saveGenerated(it) }
                db.attachments().insertAll(saved.map { it.toEntity(assistantId) })
                message = message.copy(status = MessageStatus.COMPLETE)
                db.messages().update(message)
                return
            }
            val request = LlmRequest(
                model = modelId,
                system = conversation.systemPrompt,
                messages = buildHistory(history, conversation, endpoint.format, endpoint.baseUrl),
                maxOutputTokens = model?.maxOutputTokens ?: AnthropicAdapter.DEFAULT_MAX_TOKENS,
            )
            val stream = ReplyStream(clients.getValue(endpoint.format), endpoint) { call ->
                ToolOutput("Unknown tool ${call.name}", isError = true)
            }
            reply = stream
            var lastWrite = 0L
            val done = stream.run(request) { text ->
                setLive(assistantId, text)
                val now = System.currentTimeMillis()
                if (now - lastWrite > WRITE_INTERVAL_MS) {
                    lastWrite = now
                    db.messages().updateText(assistantId, text)
                }
            }
            message = message.copy(
                text = stream.text,
                // Tool rounds are not stored, so raw blocks that answer them could not be replayed.
                rawBlocks = done?.rawBlocks?.takeIf { it.isNotEmpty() && !stream.usedTools }?.toString(),
                status = MessageStatus.COMPLETE,
                stopReason = done?.stopReason,
                inputTokens = stream.inputTokens,
                outputTokens = stream.outputTokens,
            )
            db.messages().update(message)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                db.messages().update(
                    message.copy(text = reply?.text.orEmpty(), status = MessageStatus.STOPPED, outputTokens = reply?.outputTokens),
                )
            }
            throw e
        } catch (e: Throwable) {
            db.messages().update(
                message.copy(
                    text = reply?.text.orEmpty(),
                    status = MessageStatus.ERROR,
                    error = e.message ?: e.javaClass.simpleName,
                ),
            )
        } finally {
            _liveText.update { it - assistantId }
            withContext(NonCancellable) { db.conversations().touch(conversationId, System.currentTimeMillis()) }
        }
    }

    private fun setLive(messageId: Long, text: String) {
        _liveText.update { it + (messageId to text) }
    }

    private suspend fun buildHistory(
        history: List<MessageWithAttachments>,
        conversation: ConversationEntity,
        format: ApiFormat,
        baseUrl: String,
    ): List<LlmMessage> = withContext(Dispatchers.IO) {
        history.mapNotNull { item ->
            val message = item.message
            if (message.role == MessageRole.USER) {
                val parts = item.attachments.flatMap { partsFor(it, format, baseUrl) } +
                    listOfNotNull(message.text.takeIf { it.isNotBlank() }?.let { LlmPart.Text(it) })
                parts.takeIf { it.isNotEmpty() }?.let { LlmMessage(Role.USER, it) }
            } else {
                // Thinking blocks are bound to the model and account that made them, so replay
                // raw blocks only when this turn came from the same profile and model.
                val raw = message.rawBlocks
                    ?.takeIf {
                        format == ApiFormat.ANTHROPIC &&
                            message.status == MessageStatus.COMPLETE &&
                            message.profileId == conversation.profileId &&
                            message.modelId == conversation.modelId
                    }
                    ?.let { runCatching { LenientJson.parseToJsonElement(it).jsonArray }.getOrNull() }
                    ?.takeIf { blocks -> blocks.any { it.blockType() == "text" } }
                when {
                    raw != null -> LlmMessage(Role.ASSISTANT, listOf(LlmPart.Text(message.text)), raw)
                    message.text.isNotBlank() -> LlmMessage(Role.ASSISTANT, listOf(LlmPart.Text(message.text)))
                    else -> null
                }
            }
        }
    }

    private fun partsFor(attachment: AttachmentEntity, format: ApiFormat, baseUrl: String): List<LlmPart> =
        when (attachment.kind) {
            AttachmentKind.IMAGE -> listOf(LlmPart.Image(attachment.mimeType, attachments.readBase64(attachment.localPath)))
            AttachmentKind.PDF ->
                if (format == ApiFormat.ANTHROPIC || isOpenAiHost(baseUrl)) {
                    listOf(
                        LlmPart.Document(attachment.fileName, attachment.mimeType, attachments.readBase64(attachment.localPath)),
                    )
                } else {
                    pdfFallbackCache.getOrPut(attachment.localPath) { pdfFallback(attachment) }
                }
            else -> listOf(LlmPart.Text(fileBlock(attachment.fileName, attachments.readText(attachment.localPath))))
        }

    /** Servers without PDF support get the PDF's text, or images of its pages when it has no text layer. */
    private fun pdfFallback(attachment: AttachmentEntity): List<LlmPart> {
        attachments.pdfText(attachment.localPath)?.let { return listOf(LlmPart.Text(fileBlock(attachment.fileName, it))) }
        val pages = attachments.pdfPagesAsJpeg(attachment.localPath)
        return listOf(LlmPart.Text("Pages of ${attachment.fileName}, as images:")) +
            pages.map { LlmPart.Image("image/jpeg", it) }
    }

    private fun fileBlock(name: String, content: String) = "<file name=\"$name\">\n$content\n</file>"

    private fun JsonElement.blockType(): String? = ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.content

    companion object {
        const val DEFAULT_TITLE = "New chat"
        private const val WRITE_INTERVAL_MS = 500L
        private val PrettyJson = Json { prettyPrint = true }

        fun titleFor(text: String, pending: List<PendingAttachment>): String {
            val firstLine = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            val base = firstLine ?: pending.firstOrNull()?.fileName ?: DEFAULT_TITLE
            return if (base.length > 60) base.take(57).trimEnd() + "..." else base
        }
    }
}
