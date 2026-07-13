package com.example.icarcontroller

import android.content.Context

enum class SwitchRequirement { SWITCH_NOW, ASK_USER }

object JarvisConversationPolicy {
    @JvmStatic
    fun switchRequirement(state: JarvisControlTaskState?): SwitchRequirement =
        if (state in setOf(JarvisControlTaskState.STARTING, JarvisControlTaskState.RUNNING)) {
            SwitchRequirement.ASK_USER
        } else {
            SwitchRequirement.SWITCH_NOW
        }
}

class JarvisConversationController(
    context: Context,
    databaseName: String = "jarvis_conversations.db"
) {
    private val store = JarvisConversationStore(context, databaseName)
    private val preferences = context.getSharedPreferences(
        "jarvis_conversation_state_$databaseName",
        Context.MODE_PRIVATE
    )
    private val states = mutableMapOf<String, JarvisViewState>()
    private var currentId: String = restoreCurrentConversation()

    @Synchronized
    fun currentConversationId(): String = currentId

    @Synchronized
    fun currentConversation(): JarvisConversation =
        store.getConversation(currentId) ?: createConversationInternal()

    @Synchronized
    fun currentState(): JarvisViewState = stateFor(currentId)

    @Synchronized
    fun stateFor(conversationId: String): JarvisViewState =
        states.getOrPut(conversationId) {
            JarvisViewState.initial().copy(chatItems = store.loadItems(conversationId))
        }

    @Synchronized
    fun reduceCurrent(event: JarvisEvent): JarvisViewState = reduce(currentId, event)

    @Synchronized
    fun reduce(conversationId: String, event: JarvisEvent): JarvisViewState {
        val next = JarvisReducer.reduce(stateFor(conversationId), event)
        states[conversationId] = next
        store.replaceItems(conversationId, next.chatItems)
        return next
    }

    @Synchronized
    fun createConversation(): JarvisConversation {
        val conversation = createConversationInternal()
        select(conversation.id)
        return conversation
    }

    @Synchronized
    fun switchTo(conversationId: String): JarvisViewState {
        requireNotNull(store.getConversation(conversationId)) { "Conversation not found" }
        select(conversationId)
        return stateFor(conversationId)
    }

    @Synchronized
    fun listConversations(archived: Boolean = false): List<JarvisConversation> =
        store.listConversations(archived)

    @Synchronized
    fun rename(conversationId: String, title: String) = store.renameConversation(conversationId, title)

    @Synchronized
    fun archive(conversationId: String) {
        store.setArchived(conversationId, true)
        if (conversationId == currentId) selectFallbackConversation()
    }

    @Synchronized
    fun restore(conversationId: String) = store.setArchived(conversationId, false)

    @Synchronized
    fun delete(conversationId: String) {
        store.deleteConversation(conversationId)
        states.remove(conversationId)
        if (conversationId == currentId) selectFallbackConversation()
    }

    @Synchronized
    fun activeTask(conversationId: String = currentId): JarvisControlTask? =
        stateFor(conversationId).chatItems
            .filterIsInstance<JarvisChatItem.ControlTaskCard>()
            .map { it.task }
            .lastOrNull { it.state in setOf(JarvisControlTaskState.STARTING, JarvisControlTaskState.RUNNING) }

    private fun restoreCurrentConversation(): String {
        val saved = preferences.getString("active_conversation_id", null)
        if (saved != null && store.getConversation(saved) != null) return saved
        return store.listConversations(false).firstOrNull()?.id ?: createConversationInternal().id
    }

    private fun createConversationInternal(): JarvisConversation {
        val conversation = store.createConversation()
        val initial = JarvisViewState.initial().copy(chatItems = listOf(greeting()))
        states[conversation.id] = initial
        store.replaceItems(conversation.id, initial.chatItems)
        return conversation
    }

    private fun selectFallbackConversation() {
        val fallback = store.listConversations(false).firstOrNull() ?: createConversationInternal()
        select(fallback.id)
    }

    private fun select(conversationId: String) {
        currentId = conversationId
        preferences.edit().putString("active_conversation_id", conversationId).apply()
    }

    companion object {
        fun greeting(): JarvisChatItem.AssistantMessage =
            JarvisChatItem.AssistantMessage("你好，我是贾维斯，有什么吩咐？", "")
    }
}
