package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
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

/**
 * Two independent behaviors, both togglable at runtime via
 * PrefsKeys (set from MainActivity's Run/Stop switch):
 *
 * 1. "One reel per session" -- lets you watch a single reel in the
 *    FULL-SCREEN reels viewer, then, on the next swipe, redirects you to
 *    the Home tab (feed) instead of just pressing "back" (which could
 *    exit Instagram entirely if the viewer was opened from a deep link).
 *    Only triggers for the real full-screen viewer -- not for "Suggested
 *    reels" carousels embedded inside the Feed or DMs.
 *
 * 2. Bottom-nav Reels icon covering -- draws a small overlay, color
 *    sampled from the actual screen (via takeScreenshot) so it blends
 *    with whatever theme Instagram is using, over the Reels tab icon.
 *    This overlay is a floating window independent of which app is in
 *    the foreground, so the service listens to ALL apps' events (not
 *    just Instagram's) purely to know when to hide it once you leave
 *    Instagram.
 */
class ReelsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        // Strict IDs for the real full-screen reel viewer only.
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
        private const val COLOR_RESAMPLE_MS = 3000L
    }

    private var inReelsViewer = false
    private var allowedReelConsumed = false
    private var lastActionTime = 0L

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded = false

    private var sampledColor: Int? = null
    private var lastColorSampleTime = 0L

    private lateinit var prefs: SharedPreferences

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
        setupOverlay()
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val isInstagram = event.packageName?.toString() == INSTAGRAM_PACKAGE

        if (!isInstagram) {
            // Any other app in the foreground -- make sure the overlay
            // (which is an independent floating window and doesn't
            // disappear on its own) gets hidden, and reset session state.
            hideOverlay()
            inReelsViewer = false
            allowedReelConsumed = false
            return
        }

        if (!::prefs.isInitialized || !prefs.getBoolean(PrefsKeys.KEY_ENABLED, true)) {
            hideOverlay()
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
            val bounds = Rect()
            tabNode.getBoundsInScreen(bounds)
            tabNode.recycle()
            showOverlayAt(bounds)
        } else {
            hideOverlay()
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

    // Guards against a misidentified match (e.g. a full-screen container
    // that happens to share a resource id) turning into a giant overlay
    // that blocks scrolling/taps everywhere. A real tab bar icon is small
    // and sits in the bottom strip of the screen -- nothing else qualifies.
    private fun isPlausibleTabIconBounds(bounds: Rect): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val metrics = resources.displayMetrics
        val maxIconPx = (72 * metrics.density).toInt() // generous cap for a nav icon touch target
        if (bounds.width() > maxIconPx || bounds.height() > maxIconPx) return false
        val screenHeight = metrics.heightPixels
        if (bounds.top < screenHeight * 0.80) return false // must be in the bottom nav strip
        return true
    }

    private fun showOverlayAt(bounds: Rect) {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = overlayParams ?: return
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        params.x = bounds.left
        params.y = bounds.top
        params.width = bounds.width()
        params.height = bounds.height()
        try {
            wm.updateViewLayout(view, params)
            view.setBackgroundColor(sampledColor ?: fallbackColor())
            view.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.w(TAG, "Overlay update failed: ${e.message}")
            return
        }
        maybeResampleColor(bounds)
    }

    private fun hideOverlay() {
        if (!overlayAdded) return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = overlayParams ?: return
        if (view.visibility != View.GONE) {
            view.visibility = View.GONE
            params.width = 0
            params.height = 0
            try {
                wm.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun fallbackColor(): Int {
        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (isDark) Color.BLACK else Color.WHITE
    }

    // Samples the actual pixel color just above the tab icon (still
    // inside the nav bar background, outside the icon glyph itself) so
    // the overlay blends regardless of Instagram's own theme setting.
    // Throttled since taking a screenshot isn't free.
    private fun maybeResampleColor(bounds: Rect) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = System.currentTimeMillis()
        if (now - lastColorSampleTime < COLOR_RESAMPLE_MS) return
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
                            val x = bounds.centerX().coerceIn(0, safeBitmap.width - 1)
                            val y = (bounds.top - 6).coerceIn(0, safeBitmap.height - 1)
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
                allowedReelConsumed = false
                Log.d(TAG, "Entered reels viewer -- session start, 1 reel allowed")
            }

            !currentlyInViewer && inReelsViewer -> {
                inReelsViewer = false
                allowedReelConsumed = false
                Log.d(TAG, "Left reels viewer -- session reset")
            }

            currentlyInViewer && inReelsViewer && event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val now = System.currentTimeMillis()
                if (!allowedReelConsumed) {
                    allowedReelConsumed = true
                    Log.d(TAG, "First reel shown, next scroll will exit")
                } else if (now - lastActionTime > COOLDOWN_MS) {
                    Log.d(TAG, "Second reel detected -- exiting to Home feed")
                    lastActionTime = now
                    exitToFeed(root)
                    inReelsViewer = false
                    allowedReelConsumed = false
                }
            }
        }
    }

    // Strict: only the real full-screen viewer, identified purely by its
    // dedicated resource IDs. Deliberately does NOT fall back to generic
    // text matching -- that previously misfired on things like the
    // "Suggested reels" carousel embedded directly in the Feed.
    // Strict: only the real full-screen viewer. Instagram appears to
    // reuse the same "clips_viewer_view_pager" resource id for the
    // "Suggested reels" carousel embedded directly in the Feed, so a
    // plain id match isn't enough -- it must also actually occupy
    // (almost) the whole screen, which the embedded carousel does not.
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
        if (overlayAdded) {
            try {
                windowManager?.removeView(overlayView)
            } catch (_: Exception) {
            }
            overlayAdded = false
        }
    }
}
