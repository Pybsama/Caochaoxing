// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

#if os(macOS)
import SwiftUI
import AppKit
import UniformTypeIdentifiers
import ChaoxingCore

private struct MacSignSheetPayload: Identifiable {
    let id = UUID()
    let activity: ChaoxingSignActivity
    let initialQRRaw: String
}

// MARK: - Sign Workspace

struct MacSignWorkspaceView: View {
    @Environment(MacAppState.self) private var appState
    @State private var searchText = ""
    @State private var activeSignSheet: MacSignSheetPayload?
    @State private var showStandaloneQRSheet = false

    private var sortedCourses: [ChaoxingCourse] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtered = q.isEmpty ? appState.courses : appState.courses.filter {
            $0.courseName.localizedCaseInsensitiveContains(q) ||
            $0.teacherName.localizedCaseInsensitiveContains(q)
        }
        return filtered.sorted { lhs, rhs in
            let l = appState.starredCourseIds.contains(lhs.courseId)
            let r = appState.starredCourseIds.contains(rhs.courseId)
            if l != r { return l }
            return false
        }
    }

    var body: some View {
        HStack(spacing: 0) {
            signSidebarColumn
            MacDivider(vertical: true)
            courseDetailPane
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(MacTheme.canvas)
        }
        .background(MacTheme.canvas)
        .sheet(item: $activeSignSheet) { payload in
            MacSignActionSheet(activity: payload.activity, initialQRRaw: payload.initialQRRaw)
        }
        .sheet(isPresented: $showStandaloneQRSheet) {
            MacStandaloneQRSheet { activity, qrRaw in
                showStandaloneQRSheet = false
                activeSignSheet = MacSignSheetPayload(activity: activity, initialQRRaw: qrRaw)
            }
        }
    }

    private var signSidebarColumn: some View {
        VStack(spacing: 0) {
            VStack(spacing: 8) {
                MacSearchField(placeholder: "搜索课程", text: $searchText)

                HStack(spacing: 6) {
                    Button {
                        appState.scanPendingActivities()
                    } label: {
                        HStack(spacing: 5) {
                            if appState.isScanningRadar {
                                ProgressView().controlSize(.mini)
                            } else {
                                Image(systemName: "waveform.path.ecg")
                            }
                            Text(appState.isScanningRadar ? "扫描中" : "扫待签")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(MacPrimaryButtonStyle(compact: true))
                    .disabled(appState.isScanningRadar || appState.courses.isEmpty)

                    Button {
                        showStandaloneQRSheet = true
                    } label: {
                        HStack(spacing: 5) {
                            Image(systemName: "qrcode.viewfinder")
                            Text("扫码签")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(MacSecondaryButtonStyle(compact: true))
                }

                if let message = appState.radarScanMessage {
                    Text(message)
                        .font(.caption2)
                        .foregroundStyle(appState.radarFailedCourses > 0 ? MacTheme.danger : MacTheme.inkSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(10)

            MacDivider()

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    if !appState.pendingRadarActivities.isEmpty {
                        Text("进行中 (\(appState.pendingRadarActivities.count))")
                            .font(.caption2.weight(.semibold))
                            .foregroundStyle(MacTheme.amber)
                            .padding(.horizontal, 10)
                            .padding(.top, 6)
                            .padding(.bottom, 2)

                        ForEach(appState.pendingRadarActivities) { act in
                            radarActivityRow(act)
                        }

                        MacDivider()
                            .padding(.vertical, 6)
                    }

                    Text("课程 (\(sortedCourses.count))")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(MacTheme.inkTertiary)
                        .padding(.horizontal, 10)
                        .padding(.top, 6)
                        .padding(.bottom, 2)

                    ForEach(sortedCourses) { course in
                        courseRow(course)
                    }
                }
                .padding(8)
            }
            .scrollContentBackground(.hidden)
        }
        .frame(width: 262)
        .background(MacTheme.sidebar)
    }

    private func radarActivityRow(_ act: ChaoxingSignActivity) -> some View {
        Button {
            activeSignSheet = MacSignSheetPayload(activity: act, initialQRRaw: "")
        } label: {
            HStack(spacing: 8) {
                Image(systemName: act.signType.systemImage)
                    .font(.caption)
                    .foregroundStyle(MacTheme.amber)
                VStack(alignment: .leading, spacing: 2) {
                    Text(act.courseName.isEmpty ? act.nameOne : "\(act.courseName) · \(act.nameOne)")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(MacTheme.ink)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Text(act.signType.displayName)
                        .font(.caption2)
                        .foregroundStyle(MacTheme.inkSecondary)
                }
                Spacer(minLength: 4)
                MacTag(text: "待签", color: MacTheme.amber)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(MacTheme.amber.opacity(0.09), in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func courseRow(_ course: ChaoxingCourse) -> some View {
        let isSelected = appState.selectedSignCourse?.id == course.id
        let isStarred = appState.starredCourseIds.contains(course.courseId)

        return HStack(spacing: 6) {
            Button {
                appState.loadActivities(for: course)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(course.courseName)
                        .font(.subheadline.weight(isSelected ? .semibold : .regular))
                        .foregroundStyle(MacTheme.ink)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    if !course.teacherName.isEmpty {
                        Text(course.teacherName)
                            .font(.caption2)
                            .foregroundStyle(MacTheme.inkSecondary)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button {
                Task { await appState.toggleStarCourse(course.courseId) }
            } label: {
                Image(systemName: isStarred ? "star.fill" : "star")
                    .font(.caption)
                    .foregroundStyle(isStarred ? MacTheme.amber : MacTheme.inkTertiary)
            }
            .buttonStyle(.plain)
            .help(isStarred ? "取消标星" : "标星优先扫描")
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .fill(isSelected ? MacTheme.selection : Color.clear)
        )
    }

    @ViewBuilder
    private var courseDetailPane: some View {
        if let course = appState.selectedSignCourse {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(course.courseName)
                            .font(.headline)
                            .foregroundStyle(MacTheme.ink)
                            .lineLimit(1)
                        if !course.teacherName.isEmpty {
                            Text(course.teacherName)
                                .font(.caption)
                                .foregroundStyle(MacTheme.inkSecondary)
                        }
                    }
                    Spacer()
                    Button {
                        appState.selectedTab = .study
                        appState.selectStudyCourse(course)
                    } label: {
                        Label("去本课看课", systemImage: "play.circle")
                    }
                    .buttonStyle(MacSecondaryButtonStyle(compact: true))

                    Button {
                        appState.loadActivities(for: course)
                    } label: {
                        Label("刷新", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(MacSecondaryButtonStyle(compact: true))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 11)
                .background(MacTheme.canvas)

                MacDivider()

                if appState.isLoadingActivities {
                    VStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("加载签到活动...")
                            .font(.caption)
                            .foregroundStyle(MacTheme.inkSecondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(MacTheme.canvas)
                } else if let err = appState.signErrorMessage, appState.courseActivities.isEmpty {
                    MacEmptyState(icon: "exclamationmark.triangle", title: "加载失败", subtitle: err)
                } else if appState.courseActivities.isEmpty {
                    MacEmptyState(icon: "checkmark.seal", title: "暂无签到活动", subtitle: "该课程当前没有记录到的签到任务")
                } else {
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(appState.courseActivities) { act in
                                MacActivityRow(activity: act) {
                                    activeSignSheet = MacSignSheetPayload(activity: act, initialQRRaw: "")
                                }
                            }
                        }
                        .padding(16)
                    }
                    .macCanvas()
                }
            }
        } else {
            MacEmptyState(
                icon: "location.viewfinder",
                title: "选择课程或扫描待签",
                subtitle: "在左侧选择课程查看全部签到活动，或点「扫待签」快速查找进行中的考勤"
            )
        }
    }
}

// MARK: - Group Sign Workspace

struct MacGroupSignWorkspaceView: View {
    @Environment(MacAppState.self) private var appState
    @State private var activeSignSheet: ChaoxingSignActivity?

    var body: some View {
        HStack(spacing: 0) {
            groupListColumn
            MacDivider(vertical: true)
            groupDetailColumn
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(MacTheme.canvas)
        }
        .background(MacTheme.canvas)
        .sheet(item: $activeSignSheet) { act in
            MacSignActionSheet(activity: act)
        }
    }

    private var groupListColumn: some View {
        VStack(spacing: 0) {
            HStack {
                Text("我的群聊 (\(appState.groups.count))")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(MacTheme.inkSecondary)
                Spacer()
                Button {
                    Task { await appState.loadGroups() }
                } label: {
                    if appState.isLoadingGroups {
                        ProgressView().controlSize(.mini)
                    } else {
                        Image(systemName: "arrow.clockwise")
                            .font(.caption)
                            .foregroundStyle(MacTheme.inkSecondary)
                    }
                }
                .buttonStyle(.plain)
                .disabled(appState.isLoadingGroups)
                .help("刷新群聊")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)

            MacDivider()

            if appState.groups.isEmpty && !appState.isLoadingGroups {
                VStack {
                    Text("暂无群聊")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkTertiary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(appState.groups) { group in
                            let isSelected = appState.selectedGroup?.id == group.id
                            Button {
                                Task { await appState.loadGroupActivities(for: group) }
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(group.name)
                                        .font(.subheadline.weight(isSelected ? .semibold : .regular))
                                        .foregroundStyle(MacTheme.ink)
                                        .lineLimit(2)
                                        .multilineTextAlignment(.leading)
                                    Text("ID \(group.id)")
                                        .font(.system(size: 10, design: .monospaced))
                                        .foregroundStyle(MacTheme.inkTertiary)
                                }
                                .padding(.horizontal, 10)
                                .padding(.vertical, 8)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(
                                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                                        .fill(isSelected ? MacTheme.selection : Color.clear)
                                )
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(8)
                }
                .scrollContentBackground(.hidden)
            }
        }
        .frame(width: 248)
        .background(MacTheme.sidebar)
        .task {
            if appState.groups.isEmpty {
                await appState.loadGroups()
            }
        }
    }

    @ViewBuilder
    private var groupDetailColumn: some View {
        if let group = appState.selectedGroup {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(group.name)
                        .font(.headline)
                        .foregroundStyle(MacTheme.ink)
                    Spacer()
                    Button {
                        Task { await appState.loadGroupActivities(for: group) }
                    } label: {
                        Label("刷新", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(MacSecondaryButtonStyle(compact: true))
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 11)

                MacDivider()

                if appState.isLoadingActivities {
                    VStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("加载群聊活动...")
                            .font(.caption)
                            .foregroundStyle(MacTheme.inkSecondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if appState.groupActivities.isEmpty {
                    MacEmptyState(icon: "bubble.left", title: "暂无群聊签到")
                } else {
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(appState.groupActivities) { act in
                                MacActivityRow(activity: act) {
                                    activeSignSheet = act
                                }
                            }
                        }
                        .padding(16)
                    }
                    .macCanvas()
                }
            }
        } else {
            MacEmptyState(icon: "bubble.left.and.bubble.right", title: "选择一个群聊", subtitle: "在左侧点击群聊查看群内签到活动")
        }
    }
}

// MARK: - Settings Workspace

struct MacSettingsWorkspaceView: View {
    @Environment(MacAppState.self) private var appState
    @State private var presetName = ""
    @State private var presetAddress = ""
    @State private var presetLat = ""
    @State private var presetLng = ""
    @State private var editingRemarkPhone: String?
    @State private var remarkDraft = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                accountsBox
                presetDefaultsBox
                locationPresetsBox
                historyBox
            }
            .frame(maxWidth: 740, alignment: .leading)
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
            .frame(maxWidth: .infinity)
        }
        .macCanvas()
    }

    private var accountsBox: some View {
        MacSectionBox("账号切换与管理", subtitle: "已保存 \(appState.accounts.count) 个 · 点击账号即可切换，支持 ⌥⌘1..9") {
            VStack(alignment: .leading, spacing: 8) {
                ForEach(Array(appState.accounts.enumerated()), id: \.element.id) { index, acc in
                    let isActive = acc.phoneNumber == appState.activeAccount?.phoneNumber
                    let isRefreshing = appState.refreshingAccountPhones.contains(acc.phoneNumber)
                    let remark = appState.accountRemark(for: acc)

                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 10) {
                            Button {
                                guard !isActive else { return }
                                Task { await appState.switchAccount(acc) }
                            } label: {
                                HStack(spacing: 10) {
                                    Circle()
                                        .fill(isActive ? MacTheme.sage.opacity(0.14) : MacTheme.surfaceSecondary)
                                        .frame(width: 32, height: 32)
                                        .overlay(
                                            Text(String(appState.accountDisplayTitle(for: acc).prefix(1)))
                                                .font(.caption.weight(.semibold))
                                                .foregroundStyle(isActive ? MacTheme.sage : MacTheme.inkSecondary)
                                        )

                                    VStack(alignment: .leading, spacing: 2) {
                                        HStack(spacing: 6) {
                                            Text(acc.name.isEmpty ? acc.phoneNumber : acc.name)
                                                .font(.subheadline.weight(.semibold))
                                                .foregroundStyle(MacTheme.ink)
                                            if !remark.isEmpty {
                                                MacTag(text: remark, color: MacTheme.accent)
                                            }
                                            if isActive {
                                                MacTag(text: "当前使用", color: MacTheme.sage)
                                            }
                                            if index < 9 {
                                                Text("⌥⌘\(index + 1)")
                                                    .font(.system(size: 10, design: .monospaced))
                                                    .foregroundStyle(MacTheme.inkTertiary)
                                            }
                                        }
                                        HStack(spacing: 6) {
                                            Text(acc.phoneNumber)
                                            if let school = acc.schools.first?.name, !school.isEmpty {
                                                Text("· \(school)")
                                            }
                                        }
                                        .font(.caption)
                                        .foregroundStyle(MacTheme.inkSecondary)
                                    }

                                    Spacer(minLength: 8)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(isActive || appState.isSwitchingAccount)

                            if !isActive {
                                Button("切换") {
                                    Task { await appState.switchAccount(acc) }
                                }
                                .buttonStyle(MacPrimaryButtonStyle(compact: true))
                                .disabled(appState.isSwitchingAccount)
                            }

                            Button {
                                if editingRemarkPhone == acc.phoneNumber {
                                    editingRemarkPhone = nil
                                } else {
                                    editingRemarkPhone = acc.phoneNumber
                                    remarkDraft = remark
                                }
                            } label: {
                                Text("备注")
                            }
                            .buttonStyle(MacSecondaryButtonStyle(compact: true))

                            Button {
                                Task { await appState.refreshAccountSession(acc) }
                            } label: {
                                if isRefreshing {
                                    ProgressView().controlSize(.mini).frame(width: 14, height: 14)
                                } else {
                                    Image(systemName: "arrow.clockwise")
                                }
                            }
                            .buttonStyle(MacSecondaryButtonStyle(compact: true))
                            .disabled(isRefreshing)
                            .help("刷新会话")

                            Button {
                                Task { await appState.removeAccount(acc) }
                            } label: {
                                Image(systemName: "trash")
                            }
                            .buttonStyle(MacSecondaryButtonStyle(compact: true, isDestructive: true))
                            .help(isActive ? "退出并移除当前账号" : "移除账号")
                        }

                        if editingRemarkPhone == acc.phoneNumber {
                            HStack(spacing: 8) {
                                TextField("备注（如 本人 / 室友，留空清除）", text: $remarkDraft)
                                    .macInputField()
                                    .onSubmit {
                                        let draft = remarkDraft
                                        editingRemarkPhone = nil
                                        Task { await appState.updateAccountRemark(phoneNumber: acc.phoneNumber, remark: draft) }
                                    }
                                Button("保存") {
                                    let draft = remarkDraft
                                    editingRemarkPhone = nil
                                    Task { await appState.updateAccountRemark(phoneNumber: acc.phoneNumber, remark: draft) }
                                }
                                .buttonStyle(MacPrimaryButtonStyle(compact: true))
                                Button("取消") {
                                    editingRemarkPhone = nil
                                }
                                .buttonStyle(MacSecondaryButtonStyle(compact: true))
                            }
                            .padding(.leading, 42)
                        }
                    }
                    .padding(10)
                    .background(
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(isActive ? MacTheme.selection : MacTheme.sidebar)
                    )
                }

                HStack(spacing: 10) {
                    Button {
                        appState.loginErrorMessage = nil
                        appState.showAddAccountSheet = true
                    } label: {
                        Label("添加新账号", systemImage: "person.badge.plus")
                    }
                    .buttonStyle(MacPrimaryButtonStyle(compact: true))

                    if appState.accounts.count > 1 {
                        Button {
                            Task { await appState.switchToNextAccount() }
                        } label: {
                            Label("切换下一个 (⌥⌘N)", systemImage: "arrow.triangle.2.circlepath")
                        }
                        .buttonStyle(MacSecondaryButtonStyle(compact: true))
                        .disabled(appState.isSwitchingAccount)
                    }

                    Spacer()
                }
                .padding(.top, 4)
            }
        }
    }

    private var presetDefaultsBox: some View {
        MacSectionBox("默认选项") {
            VStack(alignment: .leading, spacing: 11) {
                HStack {
                    Text("默认并发路数")
                        .font(.subheadline)
                        .foregroundStyle(MacTheme.ink)
                    Spacer()
                    HStack(spacing: 4) {
                        ForEach([1, 2, 3, 5, 8, 10], id: \.self) { count in
                            let selected = appState.maxConcurrentWatches == count
                            Button("\(count) 路") {
                                appState.maxConcurrentWatches = count
                            }
                            .font(.caption.weight(selected ? .semibold : .regular))
                            .foregroundStyle(selected ? MacTheme.accent : MacTheme.inkSecondary)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 4.5)
                            .background(
                                RoundedRectangle(cornerRadius: 6, style: .continuous)
                                    .fill(selected ? MacTheme.accent.opacity(0.12) : MacTheme.sidebar)
                            )
                            .buttonStyle(.plain)
                        }
                    }
                }

                MacDivider()
                settingToggleRow("单视频通过后自动连播下一节", isOn: Bindable(appState).autoAdvanceNextChapter)
                MacDivider()
                settingToggleRow("同时看时自动匹配并续看其他课程", isOn: Bindable(appState).watchAllCoursesAutomatically)
                MacDivider()
                settingToggleRow("同时看时顺便完成 PDF / 文档任务点", isOn: Bindable(appState).autoCompleteDocuments)
                MacDivider()
                settingToggleRow("位置签到启用轻微随机坐标偏移", isOn: Binding(
                    get: { appState.enableLocationJitter },
                    set: { appState.setEnableLocationJitter($0) }
                ))
                MacDivider()
                settingToggleRow("打开应用并恢复登录后自动开始同时看", isOn: Bindable(appState).autoStartOnLaunch)
            }
        }
    }

    private func settingToggleRow(_ title: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(MacTheme.ink)
        }
        .toggleStyle(.switch)
        .controlSize(.small)
    }

    private var locationPresetsBox: some View {
        MacSectionBox("常用签到位置") {
            VStack(alignment: .leading, spacing: 10) {
                if appState.locationPresets.isEmpty {
                    Text("尚未添加位置预设")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkTertiary)
                } else {
                    ForEach(Array(appState.locationPresets.enumerated()), id: \.element.id) { idx, preset in
                        if idx > 0 { MacDivider() }
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(preset.name)
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(MacTheme.ink)
                                Text("\(preset.address) · \(String(format: "%.6f, %.6f", preset.latitude, preset.longitude))")
                                    .font(.caption2)
                                    .foregroundStyle(MacTheme.inkSecondary)
                            }
                            Spacer()
                            Button {
                                Task { await appState.deleteLocationPreset(preset.id) }
                            } label: {
                                Image(systemName: "trash")
                                    .font(.caption)
                                    .foregroundStyle(MacTheme.inkSecondary)
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(.vertical, 2)
                    }
                }

                MacDivider()

                HStack(spacing: 8) {
                    TextField("名称（如 教学楼）", text: $presetName)
                        .macInputField()
                    TextField("地址", text: $presetAddress)
                        .macInputField()
                    TextField("纬度", text: $presetLat)
                        .macInputField()
                        .frame(width: 96)
                    TextField("经度", text: $presetLng)
                        .macInputField()
                        .frame(width: 96)
                    Button("添加") {
                        guard let lat = Double(presetLat), let lng = Double(presetLng), !presetName.isEmpty else { return }
                        let addr = presetAddress.isEmpty ? presetName : presetAddress
                        Task {
                            await appState.addLocationPreset(name: presetName, address: addr, latitude: lat, longitude: lng)
                            presetName = ""; presetAddress = ""; presetLat = ""; presetLng = ""
                        }
                    }
                    .buttonStyle(MacPrimaryButtonStyle(compact: true))
                }
            }
        }
    }

    private var historyBox: some View {
        MacSectionBox("最近签到记录", subtitle: "\(appState.signHistory.count) 条") {
            VStack(alignment: .leading, spacing: 8) {
                if appState.signHistory.isEmpty {
                    Text("暂无签到记录")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkTertiary)
                } else {
                    ForEach(Array(appState.signHistory.prefix(15).enumerated()), id: \.element.id) { idx, item in
                        if idx > 0 { MacDivider() }
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(item.courseName.isEmpty ? "活动" : item.courseName) · \(item.activityName)")
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(MacTheme.ink)
                                Text(item.summary)
                                    .font(.caption2)
                                    .foregroundStyle(MacTheme.inkSecondary)
                            }
                            Spacer()
                            Text(item.displayCounts)
                                .font(.caption)
                                .foregroundStyle(MacTheme.inkSecondary)
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
        }
    }
}

// MARK: - Activity Row

private struct MacActivityRow: View {
    let activity: ChaoxingSignActivity
    let onTap: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: activity.signType.systemImage)
                .font(.subheadline)
                .foregroundStyle(activity.isAvailable ? MacTheme.accent : MacTheme.inkTertiary)
                .frame(width: 30, height: 30)
                .background(
                    (activity.isAvailable ? MacTheme.accent : MacTheme.inkTertiary).opacity(0.10),
                    in: RoundedRectangle(cornerRadius: 7, style: .continuous)
                )

            VStack(alignment: .leading, spacing: 2) {
                Text(activity.nameOne)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(MacTheme.ink)
                Text("\(activity.signType.displayName) · #\(activity.id)")
                    .font(.caption)
                    .foregroundStyle(MacTheme.inkSecondary)
            }
            Spacer()
            if activity.isAvailable {
                Button("去签到") { onTap() }
                    .buttonStyle(MacPrimaryButtonStyle(compact: true))
            } else {
                MacTag(text: "已结束")
                Button("重试") { onTap() }
                    .buttonStyle(MacSecondaryButtonStyle(compact: true))
            }
        }
        .macPanel(padding: 12)
    }
}

// MARK: - Sign Action & Captcha Sheets

private enum MacPhotoSignMode: String, CaseIterable, Identifiable {
    case fastClick = "无图快签"
    case profileFace = "使用底库头像"
    case localImage = "上传本地图片"
    var id: String { rawValue }
}

private struct MacStandaloneQRSheet: View {
    @Environment(MacAppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    let onResolved: (ChaoxingSignActivity, String) -> Void

    @State private var rawInput = ""
    @State private var isResolving = false
    @State private var errorText: String?
    @State private var resolutionTask: Task<Void, Never>?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("独立扫码签到")
                    .font(.headline)
                    .foregroundStyle(MacTheme.ink)
                Spacer()
                Button("关闭") { close() }
                    .buttonStyle(MacSecondaryButtonStyle(compact: true))
            }
            Text("粘贴超星签到二维码链接（SIGNIN:... 或 https://...）或选择二维码截图。")
                .font(.caption)
                .foregroundStyle(MacTheme.inkSecondary)

            TextField("粘贴二维码内容或链接", text: $rawInput)
                .macInputField()
                .onSubmit { resolve() }

            HStack(spacing: 10) {
                Button {
                    pickQRImage()
                } label: {
                    Label("从图片识别二维码", systemImage: "photo.on.rectangle")
                }
                .buttonStyle(MacSecondaryButtonStyle(compact: true))

                Spacer()

                Button {
                    resolve()
                } label: {
                    if isResolving {
                        ProgressView().controlSize(.mini)
                    } else {
                        Text("解析活动")
                    }
                }
                .buttonStyle(MacPrimaryButtonStyle(compact: true))
                .disabled(isResolving || rawInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }

            if let errorText {
                Text(errorText)
                    .font(.caption)
                    .foregroundStyle(MacTheme.danger)
            }
        }
        .padding(20)
        .frame(width: 460)
        .background(MacTheme.canvas)
        .onChange(of: appState.signSessionID) { close() }
        .onDisappear { resolutionTask?.cancel() }
    }

    private func pickQRImage() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url {
            if let decoded = MacAppState.detectQRCodeFromImageURL(url) {
                rawInput = decoded
                errorText = nil
            } else {
                errorText = "未在所选图片中识别到有效二维码"
            }
        }
    }

    private func resolve() {
        guard !isResolving else { return }
        isResolving = true
        errorText = nil
        let sessionID = appState.signSessionID
        resolutionTask = Task {
            do {
                let (act, raw) = try await appState.resolveStandaloneQRCode(rawInput)
                guard !Task.isCancelled, sessionID == appState.signSessionID else { return }
                onResolved(act, raw)
            } catch {
                guard !Task.isCancelled, sessionID == appState.signSessionID else { return }
                errorText = error.localizedDescription
            }
            isResolving = false
        }
    }

    private func close() {
        resolutionTask?.cancel()
        dismiss()
    }
}

