package com.example.pstream

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var rootContainer: FrameLayout
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // Visual cursor
    private lateinit var cursorView: ImageView
    private var cursorX = 0f
    private var cursorY = 0f

    // Sub-pixel precision for smoother movement
    private var subPixelX = 0f
    private var subPixelY = 0f

    // Movement flags
    private var isMovingUp = false
    private var isMovingDown = false
    private var isMovingLeft = false
    private var isMovingRight = false
    private var movementStartTime = 0L
    private var batterySaverThreshold = 3000L // Configurable battery saver threshold

    // State flags
    private var isVideoPlaying = false
    private var isInInputField = false
    private var cursorVisibleDuringVideo = false  // Allow cursor for video controls

    // Movement parameters - optimized for smoothness
    private var moveStep: Float = 15f  // Smoother base movement
    private var scrollStep: Int = 60   // Smoother scrolling
    private var frameDelay: Long = 8L  // 120fps for smooth movement
    private var batterySaverFrameDelay: Long = 16L // 2x slower for better balance

    // Acceleration system - optimized for TV responsiveness
    private var acceleration = 1.0f
    private var maxAcceleration = 2.5f  // Configurable max acceleration
    private var accelerationRampUp = 0.15f  // Configurable ramp-up rate
    private var accelerationRampDown = 0.08f  // Smooth deceleration

    // Cached values for performance
    private var cachedScreenWidth = 0f
    private var cachedScreenHeight = 0f
    private var cachedCursorWidth = 0f
    private var cachedCursorHeight = 0f
    private var lastFrameTime = 0L

    // Performance optimization - removed frame skipping for consistent smoothness on TV

    // Cursor hide handler
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideCursorRunnable = Runnable {
        if (isVideoPlaying && !cursorVisibleDuringVideo) {
            cursorView.visibility = View.GONE
        } else if (!isVideoPlaying) {
            cursorView.visibility = View.GONE
        }
        // During video, keep cursor hidden but allow it to be shown again on user interaction
        if (isVideoPlaying) {
            cursorVisibleDuringVideo = false
        }
    }

    // Periodic video state checker
    private val videoStateChecker = object : Runnable {
        override fun run() {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("""
                    (function() {
                        var videos = document.querySelectorAll('video');
                        var isAnyPlaying = false;
                        var isFullscreen = !!(document.fullscreenElement ||
                                           document.webkitFullscreenElement ||
                                           document.mozFullScreenElement ||
                                           document.msFullscreenElement);

                        for (var i = 0; i < videos.length; i++) {
                            if (!videos[i].paused && !videos[i].ended) {
                                isAnyPlaying = true;
                                break;
                            }
                        }

                        if (isAnyPlaying || isFullscreen) {
                            AndroidVideoListener.onVideoPlay();
                        } else {
                            AndroidVideoListener.onVideoPause();
                        }

                        return isAnyPlaying || isFullscreen;
                    })();
                """.trimIndent(), null)
            }
            // Check every 2 seconds
            hideHandler.postDelayed(this, 2000)
        }
    }
    private var inactivityTimeout = 10000L

    // OkHttp with disk cache
    private val okCache by lazy {
        val cacheSize = resources.getInteger(R.integer.cache_size_mb).toLong() * 1024 * 1024
        Cache(File(cacheDir, "http_cache"), cacheSize)
    }
    private val okClient by lazy {
        OkHttpClient.Builder()
            .cache(okCache)
            .build()
    }

    // Smooth movement runnable with acceleration - optimized for consistent TV performance
    private val smoothMoveRunnable = object : Runnable {
        override fun run() {
            // Allow movement during video if cursor is visible for controls
            if (isVideoPlaying && !cursorVisibleDuringVideo) return

            val currentTime = SystemClock.uptimeMillis()
            val deltaTime = if (lastFrameTime > 0) currentTime - lastFrameTime else frameDelay
            lastFrameTime = currentTime

            // Apply smooth acceleration curve optimized for TV feel
            val timeSinceStart = currentTime - movementStartTime
            val normalizedTime = timeSinceStart / 1000f
            // Use smoother acceleration curve: exponential approach to max acceleration
            acceleration = (1.0f + (1.0f - Math.exp((-normalizedTime * accelerationRampUp).toDouble())).toFloat() * (maxAcceleration - 1.0f))
            val acceleratedMoveStep = moveStep * acceleration * (deltaTime / frameDelay.toFloat())
            val acceleratedScrollStep = (scrollStep * acceleration * (deltaTime / frameDelay.toFloat())).toInt()

            var positionChanged = false

            // Move up with sub-pixel precision
            if (isMovingUp) {
                if (cursorY <= 0f) {
                    webView.scrollBy(0, -acceleratedScrollStep)
                } else {
                    subPixelY -= acceleratedMoveStep
                    val moveAmount = subPixelY.toInt()
                    if (moveAmount != 0) {
                        cursorY = (cursorY + moveAmount).coerceAtLeast(0f)
                        subPixelY -= moveAmount
                        positionChanged = true
                    }
                }
            }

            // Move down with sub-pixel precision
            if (isMovingDown) {
                if (cursorY >= cachedScreenHeight - cachedCursorHeight) {
                    webView.scrollBy(0, acceleratedScrollStep)
                } else {
                    subPixelY += acceleratedMoveStep
                    val moveAmount = subPixelY.toInt()
                    if (moveAmount != 0) {
                        cursorY = (cursorY + moveAmount).coerceAtMost(cachedScreenHeight - cachedCursorHeight)
                        subPixelY -= moveAmount
                        positionChanged = true
                    }
                }
            }

            // Move left with sub-pixel precision
            if (isMovingLeft) {
                if (cursorX <= 0f) {
                    cursorX = 0f
                } else {
                    subPixelX -= acceleratedMoveStep
                    val moveAmount = subPixelX.toInt()
                    if (moveAmount != 0) {
                        cursorX = (cursorX + moveAmount).coerceAtLeast(0f)
                        subPixelX -= moveAmount
                        positionChanged = true
                    }
                }
            }

            // Move right with sub-pixel precision
            if (isMovingRight) {
                if (cursorX >= cachedScreenWidth - cachedCursorWidth) {
                    cursorX = cachedScreenWidth - cachedCursorWidth
                } else {
                    subPixelX += acceleratedMoveStep
                    val moveAmount = subPixelX.toInt()
                    if (moveAmount != 0) {
                        cursorX = (cursorX + moveAmount).coerceAtMost(cachedScreenWidth - cachedCursorWidth)
                        subPixelX -= moveAmount
                        positionChanged = true
                    }
                }
            }

            // Update cursor position only if it actually changed
            if (positionChanged) {
                updateCursorPosition()
            }

            // Continue if any direction is active
            if (isMovingUp || isMovingDown || isMovingLeft || isMovingRight) {
                val delay = if (timeSinceStart > batterySaverThreshold) batterySaverFrameDelay else frameDelay
                cursorView.postDelayed(this, delay)
            } else {
                // Smooth deceleration when stopping movement
                if (acceleration > 1.0f) {
                    acceleration = maxOf(1.0f, acceleration - accelerationRampDown)
                    cursorView.postDelayed(this, frameDelay)
                } else {
                    // Reset when fully decelerated
                    acceleration = 1.0f
                    lastFrameTime = 0L
                    // Reset sub-pixel accumulation
                    subPixelX = 0f
                    subPixelY = 0f
                }
            }
        }
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

        // Load configuration
        moveStep = resources.getInteger(R.integer.move_step).toFloat()
        scrollStep = resources.getInteger(R.integer.scroll_step)
        frameDelay = resources.getInteger(R.integer.frame_delay).toLong()
        batterySaverFrameDelay = frameDelay * 3
        inactivityTimeout = resources.getInteger(R.integer.inactivity_timeout).toLong()
        batterySaverThreshold = resources.getInteger(R.integer.battery_saver_threshold).toLong()

        // Load acceleration configuration
        accelerationRampUp = resources.getInteger(R.integer.acceleration_ramp_up) / 100.0f
        maxAcceleration = resources.getInteger(R.integer.max_acceleration) / 100.0f

        // Create visual cursor
        val cursorSize = resources.getInteger(R.integer.cursor_size)
        cursorView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(cursorSize, cursorSize)
            val circleDrawable = ShapeDrawable(OvalShape()).apply {
                paint.color = Color.WHITE
                paint.alpha = resources.getInteger(R.integer.cursor_alpha)
            }
            background = circleDrawable
            visibility = View.VISIBLE
        }
        rootContainer.addView(cursorView)

        // Cache expensive values for performance
        cachedScreenWidth = resources.displayMetrics.widthPixels.toFloat()
        cachedScreenHeight = resources.displayMetrics.heightPixels.toFloat()
        cachedCursorWidth = cursorSize.toFloat()
        cachedCursorHeight = cursorSize.toFloat()

        // Center cursor initially
        cursorX = (cachedScreenWidth / 2f) - (cursorSize / 2f)
        cursorY = (cachedScreenHeight / 2f) - (cursorSize / 2f)
        updateCursorPosition()

        // Setup WebView
        webView = findViewById(R.id.webview)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }

        // Disable long-press context menu
        webView.setOnLongClickListener { true }
        webView.isLongClickable = false

        // Add JavaScript interface for video/input detection
        webView.addJavascriptInterface(VideoInterface(), "AndroidVideoListener")

        // Setup WebView client
        webView.webViewClient = object : WebViewClient() {

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)

                // Inject JS to detect video playback and input focus
                view.evaluateJavascript("""
                    (function() {
                        // Video event binding
                        function bindVideoEvents(v) {
                            v.onplay = function() { AndroidVideoListener.onVideoPlay(); };
                            v.onpause = function() { AndroidVideoListener.onVideoPause(); };
                            v.onended = function() { AndroidVideoListener.onVideoPause(); };
                            v.onwaiting = function() { AndroidVideoListener.onVideoPause(); };
                            v.onstalled = function() { AndroidVideoListener.onVideoPause(); };
                        }

                        // Check for fullscreen video
                        function checkFullscreen() {
                            if (document.fullscreenElement ||
                                document.webkitFullscreenElement ||
                                document.mozFullScreenElement ||
                                document.msFullscreenElement) {
                                AndroidVideoListener.onVideoPlay();
                            }
                        }

                        // Bind to existing videos
                        document.querySelectorAll('video').forEach(bindVideoEvents);

                        // Watch for fullscreen changes
                        document.addEventListener('fullscreenchange', checkFullscreen);
                        document.addEventListener('webkitfullscreenchange', checkFullscreen);
                        document.addEventListener('mozfullscreenchange', checkFullscreen);
                        document.addEventListener('MSFullscreenChange', checkFullscreen);

                        // Watch for new videos
                        new MutationObserver(function(mutations) {
                            mutations.forEach(function(m) {
                                m.addedNodes.forEach(function(node) {
                                    if (node.tagName === 'VIDEO') {
                                        bindVideoEvents(node);
                                    }
                                });
                            });
                        }).observe(document.body, { childList: true, subtree: true });

                        // Input field focus detection
                        function bindInputEvents(input) {
                            input.addEventListener('focus', function() {
                                AndroidVideoListener.onInputFocusChanged(true);
                            });
                            input.addEventListener('blur', function() {
                                AndroidVideoListener.onInputFocusChanged(false);
                            });
                        }

                        // Bind to existing inputs
                        document.querySelectorAll('input, textarea, [contenteditable]').forEach(bindInputEvents);

                        // Watch for new inputs
                        new MutationObserver(function(mutations) {
                            mutations.forEach(function(m) {
                                m.addedNodes.forEach(function(node) {
                                    if (node.tagName === 'INPUT' || node.tagName === 'TEXTAREA' || node.contentEditable === 'true') {
                                        bindInputEvents(node);
                                    }
                                    // Check child elements too
                                    if (node.querySelectorAll) {
                                        node.querySelectorAll('input, textarea, [contenteditable]').forEach(bindInputEvents);
                                    }
                                });
                            });
                        }).observe(document.body, { childList: true, subtree: true });

                        // Check initial focus state
                        var activeElement = document.activeElement;
                        if (activeElement && (activeElement.tagName === 'INPUT' || activeElement.tagName === 'TEXTAREA' || activeElement.contentEditable === 'true')) {
                            AndroidVideoListener.onInputFocusChanged(true);
                        }

                        // Force carousel arrows to be visible on Android TV
                        function makeCarouselArrowsVisible() {
                            // Common carousel arrow selectors
                            var arrowSelectors = [
                                '.carousel-arrow',
                                '.swiper-button-next',
                                '.swiper-button-prev',
                                '.slick-next',
                                '.slick-prev',
                                '.owl-next',
                                '.owl-prev',
                                '[class*="arrow"]',
                                '[class*="next"]',
                                '[class*="prev"]',
                                '.arrow-right',
                                '.arrow-left',
                                '.next-arrow',
                                '.prev-arrow',
                                'button[class*="next"]',
                                'button[class*="prev"]',
                                '.carousel-control-next',
                                '.carousel-control-prev'
                            ];

                            arrowSelectors.forEach(function(selector) {
                                var arrows = document.querySelectorAll(selector);
                                arrows.forEach(function(arrow) {
                                    // Force visibility
                                    arrow.style.display = 'block !important';
                                    arrow.style.visibility = 'visible !important';
                                    arrow.style.opacity = '1 !important';
                                    arrow.removeAttribute('hidden');

                                    // Make sure it's not hidden by media queries
                                    arrow.setAttribute('data-tv-visible', 'true');
                                });
                            });

                            // Also check for arrows inside carousel containers
                            var carousels = document.querySelectorAll('.carousel, .swiper, .slick-slider, .owl-carousel, [class*="carousel"]');
                            carousels.forEach(function(carousel) {
                                var arrows = carousel.querySelectorAll('button, .arrow, [class*="arrow"]');
                                arrows.forEach(function(arrow) {
                                    arrow.style.display = 'block !important';
                                    arrow.style.visibility = 'visible !important';
                                    arrow.style.opacity = '1 !important';
                                });
                            });
                        }

                        // Run immediately and watch for new carousels
                        makeCarouselArrowsVisible();

                        // Watch for dynamically added carousels
                        new MutationObserver(function(mutations) {
                            mutations.forEach(function(mutation) {
                                if (mutation.type === 'childList') {
                                    mutation.addedNodes.forEach(function(node) {
                                        if (node.nodeType === Node.ELEMENT_NODE) {
                                            // Check if new node contains carousel elements
                                            if (node.querySelector && (node.querySelector('.carousel, .swiper, .slick-slider, .owl-carousel') ||
                                                node.classList.contains('carousel') || node.classList.contains('swiper'))) {
                                                setTimeout(makeCarouselArrowsVisible, 100);
                                            }
                                        }
                                    });
                                }
                            });
                        }).observe(document.body, { childList: true, subtree: true });

                        // Inject CSS to force carousel arrows visible on TV
                        var tvArrowCSS = `
                            @media screen and (max-width: 1920px) {
                                .carousel-arrow,
                                .swiper-button-next,
                                .swiper-button-prev,
                                .slick-next,
                                .slick-prev,
                                .owl-next,
                                .owl-prev,
                                [class*="arrow"],
                                [class*="next"],
                                [class*="prev"],
                                .arrow-right,
                                .arrow-left,
                                .next-arrow,
                                .prev-arrow,
                                button[class*="next"],
                                button[class*="prev"],
                                .carousel-control-next,
                                .carousel-control-prev {
                                    display: block !important;
                                    visibility: visible !important;
                                    opacity: 1 !important;
                                    pointer-events: auto !important;
                                }
                            }

                            /* Override any TV-specific hiding */
                            .carousel-arrow:not([data-tv-visible]),
                            .swiper-button-next:not([data-tv-visible]),
                            .swiper-button-prev:not([data-tv-visible]) {
                                display: block !important;
                                visibility: visible !important;
                            }
                        `;

                        var style = document.createElement('style');
                        style.type = 'text/css';
                        style.innerHTML = tvArrowCSS;
                        document.head.appendChild(style);

                        // Add keyboard navigation for carousel arrows
                        function addArrowKeyboardSupport() {
                            document.addEventListener('keydown', function(e) {
                                // Only handle if not in input field
                                if (document.activeElement && (document.activeElement.tagName === 'INPUT' ||
                                    document.activeElement.tagName === 'TEXTAREA' ||
                                    document.activeElement.contentEditable === 'true')) {
                                    return;
                                }

                                var arrows = document.querySelectorAll('.carousel-arrow, .swiper-button-next, .swiper-button-prev, .slick-next, .slick-prev, .owl-next, .owl-prev, button[class*="next"], button[class*="prev"]');

                                if (e.key === 'ArrowLeft' || e.keyCode === 37) {
                                    // Find previous arrow and click it
                                    var prevArrows = document.querySelectorAll('.swiper-button-prev, .slick-prev, .owl-prev, button[class*="prev"], .prev-arrow, .arrow-left');
                                    if (prevArrows.length > 0) {
                                        e.preventDefault();
                                        prevArrows[0].click();
                                    }
                                } else if (e.key === 'ArrowRight' || e.keyCode === 39) {
                                    // Find next arrow and click it
                                    var nextArrows = document.querySelectorAll('.swiper-button-next, .slick-next, .owl-next, button[class*="next"], .next-arrow, .arrow-right');
                                    if (nextArrows.length > 0) {
                                        e.preventDefault();
                                        nextArrows[0].click();
                                    }
                                }
                            });
                        }

                        addArrowKeyboardSupport();

                    })();
                """.trimIndent(), null)

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

        webView.loadUrl(getString(R.string.default_website_url))

        // Start periodic video state checking
        hideHandler.postDelayed(videoStateChecker, 1000)

        // Focus management
        rootContainer.isFocusable = true
        rootContainer.isFocusableInTouchMode = true
        rootContainer.requestFocus()
    }

    private fun updateCursorPosition() {
        cursorX = cursorX.coerceIn(0f, cachedScreenWidth - cachedCursorWidth)
        cursorY = cursorY.coerceIn(0f, cachedScreenHeight - cachedCursorHeight)
        cursorView.translationX = cursorX
        cursorView.translationY = cursorY
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
        cursorView.removeCallbacks(smoothMoveRunnable)
        hideHandler.removeCallbacks(hideCursorRunnable)
        hideHandler.removeCallbacks(videoStateChecker)
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveMode()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // Reset acceleration and frame time on new key press for consistent performance
        if (action == KeyEvent.ACTION_DOWN) {
            acceleration = 1.0f
            lastFrameTime = 0L
        }

        // Handle video playback - cursor stays visible for controls
        if (isVideoPlaying) {
            // Allow cursor movement for video controls
            cursorVisibleDuringVideo = true
            cursorView.visibility = View.VISIBLE

            // Reset hide timer when user interacts during video
            hideHandler.removeCallbacks(hideCursorRunnable)
            hideHandler.postDelayed(hideCursorRunnable, inactivityTimeout)
        }

        // If in input field, route DPAD to WebView for text navigation
        if (isInInputField) {
            return webView.dispatchKeyEvent(event)
        }

        // Show cursor on any DPAD activity if hidden
        if (action == KeyEvent.ACTION_DOWN && cursorView.visibility != View.VISIBLE) {
            cursorView.visibility = View.VISIBLE
            updateCursorPosition()
        }

        // Reset inactivity timer
        if (action == KeyEvent.ACTION_DOWN) {
            hideHandler.removeCallbacks(hideCursorRunnable)
            hideHandler.postDelayed(hideCursorRunnable, inactivityTimeout)
        }

        // Handle movement
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (action == KeyEvent.ACTION_DOWN && !isMovingUp) {
                    isMovingUp = true
                    movementStartTime = SystemClock.uptimeMillis()
                    smoothMoveRunnable.run()
                } else if (action == KeyEvent.ACTION_UP) {
                    isMovingUp = false
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (action == KeyEvent.ACTION_DOWN && !isMovingDown) {
                    isMovingDown = true
                    movementStartTime = SystemClock.uptimeMillis()
                    smoothMoveRunnable.run()
                } else if (action == KeyEvent.ACTION_UP) {
                    isMovingDown = false
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (action == KeyEvent.ACTION_DOWN && !isMovingLeft) {
                    isMovingLeft = true
                    movementStartTime = SystemClock.uptimeMillis()
                    smoothMoveRunnable.run()
                } else if (action == KeyEvent.ACTION_UP) {
                    isMovingLeft = false
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (action == KeyEvent.ACTION_DOWN && !isMovingRight) {
                    isMovingRight = true
                    movementStartTime = SystemClock.uptimeMillis()
                    smoothMoveRunnable.run()
                } else if (action == KeyEvent.ACTION_UP) {
                    isMovingRight = false
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (action == KeyEvent.ACTION_DOWN) {
                    // Simulate click at cursor position
                    val x = cursorX + (cursorView.width / 2f)
                    val y = cursorY + (cursorView.height / 2f)

                    val downTime = SystemClock.uptimeMillis()
                    val motionDown = MotionEvent.obtain(
                        downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0
                    )
                    webView.dispatchTouchEvent(motionDown)

                    val eventTime = SystemClock.uptimeMillis()
                    val motionUp = MotionEvent.obtain(
                        downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0
                    )
                    webView.dispatchTouchEvent(motionUp)

                    motionDown.recycle()
                    motionUp.recycle()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when {
            customView != null -> webView.webChromeClient?.onHideCustomView()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    /** JavaScript interface for video and input detection */
    private inner class VideoInterface {
        @JavascriptInterface
        fun onVideoPlay() {
            runOnUiThread {
                val wasNotPlaying = !isVideoPlaying
                isVideoPlaying = true
                cursorVisibleDuringVideo = false

                // Hide cursor when video starts playing or goes fullscreen
                cursorView.visibility = View.GONE
                hideHandler.removeCallbacks(hideCursorRunnable)

                // Only hide cursor if this is the first time video started
                if (wasNotPlaying) {
                    hideHandler.postDelayed(hideCursorRunnable, inactivityTimeout)
                }
            }
        }

        @JavascriptInterface
        fun onVideoPause() {
            runOnUiThread {
                isVideoPlaying = false
                cursorVisibleDuringVideo = false
                cursorView.visibility = View.VISIBLE
                updateCursorPosition()
                hideHandler.removeCallbacks(hideCursorRunnable)
                hideHandler.postDelayed(hideCursorRunnable, inactivityTimeout)
            }
        }

        @JavascriptInterface
        fun onInputFocusChanged(hasFocus: Boolean) {
            runOnUiThread {
                isInInputField = hasFocus
                if (hasFocus) {
                    cursorView.visibility = View.GONE
                    hideHandler.removeCallbacks(hideCursorRunnable)
                } else {
                    cursorView.visibility = View.VISIBLE
                    updateCursorPosition()
                    hideHandler.removeCallbacks(hideCursorRunnable)
                    hideHandler.postDelayed(hideCursorRunnable, inactivityTimeout)
                }
            }
        }
    }

}
