// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.state

import androidx.compose.runtime.Stable
import com.caochaoxing.core.crypto.ChaoxingCrypto
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingClientPreset
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingCustomClientProfile
import com.caochaoxing.core.model.ChaoxingEasemobIMConfig
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingGroupSignActivity
import com.caochaoxing.core.model.ChaoxingIMGroup
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingModelDefaults
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignActivityStatus
import com.caochaoxing.core.model.ChaoxingSignDetailMetadata
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.signer.ChaoxingSignExecutor
import com.caochaoxing.core.signer.ChaoxingSignOutcome
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import com.caochaoxing.core.store.ChaoxingSignHistoryEntry
import com.caochaoxing.core.store.PersistenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Central state controller for the Windows desktop client.
 * Manages protected local persistence, per-account HTTP client pools, strict account-switch
 * isolation tokens, course/group/radar loading, deep links, captcha queueing, and batch signing.
 */
@Stable
class WindowsAppState(
    private val store: PersistenceStore,
    private val dependencies: WindowsAppStateDependencies = LiveWindowsAppStateDependencies(),
    private val appScope: CoroutineScope,
    storageDescription: String = "",
    initialRestoring: Boolean = false,
) {
    private val stateLock = Any()
    private val saveMutex = Mutex()
    private val nextRequestCounter = AtomicLong(0L)

    private val _uiState = MutableStateFlow(
        WindowsUiState(
            isRestoring = initialRestoring,
            storageDescription = storageDescription,
        ),
    )
    val uiState: StateFlow<WindowsUiState> = _uiState.asStateFlow()

    private val clientPool = LinkedHashMap<String, ChaoxingHttpClient>()
    private val loadingRequests = HashSet<Long>()
    private val sessionJobs = LinkedHashMap<String, Job>()
    private val inFlightWorkJobs = LinkedHashMap<String, Job>()
    private val captchaQueue = ArrayDeque<CaptchaChallengeRequest>()
    private val captchaDeferreds = LinkedHashMap<String, CompletableDeferred<String>>()

    private var sessionGeneration: Long = 0L
    private var courseRequestId: Long = 0L
    private var groupRequestId: Long = 0L
    private var radarRequestId: Long = 0L
    private var deepLinkRequestId: Long = 0L
    private val courseActivityRequestIds = HashMap<String, Long>()
    private val groupActivityRequestIds = HashMap<String, Long>()
    private var pendingIncomingUri: String? = null
    private var presentedCaptchaId: String? = null

    /** Optional hook notified whenever the active account changes (used to isolate course study). */
    var onActiveAccountChanged: ((String?) -> Unit)? = null

    val activeAccount: ChaoxingAccountSession?
        get() = _uiState.value.activeAccount

    val currentUserAgent: String
        get() = _uiState.value.currentUserAgent

    val activeClient: ChaoxingHttpClient?
        get() = synchronized(stateLock) {
            _uiState.value.activeAccount?.let { clientForLocked(it) }
        }

    val sessionToken: SessionToken
        get() = synchronized(stateLock) { sessionTokenLocked() }

    fun isCurrent(token: SessionToken): Boolean =
        synchronized(stateLock) { isCurrentLocked(token) }

    suspend fun checkSession(token: SessionToken) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(token)) {
            throw CancellationException("账号会话已切换或失效")
        }
    }

    fun boundLocation(courseId: Long): ChaoxingLocationPreset? =
        _uiState.value.boundLocation(courseId)

    fun selectedAccounts(phones: Set<String>): List<ChaoxingAccountSession> =
        _uiState.value.accounts.filter { it.phoneNumber in phones }

    fun clientFor(session: ChaoxingAccountSession): ChaoxingHttpClient =
        synchronized(stateLock) { clientForLocked(session) }

    fun launchSessionTask(block: suspend CoroutineScope.() -> Unit): Job {
        val taskId = ChaoxingModelDefaults.newUuidString()
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                ensureActive()
                block()
            } finally {
                synchronized(stateLock) { sessionJobs.remove(taskId) }
            }
        }
        synchronized(stateLock) { sessionJobs[taskId] = job }
        job.start()
        return job
    }

    suspend fun loadFromDisk() {
        val generation = synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(
                isRestoring = true,
                errorMessage = null,
            )
            sessionGeneration
        }
        val activePhone = try {
            val snapshot = store.load()
            synchronized(stateLock) {
                if (generation != sessionGeneration) {
                    return
                }
                val phone = (snapshot.accounts.firstOrNull { it.isPrimary }
                    ?: snapshot.accounts.firstOrNull())?.phoneNumber
                applySnapshotLocked(snapshot, activePhone = phone)
                _uiState.value = _uiState.value.copy(
                    isRestoring = false,
                    persistenceBlocked = false,
                )
                phone
            }
        } catch (e: CancellationException) {
            synchronized(stateLock) {
                if (generation == sessionGeneration) {
                    _uiState.value = _uiState.value.copy(isRestoring = false)
                }
            }
            throw e
        } catch (e: Exception) {
            synchronized(stateLock) {
                if (generation == sessionGeneration) {
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        persistenceBlocked = true,
                        errorMessage = "读取本地会话失败，原文件已保留；请先恢复或归档重置后再试。${e.message.orEmpty()}",
                    )
                }
            }
            return
        }
        onActiveAccountChanged?.invoke(activePhone)
        if (activeClient != null) refreshCourses()
        consumePendingIncomingUri()
    }

    suspend fun saveToDisk(): Boolean = saveMutex.withLock {
        val snapshot = synchronized(stateLock) {
            val current = _uiState.value
            if (current.isRestoring || current.persistenceBlocked) {
                _uiState.value = current.copy(errorMessage = "本地状态尚未成功读取，已阻止覆盖原文件")
                null
            } else {
                val updatedAccounts = mergePoolSessionsLocked(current.accounts)
                val updatedState = current.copy(accounts = updatedAccounts)
                _uiState.value = updatedState
                updatedState.toSnapshot()
            }
        } ?: return@withLock false

        try {
            store.save(snapshot)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(stateLock) {
                _uiState.value = _uiState.value.copy(
                    statusMessage = null,
                    errorMessage = "保存本地状态失败：${e.message ?: "未知错误"}",
                )
            }
            false
        }
    }

    suspend fun loginPrimary(phoneNumber: String, password: String) {
        requireReadyState()
        val token = sessionToken
        val loadingId = beginLoading()
        try {
            val session = dependencies.login(phoneNumber, password, currentUserAgent, true)
            checkSession(token)
            val (jobsToCancel, deferredsToCancel) = synchronized(stateLock) {
                val current = _uiState.value
                if (current.isRestoring || current.persistenceBlocked) {
                    throw ChaoxingError.InvalidInput("本地状态尚未成功读取，请先完成恢复")
                }
                val cancelled = invalidateSessionOperationsLocked()
                val refreshed = _uiState.value
                val existingTags = refreshed.accounts
                    .firstOrNull { it.phoneNumber == session.phoneNumber }
                    ?.tags
                    .orEmpty()
                val primarySession = session.copy(
                    isPrimary = true,
                    tags = normalizeAccountTags(existingTags + session.tags),
                )
                val others = refreshed.accounts
                    .filterNot { it.phoneNumber == primarySession.phoneNumber }
                    .map { it.copy(isPrimary = false) }
                replacePoolClientLocked(primarySession, refreshed.currentUserAgent)
                _uiState.value = refreshed.copy(
                    accounts = listOf(primarySession) + others,
                    activePhoneNumber = primarySession.phoneNumber,
                    showAddAccountDialog = false,
                    statusMessage = "已登录 ${primarySession.name.ifBlank { primarySession.phoneNumber }}",
                    errorMessage = null,
                )
                cancelled
            }
            cancelCollectedOperations(jobsToCancel, deferredsToCancel)
            onActiveAccountChanged?.invoke(session.phoneNumber)
            saveToDisk()
            consumePendingIncomingUri()
            refreshCourses()
        } finally {
            endLoading(loadingId)
        }
    }

    suspend fun addOtherAccount(phoneNumber: String, password: String) {
        requireReadyState()
        val token = sessionToken
        val loadingId = beginLoading()
        try {
            val session = dependencies.login(phoneNumber, password, currentUserAgent, false)
            checkSession(token)
            var cancelledPair: Pair<List<Job>, List<CompletableDeferred<String>>> = emptyList<Job>() to emptyList()
            var activeChangedTo: String? = null
            synchronized(stateLock) {
                val current = _uiState.value
                if (current.isRestoring || current.persistenceBlocked) {
                    throw ChaoxingError.InvalidInput("本地状态尚未成功读取，请先完成恢复")
                }
                val existingIndex = current.accounts.indexOfFirst { it.phoneNumber == session.phoneNumber }
                val isFirstAccount = current.accounts.isEmpty()
                val replacesActive = existingIndex >= 0 && current.activeAccount?.phoneNumber == session.phoneNumber
                if (replacesActive || isFirstAccount) {
                    cancelledPair = invalidateSessionOperationsLocked()
                }
                val refreshed = _uiState.value
                val nextAccounts = refreshed.accounts.toMutableList()
                val savedAccount = if (existingIndex >= 0) {
                    val prev = nextAccounts[existingIndex]
                    session.copy(
                        isPrimary = prev.isPrimary || isFirstAccount,
                        tags = normalizeAccountTags(prev.tags + session.tags),
                    ).also { nextAccounts[existingIndex] = it }
                } else {
                    session.copy(
                        isPrimary = isFirstAccount,
                        tags = normalizeAccountTags(session.tags),
                    ).also { nextAccounts.add(it) }
                }
                replacePoolClientLocked(savedAccount, refreshed.currentUserAgent)
                val nextActivePhone = if (isFirstAccount) savedAccount.phoneNumber else refreshed.activePhoneNumber
                if (replacesActive || isFirstAccount) {
                    activeChangedTo = nextActivePhone
                }
                _uiState.value = refreshed.copy(
                    accounts = nextAccounts,
                    activePhoneNumber = nextActivePhone,
                    showAddAccountDialog = false,
                    statusMessage = "已添加账号 ${savedAccount.name.ifBlank { savedAccount.phoneNumber }}",
                    errorMessage = null,
                )
            }
            cancelCollectedOperations(cancelledPair.first, cancelledPair.second)
            if (activeChangedTo != null) {
                onActiveAccountChanged?.invoke(activeChangedTo)
            }
            saveToDisk()
            if (activeChangedTo != null) {
                refreshCourses()
            }
        } finally {
            endLoading(loadingId)
        }
    }

    suspend fun switchAccount(phoneNumber: String) {
        requireReadyState()
        val (switched, cancelledPair) = synchronized(stateLock) {
            val current = _uiState.value
            val target = current.accounts.firstOrNull { it.phoneNumber == phoneNumber }
                ?: return@synchronized (false to (emptyList<Job>() to emptyList()))
            if (current.activeAccount?.phoneNumber == phoneNumber && target.isPrimary) {
                return@synchronized (false to (emptyList<Job>() to emptyList()))
            }
            val cancelled = invalidateSessionOperationsLocked()
            val refreshed = _uiState.value
            val updatedAccounts = mergePoolSessionsLocked(refreshed.accounts).map { acc ->
                acc.copy(isPrimary = acc.phoneNumber == phoneNumber)
            }
            _uiState.value = refreshed.copy(
                accounts = updatedAccounts,
                activePhoneNumber = phoneNumber,
                activeSignActivity = null,
                statusMessage = "已切换至 ${target.name.ifBlank { target.phoneNumber }}",
                errorMessage = null,
            )
            true to cancelled
        }
        if (!switched) return
        cancelCollectedOperations(cancelledPair.first, cancelledPair.second)
        onActiveAccountChanged?.invoke(phoneNumber)
        saveToDisk()
        refreshCourses()
    }

    suspend fun switchToNextAccount() {
        val accounts = _uiState.value.accounts
        if (accounts.size <= 1) return
        val activePhone = _uiState.value.activeAccount?.phoneNumber
        val idx = accounts.indexOfFirst { it.phoneNumber == activePhone }
        val next = accounts[(idx + 1) % accounts.size]
        switchAccount(next.phoneNumber)
    }

    suspend fun refreshAccountSession(phoneNumber: String) {
        requireReadyState()
        val account = _uiState.value.accounts.firstOrNull { it.phoneNumber == phoneNumber } ?: return
        val client = clientFor(account)
        val loadingId = beginLoading()
        try {
            val updated = dependencies.reLogin(client)
            val merged = updated.copy(
                isPrimary = account.isPrimary,
                configuredFid = account.configuredFid,
                tags = account.tags,
            )
            client.updateSession(merged)
            updatePersistableState { current ->
                val nextAccounts = current.accounts.map { acc ->
                    if (acc.phoneNumber == phoneNumber) {
                        merged.copy(
                            isPrimary = acc.isPrimary,
                            configuredFid = acc.configuredFid,
                            tags = acc.tags,
                        )
                    } else {
                        acc
                    }
                }
                current.copy(
                    accounts = nextAccounts,
                    statusMessage = "已刷新 ${merged.name.ifBlank { phoneNumber }} 的会话",
                    errorMessage = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setErrorMessage("刷新会话失败：${e.message ?: "未知错误"}")
        } finally {
            endLoading(loadingId)
        }
    }

    suspend fun removeAccount(phoneNumber: String) {
        requireReadyState()
        var nextActive: String? = null
        val (proceed, cancelledPair) = synchronized(stateLock) {
            val current = _uiState.value
            if (current.isRestoring || current.persistenceBlocked) {
                _uiState.value = current.copy(errorMessage = BLOCKED_PERSISTENCE_MUTATION_MESSAGE)
                return@synchronized (false to (emptyList<Job>() to emptyList()))
            }
            val remaining = current.accounts.filterNot { it.phoneNumber == phoneNumber }
            val wasActive = current.activeAccount?.phoneNumber == phoneNumber
            clientPool.remove(phoneNumber)?.invalidate()
            val cancelled = if (wasActive) invalidateSessionOperationsLocked() else (emptyList<Job>() to emptyList())
            val refreshed = _uiState.value
            val chosenPrimary = remaining.firstOrNull { it.isPrimary }?.phoneNumber
                ?: remaining.firstOrNull()?.phoneNumber
            val normalized = remaining.map { it.copy(isPrimary = it.phoneNumber == chosenPrimary) }
            nextActive = if (wasActive) chosenPrimary else refreshed.activePhoneNumber
            _uiState.value = refreshed.copy(
                accounts = normalized,
                activePhoneNumber = nextActive,
                activeSignActivity = if (wasActive) null else refreshed.activeSignActivity,
                statusMessage = "已移出账号 $phoneNumber",
            )
            true to cancelled
        }
        if (!proceed) return
        cancelCollectedOperations(cancelledPair.first, cancelledPair.second)
        onActiveAccountChanged?.invoke(nextActive)
        saveToDisk()
        if (nextActive != null) {
            refreshCourses()
        }
    }

    suspend fun updateAccountFid(phoneNumber: String, fid: Int) {
        updatePersistableState { current ->
            val nextAccounts = current.accounts.map { acc ->
                if (acc.phoneNumber == phoneNumber) {
                    val client = clientPool[phoneNumber]
                    if (client != null) {
                        client.updateConfiguredFid(fid)
                        client.session
                    } else {
                        acc.copy(configuredFid = fid)
                    }
                } else {
                    acc
                }
            }
            current.copy(
                accounts = nextAccounts,
                statusMessage = "已更新学校 FID 为 $fid",
            )
        }
    }

    suspend fun resetAccountDeviceCode(phoneNumber: String) {
        val newCode = ChaoxingCrypto.randomizedDeviceCode()
        updatePersistableState { current ->
            val nextAccounts = current.accounts.map { acc ->
                if (acc.phoneNumber == phoneNumber) {
                    val updated = acc.copy(deviceCode = newCode)
                    clientPool[phoneNumber]?.updateSession(updated)
                    updated
                } else {
                    acc
                }
            }
            current.copy(
                accounts = nextAccounts,
                statusMessage = "已为账号 $phoneNumber 重置设备码",
            )
        }
    }

    suspend fun updateAccountTags(phoneNumber: String, tags: List<String>) {
        val normalized = normalizeAccountTags(tags)
        updatePersistableState { current ->
            clientPool[phoneNumber]?.updateTags(normalized)
            val nextAccounts = current.accounts.map { acc ->
                if (acc.phoneNumber == phoneNumber) acc.copy(tags = normalized) else acc
            }
            current.copy(accounts = nextAccounts)
        }
    }

    suspend fun refreshCourses() {
        val acquired = acquireSessionRequestLocked { _, _ ->
            courseRequestId += 1L
            courseRequestId
        } ?: return
        val (client, token, requestId) = acquired
        val loadingId = beginLoading()
        try {
            val courses = runCancellableSessionWork(WORK_KEY_COURSES) {
                dependencies.fetchCourses(client)
            }
            checkSession(token)
            synchronized(stateLock) {
                if (!isCurrentLocked(token) || courseRequestId != requestId) return@synchronized
                _uiState.value = _uiState.value.copy(
                    courses = courses,
                    errorMessage = null,
                )
            }
            saveToDisk()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && courseRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "获取课程列表失败：${e.message ?: "未知错误"}",
                    )
                }
            }
        } finally {
            endLoading(loadingId)
        }
    }

    suspend fun loadCourseActivities(course: ChaoxingCourse, forceRefresh: Boolean = false) {
        val acquired = acquireSessionRequestLocked { current, _ ->
            if (!forceRefresh && course.id in current.courseActivities && course.id !in current.failedCourseActivityIds) {
                null
            } else {
                val reqId = nextRequestCounter.incrementAndGet()
                courseActivityRequestIds[course.id] = reqId
                _uiState.value = current.copy(
                    loadingCourseIds = current.loadingCourseIds + course.id,
                    failedCourseActivityIds = current.failedCourseActivityIds - course.id,
                )
                reqId
            }
        } ?: return
        val (client, token, requestId) = acquired
        try {
            val activities = runCancellableSessionWork("$WORK_KEY_COURSE_ACTIVITIES_PREFIX${course.id}") {
                dependencies.fetchCourseActivities(client, course)
            }
            checkSession(token)
            synchronized(stateLock) {
                if (!isCurrentLocked(token) || courseActivityRequestIds[course.id] != requestId) return@synchronized
                val cur = _uiState.value
                _uiState.value = cur.copy(
                    courseActivities = cur.courseActivities + (course.id to activities),
                    loadingCourseIds = cur.loadingCourseIds - course.id,
                    failedCourseActivityIds = cur.failedCourseActivityIds - course.id,
                )
            }
        } catch (e: CancellationException) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && courseActivityRequestIds[course.id] == requestId) {
                    val cur = _uiState.value
                    _uiState.value = cur.copy(loadingCourseIds = cur.loadingCourseIds - course.id)
                }
            }
            throw e
        } catch (e: Exception) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && courseActivityRequestIds[course.id] == requestId) {
                    val cur = _uiState.value
                    _uiState.value = cur.copy(
                        loadingCourseIds = cur.loadingCourseIds - course.id,
                        failedCourseActivityIds = cur.failedCourseActivityIds + course.id,
                        errorMessage = "获取「${course.courseName}」活动失败：${e.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    suspend fun scanPendingRadar() {
        val acquired = acquireSessionRequestLocked { current, _ ->
            if (current.courses.isEmpty()) return@acquireSessionRequestLocked null
            radarRequestId += 1L
            _uiState.value = current.copy(
                isScanningRadar = true,
                errorMessage = null,
            )
            radarRequestId to current.courses
        } ?: return
        val (client, token, payload) = acquired
        val (requestId, courses) = payload
        try {
            val starredIds = _uiState.value.starredCourseIds
            val starred = courses.filter { it.courseId in starredIds }
            val unstarred = courses.filterNot { it.courseId in starredIds }.take(MAX_UNSTARRED_RADAR_COURSES)
            val targets = starred + unstarred

            val found = runCancellableSessionWork(WORK_KEY_RADAR) {
                val results = targets.map { course ->
                    async {
                        try {
                            val acts = dependencies.fetchCourseActivities(client, course)
                            course to acts
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            course to null
                        }
                    }
                }.awaitAll()
                results
            }
            checkSession(token)
            synchronized(stateLock) {
                if (!isCurrentLocked(token) || radarRequestId != requestId) return@synchronized
                val cur = _uiState.value
                val nextActivitiesMap = cur.courseActivities.toMutableMap()
                val radarItems = mutableListOf<PendingRadarItem>()
                for ((course, acts) in found) {
                    if (acts != null) {
                        nextActivitiesMap[course.id] = acts
                        for (act in acts) {
                            if (act.isAvailable && !act.isAlreadySignedByStatus && !act.isLate) {
                                radarItems.add(PendingRadarItem(course = course, activity = act))
                            }
                        }
                    }
                }
                _uiState.value = cur.copy(
                    courseActivities = nextActivitiesMap,
                    pendingRadarItems = radarItems,
                    isScanningRadar = false,
                    statusMessage = if (radarItems.isEmpty()) {
                        "雷达扫描完成（${targets.size} 门课程），暂无待签活动"
                    } else {
                        "雷达发现 ${radarItems.size} 个待签活动"
                    },
                )
            }
        } catch (e: CancellationException) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && radarRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(isScanningRadar = false)
                }
            }
            throw e
        } catch (e: Exception) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && radarRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(
                        isScanningRadar = false,
                        errorMessage = "雷达扫描失败：${e.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    suspend fun refreshGroups(forceRefreshIm: Boolean = false) {
        val acquired = acquireSessionRequestLocked { _, _ ->
            groupRequestId += 1L
            groupRequestId
        } ?: return
        val (client, token, requestId) = acquired
        val loadingId = beginLoading()
        try {
            val groups = runCancellableSessionWork(WORK_KEY_GROUPS) {
                fetchWithImTokenRefresh(
                    client = client,
                    token = token,
                    forceInitialRefresh = forceRefreshIm,
                    isRequestActive = { synchronized(stateLock) { groupRequestId == requestId } },
                ) { config ->
                    dependencies.fetchGroups(client, config)
                }
            } ?: return
            checkSession(token)
            synchronized(stateLock) {
                if (!isCurrentLocked(token) || groupRequestId != requestId) return@synchronized
                _uiState.value = _uiState.value.copy(
                    groups = groups,
                    errorMessage = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && groupRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "获取群聊列表失败：${e.message ?: "未知错误"}",
                    )
                }
            }
        } finally {
            endLoading(loadingId)
        }
    }

    suspend fun loadGroupActivities(group: ChaoxingIMGroup, forceRefresh: Boolean = false) {
        val acquired = acquireSessionRequestLocked { current, _ ->
            if (!forceRefresh && group.id in current.groupActivities && group.id !in current.failedGroupActivityIds) {
                null
            } else {
                val reqId = nextRequestCounter.incrementAndGet()
                groupActivityRequestIds[group.id] = reqId
                _uiState.value = current.copy(
                    loadingGroupIds = current.loadingGroupIds + group.id,
                    failedGroupActivityIds = current.failedGroupActivityIds - group.id,
                )
                reqId
            }
        } ?: return
        val (client, token, requestId) = acquired
        try {
            val activities = runCancellableSessionWork("$WORK_KEY_GROUP_ACTIVITIES_PREFIX${group.id}") {
                fetchWithImTokenRefresh(
                    client = client,
                    token = token,
                    forceInitialRefresh = false,
                    isRequestActive = { synchronized(stateLock) { groupActivityRequestIds[group.id] == requestId } },
                ) { config ->
                    dependencies.fetchGroupActivities(client, group, config)
                }
            } ?: return
            checkSession(token)
            synchronized(stateLock) {
                if (!isCurrentLocked(token) || groupActivityRequestIds[group.id] != requestId) return@synchronized
                val cur = _uiState.value
                _uiState.value = cur.copy(
                    groupActivities = cur.groupActivities + (group.id to activities),
                    loadingGroupIds = cur.loadingGroupIds - group.id,
                    failedGroupActivityIds = cur.failedGroupActivityIds - group.id,
                )
            }
        } catch (e: CancellationException) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && groupActivityRequestIds[group.id] == requestId) {
                    val cur = _uiState.value
                    _uiState.value = cur.copy(loadingGroupIds = cur.loadingGroupIds - group.id)
                }
            }
            throw e
        } catch (e: Exception) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && groupActivityRequestIds[group.id] == requestId) {
                    val cur = _uiState.value
                    _uiState.value = cur.copy(
                        loadingGroupIds = cur.loadingGroupIds - group.id,
                        failedGroupActivityIds = cur.failedGroupActivityIds + group.id,
                        errorMessage = "读取群聊「${group.name}」签到活动失败：${e.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    suspend fun fetchSignDetail(
        activeId: Long,
        fallbackClassId: Int,
        fallbackCourseId: Long,
    ): ChaoxingSignDetailMetadata? {
        val client = activeClient ?: return null
        return dependencies.fetchSignDetail(client, activeId, fallbackClassId, fallbackCourseId)
    }

    suspend fun prepareSign(activity: ChaoxingSignActivity): ChaoxingSignActivityStatus {
        val client = activeClient ?: throw ChaoxingError.SessionExpired("请先登录账号")
        return dependencies.prepareSign(client, activity)
    }

    /**
     * Handles `caochaoxing://sign?...` and `caochaoxing://qr?...` desktop deep links.
     */
    suspend fun handleIncomingUri(rawUri: String) {
        val trimmed = rawUri.trim()
        if (trimmed.isEmpty()) return
        val shouldDefer = synchronized(stateLock) {
            val cur = _uiState.value
            if (cur.isRestoring || cur.activeAccount == null) {
                pendingIncomingUri = trimmed
                true
            } else {
                false
            }
        }
        if (shouldDefer) return

        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            setErrorMessage("无法解析外部链接：$trimmed")
            return
        }
        if (!uri.scheme.equals("caochaoxing", ignoreCase = true)) return

        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val queryItems = parseQueryItemsStrict(uri.rawQuery)
        if (queryItems == null) {
            setErrorMessage("外部链接包含无效的 URL 编码参数")
            return
        }
        fun query(name: String): String? = queryItems.firstOrNull { it.first == name }?.second

        when (host) {
            "qr" -> {
                val qrRaw = query("data") ?: query("url")
                if (qrRaw.isNullOrBlank()) {
                    setErrorMessage("二维码链接缺少 data 参数")
                    return
                }
                val parsed = parseQrInputOrNull(qrRaw)
                if (parsed == null) {
                    setErrorMessage("外部链接中的二维码内容无效")
                    return
                }
                synchronized(stateLock) {
                    _uiState.value = _uiState.value.copy(
                        pendingQrCodeInput = qrRaw,
                        selectedTab = WindowsWorkspaceTab.SIGN,
                        statusMessage = parsed.aid?.let { "已导入二维码活动 #$it" } ?: "已填入二维码内容",
                    )
                }
            }
            "sign" -> {
                val activeId = (query("activeId") ?: query("id"))?.toLongOrNull()
                if (activeId == null || activeId <= 0L) {
                    setErrorMessage("外部签到链接缺少有效的 activeId")
                    return
                }
                val courseId = query("courseId")?.toLongOrNull() ?: 0L
                val classId = query("classId")?.toIntOrNull() ?: 0
                val acquired = acquireSessionRequestLocked { _, _ ->
                    deepLinkRequestId += 1L
                    deepLinkRequestId
                } ?: return
                val (client, token, requestId) = acquired
                val metadata = runCancellableSessionWork(WORK_KEY_DEEP_LINK) {
                    try {
                        dependencies.fetchSignDetail(client, activeId, classId, courseId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                }
                synchronized(stateLock) {
                    if (!isCurrentLocked(token) || deepLinkRequestId != requestId) return@synchronized
                    val fallbackTitle = "外部跳转签到 #$activeId"
                    val activity = ChaoxingSignActivity(
                        id = activeId,
                        startTimeMs = null,
                        endTimeMs = null,
                        userStatus = 0,
                        otherId = metadata?.signType?.rawValue ?: (query("type") ?: "-1"),
                        isLook = false,
                        type = 2,
                        activeType = 2,
                        nameOne = metadata?.title?.ifEmpty { fallbackTitle } ?: fallbackTitle,
                        status = 1,
                        nameFour = "",
                        courseId = metadata?.signOutInfo?.courseId?.takeIf { it != 0L } ?: courseId,
                        classId = metadata?.signOutInfo?.classId?.takeIf { it != 0 } ?: classId,
                        courseName = "",
                        extContent = "",
                    )
                    _uiState.value = _uiState.value.copy(
                        activeSignActivity = activity,
                        selectedTab = WindowsWorkspaceTab.SIGN,
                        statusMessage = "已打开外部签到活动：${activity.nameOne}",
                    )
                }
            }
        }
    }

    fun requestCaptchaValidation(
        client: ChaoxingHttpClient,
        refererUrl: String,
    ): CompletableDeferred<String> {
        val request = CaptchaChallengeRequest(client = client, refererUrl = refererUrl)
        val deferred = CompletableDeferred<String>()
        synchronized(stateLock) {
            captchaDeferreds[request.id] = deferred
            captchaQueue.addLast(request)
            presentNextCaptchaLocked()
        }
        return deferred
    }

    suspend fun awaitCaptchaValidation(
        client: ChaoxingHttpClient,
        refererUrl: String,
    ): String {
        val request = CaptchaChallengeRequest(client = client, refererUrl = refererUrl)
        val deferred = CompletableDeferred<String>()
        synchronized(stateLock) {
            captchaDeferreds[request.id] = deferred
            captchaQueue.addLast(request)
            presentNextCaptchaLocked()
        }
        try {
            return deferred.await()
        } finally {
            synchronized(stateLock) {
                if (request.id in captchaDeferreds) {
                    dequeueCaptchaLocked(request.id)
                }
            }
        }
    }

    fun resolveCaptcha(id: String, validate: String) {
        val deferred = synchronized(stateLock) { dequeueCaptchaLocked(id) }
        deferred?.complete(validate)
    }

    fun failCaptcha(id: String, message: String = "用户取消了滑块验证") {
        val deferred = synchronized(stateLock) { dequeueCaptchaLocked(id) }
        deferred?.completeExceptionally(ChaoxingError.CaptchaFailed(message))
    }

    suspend fun executeSignBatch(
        activity: ChaoxingSignActivity,
        selectedPhones: Set<String>,
        onProgress: (List<BatchSignResult>) -> Unit = {},
        operation: suspend (
            client: ChaoxingHttpClient,
            account: ChaoxingAccountSession,
            captchaValidate: String?,
            enc2: String?,
        ) -> ChaoxingSignOutcome,
    ): List<BatchSignResult> {
        requireReadyState()
        val token = sessionToken
        val targets = selectedAccounts(selectedPhones)
        if (targets.isEmpty()) {
            throw ChaoxingError.InvalidInput("请至少选择一个签到账号")
        }
        val pairs = targets.map { account -> account to clientFor(account) }
        val delayMs = _uiState.value.batchSignDelayMs.coerceIn(0, MAX_BATCH_DELAY_MS)
        val collected = mutableListOf<BatchSignResult>()
        val challenges = mutableListOf<Triple<ChaoxingAccountSession, ChaoxingHttpClient, String?>>()

        suspend fun collectOutcome(
            account: ChaoxingAccountSession,
            client: ChaoxingHttpClient,
            result: Result<ChaoxingSignOutcome>,
        ) {
            checkSession(token)
            val displayName = account.name.ifBlank { account.phoneNumber }
            if (result.isSuccess) {
                val outcome = result.getOrThrow()
                collected.add(
                    BatchSignResult(
                        accountName = displayName,
                        phoneNumber = account.phoneNumber,
                        success = outcome.success,
                        message = outcome.message,
                    ),
                )
            } else {
                val error = result.exceptionOrNull()!!
                if (error is CancellationException) throw error
                if (error is ChaoxingError.CaptchaRequired) {
                    challenges.add(Triple(account, client, error.enc2))
                } else {
                    collected.add(
                        BatchSignResult(
                            accountName = displayName,
                            phoneNumber = account.phoneNumber,
                            success = false,
                            message = error.message ?: "签到失败",
                        ),
                    )
                }
            }
            onProgress(collected.toList())
        }

        runCancellableSessionWork {
            if (delayMs == 0 && pairs.size > 1) {
                val attempts = pairs.map { (account, client) ->
                    async {
                        val result = runSignAttemptWithDeviceRetry(account, client, activity, token, operation)
                        Triple(account, client, result)
                    }
                }.awaitAll()
                for ((account, client, result) in attempts) {
                    collectOutcome(account, client, result)
                }
            } else {
                for ((index, pair) in pairs.withIndex()) {
                    checkSession(token)
                    if (index > 0 && delayMs > 0) {
                        val jitteredDelayMs = (delayMs.toDouble() * Random.nextDouble(0.75, 1.35))
                            .toLong()
                            .coerceAtLeast(1L)
                        dependencies.sleep(jitteredDelayMs)
                        checkSession(token)
                    }
                    val result = runSignAttemptWithDeviceRetry(pair.first, pair.second, activity, token, operation)
                    collectOutcome(pair.first, pair.second, result)
                }
            }

            for ((account, client, enc2) in challenges) {
                checkSession(token)
                val displayName = account.name.ifBlank { account.phoneNumber }
                try {
                    val referer = ChaoxingSignExecutor.preSignUrl(client, activity)
                    val validate = awaitCaptchaValidation(client, referer)
                    checkSession(token)
                    val outcome = try {
                        operation(client, account, validate, enc2)
                    } catch (_: ChaoxingError.DeviceDuplicated) {
                        val rotated = rotateDeviceCodeForClient(account, client)
                        operation(client, rotated, validate, enc2)
                    }
                    checkSession(token)
                    collected.add(
                        BatchSignResult(
                            accountName = displayName,
                            phoneNumber = account.phoneNumber,
                            success = outcome.success,
                            message = outcome.message,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    checkSession(token)
                    collected.add(
                        BatchSignResult(
                            accountName = displayName,
                            phoneNumber = account.phoneNumber,
                            success = false,
                            message = e.message ?: "验证码签到失败",
                        ),
                    )
                }
                onProgress(collected.toList())
            }
        }

        checkSession(token)
        val successCount = collected.count { it.success }
        val summary = collected.joinToString("；") { "${it.accountName}: ${it.message}" }
        recordSignHistory(activity, successCount, collected.size, summary)
        return collected.toList()
    }

    private suspend fun runSignAttemptWithDeviceRetry(
        account: ChaoxingAccountSession,
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
        token: SessionToken,
        operation: suspend (ChaoxingHttpClient, ChaoxingAccountSession, String?, String?) -> ChaoxingSignOutcome,
    ): Result<ChaoxingSignOutcome> = try {
        checkSession(token)
        when (dependencies.prepareSign(client, activity)) {
            ChaoxingSignActivityStatus.ALREADY_SIGNED ->
                Result.success(ChaoxingSignOutcome(success = true, message = "已经签到，无需重复提交"))
            ChaoxingSignActivityStatus.EXPIRED ->
                throw ChaoxingError.SignExpired("签到已截止")
            ChaoxingSignActivityStatus.READY_TO_SIGN -> {
                val outcome = try {
                    operation(client, account, null, null)
                } catch (_: ChaoxingError.DeviceDuplicated) {
                    val rotated = rotateDeviceCodeForClient(account, client)
                    operation(client, rotated, null, null)
                }
                checkSession(token)
                Result.success(outcome)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun rotateDeviceCodeForClient(
        account: ChaoxingAccountSession,
        client: ChaoxingHttpClient,
    ): ChaoxingAccountSession {
        val newCode = ChaoxingCrypto.randomizedDeviceCode()
        val updated = client.session.copy(deviceCode = newCode)
        client.updateSession(updated)
        updatePersistableState { current ->
            current.copy(
                accounts = current.accounts.map { acc ->
                    if (acc.phoneNumber == account.phoneNumber) acc.copy(deviceCode = newCode) else acc
                },
            )
        }
        return updated
    }

    fun exportBackupJson(includeAccounts: Boolean): String {
        val snapshot = synchronized(stateLock) {
            val current = _uiState.value
            val updatedAccounts = mergePoolSessionsLocked(current.accounts)
            current.copy(accounts = updatedAccounts).toSnapshot()
        }
        return snapshot.encodedBackupJson(includeAccounts = includeAccounts)
    }

    suspend fun importBackupFile(file: File) {
        val data = withContext(Dispatchers.IO) {
            file.inputStream().use { it.readNBytes(MAX_BACKUP_BYTES + 1) }
        }
        importBackupData(data)
    }

    suspend fun importBackupJson(rawJson: String) = importBackupData(rawJson.toByteArray(Charsets.UTF_8))

    private suspend fun importBackupData(payloadBytes: ByteArray) {
        requireReadyState()
        if (payloadBytes.size > MAX_BACKUP_BYTES) {
            throw ChaoxingError.InvalidInput("备份文件超过 8 MB，请检查文件内容")
        }
        val imported = try {
            ChaoxingAppStateSnapshot.decodedBackupData(payloadBytes)
        } catch (e: CharacterCodingException) {
            throw ChaoxingError.ParseFailed("备份文件不是有效的 UTF-8 文本")
        } catch (e: SerializationException) {
            throw ChaoxingError.ParseFailed("备份文件 JSON 格式无效：${e.message ?: "解析失败"}")
        } catch (e: IllegalArgumentException) {
            throw ChaoxingError.ParseFailed("备份文件 JSON 格式无效：${e.message ?: "解析失败"}")
        }

        val importedLocations = imported.locationPresets + imported.courseLocationBindings.values
        val hasValidCoordinates = importedLocations.all { preset ->
            preset.latitude.isFinite() &&
                preset.longitude.isFinite() &&
                preset.latitude in -90.0..90.0 &&
                preset.longitude in -180.0..180.0
        }
        if (!hasValidCoordinates) {
            throw ChaoxingError.InvalidInput("备份中包含无效经纬度，未应用任何修改")
        }
        if (imported.batchSignDelayMs !in 0..MAX_BATCH_DELAY_MS) {
            throw ChaoxingError.InvalidInput("备份中的错峰间隔必须在 0–$MAX_BATCH_DELAY_MS 毫秒之间")
        }

        var nextActive: String? = null
        saveMutex.withLock {
            val (candidate, resolvedActivePhone) = synchronized(stateLock) {
                val current = _uiState.value
                val baseAccounts = mergePoolSessionsLocked(current.accounts)
                val base = current.copy(accounts = baseAccounts).toSnapshot()
                mergeAppStateSnapshots(
                    base = base,
                    imported = imported,
                    currentActivePhone = current.activePhoneNumber,
                )
            }
            nextActive = resolvedActivePhone

            val cancelledPair = synchronized(stateLock) {
                val cancelled = invalidateSessionOperationsLocked()
                _uiState.value = _uiState.value.copy(isRestoring = true)
                cancelled
            }
            cancelCollectedOperations(cancelledPair.first, cancelledPair.second)

            try {
                store.save(candidate)
            } catch (e: CancellationException) {
                synchronized(stateLock) { _uiState.value = _uiState.value.copy(isRestoring = false) }
                throw e
            } catch (e: Exception) {
                synchronized(stateLock) {
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        errorMessage = "保存导入配置失败，原设置未改变：${e.message ?: "未知错误"}",
                    )
                }
                throw e
            }

            synchronized(stateLock) {
                applySnapshotLocked(candidate, activePhone = resolvedActivePhone)
                _uiState.value = _uiState.value.copy(
                    isRestoring = false,
                    persistenceBlocked = false,
                    statusMessage = "已合并备份；同账号会话与偏好已更新",
                )
            }
        }

        onActiveAccountChanged?.invoke(nextActive)
        if (activeClient != null) {
            refreshCourses()
        }
    }

    suspend fun toggleCourseStar(courseId: Long) {
        updatePersistableState { current ->
            val next = if (courseId in current.starredCourseIds) {
                current.starredCourseIds - courseId
            } else {
                current.starredCourseIds + courseId
            }
            current.copy(starredCourseIds = next)
        }
    }

    suspend fun bindLocationToCourse(courseId: Long, preset: ChaoxingLocationPreset?) {
        if (courseId == 0L) return
        updatePersistableState { current ->
            val nextMap = if (preset == null) {
                current.courseLocationBindings - courseId.toString()
            } else {
                current.courseLocationBindings + (courseId.toString() to preset)
            }
            current.copy(
                courseLocationBindings = nextMap,
                statusMessage = if (preset == null) "已解除课程默认位置绑定" else "已绑定位置「${preset.name}」到当前课程",
            )
        }
    }

    suspend fun recordSignHistory(
        activity: ChaoxingSignActivity,
        successCount: Int,
        totalCount: Int,
        summary: String,
    ) {
        val entry = ChaoxingSignHistoryEntry(
            activeId = activity.id,
            activityName = activity.nameOne.ifEmpty { activity.signType.displayName },
            courseName = activity.courseName.ifEmpty { "活动 #${activity.id}" },
            signTypeRaw = activity.signType.rawValue,
            successCount = successCount,
            totalCount = totalCount,
            summary = summary,
        )
        updatePersistableState { current ->
            current.copy(signHistory = (listOf(entry) + current.signHistory).take(MAX_SIGN_HISTORY))
        }
    }

    suspend fun clearSignHistory() {
        updatePersistableState { current ->
            current.copy(signHistory = emptyList(), statusMessage = "已清空签到记录")
        }
    }

    suspend fun addLocationPreset(
        name: String,
        address: String,
        latitude: Double,
        longitude: Double,
    ): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            setErrorMessage("请输入有效坐标：纬度 −90～90，经度 −180～180")
            return false
        }
        val preset = ChaoxingLocationPreset(
            name = name.trim().ifEmpty { address.trim().ifEmpty { "常用地点" } },
            address = address.trim(),
            latitude = latitude,
            longitude = longitude,
        )
        val added = updateStateIfReadyLocked { current ->
            current.copy(locationPresets = current.locationPresets + preset)
        }
        if (!added) return false
        if (!saveToDisk()) {
            synchronized(stateLock) {
                val cur = _uiState.value
                _uiState.value = cur.copy(locationPresets = cur.locationPresets.filterNot { it.id == preset.id })
            }
            return false
        }
        setStatusMessage("已保存常用位置「${preset.name}」")
        return true
    }

    suspend fun removeLocationPreset(preset: ChaoxingLocationPreset) {
        updatePersistableState { current ->
            current.copy(
                locationPresets = current.locationPresets.filterNot { it.id == preset.id },
                statusMessage = "已删除常用位置「${preset.name}」",
            )
        }
    }

    suspend fun selectClientPreset(preset: ChaoxingClientPreset) {
        updatePersistableState { current ->
            val updated = current.copy(
                selectedClientPreset = preset,
                selectedCustomClientId = null,
            )
            syncPoolUserAgentsLocked(updated.currentUserAgent)
            updated
        }
    }

    suspend fun updateCustomUserAgent(ua: String) {
        updatePersistableState { current ->
            val updated = current.copy(
                customUserAgent = ua,
                selectedCustomClientId = null,
            )
            syncPoolUserAgentsLocked(updated.currentUserAgent)
            updated
        }
    }

    suspend fun addCustomClientProfile(name: String, userAgent: String) {
        val trimmedName = name.trim()
        val trimmedUa = userAgent.trim()
        if (trimmedName.isEmpty() || trimmedUa.isEmpty()) return
        val profile = ChaoxingCustomClientProfile(name = trimmedName, userAgent = trimmedUa)
        updatePersistableState { current ->
            val updated = current.copy(
                customClientProfiles = current.customClientProfiles + profile,
                selectedCustomClientId = profile.id,
                statusMessage = "已添加客户端配置「$trimmedName」",
            )
            syncPoolUserAgentsLocked(updated.currentUserAgent)
            updated
        }
    }

    suspend fun selectCustomClientProfile(profileId: String?) {
        updatePersistableState { current ->
            val updated = current.copy(selectedCustomClientId = profileId)
            syncPoolUserAgentsLocked(updated.currentUserAgent)
            updated
        }
    }

    suspend fun removeCustomClientProfile(profileId: String) {
        updatePersistableState { current ->
            val nextProfiles = current.customClientProfiles.filterNot { it.id == profileId }
            val nextSelected = if (current.selectedCustomClientId == profileId) null else current.selectedCustomClientId
            val updated = current.copy(
                customClientProfiles = nextProfiles,
                selectedCustomClientId = nextSelected,
            )
            syncPoolUserAgentsLocked(updated.currentUserAgent)
            updated
        }
    }

    suspend fun setEnableLocationJitter(enabled: Boolean) {
        updatePersistableState { it.copy(enableLocationJitter = enabled) }
    }

    suspend fun setBatchSignDelayMs(delayMs: Int) {
        updatePersistableState { it.copy(batchSignDelayMs = delayMs.coerceIn(0, MAX_BATCH_DELAY_MS)) }
    }

    suspend fun setEnableImageObfuscation(enabled: Boolean) {
        updatePersistableState { it.copy(enableImageObfuscation = enabled) }
    }

    suspend fun setAutoSolveCaptcha(enabled: Boolean) {
        updatePersistableState { it.copy(autoSolveCaptcha = enabled) }
    }

    suspend fun logoutAll() {
        val (proceed, cancelledPair) = synchronized(stateLock) {
            val current = _uiState.value
            if (current.isRestoring || current.persistenceBlocked) {
                _uiState.value = current.copy(errorMessage = BLOCKED_PERSISTENCE_MUTATION_MESSAGE)
                false to (emptyList<Job>() to emptyList())
            } else {
                pendingIncomingUri = null
                val cancelled = invalidateSessionOperationsLocked()
                for (client in clientPool.values) {
                    client.invalidate()
                }
                clientPool.clear()
                _uiState.value = _uiState.value.copy(
                    accounts = emptyList(),
                    activePhoneNumber = null,
                    courses = emptyList(),
                    courseActivities = emptyMap(),
                    groups = emptyList(),
                    groupActivities = emptyMap(),
                    imConfig = null,
                    activeSignActivity = null,
                    statusMessage = "已退出全部账号",
                )
                true to cancelled
            }
        }
        if (!proceed) return
        cancelCollectedOperations(cancelledPair.first, cancelledPair.second)
        onActiveAccountChanged?.invoke(null)
        saveToDisk()
    }

    suspend fun recoverLocalState() {
        val (jobsToCancel, deferredsToCancel) = synchronized(stateLock) {
            invalidateSessionOperationsLocked()
        }
        cancelCollectedOperations(jobsToCancel, deferredsToCancel)
        onActiveAccountChanged?.invoke(null)
        try {
            val archive = store.archiveAndReset()
            synchronized(stateLock) {
                _uiState.value = _uiState.value.copy(persistenceBlocked = false)
            }
            loadFromDisk()
            setStatusMessage("已保留原文件并重置：${archive.name}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(stateLock) {
                _uiState.value = _uiState.value.copy(
                    persistenceBlocked = true,
                    errorMessage = "无法保留原文件并重置：${e.message ?: "未知错误"}",
                )
            }
        }
    }

    fun selectTab(tab: WindowsWorkspaceTab) {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(selectedTab = tab)
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(themeMode = mode)
        }
    }

    fun setShowAddAccountDialog(show: Boolean) {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(showAddAccountDialog = show)
        }
    }

    fun openSignWorkspace(
        activity: ChaoxingSignActivity,
        prefilledQrInput: String? = null,
    ) {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(
                activeSignActivity = activity,
                pendingQrCodeInput = prefilledQrInput ?: _uiState.value.pendingQrCodeInput,
                selectedTab = WindowsWorkspaceTab.SIGN,
            )
        }
    }

    fun closeSignWorkspace() {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(activeSignActivity = null)
        }
    }

    fun clearPendingQrCodeInput() {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(pendingQrCodeInput = null)
        }
    }

    fun setStatusMessage(message: String?) {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(statusMessage = message)
        }
    }

    fun clearStatusMessage() = setStatusMessage(null)

    fun setErrorMessage(message: String?) {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(errorMessage = message)
        }
    }

    fun clearErrorMessage() = setErrorMessage(null)

    fun shutdown() {
        val (jobs, deferreds) = synchronized(stateLock) { invalidateSessionOperationsLocked() }
        cancelCollectedOperations(jobs, deferreds)
    }

    private suspend fun groupConfiguration(
        client: ChaoxingHttpClient,
        forceRefresh: Boolean = false,
    ): ChaoxingEasemobIMConfig {
        if (!forceRefresh) {
            _uiState.value.imConfig?.let { return it }
        }
        val fetched = dependencies.fetchIMConfig(client)
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(imConfig = fetched)
        }
        return fetched
    }

    private suspend fun <T> fetchWithImTokenRefresh(
        client: ChaoxingHttpClient,
        token: SessionToken,
        forceInitialRefresh: Boolean,
        isRequestActive: () -> Boolean,
        fetcher: suspend (ChaoxingEasemobIMConfig) -> T,
    ): T? {
        var config = groupConfiguration(client, forceRefresh = forceInitialRefresh)
        checkSession(token)
        if (!isRequestActive()) return null
        return try {
            fetcher(config)
        } catch (_: ChaoxingError.SessionExpired) {
            checkSession(token)
            if (!isRequestActive()) return null
            config = groupConfiguration(client, forceRefresh = true)
            checkSession(token)
            if (!isRequestActive()) return null
            fetcher(config)
        }
    }

    private suspend fun <T> runCancellableSessionWork(
        key: String? = null,
        block: suspend CoroutineScope.() -> T,
    ): T = coroutineScope {
        val workId = key ?: ChaoxingModelDefaults.newUuidString()
        val deferred = async(start = CoroutineStart.LAZY) { block() }
        val previous = synchronized(stateLock) {
            val superseded = if (key != null) inFlightWorkJobs[key] else null
            inFlightWorkJobs[workId] = deferred
            superseded
        }
        previous?.cancel(CancellationException("已被新的请求取代"))
        deferred.invokeOnCompletion {
            synchronized(stateLock) {
                if (inFlightWorkJobs[workId] === deferred) {
                    inFlightWorkJobs.remove(workId)
                }
            }
        }
        deferred.await()
    }

    private suspend fun consumePendingIncomingUri() {
        val pending = synchronized(stateLock) {
            val cur = _uiState.value
            if (cur.isRestoring || cur.activeAccount == null) {
                null
            } else {
                pendingIncomingUri.also { pendingIncomingUri = null }
            }
        } ?: return
        handleIncomingUri(pending)
    }

    private fun updateStateIfReadyLocked(block: (WindowsUiState) -> WindowsUiState?): Boolean =
        synchronized(stateLock) {
            val current = _uiState.value
            if (current.isRestoring || current.persistenceBlocked) {
                _uiState.value = current.copy(errorMessage = BLOCKED_PERSISTENCE_MUTATION_MESSAGE)
                false
            } else {
                val next = block(current)
                if (next != null) {
                    _uiState.value = next
                    true
                } else {
                    false
                }
            }
        }

    private suspend fun updatePersistableState(
        persist: Boolean = true,
        block: (WindowsUiState) -> WindowsUiState?,
    ): Boolean {
        if (!updateStateIfReadyLocked(block)) return false
        return if (persist) saveToDisk() else true
    }

    private fun <T> acquireSessionRequestLocked(
        setupLocked: (current: WindowsUiState, token: SessionToken) -> T?,
    ): Triple<ChaoxingHttpClient, SessionToken, T>? = synchronized(stateLock) {
        val current = _uiState.value
        val active = current.activeAccount ?: return null
        val token = sessionTokenLocked()
        if (!isCurrentLocked(token)) return null
        val payload = setupLocked(current, token) ?: return null
        Triple(clientForLocked(active), token, payload)
    }

    private fun requireReadyState() {
        val blocked = synchronized(stateLock) {
            val cur = _uiState.value
            cur.isRestoring || cur.persistenceBlocked
        }
        if (blocked) {
            throw ChaoxingError.InvalidInput("本地状态尚未成功读取，请先完成恢复")
        }
    }

    private fun beginLoading(): Long = synchronized(stateLock) {
        val id = nextRequestCounter.incrementAndGet()
        loadingRequests.add(id)
        _uiState.value = _uiState.value.copy(isLoading = true)
        id
    }

    private fun endLoading(id: Long) {
        synchronized(stateLock) {
            loadingRequests.remove(id)
            _uiState.value = _uiState.value.copy(isLoading = loadingRequests.isNotEmpty())
        }
    }

    private fun sessionTokenLocked(): SessionToken = SessionToken(
        generation = sessionGeneration,
        phoneNumber = _uiState.value.activeAccount?.phoneNumber,
    )

    private fun isCurrentLocked(token: SessionToken): Boolean =
        token.generation == sessionGeneration &&
            token.phoneNumber == _uiState.value.activeAccount?.phoneNumber

    private fun clientForLocked(session: ChaoxingAccountSession): ChaoxingHttpClient {
        val ua = _uiState.value.currentUserAgent
        val existing = clientPool[session.phoneNumber]
        if (existing != null) {
            existing.userAgent = ua
            return existing
        }
        return dependencies.createHttpClient(session = session, userAgent = ua).also { created ->
            clientPool[session.phoneNumber] = created
        }
    }

    private fun replacePoolClientLocked(
        session: ChaoxingAccountSession,
        userAgent: String,
    ): ChaoxingHttpClient {
        clientPool.remove(session.phoneNumber)?.invalidate()
        return dependencies.createHttpClient(session = session, userAgent = userAgent).also { created ->
            clientPool[session.phoneNumber] = created
        }
    }

    private fun mergePoolSessionsLocked(accounts: List<ChaoxingAccountSession>): List<ChaoxingAccountSession> =
        accounts.map { account ->
            val pooled = clientPool[account.phoneNumber]?.session ?: account
            pooled.copy(
                isPrimary = account.isPrimary,
                configuredFid = account.configuredFid,
                tags = normalizeAccountTags(account.tags),
            )
        }

    private fun applySnapshotLocked(snapshot: ChaoxingAppStateSnapshot, activePhone: String?) {
        for (client in clientPool.values) {
            client.invalidate()
        }
        clientPool.clear()

        val normalizedAccounts = snapshot.accounts.map { acc ->
            acc.copy(tags = normalizeAccountTags(acc.tags))
        }
        val preset = ChaoxingClientPreset.fromRawValue(snapshot.selectedClientPreset)
            ?: ChaoxingClientPreset.DEFAULT
        val nextState = _uiState.value.copy(
            accounts = normalizedAccounts,
            activePhoneNumber = activePhone,
            locationPresets = snapshot.locationPresets,
            selectedClientPreset = preset,
            customUserAgent = snapshot.customUserAgent,
            customClientProfiles = snapshot.customClientProfiles,
            selectedCustomClientId = snapshot.selectedCustomClientId,
            enableLocationJitter = snapshot.enableLocationJitter,
            batchSignDelayMs = snapshot.batchSignDelayMs.coerceIn(0, MAX_BATCH_DELAY_MS),
            enableImageObfuscation = snapshot.enableImageObfuscation,
            autoSolveCaptcha = snapshot.autoSolveCaptcha,
            signHistory = snapshot.signHistory.take(MAX_SIGN_HISTORY),
            courseLocationBindings = snapshot.courseLocationBindings,
            starredCourseIds = snapshot.starredCourseIds.toSet(),
        )
        _uiState.value = nextState

        val effectiveUa = nextState.currentUserAgent
        for (account in normalizedAccounts) {
            replacePoolClientLocked(account, effectiveUa)
        }
    }

    private fun syncPoolUserAgentsLocked(userAgent: String) {
        for (client in clientPool.values) {
            client.userAgent = userAgent
        }
    }

    private fun dequeueCaptchaLocked(id: String): CompletableDeferred<String>? {
        val removed = captchaDeferreds.remove(id) ?: return null
        captchaQueue.removeAll { it.id == id }
        if (presentedCaptchaId == id) {
            presentedCaptchaId = null
            _uiState.value = _uiState.value.copy(captchaChallenge = null)
        }
        presentNextCaptchaLocked()
        return removed
    }

    private fun presentNextCaptchaLocked() {
        if (presentedCaptchaId != null || _uiState.value.captchaChallenge != null || captchaQueue.isEmpty()) {
            return
        }
        val next = captchaQueue.removeFirst()
        presentedCaptchaId = next.id
        _uiState.value = _uiState.value.copy(captchaChallenge = next)
    }

    private fun invalidateSessionOperationsLocked(): Pair<List<Job>, List<CompletableDeferred<String>>> {
        sessionGeneration += 1L
        courseRequestId += 1L
        groupRequestId += 1L
        radarRequestId += 1L
        deepLinkRequestId += 1L
        courseActivityRequestIds.clear()
        groupActivityRequestIds.clear()

        val jobsToCancel = sessionJobs.values.toList() + inFlightWorkJobs.values.toList()
        sessionJobs.clear()
        inFlightWorkJobs.clear()

        val deferredsToCancel = captchaDeferreds.values.toList()
        captchaDeferreds.clear()
        captchaQueue.clear()
        presentedCaptchaId = null

        loadingRequests.clear()
        _uiState.value = _uiState.value.copy(
            courses = emptyList(),
            courseActivities = emptyMap(),
            loadingCourseIds = emptySet(),
            failedCourseActivityIds = emptySet(),
            groups = emptyList(),
            groupActivities = emptyMap(),
            loadingGroupIds = emptySet(),
            failedGroupActivityIds = emptySet(),
            imConfig = null,
            pendingRadarItems = emptyList(),
            isScanningRadar = false,
            pendingQrCodeInput = null,
            isLoading = false,
            captchaChallenge = null,
        )
        return jobsToCancel to deferredsToCancel
    }

    private fun cancelCollectedOperations(
        jobs: List<Job>,
        deferreds: List<CompletableDeferred<String>>,
    ) {
        val cause = CancellationException("账号会话已切换或重置")
        for (job in jobs) job.cancel(cause)
        for (deferred in deferreds) deferred.cancel(cause)
    }

    private fun parseQueryItemsStrict(rawQuery: String?): List<Pair<String, String?>>? {
        if (rawQuery.isNullOrEmpty()) return emptyList()
        val items = ArrayList<Pair<String, String?>>()
        for (segment in rawQuery.split('&')) {
            val eqIndex = segment.indexOf('=')
            if (eqIndex < 0) {
                val key = percentDecodeUtf8OrNull(segment) ?: return null
                items.add(key to null)
            } else {
                val key = percentDecodeUtf8OrNull(segment.substring(0, eqIndex)) ?: return null
                val value = percentDecodeUtf8OrNull(segment.substring(eqIndex + 1)) ?: return null
                items.add(key to value)
            }
        }
        return items
    }

    private fun percentDecodeUtf8OrNull(input: String): String? {
        if ('%' !in input) return input
        val out = StringBuilder(input.length)
        val byteBuffer = ByteArrayOutputStream()
        var index = 0
        while (index < input.length) {
            val ch = input[index]
            if (ch == '%') {
                if (index + 2 >= input.length) return null
                val hi = hexNibble(input[index + 1])
                val lo = hexNibble(input[index + 2])
                if (hi < 0 || lo < 0) return null
                byteBuffer.write((hi shl 4) or lo)
                index += 3
            } else {
                if (byteBuffer.size() > 0) {
                    val decoded = decodeStrictUtf8OrNull(byteBuffer.toByteArray()) ?: return null
                    out.append(decoded)
                    byteBuffer.reset()
                }
                out.append(ch)
                index += 1
            }
        }
        if (byteBuffer.size() > 0) {
            val decoded = decodeStrictUtf8OrNull(byteBuffer.toByteArray()) ?: return null
            out.append(decoded)
        }
        return out.toString()
    }

    private fun decodeStrictUtf8OrNull(bytes: ByteArray): String? = try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun hexNibble(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }

    companion object {
        private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
        private const val MAX_UNSTARRED_RADAR_COURSES = 12
        private const val BLOCKED_PERSISTENCE_MUTATION_MESSAGE = "本地状态尚未成功读取，已阻止修改并覆盖原文件"

        private const val WORK_KEY_COURSES = "courses"
        private const val WORK_KEY_GROUPS = "groups"
        private const val WORK_KEY_RADAR = "radar"
        private const val WORK_KEY_DEEP_LINK = "deep_link"
        private const val WORK_KEY_COURSE_ACTIVITIES_PREFIX = "course_activities:"
        private const val WORK_KEY_GROUP_ACTIVITIES_PREFIX = "group_activities:"
    }
}
