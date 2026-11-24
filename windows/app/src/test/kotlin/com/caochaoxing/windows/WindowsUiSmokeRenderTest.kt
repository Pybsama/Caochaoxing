// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingAttachmentKind
import com.caochaoxing.core.model.ChaoxingChapterAttachment
import com.caochaoxing.core.model.ChaoxingChapterContent
import com.caochaoxing.core.model.ChaoxingChapterNode
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingCourseChaptersResult
import com.caochaoxing.core.model.ChaoxingEasemobIMConfig
import com.caochaoxing.core.model.ChaoxingGroupSignActivity
import com.caochaoxing.core.model.ChaoxingIMGroup
import com.caochaoxing.core.model.ChaoxingLiveTask
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingLocationTarget
import com.caochaoxing.core.model.ChaoxingSchool
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignActivityStatus
import com.caochaoxing.core.model.ChaoxingSignDetailMetadata
import com.caochaoxing.core.model.ChaoxingSignOutInfo
import com.caochaoxing.core.model.ChaoxingSignType
import com.caochaoxing.core.model.ChaoxingVideoDragEvent
import com.caochaoxing.core.model.ChaoxingVideoLogResponse
import com.caochaoxing.core.model.ChaoxingVideoTask
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import com.caochaoxing.core.store.ChaoxingSignHistoryEntry
import com.caochaoxing.core.store.PersistenceStore
import com.caochaoxing.windows.state.AppThemeMode
import com.caochaoxing.windows.state.WindowsAppState
import com.caochaoxing.windows.state.WindowsAppStateDependencies
import com.caochaoxing.windows.state.WindowsCourseStudyController
import com.caochaoxing.windows.state.WindowsCourseStudyDependencies
import com.caochaoxing.windows.state.WindowsWorkspaceTab
import com.caochaoxing.windows.ui.theme.CaochaoxingWindowsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException

/**
 * Headless Compose Desktop rendering smoke tests across every screen, all 5 sign-in types,
 * light/dark themes, and both standard (1280x800) & minimum (960x640) desktop window sizes.
 * Catches layout constraint exceptions, Material3/Skiko rendering crashes, and state binding bugs
 * without requiring a physical Windows display.
 */
class WindowsUiSmokeRenderTest {

    private class MemoryStore(
        var snapshot: ChaoxingAppStateSnapshot = ChaoxingAppStateSnapshot(),
        var failLoad: Boolean = false,
    ) : PersistenceStore {
        override suspend fun load(): ChaoxingAppStateSnapshot {
            if (failLoad) throw IOException("simulated unreadable dpapi state")
            return snapshot
        }

        override suspend fun save(snapshot: ChaoxingAppStateSnapshot) {
            this.snapshot = snapshot
        }

        override suspend fun archiveAndReset(): File {
            failLoad = false
            snapshot = ChaoxingAppStateSnapshot()
            return File("state.recovery-ui.dpapi")
        }
    }