struct MacSignActionSheet: View {
    @Environment(MacAppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    let activity: ChaoxingSignActivity
    var initialQRRaw: String = ""

    @State private var currentActivity: ChaoxingSignActivity
    @State private var isSignOutDrillDown = false
    @State private var selectedLocationId: UUID?
    @State private var rememberLocationForCourse = true
    @State private var codeInput = ""
    @State private var qrInput = ""
    @State private var photoMode: MacPhotoSignMode = .fastClick
    @State private var selectedPhotoURL: URL?
    @State private var selectedPhotoData: Data?
    @State private var prepared: MacPreparedSignWorkspace?
    @State private var isLoadingInfo = true
    @State private var isOpeningSignOut = false
    @State private var signOutError: String?
    @State private var selectedPhones: Set<String> = []
    @State private var owner = UUID()
    @State private var linkedActivityTask: Task<Void, Never>?
    @State private var results: [MacSignResult] = []

    private var currentPreparation: MacPreparedSignWorkspace? {
        guard let prepared, prepared.activityID == currentActivity.id,
              prepared.sessionID == appState.signSessionID else { return nil }
        return prepared
    }
    private var detailMeta: ChaoxingSignDetailMetadata? { currentPreparation?.detail }
    private var isSubmitting: Bool { appState.isSubmitting(owner: owner) }
    private var captchaSheet: Binding<MacCaptchaChallenge?> {
        let id = isSubmitting ? appState.activeCaptchaChallenge?.id : nil
        return Binding(
            get: { isSubmitting ? appState.activeCaptchaChallenge : nil },
            set: { if $0 == nil, let id { appState.cancelCaptchaChallenge(id: id) } }
        )
    }

    init(activity: ChaoxingSignActivity, initialQRRaw: String = "") {
        self.activity = activity
        self.initialQRRaw = initialQRRaw
        _currentActivity = State(initialValue: activity)
        _qrInput = State(initialValue: initialQRRaw)
    }

    private var effectiveSignType: ChaoxingSignType {
        currentPreparation?.signType(for: currentActivity) ?? currentActivity.signType
    }

    private var needsLocationPicker: Bool {
        effectiveSignType == .location || (detailMeta?.isPositionRequired ?? false)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text((isSignOutDrillDown ? "[签退] " : "") + currentActivity.nameOne)
                            .font(.headline)
                            .foregroundStyle(MacTheme.ink)
                        Text("\(currentActivity.courseName.isEmpty ? "活动 #\(currentActivity.id)" : currentActivity.courseName) · \(effectiveSignType.displayName)")
                            .font(.caption)
                            .foregroundStyle(MacTheme.inkSecondary)
                    }
                    Spacer()
                    if isLoadingInfo {
                        ProgressView().controlSize(.small)
                    }
                    if isSignOutDrillDown {
                        Button("返回签到") {
                            currentActivity = activity
                            isSignOutDrillDown = false
                            results = []
                        }
                        .buttonStyle(MacSecondaryButtonStyle(compact: true))
                        .disabled(isSubmitting)
                    }
                    Button("关闭") { close() }
                        .buttonStyle(MacSecondaryButtonStyle(compact: true))
                }

                // Live status & server requirement tags
                HStack(spacing: 6) {
                    if let status = currentPreparation?.status {
                        switch status {
                        case .readyToSign:
                            MacTag(text: "可签到", color: MacTheme.sage)
                        case .alreadySigned:
                            MacTag(text: "当前号已签", color: MacTheme.accent)
                        case .expired:
                            MacTag(text: "已截止", color: MacTheme.danger)
                        }
                    } else if !isLoadingInfo {
                        Text("未能确认活动状态，请重新打开后再试")
                            .font(.caption)
                            .foregroundStyle(MacTheme.danger)
                    }
                    if let detail = detailMeta {
                        if detail.isPhotoRequired { MacTag(text: "需拍照", color: MacTheme.amber) }
                        if detail.isPositionRequired { MacTag(text: "需位置", color: MacTheme.amber) }
                        if detail.isCaptchaRequired { MacTag(text: "需滑块", color: MacTheme.danger) }
                        if detail.isFaceRequired { MacTag(text: "自动人脸核验", color: MacTheme.accent) }
                        if let range = detail.targetLocation?.rangeMeters, range > 0 {
                            MacTag(text: "围栏 \(range)m", monospaced: true)
                        }
                    }
                }

                if !isSignOutDrillDown, let signOutId = detailMeta?.signOutInfo.signOutId, signOutId > 0 {
                    HStack {
                        Image(systemName: "arrow.uturn.right.circle.fill")
                            .foregroundStyle(MacTheme.accent)
                        Text("检测到关联签退活动 (#\(signOutId))")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(MacTheme.ink)
                        Spacer()
                        Button {
                            openLinkedSignOut(signOutId: signOutId)
                        } label: {
                            if isOpeningSignOut {
                                ProgressView().controlSize(.mini)
                            } else {
                                Text("切换至签退")
                            }
                        }
                        .buttonStyle(MacSecondaryButtonStyle(compact: true))
                        .disabled(isOpeningSignOut || isSubmitting)
                    }
                    .padding(10)
                    .background(MacTheme.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }

                if let signOutError {
                    Text(signOutError)
                        .font(.caption)
                        .foregroundStyle(MacTheme.danger)
                }

                MacDivider()

                signInputSection

                if needsLocationPicker && effectiveSignType != .location {
                    MacDivider()
                    locationPickerBlock(title: "该活动同时要求位置校验")
                }

                if appState.accounts.count > 1 {
                    MacDivider()
                    VStack(alignment: .leading, spacing: 6) {
                        Text("执行账号")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(MacTheme.inkSecondary)
                        ForEach(appState.accounts) { acc in
                            Toggle("\(appState.accountDisplayTitle(for: acc)) (\(acc.phoneNumber))", isOn: Binding(
                                get: { selectedPhones.contains(acc.phoneNumber) },
                                set: { checked in
                                    if checked { selectedPhones.insert(acc.phoneNumber) }
                                    else { selectedPhones.remove(acc.phoneNumber) }
                                }
                            ))
                            .toggleStyle(.checkbox)
                            .font(.subheadline)
                            .disabled(isSubmitting)
                        }
                    }
                }

                if !results.isEmpty {
                    MacDivider()
                    VStack(alignment: .leading, spacing: 6) {
                        Text(ChaoxingSignCounts(dispositions: results.map(\.disposition)).summary)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(MacTheme.ink)
                        ForEach(results) { res in
                            HStack {
                                Text(res.accountName)
                                    .foregroundStyle(MacTheme.ink)
                                Spacer()
                                switch res.disposition {
                                case .accepted:
                                    Label("本次请求获接受", systemImage: "checkmark.circle.fill")
                                        .foregroundStyle(MacTheme.sage)
                                case .alreadySigned:
                                    Label("此前已签", systemImage: "clock")
                                        .foregroundStyle(MacTheme.inkSecondary)
                                case .failed:
                                    Label("失败或未确认 · \(res.message)", systemImage: "xmark.circle.fill")
                                        .foregroundStyle(MacTheme.danger)
                                }
                            }
                            .font(.caption)
                        }
                    }
                    .macPanel(padding: 10)
                }

                HStack {
                    if detailMeta?.isFaceRequired == true {
                        Text("提交时将自动附带各账号人脸底库凭证")
                            .font(.caption2)
                            .foregroundStyle(MacTheme.inkSecondary)
                    }
                    Spacer()
                    Button {
                        submit()
                    } label: {
                        if isSubmitting {
                            ProgressView().controlSize(.small)
                        } else {
                            Text(isSignOutDrillDown ? "立即签退" : "立即签到")
                                .frame(minWidth: 72)
                        }
                    }
                    .buttonStyle(MacPrimaryButtonStyle())
                    .disabled(isLoadingInfo || isOpeningSignOut || !appState.canExecuteSign(
                        activity: currentActivity, prepared: currentPreparation, targetPhones: selectedPhones
                    ))
                }
                .padding(.top, 4)
            }
            .padding(20)
        }
        .macCanvas()
        .frame(width: 520, height: 470)
        .onAppear {
            if let bound = appState.boundLocation(for: currentActivity.courseId),
               let matching = appState.locationPresets.first(where: { $0.id == bound.id || $0.name == bound.name }) {
                selectedLocationId = matching.id
            } else if let firstLoc = appState.locationPresets.first {
                selectedLocationId = firstLoc.id
            }
            if let phone = appState.activeAccount?.phoneNumber {
                selectedPhones = [phone]
            }
        }
        .task(id: currentActivity.id) {
            isLoadingInfo = true
            prepared = nil
            let result = await appState.prepareSignWorkspace(for: currentActivity)
            guard !Task.isCancelled, result.activityID == currentActivity.id,
                  result.sessionID == appState.signSessionID else { return }
            prepared = result
            if result.detail?.isPhotoRequired == true && photoMode == .fastClick {
                photoMode = .profileFace
            }
            isLoadingInfo = false
        }
        .onChange(of: appState.signSessionID) { close() }
        .onDisappear { cancelSheetTasks() }
        .sheet(item: captchaSheet) { challenge in
            MacCaptchaSheet(challenge: challenge)
                .onDisappear { appState.cancelCaptchaChallenge(id: challenge.id) }
        }
    }

    @ViewBuilder
    private var signInputSection: some View {
        switch effectiveSignType {
        case .location:
            locationPickerBlock(title: "签到位置")
        case .password, .gesture:
            TextField(effectiveSignType == .gesture ? "输入手势数字（如 1235789）" : "输入签到码", text: $codeInput)
                .macInputField()
        case .qrCode:
            VStack(alignment: .leading, spacing: 8) {
                TextField("粘贴二维码链接或 enc 值", text: $qrInput)
                    .macInputField()
                Button("从本地二维码图片识别") {
                    pickQRImage()
                }
                .buttonStyle(MacSecondaryButtonStyle(compact: true))
            }
        case .unknown:
            Text("未能识别此活动类型")
                .font(.caption)
                .foregroundStyle(MacTheme.inkSecondary)
        case .photoOrClick:
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 6) {
                    ForEach(MacPhotoSignMode.allCases) { mode in
                        let selected = photoMode == mode
                        Button(mode.rawValue) {
                            photoMode = mode
                        }
                        .font(.caption.weight(selected ? .semibold : .regular))
                        .foregroundStyle(selected ? MacTheme.accent : MacTheme.inkSecondary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .fill(selected ? MacTheme.accent.opacity(0.12) : MacTheme.sidebar)
                        )
                        .buttonStyle(.plain)
                    }
                }

                switch photoMode {
                case .fastClick:
                    Text("不上传照片直接提交，适用于普通点击签到。")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkSecondary)
                case .profileFace:
                    Text("自动拉取各账号超星人脸底库照片作为签到图片提交。")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkSecondary)
                case .localImage:
                    HStack(spacing: 8) {
                        Button("选择本地图片") {
                            pickSignPhoto()
                        }
                        .buttonStyle(MacSecondaryButtonStyle(compact: true))
                        if let photoURL = selectedPhotoURL {
                            Text(photoURL.lastPathComponent)
                                .font(.caption)
                                .foregroundStyle(MacTheme.sage)
                                .lineLimit(1)
                            Button("清除") {
                                selectedPhotoURL = nil
                                selectedPhotoData = nil
                            }
                            .font(.caption)
                            .foregroundStyle(MacTheme.inkSecondary)
                            .buttonStyle(.plain)
                        } else {
                            Text("未选择图片")
                                .font(.caption)
                                .foregroundStyle(MacTheme.inkTertiary)
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func locationPickerBlock(title: String) -> some View {
        if appState.locationPresets.isEmpty {
            Text("尚未配置位置预设，请先到「设置」中添加经纬度预设。")
                .font(.caption)
                .foregroundStyle(MacTheme.danger)
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Picker(title, selection: $selectedLocationId) {
                    ForEach(appState.locationPresets, id: \.id) { preset in
                        Text("\(preset.name) (\(preset.address))").tag(Optional(preset.id))
                    }
                }
                Toggle("记住为本课默认签到位置", isOn: $rememberLocationForCourse)
                    .toggleStyle(.checkbox)
                    .font(.caption)
                    .foregroundStyle(MacTheme.inkSecondary)
            }
        }
    }

    private func openLinkedSignOut(signOutId: Int64) {
        isOpeningSignOut = true
        signOutError = nil
        let sessionID = appState.signSessionID
        linkedActivityTask = Task {
            do {
                let target = try await appState.buildLinkedSignOutActivity(signOutId: signOutId, source: currentActivity)
                guard !Task.isCancelled, sessionID == appState.signSessionID else { return }
                currentActivity = target
                isSignOutDrillDown = true
                results = []
            } catch {
                guard !Task.isCancelled, sessionID == appState.signSessionID else { return }
                signOutError = "打开签退活动失败：\(error.localizedDescription)"
            }
            isOpeningSignOut = false
        }
    }

    private func pickQRImage() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url,
           let decoded = MacAppState.detectQRCodeFromImageURL(url) {
            qrInput = decoded
        }
    }

    private func pickSignPhoto() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [.image]
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url,
           let data = try? Data(contentsOf: url) {
            selectedPhotoURL = url
            selectedPhotoData = data
        }
    }

    private func submit() {
        guard !isLoadingInfo, !isOpeningSignOut else { return }
        let loc = appState.locationPresets.first { $0.id == selectedLocationId }
        appState.executeSign(
            owner: owner, activity: currentActivity, prepared: currentPreparation,
            location: loc, code: codeInput, qrCodeRaw: qrInput, useProfileFacePhoto: photoMode == .profileFace,
            photoImageData: photoMode == .localImage ? selectedPhotoData : nil,
            rememberLocationForCourse: rememberLocationForCourse, targetPhones: selectedPhones
        ) { results = $0 }
    }

    private func cancelSheetTasks() {
        linkedActivityTask?.cancel()
        appState.cancelSignBatch(owner: owner)
    }

    private func close() {
        cancelSheetTasks()
        dismiss()
    }
}

