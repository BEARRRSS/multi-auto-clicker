package com.fayaa.autoclicker.utils

import android.content.Context

/**
 * Persistent settings stored in SharedPreferences.
 */
class PrefsManager(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether to show a completion notification (default: false = silent) */
    var notifyOnComplete: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_COMPLETE, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_COMPLETE, value).apply()

    /** Last used delay in ms */
    var lastDelayMs: Long
        get() = prefs.getLong(KEY_DELAY, 500L)
        set(value) = prefs.edit().putLong(KEY_DELAY, value).apply()

    /** Whether the last loop mode was infinite */
    var lastLoopInfinite: Boolean
        get() = prefs.getBoolean(KEY_LOOP_INFINITE, true)
        set(value) = prefs.edit().putBoolean(KEY_LOOP_INFINITE, value).apply()

    /** Last used loop count */
    var lastLoopCount: Int
        get() = prefs.getInt(KEY_LOOP_COUNT, 10)
        set(value) = prefs.edit().putInt(KEY_LOOP_COUNT, value).apply()

    companion object {
        private const val PREFS_NAME = "autoclicker_prefs"
        private const val KEY_NOTIFY_COMPLETE = "notify_complete"
        private const val KEY_DELAY = "delay_ms"
        private const val KEY_LOOP_INFINITE = "loop_infinite"
        private const val KEY_LOOP_COUNT = "loop_count"
    }
}
