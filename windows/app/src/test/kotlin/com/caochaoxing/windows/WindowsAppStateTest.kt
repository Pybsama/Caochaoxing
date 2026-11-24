// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows

import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingEasemobIMConfig
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingGroupSignActivity
import com.caochaoxing.core.model.ChaoxingIMGroup
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignActivityStatus
import com.caochaoxing.core.model.ChaoxingSignDetailMetadata
import com.caochaoxing.core.model.ChaoxingSignOutInfo
import com.caochaoxing.core.model.ChaoxingSignType
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.signer.ChaoxingSignOutcome
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import com.caochaoxing.core.store.FilePersistenceStore
import com.caochaoxing.core.store.PersistenceStore
import com.caochaoxing.windows.state.WindowsAppState
import com.caochaoxing.windows.state.WindowsAppStateDependencies
import com.caochaoxing.windows.state.WindowsWorkspaceTab
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Path

class WindowsAppStateTest {

    private class InMemoryStore(var snapshot: ChaoxingAppStateSnapshot = ChaoxingAppStateSnapshot()) : PersistenceStore {
        var failLoad: Boolean = false
        override suspend fun load(): ChaoxingAppStateSnapshot {
            if (failLoad) throw IOException("simulated corruption")
            return snapshot
        }

        override suspend fun save(snapshot: ChaoxingAppStateSnapshot) {
            this.snapshot = snapshot
        }

        override suspend fun archiveAndReset(): File {
            failLoad = false
            snapshot = ChaoxingAppStateSnapshot()
            return File("state.recovery-test.dpapi")
        }
    }

    private class FakeDeps : WindowsAppStateDependencies {
        var coursesByPhone: MutableMap<String, List<ChaoxingCourse>> = mutableMapOf()
        var activitiesByCourse: MutableMap<String, List<ChaoxingSignActivity>> = mutableMapOf()
        var preSignStatus: ChaoxingSignActivityStatus = ChaoxingSignActivityStatus.READY_TO_SIGN

        override suspend fun login(
            phoneNumber: String,
            password: String,
            userAgent: String,
            isPrimary: Boolean,
        ): ChaoxingAccountSession = ChaoxingAccountSession(
            phoneNumber = phoneNumber,
            encryptedPassword = "enc-$password",
            name = "用户$phoneNumber",
            uid = 100,
            puid = 100,
            configuredFid = 1001,
            deviceCode = "device-$phoneNumber",
            isPrimary = isPrimary,
        )

        override suspend fun reLogin(client: ChaoxingHttpClient): ChaoxingAccountSession = client.session

        override suspend fun fetchCourses(client: ChaoxingHttpClient): List<ChaoxingCourse> =
            coursesByPhone[client.session.phoneNumber].orEmpty()

        override suspend fun fetchIMConfig(client: ChaoxingHttpClient): ChaoxingEasemobIMConfig =
            ChaoxingEasemobIMConfig("tok", "uuid", "user")

        override suspend fun fetchGroups(
            client: ChaoxingHttpClient,
            imConfig: ChaoxingEasemobIMConfig,
        ): List<ChaoxingIMGroup> = emptyList()

        override suspend fun fetchCourseActivities(
            client: ChaoxingHttpClient,
            course: ChaoxingCourse,
        ): List<ChaoxingSignActivity> = activitiesByCourse[course.id].orEmpty()

        override suspend fun fetchGroupActivities(
            client: ChaoxingHttpClient,
            group: ChaoxingIMGroup,
            imConfig: ChaoxingEasemobIMConfig,
        ): List<ChaoxingGroupSignActivity> = emptyList()

        override suspend fun fetchSignDetail(
            client: ChaoxingHttpClient,
            activeId: Long,
            fallbackClassId: Int,
            fallbackCourseId: Long,
        ): ChaoxingSignDetailMetadata = ChaoxingSignDetailMetadata(
            activeId = activeId,
            signType = ChaoxingSignType.LOCATION,
            title = "测试位置签到",
            isCaptchaRequired = false,
            isFaceRequired = false,
            isPositionRequired = true,
            isPhotoRequired = false,
            isRefreshQRCode = false,
            signOutInfo = ChaoxingSignOutInfo(classId = fallbackClassId, courseId = fallbackCourseId),
        )

