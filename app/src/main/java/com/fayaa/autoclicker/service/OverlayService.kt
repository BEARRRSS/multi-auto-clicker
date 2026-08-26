package com.fayaa.autoclicker.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import com.fayaa.autoclicker.MainActivity
import com.fayaa.autoclicker.R
import com.fayaa.autoclicker.model.ClickPoint
import com.fayaa.autoclicker.model.MacroConfig
import com.fayaa.autoclicker.utils.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that hosts all floating overlay views:
 *  - Control panel (loop, delay, start/stop, settings)
 *  - Click point bubbles (draggable)
 *  - Minimized FAB (when panel is hidden)
 *
 * Communicates with [AutoClickService] directly via its singleton [AutoClickService.instance].
 */
class OverlayService : Service() {

    // ── Window Manager ────────────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private val overlayType get() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // ── Views ─────────────────────────────────────────────────────────────────
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var minimizedView: View? = null
    private var minimizedParams: WindowManager.LayoutParams? = null
    private val clickPointViews = LinkedHashMap<String, View>()
    private val clickPointParams = LinkedHashMap<String, WindowManager.LayoutParams>()

    // ── State ─────────────────────────────────────────────────────────────────
    private val clickPoints = mutableListOf<ClickPoint>()
    private var isRunning = false
    private var loopInfinite = true
    private var loopCount = 10

    // ── Coroutines ────────────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var macroJob: Job? = null

    // ── Prefs ─────────────────────────────────────────────────────────────────
    private lateinit var prefs: PrefsManager

    // ── Notification ──────────────────────────────────────────────────────────
    private val notifManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private val NOTIF_ID_SERVICE = 1001
    private val NOTIF_ID_DONE = 1002
    private val CHANNEL_ID = "auto_clicker_channel"

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = PrefsManager(this)
        createNotificationChannel()
        startForeground(NOTIF_ID_SERVICE, buildServiceNotification())
        showPanel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        macroJob?.cancel()
        serviceScope.cancel()
        removeAllViews()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Panel
    // ─────────────────────────────────────────────────────────────────────────

    private fun showPanel() {
        if (panelView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_control_panel, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 120
        }

        setupPanelDrag(view, params)
        setupPanelControls(view)

        windowManager.addView(view, params)
        panelView = view
        panelParams = params
    }

    private fun hidePanel() {
        panelView?.let { windowManager.removeView(it) }
        panelView = null
        showMinimized()
    }

    private fun showMinimized() {
        if (minimizedView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_minimized, null)
        val params = WindowManager.LayoutParams(
            dpToPx(60), dpToPx(60),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 120
        }

        setupMinimizedDrag(view, params)
        updateMinimizedStatus(view)

        view.findViewById<View>(R.id.minimizedBody).setOnClickListener {
            removeMinimized()
            showPanel()
        }

        windowManager.addView(view, params)
        minimizedView = view
        minimizedParams = params
    }

    private fun removeMinimized() {
        minimizedView?.let { windowManager.removeView(it) }
        minimizedView = null
    }

