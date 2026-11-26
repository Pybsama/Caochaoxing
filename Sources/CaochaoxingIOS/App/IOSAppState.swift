// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation
import SwiftUI
import ChaoxingCore

public struct IOSCaptchaChallengeRequest: Identifiable {
    public let id: UUID
    public let client: ChaoxingHTTPClient
    public let refererURL: String
}

public struct IOSSessionToken: Equatable, Sendable {
    fileprivate let generation: UInt64
    fileprivate let phoneNumber: String?
}

public struct IOSAppDependencies: Sendable {
    public var login: @Sendable (String, String, String, Bool) async throws -> ChaoxingAccountSession
    public var fetchCourses: @Sendable (ChaoxingHTTPClient) async throws -> [ChaoxingCourse]
    public var fetchIMConfig: @Sendable (ChaoxingHTTPClient) async throws -> ChaoxingEasemobIMConfig
    public var fetchGroups: @Sendable (ChaoxingHTTPClient, ChaoxingEasemobIMConfig) async throws -> [ChaoxingIMGroup]
    public var fetchActivities: @Sendable (ChaoxingHTTPClient, ChaoxingCourse) async throws -> [ChaoxingSignActivity]
    public var fetchDetail: @Sendable (ChaoxingHTTPClient, Int64, Int, Int64) async throws -> ChaoxingSignDetailMetadata
    public var prepareSign: @Sendable (ChaoxingHTTPClient, ChaoxingSignActivity) async throws -> ChaoxingSignActivityStatus
    public var sleep: @Sendable (Int) async throws -> Void

    public static let live = IOSAppDependencies(
        login: { try await ChaoxingAuthService.login(phoneNumber: $0, password: $1, userAgent: $2, isPrimary: $3) },
        fetchCourses: { try await ChaoxingCourseService.fetchCourses(client: $0) },
        fetchIMConfig: { try await ChaoxingGroupService.getEasemobConfig(client: $0) },
        fetchGroups: { try await ChaoxingGroupService.fetchJoinedGroups(client: $0, imConfig: $1) },
        fetchActivities: { try await ChaoxingActivityService.fetchCourseActivities(client: $0, course: $1) },
        fetchDetail: { try await ChaoxingActivityService.fetchSignDetail(client: $0, activeId: $1, fallbackClassId: $2, fallbackCourseId: $3) },
        prepareSign: { try await ChaoxingSignExecutor.preSign(client: $0, activity: $1) },
        sleep: { try await Task.sleep(for: .milliseconds($0)) }
    )
}

public struct IOSBatchSignResult: Identifiable, Sendable {
    public let id = UUID()
    public let accountName: String
    public let phoneNumber: String
    public let disposition: ChaoxingSignDisposition
    public var success: Bool { disposition == .accepted }
    public let message: String
}

@MainActor
@Observable
public final class IOSAppState {
    public let store: ChaoxingPersistenceStore
    private let dependencies: IOSAppDependencies
    @ObservationIgnored private let studyDependencies: IOSCourseStudyDependencies
    @ObservationIgnored private var _studyController: IOSCourseStudyController?
    public var studyController: IOSCourseStudyController {
        if let existing = _studyController { return existing }
        let created = IOSCourseStudyController(
            clientProvider: { [weak self] in self?.activeClient },
            dependencies: studyDependencies
        )
        created.onAccountChanged(phoneNumber: activeAccount?.phoneNumber)
        _studyController = created
        return created
    }
    public private(set) var isRestoring: Bool
    public private(set) var persistenceBlocked = false
    private var sessionGeneration: UInt64 = 0
    private var courseRequest = UUID()
    private var groupRequest = UUID()
    private var radarRequest = UUID()
    private var deepLinkRequest = UUID()
    private var pendingIncomingURL: URL?
    private var loadingRequests: Set<UUID> = []
    private var sessionTasks: [UUID: Task<Void, Never>] = [:]
    private var captchaQueue: [IOSCaptchaChallengeRequest] = []
    private var captchaContinuations: [UUID: CheckedContinuation<String, Error>] = [:]
    private var presentedCaptchaID: UUID?
    private var visibleCaptchaID: UUID?

    public var accounts: [ChaoxingAccountSession] = []
    public var activePhoneNumber: String?
    public var locationPresets: [ChaoxingLocationPreset] = []
    public var selectedClientPreset: ChaoxingClientPreset = .default
    public var customUserAgent: String = ""
    public var customClientProfiles: [ChaoxingCustomClientProfile] = []
    public var selectedCustomClientId: UUID?

    // 防风控与增强配置
    public var enableLocationJitter: Bool = true
    public var batchSignDelayMs: Int = 1200
    public var enableImageObfuscation: Bool = true
    public var autoSolveCaptcha: Bool = true
    public var signHistory: [ChaoxingSignHistoryEntry] = []
    public var courseLocationBindings: [String: ChaoxingLocationPreset] = [:]
    public var starredCourseIds: Set<Int64> = []

    // 雷达扫描结果
    public var pendingRadarActivities: [ChaoxingSignActivity] = []
    public var isScanningRadar = false

    // Deep Link & 导航状态
    public var selectedTab: Int = 0
    public var deepLinkActivity: ChaoxingSignActivity?
    public var pendingQRCodeInput: String?

    public var courses: [ChaoxingCourse] = []
    public var groups: [ChaoxingIMGroup] = []
    public var imConfig: ChaoxingEasemobIMConfig?

    public var isLoading = false
    public var statusMessage: String?
    public var errorMessage: String?
    public var captchaChallenge: IOSCaptchaChallengeRequest?

    private var clientPool: [String: ChaoxingHTTPClient] = [:]

    public var currentUserAgent: String {
        if let selectedCustomClientId,
           let profile = customClientProfiles.first(where: { $0.id == selectedCustomClientId }) {
            let ua = profile.userAgent.trimmingCharacters(in: .whitespacesAndNewlines)
            if !ua.isEmpty { return ua }
        }
        let trimmed = customUserAgent.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? selectedClientPreset.userAgent : trimmed
    }

    public var activeAccount: ChaoxingAccountSession? {
        if let activePhoneNumber, let found = accounts.first(where: { $0.phoneNumber == activePhoneNumber }) {
            return found
        }
        return accounts.first(where: { $0.isPrimary }) ?? accounts.first
    }

