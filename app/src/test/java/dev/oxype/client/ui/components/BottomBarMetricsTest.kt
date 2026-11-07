package dev.oxype.client.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three places that have to agree about the floating bar's height.
 *
 * The bar is drawn over the sections, so a snackbar placed by the Scaffold's usual bottom
 * inset ends up behind it. These are not pixel tests — they are the arithmetic that would
 * silently be wrong: a snackbar lifted by less than the bar is tall is still covered.
 */
class BottomBarMetricsTest {

    @Test
    fun `a snackbar lifted by the bar's own height clears it`() {
        assertTrue(
            "the lift must cover the capsule and the padding around it",
            BottomBarMetrics.SNACKBAR_LIFT >=
                BottomBarMetrics.BAR_HEIGHT + BottomBarMetrics.BAR_PADDING * 2
        )
    }

    @Test
    fun `content padding is at least the snackbar lift`() {
        // A row must not be the only thing above the snackbar: if content cleared the bar
        // by less than a snackbar is lifted, tapping "last row" would open something that
        // the message then covers.
        assertTrue(
            BottomBarMetrics.CONTENT_INSET >= BottomBarMetrics.SNACKBAR_LIFT
        )
    }
}
