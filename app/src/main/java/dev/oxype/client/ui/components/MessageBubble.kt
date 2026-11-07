package dev.oxype.client.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.oxype.client.data.oxype.ChatMessage
import dev.oxype.client.ui.chat.QuoteView
import dev.oxype.client.ui.theme.oxypeColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One message bubble.
 *
 * The bubble sizes itself to its text: the surrounding [Row] only chooses the side
 * (start for the other party, end for the user's own messages), and the bubble is
 * capped at a fraction of the available width so a long message wraps instead of
 * spanning the screen. The time/edited marker lives inside the bubble but must not
 * stretch it, so the metadata row is end-aligned in the column rather than
 * `fillMaxWidth`.
 *
 * In a group conversation an incoming message gets the sender's avatar and name on the
 * left; a one-to-one chat and the user's own messages keep the compact layout.
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    isOutgoing: Boolean,
    senderLabel: String? = null,
    senderAvatarUrl: String? = null,
    showAvatar: Boolean = false,
    quote: QuoteView? = null,
    onQuoteClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = oxypeColors
    val hasAvatarColumn = showAvatar && !isOutgoing
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
    ) {
        val avatarSlot = if (hasAvatarColumn) BubbleWidthPolicy.AVATAR_SLOT_DP else 0f
        val maxBubbleWidth = BubbleWidthPolicy.maxBubbleWidthDp(
            screenWidthDp = maxWidth.value,
            avatarSlotDp = avatarSlot
        ).dp
        // The minimum only keeps the metadata line on one row; the bubble still hugs
        // its text, and the maximum is what makes long text wrap.
        val minBubbleWidth = BubbleWidthPolicy.minBubbleWidthDp(
            screenWidthDp = maxWidth.value,
            avatarSlotDp = avatarSlot
        ).dp

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            if (hasAvatarColumn) {
                SenderAvatar(
                    name = senderLabel,
                    avatarUrl = senderAvatarUrl,
                    size = AVATAR_SIZE
                )
                Spacer(Modifier.width(AVATAR_GAP))
            }
            Surface(
                color = if (isOutgoing) colors.outgoingBubble else colors.incomingBubble,
                contentColor = if (isOutgoing) colors.outgoingText else colors.incomingText,
                shape = RoundedCornerShape(
                    topStart = 14.dp,
                    topEnd = 14.dp,
                    bottomStart = if (isOutgoing) 14.dp else 4.dp,
                    bottomEnd = if (isOutgoing) 4.dp else 14.dp
                ),
                tonalElevation = if (isOutgoing) 0.dp else 1.dp,
                modifier = Modifier.widthIn(min = minBubbleWidth, max = maxBubbleWidth)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (!isOutgoing && !senderLabel.isNullOrBlank()) {
                        Text(
                            text = senderLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                    }
                    if (quoteBlockVisible(quote)) {
                        QuoteBlock(
                            quote = quote!!,
                            onClick = { onQuoteClick?.invoke() }
                        )
                    }
                    Text(
                        text = message.plainText.ifBlank { "（空消息）" },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    MessageMetadata(
                        isEdited = message.isEdited,
                        timestampMillis = message.timestampMillis,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

/** Edited marker and time, end-aligned without forcing the bubble to full width. */
@Composable
private fun MessageMetadata(
    isEdited: Boolean,
    timestampMillis: Long,
    modifier: Modifier = Modifier
) {
    val colors = oxypeColors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (isEdited) {
            Text(
                text = "已编辑",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = formatTime(timestampMillis),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textSecondary
        )
    }
}

private val AVATAR_SIZE = 28.dp
private val AVATAR_GAP = 8.dp

/** Local wall-clock time; 0 renders as a dash rather than 1970. */
fun formatTime(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "--:--"
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    return formatter.format(Date(timestampMillis))
}

/** A day divider above the first message of a calendar day. */
@Composable
fun DayDivider(label: String, modifier: Modifier = Modifier) {
    val colors = oxypeColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Surface(
            color = colors.surfaceRaised,
            shape = RoundedCornerShape(10.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