        override suspend fun prepareSign(
            client: ChaoxingHttpClient,
            activity: ChaoxingSignActivity,
        ): ChaoxingSignActivityStatus = preSignStatus

        override suspend fun sleep(delayMs: Long) {
            // Instant in tests
        }
    }

    private fun sampleActivity(id: Long = 5001L): ChaoxingSignActivity = ChaoxingSignActivity(
        id = id,
        startTimeMs = 1000L,
        endTimeMs = null,
        userStatus = 0,
        otherId = ChaoxingSignType.PHOTO_OR_CLICK.rawValue,
        isLook = false,
        type = 2,
        activeType = 2,
        nameOne = "课堂点名",
        status = 1,
        nameFour = "",
        courseId = 101L,
        classId = 202,
        courseName = "数据结构",
        extContent = "",
    )

    @Test
    fun `multi-account login switching isolates courses and notifies listener`() = runTest {
        val store = InMemoryStore()
        val deps = FakeDeps().apply {
            coursesByPhone["13800000001"] = listOf(
                ChaoxingCourse("高等数学", "张老师", 1L, 10, ""),
            )
            coursesByPhone["13800000002"] = listOf(
                ChaoxingCourse("操作系统", "李老师", 2L, 20, ""),
            )
        }
        val state = WindowsAppState(store = store, dependencies = deps, appScope = backgroundScope)
        val switchedEvents = mutableListOf<String?>()
        state.onActiveAccountChanged = { switchedEvents.add(it) }

        state.loadFromDisk()
        state.loginPrimary("13800000001", "pw1")
        assertEquals("13800000001", state.activeAccount?.phoneNumber)
        assertEquals(listOf("高等数学"), state.uiState.value.courses.map { it.courseName })

        state.addOtherAccount("13800000002", "pw2")
        assertEquals(2, state.uiState.value.accounts.size)
        assertEquals("13800000001", state.activeAccount?.phoneNumber)

        state.switchToNextAccount()
        assertEquals("13800000002", state.activeAccount?.phoneNumber)
        assertEquals(listOf("操作系统"), state.uiState.value.courses.map { it.courseName })
        assertTrue(switchedEvents.contains("13800000001"))
        assertTrue(switchedEvents.contains("13800000002"))
    }

    @Test
    fun `batch sign rotates device code on DeviceDuplicated and queues CaptchaRequired`() = runTest {
        val store = InMemoryStore()
        val deps = FakeDeps()
        val state = WindowsAppState(store = store, dependencies = deps, appScope = backgroundScope)
        state.loadFromDisk()
        state.loginPrimary("13800000001", "pw1")
        state.addOtherAccount("13800000002", "pw2")
        state.setBatchSignDelayMs(100)

        val initialDevice1 = state.uiState.value.accounts.first { it.phoneNumber == "13800000001" }.deviceCode
        var dupThrownOnce = false
        val activity = sampleActivity()

        val batchJob = async {
            state.executeSignBatch(
                activity = activity,
                selectedPhones = setOf("13800000001", "13800000002"),
            ) { _, account, captchaValidate, enc2 ->
                if (account.phoneNumber == "13800000001" && !dupThrownOnce) {
                    dupThrownOnce = true
                    throw ChaoxingError.DeviceDuplicated()
                }
                if (account.phoneNumber == "13800000002" && captchaValidate == null) {
                    throw ChaoxingError.CaptchaRequired(enc2 = "enc2-token")
                }
                if (account.phoneNumber == "13800000002") {
                    assertEquals("valid-ticket", captchaValidate)
                    assertEquals("enc2-token", enc2)
                }
                ChaoxingSignOutcome(success = true, message = "签到成功")
            }
        }

        // Wait until captcha challenge is presented, then resolve it
        var challengeId: String? = null
        repeat(40) {
            challengeId = state.uiState.value.captchaChallenge?.id
            if (challengeId != null) return@repeat
            delay(10)
        }
        assertNotNull(challengeId, "Expected captcha challenge to be queued")
        state.resolveCaptcha(challengeId!!, "valid-ticket")

        val results = batchJob.await()
        assertEquals(2, results.size)
        assertTrue(results.all { it.success })

        val updatedDevice1 = state.uiState.value.accounts.first { it.phoneNumber == "13800000001" }.deviceCode
        assertNotEquals(initialDevice1, updatedDevice1)
        assertEquals(1, state.uiState.value.signHistory.size)
    }

