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
    fun `a double tap on the right side starts a reply`() {
        assertTrue(isReplyDoubleTap(x = 80f, width = 100f))
    }

    @Test
    fun `a double tap on the left side does nothing`() {
        assertFalse(
            "the left side is reserved for a future reaction gesture",
            isReplyDoubleTap(x = 20f, width = 100f)
        )
    }

    @Test
    fun `a zero-width bubble never claims a right side`() {
        assertFalse(isReplyDoubleTap(x = 0f, width = 0f))
    }
}
