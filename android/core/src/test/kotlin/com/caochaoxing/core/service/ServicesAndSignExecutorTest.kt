// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.core.service

import com.caochaoxing.core.crypto.ChaoxingCrypto
import com.caochaoxing.core.model.ChaoxingAccountSession
import com.caochaoxing.core.model.ChaoxingCourse
import com.caochaoxing.core.model.ChaoxingEasemobIMConfig
import com.caochaoxing.core.model.ChaoxingError
import com.caochaoxing.core.model.ChaoxingIMGroup
import com.caochaoxing.core.model.ChaoxingLocationSignInfo
import com.caochaoxing.core.model.ChaoxingQrCodeParseResult
import com.caochaoxing.core.model.ChaoxingSignActivity
import com.caochaoxing.core.model.ChaoxingSignActivityStatus
import com.caochaoxing.core.model.ChaoxingSignType
import com.caochaoxing.core.model.ChaoxingStoredCookie
import com.caochaoxing.core.model.CoordinateSystem
import com.caochaoxing.core.network.ChaoxingHttpClient
import com.caochaoxing.core.signer.ChaoxingSignExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ServicesAndSignExecutorTest {
    @Test
    fun `school switch clears cached cloud token`() {
        val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
        try {
            client.storageCloudToken = "synthetic-old-school"
            client.updateConfiguredFid(4004)
            assertNull(client.storageCloudToken)
        } finally { client.invalidate() }
    }

    @Test
    fun `course fetch reauthenticates at most once after 401 and expired retry`() = runBlocking {
        val courseRequests = AtomicInteger()
        val loginRequests = AtomicInteger()
        withServer(handler = { request ->
            when (request.requestUrl?.encodedPath) {
                "/mycourse/backclazzdata" -> if (courseRequests.incrementAndGet() == 1) {
                    MockResponse().setResponseCode(401)
                } else MockResponse().setBody("""{"result":0}""")
                "/fanyalogin" -> {
                    loginRequests.incrementAndGet()
                    MockResponse().setBody("""{"status":true}""")
                }
                "/apis/login/userLogin4Uname.do" -> MockResponse().setBody(
                    """{"msg":{"uid":1001,"puid":2002,"fid":3003,"name":"Synthetic"}}"""
                )
                else -> MockResponse().setResponseCode(404)
            }
        }) { _, endpoints, _ ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                assertSuspendsThrows<ChaoxingError.SessionExpired> {
                    ChaoxingCourseService.fetchCourses(endpoints, client)
                }
                assertEquals(1, loginRequests.get())
                assertEquals(2, courseRequests.get())
            } finally { client.invalidate() }
        }
    }

    private fun syntheticSession(
        phoneNumber: String = "13800000001",
        encryptedPassword: String = ChaoxingCrypto.encryptLoginField("Pass1234"),
        name: String = "A+B",
        uid: Int = 1001,
        puid: Int = 2002,
        configuredFid: Int = 3003,
        imEncryptedPassword: String = "plain-im-pass",
        cookies: List<ChaoxingStoredCookie> = emptyList(),
    ): ChaoxingAccountSession =
        ChaoxingAccountSession(
            phoneNumber = phoneNumber,
            encryptedPassword = encryptedPassword,
            name = name,
            uid = uid,
            puid = puid,
            configuredFid = configuredFid,
            imEncryptedPassword = imEncryptedPassword,
            deviceCode = "SYNTHETIC_DEVICE_CODE",
            cookies = cookies,
        )

    private fun syntheticCourse(): ChaoxingCourse =
        ChaoxingCourse(
            courseName = "软件工程",
            teacherName = "王老师",
            courseId = 112233L,
            classId = 445566,
            imageURL = "https://example.invalid/cover.jpg",
        )

    private fun syntheticActivity(
        id: Long = 9001L,
        courseId: Long = 112233L,
        classId: Int = 445566,
        otherId: String = "2",
    ): ChaoxingSignActivity =
        ChaoxingSignActivity(
            id = id,
            startTimeMs = null,
            endTimeMs = null,
            userStatus = 0,
            otherId = otherId,
            isLook = true,
            type = 2,
            activeType = 2,
            nameOne = "课堂签到",
            status = 1,
            nameFour = "",
            courseId = courseId,
            classId = classId,
            courseName = "软件工程",
            extContent = """{"source":"test"}""",
        )

    private suspend inline fun <reified T : Throwable> assertSuspendsThrows(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (thrown: Throwable) {
            if (thrown is T) return thrown
            throw AssertionError(
                "Expected ${T::class.java.simpleName}, got ${thrown::class.java.name}: ${thrown.message}",
                thrown,
            )
        }
        throw AssertionError("Expected ${T::class.java.simpleName} to be thrown, but completed normally")
    }

    private inline fun withServer(
        crossinline handler: (RecordedRequest) -> MockResponse,
        block: (MockWebServer, ChaoxingEndpoints, CopyOnWriteArrayList<RecordedRequestSummary>) -> Unit,
    ) {
        val server = MockWebServer()
        val recorded = CopyOnWriteArrayList<RecordedRequestSummary>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val bodyText = request.body.readUtf8()
                recorded.add(
                    RecordedRequestSummary(
                        method = request.method.orEmpty(),
                        path = request.requestUrl?.encodedPath.orEmpty(),
                        rawPath = request.path.orEmpty(),
                        queryPairs = (0 until (request.requestUrl?.querySize ?: 0)).map { idx ->
                            request.requestUrl!!.queryParameterName(idx) to
                                request.requestUrl!!.queryParameterValue(idx).orEmpty()
                        },
                        headers = request.headers,
                        body = bodyText,
                    ),
                )
                return handler(request)
            }
        }
        server.start()
        try {
            val endpoints = ChaoxingEndpoints.forTesting(server.url("/").toString())
            block(server, endpoints, recorded)
        } finally {
            server.shutdown()
        }
    }

    private data class RecordedRequestSummary(
        val method: String,
        val path: String,
        val rawPath: String,
        val queryPairs: List<Pair<String, String>>,
        val headers: Headers,
        val body: String,
    ) {
        fun queryValue(name: String): String? =
            queryPairs.firstOrNull { it.first == name }?.second

        fun formValue(name: String): String? {
            if (body.isEmpty()) return null
            return body.split("&").mapNotNull { segment ->
                val eq = segment.indexOf('=')
                if (eq < 0) null
                else URLDecoder.decode(segment.substring(0, eq), "UTF-8") to
                    URLDecoder.decode(segment.substring(eq + 1), "UTF-8")
            }.firstOrNull { it.first == name }?.second
        }
    }

    // -------------------------------------------------------------------------
    // Required Case 1: preSign 302 redirect to login page -> auto reLogin -> retry
    // -------------------------------------------------------------------------

    @Test
    fun `preSign encountering 302 redirect to login page triggers reLogin and retries successfully`() = runBlocking {
        val preSignCount = AtomicInteger(0)
        val loginCount = AtomicInteger(0)

        withServer(
            handler = { req ->
                when (req.requestUrl?.encodedPath) {
                    "/newsign/preSign" -> {
                        if (preSignCount.incrementAndGet() == 1) {
                            MockResponse()
                                .setResponseCode(302)
                                .addHeader("Location", "/login")
                        } else {
                            MockResponse()
                                .setResponseCode(200)
                                .setBody("""<html><input id="activePrimaryId" value="9001" /><script>var signstatus=0;</script></html>""")
                        }
                    }
                    "/login" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("<html><head><title>用户登录</title></head><body>请登录</body></html>")
                    "/fanyalogin" -> {
                        loginCount.incrementAndGet()
                        MockResponse()
                            .setResponseCode(200)
                            .addHeader("Set-Cookie", "sid=fresh-sid; Path=/")
                            .setBody("""{"status":true}""")
                    }
                    "/apis/login/userLogin4Uname.do" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("""{"msg":{"uid":1001,"puid":2002,"fid":3003,"name":"A+B","schoolname":"示例大学"}}""")
                    "/pptSign/analysis" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("var x='1';code='+'deadbeef';")
                    "/pptSign/analysis2" -> MockResponse()
                        .setResponseCode(200)
                        .setBody("ok")
                    else -> MockResponse().setResponseCode(404)
                }
            },
        ) { _, endpoints, recorded ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            client.storageCloudToken = "stale-cloud-token"
            try {
                val status = ChaoxingSignExecutor.preSign(
                    endpoints = endpoints,
                    client = client,
                    activity = syntheticActivity(),
                )
                assertEquals(ChaoxingSignActivityStatus.READY_TO_SIGN, status)
                assertEquals(2, preSignCount.get())
                assertEquals(1, loginCount.get())
                assertNull(client.storageCloudToken)
                assertTrue(recorded.any { it.path == "/login" })
                assertTrue(recorded.any { it.path == "/pptSign/analysis" && it.queryValue("aid") == "9001" })
                assertTrue(recorded.any { it.path == "/pptSign/analysis2" && it.queryValue("code") == "deadbeef" })
            } finally {
                client.invalidate()
            }
        }
    }

    @Test
    fun `preSign handles alreadySigned expired noPermission invalidHtml and double-401 sessionExpired`() = runBlocking {
        withServer(
            handler = { req ->
                when (req.requestUrl?.queryParameter("activePrimaryId")) {
                    "1" -> MockResponse().setBody("""{"primaryAttend":{"status":1}}""")
                    "2" -> MockResponse().setBody("<html>下次早点哦</html>")
                    "3" -> MockResponse().setBody("校验失败，未查询到活动数据")
                    "4" -> MockResponse().setBody("<html>maintenance</html>")
                    else -> MockResponse().setResponseCode(401)
                }
            },
        ) { _, endpoints, _ ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                assertEquals(
                    ChaoxingSignActivityStatus.ALREADY_SIGNED,
                    ChaoxingSignExecutor.preSign(endpoints, client, syntheticActivity(id = 1L)),
                )
                assertEquals(
                    ChaoxingSignActivityStatus.EXPIRED,
                    ChaoxingSignExecutor.preSign(endpoints, client, syntheticActivity(id = 2L)),
                )
                assertSuspendsThrows<ChaoxingError.NoPermission> {
                    ChaoxingSignExecutor.preSign(endpoints, client, syntheticActivity(id = 3L))
                }
                assertSuspendsThrows<ChaoxingError.ParseFailed> {
                    ChaoxingSignExecutor.preSign(endpoints, client, syntheticActivity(id = 4L))
                }
                // activePrimaryId=5 returns 401 -> reLogin fails because /fanyalogin also returns 401 -> NetworkError
                assertSuspendsThrows<ChaoxingError.NetworkError> {
                    ChaoxingSignExecutor.preSign(endpoints, client, syntheticActivity(id = 5L))
                }
            } finally {
                client.invalidate()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Required Case 2: parseActivitiesJson & fetchSignDetail boolean compatibility
    // -------------------------------------------------------------------------

    @Test
    fun `parseActivitiesJson and fetchSignDetail support Bool Int 1 String 1 and String true flags`() = runBlocking {
        val course = syntheticCourse()
        val activitiesJson = """
            {
              "result": true,
              "data": {
                "ext": {"ver": 1},
                "activeList": [
                  {"id": 10, "type": 2, "otherId": 4, "isLook": true, "userStatus": 0, "status": 1, "nameOne": "位置签到"},
                  {"id": 11, "type": 74, "otherId": "2", "isLook": 1, "userStatus": 1, "status": 1, "nameOne": "扫码签到"},
                  {"id": 12, "type": 2, "otherId": "0", "isLook": "1", "userStatus": 0, "status": 1, "nameOne": "拍照签到"},
                  {"id": 13, "type": 2, "otherId": "3", "isLook": "TRUE", "userStatus": 0, "status": 1, "nameOne": "手势签到"},
                  {"id": 14, "type": 2, "otherId": "5", "isLook": "false", "userStatus": 0, "status": 1, "nameOne": "签到码"},
                  {"id": 99, "type": 11, "otherId": "2", "isLook": true, "userStatus": 0, "status": 1, "nameOne": "讨论"}
                ]
              }
            }
        """.trimIndent()

        val parsed = ChaoxingActivityService.parseActivitiesJson(activitiesJson, course)
        assertEquals(5, parsed.size)
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L), parsed.map { it.id })
        assertTrue(parsed[0].isLook)
        assertTrue(parsed[1].isLook)
        assertTrue(parsed[2].isLook)
        assertTrue(parsed[3].isLook)
        assertFalse(parsed[4].isLook)
        assertEquals(ChaoxingSignType.LOCATION, parsed[0].signType)
        assertTrue(parsed[1].isAlreadySignedByStatus)

        // ext non-object fallback matches Swift
        for (extLiteral in listOf("null", "42", "\"encoded\"", "true")) {
            val scalarExt = ChaoxingActivityService.parseActivitiesJson(
                """{"data":{"activeList":[{"id":1,"type":2,"otherId":"0"}],"ext":$extLiteral}}""",
                course,
            )
            assertEquals("{}", scalarExt.first().extContent)
        }

        // fetchSignDetail with Bool, Int(1), "1", "true" variants
        withServer(
            handler = { req ->
                when (req.requestUrl?.queryParameter("activeId")) {
                    "101" -> MockResponse().setBody(
                        """
                        {
                          "result": true,
                          "data": {
                            "otherId": 4,
                            "name": "布尔标记测试",
                            "clazzid": 445566,
                            "courseId": 112233,
                            "ifNeedVCode": true,
                            "openCheckFaceFlag": 1,
                            "ifopenAddress": "1",
                            "ifphoto": "true",
                            "ifrefreshewm": "TRUE",
                            "locationLatitude": 39.908,
                            "locationLongitude": 116.397,
                            "locationRange": 150,
                            "signOutPublishTimeStamp": 4999
                          }
                        }
                        """.trimIndent(),
                    )
                    "102" -> MockResponse().setBody(
                        """
                        {
                          "result": true,
                          "data": {
                            "otherId": "2",
                            "clazzid": 445566,
                            "courseId": 112233,
                            "ifNeedVCode": false,
                            "openCheckFaceFlag": 0,
                            "ifopenAddress": "0",
                            "ifphoto": "false",
                            "ifrefreshewm": null,
                            "signOutId": 888,
                            "signOutPublishTimeStamp": 1700000000000
                          }
                        }
                        """.trimIndent(),
                    )
                    else -> MockResponse().setBody("""{"result":false,"data":{}}""")
                }
            },
        ) { _, endpoints, _ ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                val allTrue = ChaoxingActivityService.fetchSignDetail(endpoints, client, activeId = 101L)
                assertTrue(allTrue.isCaptchaRequired)
                assertTrue(allTrue.isFaceRequired)
                assertTrue(allTrue.isPositionRequired)
                assertTrue(allTrue.isPhotoRequired)
                assertTrue(allTrue.isRefreshQRCode)
                assertEquals(150, allTrue.targetLocation?.rangeMeters)
                assertNull(allTrue.signOutInfo.signOutPublishTimeMs)

                val allFalse = ChaoxingActivityService.fetchSignDetail(endpoints, client, activeId = 102L)
                assertFalse(allFalse.isCaptchaRequired)
                assertFalse(allFalse.isFaceRequired)
                assertFalse(allFalse.isPositionRequired)
                assertFalse(allFalse.isPhotoRequired)
                assertFalse(allFalse.isRefreshQRCode)
                assertEquals(888L, allFalse.signOutInfo.signOutId)
                assertEquals(1700000000000L, allFalse.signOutInfo.signOutPublishTimeMs)

                val qrActivity = ChaoxingActivityService.buildActivityFromQrCode(
                    endpoints,
                    client,
                    ChaoxingQrCodeParseResult(aid = 102L, code = "qr1", enc = "enc1"),
                )
                assertEquals(102L, qrActivity.id)
                assertEquals(ChaoxingSignType.QR_CODE, qrActivity.signType)
                assertEquals(112233L, qrActivity.courseId)

                assertSuspendsThrows<ChaoxingError.ParseFailed> {
                    ChaoxingActivityService.fetchSignDetail(endpoints, client, activeId = 999L)
                }
            } finally {
                client.invalidate()
            }
        }
    }

    @Test
    fun `fetchSignDetail re-authenticates once on 403 and parses string-typed IDs`() = runBlocking {
        val detailAttempts = AtomicInteger(0)
        val loginCalls = AtomicInteger(0)
        withServer(
            handler = { req ->
                when (req.requestUrl?.encodedPath) {
                    "/v2/apis/active/getPPTActiveInfo" -> {
                        if (detailAttempts.getAndIncrement() == 0) {
                            MockResponse().setResponseCode(403).setBody("Forbidden")
                        } else {
                            MockResponse().setBody(
                                """
                                {
                                  "result": true,
                                  "data": {
                                    "otherId": "2",
                                    "name": "动态二维码签到",
                                    "clazzid": "654321",
                                    "courseId": "987654",
                                    "ifopenAddress": "1",
                                    "ifrefreshewm": "1",
                                    "locationLatitude": "39.908722",
                                    "locationLongitude": "116.397499",
                                    "locationRange": "200"
                                  }
                                }
                                """.trimIndent(),
                            )
                        }
                    }

                    "/fanyalogin" -> {
                        loginCalls.incrementAndGet()
                        MockResponse()
                            .addHeader("Set-Cookie", "UID=1001; Path=/")
                            .setBody("""{"status":true}""")
                    }

                    "/apis/login/userLogin4Uname.do" -> {
                        MockResponse().setBody(
                            """{"msg":{"uid":1001,"puid":2002,"fid":3003,"name":"测试用户","uname":"u","pic":"","schoolname":"示例学校"}}""",
                        )
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            },
        ) { _, endpoints, _ ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                val (activity, detail) = ChaoxingActivityService.buildActivityAndDetailFromQrCode(
                    endpoints,
                    client,
                    ChaoxingQrCodeParseResult(aid = 505L, code = "c1", enc = "e1"),
                )
                assertEquals(1, loginCalls.get())
                assertEquals(2, detailAttempts.get())
                assertEquals(505L, activity.id)
                assertEquals(654321, activity.classId)
                assertEquals(987654L, activity.courseId)
                assertTrue(detail.isPositionRequired)
                assertTrue(detail.isRefreshQRCode)
                assertEquals(200, detail.targetLocation?.rangeMeters)
                assertEquals(39.908722, detail.targetLocation?.latitude ?: 0.0, 1e-6)
            } finally {
                client.invalidate()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Required Case 3: CloudDrive uploadImage clears cached token on 401 & retries
    // -------------------------------------------------------------------------

    @Test
    fun `cloud upload clears storageCloudToken on 401 and retries with refreshed token`() = runBlocking {
        val uploadAttempt = AtomicInteger(0)
        val tokenRequests = AtomicInteger(0)

        withServer(
            handler = { req ->
                when (req.requestUrl?.encodedPath) {
                    "/api/token/uservalid" -> {
                        tokenRequests.incrementAndGet()
                        MockResponse().setBody("""{"_token":"fresh+a/b="}""")
                    }
                    "/upload" -> {
                        if (uploadAttempt.incrementAndGet() == 1) {
                            MockResponse().setResponseCode(401)
                        } else {
                            MockResponse().setBody("""{"objectId":"uploaded-obj-777"}""")
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            },
        ) { _, endpoints, recorded ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            client.storageCloudToken = "expired-cached-token"
            try {
                val objectId = ChaoxingCloudDriveService.uploadImage(
                    endpoints = endpoints,
                    client = client,
                    imageBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
                    filename = "capture.jpg",
                )
                assertEquals("uploaded-obj-777", objectId)
                assertEquals(2, uploadAttempt.get())
                assertEquals(1, tokenRequests.get())
                assertEquals("fresh+a/b=", client.storageCloudToken)

                val uploadRequests = recorded.filter { it.path == "/upload" }
                assertEquals("expired-cached-token", uploadRequests[0].queryValue("_token"))
                assertTrue(uploadRequests[1].rawPath.contains("_token=fresh%2Ba%2Fb%3D"))
                assertTrue(uploadRequests[1].body.contains("capture.jpg"))
                assertTrue(uploadRequests[1].body.contains("2002"))
            } finally {
                client.invalidate()
            }
        }
    }

    @Test
    fun `cloud upload does not retry on non-auth HTTP 500 error`() = runBlocking {
        val uploadAttempt = AtomicInteger(0)
        withServer(
            handler = { req ->
                when (req.requestUrl?.encodedPath) {
                    "/api/token/uservalid" -> MockResponse().setBody("""{"_token":"tok"}""")
                    "/upload" -> {
                        uploadAttempt.incrementAndGet()
                        MockResponse().setResponseCode(500)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            },
        ) { _, endpoints, _ ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                val err = assertSuspendsThrows<ChaoxingError.NetworkError> {
                    ChaoxingCloudDriveService.uploadImage(endpoints, client, byteArrayOf(1, 2, 3), "a.jpg")
                }
                assertEquals(500, err.statusCode)
                assertEquals(1, uploadAttempt.get())
            } finally {
                client.invalidate()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Required Case 4: submitSign maps validate_myEnc2 & all server outcomes
    // -------------------------------------------------------------------------

    @Test
    fun `submitSign maps validate_myEnc2 to CaptchaRequired and maps all other sign responses`() = runBlocking {
        val nextBody = AtomicInteger(0)
        val responses = listOf(
            "validate_myEnc2",
            "validate",
            "success",
            "success2",
            "您已签到过了",
            "签到失败，请重新扫描。",
            "fail-need-qrcode",
            "[face]人脸未通过",
            "errorLocation_128.4",
            "同一设备不允许重复签到",
            "checkFace_42",
            "自定义异常消息",
        )

        withServer(
            handler = {
                val idx = nextBody.getAndIncrement()
                MockResponse().setResponseCode(200).setBody(responses[idx])
            },
        ) { _, endpoints, _ ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            val activity = syntheticActivity()
            try {
                val captchaWithEnc2 = assertSuspendsThrows<ChaoxingError.CaptchaRequired> {
                    ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                }
                assertEquals("myEnc2", captchaWithEnc2.enc2)

                val captchaWithoutEnc2 = assertSuspendsThrows<ChaoxingError.CaptchaRequired> {
                    ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                }
                assertNull(captchaWithoutEnc2.enc2)

                val ok = ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                assertTrue(ok.success)
                assertEquals("签到成功", ok.message)

                assertSuspendsThrows<ChaoxingError.SignAlreadyEnded> {
                    ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                }
                assertSuspendsThrows<ChaoxingError.AlreadySigned> {
                    ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                }
                assertSuspendsThrows<ChaoxingError.QrCodeExpired> {
                    ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                }
                assertSuspendsThrows<ChaoxingError.QrCodeExpired> {
                    ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                }
                val face1 = assertSuspendsThrows<ChaoxingError.FaceError> {
                    ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                }
                assertEquals("人脸未通过", face1.message)

                val wrongPos = assertSuspendsThrows<ChaoxingError.WrongPosition> {
                    ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                }
                assertEquals(128.4, wrongPos.distanceMeters)

                assertSuspendsThrows<ChaoxingError.DeviceDuplicated> {
                    ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                }
                val face2 = assertSuspendsThrows<ChaoxingError.FaceError> {
                    ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                }
                assertTrue(face2.message.contains("checkFace_42"))

                val serverMsg = assertSuspendsThrows<ChaoxingError.ServerMessage> {
                    ChaoxingSignExecutor.submitSign(endpoints, client, activity, listOf("activeId" to "1"))
                }
                assertEquals("自定义异常消息", serverMsg.message)
            } finally {
                client.invalidate()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Required Case 5: signQrCode passes non--1 lat/lon and location/locationResult
    // -------------------------------------------------------------------------

    @Test
    fun `signQrCode passes real formatted coordinates and location JSONs when location is provided`() = runBlocking {
        withServer(
            handler = { req ->
                when (req.requestUrl?.encodedPath) {
                    "/pptSign/check-face-result" -> MockResponse().setBody("""{"enc":"face-enc-xyz"}""")
                    "/pptSign/stuSignajax" -> MockResponse().setBody("success")
                    else -> MockResponse().setResponseCode(404)
                }
            },
        ) { _, endpoints, recorded ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                val location = ChaoxingLocationSignInfo(
                    latitude = 39.908722,
                    longitude = 116.397499,
                    address = "北京市东城区天安门",
                    enableRandomOffset = false,
                )
                val qr = ChaoxingQrCodeParseResult(aid = 9001L, code = "code1", enc = "qr-enc+1")
                val outcome = ChaoxingSignExecutor.signQrCode(
                    endpoints = endpoints,
                    client = client,
                    activity = syntheticActivity(id = 9001L),
                    qrResult = qr,
                    location = location,
                    faceImageObjectId = "face-obj-1",
                    captchaValidate = "val+1",
                    enc2 = "enc2-val",
                )
                assertTrue(outcome.success)

                val signReq = recorded.last { it.path == "/pptSign/stuSignajax" }
                assertEquals("POST", signReq.method)
                assertEquals("XMLHttpRequest", signReq.headers["X-Requested-With"])
                assertTrue(signReq.headers["Referer"].orEmpty().contains("/newsign/preSign"))
                assertTrue(signReq.queryPairs.isEmpty())
                assertEquals("-1", signReq.formValue("latitude"))
                assertEquals("-1", signReq.formValue("longitude"))
                assertEquals("qr-enc+1", signReq.formValue("enc"))
                assertEquals("val+1", signReq.formValue("validate"))
                assertEquals("enc2-val", signReq.formValue("enc2"))
                assertEquals("face-enc-xyz", signReq.formValue("faceEnc"))
                assertEquals("face-obj-1", signReq.formValue("currentFaceId"))
                assertTrue(signReq.body.contains("name=A%2BB"))
                assertTrue(signReq.body.contains("validate=val%2B1"))

                val locationJson = parseJsonObjectOrNull(signReq.formValue("location").orEmpty())!!
                assertEquals(1, locationJson.numberIntOrNull("result"))
                assertEquals(39.908722, locationJson.numberDoubleOrNull("latitude")!!, 1e-6)
                assertEquals(116.397499, locationJson.numberDoubleOrNull("longitude")!!, 1e-6)
                assertEquals("北京市东城区天安门", locationJson.stringOnlyOrNull("address"))
                assertNull(locationJson["mockData"])

                val locationResultJson = parseJsonObjectOrNull(signReq.formValue("locationResult").orEmpty())!!
                assertEquals(1, locationResultJson.numberIntOrNull("result"))
                assertEquals(39.908722, locationResultJson.numberDoubleOrNull("latitude")!!, 1e-6)
                assertEquals(116.397499, locationResultJson.numberDoubleOrNull("longitude")!!, 1e-6)
                assertEquals("""{"strategy":0,"probability":-1}""", locationResultJson.stringOnlyOrNull("mockData"))

                // Without location -> latitude/longitude = -1 and no location JSON params
                ChaoxingSignExecutor.signQrCode(
                    endpoints = endpoints,
                    client = client,
                    activity = syntheticActivity(id = 9001L),
                    qrResult = qr,
                    location = null,
                )
                val noLocReq = recorded.last { it.path == "/pptSign/stuSignajax" }
                assertEquals("POST", noLocReq.method)
                assertEquals("-1", noLocReq.formValue("latitude"))
                assertEquals("-1", noLocReq.formValue("longitude"))
                assertNull(noLocReq.formValue("location"))
                assertNull(noLocReq.formValue("locationResult"))

                // Mismatched aid rejected before any HTTP call
                val countBefore = recorded.size
                assertSuspendsThrows<ChaoxingError.InvalidInput> {
                    ChaoxingSignExecutor.signQrCode(
                        endpoints = endpoints,
                        client = client,
                        activity = syntheticActivity(id = 9001L),
                        qrResult = ChaoxingQrCodeParseResult(aid = 7777L, code = null, enc = "e"),
                    )
                }
                assertEquals(countBefore, recorded.size)
            } finally {
                client.invalidate()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Additional Service Coverage: Auth, Course, Group, Captcha, Face, Nominatim, Executor
    // -------------------------------------------------------------------------

    @Test
    fun `auth service login encrypts credentials upgrades pic and merges schools`() = runBlocking {
        withServer(
            handler = { req ->
                when (req.requestUrl?.encodedPath) {
                    "/fanyalogin" -> MockResponse()
                        .setResponseCode(200)
                        .addHeader("Set-Cookie", "UID=2002; Path=/")
                        .setBody("""{"status":true}""")
                    "/apis/login/userLogin4Uname.do" -> MockResponse()
                        .setResponseCode(200)
                        .setBody(
                            """
                            {
                              "msg": {
                                "uid": "1001",
                                "puid": 2002,
                                "fid": 100,
                                "name": "测试用户",
                                "uname": "13800000001",
                                "pic": "http://p.ananas.chaoxing.com/avatar.jpg",
                                "schoolname": "",
                                "clientId": "cid-1",
                                "accountInfo": {"imAccount": {"password": "enc-im-password"}},
                                "unitConfigInfos": [
                                  {"fid": 100, "schoolname": "主校区"},
                                  {"fid": 200, "schoolname": "分校区"}
                                ]
                              }
                            }
                            """.trimIndent(),
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            },
        ) { _, endpoints, recorded ->
            val session = ChaoxingAuthService.login(
                endpoints = endpoints,
                phoneNumber = " 13800000001 ",
                password = "Password1",
                isPrimary = true,
                clientFactory = { s, ua -> ChaoxingHttpClient.createForLoopbackTesting(s, ua) },
            )
            assertEquals("13800000001", session.phoneNumber)
            assertEquals(1001, session.uid)
            assertEquals(2002, session.puid)
            assertEquals(100, session.configuredFid)
            assertEquals("https://p.ananas.chaoxing.com/avatar.jpg", session.avatarURL)
            assertEquals("enc-im-password", session.imEncryptedPassword)
            assertEquals("cid-1", session.clientId)
            assertEquals(2, session.schools.size)
            assertEquals("主校区", session.schools[0].name)
            assertEquals("分校区", session.schools[1].name)

            val loginReq = recorded.first { it.path == "/fanyalogin" }
            val bodyPairs = loginReq.body.split("&").associate { pair ->
                val idx = pair.indexOf('=')
                pair.substring(0, idx) to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            }
            assertEquals(ChaoxingCrypto.encryptLoginField("13800000001"), bodyPairs["uname"])
            assertEquals(ChaoxingCrypto.encryptLoginField("Password1"), bodyPairs["password"])
        }

        // positiveIdentifier rejects booleans and <= 0
        assertNull(ChaoxingAuthService.positiveIdentifier(JsonPrimitive(true)))
        assertNull(ChaoxingAuthService.positiveIdentifier(JsonPrimitive(false)))
        assertNull(ChaoxingAuthService.positiveIdentifier(JsonPrimitive(0)))
        assertNull(ChaoxingAuthService.positiveIdentifier(JsonPrimitive(-9)))
        assertNull(ChaoxingAuthService.positiveIdentifier(JsonPrimitive("0")))
        assertNull(ChaoxingAuthService.positiveIdentifier(JsonNull))
        assertEquals(42, ChaoxingAuthService.positiveIdentifier(JsonPrimitive(42)))
        assertEquals(77, ChaoxingAuthService.positiveIdentifier(JsonPrimitive("77")))

        // Password length validation
        assertSuspendsThrows<ChaoxingError.InvalidInput> {
            ChaoxingAuthService.login(phoneNumber = "13800000001", password = "short")
        }
    }

    @Test
    fun `course service retries reLogin on parse failure and resolves class metadata`() = runBlocking {
        val courseCalls = AtomicInteger(0)
        withServer(
            handler = { req ->
                when (req.requestUrl?.encodedPath) {
                    "/mycourse/backclazzdata" -> {
                        if (courseCalls.incrementAndGet() == 1) {
                            MockResponse().setBody("""{"result":0}""")
                        } else {
                            MockResponse().setBody(
                                """
                                {
                                  "result": 1,
                                  "channelList": [
                                    {"content": {"id": 999}},
                                    {
                                      "cataName": "课程",
                                      "content": {
                                        "id": 445566,
                                        "course": {
                                          "data": [{
                                            "id": 112233,
                                            "name": "计算机网络",
                                            "teacherfactor": "李教授",
                                            "imageurl": "http://p.ananas.chaoxing.com/course.png",
                                            "schools": "计算机学院"
                                          }]
                                        }
                                      }
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )
                        }
                    }
                    "/fanyalogin" -> MockResponse().setBody("""{"status":true}""")
                    "/apis/login/userLogin4Uname.do" -> MockResponse()
                        .setBody("""{"msg":{"uid":1001,"puid":2002,"fid":3003}}""")
                    "/gas/clazz" -> MockResponse()
                        .setBody("""{"data":[{"name":"计科2401班"}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            },
        ) { _, endpoints, _ ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                val courses = ChaoxingCourseService.fetchCourses(endpoints, client)
                assertEquals(1, courses.size)
                assertEquals("计算机网络", courses[0].courseName)
                assertEquals("https://p.ananas.chaoxing.com/course.png", courses[0].imageURL)
                assertEquals(112233L, ChaoxingCourseService.getCourseIdFromClassId(endpoints, client, 445566))
                assertEquals(true, ChaoxingCourseService.checkClassValid(endpoints, client, 445566))
                assertEquals(false, ChaoxingCourseService.checkClassValid(endpoints, client, 123456))
                assertEquals("计科2401班", ChaoxingCourseService.queryClassName(endpoints, client, 445566))
            } finally {
                client.invalidate()
            }
        }
    }

    @Test
    fun `group service fetches paginated groups and decodes protobuf roaming sign activities`() = runBlocking {
        // Build synthetic protobuf Meta message: field 4 (timestampMs = 1700000001234L),
        // field 6 -> MessageBody(field 5 -> KeyValue(field 1="attachment", field 6=attachmentJson))
        val attachmentJson = """
            {
              "attachmentType": 15,
              "att_chat_sign": {
                "atypeName": "位置签到",
                "title": "实验签到",
                "subTitle": "10:00 开始",
                "url": "https://mobilelearn.chaoxing.com/newsign/preSign?activePrimaryId=55555&classId=666&courseId=777"
              }
            }
        """.trimIndent()
        val encodedMsgBase64 = Base64.getEncoder().encodeToString(
            buildEasemobMetaProtobuf(timestampMs = 1_700_000_001_234L, attachmentJson = attachmentJson),
        )

        withServer(
            handler = { req ->
                val path = req.requestUrl?.encodedPath.orEmpty()
                when {
                    path == "/cx-dev/cxstudy/token" -> {
                        MockResponse().setBody("""{"access_token":"im-tok","user":{"uuid":"u-1","username":"user_1"}}""")
                    }
                    path.endsWith("/joined_chatgroups") -> {
                        val page = req.requestUrl?.queryParameter("pagenum")
                        val rows = if (page == "1") {
                            (1..200).joinToString(",") { id ->
                                """{"id":"g-$id","name":"群$id"}"""
                            }
                        } else {
                            """{"id":"g-201","name":"","description":"{\"courseInfo\":{\"coursename\":\"高数群\",\"imageUrl\":\"http://img.chaoxing.com/g.jpg\"}}"}"""
                        }
                        MockResponse().setBody("""{"data":[$rows]}""")
                    }
                    path.endsWith("/messageroaming") -> MockResponse().setBody(
                        """{"data":{"msgs":[{"msg":"$encodedMsgBase64"}]}}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            },
        ) { _, endpoints, recorded ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                val config = ChaoxingGroupService.getEasemobConfig(endpoints, client)
                assertEquals("im-tok", config.accessToken)
                assertEquals("user_1", config.username)
                assertEquals(
                    ChaoxingGroupService.EASEMOB_USER_AGENT,
                    recorded.first { it.path == "/cx-dev/cxstudy/token" }.headers["User-Agent"],
                )

                val groups = ChaoxingGroupService.fetchJoinedGroups(endpoints, client, config)
                assertEquals(201, groups.size)
                assertEquals("高数群", groups.last().name)
                assertEquals("https://img.chaoxing.com/g.jpg", groups.last().imageURL)

                val activities = ChaoxingGroupService.fetchGroupSignActivities(
                    endpoints = endpoints,
                    client = client,
                    group = ChaoxingIMGroup(id = "g-201", name = "默认群"),
                    imConfig = config,
                )
                assertEquals(1, activities.size)
                assertEquals(55555L, activities[0].activeId)
                assertEquals(666, activities[0].classId)
                assertEquals(777L, activities[0].courseId)
                assertEquals(ChaoxingSignType.LOCATION, activities[0].signType)
            } finally {
                client.invalidate()
            }
        }
    }

    @Test
    fun `captcha service upgrades non-loopback http URLs unwraps JSONP and checks overflow`() = runBlocking {
        assertEquals(
            "https://p.ananas.chaoxing.com/shade.png",
            ChaoxingCaptchaService.upgradeHttpToHttpsUnlessLoopback("http://p.ananas.chaoxing.com/shade.png"),
        )
        assertEquals(
            "http://127.0.0.1:8080/shade.png",
            ChaoxingCaptchaService.upgradeHttpToHttpsUnlessLoopback("http://127.0.0.1:8080/shade.png"),
        )

        withServer(
            handler = { req ->
                val path = req.requestUrl?.encodedPath.orEmpty()
                val base = req.requestUrl!!.newBuilder().encodedPath("/").query(null).build().toString().trimEnd('/')
                when (path) {
                    "/captcha/get/conf" -> MockResponse().setBody("""cx_captcha_function({"t":1700000000000});""")
                    "/captcha/get/verification/image" -> MockResponse().setBody(
                        """
                        cx_captcha_function({
                          "token": "tok+1",
                          "imageVerificationVo": {
                            "shadeImage": "$base/img/shade.png",
                            "cutoutImage": "$base/img/cutout.png"
                          }
                        })
                        """.trimIndent(),
                    )
                    "/img/shade.png" -> MockResponse().setBody("SHADE_PNG_DATA")
                    "/img/cutout.png" -> MockResponse().setBody("CUTOUT_PNG_DATA")
                    "/captcha/check/verification/result" -> MockResponse().setBody(
                        """cx_captcha_function({"result":true,"extraData":"{\"validate\":\"val+a/b\"}"});""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            },
        ) { _, endpoints, _ ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                val challenge = ChaoxingCaptchaService.fetchCaptcha(endpoints, client, "https://example.invalid/")
                assertEquals("tok+1", challenge.captcha.token)
                assertEquals("SHADE_PNG_DATA", challenge.shadeImageBytes.toString(Charsets.UTF_8))
                assertEquals("CUTOUT_PNG_DATA", challenge.cutoutImageBytes.toString(Charsets.UTF_8))

                val validate = ChaoxingCaptchaService.verifyCaptcha(
                    endpoints = endpoints,
                    client = client,
                    captcha = challenge.captcha,
                    xPosition = 123,
                    refererUrl = "https://example.invalid/",
                )
                assertEquals("val+a/b", validate)
            } finally {
                client.invalidate()
            }
        }

        // Timestamp overflow protection
        withServer(
            handler = { MockResponse().setBody("""{"t":9223372036854775807}""") },
        ) { _, endpoints, recorded ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                assertSuspendsThrows<ChaoxingError.CaptchaFailed> {
                    ChaoxingCaptchaService.fetchCaptcha(endpoints, client, "https://example.invalid/")
                }
                assertEquals(1, recorded.size)
            } finally {
                client.invalidate()
            }
        }
    }

    @Test
    fun `face service fetches objectId and enc and signExecutor supports code and location sign`() = runBlocking {
        withServer(
            handler = { req ->
                when (req.requestUrl?.encodedPath) {
                    "/v2/apis/sign/collectionfilephotoEnc" -> MockResponse()
                        .setBody("""{"data":{"oldObjectId":"face-base-01"}}""")
                    "/pptSign/check-face-result" -> MockResponse()
                        .setBody("""{"enc":"face-enc-token"}""")
                    "/widget/sign/pcStuSignController/checkSignCode" -> MockResponse()
                        .setBody("""{"result":1}""")
                    "/newsign/signDetail" -> MockResponse()
                        .setBody("""{"isOver":0,"signCode":"fresh-qr"}""")
                    "/pptSign/stuSignajax" -> MockResponse().setBody("success")
                    else -> MockResponse().setResponseCode(404)
                }
            },
        ) { _, endpoints, recorded ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                val objId = ChaoxingFaceService.fetchUserProfileFaceObjectId(endpoints, client)
                assertEquals("face-base-01", objId)
                assertEquals(
                    "https://p.cldisk.com/star4/face-base-01/origin.jpg",
                    ChaoxingFaceService.fetchUserProfileFaceImageUrl(endpoints, client),
                )
                assertTrue(ChaoxingSignExecutor.checkSignCode(endpoints, client, 9001L, "1234"))
                assertEquals(
                    true,
                    ChaoxingSignExecutor.isQrCodeExpired(
                        endpoints,
                        client,
                        9001L,
                        ChaoxingQrCodeParseResult(aid = 9001L, code = "old-qr", enc = "e"),
                    ),
                )
                assertEquals(
                    false,
                    ChaoxingSignExecutor.isQrCodeExpired(
                        endpoints,
                        client,
                        9001L,
                        ChaoxingQrCodeParseResult(aid = 9001L, code = "fresh-qr", enc = "e"),
                    ),
                )

                val loc = ChaoxingLocationSignInfo(
                    latitude = 31.2304,
                    longitude = 121.4737,
                    address = "人民广场",
                    enableRandomOffset = false,
                )
                assertTrue(
                    ChaoxingSignExecutor.signLocation(
                        endpoints = endpoints,
                        client = client,
                        activity = syntheticActivity(),
                        location = loc,
                        faceImageObjectId = objId,
                    ).success,
                )
                val locReq = recorded.last { it.path == "/pptSign/stuSignajax" }
                assertEquals("POST", locReq.method)
                assertEquals("XMLHttpRequest", locReq.headers["X-Requested-With"])
                assertEquals("face-enc-token", locReq.formValue("faceEnc"))
                assertEquals("人民广场", locReq.formValue("address"))

                assertTrue(
                    ChaoxingSignExecutor.signCodeOrGesture(
                        endpoints = endpoints,
                        client = client,
                        activity = syntheticActivity(),
                        signCode = "12369",
                        location = loc,
                    ).success,
                )
                val codeReq = recorded.last { it.path == "/pptSign/stuSignajax" }
                assertEquals("POST", codeReq.method)
                assertEquals("12369", codeReq.formValue("signCode"))
                assertNotNull(codeReq.formValue("locationResult"))
            } finally {
                client.invalidate()
            }
        }
    }

    @Test
    fun `nominatim geocoder sets OSM headers parses WGS84 results and never leaks chaoxing cookies`() = runBlocking {
        withServer(
            handler = { req ->
                when (req.requestUrl?.queryParameter("q")) {
                    "清华大学" -> MockResponse().setBody(
                        """
                        [
                          {
                            "place_id": 101,
                            "lat": "40.002456",
                            "lon": "116.326789",
                            "name": "清华大学",
                            "display_name": "清华大学, 海淀区, 北京市, 中国"
                          },
                          {
                            "place_id": 102,
                            "lat": 39.991234,
                            "lon": 116.310567,
                            "name": "",
                            "display_name": "北京大学, 海淀区, 北京市"
                          }
                        ]
                        """.trimIndent(),
                    )
                    "empty" -> MockResponse().setBody("[]")
                    "badjson" -> MockResponse().setBody("{not-an-array")
                    else -> MockResponse().setResponseCode(503)
                }
            },
        ) { _, endpoints, recorded ->
            val okHttpClient = OkHttpClient()
            val results = NominatimGeocoder.search(
                endpoints = endpoints,
                query = " 清华大学 ",
                client = okHttpClient,
            )
            assertEquals(2, results.size)
            assertEquals("清华大学", results[0].name)
            assertEquals(40.002456, results[0].latitude, 1e-6)
            assertEquals(116.326789, results[0].longitude, 1e-6)
            assertEquals(CoordinateSystem.WGS84, results[0].coordinateSystem)
            assertEquals("北京大学", results[1].name)
            assertEquals(CoordinateSystem.WGS84, results[1].coordinateSystem)

            val firstReq = recorded.last()
            assertEquals("jsonv2", firstReq.queryValue("format"))
            assertEquals("8", firstReq.queryValue("limit"))
            assertEquals(NominatimGeocoder.NOMINATIM_USER_AGENT, firstReq.headers["User-Agent"])
            assertEquals(NominatimGeocoder.ACCEPT_LANGUAGE, firstReq.headers["Accept-Language"])
            assertNull(firstReq.headers["Cookie"])

            // Blank query makes no network request
            val countBeforeBlank = recorded.size
            assertEquals(emptyList<NominatimPlace>(), NominatimGeocoder.search(endpoints, "   ", okHttpClient))
            assertEquals(countBeforeBlank, recorded.size)

            // ChaoxingHttpClient with chaoxing.com cookies never leaks cookies to Nominatim
            val cxClient = ChaoxingHttpClient.createForLoopbackTesting(
                syntheticSession(
                    cookies = listOf(
                        ChaoxingStoredCookie(name = "sid", value = "cx-secret", domain = "chaoxing.com"),
                    ),
                ),
            )
            try {
                val emptyPlaces = NominatimGeocoder.search(endpoints, "empty", cxClient)
                assertTrue(emptyPlaces.isEmpty())
                val nominatimReq = recorded.last()
                assertNull(nominatimReq.headers["Cookie"])
                assertEquals(NominatimGeocoder.NOMINATIM_USER_AGENT, nominatimReq.headers["User-Agent"])
            } finally {
                cxClient.invalidate()
            }

            assertSuspendsThrows<ChaoxingError.ParseFailed> {
                NominatimGeocoder.search(endpoints, "badjson", okHttpClient)
            }
            val netErr = assertSuspendsThrows<ChaoxingError.NetworkError> {
                NominatimGeocoder.search(endpoints, "fail", okHttpClient)
            }
            assertEquals(503, netErr.statusCode)
        }
    }

    @Test
    fun `preSign direct 302 unfollowed redirect group pagination guards and signPhotoOrClick work`() = runBlocking {
        val preSignAttempts = AtomicInteger(0)
        withServer(
            handler = { req ->
                val path = req.requestUrl?.encodedPath.orEmpty()
                when {
                    path == "/newsign/preSign" -> {
                        if (preSignAttempts.incrementAndGet() == 1) {
                            MockResponse()
                                .setResponseCode(302)
                                .addHeader("Location", "https://external-untrusted.invalid/login")
                        } else {
                            MockResponse().setBody("""<form><input name="activePrimaryId" value="9001" /></form>""")
                        }
                    }
                    path == "/fanyalogin" -> MockResponse().setBody("""{"status":true}""")
                    path == "/apis/login/userLogin4Uname.do" -> MockResponse()
                        .setBody("""{"msg":{"uid":1001,"puid":2002,"fid":3003}}""")
                    path == "/v2/apis/active/student/activelist" -> MockResponse().setBody(
                        """{"result":true,"data":{"activeList":[{"id":9001,"type":2,"otherId":"0","isLook":1,"status":1,"nameOne":"拍照"}]}}""",
                    )
                    path == "/pptSign/stuSignajax" -> MockResponse().setBody("success")
                    path.endsWith("/joined_chatgroups") -> {
                        val rows = (1..200).joinToString(",") { id -> """{"id":"same-$id","name":"G$id"}""" }
                        MockResponse().setBody("""{"data":[$rows]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            },
        ) { _, endpoints, recorded ->
            val client = ChaoxingHttpClient.createForLoopbackTesting(syntheticSession())
            try {
                assertEquals(
                    ChaoxingSignActivityStatus.READY_TO_SIGN,
                    ChaoxingSignExecutor.preSign(endpoints, client, syntheticActivity()),
                )
                assertEquals(2, preSignAttempts.get())

                val acts = ChaoxingActivityService.fetchCourseActivities(endpoints, client, syntheticCourse())
                assertEquals(1, acts.size)
                assertEquals("拍照", acts[0].nameOne)

                val photoOutcome = ChaoxingSignExecutor.signPhotoOrClick(
                    endpoints = endpoints,
                    client = client,
                    activity = acts[0],
                    objectId = "photo-obj-1",
                    captchaValidate = "val+token",
                    enc2 = "enc2-token",
                )
                assertTrue(photoOutcome.success)
                val photoReq = recorded.last { it.path == "/pptSign/stuSignajax" }
                assertEquals("POST", photoReq.method)
                assertEquals("XMLHttpRequest", photoReq.headers["X-Requested-With"])
                assertEquals("photo-obj-1", photoReq.formValue("objectId"))
                assertEquals("val+token", photoReq.formValue("validate"))
                assertEquals("enc2-token", photoReq.formValue("enc2"))
                assertEquals("-1", photoReq.formValue("latitude"))
                assertEquals("-1", photoReq.formValue("longitude"))

                val err = assertSuspendsThrows<ChaoxingError.ParseFailed> {
                    ChaoxingGroupService.fetchJoinedGroups(
                        endpoints = endpoints,
                        client = client,
                        imConfig = ChaoxingEasemobIMConfig(
                            accessToken = "tok",
                            uuid = "u",
                            username = "u1",
                        ),
                    )
                }
                assertTrue(err.message.contains("未返回新数据"))
            } finally {
                client.invalidate()
            }
        }
    }

    private fun buildEasemobMetaProtobuf(timestampMs: Long, attachmentJson: String): ByteArray {
        fun encodeVarint(out: ByteArrayOutputStream, value: Long) {
            var v = value
            while (true) {
                if ((v and 0x7FL.inv()) == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v.toInt() and 0x7F) or 0x80))
                v = v ushr 7
            }
        }

        fun encodeLengthDelimited(fieldNumber: Int, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            encodeVarint(out, ((fieldNumber shl 3) or 2).toLong())
            encodeVarint(out, payload.size.toLong())
            out.write(payload)
            return out.toByteArray()
        }

        val kvOut = ByteArrayOutputStream()
        kvOut.write(encodeLengthDelimited(1, "attachment".toByteArray(Charsets.UTF_8)))
        kvOut.write(encodeLengthDelimited(6, attachmentJson.toByteArray(Charsets.UTF_8)))

        val bodyBytes = encodeLengthDelimited(5, kvOut.toByteArray())

        val metaOut = ByteArrayOutputStream()
        encodeVarint(metaOut, ((4 shl 3) or 0).toLong())
        encodeVarint(metaOut, timestampMs)
        metaOut.write(encodeLengthDelimited(6, bodyBytes))
        return metaOut.toByteArray()
    }
}
