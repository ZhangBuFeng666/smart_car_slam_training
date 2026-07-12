package com.example.icarcontroller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class CameraViewState {
    IDLE,
    CONNECTING,
    LIVE,
    BUSY,
    MISSING,
    DISCONNECTED
}

data class CameraViewSnapshot(
    val state: CameraViewState,
    val fps: Int = 0,
    val error: String? = null
)

class MjpegStreamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val stateLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val reconnectDelaysMillis = InteractionSpec.cameraReconnectDelaysMillis()

    private var displayedBitmap: Bitmap? = null
    private var active = false
    private var generation = 0L
    private var streamUrl: String? = null
    private var snapshotListener: ((CameraViewSnapshot) -> Unit)? = null
    private var executor: ScheduledExecutorService? = null
    private var connection: HttpURLConnection? = null
    private var scheduledAttempt: ScheduledFuture<*>? = null
    private var runningAttempt: Future<*>? = null
    private var runningAttemptGeneration: Long? = null

    fun start(url: String, listener: (CameraViewSnapshot) -> Unit) {
        val resources: WorkerResources
        val currentGeneration: Long
        synchronized(stateLock) {
            generation += 1
            currentGeneration = generation
            active = true
            streamUrl = url
            snapshotListener = listener
            resources = detachWorkerLocked(shutdownExecutor = true)
            executor = newExecutor()
        }
        resources.cancel()
        clearBitmapOnMain()
        publishSnapshot(currentGeneration, CameraViewSnapshot(CameraViewState.CONNECTING))
        scheduleAttempt(currentGeneration, retryIndex = 0, delayMillis = 0)
    }

    fun stop() {
        val resources: WorkerResources
        val previousListener: ((CameraViewSnapshot) -> Unit)?
        val stoppedGeneration: Long
        synchronized(stateLock) {
            generation += 1
            stoppedGeneration = generation
            active = false
            previousListener = snapshotListener
            resources = detachWorkerLocked(shutdownExecutor = true)
        }
        resources.cancel()
        clearBitmapOnMain()
        runOnMain {
            if (isStoppedGeneration(stoppedGeneration)) {
                previousListener?.invoke(CameraViewSnapshot(CameraViewState.IDLE))
            }
        }
    }

    fun reconnect() {
        val resources: WorkerResources
        val currentGeneration: Long
        synchronized(stateLock) {
            if (!active || streamUrl == null || snapshotListener == null) {
                return
            }
            generation += 1
            currentGeneration = generation
            resources = detachWorkerLocked(shutdownExecutor = true)
            executor = newExecutor()
        }
        resources.cancel()
        clearBitmapOnMain()
        publishSnapshot(currentGeneration, CameraViewSnapshot(CameraViewState.CONNECTING))
        scheduleAttempt(currentGeneration, retryIndex = 0, delayMillis = 0)
    }

    fun release() {
        val resources: WorkerResources
        synchronized(stateLock) {
            generation += 1
            active = false
            streamUrl = null
            snapshotListener = null
            resources = detachWorkerLocked(shutdownExecutor = true)
        }
        resources.cancel()
        clearBitmapOnMain()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = displayedBitmap ?: return
        if (width <= 0 || height <= 0 || bitmap.width <= 0 || bitmap.height <= 0) {
            return
        }

        val scale = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val scaledWidth = bitmap.width * scale
        val scaledHeight = bitmap.height * scale
        val left = (width - scaledWidth) / 2f
        val top = (height - scaledHeight) / 2f
        destination.set(left, top, left + scaledWidth, top + scaledHeight)
        canvas.drawBitmap(bitmap, null, destination, bitmapPaint)
    }

    private fun scheduleAttempt(currentGeneration: Long, retryIndex: Int, delayMillis: Int) {
        synchronized(stateLock) {
            if (!isCurrentLocked(currentGeneration)) {
                return
            }
            val currentExecutor = executor ?: return
            scheduledAttempt = currentExecutor.schedule(
                { runAttempt(currentGeneration, retryIndex) },
                delayMillis.toLong(),
                TimeUnit.MILLISECONDS
            )
        }
    }

    private fun runAttempt(currentGeneration: Long, retryIndex: Int) {
        val url = synchronized(stateLock) {
            if (!isCurrentLocked(currentGeneration)) {
                return
            }
            runningAttempt = scheduledAttempt
            runningAttemptGeneration = currentGeneration
            scheduledAttempt = null
            streamUrl
        } ?: return

        publishSnapshot(currentGeneration, CameraViewSnapshot(CameraViewState.CONNECTING))
        var openedConnection: HttpURLConnection? = null
        var decodedLiveFrame = false
        try {
            openedConnection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                useCaches = false
                doInput = true
                setRequestProperty("Accept", "multipart/x-mixed-replace")
                setRequestProperty("Connection", "close")
            }

            synchronized(stateLock) {
                if (!isCurrentLocked(currentGeneration)) {
                    openedConnection.disconnect()
                    return
                }
                connection = openedConnection
            }

            val responseCode = openedConnection.responseCode
            if (responseCode == HttpURLConnection.HTTP_UNAVAILABLE) {
                val responseText = readResponseText(openedConnection.errorStream)
                val state = when (InteractionSpec.cameraHttp503State(responseText)) {
                    "busy" -> CameraViewState.BUSY
                    "missing" -> CameraViewState.MISSING
                    else -> CameraViewState.DISCONNECTED
                }
                publishSnapshot(currentGeneration, CameraViewSnapshot(state, error = responseText.ifBlank { null }))
                scheduleRetry(currentGeneration, retryIndex)
                return
            }
            if (responseCode !in 200..299) {
                throw EOFException("Camera stream returned HTTP $responseCode")
            }

            readFrames(currentGeneration, openedConnection.inputStream) {
                decodedLiveFrame = true
            }
            if (isCurrent(currentGeneration)) {
                throw EOFException("Camera stream ended")
            }
        } catch (error: Exception) {
            if (isCurrent(currentGeneration)) {
                publishSnapshot(
                    currentGeneration,
                    CameraViewSnapshot(
                        CameraViewState.DISCONNECTED,
                        error = error.message ?: error.javaClass.simpleName
                    )
                )
                scheduleRetry(currentGeneration, if (decodedLiveFrame) 0 else retryIndex)
            }
        } finally {
            synchronized(stateLock) {
                if (connection === openedConnection) {
                    connection = null
                }
                if (runningAttemptGeneration == currentGeneration) {
                    runningAttempt = null
                    runningAttemptGeneration = null
                }
            }
            openedConnection?.disconnect()
        }
    }

    private fun readFrames(
        currentGeneration: Long,
        input: InputStream,
        onLiveFrame: () -> Unit
    ) {
        val reader = MjpegFrameReader(input, InteractionSpec.cameraMaxFrameBytes())
        var windowStartedAt = SystemClock.elapsedRealtime()
        var framesInWindow = 0
        var liveReported = false

        while (isCurrent(currentGeneration)) {
            val frame = reader.nextFrame() ?: return
            if (!isCurrent(currentGeneration)) {
                return
            }
            val bitmap = decodeFrame(frame) ?: continue
            if (!publishBitmap(currentGeneration, bitmap)) {
                return
            }

            onLiveFrame()
            framesInWindow += 1
            if (!liveReported) {
                liveReported = true
                publishSnapshot(currentGeneration, CameraViewSnapshot(CameraViewState.LIVE))
            }

            val now = SystemClock.elapsedRealtime()
            val elapsed = now - windowStartedAt
            if (elapsed >= FPS_WINDOW_MILLIS) {
                val fps = (framesInWindow * 1000f / elapsed).roundToInt()
                publishSnapshot(currentGeneration, CameraViewSnapshot(CameraViewState.LIVE, fps = fps))
                framesInWindow = 0
                windowStartedAt = now
            }
        }
    }

    private fun decodeFrame(frame: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(frame, 0, frame.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = InteractionSpec.cameraDecodeSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(frame, 0, frame.size, options)
    }

    private fun publishBitmap(currentGeneration: Long, bitmap: Bitmap): Boolean {
        if (!isCurrent(currentGeneration)) {
            bitmap.recycle()
            return false
        }
        mainHandler.post {
            if (!isCurrent(currentGeneration)) {
                bitmap.recycle()
                return@post
            }
            val previous = displayedBitmap
            displayedBitmap = bitmap
            previous?.recycle()
            postInvalidateOnAnimation()
        }
        return true
    }

    private fun clearBitmapOnMain() {
        runOnMain {
            val previous = displayedBitmap
            displayedBitmap = null
            previous?.recycle()
            invalidate()
        }
    }

    private fun scheduleRetry(currentGeneration: Long, retryIndex: Int) {
        if (!isCurrent(currentGeneration)) {
            return
        }
        val delayIndex = min(retryIndex, reconnectDelaysMillis.lastIndex)
        scheduleAttempt(
            currentGeneration,
            retryIndex = retryIndex + 1,
            delayMillis = reconnectDelaysMillis[delayIndex]
        )
    }

    private fun publishSnapshot(currentGeneration: Long, snapshot: CameraViewSnapshot) {
        mainHandler.post {
            val listener = synchronized(stateLock) {
                if (!isCurrentLocked(currentGeneration)) null else snapshotListener
            }
            listener?.invoke(snapshot)
        }
    }

    private fun detachWorkerLocked(shutdownExecutor: Boolean): WorkerResources {
        val resources = WorkerResources(
            connection = connection,
            scheduledAttempt = scheduledAttempt,
            runningAttempt = runningAttempt,
            executor = if (shutdownExecutor) executor else null
        )
        connection = null
        scheduledAttempt = null
        runningAttempt = null
        runningAttemptGeneration = null
        if (shutdownExecutor) {
            executor = null
        }
        return resources
    }

    private fun newExecutor(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "MjpegStreamView").apply { isDaemon = true }
        }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun isCurrent(currentGeneration: Long): Boolean = synchronized(stateLock) {
        isCurrentLocked(currentGeneration)
    }

    private fun isCurrentLocked(currentGeneration: Long): Boolean =
        active && generation == currentGeneration

    private fun isStoppedGeneration(stoppedGeneration: Long): Boolean = synchronized(stateLock) {
        !active && generation == stoppedGeneration
    }

    private fun readResponseText(input: InputStream?): String {
        if (input == null) {
            return ""
        }
        BufferedReader(InputStreamReader(input)).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(1024)
            while (result.length < MAX_ERROR_BODY_CHARS) {
                val count = reader.read(buffer, 0, min(buffer.size, MAX_ERROR_BODY_CHARS - result.length))
                if (count < 0) {
                    break
                }
                result.append(buffer, 0, count)
            }
            return result.toString().trim()
        }
    }

    private data class WorkerResources(
        val connection: HttpURLConnection?,
        val scheduledAttempt: Future<*>?,
        val runningAttempt: Future<*>?,
        val executor: ScheduledExecutorService?
    ) {
        fun cancel() {
            scheduledAttempt?.cancel(true)
            runningAttempt?.cancel(true)
            connection?.disconnect()
            executor?.shutdownNow()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 2_000
        const val READ_TIMEOUT_MILLIS = 5_000
        const val FPS_WINDOW_MILLIS = 1_000L
        const val MAX_ERROR_BODY_CHARS = 4_096
    }
}
