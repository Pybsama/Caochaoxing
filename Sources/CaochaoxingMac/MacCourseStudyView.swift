// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

#if os(macOS)
import SwiftUI
import AppKit
import AVKit
import ChaoxingCore

private struct MacWindowCloseHandler: NSViewRepresentable {
    let onClose: () -> Void

    final class Observer: NSView {
        var onClose: (() -> Void)?

        override func viewWillMove(toWindow newWindow: NSWindow?) {
            let center = NotificationCenter.default
            if let window {
                center.removeObserver(self, name: NSWindow.willCloseNotification, object: window)
            }
            if let newWindow {
                center.addObserver(
                    self,
                    selector: #selector(windowClosing),
                    name: NSWindow.willCloseNotification,
                    object: newWindow
                )
            }
            super.viewWillMove(toWindow: newWindow)
        }

        @objc private func windowClosing(_ notification: Notification) { onClose?() }
    }

    func makeNSView(context: Context) -> Observer { Observer() }
    func updateNSView(_ view: Observer, context: Context) { view.onClose = onClose }
}

struct MacCourseStudyView: View {
    @Environment(MacAppState.self) private var appState
    @State private var searchText = ""
    @State private var scrubSeconds: Double?
    @State private var showLogDrawer = false
    @State private var showStudyOptions = false
    @State private var showAccountSwitcher = false
    @State private var activeWebTarget: MacInAppWebTarget?

