package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView

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
        private const val MISS_TOLERANCE = 3
        private const val REPOSITION_THRESHOLD_PX = 6

        // Ignore scroll events that happen within this window of entering
        // the viewer -- Instagram appears to fire an internal settle/layout
        // scroll event right when the viewer opens, before the user has
        // swiped anywhere, which previously caused random instant exits.
        private const val ENTRY_GRACE_MS = 700L
    }

    private var inReelsViewer = false
    private var viewerEnteredAt = 0L
    private var lastActionTime = 0L

    private var windowManager: WindowManager? = null

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded = false
    private var lastAppliedBounds: Rect? = null
    private var missCount = 0
    private var sampledColor: Int? = null
    private var lastColorSampleTime = 0L
    private var statusBarHeightPx = 0

    private var transitionRoot: FrameLayout? = null
    private var transitionLabel: TextView? = null

    private lateinit var prefs: SharedPreferences

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.d(this, TAG, "Service connected")
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
            AppLog.w(this, TAG, "Overlay setup failed: ${e.message}")
            overlayAdded = false
        }
    }

    private fun setupTransitionOverlay() {
        try {
            val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
            windowManager = wm
            val metrics = resources.displayMetrics
            val density = metrics.density

            val root = FrameLayout(this)
            val bg = View(this).apply {
                setBackgroundColor(Color.parseColor("#73000000")) // black, ~45% opacity
            }
            root.addView(
                bg,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val label = TextView(this).apply {
                text = "\u21A9  Zpět do feedu"
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(
                    (22 * density).toInt(), (12 * density).toInt(),
                    (22 * density).toInt(), (12 * density).toInt()
                )
                setBackgroundColor(Color.parseColor("#EE2A2A2A"))
            }
            val labelParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
            root.addView(label, labelParams)

            root.alpha = 0f
            root.visibility = View.GONE

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

            wm.addView(root, params)
            transitionRoot = root
            transitionLabel = label
        } catch (e: Exception) {
            AppLog.w(this, TAG, "Transition overlay setup failed: ${e.message}")
        }
    }

    private fun playExitAnimation() {
        val root = transitionRoot ?: return
        val label = transitionLabel
        try {
            root.visibility = View.VISIBLE
            root.alpha = 0f
            label?.scaleX = 0.85f
            label?.scaleY = 0.85f
            root.animate().alpha(1f).setDuration(140).withEndAction {
                root.postDelayed({
                    root.animate().alpha(0f).setDuration(260).withEndAction {
                        root.visibility = View.GONE
                    }.start()
                }, 380)
            }.start()
            label?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(220)?.start()
        } catch (e: Exception) {
            AppLog.w(this, TAG, "Exit animation failed: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val isInstagram = event.packageName?.toString() == INSTAGRAM_PACKAGE

        if (!isInstagram) {
            hideOverlay(force = true)
            inReelsViewer = false
            return
        }

        if (!::prefs.isInitialized || !prefs.getBoolean(PrefsKeys.enabledKeyFor("instagram"), true)) {
            hideOverlay(force = true)
            return
        }

        val root = rootInActiveWindow ?: return
        try {
            updateOverlay(root)
            handleReelSession(root, event)
        } catch (e: Exception) {
            AppLog.w(this, TAG, "Error handling accessibility event: ${e.message}")
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
                } else {
                    AppLog.d(this, TAG, "Rejected tab candidate id=$id bounds=$bounds")
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
            params.x = bounds.left
            params.y = bounds.top - statusBarHeightPx
            params.width = bounds.width()
            params.height = bounds.height()
            try {
                wm.updateViewLayout(view, params)
                lastAppliedBounds = Rect(bounds)
                AppLog.d(this, TAG, "Overlay placed at x=${params.x} y=${params.y} w=${params.width} h=${params.height}")
            } catch (e: Exception) {
                AppLog.w(this, TAG, "Overlay update failed: ${e.message}")
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
                            AppLog.d(this@ReelsAccessibilityService, TAG, "Sampled color at x=$x y=$y = #${Integer.toHexString(color)}")
                        }
                    } catch (e: Exception) {
                        AppLog.w(this@ReelsAccessibilityService, TAG, "Color sample decode failed: ${e.message}")
                    }
                }

                override fun onFailure(errorCode: Int) {
                    AppLog.w(this@ReelsAccessibilityService, TAG, "Screenshot for color sampling failed: code=$errorCode")
                }
            })
        } catch (e: Exception) {
            AppLog.w(this, TAG, "takeScreenshot call failed: ${e.message}")
        }
    }

    // ---- One reel per session ----

    private fun handleReelSession(root: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val currentlyInViewer = isReelsViewerScreen(root)
        val now = System.currentTimeMillis()

        when {
            currentlyInViewer && !inReelsViewer -> {
                inReelsViewer = true
                viewerEnteredAt = now
                AppLog.d(this, TAG, "Entered reels viewer -- 1 reel allowed, next real swipe exits")
            }

            !currentlyInViewer && inReelsViewer -> {
                inReelsViewer = false
                AppLog.d(this, TAG, "Left reels viewer -- session reset")
            }

            currentlyInViewer && inReelsViewer && event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (now - viewerEnteredAt < ENTRY_GRACE_MS) {
                    AppLog.d(this, TAG, "Ignoring scroll ${now - viewerEnteredAt}ms after entry (likely settle, not a real swipe)")
                    return
                }
                if (now - lastActionTime > COOLDOWN_MS) {
                    AppLog.d(this, TAG, "Swiped past the first reel -- exiting to Home feed")
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
            AppLog.d(this, TAG, "Home tab not found -- falling back to back button")
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
        AppLog.d(this, TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            transitionRoot?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        overlayAdded = false
    }
}
