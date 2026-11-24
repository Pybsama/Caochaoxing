// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.core.model.ChaoxingGroupSignActivity
import com.caochaoxing.core.model.ChaoxingIMGroup
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.windows.state.WindowsAppState
import com.caochaoxing.windows.ui.components.CxBadge
import com.caochaoxing.windows.ui.components.CxEmptyState
import com.caochaoxing.windows.ui.theme.CxPalette
import com.caochaoxing.windows.ui.theme.CxTypography
import kotlinx.coroutines.launch

@Composable
fun GroupsScreen(
    appState: WindowsAppState,
) {
    val uiState by appState.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    var selectedGroupId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(uiState.activePhoneNumber) {
        if (uiState.activeAccount != null && uiState.groups.isEmpty()) {
            appState.refreshGroups()
        }
    }

    val selectedGroup: ChaoxingIMGroup? = remember(uiState.groups, selectedGroupId) {
        uiState.groups.firstOrNull { it.id == selectedGroupId } ?: uiState.groups.firstOrNull()
    }

    LaunchedEffect(selectedGroup?.id) {
        if (selectedGroup != null) {
            appState.loadGroupActivities(selectedGroup, forceRefresh = false)
        }
    }

    Row(modifier = Modifier.fillMaxSize().background(CxPalette.canvas)) {
        // Left: IM Groups
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(CxPalette.surface)
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("环信群聊 (${uiState.groups.size})", style = CxTypography.headline, color = CxPalette.ink)
                IconButton(onClick = { scope.launch { appState.refreshGroups(forceRefreshIm = true) } }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新群聊", modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            if (uiState.groups.isEmpty()) {
                CxEmptyState(
                    icon = Icons.Outlined.Forum,
                    title = "暂无群聊",
                    subtitle = "点击刷新获取环信群聊列表",
                    action = {
                        OutlinedButton(onClick = { scope.launch { appState.refreshGroups(forceRefreshIm = true) } }) {
                            Text("刷新群聊")
                        }
                    },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(uiState.groups, key = { it.id }) { group ->
                        val isSelected = group.id == selectedGroup?.id
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) CxPalette.accent.copy(alpha = 0.12f) else CxPalette.canvas,
                            border = BorderStroke(1.dp, if (isSelected) CxPalette.accent.copy(alpha = 0.55f) else CxPalette.border),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedGroupId = group.id },
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = group.name.ifBlank { "群聊 #${group.id}" },
                                    style = CxTypography.body,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    color = CxPalette.ink,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "ID: ${group.id}",
                                    style = CxTypography.mono(11.sp),
                                    color = CxPalette.inkTertiary,
                                )
                            }
                        }
                    }
                }
            }
        }

        VerticalDivider(color = CxPalette.border)

        // Right: Group Sign Activities
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedGroup?.name ?: "请选择群聊",
                        style = CxTypography.title,
                        color = CxPalette.ink,
                    )
                    Text(
                        text = "来自群聊消息流中解析出的课堂签到活动，点击后将跳转至统一签到工作台",
                        style = CxTypography.caption,
                        color = CxPalette.inkSecondary,
                    )
                }
                if (selectedGroup != null) {
                    IconButton(onClick = { scope.launch { appState.loadGroupActivities(selectedGroup, forceRefresh = true) } }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新群签到")
                    }
                }
            }
            HorizontalDivider(color = CxPalette.border)

            val acts = selectedGroup?.let { uiState.groupActivities[it.id] }.orEmpty()
            val loading = selectedGroup != null && selectedGroup.id in uiState.loadingGroupIds
            val failed = selectedGroup != null && selectedGroup.id in uiState.failedGroupActivityIds

            when {
                selectedGroup == null -> CxEmptyState(
                    icon = Icons.Outlined.Forum,
                    title = "未选择群聊",
                    subtitle = "请在左侧选择一个群聊查看签到活动",
                )
                loading && acts.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                failed && acts.isEmpty() -> CxEmptyState(
                    icon = Icons.Outlined.Refresh,
                    title = "加载群聊活动失败",
                    subtitle = "点击重试拉取环信消息记录",
                    action = {
                        OutlinedButton(onClick = { scope.launch { appState.loadGroupActivities(selectedGroup, true) } }) {
                            Text("重试")
                        }
                    },
                )
                acts.isEmpty() -> CxEmptyState(
                    icon = Icons.Outlined.Forum,
                    title = "该群聊暂无签到消息",
                    subtitle = "最近消息记录中未发现签到活动卡片",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(acts, key = { "${it.activeId}_${it.messageTimestampMs}" }) { item ->
                        GroupActivityCard(
                            item = item,
                            onOpen = {
                                val signActivity = ChaoxingSignActivity(
                                    id = item.activeId,
                                    startTimeMs = item.messageTimestampMs.takeIf { it > 0L },
                                    endTimeMs = null,
                                    userStatus = 0,
                                    otherId = item.signType.rawValue,
                                    isLook = false,
                                    type = 2,
                                    activeType = 2,
                                    nameOne = item.title.ifBlank { item.activeTypeName },
                                    status = 1,
                                    nameFour = item.startTimeTitle,
                                    courseId = item.courseId,
                                    classId = item.classId,
                                    courseName = item.courseName.ifBlank { selectedGroup.name },
                                    extContent = "",
                                )
                                appState.openSignWorkspace(signActivity)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupActivityCard(
    item: ChaoxingGroupSignActivity,
    onOpen: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = CxPalette.surface,
        border = BorderStroke(1.dp, CxPalette.border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !item.isUnsupportedLegacySign, onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CxBadge(item.activeTypeName.ifBlank { item.signType.displayName }, CxPalette.plum)
                    Text(
                        text = item.title.ifBlank { "群签到 #${item.activeId}" },
                        style = CxTypography.headline,
                        color = CxPalette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "${item.courseName.ifBlank { "群聊活动" }} · ${item.startTimeTitle} · ID #${item.activeId}",
                    style = CxTypography.mono(11.5.sp),
                    color = CxPalette.inkSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (item.isUnsupportedLegacySign) {
                CxBadge("旧版协议不支持", CxPalette.inkTertiary)
            } else {
                CxBadge("进入签到", CxPalette.accent)
            }
        }
    }
}