    private var filteredCourses: [ChaoxingCourse] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = q.isEmpty ? appState.courses : appState.courses.filter {
            $0.courseName.localizedCaseInsensitiveContains(q) ||
            $0.teacherName.localizedCaseInsensitiveContains(q)
        }
        return base.sorted { lhs, rhs in
            let l = appState.starredCourseIds.contains(lhs.courseId)
            let r = appState.starredCourseIds.contains(rhs.courseId)
            if l != r { return l }
            return false
        }
    }

    private var displayedChapters: [ChaoxingChapterNode] {
        if appState.onlyPendingChapters {
            return appState.chapters.filter { ($0.pendingJobCount ?? 1) > 0 }
        }
        return appState.chapters
    }

    private func shortTabTitle(_ tab: MacWorkspaceTab) -> String {
        switch tab {
        case .study: return "看课"
        case .sign: return "签到"
        case .groups: return "群聊"
        case .settings: return "设置"
        }
    }

    var body: some View {
        Group {
            if appState.activeAccount == nil {
                MacLoginScreen()
            } else {
                mainWorkspace
            }
        }
        .background(MacTheme.canvas)
        .sheet(isPresented: Bindable(appState).showAddAccountSheet) {
            MacLoginScreen(isSheet: true)
                .frame(width: 420, height: 420)
        }
        .sheet(item: $activeWebTarget) { target in
            MacInAppWebSheet(target: target)
                .environment(appState)
        }
        .alert("账号变更未能保存", isPresented: Binding(
            get: { appState.accountChangeError != nil },
            set: { if !$0 { appState.accountChangeError = nil } }
        )) {
            Button("好") { appState.accountChangeError = nil }
        } message: {
            Text(appState.accountChangeError ?? "")
        }
        .background { MacWindowCloseHandler { appState.cancelSignBatch() }.frame(width: 0, height: 0) }
        .onDisappear { appState.cancelSignBatch() }
    }

    // MARK: - Top Navigation & Workspace Shell

    private var mainWorkspace: some View {
        VStack(spacing: 0) {
            topNavigationBar
            MacDivider()

            Group {
                switch appState.selectedTab {
                case .study:
                    studyWorkspace
                case .sign:
                    MacSignWorkspaceView()
                case .groups:
                    MacGroupSignWorkspaceView()
                case .settings:
                    MacSettingsWorkspaceView()
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(MacTheme.canvas)
        }
        .background(MacTheme.canvas)
    }

    private var topNavigationBar: some View {
        HStack(spacing: 12) {
            // Flat warm pill tab switcher (zero macOS gray segmented chrome)
            HStack(spacing: 2) {
                ForEach(MacWorkspaceTab.allCases) { tab in
                    let isSelected = appState.selectedTab == tab
                    Button {
                        appState.selectedTab = tab
                    } label: {
                        Text(shortTabTitle(tab))
                            .font(.subheadline.weight(isSelected ? .semibold : .regular))
                            .foregroundStyle(isSelected ? MacTheme.ink : MacTheme.inkSecondary)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 5)
                            .background(
                                RoundedRectangle(cornerRadius: 6, style: .continuous)
                                    .fill(isSelected ? MacTheme.surface : Color.clear)
                            )
                            .overlay(
                                RoundedRectangle(cornerRadius: 6, style: .continuous)
                                    .stroke(isSelected ? MacTheme.border : Color.clear, lineWidth: 1)
                            )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(3)
            .background(MacTheme.sidebar, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .stroke(MacTheme.border, lineWidth: 1)
            )

            Spacer()

            if appState.isBatchWatching && appState.selectedTab != .study {
                Button {
                    appState.selectedTab = .study
                } label: {
                    HStack(spacing: 6) {
                        Circle()
                            .fill(MacTheme.sage)
                            .frame(width: 6, height: 6)
                        Text("看课中 · \(appState.batchActiveWorkers.count) 路")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(MacTheme.sage)
                    }
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4.5)
                    .background(MacTheme.sage.opacity(0.11), in: Capsule())
                }
                .buttonStyle(.plain)
            }

            if let active = appState.activeAccount {
                globalAccountSwitcherMenu(active: active)
            }

            Button {
                Task { await appState.loadCourses(forceRefresh: true) }
            } label: {
                if appState.isLoadingCourses {
                    ProgressView().controlSize(.mini).frame(width: 14, height: 14)
                } else {
                    Image(systemName: "arrow.clockwise")
                        .font(.subheadline)
                }
            }
            .buttonStyle(MacSecondaryButtonStyle(compact: true))
            .disabled(appState.isLoadingCourses)
            .help("刷新课程列表")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 9)
        .background(MacTheme.canvas)
    }

    private func globalAccountSwitcherMenu(active: ChaoxingAccountSession) -> some View {
        Button {
            showAccountSwitcher.toggle()
        } label: {
            HStack(spacing: 6) {
                if appState.isSwitchingAccount {
                    ProgressView().controlSize(.mini)
                } else {
                    Circle()
                        .fill(MacTheme.accent.opacity(0.13))
                        .frame(width: 20, height: 20)
                        .overlay(
                            Text(String(appState.accountDisplayTitle(for: active).prefix(1)))
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(MacTheme.accent)
                        )
                }
                Text(appState.accountDisplayTitle(for: active))
                    .font(.caption.weight(.medium))
                    .foregroundStyle(MacTheme.ink)
                    .lineLimit(1)
                if appState.accounts.count > 1 {
                    Text("\(appState.accounts.count)")
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .foregroundStyle(MacTheme.accent)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 1)
                        .background(MacTheme.accent.opacity(0.11), in: Capsule())
                }
                Image(systemName: "chevron.down")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(MacTheme.inkTertiary)
            }
            .padding(.horizontal, 9)
            .padding(.vertical, 5)
            .background(MacTheme.surface, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .stroke(MacTheme.border, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .popover(isPresented: $showAccountSwitcher, arrowEdge: .bottom) {
            MacAccountSwitcherPopover(isPresented: $showAccountSwitcher)
                .environment(appState)
        }
        .help("切换或管理账号")
    }

    // MARK: - Study Workspace (Clean 2-Pane + Collapsible Log)

    private var studyWorkspace: some View {
        HStack(spacing: 0) {
            courseSidebarColumn
            MacDivider(vertical: true)

            VStack(spacing: 0) {
                studyMainStage
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                MacDivider()
                collapsibleLogBar
            }
            .background(MacTheme.canvas)
        }
        .background(MacTheme.canvas)
    }

    private var courseSidebarColumn: some View {
        VStack(spacing: 0) {
            VStack(spacing: 8) {
                MacSearchField(placeholder: "搜索课程或教师", text: $searchText)
            }
            .padding(10)

            MacDivider()

            if appState.courses.isEmpty && appState.isLoadingCourses {
                VStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("加载课程中...")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkSecondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if filteredCourses.isEmpty {
                VStack(spacing: 6) {
                    Text(searchText.isEmpty ? "暂无课程" : "无匹配课程")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkTertiary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(filteredCourses) { course in
                            let isSelected = appState.selectedStudyCourse?.id == course.id
                            Button {
                                appState.selectStudyCourse(course)
                            } label: {
                                HStack(alignment: .top, spacing: 8) {
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
                                    Spacer(minLength: 4)
                                    if appState.starredCourseIds.contains(course.courseId) {
                                        Image(systemName: "star.fill")
                                            .font(.system(size: 10))
                                            .foregroundStyle(MacTheme.amber)
                                            .padding(.top, 3)
                                    }
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
        .frame(width: 228)
        .background(MacTheme.sidebar)
    }

    @ViewBuilder
    private var studyMainStage: some View {
        let isBatchMode = !appState.batchActiveWorkers.isEmpty || appState.isBatchWatching
        if let course = appState.selectedStudyCourse {
            VStack(spacing: 0) {
                courseActionHeader(for: course)
                MacDivider()

                if isBatchMode {
                    batchWatchingStage
                } else {
                    browseAndSinglePlayerStage(for: course)
                }
            }
        } else if isBatchMode {
            VStack(spacing: 0) {
                if let fallback = appState.courses.first {
                    courseActionHeader(for: fallback)
                    MacDivider()
                }
                batchWatchingStage
            }
        } else {
            MacEmptyState(
                icon: "book.closed",
                title: "选择一门课程",
                subtitle: "在左侧点击任意课程查看章节目录，或直接开启多路同步看课"
            )
        }
    }

    /// Single unified action strip per course — eliminates 3 redundant "同时看" buttons.
    private func courseActionHeader(for course: ChaoxingCourse) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(
                    appState.isBatchWatching && !appState.batchScopeLabel.isEmpty
                        ? appState.batchScopeLabel
                        : course.courseName
                )
                .font(.headline)
                .foregroundStyle(MacTheme.ink)
                .lineLimit(1)

                if !course.teacherName.isEmpty && !appState.isBatchWatching {
                    Text(course.teacherName)
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkSecondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: 12)

            // Concurrency selector
            Menu {
                ForEach([1, 2, 3, 5, 8, 10], id: \.self) { count in
                    let label = count == 5 ? "5 路并行（推荐）" : "\(count) 路并行"
                    Button {
                        appState.maxConcurrentWatches = count
                    } label: {
                        if appState.maxConcurrentWatches == count {
                            Label(label, systemImage: "checkmark")
                        } else {
                            Text(label)
                        }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    Text("\(appState.maxConcurrentWatches) 路")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(MacTheme.ink)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(MacTheme.inkTertiary)
                }
                .padding(.horizontal, 9)
                .padding(.vertical, 5.5)
                .background(MacTheme.surface, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .stroke(MacTheme.border, lineWidth: 1)
                )
            }
            .menuStyle(.borderlessButton)
            .fixedSize()
            .disabled(appState.isBatchWatching)
            .help("设置同时播放路数")

            // Secondary options tucked neatly in a popover
            Button {
                showStudyOptions.toggle()
            } label: {
                Label("选项", systemImage: "slider.horizontal.3")
            }
            .buttonStyle(MacSecondaryButtonStyle(compact: true))
            .popover(isPresented: $showStudyOptions, arrowEdge: .bottom) {
                VStack(alignment: .leading, spacing: 10) {
                    Text("看课选项")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(MacTheme.inkSecondary)
                    Toggle("看完本课后自动续看其他课程", isOn: Bindable(appState).watchAllCoursesAutomatically)
                        .toggleStyle(.checkbox)
                        .font(.subheadline)
                        .disabled(appState.isBatchWatching)
                    Toggle("顺便自动完成文档 / PDF 任务点", isOn: Bindable(appState).autoCompleteDocuments)
                        .toggleStyle(.checkbox)
                        .font(.subheadline)
                        .disabled(appState.isBatchWatching)
                }
                .padding(14)
                .frame(width: 260, alignment: .leading)
                .background(MacTheme.canvas)
            }

            if appState.isBatchWatching {
                Button {
                    appState.stopBatchAutoWatch()
                } label: {
                    Label("停止 (\(appState.batchActiveWorkers.count)路)", systemImage: "stop.fill")
                }
                .buttonStyle(MacPrimaryButtonStyle(tint: MacTheme.danger))
            } else {
                Button {
                    if appState.watchAllCoursesAutomatically {
                        Task { await appState.startSimultaneousWatch() }
                    } else {
                        appState.startCourseBatchAutoWatch(for: course)
                    }
                } label: {
                    Label(
                        appState.watchAllCoursesAutomatically ? "跨课同时看" : "开始同时看",
                        systemImage: "play.fill"
                    )
                }
                .buttonStyle(MacPrimaryButtonStyle())
                .disabled(appState.courses.isEmpty && appState.isLoadingCourses)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(MacTheme.canvas)
    }

    // MARK: - Mode A: Parallel Batch Watching Stage

    private var batchWatchingStage: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // Clean compact status bar
                HStack(spacing: 10) {
                    MacTag(
                        text: "已完成 \(appState.batchCompletedCount)/\(appState.batchTotalDiscoveredCount)",
                        color: appState.batchCompletedCount == appState.batchTotalDiscoveredCount && appState.batchTotalDiscoveredCount > 0 ? MacTheme.sage : MacTheme.accent,
                        monospaced: true
                    )
                    Text("播放中 \(appState.batchActiveWorkers.count) 路 · 队列 \(appState.batchQueueCount)")
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(MacTheme.inkSecondary)

                    if appState.batchCompletedDocsCount > 0 {
                        Text("· 文档 \(appState.batchCompletedDocsCount)")
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(MacTheme.sage)
                    }
                    if appState.batchSkippedCount > 0 {
                        Text("· 跳过 \(appState.batchSkippedCount)")
                            .font(.system(size: 12, design: .monospaced))
                            .foregroundStyle(MacTheme.amber)
                    }
                    Spacer()

                    if let scanMsg = appState.batchScanProgress {
                        HStack(spacing: 6) {
                            ProgressView().controlSize(.mini)
                            Text(scanMsg)
                                .font(.caption)
                                .foregroundStyle(MacTheme.accent)
                                .lineLimit(1)
                        }
                    }
                }
                .macPanel(padding: 11)

                if appState.batchCooldownRemainingSec > 0 {
                    HStack(spacing: 8) {
                        Image(systemName: "clock.badge.exclamationmark")
                            .foregroundStyle(MacTheme.amber)
                        Text("触发限流冷却，\(appState.batchCooldownRemainingSec) 秒后自动继续队列")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(MacTheme.amber)
                        Spacer()
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(MacTheme.amber.opacity(0.10), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }

                if appState.batchActiveWorkers.isEmpty {
                    MacEmptyState(
                        icon: "waveform",
                        title: appState.batchScanProgress ?? "正在准备并发任务...",
                        subtitle: "扫描到未完成视频或直播回放后将自动开播"
                    )
                    .frame(minHeight: 260)
                } else {
                    let columns = [GridItem(.adaptive(minimum: 290), spacing: 14)]
                    LazyVGrid(columns: columns, spacing: 14) {
                        ForEach(appState.batchActiveWorkers) { worker in
                            MacBatchWorkerTile(
                                worker: worker,
                                onToggleMute: { appState.toggleBatchPlayerMute(taskId: worker.id) },
                                onSkip: { appState.skipBatchWorker(taskId: worker.id) }
                            )
                        }
                    }
                }
            }
            .padding(16)
        }
        .macCanvas()
    }

    // MARK: - Mode B: Accordion Chapters + Focused Player Stage

    private func browseAndSinglePlayerStage(for course: ChaoxingCourse) -> some View {
        HStack(spacing: 0) {
            chapterAccordionColumn(for: course)
            MacDivider(vertical: true)
            singleTaskWorkbench
        }
    }

    private func chapterAccordionColumn(for course: ChaoxingCourse) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Text("章节")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(MacTheme.inkSecondary)
                if !appState.chapters.isEmpty {
                    Text("\(displayedChapters.count)/\(appState.chapters.count)")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(MacTheme.inkTertiary)
                }
                Spacer()
                Toggle("仅未完成", isOn: Bindable(appState).onlyPendingChapters)
                    .toggleStyle(.checkbox)
                    .font(.caption)
                    .foregroundStyle(MacTheme.inkSecondary)
                if let chapter = appState.selectedChapter, let chapterURL = appState.chapterStudyURL(for: chapter) {
                    Button {
                        activeWebTarget = MacInAppWebTarget(
                            title: chapter.title,
                            primaryURL: chapterURL,
                            fallbackChapterURL: nil,
                            refreshChapterOnClose: true
                        )
                    } label: {
                        Image(systemName: "globe")
                            .font(.caption)
                            .foregroundStyle(MacTheme.inkSecondary)
                    }
                    .buttonStyle(.plain)
                    .help("在应用内打开当前章节网页")
                }
                Button {
                    appState.selectStudyCourse(course, forceRefresh: true)
                } label: {
                    Image(systemName: "arrow.clockwise")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkSecondary)
                }
                .buttonStyle(.plain)
                .help("刷新章节")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(MacTheme.subColumn)

            MacDivider()

            if appState.isLoadingChapters {
                VStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("正在读取章节...")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkSecondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(MacTheme.subColumn)
            } else if appState.chapters.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: appState.studyErrorMessage != nil ? "exclamationmark.triangle" : "doc.text")
                        .font(.title3)
                        .foregroundStyle(MacTheme.inkTertiary)
                    Text(appState.studyErrorMessage ?? "暂无在线章节")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkSecondary)
                        .multilineTextAlignment(.center)
                    Button("重新加载") {
                        appState.selectStudyCourse(course, forceRefresh: true)
                    }
                    .buttonStyle(MacSecondaryButtonStyle(compact: true))
                }
                .padding(20)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(MacTheme.subColumn)
            } else {
                ScrollView {
                    LazyVStack(spacing: 4) {
                        ForEach(displayedChapters) { chapter in
                            let isExpanded = appState.selectedChapter?.id == chapter.id
                            VStack(alignment: .leading, spacing: 0) {
                                Button {
                                    appState.selectChapter(chapter)
                                } label: {
                                    HStack(alignment: .center, spacing: 8) {
                                        Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                                            .font(.system(size: 10, weight: .semibold))
                                            .foregroundStyle(MacTheme.inkTertiary)
                                            .frame(width: 12)

                                        VStack(alignment: .leading, spacing: 2) {
                                            if !chapter.sectionTitle.isEmpty {
                                                Text(chapter.sectionTitle)
                                                    .font(.caption2)
                                                    .foregroundStyle(MacTheme.inkTertiary)
                                                    .lineLimit(1)
                                            }
                                            Text(chapter.title)
                                                .font(.subheadline.weight(isExpanded ? .semibold : .regular))
                                                .foregroundStyle(isExpanded ? MacTheme.ink : MacTheme.ink.opacity(0.9))
                                                .lineLimit(2)
                                                .multilineTextAlignment(.leading)
                                        }

                                        Spacer(minLength: 4)

                                        if let pending = chapter.pendingJobCount {
                                            if pending == 0 {
                                                Image(systemName: "checkmark.circle.fill")
                                                    .font(.caption)
                                                    .foregroundStyle(MacTheme.sage)
                                            } else {
                                                MacTag(text: "\(pending)", color: MacTheme.amber, monospaced: true)
                                            }
                                        }
                                    }
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 8)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .background(
                                        RoundedRectangle(cornerRadius: 7, style: .continuous)
                                            .fill(isExpanded ? MacTheme.selection : Color.clear)
                                    )
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)

                                // Inline expanded tasks right below the selected chapter
                                if isExpanded {
                                    inlineChapterTasksList
                                        .padding(.top, 4)
                                        .padding(.leading, 18)
                                        .padding(.bottom, 6)
                                }
                            }
                        }
                    }
                    .padding(8)
                }
                .scrollContentBackground(.hidden)
                .background(MacTheme.subColumn)
            }
        }
        .frame(width: 300)
        .background(MacTheme.subColumn)
    }

    @ViewBuilder
    private var inlineChapterTasksList: some View {
        VStack(alignment: .leading, spacing: 4) {
            if appState.isLoadingVideos {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.mini)
                    Text("加载任务点...")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkSecondary)
                }
                .padding(.vertical, 6)
            } else if let err = appState.studyErrorMessage,
                      appState.videos.isEmpty && appState.liveTasks.isEmpty && appState.chapterAttachments.isEmpty {
                Text("加载失败: \(err)")
                    .font(.caption)
                    .foregroundStyle(MacTheme.danger)
                    .padding(.vertical, 4)
            } else if appState.videos.isEmpty && appState.liveTasks.isEmpty && appState.chapterAttachments.isEmpty {
                Text("本节无任务点")
                    .font(.caption)
                    .foregroundStyle(MacTheme.inkTertiary)
                    .padding(.vertical, 4)
            } else {
                ForEach(appState.videos) { video in
                    let isSelected = appState.selectedVideo?.id == video.id
                    Button {
                        appState.selectVideo(video)
                    } label: {
                        HStack(spacing: 7) {
                            Image(systemName: video.isPassed ? "checkmark.circle.fill" : (video.dtype == "Audio" ? "waveform" : "play.circle"))
                                .font(.caption)
                                .foregroundStyle(video.isPassed ? MacTheme.sage : (video.isJob ? MacTheme.amber : MacTheme.inkTertiary))
                            VStack(alignment: .leading, spacing: 1) {
                                Text(video.name)
                                    .font(.caption.weight(isSelected ? .semibold : .regular))
                                    .foregroundStyle(isSelected ? MacTheme.accent : MacTheme.ink)
                                    .lineLimit(2)
                                    .multilineTextAlignment(.leading)
                                Text(formatDuration(video.totalDuration) + (video.requiresFaceCapture ? " · 人脸抓拍" : ""))
                                    .font(.caption2)
                                    .foregroundStyle(video.requiresFaceCapture ? MacTheme.danger : MacTheme.inkTertiary)
                            }
                            Spacer(minLength: 4)
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 6)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .fill(isSelected ? MacTheme.surface : Color.clear)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 6, style: .continuous)
                                .stroke(isSelected ? MacTheme.border : Color.clear, lineWidth: 1)
                        )
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }

                ForEach(appState.liveTasks) { live in
                    HStack(spacing: 7) {
                        Image(systemName: live.isPassed ? "checkmark.circle.fill" : "dot.radiowaves.left.and.right")
                            .font(.caption)
                            .foregroundStyle(live.isPassed ? MacTheme.sage : (live.isJob ? MacTheme.amber : MacTheme.inkTertiary))
                        VStack(alignment: .leading, spacing: 1) {
                            Text(live.title)
                                .font(.caption.weight(.medium))
                                .foregroundStyle(MacTheme.ink)
                                .lineLimit(2)
                            Text(live.isPassed ? "已通过" : "\(live.statusDescription) · \(String(format: "%.0f%%", live.percentValue))")
                                .font(.caption2)
                                .foregroundStyle(MacTheme.inkTertiary)
                        }
                        Spacer(minLength: 4)
                        if live.isReportableTask && !live.isPassed {
                            Button("挂机") {
                                appState.startSingleLiveWatch(live)
                            }
                            .buttonStyle(MacSecondaryButtonStyle(compact: true))
                        }
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                }

                ForEach(appState.chapterAttachments) { att in
                    HStack(spacing: 7) {
                        Button {
                            if att.kind == .quiz || !att.canCompleteAsDocument {
                                openAttachmentInApp(att)
                            }
                        } label: {
                            HStack(spacing: 7) {
                                Image(systemName: att.isPassed ? "checkmark.circle.fill" : att.kind.systemImage)
                                    .font(.caption)
                                    .foregroundStyle(att.isPassed ? MacTheme.sage : (att.kind == .quiz ? MacTheme.amber : MacTheme.accent))
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(att.name)
                                        .font(.caption.weight(att.kind == .quiz ? .medium : .regular))
                                        .foregroundStyle(MacTheme.ink)
                                        .lineLimit(2)
                                        .multilineTextAlignment(.leading)
                                    Text(att.kind.displayName + (att.isPassed ? " · 已完成" : ""))
                                        .font(.caption2)
                                        .foregroundStyle(MacTheme.inkTertiary)
                                }
                                Spacer(minLength: 4)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)

                        if att.kind == .quiz {
                            Button(att.isPassed ? "查看" : "做测验") {
                                openAttachmentInApp(att)
                            }
                            .buttonStyle(MacSecondaryButtonStyle(compact: true))
                        } else if att.canCompleteAsDocument {
                            Button {
                                Task { await appState.completeDocument(att) }
                            } label: {
                                if appState.completingDocumentIds.contains(att.id) {
                                    ProgressView().controlSize(.mini)
                                } else {
                                    Text("完成")
                                }
                            }
                            .buttonStyle(MacSecondaryButtonStyle(compact: true))
                            .disabled(appState.completingDocumentIds.contains(att.id))
                        } else if att.preferredInAppURL != nil {
                            Button("打开") {
                                openAttachmentInApp(att)
                            }
                            .buttonStyle(MacSecondaryButtonStyle(compact: true))
                        }
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                }
            }
        }
    }

    @ViewBuilder
    private var singleTaskWorkbench: some View {
        if let video = appState.selectedVideo {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    // Video Player
                    ZStack {
                        MacAVPlayerView(player: appState.playerController.player)
                        if appState.playerController.isBuffering && !appState.playerController.isReady {
                            playerPosterOverlay(
                                screenshotURL: video.screenshotURL,
                                message: "正在缓冲..."
                            )
                        } else if let err = appState.playerController.playerErrorMessage {
                            VStack(spacing: 8) {
                                Image(systemName: "exclamationmark.triangle.fill")
                                    .font(.title2)
                                    .foregroundStyle(MacTheme.amber)
                                Text(err)
                                    .font(.caption)
                                    .foregroundStyle(.white)
                                Button("重新加载") {
                                    appState.selectVideo(video, forceReload: true)
                                }
                                .buttonStyle(MacPrimaryButtonStyle(compact: true))
                            }
                            .padding(16)
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                            .background(.black.opacity(0.72))
                        }
                    }
                    .frame(height: 350)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .stroke(MacTheme.border, lineWidth: 1)
                    )

                    // Single unified transport + auto-watch card
                    unifiedVideoControlCard(for: video)

                    // Surface chapter quizzes right below the player so they can be opened in 1 click
                    let chapterQuizzes = appState.chapterAttachments.filter { $0.kind == .quiz }
                    if !chapterQuizzes.isEmpty {
                        MacSectionBox("本节测验", subtitle: "\(chapterQuizzes.count) 项 · 应用内直接作答") {
                            VStack(alignment: .leading, spacing: 10) {
                                ForEach(Array(chapterQuizzes.enumerated()), id: \.element.id) { idx, quiz in
                                    if idx > 0 { MacDivider() }
                                    HStack(spacing: 10) {
                                        Image(systemName: quiz.isPassed ? "checkmark.circle.fill" : "checklist")
                                            .foregroundStyle(quiz.isPassed ? MacTheme.sage : MacTheme.amber)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(quiz.name)
                                                .font(.subheadline.weight(.medium))
                                                .foregroundStyle(MacTheme.ink)
                                            Text(quiz.isPassed ? "已完成" : "待完成 · 点击在应用内打开")
                                                .font(.caption)
                                                .foregroundStyle(MacTheme.inkSecondary)
                                        }
                                        Spacer()
                                        if quiz.isPassed {
                                            Button("查看测验") {
                                                openAttachmentInApp(quiz)
                                            }
                                            .buttonStyle(MacSecondaryButtonStyle(compact: true))
                                        } else {
                                            Button("打开测验") {
                                                openAttachmentInApp(quiz)
                                            }
                                            .buttonStyle(MacPrimaryButtonStyle(compact: true))
                                        }
                                    }
                                    .padding(.vertical, 2)
                                }
                            }
                        }
                    }
                }
                .padding(16)
            }
            .macCanvas()
        } else if !appState.liveTasks.isEmpty || !appState.chapterAttachments.isEmpty {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if !appState.liveTasks.isEmpty {
                        liveReplayTasksCard
                    }
                    if !appState.chapterAttachments.isEmpty {
                        nonVideoAttachmentsCard
                    }
                }
                .padding(16)
            }
            .macCanvas()
        } else {
            MacEmptyState(
                icon: "play.rectangle",
                title: "选择章节里的视频开始观看",
                subtitle: "也可以直接点击右上角「开始同时看」，自动扫描并并行播放本课未完成任务点"
            )
        }
    }

    private func unifiedVideoControlCard(for video: ChaoxingVideoTask) -> some View {
        let isThisWatching = appState.isAutoWatching && appState.autoWatchTaskId == video.id
        let duration = max(1, video.effectiveClipEnd)
        let displayed = isThisWatching ? appState.autoWatchDisplayedSec : (video.isPassed ? duration : video.headOffsetMs / 1000)

        return VStack(alignment: .leading, spacing: 12) {
            // Title + status tag
            HStack(spacing: 8) {
                Text(video.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(MacTheme.ink)
                    .lineLimit(1)
                Spacer()
                MacTag(
                    text: video.isPassed ? "已通过" : (!video.isJob ? "非任务点" : (isThisWatching ? "挂机中" : "未完成")),
                    color: video.isPassed ? MacTheme.sage : (!video.isJob ? MacTheme.inkSecondary : (isThisWatching ? MacTheme.accent : MacTheme.amber))
                )
            }

            // Playback controls & speed
            HStack(spacing: 10) {
                Button {
                    appState.playerController.togglePlayPause()
                } label: {
                    Label(
                        appState.playerController.isPlaying ? "暂停" : "播放",
                        systemImage: appState.playerController.isPlaying ? "pause.fill" : "play.fill"
                    )
                }
                .buttonStyle(MacPrimaryButtonStyle(compact: true))

                let shownCur = scrubSeconds ?? appState.playerController.currentSeconds
                let shownDur = max(appState.playerController.durationSeconds, Double(video.totalDuration))
                Text("\(formatDuration(Int(shownCur))) / \(formatDuration(Int(shownDur)))")
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundStyle(MacTheme.inkSecondary)

                if appState.playerController.isBuffering {
                    ProgressView().controlSize(.mini)
                }

                Spacer()

                HStack(spacing: 4) {
                    ForEach(ChaoxingPlaybackSpeed.allCases) { speed in
                        let selected = appState.playerController.currentSpeed == speed
                        Button(speed.label) {
                            appState.playerController.setSpeed(speed)
                        }
                        .font(.caption.weight(selected ? .semibold : .regular))
                        .foregroundStyle(selected ? MacTheme.accent : MacTheme.inkSecondary)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3.5)
                        .background(
                            RoundedRectangle(cornerRadius: 5, style: .continuous)
                                .fill(selected ? MacTheme.accent.opacity(0.12) : Color.clear)
                        )
                        .buttonStyle(.plain)
                    }
                }
            }

            if video.totalDuration > 0 {
                let maxSlider = Double(max(1, Int(max(appState.playerController.durationSeconds, Double(video.totalDuration)))))
                Slider(
                    value: Binding(
                        get: { min(maxSlider, max(0, scrubSeconds ?? appState.playerController.currentSeconds)) },
                        set: { newVal in
                            scrubSeconds = newVal
                            appState.playerController.seek(to: newVal, emitLog: false)
                        }
                    ),
                    in: 0...maxSlider,
                    onEditingChanged: { editing in
                        if !editing, let finalVal = scrubSeconds {
                            scrubSeconds = nil
                            appState.playerController.seek(to: finalVal, emitLog: true)
                        }
                    }
                )
            }

            MacDivider()

            // Auto-watch progress + single action row
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 8) {
                        Text("有效进度 \(formatDuration(displayed)) / \(formatDuration(duration))")
                            .font(.system(size: 11, design: .monospaced))
                            .foregroundStyle(MacTheme.ink)
                        if isThisWatching && appState.autoWatchNextTickInSec > 0 {
                            Text("· 心跳 \(appState.autoWatchNextTickInSec)s")
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(MacTheme.accent)
                        } else if appState.playerController.isPlaying && appState.playerHeartbeatCountdownSec > 0 {
                            Text("· 心跳 \(String(format: "%.0f", appState.playerHeartbeatCountdownSec))s")
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(MacTheme.accent)
                        }
                    }
                    Text("达标 \(Int(round(video.rt * 100)))% · 服务端上限 \(Int(video.allowedMaxRate))x" + (appState.lastHeartbeatSummary.map { " · \($0)" } ?? ""))
                        .font(.caption2)
                        .foregroundStyle(MacTheme.inkTertiary)
                        .lineLimit(1)
                }

                Spacer()

                Toggle("自动下一节", isOn: Bindable(appState).autoAdvanceNextChapter)
                    .toggleStyle(.checkbox)
                    .font(.caption)
                    .foregroundStyle(MacTheme.inkSecondary)

                if isThisWatching {
                    Button("停止挂机") {
                        appState.stopAutoWatch()
                    }
                    .buttonStyle(MacPrimaryButtonStyle(tint: MacTheme.danger, compact: true))
                } else {
                    Button(video.isPassed ? "重新挂机" : "单视频挂机") {
                        appState.startAutoWatch(for: video)
                    }
                    .buttonStyle(MacSecondaryButtonStyle(compact: true))
                    .disabled(!video.isReportableTask)
                }
            }
        }
        .macPanel(padding: 14)
    }

    private var liveReplayTasksCard: some View {
        MacSectionBox("直播回放", subtitle: "\(appState.liveTasks.count) 项") {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(appState.liveTasks.enumerated()), id: \.element.id) { idx, live in
                    if idx > 0 { MacDivider() }
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 10) {
                            Image(systemName: live.isPassed ? "checkmark.circle.fill" : "dot.radiowaves.left.and.right")
                                .foregroundStyle(live.isPassed ? MacTheme.sage : (live.isJob ? MacTheme.amber : MacTheme.inkTertiary))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(live.title)
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(MacTheme.ink)
                                Text("\(live.statusDescription) · \(formatDuration(live.duration)) · 已计 \(String(format: "%.1f%%", live.percentValue))")
                                    .font(.caption)
                                    .foregroundStyle(MacTheme.inkSecondary)
                            }
                            Spacer()
                            if live.isPassed {
                                MacTag(text: "已通过", color: MacTheme.sage)
                            } else if !live.isJob {
                                MacTag(text: "非任务点")
                            } else if live.isReportableTask {
                                Button("开始挂机") {
                                    appState.startSingleLiveWatch(live)
                                }
                                .buttonStyle(MacPrimaryButtonStyle(compact: true))
                            }
                            if let webStr = live.webPageURL, let webURL = URL(string: webStr) {
                                Button("网页") {
                                    NSWorkspace.shared.open(webURL)
                                }
                                .buttonStyle(MacSecondaryButtonStyle(compact: true))
                            }
                        }
                        if live.duration > 0 {
                            ProgressView(value: min(100.0, max(0.0, live.percentValue)), total: 100.0)
                                .tint(live.isPassed ? MacTheme.sage : MacTheme.accent)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }

    private var nonVideoAttachmentsCard: some View {
        MacSectionBox("测验、文档与附件", subtitle: "\(appState.chapterAttachments.count) 项") {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(appState.chapterAttachments.enumerated()), id: \.element.id) { idx, att in
                    if idx > 0 { MacDivider() }
                    HStack(spacing: 10) {
                        Image(systemName: att.isPassed ? "checkmark.circle.fill" : att.kind.systemImage)
                            .foregroundStyle(att.isPassed ? MacTheme.sage : (att.kind == .quiz ? MacTheme.amber : MacTheme.accent))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(att.name)
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(MacTheme.ink)
                            Text(att.kind.displayName + (att.isPassed ? " · 已完成" : (att.kind == .quiz ? " · 可在应用内直接作答" : "")))
                                .font(.caption)
                                .foregroundStyle(MacTheme.inkSecondary)
                        }
                        Spacer()
                        if att.kind == .quiz {
                            if att.isPassed {
                                Button("查看测验") {
                                    openAttachmentInApp(att)
                                }
                                .buttonStyle(MacSecondaryButtonStyle(compact: true))
                            } else {
                                Button("打开测验") {
                                    openAttachmentInApp(att)
                                }
                                .buttonStyle(MacPrimaryButtonStyle(compact: true))
                            }
                        } else if att.canCompleteAsDocument {
                            Button {
                                Task { await appState.completeDocument(att) }
                            } label: {
                                if appState.completingDocumentIds.contains(att.id) {
                                    ProgressView().controlSize(.mini)
                                } else {
                                    Text("标记完成")
                                }
                            }
                            .buttonStyle(MacPrimaryButtonStyle(compact: true))
                            .disabled(appState.completingDocumentIds.contains(att.id))
                        } else if att.preferredInAppURL != nil {
                            Button("应用内打开") {
                                openAttachmentInApp(att)
                            }
                            .buttonStyle(MacSecondaryButtonStyle(compact: true))
                        } else {
                            MacTag(text: att.isPassed ? "已通过" : att.kind.displayName, color: att.isPassed ? MacTheme.sage : MacTheme.inkSecondary)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
    }

    private func openAttachmentInApp(_ att: ChaoxingChapterAttachment) {
        guard let url = att.preferredInAppURL else { return }
        activeWebTarget = MacInAppWebTarget(
            title: att.name,
            primaryURL: url,
            fallbackChapterURL: att.chapterStudyURL,
            refreshChapterOnClose: true
        )
    }

    // MARK: - Collapsible Bottom Log Bar

    private var collapsibleLogBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button {
                    withAnimation(.easeInOut(duration: 0.16)) {
                        showLogDrawer.toggle()
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: showLogDrawer ? "chevron.down" : "chevron.up")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(MacTheme.inkTertiary)
                        Text("日志")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(MacTheme.inkSecondary)
                        if let latest = appState.logs.first {
                            Text("[\(latest.time)] \(latest.message)")
                                .font(.system(size: 11, design: .monospaced))
                                .foregroundStyle(latest.isError ? MacTheme.danger : MacTheme.inkTertiary)
                                .lineLimit(1)
                        } else {
                            Text("暂无运行记录")
                                .font(.caption)
                                .foregroundStyle(MacTheme.inkTertiary)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                Spacer()

                if showLogDrawer && !appState.logs.isEmpty {
                    Button("清空") {
                        appState.logs.removeAll()
                    }
                    .font(.caption)
                    .foregroundStyle(MacTheme.inkSecondary)
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(MacTheme.sidebar)

            if showLogDrawer {
                MacDivider()
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 4) {
                        if appState.logs.isEmpty {
                            Text("暂无日志")
                                .font(.caption)
                                .foregroundStyle(MacTheme.inkTertiary)
                        } else {
                            ForEach(appState.logs.prefix(40)) { line in
                                HStack(alignment: .top, spacing: 8) {
                                    Text(line.time)
                                        .font(.system(size: 11, design: .monospaced))
                                        .foregroundStyle(MacTheme.inkTertiary)
                                    Text(line.message)
                                        .font(.system(size: 11, design: .monospaced))
                                        .foregroundStyle(line.isError ? MacTheme.danger : MacTheme.ink)
                                        .textSelection(.enabled)
                                }
                            }
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .scrollContentBackground(.hidden)
                .frame(height: 145)
                .background(MacTheme.surface)
            }
        }
    }

    private func playerPosterOverlay(screenshotURL: String?, message: String) -> some View {
        ZStack {
            if let shot = screenshotURL, let url = URL(string: shot) {
                AsyncImage(url: url) { phase in
                    if case .success(let image) = phase {
                        image
                            .resizable()
                            .scaledToFill()
                    } else {
                        Color.black
                    }
                }
            } else {
                Color.black
            }
            Color.black.opacity(0.45)
            HStack(spacing: 8) {
                ProgressView()
                    .controlSize(.small)
                    .tint(.white)
                Text(message)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(.black.opacity(0.65), in: Capsule())
        }
    }

    private func formatDuration(_ sec: Int) -> String {
        let s = max(0, sec)
        let m = s / 60
        let r = s % 60
        return String(format: "%02d:%02d", m, r)
    }
}

/// Isolated tile view observing its own `@Observable` `MacBatchWatchWorker` so 500ms progress ticks
/// never force sibling tiles or `MacAVPlayerView` wrappers to re-evaluate.
private struct MacBatchWorkerTile: View {
    let worker: MacBatchWatchWorker
    let onToggleMute: () -> Void
    let onSkip: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack {
                MacAVPlayerView(player: worker.player)
                if (worker.isBuffering || !worker.hasPlaybackStarted) && worker.playerError == nil && !worker.isPlayerReady {
                    ZStack {
                        if let shot = worker.screenshotURL, let url = URL(string: shot) {
                            AsyncImage(url: url) { phase in
                                if case .success(let image) = phase {
                                    image.resizable().scaledToFill()
                                } else {
                                    Color.black
                                }
                            }
                        } else {
                            Color.black
                        }
                        Color.black.opacity(0.45)
                        HStack(spacing: 6) {
                            ProgressView().controlSize(.small).tint(.white)
                            Text("加载缓冲中 · 完成后开始刷课")
                                .font(.caption2.weight(.medium))
                                .foregroundStyle(.white)
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(.black.opacity(0.65), in: Capsule())
                    }
                } else if let err = worker.playerError {
                    VStack(spacing: 4) {
                        Image(systemName: "waveform.path")
                            .foregroundStyle(MacTheme.amber)
                        Text(err)
                            .font(.caption2)
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.center)
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.black.opacity(0.75))
                }
            }
            .frame(height: 172)
            .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))

            HStack(spacing: 6) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(worker.courseName) · \(worker.chapterTitle)")
                        .font(.caption2)
                        .foregroundStyle(MacTheme.inkTertiary)
                        .lineLimit(1)
                    HStack(spacing: 5) {
                        if worker.isLiveReplay {
                            MacTag(text: "回放", color: MacTheme.amber)
                        }
                        Text(worker.title)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(MacTheme.ink)
                            .lineLimit(1)
                    }
                }
                Spacer()
                if let webStr = worker.webPageURL, let webURL = URL(string: webStr) {
                    Button {
                        NSWorkspace.shared.open(webURL)
                    } label: {
                        Image(systemName: "safari")
                            .font(.caption)
                            .foregroundStyle(MacTheme.inkSecondary)
                    }
                    .buttonStyle(.plain)
                    .help("在浏览器中打开原直播回放页")
                }
                Button(action: onToggleMute) {
                    Image(systemName: worker.isMuted ? "speaker.slash" : "speaker.wave.2.fill")
                        .font(.caption)
                        .foregroundStyle(worker.isMuted ? MacTheme.inkTertiary : MacTheme.accent)
                }
                .buttonStyle(.plain)
                .help(worker.isMuted ? "取消静音" : "静音")

                Button(action: onSkip) {
                    Image(systemName: "forward.end")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkSecondary)
                }
                .buttonStyle(.plain)
                .help("跳过此视频，立即切下一节")
            }

            ProgressView(
                value: Double(min(worker.displayedSec, max(1, worker.duration))),
                total: Double(max(1, worker.duration))
            )
            .tint(MacTheme.sage)

            HStack {
                Text("\(formatDuration(worker.displayedSec)) / \(formatDuration(worker.duration))")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(MacTheme.ink)
                Text(worker.isLiveReplay ? "入库 \(formatDuration(worker.reportedSec))" : "已确认 \(formatDuration(worker.reportedSec))")
                    .font(.system(size: 10, design: .monospaced))
                    .foregroundStyle(MacTheme.inkTertiary)
                Spacer()
                if !worker.isPlaybackReadyForStudy {
                    Text("等待缓冲...")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(MacTheme.amber)
                } else if worker.isBuffering && worker.playerError == nil {
                    Text("缓冲暂停...")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(MacTheme.amber)
                } else if worker.nextTickInSec > 0 {
                    Text(worker.isLiveReplay ? "心跳 \(worker.nextTickInSec)s" : "\(Int(worker.legalRate))x · \(worker.nextTickInSec)s")
                        .font(.system(size: 11, design: .monospaced))
                        .foregroundStyle(MacTheme.accent)
                }
            }
        }
        .macPanel(padding: 10)
    }

    private func formatDuration(_ sec: Int) -> String {
        let s = max(0, sec)
        let m = s / 60
        let r = s % 60
        return String(format: "%02d:%02d", m, r)
    }
}

