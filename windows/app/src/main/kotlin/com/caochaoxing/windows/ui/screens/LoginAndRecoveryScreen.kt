// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.caochaoxing.windows.state.WindowsAppState
import com.caochaoxing.windows.ui.components.CxBanner
import com.caochaoxing.windows.ui.components.CxCard
import com.caochaoxing.windows.ui.theme.CxPalette
import com.caochaoxing.windows.ui.theme.CxTypography
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    accountScope: CoroutineScope,
    appState: WindowsAppState,
    storageDescription: String,
    isSheet: Boolean = false,
    onDismissSheet: (() -> Unit)? = null,
) {
    val uiState by appState.uiState.collectAsState()
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val trimmedPhone = phone.trim()
        if (trimmedPhone.isEmpty() || password.isEmpty()) {
            localError = "请输入手机号和密码"
            return
        }
        if (submitting) return
        submitting = true
        localError = null
        accountScope.launch {
            try {
                if (isSheet) {
                    appState.addOtherAccount(trimmedPhone, password)
                } else {
                    appState.loginPrimary(trimmedPhone, password)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                localError = e.message ?: "登录失败，请检查账号和密码"
            } finally {
                submitting = false
            }
        }
    }

    val content = @Composable {
        CxCard(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        submit()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Text(
                text = if (isSheet) "添加新账号" else "Caochaoxing · Windows",
                style = if (isSheet) CxTypography.title else CxTypography.brandDisplay,
                color = CxPalette.ink,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (isSheet) {
                    "登录的账号将加入本地多账号会话池，可一键切换主账号或参与错峰批量签到。"
                } else {
                    "面向 Windows 桌面的开源学习通客户端：支持多账号切换、多路合法同步看课、文档完成、群聊与 5 类课堂签到。"
                },
                style = CxTypography.caption,
                color = CxPalette.inkSecondary,
            )

            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("手机号 / 学习通账号") },
                leadingIcon = { Icon(Icons.Outlined.Phone, contentDescription = null) },
                singleLine = true,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        )
                    }
                },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                enabled = !submitting,
                modifier = Modifier.fillMaxWidth(),
            )

            val visibleError = localError ?: uiState.errorMessage.takeUnless { isSheet }
            if (!visibleError.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                CxBanner(
                    message = visibleError,
                    isError = true,
                    onDismiss = {
                        if (localError != null) localError = null else appState.clearErrorMessage()
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSheet && onDismissSheet != null) {
                    TextButton(onClick = onDismissSheet, enabled = !submitting) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = { submit() },
                    enabled = !submitting && phone.isNotBlank() && password.isNotEmpty(),
                    modifier = if (isSheet) Modifier else Modifier.fillMaxWidth(),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = CxPalette.onAccent,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (submitting) "正在登录…" else if (isSheet) "登录并添加" else "登录学习通")
                }
            }

            if (!isSheet && storageDescription.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "本地存储：$storageDescription",
                    style = CxTypography.caption,
                    color = CxPalette.inkTertiary,
                )
            }
        }
    }

    if (isSheet) {
        Dialog(onDismissRequest = { if (!submitting) onDismissSheet?.invoke() }) {
            content()
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CxPalette.canvas)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
fun PersistenceRecoveryScreen(
    errorMessage: String?,
    storageDescription: String,
    onRetryLoad: () -> Unit,
    onArchiveAndReset: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CxPalette.canvas)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CxCard(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.WarningAmber,
                    contentDescription = null,
                    tint = CxPalette.cinnabar,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("本地会话文件未能读取", style = CxTypography.title, color = CxPalette.ink)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "为防止静默覆盖原有账号会话与常用地点，应用已暂停写入。你可以先重试读取，或在自动保留恢复副本后安全重置。",
                style = CxTypography.body,
                color = CxPalette.inkSecondary,
            )
            if (!errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(text = errorMessage, style = CxTypography.caption, color = CxPalette.cinnabar)
            }
            if (storageDescription.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(text = storageDescription, style = CxTypography.mono(), color = CxPalette.inkTertiary)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRetryLoad) {
                    Text("重试读取")
                }
                Button(
                    onClick = onArchiveAndReset,
                    colors = ButtonDefaults.buttonColors(containerColor = CxPalette.cinnabar),
                ) {
                    Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("归档副本并重置")
                }
            }
        }
    }
}
