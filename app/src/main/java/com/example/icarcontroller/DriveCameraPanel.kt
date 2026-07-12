package com.example.icarcontroller

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

data class CameraPresentation(
    val text: String,
    val retryVisible: Boolean
)

data class CameraOverlayGeometry(
    val centered: Boolean,
    val startInsetDp: Int,
    val topInsetDp: Int,
    val endInsetDp: Int,
    val bottomInsetDp: Int
)

fun cameraErrorOverlayGeometry(): CameraOverlayGeometry = CameraOverlayGeometry(
    centered = true,
    startInsetDp = 0,
    topInsetDp = 0,
    endInsetDp = 0,
    bottomInsetDp = 0
)

fun performControlledReparent(beginReparent: () -> Unit, removeView: () -> Unit) {
    beginReparent()
    removeView()
}

class CameraSnapshotRelay(initialSnapshot: CameraViewSnapshot) {
    private var snapshot = initialSnapshot
    private var observer: ((CameraViewSnapshot) -> Unit)? = null

    fun currentSnapshot(): CameraViewSnapshot = snapshot

    fun setObserver(observer: ((CameraViewSnapshot) -> Unit)?) {
        this.observer = observer
        observer?.invoke(snapshot)
    }

    fun publish(snapshot: CameraViewSnapshot) {
        this.snapshot = snapshot
        observer?.invoke(snapshot)
    }
}

fun cameraStatePresentation(snapshot: CameraViewSnapshot): CameraPresentation = when (snapshot.state) {
    CameraViewState.CONNECTING -> CameraPresentation("正在连接", false)
    CameraViewState.LIVE -> CameraPresentation("实时 · ${snapshot.fps} FPS", false)
    CameraViewState.BUSY -> CameraPresentation("摄像头正在被其他任务使用", true)
    CameraViewState.MISSING -> CameraPresentation("未检测到小车摄像头", true)
    CameraViewState.DISCONNECTED -> CameraPresentation("连接中断", true)
    CameraViewState.IDLE -> CameraPresentation("视频未启动", true)
}

class DriveCameraPanel(
    context: Context,
    private val palette: ParkingPalette
) : FrameLayout(context) {
    private var streamView = MjpegStreamView(context)
    private var streamUrl: String? = null
    private var streamRequested = false
    private var onFullscreenRequested: (() -> Unit)? = null
    private val snapshotRelay = CameraSnapshotRelay(CameraViewSnapshot(CameraViewState.IDLE))

    private val statusChip = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 11f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(color(palette.accentText))
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = glassBackground(palette.accentSoft, palette.border, 8)
    }

    private val fullscreenButton = TextView(context).apply {
        text = "⛶"
        contentDescription = "全屏显示摄像头"
        gravity = Gravity.CENTER
        textSize = 21f
        setTextColor(color(palette.textPrimary))
        background = glassBackground(palette.surface, palette.border, 8)
        visibility = View.GONE
        isClickable = true
        isFocusable = true
        setOnClickListener { onFullscreenRequested?.invoke() }
    }

    private val errorMessage = TextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(color(palette.textPrimary))
    }

    private val retryButton = Button(context).apply {
        text = "重试"
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minimumHeight = 0
        textSize = 13f
        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(color(palette.accentText))
        background = glassBackground(palette.accentSoft, palette.accent, 8)
        setOnClickListener { reconnect() }
    }

    private val errorState = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(errorMessage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addView(retryButton, LinearLayout.LayoutParams(dp(88), dp(40)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(10)
        })
    }

    init {
        val errorOverlayGeometry = cameraErrorOverlayGeometry()
        background = panelBackground()
        clipChildren = false
        clipToPadding = false

        addView(streamView, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        })
        addView(statusChip, LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            dp(32),
            Gravity.TOP or Gravity.START
        ).apply {
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })
        addView(fullscreenButton, LayoutParams(
            dp(40),
            dp(40),
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(dp(10), dp(10), dp(10), dp(10))
        })
        addView(errorState, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (errorOverlayGeometry.centered) Gravity.CENTER else Gravity.NO_GRAVITY
        ).apply {
            setMargins(
                dp(errorOverlayGeometry.startInsetDp),
                dp(errorOverlayGeometry.topInsetDp),
                dp(errorOverlayGeometry.endInsetDp),
                dp(errorOverlayGeometry.bottomInsetDp)
            )
        })

        render(CameraViewSnapshot(CameraViewState.IDLE))
    }

    fun start(url: String) {
        streamUrl = url
        streamRequested = true
        streamView.start(url, ::render)
    }

    fun stop() {
        streamRequested = false
        streamView.stop()
    }

    fun release() {
        streamRequested = false
        streamUrl = null
        streamView.release()
        render(CameraViewSnapshot(CameraViewState.IDLE))
        snapshotRelay.setObserver(null)
    }

    fun reconnect() {
        if (streamRequested) {
            streamView.reconnect()
        } else {
            streamUrl?.let(::start)
        }
    }

    fun streamViewForReparent(): MjpegStreamView {
        detachForReparent(streamView)
        return streamView
    }

    fun restoreStreamView(view: MjpegStreamView) {
        detachForReparent(view)
        streamView = view
        addView(view, 0, LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            setMargins(dp(1), dp(1), dp(1), dp(1))
        })
    }

    fun setOnFullscreenRequested(callback: (() -> Unit)?) {
        onFullscreenRequested = callback
        fullscreenButton.visibility = if (callback == null) View.GONE else View.VISIBLE
    }

    fun currentSnapshot(): CameraViewSnapshot = snapshotRelay.currentSnapshot()

    fun setSnapshotObserver(observer: ((CameraViewSnapshot) -> Unit)?) {
        snapshotRelay.setObserver(observer)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (
            width *
                InteractionSpec.drivePortraitCameraHeightUnits().toFloat() /
                InteractionSpec.drivePortraitCameraWidthUnits()
            ).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        )
    }

    private fun render(snapshot: CameraViewSnapshot) {
        snapshotRelay.publish(snapshot)
        val presentation = cameraStatePresentation(snapshot)
        statusChip.text = presentation.text
        errorMessage.text = presentation.text
        errorState.visibility = if (presentation.retryVisible) View.VISIBLE else View.GONE
    }

    private fun detachForReparent(view: MjpegStreamView) {
        val parent = view.parent as? ViewGroup ?: return
        performControlledReparent(view::beginReparent) {
            parent.removeView(view)
        }
    }

    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(color(palette.accentSoft))
        setStroke(dp(1), color(palette.border))
        cornerRadius = dp(8).toFloat()
    }

    private fun glassBackground(fill: String, stroke: String, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(withAlpha(color(fill), 224))
            setStroke(dp(1), color(stroke))
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun withAlpha(value: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(value), Color.green(value), Color.blue(value))

    private fun color(value: String): Int = Color.parseColor(value)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
