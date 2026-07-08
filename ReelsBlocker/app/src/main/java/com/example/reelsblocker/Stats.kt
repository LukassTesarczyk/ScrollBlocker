package com.example.reelsblocker

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Tracks how many times the "one reel per session" rule kicked in.
 * Backed by the same SharedPreferences file as everything else so the
 * accessibility service (which never touches the UI directly) and
 * MainActivity (which reads it to draw the chart) stay in sync.
 */
object Stats {
    private const val KEY_TOTAL = "stat_total_blocked"
    private val dayKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayLabelFormat = SimpleDateFormat("EEE", Locale.getDefault())

    fun recordBlock(context: Context) {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putInt(KEY_TOTAL, prefs.getInt(KEY_TOTAL, 0) + 1)
        val dayKey = "stat_day_${dayKeyFormat.format(Date())}"
        editor.putInt(dayKey, prefs.getInt(dayKey, 0) + 1)
        editor.apply()
        StatsWidgetProvider.pushUpdate(context, force = true)
    }

    fun total(context: Context): Int {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_TOTAL, 0)
    }

    fun today(context: Context): Int {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt("stat_day_${dayKeyFormat.format(Date())}", 0)
    }

    /** Oldest to newest, always exactly 7 entries (today included, last). */
    fun last7Days(context: Context): List<Pair<String, Int>> {
        val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, Context.MODE_PRIVATE)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -6)
        val result = mutableListOf<Pair<String, Int>>()
        repeat(7) {
            val dateKey = dayKeyFormat.format(cal.time)
            val label = dayLabelFormat.format(cal.time)
            result.add(label to prefs.getInt("stat_day_$dateKey", 0))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return result
    }
}
