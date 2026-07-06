package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
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
 * Two independent behaviors:
 *
 * 1. "One reel per session" -- lets you watch a single reel (however you
 *    got to it: DM share, tab, profile) then bounces you back the moment
 *    you swipe to a second one.
 *
 * 2. Bottom-nav Reels icon covering -- draws a small overlay in the same
 *    color as the nav bar over the Reels tab icon whenever it's visible,
 *    so it's not visible or tappable. This is a visual patch, not a real
 *    removal -- a third-party app cannot edit Instagram's own rendered
 *    UI tree, only draw on top of it.
 */
class ReelsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        // Full-screen reel viewer (used for the "one reel per session" rule).
        private val VIEWER_RESOURCE_ID_CANDIDATES = listOf(
            "clips_viewer_view_pager",
            "reel_viewer_root"
        )

        // Bottom tab bar Reels icon (used for the overlay-cover rule).
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

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var overlayParams: WindowManager.LayoutParams
    private var overlayAdded = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        setupOverlay()
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        overlayView = View(this).apply {
            setBackgroundColor(if (isDark) Color.BLACK else Color.WHITE)
            visibility = View.GONE
        }

        overlayParams = WindowManager.LayoutParams(
            0, 0, 0, 0,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager.addView(overlayView, overlayParams)
        overlayAdded = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != INSTAGRAM_PACKAGE) {
            hideOverlay()
            return
        }

        val root = rootInActiveWindow ?: return
        try {
            updateOverlay(root)
            handleReelSession(root, event)
        } finally {
            root.recycle()
        }
    }

    // ---- Bottom-nav Reels icon covering ----

    private fun updateOverlay(root: AccessibilityNodeInfo) {
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
        if (!overlayAdded || bounds.width() <= 0 || bounds.height() <= 0) return
        overlayParams.x = bounds.left
        overlayParams.y = bounds.top
        overlayParams.width = bounds.width()
        overlayParams.height = bounds.height()
        try {
            windowManager.updateViewLayout(overlayView, overlayParams)
            overlayView.visibility = View.VISIBLE
        } catch (e: Exception) {
            Log.w(TAG, "Overlay update failed: ${e.message}")
        }
    }

    private fun hideOverlay() {
        if (!overlayAdded) return
        if (overlayView.visibility != View.GONE) {
            overlayView.visibility = View.GONE
            overlayParams.width = 0
            overlayParams.height = 0
            try {
                windowManager.updateViewLayout(overlayView, overlayParams)
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
                windowManager.removeView(overlayView)
            } catch (_: Exception) {
            }
            overlayAdded = false
        }
    }
}
