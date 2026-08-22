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
        private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"

        // Lets MainActivity's Shutdown button stop the accessibility
        // service properly (disableSelf) before killing the process --
        // without this, Android would just resurrect the service moments
        // after killProcess, defeating the point of a full shutdown.
        @Volatile var instance: ReelsAccessibilityService? = null
            private set

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

        // Tabs whose ids are actually measured from this user's logs. Used as
        // an anchor to locate the bottom tab bar itself (see
        // findSelectedBottomTab) -- the bar and the remaining tabs carry no
        // ids that have been confirmed, so they're reached structurally,
        // through this anchor's parent, rather than guessed at.
        private val BOTTOM_TAB_ANCHOR_IDS = listOf(
            "clips_tab",
            "feed_tab",
            "home_tab",
            "reels_tab"
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

        // TYPE_WINDOW_CONTENT_CHANGED arrives in bursts (dozens per second
        // while media plays or a list rebinds), and each one used to run
        // 5+ full-tree id lookups on the main thread. Sustained main-thread
        // saturation is the classic way an accessibility service ends up
        // flagged "Not working" in settings (the system gives up on a
        // service that stops responding to its event pipe). Content-change
        // floods get sampled down to this interval; window-state changes
        // and scrolls (the events blocking decisions actually hang on) are
        // always processed.
        private const val CONTENT_EVENT_MIN_INTERVAL_MS = 200L

        // v1.36: "stories_tray" (the id v1.33-1.35 looked for) never
        // matched -- the 2026-08-21 log's "Top-of-screen dump" showed why:
        // on this Instagram build the stories row's own container carries
        // no resource id at all. What's actually there is several
        // "outer_container" LinearLayouts (STORIES_ITEM_RESOURCE_ID) laid
        // out side by side right under the top app bar, one per avatar.
        // "outer_container" alone is too generic to trust anywhere else in
        // the tree (the same log shows it reused on an unrelated
        // Reels-dismiss screen) -- see findStoriesTrayBounds for how it's
        // used safely (only counted when 2+ of them share the same top).
        private const val STORIES_ITEM_RESOURCE_ID = "outer_container"

        // How long the feed-block overlay takes to grow/shrink between
        // covering just the post area and covering the whole screen. Sits
        // at the fast end of the app's established drawer/panel timing
        // (CLAUDE.md: "translationX/translationY animace ~160-220ms") --
        // v1.37 dropped it from 220ms because the morph read as sluggish.
        private const val FEED_OVERLAY_MORPH_MS = 160L

        // v1.37: while the block is on screen, how often to re-ask the
        // system what's actually in front of the user, so it disappears
        // promptly on leaving the feed instead of waiting for whatever
        // accessibility event happens to come next -- see feedOverlayRecheck.
        private const val FEED_OVERLAY_RECHECK_MS = 250L

        // How many consecutive rechecks that can't confirm Instagram is in
        // front before the block comes down -- see feedOverlayRecheck.
        private const val FEED_OVERLAY_MISS_TOLERANCE = 3
    }

    private var inReelsViewer = false
    private var inTikTokFeed = false
    private var tiktokEnteredAt = 0L
    private var tiktokMissCount = 0
    private var viewerEnteredAt = 0L
    private var viewerMissCount = 0
    private var lastActionTime = 0L
    private var lastContentEventAt = 0L
    private var lastLoggedPackage: String? = null
    private var currentForegroundPackage: String? = null

    private var windowManager: WindowManager? = null

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAdded = false
    private var lastAppliedBounds: Rect? = null
    private var lastRepositionAt = 0L
    private var lastSeenTabAt = 0L

    // Separate window from overlayView above -- that one is small (icon-
    // sized) and follows the Reels/Home tab icon. This one is always a
    // full-screen, touch-through (FLAG_NOT_TOUCHABLE) window added once;
    // feedOverlayBlock is the actual opaque rectangle inside it, resized
    // (not moved as a whole window) to morph between covering just the
    // post area and covering the whole screen -- see handleFeedBlockOverlay.
    private var feedOverlayRoot: FrameLayout? = null
    private var feedOverlayBlock: View? = null
    private var feedOverlayLabel: TextView? = null
    // null = not currently shown at all. Top/bottom are absolute screen
    // coordinates; left/right are unused (the block always spans the full
    // width) and kept only so this reads as the covered band.
    private var feedOverlayCurrentRect: Rect? = null
    private var feedOverlayShown = false
    private var lastFeedOverlayFullLogAt = 0L
    private var lastUnknownTabSignature = ""
    private var lastBottomTabsSignature = ""
    private var feedOverlayMissCount = 0
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
        instance = this
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
        setupFeedOverlay()
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
                // The status-bar glyph (small icon) is capped at ~24dp by
                // Android itself -- the large icon is the way to get a
                // visibly bigger, colored brain in the expanded shade.
                .setLargeIcon(android.graphics.BitmapFactory.decodeResource(resources, R.drawable.ic_launcher_foreground))
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
            feedOverlayRoot?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        try {
            transitionRoot?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        overlayView = null
        mainHandler.removeCallbacks(feedOverlayRecheck)
        feedOverlayRoot = null
        feedOverlayBlock = null
        feedOverlayLabel = null
        feedOverlayCurrentRect = null
        feedOverlayShown = false
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

    // Big opaque block over the feed (see handleFeedBlockOverlay) -- unlike
    // overlayView above, this one carries a centered explanatory label so
    // it reads as an intentional block instead of a rendering glitch, and
    // it's fully opaque (not color-sampled) since the point is to hide the
    // content underneath, not blend in with it.
    //
    // v1.34: this window is FLAG_NOT_TOUCHABLE -- touches pass straight
    // through it to Instagram underneath, so scrolling still actually works
    // (Instagram keeps scrolling for real, nothing freezes) even while
    // visually covered. That's also what lets handleFeedBlockOverlay detect
    // "user scrolled back up to the stories row": Instagram's own scroll
    // position is genuinely moving the whole time, our overlay is just
    // sitting on top of it. The window itself is always full-screen (added
    // once, like transitionRoot below); feedOverlayBlock is the actual
    // opaque rectangle whose top/height get resized to grow/shrink between
    // "just the post area" and "the whole screen" -- see morphFeedOverlayTo.
    private fun setupFeedOverlay() {
        try {
            val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
            windowManager = wm
            val density = resources.displayMetrics.density

            val root = FrameLayout(this)

            // A plain View, not a container: it's a flat solid colour, so it
            // can be grown/shrunk with scaleY (a GPU transform, no layout
            // pass per frame) instead of by re-assigning layout params --
            // that's what makes the morph actually smooth. pivotY = 0 keeps
            // its top edge anchored where translationY puts it, so
            // [translationY, translationY + height*scaleY] is the covered
            // band. The label lives beside it (below) rather than inside,
            // so it doesn't get stretched by that same scale.
            val block = View(this).apply {
                setBackgroundColor(Color.parseColor("#121212"))
                visibility = View.GONE
                alpha = 0f
                pivotX = 0f
                pivotY = 0f
            }
            root.addView(
                block,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val label = TextView(this).apply {
                text = localizedString(R.string.feed_block_overlay_message)
                setTextColor(Color.parseColor("#909090"))
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding((32 * density).toInt(), 0, (32 * density).toInt(), 0)
                visibility = View.GONE
                alpha = 0f
            }
            root.addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.CENTER }
            )

            // v1.37: FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_NO_LIMITS (and the
            // cutout mode below) are what make this window's y=0 mean the
            // real top of the display. Without them the window starts BELOW
            // the status bar, while every bound read out of Instagram's tree
            // is an absolute screen coordinate -- so the whole block sat
            // statusBarHeightPx too low, which is exactly the "first post's
            // username stays uncovered at the top" and "the bottom bar is
            // still visible" the user reported (the log's own dump shows
            // Instagram's content starting at y=130, i.e. one status bar
            // down). With these flags absolute coordinates can be used
            // as-is, and full-screen really means full screen.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                0, 0,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            wm.addView(root, params)
            feedOverlayRoot = root
            feedOverlayBlock = block
            feedOverlayLabel = label
        } catch (e: Exception) {
            AppLog.w(this, TAG, "Feed overlay setup failed: ${e.message}")
        }
    }

    // resources.displayMetrics excludes the system bars, which would make
    // "full screen" stop short of the navigation bar -- the overlay window
    // itself is now genuinely full-screen (see setupFeedOverlay), so it
    // needs the real display size to match.
    private fun realScreenHeightPx(): Int {
        return try {
            val wm = windowManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                wm.maximumWindowMetrics.bounds.height()
            } else {
                val dm = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(dm)
                dm.heightPixels
            }
        } catch (_: Exception) {
            resources.displayMetrics.heightPixels
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

    // Callers pass the exact pill text: "back to feed" only when the Home
    // tab click actually succeeded (a fallback "back" from a reel opened
    // inside a DM returns to the DM, not the feed, so the text must stay
    // honest about that), a generic "left" text otherwise.
    private fun playExitAnimation(textRes: Int) {
        transitionRoot ?: return
        val label = transitionLabel ?: return
        val density = resources.displayMetrics.density
        try {
            label.text = localizedString(textRes)
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
        // One uncaught exception anywhere in here takes down the whole
        // service process, and Android then shows the service as "Not
        // working" in Accessibility settings until it's toggled again --
        // exactly the symptom being reported. The Instagram and TikTok
        // branches were individually wrapped, but the shared foreground-
        // tracking code above them wasn't; nothing in this handler is
        // worth dying for, so the whole thing gets one outer net.
        try {
            handleEvent(event)
        } catch (e: Exception) {
            AppLog.w(this, TAG, "Unhandled error in event handler: ${e.message}")
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
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
        // Time tracking runs regardless of the Run/Stop toggle for either
        // app -- it's a passive usage insight, not part of the blocking
        // feature. Tracked here (not per-app-branch) purely so TikTok being
        // foreground doesn't fall into the "not Instagram -> wipe the tick
        // baseline" path below and lose a whole in-between delta.
        val isTikTok = currentForegroundPackage == TIKTOK_PACKAGE

        // Sample down content-change floods before any tree work happens
        // (see CONTENT_EVENT_MIN_INTERVAL_MS). Applied here so both the
        // Instagram and TikTok paths benefit.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val nowThrottle = System.currentTimeMillis()
            if (nowThrottle - lastContentEventAt < CONTENT_EVENT_MIN_INTERVAL_MS) return
            lastContentEventAt = nowThrottle
        }

        if (!isInstagram) {
            inReelsViewer = false
            if (isTikTok && ::prefs.isInitialized && prefs.getBoolean(PrefsKeys.enabledKeyFor("tiktok"), false)) {
                val tkRoot = rootInActiveWindow
                if (tkRoot != null) {
                    try {
                        if (tkRoot.packageName?.toString() == TIKTOK_PACKAGE) {
                            reconDumpScreenIds(tkRoot, "TikTok recon")
                            updateTikTokOverlay(tkRoot)
                            handleTikTokSession(tkRoot, event)
                            // Read after handleTikTokSession so this tick's
                            // category matches the same continuation-aware
                            // state the session logic just used -- counted
                            // as REELS (short-form video watching, same
                            // bucket Instagram Reels uses) rather than FEED,
                            // which TikTok has no real equivalent of.
                            tickTimeTracking("tiktok", if (inTikTokFeed) TimeCategory.REELS else TimeCategory.OTHER)
                        }
                    } catch (e: Exception) {
                        AppLog.w(this, TAG, "TikTok handling failed: ${e.message}")
                    } finally {
                        tkRoot.recycle()
                    }
                }
                return
            }
            // Truly not in either tracked app -- stop the tick baseline so
            // the next resumed session doesn't count the gap as usage time.
            lastTimeTickAt = 0L
            hideOverlay()
            hideFeedBlockOverlay("left Instagram")
            inTikTokFeed = false
            updateDebugBadge("not IG", "#808080")
            return
        }
        inTikTokFeed = false

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

            tickTimeTracking("instagram", classifyScreen(root))
            categoryBadge(lastTimeCategory)

            // Screens the chart still can't classify get their ids
            // recorded, so any future gap can be closed from data too.
            if (lastTimeCategory == TimeCategory.OTHER && !inReelsViewer) {
                reconDumpScreenIds(root, "IG screen recon")
            }

            if (!::prefs.isInitialized || !prefs.getBoolean(PrefsKeys.enabledKeyFor("instagram"), false)) {
                hideOverlay()
                hideFeedBlockOverlay("blocking off")
                updateDebugBadge("IG (blocking off)", "#808080")
                return
            }

            updateOverlay(root)
            handleReelSession(root, event)
            handleFeedBlockOverlay(root)
        } catch (e: Exception) {
            AppLog.w(this, TAG, "Error handling accessibility event: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    // ---- Time-spent tracking ----

    private var lastTimeTickAt = 0L
    private var lastTimeCategory = TimeCategory.OTHER
    private var lastTimeAppId = "instagram"

    // Every id here was read off the user's own recon logs (v1.26 round),
    // not guessed -- see CLAUDE.md rule 5. DM threads consistently showed
    // thread_fragment_container + message_list, Stories showed
    // reel_viewer_root, and the main feed showed row_feed_* rows. Order
    // matters: a reel opened from inside a DM thread has both the clips
    // viewer AND the thread ids in the tree, and it should count as Reels.
    private fun classifyScreen(root: AccessibilityNodeInfo): TimeCategory {
        // Full-screen viewers first: they cover the tab bar completely, so
        // there is no highlighted tab to read. Both are matched on their own
        // on-screen bounds, not mere presence in the tree.
        if (matchedReelsViewerId(root) != null) return TimeCategory.REELS
        if (hasVisibleNodeById(root, "reel_viewer_root")) return TimeCategory.STORY
        // An open DM conversation is pushed on top of the tab bar, which
        // keeps whatever tab was highlighted underneath -- so this has to be
        // decided before the tab check below, not after.
        if (hasVisibleNodeById(root, "thread_fragment_container") ||
            hasVisibleNodeById(root, "message_list")
        ) {
            return TimeCategory.DM
        }

        // v1.38, per the user's own suggestion: which bottom-nav tab is
        // highlighted IS the answer to "what screen am I on" -- home lit up
        // means the feed, the paper plane means DMs, and so on.
        val selectedTab = findSelectedBottomTab(root)
        if (selectedTab != null) {
            val mapped = categoryForBottomTab(selectedTab.first, selectedTab.second)
            if (mapped != null) return mapped
            // A highlighted tab this doesn't recognise yet: log it (throttled,
            // and only when it changes) rather than guess -- CLAUDE.md rule 5.
            // The next log then says exactly which id/description to map.
            val signature = "${selectedTab.first}|${selectedTab.second}"
            if (signature != lastUnknownTabSignature) {
                lastUnknownTabSignature = signature
                AppLog.d(this, TAG, "Selected bottom tab not mapped: id=${selectedTab.first} desc=${selectedTab.second}")
            }
        }

        // No tab bar visible (or its highlighted tab is unknown) -- fall back
        // to what's actually drawn on screen.
        if (hasVisibleNodeById(root, "row_feed_photo_imageview") ||
            hasVisibleNodeById(root, "row_feed_profile_header")
        ) {
            return TimeCategory.FEED
        }
        return TimeCategory.OTHER
    }

    // Maps a highlighted bottom-nav tab to a category. "feed_tab"/"home_tab"
    // and "clips_tab" are measured (they're the same ids findHomeTabNode and
    // findTabIconNode already rely on, and the 2026-08-21 log confirms
    // feed_tab on this build). The rest are tolerant substring patterns, not
    // asserted ids: an unrecognised tab returns null and gets logged by the
    // caller instead of being guessed into the wrong bucket.
    private fun categoryForBottomTab(idSuffix: String, desc: String): TimeCategory? {
        val id = idSuffix.lowercase()
        val d = desc.lowercase()
        return when {
            id.contains("feed") || id.contains("home") || d == "home" -> TimeCategory.FEED
            id.contains("clips") || id.contains("reels") || d.contains("reels") -> TimeCategory.REELS
            id.contains("direct") || id.contains("inbox") || id.contains("messag") ||
                d.contains("direct") || d.contains("messag") -> TimeCategory.DM
            id.contains("profile") || id.contains("avatar") || d.contains("profile") ->
                TimeCategory.OTHER
            id.contains("search") || id.contains("explore") || id.contains("discover") ||
                d.contains("search") || d.contains("explore") -> TimeCategory.OTHER
            id.contains("camera") || id.contains("creat") || d.contains("creat") ->
                TimeCategory.OTHER
            else -> null
        }
    }

    // Returns (resource-id suffix, content description) of the highlighted
    // bottom-nav tab, or null when no tab bar is on screen.
    //
    // v1.39 rewrite. The v1.38 version walked the tree looking for any
    // selected node in the bottom strip and capped the walk at 25 levels --
    // Instagram's tab bar sits deeper than that, so it found nothing at all
    // (the user's 2026-08-22 log contains not one "Selected bottom tab not
    // mapped" line, which is what that failure looks like) and classification
    // silently fell back to the old content check, reporting FEED while the
    // paper plane was lit.
    //
    // This instead anchors on a tab whose id IS measured (clips_tab /
    // feed_tab, the same ones findTabIconNode and findHomeTabNode already
    // use) via findAccessibilityNodeInfosByViewId, which has no depth limit,
    // then walks UP one level to the tab bar and reads its children -- the
    // sibling tabs. No guessing about depth, and no guessing about the other
    // tabs' ids either: they get logged whenever the row changes, so the
    // paper plane / search / profile ids can be mapped from real data.
    private fun findSelectedBottomTab(root: AccessibilityNodeInfo): Pair<String, String>? {
        for (anchorId in BOTTOM_TAB_ANCHOR_IDS) {
            val matches = root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$anchorId")
            try {
                for (anchor in matches) {
                    val anchorBounds = Rect()
                    anchor.getBoundsInScreen(anchorBounds)
                    if (!isPlausibleBottomTabBounds(anchorBounds)) continue
                    val bar = anchor.parent ?: continue
                    try {
                        val tabs = readBottomTabs(bar)
                        if (tabs.isEmpty()) continue
                        logBottomTabsIfChanged(tabs)
                        val selected = tabs.firstOrNull { it.third } ?: continue
                        return selected.first to selected.second
                    } finally {
                        bar.recycle()
                    }
                }
            } finally {
                matches.forEach { it.recycle() }
            }
        }
        return null
    }

    // (id suffix, content description, isSelected) for each tab in the bar.
    private fun readBottomTabs(bar: AccessibilityNodeInfo): List<Triple<String, String, Boolean>> {
        val tabs = mutableListOf<Triple<String, String, Boolean>>()
        for (i in 0 until bar.childCount) {
            val child = bar.getChild(i) ?: continue
            try {
                val bounds = Rect()
                child.getBoundsInScreen(bounds)
                if (!isPlausibleBottomTabBounds(bounds)) continue
                tabs.add(
                    Triple(
                        child.viewIdResourceName?.substringAfterLast('/') ?: "",
                        child.contentDescription?.toString() ?: "",
                        isSelectedWithin(child, depth = 0)
                    )
                )
            } finally {
                child.recycle()
            }
        }
        return tabs
    }

    // Instagram puts the highlight on the tab container on some builds and on
    // the icon inside it on others, so a couple of levels down still counts.
    private fun isSelectedWithin(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (node.isSelected) return true
        if (depth >= 2) return false
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (isSelectedWithin(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    // Logged only when the row actually changes, so this is a handful of
    // lines per session rather than a flood -- and it is the measurement that
    // lets the remaining tabs (paper plane, search, profile) be mapped from
    // data instead of guessed at (CLAUDE.md rule 5).
    private fun logBottomTabsIfChanged(tabs: List<Triple<String, String, Boolean>>) {
        val signature = tabs.joinToString(",") { "${it.first}/${it.second}/${it.third}" }
        if (signature == lastBottomTabsSignature) return
        lastBottomTabsSignature = signature
        AppLog.d(
            this, TAG,
            "Bottom tabs: " + tabs.joinToString(" | ") {
                "id=${it.first.ifEmpty { "(none)" }} desc=${it.second.ifEmpty { "(none)" }} selected=${it.third}"
            }
        )
    }

    // Deliberately looser than isPlausibleTabIconBounds (which sizes the
    // Reels-icon overlay and so has to match the icon itself): a tab's
    // selectable container can be taller than the icon. Still has to sit in
    // the bottom strip, be narrower than half the screen (a tab, not a
    // full-width row), and actually be on screen horizontally -- that last
    // check is what rejects the off-screen adjacent pages described above.
    private fun isPlausibleBottomTabBounds(bounds: Rect): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val metrics = resources.displayMetrics
        if (bounds.left < 0 || bounds.left >= metrics.widthPixels) return false
        if (bounds.width() > metrics.widthPixels * 0.5) return false
        if (bounds.top < metrics.heightPixels * 0.70) return false
        return true
    }

    // Presence in the tree is not the same as being on screen (see
    // findSelectedBottomTab): the adjacent tab pages sit in the tree with
    // bounds parked outside the display. Every id check that decides what the
    // user is looking at goes through this rather than mere id presence.
    private fun hasVisibleNodeById(root: AccessibilityNodeInfo, id: String): Boolean {
        val metrics = resources.displayMetrics
        val matches = root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$id")
        var visible = false
        for (m in matches) {
            val bounds = Rect()
            m.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0 &&
                bounds.left < metrics.widthPixels && bounds.right > 0
            ) {
                visible = true
            }
        }
        matches.forEach { it.recycle() }
        return visible
    }

    private fun categoryBadge(category: TimeCategory) {
        when (category) {
            TimeCategory.REELS -> updateDebugBadge("REELS", "#A855F7")
            TimeCategory.FEED -> updateDebugBadge("FEED", "#3B82F6")
            TimeCategory.DM -> updateDebugBadge("DMs", "#22C55E")
            TimeCategory.STORY -> updateDebugBadge("STORY", "#EC4899")
            TimeCategory.OTHER -> updateDebugBadge("IG · other", "#B0B0B0")
        }
    }

    private fun tickTimeTracking(appId: String, category: TimeCategory) {
        val now = System.currentTimeMillis()
        // A tick can only be attributed to the app it was measured under --
        // switching straight from Instagram to TikTok (or back) without an
        // intervening "neither app foreground" gap must not let the last
        // slice bleed into the new app's total.
        if (lastTimeTickAt != 0L && lastTimeAppId == appId) {
            TimeStats.addTime(this, appId, lastTimeCategory, now - lastTimeTickAt)
            // Throttled internally -- keeps the widget's time donut fresh
            // during long sessions without redrawing it per event.
            StatsWidgetProvider.pushUpdate(this)
        }
        lastTimeTickAt = now
        lastTimeCategory = category
        lastTimeAppId = appId
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
        } else {
            val missMs = System.currentTimeMillis() - lastSeenTabAt
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
                if (!isPagerScroll(event, "clips_viewer_view_pager", "IgLazyColumn")) {
                    // Scrolling the comments sheet (or any inner list drawn
                    // over the player) fires the same event type while the
                    // pager is still full-screen in the tree -- that's what
                    // was kicking the user out of a reel for just reading
                    // comments. Only a real swipe surface counts (the classic
                    // pager, or the Compose IgLazyColumn newer IG swipes
                    // through -- see isPagerScroll).
                    return
                }
                if (now - lastActionTime > COOLDOWN_MS) {
                    AppLog.d(this, TAG, "Swiped past the first reel -- exiting to Home feed")
                    lastActionTime = now
                    val wentToFeed = exitToFeed(root)
                    playExitAnimation(if (wentToFeed) R.string.pill_back_to_feed else R.string.pill_left_reels)
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

    // A TYPE_VIEW_SCROLLED doesn't say WHAT scrolled unless you ask the
    // event's source node. Treating every scroll as a page swipe is what
    // caused the "kicked out of a reel for opening comments" reports: the
    // comments sheet scrolls while the pager is still full-screen in the
    // tree. So only a scroll whose source is one of the known swipe
    // surfaces counts as a real swipe.
    //
    // v1.32: newer, Compose-based Instagram Reels stopped reporting the
    // reel-to-reel swipe from clips_viewer_view_pager and now reports it
    // from an "IgLazyColumn" source instead. The 2226-2224 log showed four
    // such swipes in a row, every one logged as "Ignoring scroll from
    // non-swipe view: IgLazyColumn" while the user was never kicked out --
    // i.e. the v1.30 comment-scroll fix was silently throwing away the
    // actual swipes, so blocking did nothing. IgLazyColumn is therefore
    // accepted as a swipe source too (measured from that log, not guessed
    // -- CLAUDE.md rule 5). If a comments scroll ever turns out to report
    // the same source, the "Ignoring scroll" line below still logs every
    // rejected source, so the exact culprit can be read off a future log
    // and excluded precisely instead of guessed at.
    //
    // Lenient on missing data on purpose: a null source or a source with
    // no id keeps the old treat-as-swipe behavior, so if Instagram ever
    // stops reporting sources the core blocking degrades to its old
    // (over-eager) self instead of silently dying.
    private fun isPagerScroll(event: AccessibilityEvent, vararg swipeSourceIdSuffixes: String): Boolean {
        val source = try { event.source } catch (_: Exception) { null } ?: return true
        val id = try { source.viewIdResourceName } finally { source.recycle() }
        if (id == null) return true
        val suffix = id.substringAfterLast('/')
        val isSwipe = swipeSourceIdSuffixes.any { it == suffix }
        if (!isSwipe) {
            AppLog.d(this, TAG, "Ignoring scroll from non-swipe view: $id")
        }
        return isSwipe
    }

    // ---- Optional feed blocking (opt-in toggle on the Home tab) ----

    // v1.34: touches now pass straight through the feed overlay (see
    // setupFeedOverlay) instead of being swallowed, and it morphs smoothly
    // between two sizes instead of snapping: SMALL covers just the post
    // area (same rect v1.33 used) whenever the stories row is actually
    // found on screen; FULL covers the entire screen the instant it isn't
    // (i.e. the user scrolled down into the feed). Because touches pass
    // through, Instagram keeps genuinely scrolling underneath the whole
    // time -- scrolling still "works" (nothing freezes), there's just
    // nothing to see while doing it, and scrolling back up until the
    // stories row reappears is what shrinks the block back down. Per user
    // request, not a log-driven change.
    private fun handleFeedBlockOverlay(root: AccessibilityNodeInfo) {
        if (!prefs.getBoolean(PrefsKeys.KEY_BLOCK_FEED, false)) {
            hideFeedBlockOverlay("feed blocking toggle off")
            return
        }
        if (lastTimeCategory != TimeCategory.FEED) {
            hideFeedBlockOverlay("not on the feed tab")
            return
        }
        applyFeedOverlaySizing(root)
    }

    // Split out of handleFeedBlockOverlay so the recheck watchdog below can
    // reuse it against a freshly fetched root without depending on
    // lastTimeCategory (which is only updated from the event pipeline).
    // Returns false when the overlay should not be showing at all.
    private fun applyFeedOverlaySizing(root: AccessibilityNodeInfo): Boolean {
        // classifyScreen's FEED verdict isn't enough on its own: the DM
        // inbox (and possibly other overlaid screens) can still classify
        // as FEED (known gap -- Home tab underneath keeps reporting
        // selected in some states, list ids unmeasured). Ask the Home tab
        // node directly, right now, and stand down unless it's genuinely
        // the selected tab -- same guard the old mechanic used.
        val homeNode = findHomeTabNode(root)
        val homeBounds = Rect()
        val homeSelected = homeNode?.isSelected == true
        if (homeNode != null) homeNode.getBoundsInScreen(homeBounds)
        homeNode?.recycle()
        if (!homeSelected) {
            hideFeedBlockOverlay("home tab not selected")
            return false
        }

        val screenHeight = realScreenHeightPx()
        val storiesBounds = findStoriesTrayBounds(root)
        if (storiesBounds != null) {
            // Only trust the Home tab's own bounds as the bottom edge when
            // they look like an actual bottom-nav icon (same sanity check
            // the Reels-icon overlay applies) -- an unplausible bounds read
            // falls back to the full screen height instead of covering the
            // nav bar or clipping short.
            val top = storiesBounds.bottom
            val bottom = if (isPlausibleTabIconBounds(homeBounds)) homeBounds.top else screenHeight
            if (bottom - top < resources.displayMetrics.density * 40) {
                // Bounds don't make sense (e.g. a transient layout pass) --
                // skip this tick rather than morph to a near-zero rect.
                return true
            }
            morphFeedOverlayTo(top, bottom)
        } else {
            // No stories row detected -- either the user has genuinely
            // scrolled it off screen (the normal case this is meant to
            // catch) or the structural match in findStoriesTrayBounds
            // failed to find it (e.g. a future Instagram layout change).
            // Either way, covering the full screen is the safe degrade.
            // Logged (throttled -- this branch is the expected path for as
            // long as someone is genuinely scrolled down, not just a
            // failure case) so a bad match still shows up as "always full
            // screen, never shrinks" in a log instead of silently
            // misbehaving.
            val now = System.currentTimeMillis()
            if (now - lastFeedOverlayFullLogAt > 5000L) {
                lastFeedOverlayFullLogAt = now
                AppLog.d(this, TAG, "Feed overlay: no stories row detected -- covering full screen")
                dumpTopOfScreenCandidates(root)
            }
            morphFeedOverlayTo(0, screenHeight)
        }
        return true
    }

    // v1.37: the overlay used to linger long after leaving the feed (and
    // sometimes never went away) because hiding it only ever happened on
    // the next accessibility event that reached the Instagram branch --
    // and several paths return before that (a root that briefly belongs to
    // systemui, the content-event throttle, or simply no events arriving
    // at all once a screen goes idle). While the block is on screen this
    // re-asks the system what's actually in front of the user a few times
    // a second and hides it the moment that stops being the feed.
    //
    // v1.39: it now takes FEED_OVERLAY_MISS_TOLERANCE consecutive negative
    // reads to actually hide, because a single one proved worthless as
    // evidence -- the 2026-08-22 log has systemui trading places with
    // Instagram constantly (a status-bar peek is enough), so
    // rootInActiveWindow regularly belongs to someone else for one tick
    // while the user is still sitting in the feed. Hiding on the first such
    // read tore the block down within 250ms of every single show, which is
    // exactly the reported "the overlay didn't appear at all". The same
    // consecutive-miss shape VIEWER_MISS_TOLERANCE already uses for the
    // Reels session, and for the same reason. Three misses still puts the
    // block away well under a second after really leaving -- and a read that
    // positively shows Instagram but not the feed still hides immediately,
    // so only genuinely inconclusive reads cost anything.
    private val feedOverlayRecheck = object : Runnable {
        override fun run() {
            if (!feedOverlayShown) return
            var confirmed = false
            var sawInstagram = false
            try {
                val enabled = ::prefs.isInitialized &&
                    prefs.getBoolean(PrefsKeys.KEY_BLOCK_FEED, false) &&
                    prefs.getBoolean(PrefsKeys.enabledKeyFor("instagram"), false)
                if (enabled) {
                    val liveRoot = rootInActiveWindow
                    if (liveRoot != null) {
                        try {
                            if (liveRoot.packageName?.toString() == INSTAGRAM_PACKAGE) {
                                sawInstagram = true
                                confirmed = applyFeedOverlaySizing(liveRoot)
                            }
                        } finally {
                            liveRoot.recycle()
                        }
                    }
                } else {
                    // An explicitly flipped-off toggle is not a transient
                    // read -- take it down immediately.
                    feedOverlayMissCount = FEED_OVERLAY_MISS_TOLERANCE
                }
            } catch (e: Exception) {
                AppLog.w(this@ReelsAccessibilityService, TAG, "Feed overlay recheck failed: ${e.message}")
            }

            if (confirmed) {
                feedOverlayMissCount = 0
                scheduleFeedOverlayRecheck()
                return
            }
            // applyFeedOverlaySizing hides on its own when it can see the
            // user is genuinely off the feed, so only count-and-wait here.
            if (sawInstagram) {
                hideFeedBlockOverlay("recheck: not on the feed")
                return
            }
            feedOverlayMissCount++
            if (feedOverlayMissCount >= FEED_OVERLAY_MISS_TOLERANCE) {
                hideFeedBlockOverlay("recheck: ${feedOverlayMissCount} misses")
            } else {
                scheduleFeedOverlayRecheck()
            }
        }
    }

    private fun scheduleFeedOverlayRecheck() {
        mainHandler.removeCallbacks(feedOverlayRecheck)
        mainHandler.postDelayed(feedOverlayRecheck, FEED_OVERLAY_RECHECK_MS)
    }

    // v1.36: measured from the 2026-08-21 log's "Top-of-screen dump" (see
    // STORIES_ITEM_RESOURCE_ID above) -- the stories row is several
    // "outer_container" nodes side by side, all sharing the same top, in
    // the top quarter of the screen. Requiring at least two at a matching
    // top is what keeps this from false-matching the same generic id
    // showing up as a single unrelated container elsewhere (e.g. the
    // Reels-dismiss screen in that same log).
    private fun findStoriesTrayBounds(root: AccessibilityNodeInfo): Rect? {
        val topLimit = (realScreenHeightPx() * 0.25).toInt()
        val matches = root.findAccessibilityNodeInfosByViewId("$INSTAGRAM_PACKAGE:id/$STORIES_ITEM_RESOURCE_ID")
        try {
            val candidates = mutableListOf<Rect>()
            for (m in matches) {
                val bounds = Rect()
                m.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0 && bounds.top < topLimit) {
                    candidates.add(bounds)
                }
            }
            if (candidates.size < 2) return null
            val top = candidates.minOf { it.top }
            val row = candidates.filter { Math.abs(it.top - top) <= REPOSITION_THRESHOLD_PX }
            if (row.size < 2) return null
            return Rect(0, top, 0, row.maxOf { it.bottom })
        } finally {
            matches.forEach { it.recycle() }
        }
    }

    // Smoothly moves the covered band to [top, bottom] (absolute screen
    // coordinates). The block view is permanently full-screen, so this is
    // a pure translationY + scaleY transform -- the GPU interpolates it
    // without a single layout pass, which is what makes it feel smooth
    // instead of stepping. Scaling is safe here precisely because the block
    // is a flat solid colour; the label is a sibling (not a child), so it
    // slides into place rather than getting stretched.
    //
    // First appearance (nothing shown yet) snaps straight to the right size
    // and fades in instead -- morphing a size out of nothing wouldn't mean
    // anything, and this matches how the app's other overlays introduce
    // themselves (see showOverlayAt).
    private fun morphFeedOverlayTo(top: Int, bottom: Int) {
        val block = feedOverlayBlock ?: return
        val label = feedOverlayLabel
        val current = feedOverlayCurrentRect

        if (current != null &&
            Math.abs(current.top - top) <= REPOSITION_THRESHOLD_PX &&
            Math.abs(current.bottom - bottom) <= REPOSITION_THRESHOLD_PX
        ) {
            // Already where it should be -- but keep the watchdog alive, so
            // a long stretch of "nothing changed" ticks can't leave the
            // block on screen with nothing left to take it down.
            scheduleFeedOverlayRecheck()
            return
        }

        val firstShow = current == null
        feedOverlayMissCount = 0
        if (!feedOverlayShown) {
            feedOverlayShown = true
            Stats.recordBlock(this, "instagram")
            updateDebugBadge("FEED■BLOCKED", "#FF5252")
            AppLog.d(this, TAG, "Feed overlay shown: top=$top bottom=$bottom")
        }
        feedOverlayCurrentRect = Rect(0, top, 0, bottom)

        val screenHeight = realScreenHeightPx().coerceAtLeast(1)
        val scale = ((bottom - top).toFloat() / screenHeight).coerceAtLeast(0f)
        val labelOffset = ((top + bottom) / 2f) - (screenHeight / 2f)

        block.animate().cancel()
        label?.animate()?.cancel()

        if (firstShow) {
            block.translationY = top.toFloat()
            block.scaleY = scale
            block.visibility = View.VISIBLE
            block.alpha = 0f
            block.animate().alpha(1f).setDuration(FADE_MS).start()
            label?.let {
                it.translationY = labelOffset
                it.visibility = View.VISIBLE
                it.alpha = 0f
                it.animate().alpha(1f).setDuration(FADE_MS).start()
            }
        } else {
            block.animate()
                .translationY(top.toFloat())
                .scaleY(scale)
                .setDuration(FEED_OVERLAY_MORPH_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            label?.animate()
                ?.translationY(labelOffset)
                ?.setDuration(FEED_OVERLAY_MORPH_MS)
                ?.setInterpolator(DecelerateInterpolator())
                ?.start()
        }

        scheduleFeedOverlayRecheck()
    }

    // Not animated for the same reason hideOverlay() isn't -- disappearing
    // should feel immediate, not lingering. Only the morph between the two
    // sizes (while still in the feed) is meant to be smooth.
    private fun hideFeedBlockOverlay(reason: String) {
        mainHandler.removeCallbacks(feedOverlayRecheck)
        feedOverlayCurrentRect = null
        feedOverlayMissCount = 0
        val wasShown = feedOverlayShown
        feedOverlayShown = false
        val block = feedOverlayBlock ?: return
        val label = feedOverlayLabel
        block.animate().cancel()
        label?.animate()?.cancel()
        if (block.visibility == View.GONE) return
        block.alpha = 0f
        block.visibility = View.GONE
        label?.alpha = 0f
        label?.visibility = View.GONE
        if (wasShown) {
            // v1.39: every teardown says who ordered it. The block going
            // missing was reported twice without any way to tell from a log
            // whether it never showed or was being killed right after.
            AppLog.d(this, TAG, "Feed overlay hidden ($reason)")
        }
    }

    // ---- TikTok: one video per session ----

    // The For You player is identified by the only two non-obfuscated ids
    // it consistently showed across the user's recon log: "viewpager" +
    // "long_press_layout". The drafts/editor screens in the same log had
    // viewpager WITHOUT long_press_layout, so they stay usable -- which is
    // exactly the requested behavior (block feed scrolling, keep drafts
    // and own-profile videos accessible). Everything else in TikTok's
    // tree is per-build obfuscated ("be1", "hpk"...) and useless to match.
    // Kept strict for ENTRY specifically -- see isTikTokFeedContinuation
    // below for why staying in an already-confirmed feed session uses a
    // looser check.
    private fun isTikTokFeedScreen(root: AccessibilityNodeInfo): Boolean {
        return hasAnyTikTokNodeById(root, "viewpager") && hasAnyTikTokNodeById(root, "long_press_layout")
    }

    // The 2026-07-08 log showed "long_press_layout" isn't actually present
    // for every video -- some had "feed_multi_tag_layout" or
    // "video_sticker_panel_page_rv" instead (likely ads/sponsored or
    // sticker-overlay variants), with "viewpager" the only id common to
    // literally every recon dump while the user was still visibly sitting
    // in the feed. Requiring the strict match on every single event meant
    // hitting VIEWER_MISS_TOLERANCE and resetting the session within a few
    // seconds of any such video, well before a real swipe could ever
    // register -- which is why blocking silently never fired ("tiktok
    // vubec nefunguje"). Once a session is already confirmed via the
    // strict entry check, viewpager alone is enough to keep counting as
    // "still in the feed" -- entry itself stays strict so drafts/editor
    // (viewpager without long_press_layout) still never trigger it.
    private fun isTikTokFeedContinuation(root: AccessibilityNodeInfo): Boolean {
        return hasAnyTikTokNodeById(root, "viewpager")
    }

    private fun hasAnyTikTokNodeById(root: AccessibilityNodeInfo, id: String): Boolean {
        val matches = root.findAccessibilityNodeInfosByViewId("$TIKTOK_PACKAGE:id/$id")
        val found = matches.isNotEmpty()
        matches.forEach { it.recycle() }
        return found
    }

    // TikTok's bottom-nav ids are per-build obfuscated, so the Home tab is
    // located by its accessibility description instead, gated by the same
    // bottom-of-screen plausibility check the Instagram overlay uses --
    // if TikTok localizes/renames the description, this quietly finds
    // nothing and only the icon cover is lost, not the blocking itself.
    private fun updateTikTokOverlay(root: AccessibilityNodeInfo) {
        if (!overlayAdded) return
        val node = findNodeByExactDesc(root, "home", depth = 0)
        if (node != null) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            node.recycle()
            if (isPlausibleTabIconBounds(bounds)) {
                lastSeenTabAt = System.currentTimeMillis()
                showOverlayAt(bounds)
                return
            }
        }
        val missMs = System.currentTimeMillis() - lastSeenTabAt
        if (missMs >= HIDE_GRACE_MS && overlayView?.visibility != View.GONE) {
            hideOverlay()
        }
    }

    private fun handleTikTokSession(root: AccessibilityNodeInfo, event: AccessibilityEvent) {
        val inFeed = if (inTikTokFeed) isTikTokFeedContinuation(root) else isTikTokFeedScreen(root)
        val now = System.currentTimeMillis()

        if (inFeed) {
            tiktokMissCount = 0
            if (!inTikTokFeed) {
                inTikTokFeed = true
                tiktokEnteredAt = now
                AppLog.d(this, TAG, "Entered TikTok feed -- 1 video allowed, next real swipe exits")
                updateDebugBadge("TIKTOK feed", "#7FE3E0")
            } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                if (now - tiktokEnteredAt < ENTRY_GRACE_MS) {
                    AppLog.d(this, TAG, "Ignoring TikTok scroll ${now - tiktokEnteredAt}ms after entry (settle)")
                    return
                }
                if (!isPagerScroll(event, "viewpager")) {
                    // Same comments-sheet protection as the Reels session
                    // above -- only the For You pager scrolling is a swipe.
                    return
                }
                if (now - lastActionTime > COOLDOWN_MS) {
                    AppLog.d(this, TAG, "Swiped past the first TikTok video -- exiting to Inbox")
                    lastActionTime = now
                    exitTikTokToInbox(root)
                    playExitAnimation(R.string.pill_left_feed)
                    inTikTokFeed = false
                    updateDebugBadge("TIKTOK→EXIT", "#FF5252")
                }
            }
        } else if (inTikTokFeed) {
            tiktokMissCount++
            if (tiktokMissCount >= VIEWER_MISS_TOLERANCE) {
                inTikTokFeed = false
                tiktokMissCount = 0
                AppLog.d(this, TAG, "Left TikTok feed -- session reset")
                updateDebugBadge("TIKTOK", "#B0B0B0")
            }
        } else {
            updateDebugBadge("TIKTOK", "#B0B0B0")
        }
    }

    // Mirrors exitToFeed's approach for Instagram: land somewhere useful
    // instead of a plain back-press, which could pop to whatever screen
    // was underneath (including right back into another video). TikTok's
    // bottom-nav ids are per-build obfuscated same as the Home tab (see
    // updateTikTokOverlay above), so Inbox is also located by content
    // description, falling back to BACK if that description ever changes.
    private fun exitTikTokToInbox(root: AccessibilityNodeInfo): Boolean {
        Stats.recordBlock(this, "tiktok")
        val inboxNode = findNodeByExactDesc(root, "inbox", depth = 0)
        val clicked = inboxNode?.let { clickNodeOrAncestor(it) } ?: false
        inboxNode?.recycle()
        if (!clicked) {
            AppLog.d(this, TAG, "TikTok Inbox tab not found -- falling back to back button")
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        return clicked
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
        Stats.recordBlock(this, "instagram")
        val homeNode = findHomeTabNode(root)
        val clicked = homeNode?.let { clickNodeOrAncestor(it) } ?: false
        homeNode?.recycle()
        if (!clicked) {
            AppLog.d(this, TAG, "Home tab not found -- falling back to back button")
            dumpBottomNavCandidates(root)
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        return clicked
    }

    // "Home tab not found" has shown up repeatedly across logs, forcing
    // the less reliable back-button fallback (which doesn't always land on
    // the feed). Guessing a replacement resource id without data would be
    // exactly the kind of blind Instagram-UI edit CLAUDE.md rule 5 warns
    // against -- this just logs what's actually clickable near the bottom
    // of the screen at the moment the lookup failed, so the real id can be
    // read off a future log instead of guessed at.
    private fun dumpBottomNavCandidates(root: AccessibilityNodeInfo) {
        try {
            val screenHeight = resources.displayMetrics.heightPixels
            val found = mutableListOf<String>()
            collectBottomNavCandidates(root, screenHeight, found, depth = 0)
            if (found.isEmpty()) {
                AppLog.d(this, TAG, "Bottom nav dump: no clickable nodes found in bottom 20% of screen")
            } else {
                AppLog.d(this, TAG, "Bottom nav dump: ${found.joinToString(" | ")}")
            }
        } catch (e: Exception) {
            AppLog.w(this, TAG, "Bottom nav dump failed: ${e.message}")
        }
    }

    private var lastReconAt = 0L
    private var lastReconSignature = ""

    // DM/Story screens show 0 in the time chart (and TikTok has no
    // detection at all) because their resource ids have never actually
    // been observed -- this collects the distinct view ids present on an
    // unrecognized screen so classification can be built from measured
    // data instead of guesses (CLAUDE.md rule 5). Throttled hard: at most
    // one dump per 5s, and only when the id set actually changed.
    private fun reconDumpScreenIds(root: AccessibilityNodeInfo, label: String) {
        val now = System.currentTimeMillis()
        if (now - lastReconAt < 5000L) return
        try {
            val ids = sortedSetOf<String>()
            collectViewIds(root, ids, depth = 0)
            if (ids.isEmpty()) return
            val signature = ids.joinToString(",")
            if (signature == lastReconSignature) return
            lastReconAt = now
            lastReconSignature = signature
            AppLog.d(this, TAG, "$label ids: $signature")
        } catch (e: Exception) {
            AppLog.w(this, TAG, "Recon dump failed: ${e.message}")
        }
    }

    private fun collectViewIds(node: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int) {
        if (depth > 14 || out.size >= 25) return
        node.viewIdResourceName?.let { out.add(it.substringAfterLast('/')) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectViewIds(child, out, depth + 1)
            child.recycle()
        }
    }

    private fun collectBottomNavCandidates(
        node: AccessibilityNodeInfo,
        screenHeight: Int,
        out: MutableList<String>,
        depth: Int
    ) {
        if (depth > 25 || out.size >= 10) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (node.isClickable && bounds.top >= screenHeight * 0.80) {
            val id = node.viewIdResourceName ?: "(no id)"
            val desc = node.contentDescription?.toString() ?: "(no desc)"
            out.add("id=$id desc=$desc bounds=$bounds")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectBottomNavCandidates(child, screenHeight, out, depth + 1)
            child.recycle()
        }
    }

    // v1.34/v1.35 follow-up: this is what found STORIES_ITEM_RESOURCE_ID in
    // the first place -- a 2026-08-21 "Top-of-screen dump" showed the old
    // "stories_tray" id never matched at all, but did show several
    // "outer_container" nodes side by side under the top app bar (see
    // findStoriesTrayBounds). Kept in place (not just a one-off diagnostic)
    // as the fallback path for whenever findStoriesTrayBounds itself comes
    // up empty -- a future Instagram layout change would show up here the
    // same way. Unlike collectBottomNavCandidates above, this doesn't
    // filter to isClickable -- a stories-row-like container may not be
    // clickable even though the avatars inside it are -- so it dumps every
    // node (with its class, since Compose-based views often carry no
    // resource id at all) sitting in the top 20% of the screen instead.
    private fun dumpTopOfScreenCandidates(root: AccessibilityNodeInfo) {
        try {
            val screenHeight = resources.displayMetrics.heightPixels
            val found = mutableListOf<String>()
            collectTopOfScreenCandidates(root, screenHeight, found, depth = 0)
            if (found.isEmpty()) {
                AppLog.d(this, TAG, "Top-of-screen dump: no nodes found in top 20% of screen")
            } else {
                AppLog.d(this, TAG, "Top-of-screen dump: ${found.joinToString(" | ")}")
            }
        } catch (e: Exception) {
            AppLog.w(this, TAG, "Top-of-screen dump failed: ${e.message}")
        }
    }

    private fun collectTopOfScreenCandidates(
        node: AccessibilityNodeInfo,
        screenHeight: Int,
        out: MutableList<String>,
        depth: Int
    ) {
        if (depth > 20 || out.size >= 20) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.height() > 0 && bounds.top in 0 until (screenHeight * 0.20).toInt()) {
            val id = node.viewIdResourceName ?: "(no id)"
            out.add("id=$id class=${node.className} bounds=$bounds")
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTopOfScreenCandidates(child, screenHeight, out, depth + 1)
            child.recycle()
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
        if (instance === this) instance = null
        teardownOverlays()
        colorSampleExecutor.shutdownNow()
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
