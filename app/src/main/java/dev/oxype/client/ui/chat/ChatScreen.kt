package dev.oxype.client.ui.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.oxype.client.data.oxype.ChatMessage
import dev.oxype.client.ui.components.DayDivider
import dev.oxype.client.ui.components.MessageBubble
import dev.oxype.client.ui.theme.oxypeColors
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * The transcript.
 *
 * The list is drawn newest-first (`reverseLayout = true`) so a new message stays
 * pinned to the bottom without manual scrolling, while older pages are appended at
 * the far end and appear above. History loads when the first *requested* index is
 * reached, which keeps the trigger stable even when a page contains no messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    sessionId: Long,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = oxypeColors
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var actionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var confirmDelete by remember { mutableStateOf<ChatMessage?>(null) }
    // Quote bodies fall back to the loaded message when the server snapshot is partial,
    // so the index is built once per transcript rather than once per bubble.
    val loadedMessages = remember(state.messages) { state.messages.associateBy { it.id } }

    LaunchedEffect(sessionId) {
        viewModel.open(sessionId, title, subtitle)
    }

    // Polling is bound to the screen being in front of the user: leaving the chat or
    // the activity reaching STOP cancels the network loop, and coming back resumes it.
    // `repeatOnLifecycle` also covers rotation because it re-launches per lifecycle.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, sessionId) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.onForeground()
            try {
                awaitCancellation()
            } finally {
                viewModel.onBackground()
            }
        }
    }

    // In a reversed list the newest message is index 0 at the bottom, so *older*
    // history lives at the far end. Paging therefore reacts to the last visible index,
    // not the first: reaching the top of the screen means reaching the newest message.
    LaunchedEffect(listState) {
        snapshotFlow {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: return@snapshotFlow false
            layout.totalItemsCount > 0 && lastVisible >= layout.totalItemsCount - 2
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadOlder() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.appBackground,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                title = {
                    Column {
                        Text(
                            text = state.title.ifBlank { title },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        if (state.subtitle.isNotBlank() || subtitle.isNotBlank()) {
                            Text(
                                text = state.subtitle.ifBlank { subtitle },
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text("刷新") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.accent
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            state.error?.let { message ->
                ErrorStrip(message = message, onDismiss = viewModel::consumeError)
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoadingInitial -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = colors.accent
                        )
                    }

                    state.messages.isEmpty() -> {
                        Text(
                            text = "还没有消息，发送第一条吧。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            // With `reverseLayout`, index 0 sits at the bottom, so the
                            // bottom breathing room is emitted first. Message rows come
                            // from `chatListItems`, which places each day divider after
                            // its day's oldest message so the label paints above them.
                            item(key = "bottom-spacer") { Spacer(Modifier.height(8.dp)) }
                            items(
                                items = chatListItems(state.messages.asReversed()),
                                key = { it.key }
                            ) { row ->
                                when (row) {
                                    is ChatListItem.Message -> MessageBubbleWithActions(
                                        message = row.message,
                                        isOutgoing = row.message.senderId == state.currentUserId,
                                        isGroup = state.isGroup,
                                        senderLabel = if (row.message.senderId == state.currentUserId) {
                                            null
                                        } else {
                                            senderDisplayName(row.message.senderId, state.participants)
                                        },
                                        senderAvatarUrl = state.participants[row.message.senderId]?.avatarUrl,
                                        quote = row.message.reply?.let { quote ->
                                            resolveQuote(
                                                quote = quote,
                                                participants = state.participants,
                                                loaded = loadedMessages,
                                                currentUserId = state.currentUserId
                                            )
                                        },
                                        onLongPress = { actionTarget = row.message }
                                    )

                                    is ChatListItem.Day -> DayDivider(label = row.label)
                                }
                            }
                            item(key = "history-indicator") {
                                HistoryFooter(
                                    isLoadingOlder = state.isLoadingOlder,
                                    reachedTop = state.reachedTop
                                )
                            }
                        }
                    }
                }
            }

            MessageComposer(
                draft = state.draft,
                isSending = state.isSending,
                replyTarget = state.replyTarget,
                onDraftChange = viewModel::setDraft,
                onSend = viewModel::send,
                onCancelReply = viewModel::cancelReply
            )
        }
    }

    val target = actionTarget
    if (target != null) {
        MessageActionsDialog(
            message = target,
            canDelete = target.senderId == state.currentUserId,
            onReply = {
                viewModel.beginReply(target)
                actionTarget = null
            },
            onCopy = {
                clipboard.setText(AnnotatedString(target.plainText))
                actionTarget = null
            },
            onDelete = {
                actionTarget = null
                confirmDelete = target
            },
            onDismiss = { actionTarget = null }
        )
    }

    val pendingDelete = confirmDelete
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除消息") },
            text = { Text("这条消息会从服务器删除，所有成员都将看不到它。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteMessage(pendingDelete.id)
                        confirmDelete = null
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * One tappable bubble. Row ordering (including day dividers) comes from
 * [chatListItems], which places each divider above that day's messages.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleWithActions(
    message: ChatMessage,
    isOutgoing: Boolean,
    isGroup: Boolean,
    senderLabel: String?,
    senderAvatarUrl: String?,
    quote: QuoteView?,
    onLongPress: () -> Unit,
    onQuoteClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier.combinedClickable(
            onClick = {},
            onLongClick = onLongPress
        )
    ) {
        MessageBubble(
            message = message,
            isOutgoing = isOutgoing,
            senderLabel = if (isGroup) senderLabel else null,
            senderAvatarUrl = senderAvatarUrl,
            showAvatar = isGroup,
            quote = quote,
            onQuoteClick = onQuoteClick
        )
    }
}

@Composable
private fun HistoryFooter(isLoadingOlder: Boolean, reachedTop: Boolean) {
    val colors = oxypeColors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoadingOlder -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.textSecondary
            )

            reachedTop -> Text(
                text = "已到最早的消息",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary
            )

            else -> Spacer(Modifier.height(1.dp))
        }
    }
}

