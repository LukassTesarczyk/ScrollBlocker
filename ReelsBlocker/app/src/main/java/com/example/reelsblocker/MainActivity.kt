package com.example.reelsblocker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnOpenBatterySettings).setOnClickListener {
            // Opens general app settings; on Xiaomi/HyperOS the
            // "no restrictions" / autostart toggles live inside here.
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        }

        findViewById<TextView>(R.id.tvInstructions).text = """
            1. Tap "Open Accessibility Settings" and enable "Reels Blocker Service".
            2. Tap "Open App Battery Settings" and set battery usage to
               "No restrictions" and enable Autostart (Xiaomi/HyperOS specific).
            3. Open Instagram and try tapping the Reels tab -- it should
               immediately bounce you back.
        """.trimIndent()
    }
}
