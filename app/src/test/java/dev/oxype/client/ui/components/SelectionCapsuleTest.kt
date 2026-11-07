package dev.oxype.client.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the selection capsule sits inside its slot.
 *
 * The capsule is narrower than a slot (it is inset by 6dp on each side so it reads as a
 * floating pill rather than a filled third), which is exactly why advancing it by its own
 * width is wrong: the leftover inset accumulates, and by the third tab the pill is 24dp
 * left of the item it is supposed to highlight. The offset is a fraction of a *slot*.
 */
class SelectionCapsuleTest {

    @Test
    fun `the capsule starts at the left edge of its slot`() {
        assertEquals(0f, selectionCapsuleOffsetInSlots(index = 0, count = 3))
    }

    @Test
    fun `each slot advances the capsule by a whole slot`() {
        assertEquals(1f, selectionCapsuleOffsetInSlots(index = 1, count = 3))
        assertEquals(
            "the third tab must line up with its own slot, not two capsule widths",
            2f,
            selectionCapsuleOffsetInSlots(index = 2, count = 3)
        )
    }

    @Test
    fun `an unknown tab stays at the left edge`() {
        assertEquals(0f, selectionCapsuleOffsetInSlots(index = -1, count = 3))
    }

    @Test
    fun `an index past the end is clamped to the last slot`() {
        assertEquals(2f, selectionCapsuleOffsetInSlots(index = 9, count = 3))
    }

    @Test
    fun `a bar with no slots has no offset`() {
        assertEquals(0f, selectionCapsuleOffsetInSlots(index = 0, count = 0))
    }
}