    private fun fakeDeps(): WindowsAppStateDependencies = object : WindowsAppStateDependencies {
        override suspend fun login(phoneNumber: String, password: String, userAgent: String, isPrimary: Boolean) =
            ChaoxingAccountSession(
                phoneNumber = phoneNumber,
                encryptedPassword = "enc",
                name = "测试同学",
                uid = 100,
                puid = 100,
                configuredFid = 1001,
                schools = listOf(ChaoxingSchool(1001, "测试大学")),
                deviceCode = "dev-ui",
                isPrimary = isPrimary,
                tags = listOf("宿舍"),
            )

        override suspend fun reLogin(client: ChaoxingHttpClient) = client.session

        override suspend fun fetchCourses(client: ChaoxingHttpClient) = listOf(
            ChaoxingCourse("计算机操作系统", "张教授", 101L, 202, "", cpi = 3003L),
            ChaoxingCourse("编译原理", "李教授", 102L, 203, "", cpi = 3004L),
        )

        override suspend fun fetchIMConfig(client: ChaoxingHttpClient) =
            ChaoxingEasemobIMConfig("tok", "uuid", "user")

        override suspend fun fetchGroups(client: ChaoxingHttpClient, imConfig: ChaoxingEasemobIMConfig) = listOf(
            ChaoxingIMGroup("g-1", "操作系统 2026 春课程群"),
        )

        override suspend fun fetchCourseActivities(client: ChaoxingHttpClient, course: ChaoxingCourse) = listOf(
            sampleSignActivity(1001L, ChaoxingSignType.LOCATION, "教室定位签到"),
            sampleSignActivity(1002L, ChaoxingSignType.QR_CODE, "课堂动态二维码"),
            sampleSignActivity(1003L, ChaoxingSignType.GESTURE, "九宫格手势签到"),
            sampleSignActivity(1004L, ChaoxingSignType.PASSWORD, "四位数字签到码"),
            sampleSignActivity(1005L, ChaoxingSignType.PHOTO_OR_CLICK, "课堂拍照点名"),
        )

        override suspend fun fetchGroupActivities(client: ChaoxingHttpClient, group: ChaoxingIMGroup, imConfig: ChaoxingEasemobIMConfig) = listOf(
            ChaoxingGroupSignActivity(
                activeId = 9001L,
                title = "群聊定位签到",
                classId = 202,
                courseId = 101L,
                courseName = "计算机操作系统",
                startTimeTitle = "10:00",
                activeTypeName = "位置签到",
                isUnsupportedLegacySign = false,
                messageTimestampMs = 1_700_000_000_000L,
            ),
        )

        override suspend fun fetchSignDetail(client: ChaoxingHttpClient, activeId: Long, fallbackClassId: Int, fallbackCourseId: Long): ChaoxingSignDetailMetadata {
            val type = when (activeId) {
                1001L -> ChaoxingSignType.LOCATION
                1002L -> ChaoxingSignType.QR_CODE
                1003L -> ChaoxingSignType.GESTURE
                1004L -> ChaoxingSignType.PASSWORD
                else -> ChaoxingSignType.PHOTO_OR_CLICK
            }
            return ChaoxingSignDetailMetadata(
                activeId = activeId,
                signType = type,
                title = "详情 #$activeId",
                isCaptchaRequired = false,
                isFaceRequired = type == ChaoxingSignType.LOCATION,
                isPositionRequired = type == ChaoxingSignType.LOCATION,
                isPhotoRequired = type == ChaoxingSignType.PHOTO_OR_CLICK,
                isRefreshQRCode = type == ChaoxingSignType.QR_CODE,
                numberCount = if (type == ChaoxingSignType.PASSWORD) 4 else null,
                targetLocation = if (type == ChaoxingSignType.LOCATION) ChaoxingLocationTarget(34.2301, 108.9123, 150) else null,
                signOutInfo = ChaoxingSignOutInfo(signInId = activeId, signOutId = activeId + 50000L, classId = fallbackClassId, courseId = fallbackCourseId),
            )
        }

        override suspend fun prepareSign(client: ChaoxingHttpClient, activity: ChaoxingSignActivity) =
            ChaoxingSignActivityStatus.READY_TO_SIGN

        override suspend fun sleep(delayMs: Long) {}
    }