    public var activeClient: ChaoxingHTTPClient? {
        guard let account = activeAccount else { return nil }
        return client(for: account)
    }

    public init(
        store: ChaoxingPersistenceStore = ChaoxingPersistenceStore(directoryName: "Caochaoxing"),
        dependencies: IOSAppDependencies = .live,
        studyDependencies: IOSCourseStudyDependencies = .live,
        autoLoad: Bool = true
    ) {
        self.store = store
        self.dependencies = dependencies
        self.studyDependencies = studyDependencies
        self.isRestoring = autoLoad
        if autoLoad {
            Task { await loadFromDisk() }
        }
    }

    public func client(for session: ChaoxingAccountSession) -> ChaoxingHTTPClient {
        if let existing = clientPool[session.phoneNumber] {
            existing.userAgent = currentUserAgent
            return existing
        }
        let newClient = ChaoxingHTTPClient(session: session, userAgent: currentUserAgent)
        clientPool[session.phoneNumber] = newClient
        return newClient
    }

    public func loadFromDisk() async {
        let generation = sessionGeneration
        isRestoring = true
        defer { isRestoring = false }
        do {
            let snapshot = try await store.load()
            guard generation == sessionGeneration else { return }
            accounts = snapshot.accounts
            for client in clientPool.values { client.invalidate() }
            clientPool.removeAll()
            locationPresets = snapshot.locationPresets
            selectedClientPreset = ChaoxingClientPreset(rawValue: snapshot.selectedClientPreset) ?? .default
            customUserAgent = snapshot.customUserAgent
            customClientProfiles = snapshot.customClientProfiles
            selectedCustomClientId = snapshot.selectedCustomClientId
            enableLocationJitter = snapshot.enableLocationJitter
            batchSignDelayMs = min(max(snapshot.batchSignDelayMs, 0), 60_000)
            enableImageObfuscation = snapshot.enableImageObfuscation
            autoSolveCaptcha = snapshot.autoSolveCaptcha
            signHistory = snapshot.signHistory
            courseLocationBindings = snapshot.courseLocationBindings
            starredCourseIds = Set(snapshot.starredCourseIds)
            activePhoneNumber = (accounts.first(where: { $0.isPrimary }) ?? accounts.first)?.phoneNumber
            _studyController?.onAccountChanged(phoneNumber: activePhoneNumber)
            persistenceBlocked = false
            isRestoring = false
            await consumePendingURL()
            if activeClient != nil { await refreshCourses() }
        } catch {
            guard generation == sessionGeneration else { return }
            persistenceBlocked = true
            errorMessage = "读取本地会话失败，原文件已保留；请先恢复或移走损坏文件再重试。\(error.localizedDescription)"
        }
    }

    @discardableResult
    public func saveToDisk() async -> Bool {
        guard !isRestoring, !persistenceBlocked else {
            errorMessage = "本地状态尚未成功读取，已阻止覆盖原文件"
            return false
        }
        let updatedAccounts = accounts.map { acc in
            var updated = clientPool[acc.phoneNumber]?.session ?? acc
            updated.isPrimary = acc.isPrimary
            updated.tags = acc.tags
            return updated
        }
        self.accounts = updatedAccounts
        let snapshot = ChaoxingAppStateSnapshot(
            accounts: updatedAccounts,
            locationPresets: locationPresets,
            selectedClientPreset: selectedClientPreset.rawValue,
            customUserAgent: customUserAgent,
            customClientProfiles: customClientProfiles,
            selectedCustomClientId: selectedCustomClientId,
            enableLocationJitter: enableLocationJitter,
            batchSignDelayMs: batchSignDelayMs,
            enableImageObfuscation: enableImageObfuscation,
            autoSolveCaptcha: autoSolveCaptcha,
            signHistory: signHistory,
            courseLocationBindings: courseLocationBindings,
            starredCourseIds: Array(starredCourseIds).sorted()
        )
        do {
            try await store.save(snapshot)
            return true
        } catch {
            errorMessage = "保存本地状态失败：\(error.localizedDescription)"
            return false
        }
    }

    public func loginPrimary(phoneNumber: String, password: String) async throws {
        try requireReadyState()
        let token = sessionToken
        let loading = beginLoading()
        defer { endLoading(loading) }
        let session = try await dependencies.login(
            phoneNumber,
            password,
            currentUserAgent,
            true
        )
        try checkSession(token)
        invalidateSessionOperations()
        accounts.removeAll { $0.phoneNumber == session.phoneNumber }
        accounts = accounts.map { acc in
            var copy = acc
            copy.isPrimary = false
            return copy
        }
        accounts.insert(session, at: 0)
        clientPool[session.phoneNumber]?.invalidate()
        clientPool[session.phoneNumber] = ChaoxingHTTPClient(session: session, userAgent: currentUserAgent)
        activePhoneNumber = session.phoneNumber
        _studyController?.onAccountChanged(phoneNumber: activePhoneNumber)
        await saveToDisk()
        await consumePendingURL()
        await refreshCourses()
    }

    public func addOtherAccount(phoneNumber: String, password: String) async throws {
        try requireReadyState()
        let token = sessionToken
        let loading = beginLoading()
        defer { endLoading(loading) }
        let session = try await dependencies.login(
            phoneNumber,
            password,
            currentUserAgent,
            false
        )
        try checkSession(token)
        let replacesSession = accounts.contains { $0.phoneNumber == session.phoneNumber }
        if replacesSession { invalidateSessionOperations() }
        if let idx = accounts.firstIndex(where: { $0.phoneNumber == session.phoneNumber }) {
            let wasPrimary = accounts[idx].isPrimary
            var updated = session
            updated.isPrimary = wasPrimary
            updated.tags = accounts[idx].tags
            accounts[idx] = updated
        } else {
            accounts.append(session)
        }
        clientPool[session.phoneNumber]?.invalidate()
        clientPool[session.phoneNumber] = ChaoxingHTTPClient(session: accounts.first { $0.phoneNumber == session.phoneNumber }!, userAgent: currentUserAgent)
        await saveToDisk()
        if replacesSession { await refreshCourses() }
    }

