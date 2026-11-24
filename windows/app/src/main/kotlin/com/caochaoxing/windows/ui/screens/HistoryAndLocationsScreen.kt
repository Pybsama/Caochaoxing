// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.core.crypto.CoordinateConverter
import com.caochaoxing.core.crypto.ExternalCoordinateParser
import com.caochaoxing.core.model.ChaoxingSignType
import com.caochaoxing.core.model.CoordinateSystem
import com.caochaoxing.core.service.NominatimGeocoder
import com.caochaoxing.core.service.NominatimPlace
import com.caochaoxing.windows.platform.DesktopSystemTools
import com.caochaoxing.windows.state.WindowsAppState
import com.caochaoxing.windows.ui.components.CxBadge
import com.caochaoxing.windows.ui.components.CxCard
import com.caochaoxing.windows.ui.components.CxEmptyState
import com.caochaoxing.windows.ui.theme.CxPalette
import com.caochaoxing.windows.ui.theme.CxTypography
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryAndLocationsScreen(
    appState: WindowsAppState,
) {
    val uiState by appState.uiState.collectAsState()
    val scope = rememberCoroutineScope()

    var presetName by remember { mutableStateOf("") }
    var presetAddress by remember { mutableStateOf("") }
    var presetLat by remember { mutableStateOf("") }
    var presetLon by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<NominatimPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxSize().background(CxPalette.canvas)) {
        // Left: Sign History
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("本地签到历史 (${uiState.signHistory.size})", style = CxTypography.title, color = CxPalette.ink)
                    Text("保留最近 100 条本机提交记录", style = CxTypography.caption, color = CxPalette.inkSecondary)
                }
                if (uiState.signHistory.isNotEmpty()) {
                    TextButton(onClick = { scope.launch { appState.clearSignHistory() } }) {
                        Text("清空历史", color = CxPalette.cinnabar)
                    }
                }
            }
            HorizontalDivider(color = CxPalette.border)

            if (uiState.signHistory.isEmpty()) {
                CxEmptyState(
                    icon = Icons.Outlined.History,
                    title = "暂无签到历史",
                    subtitle = "通过签到工作台提交签到后将自动记录在此",
                )
            } else {
                val fmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uiState.signHistory, key = { it.id }) { entry ->
                        val signType = ChaoxingSignType.fromOtherId(entry.signTypeRaw)
                        val timeText = entry.timestampInstant()?.let { fmt.format(it) } ?: entry.timestamp
                        val prior = entry.alreadySignedCount
                        val countsValid = entry.successCount in 0..entry.totalCount &&
                            (prior == null || prior in 0..(entry.totalCount - entry.successCount))
                        val allOk = countsValid && entry.totalCount > 0 &&
                            entry.successCount + (prior ?: 0) == entry.totalCount
                        val counts = when {
                            !countsValid -> "统计异常"
                            prior == null -> "合并统计 ${entry.successCount}/${entry.totalCount}"
                            else -> "本次接受 ${entry.successCount} · 此前已签 $prior · 共 ${entry.totalCount}"
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = CxPalette.surface,
                            border = BorderStroke(1.dp, CxPalette.border),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        CxBadge(signType.displayName, CxPalette.plum)
                                        Text(entry.activityName, style = CxTypography.headline, color = CxPalette.ink)
                                    }
                                    CxBadge(
                                        text = counts,
                                        tint = if (allOk) CxPalette.sage else CxPalette.amber,
                                    )
                                }
                                Text(
                                    text = "${entry.courseName} · $timeText · 活动 #${entry.activeId}",
                                    style = CxTypography.mono(11.5.sp),
                                    color = CxPalette.inkSecondary,
                                )
                                if (entry.summary.isNotBlank()) {
                                    Text(entry.summary, style = CxTypography.caption, color = CxPalette.ink)
                                }
                            }
                        }
                    }
                }
            }
        }

        VerticalDivider(color = CxPalette.border)

        // Right: Saved Location Presets
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("常用地点预设 (${uiState.locationPresets.size})", style = CxTypography.title, color = CxPalette.ink)
            Text(
                text = "统一以超星接口所需的 BD-09 坐标存储，支持 Nominatim 搜索和粘贴高德/百度/腾讯地图坐标自动换算。",
                style = CxTypography.caption,
                color = CxPalette.inkSecondary,
            )

            CxCard(modifier = Modifier.fillMaxWidth()) {
                Text("新增常用地点", style = CxTypography.headline, color = CxPalette.ink)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("搜索地点 (OSM)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        enabled = searchQuery.isNotBlank() && !searching,
                        onClick = {
                            searching = true
                            scope.launch {
                                try {
                                    searchResults = NominatimGeocoder.search(searchQuery)
                                } catch (e: Exception) {
                                    appState.setErrorMessage("地点搜索失败：${e.message ?: "网络异常"}")
                                } finally {
                                    searching = false
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (searching) "搜索中…" else "搜索", maxLines = 1, softWrap = false)
                    }
                    OutlinedButton(onClick = {
                        val txt = DesktopSystemTools.readClipboardText().orEmpty()
                        val parsed = ExternalCoordinateParser.parse(txt, assumedSystem = CoordinateSystem.GCJ02)
                        if (parsed != null) {
                            val bd = CoordinateConverter.toBd09(parsed.latitude, parsed.longitude, parsed.sourceSystem)
                            presetLat = String.format(Locale.US, "%.6f", bd.latitude)
                            presetLon = String.format(Locale.US, "%.6f", bd.longitude)
                            val parsedLabel = parsed.label
                            if (!parsedLabel.isNullOrBlank()) {
                                presetAddress = parsedLabel
                                if (presetName.isBlank()) presetName = parsedLabel.take(16)
                            }
                            appState.setStatusMessage("已从剪贴板识别并转换为 BD-09 坐标")
                        } else {
                            appState.setErrorMessage("剪贴板中未识别到有效坐标或地图链接")
                        }
                    }) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("粘贴坐标", maxLines = 1, softWrap = false)
                    }
                }

                if (searchResults.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    for (place in searchResults.take(4)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(place.name, style = CxTypography.caption, fontWeight = FontWeight.Medium)
                                Text(place.displayName, style = CxTypography.caption, color = CxPalette.inkTertiary, maxLines = 1)
                            }
                            TextButton(onClick = {
                                val bd = CoordinateConverter.toBd09(place.latitude, place.longitude, place.coordinateSystem)
                                presetName = place.name
                                presetAddress = place.displayName
                                presetLat = String.format(Locale.US, "%.6f", bd.latitude)
                                presetLon = String.format(Locale.US, "%.6f", bd.longitude)
                                searchResults = emptyList()
                            }) {
                                Text("填入")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = presetAddress,
                        onValueChange = { presetAddress = it },
                        label = { Text("详细地址") },
                        singleLine = true,
                        modifier = Modifier.weight(1.5f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = presetLat,
                        onValueChange = { presetLat = it },
                        label = { Text("BD-09 纬度") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = presetLon,
                        onValueChange = { presetLon = it },
                        label = { Text("BD-09 经度") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = presetLat.toDoubleOrNull() != null && presetLon.toDoubleOrNull() != null,
                        onClick = {
                            val lat = presetLat.toDoubleOrNull() ?: return@Button
                            val lon = presetLon.toDoubleOrNull() ?: return@Button
                            scope.launch {
                                val ok = appState.addLocationPreset(
                                    name = presetName.ifBlank { presetAddress.ifBlank { "常用地点" } },
                                    address = presetAddress.ifBlank { presetName.ifBlank { "自定义地点" } },
                                    latitude = lat,
                                    longitude = lon,
                                )
                                if (ok) {
                                    presetName = ""
                                    presetAddress = ""
                                    presetLat = ""
                                    presetLon = ""
                                }
                            }
                        },
                    ) {
                        Text("保存")
                    }
                }
            }

            if (uiState.locationPresets.isEmpty()) {
                CxEmptyState(
                    icon = Icons.Outlined.LocationOn,
                    title = "暂无常用地点",
                    subtitle = "在上方搜索或输入坐标保存教学楼常用位置",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(uiState.locationPresets, key = { it.id }) { preset ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = CxPalette.surface,
                            border = BorderStroke(1.dp, CxPalette.border),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(preset.name, style = CxTypography.headline, color = CxPalette.ink)
                                    Text(preset.address, style = CxTypography.caption, color = CxPalette.inkSecondary)
                                    Text(
                                        text = String.format(Locale.US, "BD-09: %.6f, %.6f", preset.latitude, preset.longitude),
                                        style = CxTypography.mono(11.5.sp),
                                        color = CxPalette.inkTertiary,
                                    )
                                }
                                IconButton(onClick = { scope.launch { appState.removeLocationPreset(preset) } }) {
                                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除地点", tint = CxPalette.cinnabar)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
