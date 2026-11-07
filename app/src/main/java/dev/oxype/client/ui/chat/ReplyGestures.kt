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
 * A double tap on the right [RIGHT_TAP_FRACTION] of the bubble, whose horizontal extent
 * is `bubbleLeft..bubbleRight` inside the tapped row.
 *
 * The measured bounds matter: a short bubble hugging the left edge has its own right
 * side far left of the row's midpoint, and the empty row beside a bubble is not part of
 * that message at all. Judging by the row width would miss the first and misfire on the
 * second. The left side is left alone so a future reaction gesture can claim it.
 */
internal fun isReplyDoubleTapOnBubble(
    x: Float,
    bubbleLeft: Float,
    bubbleRight: Float
): Boolean {
    val width = bubbleRight - bubbleLeft
    if (width <= 0f) return false
    if (x < bubbleLeft || x > bubbleRight) return false
    return x - bubbleLeft >= width * RIGHT_TAP_FRACTION
}

/** Fraction of the bubble width, measured from the right edge, that starts a reply. */
private const val RIGHT_TAP_FRACTION = 0.6f
