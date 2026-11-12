package dev.aiui.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class,
        ModelEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
    ],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun models(): ModelDao
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun attachments(): AttachmentDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "aiui.db").build()
    }
}
