package com.example.icarcontroller

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var pageContent: LinearLayout
    private lateinit var scrollContent: ScrollView
    private lateinit var txtStatusPill: TextView
    private lateinit var navViews: Map<String, TextView>

    private var hostInput: EditText? = null
    private var portInput: EditText? = null
    private var txtSpeed: TextView? = null
    private var txtDriveState: TextView? = null
    private var txtLog: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private val moveExecutor = Executors.newSingleThreadExecutor()
    private val stopExecutor = Executors.newSingleThreadExecutor()
    private val moveGate = RequestGate()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private var selectedPage = "home"
    private var currentHost = "10.161.57.230"
    private var currentPort = 8000
    private var speedProgress = 13
    private var currentDirection: String? = null
    private var currentMotionLabel = "待命"
    private var logText = "最近操作会显示在这里"

    private val repeatMoveRunnable = object : Runnable {
        override fun run() {
            val direction = currentDirection ?: return
            sendMove(direction, quiet = true)
            mainHandler.postDelayed(this, InteractionSpec.remoteRepeatMillis().toLong())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pageContent = findViewById(R.id.pageContent)
        scrollContent = findViewById(R.id.scrollContent)
        txtStatusPill = findViewById(R.id.txtStatusPill)
        scrollContent.clipToPadding = false
        pageContent.setPadding(
            pageContent.paddingLeft,
            pageContent.paddingTop,
            pageContent.paddingRight,
            dp(InteractionSpec.contentBottomClearanceDp())
        )
        navViews = mapOf(
            "home" to findViewById(R.id.navHome),
            "drive" to findViewById(R.id.navDrive),
            "tasks" to findViewById(R.id.navTasks),
            "vision" to findViewById(R.id.navVision),
            "nav" to findViewById(R.id.navNav)
        )

        navViews.forEach { (key, view) ->
            view.setOnClickListener { renderPage(key) }
        }

        renderPage("home")
    }

    override fun onDestroy() {
        stopMove()
        commandExecutor.shutdownNow()
        moveExecutor.shutdownNow()
        stopExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun renderPage(key: String) {
        saveConnectionInputs()
        selectedPage = key
        currentDirection = null
        mainHandler.removeCallbacks(repeatMoveRunnable)
        txtSpeed = null
        txtDriveState = null
        txtLog = null
        hostInput = null
        portInput = null

        pageContent.removeAllViews()
        updateNavigation()

        when (key) {
            "home" -> renderHome()
            "drive" -> renderDrive()
            "tasks" -> renderTasks()
            "vision" -> renderVision()
            "nav" -> renderNavigation()
            "ai" -> showAiAssistantSheet()
        }

        animatePageIn()
        scrollContent.post { scrollContent.smoothScrollTo(0, 0) }
    }

    private fun renderHome() {
        pageContent.addView(deviceHomeCard())
        pageContent.addView(homeActionRail())
        pageContent.addView(capabilityRail())
    }

    private fun renderDrive() {
        pageContent.addView(remoteConsole())
        updateSpeedText()
        pageContent.addView(logCard())
    }

    private fun renderTasks() {
        pageContent.addView(compactHero("任务模式", "把实训功能变成一键模式", "避障、跟随、警卫、视觉追踪"))

        FeatureCatalog.trainingTasks().forEach { task ->
            pageContent.addView(taskModeCard(task))
        }

        pageContent.addView(dangerButton("全部停止") {
            currentDirection = null
            mainHandler.removeCallbacks(repeatMoveRunnable)
            sendGet(api().stopAllUrl(), "全部停止")
        })
        pageContent.addView(logCard())
    }

    private fun renderVision() {
        pageContent.addView(compactHero("视觉实验室", "训练完成后接入识别、检测和追踪", "模型训练中"))

        val preview = card()
        preview.addView(sectionHeader("画面预览", "后续接入小车摄像头视频流"))
        preview.addView(placeholderBox("视频流待接入"))
        preview.addView(horizontalButtons(
            primaryButton("启动相机") { sendGet(api().startTaskUrl("camera"), "启动相机") },
            secondaryButton("HSV 调参") { sendGet(api().startTaskUrl("hsv"), "启动 HSV 调参") }
        ))
        pageContent.addView(preview)

        FeatureCatalog.visionFeatures().forEach { item ->
            pageContent.addView(featureInfoCard(item))
        }
        pageContent.addView(logCard())
    }

    private fun renderNavigation() {
        pageContent.addView(compactHero("地图与导航", "预留建图、路径规划和自动导航", "待接入"))

        val mapCard = card()
        mapCard.addView(sectionHeader("地图预览", "未来展示建图结果和目标点"))
        mapCard.addView(placeholderBox("地图区域待接入"))
        pageContent.addView(mapCard)

        FeatureCatalog.navigationFeatures().forEach { item ->
            pageContent.addView(featureInfoCard(item))
        }

        val safety = card()
        safety.addView(sectionHeader("安全控制", "导航接入后仍保留人工介入"))
        safety.addView(bodyText("导航功能接入后，仍会保留人工急停和全部停止。"))
        safety.addView(dangerButton("全部停止") {
            sendGet(api().stopAllUrl(), "全部停止")
        })
        pageContent.addView(safety)
    }

    private fun renderAiAssistant() {
        showAiAssistantSheet()
    }

    private fun showAiAssistantSheet() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.sheet_bg, theme)
            setPadding(dp(18), dp(10), dp(18), dp(18))
        }

        val handle = View(this).apply {
            background = resources.getDrawable(R.drawable.thin_handle_bg, theme)
        }
        panel.addView(handle, LinearLayout.LayoutParams(dp(42), dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, dp(14))
        })
        panel.addView(cardHeader("AI 智能助手", "预留"))
        panel.addView(bodyText("把复杂任务拆成可确认的步骤。当前只生成预览，不直接控制小车。"), matchWrapParams(top = 8))

        val examplesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val examplesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        examplesScroll.addView(examplesRow)
        panel.addView(examplesScroll, matchWrapParams(top = 12))

        val input = EditText(this).apply {
            hint = "例如：检查小车状态，并进入自动避障模式"
            minLines = 3
            gravity = Gravity.TOP
            background = resources.getDrawable(R.drawable.input_bg, theme)
            setTextColor(color("#102A43"))
            setHintTextColor(color("#829AB1"))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        panel.addView(input, matchWrapParams(top = 12))

        val preview = bodyText("任务拆解预览会显示在这里。当前阶段只做界面预留，不直接控制小车。")
        panel.addView(preview, matchWrapParams(top = 12))
        FeatureCatalog.aiExamples().forEach { example ->
            examplesRow.addView(aiExampleChip(example, "示例") {
                input.setText(example)
                input.setSelection(input.text.length)
            }, LinearLayout.LayoutParams(dp(178), dp(58)).apply {
                setMargins(0, 0, dp(8), 0)
            })
        }
        panel.addView(primaryButton("生成任务预览") {
            val text = input.text.toString().ifBlank { "检查小车状态，并进入自动避障模式" }
            preview.text = "任务：$text"
            animateStepPreview(preview, listOf(
                "1. 检查连接状态",
                "2. 启动底盘驱动",
                "3. 检查所需传感器",
                "4. 等待用户确认后执行"
            ))
        })

        panel.addView(dangerButton("急停") {
            sendGet(api().emergencyStopUrl(), "急停")
        })

        dialog.setContentView(panel)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    dimAmount = 0.28f
                }
            }
            panel.translationY = dp(380).toFloat()
            panel.alpha = 0f
            panel.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(InteractionSpec.sheetTransitionMillis().toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        dialog.show()
    }

    private fun deviceHomeCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.device_card_bg, theme)
            setPadding(dp(20), dp(20), dp(20), dp(18))
            elevation = dp(8).toFloat()
            layoutParams = matchWrapParams(bottom = 14)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        copy.addView(TextView(this).apply {
            text = ProductCopy.homeHeadline()
            setTextColor(Color.WHITE)
            textSize = 30f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        copy.addView(TextView(this).apply {
            text = ProductCopy.homeSubtitle()
            setTextColor(color("#DDF7F3"))
            textSize = 14f
        }, matchWrapParams(top = 4))
        top.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val orb = TextView(this).apply {
            text = "X3"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = resources.getDrawable(R.drawable.device_orb_bg, theme)
        }
        top.addView(orb, LinearLayout.LayoutParams(dp(82), dp(82)))
        card.addView(top)

        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        stats.addView(glassStat("连接", "待检测"), LinearLayout.LayoutParams(0, dp(62), 1f))
        stats.addView(glassStat("遥控", "就绪"), LinearLayout.LayoutParams(0, dp(62), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        stats.addView(glassStat("扩展", "进行中"), LinearLayout.LayoutParams(0, dp(62), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        card.addView(stats, matchWrapParams(top = 18))
        card.addView(connectionDock(), matchWrapParams(top = 16))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(primaryButton(ProductCopy.primaryAction()) { renderPage("drive") }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(secondaryHeroButton("AI 助手") { showAiAssistantSheet() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        card.addView(actions, matchWrapParams(top = 16))
        return card
    }

    private fun homeActionRail(): HorizontalScrollView {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(dp(InteractionSpec.homeRailSideInsetDp()), 0, dp(InteractionSpec.homeRailSideInsetDp()), 0)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, dp(8), 0)
        }
        listOf(
            HomeAction("驾驶", "进入遥控器", "可用") { renderPage("drive") },
            HomeAction("自动避障", "一键启动", "可用") { sendGet(api().startTaskUrl("avoidance"), "启动自动避障") },
            HomeAction("AI 助手", "任务拆解", "规划中") { showAiAssistantSheet() },
            HomeAction("视觉", "识别检测", "训练中") { renderPage("vision") }
        ).forEach { action ->
            row.addView(largeActionTile(action), LinearLayout.LayoutParams(dp(156), dp(110)).apply {
                setMargins(0, 0, dp(12), 0)
            })
        }
        scroll.addView(row)
        scroll.layoutParams = matchWrapParams(bottom = 14)
        return scroll
    }

    private fun capabilityRail(): LinearLayout {
        val shell = card()
        shell.addView(sectionHeader("能力雷达", "左右滑动查看后续能力"))
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        FeatureCatalog.homeHighlights().forEach { item ->
            row.addView(capabilityTile(item), LinearLayout.LayoutParams(dp(190), dp(120)).apply {
                setMargins(0, dp(10), dp(10), 0)
            })
        }
        scroll.addView(row)
        shell.addView(scroll)
        return shell
    }

    private fun driveHeader(): LinearLayout {
        return compactHero("驾驶", "大按钮遥控，小车响应更直接", "手动遥控")
    }

    private fun remoteConsole(): LinearLayout {
        val console = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.remote_console_bg, theme)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            elevation = dp(8).toFloat()
            layoutParams = matchWrapParams(bottom = 12)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(this).apply {
            text = "遥控器"
            setTextColor(color("#102A43"))
            textSize = 22f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        copy.addView(TextView(this).apply {
            text = "按住移动，松手刹停"
            setTextColor(color("#627D98"))
            textSize = 13f
            maxLines = 1
        }, matchWrapParams(top = 3))
        top.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(secondaryButton("底盘") {
            sendGet(api().startTaskUrl("base"), "启动底盘驱动")
        }, LinearLayout.LayoutParams(dp(86), dp(42)))
        top.addView(dangerButton("急停") {
            currentDirection = null
            mainHandler.removeCallbacks(repeatMoveRunnable)
            sendGet(api().emergencyStopUrl(), "急停")
        }, LinearLayout.LayoutParams(dp(76), dp(42)).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        console.addView(top)

        txtDriveState = TextView(this).apply {
            text = "当前动作：$currentMotionLabel"
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = resources.getDrawable(R.drawable.remote_status_bg, theme)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(13), dp(12), dp(13))
        }
        console.addView(txtDriveState, matchWrapParams(top = 14))

        console.addView(speedControlDeck(), matchWrapParams(top = 14))
        console.addView(movementGrid(), matchWrapParams(top = 14))
        return console
    }

    private fun speedControlDeck(): LinearLayout {
        val deck = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.surface_card_bg, theme)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        deck.addView(sectionHeader("速度档位", "先低速测试，确认空间安全后再提高"))
        deck.addView(speedPresetRow())
        txtSpeed = bodyText("").apply {
            setTextColor(color("#334E68"))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        deck.addView(txtSpeed, matchWrapParams(top = 8))
        deck.addView(SeekBar(this).apply {
            max = 30
            progress = speedProgress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    speedProgress = progress
                    updateSpeedText()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        })
        return deck
    }

    private fun largeActionTile(action: HomeAction): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.home_tile_bg, theme)
            setPadding(dp(15), dp(14), dp(15), dp(14))
            elevation = dp(6).toFloat()
            isClickable = true
            setOnClickListener { action.onClick() }
            setPressFeedback(this)
        }
        tile.addView(TextView(this).apply {
            text = action.title
            setTextColor(color("#102A43"))
            textSize = 19f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        tile.addView(TextView(this).apply {
            text = action.subtitle
            setTextColor(color("#627D98"))
            textSize = 13f
        }, matchWrapParams(top = 5))
        tile.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        tile.addView(chip(action.status))
        return tile
    }

    private fun capabilityTile(item: FeatureItem): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.subtle_tile_bg, theme)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        tile.addView(chip(item.status))
        tile.addView(TextView(this).apply {
            text = item.title
            setTextColor(color("#102A43"))
            textSize = 17f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, matchWrapParams(top = 10))
        tile.addView(TextView(this).apply {
            text = item.description
            setTextColor(color("#627D98"))
            textSize = 12f
            maxLines = 2
        }, matchWrapParams(top = 5))
        return tile
    }

    private fun compactHero(title: String, subtitle: String, tag: String): LinearLayout {
        val hero = card().apply {
            background = resources.getDrawable(R.drawable.device_card_bg, theme)
            elevation = dp(6).toFloat()
        }
        hero.addView(chipOnDark(tag))
        hero.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, matchWrapParams(top = 10))
        hero.addView(TextView(this).apply {
            text = subtitle
            setTextColor(color("#DDF7F3"))
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }, matchWrapParams(top = 6))
        return hero
    }

    private fun actionGrid(actions: List<HomeAction>): GridLayout {
        val grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
        }
        actions.forEach { action ->
            grid.addView(actionTile(action), GridLayout.LayoutParams().apply {
                width = 0
                height = dp(108)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(6), dp(4), dp(6))
            })
        }
        return grid
    }

    private fun actionTile(action: HomeAction): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.subtle_tile_bg, theme)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            isClickable = true
            elevation = dp(1).toFloat()
            setOnClickListener { action.onClick() }
            setPressFeedback(this)
        }
        tile.addView(TextView(this).apply {
            text = action.title
            setTextColor(color("#102A43"))
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        tile.addView(TextView(this).apply {
            text = action.subtitle
            setTextColor(color("#627D98"))
            textSize = 13f
        }, matchWrapParams(top = 4))
        tile.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        tile.addView(chip(action.status))
        return tile
    }

    private fun speedPresetRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        row.addView(speedButton("慢速", 5), LinearLayout.LayoutParams(0, dp(40), 1f))
        row.addView(speedButton("标准", 13), LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        row.addView(speedButton("快速", 23), LinearLayout.LayoutParams(0, dp(40), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        return row.apply {
            layoutParams = matchWrapParams(top = 12, bottom = 6)
        }
    }

    private fun speedButton(text: String, progress: Int): Button {
        val selected = speedProgress == progress
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(color(if (selected) "#FFFFFF" else "#35506B"))
            textSize = 13f
            background = resources.getDrawable(if (selected) R.drawable.speed_selected_bg else R.drawable.speed_plain_bg, theme)
            minHeight = 0
            minimumHeight = 0
            includeFontPadding = false
            setOnClickListener {
                speedProgress = progress
                renderPage("drive")
            }
        }
    }

    private fun taskModeCard(task: RobotTask): LinearLayout {
        val card = card()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(modeBadge(task.title.take(1)), LinearLayout.LayoutParams(dp(52), dp(52)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = task.title
            setTextColor(color("#102A43"))
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = task.description
            setTextColor(color("#627D98"))
            textSize = 13f
        }, matchWrapParams(top = 4))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(12), 0, 0, 0)
        })
        row.addView(chip(task.category))
        card.addView(row)
        card.addView(horizontalButtons(
            primaryButton("开始") { sendGet(api().startTaskUrl(task.key), "启动${task.title}") },
            secondaryButton("停止") { sendGet(api().stopTaskUrl(task.key), "停止${task.title}") }
        ))
        return card
    }

    private fun sectionHeader(title: String, subtitle: String): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        header.addView(sectionTitle(title))
        header.addView(TextView(this).apply {
            text = subtitle
            setTextColor(color("#829AB1"))
            textSize = 13f
        }, matchWrapParams(top = 3))
        return header
    }

    private fun glassStat(title: String, value: String): LinearLayout {
        val stat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = resources.getDrawable(R.drawable.chip_dark_bg, theme)
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }
        stat.addView(TextView(this).apply {
            text = value
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        stat.addView(TextView(this).apply {
            text = title
            setTextColor(color("#DDF7F3"))
            textSize = 11f
            gravity = Gravity.CENTER
        })
        return stat
    }

    private fun connectionDock(): LinearLayout {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.connection_dock_bg, theme)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "设备连接"
            setTextColor(Color.WHITE)
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(chipOnDark("HTTP ${currentHost}:${currentPort}"))
        dock.addView(titleRow)

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hostInput = EditText(this).apply {
            setText(currentHost)
            hint = "小车 IP"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = resources.getDrawable(R.drawable.input_bg, theme)
            setTextColor(color("#102A43"))
            setHintTextColor(color("#829AB1"))
            setPadding(dp(12), 0, dp(12), 0)
        }
        portInput = EditText(this).apply {
            setText(currentPort.toString())
            hint = "端口"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            background = resources.getDrawable(R.drawable.input_bg, theme)
            setTextColor(color("#102A43"))
            setHintTextColor(color("#829AB1"))
            setPadding(dp(12), 0, dp(12), 0)
        }
        inputRow.addView(hostInput, LinearLayout.LayoutParams(0, dp(44), 1f))
        inputRow.addView(portInput, LinearLayout.LayoutParams(dp(82), dp(44)).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        dock.addView(inputRow, matchWrapParams(top = 10))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(secondaryHeroButton("检测连接") { sendGet(api().healthUrl(), "检测连接") }, LinearLayout.LayoutParams(0, dp(42), 1f))
        actions.addView(secondaryHeroButton("刷新状态") { sendGet(api().statusUrl(), "刷新状态") }, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        dock.addView(actions, matchWrapParams(top = 10))
        return dock
    }

    private fun secondaryHeroButton(text: String, action: () -> Unit): Button =
        styledButton(text, R.drawable.button_on_dark, color("#0B6B62"), action)

    private fun chipOnDark(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            background = resources.getDrawable(R.drawable.chip_dark_bg, theme)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun modeBadge(text: String): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(color("#0F766E"))
            textSize = 20f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = resources.getDrawable(R.drawable.mode_badge_bg, theme)
        }

    private fun connectionCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("连接小车"))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        hostInput = EditText(this).apply {
            setText(currentHost)
            hint = "小车 IP"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            background = resources.getDrawable(R.drawable.input_bg, theme)
            setTextColor(color("#102A43"))
            setHintTextColor(color("#829AB1"))
            setPadding(dp(12), 0, dp(12), 0)
        }
        portInput = EditText(this).apply {
            setText(currentPort.toString())
            hint = "端口"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            background = resources.getDrawable(R.drawable.input_bg, theme)
            setTextColor(color("#102A43"))
            setHintTextColor(color("#829AB1"))
            setPadding(dp(12), 0, dp(12), 0)
        }

        row.addView(hostInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(portInput, LinearLayout.LayoutParams(dp(86), dp(48)).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        card.addView(row, matchWrapParams(top = 10))
        card.addView(horizontalButtons(
            primaryButton("检测连接") { sendGet(api().healthUrl(), "检测连接") },
            secondaryButton("刷新状态") { sendGet(api().statusUrl(), "刷新状态") }
        ))
        card.addView(dangerButton("全部停止") { sendGet(api().stopAllUrl(), "全部停止") })
        return card
    }

    private fun movementGrid(): GridLayout {
        val grid = GridLayout(this).apply {
            columnCount = 3
            rowCount = 3
        }

        addMoveButton(grid, "↶\n左转", "turn_left")
        addMoveButton(grid, "↑\n前进", "front")
        addMoveButton(grid, "↷\n右转", "turn_right")
        addMoveButton(grid, "←\n左移", "left")
        grid.addView(stopButton(), gridParams())
        addMoveButton(grid, "→\n右移", "right")
        grid.addView(Space(this), gridParams())
        addMoveButton(grid, "↓\n后退", "back")
        grid.addView(Space(this), gridParams())
        return grid
    }

    private fun addMoveButton(grid: GridLayout, text: String, direction: String) {
        val button = moveButton(text)
        setupHoldButton(button, direction)
        grid.addView(button, gridParams())
    }

    private fun setupHoldButton(button: Button, direction: String) {
        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate()
                        .scaleX(InteractionSpec.pressFeedbackScale())
                        .scaleY(InteractionSpec.pressFeedbackScale())
                        .alpha(0.86f)
                        .setDuration(90)
                        .start()
                    startMove(direction)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(180)
                        .setInterpolator(OvershootInterpolator())
                        .start()
                    stopMove()
                    true
                }
                else -> true
            }
        }
    }

    private fun startMove(direction: String) {
        currentDirection = direction
        currentMotionLabel = directionLabel(direction)
        updateDriveState()
        mainHandler.removeCallbacks(repeatMoveRunnable)
        sendMove(direction, quiet = false)
        mainHandler.postDelayed(repeatMoveRunnable, InteractionSpec.remoteRepeatMillis().toLong())
    }

    private fun stopMove() {
        currentDirection = null
        currentMotionLabel = "待命"
        updateDriveState()
        mainHandler.removeCallbacks(repeatMoveRunnable)
        val url = api().moveUrl("stop", currentSpeed(), currentTurn())
        setStatus("停止指令已发送")
        sendGet(url, "停止移动", quiet = true, executorService = stopExecutor)
    }

    private fun sendMove(direction: String, quiet: Boolean) {
        val url = api().moveUrl(direction, currentSpeed(), currentTurn())
        if (!moveGate.tryBegin()) {
            return
        }

        if (!quiet) {
            setStatus("移动 $direction 指令已发送")
        }

        sendGet(
            url = url,
            label = "移动 $direction",
            quiet = true,
            executorService = moveExecutor,
            onFinished = { moveGate.finish() }
        )
    }

    private fun sendGet(
        url: String,
        label: String,
        quiet: Boolean = false,
        executorService: ExecutorService = commandExecutor,
        onFinished: () -> Unit = {}
    ) {
        if (!quiet) {
            setStatus("$label 请求中")
        }

        executorService.execute {
            val result = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2500
                    readTimeout = if (quiet) 1200 else 5000
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { input ->
                    BufferedReader(InputStreamReader(input)).readText()
                }.orEmpty()
                connection.disconnect()
                "HTTP $code ${body.take(180)}"
            }.getOrElse { error ->
                "失败：${error.message ?: error.javaClass.simpleName}"
            }

            runOnUiThread {
                if (!quiet) {
                    setStatus("$label $result")
                    appendLog("$label\n$result")
                }
            }
            onFinished()
        }
    }

    private fun api(): CarApi {
        saveConnectionInputs()
        return CarApi(currentHost, currentPort)
    }

    private fun saveConnectionInputs() {
        hostInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            currentHost = it
        }
        portInput?.text?.toString()?.toIntOrNull()?.let {
            currentPort = it
        }
    }

    private fun currentSpeed(): Double =
        (0.05 + speedProgress / 100.0).coerceIn(0.05, 0.35)

    private fun currentTurn(): Double =
        (0.45 + currentSpeed() * 2.0).coerceIn(0.55, 1.15)

    private fun updateSpeedText() {
        txtSpeed?.text = String.format(Locale.US, "速度 %.2f m/s，转向 %.2f rad/s", currentSpeed(), currentTurn())
    }

    private fun updateDriveState() {
        txtDriveState?.animate()
            ?.alpha(0.55f)
            ?.scaleX(0.98f)
            ?.scaleY(0.98f)
            ?.setDuration(80)
            ?.withEndAction {
            txtDriveState?.text = "当前动作：$currentMotionLabel"
            txtDriveState?.animate()
                ?.alpha(1f)
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.setDuration(InteractionSpec.statusPulseMillis().toLong())
                ?.setInterpolator(OvershootInterpolator())
                ?.start()
        }?.start()
    }

    private fun directionLabel(direction: String): String =
        when (direction) {
            "front" -> "前进中"
            "back" -> "后退中"
            "left" -> "左移中"
            "right" -> "右移中"
            "turn_left" -> "左转中"
            "turn_right" -> "右转中"
            else -> "待命"
        }

    private fun setStatus(text: String) {
        txtStatusPill.text = text
    }

    private fun appendLog(message: String) {
        val time = timeFormat.format(Date())
        logText = "[$time] $message\n\n$logText".take(2200)
        txtLog?.text = logText
    }

    private fun updateNavigation() {
        navViews.forEach { (key, view) ->
            val selected = key == selectedPage
            view.background = resources.getDrawable(
                if (selected) R.drawable.nav_selected_bg else R.drawable.nav_plain_bg,
                theme
            )
            view.setTextColor(color(if (selected) "#0F766E" else "#627D98"))
            view.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
            view.animate()
                .scaleX(if (selected) InteractionSpec.navSelectionScale() else 1f)
                .scaleY(if (selected) InteractionSpec.navSelectionScale() else 1f)
                .setDuration(InteractionSpec.pageTransitionMillis().toLong())
                .start()
        }
    }

    private fun animatePageIn() {
        pageContent.alpha = 0f
        pageContent.translationY = dp(18).toFloat()
        pageContent.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(InteractionSpec.pageTransitionMillis().toLong())
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateStepPreview(target: TextView, steps: List<String>) {
        val base = target.text.toString()
        target.text = base
        steps.forEachIndexed { index, step ->
            mainHandler.postDelayed({
                target.text = "${target.text}\n$step"
                target.alpha = 0.75f
                target.animate().alpha(1f).setDuration(140).start()
            }, 180L * (index + 1))
        }
    }

    private fun setPressFeedback(view: View) {
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touched.animate()
                        .scaleX(InteractionSpec.pressFeedbackScale())
                        .scaleY(InteractionSpec.pressFeedbackScale())
                        .setDuration(90)
                        .start()
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touched.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(OvershootInterpolator())
                        .start()
                    false
                }
                else -> false
            }
        }
    }

    private fun heroCard(title: String, subtitle: String): LinearLayout {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.hero_bg, theme)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        hero.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        hero.addView(TextView(this).apply {
            text = subtitle
            setTextColor(color("#E8F6F3"))
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }, matchWrapParams(top = 8))
        return hero.apply {
            layoutParams = matchWrapParams(bottom = 14)
        }
    }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.soft_card_bg, theme)
            setPadding(dp(15), dp(15), dp(15), dp(15))
            layoutParams = matchWrapParams(bottom = 12)
        }

    private fun featureInfoCard(item: FeatureItem): LinearLayout {
        val card = card()
        card.addView(cardHeader(item.title, item.status))
        card.addView(bodyText(item.description))
        card.addView(disabledButton("等待接入"))
        return card
    }

    private fun featureButton(title: String, description: String, status: String, action: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = resources.getDrawable(R.drawable.feature_card_bg, theme)
            setPadding(dp(13), dp(12), dp(13), dp(12))
            setOnClickListener { action() }
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(color("#102A43"))
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = description
            setTextColor(color("#627D98"))
            textSize = 13f
        }, matchWrapParams(top = 4))
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip(status))
        return row.apply {
            layoutParams = matchWrapParams(top = 8)
        }
    }

    private fun aiExampleChip(text: String, badge: String, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = if (badge.isBlank()) text else "$badge\n$text"
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(color("#102A43"))
            textSize = 12f
            maxLines = 2
            background = resources.getDrawable(R.drawable.home_tile_bg, theme)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { action() }
            setPressFeedback(this)
        }

    private fun statusRow(title: String, description: String, status: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = title
            setTextColor(color("#102A43"))
            textSize = 15f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = description
            setTextColor(color("#627D98"))
            textSize = 13f
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip(status))
        return row
    }

    private fun cardHeader(title: String, chipText: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(color("#102A43"))
                textSize = 17f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(chipText))
        }

    private fun sectionTitle(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color("#102A43"))
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun bodyText(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color("#627D98"))
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }

    private fun chip(text: String): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color(if (text.contains("可用") || text.contains("基础") || text.contains("实训")) "#166534" else "#92400E"))
            textSize = 12f
            gravity = Gravity.CENTER
            background = resources.getDrawable(
                if (text.contains("可用") || text.contains("基础") || text.contains("实训")) R.drawable.chip_ready_bg else R.drawable.chip_wait_bg,
                theme
            )
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }

    private fun placeholderBox(text: String): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(color("#829AB1"))
            textSize = 15f
            background = resources.getDrawable(R.drawable.input_bg, theme)
            layoutParams = matchWrapParams(top = 10, bottom = 10).apply {
                height = dp(150)
            }
        }

    private fun logCard(): LinearLayout {
        val card = card()
        card.addView(sectionTitle("最近操作"))
        txtLog = bodyText(logText).apply {
            setTextColor(color("#334E68"))
        }
        card.addView(txtLog, matchWrapParams(top = 8))
        return card
    }

    private fun primaryButton(text: String, action: () -> Unit): Button =
        styledButton(text, R.drawable.button_primary, Color.WHITE, action)

    private fun secondaryButton(text: String, action: () -> Unit): Button =
        styledButton(text, R.drawable.button_secondary, color("#102A43"), action)

    private fun dangerButton(text: String, action: () -> Unit): Button =
        styledButton(text, R.drawable.button_danger, Color.WHITE, action)

    private fun moveButton(text: String): Button =
        styledButton(text, R.drawable.button_move, Color.WHITE) {}.apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(dp(1).toFloat(), 1.0f)
        }

    private fun stopButton(): Button =
        styledButton("■\n停止", R.drawable.button_stop, Color.WHITE) { stopMove() }.apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(dp(1).toFloat(), 1.0f)
        }

    private fun disabledButton(text: String): Button =
        styledButton(text, R.drawable.button_secondary, color("#627D98")) {}.apply {
            isEnabled = false
            alpha = 0.72f
        }

    private fun styledButton(text: String, backgroundRes: Int, textColor: Int, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(textColor)
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = resources.getDrawable(backgroundRes, theme)
            setOnClickListener { action() }
            minHeight = 0
            minimumHeight = 0
            includeFontPadding = false
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = matchWrapParams(top = 10).apply {
                height = dp(46)
            }
        }

    private fun horizontalButtons(left: Button, right: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(left, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(right, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
                setMargins(dp(8), 0, 0, 0)
            })
            layoutParams = matchWrapParams(top = 12)
        }

    private fun gridParams(): GridLayout.LayoutParams =
        GridLayout.LayoutParams().apply {
            width = 0
            height = dp(InteractionSpec.remoteButtonSizeDp())
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(5), dp(5), dp(5), dp(5))
        }

    private fun matchWrapParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, dp(top), 0, dp(bottom))
        }

    private fun color(hex: String): Int = Color.parseColor(hex)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private data class HomeAction(
        val title: String,
        val subtitle: String,
        val status: String,
        val onClick: () -> Unit
    )
}
