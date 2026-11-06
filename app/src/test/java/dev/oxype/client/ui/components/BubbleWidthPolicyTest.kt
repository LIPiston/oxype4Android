package dev.oxype.client.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The width bounds the bubble is actually laid out with.
 *
 * Only the min/max policy is covered here, because that is what `MessageBubble` passes
 * to `widthIn`: Compose measures the text itself and clamps it between those two bounds.
 * Short-versus-long rendering is a layout property and is verified on a device, not by a
 * character-width estimate.
 */
class BubbleWidthPolicyTest {

    @Test
    fun `the avatar slot reduces the available bubble width`() {
        val plain = BubbleWidthPolicy.maxBubbleWidthDp(screenWidthDp = 360f)
        val withAvatar = BubbleWidthPolicy.maxBubbleWidthDp(
            screenWidthDp = 360f,
            avatarSlotDp = BubbleWidthPolicy.AVATAR_SLOT_DP
        )

        assertTrue("an avatar must cost horizontal room", withAvatar < plain)
        assertTrue("the remaining bubble width must stay positive", withAvatar > 0f)
        assertEquals(plain - BubbleWidthPolicy.AVATAR_SLOT_DP, withAvatar, 0.001f)
    }

    @Test
    fun `the maximum keeps a long message inside a fraction of the screen`() {
        val max = BubbleWidthPolicy.maxBubbleWidthDp(screenWidthDp = 360f)

        assertEquals(360f * BubbleWidthPolicy.MAX_WIDTH_FRACTION, max, 0.001f)
        assertTrue("the fraction must leave room for the other side", max < 360f)
    }

    @Test
    fun `the minimum keeps room for the timestamp`() {
        assertEquals(
            BubbleWidthPolicy.MIN_BUBBLE_WIDTH_DP,
            BubbleWidthPolicy.minBubbleWidthDp(screenWidthDp = 360f),
            0.001f
        )
    }

    @Test
    fun `a narrow screen lowers the minimum to the maximum`() {
        val min = BubbleWidthPolicy.minBubbleWidthDp(screenWidthDp = 60f)
        val max = BubbleWidthPolicy.maxBubbleWidthDp(screenWidthDp = 60f)

        assertEquals("min must never exceed max", max, min, 0.001f)
        assertTrue(min < BubbleWidthPolicy.MIN_BUBBLE_WIDTH_DP)
    }

    @Test
    fun `a degenerate screen never produces a negative width`() {
        assertEquals(0f, BubbleWidthPolicy.maxBubbleWidthDp(screenWidthDp = 0f), 0.001f)
        assertEquals(0f, BubbleWidthPolicy.maxBubbleWidthDp(screenWidthDp = -10f), 0.001f)
        assertEquals(0f, BubbleWidthPolicy.minBubbleWidthDp(screenWidthDp = 0f), 0.001f)
    }

    @Test
    fun `a negative avatar slot cannot widen the bubble`() {
        assertEquals(
            BubbleWidthPolicy.maxBubbleWidthDp(screenWidthDp = 360f),
            BubbleWidthPolicy.maxBubbleWidthDp(screenWidthDp = 360f, avatarSlotDp = -40f),
            0.001f
        )
    }
}