/// `AVPlayerView` subclass that prevents macOS trackpad / mouse-wheel scrolling from
/// accidentally scrubbing or fast-forwarding the video timeline, forwarding scroll gestures
/// cleanly to the enclosing `NSScrollView` instead.
private final class ScrollSafeAVPlayerView: AVPlayerView {
    private var scrollMonitor: Any?

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        if window != nil {
            installScrollMonitorIfNeeded()
        } else {
            removeScrollMonitor()
        }
    }

    override func scrollWheel(with event: NSEvent) {
        if let scrollView = findEnclosingScrollView() {
            scrollView.scrollWheel(with: event)
        }
    }

    func installScrollMonitorIfNeeded() {
        guard scrollMonitor == nil else { return }
        scrollMonitor = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            guard let self,
                  let win = self.window,
                  event.window === win,
                  !self.isHiddenOrHasHiddenAncestor,
                  let contentView = win.contentView else {
                return event
            }
            let targetPoint = contentView.superview?.convert(event.locationInWindow, from: nil) ?? event.locationInWindow
            guard let hitView = contentView.hitTest(targetPoint),
                  hitView.isDescendant(of: self) else {
                return event
            }
            if let scrollView = self.findEnclosingScrollView() {
                scrollView.scrollWheel(with: event)
            }
            return nil
        }
    }

    func removeScrollMonitor() {
        if let monitor = scrollMonitor {
            NSEvent.removeMonitor(monitor)
            scrollMonitor = nil
        }
    }

    private func findEnclosingScrollView() -> NSScrollView? {
        if let direct = enclosingScrollView {
            return direct
        }
        var current = superview
        while let view = current {
            if let sv = view as? NSScrollView {
                return sv
            }
            current = view.superview
        }
        return nil
    }
}

