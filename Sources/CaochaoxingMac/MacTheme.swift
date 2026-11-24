// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

#if os(macOS)
import SwiftUI
import AppKit

/// Warm cream-white macOS theme designed to eliminate cold system gray chrome
/// and keep every surface, border, and control cohesive and legible.
enum MacTheme {
    /// Main window background: crisp white with just a subtle hint of warm yellow (#FEFDFA).
    static let canvas = dynamic(light: (254, 253, 250), dark: (28, 27, 25))
    /// Secondary sidebar rail: airy warm off-white (#FAF9F5).
    static let sidebar = dynamic(light: (250, 249, 245), dark: (34, 33, 30))
    /// Inner sub-column background (#FCFBF8).
    static let subColumn = dynamic(light: (252, 251, 248), dark: (31, 30, 27))
    /// Elevated cards, inputs, and active tiles (#FFFFFD).
    static let surface = dynamic(light: (255, 255, 253), dark: (40, 39, 35))
    /// Soft hover / subtle pill fill (#F6F5F0).
    static let surfaceSecondary = dynamic(light: (246, 245, 240), dark: (48, 46, 42))
    /// Subtle warm highlight for selected rows (#F0EEE6).
    static let selection = dynamic(light: (240, 238, 230), dark: (62, 58, 49))
    /// Hairline dividers and card borders (#ECEAE2).
    static let border = dynamic(light: (236, 234, 226), dark: (62, 59, 53))

    /// Primary ink (#1C1B18).
    static let ink = dynamic(light: (28, 27, 24), dark: (242, 239, 231))
    /// Secondary stone ink (#6C6962).
    static let inkSecondary = dynamic(light: (108, 105, 98), dark: (174, 168, 156))
    /// Tertiary muted ink (#9A968E).
    static let inkTertiary = dynamic(light: (154, 150, 142), dark: (132, 126, 116))

    /// Calm ink-blue primary accent (#2E6091) — pairs cleanly with warm white.
    static let accent = dynamic(light: (46, 96, 145), dark: (118, 174, 230))
    /// Completed / passed green (#2E7D52).
    static let sage = dynamic(light: (46, 125, 82), dark: (110, 204, 148))
    /// Pending / attention amber (#B86B1A).
    static let amber = dynamic(light: (184, 107, 26), dark: (238, 182, 96))
    /// Destructive / error red (#C23B2B).
    static let danger = dynamic(light: (194, 59, 43), dark: (242, 126, 118))

    static var nsCanvas: NSColor {
        NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            if isDark {
                return NSColor(srgbRed: 28 / 255, green: 27 / 255, blue: 25 / 255, alpha: 1)
            }
            return NSColor(srgbRed: 254 / 255, green: 253 / 255, blue: 250 / 255, alpha: 1)
        }
    }

    private static func dynamic(light: (Int, Int, Int), dark: (Int, Int, Int)) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua
            let c = isDark ? dark : light
            return NSColor(
                srgbRed: CGFloat(c.0) / 255,
                green: CGFloat(c.1) / 255,
                blue: CGFloat(c.2) / 255,
                alpha: 1
            )
        })
    }
}

/// Blends the NSWindow chrome (including the macOS title bar) seamlessly into `MacTheme.canvas`.
struct MacWindowChromeStyler: NSViewRepresentable {
    final class HostView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            apply(to: window)
        }

        func apply(to window: NSWindow?) {
            guard let window else { return }
            window.backgroundColor = MacTheme.nsCanvas
            window.titlebarAppearsTransparent = true
            window.isOpaque = true
        }
    }

    func makeNSView(context: Context) -> HostView { HostView() }
    func updateNSView(_ view: HostView, context: Context) { view.apply(to: view.window) }
}

/// Hairline separator in warm tone (avoids macOS cold gray `Divider`).
struct MacDivider: View {
    var vertical = false

    var body: some View {
        Rectangle()
            .fill(MacTheme.border)
            .frame(width: vertical ? 1 : nil, height: vertical ? nil : 1)
    }
}

/// Solid primary button with zero macOS gray bezel.
struct MacPrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var tint: Color = MacTheme.accent
    var compact = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(compact ? .caption.weight(.medium) : .subheadline.weight(.medium))
            .foregroundStyle(isEnabled ? Color.white : MacTheme.inkTertiary)
            .padding(.horizontal, compact ? 10 : 14)
            .padding(.vertical, compact ? 5 : 7)
            .background(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(isEnabled ? tint : MacTheme.surfaceSecondary)
            )
            .opacity(configuration.isPressed ? 0.84 : 1.0)
    }
}

