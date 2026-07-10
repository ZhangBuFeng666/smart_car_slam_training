package com.example.icarcontroller

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
class Vehicle3DStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val fallbackImage = ImageView(context).apply {
        setImageResource(R.drawable.icar_x3_front)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        alpha = 0.58f
        setPadding(0, dp(34), 0, dp(18))
    }

    private val webView = WebView(context)
    private var connected = false
    private var stageThemeMode = ParkingThemeMode.DARK
    private var hostActive = true
    private var pageLoaded = false
    private var destroyed = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        clipChildren = false
        contentDescription = "润和 iCar Pro 三维模型，可左右拖动查看"

        addView(fallbackImage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        configureWebView()
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        webView.loadUrl(VehicleStageAssetPolicy.startUrl())
    }

    fun setConnected(value: Boolean) {
        connected = value
        evaluate("window.ICar3DStage.setConnected(${value.toString()});")
    }

    fun setThemeMode(mode: ParkingThemeMode) {
        stageThemeMode = mode
        evaluate("window.ICar3DStage.setTheme('${ParkingThemeSpec.storedValue(mode)}');")
    }

    fun onHostResume() {
        if (destroyed) return
        hostActive = true
        webView.onResume()
        evaluate("window.ICar3DStage.setActive(true);")
    }

    fun onHostPause() {
        if (destroyed) return
        hostActive = false
        evaluate("window.ICar3DStage.setActive(false);")
        webView.onPause()
    }

    fun destroy() {
        if (destroyed) return
        evaluate("window.ICar3DStage.setActive(false);")
        destroyed = true
        webView.stopLoading()
        webView.removeJavascriptInterface(BRIDGE_NAME)
        removeView(webView)
        webView.destroy()
    }

    @Suppress("DEPRECATION")
    private fun configureWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.isNestedScrollingEnabled = false
        webView.setOnLongClickListener { true }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            mediaPlaybackRequiresUserGesture = true
        }
        webView.addJavascriptInterface(StageBridge(), BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                VehicleStageAssetPolicy.assetPathFor(request.url.toString()) == null

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val assetPath = VehicleStageAssetPolicy.assetPathFor(request.url.toString())
                if (assetPath != null) return packagedAssetResponse(assetPath)
                return if (request.url.scheme == "http" || request.url.scheme == "https") {
                    blockedResponse()
                } else {
                    super.shouldInterceptRequest(view, request)
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                if (VehicleStageAssetPolicy.assetPathFor(url) == null) return
                pageLoaded = true
                evaluate(
                    "window.ICar3DStage.configure({" +
                        "autoRotationDurationMillis:${VehicleMotionSpec.autoRotationDurationMillis()}," +
                        "resumeDelayMillis:${VehicleMotionSpec.resumeDelayMillis()}" +
                        "});"
                )
                evaluate("window.ICar3DStage.setConnected(${connected.toString()});")
                evaluate("window.ICar3DStage.setTheme('${ParkingThemeSpec.storedValue(stageThemeMode)}');")
                evaluate("window.ICar3DStage.setActive(${hostActive.toString()});")
            }
        }
    }

    private fun packagedAssetResponse(assetPath: String): WebResourceResponse = try {
        val mimeType = VehicleStageAssetPolicy.mimeType(assetPath)
        val encoding = if (
            mimeType.startsWith("text/") || mimeType == "application/javascript"
        ) "UTF-8" else null
        WebResourceResponse(
            mimeType,
            encoding,
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            resources.assets.open(assetPath)
        )
    } catch (_: Exception) {
        notFoundResponse()
    }

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun notFoundResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )

    private fun evaluate(script: String) {
        if (!pageLoaded || destroyed) return
        webView.evaluateJavascript("if(window.ICar3DStage){$script}", null)
    }

    private fun showModel() {
        fallbackImage.animate().cancel()
        fallbackImage.animate()
            .alpha(0f)
            .setDuration(280)
            .withEndAction { fallbackImage.visibility = View.GONE }
            .start()
    }

    private fun showFallback() {
        fallbackImage.animate().cancel()
        fallbackImage.visibility = View.VISIBLE
        fallbackImage.alpha = 0.72f
        webView.visibility = View.INVISIBLE
    }

    private inner class StageBridge {
        @JavascriptInterface
        fun onReady() {
            post { if (!destroyed) showModel() }
        }

        @JavascriptInterface
        fun onFailed() {
            post { if (!destroyed) showFallback() }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val BRIDGE_NAME = "AndroidStage"
    }
}
