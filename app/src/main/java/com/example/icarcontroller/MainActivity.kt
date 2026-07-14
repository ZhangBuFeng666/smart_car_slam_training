package com.example.icarcontroller

import android.app.Activity
import android.app.Dialog
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val VISION_PORT = 8200
private const val VISION_POLL_INTERVAL_MILLIS = 1_000L

private data class FullscreenAccessibilityState(
    val view: View,
    val importantForAccessibility: Int,
    val isFocusable: Boolean,
    val isFocusableInTouchMode: Boolean,
    val descendantFocusability: Int?
)

class MainActivity : Activity() {
    private lateinit var pageHost: FrameLayout
    private lateinit var pageContent: LinearLayout
    private lateinit var scrollContent: ScrollView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var txtAppTitle: TextView
    private lateinit var txtStatusPill: TextView
    private lateinit var globalThemeToggle: TextView
    private var jarvisChatPage: JarvisChatPage? = null
    private lateinit var navViews: Map<String, TextView>
    private lateinit var parkingThemeStore: ParkingThemeStore
    private lateinit var jarvisCredentials: JarvisCredentials
    private lateinit var jarvisConversationController: JarvisConversationController

    private var hostInput: EditText? = null
    private var portInput: EditText? = null
    private var txtSpeed: TextView? = null
    private var txtDriveState: TextView? = null
    private var txtLog: TextView? = null
    private var vehicleStage: Vehicle3DStageView? = null
    private var parkingVisionView: ParkingVisionView? = null
    private var visionStreamView: MjpegStreamView? = null
    private var visionOverlayView: VisionDetectionOverlayView? = null
    private var visionStreamStatusText: TextView? = null
    private var visionModelStatusText: TextView? = null
    private var visionPerformanceText: TextView? = null
    private var visionDetectionList: LinearLayout? = null
    private val visionMetricValues = mutableListOf<TextView>()
    private var parkingMapView: ParkingMapView? = null
    private var parkingMapCaption: TextView? = null
    private var parkingWaypointStatusText: TextView? = null
    private var navigationManualSpeedText: TextView? = null
    private var navigationManualStateText: TextView? = null
    private var driveCameraPanel: DriveCameraPanel? = null
    private var fullscreenDriveOverlay: FullscreenDriveOverlay? = null
    private var fullscreenDrivePending = false
    private var fullscreenAccessibilityState: List<FullscreenAccessibilityState>? = null
    private var driveSpeedSeekBar: SeekBar? = null
    private var homeConnectionText: TextView? = null
    private var pageStatusText: TextView? = null
    private var jarvisTokenInput: EditText? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val commandExecutor = Executors.newSingleThreadExecutor()
    private val moveExecutor = Executors.newSingleThreadExecutor()
    private val stopExecutor = Executors.newSingleThreadExecutor()
    private val voiceExecutor = Executors.newSingleThreadExecutor()
    private val navigationExecutor = Executors.newSingleThreadExecutor()
    private val visionExecutor = Executors.newSingleThreadExecutor()
    private val visionPollingGate = VisionPollingGate()
    private val moveGate = RequestGate()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    private var selectedPage = "home"
    private var currentHost = "10.161.57.230"
    private var currentPort = 8000
    private var currentRosContainer = "8b98"
    private var jarvisCurrentPlan: JarvisMissionPlan? = null
    private var jarvisMissionId: String? = null
    private var jarvisToken = ""
    private var speedProgress = 13
    private var currentDirection: String? = null
    private var currentMotionLabel = "待命"
    private var logText = "最近操作会显示在这里"
    private var isVehicleConnected = false
    private var parkingThemeMode = ParkingThemeMode.LIGHT
    private var navigationMapGeneration = -1L
    @Volatile private var navigationPollInFlight = false
    private val navigationPollRunnable = Runnable { pollNavigationState() }
    @Volatile private var visionPollInFlight = false
    private var visionPollingToken = 0L
    private val visionPollRunnable = Runnable { pollVisionState() }

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

        parkingThemeStore = ParkingThemeStore(this)
        parkingThemeMode = parkingThemeStore.load()
        jarvisCredentials = JarvisCredentials(this)
        jarvisToken = jarvisCredentials.loadToken()
        jarvisConversationController = JarvisConversationController(this)

