// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.windows.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.caochaoxing.core.crypto.ChaoxingQrCodeParser
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingQrCodeParseResult
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingClientPreset
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingCustomClientProfile
import com.caochaoxing.core.model.ChaoxingEasemobIMConfig
import com.caochaoxing.core.model.ChaoxingGroupSignActivity
import com.caochaoxing.core.model.ChaoxingIMGroup
import com.caochaoxing.core.model.ChaoxingLocationPreset
import com.caochaoxing.core.model.ChaoxingModelDefaults
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.store.ChaoxingAppStateSnapshot
import com.caochaoxing.core.store.ChaoxingSignHistoryEntry
import java.time.Instant

internal const val MAX_SIGN_HISTORY = 100
internal const val MAX_BATCH_DELAY_MS = 60_000

internal fun parseQrInputOrNull(text: String): ChaoxingQrCodeParseResult? = try {
    ChaoxingQrCodeParser.parse(text)
} catch (_: ChaoxingError.QrCodeParseFailed) {
    null
}

enum class WindowsWorkspaceTab(val title: String, val shortcutDigit: Char) {
    STUDY("课程看课", '1'),
    SIGN("签到工作台", '2'),
    GROUPS("群聊签到", '3'),
    HISTORY_LOCATIONS("历史与地点", '4'),
    SETTINGS("账号与设置", '5'),
}

enum class AppThemeMode(val displayName: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色模式"),
    DARK("深色模式"),
}

@Immutable
data class PendingRadarItem(
    val course: ChaoxingCourse,
    val activity: ChaoxingSignActivity,
)

@Stable
data class CaptchaChallengeRequest(
    val id: String = ChaoxingModelDefaults.newUuidString(),
    val client: ChaoxingHttpClient,
    val refererUrl: String,
)

@Immutable
data class BatchSignResult(
    val id: String = ChaoxingModelDefaults.newUuidString(),
    val accountName: String,
    val phoneNumber: String,
    val success: Boolean,
    val message: String,
)

@Immutable
data class SessionToken(
    val generation: Long,
    val phoneNumber: String?,
)

