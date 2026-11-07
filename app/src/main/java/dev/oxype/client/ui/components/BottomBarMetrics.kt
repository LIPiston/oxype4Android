package dev.oxype.client.ui.components

import androidx.compose.ui.unit.dp

/**
 * The vertical space the floating bar occupies, in the units the sections need.
 *
 * Three different things have to agree about the bar: the bar itself reserves room for
 * the system navigation inset, every scrollable section leaves a gap so its last row is
 * not hidden, and every section's `SnackbarHost` has to sit *above* the bar rather than
 * behind it. Snackbars are positioned by the Scaffold from the bottom safe area alone, so
 * content padding does not move them — without a shared number the message lands under
 * the bar and the user sees a sliver of it.
 *
 * Kept as raw values so a JVM test can check the relationship without composing anything.
 */
internal object BottomBarMetrics {

    /** Height of the capsule itself. */
    val BAR_HEIGHT = 64.dp

    /** Vertical padding the bar puts around the capsule. */
    val BAR_PADDING = 10.dp

    /** How far a snackbar must be lifted to clear the capsule and its padding. */
    val SNACKBAR_LIFT = BAR_HEIGHT + BAR_PADDING * 2

    /** Bottom padding a section's scroll content needs to clear the bar. */
    val CONTENT_INSET = SNACKBAR_LIFT + 20.dp
}
