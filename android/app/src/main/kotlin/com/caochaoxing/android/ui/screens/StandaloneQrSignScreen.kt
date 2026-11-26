// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.caochaoxing.android.media.QrCodeCameraPreview
import com.caochaoxing.android.media.QrCodeDecoder
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.AppUiState
import com.caochaoxing.android.state.BatchSignResult
import com.caochaoxing.android.state.SessionToken
import com.caochaoxing.android.ui.components.AccountGroupSelector
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxFlowRow
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxSecondaryButton
import com.caochaoxing.android.ui.components.CxSectionHeader
import com.caochaoxing.android.ui.components.CxSurfaceCard
import com.caochaoxing.android.ui.components.SignResultRow
import com.caochaoxing.android.ui.components.SignResultsHeader
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.components.CxWellBox
import com.caochaoxing.android.ui.components.LocationAttachmentPicker
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.crypto.ChaoxingQrCodeParser
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingQrCodeParseResult
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignDetailMetadata
import com.caochaoxing.core.service.ChaoxingActivityService
import com.caochaoxing.core.service.ChaoxingFaceService
import com.caochaoxing.core.signer.ChaoxingSignExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class StandaloneQrUiState(
    val rawQrText: String = "",
    val parsedResult: ChaoxingQrCodeParseResult? = null,
    val resolvedActivity: ChaoxingSignActivity? = null,
    val resolvedDetail: ChaoxingSignDetailMetadata? = null,
    val resolvedInput: String? = null,
    val expiredHint: Boolean? = null,
    val isResolving: Boolean = false,
    val retainedCourseId: Long = 0L,
    val retainedPositionRequired: Boolean = false,
    val selectedPhones: Set<String> = emptySet(),
    val selectionInitialized: Boolean = false,
    val includeLocation: Boolean = false,
    val selectedPresetId: String? = null,
    val autoSubmitOnScan: Boolean = false,
    val isWorking: Boolean = false,
    val results: List<BatchSignResult> = emptyList(),
)

private fun resolveLocationPreset(
    appUi: AppUiState,
    courseId: Long,
    includeLocation: Boolean,
    selectedPresetId: String?,
): ChaoxingLocationPreset? {
    if (!includeLocation) return null
    val bound = appUi.boundLocation(courseId)
    return appUi.locationPresets.firstOrNull { it.id == selectedPresetId }
        ?: bound?.takeIf { it.id == selectedPresetId }
        ?: bound
        ?: appUi.locationPresets.firstOrNull()
}

/**
 * Retains standalone QR resolution, account/location selection, and batch results across tab
 * switches and forward navigation to the location preset manager.
 */
