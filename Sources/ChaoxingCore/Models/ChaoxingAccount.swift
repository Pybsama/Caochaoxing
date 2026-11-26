// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.
// Contains Swift adaptations of ChaoxingSignFaker protocol logic.
// Upstream copyright (c) 2025-2026 aquamarine5 and contributors.
// Adapted for Apple platforms; notices updated 2026-11-14.
// See THIRD_PARTY_NOTICES.md for attribution and changes.

import Foundation

public struct ChaoxingStoredCookie: Codable, Sendable, Hashable {
    public var name: String
    public var value: String
    public var domain: String
    public var path: String
    public var isSecure: Bool
    public var expiresAt: Date?
    public var hostOnly: Bool

    public init(name: String, value: String, domain: String, path: String = "/",
                isSecure: Bool = false, expiresAt: Date? = nil, hostOnly: Bool = false) {
        self.name = name
        self.value = value
        self.domain = domain
        self.path = path
        self.isSecure = isSecure
        self.expiresAt = expiresAt
        self.hostOnly = hostOnly
    }

    private enum CodingKeys: String, CodingKey { case name, value, domain, path, isSecure, expiresAt, hostOnly }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        value = try c.decode(String.self, forKey: .value)
        domain = try c.decode(String.self, forKey: .domain)
        path = try c.decodeIfPresent(String.self, forKey: .path) ?? "/"
        isSecure = try c.decodeIfPresent(Bool.self, forKey: .isSecure) ?? false
        expiresAt = try c.decodeIfPresent(Date.self, forKey: .expiresAt)
        // Foundation stored explicit Domain cookies with a leading dot. Old host cookies
        // can therefore retain their exact-host scope; the historical manually-added
        // chaoxing.com fid cookie is the one root-domain compatibility exception.
        hostOnly = try c.decodeIfPresent(Bool.self, forKey: .hostOnly)
            ?? (!domain.hasPrefix(".") && domain.lowercased() != "chaoxing.com")
    }
}

public struct ChaoxingSchool: Codable, Sendable, Hashable, Identifiable {
    public var id: Int { fid }
    public let fid: Int
    public let name: String

    public init(fid: Int, name: String) {
        self.fid = fid
        self.name = name
    }
}

public struct ChaoxingUserProfile: Codable, Sendable, Hashable {
    public let uid: Int
    public let puid: Int
    public let fid: Int
    public let name: String
    public let uname: String
    public let avatarURL: String
    public let phoneNumber: String
    public let imEncryptedPassword: String
    public let clientId: String?
    public let schools: [ChaoxingSchool]

    public init(
        uid: Int,
        puid: Int,
        fid: Int,
        name: String,
        uname: String,
        avatarURL: String,
        phoneNumber: String,
        imEncryptedPassword: String,
        clientId: String?,
        schools: [ChaoxingSchool]
    ) {
        self.uid = uid
        self.puid = puid
        self.fid = fid
        self.name = name
        self.uname = uname
        self.avatarURL = avatarURL
        self.phoneNumber = phoneNumber
        self.imEncryptedPassword = imEncryptedPassword
        self.clientId = clientId
        self.schools = schools
    }
}

public struct ChaoxingAccountSession: Codable, Sendable, Hashable, Identifiable {
    public var id: String { phoneNumber }
    public let phoneNumber: String
    public var encryptedPassword: String
    public var name: String
    public var uid: Int
    public var puid: Int
    public var configuredFid: Int
    public var schools: [ChaoxingSchool]
    public var avatarURL: String
    public var imEncryptedPassword: String
    public var clientId: String?
    public var deviceCode: String
    public var cookies: [ChaoxingStoredCookie]
    public var isPrimary: Bool
    public var lastLoginAt: Date
    public var tags: [String]