private struct MacCaptchaSheet: View {
    @Environment(MacAppState.self) private var appState
    let challenge: MacCaptchaChallenge

    @State private var sliderX: Double = 0
    @State private var isVerifying = false
    @State private var feedback: String?
    @State private var verificationTask: Task<Void, Never>?

    private var shadeImage: NSImage? { NSImage(data: challenge.shadeImageData) }
    private var cutoutImage: NSImage? { NSImage(data: challenge.cutoutImageData) }

    private var bgWidth: Double {
        let w = Double(shadeImage?.size.width ?? 320)
        return max(100, w)
    }

    private var maxOffset: Double {
        let cw = Double(cutoutImage?.size.width ?? 56)
        return max(10, bgWidth - cw)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text("滑块验证 · \(challenge.accountName)")
                    .font(.headline)
                    .foregroundStyle(MacTheme.ink)
                Spacer()
                Button("取消") {
                    appState.cancelCaptchaChallenge(id: challenge.id)
                }
                .buttonStyle(MacSecondaryButtonStyle(compact: true))
            }

            if let bg = shadeImage {
                ZStack(alignment: .topLeading) {
                    Image(nsImage: bg)
                        .resizable()
                        .frame(width: bgWidth, height: bg.size.height)
                    if let cut = cutoutImage {
                        Image(nsImage: cut)
                            .resizable()
                            .frame(width: cut.size.width, height: cut.size.height)
                            .offset(x: sliderX)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).stroke(MacTheme.border, lineWidth: 1))
            }

