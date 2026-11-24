// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

#if os(macOS)
import SwiftUI
import AppKit
import WebKit
import ChaoxingCore

/// Payload for opening a Chaoxing quiz, assignment, or chapter page inside the app.
struct MacInAppWebTarget: Identifiable {
    let id = UUID()
    let title: String
    let primaryURL: URL
    let fallbackChapterURL: URL?
    let refreshChapterOnClose: Bool

    init(
        title: String,
        primaryURL: URL,
        fallbackChapterURL: URL? = nil,
        refreshChapterOnClose: Bool = true
    ) {
        self.title = title
        self.primaryURL = primaryURL
        self.fallbackChapterURL = fallbackChapterURL
        self.refreshChapterOnClose = refreshChapterOnClose
    }
}

/// Embedded authenticated browser sheet for completing Chaoxing chapter quizzes & tasks directly inside Caochaoxing.
struct MacInAppWebSheet: View {
    @Environment(MacAppState.self) private var appState
    @Environment(\.dismiss) private var dismiss

    let target: MacInAppWebTarget

    @State private var useFullChapterPage = false
    @State private var isLoading = true
    @State private var pageTitle = ""
    @State private var canGoBack = false
    @State private var canGoForward = false
    @State private var estimatedProgress: Double = 0
    @State private var reloadToken = UUID()
    @State private var webCommand: MacWebViewCommand?

    private var activeURL: URL {
        if useFullChapterPage, let chapterURL = target.fallbackChapterURL {
            return chapterURL
        }
        return target.primaryURL
    }

    private var hasDistinctFallback: Bool {
        guard let fallback = target.fallbackChapterURL else { return false }
        return fallback.absoluteString != target.primaryURL.absoluteString
    }

