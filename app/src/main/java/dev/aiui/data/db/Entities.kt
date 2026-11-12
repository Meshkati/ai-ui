package dev.aiui.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** A saved connection: one base URL, one API key, one API format. */
@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val format: String,
    val baseUrl: String,
    /** KeyVault ciphertext; the plain key is never stored. */
    val apiKeyEncrypted: String?,
    val extraHeaders: String = "{}",
    val extraBody: String = "{}",
    val allowInsecure: Boolean = false,
    val lastModelId: String? = null,
    val createdAt: Long,
    /** ARGB seed the chat's colours are built from while this profile is in use. Null keeps the app's colours. */
    val accentColor: Int? = null,
)

@Entity(
    tableName = "model",
    primaryKeys = ["profileId", "modelId"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profileId")],
)
data class ModelEntity(
    val profileId: Long,
    val modelId: String,
    val displayName: String? = null,
    val enabled: Boolean = true,
    val supportsAttachments: Boolean = true,
    val maxOutputTokens: Int? = null,
    val position: Int = 0,
    val manual: Boolean = false,
    /** Null until the user sets it; the model ID decides until then. */
    val generatesImages: Boolean? = null,
)

@Entity(tableName = "conversation")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val profileId: Long?,
    val modelId: String?,
    /** Copied in at creation and never changed, so replayed thinking blocks stay valid. */
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
)

object MessageStatus {
    const val COMPLETE = "complete"
    const val STREAMING = "streaming"
    const val STOPPED = "stopped"
    const val ERROR = "error"
    const val INTERRUPTED = "interrupted"
}

object MessageRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val text: String,
    /** Anthropic content blocks exactly as returned, replayed verbatim on the same profile and model. */
    val rawBlocks: String? = null,
    val profileId: Long? = null,
    val modelId: String? = null,
    val status: String = MessageStatus.COMPLETE,
    val stopReason: String? = null,
    val error: String? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val createdAt: Long,
)

object AttachmentKind {
    const val IMAGE = "image"
    const val PDF = "pdf"
    const val TEXT = "text"
}

@Entity(
    tableName = "attachment",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val kind: String,
    val mimeType: String,
    val fileName: String,
    val localPath: String,
    val sizeBytes: Long,
)

data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val attachments: List<AttachmentEntity>,
)
