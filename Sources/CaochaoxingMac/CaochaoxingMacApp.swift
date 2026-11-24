// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

#if os(macOS)
import SwiftUI
import AppKit
import ChaoxingCore

@main
struct CaochaoxingMacApp: App {
    @State private var appState = MacAppState()

    init() {
        // Ensure foreground window activation even when launched via `swift run`.
        NSApplication.shared.setActivationPolicy(.regular)
        // A standalone executable may be distributed without its optional icon bundle.
        if let resources = Bundle.main.url(forResource: "Caochaoxing_CaochaoxingMac", withExtension: "bundle"),
           let url = Bundle(url: resources)?.url(forResource: "AppIcon", withExtension: "png"),
           let icon = NSImage(contentsOf: url) {
            NSApplication.shared.applicationIconImage = icon
        }
        NSApplication.shared.activate(ignoringOtherApps: true)
    }

    var body: some Scene {
        Window("草超星", id: "main") {
            MacCourseStudyView()
                .environment(appState)
                .frame(minWidth: 1020, minHeight: 660)
                .tint(MacTheme.accent)
                .background(MacTheme.canvas)
                .toolbarBackground(MacTheme.canvas, for: .windowToolbar)
                .background { MacWindowChromeStyler().frame(width: 0, height: 0) }
                .task {
                    await appState.restore()
                }
                .onReceive(NotificationCenter.default.publisher(for: NSApplication.willTerminateNotification)) { _ in
                    appState.shutdown()
                }
        }
        .windowStyle(.titleBar)
        .commands {
            CommandMenu("账号") {
                ForEach(Array(appState.accounts.enumerated()), id: \.element.id) { index, acc in
                    let isActive = acc.phoneNumber == appState.activeAccount?.phoneNumber
                    let title = (isActive ? "✓ " : "") + "\(appState.accountDisplayTitle(for: acc)) (\(acc.phoneNumber))"
                    if index < 9 {
                        let digit = Character("\(index + 1)")
                        Button(title) {
                            Task { await appState.switchAccount(acc) }
                        }
                        .keyboardShortcut(KeyEquivalent(digit), modifiers: [.command, .option])
                        .disabled(isActive || appState.isSwitchingAccount)
                    } else {
                        Button(title) {
                            Task { await appState.switchAccount(acc) }
                        }
                        .disabled(isActive || appState.isSwitchingAccount)
                    }
                }

                if appState.accounts.count > 1 {
                    Divider()
                    Button("切换下一个账号") {
                        Task { await appState.switchToNextAccount() }
                    }
                    .keyboardShortcut("n", modifiers: [.command, .option])
                    .disabled(appState.isSwitchingAccount)
                }

                Divider()

                Button("添加新账号...") {
                    appState.loginErrorMessage = nil
                    appState.showAddAccountSheet = true
                }
                .keyboardShortcut("a", modifiers: [.command, .shift])

                if let active = appState.activeAccount {
                    Button("刷新当前账号会话") {
                        Task { await appState.refreshAccountSession(active) }
                    }
                }
            }
        }
    }
}
#endif