    public func removeAccount(_ session: ChaoxingAccountSession) async {
        invalidateSessionOperations()
        accounts.removeAll { $0.phoneNumber == session.phoneNumber }
        clientPool[session.phoneNumber]?.invalidate()
        clientPool.removeValue(forKey: session.phoneNumber)
        if activePhoneNumber == session.phoneNumber {
            activePhoneNumber = accounts.first?.phoneNumber
            courses = []
            groups = []
            imConfig = nil
            _studyController?.onAccountChanged(phoneNumber: activePhoneNumber)
        }
        await saveToDisk()
        await refreshCourses()
    }

    public func switchActiveAccount(to session: ChaoxingAccountSession) async {
        guard accounts.contains(where: { $0.phoneNumber == session.phoneNumber }) else { return }
        invalidateSessionOperations()
        activePhoneNumber = session.phoneNumber
        courses = []
        groups = []
        imConfig = nil
        _studyController?.onAccountChanged(phoneNumber: activePhoneNumber)
        await refreshCourses()
        let displayName = session.name.isEmpty ? session.phoneNumber : session.name
        statusMessage = "已切换至账号「\(displayName)」"
    }

    public func refreshActiveAccountSession() async {
        guard let account = activeAccount, let client = activeClient else { return }
        let token = sessionToken
        let loading = beginLoading()
        defer { endLoading(loading) }
        do {
            let refreshed = try await ChaoxingAuthService.reLogin(client: client)
            try checkSession(token)
            if let idx = accounts.firstIndex(where: { $0.phoneNumber == account.phoneNumber }) {
                var updated = refreshed
                updated.isPrimary = accounts[idx].isPrimary
                updated.tags = accounts[idx].tags
                accounts[idx] = updated
            }
            await saveToDisk()
            await refreshCourses()
            statusMessage = "已刷新「\(account.name.isEmpty ? account.phoneNumber : account.name)」登录会话"
        } catch is CancellationError {
            return
        } catch {
            if isCurrent(token) {
                errorMessage = "刷新会话失败：\(error.localizedDescription)"
            }
        }
    }

    public func updateFid(for session: ChaoxingAccountSession, fid: Int) async {
        guard accounts.contains(where: { $0.phoneNumber == session.phoneNumber }) else { return }
        invalidateSessionOperations()
        let httpClient = client(for: session)
        httpClient.updateConfiguredFid(fid)
        if let idx = accounts.firstIndex(where: { $0.phoneNumber == session.phoneNumber }) {
            accounts[idx] = httpClient.session
        }
        await saveToDisk()
        await refreshCourses()
        statusMessage = "已切换学校单位 (fid: \(fid))"
    }

    public func refreshCourses() async {
        guard let client = activeClient else { return }
        let token = sessionToken
        let request = UUID()
        courseRequest = request
        let loading = beginLoading()
        defer { endLoading(loading) }
        do {
            let result = try await dependencies.fetchCourses(client)
            try checkSession(token)
            guard courseRequest == request else { return }
            courses = result
            await saveToDisk()
        } catch {
            if isCurrent(token), courseRequest == request, !(error is CancellationError) {
                errorMessage = error.localizedDescription
            }
        }
    }

    fileprivate func fetchActivities(for course: ChaoxingCourse, session: IOSSessionToken) async throws -> [ChaoxingSignActivity] {
        try checkSession(session)
        guard let client = activeClient else { throw ChaoxingError.sessionExpired("请先登录后再加载活动") }
        let activities = try await dependencies.fetchActivities(client, course)
        try checkSession(session)
        return activities
    }

    public func groupConfiguration(client: ChaoxingHTTPClient, forceRefresh: Bool = false) async throws -> ChaoxingEasemobIMConfig {
        let token = sessionToken
        try checkSession(token)
        guard client.session.phoneNumber == activeAccount?.phoneNumber else { throw CancellationError() }
        if forceRefresh { imConfig = nil }
        if let existing = imConfig { return existing }
        let config = try await dependencies.fetchIMConfig(client)
        try checkSession(token)
        imConfig = config
        return config
    }

    public func refreshGroups() async {
        guard let client = activeClient else { return }
        let token = sessionToken
        let request = UUID()
        groupRequest = request
        let loading = beginLoading()
        defer { endLoading(loading) }
        do {
            var config = try await groupConfiguration(client: client)
            try checkSession(token)
            guard groupRequest == request else { return }
            let result: [ChaoxingIMGroup]
            do { result = try await dependencies.fetchGroups(client, config) }
            catch ChaoxingError.sessionExpired {
                try checkSession(token)
                guard groupRequest == request else { return }
                config = try await groupConfiguration(client: client, forceRefresh: true)
                try checkSession(token)
                result = try await dependencies.fetchGroups(client, config)
            }
            try checkSession(token)
            guard groupRequest == request else { return }
            groups = result
        } catch {
            if isCurrent(token), groupRequest == request, !(error is CancellationError) {
                if case ChaoxingError.sessionExpired = error { imConfig = nil }
                errorMessage = error.localizedDescription
            }
        }
    }

    @discardableResult
    public func addLocationPreset(name: String, address: String, latitude: Double, longitude: Double) async -> Bool {
        guard latitude.isFinite, longitude.isFinite, (-90...90).contains(latitude), (-180...180).contains(longitude) else {
            errorMessage = "请输入有效坐标：纬度 −90～90，经度 −180～180"
            return false
        }
        let preset = ChaoxingLocationPreset(name: name, address: address, latitude: latitude, longitude: longitude)
        locationPresets.append(preset)
        guard await saveToDisk() else {
            locationPresets.removeAll { $0.id == preset.id }
            return false
        }
        statusMessage = "已保存常用位置「\(name)」"
        return true
    }

    public func removeLocationPreset(_ preset: ChaoxingLocationPreset) async {
        locationPresets.removeAll { $0.id == preset.id }
        await saveToDisk()
    }

    public func addCustomClientProfile(name: String, userAgent: String) async {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedUA = userAgent.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty, !trimmedUA.isEmpty else { return }
        let profile = ChaoxingCustomClientProfile(name: trimmedName, userAgent: trimmedUA)
        customClientProfiles.append(profile)
        selectedCustomClientId = profile.id
        await saveToDisk()
        statusMessage = "已保存并切换至「\(trimmedName)」"
    }