@Composable
private fun ErrorStrip(message: String, onDismiss: () -> Unit) {
    val colors = oxypeColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = colors.danger,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onDismiss) { Text("关闭") }
    }
}

@Composable
private fun MessageComposer(
    draft: String,
    isSending: Boolean,
    replyTarget: ReplyTarget?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancelReply: () -> Unit
) {
    val colors = oxypeColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        if (replyTarget != null) {
            ReplyPreviewStrip(target = replyTarget, onCancel = onCancelReply)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("输入消息") },
                maxLines = 4,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onSend,
                enabled = draft.isNotBlank() && !isSending
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent
                    )
                } else {
                    Text("发送")
                }
            }
        }
    }
}

/**
 * What the composer is answering.
 *
 * It sits directly above the input so the quote reads as part of the message being
 * written, and it is removable without discarding the draft: cancelling a reply is a
 * separate decision from clearing what was typed.
 */
@Composable
private fun ReplyPreviewStrip(target: ReplyTarget, onCancel: () -> Unit) {
    val colors = oxypeColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised, RoundedCornerShape(10.dp))
            .padding(start = 0.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(REPLY_STRIP_BAR_HEIGHT)
                .background(colors.accent, RoundedCornerShape(2.dp))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp, end = 8.dp)
        ) {
            Text(
                text = "回复 ${target.authorName}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (target.body.isNotBlank()) {
                Text(
                    text = target.body,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✕",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary
            )
        }
    }
}

private val REPLY_STRIP_BAR_HEIGHT = 34.dp

@Composable
private fun MessageActionsDialog(
    message: ChatMessage,
    canDelete: Boolean,
    onReply: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("消息操作") },
        text = {
            Column {
                Text(
                    text = message.plainText.take(200),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))
                DropdownMenuItem(
                    text = { Text("回复") },
                    onClick = onReply
                )
                DropdownMenuItem(
                    text = { Text("复制文本") },
                    onClick = onCopy
                )
                if (canDelete) {
                    DropdownMenuItem(
                        text = { Text("删除消息", color = oxypeColors.danger) },
                        onClick = onDelete
                    )
                } else {
                    Text(
                        text = "只能删除自己发送的消息。",
                        style = MaterialTheme.typography.labelSmall,
                        color = oxypeColors.textSecondary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
