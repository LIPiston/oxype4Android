package dev.oxype.client.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import dev.oxype.client.BuildConfig
import dev.oxype.client.data.session.ThemeMode
import dev.oxype.client.ui.components.SenderAvatar
import dev.oxype.client.ui.theme.oxypeColors

/**
 * Settings: where the server lives, how the app looks, the account's exit, and the
 * licence/attribution note required for an original client.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenProfile: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = oxypeColors
    val snackbarHostState = remember { SnackbarHostState() }
    var showLogout by remember { mutableStateOf(false) }
    var showLicences by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // The screen is created with the app and may be shown long after the address was
        // configured elsewhere (the auth screen edits it while signed out, which is not
        // a credential change). Re-read it on entry so the field is never stale.
        viewModel.syncFromRepository()
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    LaunchedEffect(state.signedOut) {
        if (state.signedOut) {
            onSignedOut()
            viewModel.consumeSignedOut()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = colors.appBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
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
                .verticalScroll(rememberScrollState())
                .padding(bottom = 104.dp)
        ) {
            SectionHeader("账户")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenProfile)
                    .background(colors.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SenderAvatar(
                    name = state.username,
                    avatarUrl = state.avatarUrl,
                    size = 24.dp
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {
                    Text(
                        text = state.username ?: "已登录用户",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary
                    )
                    state.userId?.let {
                        Text(
                            text = "用户编号 $it",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                    }
                }
                Text("›", color = colors.textSecondary)
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("服务器地址")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = viewModel::updateServerUrl,
                    label = { Text("HTTP 地址") },
                    supportingText = {
                        Text("模拟器访问宿主机通常为 http://10.0.2.2:12345")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = viewModel::saveServerUrl) { Text("保存地址") }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("主题")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
            ) {
                ThemeMode.entries.forEach { mode ->
                    ThemeOption(
                        mode = mode,
                        selected = state.themeMode == mode,
                        onSelect = { viewModel.setThemeMode(mode) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("关于")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
            ) {
                SettingRow(
                    title = "版本",
                    detail = BuildConfig.VERSION_NAME,
                    onClick = null
                )
                HorizontalDivider(color = colors.divider)
                SettingRow(
                    title = "许可与说明",
                    detail = "查看第三方组件与原创声明",
                    onClick = { showLicences = true }
                )
            }

            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(16.dp)
            ) {
                TextButton(
                    onClick = { showLogout = true },
                    enabled = !state.isBusy
                ) {
                    Text("退出登录", color = colors.danger)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "本应用为 Oxype 协议的独立客户端，与 Oxype 服务端维护者无隶属关系。",
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showLogout) {
        AlertDialog(
            onDismissRequest = { showLogout = false },
            title = { Text("退出登录") },
            text = { Text("本地保存的令牌会被删除，需要重新登录。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogout = false
                        viewModel.logout()
                    }
                ) { Text("退出", color = colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { showLogout = false }) { Text("取消") }
            }
        )
    }

    if (showLicences) {
        LicenceDialog(onDismiss = { showLicences = false })
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        color = oxypeColors.textSecondary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp)
    )
}

@Composable
private fun ThemeOption(mode: ThemeMode, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = when (mode) {
                ThemeMode.SYSTEM -> "跟随系统"
                ThemeMode.LIGHT -> "浅色"
                ThemeMode.DARK -> "深色"
            },
            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun SettingRow(title: String, detail: String, onClick: (() -> Unit)?) {
    val colors = oxypeColors
    val baseModifier = Modifier.fillMaxWidth()
    val modifier = if (onClick != null) baseModifier.clickable(onClick = onClick) else baseModifier
    Row(
        modifier = modifier
            .then(Modifier.background(colors.surface))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary
            )
            Text(
                text = detail,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        if (onClick != null) {
            Text("›", color = colors.textSecondary)
        }
    }
}

@Composable
private fun LicenceDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("许可与说明") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "本应用是基于 Oxype 公开 HTTP 协议编写的独立客户端，界面与代码均为原创实现。",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "第三方组件",
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "AndroidX / Jetpack Compose / Kotlin 标准库与 kotlinx.coroutines，" +
                        "均按其各自的 Apache-2.0 许可使用。",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "协议说明",
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "客户端通过 Kalmia 包装的 JSON 响应与 Oxype 交互；所有协议细节集中在 " +
                        "data/oxype 层，便于服务端演进时集中修补。",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}
