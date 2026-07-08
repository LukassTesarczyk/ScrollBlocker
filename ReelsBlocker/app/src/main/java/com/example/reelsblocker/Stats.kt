package com.example.reelsblocker

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tracks how many times the "one [reel/video] per session" rule kicked in,
 * scoped per app id so Instagram and TikTok each keep their own total and
 * daily history. Backed by the same SharedPreferences file as everything
 * else so the accessibility service (which never touches the UI directly)
 * and MainActivity (which reads it to draw the chart) stay in sync.
 */
object Stats {
    // Pre-v1.28 key, back when only Instagram was tracked and counts were
    // global -- read as a fallback so upgrading doesn't silently zero out
    // history that's already on the device.
    private const val LEGACY_KEY_TOTAL = "stat_total_blocked"
    private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayLabelFormat = SimpleDateFormat("EEE", Locale.getDefault())

    private fun totalKey(appId: String) = "stat_total_$appId"
    private fun dayKey(appId: String, date: Date) = "stat_day_${appId}_${dayKeyFormat.format(date)}"

    fun recordBlock(context: Context, appId: String) {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(totalKey(appId), total(context, appId) + 1)
        val dk = dayKey(appId, Date())
        editor.putInt(dk, prefs.getInt(dk, 0) + 1)
        editor.apply()
        StatsWidgetProvider.pushUpdate(context, force = true)
        BarsWidgetProvider.pushUpdate(context, force = true)
    }

    fun total(context: Context, appId: String): Int {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getInt(totalKey(appId), -1)
        if (stored >= 0) return stored
        return if (appId == "instagram") prefs.getInt(LEGACY_KEY_TOTAL, 0) else 0
    }

    fun today(context: Context, appId: String): Int {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(dayKey(appId, Date()), 0)
    }

    /** Oldest to newest, always exactly 7 entries (today included, last). */
    fun last7Days(context: Context, appId: String): List<Pair<String, Int>> {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        val result = mutableListOf<Pair<String, Int>>()
        repeat(7) {
            val label = dayLabelFormat.format(cal.time)
            result.add(label to prefs.getInt(dayKey(appId, cal.time), 0))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }
}
