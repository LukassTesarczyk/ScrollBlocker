package com.example.reelsblocker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var tvPauseStatus: TextView
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvSelectedApp: TextView
    private lateinit var tvNotImplementedNote: TextView
    private lateinit var overviewContainer: LinearLayout
    private lateinit var setupContainer: LinearLayout
    private lateinit var debugContainer: LinearLayout
    private lateinit var btnOpenMenu: TextView
    private lateinit var drawerScrim: View
    private lateinit var drawerPanel: LinearLayout
    private lateinit var languagesContainer: LinearLayout
    private lateinit var languageList: LinearLayout
    private lateinit var btnMenuOverview: View
    private lateinit var btnMenuSetup: View
    private lateinit var btnMenuDebug: View
    private lateinit var btnMenuLanguages: View
    private lateinit var hubSheetScrim: View
    private lateinit var hubActionSheet: LinearLayout
    private lateinit var tvHubSheetTitle: TextView
    private lateinit var btnHubStats: TextView
    private lateinit var btnHubMove: TextView
    private lateinit var btnHubToggleRun: TextView

    private var selectedApp: String = "instagram"
    private var drawerOpen = false
    private var hubSheetOpen = false
    private var hubSheetSlot: Int? = null

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
        btnOpenMenu = findViewById(R.id.btnOpenMenu)
        drawerScrim = findViewById(R.id.drawerScrim)
        drawerPanel = findViewById(R.id.drawerPanel)
        languagesContainer = findViewById(R.id.languagesContainer)
        languageList = findViewById(R.id.languageList)
        btnMenuOverview = findViewById(R.id.btnMenuOverview)
        btnMenuSetup = findViewById(R.id.btnMenuSetup)
        btnMenuDebug = findViewById(R.id.btnMenuDebug)
        btnMenuLanguages = findViewById(R.id.btnMenuLanguages)
        hubSheetScrim = findViewById(R.id.hubSheetScrim)
        hubActionSheet = findViewById(R.id.hubActionSheet)
        tvHubSheetTitle = findViewById(R.id.tvHubSheetTitle)
        btnHubStats = findViewById(R.id.btnHubStats)
        btnHubMove = findViewById(R.id.btnHubMove)
        btnHubToggleRun = findViewById(R.id.btnHubToggleRun)

        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.version_format, versionName)

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
            ensureNotificationPermission()
            prefs.edit().putBoolean(PrefsKeys.enabledKeyFor(selectedApp), true).apply()
            refreshStatus()
        }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            prefs.edit().putBoolean(PrefsKeys.enabledKeyFor(selectedApp), false).apply()
            refreshStatus()
        }

        btnOpenMenu.setOnClickListener { if (drawerOpen) closeDrawer() else openDrawer() }
        drawerScrim.setOnClickListener { closeDrawer() }
        btnMenuOverview.setOnClickListener { showTab("overview"); closeDrawer() }
        btnMenuSetup.setOnClickListener { showTab("setup"); closeDrawer() }
        btnMenuDebug.setOnClickListener { showTab("debug"); closeDrawer() }
        btnMenuLanguages.setOnClickListener { showTab("languages"); closeDrawer() }

        hubSheetScrim.setOnClickListener { closeHubSheet() }
        btnHubStats.setOnClickListener {
            val slot = hubSheetSlot
            closeHubSheet()
            if (slot != null) {
                selectApp(hubOrder[slot])
                showTab("overview")
            }
        }
        btnHubMove.setOnClickListener {
            val slot = hubSheetSlot
            closeHubSheet()
            if (slot != null) enterReorderMode(slot)
        }
        btnHubToggleRun.setOnClickListener {
            val slot = hubSheetSlot
            closeHubSheet()
            if (slot != null) {
                val appId = hubOrder[slot]
                val enabled = prefs.getBoolean(PrefsKeys.enabledKeyFor(appId), false)
                if (!enabled) ensureNotificationPermission()
                prefs.edit().putBoolean(PrefsKeys.enabledKeyFor(appId), !enabled).apply()
                if (appId == selectedApp) refreshStatus()
            }
        }

        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener { renderLog() }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), AppLog.readAll(this)))
            Toast.makeText(this, getString(R.string.log_copied_toast), Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            AppLog.clear(this)
            renderLog()
        }
        findViewById<Button>(R.id.btnDownloadLog).setOnClickListener { exportLog() }

        setupHub()
        setupLanguages()

        findViewById<TextView>(R.id.tvInstructions).text = getString(R.string.setup_instructions)

        showTab("overview")
        selectApp("instagram")
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (overviewContainer.visibility == View.VISIBLE) renderStats()
        if (debugContainer.visibility == View.VISIBLE) renderLog()
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openAppInfo() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.data = android.net.Uri.parse("package:$packageName")
        startActivity(intent)
    }

    private val hubSlotIds = listOf(R.id.hubInstagram, R.id.hubTiktok, R.id.hubSnapchat)
    private val hubIconIds = listOf(R.id.hubInstagramIcon, R.id.hubTiktokIcon, R.id.hubSnapchatIcon)
    private val hubLabelIds = listOf(R.id.hubInstagramLabel, R.id.hubTiktokLabel, R.id.hubSnapchatLabel)
    private val hubBadgeIds = listOf(R.id.hubInstagramBadge, R.id.hubTiktokBadge, R.id.hubSnapchatBadge)

    private lateinit var hubOrder: MutableList<String>
    private var reorderingSlot: Int? = null

    private fun setupHub() {
        hubOrder = PrefsKeys.loadHubOrder(this)
        renderHubIcons()

        for (i in hubSlotIds.indices) {
            val slot = findViewById<View>(hubSlotIds[i])

            slot.setOnClickListener {
                val current = reorderingSlot
                if (current != null) {
                    if (current != i) swapSlots(current, i)
                    exitReorderMode()
                } else {
                    selectApp(hubOrder[i])
                    showTab("overview")
                }
            }

            slot.setOnLongClickListener {
                if (reorderingSlot == null) openHubSheet(i)
                true
            }
        }
    }

    private fun openHubSheet(slotIndex: Int) {
        hubSheetSlot = slotIndex
        hubSheetOpen = true
        val appId = hubOrder[slotIndex]
        tvHubSheetTitle.text = PrefsKeys.displayNameFor(appId)
        val enabled = prefs.getBoolean(PrefsKeys.enabledKeyFor(appId), false)
        if (enabled) {
            btnHubToggleRun.text = getString(R.string.stop)
            btnHubToggleRun.setTextColor(Color.parseColor("#C62828"))
        } else {
            btnHubToggleRun.text = getString(R.string.run)
            btnHubToggleRun.setTextColor(Color.parseColor("#26A69A"))
        }

        hubSheetScrim.visibility = View.VISIBLE
        hubSheetScrim.alpha = 0f
        hubSheetScrim.animate().alpha(1f).setDuration(160).start()

        hubActionSheet.visibility = View.VISIBLE
        hubActionSheet.alpha = 0f
        hubActionSheet.translationY = 40f
        hubActionSheet.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    private fun closeHubSheet() {
        hubSheetOpen = false
        hubSheetSlot = null
        hubSheetScrim.animate().alpha(0f).setDuration(160).withEndAction {
            hubSheetScrim.visibility = View.GONE
        }.start()
        hubActionSheet.animate().alpha(0f).translationY(40f).setDuration(160).withEndAction {
            hubActionSheet.visibility = View.GONE
        }.start()
    }

    private fun renderHubIcons() {
        for (i in hubOrder.indices) {
            val appId = hubOrder[i]
            val icon = findViewById<ImageView>(hubIconIds[i])
            val label = findViewById<TextView>(hubLabelIds[i])
            label.text = PrefsKeys.displayNameFor(appId)
            icon.setImageResource(hubIconRes(appId))

            val (bg, fg) = hubColors(appId)
            icon.imageTintList = ColorStateList.valueOf(Color.parseColor(fg))
            icon.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(bg))
            }

            val slot = findViewById<View>(hubSlotIds[i])
            if (reorderingSlot == null) {
                slot.alpha = if (appId == selectedApp) 1f else 0.45f
            }
        }
    }

    private fun hubColors(appId: String): Pair<String, String> = when (appId) {
        "instagram" -> "#C13584" to "#FFFFFF"
        "tiktok" -> "#000000" to "#FFFFFF"
        "snapchat" -> "#FFFC00" to "#000000"
        else -> "#444444" to "#FFFFFF"
    }

    private fun hubIconRes(appId: String): Int = when (appId) {
        "instagram" -> R.drawable.ic_instagram
        "tiktok" -> R.drawable.ic_tiktok
        "snapchat" -> R.drawable.ic_snapchat
        else -> R.drawable.ic_instagram
    }

    private fun enterReorderMode(slotIndex: Int) {
        reorderingSlot = slotIndex
        Toast.makeText(
            this,
            getString(R.string.move_prompt, PrefsKeys.displayNameFor(hubOrder[slotIndex])),
            Toast.LENGTH_SHORT
        ).show()

        for (i in hubSlotIds.indices) {
            val slot = findViewById<View>(hubSlotIds[i])
            val badge = findViewById<TextView>(hubBadgeIds[i])
            if (i == slotIndex) {
                slot.animate().scaleX(1.18f).scaleY(1.18f).alpha(1f).setDuration(150).start()
                badge.visibility = View.VISIBLE
                badge.alpha = 0f
                badge.animate().alpha(1f).setDuration(150).start()
            } else {
                slot.animate().scaleX(1f).scaleY(1f).alpha(0.35f).setDuration(150).start()
            }
        }
    }

    private fun exitReorderMode() {
        reorderingSlot = null
        for (i in hubSlotIds.indices) {
            val slot = findViewById<View>(hubSlotIds[i])
            val badge = findViewById<TextView>(hubBadgeIds[i])
            val targetAlpha = if (hubOrder[i] == selectedApp) 1f else 0.45f
            slot.animate().scaleX(1f).scaleY(1f).alpha(targetAlpha).setDuration(150).start()
            if (badge.visibility == View.VISIBLE) {
                badge.animate().alpha(0f).setDuration(120).withEndAction { badge.visibility = View.GONE }.start()
            }
        }
    }

    private fun swapSlots(a: Int, b: Int) {
        val tmp = hubOrder[a]
        hubOrder[a] = hubOrder[b]
        hubOrder[b] = tmp
        PrefsKeys.saveHubOrder(this, hubOrder)

        val viewA = findViewById<View>(hubSlotIds[a])
        val viewB = findViewById<View>(hubSlotIds[b])
        val dx = (viewB.x - viewA.x) * 0.25f

        viewA.animate().translationX(dx).setDuration(110).withEndAction {
            renderHubIcons()
            viewA.animate().translationX(0f).setDuration(160).start()
        }.start()
        viewB.animate().translationX(-dx).setDuration(110).withEndAction {
            viewB.animate().translationX(0f).setDuration(160).start()
        }.start()
    }

    private fun selectApp(appId: String) {
        selectedApp = appId
        tvSelectedApp.text = PrefsKeys.displayNameFor(appId)

        val implemented = PrefsKeys.isImplemented(appId)
        tvNotImplementedNote.visibility = if (implemented) View.GONE else View.VISIBLE
        if (!implemented) {
            tvNotImplementedNote.text = getString(R.string.not_implemented_note, PrefsKeys.displayNameFor(appId))
        }

        renderHubIcons()
        refreshStatus()
        renderStats()
    }

    // ---- Languages ----

    private data class LangEntry(val tag: String, val englishName: String, val nativeName: String)

    private val supportedLanguages = listOf(
        LangEntry("ar", "Arabic", "العربية"),
        LangEntry("bg", "Bulgarian", "Български"),
        LangEntry("zh-Hans", "Chinese (Simplified)", "中文"),
        LangEntry("hr", "Croatian", "Hrvatski"),
        LangEntry("cs", "Czech", "Čeština"),
        LangEntry("da", "Danish", "Dansk"),
        LangEntry("nl", "Dutch", "Nederlands"),
        LangEntry("en", "English", "English"),
        LangEntry("fi", "Finnish", "Suomi"),
        LangEntry("fr", "French", "Français"),
        LangEntry("de", "German", "Deutsch"),
        LangEntry("el", "Greek", "Ελληνικά"),
        LangEntry("he", "Hebrew", "עברית"),
        LangEntry("hi", "Hindi", "हिन्दी"),
        LangEntry("hu", "Hungarian", "Magyar"),
        LangEntry("id", "Indonesian", "Bahasa Indonesia"),
        LangEntry("it", "Italian", "Italiano"),
        LangEntry("ja", "Japanese", "日本語"),
        LangEntry("ko", "Korean", "한국어"),
        LangEntry("nb", "Norwegian", "Norsk"),
        LangEntry("pl", "Polish", "Polski"),
        LangEntry("pt", "Portuguese", "Português"),
        LangEntry("ro", "Romanian", "Română"),
        LangEntry("ru", "Russian", "Русский"),
        LangEntry("sk", "Slovak", "Slovenčina"),
        LangEntry("es", "Spanish", "Español"),
        LangEntry("sv", "Swedish", "Svenska"),
        LangEntry("th", "Thai", "ไทย"),
        LangEntry("tr", "Turkish", "Türkçe"),
        LangEntry("uk", "Ukrainian", "Українська"),
        LangEntry("vi", "Vietnamese", "Tiếng Việt")
    ).sortedBy { it.englishName }

    private fun setupLanguages() {
        languageList.removeAllViews()
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentLocales.isEmpty) null else currentLocales[0]?.toLanguageTag()

        addLanguageRow(getString(R.string.language_system_default), null, currentTag == null)
        for (lang in supportedLanguages) {
            val label = if (lang.englishName == lang.nativeName) {
                lang.englishName
            } else {
                "${lang.englishName} — ${lang.nativeName}"
            }
            val active = currentTag != null && currentTag.equals(lang.tag, ignoreCase = true)
            addLanguageRow(label, lang.tag, active)
        }
    }

    private fun addLanguageRow(label: String, tag: String?, active: Boolean) {
        val density = resources.displayMetrics.density
        val row = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
            setBackgroundResource(if (active) R.drawable.bg_menu_item_active else R.drawable.bg_menu_item)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val locales = if (tag == null) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tag)
                }
                AppCompatDelegate.setApplicationLocales(locales)
            }
        }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (4 * density).toInt() }
        languageList.addView(row, params)
    }

    // ---- Tabs / drawer menu ----

    private fun showTab(tab: String) {
        overviewContainer.visibility = if (tab == "overview") View.VISIBLE else View.GONE
        setupContainer.visibility = if (tab == "setup") View.VISIBLE else View.GONE
        debugContainer.visibility = if (tab == "debug") View.VISIBLE else View.GONE
        languagesContainer.visibility = if (tab == "languages") View.VISIBLE else View.GONE

        btnMenuOverview.setBackgroundResource(if (tab == "overview") R.drawable.bg_menu_item_active else R.drawable.bg_menu_item)
        btnMenuSetup.setBackgroundResource(if (tab == "setup") R.drawable.bg_menu_item_active else R.drawable.bg_menu_item)
        btnMenuDebug.setBackgroundResource(if (tab == "debug") R.drawable.bg_menu_item_active else R.drawable.bg_menu_item)
        btnMenuLanguages.setBackgroundResource(if (tab == "languages") R.drawable.bg_menu_item_active else R.drawable.bg_menu_item)

        if (tab == "overview") renderStats()
        if (tab == "debug") renderLog()
    }

    // Panel is 252dp wide (fixed in the layout) -- computed in px instead
    // of read from drawerPanel.width, since a GONE view reports width 0
    // until after its first (async) layout pass.
    private val drawerWidthPx: Float by lazy { 252f * resources.displayMetrics.density }

    private fun openDrawer() {
        drawerOpen = true
        drawerScrim.visibility = View.VISIBLE
        drawerScrim.alpha = 0f
        drawerScrim.animate().alpha(1f).setDuration(180).start()

        drawerPanel.translationX = -drawerWidthPx
        drawerPanel.visibility = View.VISIBLE
        drawerPanel.animate().translationX(0f).setDuration(220).start()

        btnOpenMenu.animate().rotation(90f).setDuration(220).start()
    }

    private fun closeDrawer() {
        drawerOpen = false
        drawerScrim.animate().alpha(0f).setDuration(180).withEndAction {
            drawerScrim.visibility = View.GONE
        }.start()

        drawerPanel.animate().translationX(-drawerWidthPx).setDuration(220).withEndAction {
            drawerPanel.visibility = View.GONE
        }.start()

        btnOpenMenu.animate().rotation(0f).setDuration(220).start()
    }

    override fun onBackPressed() {
        if (hubSheetOpen) {
            closeHubSheet()
        } else if (drawerOpen) {
            closeDrawer()
        } else {
            super.onBackPressed()
        }
    }

    // ---- Status / stats / log ----

    private fun refreshStatus() {
        val enabled = prefs.getBoolean(PrefsKeys.enabledKeyFor(selectedApp), false)
        tvPauseStatus.text = if (enabled) getString(R.string.status_running) else getString(R.string.status_stopped)

        val runBtn = findViewById<Button>(R.id.btnRun)
        val stopBtn = findViewById<Button>(R.id.btnStop)
        runBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (enabled) "#26A69A" else "#2E2E2E"))
        stopBtn.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (enabled) "#2E2E2E" else "#C62828"))
        runBtn.text = if (enabled) getString(R.string.status_running) else getString(R.string.run)
        stopBtn.text = if (enabled) getString(R.string.stop) else getString(R.string.status_stopped)

        if (selectedApp == "instagram") {
            val serviceOn = isAccessibilityServiceEnabled()
            tvServiceStatus.text = if (serviceOn) {
                getString(R.string.service_enabled)
            } else {
                getString(R.string.service_not_enabled)
            }
        } else {
            tvServiceStatus.text = getString(R.string.service_not_implemented)
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
                setBackgroundColor(Color.parseColor(if (count == 0) "#2E2E2E" else "#26A69A"))
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

    private val createLogDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(AppLog.readAll(this).toByteArray()) }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.export_failed_toast), Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportLog() {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(java.util.Date())
        createLogDocument.launch("reels_blocker_log_$stamp.txt")
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
