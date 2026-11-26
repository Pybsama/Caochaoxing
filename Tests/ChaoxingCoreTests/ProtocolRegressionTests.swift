// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
import Testing
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
@testable import ChaoxingCore

@Suite("Protocol regression tests", .serialized)
struct ProtocolRegressionTests {
    private let course = ChaoxingCourse(courseName: "Synthetic", teacherName: "", courseId: 11, classId: 22, imageURL: "")

    @Test("Activity extension null and scalars do not abort parsing")
    func activityExtension() throws {
        for value in ["null", "42", "\"encoded\"", "true"] {
            let data = Data("{\"data\":{\"activeList\":[],\"ext\":\(value)}}".utf8)
            #expect(try ChaoxingActivityService.parseActivitiesJSON(data, course: course).isEmpty)
        }
    }
}

extension ProtocolRegressionTests {
    @Test("Protobuf rejects overflowing lengths and malformed trailing fields")
    func protobufBounds() {
        // Field 6 length UInt64.max; cannot convert to signed Int or fit remaining data.
        #expect(EasemobProtobufDecoder.decodeMetaMessage(from: Data([0x32] + Array(repeating: 0xff, count: 9) + [0x01])) == nil)
        // Field 6 empty body followed by a truncated varint, zero tag, or overflowing varint.
        for tail: [UInt8] in [[0x20, 0x80], [0x00], [0x20] + Array(repeating: 0xff, count: 9) + [0x02]] {
            #expect(EasemobProtobufDecoder.decodeMetaMessage(from: Data([0x32, 0x00] + tail)) == nil)
        }
        #expect(EasemobProtobufDecoder.decodeMetaMessage(from: Data([0x32, 0x00])) != nil)
    }
}

private final class ProtocolResponseStore: @unchecked Sendable {
    private let lock = NSLock()
    private var callback: @Sendable (URLRequest) -> (Int, Data) = { _ in (500, Data()) }
    private var seen: [URLRequest] = []
    func reset(_ callback: @escaping @Sendable (URLRequest) -> (Int, Data)) {
        lock.withLock { self.callback = callback; seen = [] }
    }
    func respond(_ request: URLRequest) -> (Int, Data) {
        let handler = lock.withLock { seen.append(request); return callback }
        return handler(request)
    }
    var requests: [URLRequest] { lock.withLock { seen } }
}

private final class ProtocolMockURLProtocol: URLProtocol, @unchecked Sendable {
    static let store = ProtocolResponseStore()
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (status, data) = Self.store.respond(request)
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}

extension ProtocolRegressionTests {
    private static let activity = ChaoxingSignActivity(id: 101, startTimeMs: nil, endTimeMs: nil, userStatus: 0, otherId: "2", isLook: true, type: 2, activeType: 2, nameOne: "Synthetic", status: 1, nameFour: "", courseId: 11, classId: 22, courseName: "Synthetic", extContent: "{}")
    private func client(status: Int = 200, body: String) -> ChaoxingHTTPClient {
        ProtocolMockURLProtocol.store.reset { _ in (status, Data(body.utf8)) }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [ProtocolMockURLProtocol.self]
        return ChaoxingHTTPClient(session: .init(phoneNumber: "synthetic-offline", encryptedPassword: "synthetic", name: "A+B", deviceCode: "synthetic"), configuration: config)
    }

