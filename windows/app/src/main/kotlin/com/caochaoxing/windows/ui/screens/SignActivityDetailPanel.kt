// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.windows.state.parseQrInputOrNull
import com.caochaoxing.core.crypto.CoordinateConverter
import com.caochaoxing.core.crypto.ExternalCoordinateParser
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingLocationSignInfo
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignActivityStatus
import com.caochaoxing.core.model.ChaoxingSignDetailMetadata
import com.caochaoxing.core.model.ChaoxingSignType
import com.caochaoxing.core.model.CoordinateSystem
import com.caochaoxing.core.service.ChaoxingCloudDriveService
import com.caochaoxing.core.service.ChaoxingFaceService
import com.caochaoxing.core.service.NominatimGeocoder
import com.caochaoxing.core.service.NominatimPlace
import com.caochaoxing.core.signer.ChaoxingSignExecutor
import com.caochaoxing.windows.platform.DesktopImageObfuscator
import com.caochaoxing.windows.platform.DesktopQrCodeDecoder
import com.caochaoxing.windows.platform.DesktopSystemTools
import com.caochaoxing.windows.state.BatchSignResult
import com.caochaoxing.windows.state.WindowsAppState
import com.caochaoxing.windows.ui.components.AccountBatchSelector
import com.caochaoxing.windows.ui.components.CxBadge
import com.caochaoxing.windows.ui.components.CxCard
import com.caochaoxing.windows.ui.components.LocationCanvasPicker
import com.caochaoxing.windows.ui.theme.CxPalette
import com.caochaoxing.windows.ui.theme.CxTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.util.Locale
import kotlin.math.hypot

