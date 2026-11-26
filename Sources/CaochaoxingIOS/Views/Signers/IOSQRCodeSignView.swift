// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
import PhotosUI
import UIKit
import ChaoxingCore

struct IOSQRCodeSignView: View {
    @Environment(IOSAppState.self) private var appState

    let activity: ChaoxingSignActivity
    let isPositionRequired: Bool
    let isSigning: Bool
    let onSubmit: (ChaoxingQRCodeParseResult, ChaoxingLocationSignInfo?) async -> Void

    @State private var rawQRText = ""
    @State private var parsedResult: ChaoxingQRCodeParseResult?
    @State private var expiredHint: Bool?
    @State private var qrRequest = UUID()
    @State private var showCameraScanner = false
    @State private var pickedPhotoItem: PhotosPickerItem?
    @State private var includeLocation = false
    @State private var selectedPresetId: UUID?

    private var selectedLocation: ChaoxingLocationSignInfo? {
        guard includeLocation else { return nil }
        let bound = appState.boundLocation(forCourseId: activity.courseId)
        let preset = appState.locationPresets.first(where: { $0.id == selectedPresetId })
            ?? (bound?.id == selectedPresetId ? bound : nil)
            ?? bound
            ?? appState.locationPresets.first
        return preset?.toSignInfo(enableRandomOffset: appState.enableLocationJitter)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Button {
                    showCameraScanner = true
                } label: {
                    Label("相机扫码", systemImage: "camera.viewfinder")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(CxPrimaryButtonStyle())

                PhotosPicker(selection: $pickedPhotoItem, matching: .images) {
                    Label("相册识图", systemImage: "photo")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(CxSecondaryButtonStyle())
                .onChange(of: pickedPhotoItem) { _, item in
                    Task { await decodeFromPhoto(item) }
                }
            }

            HStack(spacing: 8) {
                TextField("粘贴学习通二维码链接或 SIGNIN 参数", text: $rawQRText)
                    .font(CxTypography.mono(12.5))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .cxInputField()

                Button("粘贴") {
                    if let text = UIPasteboard.general.string {
                        parseQRCode(text)
                    }
                }
                .buttonStyle(CxSecondaryButtonStyle())
            }
            .onChange(of: rawQRText) { _, newValue in
                parseQRCode(newValue, updateRawText: false)
            }

            if let parsed = parsedResult {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        CxBadge(
                            "AID \(parsed.aid.map(String.init) ?? "跟随当前")",
                            tone: .sage,
                            icon: "qrcode",
                            monospaced: true
                        )
                        Spacer()
                        if let expiredHint {
                            CxBadge(
                                expiredHint ? "请重新扫码" : "二维码未过期",
                                tone: expiredHint ? .amber : .sage,
                                showDot: true
                            )
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .cxWell(padding: 10, cornerRadius: 10)
            }

            IOSLocationAttachmentPickerView(
                courseId: activity.courseId,
                isPositionRequired: isPositionRequired,
                includeLocation: $includeLocation,
                selectedPresetId: $selectedPresetId
            )

            Button {
                guard let parsed = parsedResult else { return }
                guard !isPositionRequired || selectedLocation != nil else {
                    appState.errorMessage = "该活动要求位置，请先选择有效预设"
                    return
                }
                appState.launchSessionTask { await onSubmit(parsed, selectedLocation) }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "qrcode.viewfinder")
                    Text("提交二维码签到")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(CxPrimaryButtonStyle())
            .disabled(isSigning || parsedResult == nil)
        }
        .padding(.vertical, 4)
        .sheet(isPresented: $showCameraScanner) {
            NavigationStack {
                QRCodeCameraScannerView { scanned in
                    showCameraScanner = false
                    parseQRCode(scanned)
                }
                .ignoresSafeArea()
                .navigationTitle("扫描签到二维码")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("关闭") { showCameraScanner = false }
                    }
                }
            }
            .tint(CxPalette.accent)
        }
        .onAppear {
            if selectedPresetId == nil {
                selectedPresetId = appState.locationPresets.first?.id
            }
            if rawQRText.isEmpty, let clip = UIPasteboard.general.string {
                if (try? ChaoxingQRCodeParser.parse(rawValue: clip)) != nil {
                    parseQRCode(clip)
                }
            }
        }
    }

    private func parseQRCode(_ raw: String, updateRawText: Bool = true) {
        if updateRawText && rawQRText != raw { rawQRText = raw }
        let request = UUID()
        qrRequest = request
        expiredHint = nil
        parsedResult = nil
        guard let result = try? ChaoxingQRCodeParser.parse(rawValue: raw) else { return }
        guard result.aid == nil || result.aid == activity.id else {
            appState.errorMessage = "二维码属于其他活动，请返回扫码页处理"
            return
        }
        parsedResult = result
        let token = appState.sessionToken
        appState.launchSessionTask {
            if let client = appState.activeClient {
                let expired = await ChaoxingSignExecutor.isQRCodeExpired(client: client, activeId: activity.id, qrResult: result)
                guard qrRequest == request, appState.isCurrent(token), !Task.isCancelled else { return }
                expiredHint = expired
            }
        }
    }

    private func decodeFromPhoto(_ item: PhotosPickerItem?) async {
        guard let item, let data = try? await item.loadTransferable(type: Data.self) else { return }
        let strings = ChaoxingQRCodeParser.decodeQRCodeStrings(from: data)
        if let first = strings.first {
            parseQRCode(first)
        } else {
            appState.errorMessage = "未在所选图片中识别到有效二维码"
        }
    }
}