    @Test("Mismatched QR target is rejected before any request")
    func mismatchedQRCode() async {
        let c = client(body: "success")
        await #expect(throws: ChaoxingError.self) {
            try await ChaoxingSignExecutor.signQRCode(client: c, activity: Self.activity, qrResult: .init(aid: 999, code: nil, enc: "test"))
        }
        #expect(ProtocolMockURLProtocol.store.requests.isEmpty)
    }

    @Test("Unknown or login pre-sign documents never become ready")
    func preSignDocuments() async {
        for body in ["", "<html><title>请登录</title></html>", "<html>maintenance</html>"] {
            let c = client(body: body)
            await #expect(throws: ChaoxingError.self) { try await ChaoxingSignExecutor.preSign(client: c, activity: Self.activity) }
        }
        let c = client(body: "<script>var signstatus=0;</script>")
        #expect((try? await ChaoxingSignExecutor.preSign(client: c, activity: Self.activity)) == .readyToSign)
    }

    @Test("QR availability remains unknown on HTTP or incomplete JSON errors")
    func qrAvailability() async {
        let qr = ChaoxingQRCodeParseResult(aid: 101, code: "abc", enc: "synthetic")
        for (status, body) in [(503, "{}"), (200, "{}"), (200, "{\"result\":false}")] {
            let c = client(status: status, body: body)
            #expect(await ChaoxingSignExecutor.isQRCodeExpired(client: c, activeId: 101, qrResult: qr) == nil)
        }
        for (body, expected) in [("{\"isOver\":0,\"signCode\":\"abc\"}", false), ("{\"isOver\":1}", true)] {
            let c = client(body: body)
            #expect(await ChaoxingSignExecutor.isQRCodeExpired(client: c, activeId: 101, qrResult: qr) == expected)
        }
    }

    @Test("Group history transport failures are not empty successful histories")
    func groupErrors() async {
        let c = client(status: 401, body: "{}")
        await #expect(throws: ChaoxingError.self) {
            try await ChaoxingGroupService.fetchGroupSignActivities(client: c, group: .init(id: "synthetic", name: "Synthetic"), imConfig: .init(accessToken: "synthetic", uuid: "synthetic", username: "synthetic"))
        }
    }

    @Test("Detail error or absent type is not usable activity metadata")
    func detailErrors() async {
        for body in ["{\"result\":false,\"data\":{}}", "{\"data\":{}}"] {
            let c = client(body: body)
            await #expect(throws: ChaoxingError.self) { try await ChaoxingActivityService.fetchSignDetail(client: c, activeId: 101) }
        }
    }

    @Test("Captcha timestamps cannot overflow")
    func captchaTimestamp() async {
        let c = client(body: "{\"t\":9223372036854775807}")
        await #expect(throws: ChaoxingError.self) { try await ChaoxingCaptchaService.fetchCaptcha(client: c, refererURL: "https://example.invalid/") }
        #expect(ProtocolMockURLProtocol.store.requests.count == 1)
    }

    @Test("JSONP optional semicolon is accepted and validate token is preserved")
    func jsonpSemicolon() async throws {
        let c = client(body: #"cx_captcha_function({"result":true,"extraData":"{\"validate\":\"a+b\"}"});"#)
        let captcha = ChaoxingCaptchaData(captchaId: "synthetic", type: "slide", version: "test", token: "synthetic", captchaKey: "synthetic", iv: "synthetic", shadeImageURL: "", cutoutImageURL: "")
        #expect(try await ChaoxingCaptchaService.verifyCaptcha(client: c, captcha: captcha, xPosition: 42, refererURL: "https://example.invalid/") == "a+b")
    }

    @Test("Signing POST form preserves plus and percent as data with required headers")
    func queryEscaping() async throws {
        let c = client(body: "success")
        _ = try await ChaoxingSignExecutor.signPhotoOrClick(client: c, activity: Self.activity, captchaValidate: "a+b%20")
        let req = try #require(ProtocolMockURLProtocol.store.requests.last)
        #expect(req.httpMethod == "POST")
        #expect(req.value(forHTTPHeaderField: "X-Requested-With") == "XMLHttpRequest")
        #expect(req.value(forHTTPHeaderField: "Referer")?.contains("/newsign/preSign") == true)
        var bodyData = req.httpBody
        if bodyData == nil, let stream = req.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var data = Data()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count > 0 { data.append(buffer, count: count) } else { break }
            }
            bodyData = data
        }
        let body = try #require(bodyData.flatMap { String(data: $0, encoding: .utf8) })
        #expect(body.contains("name=A%2BB"))
        #expect(body.contains("validate=a%2Bb%2520"))
    }
}

extension ProtocolRegressionTests {
    private func makeImage(width: Int, height: Int, transparentBorder: Bool = false, orientation: Int = 1) throws -> Data {
        let context = try #require(CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(gray: 0.5, alpha: 1))
        let rect = transparentBorder ? CGRect(x: 2, y: 2, width: width - 4, height: height - 4) : CGRect(x: 0, y: 0, width: width, height: height)
        context.fill(rect)
        let data = NSMutableData()
        let destination = try #require(CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, try #require(context.makeImage()), [kCGImagePropertyOrientation: orientation] as CFDictionary)
        #expect(CGImageDestinationFinalize(destination))
        return data as Data
    }

    @Test("Image output applies EXIF orientation before discarding metadata")
    func imageOrientation() throws {
        let input = try makeImage(width: 120, height: 80, orientation: 6)
        let output = ChaoxingImageObfuscator.obfuscateJPEG(input)
        let source = try #require(CGImageSourceCreateWithData(output as CFData, nil))
        let image = try #require(CGImageSourceCreateImageAtIndex(source, 0, nil))
        #expect(image.height > image.width)
    }

