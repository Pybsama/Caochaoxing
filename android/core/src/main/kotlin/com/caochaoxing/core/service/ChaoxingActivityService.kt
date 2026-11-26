// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.service

import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingLocationTarget
import com.caochaoxing.core.model.ChaoxingQrCodeParseResult
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignDetailMetadata
import com.caochaoxing.core.model.ChaoxingSignOutInfo
import com.caochaoxing.core.model.ChaoxingSignType
import com.caochaoxing.core.network.ChaoxingHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Queries course sign-in activities, activity metadata details, and QR-derived activity descriptors.
 */
object ChaoxingActivityService {
    const val NO_SIGN_OFF_EVENT: Long = 4999L

    suspend fun fetchCourseActivities(
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
    ): List<ChaoxingSignActivity> =
        fetchCourseActivities(ChaoxingEndpoints.PRODUCTION, client, course)

    internal suspend fun fetchCourseActivities(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        course: ChaoxingCourse,
    ): List<ChaoxingSignActivity> {
        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.activeListBase,
            listOf(
                "courseId" to course.courseId.toString(),
                "classId" to course.classId.toString(),
            ),
        )
        val response = client.get(url)
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "获取课程活动失败")
        }
        return parseActivitiesJson(response.body, course)
    }

    fun parseActivitiesJson(data: ByteArray, course: ChaoxingCourse): List<ChaoxingSignActivity> =
        parseActivitiesJson(data.toString(Charsets.UTF_8), course)

    fun parseActivitiesJson(rawJson: String, course: ChaoxingCourse): List<ChaoxingSignActivity> {
        val root = parseJsonObjectOrNull(rawJson)
            ?: throw ChaoxingError.ParseFailed("课程活动 JSON 解析失败")
        val resultFlag = root.strictBooleanOrNull("result")
        val dataObj = root.objectOrNull("data")
        if (resultFlag == false || dataObj == null) {
            val errMsg = root.stringOnlyOrNull("errorMsg") ?: "解析课程活动数据失败"
            throw ChaoxingError.ParseFailed(errMsg)
        }
        val rawList = dataObj.arrayOrNull("activeList")
            ?: throw ChaoxingError.ParseFailed(root.stringOnlyOrNull("errorMsg") ?: "解析活动列表失败")

        val extElement = dataObj["ext"]
        val extContent = if (extElement is JsonObject || extElement is JsonArray) {
            chaoxingJson.encodeToString(JsonElement.serializer(), extElement)
        } else {
            "{}"
        }

        return rawList.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val type = item.numberIntOrNull("type") ?: 0
            if (type != 2 && type != 74) return@mapNotNull null

            val id = item.numberLongOrNull("id") ?: 0L
            val startTimeMs = item.numberLongOrNull("startTime")
            val endTimeMs = item.numberLongOrNull("endTime")
            val userStatus = item.numberIntOrNull("userStatus") ?: 0
            val otherId = item.stringOrNumberString("otherId") ?: ""
            val isLook = parseBoolFlag(item["isLook"])
            val activeType = item.numberIntOrNull("activeType") ?: 0
            val nameOne = item.stringOnlyOrNull("nameOne") ?: "签到活动"
            val status = item.numberIntOrNull("status") ?: 0
            val nameFour = item.stringOnlyOrNull("nameFour") ?: ""

            ChaoxingSignActivity(
                id = id,
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                userStatus = userStatus,
                otherId = otherId,
                isLook = isLook,
                type = type,
                activeType = activeType,
                nameOne = nameOne,
                status = status,
                nameFour = nameFour,
                courseId = course.courseId,
                classId = course.classId,
                courseName = course.courseName,
                extContent = extContent,
            )
        }
    }

    suspend fun fetchSignDetail(
        client: ChaoxingHttpClient,
        activeId: Long,
        fallbackClassId: Int = 0,
        fallbackCourseId: Long = 0L,
    ): ChaoxingSignDetailMetadata =
        fetchSignDetail(
            endpoints = ChaoxingEndpoints.PRODUCTION,
            client = client,
            activeId = activeId,
            fallbackClassId = fallbackClassId,
            fallbackCourseId = fallbackCourseId,
        )

    internal suspend fun fetchSignDetail(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        activeId: Long,
        fallbackClassId: Int = 0,
        fallbackCourseId: Long = 0L,
    ): ChaoxingSignDetailMetadata {
        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.signInfoBase,
            listOf("activeId" to activeId.toString()),
        )
        var response = client.get(url)
        var root = if (response.isSuccessful) parseJsonObjectOrNull(response.body) else null
        var info = root?.takeIf { it.strictBooleanOrNull("result") != false }?.objectOrNull("data")
        val needsReLogin = response.statusCode in setOf(401, 403, 302) ||
            (response.isSuccessful && (root == null || root.strictBooleanOrNull("result") == false || info == null))
        if (needsReLogin) {
            val reLoggedIn = try {
                ChaoxingAuthService.reLogin(endpoints, client)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (reLoggedIn) {
                response = client.get(url)
                root = if (response.isSuccessful) parseJsonObjectOrNull(response.body) else null
                info = root?.takeIf { it.strictBooleanOrNull("result") != false }?.objectOrNull("data")
            }
        }
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "获取签到详情失败")
        }
        val rawOtherId = info?.stringOrNumberString("otherId")
            ?: throw ChaoxingError.ParseFailed("获取签到活动信息失败")

        val otherIdStr = rawOtherId.trim { it.isWhitespace() }
        if (otherIdStr.isEmpty()) {
            throw ChaoxingError.ParseFailed("签到详情缺少活动类型")
        }
        val signType = ChaoxingSignType.fromOtherId(otherIdStr)
        val title = info.stringOnlyOrNull("name")
            ?: info.stringOnlyOrNull("title")
            ?: signType.displayName
        val classId = info.intOrStringIntOrNull("clazzid")
            ?: info.intOrStringIntOrNull("clazzId")
            ?: fallbackClassId
        var courseId = info.longOrStringLongOrNull("courseId")
            ?: info.longOrStringLongOrNull("courseid")
            ?: fallbackCourseId
        if (courseId == 0L && classId != 0) {
            courseId = ChaoxingCourseService.getCourseIdFromClassId(endpoints, client, classId) ?: 0L
        }

        val isCaptcha = parseBoolFlag(info["ifNeedVCode"])
        val isFace = parseBoolFlag(info["openCheckFaceFlag"])
        val isPosition = parseBoolFlag(info["ifopenAddress"])
        val isPhoto = parseBoolFlag(info["ifphoto"])
        val isRefreshQRCode = parseBoolFlag(info["ifrefreshewm"])
        val numberCount = info.intOrStringIntOrNull("numberCount")

        val targetLat = info.doubleOrStringDoubleOrNull("locationLatitude")
        val targetLon = info.doubleOrStringDoubleOrNull("locationLongitude")
        val rangeMeters = info.intOrStringIntOrNull("locationRange")
        val targetLocation = if (targetLat != null || targetLon != null || rangeMeters != null) {
            ChaoxingLocationTarget(
                latitude = targetLat,
                longitude = targetLon,
                rangeMeters = rangeMeters,
            )
        } else {
            null
        }

        val signInId = info.longOrStringLongOrNull("signInId")
        val signOutId = info.longOrStringLongOrNull("signOutId")
        val rawSignOutTs = info.longOrStringLongOrNull("signOutPublishTimeStamp")
        val signOutPublishTimeMs = if (rawSignOutTs == null || rawSignOutTs == -1L || rawSignOutTs == NO_SIGN_OFF_EVENT) {
            null
        } else {
            rawSignOutTs
        }

        val signOutInfo = ChaoxingSignOutInfo(
            signInId = signInId,
            signOutId = signOutId,
            signOutPublishTimeMs = signOutPublishTimeMs,
            classId = classId,
            courseId = courseId,
        )

        return ChaoxingSignDetailMetadata(
            activeId = activeId,
            signType = signType,
            title = title,
            isCaptchaRequired = isCaptcha,
            isFaceRequired = isFace,
            isPositionRequired = isPosition,
            isPhotoRequired = isPhoto,
            isRefreshQRCode = isRefreshQRCode,
            numberCount = numberCount,
            targetLocation = targetLocation,
            signOutInfo = signOutInfo,
        )
    }

    suspend fun buildActivityFromQrCode(
        client: ChaoxingHttpClient,
        qrResult: ChaoxingQrCodeParseResult,
    ): ChaoxingSignActivity =
        buildActivityFromQrCode(ChaoxingEndpoints.PRODUCTION, client, qrResult)

    suspend fun buildActivityAndDetailFromQrCode(
        client: ChaoxingHttpClient,
        qrResult: ChaoxingQrCodeParseResult,
    ): Pair<ChaoxingSignActivity, ChaoxingSignDetailMetadata> =
        buildActivityAndDetailFromQrCode(ChaoxingEndpoints.PRODUCTION, client, qrResult)

    internal suspend fun buildActivityFromQrCode(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        qrResult: ChaoxingQrCodeParseResult,
    ): ChaoxingSignActivity =
        buildActivityAndDetailFromQrCode(endpoints, client, qrResult).first

    internal suspend fun buildActivityAndDetailFromQrCode(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        qrResult: ChaoxingQrCodeParseResult,
    ): Pair<ChaoxingSignActivity, ChaoxingSignDetailMetadata> {
        val aid = qrResult.aid
            ?: throw ChaoxingError.QrCodeParseFailed("二维码中未包含签到活动 ID")
        val detail = fetchSignDetail(endpoints, client, activeId = aid)
        val activity = ChaoxingSignActivity(
            id = aid,
            startTimeMs = null,
            endTimeMs = null,
            userStatus = 0,
            otherId = detail.signType.rawValue,
            isLook = true,
            type = 2,
            activeType = 2,
            nameOne = detail.title,
            status = 1,
            nameFour = "",
            courseId = detail.signOutInfo.courseId,
            classId = detail.signOutInfo.classId,
            courseName = "二维码扫码活动",
            extContent = "{}",
        )
        return activity to detail
    }

    internal fun parseBoolFlag(raw: JsonElement?): Boolean {
        val prim = (raw as? JsonPrimitive)?.takeIf { it !is JsonNull } ?: return false
        if (!prim.isString) {
            if (prim.isJsonBoolean()) return prim.content == "true"
            val intVal = prim.content.toIntOrNull() ?: prim.content.toDoubleOrNull()?.takeIf { it.isFinite() }?.toInt()
            return intVal == 1
        }
        val trimmed = prim.content.trim { it.isWhitespace() }.lowercase(Locale.ROOT)
        return trimmed == "1" || trimmed == "true"
    }
}
