// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows

import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingAttachmentKind
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
import com.caochaoxing.windows.state.WindowsCourseStudyController
import com.caochaoxing.windows.state.WindowsCourseStudyDependencies
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class WindowsCourseStudyControllerTest {

    private fun testClient(): ChaoxingHttpClient = ChaoxingHttpClient(
        session = ChaoxingAccountSession(
            phoneNumber = "13800001234",
            encryptedPassword = "enc",
            name = "看课测试",
            deviceCode = "dev-study",
            isPrimary = true,
        ),
        userAgent = "TestUA",
    )

    private fun sampleCourse(): ChaoxingCourse = ChaoxingCourse(
        courseName = "计算机网络",
        teacherName = "王老师",
        courseId = 1001L,
        classId = 2002,
        imageURL = "",
        cpi = 3003L,
    )

    @Test
    fun `startWatch completes video with 2x speed and auto-completes PDF docs and toggles keep-awake`() = runTest {
        val course = sampleCourse()
        val chapter = ChaoxingChapterNode("ch-1", "第一章 概述", "一", course.courseId, course.classId, pendingJobCount = 2)
        val video = ChaoxingVideoTask(
            courseId = course.courseId,
            classId = course.classId,
            chapterId = chapter.id,
            cpi = 3003L,
            userid = "u1",
            jobid = "job-v1",
            objectId = "obj-v1",
            name = "1.1 网络分层",
            otherInfo = "",
            dtoken = "dtoken-1",
            duration = 60,
            attDuration = 60,
            attDurationEnc = "enc",
            doublespeed = 1,
            reportTimeInterval = 30,
            reportUrl = "https://mooc1.chaoxing.com/mooc-ans/multimedia/log/a",
        )
        val doc = ChaoxingChapterAttachment(
            kind = ChaoxingAttachmentKind.DOCUMENT,
            courseId = course.courseId,
            classId = course.classId,
            chapterId = chapter.id,
            jobid = "job-doc1",
            objectId = "obj-doc1",
            jtoken = "jtok-1",
            name = "第一章课件.pdf",
        )

        val events = CopyOnWriteArrayList<Pair<ChaoxingVideoDragEvent, Int>>()
        val docsCompleted = AtomicInteger(0)
        val clockMs = AtomicLong(1_000_000L)
        val keepAwakeStates = CopyOnWriteArrayList<Boolean>()

        val deps = object : WindowsCourseStudyDependencies {
            override suspend fun fetchCourseChapters(client: ChaoxingHttpClient, courseId: Long, classId: Int, knownCpi: Long) =
                ChaoxingCourseChaptersResult(cpi = 3003L, chapters = listOf(chapter))

            override suspend fun fetchChapterContent(client: ChaoxingHttpClient, courseId: Long, classId: Int, chapterId: String, cpi: Long) =
                ChaoxingChapterContent(videos = listOf(video), otherAttachments = listOf(doc))

            override suspend fun sendVideoLog(
                client: ChaoxingHttpClient,
                task: ChaoxingVideoTask,
                playingTimeSec: Int,
                event: ChaoxingVideoDragEvent,
            ): ChaoxingVideoLogResponse {
                events.add(event to playingTimeSec)
                return ChaoxingVideoLogResponse(isPassed = event == ChaoxingVideoDragEvent.ENDED)
            }

            override suspend fun completeDocumentTask(client: ChaoxingHttpClient, attachment: ChaoxingChapterAttachment): Boolean {
                docsCompleted.incrementAndGet()
                return true
            }

            override suspend fun sleep(delayMs: Long) {
                clockMs.addAndGet(delayMs)
            }

            override fun nowMs(): Long = clockMs.get()
        }

        val controller = WindowsCourseStudyController(
            clientProvider = { testClient() },
            scope = this,
            dependencies = deps,
            onWatchingStateChanged = { keepAwakeStates.add(it) },
        )

        controller.startWatch(course, listOf(course))
        testScheduler.advanceUntilIdle()

        val state = controller.uiState.value
        assertFalse(state.isWatching)
        assertEquals(1, state.completedVideosCount)
        assertEquals(1, state.completedDocsCount)
        assertEquals(1, docsCompleted.get())
        assertEquals(ChaoxingVideoDragEvent.PLAY, events.first().first)
        assertEquals(ChaoxingVideoDragEvent.ENDED, events.last().first)
        assertEquals(60, events.last().second)
        assertTrue(keepAwakeStates.contains(true))
        assertFalse(keepAwakeStates.last())
    }

    @Test
    fun `3-way parallel zhibo live replays run concurrently and complete after server reconciliation`() = runTest {
        val course = sampleCourse()
        val chapter = ChaoxingChapterNode("ch-live", "直播合集", "", course.courseId, course.classId, pendingJobCount = 3)
        val lives = (1..3).map { idx ->
            ChaoxingLiveTask(
                courseId = course.courseId,
                classId = course.classId,
                chapterId = chapter.id,
                cpi = 3003L,
                userid = "u1",
                jobid = "job-live-$idx",
                liveId = "live-$idx",
                streamName = "stream-$idx",
                vdoid = "vdoid-$idx",
                title = "第 $idx 讲直播回放",
                rt = 0.9,
                duration = 60,
            )
        }

        val inFlight = AtomicInteger(0)
        val maxParallel = AtomicInteger(0)
        val heartbeatsByLive = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val clockMs = AtomicLong(1_000_000L)

        val deps = object : WindowsCourseStudyDependencies {
            override suspend fun fetchCourseChapters(client: ChaoxingHttpClient, courseId: Long, classId: Int, knownCpi: Long) =
                ChaoxingCourseChaptersResult(cpi = 3003L, chapters = listOf(chapter))

            override suspend fun fetchChapterContent(client: ChaoxingHttpClient, courseId: Long, classId: Int, chapterId: String, cpi: Long) =
                ChaoxingChapterContent(videos = emptyList(), liveTasks = lives)

            override suspend fun sendVideoLog(client: ChaoxingHttpClient, task: ChaoxingVideoTask, playingTimeSec: Int, event: ChaoxingVideoDragEvent) =
                ChaoxingVideoLogResponse(isPassed = true)

            override suspend fun completeDocumentTask(client: ChaoxingHttpClient, attachment: ChaoxingChapterAttachment) = true

            override suspend fun prepareLiveReplaySession(client: ChaoxingHttpClient, task: ChaoxingLiveTask) =
                ChaoxingLiveSessionContext(
                    pageUrl = "https://zhibo.chaoxing.com/${task.liveId}",
                    shortLiveId = task.liveId,
                    uInfo = "u",
                    streamName = task.streamName,
                    vdoid = task.vdoid,
                    duration = task.duration,
                )

            override suspend fun sendLiveHeartbeat(
                client: ChaoxingHttpClient,
                task: ChaoxingLiveTask,
                session: ChaoxingLiveSessionContext,
                isStart: Boolean,
                watchMomentSec: Int,
            ): ChaoxingLiveHeartbeatResponse {
                val cur = inFlight.incrementAndGet()
                maxParallel.accumulateAndGet(cur) { a, b -> maxOf(a, b) }
                delay(5)
                if (!isStart) {
                    heartbeatsByLive.computeIfAbsent(task.liveId) { AtomicInteger(0) }.incrementAndGet()
                }
                inFlight.decrementAndGet()
                return ChaoxingLiveHeartbeatResponse(result = 1, accepted = true, duration = task.duration)
            }

            override suspend fun fetchLiveInfo(client: ChaoxingHttpClient, task: ChaoxingLiveTask): ChaoxingLiveInfo {
                val ticks = heartbeatsByLive[task.liveId]?.get() ?: 0
                val watchedSec = ticks * 30
                val pct = (watchedSec * 100.0 / task.duration).coerceAtMost(100.0)
                return ChaoxingLiveInfo(
                    resolvedLiveId = task.liveId,
                    title = task.title,
                    liveStatus = 4,
                    ifReview = 0,
                    duration = task.duration,
                    playbackTimelongMs = watchedSec * 1000,
                    percentValue = pct,
                    streamName = task.streamName,
                    vdoid = task.vdoid,
                )
            }

            override suspend fun sleep(delayMs: Long) {
                clockMs.addAndGet(delayMs)
                delay(1)
            }

            override fun nowMs(): Long = clockMs.get()
        }

        val controller = WindowsCourseStudyController(
            clientProvider = { testClient() },
            scope = this,
            dependencies = deps,
        )
        controller.setMaxConcurrentWatches(3)
        controller.startWatch(course, listOf(course))
        testScheduler.advanceUntilIdle()

        assertEquals(3, controller.uiState.value.completedVideosCount)
        assertTrue(maxParallel.get() >= 2, "Expected parallel live replay execution, maxParallel=${maxParallel.get()}")
    }

    @Test
    fun `403 restTime rate limit cools down and retries cleanly and account switch clears state`() = runTest {
        val course = sampleCourse()
        val chapter = ChaoxingChapterNode("ch-403", "限流章节", "", course.courseId, course.classId, pendingJobCount = 1)
        val video = ChaoxingVideoTask(
            courseId = course.courseId,
            classId = course.classId,
            chapterId = chapter.id,
            cpi = 3003L,
            userid = "u1",
            jobid = "job-403",
            objectId = "obj-403",
            name = "限流视频",
            otherInfo = "",
            dtoken = "dtoken-403",
            duration = 30,
            attDuration = 30,
            attDurationEnc = "enc",
            doublespeed = 0,
            reportTimeInterval = 30,
            reportUrl = "https://mooc1.chaoxing.com/mooc-ans/multimedia/log/a",
        )

        val attempts = AtomicInteger(0)
        val clockMs = AtomicLong(1_000_000L)
        val deps = object : WindowsCourseStudyDependencies {
            override suspend fun fetchCourseChapters(client: ChaoxingHttpClient, courseId: Long, classId: Int, knownCpi: Long) =
                ChaoxingCourseChaptersResult(cpi = 3003L, chapters = listOf(chapter))

            override suspend fun fetchChapterContent(client: ChaoxingHttpClient, courseId: Long, classId: Int, chapterId: String, cpi: Long) =
                ChaoxingChapterContent(videos = listOf(video))

            override suspend fun sendVideoLog(
                client: ChaoxingHttpClient,
                task: ChaoxingVideoTask,
                playingTimeSec: Int,
                event: ChaoxingVideoDragEvent,
            ): ChaoxingVideoLogResponse {
                if (attempts.incrementAndGet() == 1) {
                    throw ChaoxingError.NetworkError(statusCode = 403, message = "请求过快 [restTime:12]")
                }
                return ChaoxingVideoLogResponse(isPassed = event == ChaoxingVideoDragEvent.ENDED)
            }

            override suspend fun completeDocumentTask(client: ChaoxingHttpClient, attachment: ChaoxingChapterAttachment) = true
            override suspend fun sleep(delayMs: Long) { clockMs.addAndGet(delayMs) }
            override fun nowMs(): Long = clockMs.get()
        }

        val controller = WindowsCourseStudyController(
            clientProvider = { testClient() },
            scope = this,
            dependencies = deps,
        )
        controller.onAccountChanged("13800001234")
        controller.startSingleVideoWatch(course, chapter, video)
        testScheduler.advanceUntilIdle()

        assertEquals(1, controller.uiState.value.completedVideosCount)
        assertTrue(controller.uiState.value.logs.any { it.message.contains("403") })

        // Switching account clears cached chapters/content and resets logs
        controller.onAccountChanged("13999998888")
        assertTrue(controller.uiState.value.chaptersByCourse.isEmpty())
        assertTrue(controller.uiState.value.contentByChapterKey.isEmpty())
        assertEquals("13999998888", controller.uiState.value.activeAccountPhone)
    }

    @Test
    fun `rapid restart watch does not allow stale finally block to clear new watch state`() = runTest {
        val course = sampleCourse()
        val ch1 = ChaoxingChapterNode("ch-r1", "节1", "", course.courseId, course.classId, pendingJobCount = 1)
        val ch2 = ChaoxingChapterNode("ch-r2", "节2", "", course.courseId, course.classId, pendingJobCount = 1)
        val v1 = ChaoxingVideoTask(
            courseId = course.courseId, classId = course.classId, chapterId = ch1.id, cpi = 3003L,
            userid = "u1", jobid = "j-r1", objectId = "o-r1", name = "视频1", otherInfo = "",
            dtoken = "d1", duration = 60, attDuration = 60, attDurationEnc = "e", doublespeed = 0,
            reportTimeInterval = 30, reportUrl = "https://mooc1.chaoxing.com/log",
        )
        val v2 = v1.copy(chapterId = ch2.id, jobid = "j-r2", objectId = "o-r2", name = "视频2")

        val keepAwakeLog = CopyOnWriteArrayList<Boolean>()
        val clockMs = AtomicLong(1_000_000L)
        val deps = object : WindowsCourseStudyDependencies {
            override suspend fun fetchCourseChapters(client: ChaoxingHttpClient, courseId: Long, classId: Int, knownCpi: Long) =
                ChaoxingCourseChaptersResult(cpi = 3003L, chapters = listOf(ch1, ch2))

            override suspend fun fetchChapterContent(client: ChaoxingHttpClient, courseId: Long, classId: Int, chapterId: String, cpi: Long) =
                ChaoxingChapterContent(videos = listOf(if (chapterId == ch1.id) v1 else v2))

            override suspend fun sendVideoLog(client: ChaoxingHttpClient, task: ChaoxingVideoTask, playingTimeSec: Int, event: ChaoxingVideoDragEvent) =
                ChaoxingVideoLogResponse(isPassed = event == ChaoxingVideoDragEvent.ENDED)

            override suspend fun completeDocumentTask(client: ChaoxingHttpClient, attachment: ChaoxingChapterAttachment) = true
            override suspend fun sleep(delayMs: Long) {
                clockMs.addAndGet(delayMs)
                delay(5)
            }
            override fun nowMs(): Long = clockMs.get()
        }

        val controller = WindowsCourseStudyController(
            clientProvider = { testClient() },
            scope = this,
            dependencies = deps,
            onWatchingStateChanged = { keepAwakeLog.add(it) },
        )

        // Start v1, immediately supersede with v2 before v1 finishes
        controller.startSingleVideoWatch(course, ch1, v1)
        delay(2)
        controller.startSingleVideoWatch(course, ch2, v2)
        delay(10)

        // v2 must still be actively watching despite v1's cancellation finally block running
        assertTrue(controller.uiState.value.isWatching, "Stale finally must not clear active watch")
        assertTrue(controller.uiState.value.watchScopeLabel.contains("视频2"))

        testScheduler.advanceUntilIdle()
        assertFalse(controller.uiState.value.isWatching)
        assertEquals(1, controller.uiState.value.completedVideosCount)
        assertFalse(keepAwakeLog.last())
    }

    @Test
    fun `default watch scope only scans selected course unless watchAllCoursesAutomatically is enabled`() = runTest {
        val c1 = sampleCourse()
        val c2 = ChaoxingCourse("第二门课", "李老师", 2002L, 3003, "", cpi = 4004L)
        val scannedCourseIds = CopyOnWriteArrayList<Long>()
        val clockMs = AtomicLong(1_000_000L)

        val deps = object : WindowsCourseStudyDependencies {
            override suspend fun fetchCourseChapters(client: ChaoxingHttpClient, courseId: Long, classId: Int, knownCpi: Long): ChaoxingCourseChaptersResult {
                scannedCourseIds.add(courseId)
                return ChaoxingCourseChaptersResult(cpi = knownCpi, chapters = emptyList())
            }
            override suspend fun fetchChapterContent(client: ChaoxingHttpClient, courseId: Long, classId: Int, chapterId: String, cpi: Long) =
                ChaoxingChapterContent(videos = emptyList())
            override suspend fun sendVideoLog(client: ChaoxingHttpClient, task: ChaoxingVideoTask, playingTimeSec: Int, event: ChaoxingVideoDragEvent) =
                ChaoxingVideoLogResponse(isPassed = true)
            override suspend fun completeDocumentTask(client: ChaoxingHttpClient, attachment: ChaoxingChapterAttachment) = true
            override suspend fun sleep(delayMs: Long) { clockMs.addAndGet(delayMs) }
            override fun nowMs(): Long = clockMs.get()
        }

        val controller = WindowsCourseStudyController(
            clientProvider = { testClient() },
            scope = this,
            dependencies = deps,
        )

        // Default: watchAllCoursesAutomatically == false -> only c1 scanned
        controller.startWatch(c1, listOf(c1, c2))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(c1.courseId), scannedCourseIds.distinct())

        // Enable cross-course -> both c1 and c2 scanned
        scannedCourseIds.clear()
        controller.setWatchAllCoursesAutomatically(true)
        controller.startWatch(c1, listOf(c1, c2))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(c1.courseId, c2.courseId), scannedCourseIds.distinct())
    }
}
