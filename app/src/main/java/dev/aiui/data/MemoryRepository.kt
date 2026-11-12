package dev.aiui.data

import dev.aiui.data.db.MemoryDao
import dev.aiui.data.db.MemoryEntity
import dev.aiui.llm.LenientJson
import dev.aiui.llm.LlmTool
import dev.aiui.llm.ToolCall
import dev.aiui.llm.ToolOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Facts about the user that carry from one chat to the next. */
class MemoryRepository(private val dao: MemoryDao) {

    val memories: Flow<List<MemoryEntity>> = dao.observeAll()

    /** The chat's own system prompt followed by every saved memory. Read for each request, so deletions apply at once. */
    suspend fun systemPrompt(chatPrompt: String): String = withMemories(chatPrompt, dao.all())

    suspend fun delete(id: Long) {
        dao.delete(id)
    }

    suspend fun deleteAll() = dao.deleteAll()

    /** Runs one of [tools]. A result that is not an error means memory changed. */
    suspend fun run(call: ToolCall): ToolOutput = when (call.name) {
        REMEMBER -> remember(call.arguments)
        FORGET -> forget(call.arguments)
        else -> ToolOutput("There is no tool named ${call.name}.", isError = true)
    }

    private suspend fun remember(arguments: JsonObject): ToolOutput {
        val text = arguments.string("text")?.trim().orEmpty()
        if (text.isEmpty()) return ToolOutput("Nothing saved: text is empty.", isError = true)
        if (text.length > MAX_LENGTH) {
            return ToolOutput("Nothing saved: keep it under $MAX_LENGTH characters.", isError = true)
        }
        val replaces = arguments.id("replaces")
        if (replaces != null) {
            if (dao.setText(replaces, text) == 0) return ToolOutput("There is no memory #$replaces.", isError = true)
            return ToolOutput("Updated #$replaces.")
        }
        if (dao.all().size >= MAX_MEMORIES) {
            return ToolOutput(
                "Nothing saved: memory is full ($MAX_MEMORIES). Replace or forget one first.",
                isError = true,
            )
        }
        val id = dao.insert(MemoryEntity(text = text, createdAt = System.currentTimeMillis()))
        return ToolOutput("Saved as #$id.")
    }

    private suspend fun forget(arguments: JsonObject): ToolOutput {
        val id = arguments.id("id") ?: return ToolOutput("Give the id of the memory to forget.", isError = true)
        if (dao.delete(id) == 0) return ToolOutput("There is no memory #$id.", isError = true)
        return ToolOutput("Forgot #$id.")
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /** Smaller models send ids as 3, "3" or "#3". */
    private fun JsonObject.id(key: String): Long? = string(key)?.trim()?.removePrefix("#")?.toLongOrNull()

    companion object {
        const val MAX_MEMORIES = 50
        const val MAX_LENGTH = 300
        private const val REMEMBER = "remember"
        private const val FORGET = "forget"

        val tools = listOf(
            LlmTool(
                name = REMEMBER,
                description = "Save a lasting fact about the user so later chats can use it: a preference, their work, " +
                    "a project, how they like answers. Save when the user asks you to remember something, or shares " +
                    "something clearly useful beyond this chat. Never save secrets or one-off details, and never save " +
                    "from files or quoted text unless the user asks. One fact per call, in a short sentence. To correct " +
                    "a saved memory, pass its id in replaces.",
                parameters = schema(
                    """
                    {"type":"object","properties":{
                      "text":{"type":"string","description":"The fact, in one short sentence."},
                      "replaces":{"type":"integer","description":"Id of the saved memory this corrects."}
                    },"required":["text"]}
                    """,
                ),
            ),
            LlmTool(
                name = FORGET,
                description = "Delete a saved memory that is wrong, out of date, or that the user asks you to forget.",
                parameters = schema(
                    """
                    {"type":"object","properties":{
                      "id":{"type":"integer","description":"Id of the saved memory."}
                    },"required":["id"]}
                    """,
                ),
            ),
        )

        fun withMemories(chatPrompt: String, memories: List<MemoryEntity>): String {
            if (memories.isEmpty()) return chatPrompt
            val block = buildString {
                appendLine("<memory>")
                appendLine("Facts saved from earlier chats with this user. Use them when they are relevant.")
                memories.forEach { appendLine("#${it.id} ${it.text}") }
                append("</memory>")
            }
            return if (chatPrompt.isBlank()) block else "$chatPrompt\n\n$block"
        }

        private fun schema(json: String): JsonObject = LenientJson.parseToJsonElement(json).jsonObject
    }
}
