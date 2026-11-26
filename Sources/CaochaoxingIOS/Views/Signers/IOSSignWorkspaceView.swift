// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import ChaoxingCore

struct IOSSignWorkspaceView: View {
    @Environment(IOSAppState.self) private var appState

    let activity: ChaoxingSignActivity
    @State private var currentActivity: ChaoxingSignActivity

    @State private var selectedPhones: Set<String> = []
    @State private var preSignStatus: ChaoxingSignActivityStatus?
    @State private var detailMetadata: ChaoxingSignDetailMetadata?
    @State private var isLoadingInfo = true
    @State private var hasLoadedInfo = false
    @State private var infoRequest = UUID()
    @State private var infoError: String?
    @State private var selectionInitialized = false
    @State private var isSigning = false
    @State private var isVisible = false
    @State private var batchTask: Task<[IOSBatchSignResult], Error>?
    @State private var batchResults: [IOSBatchSignResult] = []
    @State private var signOutDestination: ChaoxingSignActivity?

    init(activity: ChaoxingSignActivity) {
        self.activity = activity
        _currentActivity = State(initialValue: activity)
    }

    private var effectiveSignType: ChaoxingSignType {
        if let metaType = detailMetadata?.signType, metaType != .unknown {
            return metaType
        }
        return currentActivity.signType
    }

    private var targetAccounts: [ChaoxingAccountSession] {
        appState.selectedAccounts(phones: selectedPhones)
    }

    private var isClosed: Bool {
        !currentActivity.isAvailable || currentActivity.isLate || preSignStatus == .expired
    }

    private var selectionAlreadySigned: Bool {
        guard preSignStatus == .alreadySigned, let phone = appState.activePhoneNumber else { return false }
        return targetAccounts.count == 1 && targetAccounts.first?.phoneNumber == phone
    }

