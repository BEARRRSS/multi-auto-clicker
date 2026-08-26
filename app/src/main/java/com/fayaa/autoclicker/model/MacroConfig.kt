package com.fayaa.autoclicker.model

/**
 * Full configuration for a macro run.
 */
data class MacroConfig(
    val clickPoints: List<ClickPoint>,
    val loopInfinite: Boolean = true,
    val loopCount: Int = 1,
    /** Global delay in ms between click points (overridden by point-level delayAfter if set) */
    val globalDelayMs: Long = 500L,
    /** Whether to show a completion notification when a fixed-count loop finishes */
    val notifyOnComplete: Boolean = false
)