/// Quiet warm secondary button replacing macOS `.bordered` gray buttons.
struct MacSecondaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var compact = false
    var isDestructive = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(compact ? .caption.weight(.medium) : .subheadline.weight(.medium))
            .foregroundStyle(
                !isEnabled ? MacTheme.inkTertiary : (isDestructive ? MacTheme.danger : MacTheme.ink)
            )
            .padding(.horizontal, compact ? 9 : 12)
            .padding(.vertical, compact ? 5 : 6.5)
            .background(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(configuration.isPressed ? MacTheme.surfaceSecondary : MacTheme.surface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .stroke(MacTheme.border, lineWidth: 1)
            )
    }
}

/// Minimal text/icon ghost button with subtle warm hover fill.
struct MacGhostButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var isActive = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(isActive ? .semibold : .regular))
            .foregroundStyle(
                !isEnabled ? MacTheme.inkTertiary : (isActive ? MacTheme.ink : MacTheme.inkSecondary)
            )
            .padding(.horizontal, 10)
            .padding(.vertical, 5.5)
            .background(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .fill(isActive ? MacTheme.surface : (configuration.isPressed ? MacTheme.surfaceSecondary : Color.clear))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .stroke(isActive ? MacTheme.border : Color.clear, lineWidth: 1)
            )
    }
}

/// Clean warm search bar (replaces macOS system gray `.searchable`).
struct MacSearchField: View {
    let placeholder: String
    @Binding var text: String

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass")
                .font(.caption)
                .foregroundStyle(MacTheme.inkTertiary)
            TextField(placeholder, text: $text)
                .textFieldStyle(.plain)
                .font(.subheadline)
                .foregroundStyle(MacTheme.ink)
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkTertiary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 9)
        .padding(.vertical, 6)
        .background(MacTheme.surface, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 7, style: .continuous)
                .stroke(MacTheme.border, lineWidth: 1)
        )
    }
}

/// Clean flat section card replacing macOS gray `GroupBox`.
struct MacSectionBox<Content: View>: View {
    let title: String
    let subtitle: String?
    @ViewBuilder let content: () -> Content

    init(_ title: String, subtitle: String? = nil, @ViewBuilder content: @escaping () -> Content) {
        self.title = title
        self.subtitle = subtitle
        self.content = content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(MacTheme.ink)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(MacTheme.inkTertiary)
                }
                Spacer()
            }
            .padding(.horizontal, 2)

            VStack(alignment: .leading, spacing: 0) {
                content()
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(MacTheme.surface, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(MacTheme.border, lineWidth: 1)
            )
        }
    }
}

/// Quiet minimal status tag with warm tinting.
struct MacTag: View {
    let text: String
    var color: Color = MacTheme.inkSecondary
    var monospaced = false

    var body: some View {
        Text(text)
            .font(monospaced ? .system(size: 11, weight: .medium, design: .monospaced) : .caption2.weight(.medium))
            .foregroundStyle(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 2.5)
            .background(color.opacity(0.11), in: RoundedRectangle(cornerRadius: 5, style: .continuous))
    }
}

/// Calm, clean empty state on warm paper canvas (replaces gray `ContentUnavailableView`).
struct MacEmptyState: View {
    let icon: String
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 28, weight: .light))
                .foregroundStyle(MacTheme.inkTertiary)
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(MacTheme.ink)
            if let subtitle, !subtitle.isEmpty {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(MacTheme.inkSecondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: 320)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(MacTheme.canvas)
    }
}

extension View {
    /// Clean flat warm panel.
    func macPanel(padding: CGFloat = 14) -> some View {
        self
            .padding(padding)
            .background(MacTheme.surface, in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .stroke(MacTheme.border, lineWidth: 1)
            )
    }

    /// Clean warm-paper text field styling (no cold system bezel).
    func macInputField() -> some View {
        self
            .textFieldStyle(.plain)
            .font(.subheadline)
            .foregroundStyle(MacTheme.ink)
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(MacTheme.surface, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 7, style: .continuous)
                    .stroke(MacTheme.border, lineWidth: 1)
            )
    }

    /// Ensures scroll views and containers sit directly on the warm paper canvas.
    func macCanvas() -> some View {
        self
            .scrollContentBackground(.hidden)
            .background(MacTheme.canvas)
    }
}
#endif
