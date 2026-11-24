// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.caochaoxing.core.model.ChaoxingChapterAttachment
import com.caochaoxing.core.model.ChaoxingChapterContent
import com.caochaoxing.core.model.ChaoxingChapterNode
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingCourseChaptersResult
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingLiveHeartbeatResponse
import com.caochaoxing.core.model.ChaoxingLiveInfo
import com.caochaoxing.core.model.ChaoxingLiveSessionContext
import com.caochaoxing.core.model.ChaoxingLiveTask
import com.caochaoxing.core.model.ChaoxingVideoDragEvent
import com.caochaoxing.core.model.ChaoxingVideoLogResponse
import com.caochaoxing.core.model.ChaoxingVideoTask
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.service.ChaoxingCourseStudyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

interface WindowsCourseStudyDependencies {
    suspend fun fetchCourseChapters(
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        knownCpi: Long,
    ): ChaoxingCourseChaptersResult

    suspend fun fetchChapterContent(
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        chapterId: String,
        cpi: Long,
    ): ChaoxingChapterContent

    suspend fun sendVideoLog(
        client: ChaoxingHttpClient,
        task: ChaoxingVideoTask,
        playingTimeSec: Int,
        event: ChaoxingVideoDragEvent,
    ): ChaoxingVideoLogResponse

    suspend fun completeDocumentTask(
        client: ChaoxingHttpClient,
        attachment: ChaoxingChapterAttachment,
    ): Boolean

    suspend fun fetchLiveInfo(
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
    ): ChaoxingLiveInfo = ChaoxingLiveInfo(
        resolvedLiveId = task.liveId,
        title = task.title,
        liveStatus = task.liveStatus,
        ifReview = task.ifReview,
        duration = task.duration,
        playbackTimelongMs = task.playbackTimelongMs,
        percentValue = task.percentValue,
        streamName = task.streamName,
        vdoid = task.vdoid,
        m3u8Url = task.playUrl,
    )

    suspend fun prepareLiveReplaySession(
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
    ): ChaoxingLiveSessionContext = ChaoxingLiveSessionContext(
        pageUrl = task.webPageUrl.orEmpty(),
        shortLiveId = task.shortLiveId,
        uInfo = "",
        streamName = task.streamName,
        vdoid = task.vdoid,
        duration = task.duration,
        playUrl = task.playUrl,
    )

    suspend fun sendLiveHeartbeat(
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
        session: ChaoxingLiveSessionContext,
        isStart: Boolean,
        watchMomentSec: Int,
    ): ChaoxingLiveHeartbeatResponse = ChaoxingLiveHeartbeatResponse(
        result = 1,
        accepted = true,
        duration = task.duration,
        liveStatus = task.liveStatus,
    )

    suspend fun sleep(delayMs: Long)

    fun nowMs(): Long
}

class LiveWindowsCourseStudyDependencies : WindowsCourseStudyDependencies {
    override suspend fun fetchCourseChapters(
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        knownCpi: Long,
    ): ChaoxingCourseChaptersResult =
        ChaoxingCourseStudyService.fetchCourseChapters(client, courseId, classId, knownCpi)

    override suspend fun fetchChapterContent(
        client: ChaoxingHttpClient,
        courseId: Long,
        classId: Int,
        chapterId: String,
        cpi: Long,
    ): ChaoxingChapterContent =
        ChaoxingCourseStudyService.fetchChapterContent(client, courseId, classId, chapterId, cpi)

    override suspend fun sendVideoLog(
        client: ChaoxingHttpClient,
        task: ChaoxingVideoTask,
        playingTimeSec: Int,
        event: ChaoxingVideoDragEvent,
    ): ChaoxingVideoLogResponse =
        ChaoxingCourseStudyService.sendVideoLog(client, task, playingTimeSec, event)

    override suspend fun completeDocumentTask(
        client: ChaoxingHttpClient,
        attachment: ChaoxingChapterAttachment,
    ): Boolean =
        ChaoxingCourseStudyService.completeDocumentTask(client, attachment)

    override suspend fun fetchLiveInfo(
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
    ): ChaoxingLiveInfo =
        ChaoxingCourseStudyService.fetchLiveInfo(client, task)

    override suspend fun prepareLiveReplaySession(
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
    ): ChaoxingLiveSessionContext =
        ChaoxingCourseStudyService.prepareLiveReplaySession(client, task)

    override suspend fun sendLiveHeartbeat(
        client: ChaoxingHttpClient,
        task: ChaoxingLiveTask,
        session: ChaoxingLiveSessionContext,
        isStart: Boolean,
        watchMomentSec: Int,
    ): ChaoxingLiveHeartbeatResponse =
        ChaoxingCourseStudyService.sendLiveHeartbeat(client, task, session, isStart, watchMomentSec)

    override suspend fun sleep(delayMs: Long) {
        delay(delayMs)
    }

    override fun nowMs(): Long = System.currentTimeMillis()
}

@Immutable
data class StudyLogEntry(
    val id: Long,
    val timeText: String,
    val message: String,
    val isError: Boolean = false,
)

