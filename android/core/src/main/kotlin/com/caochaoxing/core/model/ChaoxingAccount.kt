// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

object ChaoxingModelDefaults {
    /** Formats current UTC instant truncated to whole seconds for Swift ISO8601 JSON compatibility. */
    fun nowIso8601(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    /** Formats an [Instant] truncated to whole seconds in UTC ISO-8601 format (`yyyy-MM-dd'T'HH:mm:ss'Z'`). */
    fun formatIso8601(instant: Instant): String = instant.truncatedTo(ChronoUnit.SECONDS).toString()

    /** Parses an ISO-8601 timestamp string (`Z` or offset) into [Instant], or returns null if invalid. */
    fun parseIso8601OrNull(value: String?): Instant? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
    }

    fun newUuidString(): String = UUID.randomUUID().toString()
}

@Serializable(with = ChaoxingStoredCookieSerializer::class)
data class ChaoxingStoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val isSecure: Boolean = false,
    val expiresAt: String? = null,
    val hostOnly: Boolean = false,
) {
    fun expiresAtInstant(): Instant? = ChaoxingModelDefaults.parseIso8601OrNull(expiresAt)

    companion object {
        fun inferHostOnly(domain: String): Boolean =
            !domain.startsWith(".") && !domain.equals("chaoxing.com", ignoreCase = true)
    }
}

object ChaoxingStoredCookieSerializer : KSerializer<ChaoxingStoredCookie> {
    @Serializable
    private data class Surrogate(
        val name: String,
        val value: String,
        val domain: String,
        val path: String? = null,
        val isSecure: Boolean? = null,
        val expiresAt: String? = null,
        val hostOnly: Boolean? = null,
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ChaoxingStoredCookie) {
        encoder.encodeSerializableValue(
            Surrogate.serializer(),
            Surrogate(
                name = value.name,
                value = value.value,
                domain = value.domain,
                path = value.path,
                isSecure = value.isSecure,
                expiresAt = value.expiresAt,
                hostOnly = value.hostOnly,
            ),
        )
    }

    override fun deserialize(decoder: Decoder): ChaoxingStoredCookie {
        val surrogate = decoder.decodeSerializableValue(Surrogate.serializer())
        return ChaoxingStoredCookie(
            name = surrogate.name,
            value = surrogate.value,
            domain = surrogate.domain,
            path = surrogate.path ?: "/",
            isSecure = surrogate.isSecure ?: false,
            expiresAt = surrogate.expiresAt,
            hostOnly = surrogate.hostOnly ?: ChaoxingStoredCookie.inferHostOnly(surrogate.domain),
        )
    }
}

@Serializable
data class ChaoxingSchool(
    val fid: Int,
    val name: String,
) {
    val id: Int
        get() = fid
}

@Serializable
data class ChaoxingUserProfile(
    val uid: Int,
    val puid: Int,
    val fid: Int,
    val name: String,
    val uname: String,
    val avatarURL: String,
    val phoneNumber: String,
    val imEncryptedPassword: String,
    val clientId: String? = null,
    val schools: List<ChaoxingSchool> = emptyList(),
)

@Serializable
data class ChaoxingAccountSession(
    val phoneNumber: String,
    val encryptedPassword: String,
    val name: String = "",
    val uid: Int = 0,
    val puid: Int = 0,
    val configuredFid: Int = 0,
    val schools: List<ChaoxingSchool> = emptyList(),
    val avatarURL: String = "",
    val imEncryptedPassword: String = "",
    val clientId: String? = null,
    val deviceCode: String,
    val cookies: List<ChaoxingStoredCookie> = emptyList(),
    val isPrimary: Boolean = false,
    val lastLoginAt: String = ChaoxingModelDefaults.nowIso8601(),
    val tags: List<String> = emptyList(),
) {
    val id: String
        get() = phoneNumber
}

@Serializable
data class ChaoxingCustomClientProfile(
    val id: String = ChaoxingModelDefaults.newUuidString(),
    val name: String,
    val userAgent: String,
)

@Serializable
enum class ChaoxingClientPreset(
    val rawValue: String,
    val displayName: String,
    val userAgent: String,
) {
    @SerialName("@@default")
    DEFAULT(
        rawValue = "@@default",
        displayName = "官方通用版",
        userAgent = "Dalvik/2.1.0 (Linux; U; Android 12; SM-N9006 Build/8aba9e4.0) (schild:2d97f7b9439f21333c946878fc4d6ccb) (device:SM-N9006) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_6.7.5_android_phone_10941_314 (@Kalimdor)_68f184fd763546c1a04ab3a09b3deebb",
    ),

    @SerialName("@@official_webview")
    OFFICIAL_WEBVIEW(
        rawValue = "@@official_webview",
        displayName = "官方 WebView 版",
        userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36 (schild:2d97f7b9439f21333c946878fc4d6ccb) (device:Pixel 8) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_6.7.5_android_phone_10941_314 (@Kalimdor)_68f184fd763546c1a04ab3a09b3deebb",
    ),

    @SerialName("@@xuezaixidian")
    XUE_ZAI_XI_DIAN(
        rawValue = "@@xuezaixidian",
        displayName = "学在西电定制版",
        userAgent = "Mozilla/5.0 (Linux; Android 16; 23113RKC6C Build/BP2A.250605.031.A3; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.137 Mobile Safari/537.36 (schild:be536573b69ec1ae359e359d11f7f3e3) (device:23113RKC6C) Language/zh_CN com.chaoxing.mobile.xuezaixidian/ChaoXingStudy_1000149_6.3.7_android_phone_6005_249 (@Kalimdor)_f8777230ca1e45b2831ec7e36a9da1ea",
    );

    val id: String
        get() = rawValue

    companion object {
        fun fromRawValue(rawValue: String?): ChaoxingClientPreset? =
            entries.firstOrNull { it.rawValue == rawValue }
    }
}
