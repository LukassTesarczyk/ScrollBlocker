package com.example.reelsblocker

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvPauseStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var overviewContainer: LinearLayout
    private lateinit var setupContainer: LinearLayout
    private lateinit var btnTabOverview: Button
    private lateinit var btnTabSetup: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
        tvPauseStatus = findViewById(R.id.tvPauseStatus)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        overviewContainer = findViewById(R.id.overviewContainer)
        setupContainer = findViewById(R.id.setupContainer)
        btnTabOverview = findViewById(R.id.btnTabOverview)
        btnTabSetup = findViewById(R.id.btnTabSetup)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.tvVersion).text = "v$versionName"

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOpenBatterySettings).setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        }

        findViewById<Button>(R.id.btnOpenOtherPermissions).setOnClickListener {
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

        btnTabOverview.setOnClickListener { showTab(overview = true) }
        btnTabSetup.setOnClickListener { showTab(overview = false) }

        findViewById<TextView>(R.id.tvInstructions).text = """
            1. Tap "Open Accessibility Settings" and enable "Reels Blocker Service".
               If the switch won't stay on: App info -> ⋮ menu (top right) ->
               "Allow restricted settings" (Android blocks this for apps
               installed outside the Play Store until you unlock it once).
            2. Tap "Open App Battery Settings" and set battery usage to
               "No restrictions" and enable Autostart (Xiaomi/HyperOS specific).
            3. Tap "Open App Info (Other permissions)" and allow
               "Display pop-up windows while running in the background" --
               needed for the icon-cover overlay.
            4. Use Run/Stop above to pause the blocking logic anytime
               without touching system settings.
        """.trimIndent()

        showTab(overview = true)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        renderStats()
    }

    private fun showTab(overview: Boolean) {
        overviewContainer.visibility = if (overview) View.VISIBLE else View.GONE
        setupContainer.visibility = if (overview) View.GONE else View.VISIBLE
        btnTabOverview.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(if (overview) "#26A69A" else "#3A3A3A"))
        btnTabSetup.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.parseColor(if (overview) "#3A3A3A" else "#26A69A"))
        if (overview) renderStats()
    }

    private fun refreshStatus() {
        val enabled = prefs.getBoolean(PrefsKeys.KEY_ENABLED, true)
        tvPauseStatus.text = if (enabled) "Running" else "Stopped"

        val runBtn = findViewById<Button>(R.id.btnRun)
        val stopBtn = findViewById<Button>(R.id.btnStop)
        runBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (enabled) "#26A69A" else "#3A3A3A")
        )
        stopBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(
            Color.parseColor(if (enabled) "#3A3A3A" else "#C62828")
        )

        val serviceOn = isAccessibilityServiceEnabled()
        tvServiceStatus.text = if (serviceOn) {
            "Accessibility service enabled"
        } else {
            "Accessibility service NOT enabled -- see Setup tab"
        }
    }

    private fun renderStats() {
        findViewById<TextView>(R.id.tvTotalBlocked).text = Stats.total(this).toString()
        findViewById<TextView>(R.id.tvTodayBlocked).text = Stats.today(this).toString()
        renderChart(Stats.last7Days(this))
    }

    private fun renderChart(data: List<Pair<String, Int>>) {
        val container = findViewById<LinearLayout>(R.id.barsContainer)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val maxCount = (data.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
        val maxBarHeightDp = 90

        for ((label, count) in data) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
                ).apply {
                    marginStart = (4 * density).toInt()
                    marginEnd = (4 * density).toInt()
                }
            }

            val countText = TextView(this).apply {
                text = count.toString()
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#808080"))
            }

            val barHeightDp = if (count == 0) 4 else (10 + (count.toFloat() / maxCount) * maxBarHeightDp).toInt()
            val bar = View(this).apply {
                setBackgroundColor(Color.parseColor(if (count == 0) "#3A3A3A" else "#26A69A"))
                layoutParams = LinearLayout.LayoutParams(
                    (26 * density).toInt(),
                    (barHeightDp * density).toInt()
                )
            }

            val labelText = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#808080"))
            }

            column.addView(countText)
            column.addView(bar)
            column.addView(labelText)
            container.addView(column)
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
