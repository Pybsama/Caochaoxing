// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens.signers

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.media.AndroidImageObfuscator
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.BatchSignDisposition
import com.caochaoxing.android.state.BatchSignResult
import com.caochaoxing.android.ui.components.AccountGroupSelector
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxFlowRow
import com.caochaoxing.android.ui.components.CxHairlineDivider
import com.caochaoxing.android.ui.components.CxIconTile
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxSecondaryButton
import com.caochaoxing.android.ui.components.CxSectionHeader
import com.caochaoxing.android.ui.components.CxSurfaceCard
import com.caochaoxing.android.ui.components.SignResultRow
import com.caochaoxing.android.ui.components.SignResultsHeader
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.screens.rememberAppPersistenceScope
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingLocationSignInfo
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignActivityStatus
import com.caochaoxing.core.model.ChaoxingSignDetailMetadata
import com.caochaoxing.core.model.ChaoxingSignType
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.service.ChaoxingActivityService
import com.caochaoxing.core.service.ChaoxingCloudDriveService
import com.caochaoxing.core.service.ChaoxingFaceService
import com.caochaoxing.core.signer.ChaoxingSignExecutor
import com.caochaoxing.core.signer.ChaoxingSignOutcome
import com.caochaoxing.core.store.ChaoxingSnapshotJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private val SignActivityListSerializer = ListSerializer(ChaoxingSignActivity.serializer())

