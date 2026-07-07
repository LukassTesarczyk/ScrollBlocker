package com.example.reelsblocker

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var tvServiceStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
        tvStatus = findViewById(R.id.tvPauseStatus)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOpenBatterySettings).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnOpenOtherPermissions).setOnClickListener {
            // On Xiaomi/HyperOS, "Display pop-up windows while running in
            // the background" lives under App info -> Other permissions.
            // Without it, drawing the icon-cover overlay can fail.
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnRun).setOnClickListener {
            prefs.edit().putBoolean(PrefsKeys.KEY_ENABLED, true).apply()
            refreshStatus()
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            prefs.edit().putBoolean(PrefsKeys.KEY_ENABLED, false).apply()
            refreshStatus()
        }

        findViewById<TextView>(R.id.tvInstructions).text = """
            1. Tap "Open Accessibility Settings" and enable "Reels Blocker Service".
               If the switch won't stay on: App info -> ⋮ menu (top right) ->
               "Allow restricted settings" (Android blocks this for apps
               installed outside the Play Store until you unlock it once).
            2. Tap "Open App Battery Settings" and set battery usage to
               "No restrictions" and enable Autostart (Xiaomi/HyperOS specific).
            3. Use Run/Stop below to pause the blocking logic anytime without
               touching system settings.
        """.trimIndent()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = prefs.getBoolean(PrefsKeys.KEY_ENABLED, true)
        tvStatus.text = if (enabled) "Status: RUNNING" else "Status: STOPPED (paused)"

        val serviceOn = isAccessibilityServiceEnabled()
        tvServiceStatus.text = if (serviceOn) {
            "Accessibility service: enabled in system settings"
        } else {
            "Accessibility service: NOT enabled -- tap 'Open Accessibility Settings' below"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponent = "$packageName/${ReelsAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponent, ignoreCase = true)) return true
        }
        return false
    }
}
