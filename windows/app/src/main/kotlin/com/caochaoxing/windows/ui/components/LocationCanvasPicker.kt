// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.core.crypto.CoordinateConverter
import com.caochaoxing.core.model.ChaoxingLocationTarget
import com.caochaoxing.core.model.CoordinateSystem
import com.caochaoxing.windows.ui.theme.CxPalette
import com.caochaoxing.windows.ui.theme.CxTypography
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zero-API-key interactive coordinate grid and geofence visualizer for desktop location sign-in.
 * Displays BD-09 pin, teacher's geofence radius (if provided), Haversine distance, and allows
 * clicking or dragging to fine-tune the selected coordinates.
 */
@Composable
fun LocationCanvasPicker(
    latitudeBd09: Double,
    longitudeBd09: Double,
    target: ChaoxingLocationTarget?,
    onCoordinateChanged: (latBd09: Double, lonBd09: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val defaultSpanMeters = remember(target?.rangeMeters) {
        val r = target?.rangeMeters?.takeIf { it > 0 } ?: 120
        max(150.0, r * 3.2)
    }
    var spanMeters by remember(target?.latitude, target?.longitude, target?.rangeMeters) {
        mutableDoubleStateOf(defaultSpanMeters)
    }

    val centerLat = target?.latitude?.takeIf { it.isFinite() && it != 0.0 } ?: latitudeBd09
    val centerLon = target?.longitude?.takeIf { it.isFinite() && it != 0.0 } ?: longitudeBd09

    val distanceMeters = if (target?.latitude != null && target.longitude != null) {
        haversineDistanceMeters(latitudeBd09, longitudeBd09, target.latitude!!, target.longitude!!)
    } else {
        null
    }
    val inRange = if (distanceMeters != null && target?.rangeMeters != null && target.rangeMeters!! > 0) {
        distanceMeters <= target.rangeMeters!!
    } else {
        null
    }

    val gridColor = CxPalette.border
    val bgColor = CxPalette.surfaceSecondary
    val accentColor = CxPalette.accent
    val fenceColor = CxPalette.sage
    val warnColor = CxPalette.cinnabar
    val inkColor = CxPalette.ink

    val gcj = CoordinateConverter.convert(latitudeBd09, longitudeBd09, CoordinateSystem.BD09, CoordinateSystem.GCJ02)
    val wgs = CoordinateConverter.convert(latitudeBd09, longitudeBd09, CoordinateSystem.BD09, CoordinateSystem.WGS84)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bgColor)
                .border(1.dp, CxPalette.border, RoundedCornerShape(10.dp)),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(centerLat, centerLon, spanMeters) {
                        detectTapGestures { offset ->
                            val (newLat, newLon) = offsetToLatLon(
                                offset = offset,
                                widthPx = size.width.toFloat(),
                                heightPx = size.height.toFloat(),
                                centerLat = centerLat,
                                centerLon = centerLon,
                                spanMeters = spanMeters,
                            )
                            onCoordinateChanged(newLat, newLon)
                        }
                    }
                    .pointerInput(centerLat, centerLon, spanMeters) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val (newLat, newLon) = offsetToLatLon(
                                offset = change.position,
                                widthPx = size.width.toFloat(),
                                heightPx = size.height.toFloat(),
                                centerLat = centerLat,
                                centerLon = centerLon,
                                spanMeters = spanMeters,
                            )
                            onCoordinateChanged(newLat, newLon)
                        }
                    },
            ) {
                val w = size.width
                val h = size.height
                val minSide = min(w, h)
                val pxPerMeter = (minSide / spanMeters).toFloat()

                // Draw coordinate grid lines.
                val stepPx = max(24f, minSide / 8f)
                var x = (w / 2f) % stepPx
                while (x < w) {
                    drawLine(gridColor.copy(alpha = 0.55f), Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                    x += stepPx
                }
                var y = (h / 2f) % stepPx
                while (y < h) {
                    drawLine(gridColor.copy(alpha = 0.55f), Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    y += stepPx
                }

                // Crosshair axes at view center.
                drawLine(gridColor, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 1.2f)
                drawLine(gridColor, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1.2f)

                // Draw teacher's geofence if available.
                if (target?.latitude != null && target.longitude != null) {
                    val targetPos = latLonToOffset(
                        lat = target.latitude!!,
                        lon = target.longitude!!,
                        widthPx = w,
                        heightPx = h,
                        centerLat = centerLat,
                        centerLon = centerLon,
                        spanMeters = spanMeters,
                    )
                    val rangeM = target.rangeMeters?.takeIf { it > 0 } ?: 100
                    val radiusPx = (rangeM * pxPerMeter).coerceIn(10f, max(w, h) * 1.5f)
                    drawCircle(
                        color = fenceColor.copy(alpha = 0.14f),
                        radius = radiusPx,
                        center = targetPos,
                    )
                    drawCircle(
                        color = fenceColor,
                        radius = radiusPx,
                        center = targetPos,
                        style = Stroke(
                            width = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f),
                        ),
                    )
                    drawCircle(
                        color = fenceColor,
                        radius = 5f,
                        center = targetPos,
                    )
                }

                // Draw selected pin.
                val pinPos = latLonToOffset(
                    lat = latitudeBd09,
                    lon = longitudeBd09,
                    widthPx = w,
                    heightPx = h,
                    centerLat = centerLat,
                    centerLon = centerLon,
                    spanMeters = spanMeters,
                )
                val pinColor = if (inRange == false) warnColor else accentColor
                drawCircle(color = pinColor.copy(alpha = 0.22f), radius = 16f, center = pinPos)
                drawCircle(color = pinColor, radius = 7f, center = pinPos)
                drawCircle(color = inkColor, radius = 7f, center = pinPos, style = Stroke(width = 1.5f))
            }

            // Floating zoom & recenter controls.
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalIconButton(
                    onClick = { spanMeters = (spanMeters * 0.65).coerceAtLeast(30.0) },
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "放大")
                }
                FilledTonalIconButton(
                    onClick = { spanMeters = (spanMeters * 1.5).coerceAtMost(50_000.0) },
                ) {
                    Icon(Icons.Outlined.Remove, contentDescription = "缩小")
                }
                if (target?.latitude != null && target.longitude != null) {
                    FilledTonalIconButton(
                        onClick = {
                            onCoordinateChanged(target.latitude!!, target.longitude!!)
                            spanMeters = defaultSpanMeters
                        },
                    ) {
                        Icon(Icons.Outlined.MyLocation, contentDescription = "对齐围栏中心")
                    }
                }
            }

            // Scale & geofence status overlay.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CxBadge(
                    text = "视野约 ${spanMeters.toInt()} m · 点击或拖动选点",
                    tint = CxPalette.inkSecondary,
                )
                if (distanceMeters != null) {
                    val label = if (target?.rangeMeters != null && target.rangeMeters!! > 0) {
                        val status = if (inRange == true) "围栏内" else "超出围栏"
                        "距中心 ${distanceMeters.toInt()}m / ${target.rangeMeters}m ($status)"
                    } else {
                        "距目标 ${distanceMeters.toInt()}m"
                    }
                    CxBadge(
                        text = label,
                        tint = if (inRange == false) CxPalette.cinnabar else CxPalette.sage,
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = String.format(Locale.US, "BD-09: %.6f, %.6f", latitudeBd09, longitudeBd09),
                style = CxTypography.mono(11.5.sp),
                color = CxPalette.accent,
            )
            Text(
                text = String.format(Locale.US, "GCJ-02: %.6f, %.6f", gcj.latitude, gcj.longitude),
                style = CxTypography.mono(11.5.sp),
                color = CxPalette.inkSecondary,
            )
            Text(
                text = String.format(Locale.US, "WGS-84: %.6f, %.6f", wgs.latitude, wgs.longitude),
                style = CxTypography.mono(11.5.sp),
                color = CxPalette.inkTertiary,
            )
            Spacer(Modifier.width(4.dp))
        }
    }
}

