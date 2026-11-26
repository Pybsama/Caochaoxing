// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val MAX_QR_ZOOM_RATIO = 10f

/**
 * CameraX back-camera preview paired with throttled ZXing [QrCodeAnalyzer] QR detection,
 * live torch toggle, pinch-to-zoom, tap-to-focus, double-tap zoom, and quick zoom controls.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun QrCodeCameraPreview(
    modifier: Modifier = Modifier,
    torchEnabled: Boolean = false,
    onQrDetected: (String) -> Unit,
    onCameraError: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnQrDetected by rememberUpdatedState(onQrDetected)
    val currentOnCameraError by rememberUpdatedState(onCameraError)
    val currentTorchEnabled by rememberUpdatedState(torchEnabled)

    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

    if (!hasCameraPermission) {
        LaunchedEffect(Unit) {
            currentOnCameraError("相机权限未开启，请在系统设置中允许访问相机或改用相册识码")
        }
        Box(modifier = modifier.background(Color.Black))
        return
    }

    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var currentZoomRatio by remember { mutableFloatStateOf(1f) }
    var minZoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(1f) }

    val applyZoomRatio: (Float) -> Unit = { target ->
        val camera = boundCamera
        if (camera != null) {
            val state = camera.cameraInfo.zoomState.value
            val minZ = state?.minZoomRatio ?: minZoomRatio
            val maxZ = (state?.maxZoomRatio ?: maxZoomRatio).coerceAtMost(MAX_QR_ZOOM_RATIO)
            val clamped = target.coerceIn(minZ, maxZ.coerceAtLeast(minZ))
            currentZoomRatio = clamped
            camera.cameraControl.setZoomRatio(clamped)
        }
    }

    val previewView = remember(context) {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Wire native pinch-to-zoom, single-tap focus, and double-tap quick zoom directly on PreviewView.
    DisposableEffect(previewView, boundCamera) {
        val camera = boundCamera
        if (camera == null) {
            previewView.setOnTouchListener(null)
            return@DisposableEffect onDispose {}
        }

        var pinchZoom = currentZoomRatio
        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    pinchZoom = camera.cameraInfo.zoomState.value?.zoomRatio ?: currentZoomRatio
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val state = camera.cameraInfo.zoomState.value
                    val minZ = state?.minZoomRatio ?: minZoomRatio
                    val maxZ = (state?.maxZoomRatio ?: maxZoomRatio).coerceAtMost(MAX_QR_ZOOM_RATIO)
                    val next = (pinchZoom * detector.scaleFactor).coerceIn(minZ, maxZ.coerceAtLeast(minZ))
                    pinchZoom = next
                    currentZoomRatio = next
                    camera.cameraControl.setZoomRatio(next)
                    return true
                }
            },
        )

        val tapDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                    val action = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                    )
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    camera.cameraControl.startFocusAndMetering(action)
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val state = camera.cameraInfo.zoomState.value
                    val current = state?.zoomRatio ?: currentZoomRatio
                    val minZ = state?.minZoomRatio ?: minZoomRatio
                    val maxZ = (state?.maxZoomRatio ?: maxZoomRatio).coerceAtMost(MAX_QR_ZOOM_RATIO)
                    if (maxZ <= minZ + 0.05f) return false
                    val normalZoom = 1f.coerceIn(minZ, maxZ)
                    val target = if (current < normalZoom + 0.75f) {
                        2f.coerceAtMost(maxZ)
                    } else {
                        normalZoom
                    }
                    pinchZoom = target
                    currentZoomRatio = target
                    camera.cameraControl.setZoomRatio(target)
                    return true
                }
            },
        )

        previewView.setOnTouchListener { _, event ->
            val scaled = scaleDetector.onTouchEvent(event)
            val tapped = if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
                tapDetector.onTouchEvent(event)
            } else {
                false
            }
            scaled || tapped || true
        }

        onDispose {
            previewView.setOnTouchListener(null)
        }
    }

    // Observe real hardware ZoomState so UI presets & slider stay synchronized with pinch gestures.
    DisposableEffect(boundCamera, lifecycleOwner) {
        val camera = boundCamera ?: return@DisposableEffect onDispose {}
        val zoomLiveData = camera.cameraInfo.zoomState
        val observer = Observer<ZoomState> { state ->
            val minZ = state.minZoomRatio
            val maxZ = state.maxZoomRatio.coerceAtMost(MAX_QR_ZOOM_RATIO).coerceAtLeast(minZ)
            minZoomRatio = minZ
            maxZoomRatio = maxZ
            currentZoomRatio = state.zoomRatio.coerceIn(minZ, maxZ)
        }
        zoomLiveData.observe(lifecycleOwner, observer)
        onDispose {
            zoomLiveData.removeObserver(observer)
        }
    }

    LaunchedEffect(boundCamera, torchEnabled) {
        val camera = boundCamera ?: return@LaunchedEffect
        if (camera.cameraInfo.hasFlashUnit()) {
            camera.cameraControl.enableTorch(torchEnabled)
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        var disposed = false
        var cameraProvider: ProcessCameraProvider? = null
        var imageAnalysis: ImageAnalysis? = null
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener(
            {
                if (disposed) return@addListener
                try {
                    val provider = cameraProviderFuture.get()
                    if (disposed) return@addListener
                    cameraProvider = provider

                    if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        currentOnCameraError("未找到可用后置相机，请改用相册或粘贴二维码")
                        return@addListener
                    }

                    val preview = Preview.Builder()
                        .build()
                        .apply { setSurfaceProvider(previewView.surfaceProvider) }

                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build()

                    val analyzer = QrCodeAnalyzer { decoded ->
                        mainExecutor.execute {
                            if (!disposed) {
                                currentOnQrDetected(decoded)
                            }
                        }
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply { setAnalyzer(analysisExecutor, analyzer) }
                    imageAnalysis = analysis

                    provider.unbindAll()
                    val camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    boundCamera = camera
                    if (camera.cameraInfo.hasFlashUnit()) {
                        camera.cameraControl.enableTorch(currentTorchEnabled)
                    }
                } catch (e: Exception) {
                    currentOnCameraError(
                        e.message?.takeIf { it.isNotBlank() }
                            ?: "无法启动相机预览，请改用相册或粘贴二维码",
                    )
                }
            },
            mainExecutor,
        )

        onDispose {
            disposed = true
            boundCamera = null
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        if (maxZoomRatio > minZoomRatio + 0.05f) {
            val sliderMin = minZoomRatio.coerceAtLeast(0.5f)
            val sliderMax = maxZoomRatio.coerceAtLeast(sliderMin + 0.1f)
            val presets = remember(sliderMin, sliderMax) {
                listOf(1f, 2f, 3f, 5f).filter { it in (sliderMin - 0.01f)..(sliderMax + 0.05f) }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 20.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.Black.copy(alpha = 0.65f),
                border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.18f)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            presets.forEach { preset ->
                                val selected = abs(currentZoomRatio - preset) < 0.15f
                                Surface(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .clickable { applyZoomRatio(preset.coerceIn(sliderMin, sliderMax)) },
                                    shape = CircleShape,
                                    color = if (selected) Color.White else Color.White.copy(alpha = 0.16f),
                                ) {
                                    Text(
                                        text = if (preset == preset.toInt().toFloat()) {
                                            "${preset.toInt()}x"
                                        } else {
                                            String.format(Locale.US, "%.1fx", preset)
                                        },
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                        ),
                                        color = if (selected) Color.Black else Color.White,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                        }

                        Text(
                            text = String.format(Locale.US, "%.1fx", currentZoomRatio),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = Color.White,
                            modifier = Modifier.widthIn(min = 42.dp),
                        )
                    }

                    Slider(
                        value = currentZoomRatio.coerceIn(sliderMin, sliderMax),
                        onValueChange = { applyZoomRatio(it) },
                        valueRange = sliderMin..sliderMax,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                        ),
                    )
                }
            }
        }
    }
}
