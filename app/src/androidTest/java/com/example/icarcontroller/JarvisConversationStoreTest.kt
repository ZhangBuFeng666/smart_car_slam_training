package com.example.icarcontroller

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JarvisConversationStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: JarvisConversationStore

    @Before
    fun setUp() {
        context.deleteDatabase("jarvis_conversations_test.db")
        store = JarvisConversationStore(context, "jarvis_conversations_test.db")
    }

    @After
    fun tearDown() {
        store.close()
        context.deleteDatabase("jarvis_conversations_test.db")
    }

    @Test
    fun testConversationCrudArchiveAndCascadeDelete() {
        val first = store.createConversation()
        val second = store.createConversation()
        store.replaceItems(
            first.id,
            listOf(
                JarvisChatItem.AssistantMessage("你好，我是贾维斯，有什么吩咐？", ""),
                JarvisChatItem.UserMessage("启动自动避障", "")
            )
        )

        assertEquals("启动自动避障", store.getConversation(first.id)?.title)
        store.renameConversation(first.id, "避障测试")
        store.replaceItems(first.id, listOf(JarvisChatItem.UserMessage("新标题不应覆盖", "")))
        assertEquals("避障测试", store.getConversation(first.id)?.title)

        store.setArchived(first.id, true)
        assertEquals(listOf(second.id), store.listConversations(false).map { it.id })
        assertEquals(listOf(first.id), store.listConversations(true).map { it.id })

        store.setArchived(first.id, false)
        assertEquals(1, store.loadItems(first.id).size)
        store.deleteConversation(first.id)
        assertNull(store.getConversation(first.id))
        assertTrue(store.loadItems(first.id).isEmpty())
    }
}