private val SignActivityStackSaver: Saver<List<ChaoxingSignActivity>, String> = Saver(
    save = { stack ->
        ChaoxingSnapshotJson.encodeToString(SignActivityListSerializer, stack)
    },
    restore = { raw ->
        runCatching {
            ChaoxingSnapshotJson.decodeFromString(SignActivityListSerializer, raw)
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    },
)

private val StringSetSaver: Saver<Set<String>, List<String>> = Saver(
    save = { ArrayList(it) },
    restore = { it.toSet() },
)

/**
 * Unified multi-account sign-in workspace supporting all 5 Chaoxing sign types, staggered
 * execution, captcha retry queueing, automatic face profile attachment, device code
 * regeneration, and in-place sign-out drill-down via an activity stack.
 */
@Composable
fun SignWorkspaceScreen(
    appState: AndroidAppState,
    activity: ChaoxingSignActivity,
    onNavigateToLocations: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activityStack by rememberSaveable(activity.id, stateSaver = SignActivityStackSaver) {
        mutableStateOf(listOf(activity))
    }
    val currentActivity = activityStack.lastOrNull() ?: activity

    BackHandler(enabled = activityStack.size > 1) {
        activityStack = activityStack.dropLast(1)
    }

    key(currentActivity.id) {
        SignWorkspaceActivityPane(
            appState = appState,
            activity = currentActivity,
            isSignOutDrillDown = activityStack.size > 1,
            onNavigateToLocations = onNavigateToLocations,
            onOpenSignOutActivity = { nextActivity ->
                if (activityStack.none { it.id == nextActivity.id }) {
                    activityStack = activityStack + nextActivity
                }
            },
            onBack = {
                if (activityStack.size > 1) {
                    activityStack = activityStack.dropLast(1)
                } else {
                    onBack()
                }
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun SignWorkspaceActivityPane(
    appState: AndroidAppState,
    activity: ChaoxingSignActivity,
    isSignOutDrillDown: Boolean,
    onNavigateToLocations: () -> Unit,
    onOpenSignOutActivity: (ChaoxingSignActivity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val appScope = rememberAppPersistenceScope()

    var selectedPhones by rememberSaveable(activity.id, stateSaver = StringSetSaver) {
        mutableStateOf<Set<String>>(emptySet())
    }
    var selectionInitialized by rememberSaveable(activity.id) { mutableStateOf(false) }

    var preSignStatus by remember(activity.id) { mutableStateOf<ChaoxingSignActivityStatus?>(null) }
    var detailMetadata by remember(activity.id) { mutableStateOf<ChaoxingSignDetailMetadata?>(null) }
    var isLoadingInfo by remember(activity.id) { mutableStateOf(true) }
    var isRefreshing by remember(activity.id) { mutableStateOf(false) }
    var hasLoadedInfo by remember(activity.id) { mutableStateOf(false) }
    var isOpeningSignOut by remember(activity.id) { mutableStateOf(false) }

    var isSigning by remember(activity.id) { mutableStateOf(false) }
    var batchResults by remember(activity.id) { mutableStateOf<List<BatchSignResult>>(emptyList()) }
    var showOptionalPhoto by remember(activity.id) { mutableStateOf(false) }

    var loadJob by remember(activity.id) { mutableStateOf<Job?>(null) }
    var loadRequestId by remember(activity.id) { mutableLongStateOf(0L) }
    var signOutJob by remember(activity.id) { mutableStateOf<Job?>(null) }
    var batchJob by remember(activity.id) { mutableStateOf<Job?>(null) }

    DisposableEffect(activity.id) {
        onDispose {
            loadJob?.cancel()
            loadJob = null
            signOutJob?.cancel()
            signOutJob = null
            batchJob?.cancel()
            batchJob = null
        }
    }

    LaunchedEffect(uiState.accounts, uiState.activePhoneNumber) {
        val validPhones = uiState.accounts.map { it.phoneNumber }.toSet()
        if (!selectionInitialized) {
            selectionInitialized = true
            val activePhone = uiState.activeAccount?.phoneNumber
            selectedPhones = if (activePhone != null && activePhone in validPhones) {
                setOf(activePhone)
            } else {
                validPhones.take(1).toSet()
            }
        } else {
            val filtered = selectedPhones.intersect(validPhones)
            val next = if (validPhones.size == 1 && filtered.isEmpty()) {
                validPhones
            } else {
                filtered
            }
            if (next != selectedPhones) {
                selectedPhones = next
            }
        }
    }

    suspend fun loadActivityInfoSuspend() {
        val requestId = ++loadRequestId
        val token = appState.sessionToken
        isLoadingInfo = true
        hasLoadedInfo = false
        try {
            val (status, detail) = appState.loadSignWorkspaceInfo(activity)
            if (loadRequestId == requestId) {
                preSignStatus = status
                detailMetadata = detail
                hasLoadedInfo = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (appState.isCurrent(token) && loadRequestId == requestId) {
                preSignStatus = null
                detailMetadata = null
            }
        } finally {
            if (loadRequestId == requestId) {
                isLoadingInfo = false
            }
        }
    }

    LaunchedEffect(activity.id, uiState.activePhoneNumber) {
        loadActivityInfoSuspend()
    }

    fun triggerRefresh() {
        if (isRefreshing || isSigning) return
        loadJob?.cancel()
        loadJob = scope.launch {
            isRefreshing = true
            try {
                loadActivityInfoSuspend()
            } finally {
                isRefreshing = false
            }
        }
    }

    fun openSignOut(signOutId: Long) {
        if (isOpeningSignOut || isSigning) return
        val client = appState.activeClient ?: return
        val token = appState.sessionToken
        signOutJob?.cancel()
        isOpeningSignOut = true
        signOutJob = scope.launch {
            try {
                val meta = ChaoxingActivityService.fetchSignDetail(
                    client = client,
                    activeId = signOutId,
                    fallbackClassId = activity.classId,
                    fallbackCourseId = activity.courseId,
                )
                appState.checkSession(token)
                val next = ChaoxingSignActivity(
                    id = signOutId,
                    startTimeMs = null,
                    endTimeMs = null,
                    userStatus = 0,
                    otherId = meta.signType.rawValue,
                    isLook = false,
                    type = 2,
                    activeType = 2,
                    nameOne = meta.title.ifEmpty { "签退活动 #$signOutId" },
                    status = 1,
                    nameFour = "",
                    courseId = meta.signOutInfo.courseId,
                    classId = meta.signOutInfo.classId,
                    courseName = activity.courseName,
                    extContent = "",
                )
                onOpenSignOutActivity(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (appState.isCurrent(token)) {
                    appState.setErrorMessage("打开签退活动失败：${e.message ?: "未知错误"}")
                }
            } finally {
                isOpeningSignOut = false
            }
        }
    }

    val targetAccounts = remember(uiState.accounts, selectedPhones) {
        appState.selectedAccounts(selectedPhones)
    }

    val activityClosed = preSignStatus == ChaoxingSignActivityStatus.EXPIRED
    val selectionAlreadySigned = preSignStatus == ChaoxingSignActivityStatus.ALREADY_SIGNED &&
        targetAccounts.singleOrNull()?.phoneNumber == uiState.activePhoneNumber
    val signerEnabled = hasLoadedInfo && !isLoadingInfo && !isSigning && targetAccounts.isNotEmpty() &&
        !activityClosed && !selectionAlreadySigned

    fun startBatchSign(
        operation: suspend (
            client: ChaoxingHttpClient,
            account: ChaoxingAccountSession,
            captchaValidate: String?,
            enc2: String?,
        ) -> ChaoxingSignOutcome,
    ) {
        if (!signerEnabled) return
        val token = appState.sessionToken
        val phones = selectedPhones
        isSigning = true
        batchResults = emptyList()
        batchJob?.cancel()
        var didFinishBatch = false

        batchJob = appState.launchSessionTask {
            try {
                val collected = appState.executeSignBatch(
                    activity = activity,
                    selectedPhones = phones,
                    ignoreBatchDelay = detailMetadata?.isRefreshQRCode == true,
                    onProgress = { progress ->
                        batchResults = progress
                    },
                    operation = operation,
                )
                appState.checkSession(token)
                didFinishBatch = true
                batchResults = collected
                withContext(NonCancellable) {
                    appState.recordSignHistory(activity, token, collected)
                }
                appState.checkSession(token)
                loadActivityInfoSuspend()
            } catch (e: CancellationException) {
                if (appState.isCurrent(token) && !didFinishBatch) {
                    val partial = batchResults
                    withContext(NonCancellable) {
                        appState.recordSignHistory(activity, token, partial, stopped = true)
                    }
                }
                throw e
            } catch (e: Exception) {
                if (appState.isCurrent(token)) {
                    appState.setErrorMessage(e.message ?: "签到执行失败")
                }
            } finally {
                isSigning = false
                batchJob = null
            }
        }
    }

    fun submitPhotoBatch(submission: PhotoSubmission) {
        val obfuscate = appState.uiState.value.enableImageObfuscation
        when (submission) {
            PhotoSubmission.ProfileAvatar -> {
                startBatchSign { client, _, validate, enc2 ->
                    val objectId = ChaoxingFaceService.fetchUserProfileFaceObjectId(client)
                    currentCoroutineContext().ensureActive()
                    ChaoxingSignExecutor.signPhotoOrClick(
                        client = client,
                        activity = activity,
                        objectId = objectId,
                        captchaValidate = validate,
                        enc2 = enc2,
                    )
                }
            }

            is PhotoSubmission.ObjectId -> {
                startBatchSign { client, _, validate, enc2 ->
                    ChaoxingSignExecutor.signPhotoOrClick(
                        client = client,
                        activity = activity,
                        objectId = submission.objectId,
                        captchaValidate = validate,
                        enc2 = enc2,
                    )
                }
            }

            is PhotoSubmission.ImageBytes -> {
                startBatchSign { client, _, validate, enc2 ->
                    val finalData = if (obfuscate) {
                        withContext(Dispatchers.Default) {
                            AndroidImageObfuscator.obfuscateJpeg(submission.data)
                        }
                    } else {
                        submission.data
                    }
                    currentCoroutineContext().ensureActive()
                    val filename = "sign_${System.currentTimeMillis() / 1000}_${Random.nextInt(100, 1000)}.jpg"
                    val objectId = ChaoxingCloudDriveService.uploadImage(
                        client = client,
                        imageBytes = finalData,
                        filename = filename,
                    )
                    currentCoroutineContext().ensureActive()
                    ChaoxingSignExecutor.signPhotoOrClick(
                        client = client,
                        activity = activity,
                        objectId = objectId,
                        captchaValidate = validate,
                        enc2 = enc2,
                    )
                }
            }
        }
    }

    val effectiveSignType = remember(detailMetadata, activity.signType) {
        detailMetadata?.signType?.takeIf { it != ChaoxingSignType.UNKNOWN } ?: activity.signType
    }
    val positionRequired = detailMetadata?.isPositionRequired == true
    val faceRequired = detailMetadata?.isFaceRequired == true
    val jitterEnabled = uiState.enableLocationJitter

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = CxPalette.canvas,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (isSignOutDrillDown) "签退详情" else "活动详情",
                            style = CxTypography.title,
                            color = CxPalette.ink,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = CxPalette.canvas,
                        titleContentColor = CxPalette.ink,
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                )
            },
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { triggerRefresh() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = activity.courseName.ifBlank { "活动编号 ${activity.id}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CxPalette.inkSecondary,
                                )
                                Text(
                                    text = activity.nameOne.ifBlank { effectiveSignType.displayName },
                                    style = CxTypography.brandDisplay,
                                    color = CxPalette.ink,
                                )
                            }
                            if (isLoadingInfo) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = CxPalette.accent,
                                )
                            }
                        }

                        activity.endTimeMs?.takeIf { it > 0L }?.let { end ->
                            val deadline = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(end))
                            Text("截止时间  $deadline", style = MaterialTheme.typography.bodySmall, color = CxPalette.inkSecondary)
                        }

                        CxFlowRow(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                            val typeLabel = if (effectiveSignType == ChaoxingSignType.PHOTO_OR_CLICK && detailMetadata != null) {
                                if (detailMetadata?.isPhotoRequired == true) "拍照签到" else "普通签到"
                            } else effectiveSignType.displayName
                            CxBadge(text = typeLabel, tone = CxTone.ACCENT)

                            preSignStatus?.let { status ->
                                when (status) {
                                    ChaoxingSignActivityStatus.ALREADY_SIGNED -> CxBadge(
                                        text = "当前账号已签到",
                                        tone = CxTone.SAGE,
                                        icon = Icons.Rounded.Check,
                                    )

                                    ChaoxingSignActivityStatus.EXPIRED -> CxBadge(
                                        text = "活动已截止",
                                        tone = CxTone.CINNABAR,
                                        showDot = true,
                                    )

                                    ChaoxingSignActivityStatus.READY_TO_SIGN -> CxBadge(
                                        text = "当前账号待签到",
                                        tone = CxTone.AMBER,
                                        showDot = true,
                                    )
                                }
                            }

                            if (activity.isLate) {
                                CxBadge(
                                    text = "已过截止时刻",
                                    tone = CxTone.CINNABAR,
                                    icon = Icons.Rounded.Schedule,
                                )
                            }

                            if (detailMetadata?.isFaceRequired == true) {
                                CxBadge(
                                    text = "需人脸核验",
                                    tone = CxTone.PLUM,
                                    icon = Icons.Rounded.Face,
                                )
                            }

                            if (detailMetadata?.isPositionRequired == true) {
                                CxBadge(
                                    text = "需定位坐标",
                                    tone = CxTone.SAGE,
                                    icon = Icons.Rounded.LocationOn,
                                )
                            }

                            if (detailMetadata?.isCaptchaRequired == true) {
                                CxBadge(
                                    text = "含滑块验证",
                                    tone = CxTone.AMBER,
                                    icon = Icons.Rounded.Extension,
                                )
                            }
                        }
                    }

                    detailMetadata?.signOutInfo?.signOutId?.let { signOutId ->
                        CxSurfaceCard(
                            modifier = Modifier.fillMaxWidth(),
                            showBorder = false,
                            onClick = { openSignOut(signOutId) },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(11.dp),
                            ) {
                                CxIconTile(
                                    icon = Icons.Rounded.TouchApp,
                                    tone = CxTone.PLUM,
                                    size = 34.dp,
                                )
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = "关联签退活动",
                                        style = CxTypography.headline,
                                        color = CxPalette.ink,
                                    )
                                    Text(
                                        text = "打开对应的签退活动（$signOutId）",
                                        style = CxTypography.mono(11.sp),
                                        color = CxPalette.inkSecondary,
                                    )
                                }
                                if (isOpeningSignOut) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = CxPalette.plum,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = "打开关联签退活动",
                                        tint = CxPalette.inkTertiary,
                                    )
                                }
                            }
                        }
                    }

                    if (uiState.accounts.size > 1) {
                        val paceTrailing = if (uiState.batchSignDelayMs > 0) {
                            "PACE ~${String.format(Locale.US, "%.1f", uiState.batchSignDelayMs / 1000.0)}s"
                        } else {
                            "CONCURRENT"
                        }
                        CxSectionHeader(
                            title = "参与账号 (${targetAccounts.size}/${uiState.accounts.size})",
                            trailing = paceTrailing,
                            modifier = Modifier.padding(top = 4.dp),
                        )

                        CxSurfaceCard(
                            modifier = Modifier.fillMaxWidth(),
                            showBorder = false,
                        ) {
                            AccountGroupSelector(
                                accounts = uiState.accounts,
                                activePhone = uiState.activePhoneNumber,
                                selectedPhones = selectedPhones,
                                onSelectionChange = { selectedPhones = it },
                            )
                        }
                    }

                    CxSectionHeader(
                        title = if (activityClosed || selectionAlreadySigned) "签到状态" else "签到",
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    CxSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        showBorder = false,
                    ) {
                        if (!hasLoadedInfo || activityClosed || selectionAlreadySigned) {
                            Text(
                                text = when {
                                    isLoadingInfo -> "正在读取活动要求…"
                                    !hasLoadedInfo -> "暂时无法读取活动要求"
                                    activityClosed -> "已截止，无法再提交"
                                    else -> "此账号已完成签到"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = CxPalette.inkSecondary,
                            )
                            if (!hasLoadedInfo && !isLoadingInfo) {
                                androidx.compose.material3.TextButton(onClick = ::triggerRefresh) { Text("重新加载") }
                            }
                            return@CxSurfaceCard
                        }
                        when (effectiveSignType) {
                            ChaoxingSignType.LOCATION -> {
                                LocationSignPanel(
                                    appState = appState,
                                    activity = activity,
                                    detail = detailMetadata,
                                    isSigning = isSigning,
                                    enabled = signerEnabled,
                                    onSubmit = { location ->
                                        startBatchSign { client, _, validate, enc2 ->
                                            val perAccountLoc = ChaoxingLocationSignInfo(
                                                latitude = location.latitude,
                                                longitude = location.longitude,
                                                address = location.address,
                                                enableRandomOffset = jitterEnabled,
                                            )
                                            val faceObjId = if (faceRequired) {
                                                ChaoxingFaceService.fetchUserProfileFaceObjectId(client)
                                            } else {
                                                null
                                            }
                                            currentCoroutineContext().ensureActive()
                                            ChaoxingSignExecutor.signLocation(
                                                client = client,
                                                activity = activity,
                                                location = perAccountLoc,
                                                faceImageObjectId = faceObjId,
                                                captchaValidate = validate,
                                                enc2 = enc2,
                                            )
                                        }
                                    },
                                )
                            }

                            ChaoxingSignType.PHOTO_OR_CLICK -> {
                                if (detailMetadata?.isPhotoRequired == true) {
                                    PhotoSignPanel(
                                        appState = appState,
                                        requiresPhoto = true,
                                        isSigning = isSigning,
                                        enabled = signerEnabled,
                                        onSubmit = ::submitPhotoBatch,
                                    )
                                } else {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(14.dp),
                                    ) {
                                        Text(
                                            text = "本次签到无需照片。",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = CxPalette.inkSecondary,
                                        )

                                        CxPrimaryButton(
                                            onClick = {
                                                startBatchSign { client, _, validate, enc2 ->
                                                    ChaoxingSignExecutor.signPhotoOrClick(
                                                        client = client,
                                                        activity = activity,
                                                        objectId = null,
                                                        captchaValidate = validate,
                                                        enc2 = enc2,
                                                    )
                                                }
                                            },
                                            enabled = signerEnabled && !isSigning,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Verified,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "提交签到",
                                                style = MaterialTheme.typography.titleSmall.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                            )
                                        }

                                        CxHairlineDivider()

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable { showOptionalPhoto = !showOptionalPhoto }
                                                .heightIn(min = 48.dp)
                                                .padding(vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.PhotoCamera,
                                                contentDescription = null,
                                                tint = CxPalette.inkSecondary,
                                                modifier = Modifier.size(17.dp),
                                            )
                                            Text(
                                                text = "添加照片（可选）",
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontWeight = FontWeight.Medium,
                                                ),
                                                color = CxPalette.inkSecondary,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Icon(
                                                imageVector = if (showOptionalPhoto) {
                                                    Icons.Rounded.ExpandLess
                                                } else {
                                                    Icons.Rounded.ExpandMore
                                                },
                                                contentDescription = if (showOptionalPhoto) "收起" else "展开",
                                                tint = CxPalette.inkSecondary,
                                            )
                                        }

                                        AnimatedVisibility(visible = showOptionalPhoto) {
                                            PhotoSignPanel(
                                                appState = appState,
                                                requiresPhoto = false,
                                                isSigning = isSigning,
                                                enabled = signerEnabled,
                                                onSubmit = ::submitPhotoBatch,
                                            )
                                        }
                                    }
                                }
                            }

                            ChaoxingSignType.QR_CODE -> {
                                QrCodeSignPanel(
                                    appState = appState,
                                    activity = activity,
                                    isPositionRequired = positionRequired,
                                    isSigning = isSigning,
                                    enabled = signerEnabled,
                                    onManageLocations = onNavigateToLocations,
                                    onSubmit = { qrResult, location ->
                                        startBatchSign { client, _, validate, enc2 ->
                                            val perAccountLoc = location?.let {
                                                ChaoxingLocationSignInfo(
                                                    latitude = it.latitude,
                                                    longitude = it.longitude,
                                                    address = it.address,
                                                    enableRandomOffset = jitterEnabled,
                                                )
                                            }
                                            val faceObjId = if (faceRequired) {
                                                ChaoxingFaceService.fetchUserProfileFaceObjectId(client)
                                            } else {
                                                null
                                            }
                                            currentCoroutineContext().ensureActive()
                                            ChaoxingSignExecutor.signQrCode(
                                                client = client,
                                                activity = activity,
                                                qrResult = qrResult,
                                                location = perAccountLoc,
                                                faceImageObjectId = faceObjId,
                                                captchaValidate = validate,
                                                enc2 = enc2,
                                            )
                                        }
                                    },
                                )
                            }

                            ChaoxingSignType.GESTURE -> {
                                GestureSignPanel(
                                    appState = appState,
                                    activity = activity,
                                    isPositionRequired = positionRequired,
                                    isSigning = isSigning,
                                    enabled = signerEnabled,
                                    onManageLocations = onNavigateToLocations,
                                    onSubmit = { code, preset ->
                                        startBatchSign { client, _, validate, enc2 ->
                                            val perAccountLoc = preset?.toSignInfo(
                                                enableRandomOffset = jitterEnabled,
                                            )
                                            ChaoxingSignExecutor.signCodeOrGesture(
                                                client = client,
                                                activity = activity,
                                                signCode = code,
                                                location = perAccountLoc,
                                                captchaValidate = validate,
                                                enc2 = enc2,
                                            )
                                        }
                                    },
                                )
                            }

                            ChaoxingSignType.PASSWORD -> {
                                PasswordSignPanel(
                                    appState = appState,
                                    activity = activity,
                                    isPositionRequired = positionRequired,
                                    isSigning = isSigning,
                                    enabled = signerEnabled,
                                    onManageLocations = onNavigateToLocations,
                                    onSubmit = { code, preset ->
                                        startBatchSign { client, _, validate, enc2 ->
                                            val perAccountLoc = preset?.toSignInfo(
                                                enableRandomOffset = jitterEnabled,
                                            )
                                            ChaoxingSignExecutor.signCodeOrGesture(
                                                client = client,
                                                activity = activity,
                                                signCode = code,
                                                location = perAccountLoc,
                                                captchaValidate = validate,
                                                enc2 = enc2,
                                            )
                                        }
                                    },
                                )
                            }

                            ChaoxingSignType.UNKNOWN -> {
                                Text(
                                    text = "暂不支持此签到类型，请使用官方客户端处理。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = CxPalette.inkSecondary,
                                )
                            }
                        }
                    }

                    if (batchResults.isNotEmpty()) {
                        SignResultsHeader(batchResults, modifier = Modifier.padding(top = 4.dp))

                        batchResults.forEach { result ->
                            key(result.id) {
                                CxSurfaceCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    showBorder = false,
                                ) {
                                    SignResultRow(result) {
                                        if (result.disposition == BatchSignDisposition.FAILED_OR_UNCONFIRMED &&
                                            (result.message.contains("设备") ||
                                                result.message.contains("device", ignoreCase = true))
                                        ) {
                                            CxSecondaryButton(
                                                onClick = {
                                                    appScope.launch {
                                                        appState.regenerateDeviceCode(result.phoneNumber)
                                                    }
                                                },
                                                compact = true,
                                            ) {
                                                Text("换设备码")
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

        if (isSigning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center,
            ) {
                CxSurfaceCard(
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .widthIn(max = 320.dp),
                    contentPadding = PaddingValues(24.dp),
                    cornerRadius = 16.dp,
                    showBorder = false,
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = CxPalette.accent)
                        Text(
                            text = "正在提交考勤请求...",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                            color = CxPalette.ink,
                        )
                        CxSecondaryButton(
                            onClick = { batchJob?.cancel() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.StopCircle,
                                contentDescription = null,
                                tint = CxPalette.cinnabar,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "停止后续操作",
                                color = CxPalette.cinnabar,
                            )
                        }
                        Text(
                            text = "已发出的请求无法撤回",
                            style = MaterialTheme.typography.bodySmall,
                            color = CxPalette.inkSecondary,
                        )
                    }
                }
            }
        }
    }
}