private struct MacAVPlayerView: NSViewRepresentable {
    let player: AVPlayer

    func makeNSView(context: Context) -> ScrollSafeAVPlayerView {
        let view = ScrollSafeAVPlayerView()
        view.controlsStyle = .inline
        view.player = player
        return view
    }

    func updateNSView(_ nsView: ScrollSafeAVPlayerView, context: Context) {
        if nsView.player !== player {
            nsView.player = player
        }
    }

    static func dismantleNSView(_ nsView: ScrollSafeAVPlayerView, coordinator: ()) {
        nsView.removeScrollMonitor()
        nsView.player = nil
    }
}

private struct MacAccountSwitcherPopover: View {
    @Environment(MacAppState.self) private var appState
    @Binding var isPresented: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            HStack {
                Text("切换账号")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(MacTheme.ink)
                Text("\(appState.accounts.count)")
                    .font(.system(size: 11, design: .monospaced))
                    .foregroundStyle(MacTheme.inkTertiary)
                Spacer()
                Button {
                    isPresented = false
                    appState.loginErrorMessage = nil
                    appState.showAddAccountSheet = true
                } label: {
                    Label("添加", systemImage: "plus")
                }
                .buttonStyle(MacSecondaryButtonStyle(compact: true))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)

            MacDivider()

