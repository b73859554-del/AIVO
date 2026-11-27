package io.aivo.app

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.FileNotFoundException
import java.io.File
import java.util.UUID

internal data class StoredChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val attachmentNames: List<String> = emptyList(),
)

internal data class StoredConversation(
    val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val messages: List<StoredChatMessage> = emptyList(),
)

internal interface ConversationStore {
    fun list(): List<StoredConversation>
    fun find(id: String): StoredConversation?
    fun create(): StoredConversation
    fun rename(id: String, title: String): StoredConversation
    fun append(id: String, message: StoredChatMessage): StoredConversation
    fun delete(id: String)
}

internal class EncryptedConversationStore(context: Context) : ConversationStore {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val vault = AesGcmVault(AndroidKeystoreKeyProvider(SESSION_KEY_ALIAS))
    private val gson = Gson()

    @Synchronized
    override fun list(): List<StoredConversation> =
        readDocument().conversations.sortedByDescending(StoredConversation::updatedAtEpochMillis)

    @Synchronized
    override fun find(id: String): StoredConversation? =
        readDocument().conversations.firstOrNull { it.id == id }

    @Synchronized
    override fun create(): StoredConversation {
        val now = System.currentTimeMillis()
        val conversation = StoredConversation(
            id = UUID.randomUUID().toString(),
            title = NEW_CONVERSATION_TITLE,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        writeDocument(readDocument().conversations + conversation)
        return conversation
    }

    @Synchronized
    override fun rename(id: String, title: String): StoredConversation {
        val normalizedTitle = ConversationRules.normalizeTitle(title)
        val conversation = requireConversation(id)
        val updated = conversation.copy(title = normalizedTitle, updatedAtEpochMillis = System.currentTimeMillis())
        replace(updated)
        return updated
    }

    @Synchronized
    override fun append(id: String, message: StoredChatMessage): StoredConversation {
        require(message.role in ALLOWED_ROLES) { "AIVO chat message role is invalid." }
        val content = message.content.trim()
        require(content.isNotEmpty()) { "Chat messages cannot be empty." }
        val conversation = requireConversation(id)
        val title = if (conversation.title == NEW_CONVERSATION_TITLE && message.role == ROLE_USER) {
            ConversationRules.titleFromFirstMessage(content)
        } else {
            conversation.title
        }
        val updated = conversation.copy(
            title = title,
            updatedAtEpochMillis = System.currentTimeMillis(),
            messages = conversation.messages + message.copy(content = content),
        )
        replace(updated)
        return updated
    }

    @Synchronized
    override fun delete(id: String) {
        val conversations = readDocument().conversations
        require(conversations.any { it.id == id }) { "Conversation no longer exists." }
        writeDocument(conversations.filterNot { it.id == id })
    }

    private fun requireConversation(id: String): StoredConversation =
        find(id) ?: throw IllegalArgumentException("Conversation no longer exists.")

    private fun replace(updated: StoredConversation) {
        val conversations = readDocument().conversations
        require(conversations.any { it.id == updated.id }) { "Conversation no longer exists." }
        writeDocument(conversations.map { if (it.id == updated.id) updated else it })
    }

    private fun readDocument(): ConversationDocument {
        return try {
            val encrypted = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
            gson.fromJson(vault.decrypt(encrypted), ConversationDocument::class.java)
                ?: throw IllegalStateException("Conversation history is empty or invalid.")
        } catch (_: FileNotFoundException) {
            ConversationDocument()
        } catch (exception: Exception) {
            throw IllegalStateException("AIVO conversation history could not be decrypted or read.", exception)
        }
    }

    private fun writeDocument(conversations: List<StoredConversation>) {
        val encrypted = vault.encrypt(gson.toJson(ConversationDocument(conversations)))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = file.startWrite()
            stream.write(encrypted.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (exception: Exception) {
            stream?.let(file::failWrite)
            throw IllegalStateException("AIVO conversation history could not be saved.", exception)
        }
    }

    private data class ConversationDocument(
        val conversations: List<StoredConversation> = emptyList(),
    )

    private companion object {
        const val FILE_NAME = "aivo_conversations_v1"
        const val SESSION_KEY_ALIAS = "io.aivo.app.session.aes.v1"
        const val NEW_CONVERSATION_TITLE = "New conversation"
        const val ROLE_USER = "user"
        val ALLOWED_ROLES = setOf("user", "assistant")
    }
}

internal object ConversationRules {
    const val MAX_TITLE_LENGTH = 80

    fun normalizeTitle(value: String): String {
        val title = value.trim().replace(WHITESPACE, " ")
        require(title.isNotEmpty()) { "Conversation title cannot be empty." }
        return title.take(MAX_TITLE_LENGTH)
    }

    fun titleFromFirstMessage(value: String): String =
        normalizeTitle(value.replace(WHITESPACE, " ").take(MAX_TITLE_LENGTH))

    private val WHITESPACE = Regex("\\s+")
}
