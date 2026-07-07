package com.example.reelsblocker

import android.content.Context

/**
 * All-time cumulative milliseconds spent per screen category while
 * Instagram is in the foreground and blocking is on. Same SharedPreferences
 * file as Stats -- the accessibility service writes, MainActivity reads.
 *
 * Only FEED is currently classified with real confidence (it reuses the
 * already-validated home-tab resource id lookup). DM and STORY have no
 * validated resource ids yet -- until a log pins those down, that time
 * falls into OTHER instead of being guessed at and possibly mislabeled.
 */
enum class TimeCategory(val prefKey: String) {
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
