package com.example.icarcontroller

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JarvisChatPageStructureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var controller: JarvisConversationController

    @Before
    fun setUp() {
        context.deleteDatabase("jarvis_ui_test.db")
        controller = JarvisConversationController(context, "jarvis_ui_test.db")
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
        context.deleteDatabase("jarvis_ui_test.db")
    }

    @Test
    fun historyDrawerAndNewConversationControlsWork() {
        var page: JarvisChatPage? = null
        instrumentation.runOnMainSync {
            page = JarvisChatPage(
                context = context,
                host = "127.0.0.1",
                themeMode = ParkingThemeMode.LIGHT,
                executor = executor,
                conversations = controller,
                onStatus = {},
                onEmergencyStop = {},
            )
        }

        val history = page!!.findByDescription("历史会话")
        val create = page!!.findByDescription("新建对话")
        assertNotNull(history)
        assertNotNull(create)

        instrumentation.runOnMainSync { history!!.performClick() }
        assertTrue(page!!.containsText("历史会话"))
        assertTrue(page!!.containsText("已归档"))

        val before = controller.listConversations().size
        instrumentation.runOnMainSync { create!!.performClick() }
        assertEquals(before + 1, controller.listConversations().size)
    }

    private fun View.findByDescription(description: String): View? {
        if (contentDescription?.toString() == description) return this
        if (this !is ViewGroup) return null
        return (0 until childCount).firstNotNullOfOrNull { getChildAt(it).findByDescription(description) }
    }

    private fun View.containsText(value: String): Boolean {
        if (this is android.widget.TextView && text.toString() == value) return true
        if (this !is ViewGroup) return false
        return (0 until childCount).any { getChildAt(it).containsText(value) }
    }
}
