package dev.aiui.data

import dev.aiui.data.db.MemoryDao
import dev.aiui.data.db.MemoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** [MemoryDao] backed by a list, so memory behaviour can be tested without a device. */
class InMemoryMemoryDao(vararg texts: String) : MemoryDao {
    private val rows = MutableStateFlow(texts.mapIndexed { index, text -> MemoryEntity(index + 1L, text, 0) })

    override fun observeAll(): Flow<List<MemoryEntity>> = rows

    override suspend fun all(): List<MemoryEntity> = rows.value

    override suspend fun insert(memory: MemoryEntity): Long {
        val id = (rows.value.maxOfOrNull { it.id } ?: 0) + 1
        rows.value = rows.value + memory.copy(id = id)
        return id
    }

    override suspend fun setText(id: Long, text: String): Int {
        if (rows.value.none { it.id == id }) return 0
        rows.value = rows.value.map { if (it.id == id) it.copy(text = text) else it }
        return 1
    }

    override suspend fun delete(id: Long): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot { it.id == id }
        return before - rows.value.size
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }
}
