package com.example.icarcontroller

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.io.BufferedReader
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
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
    private val bitmapLock = Any()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "MjpegStreamView").apply { isDaemon = true }
    }
    private val reconnectDelaysMillis = InteractionSpec.cameraReconnectDelaysMillis()

    private var displayedBitmap: Bitmap? = null
    private var active = false
    private var generation = 0L
    private var streamUrl: String? = null
    private var snapshotListener: ((CameraViewSnapshot) -> Unit)? = null
    private var connection: HttpURLConnection? = null
    private var scheduledAttempt: ScheduledFuture<*>? = null

    fun start(url: String, listener: (CameraViewSnapshot) -> Unit) {
        val previousConnection: HttpURLConnection?
        val currentGeneration: Long
        synchronized(stateLock) {
            generation += 1
            currentGeneration = generation
            active = true
            streamUrl = url
            snapshotListener = listener
            scheduledAttempt?.cancel(true)
            scheduledAttempt = null
            previousConnection = connection
            connection = null
        }
        previousConnection?.disconnect()
        clearBitmap()
        publishSnapshot(currentGeneration, CameraViewSnapshot(CameraViewState.CONNECTING))
        scheduleAttempt(currentGeneration, retryIndex = 0, delayMillis = 0)
    }

    fun stop() {
        val previousConnection: HttpURLConnection?
        val previousListener: ((CameraViewSnapshot) -> Unit)?
        val stoppedGeneration: Long
        synchronized(stateLock) {
            generation += 1
            stoppedGeneration = generation
            active = false
            streamUrl = null
            previousListener = snapshotListener
            snapshotListener = null
            scheduledAttempt?.cancel(true)
            scheduledAttempt = null
            previousConnection = connection
            connection = null
        }
        previousConnection?.disconnect()
        clearBitmap()
        post {
            if (isStoppedGeneration(stoppedGeneration)) {
                previousListener?.invoke(CameraViewSnapshot(CameraViewState.IDLE))
            }
        }
    }

    fun reconnect() {
        val previousConnection: HttpURLConnection?
        val currentGeneration: Long
        synchronized(stateLock) {
            if (!active || streamUrl == null || snapshotListener == null) {
                return
            }
            generation += 1
            currentGeneration = generation
            scheduledAttempt?.cancel(true)
            scheduledAttempt = null
            previousConnection = connection
            connection = null
        }
        previousConnection?.disconnect()
        clearBitmap()
        publishSnapshot(currentGeneration, CameraViewSnapshot(CameraViewState.CONNECTING))
        scheduleAttempt(currentGeneration, retryIndex = 0, delayMillis = 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(bitmapLock) {
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
    }

    private fun scheduleAttempt(currentGeneration: Long, retryIndex: Int, delayMillis: Int) {
        synchronized(stateLock) {
            if (!isCurrentLocked(currentGeneration)) {
                return
            }
            scheduledAttempt = executor.schedule(
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
            scheduledAttempt = null
            streamUrl
        } ?: return

        publishSnapshot(currentGeneration, CameraViewSnapshot(CameraViewState.CONNECTING))
        var openedConnection: HttpURLConnection? = null
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
                val responseText = readResponseText(openedConnection.errorStream ?: openedConnection.inputStream)
                val state = unavailableState(responseText)
                publishSnapshot(currentGeneration, CameraViewSnapshot(state, error = responseText.ifBlank { null }))
                scheduleRetry(currentGeneration, retryIndex)
                return
            }
            if (responseCode !in 200..299) {
                throw EOFException("Camera stream returned HTTP $responseCode")
            }

            readFrames(currentGeneration, openedConnection.inputStream)
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
                scheduleRetry(currentGeneration, retryIndex)
            }
        } finally {
            synchronized(stateLock) {
                if (connection === openedConnection) {
                    connection = null
                }
            }
            openedConnection?.disconnect()
        }
    }

    private fun readFrames(currentGeneration: Long, input: InputStream) {
        val reader = MjpegFrameReader(input, InteractionSpec.cameraMaxFrameBytes())
        var windowStartedAt = SystemClock.elapsedRealtime()
        var framesInWindow = 0
        var liveReported = false

        while (isCurrent(currentGeneration)) {
            val frame = reader.nextFrame() ?: return
            val bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.size) ?: continue
            if (!replaceBitmap(currentGeneration, bitmap)) {
                return
            }

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

    private fun replaceBitmap(currentGeneration: Long, bitmap: Bitmap): Boolean {
        if (!isCurrent(currentGeneration)) {
            bitmap.recycle()
            return false
        }
        synchronized(bitmapLock) {
            if (!isCurrent(currentGeneration)) {
                bitmap.recycle()
                return false
            }
            val previous = displayedBitmap
            displayedBitmap = bitmap
            previous?.recycle()
        }
        post {
            if (isCurrent(currentGeneration)) {
                postInvalidateOnAnimation()
            }
        }
        return true
    }

    private fun clearBitmap() {
        synchronized(bitmapLock) {
            displayedBitmap?.recycle()
            displayedBitmap = null
        }
        postInvalidate()
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
        post {
            val listener = synchronized(stateLock) {
                if (!isCurrentLocked(currentGeneration)) null else snapshotListener
            }
            listener?.invoke(snapshot)
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

    private fun unavailableState(responseText: String): CameraViewState {
        val normalized = responseText.lowercase(Locale.US)
        return when {
            "busy" in normalized -> CameraViewState.BUSY
            "missing" in normalized -> CameraViewState.MISSING
            else -> CameraViewState.DISCONNECTED
        }
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

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 2_000
        const val READ_TIMEOUT_MILLIS = 5_000
        const val FPS_WINDOW_MILLIS = 1_000L
        const val MAX_ERROR_BODY_CHARS = 4_096
    }
}