    public func removeCustomClientProfile(_ profile: ChaoxingCustomClientProfile) async {
        customClientProfiles.removeAll { $0.id == profile.id }
        if selectedCustomClientId == profile.id {
            selectedCustomClientId = nil
        }
        await saveToDisk()
    }

    public func requestCaptchaValidation(client: ChaoxingHTTPClient, refererURL: String) async throws -> String {
        let id = UUID()
        let token = sessionToken
        return try await withTaskCancellationHandler {
            try checkSession(token)
            return try await withCheckedThrowingContinuation { continuation in
                guard !Task.isCancelled, isCurrent(token) else {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                captchaContinuations[id] = continuation
                captchaQueue.append(IOSCaptchaChallengeRequest(id: id, client: client, refererURL: refererURL))
                presentNextCaptcha()
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.completeCaptcha(id: id, result: .failure(CancellationError()))
            }
        }
    }

    public func completeCaptcha(id: UUID, result: Result<String, Error>) {
        guard let continuation = captchaContinuations.removeValue(forKey: id) else { return }
        captchaQueue.removeAll { $0.id == id }
        if presentedCaptchaID == id {
            captchaChallenge = nil
            if visibleCaptchaID != id { presentedCaptchaID = nil }
        }
        continuation.resume(with: result)
        presentNextCaptcha()
    }

    public func captchaSheetDidAppear(id: UUID) {
        guard presentedCaptchaID == id, captchaContinuations[id] != nil else { return }
        visibleCaptchaID = id
    }

    public func captchaSheetDidDismiss() {
        if let id = visibleCaptchaID {
            visibleCaptchaID = nil
            completeCaptcha(id: id, result: .failure(CancellationError()))
            if presentedCaptchaID == id { presentedCaptchaID = nil }
            if captchaChallenge?.id == id { captchaChallenge = nil }
        }
        presentNextCaptcha()
    }

    private func presentNextCaptcha() {
        guard presentedCaptchaID == nil, captchaChallenge == nil, !captchaQueue.isEmpty else { return }
        let next = captchaQueue.removeFirst()
        presentedCaptchaID = next.id
        captchaChallenge = next
    }

    public func logoutAll() async {
        pendingIncomingURL = nil
        invalidateSessionOperations()
        accounts.removeAll()
        for client in clientPool.values { client.invalidate() }
        clientPool.removeAll()
        activePhoneNumber = nil
        courses = []
        groups = []
        imConfig = nil
        _studyController?.onAccountChanged(phoneNumber: nil)
        await saveToDisk()
    }

    public func recordSignHistory(
        activity: ChaoxingSignActivity,
        successCount: Int,
        alreadySignedCount: Int? = nil,
        totalCount: Int,
        summary: String
    ) async {
        let entry = ChaoxingSignHistoryEntry(
            activeId: activity.id,
            activityName: activity.nameOne.isEmpty ? activity.signType.displayName : activity.nameOne,
            courseName: activity.courseName.isEmpty ? "活动 #\(activity.id)" : activity.courseName,
            signTypeRaw: activity.signType.rawValue,
            successCount: successCount,
            alreadySignedCount: alreadySignedCount,
            totalCount: totalCount,
            summary: summary
        )
        signHistory.insert(entry, at: 0)
        if signHistory.count > 100 {
            signHistory = Array(signHistory.prefix(100))
        }
        await saveToDisk()
    }

    public func clearSignHistory() async {
        signHistory.removeAll()
        await saveToDisk()
        statusMessage = "已清空签到记录"
    }

    public func regenerateDeviceCode(for phoneNumber: String) async {
        guard let idx = accounts.firstIndex(where: { $0.phoneNumber == phoneNumber }) else { return }
        invalidateSessionOperations()
        var updated = clientPool[phoneNumber]?.session ?? accounts[idx]
        updated.isPrimary = accounts[idx].isPrimary
        updated.tags = accounts[idx].tags
        updated.deviceCode = ChaoxingCrypto.randomizedDeviceCode()
        accounts[idx] = updated
        clientPool[phoneNumber]?.invalidate()
        clientPool[phoneNumber] = ChaoxingHTTPClient(session: updated, userAgent: currentUserAgent)
        await saveToDisk()
        await refreshCourses()
        statusMessage = "已为 \(updated.name) 生成新设备码"
    }

    public func exportBackupData(includeAccounts: Bool) throws -> Data {
        let updatedAccounts = accounts.map { account in
            var updated = clientPool[account.phoneNumber]?.session ?? account
            updated.isPrimary = account.isPrimary
            updated.tags = account.tags
            return updated
        }
        let snapshot = ChaoxingAppStateSnapshot(
            accounts: updatedAccounts,
            locationPresets: locationPresets,
            selectedClientPreset: selectedClientPreset.rawValue,
            customUserAgent: customUserAgent,
            customClientProfiles: customClientProfiles,
            selectedCustomClientId: selectedCustomClientId,
            enableLocationJitter: enableLocationJitter,
            batchSignDelayMs: batchSignDelayMs,
            enableImageObfuscation: enableImageObfuscation,
            autoSolveCaptcha: autoSolveCaptcha,
            signHistory: signHistory,
            courseLocationBindings: courseLocationBindings,
            starredCourseIds: Array(starredCourseIds).sorted()
        )
        return try snapshot.encodedBackupData(includeAccounts: includeAccounts)
    }

    /// Merge presets/history; imported sessions replace matching accounts (including credentials).
    /// Existing primary/active identity is retained; imported preferences become current.
    public func importBackupData(_ data: Data) async throws {
        try requireReadyState()
        guard data.count <= 8 * 1024 * 1024 else { throw ChaoxingError.invalidInput("备份文件超过 8 MB，请检查文件内容") }
        let imported = try ChaoxingAppStateSnapshot.decodedBackupData(data)
        let importedLocations = imported.locationPresets + Array(imported.courseLocationBindings.values)
        guard importedLocations.allSatisfy({ $0.latitude.isFinite && $0.longitude.isFinite && (-90...90).contains($0.latitude) && (-180...180).contains($0.longitude) }) else {
            throw ChaoxingError.invalidInput("备份中包含无效经纬度，未应用任何修改")
        }
        var candidate = try ChaoxingAppStateSnapshot.decodedBackupData(exportBackupData(includeAccounts: true))
        var nextActivePhone = activePhoneNumber
        guard (0...60_000).contains(imported.batchSignDelayMs) else {
            throw ChaoxingError.invalidInput("备份中的错峰间隔必须在 0–60000 毫秒之间")
        }
        for preset in imported.locationPresets {
            if let index = candidate.locationPresets.firstIndex(where: { $0.id == preset.id }) { candidate.locationPresets[index] = preset }
            else if !candidate.locationPresets.contains(where: { $0.name == preset.name && $0.address == preset.address && $0.latitude == preset.latitude && $0.longitude == preset.longitude }) { candidate.locationPresets.append(preset) }
        }
        var profileIDs: [UUID: UUID] = [:]
        for profile in imported.customClientProfiles {
            if let index = candidate.customClientProfiles.firstIndex(where: { $0.id == profile.id }) {
                candidate.customClientProfiles[index] = profile
                profileIDs[profile.id] = profile.id
            } else if let existing = candidate.customClientProfiles.first(where: { $0.name == profile.name && $0.userAgent == profile.userAgent }) {
                profileIDs[profile.id] = existing.id
            } else {
                candidate.customClientProfiles.append(profile)
                profileIDs[profile.id] = profile.id
            }
        }
        candidate.courseLocationBindings.merge(imported.courseLocationBindings) { _, incoming in incoming }
        candidate.starredCourseIds = Array(Set(candidate.starredCourseIds).union(imported.starredCourseIds)).sorted()
        let primaryPhone = candidate.accounts.first(where: { $0.isPrimary })?.phoneNumber
        for incoming in imported.accounts {
            if let index = candidate.accounts.firstIndex(where: { $0.phoneNumber == incoming.phoneNumber }) {
                var updated = incoming
                updated.tags = Array(Set(candidate.accounts[index].tags + incoming.tags)).sorted()
                candidate.accounts[index] = updated
            } else { candidate.accounts.append(incoming) }
        }
        let chosenPrimary = primaryPhone ?? candidate.accounts.first(where: { $0.isPrimary })?.phoneNumber ?? candidate.accounts.first?.phoneNumber
        for index in candidate.accounts.indices { candidate.accounts[index].isPrimary = candidate.accounts[index].phoneNumber == chosenPrimary }
        if !candidate.accounts.contains(where: { $0.phoneNumber == nextActivePhone }) { nextActivePhone = chosenPrimary }
        candidate.selectedClientPreset = (ChaoxingClientPreset(rawValue: imported.selectedClientPreset) ?? .default).rawValue
        candidate.customUserAgent = imported.customUserAgent
        candidate.selectedCustomClientId = imported.selectedCustomClientId.flatMap { id in profileIDs[id] ?? (candidate.customClientProfiles.contains(where: { $0.id == id }) ? id : nil) }
        // Resolve selection only against profiles that survived the merge.
        if let selectedID = candidate.selectedCustomClientId, !candidate.customClientProfiles.contains(where: { $0.id == selectedID }) { candidate.selectedCustomClientId = nil }
        candidate.enableLocationJitter = imported.enableLocationJitter
        candidate.batchSignDelayMs = imported.batchSignDelayMs
        candidate.enableImageObfuscation = imported.enableImageObfuscation
        candidate.autoSolveCaptcha = imported.autoSolveCaptcha
        for entry in imported.signHistory where !candidate.signHistory.contains(where: { $0.id == entry.id }) { candidate.signHistory.append(entry) }
        candidate.signHistory = Array(candidate.signHistory.sorted { $0.timestamp > $1.timestamp }.prefix(100))
        invalidateSessionOperations()
        isRestoring = true
        defer { isRestoring = false }
        do { try await store.save(candidate) }
        catch {
            errorMessage = "保存导入配置失败，原设置未改变：\(error.localizedDescription)"
            throw error
        }
        applySnapshot(candidate, activePhone: nextActivePhone)
        isRestoring = false
        await refreshCourses()
        statusMessage = "已合并备份；同账号会话与偏好已更新"
    }

    // MARK: - 群模式标签管理（Issue #240 / #91）
    public var allAccountTags: [String] {
        Array(Set(accounts.flatMap(\.tags))).sorted()
    }

    public func setTags(_ tags: [String], for phoneNumber: String) async {
        guard let idx = accounts.firstIndex(where: { $0.phoneNumber == phoneNumber }) else { return }
        let cleaned = Array(Set(tags.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty })).sorted()
        accounts[idx].tags = cleaned
        if let existingClient = clientPool[phoneNumber] {
            existingClient.updateTags(cleaned)
        }
        await saveToDisk()
    }