    var body: some View {
        VStack(spacing: 0) {
            // Warm-white top toolbar
            HStack(spacing: 10) {
                HStack(spacing: 4) {
                    Button {
                        webCommand = .goBack
                    } label: {
                        Image(systemName: "chevron.left")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(MacSecondaryButtonStyle(compact: true))
                    .disabled(!canGoBack)

                    Button {
                        webCommand = .goForward
                    } label: {
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(MacSecondaryButtonStyle(compact: true))
                    .disabled(!canGoForward)

                    Button {
                        reloadToken = UUID()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(MacSecondaryButtonStyle(compact: true))
                    .help("重新加载")
                }

                VStack(alignment: .leading, spacing: 1) {
                    Text(target.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(MacTheme.ink)
                        .lineLimit(1)
                    if !pageTitle.isEmpty && pageTitle != target.title {
                        Text(pageTitle)
                            .font(.caption2)
                            .foregroundStyle(MacTheme.inkSecondary)
                            .lineLimit(1)
                    }
                }

                Spacer(minLength: 10)

                if hasDistinctFallback {
                    HStack(spacing: 2) {
                        Button("测验直达") {
                            useFullChapterPage = false
                        }
                        .font(.caption.weight(!useFullChapterPage ? .semibold : .regular))
                        .foregroundStyle(!useFullChapterPage ? MacTheme.ink : MacTheme.inkSecondary)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(
                            RoundedRectangle(cornerRadius: 5, style: .continuous)
                                .fill(!useFullChapterPage ? MacTheme.surface : Color.clear)
                        )
                        .buttonStyle(.plain)

                        Button("完整章节页") {
                            useFullChapterPage = true
                        }
                        .font(.caption.weight(useFullChapterPage ? .semibold : .regular))
                        .foregroundStyle(useFullChapterPage ? MacTheme.ink : MacTheme.inkSecondary)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(
                            RoundedRectangle(cornerRadius: 5, style: .continuous)
                                .fill(useFullChapterPage ? MacTheme.surface : Color.clear)
                        )
                        .buttonStyle(.plain)
                    }
                    .padding(2)
                    .background(MacTheme.sidebar, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 7, style: .continuous)
                            .stroke(MacTheme.border, lineWidth: 1)
                    )
                }

                Button {
                    NSWorkspace.shared.open(activeURL)
                } label: {
                    Image(systemName: "safari")
                }
                .buttonStyle(MacSecondaryButtonStyle(compact: true))
                .help("在系统浏览器中打开")

                Button("完成并关闭") {
                    if target.refreshChapterOnClose {
                        appState.refreshSelectedChapterAfterWebSession()
                    }
                    dismiss()
                }
                .buttonStyle(MacPrimaryButtonStyle(compact: true))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(MacTheme.canvas)

            if isLoading {
                ProgressView(value: max(0.08, estimatedProgress), total: 1.0)
                    .progressViewStyle(.linear)
                    .tint(MacTheme.accent)
                    .frame(height: 2)
            } else {
                MacDivider()
            }

            MacAuthenticatedWebView(
                url: activeURL,
                cookies: appState.activeCookiesForWebView(),
                reloadToken: reloadToken,
                command: $webCommand,
                isLoading: $isLoading,
                pageTitle: $pageTitle,
                canGoBack: $canGoBack,
                canGoForward: $canGoForward,
                estimatedProgress: $estimatedProgress
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .frame(minWidth: 920, idealWidth: 1020, minHeight: 660, idealHeight: 740)
        .background(MacTheme.canvas)
        .background { MacWindowChromeStyler().frame(width: 0, height: 0) }
        .onChange(of: appState.signSessionID) { dismiss() }
    }
}

private enum MacWebViewCommand: Equatable {
    case goBack
    case goForward
}

private struct MacAuthenticatedWebView: NSViewRepresentable {
    let url: URL
    let cookies: [ChaoxingStoredCookie]
    let reloadToken: UUID
    @Binding var command: MacWebViewCommand?
    @Binding var isLoading: Bool
    @Binding var pageTitle: String
    @Binding var canGoBack: Bool
    @Binding var canGoForward: Bool
    @Binding var estimatedProgress: Double

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        var parent: MacAuthenticatedWebView
        var lastLoadedURL: URL?
        var lastReloadToken: UUID?
        var progressObservation: NSKeyValueObservation?
        var titleObservation: NSKeyValueObservation?

        init(_ parent: MacAuthenticatedWebView) {
            self.parent = parent
        }

        deinit {
            progressObservation?.invalidate()
            titleObservation?.invalidate()
        }

        func loadWithCookies(webView: WKWebView, url: URL, storedCookies: [ChaoxingStoredCookie]) {
            let store = webView.configuration.websiteDataStore.httpCookieStore
            let httpCookies = Self.makeHTTPCookies(from: storedCookies)

            let group = DispatchGroup()
            for cookie in httpCookies {
                group.enter()
                store.setCookie(cookie) {
                    group.leave()
                }
            }
            group.notify(queue: .main) {
                var request = URLRequest(url: url)
                request.setValue(
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15",
                    forHTTPHeaderField: "User-Agent"
                )
                webView.load(request)
            }
        }

        private static func makeHTTPCookies(from stored: [ChaoxingStoredCookie]) -> [HTTPCookie] {
            var result: [HTTPCookie] = []
            let authCookieNames: Set<String> = ["UID", "_uid", "uf", "_d", "vc3", "fid", "k8s", "lv", "DSSTASH_LOG", "xxtenc"]
            var seen = Set<String>()

            for c in stored {
                let rawDomain = c.domain.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !c.name.isEmpty, !rawDomain.isEmpty else { continue }
                let normalizedDomain: String
                if c.hostOnly && !authCookieNames.contains(c.name) {
                    normalizedDomain = rawDomain
                } else {
                    normalizedDomain = rawDomain.hasPrefix(".") ? rawDomain : "." + rawDomain
                }

                let key = "\(c.name)|\(normalizedDomain)|\(c.path)"
                if seen.insert(key).inserted {
                    var props: [HTTPCookiePropertyKey: Any] = [
                        .name: c.name,
                        .value: c.value,
                        .domain: normalizedDomain,
                        .path: c.path.isEmpty ? "/" : c.path
                    ]
                    if c.isSecure {
                        props[.secure] = "TRUE"
                    }
                    if let exp = c.expiresAt {
                        props[.expires] = exp
                    }
                    if let cookie = HTTPCookie(properties: props) {
                        result.append(cookie)
                    }
                }

                // Ensure core Chaoxing auth cookies are also visible on `.chaoxing.com` across mooc1/mooc2 subdomains.
                if authCookieNames.contains(c.name) && rawDomain.lowercased().contains("chaoxing.com") {
                    let rootKey = "\(c.name)|.chaoxing.com|/"
                    if seen.insert(rootKey).inserted {
                        var props: [HTTPCookiePropertyKey: Any] = [
                            .name: c.name,
                            .value: c.value,
                            .domain: ".chaoxing.com",
                            .path: "/"
                        ]
                        if let exp = c.expiresAt {
                            props[.expires] = exp
                        }
                        if let rootCookie = HTTPCookie(properties: props) {
                            result.append(rootCookie)
                        }
                    }
                }
            }
            return result
        }

        // MARK: WKNavigationDelegate

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            guard let scheme = navigationAction.request.url?.scheme?.lowercased() else {
                decisionHandler(.allow)
                return
            }
            if ["http", "https", "about", "blob", "data"].contains(scheme) {
                decisionHandler(.allow)
            } else {
                decisionHandler(.cancel)
            }
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.parent.isLoading = true
                self.parent.canGoBack = webView.canGoBack
                self.parent.canGoForward = webView.canGoForward
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            DispatchQueue.main.async {
                self.parent.isLoading = false
                self.parent.canGoBack = webView.canGoBack
                self.parent.canGoForward = webView.canGoForward
                self.parent.pageTitle = webView.title ?? ""
            }
        }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
            DispatchQueue.main.async {
                self.parent.isLoading = false
            }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            DispatchQueue.main.async {
                self.parent.isLoading = false
            }
        }

        // MARK: WKUIDelegate (essential for Chaoxing quiz submit confirmation popups)

        func webView(
            _ webView: WKWebView,
            createWebViewWith configuration: WKWebViewConfiguration,
            for navigationAction: WKNavigationAction,
            windowFeatures: WKWindowFeatures
        ) -> WKWebView? {
            if navigationAction.targetFrame == nil, let targetURL = navigationAction.request.url {
                webView.load(URLRequest(url: targetURL))
            }
            return nil
        }

        @MainActor
        func webView(
            _ webView: WKWebView,
            runJavaScriptAlertPanelWithMessage message: String,
            initiatedByFrame frame: WKFrameInfo
        ) async {
            let alert = NSAlert()
            alert.messageText = "提示"
            alert.informativeText = message
            alert.addButton(withTitle: "确定")
            alert.runModal()
        }

        @MainActor
        func webView(
            _ webView: WKWebView,
            runJavaScriptConfirmPanelWithMessage message: String,
            initiatedByFrame frame: WKFrameInfo
        ) async -> Bool {
            let alert = NSAlert()
            alert.messageText = "确认"
            alert.informativeText = message
            alert.addButton(withTitle: "确定")
            alert.addButton(withTitle: "取消")
            return alert.runModal() == .alertFirstButtonReturn
        }

        @MainActor
        func webView(
            _ webView: WKWebView,
            runJavaScriptTextInputPanelWithPrompt prompt: String,
            defaultText: String?,
            initiatedByFrame frame: WKFrameInfo
        ) async -> String? {
            let alert = NSAlert()
            alert.messageText = prompt
            alert.addButton(withTitle: "确定")
            alert.addButton(withTitle: "取消")
            let field = NSTextField(frame: NSRect(x: 0, y: 0, width: 260, height: 24))
            field.stringValue = defaultText ?? ""
            alert.accessoryView = field
            return alert.runModal() == .alertFirstButtonReturn ? field.stringValue : nil
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        config.preferences.javaScriptCanOpenWindowsAutomatically = true

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.uiDelegate = context.coordinator
        webView.customUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.6 Safari/605.1.15"

        context.coordinator.progressObservation = webView.observe(\.estimatedProgress, options: [.new]) { view, _ in
            DispatchQueue.main.async {
                context.coordinator.parent.estimatedProgress = view.estimatedProgress
            }
        }
        context.coordinator.titleObservation = webView.observe(\.title, options: [.new]) { view, _ in
            DispatchQueue.main.async {
                context.coordinator.parent.pageTitle = view.title ?? ""
            }
        }

        context.coordinator.lastLoadedURL = url
        context.coordinator.lastReloadToken = reloadToken
        context.coordinator.loadWithCookies(webView: webView, url: url, storedCookies: cookies)
        return webView
    }

    func updateNSView(_ webView: WKWebView, context: Context) {
        context.coordinator.parent = self

        if let cmd = command {
            DispatchQueue.main.async { self.command = nil }
            switch cmd {
            case .goBack:
                if webView.canGoBack { webView.goBack() }
            case .goForward:
                if webView.canGoForward { webView.goForward() }
            }
        }

        if context.coordinator.lastLoadedURL != url || context.coordinator.lastReloadToken != reloadToken {
            context.coordinator.lastLoadedURL = url
            context.coordinator.lastReloadToken = reloadToken
            context.coordinator.loadWithCookies(webView: webView, url: url, storedCookies: cookies)
        }
    }
}
#endif
