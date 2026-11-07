package dev.oxype.client.ui.groupinfo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.oxype.client.data.oxype.GroupInfo
import dev.oxype.client.data.oxype.GroupRole
import dev.oxype.client.data.oxype.SessionMember
import dev.oxype.client.ui.components.SenderAvatar
import dev.oxype.client.ui.theme.oxypeColors

/**
 * The full-page group info screen, opened from the chat's title.
 *
 * It is a leaf: it loads once, shows what it has, and writes nothing outside itself. The
 * back gesture returns to the *same* chat — the chat ViewModel is deliberately not
 * closed, so the transcript and the scroll position survive the round trip.
 *
 * The header shows the placeholder passed in while the load is in flight and after it
 * fails, so opening the page never paints an empty frame.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoScreen(
    viewModel: GroupInfoViewModel,
    sessionId: Long,
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = oxypeColors

    // A fresh visit asks the server; a configuration change re-binds the page it already
    // has. `visited` survives rotation, so turning the device does not re-fetch, while
    // coming back from the chat (the screen is composed anew) does — names, roles and
    // membership change while the app is open.
    var visited by rememberSaveable(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId) {
        val fresh = !visited
        visited = true
        viewModel.open(sessionId, reload = fresh)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.appBackground,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                title = { Text("群聊信息") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.surface,
                    titleContentColor = colors.textPrimary,
                    navigationIconContentColor = colors.accent
                )
            )
        }
    ) { padding ->
        // The placeholder header is always available, so the middle of the page can show
        // the spinner or the error without the screen ever looking like a blank sheet.
        val info = state.info
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderCard(
                name = info?.name ?: title,
                avatarUrl = info?.avatarUrl,
                subtitle = info?.let { groupInfoSubtitle(it) } ?: subtitle
            )

            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }

                state.error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.danger,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = viewModel::refresh) { Text("重试") }
                    }
                }

                info != null -> {
                    SectionCard(title = "简介") {
                        Text(
                            text = info.description.takeIf { it.isNotBlank() } ?: "未填写简介",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (info.description.isBlank()) {
                                colors.textSecondary
                            } else {
                                colors.textPrimary
                            }
                        )
                    }

                    SectionCard(title = "成员 ${info.memberCount}") {
                        info.members.forEachIndexed { index, member ->
                            if (index > 0) HorizontalDivider(color = colors.divider)
                            MemberRow(
                                member = member,
                                role = info.roleOf(member.userId),
                                isCurrentUser = member.userId == state.currentUserId
                            )
                        }
                    }

                    SectionCard(title = "会话编号") {
                        Text(
                            text = info.sessionId.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * The header line under the group name.
 *
 * The role badge is appended here rather than drawn as a separate chip so the subtitle
 * stays one readable line. It is derived from the same [GroupInfo.roleOf] the member rows
 * use, so the two can never disagree.
 */
internal fun groupInfoSubtitle(info: GroupInfo): String {
    val base = "群聊 · ${info.memberCount} 位成员"
    return when {
        info.isOwner -> "$base · 我是群主"
        info.isAdmin -> "$base · 我是管理员"
        else -> base
    }
}

@Composable
private fun HeaderCard(name: String, avatarUrl: String?, subtitle: String) {
    val colors = oxypeColors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // A session without a server avatar falls back to the name's initial, which is
        // the same disc the conversation list already uses.
        SenderAvatar(
            name = name.ifBlank { "群聊" },
            avatarUrl = avatarUrl,
            size = 84.dp
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = name.ifBlank { "群聊" },
            style = MaterialTheme.typography.headlineSmall,
            color = colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val colors = oxypeColors
    Spacer(Modifier.height(16.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = colors.textSecondary,
        modifier = Modifier.padding(start = 20.dp, bottom = 6.dp)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        content()
    }
}

/**
 * One member line: disc, name, and a role tag when the account has one.
 *
 * The name is never blank; the repository already replaced an empty server name with
 * `用户 N`. `（我）` is a suffix rather than a separate column so the row stays one line.
 * [role] comes from [GroupInfo.roleOf], the same function the header badge is built on.
 */
@Composable
private fun MemberRow(
    member: SessionMember,
    role: GroupRole,
    isCurrentUser: Boolean
) {
    val colors = oxypeColors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MEMBER_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SenderAvatar(
            name = member.username,
            avatarUrl = member.avatarUrl,
            size = 34.dp
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (isCurrentUser) "${member.username}（我）" else member.username,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        // Owner and admin are not exclusive in the data (an owner is also an admin), so
        // the stronger label wins rather than drawing both.
        when (role) {
            GroupRole.OWNER -> RoleTag(label = "群主", background = colors.accent, content = colors.surface)
            GroupRole.ADMIN -> RoleTag(
                label = "管理员",
                background = colors.surfaceRaised,
                content = colors.textSecondary
            )

            GroupRole.MEMBER -> Unit
        }
    }
}

@Composable
private fun RoleTag(label: String, background: Color, content: Color) {
    Box(
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = content
        )
    }
}

/** Space above the first member row is owned by [SectionCard]; keep the row itself tight. */
private val MEMBER_ROW_PADDING = 6.dp
