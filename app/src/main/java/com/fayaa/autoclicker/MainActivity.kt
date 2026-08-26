package com.fayaa.autoclicker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.fayaa.autoclicker.service.AutoClickService
import com.fayaa.autoclicker.service.OverlayService
import com.fayaa.autoclicker.utils.PrefsManager
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PrefsManager

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnEnableAccessibility: Button
    private lateinit var btnLaunch: Button
    private lateinit var btnStop: Button
    private lateinit var switchNotify: SwitchMaterial

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PrefsManager(this)
        bindViews()
        setupListeners()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Setup
    // ─────────────────────────────────────────────────────────────────────────

    private fun bindViews() {
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)
        btnLaunch = findViewById(R.id.btnLaunch)
        btnStop = findViewById(R.id.btnStop)
        switchNotify = findViewById(R.id.switchNotify)
    }

    private fun setupListeners() {
        // ── Notification preference sync ──
        switchNotify.isChecked = prefs.notifyOnComplete
        switchNotify.setOnCheckedChangeListener { _, checked ->
            prefs.notifyOnComplete = checked
        }

        // ── Grant Overlay ──
        btnGrantOverlay.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        // ── Enable Accessibility Service ──
        btnEnableAccessibility.setOnClickListener {
            Toast.makeText(
                this,
                "Find 'Multi Auto Clicker' and enable it",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // ── Launch Overlay ──
        btnLaunch.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant Overlay permission first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!AutoClickService.isEnabled()) {
                Toast.makeText(this, "Please enable Accessibility Service first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startForegroundService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Auto Clicker launched! Check your floating panel.", Toast.LENGTH_SHORT).show()
            updateLaunchButtons(true)
        }

        // ── Stop Overlay ──
        btnStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            updateLaunchButtons(false)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Permission Status
    // ─────────────────────────────────────────────────────────────────────────

    private fun updatePermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = AutoClickService.isEnabled()

        // Overlay status
        tvOverlayStatus.text = if (hasOverlay) "✓" else "✕"
        tvOverlayStatus.setTextColor(
            if (hasOverlay) getColor(R.color.status_running) else getColor(R.color.accent_stop)
        )
        btnGrantOverlay.isEnabled = !hasOverlay
        btnGrantOverlay.alpha = if (hasOverlay) 0.4f else 1f

        // Accessibility status
        tvAccessibilityStatus.text = if (hasAccessibility) "✓" else "✕"
        tvAccessibilityStatus.setTextColor(
            if (hasAccessibility) getColor(R.color.status_running) else getColor(R.color.accent_stop)
        )
        btnEnableAccessibility.isEnabled = !hasAccessibility
        btnEnableAccessibility.alpha = if (hasAccessibility) 0.4f else 1f

        // Enable launch button only when both granted
        val allGranted = hasOverlay && hasAccessibility
        btnLaunch.isEnabled = allGranted
        btnLaunch.alpha = if (allGranted) 1f else 0.4f
    }

    private fun updateLaunchButtons(serviceRunning: Boolean) {
        btnLaunch.visibility = if (serviceRunning) android.view.View.GONE else android.view.View.VISIBLE
        btnStop.visibility = if (serviceRunning) android.view.View.VISIBLE else android.view.View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification Permission (Android 13+)
    // ─────────────────────────────────────────────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION
            )
        }
    }

    companion object {
        private const val REQ_NOTIFICATION = 100
    }
}