    public func addTag(_ tag: String, to phoneNumbers: Set<String>) async {
        let trimmed = tag.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        for idx in accounts.indices where phoneNumbers.contains(accounts[idx].phoneNumber) {
            if !accounts[idx].tags.contains(trimmed) {
                accounts[idx].tags.append(trimmed)
                accounts[idx].tags.sort()
                let phone = accounts[idx].phoneNumber
                if let existingClient = clientPool[phone] {
                    existingClient.updateTags(accounts[idx].tags)
                }
            }
        }
        await saveToDisk()
        statusMessage = "已将 \(phoneNumbers.count) 个账号加入分组「\(trimmed)」"
    }

    // MARK: - 课程位置绑定（Issue #222）
    public func boundLocation(forCourseId courseId: Int64) -> ChaoxingLocationPreset? {
        guard courseId != 0 else { return nil }
        return courseLocationBindings[String(courseId)]
    }

    public func bindLocation(_ preset: ChaoxingLocationPreset, toCourseId courseId: Int64) async {
        guard courseId != 0 else { return }
        courseLocationBindings[String(courseId)] = preset
        await saveToDisk()
        statusMessage = "已绑定位置「\(preset.name)」到此课程"
    }

    public func unbindLocation(forCourseId courseId: Int64) async {
        guard courseId != 0 else { return }
        courseLocationBindings.removeValue(forKey: String(courseId))
        await saveToDisk()
        statusMessage = "已取消本课程位置绑定"
    }