/**
 * Unified desktop sign-in workbench supporting all 5 Chaoxing sign types,
 * associated sign-out drill-down, multi-account staggered execution, face profile lookup,
 * local/clipboard QR & photo decoding, interactive 3x3 gesture grid, and zero-key location canvas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignActivityDetailPanel(
    activity: ChaoxingSignActivity,
    appState: WindowsAppState,
    onClose: () -> Unit,
    onNavigateToActivity: (ChaoxingSignActivity) -> Unit,
) {
    val uiState by appState.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var preSignStatus by remember(activity.id) { mutableStateOf<ChaoxingSignActivityStatus?>(null) }
    var detail by remember(activity.id) { mutableStateOf<ChaoxingSignDetailMetadata?>(null) }
    var loadingMeta by remember(activity.id) { mutableStateOf(true) }
    var metaError by remember(activity.id) { mutableStateOf<String?>(null) }

    var selectedPhones by remember(activity.id, uiState.activePhoneNumber) {
        mutableStateOf(setOfNotNull(uiState.activeAccount?.phoneNumber))
    }
    var submitting by remember(activity.id) { mutableStateOf(false) }
    var batchResults by remember(activity.id) { mutableStateOf<List<BatchSignResult>>(emptyList()) }
    var localHint by remember(activity.id) { mutableStateOf<String?>(null) }

    // Photo / Face state
    var photoBytes by remember(activity.id) { mutableStateOf<ByteArray?>(null) }
    var photoPreview by remember(activity.id) { mutableStateOf<ImageBitmap?>(null) }
    var useProfileFace by remember(activity.id) { mutableStateOf(false) }

    // QR state
    var qrRawInput by remember(activity.id, uiState.pendingQrCodeInput) {
        mutableStateOf(uiState.pendingQrCodeInput.orEmpty())
    }
    var qrExpiryHint by remember(activity.id) { mutableStateOf<String?>(null) }

    // Code / Gesture state
    var signCode by remember(activity.id) { mutableStateOf("") }
    var codeCheckHint by remember(activity.id) { mutableStateOf<String?>(null) }

    // Location state (stored in BD-09)
    val initialPreset = remember(activity.id, activity.courseId) {
        appState.boundLocation(activity.courseId) ?: uiState.locationPresets.firstOrNull()
    }
    var attachLocation by remember(activity.id) { mutableStateOf(activity.signType == ChaoxingSignType.LOCATION) }
    var latBd09 by remember(activity.id) { mutableDoubleStateOf(initialPreset?.latitude ?: 39.908823) }
    var lonBd09 by remember(activity.id) { mutableDoubleStateOf(initialPreset?.longitude ?: 116.397470) }
    var latInputText by remember(activity.id) {
        mutableStateOf(String.format(Locale.US, "%.6f", initialPreset?.latitude ?: 39.908823))
    }
    var lonInputText by remember(activity.id) {
        mutableStateOf(String.format(Locale.US, "%.6f", initialPreset?.longitude ?: 116.397470))
    }
    fun setBd09Coordinates(newLat: Double, newLon: Double) {
        latBd09 = newLat
        lonBd09 = newLon
        latInputText = String.format(Locale.US, "%.6f", newLat)
        lonInputText = String.format(Locale.US, "%.6f", newLon)
    }
    var addressText by remember(activity.id) { mutableStateOf(initialPreset?.address ?: "") }
    var presetSaveName by remember(activity.id) { mutableStateOf("") }
    var defaultImportCoordSys by remember(activity.id) { mutableStateOf(CoordinateSystem.GCJ02) }
    var placeSearchQuery by remember(activity.id) { mutableStateOf("") }
    var placeSearchResults by remember(activity.id) { mutableStateOf<List<NominatimPlace>>(emptyList()) }
    var searchingPlaces by remember(activity.id) { mutableStateOf(false) }

    LaunchedEffect(activity.id) {
        loadingMeta = true
        metaError = null
        try {
            val fetchedDetail = runCatching {
                appState.fetchSignDetail(activity.id, activity.classId, activity.courseId)
            }.getOrNull()
            detail = fetchedDetail
            if (fetchedDetail != null) {
                if (fetchedDetail.isPositionRequired || fetchedDetail.signType == ChaoxingSignType.LOCATION) {
                    attachLocation = true
                }
                if (fetchedDetail.isFaceRequired) {
                    useProfileFace = true
                }
                val target = fetchedDetail.targetLocation
                if (target?.latitude != null && target.longitude != null && appState.boundLocation(activity.courseId) == null) {
                    setBd09Coordinates(target.latitude!!, target.longitude!!)
                }
            }
            preSignStatus = runCatching { appState.prepareSign(activity) }.getOrNull()
        } catch (e: Exception) {
            metaError = e.message ?: "读取活动详情失败"
        } finally {
            loadingMeta = false
        }
    }

    val effectiveType = detail?.signType?.takeIf { it != ChaoxingSignType.UNKNOWN } ?: activity.signType

    fun currentLocationSignInfo(): ChaoxingLocationSignInfo = ChaoxingLocationSignInfo(
        latitude = latBd09,
        longitude = lonBd09,
        address = addressText.trim().ifEmpty { "当前位置" },
        enableRandomOffset = uiState.enableLocationJitter,
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回活动列表")
                }
                Spacer(Modifier.width(4.dp))
                Column {
                    Text(
                        text = detail?.title?.ifBlank { activity.nameOne } ?: activity.nameOne.ifBlank { effectiveType.displayName },
                        style = CxTypography.title,
                        color = CxPalette.ink,
                    )
                    Text(
                        text = "${activity.courseName.ifBlank { "课程 #${activity.courseId}" }} · 活动 ID #${activity.id}",
                        style = CxTypography.mono(11.5.sp),
                        color = CxPalette.inkSecondary,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CxBadge(effectiveType.displayName, CxPalette.accent)
                when (preSignStatus) {
                    ChaoxingSignActivityStatus.READY_TO_SIGN -> CxBadge("待签到", CxPalette.amber)
                    ChaoxingSignActivityStatus.ALREADY_SIGNED -> CxBadge("已签到", CxPalette.sage)
                    ChaoxingSignActivityStatus.EXPIRED -> CxBadge("已截止", CxPalette.cinnabar)
                    null -> if (loadingMeta) CxBadge("预检中…", CxPalette.inkSecondary)
                }
            }
        }

        // Metadata chips & Sign-Out drill-down
        if (detail != null) {
            val d = detail!!
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d.isPhotoRequired) CxBadge("要求拍照", CxPalette.amber)
                if (d.isFaceRequired) CxBadge("要求人脸核验", CxPalette.cinnabar)
                if (d.isPositionRequired) CxBadge("要求位置信息", CxPalette.sage)
                if (d.isCaptchaRequired) CxBadge("需滑块验证", CxPalette.plum)
                if (d.isRefreshQRCode) CxBadge("动态刷新二维码", CxPalette.amber)
                if (d.numberCount != null && d.numberCount!! > 0) CxBadge("位数：${d.numberCount}", CxPalette.inkSecondary)
                val signOutId = d.signOutInfo.signOutId?.takeIf { it > 0L && it != activity.id }
                if (signOutId != null) {
                    OutlinedButton(
                        onClick = {
                            onNavigateToActivity(
                                activity.copy(
                                    id = signOutId,
                                    nameOne = "${activity.nameOne}（关联签退）",
                                ),
                            )
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("进入关联签退 #$signOutId", fontSize = 12.sp)
                    }
                }
            }
        }

        if (!metaError.isNullOrBlank()) {
            Text(metaError!!, style = CxTypography.caption, color = CxPalette.cinnabar)
        }
        if (!localHint.isNullOrBlank()) {
            Text(localHint!!, style = CxTypography.caption, color = CxPalette.accent)
        }

        // Type-specific sign-in form
        when (effectiveType) {
            ChaoxingSignType.PHOTO_OR_CLICK, ChaoxingSignType.UNKNOWN -> {
                CxCard(modifier = Modifier.fillMaxWidth()) {
                    Text("拍照 / 普通点击签到", style = CxTypography.headline, color = CxPalette.ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (detail?.isPhotoRequired == true) {
                            "该活动要求上传照片。可选择本地图片、粘贴剪贴板截图，或开启“使用账号人脸底库”自动提交。"
                        } else {
                            "普通点击签到无需上传图片；若教师临时要求拍照，也可以在此附带图片或人脸底库。"
                        },
                        style = CxTypography.caption,
                        color = CxPalette.inkSecondary,
                    )
                    Spacer(Modifier.height(10.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(onClick = {
                            val file = DesktopSystemTools.pickFile("选择签到照片", setOf("jpg", "jpeg", "png", "bmp", "webp"))
                            if (file != null) {
                                scope.launch(Dispatchers.IO) {
                                    val bytes = runCatching { file.readBytes() }.getOrNull()
                                    if (bytes != null) {
                                        val bmp = runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
                                        withContext(Dispatchers.Main) {
                                            photoBytes = bytes
                                            photoPreview = bmp
                                            localHint = "已选择图片：${file.name} (${bytes.size / 1024} KB)"
                                        }
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("选择本地图片")
                        }

                        OutlinedButton(onClick = {
                            val img = DesktopSystemTools.readClipboardImage()
                            if (img != null) {
                                val bytes = DesktopSystemTools.bufferedImageToPngBytes(img)
                                photoBytes = bytes
                                photoPreview = runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
                                localHint = "已从剪贴板读取图片 (${img.width}x${img.height})"
                            } else {
                                localHint = "剪贴板中未检测到图片"
                            }
                        }) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("粘贴剪贴板图片")
                        }

                        if (photoBytes != null) {
                            TextButton(onClick = {
                                photoBytes = null
                                photoPreview = null
                            }) {
                                Text("清除图片")
                            }
                        }
                    }

                    photoPreview?.let { bmp ->
                        Spacer(Modifier.height(10.dp))
                        Image(
                            bitmap = bmp,
                            contentDescription = "待上传签到照片预览",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .height(140.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, CxPalette.border, RoundedCornerShape(8.dp)),
                        )
                    }

                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = useProfileFace, onCheckedChange = { useProfileFace = it })
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.Face, contentDescription = null, tint = CxPalette.plum, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("使用服务端人脸底库照片作为凭证", style = CxTypography.body, color = CxPalette.ink)
                    }
                }
            }

            ChaoxingSignType.QR_CODE -> {
                CxCard(modifier = Modifier.fillMaxWidth()) {
                    Text("二维码签到", style = CxTypography.headline, color = CxPalette.ink)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = qrRawInput,
                        onValueChange = { qrRawInput = it },
                        label = { Text("二维码文本 / 链接（SIGNIN:... 或 https://...）") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clipText = DesktopSystemTools.readClipboardText()
                            val clipImg = DesktopSystemTools.readClipboardImage()
                            when {
                                clipImg != null -> {
                                    val decoded = DesktopQrCodeDecoder.decode(clipImg)
                                    if (decoded != null) {
                                        qrRawInput = decoded
                                        localHint = "已从剪贴板截图识别二维码"
                                    } else {
                                        localHint = "剪贴板截图中未识别到二维码"
                                    }
                                }
                                !clipText.isNullOrBlank() -> {
                                    qrRawInput = clipText
                                    localHint = "已粘贴剪贴板文本"
                                }
                                else -> localHint = "剪贴板为空"
                            }
                        }) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("粘贴剪贴板文本 / 截图识码")
                        }

                        OutlinedButton(onClick = {
                            val file = DesktopSystemTools.pickFile("选择二维码图片", setOf("png", "jpg", "jpeg", "bmp", "webp"))
                            if (file != null) {
                                scope.launch(Dispatchers.IO) {
                                    val decoded = runCatching { DesktopQrCodeDecoder.decode(file.readBytes()) }.getOrNull()
                                    withContext(Dispatchers.Main) {
                                        if (decoded != null) {
                                            qrRawInput = decoded
                                            localHint = "已从图片 ${file.name} 识别二维码"
                                        } else {
                                            localHint = "未能在 ${file.name} 中识别到二维码"
                                        }
                                    }
                                }
                            }
                        }) {
                            Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("从本地图片识码")
                        }

                        OutlinedButton(
                            enabled = qrRawInput.isNotBlank(),
                            onClick = {
                                val parsed = parseQrInputOrNull(qrRawInput)
                                val client = appState.activeClient
                                if (parsed == null) {
                                    qrExpiryHint = "无法解析二维码内容"
                                } else if (client != null) {
                                    scope.launch {
                                        val expired = ChaoxingSignExecutor.isQrCodeExpired(client, activity.id, parsed)
                                        qrExpiryHint = when (expired) {
                                            true -> "检测到该二维码已过期，请刷新后重新扫描"
                                            false -> "二维码当前有效 (aid=${parsed.aid ?: activity.id})"
                                            null -> "服务端未返回过期状态，可直接尝试提交"
                                        }
                                    }
                                }
                            },
                        ) {
                            Text("检测二维码有效期")
                        }
                    }

                    val parsedPreview = remember(qrRawInput) { parseQrInputOrNull(qrRawInput) }
                    if (parsedPreview != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "已解析：aid=${parsedPreview.aid ?: "当前活动"} · enc=${parsedPreview.enc.take(16)}…",
                            style = CxTypography.mono(11.5.sp),
                            color = CxPalette.sage,
                        )
                    }
                    if (!qrExpiryHint.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(qrExpiryHint!!, style = CxTypography.caption, color = CxPalette.amber)
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = attachLocation, onCheckedChange = { attachLocation = it })
                            Spacer(Modifier.width(6.dp))
                            Text("附带位置信息", style = CxTypography.caption)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = useProfileFace, onCheckedChange = { useProfileFace = it })
                            Spacer(Modifier.width(6.dp))
                            Text("附带人脸底库校验", style = CxTypography.caption)
                        }
                    }
                }
            }

            ChaoxingSignType.GESTURE -> {
                CxCard(modifier = Modifier.fillMaxWidth()) {
                    Text("手势签到（3×3 九宫格绘制或数字序列）", style = CxTypography.headline, color = CxPalette.ink)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GesturePatternPad(
                            pattern = signCode,
                            onPatternChange = { signCode = it },
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = signCode,
                                onValueChange = { next -> signCode = next.filter { it in '1'..'9' }.take(9) },
                                label = { Text("手势序列 (1–9)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { signCode = "" }) { Text("清空重绘") }
                                OutlinedButton(
                                    enabled = signCode.length >= 2,
                                    onClick = {
                                        val client = appState.activeClient ?: return@OutlinedButton
                                        scope.launch {
                                            codeCheckHint = runCatching {
                                                val ok = ChaoxingSignExecutor.checkSignCode(client, activity.id, signCode)
                                                if (ok) "校验通过：手势正确" else "校验失败：手势不匹配"
                                            }.getOrElse { it.message ?: "校验失败" }
                                        }
                                    },
                                ) {
                                    Text("预检手势")
                                }
                            }
                            if (!codeCheckHint.isNullOrBlank()) {
                                Text(codeCheckHint!!, style = CxTypography.caption, color = CxPalette.accent)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = attachLocation, onCheckedChange = { attachLocation = it })
                                Spacer(Modifier.width(6.dp))
                                Text("附带位置信息", style = CxTypography.caption)
                            }
                        }
                    }
                }
            }

            ChaoxingSignType.PASSWORD -> {
                CxCard(modifier = Modifier.fillMaxWidth()) {
                    Text("签到码签到", style = CxTypography.headline, color = CxPalette.ink)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = signCode,
                            onValueChange = { signCode = it.trim() },
                            label = { Text("输入教师公布的签到码") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            enabled = signCode.isNotBlank(),
                            onClick = {
                                val client = appState.activeClient ?: return@OutlinedButton
                                scope.launch {
                                    codeCheckHint = runCatching {
                                        val ok = ChaoxingSignExecutor.checkSignCode(client, activity.id, signCode)
                                        if (ok) "预检通过：签到码正确" else "预检未通过：签到码错误"
                                    }.getOrElse { it.message ?: "预检失败" }
                                }
                            },
                        ) {
                            Text("预检签到码")
                        }
                    }
                    if (!codeCheckHint.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(codeCheckHint!!, style = CxTypography.caption, color = CxPalette.accent)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = attachLocation, onCheckedChange = { attachLocation = it })
                        Spacer(Modifier.width(6.dp))
                        Text("附带位置信息", style = CxTypography.caption)
                    }
                }
            }

            ChaoxingSignType.LOCATION -> {
                // Location card rendered below unconditionally when attachLocation == true
            }
        }

        // Shared Location Picker Card (shown for LOCATION sign or when attachLocation is toggled on)
        if (effectiveType == ChaoxingSignType.LOCATION || attachLocation) {
            CxCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = CxPalette.sage, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("位置坐标与电子围栏（零密钥三坐标系）", style = CxTypography.headline, color = CxPalette.ink)
                    }
                    if (effectiveType == ChaoxingSignType.LOCATION) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(checked = useProfileFace, onCheckedChange = { useProfileFace = it })
                            Spacer(Modifier.width(6.dp))
                            Text("人脸底库校验", style = CxTypography.caption)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                LocationCanvasPicker(
                    latitudeBd09 = latBd09,
                    longitudeBd09 = lonBd09,
                    target = detail?.targetLocation,
                    onCoordinateChanged = { newLat, newLon ->
                        setBd09Coordinates(newLat, newLon)
                    },
                )

                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = latInputText,
                        onValueChange = { raw ->
                            latInputText = raw
                            raw.trim().toDoubleOrNull()?.takeIf { v -> v.isFinite() && v in -90.0..90.0 }?.let { v ->
                                latBd09 = v
                            }
                        },
                        label = { Text("BD-09 纬度") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = lonInputText,
                        onValueChange = { raw ->
                            lonInputText = raw
                            raw.trim().toDoubleOrNull()?.takeIf { v -> v.isFinite() && v in -180.0..180.0 }?.let { v ->
                                lonBd09 = v
                            }
                        },
                        label = { Text("BD-09 经度") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        label = { Text("签到地址描述") },
                        singleLine = true,
                        modifier = Modifier.weight(1.6f),
                    )
                }

                Spacer(Modifier.height(10.dp))
                // OpenStreetMap Nominatim search + clipboard coordinate import
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = placeSearchQuery,
                        onValueChange = { placeSearchQuery = it },
                        label = { Text("OpenStreetMap 地点搜索（免 API Key）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = placeSearchQuery.isNotBlank() && !searchingPlaces,
                        onClick = {
                            searchingPlaces = true
                            scope.launch {
                                try {
                                    placeSearchResults = NominatimGeocoder.search(placeSearchQuery)
                                    if (placeSearchResults.isEmpty()) {
                                        localHint = "未搜索到匹配地点，可尝试更完整的城市+地名"
                                    }
                                } catch (e: Exception) {
                                    localHint = "地点搜索失败：${e.message ?: "网络异常"}"
                                } finally {
                                    searchingPlaces = false
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (searchingPlaces) "搜索中…" else "搜索")
                    }
                    OutlinedButton(onClick = {
                        val text = DesktopSystemTools.readClipboardText().orEmpty()
                        val parsed = ExternalCoordinateParser.parse(text, assumedSystem = defaultImportCoordSys)
                        if (parsed != null) {
                            val bd = CoordinateConverter.toBd09(parsed.latitude, parsed.longitude, parsed.sourceSystem)
                            setBd09Coordinates(bd.latitude, bd.longitude)
                            if (!parsed.label.isNullOrBlank()) {
                                addressText = parsed.label!!
                            }
                            localHint = "已从剪贴板导入坐标（识别为 ${parsed.sourceSystem.displayName} -> 已转为 BD-09）"
                        } else {
                            localHint = "剪贴板中未识别到高德/百度/腾讯/OSM 坐标或经纬度"
                        }
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导入剪贴板坐标")
                    }
                }

                // Default import coordinate system selector
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("纯经纬度默认坐标系：", style = CxTypography.caption, color = CxPalette.inkSecondary)
                    for (sys in CoordinateSystem.entries) {
                        FilterChip(
                            selected = defaultImportCoordSys == sys,
                            onClick = { defaultImportCoordSys = sys },
                            label = { Text(sys.name, fontSize = 11.sp) },
                        )
                    }
                }

                if (placeSearchResults.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (place in placeSearchResults.take(5)) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = CxPalette.surfaceSecondary,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(place.name, style = CxTypography.body, fontWeight = FontWeight.Medium)
                                        Text(place.displayName, style = CxTypography.caption, color = CxPalette.inkSecondary, maxLines = 1)
                                    }
                                    TextButton(onClick = {
                                        val bd = CoordinateConverter.toBd09(place.latitude, place.longitude, place.coordinateSystem)
                                        setBd09Coordinates(bd.latitude, bd.longitude)
                                        addressText = place.displayName
                                        placeSearchResults = emptyList()
                                        localHint = "已选中地点「${place.name}」（WGS-84 自动转换 BD-09）"
                                    }) {
                                        Text("选用")
                                    }
                                }
                            }
                        }
                    }
                }

                // Quick saved location presets + bind to course
                if (uiState.locationPresets.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("常用地点预设：", style = CxTypography.caption, color = CxPalette.inkSecondary)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (preset in uiState.locationPresets) {
                            FilterChip(
                                selected = false,
                                onClick = {
                                    setBd09Coordinates(preset.latitude, preset.longitude)
                                    addressText = preset.address
                                },
                                label = { Text(preset.name, fontSize = 12.sp) },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = presetSaveName,
                        onValueChange = { presetSaveName = it },
                        label = { Text("保存当前点为常用地点名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        scope.launch {
                            val name = presetSaveName.trim().ifEmpty { addressText.trim().ifEmpty { "教学楼" } }
                            val ok = appState.addLocationPreset(name, addressText.trim().ifEmpty { name }, latBd09, lonBd09)
                            if (ok) presetSaveName = ""
                        }
                    }) {
                        Text("保存预设")
                    }
                    if (activity.courseId != 0L) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val preset = ChaoxingLocationPreset(
                                    name = presetSaveName.trim().ifEmpty { addressText.trim().ifEmpty { "课程默认点" } },
                                    address = addressText.trim().ifEmpty { "当前位置" },
                                    latitude = latBd09,
                                    longitude = lonBd09,
                                )
                                appState.bindLocationToCourse(activity.courseId, preset)
                            }
                        }) {
                            Text("绑定为本课默认点")
                        }
                    }
                }
            }
        }

        // Multi-account selector
        AccountBatchSelector(
            accounts = uiState.accounts,
            activePhone = uiState.activeAccount?.phoneNumber,
            allTags = uiState.allAccountTags,
            selectedPhones = selectedPhones,
            onSelectionChange = { selectedPhones = it },
        )

        // Submit action button
        Button(
            enabled = !submitting && selectedPhones.isNotEmpty(),
            onClick = {
                if (submitting) return@Button
                submitting = true
                batchResults = emptyList()
                localHint = null
                scope.launch {
                    try {
                        val locInfo = if (effectiveType == ChaoxingSignType.LOCATION || attachLocation) {
                            currentLocationSignInfo()
                        } else {
                            null
                        }
                        val results = appState.executeSignBatch(
                            activity = activity,
                            selectedPhones = selectedPhones,
                            ignoreBatchDelay = detail?.isRefreshQRCode == true,
                            onProgress = { progress -> batchResults = progress },
                        ) { client, _, captchaValidate, enc2 ->
                            when (effectiveType) {
                                ChaoxingSignType.PHOTO_OR_CLICK, ChaoxingSignType.UNKNOWN -> {
                                    val objectId = when {
                                        useProfileFace -> ChaoxingFaceService.fetchUserProfileFaceObjectId(client)
                                        photoBytes != null -> {
                                            val uploadBytes = withContext(Dispatchers.Default) {
                                                if (uiState.enableImageObfuscation) {
                                                    DesktopImageObfuscator.obfuscate(photoBytes!!)
                                                } else {
                                                    DesktopImageObfuscator.normalizeToJpeg(photoBytes!!)
                                                }
                                            }
                                            ChaoxingCloudDriveService.uploadImage(client, uploadBytes)
                                        }
                                        else -> null
                                    }
                                    ChaoxingSignExecutor.signPhotoOrClick(
                                        client = client,
                                        activity = activity,
                                        objectId = objectId,
                                        captchaValidate = captchaValidate,
                                        enc2 = enc2,
                                    )
                                }

                                ChaoxingSignType.QR_CODE -> {
                                    val parsed = parseQrInputOrNull(qrRawInput)
                                        ?: throw com.caochaoxing.core.model.ChaoxingError.InvalidInput("请先输入或识别有效的签到二维码")
                                    val faceId = if (useProfileFace) ChaoxingFaceService.fetchUserProfileFaceObjectId(client) else null
                                    ChaoxingSignExecutor.signQrCode(
                                        client = client,
                                        activity = activity,
                                        qrResult = parsed,
                                        location = locInfo,
                                        faceImageObjectId = faceId,
                                        captchaValidate = captchaValidate,
                                        enc2 = enc2,
                                    )
                                }

                                ChaoxingSignType.GESTURE, ChaoxingSignType.PASSWORD -> {
                                    val code = signCode.trim()
                                    if (code.isEmpty()) {
                                        throw com.caochaoxing.core.model.ChaoxingError.InvalidInput("请先绘制手势或输入签到码")
                                    }
                                    ChaoxingSignExecutor.signCodeOrGesture(
                                        client = client,
                                        activity = activity,
                                        signCode = code,
                                        location = locInfo,
                                        captchaValidate = captchaValidate,
                                        enc2 = enc2,
                                    )
                                }

                                ChaoxingSignType.LOCATION -> {
                                    val faceId = if (useProfileFace) ChaoxingFaceService.fetchUserProfileFaceObjectId(client) else null
                                    ChaoxingSignExecutor.signLocation(
                                        client = client,
                                        activity = activity,
                                        location = locInfo ?: currentLocationSignInfo(),
                                        faceImageObjectId = faceId,
                                        captchaValidate = captchaValidate,
                                        enc2 = enc2,
                                    )
                                }
                            }
                        }
                        batchResults = results
                        val okCount = results.count { it.success }
                        appState.setStatusMessage("签到提交完成：成功 $okCount / ${results.size}")
                    } catch (e: Exception) {
                        appState.setErrorMessage(e.message ?: "签到提交失败")
                    } finally {
                        submitting = false
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = CxPalette.accent),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            if (submitting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = CxPalette.onAccent)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (submitting) {
                    "正在执行错峰签到…"
                } else {
                    "提交签到（已选 ${selectedPhones.size} 个账号）"
                },
            )
        }

        // Batch results breakdown
        if (batchResults.isNotEmpty()) {
            CxCard(modifier = Modifier.fillMaxWidth()) {
                Text("执行结果明细", style = CxTypography.headline, color = CxPalette.ink)
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (item in batchResults) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (item.success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                tint = if (item.success) CxPalette.sage else CxPalette.cinnabar,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${item.accountName} (${item.phoneNumber})",
                                style = CxTypography.body,
                                fontWeight = FontWeight.Medium,
                                color = CxPalette.ink,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = item.message,
                                style = CxTypography.caption,
                                color = if (item.success) CxPalette.sage else CxPalette.cinnabar,
                            )
                        }
                        HorizontalDivider(color = CxPalette.border.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GesturePatternPad(
    pattern: String,
    onPatternChange: (String) -> Unit,
) {
    val activeNodes = remember(pattern) {
        mutableStateListOf<Int>().apply {
            pattern.mapNotNull { it.digitToIntOrNull() }.filter { it in 1..9 }.distinct().forEach { add(it) }
        }
    }
    var pointerPos by remember { mutableStateOf<Offset?>(null) }

    val accent = CxPalette.accent
    val border = CxPalette.border
    val surface = CxPalette.surfaceSecondary
    val ink = CxPalette.ink

    fun hitNode(pos: Offset, sizePx: Float): Int? {
        val cell = sizePx / 3f
        val radius = cell * 0.34f
        for (i in 0 until 9) {
            val row = i / 3
            val col = i % 3
            val center = Offset(col * cell + cell / 2f, row * cell + cell / 2f)
            if (hypot((pos.x - center.x).toDouble(), (pos.y - center.y).toDouble()) <= radius) {
                return i + 1
            }
        }
        return null
    }

    Box(
        modifier = Modifier
            .size(190.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(surface)
            .border(1.dp, border, RoundedCornerShape(10.dp)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val hit = hitNode(offset, size.width.toFloat())
                        if (hit != null && hit !in activeNodes) {
                            activeNodes.add(hit)
                            onPatternChange(activeNodes.joinToString(""))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            activeNodes.clear()
                            pointerPos = offset
                            hitNode(offset, size.width.toFloat())?.let { activeNodes.add(it) }
                            onPatternChange(activeNodes.joinToString(""))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            pointerPos = change.position
                            hitNode(change.position, size.width.toFloat())?.let { node ->
                                if (node !in activeNodes) {
                                    activeNodes.add(node)
                                    onPatternChange(activeNodes.joinToString(""))
                                }
                            }
                        },
                        onDragEnd = { pointerPos = null },
                        onDragCancel = { pointerPos = null },
                    )
                },
        ) {
            val cell = size.width / 3f
            fun centerOf(node: Int): Offset {
                val idx = node - 1
                return Offset((idx % 3) * cell + cell / 2f, (idx / 3) * cell + cell / 2f)
            }

            // Lines between selected nodes
            for (i in 0 until activeNodes.size - 1) {
                drawLine(
                    color = accent,
                    start = centerOf(activeNodes[i]),
                    end = centerOf(activeNodes[i + 1]),
                    strokeWidth = 5f,
                    cap = StrokeCap.Round,
                )
            }
            val currentPointer = pointerPos
            if (activeNodes.isNotEmpty() && currentPointer != null) {
                drawLine(
                    color = accent.copy(alpha = 0.45f),
                    start = centerOf(activeNodes.last()),
                    end = currentPointer,
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
            }

            // Draw 9 dots
            for (node in 1..9) {
                val c = centerOf(node)
                val selected = node in activeNodes
                drawCircle(
                    color = if (selected) accent.copy(alpha = 0.2f) else border.copy(alpha = 0.35f),
                    radius = cell * 0.24f,
                    center = c,
                )
                drawCircle(
                    color = if (selected) accent else ink.copy(alpha = 0.45f),
                    radius = if (selected) 8f else 5.5f,
                    center = c,
                )
                if (selected) {
                    drawCircle(color = accent, radius = cell * 0.24f, center = c, style = Stroke(width = 2f))
                }
            }
        }
    }
}
