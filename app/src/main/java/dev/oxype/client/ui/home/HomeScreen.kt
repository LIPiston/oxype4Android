package dev.oxype.client.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.oxype.client.ui.components.SessionRow
import dev.oxype.client.ui.theme.oxypeColors

/**
 * The conversation list.
 *
 * One entry point per conversation: tapping a row always calls the same
 * `onOpenChat(sessionId)`, so navigation stays predictable regardless of how the
 * session was joined.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    username: String?,
    onOpenChat: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = oxypeColors
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }
    var showJoin by remember { mutableStateOf(false) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(state.info) {
        state.info?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeInfo()
        }
    }
    LaunchedEffect(state.createdInvite) {
        state.createdInvite?.let {
            snackbarHostState.showSnackbar("邀请码：$it")
            viewModel.consumeCreatedInvite()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.appBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("会话", style = MaterialTheme.typography.titleLarge)
                        username?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.textSecondary
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { showJoin = true }) { Text("加入") }
                    TextButton(onClick = { showCreate = true }) { Text("创建") }
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                    actionIconContentColor = colors.accent
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::refresh,
                containerColor = colors.accent,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                text = { Text("刷新") },
                icon = {}
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading && state.sessions.isEmpty() -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = colors.accent
                    )
                }

                state.isEmpty -> EmptySessions(
                    modifier = Modifier.align(Alignment.Center),
                    onCreate = { showCreate = true },
                    onJoin = { showJoin = true }
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.sessionId }) { session ->
                        SessionRow(
                            session = session,
                            onClick = { onOpenChat(session.sessionId) }
                        )
                        HorizontalDivider(color = colors.divider)
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
    }

    if (showCreate) {
        CreateSessionDialog(
            isBusy = state.isBusy,
            onDismiss = { showCreate = false },
            onConfirm = { name, description ->
                showCreate = false
                viewModel.createSession(name, description)
            }
        )
    }

    if (showJoin) {
        JoinSessionDialog(
            isBusy = state.isBusy,
            onDismiss = { showJoin = false },
            onConfirm = { uuid ->
                showJoin = false
                viewModel.joinSession(uuid)
            }
        )
    }
}

@Composable
private fun EmptySessions(
    modifier: Modifier = Modifier,
    onCreate: () -> Unit,
    onJoin: () -> Unit
) {
    val colors = oxypeColors
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "还没有会话",
            style = MaterialTheme.typography.titleMedium,
            color = colors.textPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "创建一个新会话，或用邀请码加入已有会话。",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCreate) { Text("创建会话") }
            TextButton(onClick = onJoin) { Text("加入会话") }
        }
    }
}

@Composable
private fun CreateSessionDialog(
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建会话") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, description) },
                enabled = name.isNotBlank() && !isBusy
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun JoinSessionDialog(
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var uuid by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入会话") },
        text = {
            Column {
                OutlinedTextField(
                    value = uuid,
                    onValueChange = { uuid = it },
                    label = { Text("邀请码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "邀请码由会话成员生成。",
                    style = MaterialTheme.typography.labelSmall,
                    color = oxypeColors.textSecondary
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(uuid) },
                enabled = uuid.isNotBlank() && !isBusy
            ) { Text("加入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
