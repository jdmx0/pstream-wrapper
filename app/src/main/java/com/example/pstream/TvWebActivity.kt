package com.example.pstream

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Android TV Web Activity with visual cursor support.
 * Loads a movie website in fullscreen WebView with DPAD-controlled cursor.
 */
class TvWebActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var rootContainer: FrameLayout
    private lateinit var cursorOverlay: CursorOverlay
    private lateinit var cursorController: CursorController

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // OkHttp with disk cache for video streaming
    private val okCache by lazy {
        val cacheSize = resources.getInteger(R.integer.cache_size_mb).toLong() * 1024 * 1024
        Cache(File(cacheDir, "http_cache"), cacheSize)
    }
    private val okClient by lazy {
        OkHttpClient.Builder()
            .cache(okCache)
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force landscape orientation
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        supportActionBar?.hide()

        setContentView(R.layout.activity_main)

        rootContainer = findViewById(R.id.root_container)
        enterImmersiveMode()

        // Track IME visibility to hand DPAD to keyboard when shown
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            cursorController.onImeVisibilityChanged(imeVisible)
            insets
        }

        // Create cursor overlay
        cursorOverlay = CursorOverlay(this)
        rootContainer.addView(cursorOverlay)

        // Setup WebView
        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // Disable long-press context menu to avoid interference
        webView.setOnLongClickListener { true }
        webView.isLongClickable = false

        // Create cursor controller
        cursorController = CursorController(this, webView, cursorOverlay)

        // Force cursor to be visible and functional immediately
        cursorController.forceCursorVisible()

        // Create JS Bridge
        val jsBridge = JSBridge(cursorController)
        webView.addJavascriptInterface(jsBridge, "TVBridge")

        // Setup WebView client
        webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                // Inject TV Cursor scripts
                JSBridge.injectScripts(view)

                // Load TV Cursor helper
                try {
                    val inputStream = assets.open("tv_cursor.js")
                    val script = inputStream.bufferedReader().use { it.readText() }
                    view.evaluateJavascript(script, null)
                } catch (e: Exception) {
                    android.util.Log.w("TvWebActivity", "Failed to load tv_cursor.js", e)
                }

                // Re-focus root container
                rootContainer.requestFocus()
                enterImmersiveMode()
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (url.endsWith(".ts") || url.endsWith(".mp4")) {
                    try {
                        val resp = okClient.newCall(Request.Builder().url(url).build()).execute()
                        if (!resp.isSuccessful) {
                            return null
                        }
                        val contentType = resp.header("Content-Type", "application/octet-stream")
                        return WebResourceResponse(
                            contentType,
                            resp.header("Content-Encoding", "utf-8"),
                            resp.body!!.byteStream()
                        )
                    } catch (e: Exception) {
                        // Network error for video stream - don't interrupt user experience
                        e.printStackTrace()
                        return null
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) return
                customView = view!!.also {
                    rootContainer.addView(
                        it,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                customViewCallback = callback
                enterImmersiveMode()
            }

            override fun onHideCustomView() {
                customView?.let { rootContainer.removeView(it) }
                customView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                enterImmersiveMode()
            }
        }

        // Load the movie site
        webView.loadUrl(BuildConfig.MOVIE_SITE_URL)

        // Focus management
        rootContainer.isFocusable = true
        rootContainer.isFocusableInTouchMode = true
        rootContainer.requestFocus()
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        cursorController.onPause()
    }

    override fun onResume() {
        super.onResume()
        cursorController.onResume()
        enterImmersiveMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        cursorController.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Handle special keys for cursor features
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_Y -> {
                    // Snap to nearest clickable
                    cursorController.snapToNearestClickable()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    // Toggle between focus and cursor modes
                    cursorController.toggleMode()
                    android.util.Log.d("TvWebActivity", "Mode toggled to: ${cursorController.getCurrentMode()}")
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    // Debug: Force cursor visible
                    cursorController.forceCursorVisible()
                    android.util.Log.d("TvWebActivity", "Cursor forced visible via MENU key")
                    return true
                }
            }
        }

        // Route DPAD actions correctly: DOWN -> onKeyDown, UP -> onKeyUp
        val handled = when (event.action) {
            KeyEvent.ACTION_DOWN -> cursorController.onKeyDown(event.keyCode) || super.dispatchKeyEvent(event)
            KeyEvent.ACTION_UP -> cursorController.onKeyUp(event.keyCode) || super.dispatchKeyEvent(event)
            else -> super.dispatchKeyEvent(event)
        }

        // Debug logging for DPAD events
        if (event.keyCode >= KeyEvent.KEYCODE_DPAD_UP && event.keyCode <= KeyEvent.KEYCODE_DPAD_CENTER) {
            android.util.Log.d("TvWebActivity", "DPAD event: ${event.keyCode}, action: ${event.action}, handled: $handled, mode: ${cursorController.getCurrentMode()}")
        }

        return handled
    }

    override fun onBackPressed() {
        when {
            customView != null -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    webView.webChromeClient?.onHideCustomView()
                }
            }
            cursorController.getCurrentMode() == CursorController.Mode.CURSOR_MODE -> {
                // Exit cursor mode back to focus mode
                cursorController.forceFocusMode()
            }
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }
}
