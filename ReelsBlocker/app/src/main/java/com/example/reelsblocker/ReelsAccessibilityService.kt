package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ReelsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        private val VIEWER_RESOURCE_ID_CANDIDATES = listOf(
            "clips_viewer_view_pager",
            "reel_viewer_root"
        )

        private val TAB_ICON_RESOURCE_ID_CANDIDATES = listOf(
            "clips_tab",
            "reels_tab",
            "creation_tab_clips"
        )

        private val HOME_TAB_RESOURCE_ID_CANDIDATES = listOf(
            "feed_tab",
            "home_tab"
        )

        private const val COOLDOWN_MS = 800L
        private const val COLOR_RESAMPLE_MS = 4000L
        // How many consecutive "not found" checks before actually hiding the
        // overlay -- avoids flicker from a single missed frame.
        private const val MISS_TOLERANCE = 3
        // Only reposition if the icon moved more than this (px) since last draw.
        private const val REPOSITION_THRESHOLD_PX = 6
    }

    private var inReelsViewer = false
    private var lastActionTime = 0L

    private var windowManager: WindowManager? = null

    // Icon-cover overlay
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded = false
    private var lastAppliedBounds: Rect? = null
    private var missCount = 0
    private var sampledColor: Int? = null
    private var lastColorSampleTime = 0L
    private var statusBarHeightPx = 0

    // Full-screen flash overlay used only for the exit animation
    private var transitionView: View? = null
    private var transitionParams: WindowManager.LayoutParams? = null

    private lateinit var prefs: SharedPreferences

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
        statusBarHeightPx = getStatusBarHeight()
        setupOverlay()
        setupTransitionOverlay()
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun setupOverlay() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val view = View(this).apply {
                setBackgroundColor(fallbackColor())
                visibility = View.GONE
            }

            val params = WindowManager.LayoutParams(
                0, 0, 0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            wm.addView(view, params)
            overlayView = view
            overlayParams = params
            overlayAdded = true
        } catch (e: Exception) {
            Log.w(TAG, "Overlay setup failed, continuing without icon-cover feature: ${e.message}")
            overlayAdded = false
        }
    }

    private fun setupTransitionOverlay() {
        try {
            val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
            windowManager = wm
            val metrics = resources.displayMetrics

            val view = View(this).apply {
                setBackgroundColor(Color.parseColor("#26A69A"))
                alpha = 0f
                visibility = View.GONE
            }

            val params = WindowManager.LayoutParams(
                metrics.widthPixels,
                metrics.heightPixels,
                0,
                0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }

            wm.addView(view, params)
            transitionView = view
            transitionParams = params
        } catch (e: Exception) {
            Log.w(TAG, "Transition overlay setup failed: ${e.message}")
        }
    }

    private fun playExitAnimation() {
        val view = transitionView ?: return
        try {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(0.5f).setDuration(120).withEndAction {
                view.animate().alpha(0f).setDuration(250).withEndAction {
                    view.visibility = View.GONE
                }.start()
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "Exit animation failed: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val isInstagram = event.packageName?.toString() == INSTAGRAM_PACKAGE

        if (!isInstagram) {
            hideOverlay(force = true)
            inReelsViewer = false
            return
        }

        if (!::prefs.isInitialized || !prefs.getBoolean(PrefsKeys.KEY_ENABLED, true)) {
            hideOverlay(force = true)
            return
        }

        val root = rootInActiveWindow ?: return
        try {
            updateOverlay(root)
            handleReelSession(root, event)
        } catch (e: Exception) {
            Log.w(TAG, "Error handling accessibility event: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    // ---- Bottom-nav Reels icon covering ----

    private fun updateOverlay(root: AccessibilityNodeInfo) {
        if (!overlayAdded) return
        val tabNode = findTabIconNode(root)
        if (tabNode != null) {
            missCount = 0
            val bounds = Rect()
            tabNode.getBoundsInScreen(bounds)
            tabNode.recycle()
            showOverlayAt(bounds)
        } else {
            missCount++
            if (missCount >= MISS_TOLERANCE) {
                hideOverlay(force = false)
            }
            // else: keep showing at the last known position -- a single
            // missed frame shouldn't make it blink off.
        }
    }

    private fun findTabIconNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in TAB_ICON_RESOURCE_ID_CANDIDATES) {
            val matches = root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$id")
            for (m in matches) {
                val bounds = Rect()
                m.getBoundsInScreen(bounds)
                if (isPlausibleTabIconBounds(bounds)) {
                    for (other in matches) if (other !== m) other.recycle()
                    return m
                }
            }
            matches.forEach { it.recycle() }
        }
        return null
    }

    private fun isPlausibleTabIconBounds(bounds: Rect): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val metrics = resources.displayMetrics
        val maxIconPx = (120 * metrics.density).toInt()
        if (bounds.width() > maxIconPx || bounds.height() > maxIconPx) return false
        val screenHeight = metrics.heightPixels
        if (bounds.top < screenHeight * 0.70) return false
        return true
    }

    private fun showOverlayAt(bounds: Rect) {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = overlayParams ?: return
        if (bounds.width() <= 0 || bounds.height() <= 0) return

        val last = lastAppliedBounds
        val movedEnough = last == null ||
            Math.abs(last.left - bounds.left) > REPOSITION_THRESHOLD_PX ||
            Math.abs(last.top - bounds.top) > REPOSITION_THRESHOLD_PX ||
            last.width() != bounds.width() || last.height() != bounds.height()

        if (movedEnough || view.visibility != View.VISIBLE) {
            // Correct for the status bar: getBoundsInScreen() already
            // measures from the true top of the screen, but this overlay
            // window type ends up drawn *below* the status bar inset, so
            // without this correction everything lands too low by
            // roughly the status bar's height.
            params.x = bounds.left
            params.y = bounds.top - statusBarHeightPx
            params.width = bounds.width()
            params.height = bounds.height()
            try {
                wm.updateViewLayout(view, params)
                lastAppliedBounds = Rect(bounds)
            } catch (e: Exception) {
                Log.w(TAG, "Overlay update failed: ${e.message}")
                return
            }
        }

        view.setBackgroundColor(sampledColor ?: fallbackColor())
        view.visibility = View.VISIBLE
        maybeResampleColor(bounds)
    }

    private fun hideOverlay(force: Boolean) {
        if (!overlayAdded) return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = overlayParams ?: return
        if (!force && missCount < MISS_TOLERANCE) return
        if (view.visibility != View.GONE) {
            view.visibility = View.GONE
            params.width = 0
            params.height = 0
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
        lastAppliedBounds = null
        missCount = 0
    }

    private fun fallbackColor(): Int = Color.parseColor("#1A1A1A")

    // Samples the actual pixel color just beside the icon (same row,
    // just past its edge -- more reliably plain nav-bar background than
    // a point above/below it, which can catch dividers or content).
    private fun maybeResampleColor(bounds: Rect) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = System.currentTimeMillis()
        val isFirstSample = sampledColor == null
        if (!isFirstSample && now - lastColorSampleTime < COLOR_RESAMPLE_MS) return
        lastColorSampleTime = now

        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer
                        val raw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        val safeBitmap = raw?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                        if (safeBitmap != null) {
                            val y = bounds.centerY().coerceIn(0, safeBitmap.height - 1)
                            var x = bounds.right + (12 * resources.displayMetrics.density).toInt()
                            if (x >= safeBitmap.width) {
                                x = (bounds.left - (12 * resources.displayMetrics.density).toInt())
                                    .coerceIn(0, safeBitmap.width - 1)
                            }
                            val color = safeBitmap.getPixel(x, y)
                            sampledColor = color
                            overlayView?.setBackgroundColor(color)
                            safeBitmap.recycle()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Color sample decode failed: ${e.message}")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Screenshot for color sampling failed: code=$errorCode")
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot call failed: ${e.message}")
        }
    }

    // ---- One reel per session ----

    private fun handleReelSession(root: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val currentlyInViewer = isReelsViewerScreen(root)

        when {
            currentlyInViewer && !inReelsViewer -> {
                inReelsViewer = true
                Log.d(TAG, "Entered reels viewer -- 1 reel allowed, next swipe exits")
            }

            !currentlyInViewer && inReelsViewer -> {
                inReelsViewer = false
                Log.d(TAG, "Left reels viewer -- session reset")
            }

            currentlyInViewer && inReelsViewer && event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val now = System.currentTimeMillis()
                if (now - lastActionTime > COOLDOWN_MS) {
                    Log.d(TAG, "Swiped past the first reel -- exiting to Home feed")
                    lastActionTime = now
                    playExitAnimation()
                    exitToFeed(root)
                    inReelsViewer = false
                }
            }
        }
    }

    private fun isReelsViewerScreen(root: AccessibilityNodeInfo): Boolean {
        for (id in VIEWER_RESOURCE_ID_CANDIDATES) {
            val matches = root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$id")
            var found = false
            for (m in matches) {
                val bounds = Rect()
                m.getBoundsInScreen(bounds)
                if (isFullScreenBounds(bounds)) found = true
            }
            matches.forEach { it.recycle() }
            if (found) return true
        }
        return false
    }

    private fun isFullScreenBounds(bounds: Rect): Boolean {
        val metrics = resources.displayMetrics
        return bounds.width() >= metrics.widthPixels * 0.85 &&
            bounds.height() >= metrics.heightPixels * 0.6
    }

    private fun exitToFeed(root: AccessibilityNodeInfo) {
        Stats.recordBlock(this)
        val homeNode = findHomeTabNode(root)
        val clicked = homeNode?.let { clickNodeOrAncestor(it) } ?: false
        homeNode?.recycle()
        if (!clicked) {
            Log.d(TAG, "Home tab not found -- falling back to back button")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun findHomeTabNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in HOME_TAB_RESOURCE_ID_CANDIDATES) {
            val matches = root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$id")
            if (matches.isNotEmpty()) {
                val first = matches[0]
                for (i in 1 until matches.size) matches[i].recycle()
                return first
            }
        }
        return findNodeByExactDesc(root, "home", depth = 0)
    }

    private fun findNodeByExactDesc(
        node: AccessibilityNodeInfo,
        target: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 25) return null
        val desc = node.contentDescription?.toString()?.lowercase()
        if (desc == target) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByExactDesc(child, target, depth + 1)
            if (found != null) return found
            child.recycle()
        }
        return null
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            transitionView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        overlayAdded = false
    }
}
