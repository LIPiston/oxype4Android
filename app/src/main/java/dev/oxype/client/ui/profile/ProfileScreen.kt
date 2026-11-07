package dev.oxype.client.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.oxype.client.ui.components.BottomBarMetrics
import dev.oxype.client.ui.components.SenderAvatar
import dev.oxype.client.ui.theme.oxypeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The account's own page: who is signed in, and the one account field that can be
 * changed from here.
 *
 * Oxype stores a name, an id and an avatar; there is no phone number, birthday, bio or
 * presence to show, so the page does not pretend to have them. The fields sit in cards
 * under a large avatar, and the two avatar actions are next to the picture rather than
 * hidden in a menu — replacing a picture is the only edit this page has.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = oxypeColors
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()

    // Each entry is a fresh visit: the profile belongs to the server and can change
    // while the app is open, and a cached snapshot is only there to avoid a flash.
    LaunchedEffect(Unit) {
        viewModel.open()
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val picked = withContext(Dispatchers.Default) {
                AvatarPicker.read(resolver, uri)
            }) {
                is PickedAvatar.Ready -> viewModel.setAvatar(picked.bytes)
                is PickedAvatar.Rejected -> viewModel.report(picked.reason)
            }
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.appBackground,
        snackbarHost = {
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.padding(bottom = BottomBarMetrics.SNACKBAR_LIFT)
            )
        },
        topBar = {
            TopAppBar(
                title = { Text("个人资料") },
                actions = {
                    TextButton(
                        onClick = viewModel::refresh,
                        enabled = !state.isLoading
                    ) { Text("刷新") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                    actionIconContentColor = colors.accent
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = BottomBarMetrics.CONTENT_INSET)
        ) {
            ProfileHeader(
                name = state.username,
                userId = state.userId,
                avatarUrl = state.avatarUrl,
                isUploading = state.isUploading,
                isRemoving = state.isRemoving,
                hasAvatar = state.hasAvatar,
                onPick = {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onRemove = viewModel::removeAvatar
            )

            if (state.isLoading && state.username == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }

            ProfileCard(title = "账号") {
                ProfileRow(label = "用户名", value = state.username ?: "—")
                HorizontalDivider(
                    color = colors.divider,
                    modifier = Modifier.padding(start = 16.dp)
                )
                ProfileRow(label = "账号编号", value = state.userId?.toString() ?: "—")
                HorizontalDivider(
                    color = colors.divider,
                    modifier = Modifier.padding(start = 16.dp)
                )
                ProfileRow(
                    label = "头像",
                    value = if (state.hasAvatar) "已设置 · 存储在服务器" else "未设置"
                )
            }

            ProfileCard(title = "头像限制") {
                ProfileRow(
                    label = "大小与边长",
                    value = "${state.maxBytes / (1024 * 1024)} MB · 每边 ${state.maxEdge} 像素"
                )
                HorizontalDivider(
                    color = colors.divider,
                    modifier = Modifier.padding(start = 16.dp)
                )
                ProfileRow(label = "格式", value = formatLabels(state.formats))
            }

            ProfileCard(title = "服务器") {
                ProfileRow(
                    label = "当前地址",
                    value = state.serverUrl.ifBlank { "—" },
                    onClick = onOpenSettings
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "头像由服务器保存，其他资料也同样以服务器为准。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
        }
    }
}

@Composable
private fun ProfileHeader(
    name: String?,
    userId: Long?,
    avatarUrl: String?,
    hasAvatar: Boolean,
    isUploading: Boolean,
    isRemoving: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit
) {
    val colors = oxypeColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .alpha(if (isUploading) 0.5f else 1f)
            ) {
                SenderAvatar(
                    name = name,
                    avatarUrl = avatarUrl,
                    size = 96.dp,
                    modifier = Modifier
                        .size(96.dp)
                        .clickable(enabled = !isUploading && !isRemoving, onClick = onPick)
                )
            }
            if (isUploading) {
                CircularProgressIndicator(color = colors.accent)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = name ?: "已登录用户",
            fontSize = 22.sp,
            color = colors.textPrimary,
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = userId?.let { "账号编号 $it" } ?: " ",
            fontSize = 12.5.sp,
            color = colors.textSecondary
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActionButton(
                label = if (hasAvatar) "更换头像" else "设置头像",
                enabled = !isUploading && !isRemoving,
                primary = true,
                onClick = onPick,
                modifier = Modifier.weight(1f)
            )
            ActionButton(
                label = "移除头像",
                enabled = hasAvatar && !isUploading && !isRemoving,
                primary = false,
                onClick = onRemove,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Pads the card section so the two avatar buttons read as one row above it.
 *
 * The button sits inside a rounded raised surface rather than using `Button`: the bar
 * palette has one accent and the two actions must read as siblings, with only weight
 * distinguishing them.
 */
@Composable
private fun ActionButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = oxypeColors
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceRaised)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) colors.accent else colors.textPrimary
        )
    }
}

@Composable
private fun ProfileCard(title: String, content: @Composable () -> Unit) {
    val colors = oxypeColors
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 20.dp, bottom = 6.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
        ) {
            content()
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    val colors = oxypeColors
    val base = Modifier.fillMaxWidth()
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary
        )
        if (onClick != null) {
            Text(" ›", color = colors.textSecondary)
        }
    }
}

/** Turns the MIME list the server reports into the names people recognise. */
private fun formatLabels(formats: List<String>): String {
    if (formats.isEmpty()) return "—"
    return formats.map {
        when (it.lowercase()) {
            "image/png" -> "PNG"
            "image/jpeg", "image/jpg" -> "JPEG"
            "image/webp" -> "WebP"
            "image/gif" -> "GIF"
            else -> it.substringAfter('/', it).uppercase()
        }
    }.joinToString(" / ")
}