    @Test
    fun `deep link sign and qr route properly and backup import validates coordinates`(@TempDir tempDir: Path) = runTest {
        val store = FilePersistenceStore(tempDir.resolve("state.json").toFile())
        val deps = FakeDeps()
        val state = WindowsAppState(store = store, dependencies = deps, appScope = backgroundScope)
        state.loadFromDisk()
        state.loginPrimary("13800000001", "pw1")

        // Deep link sign
        state.handleIncomingUri("caochaoxing://sign?activeId=778899&courseId=101&classId=202")
        assertEquals(WindowsWorkspaceTab.SIGN, state.uiState.value.selectedTab)
        assertEquals(778899L, state.uiState.value.activeSignActivity?.id)

        // Deep link qr
        state.handleIncomingUri("caochaoxing://qr?data=SIGNIN%3Aaid%3D999%26enc%3Dxyz")
        assertEquals("SIGNIN:aid=999&enc=xyz", state.uiState.value.pendingQrCodeInput)

        // Export without accounts excludes account secrets
        assertTrue(state.addLocationPreset("主楼", "主楼101", 34.2, 108.9))
        val presetsOnlyJson = state.exportBackupJson(includeAccounts = false)
        assertFalse(presetsOnlyJson.contains("13800000001"))
        assertTrue(presetsOnlyJson.contains("主楼"))

        // Reject invalid coordinates in backup JSON
        val badBackup = ChaoxingAppStateSnapshot(
            locationPresets = listOf(ChaoxingLocationPreset(name = "Bad", address = "X", latitude = 199.0, longitude = 100.0)),
        ).encodedBackupJson(includeAccounts = false)
        val importErr = runCatching { state.importBackupJson(badBackup) }.exceptionOrNull()
        assertTrue(importErr is ChaoxingError.InvalidInput)
    }

