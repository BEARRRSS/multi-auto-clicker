package com.fayaa.autoclicker.model

import java.util.UUID

/**
 * Represents a single tap/click target point on the screen.
 */
data class ClickPoint(
    val id: String = UUID.randomUUID().toString(),
    var x: Int = 0,
    var y: Int = 0,
    /** Delay in milliseconds AFTER this click fires, before the next one. */
    var delayAfter: Long = 500L,
    /** Display order index (1-based) */
    var order: Int = 1
)
