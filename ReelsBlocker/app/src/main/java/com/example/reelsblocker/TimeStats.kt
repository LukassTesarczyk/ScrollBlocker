package com.example.reelsblocker

import android.content.Context

/**
 * All-time cumulative milliseconds spent per screen category while
 * Instagram is in the foreground. Same SharedPreferences file as Stats --
 * the accessibility service writes, MainActivity reads.
 *
 * Every category here is backed by resource ids actually observed in the
 * user's own recon logs (v1.26 round), not guessed: DM threads show
 * thread_fragment_container/message_list, Stories show reel_viewer_root,
 * the feed shows row_feed_* rows, and Reels shows clips_viewer_view_pager.
 */
enum class TimeCategory(val prefKey: String) {
    REELS("time_reels"),
    FEED("time_feed"),
    DM("time_dm"),
    STORY("time_story"),
    OTHER("time_other")
}

object TimeStats {
    // Gaps between consecutive accessibility events (screen off, app
    // backgrounded, user idle) must not get counted as "time spent" --
    // capping each tick's delta keeps a long gap from being misattributed
    // as one huge slice of whatever category was active before the gap.
    private const val MAX_TICK_MS = 2000L

    fun addTime(context: Context, category: TimeCategory, deltaMs: Long) {
        if (deltaMs <= 0) return
        val clamped = deltaMs.coerceAtMost(MAX_TICK_MS)
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val key = category.prefKey
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + clamped).apply()
    }

    fun get(context: Context, category: TimeCategory): Long {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(category.prefKey, 0L)
    }

    fun all(context: Context): Map<TimeCategory, Long> =
        TimeCategory.values().associateWith { get(context, it) }
}
