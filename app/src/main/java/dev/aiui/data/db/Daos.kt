package dev.aiui.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profile ORDER BY createdAt")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profile WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Query("DELETE FROM profile WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE profile SET lastModelId = :modelId WHERE id = :id")
    suspend fun setLastModel(id: Long, modelId: String)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM model ORDER BY profileId, position")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM model WHERE profileId = :profileId ORDER BY position")
    suspend fun forProfile(profileId: Long): List<ModelEntity>

    @Query("SELECT * FROM model WHERE profileId = :profileId AND modelId = :modelId")
    suspend fun get(profileId: Long, modelId: String): ModelEntity?

    @Upsert
    suspend fun upsertAll(models: List<ModelEntity>)

    @Query("DELETE FROM model WHERE profileId = :profileId")
    suspend fun deleteForProfile(profileId: Long)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT * FROM conversation WHERE title LIKE '%' || :query || '%'
        OR id IN (SELECT conversationId FROM message WHERE text LIKE '%' || :query || '%')
        ORDER BY updatedAt DESC
        """,
    )
    fun search(query: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Query("SELECT * FROM conversation WHERE id = :id")
    fun observe(id: Long): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversation ORDER BY updatedAt DESC")
    suspend fun all(): List<ConversationEntity>

    @Insert
    suspend fun insert(conversation: ConversationEntity): Long

    @Query("UPDATE conversation SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query("UPDATE conversation SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Query("UPDATE conversation SET profileId = :profileId, modelId = :modelId WHERE id = :id")
    suspend fun setModel(id: Long, profileId: Long, modelId: String)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversation")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Transaction
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY id")
    fun observe(conversationId: Long): Flow<List<MessageWithAttachments>>

    @Transaction
    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY id")
    suspend fun all(conversationId: Long): List<MessageWithAttachments>

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun get(id: Long): MessageEntity?

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE message SET text = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    @Query("DELETE FROM message WHERE conversationId = :conversationId AND id >= :fromId")
    suspend fun deleteFrom(conversationId: Long, fromId: Long)

    @Query("DELETE FROM message WHERE conversationId = :conversationId AND id > :afterId")
    suspend fun deleteAfter(conversationId: Long, afterId: Long)

    @Query("UPDATE message SET status = 'interrupted' WHERE status = 'streaming'")
    suspend fun markInterrupted()
}

@Dao
interface AttachmentDao {
    @Insert
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Query(
        """
        SELECT a.* FROM attachment a JOIN message m ON a.messageId = m.id
        WHERE m.conversationId = :conversationId AND m.id >= :fromId
        """,
    )
    suspend fun forConversationFrom(conversationId: Long, fromId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachment")
    suspend fun all(): List<AttachmentEntity>
}
