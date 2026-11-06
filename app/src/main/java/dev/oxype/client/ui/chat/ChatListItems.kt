package dev.oxype.client.ui.chat

import dev.oxype.client.data.oxype.ChatMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One row of the reversed transcript.
 *
 * The list is drawn with `reverseLayout = true`, so a lower index renders nearer the
 * bottom. A day divider belongs *above* the messages of its day, which means it must
 * come *after* them in this order — the opposite of an ordinary top-down list.
 */
internal sealed interface ChatListItem {
    val key: String

    data class Message(val message: ChatMessage) : ChatListItem {
        override val key: String get() = "msg-${message.id}"
    }

    data class Day(val label: String, val anchorId: Long) : ChatListItem {
        override val key: String get() = "day-$anchorId"
    }
}

/**
 * Flattens a newest-first transcript into reversed-list rows.
 *
 * Kept as a pure function (no Compose) so the divider placement can be unit-tested.
 * A divider is emitted once per consecutive run of the same calendar day, placed after
 * the run's oldest message so it paints at that day's upper edge.
 */
internal fun chatListItems(newestFirst: List<ChatMessage>): List<ChatListItem> {
    if (newestFirst.isEmpty()) return emptyList()
    val items = ArrayList<ChatListItem>(newestFirst.size + 4)
    var index = 0
    while (index < newestFirst.size) {
        val dayKey = dayKey(newestFirst[index].timestampMillis)
        var last = index
        while (last + 1 < newestFirst.size &&
            dayKey(newestFirst[last + 1].timestampMillis) == dayKey
        ) {
            last++
        }
        for (position in index..last) {
            items.add(ChatListItem.Message(newestFirst[position]))
        }
        // The oldest message of the run is `last`; its divider sits just after it in
        // this list, which is just above it on screen.
        items.add(
            ChatListItem.Day(
                label = formatDay(newestFirst[last].timestampMillis),
                anchorId = newestFirst[last].id
            )
        )
        index = last + 1
    }
    return items
}

/** Calendar day key used to decide where a divider belongs. */
internal fun dayKey(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "unknown"
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampMillis))
}

internal fun formatDay(timestampMillis: Long): String {
    if (timestampMillis <= 0L) return "未知日期"
    return SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(Date(timestampMillis))
}