            Slider(value: $sliderX, in: 0...maxOffset)

            if let feedback {
                Text(feedback)
                    .font(.caption)
                    .foregroundStyle(MacTheme.danger)
            }

            HStack {
                Text("位置 \(Int(round(sliderX))) px")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(MacTheme.inkSecondary)
                Spacer()
                Button {
                    verify()
                } label: {
                    if isVerifying {
                        ProgressView().controlSize(.mini)
                    } else {
                        Text("提交验证")
                    }
                }
                .buttonStyle(MacPrimaryButtonStyle(compact: true))
                .disabled(isVerifying)
            }
        }
        .padding(20)
        .frame(width: max(380, bgWidth + 40))
        .background(MacTheme.canvas)
        .onDisappear { verificationTask?.cancel() }
    }

    private func verify() {
        isVerifying = true
        feedback = nil
        let xPos = Int(round(sliderX))
        verificationTask = Task {
            guard appState.activeCaptchaChallenge?.id == challenge.id else { return }
            do {
                if let validate = try await ChaoxingCaptchaService.verifyCaptcha(
                    client: challenge.client,
                    captcha: challenge.captcha,
                    xPosition: xPos,
                    refererURL: challenge.refererURL
                ), !validate.isEmpty {
                    guard !Task.isCancelled else { return }
                    appState.completeCaptchaChallenge(id: challenge.id, validate: validate)
                } else {
                    guard !Task.isCancelled else { return }
                    feedback = "滑块位置未对齐，请微调后再试"
                }
            } catch {
                guard !Task.isCancelled else { return }
                feedback = error.localizedDescription
            }
            isVerifying = false
        }
    }
}
#endif