    public init(
        phoneNumber: String,
        encryptedPassword: String,
        name: String = "",
        uid: Int = 0,
        puid: Int = 0,
        configuredFid: Int = 0,
        schools: [ChaoxingSchool] = [],
        avatarURL: String = "",
        imEncryptedPassword: String = "",
        clientId: String? = nil,
        deviceCode: String,
        cookies: [ChaoxingStoredCookie] = [],
        isPrimary: Bool = false,
        lastLoginAt: Date = Date(),
        tags: [String] = []
    ) {
        self.phoneNumber = phoneNumber
        self.encryptedPassword = encryptedPassword
        self.name = name
        self.uid = uid
        self.puid = puid
        self.configuredFid = configuredFid
        self.schools = schools
        self.avatarURL = avatarURL
        self.imEncryptedPassword = imEncryptedPassword
        self.clientId = clientId
        self.deviceCode = deviceCode
        self.cookies = cookies
        self.isPrimary = isPrimary
        self.lastLoginAt = lastLoginAt
        self.tags = tags
    }

    enum CodingKeys: String, CodingKey {
        case phoneNumber, encryptedPassword, name, uid, puid, configuredFid, schools, avatarURL
        case imEncryptedPassword, clientId, deviceCode, cookies, isPrimary, lastLoginAt, tags
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        phoneNumber = try c.decode(String.self, forKey: .phoneNumber)
        encryptedPassword = try c.decode(String.self, forKey: .encryptedPassword)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        uid = try c.decodeIfPresent(Int.self, forKey: .uid) ?? 0
        puid = try c.decodeIfPresent(Int.self, forKey: .puid) ?? 0
        configuredFid = try c.decodeIfPresent(Int.self, forKey: .configuredFid) ?? 0
        schools = try c.decodeIfPresent([ChaoxingSchool].self, forKey: .schools) ?? []
        avatarURL = try c.decodeIfPresent(String.self, forKey: .avatarURL) ?? ""
        imEncryptedPassword = try c.decodeIfPresent(String.self, forKey: .imEncryptedPassword) ?? ""
        clientId = try c.decodeIfPresent(String.self, forKey: .clientId)
        deviceCode = try c.decode(String.self, forKey: .deviceCode)
        cookies = try c.decodeIfPresent([ChaoxingStoredCookie].self, forKey: .cookies) ?? []
        isPrimary = try c.decodeIfPresent(Bool.self, forKey: .isPrimary) ?? false
        lastLoginAt = try c.decodeIfPresent(Date.self, forKey: .lastLoginAt) ?? Date()
        tags = try c.decodeIfPresent([String].self, forKey: .tags) ?? []
    }
}

public struct ChaoxingCustomClientProfile: Codable, Sendable, Hashable, Identifiable {
    public let id: UUID
    public var name: String
    public var userAgent: String

    public init(id: UUID = UUID(), name: String, userAgent: String) {
        self.id = id
        self.name = name
        self.userAgent = userAgent
    }
}

public enum ChaoxingClientPreset: String, CaseIterable, Codable, Sendable, Identifiable {
    case `default` = "@@default"
    case officialWebView = "@@official_webview"
    case xueZaiXiDian = "@@xuezaixidian"

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .default: return "官方通用版"
        case .officialWebView: return "官方 WebView 版"
        case .xueZaiXiDian: return "学在西电定制版"
        }
    }

    public var userAgent: String {
        switch self {
        case .default:
            return "Dalvik/2.1.0 (Linux; U; Android 12; SM-N9006 Build/8aba9e4.0) (schild:2d97f7b9439f21333c946878fc4d6ccb) (device:SM-N9006) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_6.7.5_android_phone_10941_314 (@Kalimdor)_68f184fd763546c1a04ab3a09b3deebb"
        case .officialWebView:
            return "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/128.0.6613.127 Mobile Safari/537.36 (schild:2d97f7b9439f21333c946878fc4d6ccb) (device:Pixel 8) Language/zh_CN com.chaoxing.mobile/ChaoXingStudy_3_6.7.5_android_phone_10941_314 (@Kalimdor)_68f184fd763546c1a04ab3a09b3deebb"
        case .xueZaiXiDian:
            return "Mozilla/5.0 (Linux; Android 16; 23113RKC6C Build/BP2A.250605.031.A3; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/147.0.7727.137 Mobile Safari/537.36 (schild:be536573b69ec1ae359e359d11f7f3e3) (device:23113RKC6C) Language/zh_CN com.chaoxing.mobile.xuezaixidian/ChaoXingStudy_1000149_6.3.7_android_phone_6005_249 (@Kalimdor)_f8777230ca1e45b2831ec7e36a9da1ea"
        }
    }
}