            // Account list
            ScrollView {
                LazyVStack(spacing: 4) {
                    ForEach(Array(appState.accounts.enumerated()), id: \.element.id) { index, acc in
                        let isActive = acc.phoneNumber == appState.activeAccount?.phoneNumber
                        let isRefreshing = appState.refreshingAccountPhones.contains(acc.phoneNumber)
                        let remark = appState.accountRemark(for: acc)

                        HStack(spacing: 10) {
                            Button {
                                guard !isActive else { return }
                                isPresented = false
                                Task { await appState.switchAccount(acc) }
                            } label: {
                                HStack(spacing: 10) {
                                    Circle()
                                        .fill(isActive ? MacTheme.sage.opacity(0.14) : MacTheme.surfaceSecondary)
                                        .frame(width: 30, height: 30)
                                        .overlay(
                                            Text(String(appState.accountDisplayTitle(for: acc).prefix(1)))
                                                .font(.caption.weight(.semibold))
                                                .foregroundStyle(isActive ? MacTheme.sage : MacTheme.inkSecondary)
                                        )

                                    VStack(alignment: .leading, spacing: 2) {
                                        HStack(spacing: 5) {
                                            Text(acc.name.isEmpty ? acc.phoneNumber : acc.name)
                                                .font(.subheadline.weight(isActive ? .semibold : .regular))
                                                .foregroundStyle(MacTheme.ink)
                                                .lineLimit(1)
                                            if !remark.isEmpty {
                                                MacTag(text: remark, color: MacTheme.accent)
                                            }
                                        }
                                        HStack(spacing: 4) {
                                            Text(acc.phoneNumber)
                                            if let school = acc.schools.first?.name, !school.isEmpty {
                                                Text("· \(school)")
                                            }
                                        }
                                        .font(.caption2)
                                        .foregroundStyle(MacTheme.inkSecondary)
                                        .lineLimit(1)
                                    }

                                    Spacer(minLength: 4)

                                    if isActive {
                                        Image(systemName: "checkmark")
                                            .font(.caption.weight(.semibold))
                                            .foregroundStyle(MacTheme.sage)
                                    } else if index < 9 {
                                        Text("⌥⌘\(index + 1)")
                                            .font(.system(size: 10, design: .monospaced))
                                            .foregroundStyle(MacTheme.inkTertiary)
                                    }
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(appState.isSwitchingAccount)

                            if isActive {
                                Button {
                                    Task { await appState.refreshAccountSession(acc) }
                                } label: {
                                    if isRefreshing {
                                        ProgressView().controlSize(.mini).frame(width: 14, height: 14)
                                    } else {
                                        Image(systemName: "arrow.clockwise")
                                            .font(.caption)
                                            .foregroundStyle(MacTheme.inkSecondary)
                                    }
                                }
                                .buttonStyle(.plain)
                                .disabled(isRefreshing)
                                .help("刷新当前会话")
                            }
                        }
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 8, style: .continuous)
                                .fill(isActive ? MacTheme.selection : Color.clear)
                        )
                    }
                }
                .padding(8)
            }
            .scrollContentBackground(.hidden)
            .frame(maxHeight: 260)

