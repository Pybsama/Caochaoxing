// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.caochaoxing.windows.platform.DesktopSystemTools
import com.caochaoxing.windows.platform.ProtectedPersistenceStore
import com.caochaoxing.windows.platform.WindowsKeepAwake
import com.caochaoxing.windows.state.AppThemeMode
import com.caochaoxing.windows.state.WindowsAppState
import com.caochaoxing.windows.state.WindowsCourseStudyController
import com.caochaoxing.windows.state.WindowsWorkspaceTab
import com.caochaoxing.windows.ui.components.CxBanner
import com.caochaoxing.windows.ui.screens.CaptchaVerificationDialog
import com.caochaoxing.windows.ui.screens.CourseStudyScreen
import com.caochaoxing.windows.ui.screens.GroupsScreen
import com.caochaoxing.windows.ui.screens.HistoryAndLocationsScreen
import com.caochaoxing.windows.ui.screens.LoginScreen
import com.caochaoxing.windows.ui.screens.PersistenceRecoveryScreen
import com.caochaoxing.windows.ui.screens.SettingsScreen
import com.caochaoxing.windows.ui.screens.SignWorkspaceScreen
import com.caochaoxing.windows.ui.theme.CaochaoxingWindowsTheme
import com.caochaoxing.windows.ui.theme.CxPalette
import com.caochaoxing.windows.ui.theme.CxTypography
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Dimension