class StandaloneQrViewModel(
    private val appState: AndroidAppState,
) : ViewModel() {
    private val _state = MutableStateFlow(StandaloneQrUiState())
    val state: StateFlow<StandaloneQrUiState> = _state.asStateFlow()

    private var resolveJob: Job? = null
    private var batchJob: Job? = null
    private var resolveRequestId: Long = 0L
    private var lastSessionToken: SessionToken? = null

    fun syncAccounts(accounts: List<ChaoxingAccountSession>) {
        val validPhones = accounts.map { it.phoneNumber }.toSet()
        _state.update { current ->
            if (!current.selectionInitialized) {
                current.copy(
                    selectedPhones = validPhones,
                    selectionInitialized = true,
                )
            } else {
                val filtered = current.selectedPhones.intersect(validPhones)
                if (filtered == current.selectedPhones) {
                    current
                } else {
                    current.copy(selectedPhones = filtered)
                }
            }
        }
    }

    fun onSelectedPhonesChange(phones: Set<String>) {
        _state.update { it.copy(selectedPhones = phones) }
    }

    fun onIncludeLocationChange(include: Boolean) {
        _state.update { it.copy(includeLocation = include) }
    }

    fun onSelectedPresetIdChange(presetId: String?) {
        _state.update { it.copy(selectedPresetId = presetId) }
    }

    fun onAutoSubmitOnScanChange(enabled: Boolean) {
        _state.update { it.copy(autoSubmitOnScan = enabled) }
    }

    fun onSessionChanged(token: SessionToken) {
        if (lastSessionToken == token) return
        val isInitial = lastSessionToken == null
        lastSessionToken = token
        if (isInitial) return
        resolveJob?.cancel()
        resolveJob = null
        val currentRaw = _state.value.rawQrText
        invalidateResolution()
        if (currentRaw.isNotBlank() && appState.isCurrent(token)) {
            onRawQrTextChanged(currentRaw)
        }
    }

    fun onRawQrTextChanged(raw: String) {
        resolveJob?.cancel()
        resolveJob = null
        val requestId = ++resolveRequestId
        val payload = runCatching { ChaoxingQrCodeParser.parse(raw) }.getOrNull()
        _state.update {
            it.copy(
                rawQrText = raw,
                parsedResult = null,
                resolvedActivity = null,
                resolvedDetail = null,
                resolvedInput = null,
                expiredHint = null,
                isResolving = false,
                retainedCourseId = if (payload == null) 0L else it.retainedCourseId,
                retainedPositionRequired = if (payload == null) false else it.retainedPositionRequired,
            )
        }
        if (payload == null) return

        val client = appState.activeClient ?: return
        val token = appState.sessionToken

        _state.update { it.copy(isResolving = true) }
        resolveJob = viewModelScope.launch {
            try {
                val (activity, detail) = ChaoxingActivityService.buildActivityAndDetailFromQrCode(client, payload)
                ensureActive()
                appState.checkSession(token)
                if (resolveRequestId != requestId || _state.value.rawQrText != raw) {
                    return@launch
                }
                val aid = payload.aid
                if (aid == null || aid != activity.id || aid != detail.activeId) {
                    throw ChaoxingError.InvalidInput("二维码与活动详情不一致，请重新扫描")
                }
                _state.update { current ->
                    current.copy(
                        parsedResult = payload,
                        resolvedActivity = activity,
                        resolvedDetail = detail,
                        resolvedInput = raw,
                        expiredHint = null,
                        isResolving = false,
                        retainedCourseId = activity.courseId,
                        retainedPositionRequired = detail.isPositionRequired,
                        includeLocation = if (detail.isPositionRequired) true else current.includeLocation,
                    )
                }
                val latest = _state.value
                if (latest.autoSubmitOnScan && !latest.isWorking && latest.selectedPhones.isNotEmpty()) {
                    val appUi = appState.uiState.value
                    val needsPreset = latest.includeLocation || detail.isPositionRequired
                    val presetReady = !needsPreset || resolveLocationPreset(
                        appUi = appUi,
                        courseId = activity.courseId,
                        includeLocation = true,
                        selectedPresetId = latest.selectedPresetId,
                    ) != null
                    if (presetReady) {
                        submitBatch()
                    }
                }
                if (!payload.code.isNullOrEmpty()) {
                    val expired = try {
                        ChaoxingSignExecutor.isQrCodeExpired(
                            client = client,
                            activeId = activity.id,
                            qrResult = payload,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    appState.checkSession(token)
                    if (resolveRequestId == requestId && _state.value.rawQrText == raw) {
                        _state.update { it.copy(expiredHint = expired) }
                    }
                }
            } catch (e: CancellationException) {
                if (resolveRequestId == requestId) {
                    _state.update { it.copy(isResolving = false) }
                }
                throw e
            } catch (e: Exception) {
                if (!appState.isCurrent(token)) {
                    invalidateResolution()
                    return@launch
                }
                if (resolveRequestId == requestId) {
                    _state.update {
                        it.copy(
                            isResolving = false,
                            retainedCourseId = 0L,
                            retainedPositionRequired = false,
                        )
                    }
                    if (_state.value.rawQrText == raw) {
                        appState.setErrorMessage("无法读取二维码活动：${e.message ?: "解析失败"}")
                    }
                }
            }
        }
    }

    fun submitBatch() {
        val current = _state.value
        val appUi = appState.uiState.value
        if (current.isWorking || current.isResolving) return
        if (current.resolvedInput != current.rawQrText) return
        val parsed = current.parsedResult ?: return
        val activity = current.resolvedActivity ?: return
        val detail = current.resolvedDetail ?: return
        if (parsed.aid != activity.id || detail.activeId != activity.id) return

        val requiresLocationPreset = current.includeLocation || detail.isPositionRequired
        val preset = resolveLocationPreset(
            appUi = appUi,
            courseId = activity.courseId,
            includeLocation = requiresLocationPreset,
            selectedPresetId = current.selectedPresetId,
        )
        if (requiresLocationPreset && preset == null) {
            appState.setErrorMessage("请先添加一个地点预设后再提交")
            return
        }

        val token = appState.sessionToken
        val jitter = appUi.enableLocationJitter
        val phones = current.selectedPhones
        if (phones.isEmpty()) {
            appState.setErrorMessage("请至少选择一个签到账号")
            return
        }

        _state.update { it.copy(isWorking = true, results = emptyList()) }
        batchJob?.cancel()
        var didFinishBatch = false

        batchJob = appState.launchSessionTask {
            try {
                val collected = appState.executeSignBatch(
                    activity = activity,
                    selectedPhones = phones,
                    ignoreBatchDelay = detail.isRefreshQRCode,
                    onProgress = { progress ->
                        _state.update { it.copy(results = progress) }
                    },
                ) { client, _, validate, enc2 ->
                    val location = preset?.toSignInfo(enableRandomOffset = jitter)
                    val faceObjectId = if (detail.isFaceRequired) {
                        ChaoxingFaceService.fetchUserProfileFaceObjectId(client)
                    } else {
                        null
                    }
                    ensureActive()
                    ChaoxingSignExecutor.signQrCode(
                        client = client,
                        activity = activity,
                        qrResult = parsed,
                        location = location,
                        faceImageObjectId = faceObjectId,
                        captchaValidate = validate,
                        enc2 = enc2,
                    )
                }
                appState.checkSession(token)
                didFinishBatch = true
                _state.update { it.copy(results = collected) }
                withContext(NonCancellable) {
                    appState.recordSignHistory(activity, token, collected)
                }
            } catch (e: CancellationException) {
                if (appState.isCurrent(token) && !didFinishBatch) {
                    val partial = _state.value.results
                    withContext(NonCancellable) {
                        appState.recordSignHistory(activity, token, partial, stopped = true)
                    }
                }
                throw e
            } catch (e: Exception) {
                if (appState.isCurrent(token)) {
                    appState.setErrorMessage(e.message ?: "扫码批量签到失败")
                }
            } finally {
                _state.update { it.copy(isWorking = false) }
                batchJob = null
            }
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
    }

    private fun invalidateResolution() {
        resolveRequestId += 1L
        _state.update {
            it.copy(
                parsedResult = null,
                resolvedActivity = null,
                resolvedDetail = null,
                resolvedInput = null,
                expiredHint = null,
                isResolving = false,
                retainedCourseId = 0L,
                retainedPositionRequired = false,
            )
        }
    }

    override fun onCleared() {
        resolveJob?.cancel()
        batchJob?.cancel()
        super.onCleared()
    }
}

/**
 * Standalone multi-account QR code sign-in screen supporting live CameraX scanning, gallery image
 * decoding, clipboard parsing, automatic position/face requirement detection, and captcha retries.
 */
@Composable
fun StandaloneQrSignScreen(
    appState: AndroidAppState,
    viewModel: StandaloneQrViewModel,
    onManageLocations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appUi by appState.uiState.collectAsStateWithLifecycle()
    val qrState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var showCameraScanner by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(appUi.accounts) {
        viewModel.syncAccounts(appUi.accounts)
    }

    LaunchedEffect(appUi.locationPresets) {
        if (qrState.selectedPresetId == null && appUi.locationPresets.isNotEmpty()) {
            viewModel.onSelectedPresetIdChange(appUi.locationPresets.first().id)
        }
    }

    LaunchedEffect(appUi.pendingQrCodeInput) {
        val pending = appUi.pendingQrCodeInput ?: return@LaunchedEffect
        appState.clearPendingQrCodeInput()
        viewModel.onRawQrTextChanged(pending)
    }

    val activeAccount = appUi.activeAccount
    LaunchedEffect(
        appUi.activePhoneNumber,
        activeAccount?.lastLoginAt,
        activeAccount?.configuredFid,
    ) {
        viewModel.onSessionChanged(appState.sessionToken)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showCameraScanner = true
        } else {
            appState.setErrorMessage("相机权限未开启，请在系统设置中允许访问相机或改用相册识码")
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decodedStrings = try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }
                if (bytes == null || bytes.isEmpty()) {
                    emptyList()
                } else {
                    withContext(Dispatchers.Default) {
                        QrCodeDecoder.decodeFromBytes(bytes)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            val first = decodedStrings.firstOrNull()
            if (first != null) {
                viewModel.onRawQrTextChanged(first)
            } else {
                appState.setErrorMessage("未在图片中识别到二维码")
            }
        }
    }

    fun openCameraScanner() {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            showCameraScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val resolvedActivity = qrState.resolvedActivity
    val resolvedDetail = qrState.resolvedDetail
    val effectiveCourseId = resolvedActivity?.courseId ?: qrState.retainedCourseId
    val effectiveBoundPreset = remember(effectiveCourseId, appUi.courseLocationBindings) {
        appUi.boundLocation(effectiveCourseId)
    }
    val effectivePositionRequired = resolvedDetail?.isPositionRequired ?: qrState.retainedPositionRequired
    val requiresLocationPreset = qrState.includeLocation || effectivePositionRequired
    val resolvedSelectedPreset = remember(
        appUi,
        effectiveCourseId,
        requiresLocationPreset,
        qrState.selectedPresetId,
    ) {
        resolveLocationPreset(
            appUi = appUi,
            courseId = effectiveCourseId,
            includeLocation = requiresLocationPreset,
            selectedPresetId = qrState.selectedPresetId,
        )
    }
    val missingLocationPreset = qrState.parsedResult != null &&
        requiresLocationPreset &&
        resolvedSelectedPreset == null
    val targetAccounts = remember(appUi.accounts, qrState.selectedPhones) {
        appState.selectedAccounts(qrState.selectedPhones)
    }

    val shouldShowLocationSection = appUi.locationPresets.isNotEmpty() ||
        effectiveBoundPreset != null ||
        effectivePositionRequired ||
        qrState.includeLocation

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CxPalette.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "扫码代签",
                        style = CxTypography.title,
                        color = CxPalette.ink,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxPalette.canvas,
                    titleContentColor = CxPalette.ink,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "qr_header") {
                CxSectionHeader(title = "二维码")
            }

            item(key = "qr_input_card") {
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CxPrimaryButton(
                                onClick = { openCameraScanner() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PhotoCamera,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("相机扫码")
                            }

                            CxSecondaryButton(
                                onClick = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PhotoLibrary,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("相册识码")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = qrState.rawQrText,
                                onValueChange = viewModel::onRawQrTextChanged,
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("粘贴二维码 URL 或 SIGNIN 载荷") },
                                singleLine = true,
                                textStyle = CxTypography.mono(12.5.sp),
                                shape = RoundedCornerShape(10.dp),
                                colors = cxOutlinedTextFieldColors(),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrectEnabled = false,
                                    keyboardType = KeyboardType.Uri,
                                ),
                            )

                            CxSecondaryButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text?.trim().orEmpty()
                                    if (clip.isNotEmpty()) {
                                        viewModel.onRawQrTextChanged(clip)
                                    } else {
                                        appState.setErrorMessage("剪贴板中没有文本内容")
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("剪贴板")
                            }
                        }

                        if (qrState.isResolving) {
                            CxWellBox(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = CxPalette.accent,
                                    )
                                    Text(
                                        text = "正在读取活动详情…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CxPalette.inkSecondary,
                                    )
                                }
                            }
                        } else if (qrState.parsedResult != null) {
                            val parsed = qrState.parsedResult!!
                            CxWellBox(modifier = Modifier.fillMaxWidth()) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    CxFlowRow(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                                        CxBadge(
                                            text = "AID ${parsed.aid?.toString() ?: "AUTO"}",
                                            tone = CxTone.SAGE,
                                            icon = Icons.Rounded.Verified,
                                            monospaced = true,
                                        )
                                        if (resolvedDetail?.isFaceRequired == true) {
                                            CxBadge(
                                                text = "需人脸",
                                                tone = CxTone.PLUM,
                                                icon = Icons.Rounded.Face,
                                            )
                                        }
                                        if (resolvedDetail?.isPositionRequired == true) {
                                            CxBadge(
                                                text = "需定位",
                                                tone = CxTone.SAGE,
                                                icon = Icons.Rounded.LocationOn,
                                            )
                                        }
                                        if (resolvedDetail?.isRefreshQRCode == true) {
                                            CxBadge(
                                                text = "动态码 · 自动免错峰",
                                                tone = CxTone.AMBER,
                                                showDot = true,
                                            )
                                        }
                                        qrState.expiredHint?.let { expired ->
                                            CxBadge(
                                                text = if (expired) "警告：动态二维码可能已过期" else "动态二维码校验正常",
                                                tone = if (expired) CxTone.AMBER else CxTone.SAGE,
                                                showDot = true,
                                            )
                                        }
                                    }
                                    if (resolvedActivity != null) {
                                        Text(
                                            text = resolvedActivity.nameOne,
                                            style = CxTypography.headline,
                                            color = CxPalette.ink,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (appUi.accounts.size > 1) {
                item(key = "qr_accounts_header") {
                    CxSectionHeader(
                        title = "代签账号 (${targetAccounts.size}/${appUi.accounts.size})",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item(key = "qr_accounts_selector") {
                    CxSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        showBorder = true,
                    ) {
                        AccountGroupSelector(
                            accounts = appUi.accounts,
                            activePhone = appUi.activePhoneNumber,
                            selectedPhones = qrState.selectedPhones,
                            onSelectionChange = viewModel::onSelectedPhonesChange,
                        )
                    }
                }
            }

            if (shouldShowLocationSection) {
                item(key = "qr_location_header") {
                    CxSectionHeader(
                        title = "附带位置",
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                item(key = "qr_location_picker") {
                    CxSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        showBorder = true,
                    ) {
                        LocationAttachmentPicker(
                            courseId = effectiveCourseId,
                            boundPreset = effectiveBoundPreset,
                            presets = appUi.locationPresets,
                            isPositionRequired = effectivePositionRequired,
                            includeLocation = qrState.includeLocation,
                            onIncludeLocationChange = viewModel::onIncludeLocationChange,
                            selectedPresetId = qrState.selectedPresetId,
                            onSelectedPresetIdChange = viewModel::onSelectedPresetIdChange,
                            onManageLocations = onManageLocations,
                        )
                    }
                }
            }

            item(key = "qr_submit_card") {
                CxSurfaceCard(
                    modifier = Modifier.fillMaxWidth(),
                    showBorder = true,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (missingLocationPreset) {
                            CxWellBox(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LocationOn,
                                        contentDescription = null,
                                        tint = CxPalette.cinnabar,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = "请先添加一个地点预设后再提交",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CxPalette.cinnabar,
                                        modifier = Modifier.weight(1f),
                                    )
                                    CxSecondaryButton(
                                        onClick = onManageLocations,
                                        compact = true,
                                    ) {
                                        Text("添加地点")
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = "扫到即签（动态码推荐）",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                    ),
                                    color = CxPalette.ink,
                                )
                                Text(
                                    text = "扫码识别后立即提交，减少 5–10 秒动态码过期概率",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CxPalette.inkSecondary,
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = qrState.autoSubmitOnScan,
                                onCheckedChange = viewModel::onAutoSubmitOnScanChange,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CxPalette.onAccent,
                                    checkedTrackColor = CxPalette.accent,
                                ),
                            )
                        }

                        val canSubmit = !qrState.isWorking &&
                            !qrState.isResolving &&
                            qrState.parsedResult != null &&
                            resolvedActivity != null &&
                            targetAccounts.isNotEmpty()

                        CxPrimaryButton(
                            onClick = { viewModel.submitBatch() },
                            enabled = canSubmit,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            if (qrState.isWorking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = CxPalette.onAccent,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Bolt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    qrState.isWorking -> "正在提交..."
                                    qrState.isResolving -> "正在读取活动..."
                                    else -> "为所选账号提交"
                                },
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                            )
                        }

                        if (qrState.isWorking) {
                            CxSecondaryButton(
                                onClick = { viewModel.cancelBatch() },
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

            if (qrState.results.isNotEmpty()) {
                item(key = "qr_results_header") {
                    SignResultsHeader(qrState.results, modifier = Modifier.padding(top = 4.dp))
                }
                items(
                    items = qrState.results,
                    key = { it.id },
                ) { result ->
                    CxSurfaceCard(
                        modifier = Modifier.fillMaxWidth(),
                        showBorder = true,
                    ) {
                        SignResultRow(result)
                    }
                }
            }
        }
    }

    if (showCameraScanner) {
        QrScannerFullScreenDialog(
            torchEnabled = torchEnabled,
            onToggleTorch = { torchEnabled = !torchEnabled },
            onQrDetected = { scanned ->
                showCameraScanner = false
                torchEnabled = false
                viewModel.onRawQrTextChanged(scanned)
            },
            onCameraError = { message ->
                showCameraScanner = false
                torchEnabled = false
                appState.setErrorMessage(message)
            },
            onDismiss = {
                showCameraScanner = false
                torchEnabled = false
            },
        )
    }
}

@Composable
private fun QrScannerFullScreenDialog(
    torchEnabled: Boolean,
    onToggleTorch: () -> Unit,
    onQrDetected: (String) -> Unit,
    onCameraError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            QrCodeCameraPreview(
                modifier = Modifier.fillMaxSize(),
                torchEnabled = torchEnabled,
                onQrDetected = onQrDetected,
                onCameraError = onCameraError,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                ) {
                    TextButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("关闭", color = Color.White)
                    }
                }

                Text(
                    text = "扫描签到二维码",
                    style = CxTypography.headline,
                    color = Color.White,
                )

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.55f),
                ) {
                    IconButton(onClick = onToggleTorch) {
                        Icon(
                            imageVector = if (torchEnabled) {
                                Icons.Rounded.FlashOn
                            } else {
                                Icons.Rounded.FlashOff
                            },
                            contentDescription = if (torchEnabled) "关闭闪光灯" else "开启闪光灯",
                            tint = Color.White,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(236.dp)
                        .border(
                            width = 2.dp,
                            color = Color.White.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(18.dp),
                        ),
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                ) {
                    Text(
                        text = "双指捏合或拖动滑块缩放 · 轻点对焦",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}
