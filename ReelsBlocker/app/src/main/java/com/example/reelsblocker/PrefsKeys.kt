package com.example.reelsblocker

object PrefsKeys {
    const val PREFS_NAME = "reels_blocker_prefs"

    // Legacy key -- kept as-is so existing installs don't lose their
    // Run/Stop state. This is Instagram's enabled flag specifically.
    const val KEY_ENABLED = "enabled"

    // Known app slots in the bottom hub. Only "instagram" has real
    // detection behind it right now -- the others just persist a toggle
    // for when their detection is implemented.
    val KNOWN_APPS = listOf("instagram", "tiktok", "snapchat")

    fun enabledKeyFor(appId: String): String =
        if (appId == "instagram") KEY_ENABLED else "enabled_$appId"

    fun displayNameFor(appId: String): String = when (appId) {
        "instagram" -> "Instagram"
        "tiktok" -> "TikTok"
        "snapchat" -> "Snapchat"
        else -> appId
    }

    fun isImplemented(appId: String): Boolean = appId == "instagram"
}
