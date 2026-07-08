package com.example.reelsblocker

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class ReelsAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ReelsBlocker"
        private const val INSTAGRAM_PACKAGE = "com.instagram.android"

        // "reel_viewer_root" used to be in here too, but the v1.17.2 log
        // showed it matching right after "Tab icon missing" every single
        // time -- consistent with any full-screen immersive viewer, not
        // just Reels. Instagram's Stories feature was internally codenamed
        // "Reel" long before the separate TikTok-style feature borrowed
        // the public name (which is why Stories/DMs code tends to use
        // "reel_*" ids while the actual Reels tab uses "clips_*" ids, as
        // clips_viewer_view_pager and clips_tab below do) -- so this was
        // very likely matching Stories, not Reels, which is exactly what
        // "vyhazovalo mě to i ze storyček" described. Feed-embedded/DM-
        // shared reels (which also used this id) won't get the forced
        // "1 reel" exit anymore as a result -- an accepted trade until
        // there's a real distinguishing signal, since wrongly interrupting
        // Stories is worse than missing that one bypass route.
        private val VIEWER_RESOURCE_ID_CANDIDATES = listOf(
            "clips_viewer_view_pager"
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
        // Tab icon lookup can transiently miss for a single frame while
        // Instagram rebinds unrelated parts of the screen -- hiding (and
        // then re-showing) the overlay on every such blip is what reads
        // as "problikávání". Kept just large enough to bridge one missed
        // frame; the disappearance itself is instant (see hideOverlay),
        // so this no longer reads as a lingering delay in DMs.
        private const val HIDE_GRACE_MS = 120L
        private const val FADE_MS = 140L
        private const val REPOSITION_THRESHOLD_PX = 14
        private const val MIN_REPOSITION_INTERVAL_MS = 200L
        // How many consecutive "not in viewer" reads before we actually
        // consider the session over. Mid-swipe transition animations can
        // briefly report bounds that don't look full-screen, which was
        // resetting (and effectively re-granting) the "1 free reel" way
        // too often the longer someone scrolled. The v1.19 log showed a
        // reset only 804ms after entry while something CPU-heavy (screen
        // recording) was running alongside Instagram -- rendering hiccups
        // under load can plausibly cost more than 2 consecutive misses.
        private const val VIEWER_MISS_TOLERANCE = 4

        // The v1.17.2 log showed Instagram's own settle/lazy-load scrolls
        // landing as late as 683ms after entry -- right at the edge of the
        // old 700ms window -- with the next real event then misread as a
        // deliberate second swipe only ~800ms after entry, kicking the
        // user out before they'd finished watching the first reel at all.
        // More headroom here trades away catching a handful of genuinely
        // very fast swipe-throughs in exchange for not doing that.
        private const val ENTRY_GRACE_MS = 1200L

        // _v2 because a channel's importance can't be changed by editing
        // this code once it exists on someone's device -- switching to a
        // new id is the only way to make the heads-up (HIGH) importance
        // actually apply for people who already had the old LOW channel.
        private const val NOTIFICATION_CHANNEL_ID = "blocking_active_v2"
        private const val NOTIFICATION_ID = 1
    }

    private var inReelsViewer = false
    private var viewerEnteredAt = 0L
    private var viewerMissCount = 0
    private var lastActionTime = 0L
    private var lastLoggedPackage: String? = null
    private var currentForegroundPackage: String? = null

    private var windowManager: WindowManager? = null

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded = false
    private var lastAppliedBounds: Rect? = null
    private var lastRepositionAt = 0L
    private var lastSeenTabAt = 0L
    @Volatile private var sampledColor: Int? = null
    private var lastColorSampleTime = 0L
    @Volatile private var colorSampleInFlight = false
    private var statusBarHeightPx = 0

    private var transitionRoot: FrameLayout? = null
    private var transitionLabel: TextView? = null
    private var debugBadge: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val colorSampleExecutor = Executors.newSingleThreadExecutor()

    private lateinit var prefs: SharedPreferences

    // Reacts immediately when Run/Stop is toggled from the app, even if
    // Instagram isn't in the foreground right now to generate an event.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PrefsKeys.enabledKeyFor("instagram")) updateNotification()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AppLog.d(this, TAG, "Service connected")
        prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
        statusBarHeightPx = getStatusBarHeight()
        // onServiceConnected can fire again if the system rebinds the
        // service (e.g. after it was briefly killed on HyperOS) -- tear
        // down any overlay windows from a previous connection first,
        // otherwise adding a second TYPE_ACCESSIBILITY_OVERLAY window
        // silently duplicates it and can throw on some OEM skins.
        teardownOverlays()
        setupOverlay()
        setupTransitionOverlay()
        setupNotificationChannel()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        updateNotification()
    }

    private fun setupNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)
    }

    // A plain ongoing notification while blocking is on -- not a
    // foreground-service notification, just a visible, non-swipeable
    // reminder that the service is actively watching Instagram. HIGH
    // channel importance makes it pop up as heads-up instead of sitting
    // silently in the shade; tapping it opens the app on the Home tab.
    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val enabled = prefs.getBoolean(PrefsKeys.enabledKeyFor("instagram"), false)
        if (!enabled) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        try {
            val openIntent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_HOME
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            AppLog.w(this, TAG, "Notification permission missing: ${e.message}")
        }
    }

    private fun teardownOverlays() {
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            transitionRoot?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        overlayView = null
        transitionRoot = null
        transitionLabel = null
        debugBadge = null
        overlayAdded = false
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    // AppCompatDelegate's per-app language override is applied by
    // AppCompatActivity wrapping its own base context -- a plain
    // AccessibilityService never goes through that, so getString() here
    // would otherwise silently ignore the language picked in the app and
    // just use the phone's system language. Building a one-off
    // Configuration with the chosen locale sidesteps that.
    private fun localizedString(resId: Int): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        val locale = if (locales.isEmpty) null else locales[0]
        if (locale == null) return getString(resId)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        return createConfigurationContext(config).getString(resId)
    }

    private fun setupOverlay() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val view = View(this).apply {
                setBackgroundColor(fallbackColor())
                visibility = View.GONE
                // A plain View with no listener returns false from
                // onTouchEvent and doesn't actually consume the tap --
                // that let taps through to the Reels icon underneath it.
                // Being explicitly clickable guarantees the touch stops here.
                isClickable = true
                setOnClickListener { }
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

            val label = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setPadding(
                    (20 * density).toInt(), (13 * density).toInt(),
                    (20 * density).toInt(), (13 * density).toInt()
                )
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#EE1A1A1A"))
                    cornerRadius = 22 * density
                    setStroke((1 * density).toInt(), Color.parseColor("#3326A69A"))
                }
                alpha = 0f
                translationY = -60 * density
            }
            val labelParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = statusBarHeightPx + (16 * density).toInt()
            }
            root.addView(label, labelParams)

            // Small always-attached state badge for live debugging --
            // only shown when the "Show debug overlay" toggle is on. Root
            // used to default to GONE and only flip VISIBLE during the
            // exit-pill animation, but that would hide this too; root now
            // stays permanently attached (it's FLAG_NOT_TOUCHABLE, so this
            // costs nothing visually) and each child manages its own
            // visibility instead.
            val badge = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 11f
                setPadding(
                    (8 * density).toInt(), (4 * density).toInt(),
                    (8 * density).toInt(), (4 * density).toInt()
                )
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#CC000000"))
                    cornerRadius = 8 * density
                }
                visibility = View.GONE
            }
            val badgeParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = statusBarHeightPx + (8 * density).toInt()
                leftMargin = (8 * density).toInt()
            }
            root.addView(badge, badgeParams)
            debugBadge = badge

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

    // Reflects internal state live so a screen recording is
    // self-documenting instead of needing to be correlated against a
    // separate timestamped text log afterwards. Off by default.
    private fun updateDebugBadge(text: String, color: String) {
        val badge = debugBadge ?: return
        if (!::prefs.isInitialized || !prefs.getBoolean(PrefsKeys.KEY_DEBUG_OVERLAY, false)) {
            if (badge.visibility != View.GONE) badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE
        badge.text = text
        badge.setTextColor(Color.parseColor(color))
    }

    // wentToFeed is false when we had to fall back to the plain back
    // button instead of clicking the Home tab -- in that case we can't
    // actually promise the user landed back on the main feed (a fallback
    // "back" from a reel opened inside a DM thread returns to the DM,
    // not the feed), so the pill text needs to stay honest about that.
    private fun playExitAnimation(wentToFeed: Boolean) {
        transitionRoot ?: return
        val label = transitionLabel ?: return
        val density = resources.displayMetrics.density
        try {
            label.text = localizedString(if (wentToFeed) R.string.pill_back_to_feed else R.string.pill_left_reels)
            label.animate().cancel()
            label.alpha = 0f
            label.translationY = -60 * density
            label.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    label.postDelayed({
                        label.animate()
                            .alpha(0f)
                            .translationY(-40 * density)
                            .setDuration(200)
                            .start()
                    }, 900)
                }
                .start()
        } catch (e: Exception) {
            AppLog.w(this, TAG, "Exit animation failed: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString()

        // The service isn't package-filtered (on purpose -- see the v1.0
        // notes in README about why), so it also receives accessibility
        // events generated by our OWN overlay/transition windows whenever
        // their position, visibility or alpha changes. Those used to hit
        // the "not Instagram -> user left" branch below, which hid the
        // overlay and wiped the Reels-viewer session state -- which in
        // turn re-triggered another self-event, in a tight feedback loop.
        // That's the real cause behind the erratic "sometimes 1 reel,
        // sometimes several" behavior: the session state was getting
        // reset dozens of times a second while just sitting in a Reel.
        if (eventPackage == packageName) return
        // A null packageName carries no information about what app the
        // user is in -- treating it as "left Instagram" was the same
        // class of bug as the self-package one above (some system/IME
        // events don't report a package at all). Skip instead of guessing.
        if (eventPackage == null) return
        // The v1.14 log showed "miui.systemui.plugin" as pure noise from
        // non-window-state event types, which the TYPE_WINDOW_STATE_CHANGED
        // filter below was supposed to handle. The v1.20 log proved that
        // wrong: it fired a genuine TYPE_WINDOW_STATE_CHANGED to this same
        // package, which got trusted as a real "user left Instagram" and
        // stuck currentForegroundPackage there for 33 straight seconds
        // while the user was still actually sitting in a Reel the whole
        // time -- explaining both "kicked out instantly" (detection
        // resumed right as grace expired) and "never kicked out" (stuck
        // reporting not-Instagram, so the 1-reel limit never got a chance
        // to run at all) as the same root cause. This is HyperOS's own
        // system UI plugin, never a real app the user is using -- skip it
        // outright rather than ever trusting it as a foreground app.
        if (eventPackage == "miui.systemui.plugin") return

        // The service logs every foreground-app hop it sees, all day, not
        // just during active testing -- with no package filter, that's
        // every notification check, every home-screen tap, everything.
        // Against a 200KB log budget that noise was crowding out the
        // Instagram-relevant history within minutes of normal phone use,
        // so by the time a log got exported, exactly the events being
        // asked about were already the ones trimmed away. Only log
        // transitions that actually involve Instagram (entering or
        // leaving it) -- hops between two unrelated apps (keyboard,
        // launcher, this app itself) tell us nothing useful anyway.
        val relevantToInstagram = eventPackage == INSTAGRAM_PACKAGE || lastLoggedPackage == INSTAGRAM_PACKAGE
        if (eventPackage != lastLoggedPackage) {
            if (relevantToInstagram) {
                AppLog.d(this, TAG, "Event package changed to: $eventPackage")
            }
            lastLoggedPackage = eventPackage
        }

        // v1.14's log showed "miui.systemui.plugin" interleaved with
        // com.instagram.android dozens of times a second -- almost
        // certainly HyperOS's own accessibility-overlay plumbing reporting
        // itself under a system package instead of ours, which the
        // same-package guard above can't catch. Every event type
        // (scrolls, content changes) was being trusted for "what app is
        // this," but only TYPE_WINDOW_STATE_CHANGED actually reflects a
        // real foreground window switch -- everything else can fire from
        // transient system chrome without the foreground app changing at
        // all. Trusting those as "user left Instagram" reset the Reels
        // session state constantly, which explains the erratic reel
        // counts, the flickering overlay, and is the most likely cause of
        // swipes-from-DMs bypassing the block too (the session kept
        // getting wiped before the swipe-past-first-reel check could fire).
        //
        // v1.21 and v1.22 each fixed one specific transient window
        // (miui.systemui.plugin, then the on-screen keyboard) that could
        // still fire a *genuine* TYPE_WINDOW_STATE_CHANGED without
        // Instagram actually losing focus -- but the v1.22 log showed a
        // keyboard transition slip through the window-type check anyway
        // (the live `windows` list is a snapshot that can race with the
        // event that triggered it). Rather than keep chasing each new
        // culprit one at a time, any transition AWAY from Instagram now
        // gets one live confirmation: ask rootInActiveWindow (the same
        // check v1.19 already uses, just applied at this decision point
        // too) whether Instagram is still actually the active window right
        // now. If it is, the transition is spurious -- skip it and keep
        // treating Instagram as current. This covers every transient
        // window type at once, known or not yet seen, instead of needing a
        // new patch per culprit.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val leavingInstagram = currentForegroundPackage == INSTAGRAM_PACKAGE && eventPackage != INSTAGRAM_PACKAGE
            // rootInActiveWindow returns a node that must be recycled --
            // this was leaking one every time this branch ran (i.e. on
            // basically every real window switch away from Instagram)
            // until the accessibility node pool ran out, which plausibly
            // explains the sudden across-the-board flakiness (including in
            // completely unrelated node lookups like the reel-viewer match)
            // right after this check was added.
            val stillReallyInInstagram = if (leavingInstagram) {
                val checkRoot = rootInActiveWindow
                val matches = checkRoot?.packageName?.toString() == INSTAGRAM_PACKAGE
                checkRoot?.recycle()
                matches
            } else {
                false
            }
            if (!stillReallyInInstagram) {
                if (eventPackage != currentForegroundPackage) {
                    val fgRelevant = eventPackage == INSTAGRAM_PACKAGE || currentForegroundPackage == INSTAGRAM_PACKAGE
                    if (fgRelevant) {
                        AppLog.d(this, TAG, "Foreground app changed to: $eventPackage")
                    }
                }
                currentForegroundPackage = eventPackage
            }
        }

        val isInstagram = currentForegroundPackage == INSTAGRAM_PACKAGE

        if (!isInstagram) {
            hideOverlay()
            inReelsViewer = false
            lastTimeTickAt = 0L
            updateDebugBadge("not IG", "#808080")
            return
        }

        val root = rootInActiveWindow ?: return
        try {
            // rootInActiveWindow reflects whatever window is truly focused
            // right now, which can drift from currentForegroundPackage --
            // e.g. a transient status-bar peek while watching something
            // fullscreen doesn't always fire TYPE_WINDOW_STATE_CHANGED, so
            // our tracked "still in Instagram" can go stale while the
            // actual root briefly belongs to systemui instead. Searching
            // that root for Instagram's tab icon predictably finds
            // nothing, so the overlay silently sat hidden for up to a
            // minute (per the v1.18 log) until a real window switch
            // finally resynced things. Just skip processing this one event
            // instead of touching any state either way -- Instagram is
            // still genuinely current underneath, so neither hiding nor
            // resetting the session is correct here.
            if (root.packageName?.toString() != INSTAGRAM_PACKAGE) {
                updateDebugBadge("skip (root≠IG)", "#FFA726")
                return
            }

            // Time tracking runs regardless of the Run/Stop toggle -- it's
            // a passive usage insight, not part of the blocking feature.
            tickTimeTracking(classifyScreen(root))

            if (!::prefs.isInitialized || !prefs.getBoolean(PrefsKeys.enabledKeyFor("instagram"), false)) {
                hideOverlay()
                updateDebugBadge("IG (blocking off)", "#808080")
                return
            }

            updateOverlay(root)
            handleReelSession(root, event)
        } catch (e: Exception) {
            AppLog.w(this, TAG, "Error handling accessibility event: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    // ---- Time-spent tracking ----

    private var lastTimeTickAt = 0L
    private var lastTimeCategory = TimeCategory.OTHER

    // FEED reuses the already-validated home-tab resource id (same one
    // exitToFeed clicks) just reading its selected state instead of
    // clicking it -- low risk since that id is proven to work. DM/STORY
    // have no validated resource ids yet, so unclassified time falls into
    // OTHER rather than being guessed at (see CLAUDE.md rule 5).
    private fun classifyScreen(root: AccessibilityNodeInfo): TimeCategory {
        val homeNode = findHomeTabNode(root)
        val isFeed = homeNode?.isSelected == true
        homeNode?.recycle()
        return if (isFeed) TimeCategory.FEED else TimeCategory.OTHER
    }

    private fun tickTimeTracking(category: TimeCategory) {
        val now = System.currentTimeMillis()
        if (lastTimeTickAt != 0L) {
            TimeStats.addTime(this, lastTimeCategory, now - lastTimeTickAt)
        }
        lastTimeTickAt = now
        lastTimeCategory = category
    }

    // ---- Bottom-nav Reels icon covering ----

    private fun updateOverlay(root: AccessibilityNodeInfo) {
        if (!overlayAdded) return
        val tabNode = findTabIconNode(root)
        if (tabNode != null) {
            lastSeenTabAt = System.currentTimeMillis()
            val bounds = Rect()
            tabNode.getBoundsInScreen(bounds)
            tabNode.recycle()
            showOverlayAt(bounds)
            updateDebugBadge("IG · tab✓", "#26A69A")
        } else {
            val missMs = System.currentTimeMillis() - lastSeenTabAt
            updateDebugBadge("IG · tab✗ ${missMs}ms", "#FFA726")
            if (missMs >= HIDE_GRACE_MS && overlayView?.visibility != View.GONE) {
                AppLog.d(this, TAG, "Tab icon missing for ${missMs}ms -- hiding overlay")
                hideOverlay()
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

        val wasHidden = view.visibility != View.VISIBLE
        val last = lastAppliedBounds
        val bigEnoughChange = last == null ||
            Math.abs(last.left - bounds.left) > REPOSITION_THRESHOLD_PX ||
            Math.abs(last.top - bounds.top) > REPOSITION_THRESHOLD_PX ||
            last.width() != bounds.width() || last.height() != bounds.height()
        val now = System.currentTimeMillis()
        val enoughTimePassed = now - lastRepositionAt > MIN_REPOSITION_INTERVAL_MS
        val movedEnough = bigEnoughChange && (enoughTimePassed || last == null)

        if (movedEnough || wasHidden) {
            params.x = bounds.left
            params.y = bounds.top - statusBarHeightPx
            params.width = bounds.width()
            params.height = bounds.height()
            try {
                wm.updateViewLayout(view, params)
                lastAppliedBounds = Rect(bounds)
                lastRepositionAt = now
                AppLog.d(this, TAG, "Overlay placed at x=${params.x} y=${params.y} w=${params.width} h=${params.height}")
            } catch (e: Exception) {
                AppLog.w(this, TAG, "Overlay update failed: ${e.message}")
                return
            }
        }

        view.setBackgroundColor(sampledColor ?: fallbackColor())
        if (wasHidden) {
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(FADE_MS).start()
        }
        maybeResampleColor(bounds)
    }

    // Hiding is intentionally not animated -- a fade-out here is what read
    // as the overlay "lingering" for a moment after actually leaving for a
    // DM thread. Showing still fades in (see showOverlayAt) to avoid the
    // original flicker; disappearing should feel immediate instead.
    private fun hideOverlay() {
        if (!overlayAdded) return
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = overlayParams ?: return
        if (view.visibility == View.GONE) return
        lastAppliedBounds = null
        view.animate().cancel()
        view.alpha = 0f
        view.visibility = View.GONE
        params.width = 0
        params.height = 0
        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    private fun fallbackColor(): Int = Color.parseColor("#1A1A1A")

    private fun maybeResampleColor(bounds: Rect) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val now = System.currentTimeMillis()
        val isFirstSample = sampledColor == null
        if (!isFirstSample && now - lastColorSampleTime < COLOR_RESAMPLE_MS) return
        if (colorSampleInFlight) return
        lastColorSampleTime = now
        colorSampleInFlight = true

        try {
            // Decoding the hardware buffer into a plain bitmap is the
            // expensive part -- run the whole callback on a background
            // thread (not mainExecutor) so a full-screen copy never
            // blocks the accessibility event loop on the main thread.
            takeScreenshot(Display.DEFAULT_DISPLAY, colorSampleExecutor, object : TakeScreenshotCallback {
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
                            safeBitmap.recycle()
                            AppLog.d(this@ReelsAccessibilityService, TAG, "Sampled color at x=$x y=$y = #${Integer.toHexString(color)}")
                            mainHandler.post {
                                sampledColor = color
                                overlayView?.setBackgroundColor(color)
                            }
                        }
                    } catch (e: Exception) {
                        AppLog.w(this@ReelsAccessibilityService, TAG, "Color sample decode failed: ${e.message}")
                    } finally {
                        colorSampleInFlight = false
                    }
                }

                override fun onFailure(errorCode: Int) {
                    AppLog.w(this@ReelsAccessibilityService, TAG, "Screenshot for color sampling failed: code=$errorCode")
                    colorSampleInFlight = false
                }
            })
        } catch (e: Exception) {
            AppLog.w(this, TAG, "takeScreenshot call failed: ${e.message}")
            colorSampleInFlight = false
        }
    }

    // ---- One reel per session ----

    private fun handleReelSession(root: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val matchedId = matchedReelsViewerId(root)
        val currentlyInViewer = matchedId != null
        val now = System.currentTimeMillis()

        if (currentlyInViewer) {
            viewerMissCount = 0
            if (!inReelsViewer) {
                inReelsViewer = true
                viewerEnteredAt = now
                AppLog.d(this, TAG, "Entered reels viewer (matched id=$matchedId) -- 1 reel allowed, next real swipe exits")
                updateDebugBadge("REELS entered", "#26A69A")
            } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                if (now - viewerEnteredAt < ENTRY_GRACE_MS) {
                    AppLog.d(this, TAG, "Ignoring scroll ${now - viewerEnteredAt}ms after entry (likely settle, not a real swipe)")
                    return
                }
                if (now - lastActionTime > COOLDOWN_MS) {
                    AppLog.d(this, TAG, "Swiped past the first reel -- exiting to Home feed")
                    lastActionTime = now
                    val wentToFeed = exitToFeed(root)
                    playExitAnimation(wentToFeed)
                    inReelsViewer = false
                    updateDebugBadge("REELS→EXIT", "#FF5252")
                }
            }
        } else if (inReelsViewer) {
            viewerMissCount++
            if (viewerMissCount >= VIEWER_MISS_TOLERANCE) {
                inReelsViewer = false
                viewerMissCount = 0
                AppLog.d(this, TAG, "Left reels viewer -- session reset")
                updateDebugBadge("REELS→gone", "#808080")
            }
            // else: a single non-matching frame during a swipe transition
            // animation isn't treated as actually leaving -- avoids
            // silently re-granting a fresh "free reel" on every swipe.
        }
    }

    // Returns which candidate id matched (or null) instead of a plain
    // boolean, so callers can log it -- needed to eventually tell whether
    // Stories are wrongly matching the same viewer id as Reels, since
    // Instagram is known to share view infrastructure between the two and
    // there's no official documentation to check against (see CLAUDE.md
    // rule 5: this only logs what already matches, it doesn't add new
    // guesses).
    private fun matchedReelsViewerId(root: AccessibilityNodeInfo): String? {
        for (id in VIEWER_RESOURCE_ID_CANDIDATES) {
            val matches = root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$id")
            var found = false
            for (m in matches) {
                val bounds = Rect()
                m.getBoundsInScreen(bounds)
                if (isFullScreenBounds(bounds)) found = true
            }
            matches.forEach { it.recycle() }
            if (found) return id
        }
        return null
    }

    private fun isFullScreenBounds(bounds: Rect): Boolean {
        val metrics = resources.displayMetrics
        return bounds.width() >= metrics.widthPixels * 0.85 &&
            bounds.height() >= metrics.heightPixels * 0.6
    }

    private fun exitToFeed(root: AccessibilityNodeInfo): Boolean {
        Stats.recordBlock(this)
        val homeNode = findHomeTabNode(root)
        val clicked = homeNode?.let { clickNodeOrAncestor(it) } ?: false
        homeNode?.recycle()
        if (!clicked) {
            AppLog.d(this, TAG, "Home tab not found -- falling back to back button")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        return clicked
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
        teardownOverlays()
        colorSampleExecutor.shutdownNow()
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
