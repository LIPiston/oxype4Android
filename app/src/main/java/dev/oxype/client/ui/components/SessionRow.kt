package dev.oxype.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.oxype.client.data.oxype.SessionSummary
import dev.oxype.client.ui.theme.oxypeColors

/**
 * One row in the conversation list: a monogram disc, the name, the member count, and
 * the last message preview. Tapping it is what opens the chat.
 */
@Composable
fun SessionRow(
    session: SessionSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = oxypeColors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(colors.surfaceRaised, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = session.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.name,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val preview = session.lastMessage?.text?.takeIf { it.isNotBlank() }
                ?: session.description.takeIf { it.isNotBlank() }
                ?: "${session.memberCount} 位成员"
            Text(
                text = preview,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${session.memberCount} 人",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary
        )
    }
}
