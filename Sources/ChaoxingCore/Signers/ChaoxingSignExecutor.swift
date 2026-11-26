// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation

public enum ChaoxingSignExecutor {
    private static let preSignBase = "https://mobilelearn.chaoxing.com/newsign/preSign"
    private static let analysisBase = "https://mobilelearn.chaoxing.com/pptSign/analysis?vs=1&DB_STRATEGY=RANDOM"
    private static let analysis2Base = "https://mobilelearn.chaoxing.com/pptSign/analysis2?DB_STRATEGY=RANDOM"
    private static let stuSignBase = "https://mobilelearn.chaoxing.com/pptSign/stuSignajax"
    private static let signDetailBase = "https://mobilelearn.chaoxing.com/newsign/signDetail"
    private static let checkSignCodeBase = "https://mobilelearn.chaoxing.com/widget/sign/pcStuSignController/checkSignCode"

    private static let signedStatuses: Set<Int> = [1, 2, 3, 9]

    public static func preSignURL(client: ChaoxingHTTPClient, activity: ChaoxingSignActivity) -> URL {
        var components = URLComponents(string: preSignBase)!
        components.queryItems = [
            URLQueryItem(name: "courseId", value: String(activity.courseId)),
            URLQueryItem(name: "classId", value: String(activity.classId)),
            URLQueryItem(name: "activePrimaryId", value: String(activity.id)),
            URLQueryItem(name: "general", value: "1"),
            URLQueryItem(name: "sys", value: "1"),
            URLQueryItem(name: "ls", value: "1"),
            URLQueryItem(name: "appType", value: "15"),
            URLQueryItem(name: "uid", value: String(client.session.puid)),
            URLQueryItem(name: "isTeacherViewOpen", value: "0")
        ]
        return components.chaoxingQueryURL!
    }

