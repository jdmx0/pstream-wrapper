package com.example.pstream

import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * JavaScript bridge for Android-JS communication.
 * Handles DOM focus detection and snap-to functionality.
 */
class JSBridge(private val callback: Callback) {

    interface Callback {
        fun onVideoStateChanged(fullscreen: Boolean, playing: Boolean)
        fun onDomFocusChanged(hasFocus: Boolean)
        fun onSnapToResult(x: Float, y: Float)
    }

    /**
     * Called by injected JavaScript when DOM focus changes on editable elements.
     */
    @JavascriptInterface
    fun onDomFocusChanged(hasFocus: Boolean) {
        callback.onDomFocusChanged(hasFocus)
    }

    /**
     * Called by injected JavaScript when fullscreen or play/pause state changes.
     */
    @JavascriptInterface
    fun onVideoStateChanged(fullscreen: Boolean, playing: Boolean) {
        callback.onVideoStateChanged(fullscreen, playing)
    }

    /**
     * Called by injected JavaScript with snap-to target coordinates.
     */
    @JavascriptInterface
    fun onSnapToResult(x: Float, y: Float) {
        callback.onSnapToResult(x, y)
    }

    companion object {
        /**
         * Injects the TV cursor JavaScript into the WebView.
         */
        fun injectScripts(webView: WebView) {
            webView.evaluateJavascript("""
                (function() {
                    // Inject TV Cursor helper
                    if (!window.TVCursor) {
                        ${getTVCursorScript()}
                    }

                    // Focus change detection
                    function setupFocusDetection() {
                        let currentEditableFocus = false;

                        function checkFocus(element) {
                            return element && (
                                element.tagName === 'INPUT' ||
                                element.tagName === 'TEXTAREA' ||
                                element.contentEditable === 'true' ||
                                element.isContentEditable
                            );
                        }

                        function updateFocusState() {
                            const activeElement = document.activeElement;
                            const hasEditableFocus = checkFocus(activeElement);
                            if (hasEditableFocus !== currentEditableFocus) {
                                currentEditableFocus = hasEditableFocus;
                                if (window.TVBridge && window.TVBridge.onDomFocusChanged) {
                                    window.TVBridge.onDomFocusChanged(hasEditableFocus);
                                }
                            }
                        }

                        // Listen for focus events
                        document.addEventListener('focusin', updateFocusState, true);
                        document.addEventListener('focusout', updateFocusState, true);

                        // Initial check (defer slightly to avoid race with page scripts)
                        setTimeout(updateFocusState, 50);

                        // Mutation observer for dynamic elements
                        const observer = new MutationObserver(function(mutations) {
                            mutations.forEach(function(mutation) {
                                if (mutation.type === 'childList') {
                                    mutation.addedNodes.forEach(function(node) {
                                        if (node.nodeType === Node.ELEMENT_NODE) {
                                            if (checkFocus(node)) {
                                                node.addEventListener('focus', updateFocusState);
                                                node.addEventListener('blur', updateFocusState);
                                            }
                                            // Check child elements
                                            const inputs = node.querySelectorAll('input, textarea, [contenteditable]');
                                            inputs.forEach(function(input) {
                                                input.addEventListener('focus', updateFocusState);
                                                input.addEventListener('blur', updateFocusState);
                                            });
                                        }
                                    });
                                }
                            });
                        });

                        observer.observe(document.body, {
                            childList: true,
                            subtree: true
                        });
                    }

                    setupFocusDetection();

                    // ---- Video / fullscreen state tracking ----
                    if (!window.__TVVideo) {
                        window.__TVVideo = (function(){
                            function findVideo(){
                                const vids = Array.from(document.querySelectorAll('video'));
                                if (!vids.length) return null;
                                let best=null, bestArea=0;
                                for (const v of vids){
                                    const r=v.getBoundingClientRect();
                                    const area=Math.max(0,r.width)*Math.max(0,r.height);
                                    const cs=getComputedStyle(v);
                                    const vis=r.width>1&&r.height>1&&cs.display!=='none'&&cs.visibility!=='hidden'&&cs.opacity!=='0';
                                    if (vis && area>=bestArea){ best=v; bestArea=area; }
                                }
                                return best || vids[0] || null;
                            }
                            function isFullscreen(){
                                const v=findVideo();
                                const fs=!!document.fullscreenElement;
                                if (!v) return fs;
                                const r=v.getBoundingClientRect();
                                return fs || (r.width > innerWidth*0.9 && r.height > innerHeight*0.9);
                            }
                            function isPlaying(){
                                const v=findVideo();
                                return !!(v && !v.paused && !v.ended && v.readyState>2);
                            }
                            function sync(){
                                try { if (window.TVBridge && window.TVBridge.onVideoStateChanged) {
                                    window.TVBridge.onVideoStateChanged(isFullscreen(), isPlaying());
                                }} catch(e){}
                            }
                            document.addEventListener('fullscreenchange', sync, true);
                            window.addEventListener('play', sync, true);
                            window.addEventListener('pause', sync, true);
                            window.addEventListener('loadedmetadata', sync, true);
                            window.addEventListener('resize', sync, true);
                            setTimeout(sync, 100);
                            return { sync: sync };
                        })();
                    }
                })();
            """.trimIndent(), null)
        }

        /**
         * Returns the TV Cursor JavaScript implementation.
         */
        private fun getTVCursorScript(): String {
            return """
                window.TVCursor = {
                    nearestClickable: function(cursorX, cursorY) {
                        const elements = document.querySelectorAll('a, button, input, textarea, select, [role="button"], [tabindex]:not([tabindex="-1"])');
                        let nearest = null;
                        let minDistance = Infinity;

                        elements.forEach(function(el) {
                            // Check if element is visible
                            const rect = el.getBoundingClientRect();
                            if (rect.width <= 0 || rect.height <= 0) return;

                            const style = window.getComputedStyle(el);
                            if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return;

                            // Check if element has valid position
                            if (!el.offsetParent && el !== document.body) return;

                            // Calculate center point
                            const centerX = rect.left + rect.width / 2;
                            const centerY = rect.top + rect.height / 2;

                            // Calculate distance from cursor
                            const distance = Math.sqrt(
                                Math.pow(centerX - cursorX, 2) +
                                Math.pow(centerY - cursorY, 2)
                            );

                            if (distance < minDistance) {
                                minDistance = distance;
                                nearest = {
                                    x: centerX,
                                    y: centerY,
                                    element: el
                                };
                            }
                        });

                        return nearest;
                    }
                };
            """
        }

        /**
         * Calls the snap-to function and returns result via callback.
         */
        fun findNearestClickable(webView: WebView, cursorX: Float, cursorY: Float, callback: (Float, Float) -> Unit) {
            webView.evaluateJavascript("""
                (function() {
                    const result = window.TVCursor.nearestClickable($cursorX, $cursorY);
                    if (result && window.TVBridge && window.TVBridge.onSnapToResult) {
                        window.TVBridge.onSnapToResult(result.x, result.y);
                    }
                    return result ? {x: result.x, y: result.y} : null;
                })();
            """.trimIndent()) { result ->
                // Parse result if needed, but we'll rely on the callback for now
            }
        }

        /**
         * Attempts to focus an editable element at the given view-pixel coordinates.
         * Coordinates are converted in JS using window.devicePixelRatio.
         */
        fun focusEditableAtPixels(webView: WebView, viewX: Float, viewY: Float) {
            webView.evaluateJavascript(
                """
                (function() {
                    try {
                        var cssX = (${viewX}) / (window.devicePixelRatio || 1);
                        var cssY = (${viewY}) / (window.devicePixelRatio || 1);
                        var el = document.elementFromPoint(cssX, cssY);
                        function isEditable(n){
                            if (!n) return false;
                            if (n.tagName === 'INPUT' || n.tagName === 'TEXTAREA') return true;
                            if (n.isContentEditable) return true;
                            var ce = n.getAttribute && n.getAttribute('contenteditable');
                            return ce === '' || ce === 'true';
                        }
                        var cur = el;
                        while (cur) {
                            if (isEditable(cur)) {
                                try { cur.focus(); } catch(e){}
                                // avoid synthetic long-press; just a simple click to place caret
                                try { cur.click && cur.click(); } catch(e){}
                                if (window.TVBridge && window.TVBridge.onDomFocusChanged) {
                                    window.TVBridge.onDomFocusChanged(true);
                                }
                                return true;
                            }
                            cur = cur.parentElement;
                        }
                        return false;
                    } catch (e) { return false; }
                })();
                """.trimIndent(),
                null
            )
        }

        /**
         * Optional: synthetic click at coordinates (CSS px). Kept simple; no long-press.
         */
        fun clickAtPixels(webView: WebView, viewX: Float, viewY: Float) {
            webView.evaluateJavascript(
                """
                (function(){
                    try {
                        var cssX = (${viewX}) / (window.devicePixelRatio || 1);
                        var cssY = (${viewY}) / (window.devicePixelRatio || 1);
                        var el = document.elementFromPoint(cssX, cssY);
                        if (el && el.click) el.click();
                        return true;
                    } catch(e) { return false; }
                })();
                """.trimIndent(), null)
        }

        /**
         * Focus element by CSS selector if the provided point is within its bounds.
         */
        fun focusSelectorIfPointInside(webView: WebView, selector: String, viewX: Float, viewY: Float) {
            webView.evaluateJavascript(
                """
                (function(){
                    try {
                        var sel = `${selector}`;
                        var el = document.querySelector(sel);
                        if (!el) return false;
                        var cssX = (${viewX}) / (window.devicePixelRatio || 1);
                        var cssY = (${viewY}) / (window.devicePixelRatio || 1);
                        var r = el.getBoundingClientRect();
                        if (cssX >= r.left && cssX <= r.right && cssY >= r.top && cssY <= r.bottom) {
                            try { el.focus(); } catch(e) {}
                            try { el.click && el.click(); } catch(e) {}
                            if (window.TVBridge && window.TVBridge.onDomFocusChanged) {
                                window.TVBridge.onDomFocusChanged(true);
                            }
                            return true;
                        }
                        return false;
                    } catch(e) { return false; }
                })();
                """.trimIndent(), null)
        }
    }
}