    // MARK: - 常用课程标星 & 进行中签到雷达扫描（Issue #74 / #72）
    public func toggleCourseStar(_ courseId: Int64) async {
        if starredCourseIds.contains(courseId) {
            starredCourseIds.remove(courseId)
        } else {
            starredCourseIds.insert(courseId)
        }
        await saveToDisk()
    }

    public func scanPendingActivities() async {
        guard let client = activeClient else { return }
        let token = sessionToken
        let request = UUID()
        radarRequest = request
        isScanningRadar = true
        defer { if radarRequest == request { isScanningRadar = false } }
        let targets = starredCourseIds.isEmpty ? Array(courses.prefix(12)) : courses.filter { starredCourseIds.contains($0.courseId) }
        guard !targets.isEmpty else {
            pendingRadarActivities = []
            statusMessage = "当前没有可扫描的课程"
            return
        }
        let fetch = dependencies.fetchActivities
        var found: [ChaoxingSignActivity] = []
        var failures = 0
        var firstFailure: String?
        await withTaskGroup(of: Result<[ChaoxingSignActivity], Error>.self) { group in
            for course in targets {
                group.addTask {
                    do {
                        try Task.checkCancellation()
                        return .success(try await fetch(client, course))
                    } catch { return .failure(error) }
                }
            }
            for await result in group {
                switch result {
                case .success(let list): found.append(contentsOf: list.filter { $0.isAvailable && !$0.isAlreadySignedByStatus })
                case .failure(let error):
                    failures += 1
                    if firstFailure == nil { firstFailure = error.localizedDescription }
                }
            }
        }
        guard isCurrent(token), radarRequest == request, !Task.isCancelled else { return }
        if failures == targets.count {
            errorMessage = "扫描失败，未能刷新活动，已保留上次结果：\(firstFailure ?? "网络不可用")"
            return
        }
        pendingRadarActivities = found.sorted { ($0.startTimeMs ?? 0) > ($1.startTimeMs ?? 0) }
        if failures > 0 { errorMessage = "\(failures)/\(targets.count) 门课程读取失败，当前仅显示成功读取的结果。\(firstFailure ?? "")" }
        else if found.isEmpty { statusMessage = "已扫描 \(targets.count) 门课程，没有待签活动" }
    }

    /// 处理外部 URL Scheme / Deep Link（如 caochaoxing://sign?activeId=123 或二维码链接）
    public func handleIncomingURL(_ url: URL) async {
        guard !isRestoring, activeAccount != nil else {
            pendingIncomingURL = url
            return
        }
        let token = sessionToken
        let request = UUID()
        deepLinkRequest = request
        let raw = url.absoluteString
        if (try? ChaoxingQRCodeParser.parse(rawValue: raw)) != nil {
            pendingQRCodeInput = raw
            selectedTab = 2
            statusMessage = "已从外部链接导入签到二维码"
            return
        }

        guard url.scheme?.lowercased() == "caochaoxing" else { return }
        let host = url.host?.lowercased() ?? ""
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let queryItems = components?.queryItems ?? []
        func query(_ name: String) -> String? {
            queryItems.first(where: { $0.name == name })?.value
        }

        if host == "qr", let qrRaw = query("data") ?? query("url") {
            pendingQRCodeInput = qrRaw
            selectedTab = 2
            statusMessage = "已导入签到二维码"
            return
        }

        if host == "sign" {
            guard let text = query("activeId") ?? query("id"), let activeId = Int64(text), activeId > 0,
                  let classId = Int(query("classId") ?? "0"), classId >= 0,
                  let courseId = Int64(query("courseId") ?? "0"), courseId >= 0 else {
                errorMessage = "链接中的活动 ID 必须为正数，课程和班级 ID 必须为非负整数"
                return
            }
            if let client = activeClient,
               let meta = try? await dependencies.fetchDetail(client, activeId, classId, courseId) {
                guard isCurrent(token), deepLinkRequest == request, !Task.isCancelled else { return }
                deepLinkActivity = ChaoxingSignActivity(
                    id: activeId,
                    startTimeMs: nil,
                    endTimeMs: nil,
                    userStatus: 0,
                    otherId: meta.signType.rawValue,
                    isLook: false,
                    type: 2,
                    activeType: 2,
                    nameOne: meta.title.isEmpty ? "外部跳转签到 #\(activeId)" : meta.title,
                    status: 1,
                    nameFour: "",
                    courseId: meta.signOutInfo.courseId,
                    classId: meta.signOutInfo.classId,
                    courseName: "",
                    extContent: ""
                )
            } else {
                guard isCurrent(token), deepLinkRequest == request, !Task.isCancelled else { return }
                deepLinkActivity = ChaoxingSignActivity(
                    id: activeId,
                    startTimeMs: nil,
                    endTimeMs: nil,
                    userStatus: 0,
                    otherId: query("type") ?? "-1",
                    isLook: false,
                    type: 2,
                    activeType: 2,
                    nameOne: "外部跳转签到 #\(activeId)",
                    status: 1,
                    nameFour: "",
                    courseId: courseId,
                    classId: classId,
                    courseName: "",
                    extContent: ""
                )
            }
            selectedTab = 0
        }
    }
    private func consumePendingURL() async {
        guard !isRestoring, activeAccount != nil, let url = pendingIncomingURL else { return }
        pendingIncomingURL = nil
        await handleIncomingURL(url)
    }

    private func applySnapshot(_ snapshot: ChaoxingAppStateSnapshot, activePhone: String?) {
        accounts = snapshot.accounts
        activePhoneNumber = activePhone
        for client in clientPool.values { client.invalidate() }
        clientPool.removeAll()
        locationPresets = snapshot.locationPresets
        selectedClientPreset = ChaoxingClientPreset(rawValue: snapshot.selectedClientPreset) ?? .default
        customUserAgent = snapshot.customUserAgent
        customClientProfiles = snapshot.customClientProfiles
        selectedCustomClientId = snapshot.selectedCustomClientId
        enableLocationJitter = snapshot.enableLocationJitter
        batchSignDelayMs = snapshot.batchSignDelayMs
        enableImageObfuscation = snapshot.enableImageObfuscation
        autoSolveCaptcha = snapshot.autoSolveCaptcha
        signHistory = snapshot.signHistory
        courseLocationBindings = snapshot.courseLocationBindings
        starredCourseIds = Set(snapshot.starredCourseIds)
        _studyController?.onAccountChanged(phoneNumber: activePhone)
    }

