package dev.oxype.client.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a pointer gesture on a bubble starts a reply.
 *
 * Only the decision is tested here; the pointer wiring itself is checked on a device,
 * because a JVM test cannot prove that a swipe does not also scroll the list.
 */
class ReplyGesturesTest {

    /** 64.dp expressed in pixels at density 1, which is all these predicates see. */
    private val threshold = 64f

    @Test
    fun `a long left swipe starts a reply`() {
        assertTrue(isReplySwipe(totalDx = -80f, totalDy = 4f, thresholdPx = threshold))
    }

    @Test
    fun `a short swipe does not`() {
        assertFalse(isReplySwipe(totalDx = -40f, totalDy = 4f, thresholdPx = threshold))
    }

    @Test
    fun `a right swipe does not`() {
        assertFalse(isReplySwipe(totalDx = 80f, totalDy = 4f, thresholdPx = threshold))
    }

    @Test
    fun `a mostly vertical drag belongs to the list, not the bubble`() {
        assertFalse(
            "scrolling must win over a swipe that happens to drift horizontally",
            isReplySwipe(totalDx = -80f, totalDy = 120f, thresholdPx = threshold)
        )
    }

    @Test
    fun `a double tap on the right side of the bubble starts a reply`() {
        assertTrue(isReplyDoubleTapOnBubble(x = 80f, bubbleLeft = 0f, bubbleRight = 100f))
    }

    @Test
    fun `a double tap on the left side of the bubble does nothing`() {
        assertFalse(
            "the left side is reserved for a future reaction gesture",
            isReplyDoubleTapOnBubble(x = 20f, bubbleLeft = 0f, bubbleRight = 100f)
        )
    }

    @Test
    fun `a short bubble is judged by its own width, not the row`() {
        // A one-word incoming bubble sits at 20..60 inside a 400dp row. Its right side is
        // at x=50, which is far left of the row's midpoint — the row-relative split would
        // have ignored the gesture.
        assertTrue(isReplyDoubleTapOnBubble(x = 50f, bubbleLeft = 20f, bubbleRight = 60f))
        assertFalse(
            "x=40 is still the left half of this bubble",
            isReplyDoubleTapOnBubble(x = 40f, bubbleLeft = 20f, bubbleRight = 60f)
        )
    }

    @Test
    fun `tapping the empty row beside the bubble does nothing`() {
        // The bubble ends at 60; the rest of the row belongs to no message.
        assertFalse(isReplyDoubleTapOnBubble(x = 200f, bubbleLeft = 20f, bubbleRight = 60f))
        assertFalse(isReplyDoubleTapOnBubble(x = 0f, bubbleLeft = 20f, bubbleRight = 60f))
    }

    @Test
    fun `a zero-width bubble never claims a right side`() {
        assertFalse(isReplyDoubleTapOnBubble(x = 0f, bubbleLeft = 0f, bubbleRight = 0f))
    }
}
