package dev.aiui.data

import dev.aiui.data.db.MemoryDao
import dev.aiui.data.db.MemoryEntity
import kotlinx.coroutines.flow.Flow

/** Facts about the user that carry from one chat to the next. */
class MemoryRepository(private val dao: MemoryDao) {

    val memories: Flow<List<MemoryEntity>> = dao.observeAll()

    /** The chat's own system prompt followed by every saved memory. Read for each request, so deletions apply at once. */
    suspend fun systemPrompt(chatPrompt: String): String = withMemories(chatPrompt, dao.all())

    suspend fun delete(id: Long) {
        dao.delete(id)
    }

    suspend fun deleteAll() = dao.deleteAll()

    companion object {
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
    }
}
