package com.example.icarcontroller

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.ExecutorService

class JarvisChatPage(
    context: Context,
    private val host: String,
    private val themeMode: ParkingThemeMode,
    private val executor: ExecutorService,
    private val conversations: JarvisConversationController,
    private val onStatus: (String) -> Unit,
    private val onEmergencyStop: () -> Unit,
) : FrameLayout(context) {
    private val palette = ParkingThemeSpec.palette(themeMode)
    private val credentials = JarvisCredentials(context)
    private var token = credentials.loadToken()
    private var missionId: String? = null
    private var currentPlan: JarvisMissionPlan? = null
    private var state = conversations.currentState()

    private val chatList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, dp(4))
    }
    private val chatScroll = ScrollView(context).apply {
        isFillViewport = true
        addView(chatList)
    }
    private val drawerList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val drawerScrim = View(context).apply {
        setBackgroundColor(Color.argb(90, 0, 0, 0))
        alpha = 0f
        visibility = View.GONE
        setOnClickListener { closeHistoryDrawer() }
    }
    private val historyDrawer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(color(palette.background))
        elevation = dp(12).toFloat()
        visibility = View.GONE
    }
    private val input = EditText(context).apply {
        hint = "输入巡检目标..."
        minLines = 1
        maxLines = 4
        setTextColor(color(palette.textPrimary))
        setHintTextColor(color(palette.textSecondary))
        background = rounded(palette.surface, palette.border, 18)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    private val tokenInput = EditText(context).apply {
        hint = "Jarvis Token"
        setText(token)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setSingleLine(true)
        setTextColor(color(palette.textPrimary))
        setHintTextColor(color(palette.textSecondary))
        background = rounded(palette.surface, palette.border, 14)
        setPadding(dp(10), dp(7), dp(10), dp(7))
    }

    init {
        setPadding(0, 0, 0, 0)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(chatHeader(), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ))
            addView(chatScroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(composer(), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, 0) })
        }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(drawerScrim, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        historyDrawer.addView(TextView(context).apply {
            text = "历史会话"
            setTextColor(color(palette.textPrimary))
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(18), dp(20), dp(18), dp(14))
        })
        historyDrawer.addView(ScrollView(context).apply { addView(drawerList) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        addView(historyDrawer, FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.82f).toInt(), LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.START })
        render()
        resumeTaskPolling()
    }

    private fun chatHeader(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(headerIcon(android.R.drawable.ic_menu_recent_history, "历史会话") { openHistoryDrawer() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(TextView(context).apply {
            text = "贾维斯"
            setTextColor(color(palette.textPrimary))
            textSize = 20f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        addView(headerIcon(android.R.drawable.ic_input_add, "新建对话") {
            requestConversationChange { openNewConversation() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
    }

    private fun headerIcon(drawableId: Int, description: String, action: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(drawableId)
            setColorFilter(color(palette.textPrimary))
            contentDescription = description
            background = null
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { action() }
        }

    fun handleBack(): Boolean {
        if (historyDrawer.visibility != View.VISIBLE) return false
        closeHistoryDrawer()
        return true
    }

    private fun openHistoryDrawer(showArchived: Boolean = false) {
        rebuildDrawer(showArchived)
        drawerScrim.visibility = View.VISIBLE
        historyDrawer.visibility = View.VISIBLE
        historyDrawer.post {
            historyDrawer.translationX = -historyDrawer.width.toFloat()
            drawerScrim.animate().alpha(1f).setDuration(180).start()
            historyDrawer.animate().translationX(0f).setDuration(240).start()
        }
    }

    private fun closeHistoryDrawer() {
        if (historyDrawer.visibility != View.VISIBLE) return
        drawerScrim.animate().alpha(0f).setDuration(160).withEndAction {
            drawerScrim.visibility = View.GONE
        }.start()
        historyDrawer.animate().translationX(-historyDrawer.width.toFloat()).setDuration(200)
            .withEndAction { historyDrawer.visibility = View.GONE }.start()
    }

    private fun rebuildDrawer(showArchived: Boolean) {
        (historyDrawer.getChildAt(0) as? TextView)?.text = if (showArchived) "已归档" else "历史会话"
        drawerList.removeAllViews()
        conversations.listConversations(showArchived).forEach { conversation ->
            drawerList.addView(TextView(context).apply {
                text = conversation.title
                setTextColor(color(
                    if (conversation.id == conversations.currentConversationId()) palette.accentText
                    else palette.textPrimary
                ))
                textSize = 14f
                maxLines = 2
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = if (conversation.id == conversations.currentConversationId()) {
                    rounded(palette.accentSoft, palette.border, 8)
                } else null
                setOnClickListener {
                    requestConversationChange { switchConversation(conversation.id) }
                }
                setOnLongClickListener {
                    showConversationActions(conversation, showArchived)
                    true
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(2), dp(8), dp(2))
            })
        }
        drawerList.addView(TextView(context).apply {
            text = if (showArchived) "‹ 返回历史会话" else "已归档"
            setTextColor(color(palette.textSecondary))
            textSize = 14f
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setOnClickListener { rebuildDrawer(!showArchived) }
        })
    }

    private fun openNewConversation() {
        conversations.createConversation()
        state = conversations.currentState()
        missionId = null
        currentPlan = null
        closeHistoryDrawer()
        render()
    }

    private fun switchConversation(conversationId: String) {
        state = conversations.switchTo(conversationId)
        closeHistoryDrawer()
        render()
    }

    private fun requestConversationChange(action: () -> Unit) {
        val activeTask = conversations.activeTask()
        if (JarvisConversationPolicy.switchRequirement(activeTask?.state) == SwitchRequirement.SWITCH_NOW) {
            action()
            return
        }
        showRunningTaskDialog(activeTask!!, action)
    }

    private fun runWithConversationSafety(conversationId: String, action: () -> Unit) {
        if (conversationId == conversations.currentConversationId()) {
            requestConversationChange(action)
        } else {
            action()
        }
    }

    private fun showRunningTaskDialog(task: JarvisControlTask, action: () -> Unit) {
        val dialog = Dialog(context)
        dialog.setContentView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(palette.surface, palette.border, 10)
            setPadding(dp(18), dp(12), dp(18), dp(18))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "任务仍在运行"
                    textSize = 17f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(color(palette.textPrimary))
                }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton("×") { dialog.dismiss() }, LayoutParams(dp(40), dp(40)))
            })
            addView(body("切换对话前，请选择如何处理当前任务。"), lp(top = 8, bottom = 12))
            addView(primaryButton("停止任务并切换") {
                val savedToken = saveToken()
                executor.execute {
                    val result = runCatching { JarvisApi(host, savedToken).stopControlTask(task.id) }
                    runOnUiThread {
                        result.onSuccess {
                            reduceEvent(JarvisEvent.ControlTaskUpdated(it))
                            dialog.dismiss()
                            action()
                        }.onFailure { appendError(it.message ?: "停止任务失败，未切换对话") }
                    }
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
            addView(outlineButton("保持运行并切换") {
                dialog.dismiss()
                action()
            }, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { setMargins(0, dp(8), 0, 0) })
        })
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), LayoutParams.WRAP_CONTENT)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.86f).toInt(), LayoutParams.WRAP_CONTENT)
    }

    private fun showConversationActions(conversation: JarvisConversation, archived: Boolean) {
        val actions = if (archived) arrayOf("恢复", "删除") else arrayOf("重命名", "归档", "删除")
        AlertDialog.Builder(context)
            .setTitle(conversation.title)
            .setItems(actions) { _, which ->
                val selected = actions[which]
                when (selected) {
                    "重命名" -> showRenameDialog(conversation)
                    "恢复" -> {
                        conversations.restore(conversation.id)
                        rebuildDrawer(true)
                    }
                    "归档" -> runWithConversationSafety(conversation.id) {
                        conversations.archive(conversation.id)
                        state = conversations.currentState()
                        rebuildDrawer(false)
                        render()
                    }
                    "删除" -> confirmDelete(conversation, archived)
                }
            }.show()
    }

    private fun showRenameDialog(conversation: JarvisConversation) {
        val titleInput = EditText(context).apply {
            setText(conversation.title)
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle("重命名对话")
            .setView(titleInput)
            .setPositiveButton("保存") { _, _ ->
                runCatching { conversations.rename(conversation.id, titleInput.text.toString()) }
                    .onFailure { appendError(it.message ?: "重命名失败") }
                rebuildDrawer(false)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete(conversation: JarvisConversation, archived: Boolean) {
        AlertDialog.Builder(context)
            .setTitle("删除对话？")
            .setMessage("删除后无法恢复。")
            .setPositiveButton("删除") { _, _ ->
                runWithConversationSafety(conversation.id) {
                    conversations.delete(conversation.id)
                    state = conversations.currentState()
                    rebuildDrawer(archived)
                    render()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun composer(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, 0)
        addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(iconButton("!") {
            onEmergencyStop()
            reduceEvent(JarvisEvent.SystemMessageAdded("已发送急停指令。"))
            render()
        }, LayoutParams(dp(38), dp(38)).apply {
            setMargins(dp(6), 0, 0, 0)
        })
        addView(primaryButton("发送") { sendMessage() }, LayoutParams(dp(58), dp(38)).apply {
            setMargins(dp(6), 0, 0, 0)
        })
    }

    private fun promptChip(prompt: String): TextView = TextView(context).apply {
        text = prompt
        setTextColor(color(palette.textPrimary))
        textSize = 11f
        background = rounded(palette.surfaceAlt, palette.border, 14)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        isClickable = true
        setOnClickListener {
            if (prompt == "鐢熸垚鎶ュ憡") requestReport(missionId) else {
                input.setText(prompt)
                input.setSelection(input.text.length)
            }
        }
    }

    private fun render() {
        chatList.removeAllViews()
        state.chatItems.forEach { item ->
            chatList.addView(
                when (item) {
                    is JarvisChatItem.UserMessage -> bubble(item.text, Gravity.END, palette.accentSoft, palette.accent)
                    is JarvisChatItem.AssistantMessage -> bubble(item.text, Gravity.START, palette.surface, palette.border)
                    is JarvisChatItem.PlanCard -> planCard(item.plan)
                    is JarvisChatItem.ControlTaskCard -> controlTaskCard(item.task)
                    is JarvisChatItem.ProgressCard -> progressCard(item.mission, item.timeline)
                    is JarvisChatItem.ReportCard -> reportCard(item.report)
                    is JarvisChatItem.ErrorMessage -> errorCard(item.title, item.detail)
                    is JarvisChatItem.SystemEvent -> systemRow(
                        if (state.loading) "贾维斯正在回复…" else item.text
                    )
                }
            )
        }
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isBlank()) return
        val savedToken = saveToken()
        if (savedToken.isBlank()) {
            appendError("璇峰厛閰嶇疆 Jarvis Token")
            return
        }
        input.text.clear()
        val conversationId = conversations.currentConversationId()
        reduceEvent(JarvisEvent.UserMessageSubmitted(message), conversationId)
        onStatus("Jarvis 正在生成计划")
        render()
        executor.execute {
            val result = runCatching { JarvisApi(host, savedToken).chat(message) }
            runOnUiThread {
                result.fold(
                    onSuccess = { response ->
                        val plan = response.plan
                        val controlTask = response.controlTask
                        if (controlTask != null) {
                            reduceEvent(JarvisEvent.ControlTaskReady(controlTask), conversationId)
                            onStatus("Jarvis 正在准备控制任务")
                            pollControlTask(controlTask.id, conversationId)
                        } else if (plan == null) {
                            reduceEvent(JarvisEvent.ChatReply(response.reply), conversationId)
                            onStatus("Jarvis")
                        } else {
                            currentPlan = plan
                            reduceEvent(JarvisEvent.PlanReady(plan), conversationId)
                            onStatus("Jarvis 计划已生成")
                        }
                    },
                    onFailure = { error ->
                        reduceEvent(JarvisEvent.NetworkFailed(error.message ?: error.javaClass.simpleName), conversationId)
                        onStatus("Jarvis 计划失败")
                    }
                )
                render()
            }
        }
    }

    private fun confirmPlan(plan: JarvisMissionPlan) {
        val savedToken = saveToken()
        if (savedToken.isBlank()) {
            appendError("请先配置 Jarvis Token")
            return
        }
        reduceEvent(JarvisEvent.SystemMessageAdded("已确认执行，Jarvis 正在下发任务。"))
        render()
        executor.execute {
            val result = runCatching {
                val api = JarvisApi(host, savedToken)
                val mission = api.createMission(plan)
                val confirmed = api.confirmMission(mission.id)
                confirmed to api.getTimeline(confirmed.id)
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { pair ->
                        missionId = pair.first.id
                        currentPlan = pair.first.plan
                        reduceEvent(JarvisEvent.MissionUpdated(pair.first, pair.second))
                        onStatus("Jarvis 任务已执行")
                    },
                    onFailure = { error ->
                        reduceEvent(JarvisEvent.NetworkFailed(error.message ?: error.javaClass.simpleName))
                        onStatus("Jarvis 执行失败")
                    }
                )
                render()
            }
        }
    }

    private fun refreshMission(targetMissionId: String) {
        val savedToken = saveToken()
        if (savedToken.isBlank()) {
            appendError("请先配置 Jarvis Token")
            return
        }
        executor.execute {
            val result = runCatching {
                val api = JarvisApi(host, savedToken)
                api.getMission(targetMissionId) to api.getTimeline(targetMissionId)
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { pair ->
                        reduceEvent(JarvisEvent.MissionUpdated(pair.first, pair.second))
                        onStatus("Jarvis 任务已刷新")
                    },
                    onFailure = { error -> reduceEvent(JarvisEvent.NetworkFailed(error.message ?: error.javaClass.simpleName)) }
                )
                render()
            }
        }
    }

    private fun cancelMission(targetMissionId: String) {
        val savedToken = saveToken()
        if (savedToken.isBlank()) {
            appendError("请先配置 Jarvis Token")
            return
        }
        executor.execute {
            val result = runCatching {
                val api = JarvisApi(host, savedToken)
                val mission = api.cancelMission(targetMissionId)
                mission to api.getTimeline(targetMissionId)
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { pair ->
                        reduceEvent(JarvisEvent.MissionUpdated(pair.first, pair.second))
                        onStatus("Jarvis 任务已取消")
                    },
                    onFailure = { error -> reduceEvent(JarvisEvent.NetworkFailed(error.message ?: error.javaClass.simpleName)) }
                )
                render()
            }
        }
    }

    private fun requestReport(targetMissionId: String?) {
        val id = targetMissionId ?: missionId
        if (id == null) {
            appendError("暂无可生成报告的 Jarvis 任务")
            return
        }
        val savedToken = saveToken()
        if (savedToken.isBlank()) {
            appendError("请先配置 Jarvis Token")
            return
        }
        executor.execute {
            val result = runCatching { JarvisApi(host, savedToken).getReport(id) }
            runOnUiThread {
                result.fold(
                    onSuccess = {
                        reduceEvent(JarvisEvent.ReportReady(it))
                        onStatus("Jarvis 报告已生成")
                    },
                    onFailure = { error -> reduceEvent(JarvisEvent.NetworkFailed(error.message ?: error.javaClass.simpleName)) }
                )
                render()
            }
        }
    }

    private fun planCard(plan: JarvisMissionPlan): LinearLayout = card("任务计划", "等待确认", palette.accentText).apply {
        addView(body(plan.summary), lp(top = 8))
        addView(listBlock("计划步骤", plan.steps.mapIndexed { index, step ->
            val args = if (step.arguments.isEmpty()) "" else " " + step.arguments.entries.joinToString(", ") { "${it.key}=${it.value}" }
            "${index + 1}. ${step.action.name}$args"
        }), lp(top = 10))
        addView(listBlock("完成条件", plan.completionCriteria.ifEmpty { listOf("等待用户继续确认") }), lp(top = 10))
        addView(body(if (plan.requiresConfirmation) "安全策略：确认后才会执行控制动作" else "安全策略：该计划不包含直接控制动作"), lp(top = 10))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primaryButton("确认执行") { confirmPlan(plan) }, LayoutParams(0, dp(44), 1f))
            addView(outlineButton("修改目标") {
                input.setText(plan.summary)
                input.setSelection(input.text.length)
            }, LayoutParams(0, dp(44), 1f).apply { setMargins(dp(8), 0, 0, 0) })
        }, lp(top = 12))
    }

    private fun controlTaskCard(task: JarvisControlTask): LinearLayout =
        card("控制任务", task.state.name, controlTaskAccent(task.state)).apply {
            addView(TextView(context).apply {
                text = task.title
                setTextColor(color(palette.textPrimary))
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, lp(top = 8))

            if (task.targetValue > 0.0) {
                addView(body("%.2f / %.2f %s".format(task.currentValue, task.targetValue, task.unit)), lp(top = 6))
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                task.steps.forEachIndexed { index, step ->
                    addView(TextView(context).apply {
                        text = when {
                            index < task.completedSteps -> "✓ $step"
                            index == task.completedSteps && task.state in setOf(
                                JarvisControlTaskState.PREPARING,
                                JarvisControlTaskState.STARTING,
                                JarvisControlTaskState.RUNNING
                            ) -> "● $step"
                            else -> "○ $step"
                        }
                        setTextColor(color(if (index < task.completedSteps) successAccent() else palette.textSecondary))
                        textSize = 12f
                        setPadding(0, dp(4), 0, 0)
                    })
                }
            }, lp(top = 8))
            addView(body(task.currentMessage), lp(top = 8))
            if (task.state !in setOf(JarvisControlTaskState.COMPLETED, JarvisControlTaskState.FAILED)) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(outlineButton("停止") { stopControlTask(task.id) }, LayoutParams(0, dp(44), 1f))
                    when (JarvisTaskActions.primaryFor(task.state)) {
                        JarvisTaskPrimaryAction.START -> addTaskPrimary("启动", task.id, ::startControlTask)
                        JarvisTaskPrimaryAction.RETRY -> addTaskPrimary("重试", task.id, ::prepareControlTask)
                        JarvisTaskPrimaryAction.CONTINUE -> addTaskPrimary("继续", task.id, ::prepareControlTask)
                        JarvisTaskPrimaryAction.NONE -> Unit
                    }
                }, lp(top = 12))
            }
        }

    private fun LinearLayout.addTaskPrimary(
        label: String,
        taskId: String,
        action: (String) -> Unit
    ) {
        addView(primaryButton(label) { action(taskId) }, LayoutParams(0, dp(44), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
    }

    private fun startControlTask(taskId: String) {
        val savedToken = saveToken()
        val conversationId = conversations.currentConversationId()
        executor.execute {
            val api = JarvisApi(host, savedToken)
            val result = runCatching { api.startControlTask(taskId) }
            runOnUiThread {
                result.onSuccess {
                    reduceEvent(JarvisEvent.ControlTaskUpdated(it), conversationId)
                    render()
                    pollControlTask(taskId, conversationId)
                }.onFailure { appendError(it.message ?: "控制任务启动失败") }
            }
        }
    }

    private fun prepareControlTask(taskId: String) {
        val savedToken = saveToken()
        val conversationId = conversations.currentConversationId()
        executor.execute {
            val result = runCatching { JarvisApi(host, savedToken).prepareControlTask(taskId) }
            runOnUiThread {
                result.onSuccess {
                    reduceEvent(JarvisEvent.ControlTaskUpdated(it), conversationId)
                    render()
                    pollControlTask(taskId, conversationId)
                }.onFailure { appendError(it.message ?: "控制任务准备失败") }
            }
        }
    }

    private fun pollControlTask(taskId: String, conversationId: String) {
        val savedToken = saveToken()
        executor.execute {
            val api = JarvisApi(host, savedToken)
            while (true) {
                val task = runCatching { api.getControlTask(taskId) }.getOrElse {
                    runOnUiThread { appendError(it.message ?: "控制任务进度读取失败") }
                    return@execute
                }
                runOnUiThread {
                    reduceEvent(JarvisEvent.ControlTaskUpdated(task), conversationId)
                    if (conversations.currentConversationId() == conversationId) render()
                }
                if (task.state in setOf(
                    JarvisControlTaskState.COMPLETED,
                    JarvisControlTaskState.STOPPED,
                    JarvisControlTaskState.FAILED,
                    JarvisControlTaskState.READY,
                    JarvisControlTaskState.PREPARATION_FAILED
                )) return@execute
                Thread.sleep(500)
            }
        }
    }

    private fun resumeTaskPolling() {
        val task = state.chatItems.filterIsInstance<JarvisChatItem.ControlTaskCard>()
            .map { it.task }
            .lastOrNull {
                it.state in setOf(
                    JarvisControlTaskState.PREPARING,
                    JarvisControlTaskState.STARTING,
                    JarvisControlTaskState.RUNNING,
                )
            } ?: return
        pollControlTask(task.id, conversations.currentConversationId())
    }

    private fun stopControlTask(taskId: String) {
        val savedToken = saveToken()
        val conversationId = conversations.currentConversationId()
        executor.execute {
            val result = runCatching { JarvisApi(host, savedToken).stopControlTask(taskId) }
            runOnUiThread {
                result.onSuccess {
                    reduceEvent(JarvisEvent.ControlTaskUpdated(it), conversationId)
                    render()
                }.onFailure { appendError(it.message ?: "控制任务停止失败") }
            }
        }
    }

    private fun progressCard(mission: JarvisMission, timeline: List<JarvisTimelineEntry>): LinearLayout =
        card("任务进度", mission.state.name, successAccent()).apply {
            addView(body("Mission ${mission.id} · 当前状态 ${mission.state.name}"), lp(top = 8))
            val rows = timeline.ifEmpty {
                listOf(JarvisTimelineEntry("", mission.id, "", "state", "等待任务事件", emptyMap()))
            }.map { "${it.kind}: ${it.message}" }
            addView(listBlock("时间线", rows.takeLast(6)), lp(top = 10))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(outlineButton("刷新") { refreshMission(mission.id) }, LayoutParams(0, dp(44), 1f))
                addView(outlineButton("取消任务") { cancelMission(mission.id) }, LayoutParams(0, dp(44), 1f).apply { setMargins(dp(8), 0, 0, 0) })
            }, lp(top = 12))
        }

    private fun reportCard(report: JarvisReport): LinearLayout = card("巡检报告", report.createdAt, warningAccent()).apply {
        addView(body(report.markdown.lineSequence().take(5).joinToString("\n")), lp(top = 8))
        addView(primaryButton("鏌ョ湅瀹屾暣鎶ュ憡") {
            reduceEvent(JarvisEvent.SystemMessageAdded(report.markdown))
            render()
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)).apply { setMargins(0, dp(12), 0, 0) })
    }

    private fun errorCard(title: String, detail: String): LinearLayout = card(title, "ERROR", palette.danger).apply {
        addView(body(detail), lp(top = 8))
    }

    private fun bubble(textValue: String, gravityValue: Int, bg: String, border: String): LinearLayout = LinearLayout(context).apply {
        gravity = gravityValue
        addView(TextView(context).apply {
            text = textValue
            setTextColor(color(palette.textPrimary))
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1.0f)
            background = rounded(bg, border, 18)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        layoutParams = lp(top = 6, bottom = 6)
    }

    private fun systemRow(textValue: String): TextView = TextView(context).apply {
        text = textValue
        gravity = Gravity.CENTER
        setTextColor(color(palette.textSecondary))
        textSize = 11f
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun card(title: String, status: String, accent: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(palette.surface, palette.border, 18)
        setPadding(dp(13), dp(12), dp(13), dp(12))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = title
                setTextColor(color(palette.textPrimary))
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(status, accent))
        })
        layoutParams = lp(top = 8, bottom = 8)
    }

    private fun listBlock(title: String, rows: List<String>): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = title
            setTextColor(color(palette.accentText))
            textSize = 11f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        rows.forEach { row ->
            addView(TextView(context).apply {
                text = row
                setTextColor(color(palette.textPrimary))
                textSize = 12f
                setPadding(0, dp(4), 0, 0)
            })
        }
    }

    private fun body(textValue: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(palette.textSecondary))
        textSize = 12f
        setLineSpacing(dp(2).toFloat(), 1.0f)
    }

    private fun chip(textValue: String, accent: String): TextView = TextView(context).apply {
        text = textValue
        setTextColor(color(accent))
        textSize = 9f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        background = rounded(palette.accentSoft, accent, 12)
        setPadding(dp(8), dp(5), dp(8), dp(5))
    }

    private fun primaryButton(textValue: String, action: () -> Unit): Button = Button(context).apply {
        text = textValue
        setTextColor(Color.WHITE)
        textSize = 12f
        background = rounded(palette.accent, palette.accentText, 16)
        setOnClickListener { action() }
    }

    private fun outlineButton(textValue: String, action: () -> Unit): Button = Button(context).apply {
        text = textValue
        setTextColor(color(palette.textPrimary))
        textSize = 12f
        background = rounded(palette.surfaceAlt, palette.border, 16)
        setOnClickListener { action() }
    }

    private fun iconButton(textValue: String, action: () -> Unit): Button = Button(context).apply {
        text = textValue
        setTextColor(color(palette.accentText))
        textSize = 15f
        background = rounded(palette.surfaceAlt, palette.border, 14)
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
    }

    private fun appendError(message: String) {
        reduceEvent(JarvisEvent.NetworkFailed(message))
        render()
    }

    private fun reduceEvent(
        event: JarvisEvent,
        conversationId: String = conversations.currentConversationId()
    ) {
        val next = conversations.reduce(conversationId, event)
        if (conversationId == conversations.currentConversationId()) state = next
    }

    private fun saveToken(): String {
        token = tokenInput.text.toString().trim()
        if (token.isNotBlank()) credentials.saveToken(token)
        return token
    }

    private fun runOnUiThread(block: () -> Unit) {
        (context as Activity).runOnUiThread(block)
    }

    private fun lp(top: Int = 0, bottom: Int = 0): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, dp(top), 0, dp(bottom))
        }

    private fun rounded(fill: String, stroke: String, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color(fill))
            setStroke(dp(1), color(stroke))
            cornerRadius = dp(radius).toFloat()
        }

    private fun color(hex: String): Int = Color.parseColor(hex)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun successAccent(): String =
        if (themeMode == ParkingThemeMode.LIGHT) "#2F8F5B" else "#B8C878"

    private fun controlTaskAccent(state: JarvisControlTaskState): String = when (state) {
        JarvisControlTaskState.COMPLETED -> successAccent()
        JarvisControlTaskState.FAILED,
        JarvisControlTaskState.PREPARATION_FAILED -> palette.danger
        JarvisControlTaskState.READY -> successAccent()
        JarvisControlTaskState.STOPPED -> warningAccent()
        else -> palette.accentText
    }

    private fun warningAccent(): String =
        if (themeMode == ParkingThemeMode.LIGHT) "#A87516" else "#D6B05D"
}