    @Test
    fun `file import rejects oversized and malformed UTF8 backups before changing state`(@TempDir dir: Path) = runTest {
        val store = InMemoryStore()
        val state = WindowsAppState(store, FakeDeps(), backgroundScope)
        val file = dir.resolve("backup.json").toFile()
        java.io.RandomAccessFile(file, "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        assertTrue(runCatching { state.importBackupFile(file) }.exceptionOrNull() is ChaoxingError.InvalidInput)

        file.writeBytes(byteArrayOf(0xff.toByte()))
        assertTrue(runCatching { state.importBackupFile(file) }.exceptionOrNull() is ChaoxingError.ParseFailed)
        assertEquals(ChaoxingAppStateSnapshot(), store.snapshot)
    }

    @Test
    fun `invalid queued QR input does not block restoration of a healthy account file`() = runTest {
        val deps = FakeDeps()
        val account = deps.login("13800000001", "pw", "test", true)
        val store = InMemoryStore(ChaoxingAppStateSnapshot(accounts = listOf(account)))
        val state = WindowsAppState(store, deps, backgroundScope, initialRestoring = true)
        state.handleIncomingUri("caochaoxing://qr?data=invalid")
        state.loadFromDisk()
        assertFalse(state.uiState.value.persistenceBlocked)
        assertEquals(account.phoneNumber, state.activeAccount?.phoneNumber)
        assertNull(state.uiState.value.pendingQrCodeInput)
        assertNotNull(state.uiState.value.errorMessage)
    }

    @Test
    fun `persistence load failure blocks mutations until recoverLocalState runs`() = runTest {
        val store = InMemoryStore().apply { failLoad = true }
        val state = WindowsAppState(store = store, dependencies = FakeDeps(), appScope = backgroundScope)

        state.loadFromDisk()
        assertTrue(state.uiState.value.persistenceBlocked)
        assertNull(state.activeAccount)

        val added = state.addLocationPreset("不应写入", "测试", 30.0, 120.0)
        assertFalse(added)

        state.recoverLocalState()
        assertFalse(state.uiState.value.persistenceBlocked)
        assertTrue(state.addLocationPreset("恢复后写入", "测试", 30.0, 120.0))
    }

    @Test
    fun `refreshAccountSession preserves configuredFid and tags and standalone QR prefills input`() = runTest {
        val store = InMemoryStore()
        val deps = FakeDeps()
        val state = WindowsAppState(store = store, dependencies = deps, appScope = backgroundScope)
        state.loadFromDisk()
        state.loginPrimary("13800000001", "pw1")
        state.updateAccountFid("13800000001", 2048)
        state.updateAccountTags("13800000001", listOf("宿舍", "主号"))

        state.refreshAccountSession("13800000001")
        val refreshed = state.uiState.value.accounts.first()
        assertEquals(2048, refreshed.configuredFid)
        assertEquals(listOf("宿舍", "主号"), refreshed.tags)

        val qrPayload = "SIGNIN:aid=654321&enc=deadbeef"
        state.openSignWorkspace(sampleActivity(654321L), prefilledQrInput = qrPayload)
        assertEquals(qrPayload, state.uiState.value.pendingQrCodeInput)
        assertEquals(654321L, state.uiState.value.activeSignActivity?.id)
        assertEquals(WindowsWorkspaceTab.SIGN, state.uiState.value.selectedTab)
    }

    @Test
    fun `cross-platform backup import merges accounts presets bindings stars and rejects oversize`() = runTest {
        val store = InMemoryStore()
        val deps = FakeDeps()
        val state = WindowsAppState(store = store, dependencies = deps, appScope = backgroundScope)
        state.loadFromDisk()
        state.loginPrimary("13800000001", "pw1")
        state.toggleCourseStar(101L)

        // Simulate a JSON backup exported from macOS / iOS / Android
        val crossPlatformJson = """
            {
              "accounts": [
                {
                  "phoneNumber": "13800000001",
                  "encryptedPassword": "enc-updated",
                  "name": "主号同步",
                  "uid": 100,
                  "puid": 100,
                  "configuredFid": 3333,
                  "deviceCode": "dev-ios-1",
                  "isPrimary": true,
                  "tags": ["来自iOS"]
                },
                {
                  "phoneNumber": "13900000002",
                  "encryptedPassword": "enc-2",
                  "name": "副号",
                  "uid": 200,
                  "puid": 200,
                  "configuredFid": 4444,
                  "deviceCode": "dev-ios-2",
                  "isPrimary": false,
                  "tags": ["宿舍"]
                }
              ],
              "locationPresets": [
                {
                  "id": "preset-ios-1",
                  "name": "图书馆",
                  "address": "主校区图书馆",
                  "latitude": 34.2301,
                  "longitude": 108.9123
                }
              ],
              "selectedClientPreset": "@@xuezaixidian",
              "customUserAgent": "",
              "customClientProfiles": [
                {
                  "id": "prof-1",
                  "name": "自定义端",
                  "userAgent": "CustomUA/1.0"
                }
              ],
              "selectedCustomClientId": "prof-1",
              "enableLocationJitter": false,
              "batchSignDelayMs": 2500,
              "enableImageObfuscation": true,
              "autoSolveCaptcha": true,
              "signHistory": [],
              "courseLocationBindings": {
                "101": {
                  "id": "preset-ios-1",
                  "name": "图书馆",
                  "address": "主校区图书馆",
                  "latitude": 34.2301,
                  "longitude": 108.9123
                }
              },
              "starredCourseIds": [202]
            }
        """.trimIndent()

        state.importBackupJson(crossPlatformJson)
        val ui = state.uiState.value
        assertEquals(2, ui.accounts.size)
        assertEquals("13800000001", ui.activeAccount?.phoneNumber)
        assertEquals(setOf(101L, 202L), ui.starredCourseIds)
        assertEquals("图书馆", ui.boundLocation(101L)?.name)
        assertEquals("CustomUA/1.0", ui.currentUserAgent)
        assertEquals(2500, ui.batchSignDelayMs)
        assertFalse(ui.enableLocationJitter)

        // Reject invalid batchSignDelayMs
        val badDelay = ChaoxingAppStateSnapshot(batchSignDelayMs = 999_999).encodedBackupJson(false)
        assertTrue(runCatching { state.importBackupJson(badDelay) }.exceptionOrNull() is ChaoxingError.InvalidInput)
    }
}