    public static func preSign(
        client: ChaoxingHTTPClient,
        activity: ChaoxingSignActivity
    ) async throws -> ChaoxingSignActivityStatus {
        let url = preSignURL(client: client, activity: activity)
        var (data, response) = try await client.postForm(url, items: [("ext", activity.extContent)])
        if response.statusCode == 403 || response.statusCode == 401 || response.statusCode == 302 || isLoginPage(data, response: response) {
            _ = try await ChaoxingAuthService.reLogin(client: client)
            (data, response) = try await client.postForm(url, items: [("ext", activity.extContent)])
            if response.statusCode == 403 || response.statusCode == 401 || isLoginPage(data, response: response) {
                throw ChaoxingError.sessionExpired()
            }
        }
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "预签到请求失败")
        }

        let body = String(data: data, encoding: .utf8) ?? ""
        if body.contains("校验失败，未查询到活动数据") {
            throw ChaoxingError.noPermission()
        }

        try Task.checkCancellation()
        if body.contains("下次早点哦") {
            return .expired
        }
        if let status = attendanceStatus(in: body) {
            if signedStatuses.contains(status) { return .alreadySigned }
            guard status == 0 else { throw ChaoxingError.parseFailed("无法确认预签到状态") }
        } else {
            // Accept an actual activity form, never an arbitrary successful HTTP document.
            let marker = #"(?:id|name)\s*=\s*["']activePrimaryId["']"#
            guard body.range(of: marker, options: .regularExpression) != nil else {
                throw ChaoxingError.parseFailed("预签到响应不是可识别的活动页面")
            }
        }
        try? await postAnalysis(client: client, activeId: activity.id)
        try Task.checkCancellation()
        return .readyToSign
    }

    private static func isLoginPage(_ data: Data, response: HTTPURLResponse) -> Bool {
        let host = response.url?.host?.lowercased() ?? ""
        if host == "passport2.chaoxing.com" || host == "passport.chaoxing.com" { return true }
        let html = String(data: data, encoding: .utf8) ?? ""
        return html.range(of: #"(?is)<title[^>]*>[^<]*登录|<input[^>]+type\s*=\s*["']password["']"#, options: .regularExpression) != nil
    }

    private static func attendanceStatus(in html: String) -> Int? {
        let patterns = [
            #""primaryAttend"\s*:\s*\{[^{}]*?"status"\s*:\s*(\d+)"#,
            #"signstatus\s*=\s*(\d+)"#
        ]
        for pattern in patterns {
            if let regex = try? NSRegularExpression(pattern: pattern),
               let match = regex.firstMatch(in: html, range: NSRange(html.startIndex..., in: html)),
               match.numberOfRanges > 1,
               let range = Range(match.range(at: 1), in: html),
               let status = Int(html[range]) {
                return status
            }
        }
        return nil
    }

    private static func postAnalysis(client: ChaoxingHTTPClient, activeId: Int64) async throws {
        var c1 = URLComponents(string: analysisBase)!
        var items1 = c1.queryItems ?? []
        items1.append(URLQueryItem(name: "aid", value: String(activeId)))
        c1.queryItems = items1
        let (data, _) = try await client.get(c1.chaoxingQueryURL!)
        let text = String(data: data, encoding: .utf8) ?? ""
        if let regex = try? NSRegularExpression(pattern: #"code='\+'([a-f0-9]+)'"#),
           let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
           match.numberOfRanges > 1,
           let range = Range(match.range(at: 1), in: text) {
            let code = String(text[range])
            var c2 = URLComponents(string: analysis2Base)!
            var items2 = c2.queryItems ?? []
            items2.append(URLQueryItem(name: "code", value: code))
            c2.queryItems = items2
            _ = try? await client.get(c2.chaoxingQueryURL!)
        }
    }

    public static func checkSignCode(
        client: ChaoxingHTTPClient,
        activeId: Int64,
        signCode: String
    ) async throws -> Bool {
        var components = URLComponents(string: checkSignCodeBase)!
        components.queryItems = [
            URLQueryItem(name: "activeId", value: String(activeId)),
            URLQueryItem(name: "signCode", value: signCode)
        ]
        let (data, response) = try await client.get(components.chaoxingQueryURL!)
        guard (200..<300).contains(response.statusCode),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ChaoxingError.parseFailed("校验签到码响应失败")
        }
        return (json["result"] as? NSNumber)?.intValue == 1
    }

    public static func isQRCodeExpired(
        client: ChaoxingHTTPClient,
        activeId: Int64,
        qrResult: ChaoxingQRCodeParseResult
    ) async -> Bool? {
        guard let code = qrResult.code else { return nil }
        var components = URLComponents(string: signDetailBase)!
        components.queryItems = [
            URLQueryItem(name: "activePrimaryId", value: String(qrResult.aid ?? activeId)),
            URLQueryItem(name: "type", value: "1"),
            URLQueryItem(name: "msg", value: code)
        ]
        guard let url = components.chaoxingQueryURL,
              let (data, response) = try? await client.get(url),
              (200..<300).contains(response.statusCode),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (json["result"] as? Bool) != false else {
            return nil
        }
        guard let isOver = (json["isOver"] as? NSNumber)?.intValue, isOver == 0 || isOver == 1 else { return nil }
        if isOver == 1 { return true }
        guard let signCode = json["signCode"] as? String, !signCode.isEmpty else { return nil }
        return signCode != code
    }

    public static func signPhotoOrClick(
        client: ChaoxingHTTPClient,
        activity: ChaoxingSignActivity,
        objectId: String? = nil,
        captchaValidate: String? = nil,
        enc2: String? = nil
    ) async throws -> ChaoxingSignOutcome {
        var items: [URLQueryItem] = [
            URLQueryItem(name: "activeId", value: String(activity.id)),
            URLQueryItem(name: "courseId", value: String(activity.courseId)),
            URLQueryItem(name: "uid", value: String(client.session.puid)),
            URLQueryItem(name: "clientip", value: ""),
            URLQueryItem(name: "useragent", value: ""),
            URLQueryItem(name: "latitude", value: "-1"),
            URLQueryItem(name: "longitude", value: "-1"),
            URLQueryItem(name: "appType", value: "15"),
            URLQueryItem(name: "fid", value: String(client.session.configuredFid)),
            URLQueryItem(name: "name", value: client.session.name),
            URLQueryItem(name: "deviceCode", value: client.session.deviceCode)
        ]
        if let objectId, !objectId.isEmpty {
            items.append(URLQueryItem(name: "objectId", value: objectId))
        }
        if let captchaValidate {
            items.append(URLQueryItem(name: "validate", value: captchaValidate))
        }
        if let enc2 {
            items.append(URLQueryItem(name: "enc2", value: enc2))
        }
        return try await submitSign(client: client, activity: activity, queryItems: items, location: nil)
    }

    public static func signLocation(
        client: ChaoxingHTTPClient,
        activity: ChaoxingSignActivity,
        location: ChaoxingLocationSignInfo,
        faceImageObjectId: String? = nil,
        captchaValidate: String? = nil,
        enc2: String? = nil
    ) async throws -> ChaoxingSignOutcome {
        var items: [URLQueryItem] = [
            URLQueryItem(name: "name", value: client.session.name),
            URLQueryItem(name: "address", value: location.address),
            URLQueryItem(name: "activeId", value: String(activity.id)),
            URLQueryItem(name: "courseId", value: String(activity.courseId)),
            URLQueryItem(name: "uid", value: String(client.session.puid)),
            URLQueryItem(name: "clientip", value: ""),
            URLQueryItem(name: "latitude", value: String(location.randomizedLatitude)),
            URLQueryItem(name: "longitude", value: String(location.randomizedLongitude)),
            URLQueryItem(name: "fid", value: String(client.session.configuredFid)),
            URLQueryItem(name: "appType", value: "15"),
            URLQueryItem(name: "ifTiJiao", value: "1"),
            URLQueryItem(name: "deviceCode", value: client.session.deviceCode),
            URLQueryItem(name: "vpProbability", value: "-1"),
            URLQueryItem(name: "vpStrategy", value: "")
        ]
        if let captchaValidate {
            items.append(URLQueryItem(name: "validate", value: captchaValidate))
        }
        if let enc2 {
            items.append(URLQueryItem(name: "enc2", value: enc2))
        }
        items.append(URLQueryItem(name: "locationResult", value: buildLocationJSON(location, includeMockData: true)))
        try await appendFaceParameters(
            to: &items,
            client: client,
            activeId: activity.id,
            faceImageObjectId: faceImageObjectId
        )
        return try await submitSign(client: client, activity: activity, queryItems: items, location: location)
    }

    public static func signQRCode(
        client: ChaoxingHTTPClient,
        activity: ChaoxingSignActivity,
        qrResult: ChaoxingQRCodeParseResult,
        location: ChaoxingLocationSignInfo? = nil,
        faceImageObjectId: String? = nil,
        captchaValidate: String? = nil,
        enc2: String? = nil
    ) async throws -> ChaoxingSignOutcome {
        guard activity.id > 0, qrResult.aid == nil || qrResult.aid == activity.id else {
            throw ChaoxingError.invalidInput("二维码不属于当前活动，请重新选择活动或扫描对应二维码")
        }
        let targetActiveId = activity.id
        var items: [URLQueryItem] = [
            URLQueryItem(name: "enc", value: qrResult.enc),
            URLQueryItem(name: "name", value: client.session.name),
            URLQueryItem(name: "activeId", value: String(targetActiveId)),
            URLQueryItem(name: "uid", value: String(client.session.puid)),
            URLQueryItem(name: "clientip", value: ""),
            URLQueryItem(name: "latitude", value: "-1"),
            URLQueryItem(name: "longitude", value: "-1"),
            URLQueryItem(name: "fid", value: String(client.session.configuredFid)),
            URLQueryItem(name: "appType", value: "15"),
            URLQueryItem(name: "deviceCode", value: client.session.deviceCode),
            URLQueryItem(name: "vpProbability", value: "-1"),
            URLQueryItem(name: "vpStrategy", value: ""),
            URLQueryItem(name: "courseId", value: String(activity.courseId))
        ]
        if let location {
            items.append(URLQueryItem(name: "location", value: buildLocationJSON(location, includeMockData: false)))
            items.append(URLQueryItem(name: "locationResult", value: buildLocationJSON(location, includeMockData: true)))
        }
        if let enc2 {
            items.append(URLQueryItem(name: "enc2", value: enc2))
        }
        if let captchaValidate {
            items.append(URLQueryItem(name: "validate", value: captchaValidate))
        }
        try await appendFaceParameters(
            to: &items,
            client: client,
            activeId: targetActiveId,
            faceImageObjectId: faceImageObjectId
        )
        return try await submitSign(client: client, activity: activity, queryItems: items, location: location)
    }

    public static func signCodeOrGesture(
        client: ChaoxingHTTPClient,
        activity: ChaoxingSignActivity,
        signCode: String,
        location: ChaoxingLocationSignInfo? = nil,
        captchaValidate: String? = nil,
        enc2: String? = nil
    ) async throws -> ChaoxingSignOutcome {
        let latStr = location.map { String(format: "%.6f", $0.randomizedLatitude) } ?? "-1"
        let lonStr = location.map { String(format: "%.6f", $0.randomizedLongitude) } ?? "-1"
        var items: [URLQueryItem] = [
            URLQueryItem(name: "activeId", value: String(activity.id)),
            URLQueryItem(name: "courseId", value: String(activity.courseId)),
            URLQueryItem(name: "uid", value: String(client.session.puid)),
            URLQueryItem(name: "clientip", value: ""),
            URLQueryItem(name: "latitude", value: latStr),
            URLQueryItem(name: "longitude", value: lonStr),
            URLQueryItem(name: "appType", value: "15"),
            URLQueryItem(name: "fid", value: String(client.session.configuredFid)),
            URLQueryItem(name: "name", value: client.session.name),
            URLQueryItem(name: "signCode", value: signCode),
            URLQueryItem(name: "deviceCode", value: client.session.deviceCode)
        ]
        if let location {
            items.append(URLQueryItem(name: "location", value: buildLocationJSON(location, includeMockData: false)))
            items.append(URLQueryItem(name: "locationResult", value: buildLocationJSON(location, includeMockData: true)))
        }
        if let enc2 {
            items.append(URLQueryItem(name: "enc2", value: enc2))
        }
        if let captchaValidate {
            items.append(URLQueryItem(name: "validate", value: captchaValidate))
        }
        return try await submitSign(client: client, activity: activity, queryItems: items, location: location)
    }

    private static func appendFaceParameters(
        to items: inout [URLQueryItem],
        client: ChaoxingHTTPClient,
        activeId: Int64,
        faceImageObjectId: String?
    ) async throws {
        guard let faceImageObjectId, !faceImageObjectId.isEmpty else { return }
        let faceEnc = try await ChaoxingFaceService.checkFaceResultAndGetEnc(
            client: client,
            objectId: faceImageObjectId,
            activeId: activeId
        )
        items.append(URLQueryItem(name: "currentFaceId", value: faceImageObjectId))
        items.append(URLQueryItem(name: "ifCFP", value: "0"))
        items.append(URLQueryItem(name: "faceEnc", value: faceEnc))
        items.append(URLQueryItem(name: "faceCode", value: ""))
        items.append(URLQueryItem(name: "faceEncAid", value: ""))
    }

    private static func buildLocationJSON(_ location: ChaoxingLocationSignInfo, includeMockData: Bool) -> String {
        let lat = (String(format: "%.6f", location.randomizedLatitude) as NSString).doubleValue
        let lon = (String(format: "%.6f", location.randomizedLongitude) as NSString).doubleValue
        var dict: [String: Any] = [
            "result": 1,
            "latitude": lat,
            "longitude": lon,
            "address": location.address
        ]
        if includeMockData {
            dict["mockData"] = "{\"strategy\":0,\"probability\":-1}"
        }
        if let data = try? JSONSerialization.data(withJSONObject: dict),
           let json = String(data: data, encoding: .utf8) {
            return json
        }
        return "{}"
    }

    private static func submitSign(
        client: ChaoxingHTTPClient,
        activity: ChaoxingSignActivity,
        queryItems: [URLQueryItem],
        location: ChaoxingLocationSignInfo?
    ) async throws -> ChaoxingSignOutcome {
        guard let url = URL(string: stuSignBase) else {
            throw ChaoxingError.parseFailed("签到请求 URL 无效")
        }
        let referer = preSignURL(client: client, activity: activity).absoluteString
        let formItems = queryItems.map { ($0.name, $0.value ?? "") }
        let (data, response) = try await client.postForm(
            url,
            items: formItems,
            headers: [
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "Referer": referer,
                "X-Requested-With": "XMLHttpRequest"
            ]
        )
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "签到请求失败")
        }
        let result = (String(data: data, encoding: .utf8) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        if result == "success" {
            return ChaoxingSignOutcome(disposition: .accepted, message: "本次请求已获接受")
        }
        if result == "success2" {
            throw ChaoxingError.signAlreadyEnded()
        }
        if result == "您已签到过了" {
            throw ChaoxingError.alreadySigned()
        }
        if result == "签到失败，请重新扫描。" || result == "fail-need-qrcode" {
            throw ChaoxingError.qrCodeExpired()
        }
        if result.hasPrefix("[face]") {
            throw ChaoxingError.faceError(String(result.dropFirst("[face]".count)))
        }
        if result.hasPrefix("errorLocation") {
            let parts = result.components(separatedBy: "_")
            let dist = parts.count > 1 ? Double(parts[1]) : nil
            throw ChaoxingError.wrongPosition(distanceMeters: dist)
        }
        if result.contains("同一设备不允许重复签到") {
            throw ChaoxingError.deviceDuplicated()
        }
        if result.hasPrefix("validate") {
            let enc2 = result.hasPrefix("validate_") ? String(result.dropFirst("validate_".count)) : nil
            throw ChaoxingError.captchaRequired(enc2: enc2?.isEmpty == false ? enc2 : nil)
        }
        if result.hasPrefix("checkFace_") {
            throw ChaoxingError.faceError("需要人脸识别校验: \(result)")
        }
        throw ChaoxingError.serverMessage(result.isEmpty ? "签到失败（空响应）" : result)
    }
}

// Chaoxing query endpoints use form decoding, where a literal '+' means a space.
// URLComponents escapes '%' and delimiters, but intentionally leaves '+' untouched.
extension URLComponents {
    var chaoxingQueryURL: URL? {
        var encoded = self
        encoded.percentEncodedQuery = percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        return encoded.url
    }
}
