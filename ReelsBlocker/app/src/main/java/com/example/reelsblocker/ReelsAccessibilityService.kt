package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Two independent behaviors, both togglable at runtime via
 * PREFS_NAME/KEY_ENABLED (set from MainActivity's Run/Stop switch):
 *
 * 1. "One reel per session" -- lets you watch a single reel (however you
 *    got to it: DM share, tab, profile) then bounces you back the moment
 *    you swipe to a second one.
 *
 * 2. Bottom-nav Reels icon covering -- draws a small overlay in the same
 *    color as the nav bar over the Reels tab icon whenever it's visible,
 *    so it's not visible or tappable.
 *
 * NOTE: Android does not let an app enable/disable its own Accessibility
 * Service (that's blocked for security reasons -- otherwise malware
 * could silently re-enable itself). The in-app switch only pauses the
 * *logic* below; the service itself stays registered with the system.
 * To fully turn the service off, the user has to do it from
 * Settings > Accessibility, same as enabling it.
 */
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

        private val TEXT_CANDIDATES = listOf("reels")

        private const val COOLDOWN_MS = 800L
    }

    private var inReelsViewer = false
    private var allowedReelConsumed = false
    private var lastActionTime = 0L

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded = false

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

            val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            val view = View(this).apply {
                setBackgroundColor(if (isDark) Color.BLACK else Color.WHITE)
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
            // If this fails (blocked overlay type, OEM restriction, etc.)
            // don't take the whole service down with it -- the "one reel
            // per session" logic below still works fine without the
            // overlay feature.
            Log.w(TAG, "Overlay setup failed, continuing without icon-cover feature: ${e.message}")
            overlayAdded = false
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != INSTAGRAM_PACKAGE) {
            hideOverlay()
            return
        }

        if (!::prefs.isInitialized || !prefs.getBoolean(PrefsKeys.KEY_ENABLED, true)) {
            // Paused from the in-app switch -- do nothing.
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
            if (matches.isNotEmpty()) {
                val first = matches[0]
                for (i in 1 until matches.size) matches[i].recycle()
                return first
            }
        }
        return null
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
            view.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.w(TAG, "Overlay update failed: ${e.message}")
        }
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
                    Log.d(TAG, "Second reel detected -- backing out")
                    lastActionTime = now
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    inReelsViewer = false
                    allowedReelConsumed = false
                }
            }
        }
    }

    private fun isReelsViewerScreen(root: AccessibilityNodeInfo): Boolean {
        for (id in VIEWER_RESOURCE_ID_CANDIDATES) {
            val matches = root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$id")
            if (matches.isNotEmpty()) {
                matches.forEach { it.recycle() }
                return true
            }
        }
        return containsReelsText(root, depth = 0)
    }

    private fun containsReelsText(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 25) return false

        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        for (candidate in TEXT_CANDIDATES) {
            if (text?.contains(candidate) == true || desc?.contains(candidate) == true) {
                if (node.isSelected || node.isFocused) return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = containsReelsText(child, depth + 1)
            child.recycle()
            if (found) return true
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