private const val METERS_PER_LAT_DEGREE = 111_320.0

private fun metersPerLonDegree(latDeg: Double): Double =
    max(1_000.0, METERS_PER_LAT_DEGREE * cos(latDeg * PI / 180.0))

private fun latLonToOffset(
    lat: Double,
    lon: Double,
    widthPx: Float,
    heightPx: Float,
    centerLat: Double,
    centerLon: Double,
    spanMeters: Double,
): Offset {
    val minSide = min(widthPx, heightPx).toDouble()
    val pxPerMeter = minSide / spanMeters
    val dyMeters = (lat - centerLat) * METERS_PER_LAT_DEGREE
    val dxMeters = (lon - centerLon) * metersPerLonDegree(centerLat)
    val x = (widthPx / 2.0 + dxMeters * pxPerMeter).toFloat()
    val y = (heightPx / 2.0 - dyMeters * pxPerMeter).toFloat()
    return Offset(x, y)
}

private fun offsetToLatLon(
    offset: Offset,
    widthPx: Float,
    heightPx: Float,
    centerLat: Double,
    centerLon: Double,
    spanMeters: Double,
): Pair<Double, Double> {
    val minSide = min(widthPx, heightPx).toDouble()
    val metersPerPx = spanMeters / max(1.0, minSide)
    val dxMeters = (offset.x - widthPx / 2.0) * metersPerPx
    val dyMeters = (heightPx / 2.0 - offset.y) * metersPerPx
    val newLat = (centerLat + dyMeters / METERS_PER_LAT_DEGREE).coerceIn(-90.0, 90.0)
    val newLon = (centerLon + dxMeters / metersPerLonDegree(centerLat)).coerceIn(-180.0, 180.0)
    return newLat to newLon
}

internal fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * r * atan2(sqrt(a), sqrt(max(0.0, 1.0 - a)))
}