fun main(args: Array<String>) {
    val initialDeepLink = args.firstOrNull { it.startsWith("caochaoxing://", ignoreCase = true) }

    application {
        val appScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val (store, storageDesc) = remember { ProtectedPersistenceStore.createDefault() }
        val appState = remember {
            WindowsAppState(
                store = store,
                appScope = appScope,
                storageDescription = storageDesc,
                initialRestoring = true,
            )
        }
        val studyController = remember {
            WindowsCourseStudyController(
                appState = appState,
                scope = appScope,
                onWatchingStateChanged = { watching -> WindowsKeepAwake.setActive(watching) },
            )
        }

        LaunchedEffect(Unit) {
            appState.onActiveAccountChanged = { phone -> studyController.onAccountChanged(phone) }
            if (initialDeepLink != null) {
                appState.handleIncomingUri(initialDeepLink)
            }
            appState.loadFromDisk()
        }

        DisposableEffect(Unit) {
            onDispose {
                studyController.stopWatch(logStopped = false)
                appState.shutdown()
                WindowsKeepAwake.release()
                appScope.cancel()
            }
        }

        val windowIcon = remember {
            runCatching {
                val stream = object {}.javaClass.getResourceAsStream("/icon.png")
                if (stream != null) {
                    val bytes = stream.use { it.readBytes() }
                    BitmapPainter(SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap())
                } else {
                    null
                }
            }.getOrNull()
        }

        val windowState = rememberWindowState(
            size = DpSize(1280.dp, 800.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )

        val uiState by appState.uiState.collectAsState()
        val scope = rememberCoroutineScope()

        Window(
            onCloseRequest = {
                DesktopSystemTools.registerOwnerWindow(null)
                studyController.stopWatch(logStopped = false)
                appState.shutdown()
                WindowsKeepAwake.release()
                exitApplication()
            },
            state = windowState,
            title = buildWindowTitle(uiState.activeAccount?.let { uiState.displayNameFor(it) }),
            icon = windowIcon,
        ) {
            window.minimumSize = Dimension(1040, 680)
            DisposableEffect(window) {
                DesktopSystemTools.registerOwnerWindow(window)
                onDispose { DesktopSystemTools.registerOwnerWindow(null) }
            }

            MenuBar {
                Menu("工作区") {
                    for (tab in WindowsWorkspaceTab.entries) {
                        Item(
                            text = tab.title,
                            shortcut = KeyShortcut(digitKey(tab.shortcutDigit), ctrl = true),
                            onClick = { appState.selectTab(tab) },
                        )
                    }
                    Separator()
                    Item(
                        text = "刷新当前课程",
                        shortcut = KeyShortcut(Key.R, ctrl = true),
                        enabled = uiState.activeAccount != null,
                        onClick = { scope.launch { appState.refreshCourses() } },
                    )
                }

                Menu("账号") {
                    uiState.accounts.forEachIndexed { index, acc ->
                        val isActive = acc.phoneNumber == uiState.activeAccount?.phoneNumber
                        val label = (if (isActive) "✓ " else "") + "${uiState.displayNameFor(acc)} (${acc.phoneNumber})"
                        if (index < 9) {
                            val digit = ('1'.code + index).toChar()
                            Item(
                                text = label,
                                shortcut = KeyShortcut(digitKey(digit), ctrl = true, alt = true),
                                enabled = !isActive,
                                onClick = { scope.launch { appState.switchAccount(acc.phoneNumber) } },
                            )
                        } else {
                            Item(
                                text = label,
                                enabled = !isActive,
                                onClick = { scope.launch { appState.switchAccount(acc.phoneNumber) } },
                            )
                        }
                    }

                    if (uiState.accounts.size > 1) {
                        Separator()
                        Item(
                            text = "切换下一个账号",
                            shortcut = KeyShortcut(Key.N, ctrl = true, alt = true),
                            onClick = { scope.launch { appState.switchToNextAccount() } },
                        )
                    }

                    Separator()
                    Item(
                        text = "添加新账号…",
                        shortcut = KeyShortcut(Key.A, ctrl = true, shift = true),
                        onClick = { appState.setShowAddAccountDialog(true) },
                    )
                    val active = uiState.activeAccount
                    if (active != null) {
                        Item(
                            text = "刷新当前账号会话",
                            onClick = { scope.launch { appState.refreshAccountSession(active.phoneNumber) } },
                        )
                    }
                }
            }

            CaochaoxingWindowsTheme(themeMode = uiState.themeMode) {
                MainAppShell(
                    appState = appState,
                    studyController = studyController,
                )
            }
        }
    }
}

@Composable
internal fun MainAppShell(
    appState: WindowsAppState,
    studyController: WindowsCourseStudyController,
) {
    val uiState by appState.uiState.collectAsState()
    val studyState by studyController.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = CxPalette.canvas,
        contentColor = CxPalette.ink,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isRestoring && uiState.accounts.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text("正在恢复本地会话…", style = CxTypography.body, color = CxPalette.inkSecondary)
                    }
                }
            }

            uiState.persistenceBlocked -> {
                PersistenceRecoveryScreen(
                    errorMessage = uiState.errorMessage,
                    storageDescription = uiState.storageDescription,
                    onRetryLoad = { scope.launch { appState.loadFromDisk() } },
                    onArchiveAndReset = { scope.launch { appState.recoverLocalState() } },
                )
            }

            uiState.activeAccount == null -> {
                LoginScreen(
                    accountScope = scope,
                    appState = appState,
                    storageDescription = uiState.storageDescription,
                    isSheet = false,
                )
            }

            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Top Desktop Toolbar
                    DesktopTopBar(
                        appState = appState,
                        studyController = studyController,
                    )
                    HorizontalDivider(color = CxPalette.border)

                    // Global Status / Error banners
                    val err = uiState.errorMessage
                    val status = uiState.statusMessage
                    if (!err.isNullOrBlank() || !status.isNullOrBlank()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(CxPalette.canvas)
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (!err.isNullOrBlank()) {
                                CxBanner(
                                    message = err,
                                    isError = true,
                                    onDismiss = { appState.clearErrorMessage() },
                                )
                            }
                            if (!status.isNullOrBlank()) {
                                CxBanner(
                                    message = status,
                                    isError = false,
                                    onDismiss = { appState.clearStatusMessage() },
                                )
                            }
                        }
                    }

                    // Active Workspace Body
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when (uiState.selectedTab) {
                            WindowsWorkspaceTab.STUDY -> CourseStudyScreen(appState, studyController)
                            WindowsWorkspaceTab.SIGN -> SignWorkspaceScreen(appState)
                            WindowsWorkspaceTab.GROUPS -> GroupsScreen(appState)
                            WindowsWorkspaceTab.HISTORY_LOCATIONS -> HistoryAndLocationsScreen(appState)
                            WindowsWorkspaceTab.SETTINGS -> SettingsScreen(appState, scope)
                        }
                    }
                }
            }
        }

        // Global Add-Account Dialog
        if (uiState.showAddAccountDialog) {
            LoginScreen(
                accountScope = scope,
                appState = appState,
                storageDescription = uiState.storageDescription,
                isSheet = true,
                onDismissSheet = { appState.setShowAddAccountDialog(false) },
            )
        }

        // Global Slider Captcha Queue Dialog
        val activeCaptcha = uiState.captchaChallenge
        if (activeCaptcha != null) {
            CaptchaVerificationDialog(
                request = activeCaptcha,
                autoSolve = uiState.autoSolveCaptcha,
                onSuccess = { validate -> appState.resolveCaptcha(activeCaptcha.id, validate) },
                onCancel = { reason -> appState.failCaptcha(activeCaptcha.id, reason) },
            )
        }

        // Subtle global watch-in-progress indicator when user switches away from Study tab
        if (studyState.isWatching && uiState.selectedTab != WindowsWorkspaceTab.STUDY && uiState.activeAccount != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = CxPalette.surface,
                border = BorderStroke(1.dp, CxPalette.sage),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Outlined.VideoLibrary, contentDescription = null, tint = CxPalette.sage, modifier = Modifier.size(18.dp))
                    Text(
                        text = "后台看课进行中 (${studyState.activeWorkers.size}路)",
                        style = CxTypography.caption,
                        fontWeight = FontWeight.Medium,
                    )
                    OutlinedButton(
                        onClick = { appState.selectTab(WindowsWorkspaceTab.STUDY) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("查看", fontSize = 11.5.sp)
                    }
                    OutlinedButton(
                        onClick = { studyController.stopWatch() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CxPalette.cinnabar),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("停止", fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun DesktopTopBar(
    appState: WindowsAppState,
    studyController: WindowsCourseStudyController,
) {
    val uiState by appState.uiState.collectAsState()
    val studyState by studyController.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var accountMenuOpen by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CxPalette.surface,
        contentColor = CxPalette.ink,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isCompactWidth = maxWidth < 900.dp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Navigation Tabs
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (tab in WindowsWorkspaceTab.entries) {
                        val selected = uiState.selectedTab == tab
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) CxPalette.accent.copy(alpha = 0.14f) else CxPalette.surface,
                            border = BorderStroke(1.dp, if (selected) CxPalette.accent.copy(alpha = 0.5f) else CxPalette.border),
                            onClick = { appState.selectTab(tab) },
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = if (isCompactWidth) 8.dp else 10.dp,
                                    vertical = 6.dp,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    imageVector = tabIcon(tab),
                                    contentDescription = null,
                                    tint = if (selected) CxPalette.accent else CxPalette.inkSecondary,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = tab.title,
                                    style = CxTypography.caption,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = if (selected) CxPalette.accent else CxPalette.ink,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }

                // Right contextual actions + Account Switcher + Theme + Refresh
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isCompactWidth && uiState.selectedTab == WindowsWorkspaceTab.STUDY) {
                        val sortedCourses = remember(uiState.courses, uiState.starredCourseIds) {
                            uiState.courses.sortedByDescending { it.courseId in uiState.starredCourseIds }
                        }
                        val targetCourse = uiState.courses.firstOrNull { it.id == studyState.selectedCourseId }
                            ?: sortedCourses.firstOrNull()
                        if (studyState.isWatching) {
                            Button(
                                onClick = { studyController.stopWatch() },
                                colors = ButtonDefaults.buttonColors(containerColor = CxPalette.cinnabar),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("停止 (${studyState.activeWorkers.size}路)", fontSize = 12.sp, maxLines = 1, softWrap = false)
                            }
                        } else {
                            Button(
                                enabled = targetCourse != null,
                                onClick = { targetCourse?.let { studyController.startWatch(it, uiState.courses) } },
                                colors = ButtonDefaults.buttonColors(containerColor = CxPalette.sage),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Icon(Icons.Outlined.PlayCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (studyState.watchAllCoursesAutomatically) {
                                        "跨课看 (${studyState.maxConcurrentWatches}路)"
                                    } else {
                                        "看本课 (${studyState.maxConcurrentWatches}路)"
                                    },
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    } else if (!isCompactWidth && uiState.selectedTab == WindowsWorkspaceTab.SIGN) {
                        Button(
                            enabled = !uiState.isScanningRadar && uiState.courses.isNotEmpty(),
                            onClick = { scope.launch { appState.scanPendingRadar() } },
                            colors = ButtonDefaults.buttonColors(containerColor = CxPalette.accent),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Icon(Icons.Outlined.Radar, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (uiState.isScanningRadar) "扫描中…" else "一键扫待签", fontSize = 12.sp, maxLines = 1, softWrap = false)
                        }
                    }

                    // Global Account Switcher Menu
                    val active = uiState.activeAccount
                    if (active != null) {
                        Box {
                            OutlinedButton(
                                onClick = { accountMenuOpen = true },
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 5.dp),
                            ) {
                                Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = uiState.displayNameFor(active),
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DropdownMenu(
                                expanded = accountMenuOpen,
                                onDismissRequest = { accountMenuOpen = false },
                            ) {
                                for (acc in uiState.accounts) {
                                    val isCurrent = acc.phoneNumber == active.phoneNumber
                                    DropdownMenuItem(
                                        text = { Text("${uiState.displayNameFor(acc)} (${acc.phoneNumber})") },
                                        leadingIcon = {
                                            if (isCurrent) {
                                                Icon(Icons.Outlined.Check, contentDescription = null, tint = CxPalette.accent)
                                            }
                                        },
                                        enabled = !isCurrent,
                                        onClick = {
                                            accountMenuOpen = false
                                            scope.launch { appState.switchAccount(acc.phoneNumber) }
                                        },
                                    )
                                }
                                if (uiState.accounts.size > 1) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("切换下一个账号") },
                                        leadingIcon = { Icon(Icons.Outlined.SwitchAccount, contentDescription = null) },
                                        onClick = {
                                            accountMenuOpen = false
                                            scope.launch { appState.switchToNextAccount() }
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("添加新账号…") },
                                    leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                                    onClick = {
                                        accountMenuOpen = false
                                        appState.setShowAddAccountDialog(true)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("刷新当前账号会话") },
                                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                                    onClick = {
                                        accountMenuOpen = false
                                        scope.launch { appState.refreshAccountSession(active.phoneNumber) }
                                    },
                                )
                            }
                        }
                    }

                    // Theme toggle cycle
                    IconButton(
                        onClick = {
                            val nextMode = when (uiState.themeMode) {
                                AppThemeMode.SYSTEM -> AppThemeMode.LIGHT
                                AppThemeMode.LIGHT -> AppThemeMode.DARK
                                AppThemeMode.DARK -> AppThemeMode.SYSTEM
                            }
                            appState.setThemeMode(nextMode)
                        },
                        modifier = Modifier.size(34.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Brightness4,
                            contentDescription = "切换浅色/深色主题",
                            tint = CxPalette.ink,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    // Refresh courses
                    IconButton(
                        enabled = !uiState.isLoading,
                        onClick = { scope.launch { appState.refreshCourses() } },
                        modifier = Modifier.size(34.dp),
                    ) {
                        if (uiState.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "刷新课程",
                                tint = CxPalette.ink,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun tabIcon(tab: WindowsWorkspaceTab): ImageVector = when (tab) {
    WindowsWorkspaceTab.STUDY -> Icons.Outlined.VideoLibrary
    WindowsWorkspaceTab.SIGN -> Icons.Outlined.HowToReg
    WindowsWorkspaceTab.GROUPS -> Icons.Outlined.Forum
    WindowsWorkspaceTab.HISTORY_LOCATIONS -> Icons.Outlined.History
    WindowsWorkspaceTab.SETTINGS -> Icons.Outlined.Settings
}

private fun buildWindowTitle(activeName: String?): String =
    if (activeName.isNullOrBlank()) "Caochaoxing · Windows" else "Caochaoxing · Windows — $activeName"

private fun digitKey(ch: Char): Key = when (ch) {
    '1' -> Key.One
    '2' -> Key.Two
    '3' -> Key.Three
    '4' -> Key.Four
    '5' -> Key.Five
    '6' -> Key.Six
    '7' -> Key.Seven
    '8' -> Key.Eight
    '9' -> Key.Nine
    else -> Key.Zero
}
