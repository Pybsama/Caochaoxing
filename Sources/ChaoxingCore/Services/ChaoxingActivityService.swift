// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation

public enum ChaoxingActivityService {
    private static let activeListBase = "https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist?fid=0&showNotStartedActive=0"
    private static let signInfoBase = "https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo"
    public static let noSignOffEvent: Int64 = 4999

    public static func fetchCourseActivities(
        client: ChaoxingHTTPClient,
        course: ChaoxingCourse
    ) async throws -> [ChaoxingSignActivity] {
        guard var components = URLComponents(string: activeListBase) else {
            throw ChaoxingError.parseFailed("活动列表 URL 构建失败")
        }
        var queryItems = components.queryItems ?? []
        queryItems.append(URLQueryItem(name: "courseId", value: String(course.courseId)))
        queryItems.append(URLQueryItem(name: "classId", value: String(course.classId)))
        components.queryItems = queryItems
        guard let url = components.chaoxingQueryURL else {
            throw ChaoxingError.parseFailed("活动列表 URL 无效")
        }

        let (data, response) = try await client.get(url)
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "获取课程活动失败")
        }
        return try parseActivitiesJSON(data, course: course)
    }

    public static func parseActivitiesJSON(_ data: Data, course: ChaoxingCourse) throws -> [ChaoxingSignActivity] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ChaoxingError.parseFailed("课程活动 JSON 解析失败")
        }
        guard (root["result"] as? Bool) != false,
              let dataObj = root["data"] as? [String: Any] else {
            let errMsg = (root["errorMsg"] as? String) ?? "解析课程活动数据失败"
            throw ChaoxingError.parseFailed(errMsg)
        }
        guard let rawList = dataObj["activeList"] as? [[String: Any]] else {
            let errMsg = (root["errorMsg"] as? String) ?? "解析活动列表失败"
            throw ChaoxingError.parseFailed(errMsg)
        }

        let extContent: String
        if let extObj = dataObj["ext"],
           JSONSerialization.isValidJSONObject(extObj),
           let extData = try? JSONSerialization.data(withJSONObject: extObj),
           let extStr = String(data: extData, encoding: .utf8) {
            extContent = extStr
        } else {
            extContent = "{}"
        }

        return rawList.compactMap { item -> ChaoxingSignActivity? in
            let type = (item["type"] as? NSNumber)?.intValue ?? 0
            guard type == 2 || type == 74 else { return nil }

            let id = (item["id"] as? NSNumber)?.int64Value ?? 0
            let startTime = (item["startTime"] as? NSNumber)?.int64Value
            let endTime = (item["endTime"] as? NSNumber)?.int64Value
            let userStatus = (item["userStatus"] as? NSNumber)?.intValue ?? 0
            let otherId = (item["otherId"] as? String) ?? ((item["otherId"] as? NSNumber)?.stringValue ?? "")
            let isLook = (item["isLook"] as? NSNumber)?.intValue == 1
            let activeType = (item["activeType"] as? NSNumber)?.intValue ?? 0
            let nameOne = (item["nameOne"] as? String) ?? "签到活动"
            let status = (item["status"] as? NSNumber)?.intValue ?? 0
            let nameFour = (item["nameFour"] as? String) ?? ""

            return ChaoxingSignActivity(
                id: id,
                startTimeMs: startTime,
                endTimeMs: endTime,
                userStatus: userStatus,
                otherId: otherId,
                isLook: isLook,
                type: type,
                activeType: activeType,
                nameOne: nameOne,
                status: status,
                nameFour: nameFour,
                courseId: course.courseId,
                classId: course.classId,
                courseName: course.courseName,
                extContent: extContent
            )
        }
    }

    public static func fetchSignDetail(
        client: ChaoxingHTTPClient,
        activeId: Int64,
        fallbackClassId: Int = 0,
        fallbackCourseId: Int64 = 0
    ) async throws -> ChaoxingSignDetailMetadata {
        guard var components = URLComponents(string: signInfoBase) else {
            throw ChaoxingError.parseFailed("活动详情 URL 构建失败")
        }
        components.queryItems = [URLQueryItem(name: "activeId", value: String(activeId))]
        guard let url = components.chaoxingQueryURL else {
            throw ChaoxingError.parseFailed("活动详情 URL 无效")
        }

        var (data, response) = try await client.get(url)
        var root = (200..<300).contains(response.statusCode)
            ? (try? JSONSerialization.jsonObject(with: data) as? [String: Any])
            : nil
        var info = (root?["result"] as? Bool) != false ? (root?["data"] as? [String: Any]) : nil
        let needsReLogin = response.statusCode == 401 || response.statusCode == 403 || response.statusCode == 302
            || ((200..<300).contains(response.statusCode) && (root == nil || (root?["result"] as? Bool) == false || info == nil))
        if needsReLogin {
            if (try? await ChaoxingAuthService.reLogin(client: client)) != nil {
                (data, response) = try await client.get(url)
                root = (200..<300).contains(response.statusCode)
                    ? (try? JSONSerialization.jsonObject(with: data) as? [String: Any])
                    : nil
                info = (root?["result"] as? Bool) != false ? (root?["data"] as? [String: Any]) : nil
            }
        }
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "获取签到详情失败")
        }
        guard let info,
              info["otherId"] is NSNumber || info["otherId"] is String else {
            throw ChaoxingError.parseFailed("获取签到活动信息失败")
        }

        let otherIdStr = (info["otherId"] as? NSNumber)?.stringValue ?? (info["otherId"] as? String) ?? ""
        guard !otherIdStr.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ChaoxingError.parseFailed("签到详情缺少活动类型")
        }
        let signType = ChaoxingSignType(otherId: otherIdStr)
        let title = (info["name"] as? String) ?? (info["title"] as? String) ?? signType.displayName
        let classId = parseInt(info["clazzid"]) ?? parseInt(info["clazzId"]) ?? fallbackClassId
        var courseId = parseInt64(info["courseId"]) ?? parseInt64(info["courseid"]) ?? fallbackCourseId
        if courseId == 0 && classId != 0 {
            courseId = await ChaoxingCourseService.getCourseIdFromClassId(client: client, classId: classId) ?? 0
        }

        let isCaptcha = parseBoolFlag(info["ifNeedVCode"])
        let isFace = parseBoolFlag(info["openCheckFaceFlag"])
        let isPosition = parseBoolFlag(info["ifopenAddress"])
        let isPhoto = parseBoolFlag(info["ifphoto"])
        let isRefreshQRCode = parseBoolFlag(info["ifrefreshewm"])
        let numberCount = parseInt(info["numberCount"])

        let targetLat = parseDouble(info["locationLatitude"])
        let targetLon = parseDouble(info["locationLongitude"])
        let rangeMeters = parseInt(info["locationRange"])
        let targetLocation: ChaoxingLocationTarget? = (targetLat != nil || targetLon != nil || rangeMeters != nil)
            ? ChaoxingLocationTarget(latitude: targetLat, longitude: targetLon, rangeMeters: rangeMeters)
            : nil

        let signInId = parseInt64(info["signInId"])
        let signOutId = parseInt64(info["signOutId"])
        let rawSignOutTs = parseInt64(info["signOutPublishTimeStamp"])
        let signOutPublishTimeMs = (rawSignOutTs == nil || rawSignOutTs == -1 || rawSignOutTs == noSignOffEvent)
            ? nil
            : rawSignOutTs

        let signOutInfo = ChaoxingSignOutInfo(
            signInId: signInId,
            signOutId: signOutId,
            signOutPublishTimeMs: signOutPublishTimeMs,
            classId: classId,
            courseId: courseId
        )

        return ChaoxingSignDetailMetadata(
            activeId: activeId,
            signType: signType,
            title: title,
            isCaptchaRequired: isCaptcha,
            isFaceRequired: isFace,
            isPositionRequired: isPosition,
            isPhotoRequired: isPhoto,
            isRefreshQRCode: isRefreshQRCode,
            numberCount: numberCount,
            targetLocation: targetLocation,
            signOutInfo: signOutInfo
        )
    }

    public static func buildActivityFromQRCode(
        client: ChaoxingHTTPClient,
        qrResult: ChaoxingQRCodeParseResult
    ) async throws -> ChaoxingSignActivity {
        try await buildActivityAndDetailFromQRCode(client: client, qrResult: qrResult).0
    }

    public static func buildActivityAndDetailFromQRCode(
        client: ChaoxingHTTPClient,
        qrResult: ChaoxingQRCodeParseResult
    ) async throws -> (ChaoxingSignActivity, ChaoxingSignDetailMetadata) {
        guard let aid = qrResult.aid else {
            throw ChaoxingError.qrCodeParseFailed("二维码中未包含签到活动 ID")
        }
        let detail = try await fetchSignDetail(client: client, activeId: aid)
        let activity = ChaoxingSignActivity(
            id: aid,
            startTimeMs: nil,
            endTimeMs: nil,
            userStatus: 0,
            otherId: detail.signType.rawValue,
            isLook: true,
            type: 2,
            activeType: 2,
            nameOne: detail.title,
            status: 1,
            nameFour: "",
            courseId: detail.signOutInfo.courseId,
            classId: detail.signOutInfo.classId,
            courseName: "二维码扫码活动",
            extContent: "{}"
        )
        return (activity, detail)
    }

    private static func parseInt(_ raw: Any?) -> Int? {
        if raw is Bool { return nil }
        if let num = raw as? NSNumber { return num.intValue }
        if let str = raw as? String { return Int(str.trimmingCharacters(in: .whitespacesAndNewlines)) }
        return nil
    }

    private static func parseInt64(_ raw: Any?) -> Int64? {
        if raw is Bool { return nil }
        if let num = raw as? NSNumber { return num.int64Value }
        if let str = raw as? String { return Int64(str.trimmingCharacters(in: .whitespacesAndNewlines)) }
        return nil
    }

    private static func parseDouble(_ raw: Any?) -> Double? {
        if raw is Bool { return nil }
        if let num = raw as? NSNumber {
            let value = num.doubleValue
            return value.isFinite ? value : nil
        }
        if let str = raw as? String,
           let value = Double(str.trimmingCharacters(in: .whitespacesAndNewlines)),
           value.isFinite {
            return value
        }
        return nil
    }

    private static func parseBoolFlag(_ raw: Any?) -> Bool {
        if let b = raw as? Bool { return b }
        if let num = raw as? NSNumber { return num.intValue == 1 }
        if let str = raw as? String {
            let trimmed = str.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            return trimmed == "1" || trimmed == "true"
        }
        return false
    }
}
