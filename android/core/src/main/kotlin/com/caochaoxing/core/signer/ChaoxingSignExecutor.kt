// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Kotlin adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

package com.caochaoxing.core.signer

import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingLocationSignInfo
import com.caochaoxing.core.model.ChaoxingQrCodeParseResult
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignActivityStatus
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.network.HttpResult
import com.caochaoxing.core.service.ChaoxingAuthService
import com.caochaoxing.core.service.ChaoxingEndpoints
import com.caochaoxing.core.service.ChaoxingFaceService
import com.caochaoxing.core.service.numberIntOrNull
import com.caochaoxing.core.service.parseJsonObjectOrNull
import com.caochaoxing.core.service.strictBooleanOrNull
import com.caochaoxing.core.service.stringOnlyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * Outcome returned by a Chaoxing sign-in submission.
 */
data class ChaoxingSignOutcome(
    val success: Boolean,
    val message: String,
)

/**
 * Executes Chaoxing pre-sign validation, analytics, QR expiry checks, and all 5 sign-in types.
 */
object ChaoxingSignExecutor {
    private val SIGNED_STATUSES = setOf(1, 2, 3, 9)
    private val LOGIN_PAGE_REGEX = Regex(
        pattern = """<title[^>]*>[^<]*登录|<input[^>]+type\s*=\s*["']password["']""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val PRIMARY_ATTEND_STATUS_REGEX = Regex(""""primaryAttend"\s*:\s*\{[^{}]*?"status"\s*:\s*(\d+)""")
    private val SIGN_STATUS_REGEX = Regex("""signstatus\s*=\s*(\d+)""")
    private val ACTIVE_PRIMARY_ID_MARKER_REGEX = Regex("""(?:id|name)\s*=\s*["']activePrimaryId["']""")
    private val ANALYSIS_CODE_REGEX = Regex("""code='\+'([a-f0-9]+)'""")

    fun preSignUrl(client: ChaoxingHttpClient, activity: ChaoxingSignActivity): String =
        preSignUrl(ChaoxingEndpoints.PRODUCTION, client, activity)

    internal fun preSignUrl(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
    ): String =
        ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.preSignBase,
            listOf(
                "courseId" to activity.courseId.toString(),
                "classId" to activity.classId.toString(),
                "activePrimaryId" to activity.id.toString(),
                "general" to "1",
                "sys" to "1",
                "ls" to "1",
                "appType" to "15",
                "uid" to client.session.puid.toString(),
                "isTeacherViewOpen" to "0",
            ),
        )

    suspend fun preSign(
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
    ): ChaoxingSignActivityStatus =
        preSign(ChaoxingEndpoints.PRODUCTION, client, activity)

    internal suspend fun preSign(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
    ): ChaoxingSignActivityStatus {
        val url = preSignUrl(endpoints, client, activity)
        var response = client.postForm(url, listOf("ext" to activity.extContent))
        if (
            response.statusCode == 403 ||
            response.statusCode == 401 ||
            response.statusCode == 302 ||
            isLoginPage(response)
        ) {
            ChaoxingAuthService.reLogin(endpoints, client)
            response = client.postForm(url, listOf("ext" to activity.extContent))
            if (
                response.statusCode == 403 ||
                response.statusCode == 401 ||
                response.statusCode == 302 ||
                isLoginPage(response)
            ) {
                throw ChaoxingError.SessionExpired()
            }
        }
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "预签到请求失败")
        }

        val body = response.bodyAsString()
        if (body.contains("校验失败，未查询到活动数据")) {
            throw ChaoxingError.NoPermission()
        }

        currentCoroutineContext().ensureActive()
        if (body.contains("下次早点哦")) {
            return ChaoxingSignActivityStatus.EXPIRED
        }

        val status = attendanceStatus(body)
        if (status != null) {
            if (status in SIGNED_STATUSES) {
                return ChaoxingSignActivityStatus.ALREADY_SIGNED
            }
            if (status != 0) {
                throw ChaoxingError.ParseFailed("无法确认预签到状态")
            }
        } else if (!ACTIVE_PRIMARY_ID_MARKER_REGEX.containsMatchIn(body)) {
            // Accept an actual activity form, never an arbitrary successful HTTP document.
            throw ChaoxingError.ParseFailed("预签到响应不是可识别的活动页面")
        }

        try {
            postAnalysis(endpoints, client, activeId = activity.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort telemetry matching Swift `try? await postAnalysis(...)`.
        }
        currentCoroutineContext().ensureActive()
        return ChaoxingSignActivityStatus.READY_TO_SIGN
    }

    private fun isLoginPage(response: HttpResult): Boolean {
        val host = response.finalUrl.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT).orEmpty()
        if (host == "passport2.chaoxing.com" || host == "passport.chaoxing.com") {
            return true
        }
        return LOGIN_PAGE_REGEX.containsMatchIn(response.bodyAsString())
    }