    public var sessionToken: IOSSessionToken {
        IOSSessionToken(generation: sessionGeneration, phoneNumber: activeAccount?.phoneNumber)
    }

    public func isCurrent(_ token: IOSSessionToken) -> Bool { token == sessionToken }

    public func checkSession(_ token: IOSSessionToken) throws {
        try Task.checkCancellation()
        guard isCurrent(token) else { throw CancellationError() }
    }

    public func selectedAccounts(phones: Set<String>) -> [ChaoxingAccountSession] {
        accounts.filter { phones.contains($0.phoneNumber) }
    }

    @discardableResult
    public func launchSessionTask(_ operation: @escaping @MainActor () async -> Void) -> Task<Void, Never> {
        let id = UUID()
        let task = Task { @MainActor [weak self] in
            defer { self?.sessionTasks.removeValue(forKey: id) }
            guard !Task.isCancelled else { return }
            await operation()
        }
        sessionTasks[id] = task
        return task
    }

    private func requireReadyState() throws {
        guard !isRestoring, !persistenceBlocked else {
            throw ChaoxingError.invalidInput("本地状态尚未成功读取，请先完成恢复")
        }
    }

    private func beginLoading() -> UUID {
        let id = UUID()
        loadingRequests.insert(id)
        isLoading = true
        return id
    }

    private func endLoading(_ id: UUID) {
        loadingRequests.remove(id)
        isLoading = !loadingRequests.isEmpty
    }

    private func invalidateSessionOperations() {
        sessionGeneration &+= 1
        for task in sessionTasks.values { task.cancel() }
        sessionTasks.removeAll()
        let continuations = Array(captchaContinuations.values)
        captchaContinuations.removeAll()
        captchaQueue.removeAll()
        captchaChallenge = nil
        if visibleCaptchaID == nil { presentedCaptchaID = nil }
        for continuation in continuations { continuation.resume(throwing: CancellationError()) }
        courses = []
        groups = []
        imConfig = nil
        pendingRadarActivities = []
        isScanningRadar = false
        deepLinkActivity = nil
        pendingQRCodeInput = nil
        loadingRequests.removeAll()
        isLoading = false
        _studyController?.stopWatch(logStopped: false)
    }

    /// Captures targets and credentials once, rejects empty selection, and stops after session invalidation.
    public func executeSignBatch(
        activity: ChaoxingSignActivity,
        selectedPhones: Set<String>,
        ignoreBatchDelay: Bool = false,
        onProgress: (@MainActor @Sendable ([IOSBatchSignResult]) -> Void)? = nil,
        operation: @escaping @Sendable (ChaoxingHTTPClient, ChaoxingAccountSession, String?, String?) async throws -> ChaoxingSignOutcome
    ) async throws -> [IOSBatchSignResult] {
        try requireReadyState()
        let token = sessionToken
        guard activity.id > 0 else { throw ChaoxingError.invalidInput("签到活动 ID 无效") }
        guard activity.isAvailable, !activity.isLate else { throw ChaoxingError.signExpired() }
        let targets = selectedAccounts(phones: selectedPhones)
        guard !targets.isEmpty else { throw ChaoxingError.invalidInput("请至少选择一个签到账号") }
        let pairs = targets.map { ($0, client(for: $0)) }
        let delay = ignoreBatchDelay ? 0 : min(max(batchSignDelayMs, 0), 60_000)
        var collected: [IOSBatchSignResult] = []
        var challenges: [(ChaoxingAccountSession, ChaoxingHTTPClient, String?)] = []

        func collect(_ account: ChaoxingAccountSession, _ client: ChaoxingHTTPClient, _ result: Result<ChaoxingSignOutcome, Error>) throws {
            try checkSession(token)
            switch result {
            case .success(let outcome):
                collected.append(IOSBatchSignResult(accountName: account.name, phoneNumber: account.phoneNumber, disposition: outcome.disposition, message: outcome.message))
            case .failure(let error):
                if error is CancellationError { throw error }
                if case ChaoxingError.captchaRequired(let enc2) = error { challenges.append((account, client, enc2)) }
                else { collected.append(IOSBatchSignResult(accountName: account.name, phoneNumber: account.phoneNumber, disposition: .init(error: error), message: error.localizedDescription)) }
            }
            onProgress?(collected)
        }

        if delay == 0 && pairs.count > 1 {
            try await withThrowingTaskGroup(of: (ChaoxingAccountSession, ChaoxingHTTPClient, Result<ChaoxingSignOutcome, Error>).self) { group in
                for (account, client) in pairs {
                    group.addTask {
                        let result = await self.runSignAttempt(account: account, client: client, activity: activity, token: token, operation: operation)
                        return (account, client, result)
                    }
                }
                for try await (account, client, result) in group { try collect(account, client, result) }
            }
        } else {
            for (index, pair) in pairs.enumerated() {
                try checkSession(token)
                if index > 0 && delay > 0 {
                    try await dependencies.sleep(Int(Double(delay) * Double.random(in: 0.75...1.35)))
                    try checkSession(token)
                }
                let result = await runSignAttempt(account: pair.0, client: pair.1, activity: activity, token: token, operation: operation)
                try collect(pair.0, pair.1, result)
            }
        }
        for (account, client, enc2) in challenges {
            try checkSession(token)
            do {
                let referer = ChaoxingSignExecutor.preSignURL(client: client, activity: activity).absoluteString
                let validate = try await requestCaptchaValidation(client: client, refererURL: referer)
                try checkSession(token)
                let outcome = try await executeWithDeviceRetry(account: account, client: client, token: token) { refreshedAccount in
                    try await operation(client, refreshedAccount, validate, enc2)
                }
                try checkSession(token)
                collected.append(IOSBatchSignResult(accountName: account.name, phoneNumber: account.phoneNumber, disposition: outcome.disposition, message: outcome.message))
            } catch {
                try checkSession(token)
                if error is CancellationError { throw error }
                collected.append(IOSBatchSignResult(accountName: account.name, phoneNumber: account.phoneNumber, disposition: .init(error: error), message: error.localizedDescription))
            }
            onProgress?(collected)
        }
        try checkSession(token)
        return collected
    }

