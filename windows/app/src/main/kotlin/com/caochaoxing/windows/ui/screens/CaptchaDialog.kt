// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.caochaoxing.core.crypto.ChaoxingCaptchaSolver
import com.caochaoxing.core.service.ChaoxingCaptchaChallenge
import com.caochaoxing.core.service.ChaoxingCaptchaService
import com.caochaoxing.windows.platform.DesktopCaptchaImageDecoder
import com.caochaoxing.windows.state.CaptchaChallengeRequest
import com.caochaoxing.windows.ui.components.CxCard
import com.caochaoxing.windows.ui.theme.CxPalette
import com.caochaoxing.windows.ui.theme.CxTypography
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import kotlin.math.roundToInt

/**
 * Slider captcha verification dialog.
 * Attempts local gradient-edge matching once when [autoSolve] is enabled, falls back
 * to an interactive slider with +-1px nudging, and guarantees deferred cleanup on disposal.
 */
@Composable
fun CaptchaVerificationDialog(
    request: CaptchaChallengeRequest,
    autoSolve: Boolean,
    onSuccess: (validate: String) -> Unit,
    onCancel: (reason: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reloadKey by remember(request.id) { mutableIntStateOf(0) }
    var loading by remember(request.id) { mutableStateOf(true) }
    var verifying by remember(request.id) { mutableStateOf(false) }
    var didAutoAttempt by remember(request.id) { mutableStateOf(false) }
    var hasSettled by remember(request.id) { mutableStateOf(false) }
    var statusText by remember(request.id) { mutableStateOf("正在拉取滑块验证码…") }
    var errorText by remember(request.id) { mutableStateOf<String?>(null) }

    var challenge by remember(request.id) { mutableStateOf<ChaoxingCaptchaChallenge?>(null) }
    var shadeBitmap by remember(request.id) { mutableStateOf<ImageBitmap?>(null) }
    var cutoutBitmap by remember(request.id) { mutableStateOf<ImageBitmap?>(null) }
    var shadeWidthPx by remember(request.id) { mutableIntStateOf(320) }
    var cutoutWidthPx by remember(request.id) { mutableIntStateOf(60) }
    var sliderX by remember(request.id) { mutableFloatStateOf(0f) }

    fun settleSuccess(validate: String) {
        if (hasSettled) return
        hasSettled = true
        onSuccess(validate)
    }

    fun settleCancel(reason: String) {
        if (hasSettled) return
        hasSettled = true
        onCancel(reason)
    }

    // Ensure pending batch sign coroutines never hang if the dialog leaves composition unexpectedly.
    DisposableEffect(request.id) {
        onDispose {
            if (!hasSettled) {
                hasSettled = true
                onCancel("滑块验证窗口已关闭")
            }
        }
    }

    suspend fun runAutoSolveOnCurrent(current: ChaoxingCaptchaChallenge) {
        val estX = withContext(Dispatchers.Default) {
            val shadePix = DesktopCaptchaImageDecoder.decode(current.shadeImageBytes)
            val cutoutPix = DesktopCaptchaImageDecoder.decode(current.cutoutImageBytes)
            if (shadePix != null && cutoutPix != null) {
                ChaoxingCaptchaSolver.estimateXPosition(shadePix, cutoutPix)
            } else {
                null
            }
        }
        if (hasSettled) return
        if (estX != null) {
            sliderX = estX.toFloat()
            statusText = "本地梯度匹配定位 x=$estX，正在自动校验…"
            verifying = true
            val validate = ChaoxingCaptchaService.verifyCaptcha(
                client = request.client,
                captcha = current.captcha,
                xPosition = estX,
                refererUrl = request.refererUrl,
            )
            verifying = false
            if (!validate.isNullOrBlank()) {
                settleSuccess(validate)
            } else {
                statusText = "自动校验未通过，请手动拖动或微调滑块对齐缺口"
            }
        } else {
            statusText = "自动识别未命中明确峰值，请手动拖动滑块对齐缺口"
        }
    }

    LaunchedEffect(request.id, reloadKey) {
        loading = true
        verifying = false
        errorText = null
        statusText = "正在拉取滑块验证码…"
        sliderX = 0f
        try {
            val fetched = ChaoxingCaptchaService.fetchCaptcha(request.client, request.refererUrl)
            if (hasSettled) return@LaunchedEffect
            challenge = fetched
            val (shadeImg, cutoutImg) = withContext(Dispatchers.Default) {
                val bg = SkiaImage.makeFromEncoded(fetched.shadeImageBytes).toComposeImageBitmap()
                val cut = SkiaImage.makeFromEncoded(fetched.cutoutImageBytes).toComposeImageBitmap()
                bg to cut
            }
            if (hasSettled) return@LaunchedEffect
            shadeBitmap = shadeImg
            cutoutBitmap = cutoutImg
            shadeWidthPx = shadeImg.width.coerceAtLeast(1)
            cutoutWidthPx = cutoutImg.width.coerceAtLeast(1)
            loading = false

            if (autoSolve && !didAutoAttempt) {
                didAutoAttempt = true
                runAutoSolveOnCurrent(fetched)
            } else {
                statusText = "请拖动滑块对齐缺口"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loading = false
            verifying = false
            errorText = e.message ?: "获取验证码失败"
        }
    }

    Dialog(onDismissRequest = { if (!verifying) settleCancel("用户取消了滑块验证") }) {
        CxCard(modifier = Modifier.widthIn(min = 390.dp, max = 450.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("安全滑块验证", style = CxTypography.headline, color = CxPalette.ink)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = !loading && !verifying && challenge != null,
                        onClick = {
                            val current = challenge ?: return@TextButton
                            scope.launch {
                                try {
                                    errorText = null
                                    runAutoSolveOnCurrent(current)
                                } catch (e: Exception) {
                                    verifying = false
                                    errorText = e.message ?: "自动识别失败"
                                }
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("自动识别", fontSize = 12.sp)
                    }
                    IconButton(
                        onClick = { reloadKey += 1 },
                        enabled = !loading && !verifying,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "换一张验证码")
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(text = statusText, style = CxTypography.caption, color = CxPalette.inkSecondary)

            if (!errorText.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(text = errorText!!, style = CxTypography.caption, color = CxPalette.cinnabar)
            }

            Spacer(Modifier.height(12.dp))
            val bg = shadeBitmap
            val cut = cutoutBitmap
            if (loading || bg == null || cut == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CxPalette.surfaceSecondary),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else {
                val maxSliderX = (shadeWidthPx - cutoutWidthPx).coerceAtLeast(1).toFloat()
                val bgAspect = (bg.width.toFloat() / bg.height.coerceAtLeast(1).toFloat()).coerceIn(0.8f, 4.0f)
                val density = LocalDensity.current
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(bgAspect)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, CxPalette.border, RoundedCornerShape(8.dp)),
                ) {
                    val containerWidthPx = constraints.maxWidth.toFloat()
                    val scale = containerWidthPx / shadeWidthPx.toFloat()
                    val cutoutOffsetPx = (sliderX * scale).roundToInt()
                    val cutWidthDp = with(density) { (cut.width * scale).toDp() }
                    val cutHeightDp = with(density) { (cut.height * scale).toDp() }

                    Image(
                        bitmap = bg,
                        contentDescription = "验证码背景图",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxWidth().aspectRatio(bgAspect),
                    )
                    Image(
                        bitmap = cut,
                        contentDescription = "滑块拼图",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .offset { IntOffset(cutoutOffsetPx, 0) }
                            .size(width = cutWidthDp, height = cutHeightDp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Slider(
                    value = sliderX.coerceIn(0f, maxSliderX),
                    onValueChange = { sliderX = it },
                    valueRange = 0f..maxSliderX,
                    enabled = !verifying,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "位置 X: ${sliderX.roundToInt()} / ${maxSliderX.roundToInt()} px",
                        style = CxTypography.mono(),
                        color = CxPalette.inkSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(
                            enabled = !verifying && sliderX > 0f,
                            onClick = { sliderX = (sliderX - 1f).coerceIn(0f, maxSliderX) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("-1px", fontSize = 11.5.sp)
                        }
                        OutlinedButton(
                            enabled = !verifying && sliderX < maxSliderX,
                            onClick = { sliderX = (sliderX + 1f).coerceIn(0f, maxSliderX) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("+1px", fontSize = 11.5.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = { settleCancel("用户取消了滑块验证") },
                    enabled = !verifying,
                ) {
                    Text("取消")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !loading && !verifying && challenge != null,
                    onClick = {
                        val currentChallenge = challenge ?: return@Button
                        verifying = true
                        errorText = null
                        scope.launch {
                            try {
                                val validate = ChaoxingCaptchaService.verifyCaptcha(
                                    client = request.client,
                                    captcha = currentChallenge.captcha,
                                    xPosition = sliderX.roundToInt(),
                                    refererUrl = request.refererUrl,
                                )
                                verifying = false
                                if (!validate.isNullOrBlank()) {
                                    settleSuccess(validate)
                                } else {
                                    errorText = "拼图未对齐，正在刷新验证码…"
                                    reloadKey += 1
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                verifying = false
                                errorText = e.message ?: "验证码提交失败"
                            }
                        }
                    },
                ) {
                    if (verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = CxPalette.onAccent,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (verifying) "校验中…" else "提交验证")
                }
            }
        }
    }
}