    private fun fakeStudyDeps(): WindowsCourseStudyDependencies = object : WindowsCourseStudyDependencies {
        override suspend fun fetchCourseChapters(client: ChaoxingHttpClient, courseId: Long, classId: Int, knownCpi: Long) =
            ChaoxingCourseChaptersResult(
                cpi = 3003L,
                chapters = listOf(
                    ChaoxingChapterNode("ch-1", "1.1 进程与线程", "第一章", courseId, classId, pendingJobCount = 2),
                    ChaoxingChapterNode("ch-2", "1.2 调度算法", "第一章", courseId, classId, pendingJobCount = 0),
                ),
            )

        override suspend fun fetchChapterContent(client: ChaoxingHttpClient, courseId: Long, classId: Int, chapterId: String, cpi: Long) =
            ChaoxingChapterContent(
                videos = listOf(
                    ChaoxingVideoTask(
                        courseId = courseId, classId = classId, chapterId = chapterId, cpi = cpi,
                        userid = "u1", jobid = "j1", objectId = "o1", name = "进程概念讲解.mp4",
                        otherInfo = "", dtoken = "dt1", duration = 300, attDuration = 300,
                        attDurationEnc = "e", doublespeed = 1, reportUrl = "https://mooc1.chaoxing.com/log",
                    ),
                ),
                liveTasks = listOf(
                    ChaoxingLiveTask(
                        courseId = courseId, classId = classId, chapterId = chapterId, cpi = cpi,
                        userid = "u1", jobid = "jl1", liveId = "live-1", streamName = "s1",
                        vdoid = "v1", title = "直播答疑回放", duration = 1800, percentValue = 42.5,
                        webPageUrl = "https://zhibo.chaoxing.com/live-1",
                    ),
                ),
                otherAttachments = listOf(
                    ChaoxingChapterAttachment(
                        kind = ChaoxingAttachmentKind.DOCUMENT, courseId = courseId, classId = classId,
                        chapterId = chapterId, jobid = "jd1", objectId = "od1", jtoken = "jt1",
                        name = "第1章讲义.pdf",
                    ),
                ),
            )

        override suspend fun sendVideoLog(client: ChaoxingHttpClient, task: ChaoxingVideoTask, playingTimeSec: Int, event: ChaoxingVideoDragEvent) =
            ChaoxingVideoLogResponse(isPassed = true)

        override suspend fun completeDocumentTask(client: ChaoxingHttpClient, attachment: ChaoxingChapterAttachment) = true
        override suspend fun sleep(delayMs: Long) {}
        override fun nowMs(): Long = 1_000_000L
    }

    private fun sampleSignActivity(id: Long, type: ChaoxingSignType, title: String) = ChaoxingSignActivity(
        id = id,
        startTimeMs = 1_700_000_000_000L,
        endTimeMs = null,
        userStatus = 0,
        otherId = type.rawValue,
        isLook = false,
        type = 2,
        activeType = 2,
        nameOne = title,
        status = 1,
        nameFour = "",
        courseId = 101L,
        classId = 202,
        courseName = "计算机操作系统",
        extContent = "",
    )

    private fun renderFrame(
        width: Int,
        height: Int,
        themeMode: AppThemeMode,
        appState: WindowsAppState,
        studyController: WindowsCourseStudyController,
        dumpName: String? = null,
    ) {
        val scene = ImageComposeScene(
            width = width,
            height = height,
            density = Density(1.25f), // 125% Windows HiDPI scaling
        ) {
            CaochaoxingWindowsTheme(themeMode = themeMode) {
                MainAppShell(appState = appState, studyController = studyController)
            }
        }
        try {
            val image = scene.render(nanoTime = 0L)
            assertEquals(width, image.width)
            assertEquals(height, image.height)
            val second = scene.render(nanoTime = 16_000_000L)
            assertTrue(second.width == width && second.height == height)
            val dumpDir = System.getProperty("caochaoxing.dumpUiDir")?.takeIf { it.isNotBlank() }
            if (dumpDir != null && dumpName != null) {
                val outDir = File(dumpDir).apply { mkdirs() }
                val encoded = second.encodeToData(EncodedImageFormat.PNG)?.bytes
                if (encoded != null) {
                    File(outDir, "$dumpName.png").writeBytes(encoded)
                }
            }
        } finally {
            scene.close()
        }
    }

    @Test
    fun `login and persistence recovery screens render cleanly in light and dark modes`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = MemoryStore()
            val appState = WindowsAppState(store, fakeDeps(), scope, storageDescription = "Windows DPAPI · state.dpapi")
            val study = WindowsCourseStudyController(appState, scope, fakeStudyDeps())

            // Unauthenticated login screen
            renderFrame(1280, 800, AppThemeMode.LIGHT, appState, study, dumpName = "01_login_light")
            renderFrame(960, 640, AppThemeMode.DARK, appState, study, dumpName = "01b_login_dark_960")

