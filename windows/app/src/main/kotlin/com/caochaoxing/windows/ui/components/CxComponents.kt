// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.windows.ui.theme.CxPalette
import com.caochaoxing.windows.ui.theme.CxTypography

@Composable
fun CxCard(
    modifier: Modifier = Modifier,
    containerColor: Color = CxPalette.surface,
    borderColor: Color = CxPalette.border,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun CxBadge(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val bg = lerp(CxPalette.surface, tint, if (CxPalette.isDark) 0.22f else 0.13f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = tint,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun CxSectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = CxTypography.headline,
                color = CxPalette.ink,
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = CxTypography.caption,
                    color = CxPalette.inkSecondary,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun CxStatTile(
    label: String,
    value: String,
    tint: Color = CxPalette.ink,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = CxPalette.surfaceSecondary,
        border = BorderStroke(1.dp, CxPalette.border),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = CxTypography.caption,
                color = CxPalette.inkSecondary,
                maxLines = 1,
            )
            Text(
                text = value,
                style = CxTypography.mono(size = 16.sp, weight = FontWeight.SemiBold),
                color = tint,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun CxBanner(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = if (isError) CxPalette.cinnabar else CxPalette.sage
    val bg = lerp(CxPalette.surface, tint, if (CxPalette.isDark) 0.2f else 0.11f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = bg,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                style = CxTypography.body,
                color = CxPalette.ink,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "关闭提示",
                    tint = CxPalette.inkSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
fun CxEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CxPalette.inkTertiary,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(text = title, style = CxTypography.headline, color = CxPalette.ink)
        Spacer(Modifier.height(3.dp))
        Text(text = subtitle, style = CxTypography.caption, color = CxPalette.inkSecondary)
        if (action != null) {
            Spacer(Modifier.height(10.dp))
            action()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountBatchSelector(
    accounts: List<ChaoxingAccountSession>,
    activePhone: String?,
    allTags: List<String>,
    selectedPhones: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    CxCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "参与签到的账号 (${selectedPhones.size}/${accounts.size})",
                style = CxTypography.headline,
                color = CxPalette.ink,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { onSelectionChange(accounts.map { it.phoneNumber }.toSet()) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("全选", fontSize = 12.sp)
                }
                if (activePhone != null) {
                    TextButton(
                        onClick = { onSelectionChange(setOf(activePhone)) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("仅当前账号", fontSize = 12.sp)
                    }
                }
            }
        }

        if (allTags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (tag in allTags) {
                    val taggedPhones = accounts.filter { tag in it.tags }.map { it.phoneNumber }.toSet()
                    val allSelected = taggedPhones.isNotEmpty() && taggedPhones.all { it in selectedPhones }
                    FilterChip(
                        selected = allSelected,
                        onClick = {
                            val next = if (allSelected) {
                                selectedPhones - taggedPhones
                            } else {
                                selectedPhones + taggedPhones
                            }
                            onSelectionChange(next)
                        },
                        label = { Text("#$tag (${taggedPhones.size})", fontSize = 12.sp) },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (account in accounts) {
                val checked = account.phoneNumber in selectedPhones
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            onSelectionChange(
                                if (checked) selectedPhones - account.phoneNumber else selectedPhones + account.phoneNumber,
                            )
                        }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { isChecked ->
                            onSelectionChange(
                                if (isChecked) selectedPhones + account.phoneNumber else selectedPhones - account.phoneNumber,
                            )
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = account.name.ifBlank { account.phoneNumber },
                        style = CxTypography.body,
                        fontWeight = FontWeight.Medium,
                        color = CxPalette.ink,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = account.phoneNumber,
                        style = CxTypography.mono(11.5.sp),
                        color = CxPalette.inkSecondary,
                    )
                    if (account.phoneNumber == activePhone) {
                        Spacer(Modifier.width(6.dp))
                        CxBadge("主", CxPalette.accent)
                    }
                    if (account.tags.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = account.tags.joinToString(" ") { "#$it" },
                            style = CxTypography.caption,
                            color = CxPalette.plum,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