    private fun updateMinimizedStatus(view: View? = minimizedView) {
        view?.findViewById<TextView>(R.id.tvMinStatus)?.apply {
            text = "●"
            setTextColor(if (isRunning) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Drag helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupPanelDrag(view: View, params: WindowManager.LayoutParams) {
        val header = view.findViewById<View>(R.id.panelHeader)
        makeDraggable(header, view, params)
    }

    private fun setupMinimizedDrag(view: View, params: WindowManager.LayoutParams) {
        val body = view.findViewById<View>(R.id.minimizedBody)
        makeDraggable(body, view, params)
    }

    /**
     * Attaches a touch listener to [handle] that moves [container] via [params].
     * Distinguishes drag vs click: if moved < 8dp it counts as a click.
     */
    private fun makeDraggable(handle: View, container: View, params: WindowManager.LayoutParams) {
        var initX = 0; var initY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (Math.abs(dx) > dpToPx(4) || Math.abs(dy) > dpToPx(4))) {
                        moved = true
                    }
                    if (moved) {
                        params.x = initX + dx.toInt()
                        params.y = initY + dy.toInt()
                        windowManager.updateViewLayout(container, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> moved // consume if dragged; pass through if clicked
                else -> false
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Panel controls
    // ─────────────────────────────────────────────────────────────────────────

    private fun setupPanelControls(view: View) {
        val btnLoopInfinite = view.findViewById<Button>(R.id.btnLoopInfinite)
        val btnLoopFixed = view.findViewById<Button>(R.id.btnLoopFixed)
        val etLoopCount = view.findViewById<EditText>(R.id.etLoopCount)
        val etDelay = view.findViewById<EditText>(R.id.etDelay)
        val tvPointCount = view.findViewById<TextView>(R.id.tvPointCount)
        val btnAddPoint = view.findViewById<Button>(R.id.btnAddPoint)
        val btnClearPoints = view.findViewById<Button>(R.id.btnClearPoints)
        val btnStartStop = view.findViewById<Button>(R.id.btnStartStop)
        val btnSettings = view.findViewById<Button>(R.id.btnSettings)
        val btnMinimize = view.findViewById<Button>(R.id.btnMinimize)
        val tvProgress = view.findViewById<TextView>(R.id.tvProgress)
        val statusDot = view.findViewById<View>(R.id.statusDot)

        // ── Restore saved prefs ──
        loopInfinite = prefs.lastLoopInfinite
        loopCount = prefs.lastLoopCount
        etDelay.setText(prefs.lastDelayMs.toString())
        etLoopCount.setText(loopCount.toString())
        applyLoopMode(btnLoopInfinite, btnLoopFixed, etLoopCount, loopInfinite)

        // ── Loop toggle ──
        btnLoopInfinite.setOnClickListener {
            loopInfinite = true
            prefs.lastLoopInfinite = true
            applyLoopMode(btnLoopInfinite, btnLoopFixed, etLoopCount, true)
        }
        btnLoopFixed.setOnClickListener {
            loopInfinite = false
            prefs.lastLoopInfinite = false
            applyLoopMode(btnLoopInfinite, btnLoopFixed, etLoopCount, false)
        }

        // ── Add click point ──
        btnAddPoint.setOnClickListener {
            addClickPoint(tvPointCount)
        }

        // ── Clear click points ──
        btnClearPoints.setOnClickListener {
            clearClickPoints(tvPointCount)
        }

        // ── Start / Stop ──
        btnStartStop.setOnClickListener {
            if (isRunning) {
                stopMacro(btnStartStop, tvProgress, statusDot)
            } else {
                // Read current config from panel
                val delayMs = etDelay.text.toString().toLongOrNull()?.coerceAtLeast(50L) ?: 500L
                val count = etLoopCount.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 10
                prefs.lastDelayMs = delayMs
                prefs.lastLoopCount = count

                startMacro(
                    config = MacroConfig(
                        clickPoints = clickPoints.toList(),
                        loopInfinite = loopInfinite,
                        loopCount = count,
                        globalDelayMs = delayMs,
                        notifyOnComplete = prefs.notifyOnComplete
                    ),
                    btnStartStop = btnStartStop,
                    tvProgress = tvProgress,
                    statusDot = statusDot
                )
            }
        }

        // ── Settings ──
        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        // ── Minimize ──
        btnMinimize.setOnClickListener {
            hidePanel()
        }
    }

    private fun applyLoopMode(
        btnInfinite: Button, btnFixed: Button, etCount: EditText, infinite: Boolean
    ) {
        if (infinite) {
            btnInfinite.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E94560"))
            btnInfinite.setTextColor(Color.WHITE)
            btnFixed.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            btnFixed.setTextColor(Color.parseColor("#A8A8B3"))
            etCount.visibility = View.GONE
        } else {
            btnFixed.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E94560"))
            btnFixed.setTextColor(Color.WHITE)
            btnInfinite.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            btnInfinite.setTextColor(Color.parseColor("#A8A8B3"))
            etCount.visibility = View.VISIBLE
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Click Point Bubbles
    // ─────────────────────────────────────────────────────────────────────────

    private fun addClickPoint(tvPointCount: TextView? = null) {
        val order = clickPoints.size + 1
        val point = ClickPoint(
            x = dpToPx(160),
            y = dpToPx(300) + (clickPoints.size * dpToPx(80)),
            order = order
        )
        clickPoints.add(point)
        spawnBubble(point)
        refreshPointCount(tvPointCount)
    }

    private fun spawnBubble(point: ClickPoint) {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_click_point, null)
        val params = WindowManager.LayoutParams(
            dpToPx(64), dpToPx(64),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = point.x; y = point.y
        }

        // Set order label
        view.findViewById<TextView>(R.id.tvOrder).text = point.order.toString()

        // Color the bubble based on order
        val colors = listOf(
            "#E94560", "#4ECDC4", "#FFE66D", "#A8E6CF",
            "#FF8B94", "#C3A6FF", "#F7DC6F", "#82E0AA"
        )
        val colorHex = colors[(point.order - 1) % colors.size]
        view.findViewById<TextView>(R.id.tvOrder).background?.also { /* use xml bg */ }

        // Make bubble draggable — update point coords when dragged
        val bubbleBody = view.findViewById<View>(R.id.bubbleBody)
        makeDraggable(bubbleBody, view, params)

        // Also update ClickPoint position when drag ends
        bubbleBody.setOnTouchListener { v, event ->
            val result = (v.tag as? View.OnTouchListener)?.onTouchEvent(v, event) ?: false
            if (event.action == MotionEvent.ACTION_UP) {
                point.x = params.x
                point.y = params.y
            }
            result
        }

        // Custom drag that also updates point position
        var initX = 0; var initY = 0; var tx = 0f; var ty = 0f; var moved = false
        bubbleBody.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    tx = event.rawX; ty = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - tx; val dy = event.rawY - ty
                    if (!moved && (Math.abs(dx) > 8 || Math.abs(dy) > 8)) moved = true
                    if (moved) {
                        params.x = initX + dx.toInt(); params.y = initY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Persist updated position
                    point.x = params.x; point.y = params.y
                    false
                }
                else -> false
            }
        }

        // Delete button
        view.findViewById<TextView>(R.id.btnDeletePoint).setOnClickListener {
            removeClickPoint(point, view)
        }

        windowManager.addView(view, params)
        clickPointViews[point.id] = view
        clickPointParams[point.id] = params
    }

    private fun removeClickPoint(point: ClickPoint, view: View) {
        clickPoints.remove(point)
        clickPointViews.remove(point.id)
        clickPointParams.remove(point.id)
        windowManager.removeView(view)
        // Re-number remaining points
        clickPoints.forEachIndexed { i, cp ->
            cp.order = i + 1
            clickPointViews[cp.id]?.findViewById<TextView>(R.id.tvOrder)?.text = (i + 1).toString()
        }
        refreshPointCount(panelView?.findViewById(R.id.tvPointCount))
    }

    private fun clearClickPoints(tvPointCount: TextView? = null) {
        clickPointViews.values.forEach { windowManager.removeView(it) }
        clickPointViews.clear()
        clickPointParams.clear()
        clickPoints.clear()
        refreshPointCount(tvPointCount)
    }

    private fun refreshPointCount(tvPointCount: TextView?) {
        tvPointCount?.text = "Click Points  (${clickPoints.size})"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Macro Execution
    // ─────────────────────────────────────────────────────────────────────────

    private fun startMacro(
        config: MacroConfig,
        btnStartStop: Button,
        tvProgress: TextView,
        statusDot: View
    ) {
        if (config.clickPoints.isEmpty()) {
            Toast.makeText(this, "Add at least 1 click point first", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AutoClickService.isEnabled()) {
            Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
            return
        }

        isRunning = true
        updateRunningUI(btnStartStop, tvProgress, statusDot, true)

        macroJob = serviceScope.launch(Dispatchers.Default) {
            var loopsDone = 0
            val sortedPoints = config.clickPoints.sortedBy { it.order }

            outer@ while (isActive) {
                for (point in sortedPoints) {
                    if (!isActive) break@outer
                    AutoClickService.instance?.tap(point.x.toFloat(), point.y.toFloat())
                    delay(config.globalDelayMs)
                }
                loopsDone++

                withContext(Dispatchers.Main) {
                    if (config.loopInfinite) {
                        tvProgress.text = getString(R.string.progress_infinite, loopsDone)
                    } else {
                        tvProgress.text = getString(R.string.progress_running, loopsDone)
                    }
                    tvProgress.visibility = View.VISIBLE
                }

                if (!config.loopInfinite && loopsDone >= config.loopCount) break
            }

            val totalDone = loopsDone
            withContext(Dispatchers.Main) {
                isRunning = false
                updateRunningUI(btnStartStop, tvProgress, statusDot, false)
                tvProgress.text = getString(R.string.progress_done, totalDone)

                // Silent by default — only notify if user enabled it
                if (config.notifyOnComplete && !config.loopInfinite) {
                    showCompletionNotification(totalDone)
                }
            }
        }
    }

    private fun stopMacro(btnStartStop: Button, tvProgress: TextView, statusDot: View) {
        macroJob?.cancel()
        isRunning = false
        updateRunningUI(btnStartStop, tvProgress, statusDot, false)
        tvProgress.text = "Stopped"
        tvProgress.visibility = View.VISIBLE
    }

    private fun updateRunningUI(
        btnStartStop: Button,
        tvProgress: TextView,
        statusDot: View,
        running: Boolean
    ) {
        if (running) {
            btnStartStop.text = "■  STOP"
            btnStartStop.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#FF6B6B"))
            statusDot.setBackgroundResource(R.drawable.bg_btn_primary)
            statusDot.background?.setTint(Color.parseColor("#4CAF50"))
            tvProgress.visibility = View.VISIBLE
        } else {
            btnStartStop.text = "▶  START"
            btnStartStop.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor("#E94560"))
            statusDot.setBackgroundResource(R.drawable.bg_btn_stop)
            statusDot.background?.setTint(Color.parseColor("#F44336"))
        }
        updateMinimizedStatus()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Settings Dialog
    // ─────────────────────────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        // We need a themed context; show via a transparent Activity trick or use system theme
        // For overlay service, we use ApplicationContext with dialog window type
        val dialogView = LayoutInflater.from(this).inflate(R.layout.overlay_settings_dialog, null)

        // Since we're in a service, use a basic approach with WindowManager hosted dialog-like view
        // Actually use application context dialog — needs SHOW_WHEN_LOCKED or TYPE_APPLICATION_OVERLAY
        showSettingsOverlay()
    }

    private var settingsView: View? = null

    private fun showSettingsOverlay() {
        if (settingsView != null) return

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_settings_dialog, null)
        val params = WindowManager.LayoutParams(
            dpToPx(280), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val switchNotify = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchNotifyDone)
        switchNotify.isChecked = prefs.notifyOnComplete
        switchNotify.setOnCheckedChangeListener { _, checked ->
            prefs.notifyOnComplete = checked
        }

        view.findViewById<Button>(R.id.btnCloseSettings).setOnClickListener {
            settingsView?.let { windowManager.removeView(it) }
            settingsView = null
        }

        windowManager.addView(view, params)
        settingsView = view
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notifications
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the Auto Clicker service running in the background"
            setShowBadge(false)
        }
        notifManager.createNotificationChannel(channel)
    }

    private fun buildServiceNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle(getString(R.string.notif_running_title))
        .setContentText(getString(R.string.notif_running_text))
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    /** Only called when notifyOnComplete == true */
    private fun showCompletionNotification(loops: Int) {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notif_done_title))
            .setContentText(getString(R.string.notif_done_text, loops))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notifManager.notify(NOTIF_ID_DONE, notif)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun removeAllViews() {
        panelView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        minimizedView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        settingsView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        clickPointViews.values.forEach { try { windowManager.removeView(it) } catch (_: Exception) {} }
        panelView = null; minimizedView = null; settingsView = null
        clickPointViews.clear(); clickPointParams.clear()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()
}