    private fun attendanceStatus(html: String): Int? {
        for (regex in listOf(PRIMARY_ATTEND_STATUS_REGEX, SIGN_STATUS_REGEX)) {
            val match = regex.find(html) ?: continue
            val value = match.groupValues.getOrNull(1)?.toIntOrNull()
            if (value != null) return value
        }
        return null
    }

    private suspend fun postAnalysis(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        activeId: Long,
    ) {
        val url1 = ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.analysisBase,
            listOf("aid" to activeId.toString()),
        )
        val response1 = client.get(url1)
        val text = response1.bodyAsString()
        val match = ANALYSIS_CODE_REGEX.find(text) ?: return
        val code = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return
        val url2 = ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.analysis2Base,
            listOf("code" to code),
        )
        try {
            client.get(url2)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort analysis2 beacon.
        }
    }

    suspend fun checkSignCode(
        client: ChaoxingHttpClient,
        activeId: Long,
        signCode: String,
    ): Boolean =
        checkSignCode(ChaoxingEndpoints.PRODUCTION, client, activeId, signCode)

    internal suspend fun checkSignCode(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        activeId: Long,
        signCode: String,
    ): Boolean {
        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.checkSignCodeBase,
            listOf(
                "activeId" to activeId.toString(),
                "signCode" to signCode,
            ),
        )
        val response = client.get(url)
        val json = if (response.isSuccessful) parseJsonObjectOrNull(response.body) else null
        if (json == null) {
            throw ChaoxingError.ParseFailed("校验签到码响应失败")
        }
        return json.numberIntOrNull("result") == 1
    }

    suspend fun isQrCodeExpired(
        client: ChaoxingHttpClient,
        activeId: Long,
        qrResult: ChaoxingQrCodeParseResult,
    ): Boolean? =
        isQrCodeExpired(ChaoxingEndpoints.PRODUCTION, client, activeId, qrResult)

    internal suspend fun isQrCodeExpired(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        activeId: Long,
        qrResult: ChaoxingQrCodeParseResult,
    ): Boolean? {
        val code = qrResult.code ?: return null
        val url = ChaoxingHttpClient.encodeChaoxingUrl(
            endpoints.signDetailBase,
            listOf(
                "activePrimaryId" to (qrResult.aid ?: activeId).toString(),
                "type" to "1",
                "msg" to code,
            ),
        )
        val response = try {
            client.get(url)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        if (!response.isSuccessful) return null
        val json = parseJsonObjectOrNull(response.body) ?: return null
        if (json.strictBooleanOrNull("result") == false) return null
        val isOver = json.numberIntOrNull("isOver")
        if (isOver != 0 && isOver != 1) return null
        if (isOver == 1) return true
        val signCode = json.stringOnlyOrNull("signCode")?.takeIf { it.isNotEmpty() } ?: return null
        return signCode != code
    }

    suspend fun signPhotoOrClick(
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
        objectId: String? = null,
        captchaValidate: String? = null,
        enc2: String? = null,
    ): ChaoxingSignOutcome =
        signPhotoOrClick(
            endpoints = ChaoxingEndpoints.PRODUCTION,
            client = client,
            activity = activity,
            objectId = objectId,
            captchaValidate = captchaValidate,
            enc2 = enc2,
        )

    internal suspend fun signPhotoOrClick(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
        objectId: String? = null,
        captchaValidate: String? = null,
        enc2: String? = null,
    ): ChaoxingSignOutcome {
        val items = mutableListOf(
            "activeId" to activity.id.toString(),
            "courseId" to activity.courseId.toString(),
            "uid" to client.session.puid.toString(),
            "clientip" to "",
            "useragent" to "",
            "latitude" to "-1",
            "longitude" to "-1",
            "appType" to "15",
            "fid" to client.session.configuredFid.toString(),
            "name" to client.session.name,
            "deviceCode" to client.session.deviceCode,
        )
        if (!objectId.isNullOrEmpty()) {
            items.add("objectId" to objectId)
        }
        if (captchaValidate != null) {
            items.add("validate" to captchaValidate)
        }
        if (enc2 != null) {
            items.add("enc2" to enc2)
        }
        return submitSign(endpoints, client, activity, items)
    }

    suspend fun signLocation(
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
        location: ChaoxingLocationSignInfo,
        faceImageObjectId: String? = null,
        captchaValidate: String? = null,
        enc2: String? = null,
    ): ChaoxingSignOutcome =
        signLocation(
            endpoints = ChaoxingEndpoints.PRODUCTION,
            client = client,
            activity = activity,
            location = location,
            faceImageObjectId = faceImageObjectId,
            captchaValidate = captchaValidate,
            enc2 = enc2,
        )

    internal suspend fun signLocation(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
        location: ChaoxingLocationSignInfo,
        faceImageObjectId: String? = null,
        captchaValidate: String? = null,
        enc2: String? = null,
    ): ChaoxingSignOutcome {
        val items = mutableListOf(
            "name" to client.session.name,
            "address" to location.address,
            "activeId" to activity.id.toString(),
            "courseId" to activity.courseId.toString(),
            "uid" to client.session.puid.toString(),
            "clientip" to "",
            "latitude" to location.randomizedLatitude.toString(),
            "longitude" to location.randomizedLongitude.toString(),
            "fid" to client.session.configuredFid.toString(),
            "appType" to "15",
            "ifTiJiao" to "1",
            "deviceCode" to client.session.deviceCode,
            "vpProbability" to "-1",
            "vpStrategy" to "",
        )
        if (captchaValidate != null) {
            items.add("validate" to captchaValidate)
        }
        if (enc2 != null) {
            items.add("enc2" to enc2)
        }
        items.add("locationResult" to buildLocationJson(location, includeMockData = true))
        appendFaceParameters(endpoints, items, client, activeId = activity.id, faceImageObjectId = faceImageObjectId)
        return submitSign(endpoints, client, activity, items)
    }

    suspend fun signQrCode(
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
        qrResult: ChaoxingQrCodeParseResult,
        location: ChaoxingLocationSignInfo? = null,
        faceImageObjectId: String? = null,
        captchaValidate: String? = null,
        enc2: String? = null,
    ): ChaoxingSignOutcome =
        signQrCode(
            endpoints = ChaoxingEndpoints.PRODUCTION,
            client = client,
            activity = activity,
            qrResult = qrResult,
            location = location,
            faceImageObjectId = faceImageObjectId,
            captchaValidate = captchaValidate,
            enc2 = enc2,
        )

    internal suspend fun signQrCode(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
        qrResult: ChaoxingQrCodeParseResult,
        location: ChaoxingLocationSignInfo? = null,
        faceImageObjectId: String? = null,
        captchaValidate: String? = null,
        enc2: String? = null,
    ): ChaoxingSignOutcome {
        if (activity.id <= 0L || (qrResult.aid != null && qrResult.aid != activity.id)) {
            throw ChaoxingError.InvalidInput("二维码不属于当前活动，请重新选择活动或扫描对应二维码")
        }
        val targetActiveId = activity.id

        val items = mutableListOf(
            "enc" to qrResult.enc,
            "name" to client.session.name,
            "activeId" to targetActiveId.toString(),
            "uid" to client.session.puid.toString(),
            "clientip" to "",
            "latitude" to "-1",
            "longitude" to "-1",
            "fid" to client.session.configuredFid.toString(),
            "appType" to "15",
            "deviceCode" to client.session.deviceCode,
            "vpProbability" to "-1",
            "vpStrategy" to "",
            "courseId" to activity.courseId.toString(),
        )
        if (location != null) {
            items.add("location" to buildLocationJson(location, includeMockData = false))
            items.add("locationResult" to buildLocationJson(location, includeMockData = true))
        }
        if (enc2 != null) {
            items.add("enc2" to enc2)
        }
        if (captchaValidate != null) {
            items.add("validate" to captchaValidate)
        }
        appendFaceParameters(endpoints, items, client, activeId = targetActiveId, faceImageObjectId = faceImageObjectId)
        return submitSign(endpoints, client, activity, items)
    }

    suspend fun signCodeOrGesture(
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
        signCode: String,
        location: ChaoxingLocationSignInfo? = null,
        captchaValidate: String? = null,
        enc2: String? = null,
    ): ChaoxingSignOutcome =
        signCodeOrGesture(
            endpoints = ChaoxingEndpoints.PRODUCTION,
            client = client,
            activity = activity,
            signCode = signCode,
            location = location,
            captchaValidate = captchaValidate,
            enc2 = enc2,
        )

    internal suspend fun signCodeOrGesture(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
        signCode: String,
        location: ChaoxingLocationSignInfo? = null,
        captchaValidate: String? = null,
        enc2: String? = null,
    ): ChaoxingSignOutcome {
        val latStr = location?.let { String.format(Locale.US, "%.6f", it.randomizedLatitude) } ?: "-1"
        val lonStr = location?.let { String.format(Locale.US, "%.6f", it.randomizedLongitude) } ?: "-1"

        val items = mutableListOf(
            "activeId" to activity.id.toString(),
            "courseId" to activity.courseId.toString(),
            "uid" to client.session.puid.toString(),
            "clientip" to "",
            "latitude" to latStr,
            "longitude" to lonStr,
            "appType" to "15",
            "fid" to client.session.configuredFid.toString(),
            "name" to client.session.name,
            "signCode" to signCode,
            "deviceCode" to client.session.deviceCode,
        )
        if (location != null) {
            items.add("location" to buildLocationJson(location, includeMockData = false))
            items.add("locationResult" to buildLocationJson(location, includeMockData = true))
        }
        if (enc2 != null) {
            items.add("enc2" to enc2)
        }
        if (captchaValidate != null) {
            items.add("validate" to captchaValidate)
        }
        return submitSign(endpoints, client, activity, items)
    }

    private suspend fun appendFaceParameters(
        endpoints: ChaoxingEndpoints,
        items: MutableList<Pair<String, String>>,
        client: ChaoxingHttpClient,
        activeId: Long,
        faceImageObjectId: String?,
    ) {
        if (faceImageObjectId.isNullOrEmpty()) return
        val faceEnc = ChaoxingFaceService.checkFaceResultAndGetEnc(
            endpoints = endpoints,
            client = client,
            objectId = faceImageObjectId,
            activeId = activeId,
        )
        items.add("currentFaceId" to faceImageObjectId)
        items.add("ifCFP" to "0")
        items.add("faceEnc" to faceEnc)
        items.add("faceCode" to "")
        items.add("faceEncAid" to "")
    }

    private fun buildLocationJson(
        location: ChaoxingLocationSignInfo,
        includeMockData: Boolean,
    ): String {
        val lat = String.format(Locale.US, "%.6f", location.randomizedLatitude).toDouble()
        val lon = String.format(Locale.US, "%.6f", location.randomizedLongitude).toDouble()
        return buildJsonObject {
            put("result", 1)
            put("latitude", lat)
            put("longitude", lon)
            put("address", location.address)
            if (includeMockData) {
                put("mockData", """{"strategy":0,"probability":-1}""")
            }
        }.toString()
    }

    internal suspend fun submitSign(
        endpoints: ChaoxingEndpoints,
        client: ChaoxingHttpClient,
        activity: ChaoxingSignActivity,
        queryItems: List<Pair<String, String>>,
    ): ChaoxingSignOutcome {
        val referer = preSignUrl(endpoints, client, activity)
        val response = client.postForm(
            url = endpoints.stuSignBase,
            items = queryItems,
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer" to referer,
                "X-Requested-With" to "XMLHttpRequest",
            ),
        )
        if (!response.isSuccessful) {
            throw ChaoxingError.NetworkError(statusCode = response.statusCode, message = "签到请求失败")
        }
        val result = response.bodyAsString().trim { it.isWhitespace() }

        if (result == "success") {
            return ChaoxingSignOutcome(success = true, message = "签到成功")
        }
        if (result == "success2") {
            throw ChaoxingError.SignAlreadyEnded()
        }
        if (result == "您已签到过了") {
            throw ChaoxingError.AlreadySigned()
        }
        if (result == "签到失败，请重新扫描。" || result == "fail-need-qrcode") {
            throw ChaoxingError.QrCodeExpired()
        }
        if (result.startsWith("[face]")) {
            throw ChaoxingError.FaceError(result.removePrefix("[face]"))
        }
        if (result.startsWith("errorLocation")) {
            val parts = result.split("_")
            val dist = parts.getOrNull(1)?.toDoubleOrNull()
            throw ChaoxingError.WrongPosition(distanceMeters = dist)
        }
        if (result.contains("同一设备不允许重复签到")) {
            throw ChaoxingError.DeviceDuplicated()
        }
        if (result.startsWith("validate")) {
            val enc2 = if (result.startsWith("validate_")) {
                result.removePrefix("validate_").takeIf { it.isNotEmpty() }
            } else {
                null
            }
            throw ChaoxingError.CaptchaRequired(enc2 = enc2)
        }
        if (result.startsWith("checkFace_")) {
            throw ChaoxingError.FaceError("需要人脸识别校验: $result")
        }
        throw ChaoxingError.ServerMessage(result.ifEmpty { "签到失败（空响应）" })
    }
}
