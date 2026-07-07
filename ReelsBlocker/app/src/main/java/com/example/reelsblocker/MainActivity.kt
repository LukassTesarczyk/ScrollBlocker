package com.example.reelsblocker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvPauseStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvSelectedApp: TextView
    private lateinit var tvNotImplementedNote: TextView
    private lateinit var overviewContainer: LinearLayout
    private lateinit var setupContainer: LinearLayout
    private lateinit var debugContainer: LinearLayout
    private lateinit var btnTabOverview: Button
    private lateinit var btnTabSetup: Button
    private lateinit var btnTabDebug: Button

    private var selectedApp: String = "instagram"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PrefsKeys.PREFS_NAME, MODE_PRIVATE)
        tvPauseStatus = findViewById(R.id.tvPauseStatus)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvSelectedApp = findViewById(R.id.tvSelectedApp)
        tvNotImplementedNote = findViewById(R.id.tvNotImplementedNote)
        overviewContainer = findViewById(R.id.overviewContainer)
        setupContainer = findViewById(R.id.setupContainer)
        debugContainer = findViewById(R.id.debugContainer)
        btnTabOverview = findViewById(R.id.btnTabOverview)
        btnTabSetup = findViewById(R.id.btnTabSetup)
        btnTabDebug = findViewById(R.id.btnTabDebug)

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
            openAppInfo()
        }
        findViewById<Button>(R.id.btnOpenOtherPermissions).setOnClickListener {
            openAppInfo()
        }

        findViewById<Button>(R.id.btnRun).setOnClickListener {
            prefs.edit().putBoolean(PrefsKeys.enabledKeyFor(selectedApp), true).apply()
            refreshStatus()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            prefs.edit().putBoolean(PrefsKeys.enabledKeyFor(selectedApp), false).apply()
            refreshStatus()
        }

        btnTabOverview.setOnClickListener { showTab("overview") }
        btnTabSetup.setOnClickListener { showTab("setup") }
        btnTabDebug.setOnClickListener { showTab("debug") }

        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener { renderLog() }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Reels Blocker log", AppLog.readAll(this)))
            Toast.makeText(this, "Log zkopírován", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AppLog.clear(this)
            renderLog()
        }

        setupHub()

        findViewById<TextView>(R.id.tvInstructions).text = """
            1. Tap "Open Accessibility Settings" and enable "Reels Blocker Service".
               If the switch won't stay on: App info -> ⋮ menu (top right) ->
               "Allow restricted settings".
            2. Tap "Open App Battery Settings" and set battery usage to
               "No restrictions" and enable Autostart (Xiaomi/HyperOS specific).
            3. Same screen -> Other permissions -> allow "Display pop-up
               windows while running in the background".
            4. Use Run/Stop above to pause blocking anytime, per app.
            5. Debug tab has a log if something misbehaves -- copy it and
               send it over so it can be fixed precisely.
        """.trimIndent()

        showTab("overview")
        selectApp("instagram")
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (overviewContainer.visibility == View.VISIBLE) renderStats()
        if (debugContainer.visibility == View.VISIBLE) renderLog()
    }

    private fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = android.net.Uri.parse("package:$packageName")
        startActivity(intent)
    }

    // ---- App hub ----

    private fun setupHub() {
        setupHubIcon(R.id.hubInstagramIcon, "#C13584")
        setupHubIcon(R.id.hubTiktokIcon, "#000000")
        setupHubIcon(R.id.hubSnapchatIcon, "#FFFC00")

        findViewById<LinearLayout>(R.id.hubInstagram).setOnClickListener { selectApp("instagram") }
        findViewById<LinearLayout>(R.id.hubTiktok).setOnClickListener { selectApp("tiktok") }
        findViewById<LinearLayout>(R.id.hubSnapchat).setOnClickListener { selectApp("snapchat") }
    }

    private fun setupHubIcon(viewId: Int, colorHex: String) {
        val view = findViewById<TextView>(viewId)
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(colorHex))
        }
        view.background = drawable
    }

    private fun selectApp(appId: String) {
        selectedApp = appId
        tvSelectedApp.text = PrefsKeys.displayNameFor(appId)

        val implemented = PrefsKeys.isImplemented(appId)
        tvNotImplementedNote.visibility = if (implemented) View.GONE else View.VISIBLE
        if (!implemented) {
            tvNotImplementedNote.text =
                "${PrefsKeys.displayNameFor(appId)} zatím nemá implementovanou detekci -- " +
                    "Run/Stop se ukládá pro až to bude hotové, ale zatím nic neblokuje ani nesleduje."
        }

        // Dim the unselected hub icons a bit so it's clear which is active.
        listOf("instagram" to R.id.hubInstagram, "tiktok" to R.id.hubTiktok, "snapchat" to R.id.hubSnapchat)
            .forEach { (id, viewId) ->
                findViewById<LinearLayout>(viewId).alpha = if (id == appId) 1f else 0.45f
            }

        refreshStatus()
        renderStats()
    }

    // ---- Tabs ----

    private fun showTab(tab: String) {
        overviewContainer.visibility = if (tab == "overview") View.VISIBLE else View.GONE
        setupContainer.visibility = if (tab == "setup") View.VISIBLE else View.GONE
        debugContainer.visibility = if (tab == "debug") View.VISIBLE else View.GONE

        val active = "#26A69A"
        val inactive = "#3A3A3A"
        btnTabOverview.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (tab == "overview") active else inactive))
        btnTabSetup.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (tab == "setup") active else inactive))
        btnTabDebug.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (tab == "debug") active else inactive))

        if (tab == "overview") renderStats()
        if (tab == "debug") renderLog()
    }

    // ---- Status / stats / log ----

    private fun refreshStatus() {
        val enabled = prefs.getBoolean(PrefsKeys.enabledKeyFor(selectedApp), true)
        tvPauseStatus.text = if (enabled) "Running" else "Stopped"

        val runBtn = findViewById<Button>(R.id.btnRun)
        val stopBtn = findViewById<Button>(R.id.btnStop)
        runBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (enabled) "#26A69A" else "#3A3A3A"))
        stopBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (enabled) "#3A3A3A" else "#C62828"))

        if (selectedApp == "instagram") {
            val serviceOn = isAccessibilityServiceEnabled()
            tvServiceStatus.text = if (serviceOn) {
                "Accessibility service enabled"
            } else {
                "Accessibility service NOT enabled -- see Setup tab"
            }
        } else {
            tvServiceStatus.text = "Detekce zatím neimplementována"
        }
    }

    private fun renderStats() {
        if (selectedApp != "instagram") {
            findViewById<TextView>(R.id.tvTotalBlocked).text = "--"
            findViewById<TextView>(R.id.tvTodayBlocked).text = "--"
            findViewById<LinearLayout>(R.id.barsContainer).removeAllViews()
            return
        }
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

    private fun renderLog() {
        findViewById<TextView>(R.id.tvLog).text = AppLog.readAll(this)
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