    @Test("Flat captcha images have no confident solution; oversized input is bounded")
    func captchaConfidence() throws {
        let cutout = try makeImage(width: 60, height: 60, transparentBorder: true)
        #expect(ChaoxingCaptchaSolver.estimateXPosition(shadeImageData: try makeImage(width: 320, height: 160), cutoutImageData: cutout) == nil)
        #expect(ChaoxingCaptchaSolver.estimateXPosition(shadeImageData: try makeImage(width: 2048, height: 1200), cutoutImageData: cutout) == nil)
    }
}

extension ProtocolRegressionTests {
    @Test("Joined groups continue after a full first page")
    func joinedGroupPages() async throws {
        let c = client(body: "{}")
        ProtocolMockURLProtocol.store.reset { request in
            let page = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "pagenum" }?.value
            let ids = page == "1" ? Array(1...200) : [201]
            let rows = ids.map { "{\"id\":\"\($0)\",\"name\":\"Synthetic\"}" }.joined(separator: ",")
            return (200, Data("{\"data\":[\(rows)]}".utf8))
        }
        let groups = try await ChaoxingGroupService.fetchJoinedGroups(client: c, imConfig: .init(accessToken: "synthetic", uuid: "synthetic", username: "synthetic"))
        #expect(groups.count == 201)
        #expect(ProtocolMockURLProtocol.store.requests.count == 2)
    }

    @Test("QR parsing rejects ambiguous targets and falls back from malformed aliases")
    func qrTargetParsing() throws {
        #expect(try ChaoxingQRCodeParser.parse(rawValue: "https://example.invalid/?id=oops&aid=999&enc=abc").aid == 999)
        #expect(throws: ChaoxingError.self) { try ChaoxingQRCodeParser.parse(rawValue: "https://example.invalid/?id=101&aid=999&enc=abc") }
        #expect(throws: ChaoxingError.self) { try ChaoxingQRCodeParser.parse(rawValue: "https://example.invalid/?id=-1&enc=abc") }
        #expect(throws: ChaoxingError.self) { try ChaoxingQRCodeParser.parse(rawValue: "https://example.invalid/?id=101&enc=%20") }
    }

    @Test("Captcha matcher still finds a distinct synthetic gap")
    func captchaDistinctGap() throws {
        let context = try #require(CGContext(data: nil, width: 320, height: 160, bitsPerComponent: 8, bytesPerRow: 1280, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        context.setFillColor(CGColor(gray: 0, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: 320, height: 160))
        context.setFillColor(CGColor(gray: 1, alpha: 1)); context.fill(CGRect(x: 102, y: 2, width: 56, height: 56))
        let data = NSMutableData()
        let destination = try #require(CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, try #require(context.makeImage()), nil)
        #expect(CGImageDestinationFinalize(destination))
        let position = try #require(ChaoxingCaptchaSolver.estimateXPosition(shadeImageData: data as Data, cutoutImageData: try makeImage(width: 60, height: 60, transparentBorder: true)))
        #expect((98...102).contains(position))
    }

    @Test("fetchSignDetail re-authenticates once on 403 and parses string-typed IDs")
    func fetchSignDetailReLoginAndStringIds() async throws {
        let c = client(body: "{}")
        ProtocolMockURLProtocol.store.reset { request in
            let path = request.url?.path ?? ""
            if path.contains("getPPTActiveInfo") {
                let count = ProtocolMockURLProtocol.store.requests.filter { $0.url?.path.contains("getPPTActiveInfo") == true }.count
                if count == 1 {
                    return (403, Data("Forbidden".utf8))
                }
                let json = """
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
                """
                return (200, Data(json.utf8))
            }
            if path.contains("fanyalogin") {
                return (200, Data("{\"status\":true}".utf8))
            }
            if path.contains("userLogin4Uname") {
                return (200, Data("{\"msg\":{\"uid\":1001,\"puid\":2002,\"fid\":3003,\"name\":\"测试用户\",\"uname\":\"u\",\"pic\":\"\",\"schoolname\":\"示例学校\"}}".utf8))
            }
            return (404, Data())
        }
        let (activity, detail) = try await ChaoxingActivityService.buildActivityAndDetailFromQRCode(
            client: c,
            qrResult: ChaoxingQRCodeParseResult(aid: 505, code: "c1", enc: "e1")
        )
        let detailCalls = ProtocolMockURLProtocol.store.requests.filter { $0.url?.path.contains("getPPTActiveInfo") == true }.count
        #expect(detailCalls == 2)
        #expect(activity.id == 505)
        #expect(activity.classId == 654321)
        #expect(activity.courseId == 987654)
        #expect(detail.isPositionRequired)
        #expect(detail.isRefreshQRCode)
        #expect(detail.targetLocation?.rangeMeters == 200)
    }
}