    var body: some View {
        List {
            // 活动档案概览
            Section {
                VStack(alignment: .leading, spacing: 11) {
                    HStack(alignment: .top, spacing: 12) {
                        CxIconTile(effectiveSignType.systemImage, tone: .accent, size: 38)

                        VStack(alignment: .leading, spacing: 4) {
                            Text(currentActivity.nameOne.isEmpty ? effectiveSignType.displayName : currentActivity.nameOne)
                                .font(CxTypography.serifTitle(18))
                                .foregroundStyle(CxPalette.ink)

                            Text(currentActivity.courseName.isEmpty
                                 ? "AID \(currentActivity.id)"
                                 : "\(currentActivity.courseName) · AID \(currentActivity.id)")
                                .font(CxTypography.mono(11.5))
                                .foregroundStyle(CxPalette.inkSecondary)
                        }

                        Spacer()

                        if isLoadingInfo {
                            ProgressView()
                                .controlSize(.small)
                        }
                    }

                    CxFlowLayout(horizontalSpacing: 6, verticalSpacing: 6) {
                        CxBadge(effectiveSignType.displayName, tone: .accent)

                        if let status = preSignStatus {
                            switch status {
                            case .alreadySigned:
                                CxBadge("主账号已签到", tone: .sage, icon: "checkmark")
                            case .expired:
                                CxBadge("活动已截止", tone: .cinnabar, showDot: true)
                            case .readyToSign:
                                CxBadge("主账号待签到", tone: .amber, showDot: true)
                            }
                        }

                        if isClosed && preSignStatus != .expired {
                            CxBadge("活动已截止", tone: .cinnabar, icon: "clock.badge.exclamationmark")
                        }

                        if detailMetadata?.isFaceRequired == true {
                            CxBadge("需人脸核验", tone: .plum, icon: "faceid")
                        }

                        if detailMetadata?.isPositionRequired == true {
                            CxBadge("需定位坐标", tone: .sage, icon: "location.fill")
                        }

                        if detailMetadata?.isCaptchaRequired == true {
                            CxBadge("含滑块验证", tone: .amber, icon: "puzzlepiece.extension")
                        }
                    }
                }
                .padding(.vertical, 4)
                .cxCardRow()

                // 签退跳转卡片
                if let signOutId = detailMetadata?.signOutInfo.signOutId {
                    Button {
                        appState.launchSessionTask { await openSignOutActivity(signOutId: signOutId) }
                    } label: {
                        HStack(spacing: 11) {
                            CxIconTile("arrow.uturn.right.circle.fill", tone: .plum, size: 34)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("关联签退活动")
                                    .font(CxTypography.serifHeadline(15.5))
                                    .foregroundStyle(CxPalette.ink)
                                Text("打开对应的签退活动（\(signOutId)）")
                                    .font(CxTypography.mono(11))
                                    .foregroundStyle(CxPalette.inkSecondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(CxPalette.inkTertiary)
                        }
                        .padding(.vertical, 2)
                    }
                    .cxCardRow()
                }
            } header: {
                CxSectionHeader("活动信息")
            }

            // 账号选择（支持群模式分组芯片）
            if appState.accounts.count > 1 {
                Section {
                    IOSAccountGroupSelectorView(selectedPhones: $selectedPhones)
                        .padding(.vertical, 4)
                        .cxCardRow()
                } header: {
                    CxSectionHeader(
                        "参与账号 (\(targetAccounts.count)/\(appState.accounts.count))",
                        trailing: appState.batchSignDelayMs > 0
                            ? "PACE ~\(String(format: "%.1f", Double(appState.batchSignDelayMs) / 1000.0))s"
                            : "CONCURRENT"
                    )
                }
            }

            // 签到操作区
            if let infoError, !isClosed {
                Section {
                    Text(infoError)
                    Button("重新加载活动信息") { Task { await loadActivityInfo() } }
                }
            } else if !isClosed && !selectionAlreadySigned && hasLoadedInfo {
                Section {
                    signerContent
                        .disabled(isLoadingInfo || isSigning || targetAccounts.isEmpty)
                        .cxCardRow()
                } header: {
                    CxSectionHeader("签到")
                }
            }

            // 批量执行结果
            if !batchResults.isEmpty {
                Section {
                    ForEach(batchResults) { result in
                        HStack(alignment: .center, spacing: 10) {
                            CxIconTile(
                                result.disposition.symbol,
                                tone: result.disposition.tone,
                                size: 30
                            )

                            VStack(alignment: .leading, spacing: 3) {
                                HStack(spacing: 6) {
                                    Text(result.accountName.isEmpty ? result.phoneNumber : result.accountName)
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundStyle(CxPalette.ink)
                                    Text(result.phoneNumber)
                                        .font(CxTypography.mono(11))
                                        .foregroundStyle(CxPalette.inkTertiary)
                                }
                                Text(result.message)
                                    .font(.caption)
                                    .foregroundStyle(result.disposition == .failed ? CxPalette.cinnabar : CxPalette.inkSecondary)
                            }

                            Spacer()

                            if result.disposition == .failed && (result.message.contains("设备") || result.message.contains("device")) {
                                Button("换设备码") {
                                    appState.launchSessionTask {
                                        await appState.regenerateDeviceCode(for: result.phoneNumber)
                                    }
                                }
                                .buttonStyle(CxSecondaryButtonStyle(compact: true))
                            }
                        }
                        .padding(.vertical, 3)
                        .cxCardRow()
                    }
                } header: {
                    Text(ChaoxingSignCounts(dispositions: batchResults.map(\.disposition)).summary)
                }
            }
        }
        .listStyle(.insetGrouped)
        .cxEditorialList()
        .navigationTitle("签到工作台")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(item: $signOutDestination) { target in
            IOSSignWorkspaceView(activity: target)
        }
        .overlay {
            if isSigning {
                ZStack {
                    Color.black.opacity(0.24).ignoresSafeArea()
                    VStack(spacing: 12) {
                        ProgressView()
                            .tint(CxPalette.accent)
                        Text("正在提交考勤请求...")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(CxPalette.ink)
                        Button("停止后续操作", role: .cancel) { batchTask?.cancel() }
                        Text("已发出的请求无法撤回")
                            .font(.caption)
                            .foregroundStyle(CxPalette.inkSecondary)
                    }
                    .cxCard(padding: 24, cornerRadius: 16)
                }
            }
        }
        .refreshable { await loadActivityInfo() }
        .onAppear { isVisible = true }
        .onDisappear {
            isVisible = false
            batchTask?.cancel()
        }
        .task(id: appState.sessionToken) {
            if !selectionInitialized {
                selectionInitialized = true
                if let phone = appState.activeAccount?.phoneNumber { selectedPhones.insert(phone) }
            }
            await loadActivityInfo()
        }
    }

    @ViewBuilder
    private var signerContent: some View {
        let jitterEnabled = appState.enableLocationJitter
        let obfuscateImages = appState.enableImageObfuscation
        let positionRequired = detailMetadata?.isPositionRequired ?? false
        let faceRequired = detailMetadata?.isFaceRequired ?? false

        switch effectiveSignType {
        case .location:
            IOSLocationSignView(
                activity: currentActivity,
                detail: detailMetadata,
                isSigning: isSigning,
                onSubmit: { location in
                    await performBatchSign { client, _, validate, enc2 in
                        let perAccountLoc = ChaoxingLocationSignInfo(
                            latitude: location.latitude,
                            longitude: location.longitude,
                            address: location.address,
                            enableRandomOffset: jitterEnabled
                        )
                        let faceObjId = faceRequired
                            ? (try await ChaoxingFaceService.fetchUserProfileFaceObjectId(client: client))
                            : nil
                        try Task.checkCancellation()
                        return try await ChaoxingSignExecutor.signLocation(
                            client: client,
                            activity: currentActivity,
                            location: perAccountLoc,
                            faceImageObjectId: faceObjId,
                            captchaValidate: validate,
                            enc2: enc2
                        )
                    }
                }
            )
        case .photoOrClick:
            if detailMetadata?.isPhotoRequired == true {
                IOSPhotoSignView(
                    activity: currentActivity,
                    isSigning: isSigning,
                    onSubmit: { submission in
                        await submitPhotoBatch(submission, obfuscate: obfuscateImages)
                    }
                )
            } else {
                VStack(alignment: .leading, spacing: 14) {
                    Text("普通点击签到无需附加参数；若课程要求现场拍照，可展开下方相册附件面板。")
                        .font(.caption)
                        .foregroundStyle(CxPalette.inkSecondary)

                    Button {
                        appState.launchSessionTask {
                            await performBatchSign { client, _, validate, enc2 in
                                try await ChaoxingSignExecutor.signPhotoOrClick(
                                    client: client,
                                    activity: currentActivity,
                                    objectId: nil,
                                    captchaValidate: validate,
                                    enc2: enc2
                                )
                            }
                        }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "checkmark.seal.fill")
                            Text("立即提交签到")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(CxPrimaryButtonStyle())
                    .disabled(isSigning)

                    Divider()
                        .overlay(CxPalette.border)

                    DisclosureGroup {
                        IOSPhotoSignView(
                            activity: currentActivity,
                            requiresPhoto: false,
                            isSigning: isSigning,
                            onSubmit: { submission in
                                await submitPhotoBatch(submission, obfuscate: obfuscateImages)
                            }
                        )
                        .padding(.top, 8)
                    } label: {
                        Label("附加照片上传（可选）", systemImage: "camera.on.rectangle")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(CxPalette.inkSecondary)
                    }
                    .tint(CxPalette.inkSecondary)
                }
                .padding(.vertical, 4)
            }
        case .qrCode:
            IOSQRCodeSignView(
                activity: currentActivity,
                isPositionRequired: positionRequired,
                isSigning: isSigning,
                onSubmit: { qrResult, location in
                    await performBatchSign { client, _, validate, enc2 in
                        let perAccountLoc = location.map {
                            ChaoxingLocationSignInfo(
                                latitude: $0.latitude,
                                longitude: $0.longitude,
                                address: $0.address,
                                enableRandomOffset: jitterEnabled
                            )
                        }
                        let faceObjId = faceRequired
                            ? (try await ChaoxingFaceService.fetchUserProfileFaceObjectId(client: client))
                            : nil
                        try Task.checkCancellation()
                        return try await ChaoxingSignExecutor.signQRCode(
                            client: client,
                            activity: currentActivity,
                            qrResult: qrResult,
                            location: perAccountLoc,
                            faceImageObjectId: faceObjId,
                            captchaValidate: validate,
                            enc2: enc2
                        )
                    }
                }
            )
        case .gesture:
            IOSGestureSignView(
                activity: currentActivity,
                isSigning: isSigning,
                isPositionRequired: positionRequired,
                onSubmit: { code, preset in
                    await performBatchSign { client, _, validate, enc2 in
                        let perAccountLoc = preset?.toSignInfo(enableRandomOffset: jitterEnabled)
                        return try await ChaoxingSignExecutor.signCodeOrGesture(
                            client: client,
                            activity: currentActivity,
                            signCode: code,
                            location: perAccountLoc,
                            captchaValidate: validate,
                            enc2: enc2
                        )
                    }
                }
            )
        case .password:
            IOSPasswordSignView(
                activity: currentActivity,
                isSigning: isSigning,
                isPositionRequired: positionRequired,
                onSubmit: { code, preset in
                    await performBatchSign { client, _, validate, enc2 in
                        let perAccountLoc = preset?.toSignInfo(enableRandomOffset: jitterEnabled)
                        return try await ChaoxingSignExecutor.signCodeOrGesture(
                            client: client,
                            activity: currentActivity,
                            signCode: code,
                            location: perAccountLoc,
                            captchaValidate: validate,
                            enc2: enc2
                        )
                    }
                }
            )
        case .unknown:
            Text("暂不支持此签到类型，请使用官方客户端处理。")
                .font(.body)
                .foregroundStyle(CxPalette.inkSecondary)
        }
    }

    private func submitPhotoBatch(_ submission: IOSPhotoSubmission, obfuscate: Bool) async {
        switch submission {
        case .profileAvatar:
            await performBatchSign { client, _, validate, enc2 in
                let objectId = try await ChaoxingFaceService.fetchUserProfileFaceObjectId(client: client)
                try Task.checkCancellation()
                return try await ChaoxingSignExecutor.signPhotoOrClick(client: client, activity: currentActivity, objectId: objectId, captchaValidate: validate, enc2: enc2)
            }
        case .objectId(let objectId):
            await performBatchSign { client, _, validate, enc2 in
                try await ChaoxingSignExecutor.signPhotoOrClick(
                    client: client,
                    activity: currentActivity,
                    objectId: objectId,
                    captchaValidate: validate,
                    enc2: enc2
                )
            }
        case .imageData(let rawData):
            await performBatchSign { client, _, validate, enc2 in
                let finalData = obfuscate ? ChaoxingImageObfuscator.obfuscateJPEG(rawData) : rawData
                let objectId = try await ChaoxingCloudDriveService.uploadImage(
                    client: client,
                    imageData: finalData,
                    filename: "sign_\(Int(Date().timeIntervalSince1970))_\(Int.random(in: 100...999)).jpg"
                )
                try Task.checkCancellation()
                return try await ChaoxingSignExecutor.signPhotoOrClick(
                    client: client,
                    activity: currentActivity,
                    objectId: objectId,
                    captchaValidate: validate,
                    enc2: enc2
                )
            }
        }
    }

    private func loadActivityInfo() async {
        let request = UUID()
        infoRequest = request
        infoError = nil
        preSignStatus = nil
        detailMetadata = nil
        hasLoadedInfo = false
        guard let client = appState.activeClient else { isLoadingInfo = false; return }
        let token = appState.sessionToken
        isLoadingInfo = true
        defer { if infoRequest == request { isLoadingInfo = false } }
        do {
            let status = try await ChaoxingSignExecutor.preSign(client: client, activity: currentActivity)
            try appState.checkSession(token)
            guard infoRequest == request else { return }
            preSignStatus = status
            let detail = try await ChaoxingActivityService.fetchSignDetail(client: client, activeId: currentActivity.id, fallbackClassId: currentActivity.classId, fallbackCourseId: currentActivity.courseId)
            try appState.checkSession(token)
            guard infoRequest == request else { return }
            detailMetadata = detail
            hasLoadedInfo = true
        } catch {
            if infoRequest == request, appState.isCurrent(token), !(error is CancellationError), !Task.isCancelled {
                infoError = "无法读取活动要求：\(error.localizedDescription)"
            }
        }
    }

    private func openSignOutActivity(signOutId: Int64) async {
        guard let client = appState.activeClient else { return }
        let token = appState.sessionToken
        do {
            let meta = try await ChaoxingActivityService.fetchSignDetail(
                client: client,
                activeId: signOutId,
                fallbackClassId: currentActivity.classId,
                fallbackCourseId: currentActivity.courseId
            )
            let next = ChaoxingSignActivity(
                id: signOutId,
                startTimeMs: nil,
                endTimeMs: nil,
                userStatus: 0,
                otherId: meta.signType.rawValue,
                isLook: false,
                type: 2,
                activeType: 2,
                nameOne: meta.title.isEmpty ? "签退活动 #\(signOutId)" : meta.title,
                status: 1,
                nameFour: "",
                courseId: meta.signOutInfo.courseId,
                classId: meta.signOutInfo.classId,
                courseName: currentActivity.courseName,
                extContent: ""
            )
            try appState.checkSession(token)
            signOutDestination = next
        } catch {
            if appState.isCurrent(token), !(error is CancellationError) {
                appState.errorMessage = "打开签退活动失败：\(error.localizedDescription)"
            }
        }
    }

    private func performBatchSign(
        operation: @escaping @Sendable (ChaoxingHTTPClient, ChaoxingAccountSession, String?, String?) async throws -> ChaoxingSignOutcome
    ) async {
        guard isVisible, !Task.isCancelled, !isSigning, hasLoadedInfo, !isLoadingInfo,
              !isClosed, !selectionAlreadySigned, !targetAccounts.isEmpty else { return }
        let token = appState.sessionToken
        let activity = currentActivity
        let phones = selectedPhones
        isSigning = true
        batchResults = []
        defer { isSigning = false; batchTask = nil }
        let ignoreDelay = detailMetadata?.isRefreshQRCode == true
        let task = Task {
            try await appState.executeSignBatch(activity: activity, selectedPhones: phones, ignoreBatchDelay: ignoreDelay, onProgress: { batchResults = $0 }, operation: operation)
        }
        batchTask = task
        var didFinishBatch = false
        do {
            let collected = try await withTaskCancellationHandler {
                try await task.value
            } onCancel: {
                task.cancel()
            }
            try appState.checkSession(token)
            didFinishBatch = true
            batchResults = collected
            let counts = ChaoxingSignCounts(dispositions: collected.map(\.disposition))
            appState.statusMessage = counts.summary
            await appState.recordSignHistory(activity: activity, successCount: counts.accepted, alreadySignedCount: counts.alreadySigned, totalCount: counts.total, summary: "")
            try appState.checkSession(token)
            await loadActivityInfo()
        } catch {
            guard appState.isCurrent(token) else { return }
            if error is CancellationError || Task.isCancelled {
                if !didFinishBatch {
                    let summary = "已停止，仅统计已返回的结果"
                    appState.statusMessage = summary
                    if !batchResults.isEmpty {
                        let counts = ChaoxingSignCounts(dispositions: batchResults.map(\.disposition))
                        await appState.recordSignHistory(activity: activity, successCount: counts.accepted, alreadySignedCount: counts.alreadySigned, totalCount: counts.total, summary: summary)
                    }
                }
            } else {
                appState.errorMessage = error.localizedDescription
            }
        }
    }
}

private extension ChaoxingSignDisposition {
    var symbol: String {
        switch self {
        case .accepted: "checkmark"
        case .alreadySigned: "clock"
        case .failed: "exclamationmark"
        }
    }

    var tone: CxTone {
        switch self {
        case .accepted: .sage
        case .alreadySigned: .neutral
        case .failed: .cinnabar
        }
    }
}
