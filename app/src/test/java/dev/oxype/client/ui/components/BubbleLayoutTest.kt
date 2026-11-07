package dev.oxype.client.ui.components

import dev.oxype.client.ui.chat.QuoteView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layout rules for the quote block inside a bubble.
 *
 * The block is designed around two failure modes: an empty quote body leaving a blank
 * line, and a message without a quote reserving space for one. Both are pure decisions
 * here so they can be checked without Compose.
 */
class BubbleLayoutTest {

    @Test
    fun `a quote with a body draws the clamped second line`() {
        assertEquals(2, quoteBodyLines("在那边等你们"))
    }

    @Test
    fun `a quote without a body draws no body line`() {
        assertEquals(0, quoteBodyLines(""))
        assertEquals(0, quoteBodyLines("   "))
    }

    @Test
    fun `no quote means no block`() {
        assertFalse(quoteBlockVisible(null))
    }

    @Test
    fun `a quote always draws a block, even with no body`() {
        assertTrue(
            quoteBlockVisible(
                QuoteView(author = "林间小道", body = "", targetMessageId = 7L, isJumpable = false)
            )
        )
    }
}
