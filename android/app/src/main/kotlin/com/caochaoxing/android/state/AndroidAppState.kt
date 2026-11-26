// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.state

import androidx.compose.runtime.Stable
import com.caochaoxing.core.crypto.ChaoxingCrypto
import com.caochaoxing.core.crypto.ChaoxingQrCodeParser
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
import java.net.URI
import java.net.URISyntaxException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Central application state controller managing encrypted persistence, per-account HTTP client pools,
 * session invalidation tokens, course/group/radar fetching, deep links, captcha queueing, and batch signing.
 */
@Stable
class AndroidAppState(
    private val store: PersistenceStore,
    private val dependencies: AppStateDependencies = LiveAppStateDependencies(),
    private val appScope: CoroutineScope,
    initialRestoring: Boolean = false,
) {
    private val stateLock = Any()
    private val saveMutex = Mutex()
    private val nextRequestCounter = AtomicLong(0L)

    private val _uiState = MutableStateFlow(AppUiState(isRestoring = initialRestoring))
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

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
    private var visibleCaptchaId: String? = null

    val activeAccount: ChaoxingAccountSession?
        get() = _uiState.value.activeAccount

    val currentUserAgent: String
        get() = _uiState.value.currentUserAgent

    val activeClient: ChaoxingHttpClient?
        get() = synchronized(stateLock) {
            _uiState.value.activeAccount?.let { clientForLocked(it) }
        }

    val allAccountTags: List<String>
        get() = _uiState.value.allAccountTags

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

    fun existingClientFor(phoneNumber: String): ChaoxingHttpClient? =
        synchronized(stateLock) { clientPool[phoneNumber] }

    fun clientFor(session: ChaoxingAccountSession): ChaoxingHttpClient =
        synchronized(stateLock) { clientForLocked(session) }

    fun launchSessionTask(block: suspend CoroutineScope.() -> Unit): Job {
        val taskId = ChaoxingModelDefaults.newUuidString()
        val job = appScope.launch(start = CoroutineStart.LAZY) {
            try {
                ensureActive()
                block()
            } finally {
                synchronized(stateLock) {
                    sessionJobs.remove(taskId)
                }
            }
        }
        synchronized(stateLock) {
            sessionJobs[taskId] = job
        }
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
        try {
            val snapshot = store.load()
            val shouldContinue = synchronized(stateLock) {
                if (generation != sessionGeneration) {
                    false
                } else {
                    val activePhone = (snapshot.accounts.firstOrNull { it.isPrimary }
                        ?: snapshot.accounts.firstOrNull())?.phoneNumber
                    applySnapshotLocked(snapshot, activePhone = activePhone)
                    _uiState.value = _uiState.value.copy(
                        isRestoring = false,
                        persistenceBlocked = false,
                    )
                    true
                }
            }
            if (!shouldContinue) return
            consumePendingIncomingUri()
            if (activeClient != null) {
                refreshCourses()
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
                        errorMessage = "读取本地会话失败，原文件已保留；请先恢复或移走损坏文件再重试。${e.message.orEmpty()}",
                    )
                }
            }
        }
    }

    suspend fun saveToDisk(): Boolean = saveMutex.withLock {
        val snapshot = synchronized(stateLock) {
            val current = _uiState.value
            if (current.isRestoring || current.persistenceBlocked) {
                _uiState.value = current.copy(
                    errorMessage = "本地状态尚未成功读取，已阻止覆盖原文件",
                )
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
            setErrorMessage("保存本地状态失败：${e.message ?: "未知错误"}")
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
                )
                cancelled
            }
            cancelCollectedOperations(jobsToCancel, deferredsToCancel)
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
            var replacesExisting = false
            var cancelledPair: Pair<List<Job>, List<CompletableDeferred<String>>> = emptyList<Job>() to emptyList()
            synchronized(stateLock) {
                val current = _uiState.value
                if (current.isRestoring || current.persistenceBlocked) {
                    throw ChaoxingError.InvalidInput("本地状态尚未成功读取，请先完成恢复")
                }
                val existingIndex = current.accounts.indexOfFirst { it.phoneNumber == session.phoneNumber }
                replacesExisting = existingIndex >= 0
                if (replacesExisting) {
                    cancelledPair = invalidateSessionOperationsLocked()
                }
                val refreshed = _uiState.value
                val nextAccounts = refreshed.accounts.toMutableList()
                val savedAccount = if (existingIndex >= 0) {
                    val prev = nextAccounts[existingIndex]
                    session.copy(
                        isPrimary = prev.isPrimary,
                        tags = normalizeAccountTags(prev.tags + session.tags),
                    ).also { nextAccounts[existingIndex] = it }
                } else {
                    session.copy(
                        isPrimary = false,
                        tags = normalizeAccountTags(session.tags),
                    ).also { nextAccounts.add(it) }
                }
                replacePoolClientLocked(savedAccount, refreshed.currentUserAgent)
                _uiState.value = refreshed.copy(accounts = nextAccounts)
            }
            cancelCollectedOperations(cancelledPair.first, cancelledPair.second)
            saveToDisk()
            if (replacesExisting) {
                refreshCourses()
            }
        } finally {
            endLoading(loadingId)
        }
    }

    suspend fun removeAccount(session: ChaoxingAccountSession) {
        val (proceed, cancelledPair) = synchronized(stateLock) {
            val current = _uiState.value
            if (current.isRestoring || current.persistenceBlocked) {
                _uiState.value = current.copy(errorMessage = BLOCKED_PERSISTENCE_MUTATION_MESSAGE)
                false to (emptyList<Job>() to emptyList())
            } else {
                val cancelled = invalidateSessionOperationsLocked()
                val refreshed = _uiState.value
                val remaining = refreshed.accounts.filterNot { it.phoneNumber == session.phoneNumber }
                clientPool.remove(session.phoneNumber)?.invalidate()
                val wasActive = refreshed.activePhoneNumber == session.phoneNumber
                val nextActivePhone = if (wasActive) {
                    (remaining.firstOrNull { it.isPrimary } ?: remaining.firstOrNull())?.phoneNumber
                } else {
                    refreshed.activePhoneNumber
                }
                _uiState.value = refreshed.copy(
                    accounts = remaining,
                    activePhoneNumber = nextActivePhone,
                    courses = if (wasActive) emptyList() else refreshed.courses,
                    courseActivities = if (wasActive) emptyMap() else refreshed.courseActivities,
                    groups = if (wasActive) emptyList() else refreshed.groups,
                    groupActivities = if (wasActive) emptyMap() else refreshed.groupActivities,
                    imConfig = if (wasActive) null else refreshed.imConfig,
                )
                true to cancelled
            }
        }
        if (!proceed) return
        cancelCollectedOperations(cancelledPair.first, cancelledPair.second)
        saveToDisk()
        if (activeClient != null) {
            refreshCourses()
        }
    }

    suspend fun switchActiveAccount(session: ChaoxingAccountSession) {
        val (switched, cancelledPair) = synchronized(stateLock) {
            val current = _uiState.value
            if (current.accounts.none { it.phoneNumber == session.phoneNumber }) {
                false to (emptyList<Job>() to emptyList())
            } else {
                val cancelled = invalidateSessionOperationsLocked()
                _uiState.value = _uiState.value.copy(
                    activePhoneNumber = session.phoneNumber,
                    courses = emptyList(),
                    courseActivities = emptyMap(),
                    groups = emptyList(),
                    groupActivities = emptyMap(),
                    imConfig = null,
                )
                true to cancelled
            }
        }
        if (!switched) return
        cancelCollectedOperations(cancelledPair.first, cancelledPair.second)
        refreshCourses()
    }

    suspend fun updateFid(session: ChaoxingAccountSession, fid: Int) {
        val (updated, cancelledPair) = synchronized(stateLock) {
            val current = _uiState.value
            if (current.isRestoring || current.persistenceBlocked) {
                _uiState.value = current.copy(errorMessage = BLOCKED_PERSISTENCE_MUTATION_MESSAGE)
                false to (emptyList<Job>() to emptyList())
            } else if (current.accounts.none { it.phoneNumber == session.phoneNumber }) {
                false to (emptyList<Job>() to emptyList())
            } else {
                val cancelled = invalidateSessionOperationsLocked()
                val refreshed = _uiState.value
                val index = refreshed.accounts.indexOfFirst { it.phoneNumber == session.phoneNumber }
                if (index < 0) {
                    false to cancelled
                } else {
                    val httpClient = clientForLocked(refreshed.accounts[index])
                    httpClient.updateConfiguredFid(fid)
                    val updatedAccounts = refreshed.accounts.toMutableList()
                    updatedAccounts[index] = httpClient.session.copy(
                        isPrimary = refreshed.accounts[index].isPrimary,
                        tags = normalizeAccountTags(refreshed.accounts[index].tags),
                    )
                    _uiState.value = refreshed.copy(
                        accounts = updatedAccounts,
                        statusMessage = "已切换学校单位 (fid: $fid)",
                    )
                    true to cancelled
                }
            }
        }
        cancelCollectedOperations(cancelledPair.first, cancelledPair.second)
        if (!updated) return
        saveToDisk()
        refreshCourses()
    }

    suspend fun setTags(phoneNumber: String, tags: List<String>) {
        val normalizedPhone = phoneNumber.trim()
        if (normalizedPhone.isEmpty()) return
        val cleaned = normalizeAccountTags(tags)
        updatePersistableState { current ->
            val index = current.accounts.indexOfFirst { it.phoneNumber.trim() == normalizedPhone }
            if (index < 0) {
                null
            } else {
                val targetAccount = current.accounts[index]
                val nextAccounts = current.accounts.toMutableList()
                nextAccounts[index] = targetAccount.copy(tags = cleaned)
                clientPool[targetAccount.phoneNumber]?.updateTags(cleaned)
                current.copy(accounts = nextAccounts)
            }
        }
    }

    suspend fun addTag(phoneNumber: String, tag: String) {
        addTag(setOf(phoneNumber), tag)
    }

    suspend fun addTag(phoneNumbers: Set<String>, tag: String) {
        val trimmedTag = tag.trim()
        val normalizedPhones = phoneNumbers.mapTo(HashSet()) { it.trim() }.apply { remove("") }
        if (trimmedTag.isEmpty() || normalizedPhones.isEmpty()) return
        updatePersistableState { current ->
            var matches = 0
            val nextAccounts = current.accounts.map { account ->
                if (account.phoneNumber.trim() in normalizedPhones) {
                    matches += 1
                    val nextTags = normalizeAccountTags(account.tags + trimmedTag)
                    clientPool[account.phoneNumber]?.updateTags(nextTags)
                    account.copy(tags = nextTags)
                } else {
                    account
                }
            }
            if (matches == 0) {
                null
            } else {
                val message = if (matches == 1) {
                    "已加入分组「$trimmedTag」"
                } else {
                    "已将 $matches 个账号加入分组「$trimmedTag」"
                }
                current.copy(
                    accounts = nextAccounts,
                    statusMessage = message,
                )
            }
        }
    }

    suspend fun bindLocation(courseId: Long, preset: ChaoxingLocationPreset) {
        if (courseId == 0L) return
        updatePersistableState { current ->
            current.copy(
                courseLocationBindings = current.courseLocationBindings + (courseId.toString() to preset),
                statusMessage = "已绑定位置「${preset.name}」到此课程",
            )
        }
    }

    suspend fun unbindLocation(courseId: Long) {
        if (courseId == 0L) return
        updatePersistableState { current ->
            current.copy(
                courseLocationBindings = current.courseLocationBindings - courseId.toString(),
                statusMessage = "已取消本课程位置绑定",
            )
        }
    }

    suspend fun toggleStarCourse(courseId: Long) {
        updatePersistableState { current ->
            val nextStars = if (courseId in current.starredCourseIds) {
                current.starredCourseIds - courseId
            } else {
                current.starredCourseIds + courseId
            }
            current.copy(starredCourseIds = nextStars)
        }
    }

    suspend fun refreshCourses() {
        val requestId = nextRequestCounter.incrementAndGet()
        val (client, token, _) = acquireSessionRequestLocked { _, _ ->
            courseRequestId = requestId
            Unit
        } ?: return

        val loadingId = beginLoading()
        try {
            val fetched = runCancellableSessionWork(WORK_KEY_COURSES) {
                dependencies.fetchCourses(client)
            }
            val committed = synchronized(stateLock) {
                if (isCurrentLocked(token) && courseRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(courses = fetched)
                    true
                } else {
                    false
                }
            }
            if (committed) {
                saveToDisk()
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return
        } catch (e: Exception) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && courseRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = e.message ?: "获取课程列表失败",
                    )
                }
            }
        } finally {
            endLoading(loadingId)
        }
    }

    suspend fun loadSignWorkspaceInfo(
        activity: ChaoxingSignActivity,
    ): Pair<ChaoxingSignActivityStatus, ChaoxingSignDetailMetadata> {
        val token = sessionToken
        val client = checkNotNull(activeClient)
        val status = dependencies.prepareSign(client, activity)
        checkSession(token)
        val detail = dependencies.fetchSignDetail(client, activity.id, activity.classId, activity.courseId)
        checkSession(token)
        return status to detail
    }

    suspend fun loadCourseActivities(course: ChaoxingCourse): List<ChaoxingSignActivity> {
        val courseKey = course.id
        val requestId = nextRequestCounter.incrementAndGet()
        val (client, token, _) = acquireSessionRequestLocked { current, _ ->
            courseActivityRequestIds[courseKey] = requestId
            _uiState.value = current.copy(
                loadingCourseIds = current.loadingCourseIds + courseKey,
                failedCourseActivityIds = current.failedCourseActivityIds - courseKey,
            )
            Unit
        } ?: return emptyList()

        try {
            val fetched = runCancellableSessionWork("$WORK_KEY_COURSE_ACTIVITIES_PREFIX$courseKey") {
                dependencies.fetchCourseActivities(client, course)
            }
            val isStillValid = synchronized(stateLock) {
                if (isCurrentLocked(token) && courseActivityRequestIds[courseKey] == requestId) {
                    _uiState.value = _uiState.value.copy(
                        courseActivities = _uiState.value.courseActivities + (courseKey to fetched),
                    )
                    true
                } else {
                    false
                }
            }
            return if (isStillValid) fetched else emptyList()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return emptyList()
        } catch (e: Exception) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && courseActivityRequestIds[courseKey] == requestId) {
                    _uiState.value = _uiState.value.copy(
                        failedCourseActivityIds = _uiState.value.failedCourseActivityIds + courseKey,
                    )
                }
            }
            return emptyList()
        } finally {
            synchronized(stateLock) {
                if (courseActivityRequestIds[courseKey] == requestId) {
                    courseActivityRequestIds.remove(courseKey)
                    _uiState.value = _uiState.value.copy(
                        loadingCourseIds = _uiState.value.loadingCourseIds - courseKey,
                    )
                }
            }
        }
    }

    suspend fun scanPendingActivitiesRadar() {
        val requestId = nextRequestCounter.incrementAndGet()
        val (client, token, targets) = acquireSessionRequestLocked { current, _ ->
            radarRequestId = requestId
            val selected = if (current.starredCourseIds.isEmpty()) {
                current.courses.take(MAX_UNSTARRED_RADAR_COURSES)
            } else {
                current.courses.filter { it.courseId in current.starredCourseIds }
            }
            if (selected.isEmpty()) {
                _uiState.value = current.copy(
                    pendingRadarItems = emptyList(),
                    isScanningRadar = false,
                    statusMessage = "当前没有可扫描的课程",
                )
                null
            } else {
                _uiState.value = current.copy(isScanningRadar = true)
                selected
            }
        } ?: return

        try {
            val results = runCancellableSessionWork(WORK_KEY_RADAR) {
                targets.map { course ->
                    async {
                        try {
                            ensureActive()
                            Result.success(course to dependencies.fetchCourseActivities(client, course))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Result.failure(e)
                        }
                    }
                }.awaitAll()
            }

            val found = mutableListOf<PendingRadarItem>()
            var failures = 0
            var firstFailure: String? = null
            for (result in results) {
                if (result.isSuccess) {
                    val (course, activities) = result.getOrThrow()
                    activities.asSequence()
                        .filter { it.isAvailable && !it.isAlreadySignedByStatus }
                        .forEach { activity ->
                            found.add(PendingRadarItem(course = course, activity = activity))
                        }
                } else {
                    failures += 1
                    if (firstFailure == null) {
                        firstFailure = result.exceptionOrNull()?.message
                    }
                }
            }

            synchronized(stateLock) {
                if (!isCurrentLocked(token) || radarRequestId != requestId) return@synchronized
                if (failures == targets.size) {
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "扫描失败，未能刷新活动，已保留上次结果：${firstFailure ?: "网络不可用"}",
                    )
                    return@synchronized
                }
                val sortedItems = found.sortedByDescending { it.activity.startTimeMs ?: 0L }
                val nextError = if (failures > 0) {
                    "$failures/${targets.size} 门课程读取失败，当前仅显示成功读取的结果。${firstFailure.orEmpty()}"
                } else {
                    _uiState.value.errorMessage
                }
                val nextStatus = if (failures == 0 && sortedItems.isEmpty()) {
                    "已扫描 ${targets.size} 门课程，没有待签活动"
                } else {
                    _uiState.value.statusMessage
                }
                _uiState.value = _uiState.value.copy(
                    pendingRadarItems = sortedItems,
                    errorMessage = nextError,
                    statusMessage = nextStatus,
                )
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return
        } finally {
            synchronized(stateLock) {
                if (radarRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(isScanningRadar = false)
                }
            }
        }
    }

    suspend fun groupConfiguration(
        client: ChaoxingHttpClient,
        forceRefresh: Boolean = false,
    ): ChaoxingEasemobIMConfig {
        val token = sessionToken
        checkSession(token)
        val existing = synchronized(stateLock) {
            if (!isCurrentLocked(token) || client.session.phoneNumber != _uiState.value.activeAccount?.phoneNumber) {
                throw CancellationException("群聊会话所属账号已变更")
            }
            if (forceRefresh) {
                _uiState.value = _uiState.value.copy(imConfig = null)
                null
            } else {
                _uiState.value.imConfig
            }
        }
        if (existing != null) return existing

        val fetched = dependencies.fetchIMConfig(client)
        checkSession(token)
        synchronized(stateLock) {
            if (isCurrentLocked(token)) {
                _uiState.value = _uiState.value.copy(imConfig = fetched)
            }
        }
        return fetched
    }

    suspend fun refreshGroups() {
        val requestId = nextRequestCounter.incrementAndGet()
        val (client, token, _) = acquireSessionRequestLocked { _, _ ->
            groupRequestId = requestId
            Unit
        } ?: return

        val loadingId = beginLoading()
        try {
            val fetched = runCancellableSessionWork(WORK_KEY_GROUPS) {
                fetchWithImTokenRefresh(
                    client = client,
                    token = token,
                    isRequestActive = { synchronized(stateLock) { isCurrentLocked(token) && groupRequestId == requestId } },
                ) { config ->
                    dependencies.fetchGroups(client, config)
                }
            } ?: return

            synchronized(stateLock) {
                if (isCurrentLocked(token) && groupRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(groups = fetched)
                }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return
        } catch (e: Exception) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && groupRequestId == requestId) {
                    val nextConfig = if (e is ChaoxingError.SessionExpired) null else _uiState.value.imConfig
                    _uiState.value = _uiState.value.copy(
                        imConfig = nextConfig,
                        errorMessage = e.message ?: "获取群聊列表失败",
                    )
                }
            }
        } finally {
            endLoading(loadingId)
        }
    }

    suspend fun loadGroupActivities(group: ChaoxingIMGroup): List<ChaoxingGroupSignActivity> {
        val groupKey = group.id
        val requestId = nextRequestCounter.incrementAndGet()
        val (client, token, _) = acquireSessionRequestLocked { current, _ ->
            groupActivityRequestIds[groupKey] = requestId
            _uiState.value = current.copy(
                loadingGroupIds = current.loadingGroupIds + groupKey,
                failedGroupActivityIds = current.failedGroupActivityIds - groupKey,
            )
            Unit
        } ?: return emptyList()

        try {
            val fetched = runCancellableSessionWork("$WORK_KEY_GROUP_ACTIVITIES_PREFIX$groupKey") {
                fetchWithImTokenRefresh(
                    client = client,
                    token = token,
                    isRequestActive = {
                        synchronized(stateLock) {
                            isCurrentLocked(token) && groupActivityRequestIds[groupKey] == requestId
                        }
                    },
                ) { config ->
                    dependencies.fetchGroupActivities(client, group, config)
                }
            } ?: return emptyList()

            val committed = synchronized(stateLock) {
                if (isCurrentLocked(token) && groupActivityRequestIds[groupKey] == requestId) {
                    _uiState.value = _uiState.value.copy(
                        groupActivities = _uiState.value.groupActivities + (groupKey to fetched),
                    )
                    true
                } else {
                    false
                }
            }
            return if (committed) fetched else emptyList()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return emptyList()
        } catch (e: Exception) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && groupActivityRequestIds[groupKey] == requestId) {
                    val nextConfig = if (e is ChaoxingError.SessionExpired) null else _uiState.value.imConfig
                    _uiState.value = _uiState.value.copy(
                        imConfig = nextConfig,
                        failedGroupActivityIds = _uiState.value.failedGroupActivityIds + groupKey,
                    )
                }
            }
            return emptyList()
        } finally {
            synchronized(stateLock) {
                if (groupActivityRequestIds[groupKey] == requestId) {
                    groupActivityRequestIds.remove(groupKey)
                    _uiState.value = _uiState.value.copy(
                        loadingGroupIds = _uiState.value.loadingGroupIds - groupKey,
                    )
                }
            }
        }
    }

    suspend fun requestCaptchaValidation(
        client: ChaoxingHttpClient,
        refererUrl: String,
    ): String {
        val token = sessionToken
        checkSession(token)

        val request = CaptchaChallengeRequest(
            id = ChaoxingModelDefaults.newUuidString(),
            client = client,
            refererUrl = refererUrl,
        )
        val deferred = CompletableDeferred<String>()

        val registered = synchronized(stateLock) {
            if (!isCurrentLocked(token)) {
                false
            } else {
                captchaDeferreds[request.id] = deferred
                captchaQueue.addLast(request)
                presentNextCaptchaLocked()
                true
            }
        }
        if (!registered) {
            throw CancellationException("账号会话已切换，验证码已取消")
        }

        return try {
            deferred.await()
        } catch (e: CancellationException) {
            cancelCaptcha(request.id, e)
            throw e
        }
    }

    fun completeCaptcha(id: String, validate: String) {
        val deferred = synchronized(stateLock) { dequeueCaptchaLocked(id) } ?: return
        deferred.complete(validate)
    }

    fun failCaptcha(
        id: String,
        error: Throwable = CancellationException("已取消验证码校验"),
    ) {
        val deferred = synchronized(stateLock) { dequeueCaptchaLocked(id) } ?: return
        if (error is CancellationException) {
            deferred.cancel(error)
        } else {
            deferred.completeExceptionally(error)
        }
    }

    fun cancelCaptcha(
        id: String,
        cause: CancellationException = CancellationException("已取消验证码校验"),
    ) {
        failCaptcha(id, cause)
    }

    fun captchaSheetDidAppear(id: String) {
        synchronized(stateLock) {
            if (presentedCaptchaId == id && captchaDeferreds.containsKey(id)) {
                visibleCaptchaId = id
            }
        }
    }

    fun captchaSheetDidDismiss(id: String? = null) {
        val targetId = synchronized(stateLock) {
            val currentPresented = presentedCaptchaId
            val currentVisible = visibleCaptchaId
            val resolved = when {
                id != null && (currentPresented == id || currentVisible == id || _uiState.value.captchaChallenge?.id == id) -> id
                id == null -> currentVisible
                else -> null
            }
            if (resolved == null) {
                presentNextCaptchaLocked()
            }
            resolved
        } ?: return
        failCaptcha(targetId, CancellationException("用户关闭了验证码弹窗"))
    }

    suspend fun handleIncomingIntent(uriString: String?) {
        val trimmed = uriString?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val canHandleNow = synchronized(stateLock) {
            val current = _uiState.value
            if (current.isRestoring || current.activeAccount == null) {
                pendingIncomingUri = trimmed
                false
            } else {
                true
            }
        }
        if (!canHandleNow) return

        val token = sessionToken
        val requestId = nextRequestCounter.incrementAndGet()
        synchronized(stateLock) {
            deepLinkRequestId = requestId
        }

        val directQrParsed = runCatching { ChaoxingQrCodeParser.parse(trimmed) }.getOrNull()
        if (directQrParsed != null) {
            synchronized(stateLock) {
                if (isCurrentLocked(token) && deepLinkRequestId == requestId) {
                    _uiState.value = _uiState.value.copy(
                        pendingQrCodeInput = trimmed,
                        selectedTab = TAB_QR,
                        statusMessage = "已从外部链接导入签到二维码",
                    )
                }
            }
            return
        }

        val uri = try {
            URI(trimmed)
        } catch (_: URISyntaxException) {
            if (trimmed.startsWith("caochaoxing://", ignoreCase = true)) {
                setErrorMessage("外部链接格式无效，无法解析")
            }
            return
        }
        if (!uri.scheme.equals("caochaoxing", ignoreCase = true)) return

        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val queryItems = parseQueryItemsStrict(uri.rawQuery)
        if (queryItems == null) {
            setErrorMessage("外部链接参数包含无效的百分比编码")
            return
        }
        fun query(name: String): String? =
            queryItems.firstOrNull { it.first == name }?.second

        when (host) {
            "qr" -> {
                val qrRaw = (query("data") ?: query("url"))?.trim()?.takeIf { it.isNotEmpty() }
                if (qrRaw == null) {
                    setErrorMessage("二维码链接缺少 data 或 url 参数")
                    return
                }
                synchronized(stateLock) {
                    if (isCurrentLocked(token) && deepLinkRequestId == requestId) {
                        _uiState.value = _uiState.value.copy(
                            pendingQrCodeInput = qrRaw,
                            selectedTab = TAB_QR,
                            statusMessage = "已导入签到二维码",
                        )
                    }
                }
            }

            "sign" -> {
                val activeId = (query("activeId") ?: query("id"))?.toLongOrNull()
                val classId = (query("classId") ?: "0").toIntOrNull()
                val courseId = (query("courseId") ?: "0").toLongOrNull()
                if (activeId == null || activeId <= 0L || classId == null || classId < 0 || courseId == null || courseId < 0L) {
                    setErrorMessage("链接中的活动 ID 必须为正数，课程和班级 ID 必须为非负整数")
                    return
                }
                val client = activeClient
                val metadata = if (client != null) {
                    try {
                        runCancellableSessionWork(WORK_KEY_DEEP_LINK) {
                            dependencies.fetchSignDetail(client, activeId, classId, courseId)
                        }
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        return
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
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
                        deepLinkActivity = activity,
                        selectedTab = TAB_COURSES,
                    )
                }
            }
        }
    }

    fun exportBackupJson(includeAccounts: Boolean): String {
        val snapshot = synchronized(stateLock) {
            val current = _uiState.value
            val updatedAccounts = mergePoolSessionsLocked(current.accounts)
            current.copy(accounts = updatedAccounts).toSnapshot()
        }
        return snapshot.encodedBackupJson(includeAccounts = includeAccounts)
    }

    suspend fun importBackupJson(rawJson: String) {
        requireReadyState()
        val payloadBytes = rawJson.toByteArray(Charsets.UTF_8)
        if (payloadBytes.size > MAX_BACKUP_BYTES) {
            throw ChaoxingError.InvalidInput("备份文件超过 8 MB，请检查文件内容")
        }
        val imported = try {
            ChaoxingAppStateSnapshot.decodedBackupJson(rawJson)
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

        saveMutex.withLock {
            val (candidate, nextActivePhone) = synchronized(stateLock) {
                val current = _uiState.value
                val baseAccounts = mergePoolSessionsLocked(current.accounts)
                val base = current.copy(accounts = baseAccounts).toSnapshot()
                mergeAppStateSnapshots(
                    base = base,
                    imported = imported,
                    currentActivePhone = current.activePhoneNumber,
                )
            }

            val cancelledPair = synchronized(stateLock) {
                val cancelled = invalidateSessionOperationsLocked()
                _uiState.value = _uiState.value.copy(isRestoring = true)
                cancelled
            }
            cancelCollectedOperations(cancelledPair.first, cancelledPair.second)

            try {
                store.save(candidate)
            } catch (e: CancellationException) {
                synchronized(stateLock) {
                    _uiState.value = _uiState.value.copy(isRestoring = false)
                }
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
                applySnapshotLocked(candidate, activePhone = nextActivePhone)
                _uiState.value = _uiState.value.copy(
                    isRestoring = false,
                    persistenceBlocked = false,
                    statusMessage = "已合并备份；同账号会话与偏好已更新",
                )
            }
        }

        if (activeClient != null) {
            refreshCourses()
        }
    }

    internal suspend fun recordSignHistory(
        activity: ChaoxingSignActivity,
        token: SessionToken,
        results: List<BatchSignResult>,
        stopped: Boolean = false,
    ) {
        val counts = results.signCounts()
        val stopNotice = "已停止，仅统计已返回的结果"
        val status = if (stopped) "$stopNotice。${counts.description}" else counts.description
        val entry = if (results.isEmpty()) null else ChaoxingSignHistoryEntry(
            activeId = activity.id,
            activityName = activity.nameOne.ifEmpty { activity.signType.displayName },
            courseName = activity.courseName.ifEmpty { "活动 #${activity.id}" },
            signTypeRaw = activity.signType.rawValue,
            successCount = counts.accepted,
            alreadySignedCount = counts.alreadySigned,
            totalCount = counts.total,
            summary = if (stopped) stopNotice else results.singleOrNull()
                ?.takeIf { it.disposition == BatchSignDisposition.FAILED_OR_UNCONFIRMED }?.message.orEmpty(),
        )
        updatePersistableState(persist = entry != null) { current ->
            if (!isCurrentLocked(token)) return@updatePersistableState null
            val history = if (entry == null) current.signHistory else (listOf(entry) + current.signHistory).take(MAX_SIGN_HISTORY)
            current.copy(signHistory = history, statusMessage = status)
        }
    }

    suspend fun clearSignHistory() {
        updatePersistableState { current ->
            current.copy(
                signHistory = emptyList(),
                statusMessage = "已清空签到记录",
            )
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
            name = name,
            address = address,
            latitude = latitude,
            longitude = longitude,
        )
        val added = updateStateIfReadyLocked { current ->
            current.copy(locationPresets = current.locationPresets + preset)
        }
        if (!added) return false

        if (!saveToDisk()) {
            synchronized(stateLock) {
                val current = _uiState.value
                _uiState.value = current.copy(
                    locationPresets = current.locationPresets.filterNot { it.id == preset.id },
                )
            }
            return false
        }
        setStatusMessage("已保存常用位置「$name」")
        return true
    }

    suspend fun removeLocationPreset(preset: ChaoxingLocationPreset) {
        updatePersistableState { current ->
            current.copy(
                locationPresets = current.locationPresets.filterNot { it.id == preset.id },
            )
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
                statusMessage = "已保存并切换至「$trimmedName」",
            )
            syncPoolUserAgentsLocked(updated.currentUserAgent)
            updated
        }
    }

    suspend fun removeCustomClientProfile(profile: ChaoxingCustomClientProfile) {
        updatePersistableState { current ->
            val nextSelected = if (current.selectedCustomClientId == profile.id) null else current.selectedCustomClientId
            val updated = current.copy(
                customClientProfiles = current.customClientProfiles.filterNot { it.id == profile.id },
                selectedCustomClientId = nextSelected,
            )
            syncPoolUserAgentsLocked(updated.currentUserAgent)
            updated
        }
    }

    suspend fun setLocationJitterEnabled(enabled: Boolean) {
        updatePersistableState { current -> current.copy(enableLocationJitter = enabled) }
    }

    suspend fun setBatchSignDelayMs(delayMs: Int) {
        val clamped = delayMs.coerceIn(0, MAX_BATCH_DELAY_MS)
        updatePersistableState { current -> current.copy(batchSignDelayMs = clamped) }
    }

    suspend fun setImageObfuscationEnabled(enabled: Boolean) {
        updatePersistableState { current -> current.copy(enableImageObfuscation = enabled) }
    }

    suspend fun setAutoSolveCaptcha(enabled: Boolean) {
        updatePersistableState { current -> current.copy(autoSolveCaptcha = enabled) }
    }

    suspend fun selectClientPreset(preset: ChaoxingClientPreset, persist: Boolean = true) {
        updatePersistableState(persist = persist) { current ->
            val updated = current.copy(
                selectedClientPreset = preset,
                selectedCustomClientId = null,
                customUserAgent = "",
            )
            syncPoolUserAgentsLocked(updated.currentUserAgent)
            updated
        }
    }

    suspend fun selectCustomClientProfile(profileId: String?, persist: Boolean = true) {
        updatePersistableState(persist = persist) { current ->
            val updated = current.copy(selectedCustomClientId = profileId)
            syncPoolUserAgentsLocked(updated.currentUserAgent)
            updated
        }
    }

    suspend fun setCustomUserAgent(userAgent: String, persist: Boolean = true) {
        updatePersistableState(persist = persist) { current ->
            val updated = current.copy(customUserAgent = userAgent)
            syncPoolUserAgentsLocked(updated.currentUserAgent)
            updated
        }
    }

    suspend fun regenerateDeviceCode(phoneNumber: String) {
        val normalizedPhone = phoneNumber.trim()
        if (normalizedPhone.isEmpty()) return
        val (proceed, cancelledPair) = synchronized(stateLock) {
            val current = _uiState.value
            if (current.isRestoring || current.persistenceBlocked) {
                _uiState.value = current.copy(errorMessage = BLOCKED_PERSISTENCE_MUTATION_MESSAGE)
                false to (emptyList<Job>() to emptyList())
            } else if (current.accounts.none { it.phoneNumber.trim() == normalizedPhone }) {
                false to (emptyList<Job>() to emptyList())
            } else {
                val cancelled = invalidateSessionOperationsLocked()
                val refreshed = _uiState.value
                val index = refreshed.accounts.indexOfFirst { it.phoneNumber.trim() == normalizedPhone }
                if (index < 0) {
                    false to cancelled
                } else {
                    val target = refreshed.accounts[index]
                    val base = clientPool[target.phoneNumber]?.session ?: target
                    val updated = base.copy(
                        isPrimary = target.isPrimary,
                        tags = normalizeAccountTags(target.tags),
                        deviceCode = ChaoxingCrypto.randomizedDeviceCode(),
                    )
                    val nextAccounts = refreshed.accounts.toMutableList()
                    nextAccounts[index] = updated
                    replacePoolClientLocked(updated, refreshed.currentUserAgent)
                    _uiState.value = refreshed.copy(
                        accounts = nextAccounts,
                        statusMessage = "已为 ${updated.name.ifBlank { updated.phoneNumber }} 生成新设备码",
                    )
                    true to cancelled
                }
            }
        }
        cancelCollectedOperations(cancelledPair.first, cancelledPair.second)
        if (!proceed) return
        saveToDisk()
        refreshCourses()
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
                )
                true to cancelled
            }
        }
        if (!proceed) return
        cancelCollectedOperations(cancelledPair.first, cancelledPair.second)
        saveToDisk()
    }

    suspend fun recoverLocalState() {
        val (jobsToCancel, deferredsToCancel) = synchronized(stateLock) {
            invalidateSessionOperationsLocked()
        }
        cancelCollectedOperations(jobsToCancel, deferredsToCancel)
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

    /**
     * Captures target sessions once, runs batch sign-in with anti-risk staggered delays,
     * queues any `CaptchaRequired(enc2)` challenges sequentially, and aborts on session invalidation.
     */
    suspend fun executeSignBatch(
        activity: ChaoxingSignActivity,
        selectedPhones: Set<String>,
        onProgress: (List<BatchSignResult>) -> Unit = {},
        ignoreBatchDelay: Boolean = false,
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
        val delayMs = if (ignoreBatchDelay) 0 else _uiState.value.batchSignDelayMs.coerceIn(0, MAX_BATCH_DELAY_MS)
        val collected = arrayOfNulls<BatchSignResult>(pairs.size)
        val resultMutex = Mutex()

        suspend fun publishResult(index: Int, result: Result<ChaoxingSignOutcome>) {
            resultMutex.withLock {
                checkSession(token)
                collected[index] = BatchSignResult.from(pairs[index].first, result)
                onProgress(collected.filterNotNull())
            }
        }

        suspend fun firstAttempt(index: Int): ChaoxingError.CaptchaRequired? {
            val (account, client) = pairs[index]
            val result = runSignAttempt(account, client, activity, token, operation)
            checkSession(token)
            val error = result.exceptionOrNull()
            if (error is ChaoxingError.CaptchaRequired) return error
            publishResult(index, result)
            return null
        }

        runCancellableSessionWork {
            // Publish completed accounts even when another concurrent result is still pending.
            val challenges = if (delayMs == 0 && pairs.size > 1) {
                pairs.indices.map { index -> async { firstAttempt(index) } }.awaitAll()
            } else {
                pairs.indices.map { index ->
                    checkSession(token)
                    if (index > 0 && delayMs > 0) {
                        val jitteredDelayMs = (delayMs.toDouble() * Random.nextDouble(0.75, 1.35))
                            .toLong()
                            .coerceAtLeast(1L)
                        dependencies.sleep(jitteredDelayMs)
                        checkSession(token)
                    }
                    firstAttempt(index)
                }
            }

            for ((index, challenge) in challenges.withIndex()) {
                if (challenge == null) continue
                checkSession(token)
                val (account, client) = pairs[index]
                val result = try {
                    val referer = ChaoxingSignExecutor.preSignUrl(client, activity)
                    val validate = requestCaptchaValidation(client, referer)
                    checkSession(token)
                    Result.success(
                        executeWithDeviceRetry(account, client, token) { refreshedAccount ->
                            operation(client, refreshedAccount, validate, challenge.enc2)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
                publishResult(index, result)
            }
        }

        checkSession(token)
        return collected.filterNotNull()
    }

    fun setSelectedTab(tab: Int) {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(selectedTab = tab)
        }
    }

    fun clearDeepLinkActivity() {
        synchronized(stateLock) {
            _uiState.value = _uiState.value.copy(deepLinkActivity = null)
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

    private fun updateStateIfReadyLocked(
        block: (AppUiState) -> AppUiState?,
    ): Boolean = synchronized(stateLock) {
        val current = _uiState.value
        if (current.isRestoring || current.persistenceBlocked) {
            _uiState.value = current.copy(
                errorMessage = BLOCKED_PERSISTENCE_MUTATION_MESSAGE,
            )
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
        block: (AppUiState) -> AppUiState?,
    ): Boolean {
        if (!updateStateIfReadyLocked(block)) return false
        return if (persist) saveToDisk() else true
    }

    private fun <T> acquireSessionRequestLocked(
        setupLocked: (current: AppUiState, token: SessionToken) -> T?,
    ): Triple<ChaoxingHttpClient, SessionToken, T>? = synchronized(stateLock) {
        val current = _uiState.value
        val active = current.activeAccount ?: return null
        val token = sessionTokenLocked()
        if (!isCurrentLocked(token)) return null
        val payload = setupLocked(current, token) ?: return null
        Triple(clientForLocked(active), token, payload)
    }

    private suspend fun <T> fetchWithImTokenRefresh(
        client: ChaoxingHttpClient,
        token: SessionToken,
        isRequestActive: () -> Boolean,
        fetcher: suspend (ChaoxingEasemobIMConfig) -> T,
    ): T? {
        var config = groupConfiguration(client)
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

    private suspend fun runSignAttempt(
        account: ChaoxingAccountSession,
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
        token: SessionToken,
        operation: suspend (ChaoxingHttpClient, ChaoxingAccountSession, String?, String?) -> ChaoxingSignOutcome,
    ): Result<ChaoxingSignOutcome> = try {
        checkSession(token)
        when (dependencies.prepareSign(client, activity)) {
            ChaoxingSignActivityStatus.ALREADY_SIGNED ->
                throw ChaoxingError.AlreadySigned()
            ChaoxingSignActivityStatus.EXPIRED ->
                throw ChaoxingError.SignExpired("签到已截止")
            ChaoxingSignActivityStatus.READY_TO_SIGN -> {
                val outcome = executeWithDeviceRetry(account, client, token) { refreshedAccount ->
                    operation(client, refreshedAccount, null, null)
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

    private suspend fun executeWithDeviceRetry(
        account: ChaoxingAccountSession,
        client: ChaoxingHttpClient,
        token: SessionToken,
        submit: suspend (ChaoxingAccountSession) -> ChaoxingSignOutcome,
    ): ChaoxingSignOutcome {
        return try {
            submit(account)
        } catch (e: ChaoxingError.DeviceDuplicated) {
            checkSession(token)
            val rotated = rotateDeviceCodeInPlace(account.phoneNumber, client) ?: throw e
            checkSession(token)
            submit(rotated)
        }
    }

    private suspend fun rotateDeviceCodeInPlace(
        phoneNumber: String,
        client: ChaoxingHttpClient,
    ): ChaoxingAccountSession? {
        val normalizedPhone = phoneNumber.trim()
        val updated: ChaoxingAccountSession = synchronized(stateLock) {
            val current = _uiState.value
            val index = current.accounts.indexOfFirst { it.phoneNumber.trim() == normalizedPhone }
            if (index < 0) return null
            val target = current.accounts[index]
            val base = client.session
            val next = base.copy(
                isPrimary = target.isPrimary,
                tags = normalizeAccountTags(target.tags),
                deviceCode = ChaoxingCrypto.randomizedDeviceCode(),
            )
            client.updateSession(next)
            val nextAccounts = current.accounts.toMutableList()
            nextAccounts[index] = next
            _uiState.value = current.copy(accounts = nextAccounts)
            next
        }
        saveToDisk()
        return updated
    }

    private suspend fun <T> runCancellableSessionWork(
        key: String? = null,
        block: suspend CoroutineScope.() -> T,
    ): T = coroutineScope {
        val workId = key ?: ChaoxingModelDefaults.newUuidString()
        val deferred = async(start = CoroutineStart.LAZY) {
            block()
        }
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
            val current = _uiState.value
            if (current.isRestoring || current.activeAccount == null) {
                null
            } else {
                pendingIncomingUri.also { pendingIncomingUri = null }
            }
        } ?: return
        handleIncomingIntent(pending)
    }

    private fun requireReadyState() {
        val blocked = synchronized(stateLock) {
            val current = _uiState.value
            current.isRestoring || current.persistenceBlocked
        }
        if (blocked) {
            throw ChaoxingError.InvalidInput("本地状态尚未成功读取，请先完成恢复")
        }
    }

    private fun beginLoading(): Long =
        synchronized(stateLock) {
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

    private fun sessionTokenLocked(): SessionToken =
        SessionToken(
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
                tags = normalizeAccountTags(account.tags),
            )
        }

    private fun applySnapshotLocked(snapshot: ChaoxingAppStateSnapshot, activePhone: String?) {
        for (client in clientPool.values) {
            client.invalidate()
        }
        clientPool.clear()

        val normalizedAccounts = snapshot.accounts.map { account ->
            account.copy(tags = normalizeAccountTags(account.tags))
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
        if (visibleCaptchaId == id) {
            visibleCaptchaId = null
        }
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
        visibleCaptchaId = null

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
            deepLinkActivity = null,
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
        for (job in jobs) {
            job.cancel(cause)
        }
        for (deferred in deferreds) {
            deferred.cancel(cause)
        }
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
        const val TAB_COURSES: Int = 0
        const val TAB_GROUPS: Int = 1
        const val TAB_QR: Int = 2
        const val TAB_SETTINGS: Int = 3

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
