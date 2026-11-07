package dev.oxype.client.ui.chat

/**
 * Gesture decisions for starting a reply.
 *
 * Plain predicates so the thresholds are testable without a device: the pointer wiring
 * lives in [ChatScreen], but "did this gesture mean reply" does not have to.
 */

/**
 * A leftward drag far enough to count, and clearly horizontal.
 *
 * The horizontal dominance check is what keeps vertical scrolling working: a drag with a
 * larger vertical component belongs to the list, even if its total is long. Requiring a
 * sign is deliberate — dragging right is not a reply, so it cannot fire by accident.
 */
internal fun isReplySwipe(totalDx: Float, totalDy: Float, thresholdPx: Float): Boolean {
    if (thresholdPx <= 0f) return false
    if (totalDx > -thresholdPx) return false
    return kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy)
}

/**
 * A double tap on the right [RIGHT_TAP_FRACTION] of the bubble.
 *
 * The split is a privacy of the gesture, not of the bubble: the left side is left alone
 * so a future reaction gesture can claim it without breaking this one.
 */
internal fun isReplyDoubleTap(x: Float, width: Float): Boolean {
    if (width <= 0f) return false
    return x >= width * RIGHT_TAP_FRACTION
}

/** Fraction of the bubble width, measured from the right edge, that starts a reply. */
private const val RIGHT_TAP_FRACTION = 0.6f