            // Corrupted storage recovery screen
            val corruptStore = MemoryStore(failLoad = true)
            val blockedState = WindowsAppState(corruptStore, fakeDeps(), scope, storageDescription = "Windows DPAPI · state.dpapi")
            runBlocking { blockedState.loadFromDisk() }
            renderFrame(1280, 800, AppThemeMode.LIGHT, blockedState, study, dumpName = "02_recovery_light")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `all 5 workspace tabs and all 5 sign-in panels render without errors at 1280x800 and 960x640`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val seededSnapshot = ChaoxingAppStateSnapshot(
                accounts = listOf(
                    ChaoxingAccountSession(
                        phoneNumber = "13800000001",
                        encryptedPassword = "enc",
                        name = "张三",
                        uid = 100,
                        puid = 100,
                        configuredFid = 1001,
                        schools = listOf(ChaoxingSchool(1001, "示范大学")),
                        deviceCode = "dev-1",
                        isPrimary = true,
                        tags = listOf("宿舍", "计科"),
                    ),
                ),
                locationPresets = listOf(
                    ChaoxingLocationPreset(name = "教学楼A座", address = "南校区教学楼A座", latitude = 34.2301, longitude = 108.9123),
                ),
                signHistory = listOf(
                    ChaoxingSignHistoryEntry(
                        activeId = 1001L,
                        activityName = "教室定位签到",
                        courseName = "计算机操作系统",
                        signTypeRaw = ChaoxingSignType.LOCATION.rawValue,
                        successCount = 1,
                        totalCount = 1,
                        summary = "张三: 签到成功",
                    ),
                ),
            )
            val store = MemoryStore(snapshot = seededSnapshot)
            val appState = WindowsAppState(store, fakeDeps(), scope, storageDescription = "Windows DPAPI · state.dpapi")
            val study = WindowsCourseStudyController(appState, scope, fakeStudyDeps())

            runBlocking {
                appState.loadFromDisk()
                appState.scanPendingRadar()
                appState.refreshGroups()
                val firstCourse = appState.uiState.value.courses.first()
                study.selectCourse(firstCourse)
                val firstChapter = study.uiState.value.chaptersByCourse[firstCourse.id]?.chapters?.first()
                if (firstChapter != null) {
                    study.selectChapter(firstCourse, firstChapter)
                }
            }

            // 1. Render all 5 tabs in both light (1280x800) and dark (960x640 minimum window size)
            for (tab in WindowsWorkspaceTab.entries) {
                appState.selectTab(tab)
                renderFrame(1280, 800, AppThemeMode.LIGHT, appState, study, dumpName = "tab_${tab.name.lowercase()}_1280_light")
                renderFrame(960, 640, AppThemeMode.DARK, appState, study, dumpName = "tab_${tab.name.lowercase()}_960_dark")
            }

            // 2. Render all 5 sign-in activity detail forms (including Canvas geofence & 3x3 gesture grid)
            val allFiveSignTypes = listOf(
                sampleSignActivity(1001L, ChaoxingSignType.LOCATION, "教室定位签到"),
                sampleSignActivity(1002L, ChaoxingSignType.QR_CODE, "课堂动态二维码"),
                sampleSignActivity(1003L, ChaoxingSignType.GESTURE, "九宫格手势签到"),
                sampleSignActivity(1004L, ChaoxingSignType.PASSWORD, "四位数字签到码"),
                sampleSignActivity(1005L, ChaoxingSignType.PHOTO_OR_CLICK, "课堂拍照点名"),
            )
            for (act in allFiveSignTypes) {
                appState.openSignWorkspace(act, prefilledQrInput = "SIGNIN:aid=1002&enc=demoenc")
                renderFrame(1280, 800, AppThemeMode.LIGHT, appState, study, dumpName = "sign_${act.signType.name.lowercase()}_1280_light")
            }
        } finally {
            scope.cancel()
        }
    }
}
