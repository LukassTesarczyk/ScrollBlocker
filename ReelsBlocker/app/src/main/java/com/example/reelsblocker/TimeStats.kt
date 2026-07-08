package com.example.reelsblocker

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Milliseconds spent per screen category while a given app is in the
 * foreground, scoped per app id and per calendar day -- resets naturally at
 * midnight (a new day just starts reading/writing a fresh, absent key) and
 * never mixes one app's numbers into another's, same pattern Stats.kt
 * already uses for the blocked-count bar chart.
 *
 * Every Instagram category here is backed by resource ids actually observed
 * in the user's own recon logs (v1.26 round), not guessed: DM threads show
 * thread_fragment_container/message_list, Stories show reel_viewer_root,
 * the feed shows row_feed_* rows, and Reels shows clips_viewer_view_pager.
 * TikTok only ever reports FEED/OTHER -- it has no separate DM/Story/Reels
 * screens in this app's model.
 */
enum class TimeCategory(val prefKey: String) {
    REELS("reels"),
    FEED("feed"),
    DM("dm"),
    STORY("story"),
    OTHER("other")
}

object TimeStats {
    // Gaps between consecutive accessibility events (screen off, app
    // backgrounded, user idle) must not get counted as "time spent" --
    // capping each tick's delta keeps a long gap from being misattributed
    // as one huge slice of whatever category was active before the gap.
    private const val MAX_TICK_MS = 2000L
    private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private fun key(appId: String, category: TimeCategory, day: String): String =
        "time_${appId}_${category.prefKey}_$day"

    fun addTime(context: Context, appId: String, category: TimeCategory, deltaMs: Long) {
        if (deltaMs <= 0) return
        val clamped = deltaMs.coerceAtMost(MAX_TICK_MS)
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val k = key(appId, category, dayKeyFormat.format(Date()))
        prefs.edit().putLong(k, prefs.getLong(k, 0L) + clamped).apply()
    }

    /** Today's per-category milliseconds for one app -- resets at midnight. */
    fun today(context: Context, appId: String): Map<TimeCategory, Long> {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val day = dayKeyFormat.format(Date())
        return TimeCategory.values().associateWith { prefs.getLong(key(appId, it, day), 0L) }
    }
}
