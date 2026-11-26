// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.ui.screens.signers

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.caochaoxing.android.media.QrCodeCameraPreview
import com.caochaoxing.android.media.QrCodeDecoder
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.components.CxBadge
import com.caochaoxing.android.ui.components.CxFlowRow
import com.caochaoxing.android.ui.components.CxPrimaryButton
import com.caochaoxing.android.ui.components.CxSecondaryButton
import com.caochaoxing.android.ui.components.CxTone
import com.caochaoxing.android.ui.components.CxWellBox
import com.caochaoxing.android.ui.components.LocationAttachmentPicker
import com.caochaoxing.android.ui.screens.cxOutlinedTextFieldColors
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.crypto.ChaoxingQrCodeParser
import com.caochaoxing.core.model.ChaoxingLocationSignInfo
import com.caochaoxing.core.model.ChaoxingQrCodeParseResult
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.signer.ChaoxingSignExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private const val MAX_QR_GALLERY_IMAGE_BYTES = 16 * 1024 * 1024

/**
 * Activity-scoped QR code sign-in panel supporting CameraX live scanning, gallery QR decoding,
 * clipboard auto-import, AID mismatch validation, expiry verification, and location attachment.
 */
@Composable
fun QrCodeSignPanel(
    appState: AndroidAppState,
    activity: ChaoxingSignActivity,
    isPositionRequired: Boolean,
    isSigning: Boolean,
    enabled: Boolean = true,
    onManageLocations: () -> Unit,
    onSubmit: (ChaoxingQrCodeParseResult, ChaoxingLocationSignInfo?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by appState.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var rawQrText by remember(activity.id) { mutableStateOf("") }
    var parsedResult by remember(activity.id) { mutableStateOf<ChaoxingQrCodeParseResult?>(null) }
    var expiredHint by remember(activity.id) { mutableStateOf<Boolean?>(null) }
    var isCheckingExpiry by remember(activity.id) { mutableStateOf(false) }
    var expiryJob by remember(activity.id) { mutableStateOf<Job?>(null) }
    var decodeJob by remember(activity.id) { mutableStateOf<Job?>(null) }
    var qrRequestId by remember(activity.id) { mutableLongStateOf(0L) }

    var showCameraScanner by remember { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }

    var includeLocation by remember(activity.id) { mutableStateOf(isPositionRequired) }
    var selectedPresetId by remember(activity.id) { mutableStateOf<String?>(null) }

    DisposableEffect(activity.id) {
        onDispose {
            expiryJob?.cancel()
            expiryJob = null
            decodeJob?.cancel()
            decodeJob = null
        }
    }

    val boundPreset = remember(activity.courseId, uiState.courseLocationBindings) {
        uiState.boundLocation(activity.courseId)
    }

    LaunchedEffect(uiState.locationPresets, boundPreset) {
        if (selectedPresetId == null) {
            selectedPresetId = boundPreset?.id ?: uiState.locationPresets.firstOrNull()?.id
        }
    }

    val selectedLocation = remember(
        includeLocation,
        isPositionRequired,
        boundPreset,
        uiState.locationPresets,
        selectedPresetId,
        uiState.enableLocationJitter,
    ) {
        if (!includeLocation && !isPositionRequired) {
            null
        } else {
            val preset = uiState.locationPresets.firstOrNull { it.id == selectedPresetId }
                ?: boundPreset?.takeIf { it.id == selectedPresetId }
                ?: boundPreset
                ?: uiState.locationPresets.firstOrNull()
            preset?.toSignInfo(enableRandomOffset = uiState.enableLocationJitter)
        }
    }

    fun parseQrCode(raw: String, updateRawText: Boolean = true) {
        if (updateRawText && rawQrText != raw) {
            rawQrText = raw
        }
        expiryJob?.cancel()
        expiryJob = null
        val requestId = ++qrRequestId
        expiredHint = null
        isCheckingExpiry = false
        parsedResult = null

        val result = runCatching { ChaoxingQrCodeParser.parse(raw) }.getOrNull() ?: return
        if (result.aid != null && result.aid != activity.id) {
            appState.setErrorMessage("二维码属于其他活动，请返回扫码页处理")
            return
        }
        parsedResult = result

        val client = appState.activeClient ?: return
        val token = appState.sessionToken
        if (result.code.isNullOrEmpty()) return

        isCheckingExpiry = true
        expiryJob = scope.launch {
            try {
                val expired = ChaoxingSignExecutor.isQrCodeExpired(
                    client = client,
                    activeId = activity.id,
                    qrResult = result,
                )
                appState.checkSession(token)
                if (qrRequestId == requestId && rawQrText == raw) {
                    expiredHint = expired
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (qrRequestId == requestId) {
                    isCheckingExpiry = false
                }
            }
        }
    }

    LaunchedEffect(activity.id) {
        if (rawQrText.isEmpty()) {
            val clip = clipboardManager.getText()?.text?.trim().orEmpty()
            if (clip.isNotEmpty()) {
                val parsed = runCatching { ChaoxingQrCodeParser.parse(clip) }.getOrNull()
                if (parsed != null && (parsed.aid == null || parsed.aid == activity.id)) {
                    parseQrCode(clip, updateRawText = true)
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showCameraScanner = true
        } else {
            appState.setErrorMessage("相机权限未开启，请在系统设置中允许访问相机或改用相册识图")
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        decodeJob?.cancel()
        decodeJob = scope.launch {
            try {
                val decoded = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(8192)
                        val out = ByteArrayOutputStream()
                        var total = 0
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            total += read
                            if (total > MAX_QR_GALLERY_IMAGE_BYTES) {
                                error("所选图片过大，无法读取")
                            }
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    }
                    if (bytes == null || bytes.isEmpty()) {
                        emptyList()
                    } else {
                        QrCodeDecoder.decodeFromBytes(bytes)
                    }
                }
                val first = decoded.firstOrNull()
                if (first != null) {
                    parseQrCode(first, updateRawText = true)
                } else {
                    appState.setErrorMessage("未在所选图片中识别到有效二维码")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                appState.setErrorMessage(
                    e.message?.takeIf { it.isNotBlank() } ?: "读取所选图片失败",
                )
            } catch (_: Exception) {
                appState.setErrorMessage("未在所选图片中识别到有效二维码")
            }
        }
    }

    fun openCameraScanner() {
        if (!enabled) return
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

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CxPrimaryButton(
                onClick = { openCameraScanner() },
                enabled = enabled && !isSigning,
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
                enabled = enabled && !isSigning,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoLibrary,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("相册识图")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = rawQrText,
                onValueChange = { next ->
                    rawQrText = next
                    parseQrCode(next, updateRawText = false)
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("粘贴学习通二维码链接或 SIGNIN 参数") },
                enabled = enabled,
                singleLine = true,
                textStyle = CxTypography.mono(12.5.sp),
                colors = cxOutlinedTextFieldColors(),
                shape = RoundedCornerShape(10.dp),
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
                        parseQrCode(clip, updateRawText = true)
                    } else {
                        appState.setErrorMessage("剪贴板中没有文本内容")
                    }
                },
                enabled = enabled,
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentPaste,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("粘贴")
            }
        }

        parsedResult?.let { parsed ->
            CxWellBox(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    CxFlowRow(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                        CxBadge(
                            text = "AID ${parsed.aid?.toString() ?: "跟随当前"}",
                            tone = CxTone.SAGE,
                            icon = Icons.Rounded.QrCode2,
                            monospaced = true,
                        )
                        if (isCheckingExpiry) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = CxPalette.accent,
                            )
                        } else {
                            expiredHint?.let { expired ->
                                CxBadge(
                                    text = if (expired) "请重新扫码" else "二维码未过期",
                                    tone = if (expired) CxTone.AMBER else CxTone.SAGE,
                                    showDot = true,
                                )
                            }
                        }
                    }
                }
            }
        }

        LocationAttachmentPicker(
            courseId = activity.courseId,
            boundPreset = boundPreset,
            presets = uiState.locationPresets,
            isPositionRequired = isPositionRequired,
            includeLocation = includeLocation,
            onIncludeLocationChange = { includeLocation = it },
            selectedPresetId = selectedPresetId,
            onSelectedPresetIdChange = { selectedPresetId = it },
            onManageLocations = onManageLocations,
        )

        CxPrimaryButton(
            onClick = {
                val parsed = parsedResult ?: return@CxPrimaryButton
                if (isPositionRequired && selectedLocation == null) {
                    appState.setErrorMessage("该活动要求位置，请先选择有效预设")
                    return@CxPrimaryButton
                }
                onSubmit(parsed, selectedLocation)
            },
            enabled = enabled && !isSigning && parsedResult != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Rounded.QrCodeScanner,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "提交二维码签到",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }

    if (showCameraScanner) {
        WorkspaceQrCameraDialog(
            torchEnabled = torchEnabled,
            onToggleTorch = { torchEnabled = !torchEnabled },
            onQrDetected = { scanned ->
                if (!showCameraScanner) return@WorkspaceQrCameraDialog
                showCameraScanner = false
                torchEnabled = false
                parseQrCode(scanned, updateRawText = true)
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
private fun WorkspaceQrCameraDialog(
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
