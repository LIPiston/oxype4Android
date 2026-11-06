package dev.oxype.client.ui.components

/**
 * Width rules for a message bubble, kept free of Compose so they can be tested.
 *
 * A bubble has to do two things at once: hug a short message instead of filling the
 * row, and stop growing at a fraction of the screen so a long message wraps. Both are
 * properties of the *bubble*, not of the surrounding `Row` — the row only decides
 * alignment (start for the other party, end for the user's own messages).
 *
 * The hug/wrap split itself is Compose's: the bubble uses `widthIn(min, max)` and lets
 * the text measure naturally, which is why no character-width estimator lives here.
 * Measuring glyph advance by hand would only be a guess that the layout never consults.
 */
object BubbleWidthPolicy {

    /**
     * Largest share of the screen a bubble may occupy.
     *
     * Leaving the remainder free is what makes the left/right alignment visible and
     * keeps a long message from looking like a full-width card.
     */
    const val MAX_WIDTH_FRACTION: Float = 0.78f

    /** Horizontal room an avatar column costs: a 28dp disc plus an 8dp gap. */
    const val AVATAR_SLOT_DP: Float = 36f

    /**
     * Smallest width a bubble may be clamped to.
     *
     * Even a one-character message carries a timestamp, and a bound narrower than that
     * would wrap "23:59" onto its own line. The floor is only an upper bound, so the
     * bubble still shrinks to its content below it.
     */
    const val MIN_BUBBLE_WIDTH_DP: Float = 64f

    /**
     * The maximum bubble width for [screenWidthDp], reduced by an avatar column.
     *
     * A degenerate (zero or negative) screen width yields `0` instead of a negative
     * bound, which Compose would reject.
     */
    fun maxBubbleWidthDp(screenWidthDp: Float, avatarSlotDp: Float = 0f): Float {
        val limit = screenWidthDp * MAX_WIDTH_FRACTION - avatarSlotDp.coerceAtLeast(0f)
        return if (limit.isFinite() && limit > 0f) limit else 0f
    }

    /**
     * The floor a bubble is clamped to inside [screenWidthDp].
     *
     * [MIN_BUBBLE_WIDTH_DP] normally applies, but it can never exceed the maximum: on a
     * very narrow screen the maximum wins (Compose rejects `min > max`).
     */
    fun minBubbleWidthDp(screenWidthDp: Float, avatarSlotDp: Float = 0f): Float =
        minOf(MIN_BUBBLE_WIDTH_DP, maxBubbleWidthDp(screenWidthDp, avatarSlotDp))
}