        pageHost = findViewById(R.id.pageHost)
        pageContent = findViewById(R.id.pageContent)
        scrollContent = findViewById(R.id.scrollContent)
        scrollContent.isVerticalScrollBarEnabled = false
        topBar = findViewById(R.id.topBar)
        bottomNav = findViewById(R.id.bottomNav)
        val activityRoot = findViewById<View>(android.R.id.content)
        activityRoot.viewTreeObserver.addOnGlobalLayoutListener {
            val visibleFrame = Rect()
            activityRoot.getWindowVisibleDisplayFrame(visibleFrame)
            val keyboardHeight = activityRoot.rootView.height - visibleFrame.bottom
            val keyboardVisible = keyboardHeight > activityRoot.rootView.height * 0.15f
            bottomNav.visibility = if (selectedPage == "ai" && keyboardVisible) View.GONE else View.VISIBLE
        }
        txtAppTitle = findViewById(R.id.txtAppTitle)
        txtStatusPill = findViewById(R.id.txtStatusPill)
        globalThemeToggle = findViewById(R.id.globalThemeToggle)
        globalThemeToggle.setOnClickListener {
            forceStopForExit(DriveExitEvent.THEME_CHANGE)
            releaseDriveCameraPanel()
            parkingThemeMode = ParkingThemeSpec.nextMode(parkingThemeMode)
            parkingThemeStore.save(parkingThemeMode)
            renderPage(selectedPage)
        }
        setPressFeedback(globalThemeToggle)
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
            "ai" to findViewById(R.id.navTasks),
            "vision" to findViewById(R.id.navVision),
            "nav" to findViewById(R.id.navNav)
        )

        navViews.forEach { (key, view) ->
            view.setOnClickListener { renderPage(key) }
        }

        renderPage("home")
    }

    override fun onDestroy() {
        exitFullscreenDrive(DriveExitEvent.APP_PAUSE)
        releaseDriveCameraPanel()
        releaseVisionPage()
        vehicleStage?.destroy()
        vehicleStage = null
        stopMove()
        stopNavigationPolling()
        commandExecutor.shutdownNow()
        moveExecutor.shutdown()
        stopExecutor.shutdown()
    voiceExecutor.shutdown()
        navigationExecutor.shutdownNow()
        visionExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        vehicleStage?.onHostResume()
        parkingVisionView?.setActive(true)
        if (selectedPage == "drive") {
            driveCameraPanel?.start(api().cameraStreamUrl())
        }
        if (selectedPage == "vision") startVisionPage()
        if (selectedPage == "nav") startNavigationPolling()
    }

    override fun onPause() {
        exitFullscreenDrive(DriveExitEvent.APP_PAUSE)
        forceStopForExit(DriveExitEvent.APP_PAUSE)
        driveCameraPanel?.stop()
        stopVisionPage()
        vehicleStage?.onHostPause()
        parkingVisionView?.setActive(false)
        stopNavigationPolling()
        super.onPause()
    }

    private fun renderPage(key: String) {
        exitFullscreenDrive(DriveExitEvent.PAGE_CHANGE)
        forceStopForExit(DriveExitEvent.PAGE_CHANGE)
        releaseDriveCameraPanel()
        releaseVisionPage()
        stopNavigationPolling()
        saveConnectionInputs()
        selectedPage = key
        currentDirection = null
        mainHandler.removeCallbacks(repeatMoveRunnable)
        txtSpeed = null
        txtDriveState = null
        txtLog = null
        driveSpeedSeekBar = null
        vehicleStage?.destroy()
        vehicleStage = null
        parkingVisionView = null
        visionMetricValues.clear()
        visionStreamStatusText = null
        visionOverlayView = null
        visionModelStatusText = null
        visionPerformanceText = null
        visionDetectionList = null
        parkingMapView = null
        parkingMapCaption = null
        navigationManualSpeedText = null
        navigationManualStateText = null
        homeConnectionText = null
        pageStatusText = null
        hostInput = null
        portInput = null

        pageContent.removeAllViews()
        jarvisChatPage?.let(pageHost::removeView)
        jarvisChatPage = null
        scrollContent.visibility = if (key == "ai") View.GONE else View.VISIBLE
        pageContent.layoutParams = pageContent.layoutParams.apply {
            height = if (key == "ai") ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        pageContent.setPadding(
            pageContent.paddingLeft,
            pageContent.paddingTop,
            pageContent.paddingRight,
            if (key == "ai") 0 else dp(InteractionSpec.contentBottomClearanceDp())
        )
        updateChrome(key)
        updateTopBar(key)
        updateNavigation()

        when (key) {
            "home" -> renderHome()
            "drive" -> renderDrive()
            "ai" -> renderAiPage()
            "vision" -> renderVision()
            "nav" -> renderNavigation()
        }

        if (key == "nav") {
            navigationMapGeneration = -1L
            startNavigationPolling()
        }
        if (key == "vision") startVisionPage()

        animatePageIn()
        scrollContent.post { scrollContent.smoothScrollTo(0, 0) }
    }

    private fun releaseDriveCameraPanel() {
        driveCameraPanel?.release()
        driveCameraPanel = null
    }

    private fun enterFullscreenDrive() {
        if (fullscreenDriveOverlay != null || fullscreenDrivePending) return
        if (driveCameraPanel == null) return

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            showFullscreenDriveOverlay()
        } else {
            fullscreenDrivePending = true
        }
    }

    private fun showFullscreenDriveOverlay() {
        if (
            fullscreenDriveOverlay != null ||
            resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        ) return
        val panel = driveCameraPanel ?: return

        val overlay = FullscreenDriveOverlay(
            context = this,
            streamView = panel.streamViewForReparent(),
            directionLabel = ::directionLabel,
            onMoveStart = ::startMove,
            isDirectionActive = { direction -> currentDirection == direction },
            onStop = ::stopMove,
            onExit = { exitFullscreenDrive(DriveExitEvent.PAGE_CHANGE) },
            onSpeedProgressChanged = { progress ->
                speedProgress = fullscreenSpeedSelection(progress).progress
                updateSpeedText()
            },
            initialSpeedProgress = speedProgress,
            initialSnapshot = panel.currentSnapshot(),
            initialMotion = currentMotionLabel
        )
        fullscreenDrivePending = false
        fullscreenDriveOverlay = overlay
        hideUnderlyingAccessibility()
        (window.decorView as ViewGroup).addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        panel.setSnapshotObserver(overlay::updateCameraSnapshot)
        hideDriveSystemUi()
        overlay.requestFocus()
    }

    private fun exitFullscreenDrive(event: DriveExitEvent) {
        val wasPending = fullscreenDrivePending
        fullscreenDrivePending = false
        val overlay = fullscreenDriveOverlay
        if (overlay == null) {
            if (wasPending) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            return
        }
        forceStopForExit(event)

        val panel = driveCameraPanel
        if (panel != null) {
            panel.setSnapshotObserver(null)
            panel.restoreStreamView(overlay.streamView)
        } else {
            overlay.streamView.beginReparent()
            overlay.removeView(overlay.streamView)
        }
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        fullscreenDriveOverlay = null
        restoreUnderlyingAccessibility()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        showDriveSystemUi()
    }

    private fun hideDriveSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        }
    }

    private fun showDriveSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        }
        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
        updateChrome(selectedPage)
    }

    private fun hideUnderlyingAccessibility() {
        val views = listOf<View>(topBar, pageContent, bottomNav)
        fullscreenAccessibilityState = views.map { view ->
            FullscreenAccessibilityState(
                view = view,
                importantForAccessibility = view.importantForAccessibility,
                isFocusable = view.isFocusable,
                isFocusableInTouchMode = view.isFocusableInTouchMode,
                descendantFocusability = (view as? ViewGroup)?.descendantFocusability
            )
        }
        views.forEach { view ->
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            view.isFocusable = false
            view.isFocusableInTouchMode = false
            (view as? ViewGroup)?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
    }

    private fun restoreUnderlyingAccessibility() {
        fullscreenAccessibilityState?.forEach { state ->
            state.view.importantForAccessibility = state.importantForAccessibility
            state.view.isFocusable = state.isFocusable
            state.view.isFocusableInTouchMode = state.isFocusableInTouchMode
            state.descendantFocusability?.let { value ->
                (state.view as? ViewGroup)?.descendantFocusability = value
            }
        }
        fullscreenAccessibilityState = null
    }

    override fun onBackPressed() {
        if (fullscreenDriveOverlay != null || fullscreenDrivePending) {
            exitFullscreenDrive(DriveExitEvent.PAGE_CHANGE)
        } else if (jarvisChatPage?.handleBack() == true) {
            return
        } else {
            super.onBackPressed()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (fullscreenDrivePending && newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            showFullscreenDriveOverlay()
            return
        }
        if (
            fullscreenDriveOverlay != null &&
            newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE
        ) {
            exitFullscreenDrive(DriveExitEvent.PAGE_CHANGE)
        }
    }

    private fun renderHome() {
        pageContent.addView(digitalKeyStage())
    }

    private fun renderDrive() {
        pageContent.addView(parkingPageHeader(
            kicker = "B2 / MANUAL OVERRIDE",
            title = "低速驾驶",
            subtitle = "停车场低速操控，按住移动，松手立即刹停。",
            status = "待连接"
        ))
        driveCameraPanel = DriveCameraPanel(this, parkingPalette()).also { panel ->
            panel.layoutParams = matchWrapParams(bottom = 8)
            panel.setOnFullscreenRequested { enterFullscreenDrive() }
            pageContent.addView(panel)
            panel.start(api().cameraStreamUrl())
        }
        pageContent.addView(parkingRemoteConsole())
        updateSpeedText()
    }

    private fun renderAiPage() {
        jarvisChatPage = JarvisChatPage(
            context = this,
            host = currentHost,
            themeMode = parkingThemeMode,
            executor = commandExecutor,
            conversations = jarvisConversationController,
            onStatus = { status -> setStatus(status) },
            onEmergencyStop = {
                sendGet(api().emergencyStopUrl(), "急停", executorService = stopExecutor)
            }
        ).also { chatPage ->
            pageHost.addView(chatPage, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(dp(14), 0, dp(14), 0)
            })
        }
    }

    private fun renderVision() {
        pageContent.addView(parkingPageHeader(
            kicker = "B2 / VISION REVIEW",
            title = "停车视觉",
            subtitle = "Jetson 边缘模型实时识别车辆、车位、标志与通道障碍。",
            status = "边缘 AI"
        ))
        pageContent.addView(parkingVisionSurface())
        pageContent.addView(parkingVisionSummary())
        pageContent.addView(parkingVisionClassRail())
    }

    private fun renderNavigation() {
        pageContent.addView(parkingPageHeader(
            kicker = "B2 / ROUTE CONTROL",
            title = "路线导航",
            subtitle = "生成停车场地图，规划巡逻路线，并按检查点执行自动导航。",
            status = "SLAM / Nav2"
        ))
        pageContent.addView(parkingMapSurface())
        pageContent.addView(parkingRouteStatus())
        pageContent.addView(parkingContainerSelector())
        pageContent.addView(parkingNavigationDeck())
        pageContent.addView(parkingSafetyStrip())
    }

    private fun updateTopBar(key: String) {
        val page = FeatureCatalog.primaryPages().firstOrNull { it.key == key }
        txtAppTitle.text = "iCar · B2 停车场巡逻"
        txtStatusPill.text = page?.subtitle ?: "准备连接小车"
    }

    private fun updateChrome(key: String) {
        val home = key == "home"
        topBar.visibility = View.VISIBLE
        val palette = parkingPalette()
        val useDarkChrome = parkingThemeMode == ParkingThemeMode.DARK
        pageHost.setBackgroundColor(color(palette.background))
        scrollContent.setBackgroundColor(color(palette.background))
        window.statusBarColor = color(palette.background)
        window.navigationBarColor = color(palette.surface)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val lightStatusFlag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            window.decorView.systemUiVisibility = if (useDarkChrome) {
                window.decorView.systemUiVisibility and lightStatusFlag.inv()
            } else {
                window.decorView.systemUiVisibility or lightStatusFlag
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val lightNavigationFlag = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = if (useDarkChrome) {
                window.decorView.systemUiVisibility and lightNavigationFlag.inv()
            } else {
                window.decorView.systemUiVisibility or lightNavigationFlag
            }
        }
        bottomNav.background = if (home && parkingThemeMode == ParkingThemeMode.DARK) {
            resources.getDrawable(R.drawable.bottom_bar_dark_bg, theme)
        } else {
            roundedBackground(palette.surface, palette.border, 0)
        }
        topBar.background = ColorDrawable(color(palette.background))
        txtAppTitle.setTextColor(color(palette.textSecondary))
        txtStatusPill.setTextColor(color(palette.textSecondary))
        attachGlobalThemeToggle(
            topBar,
            LinearLayout.LayoutParams(
                dp(InteractionSpec.parkingThemeToggleSizeDp()),
                dp(InteractionSpec.parkingThemeToggleSizeDp())
            )
        )
        styleGlobalThemeToggle(palette)
        pageContent.setPadding(
            if (home) 0 else dp(12),
            pageContent.paddingTop,
            if (home) 0 else dp(12),
            dp(if (home) InteractionSpec.obsidianBottomClearanceDp() else 18)
        )
        navViews["home"]?.text = "◆\n巡逻"
        navViews["drive"]?.text = "◉\n驾驶"
        navViews["ai"]?.text = "✦\nAI"
        navViews["vision"]?.text = "◎\n视觉"
        navViews["nav"]?.text = "⌖\n导航"
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
        panel.addView(cardHeader("停车场任务助手", "预留"))
        panel.addView(bodyText("把巡逻、避障、视觉复查和返回任务拆成可确认步骤。当前只生成预览，不直接控制小车。"), matchWrapParams(top = 8))

        val examplesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val examplesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        examplesScroll.addView(examplesRow)
        panel.addView(examplesScroll, matchWrapParams(top = 12))

        val input = EditText(this).apply {
            hint = "例如：巡检 B2 东区并复查禁停车辆"
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
            val text = input.text.toString().ifBlank { "巡检 B2 东区并复查禁停车辆" }
            preview.text = "任务：$text"
            animateStepPreview(preview, listOf(
                "1. 检查连接状态",
                "2. 加载 B2 巡逻路线",
                "3. 启动雷达避障与停车视觉",
                "4. 标记异常并等待用户确认"
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

    private fun digitalKeyStage(): FrameLayout {
        val palette = parkingPalette()
        val lightHome = parkingThemeMode == ParkingThemeMode.LIGHT
        val stage = FrameLayout(this).apply {
            background = if (lightHome) {
                roundedBackground(palette.surface, palette.border, 8, palette.surfaceAlt)
            } else {
                resources.getDrawable(R.drawable.obsidian_stage_bg, theme)
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = matchWrapParams(bottom = 0).apply {
                height = dp(600)
                setMargins(dp(12), dp(10), dp(12), dp(12))
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "iCAR  X3"
            setTextColor(color(palette.textPrimary))
            textSize = 15f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(30), 1f))
        homeConnectionText = TextView(this).apply {
            text = if (isVehicleConnected) "●  已连接" else "●  待检测"
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(color(if (isVehicleConnected) "#4FE1B6" else palette.textSecondary))
            textSize = 11f
        }
        header.addView(homeConnectionText, LinearLayout.LayoutParams(dp(100), dp(30)))
        stage.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32), Gravity.TOP))

        val headline = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "B2 / PARKING PATROL"
                setTextColor(color(palette.accentText))
                textSize = 10f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "巡逻待命"
                setTextColor(color(palette.textPrimary))
                textSize = 30f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, matchWrapParams(top = 3))
            addView(TextView(this@MainActivity).apply {
                text = "路线待配置 · 设备状态可检测"
                setTextColor(color(palette.textSecondary))
                textSize = 11f
            }, matchWrapParams(top = 2))
        }
        stage.addView(headline, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(82),
            Gravity.TOP
        ).apply {
            topMargin = dp(48)
        })

        vehicleStage = Vehicle3DStageView(this).apply {
            setConnected(isVehicleConnected)
            setThemeMode(parkingThemeMode)
        }
        stage.addView(vehicleStage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(InteractionSpec.vehicleStageHeightDp() - 32),
            Gravity.TOP
        ).apply {
            topMargin = dp(112)
        })

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(
            "86" to "电量 %",
            "00" to "异常",
            "--" to "预计 min"
        ).forEachIndexed { index, metric ->
            metrics.addView(obsidianMetric(metric.first, metric.second), LinearLayout.LayoutParams(0, dp(58), 1f).apply {
                if (index > 0) setMargins(dp(1), 0, 0, 0)
            })
        }
        stage.addView(metrics, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.TOP).apply {
            topMargin = dp(392)
        })

        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            "连接" to { showConnectionSheet() },
            "驾驶" to { renderPage("drive") },
            "避障" to { sendGet(api().startTaskUrl("avoidance"), "启动自动避障") },
            "AI" to { renderPage("ai") }
        ).forEachIndexed { index, item ->
            shortcuts.addView(obsidianShortcut(item.first, item.second), LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                if (index > 0) setMargins(dp(7), 0, 0, 0)
            })
        }
        stage.addView(shortcuts, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40), Gravity.TOP).apply {
            topMargin = dp(458)
        })

        val primary = TextView(this).apply {
            text = "进入巡逻准备    →"
            gravity = Gravity.CENTER
            setTextColor(color(accentOnColor()))
            textSize = 14f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = if (lightHome) {
                tactileBackground(palette.accent, palette.accentSoft, palette.accent, 18)
            } else {
                resources.getDrawable(R.drawable.obsidian_primary_bg, theme)
            }
            isClickable = true
            setOnClickListener { renderPage("nav") }
            setPressFeedback(this)
        }
        stage.addView(primary, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(InteractionSpec.obsidianPrimaryHeightDp()),
            Gravity.BOTTOM
        ).apply {
            bottomMargin = dp(2)
        })

        stage.alpha = 0f
        stage.translationY = dp(14).toFloat()
        stage.post {
            stage.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(InteractionSpec.pageTransitionMillis().toLong())
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        return stage
    }

    private fun obsidianMetric(value: String, label: String): LinearLayout =
        LinearLayout(this).apply {
            val palette = parkingPalette()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply {
                text = value
                gravity = Gravity.CENTER
                setTextColor(color(palette.textPrimary))
                textSize = 20f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = label
                gravity = Gravity.CENTER
                setTextColor(color(palette.textSecondary))
                textSize = 9f
            })
        }

    private fun obsidianShortcut(label: String, action: () -> Unit): TextView =
        TextView(this).apply {
            val palette = parkingPalette()
            text = label
            gravity = Gravity.CENTER
            setTextColor(color(palette.textPrimary))
            textSize = 11f
            background = if (parkingThemeMode == ParkingThemeMode.LIGHT) {
                tactileBackground(palette.surface, palette.surfaceAlt, palette.border, 18)
            } else {
                resources.getDrawable(R.drawable.obsidian_action_bg, theme)
            }
            isClickable = true
            setOnClickListener { action() }
            setPressFeedback(this)
        }

    private fun keyStatusRail(): LinearLayout {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = resources.getDrawable(R.drawable.connection_dock_bg, theme)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        rail.addView(keyStatusCell("连接", "待检测"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        rail.addView(keyStatusCell("底盘", "可启动"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(dp(6), 0, 0, 0)
        })
        rail.addView(keyStatusCell("急停", "可用"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(dp(6), 0, 0, 0)
        })
        return rail
    }

    private fun keyStatusCell(title: String, value: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply {
                text = value
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 13f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                maxLines = 1
            })
            addView(TextView(this@MainActivity).apply {
                text = title
                gravity = Gravity.CENTER
                setTextColor(color("#DDF7F3"))
                textSize = 10f
                maxLines = 1
            }, matchWrapParams(top = 1))
        }

    private fun keyFloatingAction(item: FeatureItem, emphasized: Boolean): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = resources.getDrawable(if (emphasized) R.drawable.key_action_active_bg else R.drawable.key_action_bg, theme)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            isClickable = true
            setOnClickListener { handleKeyAction(item.key) }
            setPressFeedback(this)
        }
        tile.addView(TextView(this).apply {
            text = item.title
            gravity = Gravity.CENTER
            setTextColor(color(if (emphasized) "#FFFFFF" else "#141A1F"))
            textSize = 17f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        tile.addView(TextView(this).apply {
            text = item.description
            gravity = Gravity.CENTER
            setTextColor(color(if (emphasized) "#DDF7F3" else "#66706C"))
            textSize = 11f
            maxLines = 1
        }, matchWrapParams(top = 3))
        return tile
    }

    private fun handleKeyAction(key: String) {
        when (key) {
            "drive" -> renderPage("drive")
            "base" -> sendGet(api().startTaskUrl("base"), "启动底盘驱动")
            "avoidance" -> sendGet(api().startTaskUrl("avoidance"), "启动自动避障")
            "nav" -> renderPage("nav")
            "ai" -> renderPage("ai")
        }
    }

    private fun keyPrimaryButton(text: String, action: () -> Unit): TextView =
        keyTextButton(text, R.drawable.key_action_active_bg, Color.WHITE, action)

    private fun keySecondaryButton(text: String, action: () -> Unit): TextView =
        keyTextButton(text, R.drawable.key_action_bg, color("#141A1F"), action)

    private fun keyTextButton(text: String, bg: Int, textColor: Int, action: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(textColor)
            textSize = 15f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = resources.getDrawable(bg, theme)
            isClickable = true
            setOnClickListener { action() }
            setPressFeedback(this)
        }

    private fun productModuleDock(): LinearLayout {
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.product_panel_bg, theme)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = matchWrapParams(bottom = 12)
        }
        dock.addView(sectionHeader("功能入口", "像车钥匙 App 一样，先选模式，再进入具体控制"))

        val grid = GridLayout(this).apply {
            columnCount = 2
            rowCount = 2
        }
        listOf(
            HomeAction("AI 巡逻", "避障、跟随、警卫", "AI") { renderPage("ai") },
            HomeAction("视觉实验", "相机与模型结果", "视觉") { renderPage("vision") },
            HomeAction("建图导航", "SLAM 与目标点", "导航") { renderPage("nav") },
            HomeAction("AI 助手", "复杂任务预留", "预留") { renderPage("ai") }
        ).forEach { action ->
            grid.addView(moduleJumpTile(action), GridLayout.LayoutParams().apply {
                width = 0
                height = dp(InteractionSpec.modeTileHeightDp())
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(8), dp(4), 0)
            })
        }
        dock.addView(grid)
        return dock
    }

    private fun moduleJumpTile(action: HomeAction): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.mode_tile_product_bg, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { action.onClick() }
            setPressFeedback(this)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this).apply {
            text = action.title
            setTextColor(color("#141A1F"))
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(chip(action.status))
        tile.addView(top)
        tile.addView(TextView(this).apply {
            text = action.subtitle
            setTextColor(color("#66706C"))
            textSize = 12f
            maxLines = 2
        }, matchWrapParams(top = 8))
        return tile
    }

    private fun productHero(title: String, subtitle: String, tag: String): LinearLayout {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.key_stage_bg, theme)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            layoutParams = matchWrapParams(bottom = 12)
            elevation = dp(4).toFloat()
        }
        hero.addView(chipOnDark(tag))
        hero.addView(TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 25f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, matchWrapParams(top = 9))
        hero.addView(TextView(this).apply {
            text = subtitle
            setTextColor(color("#E8F6F0"))
            textSize = 13f
            maxLines = 2
        }, matchWrapParams(top = 5))
        return hero
    }

    private fun driveKeyHeader(): LinearLayout =
        productHero("驾驶舱", "按住方向移动，松手立即刹停。先低速试车，保持急停可触达。", "手动控制")

    private fun parkingPageHeader(
        kicker: String,
        title: String,
        subtitle: String,
        status: String
    ): LinearLayout = LinearLayout(this).apply {
        val palette = parkingPalette()
        orientation = LinearLayout.VERTICAL
        if (selectedPage == "drive") {
            setPadding(0, 0, 0, 0)
            layoutParams = matchWrapParams(bottom = 0)
            return@apply
        }
        setPadding(dp(6), dp(22), dp(6), dp(18))
        layoutParams = matchWrapParams(bottom = 4)
        val top = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(TextView(this@MainActivity).apply {
            text = kicker
            setTextColor(color(palette.accentText))
            textSize = 10f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(parkingChip(status).also { pageStatusText = it })
        addView(top)
        addView(TextView(this@MainActivity).apply {
            text = title
            setTextColor(color(palette.textPrimary))
            textSize = 31f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, matchWrapParams(top = 8))
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            setTextColor(color(palette.textSecondary))
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1f)
        }, matchWrapParams(top = 5))
    }

    private fun parkingChip(text: String): TextView = TextView(this).apply {
        val palette = parkingPalette()
        this.text = text
        setTextColor(color(palette.accentText))
        textSize = 10f
        gravity = Gravity.CENTER
        background = roundedBackground(palette.accentSoft, palette.border, 14)
        setPadding(dp(10), dp(5), dp(10), dp(5))
        maxLines = 1
        maxWidth = dp(132)
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun parkingDriveStatus(): LinearLayout = parkingMetricBand(listOf(
        "0.00" to "m/s",
        "待命" to "当前动作",
        if (isVehicleConnected) "在线" to "HTTP" else "离线" to "HTTP"
    ))

    private fun parkingMetricBand(items: List<Pair<String, String>>): LinearLayout =
        LinearLayout(this).apply {
            val palette = parkingPalette()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedBackground(palette.surface, palette.border, 18)
        setPadding(dp(6), dp(6), dp(6), dp(6))
        layoutParams = matchWrapParams(bottom = 8)
            items.forEachIndexed { index, item ->
                val metric = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(TextView(this@MainActivity).apply {
                        text = item.first
                        gravity = Gravity.CENTER
                        setTextColor(color(palette.textPrimary))
                        textSize = 18f
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = item.second
                        gravity = Gravity.CENTER
                        setTextColor(color(palette.textSecondary))
                        textSize = 10f
                    }, matchWrapParams(top = 2))
                }
                addView(metric, LinearLayout.LayoutParams(0, dp(42), 1f))
                if (index < items.lastIndex) {
                    addView(View(this@MainActivity).apply {
                        setBackgroundColor(color(palette.border))
                    }, LinearLayout.LayoutParams(dp(1), dp(32)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    })
                }
            }
        }

    private fun parkingRemoteConsole(): LinearLayout = LinearLayout(this).apply {
        val palette = parkingPalette()
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(palette.surface, palette.border, 22, palette.surfaceAlt)
        elevation = dp(InteractionSpec.parkingDriveButtonElevationDp()).toFloat()
        setPadding(dp(10), dp(9), dp(10), dp(10))
        layoutParams = matchWrapParams(bottom = 8)

        addView(parkingSpeedDeck())
        addView(parkingMovementGrid(), matchWrapParams(top = 6))
    }

    private fun parkingSpeedDeck(): LinearLayout = LinearLayout(this).apply {
        val palette = parkingPalette()
        orientation = LinearLayout.VERTICAL
        val presets = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(parkingSpeedButton("慢速", 5), LinearLayout.LayoutParams(0, dp(34), 1f))
            addView(parkingSpeedButton("标准", 13), LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                setMargins(dp(7), 0, 0, 0)
            })
            addView(parkingSpeedButton("快速", 23), LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                setMargins(dp(7), 0, 0, 0)
            })
        }
        addView(presets)
        txtSpeed = TextView(this@MainActivity).apply {
            setTextColor(color(palette.textPrimary))
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        addView(txtSpeed, matchWrapParams(top = 5))
        val speedBar = SeekBar(this@MainActivity).apply {
            max = 30
            progress = speedProgress
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    speedProgress = fullscreenSpeedSelection(progress).progress
                    updateSpeedText()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        driveSpeedSeekBar = speedBar
        addView(speedBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(34)
        ))
    }

    private fun parkingSpeedButton(text: String, progress: Int): Button = Button(this).apply {
        val palette = parkingPalette()
        this.text = text
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        setTextColor(color(if (speedProgress == progress) palette.accentText else palette.textSecondary))
        textSize = 12f
        background = if (speedProgress == progress) {
            tactileBackground(palette.accentSoft, palette.surface, palette.accent, 14)
        } else {
            tactileBackground(palette.surface, palette.surfaceAlt, palette.border, 14)
        }
        setOnClickListener {
            speedProgress = progress
            renderPage("drive")
        }
    }

    private fun parkingMovementGrid(): GridLayout = GridLayout(this).apply {
        columnCount = 3
        rowCount = 3
        addParkingMoveButton(this, "↶\n左转", "turn_left")
        addParkingMoveButton(this, "↑\n前进", "front")
        addParkingMoveButton(this, "↷\n右转", "turn_right")
        addParkingMoveButton(this, "←\n左移", "left")
        addView(parkingStopButton(), parkingGridParams())
        addParkingMoveButton(this, "→\n右移", "right")
        addView(parkingChassisButton(), parkingGridParams())
        addParkingMoveButton(this, "↓\n后退", "back")
        addView(parkingEmergencyButton(), parkingGridParams())
    }

    private fun addParkingMoveButton(grid: GridLayout, text: String, direction: String) {
        val button = Button(this).apply {
            val palette = parkingPalette()
            this.text = text
            isAllCaps = false
            includeFontPadding = false
            minHeight = 0
            minimumHeight = 0
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(color(palette.textPrimary))
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            background = tactileBackground(palette.surface, palette.surfaceAlt, palette.border, 20)
            elevation = dp(InteractionSpec.parkingDriveButtonElevationDp()).toFloat()
            stateListAnimator = null
        }
        setupHoldButton(button, direction)
        grid.addView(button, parkingGridParams())
    }

    private fun parkingStopButton(): Button = Button(this).apply {
        val palette = parkingPalette()
        text = "■\n停止"
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(color(accentOnColor()))
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        background = tactileBackground(palette.accent, palette.accentSoft, palette.accent, 20)
        elevation = dp(InteractionSpec.parkingDriveButtonElevationDp() + 2).toFloat()
        stateListAnimator = null
        setOnClickListener { stopMove() }
    }

    private fun parkingChassisButton(): Button = Button(this).apply {
        val palette = parkingPalette()
        text = "◇\n底盘"
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        gravity = Gravity.CENTER
        textSize = 14f
        setLineSpacing(dp(1).toFloat(), 1.0f)
        setTextColor(color(palette.textPrimary))
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        background = tactileBackground(palette.surface, palette.surfaceAlt, palette.border, 20)
        elevation = dp(InteractionSpec.parkingDriveButtonElevationDp()).toFloat()
        stateListAnimator = null
        setOnClickListener { sendGet(api().startTaskUrl("base"), "启动底盘驱动") }
    }

    private fun parkingEmergencyButton(): Button = Button(this).apply {
        text = "!\n急停"
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        gravity = Gravity.CENTER
        textSize = 14f
        setLineSpacing(dp(1).toFloat(), 1.0f)
        setTextColor(color(accentOnColor()))
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        background = tactileBackground("#B8464F", "#FAD0D5", "#E06A75", 20)
        elevation = dp(InteractionSpec.parkingDriveButtonElevationDp() + 2).toFloat()
        stateListAnimator = null
        setOnClickListener {
            currentDirection = null
            mainHandler.removeCallbacks(repeatMoveRunnable)
            sendGet(api().emergencyStopUrl(), "急停")
        }
    }

    private fun parkingGridParams(): GridLayout.LayoutParams = GridLayout.LayoutParams().apply {
        width = 0
        height = dp(InteractionSpec.parkingDriveControlSizeDp())
        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
        setMargins(dp(4), dp(4), dp(4), dp(4))
    }

    private fun parkingActivityBar(): LinearLayout = LinearLayout(this).apply {
        val palette = parkingPalette()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedBackground(palette.surface, palette.border, 16)
        setPadding(dp(13), dp(11), dp(13), dp(11))
        layoutParams = matchWrapParams(bottom = 12)
        addView(TextView(this@MainActivity).apply {
            text = "最近操作"
            setTextColor(color(palette.accentText))
            textSize = 11f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        txtLog = TextView(this@MainActivity).apply {
            text = logText.lineSequence().firstOrNull().orEmpty()
            gravity = Gravity.END
            maxLines = 1
            setTextColor(color(palette.textSecondary))
            textSize = 11f
        }
        addView(txtLog, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(12), 0, 0, 0)
        })
        isClickable = true
        setOnClickListener {
            showProductSheet("操作记录", "最近的控制请求与返回结果", "LOG") { panel ->
                panel.addView(bodyText(logText), matchWrapParams(top = 10))
            }
        }
        setPressFeedback(this)
    }

    private fun parkingAiWorkspace(): LinearLayout = LinearLayout(this).apply {
        val palette = parkingPalette()
        orientation = LinearLayout.VERTICAL

        val promptSurface = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(palette.surfaceAlt, palette.border, 22, palette.surface)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = "✦  AI 巡逻助手"
                setTextColor(color(palette.accentText))
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "连接 Jetson 8100 智能体，先生成白名单计划，确认后才调用小车控制服务。"
                setTextColor(color(palette.textSecondary))
                textSize = 10f
            }, matchWrapParams(top = 4))
        }

        jarvisTokenInput = EditText(this@MainActivity).apply {
            hint = "Jarvis Token"
            setText(jarvisToken)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(color(palette.textPrimary))
            setHintTextColor(color(palette.textSecondary))
            background = roundedBackground(palette.surface, palette.border, 16)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        promptSurface.addView(jarvisTokenInput, matchWrapParams(top = 12))

        val input = EditText(this@MainActivity).apply {
            hint = "描述本次停车场巡逻任务..."
            minLines = 3
            gravity = Gravity.TOP
            setTextColor(color(palette.textPrimary))
            setHintTextColor(color(palette.textSecondary))
            background = roundedBackground(palette.surface, palette.border, 16)
            setPadding(dp(12), dp(11), dp(12), dp(11))
        }
        promptSurface.addView(input, matchWrapParams(top = 12))

        val examplesScroll = HorizontalScrollView(this@MainActivity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val examples = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        FeatureCatalog.aiExamples().forEach { example ->
            examples.addView(TextView(this@MainActivity).apply {
                text = example
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(color(palette.textPrimary))
                textSize = 10f
                background = tactileBackground(palette.surface, palette.surfaceAlt, palette.border, 14)
                setPadding(dp(11), dp(8), dp(11), dp(8))
                isClickable = true
                setOnClickListener {
                    input.setText(example)
                    input.setSelection(input.text.length)
                }
            }, LinearLayout.LayoutParams(dp(188), dp(54)).apply { setMargins(0, 0, dp(8), 0) })
        }
        examplesScroll.addView(examples)
        promptSurface.addView(examplesScroll, matchWrapParams(top = 10))

        addView(promptSurface, matchWrapParams(bottom = 18))
        addView(parkingSectionTitle("Jarvis 安全计划", "确认前只展示计划，确认后才执行控制动作"))

        val timeline = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        val defaultSteps = listOf(
            "01" to "检查底盘、雷达和相机连接",
            "02" to "加载 B2 五点巡逻路线",
            "03" to "启动通道避障与停车视觉",
            "04" to "标记异常并等待人工复核"
        )
        defaultSteps.forEachIndexed { index, step ->
            timeline.addView(parkingTimelineStep(step.first, step.second, index == 0))
        }
        addView(timeline, matchWrapParams(top = 10))

        val actions = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(parkingOutlineButton(JarvisUiSpec.primaryActions()[1]) {
                val plan = jarvisCurrentPlan
                val token = saveJarvisToken()
                if (plan == null || token.isBlank()) {
                    setStatus("请先生成 Jarvis 计划")
                    appendLog("Jarvis：没有可确认的计划")
                    return@parkingOutlineButton
                }
                setStatus("Jarvis 正在确认执行")
                commandExecutor.execute {
                    val result = runCatching {
                        val api = JarvisApi(currentHost, token)
                        val mission = api.createMission(plan)
                        val confirmed = api.confirmMission(mission.id)
                        val entries = api.getTimeline(confirmed.id)
                        jarvisMissionId = confirmed.id
                        entries
                    }
                    runOnUiThread {
                        result.fold(
                            onSuccess = { entries ->
                                timeline.removeAllViews()
                                entries.forEachIndexed { index, entry ->
                                    timeline.addView(parkingTimelineStep("%02d".format(index + 1), "${entry.kind}: ${entry.message}", index == 0))
                                }
                                setStatus("Jarvis 任务已执行")
                                appendLog("Jarvis：任务已确认执行")
                            },
                            onFailure = { error ->
                                setStatus("Jarvis 执行失败")
                                appendLog("Jarvis 执行失败：${error.message ?: error.javaClass.simpleName}")
                            }
                        )
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(50), 0.34f))
            addView(parkingPrimaryButton(JarvisUiSpec.primaryActions()[0]) {
                val mission = input.text.toString().ifBlank { FeatureCatalog.aiExamples().first() }
                input.setText(mission)
                val token = saveJarvisToken()
                if (token.isBlank()) {
                    setStatus("请先填写 Jarvis Token")
                    appendLog("Jarvis：缺少 Token")
                    return@parkingPrimaryButton
                }
                setStatus("Jarvis 正在生成计划")
                commandExecutor.execute {
                    val result = runCatching { JarvisApi(currentHost, token).chat(mission) }
                    runOnUiThread {
                        result.fold(
                            onSuccess = { response ->
                                val plan = response.plan
                                if (plan == null) {
                                    setStatus("Jarvis")
                                    appendLog("Jarvis：${response.reply}")
                                    return@fold
                                }
                                jarvisCurrentPlan = plan
                                timeline.removeAllViews()
                                plan.steps.forEachIndexed { index, step ->
                                    val task = step.arguments["task"]?.toString()?.let { " $it" }.orEmpty()
                                    timeline.addView(parkingTimelineStep("%02d".format(index + 1), "${step.action.name}$task", index == 0))
                                }
                                setStatus("Jarvis 计划已生成")
                                appendLog("Jarvis 计划：${plan.summary}")
                            },
                            onFailure = { error ->
                                setStatus("Jarvis 计划失败")
                                appendLog("Jarvis 计划失败：${error.message ?: error.javaClass.simpleName}")
                            }
                        )
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(50), 0.66f).apply { setMargins(dp(9), 0, 0, 0) })
        }
        addView(actions, matchWrapParams(top = 14, bottom = 12))
        val missionActions = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(parkingOutlineButton(JarvisUiSpec.secondaryActions()[0]) {
                val missionId = jarvisMissionId
                val token = saveJarvisToken()
                if (missionId == null || token.isBlank()) {
                    setStatus("暂无 Jarvis 任务")
                    return@parkingOutlineButton
                }
                setStatus("刷新 Jarvis 任务")
                commandExecutor.execute {
                    val result = runCatching { JarvisApi(currentHost, token).getTimeline(missionId) }
                    runOnUiThread {
                        result.fold(
                            onSuccess = { entries ->
                                timeline.removeAllViews()
                                entries.forEachIndexed { index, entry ->
                                    timeline.addView(parkingTimelineStep("%02d".format(index + 1), "${entry.kind}: ${entry.message}", index == 0))
                                }
                                setStatus("Jarvis 任务已刷新")
                            },
                            onFailure = { setStatus("Jarvis 刷新失败") }
                        )
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(parkingOutlineButton(JarvisUiSpec.secondaryActions()[1]) {
                val missionId = jarvisMissionId
                val token = saveJarvisToken()
                if (missionId == null || token.isBlank()) {
                    setStatus("暂无 Jarvis 报告")
                    return@parkingOutlineButton
                }
                setStatus("读取 Jarvis 报告")
                commandExecutor.execute {
                    val result = runCatching { JarvisApi(currentHost, token).getReport(missionId) }
                    runOnUiThread {
                        result.fold(
                            onSuccess = { report ->
                                timeline.removeAllViews()
                                timeline.addView(parkingTimelineStep("OK", report.markdown.take(120), true))
                                setStatus("Jarvis 报告已生成")
                            },
                            onFailure = { setStatus("Jarvis 报告失败") }
                        )
                    }
                }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(8), 0, 0, 0) })
            addView(dangerButton(JarvisUiSpec.dangerAction()) {
                sendGet(api().emergencyStopUrl(), "急停", executorService = stopExecutor)
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(8), 0, 0, 0) })
        }
        addView(missionActions, matchWrapParams(bottom = 12))
    }

    private fun parkingTimelineStep(number: String, title: String, active: Boolean): LinearLayout =
        LinearLayout(this).apply {
            val palette = parkingPalette()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(TextView(this@MainActivity).apply {
                text = if (active) "✓" else number
                gravity = Gravity.CENTER
                setTextColor(color(if (active) accentOnColor() else palette.accentText))
                textSize = 10f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                background = roundedBackground(if (active) palette.accent else palette.accentSoft, palette.accent, 20)
            }, LinearLayout.LayoutParams(dp(34), dp(34)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextColor(color(palette.textPrimary))
                    textSize = 12f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = if (active) "已完成准备检查" else "等待用户确认"
                    setTextColor(color(palette.textSecondary))
                    textSize = 9f
                }, matchWrapParams(top = 2))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(12), 0, 0, 0) })
            addView(TextView(this@MainActivity).apply {
                text = "›"
                setTextColor(color(palette.accentText))
                textSize = 20f
            })
        }

    private fun parkingMissionSummary(): LinearLayout = parkingMetricBand(listOf(
        "05" to "检查点",
        "680m" to "计划路线",
        "00" to "待复核"
    ))

    private fun parkingCheckpointRail(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = matchWrapParams(bottom = 12)
        addView(parkingSectionTitle("巡逻检查点", "横向浏览，点击进入对应能力"))
        val scroll = HorizontalScrollView(this@MainActivity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
        }
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val checkpoints = listOf(
            PatrolCheckpoint("A", "A区车位", "编号与占用", "camera", "视觉"),
            PatrolCheckpoint("B", "主通道", "雷达避障", "avoidance", "雷达"),
            PatrolCheckpoint("C", "禁停区域", "标志与车辆", null, "训练中"),
            PatrolCheckpoint("D", "设备间", "限制区警卫", "warning", "警卫"),
            PatrolCheckpoint("E", "环境点", "烟雾与气体", null, "待接入")
        )
        checkpoints.forEach { checkpoint ->
            row.addView(parkingCheckpointTile(checkpoint), LinearLayout.LayoutParams(
                dp(146), dp(InteractionSpec.parkingTaskRailHeightDp())
            ).apply { setMargins(0, dp(10), dp(9), 0) })
        }
        scroll.addView(row)
        addView(scroll)
    }

    private fun parkingCheckpointTile(checkpoint: PatrolCheckpoint): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.patrol_surface_bg, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(TextView(this@MainActivity).apply {
                text = checkpoint.code
                setTextColor(color("#C6B57B"))
                textSize = 11f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = checkpoint.title
                setTextColor(Color.WHITE)
                textSize = 16f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, matchWrapParams(top = 8))
            addView(TextView(this@MainActivity).apply {
                text = checkpoint.subtitle
                setTextColor(color("#89938E"))
                textSize = 12f
            }, matchWrapParams(top = 3))
            addView(Space(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(parkingChip(checkpoint.status))
            isClickable = true
            setOnClickListener {
                checkpoint.taskKey?.let { key ->
                    sendGet(api().startTaskUrl(key), "启动${checkpoint.title}")
                } ?: showProductSheet(checkpoint.title, checkpoint.subtitle, checkpoint.status) { panel ->
                    panel.addView(disabledButton("能力接入后可在此启动"))
                }
            }
            setPressFeedback(this)
        }

    private fun parkingServiceDeck(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = resources.getDrawable(R.drawable.patrol_surface_bg, theme)
        setPadding(dp(13), dp(13), dp(13), dp(13))
        layoutParams = matchWrapParams(bottom = 12)
        addView(parkingSectionTitle("基础服务", "按实训手册逐项启动，不自动跳过依赖"))
        val grid = GridLayout(this@MainActivity).apply { columnCount = 2 }
        listOf(
            Triple("底盘驱动", "base", "启动底盘"),
            Triple("激光雷达", "lidar", "启动雷达"),
            Triple("Astra 相机", "camera", "启动相机"),
            Triple("自动避障", "avoidance", "启动避障")
        ).forEach { item ->
            grid.addView(parkingOutlineButton(item.first) {
                sendGet(api().startTaskUrl(item.second), item.third)
            }, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(52)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(8), dp(4), 0)
            })
        }
        addView(grid)
    }

    private fun parkingSafetyStrip(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = matchWrapParams(bottom = 12)
        addView(parkingDangerButton("急停") {
            currentDirection = null
            mainHandler.removeCallbacks(repeatMoveRunnable)
            sendGet(api().emergencyStopUrl(), "急停")
        }, LinearLayout.LayoutParams(0, dp(50), 1f))
        addView(parkingOutlineButton("全部停止") {
            currentDirection = null
            mainHandler.removeCallbacks(repeatMoveRunnable)
            sendGet(api().stopAllUrl(), "全部停止")
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            setMargins(dp(9), 0, 0, 0)
        })
    }

    private fun parkingVisionSurface(): LinearLayout = LinearLayout(this).apply {
        val palette = parkingPalette()
        orientation = LinearLayout.VERTICAL
        layoutParams = matchWrapParams(bottom = 12)
        val stage = FrameLayout(this@MainActivity).apply {
            background = roundedBackground(palette.surfaceAlt, palette.border, 18)
            clipToOutline = true
            elevation = dp(3).toFloat()
        }
        stage.addView(MjpegStreamView(this@MainActivity).also { visionStreamView = it }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        stage.addView(VisionDetectionOverlayView(this@MainActivity).also { visionOverlayView = it }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        stage.addView(parkingChip("正在连接").also { visionStreamStatusText = it }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(32),
            Gravity.TOP or Gravity.START
        ).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        stage.addView(parkingChip("模型加载中").also { visionModelStatusText = it }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(32),
            Gravity.TOP or Gravity.END
        ).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        stage.addView(TextView(this@MainActivity).apply {
            text = "等待推理数据"
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground("#99080B0A", "#44FFFFFF", 10)
            visionPerformanceText = this
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START
        ).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        addView(stage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(InteractionSpec.parkingVisionStageHeightDp())
        ))
        val actions = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(parkingPrimaryButton("重新连接视频") {
                visionStreamView?.reconnect()
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(parkingOutlineButton("刷新检测结果") {
                mainHandler.removeCallbacks(visionPollRunnable)
                mainHandler.post(visionPollRunnable)
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                setMargins(dp(9), 0, 0, 0)
            })
        }
        addView(actions, matchWrapParams(top = 9))
    }

    private fun parkingVisionSummary(): LinearLayout = LinearLayout(this).apply {
        val palette = parkingPalette()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = roundedBackground(palette.surface, palette.border, 18)
        setPadding(dp(5), dp(7), dp(5), dp(7))
        layoutParams = matchWrapParams(bottom = 10)
        listOf("车辆", "车位", "标志", "障碍").forEachIndexed { index, label ->
            val value = TextView(this@MainActivity).apply {
                text = "—"
                gravity = Gravity.CENTER
                setTextColor(color(palette.textPrimary))
                textSize = 18f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }
            visionMetricValues += value
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                addView(value)
                addView(TextView(this@MainActivity).apply {
                    text = label
                    gravity = Gravity.CENTER
                    setTextColor(color(palette.textSecondary))
                    textSize = 9f
                }, matchWrapParams(top = 2))
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            if (index < 3) addView(View(this@MainActivity).apply {
                setBackgroundColor(color(palette.border))
            }, LinearLayout.LayoutParams(dp(1), dp(30)).apply { gravity = Gravity.CENTER_VERTICAL })
        }
    }

    private fun parkingVisionClassRail(): LinearLayout = LinearLayout(this).apply {
        val palette = parkingPalette()
        orientation = LinearLayout.VERTICAL
        layoutParams = matchWrapParams(bottom = 12)
        addView(parkingSectionTitle("识别类别", "模型支持九类停车场目标，结果来自小车实时画面。"))
        val scroll = HorizontalScrollView(this@MainActivity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            "parking_slot", "car", "no_parking_sign", "entrance_sign", "exit_sign",
            "direction_arrow", "stop_line", "roadblock", "danger_sign"
        ).forEach { label ->
            row.addView(parkingChip(VisionUiSpec.labelText(label)), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)
            ).apply { setMargins(0, dp(9), dp(8), 0) })
        }
        scroll.addView(row)
        addView(scroll)
        addView(parkingSectionTitle("当前帧目标", "按置信度显示最近一帧的真实检测结果。"), matchWrapParams(top = 16))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(palette.surface, palette.border, 16)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = "等待视觉服务数据"
                setTextColor(color(palette.textSecondary))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
            })
            visionDetectionList = this
        }, matchWrapParams(top = 8))
    }

    private fun startVisionPage() {
        if (selectedPage != "vision") return
        visionPollingToken = visionPollingGate.start()
        visionStreamView?.start(api().cameraStreamUrl()) { snapshot ->
            if (selectedPage != "vision") return@start
            visionStreamStatusText?.text = when (snapshot.state) {
                CameraViewState.CONNECTING -> "正在连接"
                CameraViewState.LIVE -> if (snapshot.fps > 0) "实时 · ${snapshot.fps} FPS" else "实时画面"
                CameraViewState.BUSY -> "视频源忙"
                CameraViewState.MISSING -> "未发现相机"
                CameraViewState.DISCONNECTED -> "视频已断开"
                CameraViewState.IDLE -> "视频未启动"
            }
        }
        mainHandler.removeCallbacks(visionPollRunnable)
        mainHandler.post(visionPollRunnable)
    }

    private fun stopVisionPage() {
        visionPollingGate.stop()
        mainHandler.removeCallbacks(visionPollRunnable)
        visionStreamView?.stop()
    }

    private fun releaseVisionPage() {
        visionPollingGate.stop()
        mainHandler.removeCallbacks(visionPollRunnable)
        visionStreamView?.release()
        visionStreamView = null
        visionOverlayView = null
    }

    private fun pollVisionState() {
        val session = visionPollingToken
        if (selectedPage != "vision" || !visionPollingGate.isActive(session)) return
        if (visionPollInFlight) {
            if (visionPollingGate.isActive(session)) {
                mainHandler.postDelayed(visionPollRunnable, VISION_POLL_INTERVAL_MILLIS)
            }
            return
        }
        visionPollInFlight = true
        val url = VisionApi(currentHost, VISION_PORT).detectionsUrl()
        visionExecutor.execute {
            val result = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1_800
                    readTimeout = 2_500
                    useCaches = false
                }
                try {
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    check(code in 200..299) { "HTTP $code ${body.take(120)}" }
                    VisionSnapshotParser.parse(body)
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread {
                if (selectedPage == "vision" && visionPollingGate.isActive(session)) {
                    result.onSuccess(::renderVisionSnapshot)
                        .onFailure { renderVisionFailure(it.message ?: "视觉服务不可用") }
                }
                visionPollInFlight = false
                if (
                    selectedPage == "vision" &&
                    !isFinishing &&
                    visionPollingGate.isActive(session)
                ) {
                    mainHandler.postDelayed(visionPollRunnable, VISION_POLL_INTERVAL_MILLIS)
                }
            }
        }
    }

    private fun renderVisionSnapshot(snapshot: VisionSnapshot) {
        val model = snapshot.model
        visionModelStatusText?.text = VisionUiSpec.modelText(model.ready, model.device, model.fp16)
        visionPerformanceText?.text = if (snapshot.state == "live") {
            VisionUiSpec.performanceText(snapshot.inferenceMs, snapshot.sourceFps)
        } else {
            snapshot.error ?: "等待实时帧"
        }
        pageStatusText?.text = when (snapshot.state) {
            "live" -> "模型在线"
            "connecting", "starting" -> "正在准备"
            "error" -> "视觉异常"
            else -> snapshot.state
        }
        val values = listOf(
            snapshot.summary.cars,
            snapshot.summary.parkingSlots,
            snapshot.summary.signs,
            snapshot.summary.obstacles
        )
        visionMetricValues.forEachIndexed { index, textView ->
            textView.text = values.getOrNull(index)?.toString() ?: "0"
        }
        val currentDetections = if (snapshot.state == "live") snapshot.detections else emptyList()
        visionOverlayView?.setDetections(currentDetections)
        renderVisionDetections(currentDetections, snapshot.error)
    }

    private fun renderVisionFailure(message: String) {
        visionModelStatusText?.text = "服务离线"
        visionPerformanceText?.text = message.take(48)
        pageStatusText?.text = "连接失败"
        visionMetricValues.forEach { it.text = "—" }
        visionOverlayView?.setDetections(emptyList())
        renderVisionDetections(emptyList(), "无法读取检测结果")
    }

    private fun renderVisionDetections(detections: List<VisionDetection>, error: String?) {
        val container = visionDetectionList ?: return
        val palette = parkingPalette()
        container.removeAllViews()
        if (detections.isEmpty()) {
            container.addView(TextView(this).apply {
                text = error ?: "当前画面未检测到目标"
                setTextColor(color(palette.textSecondary))
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        detections.sortedByDescending { it.confidence }.take(8).forEachIndexed { index, detection ->
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
                addView(TextView(this@MainActivity).apply {
                    text = VisionUiSpec.detectionLabel(detection)
                    setTextColor(color(palette.textPrimary))
                    textSize = 12f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@MainActivity).apply {
                    text = VisionUiSpec.detectionConfidence(detection)
                    setTextColor(color(palette.accentText))
                    textSize = 11f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                })
            })
            if (index < detections.take(8).lastIndex) {
                container.addView(View(this).apply {
                    setBackgroundColor(color(palette.border))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
        }
    }

    private fun parkingMapSurface(): LinearLayout = LinearLayout(this).apply {
        val palette = parkingPalette()
        orientation = LinearLayout.VERTICAL
        layoutParams = matchWrapParams(bottom = 12)
        lateinit var startButton: Button
        lateinit var waypointButton: Button
        val map = ParkingMapView(this@MainActivity).apply {
            setPalette(palette)
            elevation = dp(3).toFloat()
            setOnCheckpointSelectedListener { checkpoint ->
                setStatus("已选择检查点 $checkpoint")
                appendLog("路线图选择检查点 $checkpoint")
            }
            setOnWaypointSelectedListener { point, error ->
                if (error != null) {
                    setStatus(error)
                } else if (point != null) {
                    val count = routeWaypoints().size
                    setStatus("已添加巡逻点 $count：${"%.2f".format(Locale.US, point.x)}, ${"%.2f".format(Locale.US, point.y)}")
                    parkingWaypointStatusText?.text = "已选择 $count 个点 · 按编号顺序执行"
                }
            }
            setOnRouteStartSelectedListener { start, error ->
                if (error != null) {
                    setStatus(error)
                } else if (start != null) {
                    setStatus("已设置路线起点：${"%.2f".format(Locale.US, start.x)}, ${"%.2f".format(Locale.US, start.y)}")
                    parkingWaypointStatusText?.text = "起点 S 已设置 · 请继续添加目标点"
                    startButton.text = "设置起点"
                    waypointButton.text = "添加目标点"
                }
            }
        }
        parkingMapView = map
        addView(map, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(InteractionSpec.parkingMapStageHeightDp())
        ))
        addView(parkingNavigationManualControl(), matchWrapParams(top = 8))
        addView(TextView(this@MainActivity).apply {
            text = "本地路线草图 · 接入 ROS 地图后替换为实时位姿"
            setTextColor(color(palette.textSecondary))
            textSize = 10f
            parkingMapCaption = this
        }, matchWrapParams(top = 7))

        addView(TextView(this@MainActivity).apply {
            text = "触屏路线未编辑"
            setTextColor(color(palette.textSecondary))
            textSize = 11f
            parkingWaypointStatusText = this
        }, matchWrapParams(top = 7))

        startButton = parkingOutlineButton("设置起点") {
            if (!map.hasLiveMap()) {
                setStatus("请先启动导航并等待实时地图加载")
            } else {
                val enabled = !map.isStartSelectionMode()
                map.setStartSelectionMode(enabled)
                startButton.text = if (enabled) "取消设置" else "设置起点"
                waypointButton.text = "添加目标点"
                parkingWaypointStatusText?.text = if (enabled) "请在地图空闲区域点击路线起点 S"
                else "起点设置已取消"
            }
        }
        waypointButton = parkingOutlineButton("添加目标点") {
            if (!map.hasLiveMap()) {
                setStatus("请先启动导航并等待实时地图加载")
            } else if (map.routeStartPose() == null) {
                setStatus("请先点击“设置起点”，在地图上标记 S")
            } else {
                val enabled = !map.isWaypointEditMode()
                map.setWaypointEditMode(enabled)
                waypointButton.text = if (enabled) "完成选点" else "添加目标点"
                startButton.text = "设置起点"
                parkingWaypointStatusText?.text = if (enabled) {
                    "目标点模式 · 依次点击地图空闲区域"
                } else {
                    "起点 S · ${map.routeWaypoints().size} 个目标点"
                }
            }
        }
        val modeRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startButton, LinearLayout.LayoutParams(0, dp(46), 1f))
            addView(waypointButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(7), 0, 0, 0) })
        }
        addView(modeRow, matchWrapParams(top = 9))

        val editRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(parkingOutlineButton("撤销") {
                val count = map.undoWaypoint()
                parkingWaypointStatusText?.text = "起点 S · 已选择 $count 个目标点"
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(7), 0, 0, 0) })
            addView(parkingOutlineButton("清空") {
                map.clearWaypoints()
                startButton.text = "设置起点"
                waypointButton.text = "添加目标点"
                parkingWaypointStatusText?.text = "起点和目标点已清空"
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(7), 0, 0, 0) })
        }
        addView(editRow, matchWrapParams(top = 7))

        val zoomRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(parkingOutlineButton("放大 +") { map.zoomIn() }, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(parkingOutlineButton("缩小 −") { map.zoomOut() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(7), 0, 0, 0) })
            addView(parkingOutlineButton("视图复位") { map.resetMapViewport() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(7), 0, 0, 0) })
        }
        addView(zoomRow, matchWrapParams(top = 7))

        val routeRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(parkingOutlineButton("返回起点") {
                if (!map.appendReturnToStart()) setStatus("尚未获得小车实时位姿")
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(parkingPrimaryButton("提交起点与目标点") {
                submitTouchWaypoints(map.routeStartPose(), map.routeWaypoints())
            }, LinearLayout.LayoutParams(0, dp(48), 1.6f).apply { setMargins(dp(8), 0, 0, 0) })
        }
        addView(routeRow, matchWrapParams(top = 8))
    }

    private fun startNavigationPolling() {
        mainHandler.removeCallbacks(navigationPollRunnable)
        if (selectedPage == "nav" && !isFinishing) {
            mainHandler.post(navigationPollRunnable)
        }
    }

    private fun parkingNavigationManualControl(): LinearLayout = LinearLayout(this).apply {
        val palette = parkingPalette()
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(palette.surface, palette.border, 18, palette.surfaceAlt)
        elevation = dp(InteractionSpec.parkingDriveButtonElevationDp()).toFloat()
        setPadding(dp(9), dp(9), dp(9), dp(9))

        val heading = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "建图手动控制"
                setTextColor(color(palette.textPrimary))
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            navigationManualStateText = TextView(this@MainActivity).apply {
                text = "待命"
                gravity = Gravity.END
                setTextColor(color(palette.accentText))
                textSize = 12f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }
            addView(navigationManualStateText)
        }
        addView(heading)

        addView(TextView(this@MainActivity).apply {
            text = "按住移动，松手停止 · 自动导航执行时请勿使用"
            setTextColor(color(palette.textSecondary))
            textSize = 11f
        }, matchWrapParams(top = 2))

        val presetButtons = mutableListOf<Pair<Button, Int>>()
        lateinit var refreshPresets: () -> Unit
        val presets = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf("慢速" to 5, "标准" to 13, "快速" to 23).forEachIndexed { index, item ->
                val button = Button(this@MainActivity).apply {
                    text = item.first
                    isAllCaps = false
                    includeFontPadding = false
                    minHeight = 0
                    minimumHeight = 0
                    textSize = 12f
                    stateListAnimator = null
                    setOnClickListener {
                        speedProgress = item.second
                        updateSpeedText()
                        refreshPresets()
                    }
                }
                presetButtons += button to item.second
                addView(button, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                    if (index > 0) setMargins(dp(6), 0, 0, 0)
                })
            }
        }
        refreshPresets = {
            presetButtons.forEach { (button, progress) ->
                val selected = speedProgress == progress
                button.setTextColor(color(if (selected) palette.accentText else palette.textSecondary))
                button.background = if (selected) {
                    tactileBackground(palette.accentSoft, palette.surface, palette.accent, 12)
                } else {
                    tactileBackground(palette.surface, palette.surfaceAlt, palette.border, 12)
                }
            }
            navigationManualSpeedText?.text = String.format(
                Locale.US,
                "速度 %.2f m/s · 转向 %.2f rad/s",
                currentSpeed(),
                currentTurn()
            )
        }
        addView(presets, matchWrapParams(top = 7))
        navigationManualSpeedText = TextView(this@MainActivity).apply {
            setTextColor(color(palette.textSecondary))
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        addView(navigationManualSpeedText, matchWrapParams(top = 4))
        refreshPresets()
        addView(parkingMovementGrid(), matchWrapParams(top = 6))
    }

    private fun stopNavigationPolling() {
        mainHandler.removeCallbacks(navigationPollRunnable)
    }

    private fun pollNavigationState() {
        if (selectedPage != "nav") return
        if (navigationPollInFlight) {
            mainHandler.postDelayed(navigationPollRunnable, 300L)
            return
        }
        navigationPollInFlight = true
        val generation = navigationMapGeneration
        val url = CarApi(currentHost, currentPort).navigationStateUrl(generation)
        navigationExecutor.execute {
            val result = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 1800
                    readTimeout = 2500
                }
                try {
                    val code = connection.responseCode
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.use { input ->
                        BufferedReader(InputStreamReader(input)).readText()
                    }.orEmpty()
                    check(code in 200..299) { "HTTP $code ${body.take(120)}" }
                    NavigationSnapshotParser.parse(body)
                } finally {
                    connection.disconnect()
                }
            }
            runOnUiThread {
                result.onSuccess { snapshot ->
                    navigationMapGeneration = snapshot.mapGeneration
                    parkingMapView?.applyNavigationSnapshot(snapshot)
                    if (parkingMapView?.hasLiveMap() == true) {
                        parkingMapCaption?.text = "ROS 实时地图 · 小车位姿、目标点与规划路径"
                    }
                    val waypointStatus = snapshot.waypoints
                    if (waypointStatus.state != "idle" || waypointStatus.total > 0) {
                        val current = if (waypointStatus.currentIndex >= 0) waypointStatus.currentIndex + 1 else 0
                        parkingWaypointStatusText?.text = when (waypointStatus.state) {
                            "preparing", "active", "submitting" -> "多点导航 $current/${waypointStatus.total} · ${waypointStatus.message}"
                            "completed" -> "多点导航完成 · ${waypointStatus.total}/${waypointStatus.total}"
                            "failed", "rejected" -> "多点导航失败 · ${waypointStatus.message}"
                            "canceling", "canceled" -> waypointStatus.message
                            else -> waypointStatus.message.ifBlank { "多点导航 ${waypointStatus.state}" }
                        }
                    }
                }
                navigationPollInFlight = false
                if (selectedPage == "nav" && !isFinishing) {
                    mainHandler.postDelayed(navigationPollRunnable, 750L)
                }
            }
        }
    }

    private fun submitTouchWaypoints(start: NavigationPose?, points: List<NavigationPoint>) {
        if (start == null) {
            setStatus("请先在地图上设置路线起点 S")
            return
        }
        if (points.isEmpty()) {
            setStatus("请先在地图上选择至少一个目标点")
            return
        }
        val startYaw = kotlin.math.atan2(points.first().y - start.y, points.first().x - start.x)
        val payloadPoints = JSONArray()
        points.forEachIndexed { index, point ->
            val next = when {
                index < points.lastIndex -> points[index + 1]
                index > 0 -> point
                else -> point
            }
            val yaw = if (index < points.lastIndex) {
                kotlin.math.atan2(next.y - point.y, next.x - point.x)
            } else if (index > 0) {
                kotlin.math.atan2(point.y - points[index - 1].y, point.x - points[index - 1].x)
            } else 0.0
            payloadPoints.put(JSONObject().apply {
                put("x", point.x)
                put("y", point.y)
                put("yaw", yaw)
            })
        }
        parkingMapView?.setWaypointEditMode(false)
        val payload = JSONObject().apply {
            put("start", JSONObject().apply {
                put("x", start.x)
                put("y", start.y)
                put("yaw", startYaw)
            })
            put("points", payloadPoints)
        }
        sendPostJson(
            api().navigationWaypointsUrl(),
            payload.toString(),
            "向当前导航算法提交起点 S 和 ${points.size} 个目标点",
            readTimeoutMillis = 8000
        )
    }

    private fun parkingRouteStatus(): LinearLayout = parkingMetricBand(listOf(
        "05" to "检查点",
        "12" to "车位",
        "未开始" to "导航状态"
    ))

    private fun parkingNavigationDeck(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(4), 0, 0)
        layoutParams = matchWrapParams(bottom = 12)
        addView(parkingSectionTitle("自动建图与导航", "按手册顺序自动启动 ROS 节点；建图时请到驾驶页低速扫描环境"))
        val actions = listOf(
            Triple("一键开始自动建图", "m1 内置底盘驱动，执行 m1 → m2 启动建图", {
                sendGet(api().automaticMappingStartUrl(), "自动建图", readTimeoutMillis = 15000)
            }),
            Triple("保存地图并结束建图", "执行 m4 保存 pgm/yaml，再关闭建图节点", {
                sendGet(api().automaticMappingSaveUrl(), "保存地图", readTimeoutMillis = 40000)
            })
        )
        actions.forEach { action ->
            addView(parkingActionRow(action.first, action.second, action.third))
        }
        addView(parkingSectionTitle("导航算法", "DWA/TEB/A*+RPP 各自启动 n1、n2 与所选算法；路线提交不会重复启动"), matchWrapParams(top = 14))
        val algorithms = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(parkingOutlineButton("DWA\nn3") {
                sendGet(api().automaticNavigationStartUrl("dwa"), "启动 DWA 自动导航", readTimeoutMillis = 20000)
            }, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(parkingOutlineButton("TEB\nn4") {
                sendGet(api().automaticNavigationStartUrl("teb"), "启动 TEB 自动导航", readTimeoutMillis = 20000)
            }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(8), 0, 0, 0) })
            addView(parkingPrimaryButton("A* + RPP\nn5") {
                sendGet(api().automaticNavigationStartUrl("astar_rpp"), "启动 A* + RPP 自动导航", readTimeoutMillis = 20000)
            }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(8), 0, 0, 0) })
        }
        addView(algorithms, matchWrapParams(top = 10))
        addView(parkingActionRow("目标点与路径规划", "发布初始位姿和目标点", { showParkingGoalSheet() }), matchWrapParams(top = 7))
        val workflowActions = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(parkingOutlineButton("刷新运行状态") {
                sendGet(api().automationStatusUrl(), "刷新自动建图与导航状态")
            }, LinearLayout.LayoutParams(0, dp(50), 1f))
            addView(dangerButton("停止自动导航") {
                sendGet(api().automaticNavigationStopUrl(), "停止自动导航", executorService = stopExecutor)
            }, LinearLayout.LayoutParams(0, dp(50), 1f).apply { setMargins(dp(9), 0, 0, 0) })
        }
        addView(workflowActions, matchWrapParams(top = 12))
    }

    private fun parkingContainerSelector(): LinearLayout = LinearLayout(this).apply {
        val palette = parkingPalette()
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(palette.surface, palette.border, 18)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = matchWrapParams(bottom = 12)
        addView(parkingSectionTitle("ROS 容器", "输入 docker ps 中的容器 ID 或名称，切换前会停止当前建图与导航任务"))

        val containerInput = EditText(this@MainActivity).apply {
            setText(currentRosContainer)
            hint = "例如 8b98 或 ed97"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            background = roundedBackground(palette.surfaceAlt, palette.border, 14)
            setTextColor(color(palette.textPrimary))
            setHintTextColor(color(palette.textSecondary))
            textSize = 13f
            setPadding(dp(12), 0, dp(12), 0)
        }
        val containerState = TextView(this@MainActivity).apply {
            text = "当前选择 · $currentRosContainer"
            setTextColor(color(palette.textSecondary))
            textSize = 10f
        }
        val controls = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(containerInput, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(parkingPrimaryButton("应用容器") {
                val selected = containerInput.text.toString().trim()
                if (selected.isBlank()) {
                    setStatus("请输入 ROS 容器 ID 或名称")
                } else {
                    currentRosContainer = selected
                    containerState.text = "当前选择 · $selected"
                    sendGet(api().selectContainerUrl(selected), "切换 ROS 容器 $selected", readTimeoutMillis = 15000)
                }
            }, LinearLayout.LayoutParams(dp(112), dp(48)).apply { setMargins(dp(9), 0, 0, 0) })
        }
        addView(controls, matchWrapParams(top = 11))

        val statusActions = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(containerState, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "检查状态 ›"
                setTextColor(color(palette.accentText))
                textSize = 10f
                isClickable = true
                setOnClickListener { sendGet(api().containerStatusUrl(), "检查 ROS 容器") }
            })
        }
        addView(statusActions, matchWrapParams(top = 9))
    }

    private fun parkingActionRow(title: String, subtitle: String, action: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            val palette = parkingPalette()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(12), dp(2), dp(12))
            background = bottomBorderBackground(palette.border)
            isClickable = true
            setOnClickListener { action() }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    setTextColor(color(palette.textPrimary))
                    textSize = 13f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    setTextColor(color(palette.textSecondary))
                    textSize = 9f
                }, matchWrapParams(top = 2))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "›"
                setTextColor(color(palette.accentText))
                textSize = 22f
            })
            setPressFeedback(this)
        }

    private fun showParkingGoalSheet() {
        showProductSheet("导航目标", "发布初始位姿后，再发送停车场目标点。", "Nav2") { panel ->
            val initX = poseInput("0", "x")
            val initY = poseInput("0", "y")
            val initYaw = poseInput("0", "yaw")
            panel.addView(poseInputRow("初始位姿", initX, initY, initYaw), matchWrapParams(top = 10))
            panel.addView(secondaryButton("发布初始位姿") {
                sendGet(
                    api().navigationInitialPoseUrl(readPose(initX), readPose(initY), readPose(initYaw)),
                    "发布初始位姿"
                )
            })
            val goalX = poseInput("1", "x")
            val goalY = poseInput("0", "y")
            val goalYaw = poseInput("0", "yaw")
            panel.addView(poseInputRow("目标点", goalX, goalY, goalYaw), matchWrapParams(top = 12))
            panel.addView(primaryButton("发送目标点") {
                sendGet(
                    api().navigationGoalUrl(readPose(goalX), readPose(goalY), readPose(goalYaw)),
                    "发送导航目标"
                )
            })
        }
    }

    private fun parkingSectionTitle(title: String, subtitle: String): LinearLayout =
        LinearLayout(this).apply {
            val palette = parkingPalette()
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(color(palette.textPrimary))
                textSize = 15f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                setTextColor(color(palette.textSecondary))
                textSize = 10f
            }, matchWrapParams(top = 2))
        }

    private fun parkingOutlineButton(text: String, action: () -> Unit): Button = Button(this).apply {
        val palette = parkingPalette()
        this.text = text
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        setTextColor(color(palette.textPrimary))
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        background = tactileBackground(palette.surface, palette.surfaceAlt, palette.border, 16)
        elevation = dp(2).toFloat()
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun parkingPrimaryButton(text: String, action: () -> Unit): Button = Button(this).apply {
        val palette = parkingPalette()
        this.text = text
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        setTextColor(color(accentOnColor()))
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        background = tactileBackground(palette.accent, palette.accentSoft, palette.accent, 16)
        elevation = dp(3).toFloat()
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun parkingDangerButton(text: String, action: () -> Unit): Button = Button(this).apply {
        val palette = parkingPalette()
        this.text = text
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        setTextColor(Color.WHITE)
        textSize = 12f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        background = tactileBackground(palette.danger, palette.surfaceAlt, palette.danger, 16)
        elevation = dp(2).toFloat()
        stateListAnimator = null
        setOnClickListener { action() }
    }

    private fun taskModeGallery(): LinearLayout {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.product_panel_bg, theme)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = matchWrapParams(bottom = 12)
        }
        shell.addView(sectionHeader("选择模式", "点击模式进入详情，不在首页堆满说明"))
        val grid = GridLayout(this).apply {
            columnCount = 2
        }
        FeatureCatalog.trainingTasks().forEach { task ->
            grid.addView(taskLaunchTile(task), GridLayout.LayoutParams().apply {
                width = 0
                height = dp(InteractionSpec.modeTileHeightDp())
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(8), dp(4), 0)
            })
        }
        shell.addView(grid)
        return shell
    }

    private fun taskLaunchTile(task: RobotTask): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.mode_tile_product_bg, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { showTaskSheet(task) }
            setPressFeedback(this)
        }
        tile.addView(TextView(this).apply {
            text = task.title
            setTextColor(color("#141A1F"))
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        tile.addView(TextView(this).apply {
            text = task.description
            setTextColor(color("#66706C"))
            textSize = 12f
            maxLines = 2
        }, matchWrapParams(top = 6))
        tile.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))
        tile.addView(chip(task.category))
        return tile
    }

    private fun showTaskSheet(task: RobotTask) {
        showProductSheet(task.title, task.description, task.category) { panel ->
            panel.addView(horizontalButtons(
                primaryButton("启动${task.title}") { sendGet(api().startTaskUrl(task.key), "启动${task.title}") },
                secondaryButton("停止") { sendGet(api().stopTaskUrl(task.key), "停止${task.title}") }
            ))
            panel.addView(dangerButton("急停") { sendGet(api().emergencyStopUrl(), "急停") })
        }
    }

    private fun visionPreviewSurface(): FrameLayout {
        val surface = FrameLayout(this).apply {
            background = resources.getDrawable(R.drawable.map_surface_bg, theme)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = matchWrapParams(bottom = 12).apply {
                height = dp(232)
            }
        }
        surface.addView(TextView(this).apply {
            text = "CAMERA"
            setTextColor(color("#9AA59D"))
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        surface.addView(TextView(this).apply {
            text = "视频流待接入"
            gravity = Gravity.CENTER
            setTextColor(color("#141A1F"))
            textSize = 22f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(keyPrimaryButton("启动相机") { sendGet(api().startTaskUrl("camera"), "启动相机") }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(keySecondaryButton("HSV 调参") { sendGet(api().startTaskUrl("hsv"), "启动 HSV 调参") }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        surface.addView(actions, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50), Gravity.BOTTOM))
        return surface
    }

    private fun visionFeatureDock(): LinearLayout {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.product_panel_bg, theme)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = matchWrapParams(bottom = 12)
        }
        shell.addView(sectionHeader("视觉能力", "训练完成后逐项接入，不伪造实时结果"))
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        FeatureCatalog.visionFeatures().forEach { item ->
            row.addView(featureLaunchTile(item), LinearLayout.LayoutParams(dp(158), dp(116)).apply {
                setMargins(0, dp(10), dp(10), 0)
            })
        }
        scroll.addView(row)
        shell.addView(scroll)
        return shell
    }

    private fun featureLaunchTile(item: FeatureItem): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.mode_tile_product_bg, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { showFeatureSheet(item) }
            setPressFeedback(this)
        }
        tile.addView(chip(item.status))
        tile.addView(TextView(this).apply {
            text = item.title
            setTextColor(color("#141A1F"))
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, matchWrapParams(top = 10))
        tile.addView(TextView(this).apply {
            text = item.description
            setTextColor(color("#66706C"))
            textSize = 12f
            maxLines = 2
        }, matchWrapParams(top = 5))
        return tile
    }

    private fun showFeatureSheet(item: FeatureItem) {
        showProductSheet(item.title, item.description, item.status) { panel ->
            panel.addView(disabledButton("等待模型或视频流接入"))
            panel.addView(secondaryButton("返回视觉页") { renderPage("vision") })
        }
    }

    private fun mapPreviewSurface(): FrameLayout {
        val surface = FrameLayout(this).apply {
            background = resources.getDrawable(R.drawable.map_surface_bg, theme)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = matchWrapParams(bottom = 12).apply {
                height = dp(238)
            }
        }
        surface.addView(TextView(this).apply {
            text = "MAP CANVAS"
            setTextColor(color("#8F9A93"))
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        surface.addView(TextView(this).apply {
            text = "地图画布等待接入"
            gravity = Gravity.CENTER
            setTextColor(color("#141A1F"))
            textSize = 21f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actions.addView(keyPrimaryButton("启动建图") { sendGet(api().startTaskUrl("map_gmapping"), "启动建图") }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(keySecondaryButton("进入驾驶") { renderPage("drive") }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        surface.addView(actions, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50), Gravity.BOTTOM))
        return surface
    }

    private fun navigationTimeline(title: String, subtitle: String, tasks: List<RobotTask>): LinearLayout {
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.product_panel_bg, theme)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = matchWrapParams(bottom = 12)
        }
        shell.addView(sectionHeader(title, subtitle))
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tasks.forEachIndexed { index, task ->
            row.addView(navigationStepPill(index + 1, task), LinearLayout.LayoutParams(dp(150), dp(108)).apply {
                setMargins(0, dp(10), dp(10), 0)
            })
        }
        scroll.addView(row)
        shell.addView(scroll)
        return shell
    }

    private fun navigationStepPill(step: Int, task: RobotTask): LinearLayout {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.mode_tile_product_bg, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            setOnClickListener { showNavigationTaskSheet(step, task) }
            setPressFeedback(this)
        }
        tile.addView(TextView(this).apply {
            text = "0$step"
            setTextColor(color("#0F766E"))
            textSize = 12f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        tile.addView(TextView(this).apply {
            text = task.title
            setTextColor(color("#141A1F"))
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            maxLines = 1
        }, matchWrapParams(top = 7))
        tile.addView(TextView(this).apply {
            text = task.description
            setTextColor(color("#66706C"))
            textSize = 11f
            maxLines = 2
        }, matchWrapParams(top = 5))
        return tile
    }

    private fun showNavigationTaskSheet(step: Int, task: RobotTask) {
        showProductSheet("步骤 $step · ${task.title}", task.description, task.category) { panel ->
            panel.addView(horizontalButtons(
                primaryButton("启动") { sendGet(api().startTaskUrl(task.key), "启动${task.title}") },
                secondaryButton("停止") { sendGet(api().stopTaskUrl(task.key), "停止${task.title}") }
            ))
            panel.addView(secondaryButton("查看驾驶页") { renderPage("drive") })
        }
    }

    private fun actionSafetyStrip(): LinearLayout {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = resources.getDrawable(R.drawable.product_panel_bg, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = matchWrapParams(bottom = 12)
        }
        strip.addView(dangerButton("急停") {
            currentDirection = null
            mainHandler.removeCallbacks(repeatMoveRunnable)
            sendGet(api().emergencyStopUrl(), "急停")
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        strip.addView(secondaryButton("全部停止") {
            currentDirection = null
            mainHandler.removeCallbacks(repeatMoveRunnable)
            sendGet(api().stopAllUrl(), "全部停止")
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        return strip
    }

    private fun compactActivityLog(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.product_panel_bg, theme)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = matchWrapParams(bottom = 12)
        }
        panel.addView(cardHeader("最近操作", "日志"))
        txtLog = bodyText(logText).apply {
            setTextColor(color("#334E68"))
            maxLines = 5
        }
        panel.addView(txtLog, matchWrapParams(top = 8))
        return panel
    }

    private fun showConnectionSheet() {
        showProductSheet("连接小车", "输入小车端 Python 服务地址。保存后可以直接检测连接。", "HTTP") { panel ->
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
            panel.addView(row, matchWrapParams(top = 12))
            panel.addView(horizontalButtons(
                primaryButton("保存并检测") { sendGet(api().healthUrl(), "检测连接") },
                secondaryButton("刷新状态") { sendGet(api().statusUrl(), "刷新状态") }
            ))
            panel.addView(dangerButton("全部停止") { sendGet(api().stopAllUrl(), "全部停止") })
        }
    }

    private fun showProductSheet(
        title: String,
        subtitle: String,
        status: String,
        content: (LinearLayout) -> Unit
    ) {
        val themedParkingPage = selectedPage != "home"
        val palette = parkingPalette()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (themedParkingPage) {
                roundedBackground(palette.surface, palette.border, 24)
            } else {
                resources.getDrawable(R.drawable.sheet_bg, theme)
            }
            setPadding(dp(18), dp(10), dp(18), dp(18))
        }
        val handle = View(this).apply {
            background = if (themedParkingPage) {
                roundedBackground(palette.border, palette.border, 4)
            } else {
                resources.getDrawable(R.drawable.thin_handle_bg, theme)
            }
        }
        panel.addView(handle, LinearLayout.LayoutParams(dp(42), dp(5)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setMargins(0, 0, 0, dp(14))
        })
        panel.addView(cardHeader(title, status))
        panel.addView(bodyText(subtitle), matchWrapParams(top = 8))
        content(panel)
        dialog.setContentView(panel)
        dialog.setOnShowListener {
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
                setGravity(Gravity.BOTTOM)
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply {
                    width = WindowManager.LayoutParams.MATCH_PARENT
                    dimAmount = 0.30f
                }
            }
            panel.translationY = dp(InteractionSpec.sheetPeekHeightDp()).toFloat()
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
        actions.addView(secondaryHeroButton("AI 助手") { renderPage("ai") }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
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
            HomeAction("AI 助手", "任务拆解", "规划中") { renderPage("ai") },
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
            background = resources.getDrawable(R.drawable.cockpit_bg, theme)
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
            text = "运动控制"
            setTextColor(color("#141A1F"))
            textSize = 22f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        copy.addView(TextView(this).apply {
            text = "低延迟连续发送 · 松手刹停"
            setTextColor(color("#66706C"))
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

    private fun navigationOverviewCard(): LinearLayout {
        val card = card().apply {
            background = resources.getDrawable(R.drawable.remote_console_bg, theme)
        }
        card.addView(sectionHeader("实验理解", "APP 负责下发流程命令，ROS 负责建图、规划和控制"))
        card.addView(statusRow("建图", "Gmapping 根据雷达扫描和里程计逐步生成栅格地图", "m1/m2/m4"))
        card.addView(statusRow("路径规划", "Nav2 收到目标点后由 planner_server 计算从当前位置到目标点的路线", "Nav2"))
        card.addView(statusRow("自动导航", "controller_server 使用 DWA 或 TEB 生成速度指令跟随路线", "DWA/TEB"))
        return card
    }

    private fun navigationWorkflowCard(title: String, subtitle: String, tasks: List<RobotTask>): LinearLayout {
        val card = card()
        card.addView(sectionHeader(title, subtitle))
        tasks.forEachIndexed { index, task ->
            card.addView(navigationTaskRow(index + 1, task))
        }
        return card
    }

    private fun navigationTaskRow(step: Int, task: RobotTask): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = resources.getDrawable(R.drawable.feature_card_bg, theme)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = matchWrapParams(top = 10)
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(modeBadge(step.toString()), LinearLayout.LayoutParams(dp(44), dp(44)))
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = task.title
            setTextColor(color("#102A43"))
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = task.description
            setTextColor(color("#627D98"))
            textSize = 12f
            maxLines = 2
        }, matchWrapParams(top = 3))
        top.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(dp(10), 0, 0, 0)
        })
        top.addView(chip(task.category))
        row.addView(top)
        row.addView(horizontalButtons(
            primaryButton("启动") { sendGet(api().startTaskUrl(task.key), "启动${task.title}") },
            secondaryButton("停止") { sendGet(api().stopTaskUrl(task.key), "停止${task.title}") }
        ))
        return row
    }

    private fun navigationPoseCard(): LinearLayout {
        val card = card()
        card.addView(sectionHeader("目标点与路径规划", "先发布初始位姿，再发送目标点。目标点发出后 Nav2 会自动规划路径并执行导航。"))

        val initX = poseInput("0", "x")
        val initY = poseInput("0", "y")
        val initYaw = poseInput("0", "yaw")
        card.addView(poseInputRow("初始位姿", initX, initY, initYaw), matchWrapParams(top = 10))
        card.addView(primaryButton("发布初始位姿") {
            sendGet(
                api().navigationInitialPoseUrl(readPose(initX), readPose(initY), readPose(initYaw)),
                "发布初始位姿"
            )
        })

        val goalX = poseInput("1", "x")
        val goalY = poseInput("0", "y")
        val goalYaw = poseInput("0", "yaw")
        card.addView(poseInputRow("目标点", goalX, goalY, goalYaw), matchWrapParams(top = 14))
        card.addView(horizontalButtons(
            primaryButton("发送目标点") {
                sendGet(api().navigationGoalUrl(readPose(goalX), readPose(goalY), readPose(goalYaw)), "发送导航目标")
            },
            secondaryButton("用 RViz 校准") {
                setStatus("请在 RViz 用 2D Pose Estimate 校准位姿")
                appendLog("提示\n手册要求先在 RViz 校准初始位姿，使雷达点云与地图轮廓重合。")
            }
        ))
        return card
    }

    private fun poseInputRow(label: String, x: EditText, y: EditText, yaw: EditText): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        row.addView(sectionTitle(label))
        val inputs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        inputs.addView(x, LinearLayout.LayoutParams(0, dp(46), 1f))
        inputs.addView(y, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        inputs.addView(yaw, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        row.addView(inputs, matchWrapParams(top = 8))
        return row
    }

    private fun poseInput(defaultValue: String, hintText: String): EditText =
        EditText(this).apply {
            val palette = parkingPalette()
            val themed = selectedPage != "home"
            setText(defaultValue)
            hint = hintText
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            background = if (themed) roundedBackground(palette.surfaceAlt, palette.border, 12) else resources.getDrawable(R.drawable.input_bg, theme)
            setTextColor(color(if (themed) palette.textPrimary else "#102A43"))
            setHintTextColor(color(if (themed) palette.textSecondary else "#829AB1"))
            textSize = 14f
            setPadding(dp(10), 0, dp(10), 0)
        }

    private fun readPose(input: EditText): Double =
        input.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0

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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupHoldButton(button: Button, direction: String) {
        val tracker = HoldGestureTracker()
        button.isFocusable = true
        button.contentDescription = "${directionLabel(direction).removeSuffix("中")}，按住持续移动"
        button.setOnClickListener {
            if (currentDirection != null) return@setOnClickListener
            startMove(direction)
            mainHandler.postDelayed({
                if (currentDirection == direction) stopMove()
            }, 320L)
        }
        button.setOnTouchListener { view, event ->
            fun setPressed(pressed: Boolean) {
                view.animate()
                    .scaleX(if (pressed) InteractionSpec.pressFeedbackScale() else 1f)
                    .scaleY(if (pressed) InteractionSpec.pressFeedbackScale() else 1f)
                    .alpha(if (pressed) 0.86f else 1f)
                    .setDuration(if (pressed) 90 else 180)
                    .apply {
                        if (!pressed) setInterpolator(OvershootInterpolator())
                    }
                    .start()
            }

            fun handle(action: HoldGestureAction) {
                if (action != HoldGestureAction.STOP) return
                setPressed(false)
                stopMove()
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (tracker.onDown(event.getPointerId(event.actionIndex)) == HoldGestureAction.START) {
                        setPressed(true)
                        startMove(direction)
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    handle(tracker.onPointerUp(event.getPointerId(event.actionIndex)))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val pointerIndex = tracker.activePointerId()?.let(event::findPointerIndex) ?: -1
                    val inside = pointerIndex >= 0 &&
                        event.getX(pointerIndex) >= 0f && event.getX(pointerIndex) < view.width.toFloat() &&
                        event.getY(pointerIndex) >= 0f && event.getY(pointerIndex) < view.height.toFloat()
                    handle(tracker.onMove(pointerIndex >= 0, inside))
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handle(tracker.onUp())
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handle(tracker.onCancel())
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
        DriveSafetySpec.stopDispatchLanes().forEach { lane ->
            sendGet(
                url,
                "停止移动",
                quiet = true,
                executorService = when (lane) {
                    StopDispatchLane.URGENT -> stopExecutor
                    StopDispatchLane.MOVE_BARRIER -> moveExecutor
                },
                onLatencyMillis = { latency ->
                    fullscreenDriveOverlay?.updateControlLatency(latency)
                }
            )
        }
    }

    private fun forceStopForExit(event: DriveExitEvent) {
        if (DriveSafetySpec.shouldStop(currentDirection, event)) {
            stopMove()
        }
    }

    private fun saveJarvisToken(): String {
        val token = jarvisTokenInput?.text?.toString().orEmpty().trim()
        jarvisToken = token
        if (token.isNotBlank()) {
            jarvisCredentials.saveToken(token)
        }
        return token
    }

    private fun sendMove(direction: String, quiet: Boolean) {
        val url = api().moveUrl(direction, currentSpeed(), currentTurn())
        val requestedAt = SystemClock.elapsedRealtime()
        recordMotionDiagnostic("direction=$direction state=requested at_ms=$requestedAt quiet=$quiet")
        if (!moveGate.tryBegin()) {
            Log.i("ICarMotion", "direction=$direction skipped=gate_busy")
            recordMotionDiagnostic("direction=$direction state=gate_busy at_ms=${SystemClock.elapsedRealtime()}")
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
            onFinished = { moveGate.finish() },
            onLatencyMillis = { networkLatency ->
                val endToEndLatency = SystemClock.elapsedRealtime() - requestedAt
                Log.i(
                    "ICarMotion",
                    "direction=$direction network_ms=$networkLatency end_to_end_ms=$endToEndLatency quiet=$quiet"
                )
                recordMotionDiagnostic(
                    "direction=$direction state=completed network_ms=$networkLatency end_to_end_ms=$endToEndLatency quiet=$quiet"
                )
                fullscreenDriveOverlay?.updateControlLatency(endToEndLatency)
            }
        )
    }

    private fun recordMotionDiagnostic(value: String) {
        val preferences = getSharedPreferences("motion_diagnostics", MODE_PRIVATE)
        val entries = preferences.getString("history", "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .plus(value)
            .toList()
            .takeLast(30)
        preferences.edit()
            .putString("latest", value)
            .putString("history", entries.joinToString("\n"))
            .apply()
    }

    private fun sendGet(
        url: String,
        label: String,
        quiet: Boolean = false,
        executorService: ExecutorService = commandExecutor,
        readTimeoutMillis: Int = if (quiet) 1200 else 5000,
        onFinished: () -> Unit = {},
        onLatencyMillis: ((Long) -> Unit)? = null
    ) {
        if (!quiet) {
            setStatus("$label 请求中")
        }

        executorService.execute {
            val latencyMeasurement = onLatencyMillis?.let { callback ->
                RequestLatencyMeasurement(SystemClock.elapsedRealtime()) { latency ->
                    runOnUiThread { callback(latency) }
                }
            }
            val result = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2500
                    readTimeout = readTimeoutMillis
                }
                try {
                    val code = connection.responseCode
                    val body = if (quiet) {
                        ""
                    } else {
                        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                        stream?.use { input ->
                            HttpResponseBodyReader.read(input, connection.contentLengthLong, includeBody = true)
                        }.orEmpty()
                    }
                    "HTTP $code ${body.take(180)}"
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { error ->
                "失败：${error.message ?: error.javaClass.simpleName}"
            }
            if (label.startsWith("移动 ")) {
                runOnUiThread {
                    recordMotionDiagnostic("label=$label server_response=${result.take(260)}")
                }
            }
            latencyMeasurement?.complete(SystemClock.elapsedRealtime())

            runOnUiThread {
                if (!quiet) {
                    updateVehicleConnection(result.startsWith("HTTP "))
                    setStatus("$label $result")
                    appendLog("$label\n$result")
                    if (result.startsWith("HTTP 2")) {
                        notificationEventForLabel(label)?.let { event -> sendNotification(event) }
                    }
                }
            }
            onFinished()
        }
    }

    private fun sendPostJson(
        url: String,
        jsonBody: String,
        label: String,
        readTimeoutMillis: Int = 8000
    ) {
        setStatus("$label 请求中")
        commandExecutor.execute {
            val result = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2500
                    readTimeout = readTimeoutMillis
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                connection.outputStream.use { output ->
                    output.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { input ->
                    BufferedReader(InputStreamReader(input)).readText()
                }.orEmpty()
                connection.disconnect()
                "HTTP $code ${body.take(220)}"
            }.getOrElse { error ->
                "失败：${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread {
                updateVehicleConnection(result.startsWith("HTTP "))
                setStatus("$label $result")
                appendLog("$label\n$result")
            }
        }
    }

    private fun notificationEventForLabel(label: String): String? = when {
        label.contains("急停") -> "emergency_stop"
        label.contains("全部停止") -> "all_stop"
        label.contains("检测连接") -> "connected"
        label.contains("驾驶") || label.contains("底盘") -> "drive_ready"
        label.contains("巡逻") && label.contains("停止") -> "patrol_stop"
        label.contains("巡逻") && label.contains("启动") -> "patrol_start"
        label.contains("启动相机") -> "camera_ready"
        label.contains("启动") -> "task_start"
        label.contains("停止") -> "task_stop"
        else -> null
    }

    private fun sendNotification(event: String) {
        val url = api().notifyUrl(event)
        voiceExecutor.execute {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 800
                    readTimeout = 2500
                    doOutput = true
                }
                connection.outputStream.use { output -> output.write(ByteArray(0)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.use { input ->
                    BufferedReader(InputStreamReader(input)).readText()
                }.orEmpty()
                connection.disconnect()
                if (code !in 200..299) {
                    runOnUiThread { appendLog("语音通知失败\nHTTP $code ${body.take(120)}") }
                }
            }.onFailure { error ->
                runOnUiThread {
                    appendLog("语音通知失败\n${error.message ?: error.javaClass.simpleName}")
                }
            }
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

    private fun currentSpeed(): Double = fullscreenSpeedSelection(speedProgress).speed

    private fun currentTurn(): Double = fullscreenSpeedSelection(speedProgress).turn

    private fun updateSpeedText() {
        txtSpeed?.text = String.format(Locale.US, "速度 %.2f m/s，转向 %.2f rad/s", currentSpeed(), currentTurn())
        navigationManualSpeedText?.text = String.format(
            Locale.US,
            "速度 %.2f m/s · 转向 %.2f rad/s",
            currentSpeed(),
            currentTurn()
        )
        if (driveSpeedSeekBar?.progress != speedProgress) {
            driveSpeedSeekBar?.progress = speedProgress
        }
        fullscreenDriveOverlay?.updateSpeedProgress(speedProgress)
    }

    private fun updateDriveState() {
        fullscreenDriveOverlay?.updateMotion(currentMotionLabel)
        navigationManualStateText?.text = currentMotionLabel
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
        pageStatusText?.text = when {
            text.contains("失败") -> "操作失败"
            text.contains("请求中") -> "执行中"
            text.contains("HTTP 2") -> "已完成"
            else -> text.substringBefore(" HTTP").take(12)
        }
    }

    private fun updateVehicleConnection(connected: Boolean) {
        isVehicleConnected = connected
        vehicleStage?.setConnected(connected)
        homeConnectionText?.apply {
            text = if (connected) "●  已连接" else "●  连接失败"
            setTextColor(color(if (connected) "#4FE1B6" else "#E16B64"))
        }
    }

    private fun appendLog(message: String) {
        val time = timeFormat.format(Date())
        logText = "[$time] $message\n\n$logText".take(2200)
        txtLog?.text = logText
    }

    private fun updateNavigation() {
        val home = selectedPage == "home"
        val palette = parkingPalette()
        val darkChrome = parkingThemeMode == ParkingThemeMode.DARK
        navViews.forEach { (key, view) ->
            val selected = key == selectedPage
            view.background = if (home && parkingThemeMode == ParkingThemeMode.DARK) {
                resources.getDrawable(
                    if (selected) R.drawable.nav_dark_selected_bg else R.drawable.nav_plain_bg,
                    theme
                )
            } else if (selected) {
                roundedBackground(
                    palette.accentSoft,
                    palette.border,
                    15
                )
            } else {
                ColorDrawable(Color.TRANSPARENT)
            }
            view.setTextColor(color(
                if (selected) {
                    if (home && parkingThemeMode == ParkingThemeMode.DARK) "#C6B57B" else palette.accentText
                } else {
                    if (darkChrome) "#7D8882" else palette.textSecondary
                }
            ))
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
            val palette = parkingPalette()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(color(if (selectedPage == "home") "#102A43" else palette.textPrimary))
                textSize = 17f
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chip(chipText))
        }

    private fun sectionTitle(text: String): TextView =
        TextView(this).apply {
            val palette = parkingPalette()
            this.text = text
            setTextColor(color(if (selectedPage == "home") "#102A43" else palette.textPrimary))
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }

    private fun bodyText(text: String): TextView =
        TextView(this).apply {
            val palette = parkingPalette()
            this.text = text
            setTextColor(color(if (selectedPage == "home") "#627D98" else palette.textSecondary))
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }

    private fun chip(text: String): TextView =
        TextView(this).apply {
            val palette = parkingPalette()
            this.text = text
            setTextColor(color(if (selectedPage == "home") {
                if (text.contains("可用") || text.contains("基础") || text.contains("实训")) "#166534" else "#92400E"
            } else palette.accentText))
            textSize = 12f
            gravity = Gravity.CENTER
            background = if (selectedPage == "home") {
                resources.getDrawable(
                    if (text.contains("可用") || text.contains("基础") || text.contains("实训")) R.drawable.chip_ready_bg else R.drawable.chip_wait_bg,
                    theme
                )
            } else roundedBackground(palette.accentSoft, palette.border, 14)
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
        if (selectedPage == "home") styledButton(text, R.drawable.button_primary, Color.WHITE, action)
        else parkingPrimaryButton(text, action)

    private fun secondaryButton(text: String, action: () -> Unit): Button =
        if (selectedPage == "home") styledButton(text, R.drawable.button_secondary, color("#102A43"), action)
        else parkingOutlineButton(text, action)

    private fun dangerButton(text: String, action: () -> Unit): Button =
        if (selectedPage == "home") styledButton(text, R.drawable.button_danger, Color.WHITE, action)
        else parkingDangerButton(text, action)

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

    private fun parkingPalette(): ParkingPalette = ParkingThemeSpec.palette(parkingThemeMode)

    private fun accentOnColor(): String = if (parkingThemeMode == ParkingThemeMode.LIGHT) {
        parkingPalette().textPrimary
    } else {
        parkingPalette().background
    }

    private fun attachGlobalThemeToggle(parent: ViewGroup, params: ViewGroup.LayoutParams) {
        (globalThemeToggle.parent as? ViewGroup)?.removeView(globalThemeToggle)
        parent.addView(globalThemeToggle, params)
    }

    private fun styleGlobalThemeToggle(palette: ParkingPalette) {
        globalThemeToggle.apply {
            text = if (parkingThemeMode == ParkingThemeMode.LIGHT) "☾" else "☀"
            contentDescription = if (parkingThemeMode == ParkingThemeMode.LIGHT) {
                "切换到深色模式"
            } else {
                "切换到浅色模式"
            }
            gravity = Gravity.CENTER
            setTextColor(color(palette.accentText))
            background = tactileBackground(palette.surface, palette.surfaceAlt, palette.border, 14)
        }
    }

    private fun roundedBackground(
        fill: String,
        stroke: String,
        radiusDp: Int,
        endFill: String? = null
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(color(fill), color(endFill ?: fill))
    ).apply {
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), color(stroke))
    }

    private fun tactileBackground(
        normalFill: String,
        pressedFill: String,
        stroke: String,
        radiusDp: Int
    ): Drawable {
        val states = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedBackground(pressedFill, stroke, radiusDp)
            )
            addState(intArrayOf(), roundedBackground(normalFill, stroke, radiusDp, pressedFill))
        }
        return RippleDrawable(
            ColorStateList.valueOf(color(parkingPalette().accentSoft)),
            states,
            null
        )
    }

    private fun bottomBorderBackground(border: String): Drawable =
        object : ColorDrawable(Color.TRANSPARENT) {
            private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = this@MainActivity.color(border)
            }

            override fun draw(canvas: Canvas) {
                super.draw(canvas)
                canvas.drawRect(
                    bounds.left.toFloat(),
                    (bounds.bottom - dp(1)).toFloat(),
                    bounds.right.toFloat(),
                    bounds.bottom.toFloat(),
                    linePaint
                )
            }
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

    private data class PatrolCheckpoint(
        val code: String,
        val title: String,
        val subtitle: String,
        val taskKey: String?,
        val status: String
    )
}