@Immutable
data class DesktopWatchWorker(
    val taskId: String,
    val courseKey: String,
    val courseName: String,
    val chapterKey: String,
    val chapterTitle: String,
    val videoName: String,
    val duration: Int,
    val confirmedSeconds: Int,
    val displaySeconds: Int,
    val speedMultiplier: Double,
    val requiresFaceCapture: Boolean,
    val nextReportCountdownSec: Int,
    val statusText: String,
    val isSkipping: Boolean = false,
    val isLiveReplay: Boolean = false,
    val webPageUrl: String? = null,
    val playUrl: String? = null,
) {
    val progressFraction: Float
        get() = if (duration <= 0) 0f else (displaySeconds.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
}

@Immutable
data class WindowsCourseStudyUiState(
    val activeAccountPhone: String? = null,
    val selectedCourseId: String? = null,
    val selectedChapterKey: String? = null,
    val chaptersByCourse: Map<String, ChaoxingCourseChaptersResult> = emptyMap(),
    val loadingChapterCourseIds: Set<String> = emptySet(),
    val chapterErrorsByCourse: Map<String, String> = emptyMap(),
    val contentByChapterKey: Map<String, ChaoxingChapterContent> = emptyMap(),
    val loadingChapterKeys: Set<String> = emptySet(),
    val completingDocumentIds: Set<String> = emptySet(),
    val onlyPendingChapters: Boolean = false,
    val maxConcurrentWatches: Int = 3,
    val watchAllCoursesAutomatically: Boolean = false,
    val autoCompleteDocuments: Boolean = true,
    val isWatching: Boolean = false,
    val watchScopeLabel: String = "",
    val scanProgress: String = "",
    val cooldownRemainingSec: Int = 0,
    val queueCount: Int = 0,
    val completedVideosCount: Int = 0,
    val completedDocsCount: Int = 0,
    val skippedCount: Int = 0,
    val totalDiscoveredCount: Int = 0,
    val activeWorkers: List<DesktopWatchWorker> = emptyList(),
    val logs: List<StudyLogEntry> = emptyList(),
)

/**
 * Desktop legal multi-way course study engine supporting:
 * - Standard video/audio heartbeats at server-permitted 1x/2x speeds
 * - Document/PDF task completion
 * - External `zhibo.chaoxing.com` live-replay 29s/30s real-clock heartbeats with 60s server reconciliation
 * - Automatic 403 `restTime` concurrency downgrade & cooldown
 * - Strict per-account isolation and Windows keep-awake notifications
 */
@Stable
class WindowsCourseStudyController(
    private val clientProvider: () -> ChaoxingHttpClient?,
    private val scope: CoroutineScope,
    private val dependencies: WindowsCourseStudyDependencies = LiveWindowsCourseStudyDependencies(),
    private val onWatchingStateChanged: (Boolean) -> Unit = {},
) {
    constructor(
        appState: WindowsAppState,
        scope: CoroutineScope,
        dependencies: WindowsCourseStudyDependencies = LiveWindowsCourseStudyDependencies(),
        onWatchingStateChanged: (Boolean) -> Unit = {},
    ) : this(
        clientProvider = { appState.activeClient },
        scope = scope,
        dependencies = dependencies,
        onWatchingStateChanged = onWatchingStateChanged,
    )

    private val _uiState = MutableStateFlow(WindowsCourseStudyUiState())
    val uiState: StateFlow<WindowsCourseStudyUiState> = _uiState.asStateFlow()

    private val logSeq = AtomicLong(0L)
    private val generation = AtomicLong(0L)
    private val watchSessionId = AtomicLong(0L)
    private val skipTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val activeChapterJobs = ConcurrentHashMap<String, Job>()
    private var watchJob: Job? = null

    private sealed interface QueuedStudyItem {
        val course: ChaoxingCourse
        val chapter: ChaoxingChapterNode
        val chapterKey: String
        val taskId: String

        data class Video(
            override val course: ChaoxingCourse,
            override val chapter: ChaoxingChapterNode,
            override val chapterKey: String,
            val video: ChaoxingVideoTask,
        ) : QueuedStudyItem {
            override val taskId: String get() = video.id
        }

        data class Live(
            override val course: ChaoxingCourse,
            override val chapter: ChaoxingChapterNode,
            override val chapterKey: String,
            val live: ChaoxingLiveTask,
        ) : QueuedStudyItem {
            override val taskId: String get() = live.id
        }
    }

    fun onAccountChanged(phoneNumber: String?) {
        val current = _uiState.value.activeAccountPhone
        if (current == phoneNumber && !isAccountResetNeeded(phoneNumber)) return
        generation.incrementAndGet()
        watchSessionId.incrementAndGet()
        watchJob?.cancel()
        watchJob = null
        activeChapterJobs.values.forEach { it.cancel() }
        activeChapterJobs.clear()
        skipTaskIds.clear()
        onWatchingStateChanged(false)
        _uiState.update { state ->
            WindowsCourseStudyUiState(
                activeAccountPhone = phoneNumber,
                onlyPendingChapters = state.onlyPendingChapters,
                maxConcurrentWatches = state.maxConcurrentWatches,
                watchAllCoursesAutomatically = state.watchAllCoursesAutomatically,
                autoCompleteDocuments = state.autoCompleteDocuments,
            )
        }
    }

    private fun isAccountResetNeeded(phoneNumber: String?): Boolean =
        phoneNumber == null && (
            _uiState.value.chaptersByCourse.isNotEmpty() ||
                _uiState.value.isWatching ||
                _uiState.value.contentByChapterKey.isNotEmpty()
            )

    fun selectCourse(course: ChaoxingCourse) {
        _uiState.update { it.copy(selectedCourseId = course.id, selectedChapterKey = null) }
        loadChapters(course, forceRefresh = false)
    }

    fun selectChapter(course: ChaoxingCourse, chapter: ChaoxingChapterNode) {
        val key = chapterKey(chapter)
        _uiState.update { it.copy(selectedCourseId = course.id, selectedChapterKey = key) }
        loadChapterContent(course, chapter, forceRefresh = false)
    }

    fun setOnlyPendingChapters(enabled: Boolean) {
        _uiState.update { it.copy(onlyPendingChapters = enabled) }
    }

    fun setMaxConcurrentWatches(count: Int) {
        _uiState.update { it.copy(maxConcurrentWatches = count.coerceIn(1, 5)) }
    }

    fun setWatchAllCoursesAutomatically(enabled: Boolean) {
        _uiState.update { it.copy(watchAllCoursesAutomatically = enabled) }
    }

    fun setAutoCompleteDocuments(enabled: Boolean) {
        _uiState.update { it.copy(autoCompleteDocuments = enabled) }
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    fun loadChapters(course: ChaoxingCourse, forceRefresh: Boolean = false) {
        val courseKey = course.id
        if (!forceRefresh && courseKey in _uiState.value.chaptersByCourse) return
        val client = clientProvider() ?: return
        val gen = generation.get()

        activeChapterJobs[courseKey]?.cancel()
        val job = scope.launch {
            _uiState.update { state ->
                state.copy(
                    loadingChapterCourseIds = state.loadingChapterCourseIds + courseKey,
                    chapterErrorsByCourse = state.chapterErrorsByCourse - courseKey,
                )
            }
            try {
                val result = dependencies.fetchCourseChapters(
                    client = client,
                    courseId = course.courseId,
                    classId = course.classId,
                    knownCpi = course.cpi ?: 0L,
                )
                if (generation.get() != gen) return@launch
                _uiState.update { state ->
                    state.copy(
                        chaptersByCourse = state.chaptersByCourse + (courseKey to result),
                        loadingChapterCourseIds = state.loadingChapterCourseIds - courseKey,
                        chapterErrorsByCourse = state.chapterErrorsByCourse - courseKey,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation.get() != gen) return@launch
                val msg = e.message ?: "章节目录加载失败"
                _uiState.update { state ->
                    state.copy(
                        loadingChapterCourseIds = state.loadingChapterCourseIds - courseKey,
                        chapterErrorsByCourse = state.chapterErrorsByCourse + (courseKey to msg),
                    )
                }
                appendLog("「${course.courseName}」章节加载失败：$msg", isError = true)
            } finally {
                if (generation.get() == gen) {
                    _uiState.update { it.copy(loadingChapterCourseIds = it.loadingChapterCourseIds - courseKey) }
                }
            }
        }
        activeChapterJobs[courseKey] = job
    }

    fun loadChapterContent(
        course: ChaoxingCourse,
        chapter: ChaoxingChapterNode,
        forceRefresh: Boolean = false,
    ) {
        val key = chapterKey(chapter)
        if (!forceRefresh && key in _uiState.value.contentByChapterKey) return
        val client = clientProvider() ?: return
        val gen = generation.get()
        val knownCpi = _uiState.value.chaptersByCourse[course.id]?.cpi ?: course.cpi ?: 0L

        scope.launch {
            _uiState.update { it.copy(loadingChapterKeys = it.loadingChapterKeys + key) }
            try {
                val content = dependencies.fetchChapterContent(
                    client = client,
                    courseId = chapter.courseId,
                    classId = chapter.classId,
                    chapterId = chapter.id,
                    cpi = knownCpi,
                )
                if (generation.get() != gen) return@launch
                _uiState.update { state ->
                    state.copy(
                        contentByChapterKey = state.contentByChapterKey + (key to content),
                        loadingChapterKeys = state.loadingChapterKeys - key,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation.get() != gen) return@launch
                _uiState.update { it.copy(loadingChapterKeys = it.loadingChapterKeys - key) }
                appendLog("「${chapter.title}」任务点读取失败：${e.message ?: "未知错误"}", isError = true)
            }
        }
    }

    fun completeDocument(
        courseKey: String,
        chapter: ChaoxingChapterNode,
        attachment: ChaoxingChapterAttachment,
    ) {
        if (!attachment.canCompleteAsDocument) return
        val client = clientProvider() ?: return
        val gen = generation.get()
        val key = chapterKey(chapter)

        scope.launch {
            _uiState.update { it.copy(completingDocumentIds = it.completingDocumentIds + attachment.id) }
            try {
                val ok = dependencies.completeDocumentTask(client, attachment)
                if (generation.get() != gen) return@launch
                if (ok) {
                    markAttachmentPassed(courseKey, key, chapter.id, attachment.id)
                    appendLog("文档已完成：${attachment.name}")
                } else {
                    appendLog("文档上报未通过：${attachment.name}", isError = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation.get() != gen) return@launch
                appendLog("文档完成失败（${attachment.name}）：${e.message ?: "未知错误"}", isError = true)
            } finally {
                if (generation.get() == gen) {
                    _uiState.update { it.copy(completingDocumentIds = it.completingDocumentIds - attachment.id) }
                }
            }
        }
    }

    fun startSingleVideoWatch(
        course: ChaoxingCourse,
        chapter: ChaoxingChapterNode,
        video: ChaoxingVideoTask,
    ) {
        val client = clientProvider()
        if (client == null) {
            appendLog("当前未登录，无法启动视频挂机", isError = true)
            return
        }
        if (video.isPassed) {
            appendLog("视频「${video.name}」已完成，无需重复挂机")
            return
        }
        if (!video.isReportableTask) {
            appendLog("视频「${video.name}」缺少上报凭证或不是任务点", isError = true)
            return
        }

        stopWatch(logStopped = false)
        val gen = generation.get()
        val myWatchId = watchSessionId.incrementAndGet()
        skipTaskIds.clear()
        onWatchingStateChanged(true)

        watchJob = scope.launch {
            _uiState.update { state ->
                state.copy(
                    isWatching = true,
                    watchScopeLabel = "单视频：${video.name}",
                    scanProgress = "",
                    cooldownRemainingSec = 0,
                    queueCount = 1,
                    completedVideosCount = 0,
                    completedDocsCount = 0,
                    skippedCount = 0,
                    totalDiscoveredCount = 1,
                    activeWorkers = emptyList(),
                )
            }
            appendLog("开始合法挂机单视频：${video.name}（${formatRate(video.allowedMaxRate)}）")

            val effectiveConcurrency = AtomicInteger(1)
            val cooldownUntilMs = AtomicLong(0L)
            try {
                runLegalVideoWorker(
                    client = client,
                    queued = QueuedStudyItem.Video(course, chapter, chapterKey(chapter), video),
                    gen = gen,
                    effectiveConcurrency = effectiveConcurrency,
                    cooldownUntilMs = cooldownUntilMs,
                )
            } finally {
                if (generation.get() == gen && watchSessionId.get() == myWatchId) {
                    onWatchingStateChanged(false)
                    _uiState.update { state ->
                        state.copy(
                            isWatching = false,
                            scanProgress = "",
                            cooldownRemainingSec = 0,
                            queueCount = 0,
                            activeWorkers = emptyList(),
                        )
                    }
                }
            }
        }
    }

    fun startSingleLiveWatch(
        course: ChaoxingCourse,
        chapter: ChaoxingChapterNode,
        live: ChaoxingLiveTask,
    ) {
        val client = clientProvider()
        if (client == null) {
            appendLog("当前未登录，无法启动直播回放挂机", isError = true)
            return
        }
        if (live.isPassed) {
            appendLog("直播回放「${live.title}」已完成，无需重复挂机")
            return
        }
        if (!live.isReportableTask) {
            appendLog("直播回放「${live.title}」当前不可上报（${live.statusDescription}）", isError = true)
            return
        }

        stopWatch(logStopped = false)
        val gen = generation.get()
        val myWatchId = watchSessionId.incrementAndGet()
        skipTaskIds.clear()
        onWatchingStateChanged(true)

        watchJob = scope.launch {
            _uiState.update { state ->
                state.copy(
                    isWatching = true,
                    watchScopeLabel = "单场直播回放：${live.title}",
                    scanProgress = "",
                    cooldownRemainingSec = 0,
                    queueCount = 1,
                    completedVideosCount = 0,
                    completedDocsCount = 0,
                    skippedCount = 0,
                    totalDiscoveredCount = 1,
                    activeWorkers = emptyList(),
                )
            }
            appendLog("开始合法挂机直播回放：${live.title}（zhibo.chaoxing.com 每 30s 心跳）")

            val effectiveConcurrency = AtomicInteger(1)
            val cooldownUntilMs = AtomicLong(0L)
            try {
                runLegalLiveWorker(
                    client = client,
                    queued = QueuedStudyItem.Live(course, chapter, chapterKey(chapter), live),
                    gen = gen,
                    effectiveConcurrency = effectiveConcurrency,
                    cooldownUntilMs = cooldownUntilMs,
                )
            } finally {
                if (generation.get() == gen && watchSessionId.get() == myWatchId) {
                    onWatchingStateChanged(false)
                    _uiState.update { state ->
                        state.copy(
                            isWatching = false,
                            scanProgress = "",
                            cooldownRemainingSec = 0,
                            queueCount = 0,
                            activeWorkers = emptyList(),
                        )
                    }
                }
            }
        }
    }

    fun startWatch(primaryCourse: ChaoxingCourse, allCourses: List<ChaoxingCourse>) {
        val client = clientProvider()
        if (client == null) {
            appendLog("当前未登录，无法开始看课", isError = true)
            return
        }

        stopWatch(logStopped = false)
        val gen = generation.get()
        val myWatchId = watchSessionId.incrementAndGet()
        skipTaskIds.clear()

        val snapshot = _uiState.value
        val includeOthers = snapshot.watchAllCoursesAutomatically
        val targetCourses = if (includeOthers) {
            listOf(primaryCourse) + allCourses.filter { it.id != primaryCourse.id }
        } else {
            listOf(primaryCourse)
        }
        val configuredConcurrency = snapshot.maxConcurrentWatches.coerceIn(1, 5)
        val autoDocs = snapshot.autoCompleteDocuments
        val scopeLabel = if (includeOthers && targetCourses.size > 1) {
            "全部课程 (${targetCourses.size} 门)"
        } else {
            "当前课程：${primaryCourse.courseName}"
        }

        onWatchingStateChanged(true)
        watchJob = scope.launch {
            _uiState.update { state ->
                state.copy(
                    isWatching = true,
                    watchScopeLabel = scopeLabel,
                    scanProgress = "准备扫描章节…",
                    cooldownRemainingSec = 0,
                    queueCount = 0,
                    completedVideosCount = 0,
                    completedDocsCount = 0,
                    skippedCount = 0,
                    totalDiscoveredCount = 0,
                    activeWorkers = emptyList(),
                )
            }
            appendLog(
                "开始同时看课：$scopeLabel（最多 $configuredConcurrency 路并发${if (autoDocs) "，顺便完成文档" else ""}）",
            )

            val effectiveConcurrency = AtomicInteger(configuredConcurrency)
            val cooldownUntilMs = AtomicLong(0L)
            val handledTaskIds = ConcurrentHashMap.newKeySet<String>()
            val completedDocIds = ConcurrentHashMap.newKeySet<String>()

            try {
                for (pass in 1..MAX_SCAN_PASSES) {
                    ensureActive()
                    val queuedCountInPass = AtomicInteger(0)
                    val channel = Channel<QueuedStudyItem>(Channel.UNLIMITED)
                    val pendingQueueCounter = AtomicInteger(0)

                    coroutineScope {
                        val consumers = (0 until configuredConcurrency).map { slotIndex ->
                            launch {
                                while (slotIndex < effectiveConcurrency.get()) {
                                    val received = channel.receiveCatching()
                                    val item = received.getOrNull() ?: break
                                    val remaining = pendingQueueCounter.decrementAndGet().coerceAtLeast(0)
                                    _uiState.update { it.copy(queueCount = remaining) }
                                    when (item) {
                                        is QueuedStudyItem.Video -> runLegalVideoWorker(
                                            client = client,
                                            queued = item,
                                            gen = gen,
                                            effectiveConcurrency = effectiveConcurrency,
                                            cooldownUntilMs = cooldownUntilMs,
                                        )
                                        is QueuedStudyItem.Live -> runLegalLiveWorker(
                                            client = client,
                                            queued = item,
                                            gen = gen,
                                            effectiveConcurrency = effectiveConcurrency,
                                            cooldownUntilMs = cooldownUntilMs,
                                        )
                                    }
                                }
                            }
                        }

                        try {
                            val scanSem = Semaphore(CHAPTER_SCAN_CONCURRENCY)
                            for ((courseIndex, course) in targetCourses.withIndex()) {
                                ensureActive()
                                val passPrefix = if (pass > 1) "第 $pass 轮复查 · " else ""
                                _uiState.update {
                                    it.copy(
                                        scanProgress = "${passPrefix}正在获取「${course.courseName}」章节 (${courseIndex + 1}/${targetCourses.size})",
                                    )
                                }
                                val chapterResult = try {
                                    val fetched = dependencies.fetchCourseChapters(
                                        client = client,
                                        courseId = course.courseId,
                                        classId = course.classId,
                                        knownCpi = _uiState.value.chaptersByCourse[course.id]?.cpi
                                            ?: course.cpi
                                            ?: 0L,
                                    )
                                    if (generation.get() == gen) {
                                        _uiState.update { state ->
                                            state.copy(
                                                chaptersByCourse = state.chaptersByCourse + (course.id to fetched),
                                                chapterErrorsByCourse = state.chapterErrorsByCourse - course.id,
                                            )
                                        }
                                    }
                                    fetched
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    appendLog("扫描「${course.courseName}」章节失败：${e.message ?: "未知错误"}", isError = true)
                                    continue
                                }

                                val candidateChapters = chapterResult.chapters.filter { it.pendingJobCount != 0 }
                                if (candidateChapters.isEmpty()) continue

                                val scannedCount = AtomicInteger(0)
                                candidateChapters.map { chapter ->
                                    async {
                                        scanSem.withPermit {
                                            ensureActive()
                                            val key = chapterKey(chapter)
                                            val content = try {
                                                dependencies.fetchChapterContent(
                                                    client = client,
                                                    courseId = chapter.courseId,
                                                    classId = chapter.classId,
                                                    chapterId = chapter.id,
                                                    cpi = chapterResult.cpi,
                                                )
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Throwable) {
                                                appendLog("读取「${chapter.title}」失败：${e.message ?: "未知错误"}", isError = true)
                                                return@withPermit
                                            }

                                            if (generation.get() == gen) {
                                                _uiState.update { state ->
                                                    state.copy(contentByChapterKey = state.contentByChapterKey + (key to content))
                                                }
                                            }

                                            for (video in content.videos) {
                                                if (!video.isPassed && video.isReportableTask && handledTaskIds.add(video.id)) {
                                                    queuedCountInPass.incrementAndGet()
                                                    val q = pendingQueueCounter.incrementAndGet()
                                                    _uiState.update { state ->
                                                        state.copy(
                                                            queueCount = q,
                                                            totalDiscoveredCount = state.totalDiscoveredCount + 1,
                                                        )
                                                    }
                                                    channel.send(QueuedStudyItem.Video(course, chapter, key, video))
                                                }
                                            }

                                            for (live in content.liveTasks) {
                                                if (!live.isPassed && live.isReportableTask && handledTaskIds.add(live.id)) {
                                                    queuedCountInPass.incrementAndGet()
                                                    val q = pendingQueueCounter.incrementAndGet()
                                                    _uiState.update { state ->
                                                        state.copy(
                                                            queueCount = q,
                                                            totalDiscoveredCount = state.totalDiscoveredCount + 1,
                                                        )
                                                    }
                                                    channel.send(QueuedStudyItem.Live(course, chapter, key, live))
                                                }
                                            }

                                            if (autoDocs) {
                                                for (att in content.otherAttachments) {
                                                    if (att.canCompleteAsDocument && completedDocIds.add(att.id)) {
                                                        try {
                                                            val ok = dependencies.completeDocumentTask(client, att)
                                                            if (ok && generation.get() == gen) {
                                                                markAttachmentPassed(course.id, key, chapter.id, att.id)
                                                                _uiState.update {
                                                                    it.copy(completedDocsCount = it.completedDocsCount + 1)
                                                                }
                                                                appendLog("已自动完成文档：${att.name}")
                                                            }
                                                        } catch (e: CancellationException) {
                                                            throw e
                                                        } catch (e: Throwable) {
                                                            appendLog(
                                                                "自动完成文档失败（${att.name}）：${e.message ?: "未知错误"}",
                                                                isError = true,
                                                            )
                                                        }
                                                    }
                                                }
                                            }

                                            val done = scannedCount.incrementAndGet()
                                            if (generation.get() == gen) {
                                                _uiState.update {
                                                    it.copy(
                                                        scanProgress = "${passPrefix}「${course.courseName}」已扫描 $done/${candidateChapters.size} 节",
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }.awaitAll()
                            }
                        } finally {
                            channel.close()
                        }

                        if (generation.get() == gen) {
                            _uiState.update { it.copy(scanProgress = "扫描完成，等待视频队列收尾…") }
                        }
                        consumers.forEach { it.join() }
                    }

                    if (queuedCountInPass.get() == 0) {
                        if (pass == 1) {
                            appendLog("未发现待完成的视频任务点")
                        }
                        break
                    }
                }

                if (generation.get() == gen) {
                    val finalState = _uiState.value
                    appendLog(
                        "本次看课结束：视频/回放完成 ${finalState.completedVideosCount} 个，文档完成 ${finalState.completedDocsCount} 个" +
                            if (finalState.skippedCount > 0) "，跳过 ${finalState.skippedCount} 个" else "",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation.get() == gen) {
                    appendLog("同时看课异常中断：${e.message ?: "未知错误"}", isError = true)
                }
            } finally {
                if (generation.get() == gen && watchSessionId.get() == myWatchId) {
                    onWatchingStateChanged(false)
                    _uiState.update { state ->
                        state.copy(
                            isWatching = false,
                            scanProgress = "",
                            cooldownRemainingSec = 0,
                            queueCount = 0,
                            activeWorkers = emptyList(),
                        )
                    }
                }
            }
        }
    }

    fun skipWorker(taskId: String) {
        skipTaskIds.add(taskId)
        _uiState.update { state ->
            state.copy(
                activeWorkers = state.activeWorkers.map { worker ->
                    if (worker.taskId == taskId) {
                        worker.copy(isSkipping = true, statusText = "正在跳过…")
                    } else {
                        worker
                    }
                },
            )
        }
    }

    fun stopWatch(logStopped: Boolean = true) {
        val wasRunning = watchJob?.isActive == true || _uiState.value.isWatching
        watchSessionId.incrementAndGet()
        watchJob?.cancel()
        watchJob = null
        skipTaskIds.clear()
        onWatchingStateChanged(false)
        _uiState.update { state ->
            state.copy(
                isWatching = false,
                scanProgress = "",
                cooldownRemainingSec = 0,
                queueCount = 0,
                activeWorkers = emptyList(),
            )
        }
        if (logStopped && wasRunning) {
            appendLog("已手动停止看课")
        }
    }

    private suspend fun runLegalVideoWorker(
        client: ChaoxingHttpClient,
        queued: QueuedStudyItem.Video,
        gen: Long,
        effectiveConcurrency: AtomicInteger,
        cooldownUntilMs: AtomicLong,
    ) {
        val task = queued.video
        val clipStart = task.effectiveClipStart
        val targetEnd = task.effectiveClipEnd
        if (targetEnd <= clipStart) {
            appendLog("跳过无效时长视频：${task.name}")
            incrementSkipped(gen)
            return
        }

        val speed = task.allowedMaxRate
        val reportInterval = task.reportTimeInterval.coerceIn(15, 60)
        var currentPos = max(clipStart, min(task.headOffsetMs / 1000, targetEnd))

        upsertWorker(
            gen,
            DesktopWatchWorker(
                taskId = task.id,
                courseKey = queued.course.id,
                courseName = queued.course.courseName,
                chapterKey = queued.chapterKey,
                chapterTitle = queued.chapter.title,
                videoName = task.name,
                duration = targetEnd,
                confirmedSeconds = currentPos,
                displaySeconds = currentPos,
                speedMultiplier = speed,
                requiresFaceCapture = task.requiresFaceCapture,
                nextReportCountdownSec = reportInterval,
                statusText = "发送起播心跳…",
                playUrl = task.videoPlayUrl,
            ),
        )

        var retry403Count = 0
        var transientErrorCount = 0

        try {
            waitForCooldownIfNeeded(gen, task.id, cooldownUntilMs)
            if (isSkipped(task.id)) {
                appendLog("已跳过视频：${task.name}")
                incrementSkipped(gen)
                return
            }

            var startReported = false
            while (!startReported) {
                currentCoroutineContext().ensureActive()
                if (isSkipped(task.id)) {
                    appendLog("已跳过视频：${task.name}")
                    incrementSkipped(gen)
                    return
                }
                try {
                    val initRes = dependencies.sendVideoLog(client, task, currentPos, ChaoxingVideoDragEvent.PLAY)
                    startReported = true
                    if (initRes.isPassed) {
                        markVideoPassed(queued.course.id, queued.chapterKey, queued.chapter.id, task.id, targetEnd)
                        incrementCompletedVideos(gen)
                        appendLog("视频已通过：${task.name}")
                        return
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val handled = handleWorkerError(
                        error = e,
                        taskName = task.name,
                        gen = gen,
                        taskId = task.id,
                        effectiveConcurrency = effectiveConcurrency,
                        cooldownUntilMs = cooldownUntilMs,
                        retry403Count = retry403Count,
                        transientErrorCount = transientErrorCount,
                    )
                    if (handled.isRateLimit) retry403Count += 1 else transientErrorCount += 1
                    if (!handled.shouldRetry) {
                        incrementSkipped(gen)
                        return
                    }
                    waitForCooldownIfNeeded(gen, task.id, cooldownUntilMs)
                }
            }

            var postEndRetries = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (isSkipped(task.id)) {
                    appendLog("已跳过视频：${task.name}")
                    incrementSkipped(gen)
                    return
                }

                waitForCooldownIfNeeded(gen, task.id, cooldownUntilMs)
                if (isSkipped(task.id)) {
                    appendLog("已跳过视频：${task.name}")
                    incrementSkipped(gen)
                    return
                }

                val remainingMedia = max(0, targetEnd - currentPos)
                val stepWall = if (remainingMedia == 0) {
                    3
                } else {
                    val remainingWall = max(1, ceil(remainingMedia / speed).toInt())
                    min(reportInterval, remainingWall)
                }

                for (waited in 1..stepWall) {
                    currentCoroutineContext().ensureActive()
                    if (isSkipped(task.id)) {
                        appendLog("已跳过视频：${task.name}")
                        incrementSkipped(gen)
                        return
                    }
                    dependencies.sleep(1_000L)
                    val visualPos = min(targetEnd, currentPos + (waited * speed).toInt())
                    updateWorker(gen, task.id) { w ->
                        w.copy(
                            displaySeconds = visualPos,
                            nextReportCountdownSec = max(0, stepWall - waited),
                            statusText = "合法播放中 (${formatRate(speed)})",
                        )
                    }
                }

                val nextPos = if (remainingMedia == 0) {
                    targetEnd
                } else {
                    val remainingWall = max(1, ceil(remainingMedia / speed).toInt())
                    if (stepWall >= remainingWall) targetEnd else min(targetEnd, currentPos + (stepWall * speed).toInt())
                }
                val dragEvent = if (nextPos >= targetEnd) ChaoxingVideoDragEvent.ENDED else ChaoxingVideoDragEvent.PLAYING

                updateWorker(gen, task.id) { w ->
                    w.copy(
                        displaySeconds = nextPos,
                        nextReportCountdownSec = 0,
                        statusText = if (dragEvent == ChaoxingVideoDragEvent.ENDED) "发送完播确认…" else "上报播放心跳…",
                    )
                }

                try {
                    val res = dependencies.sendVideoLog(client, task, nextPos, dragEvent)
                    currentPos = nextPos
                    transientErrorCount = 0
                    updateWorker(gen, task.id) { w ->
                        w.copy(
                            confirmedSeconds = currentPos,
                            displaySeconds = currentPos,
                            nextReportCountdownSec = reportInterval,
                            statusText = "已确认 ${formatTime(currentPos)} / ${formatTime(targetEnd)}",
                        )
                    }
                    if (res.isPassed) {
                        markVideoPassed(queued.course.id, queued.chapterKey, queued.chapter.id, task.id, targetEnd)
                        incrementCompletedVideos(gen)
                        appendLog("视频完成：${task.name}")
                        return
                    }
                    if (currentPos >= targetEnd) {
                        postEndRetries += 1
                        if (postEndRetries >= 2) {
                            appendLog("视频已播至末尾但服务端尚未标记通过：${task.name}", isError = true)
                            incrementSkipped(gen)
                            return
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val handled = handleWorkerError(
                        error = e,
                        taskName = task.name,
                        gen = gen,
                        taskId = task.id,
                        effectiveConcurrency = effectiveConcurrency,
                        cooldownUntilMs = cooldownUntilMs,
                        retry403Count = retry403Count,
                        transientErrorCount = transientErrorCount,
                    )
                    if (handled.isRateLimit) retry403Count += 1 else transientErrorCount += 1
                    if (!handled.shouldRetry) {
                        incrementSkipped(gen)
                        return
                    }
                }
            }
        } finally {
            removeWorker(gen, task.id)
            skipTaskIds.remove(task.id)
        }
    }

    private suspend fun runLegalLiveWorker(
        client: ChaoxingHttpClient,
        queued: QueuedStudyItem.Live,
        gen: Long,
        effectiveConcurrency: AtomicInteger,
        cooldownUntilMs: AtomicLong,
    ) {
        val live = queued.live
        upsertWorker(
            gen,
            DesktopWatchWorker(
                taskId = live.id,
                courseKey = queued.course.id,
                courseName = queued.course.courseName,
                chapterKey = queued.chapterKey,
                chapterTitle = queued.chapter.title,
                videoName = live.title,
                duration = max(1, live.duration),
                confirmedSeconds = live.watchedSeconds,
                displaySeconds = live.watchedSeconds,
                speedMultiplier = 1.0,
                requiresFaceCapture = false,
                nextReportCountdownSec = 30,
                statusText = "关联课程并初始化直播回放…",
                isLiveReplay = true,
                webPageUrl = live.webPageUrl,
                playUrl = live.playUrl,
            ),
        )

        var retry403Count = 0
        var transientErrorCount = 0

        try {
            waitForCooldownIfNeeded(gen, live.id, cooldownUntilMs)
            if (isSkipped(live.id)) {
                appendLog("已跳过直播回放：${live.title}")
                incrementSkipped(gen)
                return
            }

            var session = try {
                dependencies.prepareLiveReplaySession(client, live)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                appendLog("初始化直播回放失败（${live.title}）：${e.message ?: "网络错误"}", isError = true)
                incrementSkipped(gen)
                return
            }

            val refreshedInfo = try {
                dependencies.fetchLiveInfo(client, live)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            var targetDuration = maxOf(refreshedInfo?.duration ?: 0, session.duration, live.duration)
            val thresholdPercent = live.rt * 100.0
            var serverConfirmedSec = max(live.watchedSeconds, (refreshedInfo?.playbackTimelongMs ?: 0) / 1000)
            var estimatedSec = serverConfirmedSec
            var serverPercent = max(live.percentValue, refreshedInfo?.percentValue ?: 0.0)

            updateWorker(gen, live.id) { w ->
                w.copy(
                    duration = max(1, targetDuration),
                    confirmedSeconds = serverConfirmedSec,
                    displaySeconds = estimatedSec,
                    nextReportCountdownSec = 29,
                    statusText = "发送直播起播心跳 (isStart=0)…",
                )
            }

            var startSent = false
            while (!startSent) {
                currentCoroutineContext().ensureActive()
                if (isSkipped(live.id)) {
                    appendLog("已跳过直播回放：${live.title}")
                    incrementSkipped(gen)
                    return
                }
                try {
                    val startRes = dependencies.sendLiveHeartbeat(client, live, session, isStart = true, watchMomentSec = estimatedSec)
                    if (startRes.duration > targetDuration) {
                        targetDuration = startRes.duration
                        updateWorker(gen, live.id) { w -> w.copy(duration = max(1, targetDuration)) }
                    }
                    startSent = true
                    appendLog(
                        String.format(
                            Locale.ROOT,
                            "直播回放起播已接收：%s（服务端已入库 %.1f%%）",
                            live.title,
                            serverPercent,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val handled = handleWorkerError(
                        error = e,
                        taskName = live.title,
                        gen = gen,
                        taskId = live.id,
                        effectiveConcurrency = effectiveConcurrency,
                        cooldownUntilMs = cooldownUntilMs,
                        retry403Count = retry403Count,
                        transientErrorCount = transientErrorCount,
                    )
                    if (handled.isRateLimit) retry403Count += 1 else transientErrorCount += 1
                    if (!handled.shouldRetry) {
                        incrementSkipped(gen)
                        return
                    }
                    waitForCooldownIfNeeded(gen, live.id, cooldownUntilMs)
                }
            }

            var tickCount = 0
            var postThresholdTicks = 0
            var ticksSinceServerAdvance = 0
            var sessionRecoveryAttempts = 0
            var needsSessionRestart = false
            val maxTicks = max(240, if (targetDuration > 0) (targetDuration / 30) + 30 else 240)

            while (tickCount < maxTicks) {
                currentCoroutineContext().ensureActive()
                if (isSkipped(live.id)) {
                    appendLog("已跳过直播回放：${live.title}")
                    incrementSkipped(gen)
                    return
                }
                val cooldownBefore = cooldownUntilMs.get()
                waitForCooldownIfNeeded(gen, live.id, cooldownUntilMs)
                if (cooldownBefore > dependencies.nowMs() || needsSessionRestart) {
                    needsSessionRestart = false
                    try {
                        dependencies.sendLiveHeartbeat(client, live, session, isStart = true, watchMomentSec = estimatedSec)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Best-effort restart.
                    }
                }

                val stepSec = if (tickCount == 0) 29 else 30
                for (sec in 1..stepSec) {
                    currentCoroutineContext().ensureActive()
                    if (isSkipped(live.id)) {
                        appendLog("已跳过直播回放：${live.title}")
                        incrementSkipped(gen)
                        return
                    }
                    dependencies.sleep(1_000L)
                    val visualSec = if (targetDuration > 0) min(targetDuration, estimatedSec + sec) else estimatedSec + sec
                    val pctLabel = if (serverPercent > 0.0) {
                        String.format(Locale.ROOT, " · 服务端入库 %.1f%%", serverPercent)
                    } else {
                        " · 每60s入库同步"
                    }
                    updateWorker(gen, live.id) { w ->
                        w.copy(
                            confirmedSeconds = serverConfirmedSec,
                            displaySeconds = visualSec,
                            nextReportCountdownSec = stepSec - sec,
                            statusText = "直播回放合法同步中$pctLabel",
                        )
                    }
                }

                val candidateMoment = if (targetDuration > 0) min(targetDuration, estimatedSec + stepSec) else estimatedSec + stepSec
                updateWorker(gen, live.id) { w ->
                    w.copy(displaySeconds = candidateMoment, nextReportCountdownSec = 0, statusText = "上报直播心跳 (isStart=1)…")
                }

                try {
                    val hbRes = dependencies.sendLiveHeartbeat(client, live, session, isStart = false, watchMomentSec = candidateMoment)
                    if (hbRes.duration > targetDuration) {
                        targetDuration = hbRes.duration
                        updateWorker(gen, live.id) { w -> w.copy(duration = max(1, targetDuration)) }
                    }
                    transientErrorCount = 0
                    tickCount += 1
                    ticksSinceServerAdvance += 1
                    estimatedSec = max(serverConfirmedSec, candidateMoment)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val handled = handleWorkerError(
                        error = e,
                        taskName = live.title,
                        gen = gen,
                        taskId = live.id,
                        effectiveConcurrency = effectiveConcurrency,
                        cooldownUntilMs = cooldownUntilMs,
                        retry403Count = retry403Count,
                        transientErrorCount = transientErrorCount,
                    )
                    if (handled.isRateLimit) {
                        retry403Count += 1
                        needsSessionRestart = true
                    } else {
                        transientErrorCount += 1
                    }
                    if (!handled.shouldRetry) {
                        incrementSkipped(gen)
                        return
                    }
                    continue
                }

                val requiredSec = if (targetDuration > 0) ceil(targetDuration * live.rt).toInt() else 0
                val nearCompletion = requiredSec > 0 && estimatedSec >= requiredSec
                if (tickCount % 2 == 0 || nearCompletion) {
                    val info = try {
                        dependencies.fetchLiveInfo(client, live)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        null
                    }
                    if (info != null) {
                        if (info.duration > targetDuration) targetDuration = info.duration
                        val serverWatched = info.playbackTimelongMs / 1000
                        serverPercent = info.percentValue
                        if (serverWatched > serverConfirmedSec) {
                            serverConfirmedSec = serverWatched
                            estimatedSec = max(estimatedSec, serverConfirmedSec)
                            ticksSinceServerAdvance = 0
                            appendLog(
                                String.format(
                                    Locale.ROOT,
                                    "直播回放服务端已入库：%s · %s (%.1f%%)",
                                    live.title,
                                    formatTime(serverConfirmedSec),
                                    serverPercent,
                                ),
                            )
                        }
                        updateWorker(gen, live.id) { w ->
                            w.copy(
                                duration = max(1, targetDuration),
                                confirmedSeconds = serverConfirmedSec,
                                displaySeconds = estimatedSec,
                                nextReportCountdownSec = 30,
                                statusText = String.format(Locale.ROOT, "直播回放 · 服务端已入库 %.1f%%", serverPercent),
                            )
                        }
                        val refreshedRequired = if (targetDuration > 0) ceil(targetDuration * live.rt).toInt() else 0
                        val reachedTarget = serverPercent >= thresholdPercent || (refreshedRequired > 0 && serverConfirmedSec >= refreshedRequired)
                        if (reachedTarget) {
                            postThresholdTicks += 1
                            val cardPassed = try {
                                val refreshed = dependencies.fetchChapterContent(
                                    client = client,
                                    courseId = live.courseId,
                                    classId = live.classId,
                                    chapterId = live.chapterId,
                                    cpi = live.cpi,
                                )
                                refreshed.liveTasks.firstOrNull { it.id == live.id }?.isPassed == true
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                false
                            }
                            if (cardPassed || postThresholdTicks >= 2) {
                                markLivePassed(queued.course.id, queued.chapterKey, queued.chapter.id, live.id, targetDuration)
                                incrementCompletedVideos(gen)
                                appendLog("直播回放完成：${live.title}")
                                return
                            }
                        } else if (ticksSinceServerAdvance >= 6 && sessionRecoveryAttempts < 2) {
                            sessionRecoveryAttempts += 1
                            ticksSinceServerAdvance = 0
                            appendLog("直播回放 3 分钟内入库时长未更新，正在重新关联会话：${live.title}", isError = true)
                            val refreshedSession = try {
                                dependencies.prepareLiveReplaySession(client, live)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                null
                            }
                            if (refreshedSession != null) {
                                session = refreshedSession
                                try {
                                    dependencies.sendLiveHeartbeat(client, live, session, isStart = true, watchMomentSec = estimatedSec)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                    // Best-effort recovery.
                                }
                            }
                        }
                    }
                }
            }

            appendLog("直播回放达到最大挂机次数仍未确认通过：${live.title}", isError = true)
            incrementSkipped(gen)
        } finally {
            removeWorker(gen, live.id)
            skipTaskIds.remove(live.id)
        }
    }

    private data class ErrorDisposition(val shouldRetry: Boolean, val isRateLimit: Boolean)

    private suspend fun handleWorkerError(
        error: Throwable,
        taskName: String,
        gen: Long,
        taskId: String,
        effectiveConcurrency: AtomicInteger,
        cooldownUntilMs: AtomicLong,
        retry403Count: Int,
        transientErrorCount: Int,
    ): ErrorDisposition {
        val statusCode = (error as? ChaoxingError.NetworkError)?.statusCode
        val msg = error.message.orEmpty()
        if (statusCode == 403 || msg.contains("restTime:", ignoreCase = true) || msg.contains("(403)")) {
            val restSec = REST_TIME_REGEX.find(msg)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.coerceIn(5, 600)
                ?: DEFAULT_403_COOLDOWN_SEC
            val newCap = effectiveConcurrency.updateAndGet { max(1, it - 1) }
            val targetUntil = dependencies.nowMs() + restSec * 1_000L
            cooldownUntilMs.updateAndGet { existing -> max(existing, targetUntil) }
            if (generation.get() == gen) {
                _uiState.update { it.copy(cooldownRemainingSec = max(it.cooldownRemainingSec, restSec)) }
            }
            appendLog("触发学习通限流 (403)：$taskName，并发降至 $newCap 路，冷却 ${restSec}s 后继续", isError = true)
            if (retry403Count >= MAX_403_RETRIES) {
                appendLog("「$taskName」连续限流次数过多，已跳过", isError = true)
                return ErrorDisposition(shouldRetry = false, isRateLimit = true)
            }
            return ErrorDisposition(shouldRetry = true, isRateLimit = true)
        }

        if (transientErrorCount >= MAX_TRANSIENT_RETRIES) {
            appendLog("「$taskName」上报持续失败，已跳过：${msg.ifBlank { "网络错误" }}", isError = true)
            return ErrorDisposition(shouldRetry = false, isRateLimit = false)
        }
        appendLog("「$taskName」上报出错，4 秒后重试：${msg.ifBlank { "网络错误" }}", isError = true)
        updateWorker(gen, taskId) { it.copy(statusText = "网络异常，即将重试…") }
        dependencies.sleep(4_000L)
        return ErrorDisposition(shouldRetry = true, isRateLimit = false)
    }

    private suspend fun waitForCooldownIfNeeded(
        gen: Long,
        taskId: String,
        cooldownUntilMs: AtomicLong,
    ) {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (isSkipped(taskId)) return
            val remainingMs = cooldownUntilMs.get() - dependencies.nowMs()
            if (remainingMs <= 0L) {
                if (generation.get() == gen && _uiState.value.cooldownRemainingSec != 0) {
                    _uiState.update { it.copy(cooldownRemainingSec = 0) }
                }
                return
            }
            val sec = max(1, ceil(remainingMs / 1_000.0).toInt())
            if (generation.get() == gen) {
                _uiState.update { it.copy(cooldownRemainingSec = sec) }
                updateWorker(gen, taskId) { it.copy(statusText = "限流冷却中 (${sec}s)…") }
            }
            dependencies.sleep(min(1_000L, remainingMs))
        }
    }

    private fun isSkipped(taskId: String): Boolean = taskId in skipTaskIds

    private fun upsertWorker(gen: Long, worker: DesktopWatchWorker) {
        if (generation.get() != gen) return
        _uiState.update { state ->
            val idx = state.activeWorkers.indexOfFirst { it.taskId == worker.taskId }
            val next = if (idx >= 0) {
                state.activeWorkers.toMutableList().apply { set(idx, worker) }
            } else {
                state.activeWorkers + worker
            }
            state.copy(activeWorkers = next)
        }
    }

    private fun updateWorker(
        gen: Long,
        taskId: String,
        transform: (DesktopWatchWorker) -> DesktopWatchWorker,
    ) {
        if (generation.get() != gen) return
        _uiState.update { state ->
            val idx = state.activeWorkers.indexOfFirst { it.taskId == taskId }
            if (idx < 0) return@update state
            val next = state.activeWorkers.toMutableList()
            next[idx] = transform(next[idx])
            state.copy(activeWorkers = next)
        }
    }

    private fun removeWorker(gen: Long, taskId: String) {
        if (generation.get() != gen) return
        _uiState.update { state ->
            state.copy(activeWorkers = state.activeWorkers.filterNot { it.taskId == taskId })
        }
    }

    private fun incrementCompletedVideos(gen: Long) {
        if (generation.get() != gen) return
        _uiState.update { it.copy(completedVideosCount = it.completedVideosCount + 1) }
    }

    private fun incrementSkipped(gen: Long) {
        if (generation.get() != gen) return
        _uiState.update { it.copy(skippedCount = it.skippedCount + 1) }
    }

    private fun markVideoPassed(
        courseKey: String,
        chapterKey: String,
        chapterId: String,
        videoId: String,
        endSec: Int,
    ) {
        _uiState.update { state ->
            val updatedContent = state.contentByChapterKey[chapterKey]?.let { content ->
                content.copy(
                    videos = content.videos.map { video ->
                        if (video.id == videoId) video.updating(isPassed = true, headOffsetMs = endSec * 1000) else video
                    },
                )
            }
            val updatedChapters = decrementPendingChapter(state.chaptersByCourse[courseKey], chapterId)
            state.copy(
                contentByChapterKey = updatedContent?.let { state.contentByChapterKey + (chapterKey to it) }
                    ?: state.contentByChapterKey,
                chaptersByCourse = updatedChapters?.let { state.chaptersByCourse + (courseKey to it) }
                    ?: state.chaptersByCourse,
            )
        }
    }

    private fun markLivePassed(
        courseKey: String,
        chapterKey: String,
        chapterId: String,
        liveId: String,
        durationSec: Int,
    ) {
        _uiState.update { state ->
            val updatedContent = state.contentByChapterKey[chapterKey]?.let { content ->
                content.copy(
                    liveTasks = content.liveTasks.map { item ->
                        if (item.id == liveId) {
                            item.copy(
                                isPassed = true,
                                playbackTimelongMs = max(item.playbackTimelongMs, durationSec * 1000),
                                percentValue = max(item.percentValue, 100.0),
                            )
                        } else {
                            item
                        }
                    },
                )
            }
            val updatedChapters = decrementPendingChapter(state.chaptersByCourse[courseKey], chapterId)
            state.copy(
                contentByChapterKey = updatedContent?.let { state.contentByChapterKey + (chapterKey to it) }
                    ?: state.contentByChapterKey,
                chaptersByCourse = updatedChapters?.let { state.chaptersByCourse + (courseKey to it) }
                    ?: state.chaptersByCourse,
            )
        }
    }

    private fun markAttachmentPassed(
        courseKey: String,
        chapterKey: String,
        chapterId: String,
        attachmentId: String,
    ) {
        _uiState.update { state ->
            val updatedContent = state.contentByChapterKey[chapterKey]?.let { content ->
                content.copy(
                    otherAttachments = content.otherAttachments.map { att ->
                        if (att.id == attachmentId) att.updating(isPassed = true) else att
                    },
                )
            }
            val updatedChapters = decrementPendingChapter(state.chaptersByCourse[courseKey], chapterId)
            state.copy(
                contentByChapterKey = updatedContent?.let { state.contentByChapterKey + (chapterKey to it) }
                    ?: state.contentByChapterKey,
                chaptersByCourse = updatedChapters?.let { state.chaptersByCourse + (courseKey to it) }
                    ?: state.chaptersByCourse,
            )
        }
    }

    private fun decrementPendingChapter(
        result: ChaoxingCourseChaptersResult?,
        chapterId: String,
    ): ChaoxingCourseChaptersResult? = result?.copy(
        chapters = result.chapters.map { node ->
            val pending = node.pendingJobCount
            if (node.id == chapterId && pending != null && pending > 0) {
                node.copy(pendingJobCount = max(0, pending - 1))
            } else {
                node
            }
        },
    )

    private fun appendLog(message: String, isError: Boolean = false) {
        val entry = StudyLogEntry(
            id = logSeq.incrementAndGet(),
            timeText = LocalTime.now().format(LOG_TIME_FORMATTER),
            message = message,
            isError = isError,
        )
        _uiState.update { state ->
            state.copy(logs = (listOf(entry) + state.logs).take(MAX_LOG_ENTRIES))
        }
    }

    companion object {
        private const val CHAPTER_SCAN_CONCURRENCY = 6
        private const val MAX_SCAN_PASSES = 2
        private const val DEFAULT_403_COOLDOWN_SEC = 20
        private const val MAX_403_RETRIES = 6
        private const val MAX_TRANSIENT_RETRIES = 3
        private const val MAX_LOG_ENTRIES = 100
        private val REST_TIME_REGEX = Regex("""\[restTime:(\d+)]""")
        private val LOG_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        fun chapterKey(chapter: ChaoxingChapterNode): String =
            "${chapter.courseId}_${chapter.classId}_${chapter.id}"

        fun formatTime(totalSec: Int): String {
            val safe = max(0, totalSec)
            val m = safe / 60
            val s = safe % 60
            return String.format(Locale.ROOT, "%02d:%02d", m, s)
        }

        fun formatRate(rate: Double): String =
            if (rate >= 1.99) "2x 合法倍速" else "1x 原速"
    }
}
