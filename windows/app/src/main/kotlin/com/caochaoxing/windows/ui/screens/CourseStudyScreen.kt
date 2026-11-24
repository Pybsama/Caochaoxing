// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.core.model.ChaoxingChapterAttachment
import com.caochaoxing.core.model.ChaoxingChapterNode
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingLiveTask
import com.caochaoxing.core.model.ChaoxingVideoTask
import com.caochaoxing.windows.platform.DesktopSystemTools
import com.caochaoxing.windows.state.DesktopWatchWorker
import com.caochaoxing.windows.state.WindowsAppState
import com.caochaoxing.windows.state.WindowsCourseStudyController
import com.caochaoxing.windows.ui.components.CxBadge
import com.caochaoxing.windows.ui.components.CxCard
import com.caochaoxing.windows.ui.components.CxEmptyState
import com.caochaoxing.windows.ui.components.CxStatTile
import com.caochaoxing.windows.ui.theme.CxPalette
import com.caochaoxing.windows.ui.theme.CxTypography
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Three-pane desktop course study workbench:
 * Left: searchable course list with star pinning
 * Middle: chapter list & chapter task points (video, audio, live replay, PDF/doc)
 * Right: legal multi-way simultaneous study engine dashboard & live execution log
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseStudyScreen(
    appState: WindowsAppState,
    studyController: WindowsCourseStudyController,
) {
    val uiState by appState.uiState.collectAsState()
    val studyState by studyController.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var courseQuery by remember { mutableStateOf("") }

    val filteredCourses = remember(uiState.courses, uiState.starredCourseIds, courseQuery) {
        val q = courseQuery.trim()
        val base = if (q.isEmpty()) {
            uiState.courses
        } else {
            uiState.courses.filter {
                it.courseName.contains(q, ignoreCase = true) || it.teacherName.contains(q, ignoreCase = true)
            }
        }
        base.sortedByDescending { it.courseId in uiState.starredCourseIds }
    }

    val selectedCourse = remember(filteredCourses, uiState.courses, studyState.selectedCourseId) {
        uiState.courses.firstOrNull { it.id == studyState.selectedCourseId } ?: filteredCourses.firstOrNull()
    }

    LaunchedEffect(selectedCourse?.id) {
        if (selectedCourse != null) {
            studyController.loadChapters(selectedCourse)
        }
    }

    val chapterResult = selectedCourse?.let { studyState.chaptersByCourse[it.id] }
    val chapters = remember(chapterResult, studyState.onlyPendingChapters) {
        val raw = chapterResult?.chapters.orEmpty()
        if (studyState.onlyPendingChapters) {
            raw.filter { (it.pendingJobCount ?: 1) > 0 }
        } else {
            raw
        }
    }

    val selectedChapter = remember(chapters, studyState.selectedChapterKey) {
        chapters.firstOrNull { WindowsCourseStudyController.chapterKey(it) == studyState.selectedChapterKey }
    }

    Row(modifier = Modifier.fillMaxSize().background(CxPalette.canvas)) {
        // Column 1: Courses list (responsive ~22%)
        Column(
            modifier = Modifier
                .weight(0.22f)
                .fillMaxHeight()
                .background(CxPalette.surface)
                .padding(10.dp),
        ) {
            OutlinedTextField(
                value = courseQuery,
                onValueChange = { courseQuery = it },
                placeholder = { Text("搜索课程或教师…", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("我的课程 (${filteredCourses.size})", style = CxTypography.caption, color = CxPalette.inkSecondary)
                TextButton(
                    onClick = { scope.launch { appState.refreshCourses() } },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text("刷新", fontSize = 12.sp)
                }
            }

            if (filteredCourses.isEmpty()) {
                CxEmptyState(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "暂无课程",
                    subtitle = "点击刷新以从学习通加载课程",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(filteredCourses, key = { it.id }) { course ->
                        val isSelected = course.id == selectedCourse?.id
                        val isStarred = course.courseId in uiState.starredCourseIds
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) CxPalette.accent.copy(alpha = 0.12f) else CxPalette.canvas,
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) CxPalette.accent.copy(alpha = 0.55f) else CxPalette.border,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { studyController.selectCourse(course) },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = course.courseName,
                                        style = CxTypography.body,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = CxPalette.ink,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (course.teacherName.isNotBlank()) {
                                        Text(
                                            text = course.teacherName,
                                            style = CxTypography.caption,
                                            color = CxPalette.inkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { scope.launch { appState.toggleCourseStar(course.courseId) } },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        imageVector = if (isStarred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                        contentDescription = if (isStarred) "取消标星" else "标星置顶",
                                        tint = if (isStarred) CxPalette.amber else CxPalette.inkTertiary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        VerticalDivider(color = CxPalette.border)

        // Column 2: Chapters & Task Points (responsive ~32%)
        Column(
            modifier = Modifier
                .weight(0.32f)
                .fillMaxHeight()
                .background(CxPalette.canvas)
                .padding(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedCourse?.courseName ?: "未选择课程",
                        style = CxTypography.headline,
                        color = CxPalette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "共 ${chapters.size} 个章节",
                        style = CxTypography.caption,
                        color = CxPalette.inkSecondary,
                    )
                }
                FilterChip(
                    selected = studyState.onlyPendingChapters,
                    onClick = { studyController.setOnlyPendingChapters(!studyState.onlyPendingChapters) },
                    label = { Text("仅未完成", fontSize = 11.5.sp) },
                )
                IconButton(
                    onClick = { selectedCourse?.let { studyController.loadChapters(it, forceRefresh = true) } },
                    enabled = selectedCourse != null,
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新章节", modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            val isLoadingChapters = selectedCourse != null && selectedCourse.id in studyState.loadingChapterCourseIds
            val chapterError = selectedCourse?.let { studyState.chapterErrorsByCourse[it.id] }

            if (isLoadingChapters && chapters.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else if (chapterError != null && chapters.isEmpty()) {
                CxEmptyState(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "章节加载失败",
                    subtitle = chapterError,
                    action = {
                        OutlinedButton(onClick = { selectedCourse?.let { studyController.loadChapters(it, true) } }) {
                            Text("重试")
                        }
                    },
                )
            } else if (chapters.isEmpty()) {
                CxEmptyState(
                    icon = Icons.Outlined.CheckCircle,
                    title = if (studyState.onlyPendingChapters) "无未完成章节" else "暂无章节",
                    subtitle = "点击右上角刷新或关闭“仅未完成”筛选",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(chapters, key = { WindowsCourseStudyController.chapterKey(it) }) { chapter ->
                        val cKey = WindowsCourseStudyController.chapterKey(chapter)
                        val isExpanded = cKey == studyState.selectedChapterKey
                        val content = studyState.contentByChapterKey[cKey]
                        val isContentLoading = cKey in studyState.loadingChapterKeys

                        ChapterCard(
                            course = selectedCourse!!,
                            chapter = chapter,
                            isExpanded = isExpanded,
                            isLoadingContent = isContentLoading,
                            content = content,
                            completingDocIds = studyState.completingDocumentIds,
                            onSelect = { studyController.selectChapter(selectedCourse, chapter) },
                            onRefreshContent = { studyController.loadChapterContent(selectedCourse, chapter, forceRefresh = true) },
                            onWatchVideo = { video -> studyController.startSingleVideoWatch(selectedCourse, chapter, video) },
                            onWatchLive = { live -> studyController.startSingleLiveWatch(selectedCourse, chapter, live) },
                            onCompleteDoc = { att -> studyController.completeDocument(selectedCourse.id, chapter, att) },
                            onOpenExternal = { url ->
                                DesktopSystemTools.openExternalUrl(url).onFailure {
                                    appState.setErrorMessage(it.message)
                                }
                            },
                        )
                    }
                }
            }
        }

        VerticalDivider(color = CxPalette.border)

        // Column 3: Multi-way Study Engine Workbench (responsive ~46%, vertically scrollable on compact windows)
        Column(
            modifier = Modifier
                .weight(0.46f)
                .fillMaxHeight()
                .background(CxPalette.canvas)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Controls card
            CxCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.padding(end = 8.dp)) {
                        Text("多路合法同步看课引擎", style = CxTypography.headline, color = CxPalette.ink)
                        Text(
                            text = if (studyState.isWatching) {
                                "运行中：${studyState.watchScopeLabel} · 防息屏已开启"
                            } else {
                                "严格按服务端倍速（普通 1x/2x，直播回放 30s 心跳）"
                            },
                            style = CxTypography.caption,
                            color = if (studyState.isWatching) CxPalette.sage else CxPalette.inkSecondary,
                        )
                    }
                    if (studyState.isWatching) {
                        Button(
                            onClick = { studyController.stopWatch() },
                            colors = ButtonDefaults.buttonColors(containerColor = CxPalette.cinnabar),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("停止 (${studyState.activeWorkers.size}路)", fontSize = 12.5.sp, maxLines = 1, softWrap = false)
                        }
                    } else {
                        Button(
                            enabled = selectedCourse != null,
                            onClick = {
                                selectedCourse?.let { course ->
                                    studyController.startWatch(course, uiState.courses)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CxPalette.sage),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Outlined.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = if (studyState.watchAllCoursesAutomatically) {
                                    "跨课看 (${studyState.maxConcurrentWatches}路)"
                                } else {
                                    "看本课 (${studyState.maxConcurrentWatches}路)"
                                },
                                fontSize = 12.5.sp,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = CxPalette.border)
                Spacer(Modifier.height(8.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text("并发", style = CxTypography.caption, color = CxPalette.inkSecondary)
                        for (count in listOf(1, 2, 3, 5)) {
                            FilterChip(
                                selected = studyState.maxConcurrentWatches == count,
                                onClick = { studyController.setMaxConcurrentWatches(count) },
                                enabled = !studyState.isWatching,
                                label = { Text("${count}路", fontSize = 11.5.sp, maxLines = 1, softWrap = false) },
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = studyState.watchAllCoursesAutomatically,
                            onCheckedChange = { studyController.setWatchAllCoursesAutomatically(it) },
                            enabled = !studyState.isWatching,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("跨课自动匹配", style = CxTypography.caption, color = CxPalette.ink, maxLines = 1, softWrap = false)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = studyState.autoCompleteDocuments,
                            onCheckedChange = { studyController.setAutoCompleteDocuments(it) },
                            enabled = !studyState.isWatching,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("顺便完成文档", style = CxTypography.caption, color = CxPalette.ink, maxLines = 1, softWrap = false)
                    }
                }

                if (studyState.scanProgress.isNotBlank() || studyState.cooldownRemainingSec > 0) {
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (studyState.scanProgress.isNotBlank()) {
                            CxBadge(studyState.scanProgress, CxPalette.accent)
                        }
                        if (studyState.cooldownRemainingSec > 0) {
                            CxBadge("403 冷却中：${studyState.cooldownRemainingSec}s", CxPalette.cinnabar)
                        }
                    }
                }
            }

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CxStatTile("进行中", "${studyState.activeWorkers.size}", CxPalette.accent, Modifier.weight(1f))
                CxStatTile("待播", "${studyState.queueCount}", CxPalette.ink, Modifier.weight(1f))
                CxStatTile("视频", "${studyState.completedVideosCount}", CxPalette.sage, Modifier.weight(1f))
                CxStatTile("文档", "${studyState.completedDocsCount}", CxPalette.plum, Modifier.weight(1f))
                CxStatTile("跳过", "${studyState.skippedCount}", CxPalette.amber, Modifier.weight(1f))
            }

            // Active Workers List
            CxCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                Text(
                    text = "活跃看课通道 (${studyState.activeWorkers.size})",
                    style = CxTypography.headline,
                    color = CxPalette.ink,
                )
                Spacer(Modifier.height(8.dp))
                if (studyState.activeWorkers.isEmpty()) {
                    CxEmptyState(
                        icon = Icons.Outlined.VideoLibrary,
                        title = if (studyState.isWatching) "正在扫描待完成任务点…" else "当前无正在播放的通道",
                        subtitle = "点击右上角“看本课”或在中间章节栏任选单个视频/直播回放开始",
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (worker in studyState.activeWorkers) {
                            ActiveWorkerRow(
                                worker = worker,
                                onSkip = { studyController.skipWorker(worker.taskId) },
                                onOpenBrowser = { url ->
                                    DesktopSystemTools.openExternalUrl(url).onFailure {
                                        appState.setErrorMessage(it.message)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // Execution Logs
            CxCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("实时运行日志 (${studyState.logs.size})", style = CxTypography.headline, color = CxPalette.ink)
                    if (studyState.logs.isNotEmpty()) {
                        TextButton(onClick = { studyController.clearLogs() }) {
                            Text("清空", fontSize = 12.sp)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (studyState.logs.isEmpty()) {
                    Text("暂无日志，开始看课后将实时显示上报与对账状态。", style = CxTypography.caption, color = CxPalette.inkTertiary)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(studyState.logs, key = { it.id }) { log ->
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "[${log.timeText}] ",
                                    style = CxTypography.mono(11.5.sp),
                                    color = CxPalette.inkTertiary,
                                )
                                Text(
                                    text = log.message,
                                    style = CxTypography.caption,
                                    color = if (log.isError) CxPalette.cinnabar else CxPalette.ink,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(
    course: ChaoxingCourse,
    chapter: ChaoxingChapterNode,
    isExpanded: Boolean,
    isLoadingContent: Boolean,
    content: com.caochaoxing.core.model.ChaoxingChapterContent?,
    completingDocIds: Set<String>,
    onSelect: () -> Unit,
    onRefreshContent: () -> Unit,
    onWatchVideo: (ChaoxingVideoTask) -> Unit,
    onWatchLive: (ChaoxingLiveTask) -> Unit,
    onCompleteDoc: (ChaoxingChapterAttachment) -> Unit,
    onOpenExternal: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = CxPalette.surface,
        border = BorderStroke(1.dp, if (isExpanded) CxPalette.accent.copy(alpha = 0.55f) else CxPalette.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onSelect)
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (chapter.sectionTitle.isNotBlank()) {
                        Text(
                            text = chapter.sectionTitle,
                            style = CxTypography.caption,
                            color = CxPalette.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = chapter.title,
                        style = CxTypography.body,
                        fontWeight = if (isExpanded) FontWeight.SemiBold else FontWeight.Medium,
                        color = CxPalette.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(6.dp))
                when (val pending = chapter.pendingJobCount) {
                    0 -> CxBadge("已完成", CxPalette.sage)
                    null -> CxBadge("待查", CxPalette.inkSecondary)
                    else -> CxBadge("待办 $pending", CxPalette.amber)
                }
            }

            if (isExpanded) {
                HorizontalDivider(color = CxPalette.border)
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isLoadingContent && content == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在读取章节任务点…", style = CxTypography.caption, color = CxPalette.inkSecondary)
                        }
                    } else if (content == null) {
                        OutlinedButton(onClick = onRefreshContent) {
                            Text("重新加载任务点", fontSize = 12.sp)
                        }
                    } else {
                        if (content.videos.isEmpty() && content.liveTasks.isEmpty() && content.otherAttachments.isEmpty()) {
                            Text("本节无识别到的视频、直播或文档任务点", style = CxTypography.caption, color = CxPalette.inkTertiary)
                        }

                        for (video in content.videos) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CxPalette.surfaceSecondary)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PlayCircle,
                                    contentDescription = null,
                                    tint = if (video.isPassed) CxPalette.sage else CxPalette.accent,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = video.name,
                                        style = CxTypography.caption,
                                        fontWeight = FontWeight.Medium,
                                        color = CxPalette.ink,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${video.dtype} · ${WindowsCourseStudyController.formatTime(video.effectiveClipEnd)} · ${WindowsCourseStudyController.formatRate(video.allowedMaxRate)}",
                                        style = CxTypography.mono(11.sp),
                                        color = CxPalette.inkSecondary,
                                    )
                                }
                                if (video.isPassed) {
                                    CxBadge("已通过", CxPalette.sage)
                                } else if (video.isReportableTask) {
                                    TextButton(
                                        onClick = { onWatchVideo(video) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Text("单看", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        for (live in content.liveTasks) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CxPalette.surfaceSecondary)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.LiveTv,
                                    contentDescription = null,
                                    tint = if (live.isPassed) CxPalette.sage else CxPalette.plum,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = live.title,
                                        style = CxTypography.caption,
                                        fontWeight = FontWeight.Medium,
                                        color = CxPalette.ink,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${live.statusDescription} · 已入库 ${String.format(Locale.ROOT, "%.1f", live.percentValue)}%",
                                        style = CxTypography.mono(11.sp),
                                        color = CxPalette.inkSecondary,
                                    )
                                }
                                if (!live.webPageUrl.isNullOrBlank()) {
                                    IconButton(
                                        onClick = { onOpenExternal(live.webPageUrl!!) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(Icons.Outlined.OpenInBrowser, contentDescription = "浏览器打开", modifier = Modifier.size(16.dp))
                                    }
                                }
                                if (live.isPassed) {
                                    CxBadge("已通过", CxPalette.sage)
                                } else if (live.isReportableTask) {
                                    TextButton(
                                        onClick = { onWatchLive(live) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text("看回放", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        for (att in content.otherAttachments) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CxPalette.surfaceSecondary)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Description,
                                    contentDescription = null,
                                    tint = if (att.isPassed) CxPalette.sage else CxPalette.inkSecondary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = att.name,
                                        style = CxTypography.caption,
                                        color = CxPalette.ink,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = att.kind.displayName,
                                        style = CxTypography.caption,
                                        color = CxPalette.inkTertiary,
                                    )
                                }
                                if (att.isPassed) {
                                    CxBadge("已完成", CxPalette.sage)
                                } else if (att.canCompleteAsDocument) {
                                    val busy = att.id in completingDocIds
                                    TextButton(
                                        enabled = !busy,
                                        onClick = { onCompleteDoc(att) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    ) {
                                        Text(if (busy) "提交中…" else "完成文档", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveWorkerRow(
    worker: DesktopWatchWorker,
    onSkip: () -> Unit,
    onOpenBrowser: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = CxPalette.surfaceSecondary,
        border = BorderStroke(1.dp, CxPalette.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CxBadge(
                            text = if (worker.isLiveReplay) "直播回放" else WindowsCourseStudyController.formatRate(worker.speedMultiplier),
                            tint = if (worker.isLiveReplay) CxPalette.plum else CxPalette.accent,
                        )
                        Text(
                            text = worker.videoName,
                            style = CxTypography.body,
                            fontWeight = FontWeight.SemiBold,
                            color = CxPalette.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${worker.courseName} · ${worker.chapterTitle}",
                        style = CxTypography.caption,
                        color = CxPalette.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val externalUrl = worker.webPageUrl ?: worker.playUrl
                    if (!externalUrl.isNullOrBlank()) {
                        IconButton(
                            onClick = { onOpenBrowser(externalUrl) },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(Icons.Outlined.OpenInBrowser, contentDescription = "浏览器打开", modifier = Modifier.size(16.dp))
                        }
                    }
                    TextButton(
                        enabled = !worker.isSkipping,
                        onClick = onSkip,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Outlined.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(if (worker.isSkipping) "跳过中" else "跳过", fontSize = 12.sp)
                    }
                }
            }

            LinearProgressIndicator(
                progress = { worker.progressFraction },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = if (worker.isLiveReplay) CxPalette.plum else CxPalette.sage,
                trackColor = CxPalette.border,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = worker.statusText,
                    style = CxTypography.caption,
                    color = CxPalette.inkSecondary,
                )
                Text(
                    text = "${WindowsCourseStudyController.formatTime(worker.displaySeconds)} / ${WindowsCourseStudyController.formatTime(worker.duration)} · 下次心跳 ${worker.nextReportCountdownSec}s",
                    style = CxTypography.mono(11.5.sp),
                    color = CxPalette.inkTertiary,
                )
            }
        }
    }
}