    private func runSignAttempt(
        account: ChaoxingAccountSession, client: ChaoxingHTTPClient, activity: ChaoxingSignActivity,
        token: IOSSessionToken,
        operation: @escaping @Sendable (ChaoxingHTTPClient, ChaoxingAccountSession, String?, String?) async throws -> ChaoxingSignOutcome
    ) async -> Result<ChaoxingSignOutcome, Error> {
        do {
            try checkSession(token)
            let status = try await dependencies.prepareSign(client, activity)
            try checkSession(token)
            switch status {
            case .alreadySigned: return .success(ChaoxingSignOutcome(disposition: .alreadySigned, message: "此前已经签到"))
            case .expired: throw ChaoxingError.signExpired("签到已截止")
            case .readyToSign: break
            }
            let outcome = try await executeWithDeviceRetry(account: account, client: client, token: token) { refreshedAccount in
                try await operation(client, refreshedAccount, nil, nil)
            }
            try checkSession(token)
            return .success(outcome)
        } catch { return .failure(error) }
    }

    private func executeWithDeviceRetry(
        account: ChaoxingAccountSession,
        client: ChaoxingHTTPClient,
        token: IOSSessionToken,
        submit: @escaping @Sendable (ChaoxingAccountSession) async throws -> ChaoxingSignOutcome
    ) async throws -> ChaoxingSignOutcome {
        do {
            return try await submit(account)
        } catch ChaoxingError.deviceDuplicated {
            try checkSession(token)
            guard let rotated = await rotateDeviceCodeInPlace(for: account.phoneNumber, client: client) else {
                throw ChaoxingError.deviceDuplicated()
            }
            try checkSession(token)
            return try await submit(rotated)
        }
    }

    private func rotateDeviceCodeInPlace(
        for phoneNumber: String,
        client: ChaoxingHTTPClient
    ) async -> ChaoxingAccountSession? {
        guard let idx = accounts.firstIndex(where: { $0.phoneNumber == phoneNumber }) else { return nil }
        var updated = client.session
        updated.isPrimary = accounts[idx].isPrimary
        updated.tags = accounts[idx].tags
        updated.deviceCode = ChaoxingCrypto.randomizedDeviceCode()
        client.updateSession(updated)
        accounts[idx] = updated
        await saveToDisk()
        return updated
    }

    /// Only call after explicit user confirmation; the store preserves the old file before reset.
    public func recoverLocalState() async {
        invalidateSessionOperations()
        do {
            let archive = try await store.archiveAndReset()
            persistenceBlocked = false
            await loadFromDisk()
            statusMessage = "已保留原文件并重置：\(archive.lastPathComponent)"
        } catch {
            persistenceBlocked = true
            errorMessage = "无法保留原文件并重置：\(error.localizedDescription)"
        }
    }

}

/// Keeps a QR payload and its server metadata atomic across edits and cancelled lookups.
@MainActor
@Observable
public final class IOSQRCodeResolution {
    public private(set) var parsed: ChaoxingQRCodeParseResult?
    public private(set) var activity: ChaoxingSignActivity?
    public private(set) var detail: ChaoxingSignDetailMetadata?
    public private(set) var isResolving = false
    public private(set) var resolvedInput: String?
    private var request = UUID()

    public init() {}

    public func invalidate() {
        request = UUID()
        parsed = nil
        activity = nil
        detail = nil
        resolvedInput = nil
        isResolving = false
    }

    public func resolve(
        _ raw: String,
        lookup: @escaping @Sendable (ChaoxingQRCodeParseResult) async throws -> (ChaoxingSignActivity, ChaoxingSignDetailMetadata)
    ) async throws {
        invalidate()
        guard let payload = try? ChaoxingQRCodeParser.parse(rawValue: raw) else { return }
        let current = request
        isResolving = true
        defer { if request == current { isResolving = false } }
        let result: (ChaoxingSignActivity, ChaoxingSignDetailMetadata)
        do { result = try await lookup(payload) }
        catch {
            guard request == current, !Task.isCancelled else { throw CancellationError() }
            throw error
        }
        try Task.checkCancellation()
        guard request == current else { throw CancellationError() }
        guard let aid = payload.aid, aid == result.0.id, aid == result.1.activeId else {
            throw ChaoxingError.invalidInput("二维码与活动详情不一致，请重新扫描")
        }
        parsed = payload
        activity = result.0
        detail = result.1
        resolvedInput = raw
    }
}

@MainActor
@Observable
final class IOSCourseActivityList {
    struct Scope: Equatable {
        let session: IOSSessionToken
        let courseID: String
    }

    enum Phase: Equatable {
        case loading(previous: [ChaoxingSignActivity]?)
        case loaded([ChaoxingSignActivity])
        case failed(message: String, previous: [ChaoxingSignActivity]?)

        var previousResults: [ChaoxingSignActivity]? {
            switch self {
            case .loading(let previous), .failed(_, let previous): previous
            case .loaded(let activities): activities
            }
        }
    }

    private var scope: Scope?
    private var current: Phase = .loading(previous: nil)
    private var requestID = UUID()

    func phase(for scope: Scope) -> Phase {
        self.scope == scope ? current : .loading(previous: nil)
    }

    func load(_ course: ChaoxingCourse, session: IOSSessionToken, in appState: IOSAppState) async {
        guard appState.isCurrent(session), !Task.isCancelled else { return }
        let scope = Scope(session: session, courseID: course.id)
        let previous = phase(for: scope).previousResults
        let request = UUID()
        self.scope = scope
        requestID = request
        current = .loading(previous: previous)
        do {
            let activities = try await appState.fetchActivities(for: course, session: session)
            guard requestID == request, appState.isCurrent(session), !Task.isCancelled else { return }
            current = .loaded(activities)
        } catch {
            guard requestID == request, appState.isCurrent(session), !Task.isCancelled else { return }
            current = .failed(message: error.localizedDescription, previous: previous)
        }
    }
}
