package dev.oxype.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.oxype.client.ui.chat.QuoteView
import dev.oxype.client.ui.theme.oxypeColors

/**
 * The quote block at the top of a reply bubble.
 *
 * A left bar with the quoted author and up to two lines of the quoted text. It is
 * deliberately flat (no filled background): the bubble already carries the message role,
 * and a second surface inside it would compete with the reply's own text.
 *
 * Nothing is invented here — a missing body simply omits the second line rather than
 * drawing a blank one, so a partial snapshot never looks like a broken bubble.
 */
@Composable
fun QuoteBlock(
    quote: QuoteView,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = oxypeColors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = QUOTE_BOTTOM_GAP)
    ) {
        Box(
            modifier = Modifier
                .width(QUOTE_BAR_WIDTH)
                .height(quoteBarHeightDp(quote).dp)
                .background(colors.accent, RoundedCornerShape(2.dp))
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = QUOTE_BAR_WIDTH + QUOTE_TEXT_GAP)
                .clickable(onClick = onClick)
        ) {
            Text(
                text = quote.author,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val lines = quoteBodyLines(quote.body)
            if (lines > 0) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = quote.body,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = lines,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * How many body lines the quote draws.
 *
 * Zero for a missing body, so the block keeps its author line without an empty second
 * line under it. Two is the clamp for everything else.
 */
internal fun quoteBodyLines(body: String): Int = if (body.isBlank()) 0 else MAX_QUOTE_LINES

/** True when a message has a quote block at all. */
internal fun quoteBlockVisible(quote: QuoteView?): Boolean = quote != null

/**
 * Height of the left bar, measured to span the author line and whatever body lines were
 * actually drawn. Keeping the bar in step with the text avoids a bar floating past a
 * quote that has no body.
 */
internal fun quoteBarHeightDp(quote: QuoteView): Int =
    if (quoteBodyLines(quote.body) > 0) QUOTE_BAR_HEIGHT_WITH_BODY else QUOTE_BAR_HEIGHT_AUTHOR_ONLY

private val QUOTE_BAR_WIDTH = 3.dp
private val QUOTE_TEXT_GAP = 5.dp
private val QUOTE_BOTTOM_GAP = 4.dp
private const val MAX_QUOTE_LINES = 2
private const val QUOTE_BAR_HEIGHT_WITH_BODY = 32
private const val QUOTE_BAR_HEIGHT_AUTHOR_ONLY = 16
