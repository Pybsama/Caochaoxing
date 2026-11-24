// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingClientPreset
import com.caochaoxing.windows.platform.DesktopSystemTools
import com.caochaoxing.windows.platform.WindowsPaths
import com.caochaoxing.windows.state.AppThemeMode
import com.caochaoxing.windows.state.WindowsAppState
import com.caochaoxing.windows.ui.components.CxBadge
import com.caochaoxing.windows.ui.components.CxCard
import com.caochaoxing.windows.ui.components.CxSectionHeader
import com.caochaoxing.windows.ui.theme.CxPalette
import com.caochaoxing.windows.ui.theme.CxTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    appState: WindowsAppState,
    accountScope: CoroutineScope,
) {
    val uiState by appState.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var customUaDraft by remember(uiState.customUserAgent) { mutableStateOf(uiState.customUserAgent) }
    var newProfileName by remember { mutableStateOf("") }
    var newProfileUa by remember { mutableStateOf("") }
    var exportIncludeAccounts by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CxPalette.canvas)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Multi-Account Session Pool
        CxCard(modifier = Modifier.fillMaxWidth()) {
            CxSectionHeader(
                title = "多账号会话池 (${uiState.accounts.size})",
                subtitle = "支持顶栏/菜单栏 Ctrl+Alt+1..9 快捷切换主账号；切换时会自动隔离课程与看课缓存",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { appState.setShowAddAccountDialog(true) }) {
                            Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("添加账号")
                        }
                        if (uiState.accounts.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { accountScope.launch { appState.logoutAll() } },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CxPalette.cinnabar),
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("全部退出")
                            }
                        }
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (account in uiState.accounts) {
                    AccountManageRow(
                        account = account,
                        isActive = account.phoneNumber == uiState.activeAccount?.phoneNumber,
                        onSwitch = { accountScope.launch { appState.switchAccount(account.phoneNumber) } },
                        onRefresh = { scope.launch { appState.refreshAccountSession(account.phoneNumber) } },
                        onResetDevice = { scope.launch { appState.resetAccountDeviceCode(account.phoneNumber) } },
                        onChangeFid = { fid -> scope.launch { appState.updateAccountFid(account.phoneNumber, fid) } },
                        onUpdateTags = { tags -> scope.launch { appState.updateAccountTags(account.phoneNumber, tags) } },
                        onRemove = { accountScope.launch { appState.removeAccount(account.phoneNumber) } },
                    )
                }
            }
        }

        // 2. Sign-in & Risk Control Preferences
        CxCard(modifier = Modifier.fillMaxWidth()) {
            CxSectionHeader(
                title = "签到与风控选项",
                subtitle = "控制位置抖动、批量错峰间隔、拍照防重复混淆与本地自动滑块识别",
            )
            Spacer(Modifier.height(10.dp))

            SettingSwitchRow(
                title = "位置签到随机微偏移",
                subtitle = "在选定坐标周围施加约 ±5 米随机微偏移（±0.000045°）",
                checked = uiState.enableLocationJitter,
                onCheckedChange = { scope.launch { appState.setEnableLocationJitter(it) } },
            )
            HorizontalDivider(color = CxPalette.border.copy(alpha = 0.6f))
            SettingSwitchRow(
                title = "拍照签到防重复混淆",
                subtitle = "上传前执行微裁切、稀疏像素扰动并写入随机 JPEG COM 注释块",
                checked = uiState.enableImageObfuscation,
                onCheckedChange = { scope.launch { appState.setEnableImageObfuscation(it) } },
            )
            HorizontalDivider(color = CxPalette.border.copy(alpha = 0.6f))
            SettingSwitchRow(
                title = "本地自动识别滑块验证码",
                subtitle = "纯本地像素边缘梯度匹配（零第三方模型依赖），失败时自动回退手动拖动",
                checked = uiState.autoSolveCaptcha,
                onCheckedChange = { scope.launch { appState.setAutoSolveCaptcha(it) } },
            )
            HorizontalDivider(color = CxPalette.border.copy(alpha = 0.6f))

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("多账号批量错峰间隔", style = CxTypography.body, fontWeight = FontWeight.Medium)
                Text("${uiState.batchSignDelayMs} ms", style = CxTypography.mono(), color = CxPalette.accent)
            }
            Slider(
                value = uiState.batchSignDelayMs.toFloat(),
                onValueChange = { v -> scope.launch { appState.setBatchSignDelayMs((v / 100f).roundToInt() * 100) } },
                valueRange = 0f..10_000f,
            )
            Text(
                text = "设为 0 ms 时将并发提交；>0 时在各账号间按 0.75x–1.35x 随机抖动间隔依次提交。",
                style = CxTypography.caption,
                color = CxPalette.inkTertiary,
            )
        }

        // 3. Client Identity / User-Agent
        CxCard(modifier = Modifier.fillMaxWidth()) {
            CxSectionHeader(
                title = "客户端标识 (User-Agent)",
                subtitle = "支持切换内置预设、临时自定义 UA 或保存多套客户端档案",
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in ChaoxingClientPreset.entries) {
                    FilterChip(
                        selected = uiState.selectedCustomClientId == null && uiState.selectedClientPreset == preset,
                        onClick = { scope.launch { appState.selectClientPreset(preset) } },
                        label = { Text(preset.displayName) },
                    )
                }
                for (profile in uiState.customClientProfiles) {
                    FilterChip(
                        selected = uiState.selectedCustomClientId == profile.id,
                        onClick = { scope.launch { appState.selectCustomClientProfile(profile.id) } },
                        label = { Text("自定义：${profile.name}") },
                        trailingIcon = {
                            IconButton(
                                onClick = { scope.launch { appState.removeCustomClientProfile(profile.id) } },
                                modifier = Modifier.size(18.dp),
                            ) {
                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除档案", modifier = Modifier.size(14.dp))
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = customUaDraft,
                    onValueChange = { customUaDraft = it },
                    label = { Text("自定义 User-Agent（留空则使用所选预设）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { scope.launch { appState.updateCustomUserAgent(customUaDraft) } }) {
                    Text("应用 UA")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    label = { Text("新档案名称") },
                    singleLine = true,
                    modifier = Modifier.width(180.dp),
                )
                OutlinedTextField(
                    value = newProfileUa,
                    onValueChange = { newProfileUa = it },
                    label = { Text("新档案 User-Agent") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    enabled = newProfileName.isNotBlank() && newProfileUa.isNotBlank(),
                    onClick = {
                        scope.launch {
                            appState.addCustomClientProfile(newProfileName, newProfileUa)
                            newProfileName = ""
                            newProfileUa = ""
                        }
                    },
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("新增档案")
                }
            }
        }

        // 4. Appearance, Backup & Windows Integration
        CxCard(modifier = Modifier.fillMaxWidth()) {
            CxSectionHeader(
                title = "外观、跨端备份与 Windows 集成",
                subtitle = "备份 JSON 与 macOS / iOS / Android 完全兼容；默认不含账号凭据",
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("外观主题：", style = CxTypography.body, fontWeight = FontWeight.Medium)
                for (mode in AppThemeMode.entries) {
                    FilterChip(
                        selected = uiState.themeMode == mode,
                        onClick = { appState.setThemeMode(mode) },
                        label = { Text(mode.displayName) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = CxPalette.border.copy(alpha = 0.6f))
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = exportIncludeAccounts,
                    onCheckedChange = { exportIncludeAccounts = it },
                )
                Text("导出备份时包含账号会话凭据（明文 JSON，切勿分享给他人）", style = CxTypography.caption, color = CxPalette.ink)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    val defaultName = if (exportIncludeAccounts) {
                        "caochaoxing_full_backup.json"
                    } else {
                        "caochaoxing_presets_backup.json"
                    }
                    val target = DesktopSystemTools.pickSaveFile("导出 Caochaoxing 备份", defaultName)
                    if (target != null) {
                        scope.launch(Dispatchers.IO) {
                            val json = appState.exportBackupJson(includeAccounts = exportIncludeAccounts)
                            val result = runCatching { target.writeText(json, Charsets.UTF_8) }
                            withContext(Dispatchers.Main) {
                                result.onSuccess {
                                    appState.setStatusMessage("已导出备份至 ${target.absolutePath}")
                                }.onFailure { err ->
                                    appState.setErrorMessage("导出备份失败：${err.message}")
                                }
                            }
                        }
                    }
                }) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导出 JSON 备份")
                }

                OutlinedButton(onClick = {
                    val file = DesktopSystemTools.pickFile("导入 Caochaoxing 备份", setOf("json"))
                    if (file != null) {
                        accountScope.launch {
                            try {
                                appState.importBackupFile(file)
                            } catch (e: Exception) {
                                appState.setErrorMessage("导入备份失败：${e.message ?: "格式错误"}")
                            }
                        }
                    }
                }) {
                    Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("导入并合并备份")
                }

                OutlinedButton(onClick = {
                    DesktopSystemTools.registerWindowsUrlScheme()
                        .onSuccess { appState.setStatusMessage(it) }
                        .onFailure { appState.setErrorMessage(it.message ?: "注册 URL Scheme 失败") }
                }) {
                    Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("注册 caochaoxing:// 协议")
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "当前存储：${uiState.storageDescription.ifBlank { if (WindowsPaths.isWindows) "Windows DPAPI" else "本地 JSON" }}",
                style = CxTypography.mono(11.5.sp),
                color = CxPalette.inkTertiary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Caochaoxing Windows 客户端 · AGPL-3.0-only 纯开源实现 · 复用 :core 协议核心（零闭源 SDK）。仅限学习研究使用。",
                style = CxTypography.caption,
                color = CxPalette.inkTertiary,
            )
        }
    }
}

@Composable
private fun AccountManageRow(
    account: ChaoxingAccountSession,
    isActive: Boolean,
    onSwitch: () -> Unit,
    onRefresh: () -> Unit,
    onResetDevice: () -> Unit,
    onChangeFid: (Int) -> Unit,
    onUpdateTags: (List<String>) -> Unit,
    onRemove: () -> Unit,
) {
    var fidMenuExpanded by remember { mutableStateOf(false) }
    var tagInput by remember(account.tags) { mutableStateOf(account.tags.joinToString(", ")) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isActive) CxPalette.accent.copy(alpha = 0.08f) else CxPalette.canvas,
        border = BorderStroke(1.dp, if (isActive) CxPalette.accent.copy(alpha = 0.5f) else CxPalette.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = account.name.ifBlank { account.phoneNumber },
                            style = CxTypography.headline,
                            color = CxPalette.ink,
                        )
                        Text(account.phoneNumber, style = CxTypography.mono(), color = CxPalette.inkSecondary)
                        if (isActive) CxBadge("当前主账号", CxPalette.accent)
                    }
                    Text(
                        text = "FID: ${account.configuredFid} · PUID: ${account.puid} · 设备码: ${account.deviceCode.take(16)}…",
                        style = CxTypography.mono(11.sp),
                        color = CxPalette.inkTertiary,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isActive) {
                        OutlinedButton(onClick = onSwitch) { Text("设为主账号", fontSize = 12.sp) }
                    }
                    if (account.schools.isNotEmpty()) {
                        androidx.compose.foundation.layout.Box {
                            OutlinedButton(onClick = { fidMenuExpanded = true }) {
                                Text("切换学校", fontSize = 12.sp)
                            }
                            DropdownMenu(
                                expanded = fidMenuExpanded,
                                onDismissRequest = { fidMenuExpanded = false },
                            ) {
                                for (school in account.schools) {
                                    DropdownMenuItem(
                                        text = { Text("${school.name} (${school.fid})") },
                                        onClick = {
                                            fidMenuExpanded = false
                                            onChangeFid(school.fid)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新会话", modifier = Modifier.size(18.dp))
                    }
                    TextButton(onClick = onResetDevice) { Text("重置设备码", fontSize = 12.sp) }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "移出账号", tint = CxPalette.cinnabar, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = tagInput,
                    onValueChange = { tagInput = it },
                    label = { Text("账号分组标签（逗号分隔，如：宿舍, 计科1班）") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = {
                    val parsed = tagInput.split(',', '，', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }
                    onUpdateTags(parsed)
                }) {
                    Text("保存标签", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = CxTypography.body, fontWeight = FontWeight.Medium, color = CxPalette.ink)
            Text(subtitle, style = CxTypography.caption, color = CxPalette.inkSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
