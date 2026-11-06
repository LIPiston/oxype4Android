package dev.oxype.client.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.oxype.client.ui.theme.oxypeColors

/**
 * Sign-in and sign-up in one screen, switched by a text button rather than tabs: there
 * are only two states and the difference is one field.
 */
@Composable
fun AuthScreen(viewModel: AuthViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = oxypeColors

    Surface(modifier = modifier.fillMaxSize(), color = colors.appBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))
            Text(
                text = "Oxype",
                style = MaterialTheme.typography.headlineLarge,
                color = colors.accent
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "轻量即时消息",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary
            )
            Spacer(Modifier.height(40.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { viewModel.setMode(AuthMode.LOGIN) }) {
                    Text(
                        "登录",
                        color = if (state.mode == AuthMode.LOGIN) colors.accent else colors.textSecondary
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { viewModel.setMode(AuthMode.REGISTER) }) {
                    Text(
                        "注册",
                        color = if (state.mode == AuthMode.REGISTER) colors.accent else colors.textSecondary
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (state.mode == AuthMode.LOGIN) {
                OutlinedTextField(
                    value = state.userId,
                    onValueChange = viewModel::setUserId,
                    label = { Text("用户编号") },
                    supportingText = { Text("注册时由服务器分配的数字编号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = viewModel::setUsername,
                    label = { Text("用户名") },
                    supportingText = { Text("1-15 个字符") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::setPassword,
                label = { Text("密码") },
                supportingText = { Text("6-20 个字符") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = viewModel::submit,
                enabled = state.isValid && !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp).width(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (state.mode == AuthMode.LOGIN) "登录" else "创建账户")
                }
            }

            state.error?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    color = colors.danger,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            state.notice?.let { message ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = viewModel::updateServerUrl,
                label = { Text("服务器地址") },
                supportingText = {
                    Text(
                        state.serverNotice
                            ?: "模拟器访问宿主机通常为 http://10.0.2.2:12345"
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = viewModel::saveServerUrl) { Text("保存地址") }
            Spacer(Modifier.height(8.dp))
        }
    }
}
