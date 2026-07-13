package com.example.icarcontroller

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

data class JarvisConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?
)

class JarvisConversationStore(
    context: Context,
    databaseName: String = "jarvis_conversations.db"
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE conversations (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                explicit_title INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                archived_at INTEGER
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE conversation_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
                UNIQUE(conversation_id, position)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_conversations_archive_update ON conversations(archived_at, updated_at DESC)")
        db.execSQL("CREATE INDEX idx_conversation_items_order ON conversation_items(conversation_id, position)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun createConversation(title: String = "新对话"): JarvisConversation {
        val now = System.currentTimeMillis()
        val conversation = JarvisConversation(UUID.randomUUID().toString(), title, now, now, null)
        writableDatabase.insertOrThrow(
            "conversations",
            null,
            ContentValues().apply {
                put("id", conversation.id)
                put("title", conversation.title)
                put("explicit_title", 0)
                put("created_at", now)
                put("updated_at", now)
            }
        )
        return conversation
    }

    fun getConversation(id: String): JarvisConversation? =
        readableDatabase.query(
            "conversations",
            CONVERSATION_COLUMNS,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toConversation() else null }

    fun listConversations(archived: Boolean): List<JarvisConversation> =
        readableDatabase.query(
            "conversations",
            CONVERSATION_COLUMNS,
            if (archived) "archived_at IS NOT NULL" else "archived_at IS NULL",
            null,
            null,
            null,
            "updated_at DESC, created_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toConversation())
            }
        }

    fun loadItems(conversationId: String): List<JarvisChatItem> =
        readableDatabase.query(
            "conversation_items",
            arrayOf("type", "payload_json"),
            "conversation_id = ?",
            arrayOf(conversationId),
            null,
            null,
            "position ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        JarvisChatItemCodec.decode(
                            JarvisEncodedChatItem(cursor.getString(0), cursor.getString(1))
                        )
                    )
                }
            }
        }

    fun replaceItems(conversationId: String, items: List<JarvisChatItem>) {
        val now = System.currentTimeMillis()
        writableDatabase.inTransaction {
            delete("conversation_items", "conversation_id = ?", arrayOf(conversationId))
            items.forEachIndexed { position, item ->
                val encoded = JarvisChatItemCodec.encode(item)
                insertOrThrow(
                    "conversation_items",
                    null,
                    ContentValues().apply {
                        put("conversation_id", conversationId)
                        put("position", position)
                        put("type", encoded.type)
                        put("payload_json", encoded.payloadJson)
                        put("created_at", now)
                    }
                )
            }
            val firstUserMessage = items.filterIsInstance<JarvisChatItem.UserMessage>()
                .firstOrNull()?.text?.trim()?.take(36)
            execSQL(
                """
                UPDATE conversations
                SET title = CASE
                    WHEN explicit_title = 0 AND ? IS NOT NULL THEN ?
                    ELSE title
                END,
                updated_at = ?
                WHERE id = ?
                """.trimIndent(),
                arrayOf(firstUserMessage, firstUserMessage, now, conversationId)
            )
        }
    }

    fun renameConversation(id: String, title: String) {
        val cleanTitle = title.trim().take(48)
        require(cleanTitle.isNotEmpty()) { "Conversation title must not be blank" }
        writableDatabase.update(
            "conversations",
            ContentValues().apply {
                put("title", cleanTitle)
                put("explicit_title", 1)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id),
        )
    }

    fun setArchived(id: String, archived: Boolean) {
        writableDatabase.update(
            "conversations",
            ContentValues().apply {
                if (archived) put("archived_at", System.currentTimeMillis()) else putNull("archived_at")
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(id),
        )
    }

    fun deleteConversation(id: String) {
        writableDatabase.delete("conversations", "id = ?", arrayOf(id))
    }

    private fun Cursor.toConversation(): JarvisConversation = JarvisConversation(
        id = getString(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        archivedAt = getColumnIndexOrThrow("archived_at").let { index ->
            if (isNull(index)) null else getLong(index)
        },
    )

    private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            val result = block()
            setTransactionSuccessful()
            result
        } finally {
            endTransaction()
        }
    }

    companion object {
        private val CONVERSATION_COLUMNS = arrayOf(
            "id", "title", "created_at", "updated_at", "archived_at"
        )
    }
}
