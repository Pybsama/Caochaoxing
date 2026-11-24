// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.windows.state.parseQrInputOrNull
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignType
import com.caochaoxing.windows.platform.DesktopQrCodeDecoder
import com.caochaoxing.windows.platform.DesktopSystemTools
import com.caochaoxing.windows.state.WindowsAppState
import com.caochaoxing.windows.ui.components.CxBadge
import com.caochaoxing.windows.ui.components.CxCard
import com.caochaoxing.windows.ui.components.CxEmptyState
import com.caochaoxing.windows.ui.theme.CxPalette
import com.caochaoxing.windows.ui.theme.CxTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Two-pane sign-in workspace:
 * Left: standalone QR importer, pending radar hits, and course list
 * Right: selected course's sign activities OR the active sign-in detail workbench
 */
@Composable
fun SignWorkspaceScreen(
    appState: WindowsAppState,
) {
    val uiState by appState.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var selectedCourseId by remember { mutableStateOf<String?>(null) }
    var standaloneQrText by remember(uiState.pendingQrCodeInput) {
        mutableStateOf(uiState.pendingQrCodeInput.orEmpty())
    }

    val filteredCourses = remember(uiState.courses, uiState.starredCourseIds, query) {
        val q = query.trim()
        val base = if (q.isEmpty()) {
            uiState.courses
        } else {
            uiState.courses.filter {
                it.courseName.contains(q, ignoreCase = true) || it.teacherName.contains(q, ignoreCase = true)
            }
        }
        base.sortedByDescending { it.courseId in uiState.starredCourseIds }
    }

    val selectedCourse: ChaoxingCourse? = remember(filteredCourses, uiState.courses, selectedCourseId) {
        uiState.courses.firstOrNull { it.id == selectedCourseId } ?: filteredCourses.firstOrNull()
    }

    LaunchedEffect(selectedCourse?.id) {
        if (selectedCourse != null) {
            appState.loadCourseActivities(selectedCourse, forceRefresh = false)
        }
    }

    fun openStandaloneQr(raw: String) {
        val activityId = parseQrInputOrNull(raw)?.aid
        if (activityId == null) {
            appState.setErrorMessage("无法解析有效活动 ID，请检查二维码文本或链接")
            return
        }
        val syntheticActivity = ChaoxingSignActivity(
            id = activityId,
            startTimeMs = null,
            endTimeMs = null,
            userStatus = 0,
            otherId = ChaoxingSignType.QR_CODE.rawValue,
            isLook = false,
            type = 2,
            activeType = 2,
            nameOne = "独立二维码签到 #$activityId",
            status = 1,
            nameFour = "",
            courseId = 0L,
            classId = 0,
            courseName = "独立二维码入口",
            extContent = "",
        )
        appState.openSignWorkspace(syntheticActivity, prefilledQrInput = raw)
    }

    Row(modifier = Modifier.fillMaxSize().background(CxPalette.canvas)) {
        // Left sidebar: Standalone QR + Radar + Courses
        Column(
            modifier = Modifier
                .width(310.dp)
                .fillMaxHeight()
                .background(CxPalette.surface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Standalone QR Quick Sign card
            CxCard(
                containerColor = CxPalette.canvas,
                contentPadding = PaddingValues(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = null, tint = CxPalette.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("独立二维码签到", style = CxTypography.headline, color = CxPalette.ink)
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = standaloneQrText,
                    onValueChange = { standaloneQrText = it },
                    placeholder = { Text("粘贴 SIGNIN:... 或链接", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            val img = DesktopSystemTools.readClipboardImage()
                            val txt = DesktopSystemTools.readClipboardText()
                            when {
                                img != null -> {
                                    val decoded = DesktopQrCodeDecoder.decode(img)
                                    if (decoded != null) {
                                        standaloneQrText = decoded
                                        openStandaloneQr(decoded)
                                    } else {
                                        appState.setErrorMessage("剪贴板截图中未识别到二维码")
                                    }
                                }
                                !txt.isNullOrBlank() -> {
                                    standaloneQrText = txt
                                    openStandaloneQr(txt)
                                }
                                else -> appState.setErrorMessage("剪贴板为空")
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("剪贴板识码", fontSize = 11.5.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val file = DesktopSystemTools.pickFile("选择二维码图片", setOf("png", "jpg", "jpeg", "bmp", "webp"))
                            if (file != null) {
                                scope.launch(Dispatchers.IO) {
                                    val decoded = runCatching { DesktopQrCodeDecoder.decode(file.readBytes()) }.getOrNull()
                                    withContext(Dispatchers.Main) {
                                        if (decoded != null) {
                                            standaloneQrText = decoded
                                            openStandaloneQr(decoded)
                                        } else {
                                            appState.setErrorMessage("未在图片中识别到二维码")
                                        }
                                    }
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("选图识码", fontSize = 11.5.sp)
                    }
                }
                if (standaloneQrText.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { openStandaloneQr(standaloneQrText) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("进入二维码签到工作台", fontSize = 12.sp)
                    }
                }
            }

            // Pending Radar Hits (height-bounded scrollable list so courses below remain visible)
            if (uiState.pendingRadarItems.isNotEmpty()) {
                CxCard(
                    containerColor = CxPalette.accent.copy(alpha = 0.08f),
                    borderColor = CxPalette.accent.copy(alpha = 0.4f),
                    contentPadding = PaddingValues(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Radar, contentDescription = null, tint = CxPalette.accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("待签雷达 (${uiState.pendingRadarItems.size})", style = CxTypography.headline, color = CxPalette.accent)
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 144.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(uiState.pendingRadarItems, key = { "${it.course.id}_${it.activity.id}" }) { item ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = CxPalette.surface,
                                border = BorderStroke(1.dp, CxPalette.border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { appState.openSignWorkspace(item.activity) },
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.course.courseName, style = CxTypography.caption, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.activity.nameOne.ifBlank { item.activity.signType.displayName }, style = CxTypography.caption, color = CxPalette.inkSecondary, maxLines = 1)
                                    }
                                    CxBadge("去签到", CxPalette.accent)
                                }
                            }
                        }
                    }
                }
            }

            // Course filter & list
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("筛选课程…", fontSize = 12.5.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(17.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(filteredCourses, key = { it.id }) { course ->
                    val isSelected = course.id == selectedCourse?.id
                    val isStarred = course.courseId in uiState.starredCourseIds
                    val acts = uiState.courseActivities[course.id]
                    val pendingCount = acts?.count { it.isAvailable && !it.isAlreadySignedByStatus && !it.isLate } ?: 0
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) CxPalette.accent.copy(alpha = 0.12f) else CxPalette.canvas,
                        border = BorderStroke(1.dp, if (isSelected) CxPalette.accent.copy(alpha = 0.55f) else CxPalette.border),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedCourseId = course.id
                                appState.closeSignWorkspace()
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = course.courseName,
                                    style = CxTypography.body,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (course.teacherName.isNotBlank()) {
                                    Text(course.teacherName, style = CxTypography.caption, color = CxPalette.inkSecondary, maxLines = 1)
                                }
                            }
                            if (pendingCount > 0) {
                                CxBadge("$pendingCount 待签", CxPalette.accent)
                                Spacer(Modifier.width(4.dp))
                            }
                            if (isStarred) {
                                Icon(Icons.Outlined.Star, contentDescription = null, tint = CxPalette.amber, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }

        VerticalDivider(color = CxPalette.border)

        // Right pane: either active sign workbench OR course activities list
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val activeSign = uiState.activeSignActivity
            if (activeSign != null) {
                SignActivityDetailPanel(
                    activity = activeSign,
                    appState = appState,
                    onClose = { appState.closeSignWorkspace() },
                    onNavigateToActivity = { next -> appState.openSignWorkspace(next) },
                )
            } else {
                CourseActivitiesPane(
                    course = selectedCourse,
                    activities = selectedCourse?.let { uiState.courseActivities[it.id] }.orEmpty(),
                    isLoading = selectedCourse != null && selectedCourse.id in uiState.loadingCourseIds,
                    hasFailed = selectedCourse != null && selectedCourse.id in uiState.failedCourseActivityIds,
                    onRefresh = {
                        selectedCourse?.let { c ->
                            scope.launch { appState.loadCourseActivities(c, forceRefresh = true) }
                        }
                    },
                    onSelectActivity = { act -> appState.openSignWorkspace(act) },
                )
            }
        }
    }
}

@Composable
private fun CourseActivitiesPane(
    course: ChaoxingCourse?,
    activities: List<ChaoxingSignActivity>,
    isLoading: Boolean,
    hasFailed: Boolean,
    onRefresh: () -> Unit,
    onSelectActivity: (ChaoxingSignActivity) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = course?.courseName ?: "请选择课程",
                    style = CxTypography.title,
                    color = CxPalette.ink,
                )
                Text(
                    text = if (course != null) "共 ${activities.size} 条签到活动 · 点击任意活动进入签到工作台" else "在左侧选择课程或扫码入口",
                    style = CxTypography.caption,
                    color = CxPalette.inkSecondary,
                )
            }
            if (course != null) {
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新活动")
                }
            }
        }
        HorizontalDivider(color = CxPalette.border)

        when {
            course == null -> {
                CxEmptyState(
                    icon = Icons.Outlined.CheckCircle,
                    title = "未选择课程",
                    subtitle = "请在左侧选择一门课程查看课堂签到活动",
                )
            }
            isLoading && activities.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            hasFailed && activities.isEmpty() -> {
                CxEmptyState(
                    icon = Icons.Outlined.Refresh,
                    title = "加载活动失败",
                    subtitle = "请检查网络或刷新当前账号会话后重试",
                    action = { OutlinedButton(onClick = onRefresh) { Text("重试") } },
                )
            }
            activities.isEmpty() -> {
                CxEmptyState(
                    icon = Icons.Outlined.CheckCircle,
                    title = "暂无签到活动",
                    subtitle = "当前课程下未查询到课堂签到记录",
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(activities, key = { it.id }) { act ->
                        ActivityListRow(activity = act, onClick = { onSelectActivity(act) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityListRow(
    activity: ChaoxingSignActivity,
    onClick: () -> Unit,
) {
    val (statusLabel, statusTint) = when {
        activity.isAlreadySignedByStatus -> "已签到" to CxPalette.sage
        !activity.isAvailable || activity.isLate -> "已截止" to CxPalette.inkTertiary
        else -> "进行中 · 待签到" to CxPalette.accent
    }
    val timeText = remember(activity.startTimeMs, activity.endTimeMs) {
        val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
        val start = activity.startTimeMs?.takeIf { it > 0 }?.let { fmt.format(Instant.ofEpochMilli(it)) }
        val end = activity.endTimeMs?.takeIf { it > 0 }?.let { fmt.format(Instant.ofEpochMilli(it)) }
        when {
            start != null && end != null -> "$start ～ $end"
            start != null -> "开始于 $start"
            else -> activity.nameFour.ifBlank { "活动 #${activity.id}" }
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = CxPalette.surface,
        border = BorderStroke(1.dp, CxPalette.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CxBadge(activity.signType.displayName, CxPalette.plum)
                    Text(
                        text = activity.nameOne.ifBlank { activity.signType.displayName },
                        style = CxTypography.headline,
                        color = CxPalette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "$timeText · ID #${activity.id}",
                    style = CxTypography.mono(11.5.sp),
                    color = CxPalette.inkSecondary,
                )
            }
            Spacer(Modifier.width(10.dp))
            CxBadge(statusLabel, statusTint)
        }
    }
}