            MacDivider()

            // Footer actions
            HStack(spacing: 8) {
                Button {
                    isPresented = false
                    appState.selectedTab = .settings
                } label: {
                    Text("管理账号")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkSecondary)
                }
                .buttonStyle(.plain)

                if appState.accounts.count > 1 {
                    Text("·")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkTertiary)
                    Button {
                        isPresented = false
                        Task { await appState.switchToNextAccount() }
                    } label: {
                        Text("切下一个")
                            .font(.caption)
                            .foregroundStyle(MacTheme.inkSecondary)
                    }
                    .buttonStyle(.plain)
                    .disabled(appState.isSwitchingAccount)
                }

                Spacer()

                Button {
                    isPresented = false
                    Task { await appState.logoutCurrent() }
                } label: {
                    Text("退出当前")
                        .font(.caption)
                        .foregroundStyle(MacTheme.danger)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(MacTheme.sidebar)
        }
        .frame(width: 316)
        .background(MacTheme.canvas)
    }
}

struct MacLoginScreen: View {
    @Environment(MacAppState.self) private var appState
    @Environment(\.dismiss) private var dismiss
    var isSheet = false

    @State private var phone = ""
    @State private var password = ""
    @State private var remark = ""
    @State private var switchImmediately = true

    var body: some View {
        VStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(isSheet ? "添加超星账号" : "登录草超星")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(MacTheme.ink)
                    Text("账号凭据仅保存在本机，不会上传第三方服务器")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkSecondary)
                }

                VStack(alignment: .leading, spacing: 12) {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("手机号")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(MacTheme.inkSecondary)
                        TextField("输入学习通绑定手机号", text: $phone)
                            .macInputField()
                    }

                    VStack(alignment: .leading, spacing: 5) {
                        Text("密码")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(MacTheme.inkSecondary)
                        SecureField("输入登录密码", text: $password)
                            .macInputField()
                            .onSubmit { submit() }
                    }

                    VStack(alignment: .leading, spacing: 5) {
                        Text("备注标签（可选）")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(MacTheme.inkSecondary)
                        TextField("例如：本人 / 小号 / 室友", text: $remark)
                            .macInputField()
                            .onSubmit { submit() }
                    }

                    if isSheet && appState.activeAccount != nil {
                        Toggle("登录后立即切换为当前账号", isOn: $switchImmediately)
                            .toggleStyle(.checkbox)
                            .font(.caption)
                            .foregroundStyle(MacTheme.inkSecondary)
                            .padding(.top, 2)
                    }
                }

                if let error = appState.loginErrorMessage, !error.isEmpty {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(MacTheme.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }

                HStack(spacing: 10) {
                    Spacer()
                    if isSheet {
                        Button("取消") { dismiss() }
                            .buttonStyle(MacSecondaryButtonStyle())
                    }
                    Button {
                        submit()
                    } label: {
                        if appState.isLoggingIn {
                            ProgressView().controlSize(.small)
                        } else {
                            Text(isSheet ? (switchImmediately ? "登录并切换" : "添加账号") : "登录")
                                .frame(minWidth: 76)
                        }
                    }
                    .buttonStyle(MacPrimaryButtonStyle())
                    .disabled(appState.isLoggingIn || phone.trimmingCharacters(in: .whitespaces).isEmpty || password.isEmpty)
                }
                .padding(.top, 4)
            }
            .padding(24)
            .frame(maxWidth: 380)
            .background(isSheet ? MacTheme.canvas : MacTheme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(isSheet ? Color.clear : MacTheme.border, lineWidth: 1)
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(MacTheme.canvas)
    }

    private func submit() {
        Task {
            await appState.login(
                phoneNumber: phone,
                password: password,
                remark: remark,
                switchImmediately: isSheet ? switchImmediately : true
            )
            if isSheet && appState.loginErrorMessage == nil {
                dismiss()
            }
        }
    }
}
#endif
