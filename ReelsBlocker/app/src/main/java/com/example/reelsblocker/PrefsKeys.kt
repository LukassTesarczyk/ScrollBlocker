package com.example.reelsblocker

import android.content.Context

object PrefsKeys {
    const val PREFS_NAME = "reels_blocker_prefs"

    // Legacy key -- kept as-is so existing installs don't lose their
    // Run/Stop state. This is Instagram's enabled flag specifically.
    const val KEY_ENABLED = "enabled"
    const val KEY_HUB_ORDER = "hub_order"
    const val KEY_DEBUG_OVERLAY = "debug_overlay_enabled"
    // Empty string = PIN lock disabled. Stored as plain text on purpose:
    // this is a self-control speed bump against impulsively disabling the
    // blocker, not a security boundary against another person.
    const val KEY_PIN = "pin_code"

    // Order here is the default hub layout for fresh installs --
    // Instagram in the middle slot, since it's the only one that's
    // actually implemented.
    val KNOWN_APPS = listOf("tiktok", "instagram", "snapchat")

    fun enabledKeyFor(appId: String): String =
        if (appId == "instagram") KEY_ENABLED else "enabled_$appId"

    fun displayNameFor(appId: String): String = when (appId) {
        "instagram" -> "Instagram"
        "tiktok" -> "TikTok"
        "snapchat" -> "Snapchat"
        else -> appId
    }

    fun isImplemented(appId: String): Boolean = appId == "instagram"

    fun loadHubOrder(context: Context): MutableList<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_HUB_ORDER, null)
        val order = if (saved.isNullOrBlank()) {
            KNOWN_APPS.toMutableList()
        } else {
            saved.split(",").filter { it in KNOWN_APPS }.toMutableList()
        }
        // Guard against a stale/partial saved order missing an app.
        for (app in KNOWN_APPS) {
            if (app !in order) order.add(app)
        }
        return order
    }

    fun saveHubOrder(context: Context, order: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HUB_ORDER, order.joinToString(",")).apply()
    }
}