struct IOSStandaloneQRCodeSignView: View {
    @Environment(IOSAppState.self) private var appState

    @State private var rawQRText = ""
    @State private var resolution = IOSQRCodeResolution()
    @State private var selectionInitialized = false
    private var parsedResult: ChaoxingQRCodeParseResult? { resolution.parsed }
    private var resolvedActivity: ChaoxingSignActivity? { resolution.activity }
    private var resolvedDetail: ChaoxingSignDetailMetadata? { resolution.detail }
    @State private var showCameraScanner = false
    @State private var pickedPhotoItem: PhotosPickerItem?
    @State private var selectedPhones: Set<String> = []
    @State private var includeLocation = false
    @State private var selectedPresetId: UUID?
    @State private var autoSubmitOnScan = false
    @State private var isWorking = false
    @State private var isVisible = false
    @State private var batchTask: Task<[IOSBatchSignResult], Error>?
    @State private var results: [IOSBatchSignResult] = []

    private var targetAccounts: [ChaoxingAccountSession] {
        appState.selectedAccounts(phones: selectedPhones)
    }

    private var selectedLocationPreset: ChaoxingLocationPreset? {
        guard includeLocation else { return nil }
        let bound = appState.boundLocation(forCourseId: resolvedActivity?.courseId ?? 0)
        return appState.locationPresets.first(where: { $0.id == selectedPresetId })
            ?? (bound?.id == selectedPresetId ? bound : nil)
            ?? bound
            ?? appState.locationPresets.first
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack(spacing: 10) {
                            Button {
                                showCameraScanner = true
                            } label: {
                                Label("相机扫码", systemImage: "camera.viewfinder")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(CxPrimaryButtonStyle())

                            PhotosPicker(selection: $pickedPhotoItem, matching: .images) {
                                Label("相册识码", systemImage: "photo")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(CxSecondaryButtonStyle())
                            .onChange(of: pickedPhotoItem) { _, item in
                                Task { await decodePhoto(item) }
                            }
                        }

                        HStack(spacing: 8) {
                            TextField("粘贴二维码 URL 或 SIGNIN 载荷", text: $rawQRText)
                                .font(CxTypography.mono(12.5))
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .cxInputField()

                            Button("剪贴板") {
                                if let clip = UIPasteboard.general.string {
                                    Task { await parseAndResolve(clip) }
                                }
                            }
                            .buttonStyle(CxSecondaryButtonStyle())
                        }
                        .task(id: rawQRText) {
                            await parseAndResolve(rawQRText, updateField: false)
                        }

                        if let parsed = parsedResult {
                            VStack(alignment: .leading, spacing: 5) {
                                HStack {
                                    CxBadge(
                                        "AID \(parsed.aid.map(String.init) ?? "AUTO")",
                                        tone: .sage,
                                        icon: "checkmark.seal.fill",
                                        monospaced: true
                                    )
                                    if resolvedDetail?.isFaceRequired == true {
                                        CxBadge("需人脸", tone: .plum, icon: "faceid")
                                    }
                                    if resolvedDetail?.isPositionRequired == true {
                                        CxBadge("需定位", tone: .sage, icon: "location.fill")
                                    }
                                    if resolvedDetail?.isRefreshQRCode == true {
                                        CxBadge("动态码 · 免错峰", tone: .amber, showDot: true)
                                    }
                                }
                                if let act = resolvedActivity {
                                    Text(act.nameOne)
                                        .font(CxTypography.serifHeadline(15))
                                        .foregroundStyle(CxPalette.ink)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .cxWell(padding: 10, cornerRadius: 10)
                        }
                    }
                    .padding(.vertical, 4)
                    .cxCardRow()
                } header: {
                    CxSectionHeader("二维码")
                }

                if appState.accounts.count > 1 {
                    Section {
                        IOSAccountGroupSelectorView(selectedPhones: $selectedPhones)
                            .padding(.vertical, 4)
                            .cxCardRow()
                    } header: {
                        CxSectionHeader(
                            "代签账号 (\(targetAccounts.count)/\(appState.accounts.count))"
                        )
                    }
                }

                if !appState.locationPresets.isEmpty || (resolvedActivity.map { appState.boundLocation(forCourseId: $0.courseId) != nil } ?? false) || (resolvedDetail?.isPositionRequired ?? false) {
                    Section {
                        IOSLocationAttachmentPickerView(
                            courseId: resolvedActivity?.courseId ?? 0,
                            isPositionRequired: resolvedDetail?.isPositionRequired ?? false,
                            includeLocation: $includeLocation,
                            selectedPresetId: $selectedPresetId
                        )
                        .padding(.vertical, 2)
                        .cxCardRow()
                    } header: {
                        CxSectionHeader("附带位置")
                    }
                }

                Section {
                    Toggle(isOn: $autoSubmitOnScan) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("扫到即签（动态码推荐）")
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(CxPalette.ink)
                            Text("识别二维码后立即提交，降低动态码过期概率")
                                .font(.caption)
                                .foregroundStyle(CxPalette.inkSecondary)
                        }
                    }
                    .tint(CxPalette.accent)
                    .cxCardRow()

                    Button {
                        appState.launchSessionTask { await submitStandaloneQRSign() }
                    } label: {
                        HStack(spacing: 8) {
                            if isWorking {
                                ProgressView()
                                    .tint(CxPalette.onAccent)
                            } else {
                                Image(systemName: "bolt.horizontal.circle.fill")
                            }
                            Text(isWorking ? "正在提交..." : (resolution.isResolving ? "正在读取活动..." : "为所选账号提交"))
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(CxPrimaryButtonStyle())
                    .disabled(isWorking || resolution.isResolving || parsedResult == nil || targetAccounts.isEmpty)
                    .padding(.vertical, 2)
                    .cxCardRow()
                    if isWorking {
                        Button("停止后续操作", role: .cancel) { batchTask?.cancel() }
                            .cxCardRow()
                        Text("已发出的请求无法撤回")
                            .font(.caption)
                            .foregroundStyle(CxPalette.inkSecondary)
                            .cxCardRow()
                    }
                }

                if !results.isEmpty {
                    Section {
                        ForEach(results) { item in
                            HStack(alignment: .center, spacing: 10) {
                                CxIconTile(
                                    item.success ? "checkmark" : "xmark",
                                    tone: item.success ? .sage : .cinnabar,
                                    size: 30
                                )
                                VStack(alignment: .leading, spacing: 3) {
                                    HStack(spacing: 6) {
                                        Text(item.accountName.isEmpty ? item.phoneNumber : item.accountName)
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(CxPalette.ink)
                                        Text(item.phoneNumber)
                                            .font(CxTypography.mono(11))
                                            .foregroundStyle(CxPalette.inkTertiary)
                                    }
                                    Text(item.message)
                                        .font(.caption)
                                        .foregroundStyle(item.success ? CxPalette.inkSecondary : CxPalette.cinnabar)
                                }
                            }
                            .padding(.vertical, 3)
                            .cxCardRow()
                        }
                    } header: {
                        let passCount = results.filter(\.success).count
                        CxSectionHeader("执行结果", trailing: "成功 \(passCount)/\(results.count)")
                    }
                }
            }
            .listStyle(.insetGrouped)
            .cxEditorialList()
            .navigationTitle("扫码代签")
            .sheet(isPresented: $showCameraScanner) {
                NavigationStack {
                    QRCodeCameraScannerView { scanned in
                        showCameraScanner = false
                        Task { await parseAndResolve(scanned) }
                    }
                    .ignoresSafeArea()
                    .navigationTitle("扫描签到二维码")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("关闭") { showCameraScanner = false }
                        }
                    }
                }
                .tint(CxPalette.accent)
            }
            .onAppear {
                isVisible = true
                if !selectionInitialized {
                    selectionInitialized = true
                    selectedPhones = Set(appState.accounts.map(\.phoneNumber))
                }
                if selectedPresetId == nil {
                    selectedPresetId = appState.locationPresets.first?.id
                }
                if let pending = appState.pendingQRCodeInput {
                    appState.pendingQRCodeInput = nil
                    Task { await parseAndResolve(pending) }
                }
            }
            .onDisappear {
                isVisible = false
                batchTask?.cancel()
            }
            .onChange(of: appState.sessionToken) { _, _ in resolution.invalidate() }
            .onChange(of: appState.pendingQRCodeInput) { _, newValue in
                if let pending = newValue {
                    appState.pendingQRCodeInput = nil
                    Task { await parseAndResolve(pending) }
                }
            }
        }
    }

    private func decodePhoto(_ item: PhotosPickerItem?) async {
        guard let item, let data = try? await item.loadTransferable(type: Data.self) else { return }
        let strings = ChaoxingQRCodeParser.decodeQRCodeStrings(from: data)
        if let first = strings.first {
            await parseAndResolve(first)
        } else {
            appState.errorMessage = "未在图片中识别到二维码"
        }
    }

    private func parseAndResolve(_ raw: String, updateField: Bool = true) async {
        if updateField && rawQRText != raw {
            resolution.invalidate()
            rawQRText = raw
            return // The task keyed by rawQRText performs exactly one lookup.
        }
        guard let client = appState.activeClient else { resolution.invalidate(); return }
        let token = appState.sessionToken
        do {
            try await resolution.resolve(raw) { payload in
                try await ChaoxingActivityService.buildActivityAndDetailFromQRCode(client: client, qrResult: payload)
            }
            try appState.checkSession(token)
            if resolvedDetail?.isPositionRequired == true { includeLocation = true }
            if autoSubmitOnScan,
               !isWorking,
               !targetAccounts.isEmpty,
               resolvedDetail?.isPositionRequired != true || selectedLocationPreset != nil {
                appState.launchSessionTask { await submitStandaloneQRSign() }
            }
        } catch {
            if !appState.isCurrent(token) { resolution.invalidate() }
            if appState.isCurrent(token), !(error is CancellationError), !Task.isCancelled, rawQRText == raw {
                appState.errorMessage = "无法读取二维码活动：\(error.localizedDescription)"
            }
        }
    }

    private func submitStandaloneQRSign() async {
        guard isVisible, !Task.isCancelled, !isWorking, !resolution.isResolving,
              resolution.resolvedInput == rawQRText,
              let parsed = parsedResult, let activity = resolvedActivity, let detail = resolvedDetail else { return }
        guard parsed.aid == activity.id, detail.activeId == activity.id else { return }
        let token = appState.sessionToken
        let preset = selectedLocationPreset
        if detail.isPositionRequired && preset == nil {
            appState.errorMessage = "该活动要求位置，请先选择有效预设"
            return
        }
        isWorking = true
        results = []
        defer { isWorking = false; batchTask = nil }
        let jitter = appState.enableLocationJitter
        let phones = selectedPhones
        let task = Task {
            try await appState.executeSignBatch(activity: activity, selectedPhones: phones, ignoreBatchDelay: detail.isRefreshQRCode, onProgress: { results = $0 }) { client, _, validate, enc2 in
                let location = preset?.toSignInfo(enableRandomOffset: jitter)
                let face = detail.isFaceRequired ? try await ChaoxingFaceService.fetchUserProfileFaceObjectId(client: client) : nil
                try Task.checkCancellation()
                return try await ChaoxingSignExecutor.signQRCode(client: client, activity: activity, qrResult: parsed, location: location, faceImageObjectId: face, captchaValidate: validate, enc2: enc2)
            }
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
            results = collected
            let success = collected.filter(\.success).count
            appState.statusMessage = "扫码完成：\(success)/\(collected.count) 成功"
            await appState.recordSignHistory(activity: activity, successCount: success, totalCount: collected.count, summary: collected.first?.message ?? "扫码完成")
        } catch {
            guard appState.isCurrent(token) else { return }
            if error is CancellationError || Task.isCancelled {
                if !didFinishBatch {
                    let summary = "已停止后续操作；已发出的请求无法撤回"
                    appState.statusMessage = summary
                    if !results.isEmpty {
                        await appState.recordSignHistory(activity: activity, successCount: results.filter(\.success).count, totalCount: results.count, summary: summary)
                    }
                }
            } else { appState.errorMessage = error.localizedDescription }
        }
    }
}
