package dev.oxype.client.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the selection capsule sits for a given tab.
 *
 * The capsule is one third of the bar wide and slides between slots, so its horizontal
 * offset is the selected index expressed as a fraction of its own width. Keeping that
 * arithmetic out of the composable is what lets it be checked without a device — and it
 * is the part that would silently be a third of a slot off.
 */
class BottomBarLayoutTest {

    @Test
    fun `the first of three slots has no offset`() {
        assertEquals(0f, bottomBarFraction(0, 3))
    }

    @Test
    fun `the third of three slots is fully shifted`() {
        assertEquals(2f, bottomBarFraction(2, 3))
    }

    @Test
    fun `an unknown index keeps the capsule where it is`() {
        // A detail screen has no tab; the bar is hidden there, and if it is ever painted
        // anyway the capsule must not fly off the bar.
        assertEquals(0f, bottomBarFraction(-1, 3))
    }

    @Test
    fun `a bar without slots has no offset`() {
        assertEquals(0f, bottomBarFraction(0, 0))
    }
}