@Immutable
data class WindowsUiState(
    val isRestoring: Boolean = false,
    val persistenceBlocked: Boolean = false,
    val storageDescription: String = "",
    val accounts: List<ChaoxingAccountSession> = emptyList(),
    val activePhoneNumber: String? = null,
    val locationPresets: List<ChaoxingLocationPreset> = emptyList(),
    val selectedClientPreset: ChaoxingClientPreset = ChaoxingClientPreset.DEFAULT,
    val customUserAgent: String = "",
    val customClientProfiles: List<ChaoxingCustomClientProfile> = emptyList(),
    val selectedCustomClientId: String? = null,
    val enableLocationJitter: Boolean = true,
    val batchSignDelayMs: Int = 1200,
    val enableImageObfuscation: Boolean = true,
    val autoSolveCaptcha: Boolean = true,
    val signHistory: List<ChaoxingSignHistoryEntry> = emptyList(),
    val courseLocationBindings: Map<String, ChaoxingLocationPreset> = emptyMap(),
    val starredCourseIds: Set<Long> = emptySet(),
    val courses: List<ChaoxingCourse> = emptyList(),
    val courseActivities: Map<String, List<ChaoxingSignActivity>> = emptyMap(),
    val loadingCourseIds: Set<String> = emptySet(),
    val failedCourseActivityIds: Set<String> = emptySet(),
    val groups: List<ChaoxingIMGroup> = emptyList(),
    val groupActivities: Map<String, List<ChaoxingGroupSignActivity>> = emptyMap(),
    val loadingGroupIds: Set<String> = emptySet(),
    val failedGroupActivityIds: Set<String> = emptySet(),
    val imConfig: ChaoxingEasemobIMConfig? = null,
    val pendingRadarItems: List<PendingRadarItem> = emptyList(),
    val isScanningRadar: Boolean = false,
    val selectedTab: WindowsWorkspaceTab = WindowsWorkspaceTab.STUDY,
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val showAddAccountDialog: Boolean = false,
    val activeSignActivity: ChaoxingSignActivity? = null,
    val pendingQrCodeInput: String? = null,
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val captchaChallenge: CaptchaChallengeRequest? = null,
) {
    val activeAccount: ChaoxingAccountSession?
        get() = activePhoneNumber?.let { phone ->
            accounts.firstOrNull { it.phoneNumber == phone }
        } ?: accounts.firstOrNull { it.isPrimary } ?: accounts.firstOrNull()

    val currentUserAgent: String
        get() {
            selectedCustomClientId?.let { id ->
                customClientProfiles.firstOrNull { it.id == id }
                    ?.userAgent
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
            val trimmed = customUserAgent.trim()
            return trimmed.ifEmpty { selectedClientPreset.userAgent }
        }

    val allAccountTags: List<String>
        get() = accounts.asSequence()
            .flatMap { it.tags.asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
            .toList()

    fun boundLocation(courseId: Long): ChaoxingLocationPreset? =
        if (courseId == 0L) null else courseLocationBindings[courseId.toString()]

    fun displayNameFor(account: ChaoxingAccountSession): String =
        account.name.trim().ifEmpty { account.phoneNumber }
}

internal fun normalizeAccountTags(tags: List<String>): List<String> =
    tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

internal fun WindowsUiState.toSnapshot(): ChaoxingAppStateSnapshot =
    ChaoxingAppStateSnapshot(
        accounts = accounts,
        locationPresets = locationPresets,
        selectedClientPreset = selectedClientPreset.rawValue,
        customUserAgent = customUserAgent,
        customClientProfiles = customClientProfiles,
        selectedCustomClientId = selectedCustomClientId,
        enableLocationJitter = enableLocationJitter,
        batchSignDelayMs = batchSignDelayMs,
        enableImageObfuscation = enableImageObfuscation,
        autoSolveCaptcha = autoSolveCaptcha,
        signHistory = signHistory,
        courseLocationBindings = courseLocationBindings,
        starredCourseIds = starredCourseIds.sorted(),
    )

internal fun mergeAppStateSnapshots(
    base: ChaoxingAppStateSnapshot,
    imported: ChaoxingAppStateSnapshot,
    currentActivePhone: String?,
): Pair<ChaoxingAppStateSnapshot, String?> {
    val mergedPresets = base.locationPresets.toMutableList()
    for (preset in imported.locationPresets) {
        val byId = mergedPresets.indexOfFirst { it.id == preset.id }
        if (byId >= 0) {
            mergedPresets[byId] = preset
        } else {
            val duplicate = mergedPresets.any {
                it.name == preset.name &&
                    it.address == preset.address &&
                    it.latitude == preset.latitude &&
                    it.longitude == preset.longitude
            }
            if (!duplicate) {
                mergedPresets.add(preset)
            }
        }
    }

    val mergedProfiles = base.customClientProfiles.toMutableList()
    val remappedProfileIds = HashMap<String, String>()
    for (profile in imported.customClientProfiles) {
        val byId = mergedProfiles.indexOfFirst { it.id == profile.id }
        if (byId >= 0) {
            mergedProfiles[byId] = profile
            remappedProfileIds[profile.id] = profile.id
        } else {
            val existing = mergedProfiles.firstOrNull {
                it.name == profile.name && it.userAgent == profile.userAgent
            }
            if (existing != null) {
                remappedProfileIds[profile.id] = existing.id
            } else {
                mergedProfiles.add(profile)
                remappedProfileIds[profile.id] = profile.id
            }
        }
    }

    val mergedBindings = base.courseLocationBindings + imported.courseLocationBindings
    val mergedStars = (base.starredCourseIds + imported.starredCourseIds).distinct().sorted()

    val existingPrimaryPhone = base.accounts.firstOrNull { it.isPrimary }?.phoneNumber
    val mergedAccounts = base.accounts.toMutableList()
    for (incoming in imported.accounts) {
        val idx = mergedAccounts.indexOfFirst { it.phoneNumber == incoming.phoneNumber }
        if (idx >= 0) {
            mergedAccounts[idx] = incoming.copy(
                tags = normalizeAccountTags(mergedAccounts[idx].tags + incoming.tags),
            )
        } else {
            mergedAccounts.add(incoming.copy(tags = normalizeAccountTags(incoming.tags)))
        }
    }
    val chosenPrimary = existingPrimaryPhone
        ?: mergedAccounts.firstOrNull { it.isPrimary }?.phoneNumber
        ?: mergedAccounts.firstOrNull()?.phoneNumber
    for (i in mergedAccounts.indices) {
        mergedAccounts[i] = mergedAccounts[i].copy(
            isPrimary = mergedAccounts[i].phoneNumber == chosenPrimary,
        )
    }
    val nextActivePhone = if (currentActivePhone != null && mergedAccounts.any { it.phoneNumber == currentActivePhone }) {
        currentActivePhone
    } else {
        chosenPrimary
    }

    val selectedPresetRaw = (ChaoxingClientPreset.fromRawValue(imported.selectedClientPreset)
        ?: ChaoxingClientPreset.DEFAULT).rawValue
    var resolvedCustomProfileId = imported.selectedCustomClientId?.let { id ->
        remappedProfileIds[id] ?: id.takeIf { candidateId -> mergedProfiles.any { it.id == candidateId } }
    }
    if (resolvedCustomProfileId != null && mergedProfiles.none { it.id == resolvedCustomProfileId }) {
        resolvedCustomProfileId = null
    }

    val mergedHistory = base.signHistory.toMutableList()
    val seenHistoryIds = mergedHistory.mapTo(HashSet()) { it.id }
    for (entry in imported.signHistory) {
        if (seenHistoryIds.add(entry.id)) {
            mergedHistory.add(entry)
        }
    }
    val sortedHistory = mergedHistory
        .sortedByDescending { it.timestampInstant() ?: Instant.EPOCH }
        .take(MAX_SIGN_HISTORY)

    val candidate = ChaoxingAppStateSnapshot(
        accounts = mergedAccounts,
        locationPresets = mergedPresets,
        selectedClientPreset = selectedPresetRaw,
        customUserAgent = imported.customUserAgent,
        customClientProfiles = mergedProfiles,
        selectedCustomClientId = resolvedCustomProfileId,
        enableLocationJitter = imported.enableLocationJitter,
        batchSignDelayMs = imported.batchSignDelayMs,
        enableImageObfuscation = imported.enableImageObfuscation,
        autoSolveCaptcha = imported.autoSolveCaptcha,
        signHistory = sortedHistory,
        courseLocationBindings = mergedBindings,
        starredCourseIds = mergedStars,
    )
    return candidate to nextActivePhone
}
