// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

#if os(macOS)
import Foundation
import AVFoundation
import SwiftUI
import CoreImage
import AppKit
import ChaoxingCore

@MainActor
@Observable
public final class MacVideoPlayerController {
    public let player = AVPlayer()
    public var currentSpeed: ChaoxingPlaybackSpeed = .x1
    public var isPlaying = false
    public var isBuffering = false
    public var isReady = false
    public var playerErrorMessage: String?
    public var currentSeconds: Double = 0
    public var durationSeconds: Double = 0

    private var boostTimer: Timer?
    private var timeObserverToken: Any?
    private var endObserverToken: NSObjectProtocol?
    private var rateObserver: NSKeyValueObservation?
    private var itemStatusObserver: NSKeyValueObservation?
    private var timeControlObserver: NSKeyValueObservation?
    private var pendingResumeSec: Int?

    private final class AtomicFlag: @unchecked Sendable {
        private let lock = NSLock()
        private var value = false
        func get() -> Bool { lock.withLock { value } }
        func set(_ newValue: Bool) { lock.withLock { value = newValue } }
    }
    private let suppressRateFlag = AtomicFlag()
    private var isBoostSeekInFlight = false
    private var pendingBoostDelta: Double = 0

    nonisolated private var suppressRateObservation: Bool {
        get { suppressRateFlag.get() }
        set { suppressRateFlag.set(newValue) }
    }

    public var onPlaybackEvent: ((ChaoxingVideoDragEvent, Int) -> Void)?

    public init() {
        let interval = CMTime(seconds: 0.25, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        timeObserverToken = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            Task { @MainActor [weak self] in
                guard let self else { return }
                let sec = time.seconds
                if sec.isFinite && sec >= 0 && !self.isBoostSeekInFlight {
                    self.currentSeconds = sec
                }
                if let dur = self.player.currentItem?.duration.seconds, dur.isFinite && dur > 0 {
                    self.durationSeconds = dur
                }
                self.isPlaying = self.player.rate > 0
            }
        }
        timeControlObserver = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] player, _ in
            let tc = player.timeControlStatus
            Task { @MainActor [weak self] in
                guard let self else { return }
                switch tc {
                case .waitingToPlayAtSpecifiedRate:
                    self.isBuffering = self.player.currentItem != nil
                case .playing:
                    self.isBuffering = false
                case .paused:
                    self.isBuffering = self.player.currentItem != nil && !self.isReady && self.playerErrorMessage == nil
                @unknown default:
                    break
                }
            }
        }
        rateObserver = player.observe(\.rate, options: [.old, .new]) { [weak self] player, change in
            guard let self else { return }
            if self.suppressRateObservation { return }
            let oldRate = change.oldValue ?? 0
            let newRate = change.newValue ?? player.rate
            Task { @MainActor [weak self] in
                guard let self, !self.suppressRateObservation else { return }
                let wasPlaying = oldRate > 0
                let nowPlaying = newRate > 0
                self.isPlaying = nowPlaying
                if !wasPlaying && nowPlaying {
                    if !self.currentSpeed.isNativeRateSupported || abs(Double(newRate) - self.currentSpeed.rawValue) > 0.05 {
                        self.suppressRateObservation = true
                        self.applyCurrentRate(playing: true)
                        self.suppressRateObservation = false
                    }
                    let playSec = Int(round(self.player.currentTime().seconds.isFinite ? self.player.currentTime().seconds : self.currentSeconds))
                    self.onPlaybackEvent?(.play, playSec)
                } else if wasPlaying && !nowPlaying {
                    let curSec = self.player.currentTime().seconds.isFinite ? self.player.currentTime().seconds : self.currentSeconds
                    let dur = self.durationSeconds
                    if dur <= 0 || curSec < dur - 0.5 {
                        self.onPlaybackEvent?(.pause, Int(round(curSec)))
                    }
                }
            }
        }
    }

    public func cleanup() {
        stopBoostTimer()
        if let token = timeObserverToken {
            player.removeTimeObserver(token)
            timeObserverToken = nil
        }
        if let endToken = endObserverToken {
            NotificationCenter.default.removeObserver(endToken)
            endObserverToken = nil
        }
        itemStatusObserver?.invalidate()
        itemStatusObserver = nil
        timeControlObserver?.invalidate()
        timeControlObserver = nil
        rateObserver?.invalidate()
        rateObserver = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
    }

    public func load(urlString: String?, fallbackDuration: Int, resumeAtSeconds: Int = 0) {
        suppressRateObservation = true
        stopBoostTimer()
        player.pause()
        isPlaying = false
        isReady = false
        playerErrorMessage = nil
        if let endToken = endObserverToken {
            NotificationCenter.default.removeObserver(endToken)
            endObserverToken = nil
        }
        itemStatusObserver?.invalidate()
        itemStatusObserver = nil

        let shouldResume = resumeAtSeconds > 0 && resumeAtSeconds < max(1, fallbackDuration - 3)
        pendingResumeSec = shouldResume ? resumeAtSeconds : nil
        currentSeconds = shouldResume ? Double(resumeAtSeconds) : 0
        durationSeconds = Double(max(0, fallbackDuration))
        suppressRateObservation = false

        guard let item = Self.makeAuthenticatedPlayerItem(urlString: urlString) else {
            isBuffering = false
            player.replaceCurrentItem(with: nil)
            if urlString != nil {
                playerErrorMessage = "无效的视频播放地址"
            }
            return
        }

        isBuffering = true
        player.replaceCurrentItem(with: item)

        itemStatusObserver = item.observe(\.status, options: [.initial, .new]) { [weak self] observedItem, _ in
            let status = observedItem.status
            let errMsg = observedItem.error?.localizedDescription
            let itemDur = observedItem.duration.seconds
            Task { @MainActor [weak self] in
                guard let self, self.player.currentItem === observedItem else { return }
                switch status {
                case .readyToPlay:
                    self.isReady = true
                    self.playerErrorMessage = nil
                    if itemDur.isFinite && itemDur > 0 {
                        self.durationSeconds = itemDur
                    }
                    if let resume = self.pendingResumeSec {
                        self.pendingResumeSec = nil
                        let target = CMTime(seconds: Double(resume), preferredTimescale: 600)
                        self.player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                            Task { @MainActor [weak self] in
                                guard let self else { return }
                                self.isBuffering = self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate
                                if self.isPlaying {
                                    self.suppressRateObservation = true
                                    self.applyCurrentRate(playing: true)
                                    self.suppressRateObservation = false
                                }
                            }
                        }
                    } else {
                        self.isBuffering = self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate
                    }
                case .failed:
                    self.isReady = false
                    self.isBuffering = false
                    self.isPlaying = false
                    self.stopBoostTimer()
                    self.playerErrorMessage = errMsg ?? "视频流加载失败，请切换网络或重试"
                case .unknown:
                    self.isBuffering = true
                @unknown default:
                    break
                }
            }
        }

        // Observe ONLY this specific AVPlayerItem so concurrent batch players never trigger main player ended events.
        endObserverToken = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.isPlaying else { return }
                self.stopBoostTimer()
                self.isPlaying = false
                let endSec = Int(round(max(self.durationSeconds, self.currentSeconds)))
                self.currentSeconds = Double(endSec)
                self.onPlaybackEvent?(.ended, endSec)
            }
        }
    }

    public static func makeAuthenticatedPlayerItem(
        urlString: String?,
        referer: String = "https://mooc1-2.chaoxing.com/ananas/modules/video/index.html"
    ) -> AVPlayerItem? {
        guard let urlString, let url = URL(string: urlString) else { return nil }
        let headers = [
            "Referer": referer,
            "User-Agent": ChaoxingCourseStudyService.pcWebUserAgent
        ]
        let asset = AVURLAsset(url: url, options: ["AVURLAssetHTTPHeaderFieldsKey": headers])
        return AVPlayerItem(asset: asset)
    }

    public func setSpeed(_ speed: ChaoxingPlaybackSpeed) {
        currentSpeed = speed
        let nativeRate = Float(speed.isNativeRateSupported ? speed.rawValue : 16.0)
        player.defaultRate = nativeRate
        if isPlaying || player.rate > 0 {
            suppressRateObservation = true
            applyCurrentRate(playing: true)
            suppressRateObservation = false
        }
    }

    public func togglePlayPause() {
        if isPlaying || player.rate > 0 {
            pause()
        } else {
            play()
        }
    }

    public func play() {
        guard player.currentItem != nil else { return }
        if durationSeconds > 0 && currentSeconds >= durationSeconds - 0.5 {
            seek(to: 0, emitLog: false)
        }
        suppressRateObservation = true
        let wasPlaying = isPlaying || player.rate > 0
        applyCurrentRate(playing: true)
        suppressRateObservation = false
        if !wasPlaying && isPlaying {
            onPlaybackEvent?(.play, Int(round(currentSeconds)))
        }
    }

    public func pause() {
        suppressRateObservation = true
        let wasPlaying = isPlaying || player.rate > 0
        stopBoostTimer()
        player.pause()
        isPlaying = false
        suppressRateObservation = false
        if wasPlaying {
            onPlaybackEvent?(.pause, Int(round(currentSeconds)))
        }
    }

    public func seek(to seconds: Double, emitLog: Bool = true) {
        let upper = durationSeconds > 0 ? durationSeconds : max(seconds, 0)
        let clamped = max(0, min(seconds, upper))
        pendingResumeSec = nil
        let target = CMTime(seconds: clamped, preferredTimescale: 600)
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero)
        currentSeconds = clamped
        if emitLog {
            onPlaybackEvent?(.drag, Int(round(clamped)))
        }
    }

    private func applyCurrentRate(playing: Bool) {
        stopBoostTimer()
        guard playing, player.currentItem != nil else {
            player.pause()
            isPlaying = false
            return
        }

        isPlaying = true
        let nativeRate = Float(currentSpeed.isNativeRateSupported ? currentSpeed.rawValue : 16.0)
        player.defaultRate = nativeRate
        player.play()
        player.rate = nativeRate

        if !currentSpeed.isNativeRateSupported {
            // AVFoundation reliably plays up to 16x; 32x and 64x pair 16x with non-overlapping forward seeks
            // ONLY once the video item is actually ready and playing (never during initial buffering).
            pendingBoostDelta = 0
            isBoostSeekInFlight = false
            let stepPeriod = 0.25
            let extraPerTick = (currentSpeed.rawValue - 16.0) * stepPeriod
            boostTimer = Timer.scheduledTimer(withTimeInterval: stepPeriod, repeats: true) { [weak self] _ in
                Task { @MainActor [weak self] in
                    guard let self, self.isPlaying, self.isReady, self.player.timeControlStatus == .playing else { return }
                    self.pendingBoostDelta += extraPerTick
                    guard !self.isBoostSeekInFlight else { return }

                    let dur = self.durationSeconds
                    let rawCur = self.player.currentTime().seconds
                    let cur = rawCur.isFinite ? rawCur : self.currentSeconds
                    let next = cur + self.pendingBoostDelta
                    self.pendingBoostDelta = 0

                    if dur > 0 && next >= dur - 0.3 {
                        self.suppressRateObservation = true
                        self.stopBoostTimer()
                        self.player.pause()
                        self.isPlaying = false
                        self.suppressRateObservation = false
                        self.seek(to: dur, emitLog: false)
                        self.onPlaybackEvent?(.ended, Int(round(dur)))
                    } else {
                        self.isBoostSeekInFlight = true
                        let target = CMTime(seconds: next, preferredTimescale: 600)
                        self.player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                            Task { @MainActor [weak self] in
                                self?.isBoostSeekInFlight = false
                            }
                        }
                        self.currentSeconds = next
                    }
                }
            }
        }
    }

    private func stopBoostTimer() {
        boostTimer?.invalidate()
        boostTimer = nil
        isBoostSeekInFlight = false
        pendingBoostDelta = 0
    }
}

public struct MacStudyLogLine: Identifiable, Sendable {
    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    public let id = UUID()
    public let time: String
    public let message: String
    public let isError: Bool

    public init(message: String, isError: Bool = false) {
        self.time = Self.formatter.string(from: Date())
        self.message = message
        self.isError = isError
    }
}

/// Observable per-tile worker for simultaneous multi-video playback.
/// Being a reference `@Observable` object isolates 500ms progress ticks to its own tile without
/// re-evaluating sibling `AVPlayerView`s or the surrounding `LazyVGrid`.
@MainActor
@Observable
public final class MacBatchWatchWorker: Identifiable {
    public let id: String
    public let courseName: String
    public let chapterTitle: String
    public let video: ChaoxingVideoTask?
    public let liveTask: ChaoxingLiveTask?
    public let title: String
    public private(set) var playURL: String?
    public let screenshotURL: String?
    public let isLiveReplay: Bool
    public let webPageURL: String?
    public var duration: Int
    public let legalRate: Double
    public let player: AVPlayer

    /// Smooth per-second UI progress position.
    public var displayedSec: Int
    /// Last position confirmed/persisted by the server.
    public var reportedSec: Int
    public var nextTickInSec: Int
    public var statusText: String
    public var isPassed: Bool
    public var isMuted: Bool
    public var isBuffering: Bool
    public var isPlayerReady: Bool
    public var hasPlaybackStarted: Bool
    public var playerError: String?

    /// `true` once the player has finished initial loading/buffering and started rendering frames,
    /// or when no direct media stream can be loaded (allowing heartbeat-only fallback without deadlocking).
    public var isPlaybackReadyForStudy: Bool {
        hasPlaybackStarted || playerError != nil || candidatePlayURLs.isEmpty
    }

    @ObservationIgnored private var candidatePlayURLs: [String]
    @ObservationIgnored private var currentPlayURLIndex = 0
    @ObservationIgnored private var itemStatusObserver: NSKeyValueObservation?
    @ObservationIgnored private var timeControlObserver: NSKeyValueObservation?
    @ObservationIgnored private var endObserverToken: NSObjectProtocol?
    @ObservationIgnored private var bufferingWatchdogTask: Task<Void, Never>?
    @ObservationIgnored private var pendingSeekSec: Int?

    public init(
        courseName: String,
        chapterTitle: String,
        video: ChaoxingVideoTask,
        initialSec: Int
    ) {
        let initialURLs = video.videoPlayURL.flatMap { $0.isEmpty ? nil : [$0] } ?? []
        self.id = video.id
        self.courseName = courseName
        self.chapterTitle = chapterTitle
        self.video = video
        self.liveTask = nil
        self.title = video.name
        self.candidatePlayURLs = initialURLs
        self.playURL = initialURLs.first
        self.screenshotURL = video.screenshotURL
        self.isLiveReplay = false
        self.webPageURL = nil
        self.duration = video.effectiveClipEnd
        self.legalRate = video.allowedMaxRate
        self.player = AVPlayer()
        self.displayedSec = initialSec
        self.reportedSec = initialSec
        self.nextTickInSec = 0
        self.statusText = "等待视频加载缓冲..."
        self.isPassed = false
        self.isMuted = true
        self.isBuffering = !initialURLs.isEmpty
        self.isPlayerReady = false
        self.hasPlaybackStarted = initialURLs.isEmpty
        self.playerError = nil

        self.player.isMuted = true
        self.player.automaticallyWaitsToMinimizeStalling = true
        self.player.defaultRate = Float(video.allowedMaxRate)
        startPlayback(startAtSec: initialSec)
    }

    public init(
        courseName: String,
        chapterTitle: String,
        liveTask: ChaoxingLiveTask,
        initialSec: Int
    ) {
        let initialURLs = liveTask.allPlayURLs
        self.id = liveTask.id
        self.courseName = courseName
        self.chapterTitle = chapterTitle
        self.video = nil
        self.liveTask = liveTask
        self.title = liveTask.title
        self.candidatePlayURLs = initialURLs
        self.playURL = initialURLs.first
        self.screenshotURL = nil
        self.isLiveReplay = true
        self.webPageURL = liveTask.webPageURL
        self.duration = max(1, liveTask.duration)
        self.legalRate = 1.0
        self.player = AVPlayer()
        self.displayedSec = initialSec
        self.reportedSec = initialSec
        self.nextTickInSec = 0
        self.statusText = "连接直播回放 (\(liveTask.statusDescription))..."
        self.isPassed = false
        self.isMuted = true
        self.isBuffering = !initialURLs.isEmpty
        self.isPlayerReady = false
        self.hasPlaybackStarted = false
        self.playerError = nil

        self.player.isMuted = true
        self.player.automaticallyWaitsToMinimizeStalling = true
        self.player.defaultRate = 1.0
        startPlayback(startAtSec: initialSec)
    }

    public func toggleMute() {
        isMuted.toggle()
        player.isMuted = isMuted
    }

    /// Appends additional deduplicated playback URLs (e.g. `m3u8` + `mp4` fallback) discovered after session init,
    /// and automatically kicks off playback if no stream is currently ready.
    public func updateStreamURLs(_ urls: [String], startAtSec: Int) {
        let hadCount = candidatePlayURLs.count
        for raw in urls {
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty && !candidatePlayURLs.contains(trimmed) {
                candidatePlayURLs.append(trimmed)
            }
        }
        guard candidatePlayURLs.count > hadCount else { return }
        if !isPlayerReady && (player.currentItem == nil || playerError != nil) {
            currentPlayURLIndex = hadCount
            playURL = candidatePlayURLs[currentPlayURLIndex]
            startPlayback(startAtSec: startAtSec)
        }
    }

    public func startPlayback(startAtSec: Int, overridePlayURL: String? = nil) {
        itemStatusObserver?.invalidate()
        itemStatusObserver = nil
        timeControlObserver?.invalidate()
        timeControlObserver = nil
        if let endToken = endObserverToken {
            NotificationCenter.default.removeObserver(endToken)
            endObserverToken = nil
        }
        bufferingWatchdogTask?.cancel()
        bufferingWatchdogTask = nil

        if let overridePlayURL, !overridePlayURL.isEmpty {
            if let existingIdx = candidatePlayURLs.firstIndex(of: overridePlayURL) {
                currentPlayURLIndex = existingIdx
            } else {
                candidatePlayURLs.insert(overridePlayURL, at: 0)
                currentPlayURLIndex = 0
            }
        }

        let targetURL = currentPlayURLIndex < candidatePlayURLs.count ? candidatePlayURLs[currentPlayURLIndex] : nil
        self.playURL = targetURL
        let referer = isLiveReplay ? "\(ChaoxingCourseStudyService.zhiboBase)/" : "https://mooc1-2.chaoxing.com/ananas/modules/video/index.html"
        guard let item = MacVideoPlayerController.makeAuthenticatedPlayerItem(urlString: targetURL, referer: referer) else {
            isBuffering = false
            playerError = isLiveReplay ? "直播回放心跳同步中（30s/次）" : "无视频直链（仍正常上报心跳）"
            return
        }

        // Keep forward buffer lean and cap peak HLS bitrate so 5-10 simultaneous tiles start up quickly without saturating bandwidth.
        item.preferredForwardBufferDuration = 3
        item.preferredPeakBitRate = 1_500_000

        let shouldSeek = startAtSec > 0 && startAtSec < max(1, duration - 3)
        pendingSeekSec = shouldSeek ? startAtSec : nil
        isBuffering = true
        isPlayerReady = false
        hasPlaybackStarted = false
        playerError = nil

        player.automaticallyWaitsToMinimizeStalling = true
        player.defaultRate = Float(legalRate)
        player.replaceCurrentItem(with: item)

        if isLiveReplay {
            endObserverToken = NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: item,
                queue: .main
            ) { [weak self] _ in
                Task { @MainActor [weak self] in
                    self?.restart(at: 0)
                }
            }
        }

        timeControlObserver = player.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] p, _ in
            let tc = p.timeControlStatus
            Task { @MainActor [weak self] in
                guard let self else { return }
                switch tc {
                case .playing:
                    self.isBuffering = false
                    if self.isPlayerReady && self.pendingSeekSec == nil {
                        self.hasPlaybackStarted = true
                    }
                    self.bufferingWatchdogTask?.cancel()
                    self.bufferingWatchdogTask = nil
                case .waitingToPlayAtSpecifiedRate:
                    self.isBuffering = true
                case .paused:
                    self.isBuffering = !self.isPlayerReady && self.playerError == nil
                @unknown default:
                    break
                }
            }
        }

        itemStatusObserver = item.observe(\.status, options: [.initial, .new]) { [weak self] observedItem, _ in
            let status = observedItem.status
            let errText = observedItem.error?.localizedDescription
            Task { @MainActor [weak self] in
                guard let self, self.player.currentItem === observedItem else { return }
                switch status {
                case .readyToPlay:
                    self.isPlayerReady = true
                    self.playerError = nil
                    self.bufferingWatchdogTask?.cancel()
                    self.bufferingWatchdogTask = nil
                    if let seekSec = self.pendingSeekSec {
                        self.pendingSeekSec = nil
                        let target = CMTime(seconds: Double(seekSec), preferredTimescale: 600)
                        self.player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
                            Task { @MainActor [weak self] in
                                guard let self else { return }
                                self.player.defaultRate = Float(self.legalRate)
                                self.player.play()
                                self.player.rate = Float(self.legalRate)
                                if self.player.timeControlStatus == .playing {
                                    self.isBuffering = false
                                    self.hasPlaybackStarted = true
                                }
                            }
                        }
                    } else {
                        self.player.defaultRate = Float(self.legalRate)
                        self.player.play()
                        self.player.rate = Float(self.legalRate)
                        if self.player.timeControlStatus == .playing {
                            self.isBuffering = false
                            self.hasPlaybackStarted = true
                        }
                    }
                case .failed:
                    self.bufferingWatchdogTask?.cancel()
                    self.bufferingWatchdogTask = nil
                    if self.tryFallbackStream() {
                        return
                    }
                    self.isPlayerReady = false
                    self.isBuffering = false
                    self.playerError = errText ?? "画面加载失败（心跳仍在运行）"
                case .unknown:
                    self.isBuffering = true
                @unknown default:
                    break
                }
            }
        }

        // If another fallback stream exists (e.g. progressive MP4 when HLS m3u8 times out), switch automatically after 15s stall.
        if currentPlayURLIndex + 1 < candidatePlayURLs.count {
            let watchedItem = item
            bufferingWatchdogTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(15))
                guard !Task.isCancelled else { return }
                await MainActor.run { [weak self] in
                    guard let self, self.player.currentItem === watchedItem, !self.isPlayerReady else { return }
                    _ = self.tryFallbackStream()
                }
            }
        }

        // Kick off playback immediately only when starting from 0; otherwise wait for `.readyToPlay` -> seek -> play
        // so the tile does not flash frames from 00:00 before jumping to `startAtSec`.
        if !shouldSeek {
            player.play()
        }
    }

    private func tryFallbackStream() -> Bool {
        let nextIndex = currentPlayURLIndex + 1
        guard nextIndex < candidatePlayURLs.count else { return false }
        currentPlayURLIndex = nextIndex
        startPlayback(startAtSec: displayedSec)
        return true
    }

    public func restart(at startAtSec: Int) {
        let target = CMTime(seconds: Double(startAtSec), preferredTimescale: 600)
        player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.player.defaultRate = Float(self.legalRate)
                self.player.play()
                self.player.rate = Float(self.legalRate)
            }
        }
    }

    public func stopAndCleanup() {
        bufferingWatchdogTask?.cancel()
        bufferingWatchdogTask = nil
        if let endToken = endObserverToken {
            NotificationCenter.default.removeObserver(endToken)
            endObserverToken = nil
        }
        itemStatusObserver?.invalidate()
        itemStatusObserver = nil
        timeControlObserver?.invalidate()
        timeControlObserver = nil
        player.pause()
        player.replaceCurrentItem(with: nil)
    }
}

public enum MacWorkspaceTab: String, CaseIterable, Identifiable {
    case study = "课程看课"
    case sign = "课程签到"
    case groups = "群聊签到"
    case settings = "账号与设置"

    public var id: String { rawValue }
    public var systemImage: String {
        switch self {
        case .study: return "play.rectangle.on.rectangle"
        case .sign: return "location.viewfinder"
        case .groups: return "bubble.left.and.bubble.right"
        case .settings: return "gearshape"
        }
    }
}

private enum PendingBatchPayload: Sendable {
    case video(ChaoxingVideoTask)
    case live(ChaoxingLiveTask)

    var id: String {
        switch self {
        case .video(let v): return v.id
        case .live(let l): return l.id
        }
    }

    var displayName: String {
        switch self {
        case .video(let v): return v.name
        case .live(let l): return l.title
        }
    }
}

private struct PendingBatchItem: Sendable {
    let courseName: String
    let chapterTitle: String
    let payload: PendingBatchPayload
}

private enum WorkerOutcome: Sendable {
    case passed
    case skipped
    case rateLimited(restSeconds: Int?)
    case failed(String)
}

public struct MacCaptchaChallenge: Identifiable, Sendable {
    public let id = UUID()
    public let client: ChaoxingHTTPClient
    public let refererURL: String
    public let accountName: String
    public let captcha: ChaoxingCaptchaData
    public let shadeImageData: Data
    public let cutoutImageData: Data
}

struct MacPreparedSignWorkspace {
    let activityID: Int64
    let sessionID: UUID
    let status: ChaoxingSignActivityStatus?
    let detail: ChaoxingSignDetailMetadata?

    func signType(for activity: ChaoxingSignActivity) -> ChaoxingSignType {
        if let type = detail?.signType, type != .unknown { return type }
        return activity.signType
    }
}

struct MacSignResult: Identifiable {
    let id: String
    let accountName: String
    let disposition: ChaoxingSignDisposition
    let message: String
}

private extension ChaoxingPersistenceStore {
    // Keep settings managed by other clients when writing the desktop fields.
    func saveMacState(_ state: ChaoxingAppStateSnapshot) throws {
        var snapshot = try load()
        snapshot.accounts = state.accounts
        snapshot.locationPresets = state.locationPresets
        snapshot.starredCourseIds = state.starredCourseIds
        snapshot.courseLocationBindings = state.courseLocationBindings
        snapshot.signHistory = state.signHistory
        snapshot.enableLocationJitter = state.enableLocationJitter
        snapshot.batchSignDelayMs = state.batchSignDelayMs
        try save(snapshot)
    }
}

@MainActor
@Observable
public final class MacAppState {
    public let store: ChaoxingPersistenceStore
    private let clientConfiguration: URLSessionConfiguration
    private let waitBetweenSignAccounts: @Sendable (Duration) async throws -> Void
    public let playerController = MacVideoPlayerController()

    public var selectedTab: MacWorkspaceTab = .study

    // Account & courses
    public var accounts: [ChaoxingAccountSession] = []
    public var activePhoneNumber: String?
    public var courses: [ChaoxingCourse] = []

    // Study state (completely decoupled from sign-in state)
    public var selectedStudyCourse: ChaoxingCourse?
    public var currentCpi: Int64 = 0
    public var chapters: [ChaoxingChapterNode] = []
    public var selectedChapter: ChaoxingChapterNode?
    public var videos: [ChaoxingVideoTask] = []
    public var liveTasks: [ChaoxingLiveTask] = []
    public var chapterAttachments: [ChaoxingChapterAttachment] = []
    public var selectedVideo: ChaoxingVideoTask?
    public var onlyPendingChapters = false
    public var completingDocumentIds: Set<String> = []

    // Sign-in & Radar state (completely decoupled from study state)
    public var selectedSignCourse: ChaoxingCourse?
    public var locationPresets: [ChaoxingLocationPreset] = []
    public var starredCourseIds: Set<Int64> = []
    public var courseLocationBindings: [String: ChaoxingLocationPreset] = [:]
    public var signHistory: [ChaoxingSignHistoryEntry] = []
    public var enableLocationJitter: Bool = true
    public var batchSignDelayMs: Int = 1200

    public var pendingRadarActivities: [ChaoxingSignActivity] = []
    public var isScanningRadar: Bool { radarScanID != nil }
    private(set) var radarScanMessage: String?
    private(set) var radarFailedCourses = 0
    public var courseActivities: [ChaoxingSignActivity] = []
    public var isLoadingActivities = false
    public var groups: [ChaoxingIMGroup] = []
    public var imConfig: ChaoxingEasemobIMConfig?
    public var selectedGroup: ChaoxingIMGroup?
    public var groupActivities: [ChaoxingSignActivity] = []
    public var isLoadingGroups = false
    public var activeCaptchaChallenge: MacCaptchaChallenge?
    private var captchaContinuation: CheckedContinuation<String, Error>?

    // Isolated loading & error states
    public var isLoggingIn = false
    public var isSwitchingAccount = false
    public var refreshingAccountPhones: Set<String> = []
    public var showAddAccountSheet = false
    public var isLoadingCourses = false
    public var isLoadingChapters = false
    public var isLoadingVideos = false
    public var loginErrorMessage: String?
    public var studyErrorMessage: String?
    public var signErrorMessage: String?
    public var accountChangeError: String?

    // Single-video built-in player adaptive heartbeat state
    public var playerHeartbeatCountdownSec: Double = 0
    public var lastHeartbeatSummary: String?
    public var heartbeatSentCount: Int = 0

    // Single-video auto-watch state
    public var isAutoWatching = false
    public var autoAdvanceNextChapter: Bool = true {
        didSet { UserDefaults.standard.set(autoAdvanceNextChapter, forKey: "caochaoxing.mac.autoAdvance") }
    }
    public var autoCompleteDocuments: Bool = true {
        didSet { UserDefaults.standard.set(autoCompleteDocuments, forKey: "caochaoxing.mac.autoCompleteDocuments") }
    }
    public var autoWatchDisplayedSec: Int = 0
    public var autoWatchReportedSec: Int = 0
    public var autoWatchNextTickInSec: Int = 0
    public var autoWatchTaskId: String?

    // Streaming multi-way concurrent watch state
    public var maxConcurrentWatches: Int = 5 {
        didSet { UserDefaults.standard.set(maxConcurrentWatches, forKey: "caochaoxing.mac.maxConcurrency") }
    }
    public var watchAllCoursesAutomatically: Bool = false {
        didSet { UserDefaults.standard.set(watchAllCoursesAutomatically, forKey: "caochaoxing.mac.watchAllCourses") }
    }
    public var autoStartOnLaunch: Bool = false {
        didSet { UserDefaults.standard.set(autoStartOnLaunch, forKey: "caochaoxing.mac.autoStartOnLaunch") }
    }
    public var isBatchWatching = false
    public var batchScopeLabel: String = ""
    public var batchScanProgress: String?
    public var batchCooldownRemainingSec: Int = 0
    public var batchQueueCount: Int = 0
    public var batchCompletedCount: Int = 0
    public var batchCompletedDocsCount: Int = 0
    public var batchSkippedCount: Int = 0
    public var batchTotalDiscoveredCount: Int = 0
    public var batchActiveWorkers: [MacBatchWatchWorker] = []

    public var logs: [MacStudyLogLine] = []

    // Caches & task cancellation guards
    private var chaptersCache: [String: (cpi: Int64, chapters: [ChaoxingChapterNode])] = [:]
    private var chapterContentCache: [String: ChaoxingChapterContent] = [:]
    private var courseSelectTask: Task<Void, Never>?
    private var chapterSelectTask: Task<Void, Never>?
    private var signActivitiesTask: Task<Void, Never>?
    private var radarScanID: UUID?
    private var radarTask: Task<Void, Never>?
    private(set) var signSessionID = UUID()
    private var signBatch: (id: UUID, owner: UUID, task: Task<Void, Never>)?
    private var signRunnerClient: ChaoxingHTTPClient?
    private var accountRemovalTask: Task<Void, Never>?
    private var pendingSave: Task<Void, Error>?

    private var client: ChaoxingHTTPClient?
    private var autoWatchRunner: Task<Void, Never>?
    private var batchRunner: Task<Void, Never>?
    private var batchWorkerTasks: [String: Task<WorkerOutcome, Never>] = [:]
    private var playerHeartbeatTask: Task<Void, Never>?
    private var lastPlayerLogTime: Date = .distantPast
    private var powerActivityToken: NSObjectProtocol?
    private var hasRestored = false
    private var accountSwitchGeneration: UInt64 = 0
    private var coursesCacheByPhone: [String: [ChaoxingCourse]] = [:]

    public convenience init() {
        self.init(store: ChaoxingPersistenceStore())
    }

    init(store: ChaoxingPersistenceStore, clientConfiguration: URLSessionConfiguration = .ephemeral,
         waitBetweenSignAccounts: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.store = store
        self.clientConfiguration = clientConfiguration
        self.waitBetweenSignAccounts = waitBetweenSignAccounts
        let savedConcurrency = UserDefaults.standard.integer(forKey: "caochaoxing.mac.maxConcurrency")
        if savedConcurrency >= 1 && savedConcurrency <= 10 {
            self.maxConcurrentWatches = savedConcurrency
        }
        if UserDefaults.standard.object(forKey: "caochaoxing.mac.autoAdvance") != nil {
            self.autoAdvanceNextChapter = UserDefaults.standard.bool(forKey: "caochaoxing.mac.autoAdvance")
        }
        if UserDefaults.standard.object(forKey: "caochaoxing.mac.autoCompleteDocuments") != nil {
            self.autoCompleteDocuments = UserDefaults.standard.bool(forKey: "caochaoxing.mac.autoCompleteDocuments")
        }
        self.watchAllCoursesAutomatically = UserDefaults.standard.bool(forKey: "caochaoxing.mac.watchAllCourses")
        self.autoStartOnLaunch = UserDefaults.standard.bool(forKey: "caochaoxing.mac.autoStartOnLaunch")

        playerController.onPlaybackEvent = { [weak self] event, sec in
            Task { @MainActor [weak self] in
                await self?.handlePlayerPlaybackEvent(event, playingTimeSec: sec)
            }
        }
    }

    private func makeClient(for session: ChaoxingAccountSession) -> ChaoxingHTTPClient {
        ChaoxingHTTPClient(session: session, configuration: clientConfiguration)
    }

    public var playerHeartbeatIntervalSec: Double {
        let base = Double(selectedVideo?.reportTimeInterval ?? 60)
        return max(0.8, base / playerController.currentSpeed.rawValue)
    }

    public var activeAccount: ChaoxingAccountSession? {
        accounts.first { $0.phoneNumber == activePhoneNumber } ?? accounts.first
    }

    public func restore() async {
        guard !hasRestored else { return }
        hasRestored = true
        do {
            let snapshot = try await store.load()
            accounts = snapshot.accounts
            locationPresets = snapshot.locationPresets
            starredCourseIds = Set(snapshot.starredCourseIds)
            courseLocationBindings = snapshot.courseLocationBindings
            signHistory = snapshot.signHistory
            enableLocationJitter = snapshot.enableLocationJitter
            batchSignDelayMs = snapshot.batchSignDelayMs
            if let first = accounts.first(where: { $0.isPrimary }) ?? accounts.first {
                activePhoneNumber = first.phoneNumber
                client = makeClient(for: first)
                await loadCourses()
                if autoStartOnLaunch && !courses.isEmpty {
                    await startSimultaneousWatch()
                }
            }
        } catch {
            appendLog("初始化本地会话失败: \(error.localizedDescription)", isError: true)
        }
    }

    public func setEnableLocationJitter(_ enabled: Bool) {
        enableLocationJitter = enabled
        Task { try? await persistAccounts() }
    }

    public func accountRemark(for session: ChaoxingAccountSession) -> String {
        session.tags.first?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    public func accountDisplayTitle(for session: ChaoxingAccountSession) -> String {
        let baseName = session.name.isEmpty ? session.phoneNumber : session.name
        let remark = accountRemark(for: session)
        return remark.isEmpty ? baseName : "\(baseName) · \(remark)"
    }

    private func accountLogTitle(for session: ChaoxingAccountSession) -> String {
        Self.redactPhones(in: accountDisplayTitle(for: session), known: [session.phoneNumber])
    }

    public func login(
        phoneNumber: String,
        password: String,
        remark: String = "",
        switchImmediately: Bool = true
    ) async {
        while let accountRemovalTask { await accountRemovalTask.value }
        guard !isLoggingIn else { return }
        let phone = phoneNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedRemark = remark.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !phone.isEmpty, !password.isEmpty else {
            loginErrorMessage = "请输入手机号和密码"
            return
        }
        isLoggingIn = true
        loginErrorMessage = nil
        defer { isLoggingIn = false }

        do {
            let originalSessionID = signSessionID
            let shouldActivate = switchImmediately || accounts.isEmpty || activePhoneNumber == nil || activePhoneNumber == phone
            let existingTags = accounts.first(where: { $0.phoneNumber == phone })?.tags ?? []
            var session = try await ChaoxingAuthService.login(
                phoneNumber: phone,
                password: password,
                userAgent: ChaoxingClientPreset.default.userAgent,
                isPrimary: shouldActivate
            )
            guard signSessionID == originalSessionID, !Task.isCancelled else {
                loginErrorMessage = "账号已变更，请重新登录"
                return
            }
            if !trimmedRemark.isEmpty {
                session.tags = [trimmedRemark]
            } else if !existingTags.isEmpty {
                session.tags = existingTags
            }

            if shouldActivate {
                invalidateSignWorkspace()
                accountSwitchGeneration &+= 1
                stopAllWatchActivity()
                client?.invalidate()
                for idx in accounts.indices {
                    accounts[idx].isPrimary = false
                }
                accounts.removeAll { $0.phoneNumber == session.phoneNumber }
                accounts.insert(session, at: 0)
                activePhoneNumber = session.phoneNumber
                client = makeClient(for: session)
                isSwitchingAccount = false
                resetWorkspaceCollections()
                let expectedSession = signSessionID
                try await persistAccounts()
                guard expectedSession == signSessionID else { return }
                appendLog("登录并切换至账号: \(accountLogTitle(for: session))")
                await loadCourses()
                if expectedSession == signSessionID, selectedTab == .groups {
                    await loadGroups()
                }
            } else {
                session.isPrimary = false
                if let idx = accounts.firstIndex(where: { $0.phoneNumber == session.phoneNumber }) {
                    invalidateSignWorkspace()
                    accounts[idx] = session
                } else {
                    accounts.append(session)
                }
                let expectedSession = signSessionID
                try await persistAccounts()
                guard expectedSession == signSessionID else { return }
                appendLog("已添加备用账号（保持当前播放不中断）: \(accountLogTitle(for: session))")
            }
        } catch {
            loginErrorMessage = error.localizedDescription
            appendLog("登录失败: \(error.localizedDescription)", isError: true)
        }
    }

    public func switchAccount(_ session: ChaoxingAccountSession) async {
        while let accountRemovalTask { await accountRemovalTask.value }
        guard session.phoneNumber != activePhoneNumber,
              accounts.contains(where: { $0.phoneNumber == session.phoneNumber }) else { return }
        invalidateSignWorkspace()
        accountChangeError = nil
        accountSwitchGeneration &+= 1
        let expectedGen = accountSwitchGeneration
        isSwitchingAccount = true
        defer {
            if accountSwitchGeneration == expectedGen {
                isSwitchingAccount = false
            }
        }

        // Persist current client's latest cookies before retiring it.
        syncActiveClientSessionInMemory()
        stopAllWatchActivity()
        client?.invalidate()

        for idx in accounts.indices {
            accounts[idx].isPrimary = (accounts[idx].phoneNumber == session.phoneNumber)
        }
        let targetSession = accounts.first(where: { $0.phoneNumber == session.phoneNumber }) ?? session
        activePhoneNumber = targetSession.phoneNumber
        client = makeClient(for: targetSession)
        resetWorkspaceCollections()

        // Show cached course list immediately for snappy switching, then refresh from server.
        if let cachedCourses = coursesCacheByPhone[targetSession.phoneNumber], !cachedCourses.isEmpty {
            courses = cachedCourses
        }
        do {
            try await persistAccounts()
        } catch {
            guard accountSwitchGeneration == expectedGen else { return }
            let message = "当前账号已切换，但未能保存：\(error.localizedDescription)"
            accountChangeError = message
            appendLog(message, isError: true)
        }
        guard accountSwitchGeneration == expectedGen else { return }
        appendLog("🔄 已切换账号: \(accountLogTitle(for: targetSession))")

        await loadCourses()
        guard accountSwitchGeneration == expectedGen else { return }
        if selectedTab == .groups {
            await loadGroups()
        }
    }

    public func switchToNextAccount() async {
        guard accounts.count > 1 else { return }
        let currentIdx = accounts.firstIndex(where: { $0.phoneNumber == activePhoneNumber }) ?? 0
        let nextIdx = (currentIdx + 1) % accounts.count
        await switchAccount(accounts[nextIdx])
    }

    public func switchToAccount(at index: Int) async {
        guard accounts.indices.contains(index) else { return }
        await switchAccount(accounts[index])
    }

    public func updateAccountRemark(phoneNumber: String, remark: String) async {
        while let accountRemovalTask { await accountRemovalTask.value }
        guard let idx = accounts.firstIndex(where: { $0.phoneNumber == phoneNumber }) else { return }
        let trimmed = remark.trimmingCharacters(in: .whitespacesAndNewlines)
        accounts[idx].tags = trimmed.isEmpty ? [] : [trimmed]
        if phoneNumber == activePhoneNumber {
            client?.updateTags(accounts[idx].tags)
        }
        let label = accountDisplayTitle(for: accounts[idx])
        try? await persistAccounts()
        appendLog("已更新账号备注: \(label)")
    }

    public func refreshAccountSession(_ session: ChaoxingAccountSession) async {
        guard accountRemovalTask == nil, !refreshingAccountPhones.contains(session.phoneNumber) else { return }
        let originalSessionID = signSessionID
        refreshingAccountPhones.insert(session.phoneNumber)
        defer { refreshingAccountPhones.remove(session.phoneNumber) }

        let tempClient = (session.phoneNumber == activePhoneNumber ? client : nil) ?? makeClient(for: session)
        let shouldInvalidateTemp = (tempClient !== client)
        defer {
            if shouldInvalidateTemp {
                tempClient.invalidate()
            }
        }

        do {
            var refreshed = try await ChaoxingAuthService.reLogin(client: tempClient)
            guard signSessionID == originalSessionID else { return }
            if let idx = accounts.firstIndex(where: { $0.phoneNumber == session.phoneNumber }) {
                refreshed.isPrimary = accounts[idx].isPrimary
                refreshed.tags = accounts[idx].tags
                accounts[idx] = refreshed
            }
            try? await persistAccounts()
            guard signSessionID == originalSessionID else { return }
            appendLog("🔄 账号会话刷新成功: \(accountDisplayTitle(for: refreshed))")
            if session.phoneNumber == activePhoneNumber {
                await loadCourses(forceRefresh: true)
            }
        } catch {
            guard signSessionID == originalSessionID else { return }
            appendLog("账号会话刷新失败 [\(accountDisplayTitle(for: session))]: \(error.localizedDescription)", isError: true)
        }
    }

    public func removeAccount(_ session: ChaoxingAccountSession) async {
        while let accountRemovalTask { await accountRemovalTask.value }
        let phone = session.phoneNumber
        guard accounts.contains(where: { $0.phoneNumber == phone }) else { return }
        invalidateSignWorkspace()
        let removingActive = activePhoneNumber == phone
        if removingActive { cancelRadarScan() }
        accountChangeError = nil
        let nextPhone = accounts.first(where: { $0.phoneNumber != phone })?.phoneNumber
        let removal = Task {
            defer { accountRemovalTask = nil }
            var remaining = accounts.filter { $0.phoneNumber != phone }
            if removingActive {
                for index in remaining.indices { remaining[index].isPrimary = index == 0 }
            }
            do {
                try await saveMacState(accounts: remaining)
            } catch {
                let message = "未能移除账号：\(error.localizedDescription)"
                accountChangeError = message
                appendLog(message, isError: true)
                return
            }

            accounts = remaining
            coursesCacheByPhone.removeValue(forKey: phone)
            if removingActive {
                accountSwitchGeneration &+= 1
                isSwitchingAccount = false
                stopAllWatchActivity()
                client?.invalidate()
                activePhoneNumber = nextPhone
                client = activeAccount.map(makeClient(for:))
                resetWorkspaceCollections()
                appendLog("已退出账号: \(accountLogTitle(for: session))")
            } else {
                appendLog("已移除备用账号: \(accountLogTitle(for: session))")
            }
        }
        accountRemovalTask = removal
        await removal.value
        guard removingActive, let nextPhone, activePhoneNumber == nextPhone,
              let next = activeAccount, !accounts.contains(where: { $0.phoneNumber == phone }) else { return }
        appendLog("自动切换至账号: \(accountLogTitle(for: next))")
        await loadCourses()
        if activePhoneNumber == nextPhone, selectedTab == .groups { await loadGroups() }
    }

    public func logoutCurrent() async {
        guard let current = activeAccount else { return }
        await removeAccount(current)
    }

    private func syncActiveClientSessionInMemory() {
        guard let client, let phone = activePhoneNumber,
              let idx = accounts.firstIndex(where: { $0.phoneNumber == phone }) else { return }
        var updated = client.session
        updated.isPrimary = accounts[idx].isPrimary
        updated.tags = accounts[idx].tags
        accounts[idx] = updated
    }

    public func shutdown() {
        invalidateSignWorkspace()
        stopAllWatchActivity()
        playerController.cleanup()
    }

    private func stopAllWatchActivity() {
        cancelRadarScan()
        courseSelectTask?.cancel()
        chapterSelectTask?.cancel()
        signActivitiesTask?.cancel()
        playerController.pause()
        stopPlayerHeartbeat()
        stopBatchAutoWatch()
        stopAutoWatch()
    }

    private func clearCaches() {
        chaptersCache.removeAll()
        chapterContentCache.removeAll()
    }

    private func resetWorkspaceCollections() {
        clearCaches()
        courses = []
        chapters = []
        videos = []
        liveTasks = []
        chapterAttachments = []
        selectedStudyCourse = nil
        selectedSignCourse = nil
        selectedChapter = nil
        selectedVideo = nil
        courseActivities = []
        pendingRadarActivities = []
        radarScanMessage = nil
        radarFailedCourses = 0
        signErrorMessage = nil
        isLoadingActivities = false
        isLoadingGroups = false
        groups = []
        selectedGroup = nil
        groupActivities = []
        imConfig = nil
    }

    // MARK: - Courses, Chapters & Content (Cached + Cancellable)

    public func loadCourses(forceRefresh: Bool = false) async {
        guard let client, let targetPhone = activePhoneNumber else { return }
        let expectedGen = accountSwitchGeneration
        if forceRefresh {
            clearCaches()
            coursesCacheByPhone.removeValue(forKey: targetPhone)
        }
        isLoadingCourses = true
        studyErrorMessage = nil
        defer {
            if accountSwitchGeneration == expectedGen {
                isLoadingCourses = false
            }
        }
        do {
            let fetched = try await ChaoxingCourseService.fetchCourses(client: client)
            while let accountRemovalTask { await accountRemovalTask.value }
            guard accountSwitchGeneration == expectedGen, activePhoneNumber == targetPhone else { return }
            courses = fetched
            coursesCacheByPhone[targetPhone] = fetched
            syncActiveClientSessionInMemory()
            try? await persistAccounts()
            guard accountSwitchGeneration == expectedGen, activePhoneNumber == targetPhone else { return }
            appendLog("已加载 \(courses.count) 门课程")
            if selectedStudyCourse == nil {
                let preferred = courses.first(where: { starredCourseIds.contains($0.courseId) }) ?? courses.first
                if let preferred {
                    selectStudyCourse(preferred)
                }
            }
        } catch {
            guard accountSwitchGeneration == expectedGen, activePhoneNumber == targetPhone else { return }
            studyErrorMessage = error.localizedDescription
            appendLog("获取课程失败: \(error.localizedDescription)", isError: true)
        }
    }

    public func selectStudyCourse(_ course: ChaoxingCourse, forceRefresh: Bool = false) {
        if !forceRefresh && selectedStudyCourse?.id == course.id && (isLoadingChapters || !chapters.isEmpty) {
            return
        }
        courseSelectTask?.cancel()
        chapterSelectTask?.cancel()
        selectedStudyCourse = course
        selectedChapter = nil
        selectedVideo = nil
        videos = []
        liveTasks = []
        chapterAttachments = []
        studyErrorMessage = nil

        if !forceRefresh, let cached = chaptersCache[course.id] {
            currentCpi = cached.cpi
            chapters = cached.chapters
            if let firstChapter = preferredInitialChapter(from: cached.chapters) {
                selectChapter(firstChapter)
            }
            return
        }

        chapters = []
        guard let client else { return }
        isLoadingChapters = true

        courseSelectTask = Task { [weak self] in
            guard let self else { return }
            defer {
                if self.selectedStudyCourse?.id == course.id {
                    self.isLoadingChapters = false
                }
            }
            do {
                let result = try await ChaoxingCourseStudyService.fetchCourseChapters(
                    client: client,
                    courseId: course.courseId,
                    classId: course.classId,
                    knownCpi: course.cpi ?? 0
                )
                try Task.checkCancellation()
                guard self.selectedStudyCourse?.id == course.id else { return }
                self.chaptersCache[course.id] = result
                self.currentCpi = result.cpi
                self.chapters = result.chapters
                if result.chapters.isEmpty {
                    self.appendLog("[\(course.courseName)] 暂无已发布章节")
                } else {
                    let pendingCnt = result.chapters.filter { ($0.pendingJobCount ?? 0) > 0 }.count
                    self.appendLog("[\(course.courseName)] 加载 \(result.chapters.count) 个章节（待完成 \(pendingCnt) 节，cpi=\(result.cpi)）")
                    if let firstChapter = self.preferredInitialChapter(from: result.chapters) {
                        self.selectChapter(firstChapter)
                    }
                }
            } catch is CancellationError {
                return
            } catch {
                guard self.selectedStudyCourse?.id == course.id else { return }
                self.studyErrorMessage = error.localizedDescription
                self.appendLog("加载章节失败: \(error.localizedDescription)", isError: true)
            }
        }
    }

    private func preferredInitialChapter(from list: [ChaoxingChapterNode]) -> ChaoxingChapterNode? {
        let quizKeywords = ["测验", "作业", "考试", "测试", "题库", "问卷", "结课报告"]
        func looksLikeQuiz(_ ch: ChaoxingChapterNode) -> Bool {
            quizKeywords.contains { ch.title.contains($0) }
        }
        // 1. Prefer first unfinished chapter that doesn't look like a quiz/exam
        if let pendingNonQuiz = list.first(where: { ($0.pendingJobCount ?? 0) > 0 && !looksLikeQuiz($0) }) {
            return pendingNonQuiz
        }
        // 2. Next prefer chapters with unknown status (e.g., `roundpointStudent review`) that don't look like quizzes
        if let unknownNonQuiz = list.first(where: { $0.pendingJobCount == nil && !looksLikeQuiz($0) }) {
            return unknownNonQuiz
        }
        // 3. Fallback to any pending chapter, or simply the first chapter
        return list.first(where: { ($0.pendingJobCount ?? 0) > 0 }) ?? list.first
    }

    public func activeCookiesForWebView() -> [ChaoxingStoredCookie] {
        if let live = client?.exportCookies(), !live.isEmpty {
            return live
        }
        return activeAccount?.cookies ?? []
    }

    public func chapterStudyURL(for chapter: ChaoxingChapterNode) -> URL? {
        let resolvedCpi = currentCpi > 0 ? currentCpi : (selectedStudyCourse?.cpi ?? 0)
        var comps = URLComponents(string: "https://mooc1.chaoxing.com/mycourse/studentstudy")
        var items: [URLQueryItem] = [
            URLQueryItem(name: "chapterId", value: chapter.id),
            URLQueryItem(name: "courseId", value: String(chapter.courseId)),
            URLQueryItem(name: "clazzid", value: String(chapter.classId)),
            URLQueryItem(name: "mooc2", value: "1")
        ]
        if resolvedCpi > 0 {
            items.append(URLQueryItem(name: "cpi", value: String(resolvedCpi)))
        }
        comps?.queryItems = items
        return comps?.url
    }

    public func refreshSelectedChapterAfterWebSession() {
        if let chapter = selectedChapter {
            let cacheKey = "\(chapter.courseId)_\(chapter.classId)_\(chapter.id)"
            chapterContentCache.removeValue(forKey: cacheKey)
            selectChapter(chapter, forceRefresh: true)
        }
    }

    public func selectChapter(_ chapter: ChaoxingChapterNode, forceRefresh: Bool = false) {
        if !forceRefresh && selectedChapter?.id == chapter.id && (isLoadingVideos || !videos.isEmpty || !liveTasks.isEmpty || !chapterAttachments.isEmpty) {
            return
        }
        chapterSelectTask?.cancel()
        selectedChapter = chapter
        selectedVideo = nil
        studyErrorMessage = nil

        let cacheKey = "\(chapter.courseId)_\(chapter.classId)_\(chapter.id)"
        if !forceRefresh, let cached = chapterContentCache[cacheKey] {
            videos = cached.videos
            liveTasks = cached.liveTasks
            chapterAttachments = cached.otherAttachments
            if let firstVideo = cached.videos.first(where: { !$0.isPassed && $0.isJob }) ?? cached.videos.first {
                selectVideo(firstVideo)
            }
            return
        }

        videos = []
        liveTasks = []
        chapterAttachments = []
        guard let client else { return }
        isLoadingVideos = true

        let resolvedCpi = currentCpi > 0 ? currentCpi : (selectedStudyCourse?.cpi ?? 0)
        chapterSelectTask = Task { [weak self] in
            guard let self else { return }
            defer {
                if self.selectedChapter?.id == chapter.id {
                    self.isLoadingVideos = false
                }
            }
            do {
                let fetched = try await ChaoxingCourseStudyService.fetchChapterContent(
                    client: client,
                    courseId: chapter.courseId,
                    classId: chapter.classId,
                    chapterId: chapter.id,
                    cpi: resolvedCpi
                )
                try Task.checkCancellation()
                guard self.selectedChapter?.id == chapter.id else { return }
                self.chapterContentCache[cacheKey] = fetched
                self.videos = fetched.videos
                self.liveTasks = fetched.liveTasks
                self.chapterAttachments = fetched.otherAttachments
                if fetched.videos.isEmpty && !fetched.liveTasks.isEmpty {
                    self.appendLog("[\(chapter.title)] 发现 \(fetched.liveTasks.count) 个直播回放任务，\(fetched.otherAttachments.count) 个其他任务")
                } else if fetched.videos.isEmpty && !fetched.otherAttachments.isEmpty {
                    let kinds = Set(fetched.otherAttachments.map(\.kind.displayName)).sorted().joined(separator: "/")
                    self.appendLog("[\(chapter.title)] 含 \(fetched.otherAttachments.count) 个非视频任务（\(kinds)）")
                } else {
                    let livePart = fetched.liveTasks.isEmpty ? "" : "，\(fetched.liveTasks.count) 个直播回放"
                    self.appendLog("[\(chapter.title)] 发现 \(fetched.videos.count) 个视频\(livePart)，\(fetched.otherAttachments.count) 个其他任务")
                }
                if let firstVideo = fetched.videos.first(where: { !$0.isPassed && $0.isJob }) ?? fetched.videos.first {
                    self.selectVideo(firstVideo)
                }
            } catch is CancellationError {
                return
            } catch {
                guard self.selectedChapter?.id == chapter.id else { return }
                self.studyErrorMessage = error.localizedDescription
                self.appendLog("加载章节内容失败: \(error.localizedDescription)", isError: true)
            }
        }
    }

    public func selectVideo(_ video: ChaoxingVideoTask, forceReload: Bool = false) {
        if !forceReload && selectedVideo?.id == video.id && playerController.player.currentItem != nil && playerController.playerErrorMessage == nil {
            return
        }
        stopPlayerHeartbeat()
        selectedVideo = video
        lastHeartbeatSummary = nil
        heartbeatSentCount = 0
        let resumeSec = video.headOffsetMs > 0 ? video.headOffsetMs / 1000 : video.effectiveClipStart
        playerController.load(
            urlString: video.videoPlayURL,
            fallbackDuration: video.totalDuration,
            resumeAtSeconds: resumeSec
        )
    }

    public func completeDocument(_ attachment: ChaoxingChapterAttachment) async {
        guard let client, attachment.canCompleteAsDocument else { return }
        guard !completingDocumentIds.contains(attachment.id) else { return }
        completingDocumentIds.insert(attachment.id)
        defer { completingDocumentIds.remove(attachment.id) }

        do {
            let ok = try await ChaoxingCourseStudyService.completeDocumentTask(client: client, task: attachment)
            if ok {
                markDocumentAsPassedLocally(attachment)
                appendLog("📄 文档任务点已完成 ✅: \(attachment.name)")
            } else {
                appendLog("📄 文档任务点未返回通过状态: \(attachment.name)", isError: true)
            }
        } catch {
            appendLog("📄 文档任务点上报失败 [\(attachment.name)]: \(error.localizedDescription)", isError: true)
        }
    }

    // MARK: - Power Assertion (Prevent macOS App Nap from Throttling Heartbeats)

    private func updatePowerAssertion() {
        let needsAssertion = isAutoWatching || isBatchWatching
        if needsAssertion && powerActivityToken == nil {
            powerActivityToken = ProcessInfo.processInfo.beginActivity(
                options: [.idleSystemSleepDisabled, .suddenTerminationDisabled, .automaticTerminationDisabled],
                reason: "Caochaoxing Course Video Heartbeat"
            )
        } else if !needsAssertion, let token = powerActivityToken {
            ProcessInfo.processInfo.endActivity(token)
            powerActivityToken = nil
        }
    }

    // MARK: - Single-Video Player Adaptive Heartbeat

    private func handlePlayerPlaybackEvent(_ event: ChaoxingVideoDragEvent, playingTimeSec: Int) async {
        guard let video = selectedVideo, let client else { return }
        if (isAutoWatching && autoWatchTaskId == video.id) || isBatchWatching { return }
        guard video.isReportableTask else { return }

        switch event {
        case .play:
            startPlayerHeartbeatLoop()
        case .pause, .ended:
            stopPlayerHeartbeat()
        case .drag, .playing:
            break
        }

        do {
            let speedLabel = playerController.currentSpeed.label
            let res = try await ChaoxingCourseStudyService.sendVideoLog(
                client: client,
                task: video,
                playingTimeSec: playingTimeSec,
                event: event
            )
            heartbeatSentCount += 1
            lastPlayerLogTime = Date()
            let statusText = "[\(speedLabel)] \(event.displayName) @ \(playingTimeSec)/\(video.totalDuration)s -> isPassed=\(res.isPassed)"
            lastHeartbeatSummary = statusText
            appendLog(statusText)

            if res.isPassed {
                await handleSingleVideoPassed(video)
            } else if event == .ended {
                appendLog("已播完并上报 ended(4)，服务端返回 isPassed=false（课程限速 \(Int(video.allowedMaxRate))x 或有效观看时长未达标，请使用同时看/合法挂机）", isError: true)
            }
        } catch {
            let msg = "播放器上报异常(\(event.displayName)): \(error.localizedDescription)"
            lastHeartbeatSummary = msg
            appendLog(msg, isError: true)
        }
    }

    private func startPlayerHeartbeatLoop() {
        stopPlayerHeartbeat()
        lastPlayerLogTime = Date()
        playerHeartbeatTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                if Task.isCancelled { return }
                guard self.playerController.isPlaying,
                      self.playerController.isReady,
                      !self.playerController.isBuffering,
                      let video = self.selectedVideo,
                      video.isReportableTask,
                      let client = self.client else {
                    self.playerHeartbeatCountdownSec = 0
                    continue
                }

                let targetInterval = self.playerHeartbeatIntervalSec
                let elapsed = Date().timeIntervalSince(self.lastPlayerLogTime)
                self.playerHeartbeatCountdownSec = max(0, targetInterval - elapsed)

                if elapsed >= targetInterval {
                    self.lastPlayerLogTime = Date()
                    let sec = Int(round(self.playerController.currentSeconds))
                    let speedLabel = self.playerController.currentSpeed.label
                    do {
                        let res = try await ChaoxingCourseStudyService.sendVideoLog(
                            client: client,
                            task: video,
                            playingTimeSec: sec,
                            event: .playing
                        )
                        self.heartbeatSentCount += 1
                        let text = "[\(speedLabel) · 间隔\(String(format: "%.1f", targetInterval))s] playing(0) @ \(sec)/\(video.totalDuration)s -> isPassed=\(res.isPassed)"
                        self.lastHeartbeatSummary = text
                        self.appendLog(text)
                        if res.isPassed {
                            await self.handleSingleVideoPassed(video)
                            return
                        }
                        if res.videoTimeLimit || res.hasJobLimit {
                            self.appendLog("触发服务端学习时长或任务点上限，已暂停播放", isError: true)
                            self.playerController.pause()
                            return
                        }
                    } catch {
                        let msg = "心跳上报失败: \(error.localizedDescription)"
                        self.lastHeartbeatSummary = msg
                        self.appendLog(msg, isError: true)
                    }
                }
            }
        }
    }

    private func stopPlayerHeartbeat() {
        playerHeartbeatTask?.cancel()
        playerHeartbeatTask = nil
        playerHeartbeatCountdownSec = 0
    }

    // MARK: - Single-Video Legal Auto-Watch

    public func startAutoWatch(for video: ChaoxingVideoTask) {
        guard video.isReportableTask else {
            appendLog("[\(video.name)] 非有效视频任务点（无 jobid 或 dtoken），已跳过", isError: true)
            return
        }
        if video.requiresFaceCapture {
            appendLog("[\(video.name)] 警告：课程启用了章节人脸抓拍，可能需要客户端配合人脸采集", isError: true)
        }
        stopPlayerHeartbeat()
        stopBatchAutoWatch()
        stopAutoWatch()
        guard let client else { return }

        isAutoWatching = true
        autoWatchTaskId = video.id
        updatePowerAssertion()
        // Sync single-video AVPlayer so the video visually plays along at the server-accepted speed.
        if selectedVideo?.id != video.id {
            selectVideo(video)
        }
        playerController.setSpeed(video.allowedMaxRate >= 2.0 ? .x2 : .x1)
        playerController.play()
        appendLog("开始合法挂机: \(video.name) · 速率 \(Int(video.allowedMaxRate))x · 间隔 \(max(15, video.reportTimeInterval))s · 区间 \(video.effectiveClipTime)")

        autoWatchRunner = Task { [weak self] in
            guard let self else { return }
            let outcome = await self.runLegalHeartbeatLoop(
                for: video,
                client: client,
                isReadyForStudy: { [weak self] in
                    guard let self else { return true }
                    return (self.playerController.isReady && !self.playerController.isBuffering)
                        || self.playerController.playerErrorMessage != nil
                        || (video.videoPlayURL?.isEmpty ?? true)
                },
                isCurrentlyBuffering: { [weak self] in
                    guard let self else { return false }
                    return self.playerController.isBuffering && self.playerController.playerErrorMessage == nil
                },
                onProgress: { displayed, reported, nextTick, status in
                    self.autoWatchDisplayedSec = displayed
                    self.autoWatchReportedSec = reported
                    self.autoWatchNextTickInSec = nextTick
                    self.lastHeartbeatSummary = status
                },
                onLoopRestart: { [weak self] startSec in
                    self?.playerController.seek(to: Double(startSec), emitLog: false)
                }
            )
            switch outcome {
            case .passed:
                await self.handleSingleVideoPassed(video)
            case .skipped, .rateLimited, .failed:
                self.stopAutoWatch()
            }
        }
    }

    public func stopAutoWatch() {
        let wasWatching = isAutoWatching
        autoWatchRunner?.cancel()
        autoWatchRunner = nil
        isAutoWatching = false
        autoWatchNextTickInSec = 0
        autoWatchTaskId = nil
        if wasWatching {
            playerController.pause()
        }
        updatePowerAssertion()
    }

    // MARK: - Streaming Simultaneous Watch (Fast Producer -> Immediate 3-Way Playback)

    /// Primary simultaneous watch entry point.
    /// Respects `watchAllCoursesAutomatically`:
    /// - `false` (default): watches ONLY the selected course.
    /// - `true`: watches the selected course first, then automatically continues across remaining courses.
    public func startSimultaneousWatch(preferredCourse: ChaoxingCourse? = nil) async {
        if courses.isEmpty {
            await loadCourses()
        }
        guard !courses.isEmpty else {
            appendLog("暂无课程可同时观看", isError: true)
            return
        }

        let primary = preferredCourse ?? selectedStudyCourse ?? courses.first
        if watchAllCoursesAutomatically {
            let orderedCourses = courses.sorted { lhs, rhs in
                if let primary {
                    if lhs.id == primary.id && rhs.id != primary.id { return true }
                    if rhs.id == primary.id && lhs.id != primary.id { return false }
                }
                let l = starredCourseIds.contains(lhs.courseId)
                let r = starredCourseIds.contains(rhs.courseId)
                if l != r { return l }
                return false
            }
            appendLog("🎬 启动跨课同时看（\(maxConcurrentWatches) 路流式同步播放 · 优先当前课，共 \(orderedCourses.count) 门课）")
            startBatchWatch(courses: orderedCourses, scopeLabel: "跨课全部 \(orderedCourses.count) 门课程")
        } else if let target = primary {
            appendLog("🎬 启动当前课程同时看（\(maxConcurrentWatches) 路流式同步播放 · 仅看 [\(target.courseName)]）")
            startBatchWatch(courses: [target], scopeLabel: target.courseName)
        }
    }

    public func startAllCoursesSimultaneousWatch() async {
        await startSimultaneousWatch()
    }

    public func startCourseBatchAutoWatch(for course: ChaoxingCourse) {
        if watchAllCoursesAutomatically {
            Task { await startSimultaneousWatch(preferredCourse: course) }
        } else {
            appendLog("🎬 启动当前课程同时看（\(maxConcurrentWatches) 路流式同步播放 · 仅看 [\(course.courseName)]）")
            startBatchWatch(courses: [course], scopeLabel: course.courseName)
        }
    }

    public func toggleBatchPlayerMute(taskId: String) {
        batchActiveWorkers.first(where: { $0.id == taskId })?.toggleMute()
    }

    public func skipBatchWorker(taskId: String) {
        if let task = batchWorkerTasks[taskId] {
            task.cancel()
        }
        if let worker = batchActiveWorkers.first(where: { $0.id == taskId }) {
            worker.stopAndCleanup()
            appendLog("⏭️ 已手动跳过: [\(worker.courseName)] \(worker.title)")
        }
    }

    private func cleanupAllBatchWorkers() {
        for worker in batchActiveWorkers {
            worker.stopAndCleanup()
        }
        batchActiveWorkers = []
    }

    /// Streams discovered unpassed videos immediately into up to `maxConcurrentWatches` simultaneous AVPlayers
    /// while continuing to scan remaining chapters in parallel (6-way scanner, skipping completed chapters).
    public func startBatchWatch(courses targetCourses: [ChaoxingCourse], scopeLabel: String) {
        playerController.pause()
        stopPlayerHeartbeat()
        stopAutoWatch()
        stopBatchAutoWatch()
        guard let client, !targetCourses.isEmpty else { return }

        isBatchWatching = true
        batchScopeLabel = scopeLabel
        batchCompletedCount = 0
        batchCompletedDocsCount = 0
        batchSkippedCount = 0
        batchTotalDiscoveredCount = 0
        batchQueueCount = 0
        batchCooldownRemainingSec = 0
        batchActiveWorkers = []
        batchScanProgress = "快速扫描 \(scopeLabel)..."
        updatePowerAssertion()

        batchRunner = Task { [weak self] in
            guard let self else { return }
            defer {
                self.cancelAllBatchWorkerTasks()
                self.cleanupAllBatchWorkers()
                self.batchScanProgress = nil
                self.batchCooldownRemainingSec = 0
                self.isBatchWatching = false
                self.batchQueueCount = 0
                self.updatePowerAssertion()
            }

            var pendingQueue: [PendingBatchItem] = []
            var seenItemIds: Set<String> = []
            var seenDocIds: Set<String> = []
            var scanningDone = false
            var failedCoursesCount = 0
            var effectiveConcurrency = max(1, self.maxConcurrentWatches)
            var cooldownUntil: Date? = nil

            // Background fast producer: scans chapters with 6-way bounded concurrency and skips chapters with `pendingJobCount == 0`.
            let producerTask = Task { [weak self] in
                guard let self else { return }
                for (cIdx, course) in targetCourses.enumerated() {
                    if Task.isCancelled { return }
                    self.batchScanProgress = "读取目录 \(cIdx + 1)/\(targetCourses.count): \(course.courseName)"
                    let fetched: (cpi: Int64, chapters: [ChaoxingChapterNode])
                    if let cached = self.chaptersCache[course.id] {
                        fetched = cached
                    } else {
                        do {
                            let fresh = try await ChaoxingCourseStudyService.fetchCourseChapters(
                                client: client,
                                courseId: course.courseId,
                                classId: course.classId,
                                knownCpi: course.cpi ?? 0
                            )
                            self.chaptersCache[course.id] = fresh
                            if self.selectedStudyCourse?.id == course.id {
                                self.currentCpi = fresh.cpi
                                self.chapters = fresh.chapters
                            }
                            fetched = fresh
                        } catch is CancellationError {
                            return
                        } catch {
                            failedCoursesCount += 1
                            self.appendLog("[\(course.courseName)] 读取章节失败: \(error.localizedDescription)", isError: true)
                            continue
                        }
                    }

                    if fetched.chapters.isEmpty {
                        continue
                    }

                    // Skip chapters explicitly confirmed as 0 pending (`pendingJobCount == 0`); keep `nil` (review/unknown) and `> 0`.
                    let candidateChapters = fetched.chapters.filter { ($0.pendingJobCount ?? 1) > 0 }
                    if candidateChapters.isEmpty {
                        self.appendLog("[\(course.courseName)] 所有章节均已完成，跳过扫描 ✅")
                        continue
                    }

                    let chunkSize = max(6, min(10, effectiveConcurrency))
                    var offset = 0
                    while offset < candidateChapters.count && !Task.isCancelled {
                        let end = min(candidateChapters.count, offset + chunkSize)
                        let slice = Array(candidateChapters[offset..<end])
                        self.batchScanProgress = "[\(course.courseName)] 高速扫描 \(end)/\(candidateChapters.count) 个待检查章节..."

                        let sliceContents = await withTaskGroup(of: (Int, ChaoxingChapterNode, ChaoxingChapterContent?).self) { group in
                            for (i, ch) in slice.enumerated() {
                                group.addTask {
                                    let content = try? await ChaoxingCourseStudyService.fetchChapterContent(
                                        client: client,
                                        courseId: ch.courseId,
                                        classId: ch.classId,
                                        chapterId: ch.id,
                                        cpi: fetched.cpi
                                    )
                                    return (i, ch, content)
                                }
                            }
                            var res: [(Int, ChaoxingChapterNode, ChaoxingChapterContent?)] = []
                            for await item in group { res.append(item) }
                            return res.sorted { $0.0 < $1.0 }
                        }

                        for (_, ch, contentOpt) in sliceContents {
                            guard let content = contentOpt else { continue }
                            let cacheKey = "\(ch.courseId)_\(ch.classId)_\(ch.id)"
                            self.chapterContentCache[cacheKey] = content
                            if self.selectedChapter?.id == ch.id {
                                self.videos = content.videos
                                self.liveTasks = content.liveTasks
                                self.chapterAttachments = content.otherAttachments
                            }

                            // 1. Enqueue playable videos/audios FIRST so multi-way playback starts with zero wait.
                            for v in content.videos {
                                guard v.isReportableTask, !v.isPassed, !seenItemIds.contains(v.id) else { continue }
                                if v.requiresFaceCapture {
                                    seenItemIds.insert(v.id)
                                    self.batchSkippedCount += 1
                                    self.appendLog("⚠️ 跳过人脸抓拍视频: [\(course.courseName)] \(v.name)", isError: true)
                                    continue
                                }
                                seenItemIds.insert(v.id)
                                pendingQueue.append(PendingBatchItem(courseName: course.courseName, chapterTitle: ch.title, payload: .video(v)))
                                self.batchTotalDiscoveredCount += 1
                                self.batchQueueCount = pendingQueue.count
                            }

                            // 2. Enqueue external `zhibo.chaoxing.com` live-replay task points!
                            for live in content.liveTasks {
                                guard live.isReportableTask, !live.isPassed, !seenItemIds.contains(live.id) else { continue }
                                seenItemIds.insert(live.id)
                                pendingQueue.append(PendingBatchItem(courseName: course.courseName, chapterTitle: ch.title, payload: .live(live)))
                                self.batchTotalDiscoveredCount += 1
                                self.batchQueueCount = pendingQueue.count
                            }

                            // 3. Optionally auto-complete PDF/DOC/PPT document task points discovered during scan
                            if self.autoCompleteDocuments {
                                for doc in content.otherAttachments where doc.canCompleteAsDocument && !seenDocIds.contains(doc.id) {
                                    seenDocIds.insert(doc.id)
                                    if let ok = try? await ChaoxingCourseStudyService.completeDocumentTask(client: client, task: doc), ok {
                                        self.batchCompletedDocsCount += 1
                                        self.markDocumentAsPassedLocally(doc)
                                        self.appendLog("📄 已自动完成文档: [\(course.courseName)] \(doc.name)")
                                    }
                                }
                            }
                        }
                        offset = end
                    }
                }
                scanningDone = true
                self.batchScanProgress = nil
            }

            defer { producerTask.cancel() }

            // Streaming scheduler loop: launches AVPlayer + heartbeat workers immediately as videos/live replays arrive!
            while !Task.isCancelled {
                if let until = cooldownUntil {
                    let rem = Int(ceil(until.timeIntervalSinceNow))
                    if rem > 0 {
                        self.batchCooldownRemainingSec = rem
                    } else {
                        cooldownUntil = nil
                        self.batchCooldownRemainingSec = 0
                    }
                } else {
                    self.batchCooldownRemainingSec = 0
                }
                let canLaunchNow = cooldownUntil == nil

                var launchedThisCycle = 0
                while canLaunchNow && self.batchActiveWorkers.count < effectiveConcurrency && !pendingQueue.isEmpty && !Task.isCancelled {
                    if launchedThisCycle > 0 {
                        // Gently stagger multi-stream launches (5-10 tiles) so CDN/TLS handshakes don't stall each other.
                        try? await Task.sleep(for: .milliseconds(350))
                        if Task.isCancelled || self.batchActiveWorkers.count >= effectiveConcurrency || pendingQueue.isEmpty {
                            break
                        }
                    }
                    launchedThisCycle += 1
                    let item = pendingQueue.removeFirst()
                    self.batchQueueCount = pendingQueue.count
                    let itemId = item.payload.id

                    let worker: MacBatchWatchWorker
                    switch item.payload {
                    case .video(let v):
                        worker = MacBatchWatchWorker(
                            courseName: item.courseName,
                            chapterTitle: item.chapterTitle,
                            video: v,
                            initialSec: self.initialReportPosition(for: v)
                        )
                    case .live(let live):
                        worker = MacBatchWatchWorker(
                            courseName: item.courseName,
                            chapterTitle: item.chapterTitle,
                            liveTask: live,
                            initialSec: live.watchedSeconds
                        )
                    }
                    self.batchActiveWorkers.append(worker)

                    let workerTask = Task<WorkerOutcome, Never> { [weak self] in
                        guard let self else { return .skipped }
                        let outcome: WorkerOutcome
                        switch item.payload {
                        case .video(let v):
                            outcome = await self.runLegalHeartbeatLoop(
                                for: v,
                                client: client,
                                isReadyForStudy: { worker.isPlaybackReadyForStudy },
                                isCurrentlyBuffering: { worker.isBuffering && worker.playerError == nil },
                                onProgress: { displayed, reported, nextTick, status in
                                    worker.displayedSec = displayed
                                    worker.reportedSec = reported
                                    worker.nextTickInSec = nextTick
                                    worker.statusText = status
                                },
                                onLoopRestart: { startSec in
                                    worker.restart(at: startSec)
                                }
                            )
                        case .live(let live):
                            outcome = await self.runLegalLiveReplayLoop(
                                for: live,
                                client: client,
                                isReadyForStudy: { worker.isPlaybackReadyForStudy },
                                isCurrentlyBuffering: { worker.isBuffering && worker.playerError == nil },
                                onProgress: { displayed, reported, nextTick, status in
                                    worker.displayedSec = displayed
                                    worker.reportedSec = reported
                                    worker.nextTickInSec = nextTick
                                    worker.statusText = status
                                },
                                onSessionReady: { duration, streamURLs in
                                    if duration > 0 {
                                        worker.duration = duration
                                    }
                                    worker.updateStreamURLs(streamURLs, startAtSec: worker.displayedSec)
                                }
                            )
                        }

                        self.batchWorkerTasks.removeValue(forKey: itemId)
                        worker.stopAndCleanup()
                        self.batchActiveWorkers.removeAll { $0.id == itemId }

                        switch outcome {
                        case .passed:
                            self.batchCompletedCount += 1
                            switch item.payload {
                            case .video(let v):
                                self.markVideoAsPassedLocally(v)
                                self.appendLog("✅ 视频已通过 (\(self.batchCompletedCount)/\(self.batchTotalDiscoveredCount)): [\(item.courseName)] \(v.name)")
                            case .live(let live):
                                self.markLiveAsPassedLocally(live)
                                self.appendLog("✅ 直播回放已通过 (\(self.batchCompletedCount)/\(self.batchTotalDiscoveredCount)): [\(item.courseName)] \(live.title)")
                            }
                        case .skipped:
                            self.batchSkippedCount += 1
                        case .rateLimited(let restSeconds):
                            if effectiveConcurrency > 1 {
                                effectiveConcurrency -= 1
                                self.appendLog("⚠️ 服务端 403 限流，自动降并发至 \(effectiveConcurrency) 路并将 [\(item.payload.displayName)] 放回队尾", isError: true)
                            }
                            let waitSec = min(600, max(60, restSeconds ?? 60))
                            cooldownUntil = Date().addingTimeInterval(TimeInterval(waitSec))
                            self.batchCooldownRemainingSec = waitSec
                            self.appendLog("⚠️ 冷却 \(waitSec) 秒后继续补位队列", isError: true)
                            pendingQueue.append(item)
                            self.batchQueueCount = pendingQueue.count
                        case .failed(let reason):
                            self.batchSkippedCount += 1
                            self.appendLog("❌ 跳过异常任务 [\(item.payload.displayName)]: \(reason)", isError: true)
                        }
                        return outcome
                    }
                    self.batchWorkerTasks[itemId] = workerTask
                }

                if scanningDone && pendingQueue.isEmpty && self.batchActiveWorkers.isEmpty && self.batchWorkerTasks.isEmpty {
                    break
                }

                try? await Task.sleep(for: .milliseconds(250))
            }

            if !Task.isCancelled {
                if self.batchTotalDiscoveredCount == 0 && self.batchCompletedDocsCount == 0 {
                    if failedCoursesCount > 0 {
                        self.appendLog("⚠️ [\(scopeLabel)] 扫描结束，有 \(failedCoursesCount) 门课程目录读取失败，请刷新或重新登录", isError: true)
                    } else {
                        self.appendLog("[\(scopeLabel)] 未发现待完成的视频、直播回放或文档任务点（全部已通过或仅剩测验 ✅）")
                    }
                } else {
                    let docPart = self.batchCompletedDocsCount > 0 ? "，自动完成文档 \(self.batchCompletedDocsCount)" : ""
                    self.appendLog("🎉 [\(scopeLabel)] 同步看课全部结束：视频/直播通过 \(self.batchCompletedCount)\(docPart)，跳过/异常 \(self.batchSkippedCount)")
                }
            }
        }
    }

    /// Starts legal wall-clock 30s heartbeat watching for a single `zhibo.chaoxing.com` live replay task.
    public func startSingleLiveWatch(_ live: ChaoxingLiveTask) {
        guard let client else { return }
        guard live.isReportableTask else {
            appendLog("⚠️ 直播回放 [\(live.title)] 当前不可上报（\(live.statusDescription)）", isError: true)
            return
        }
        playerController.pause()
        stopPlayerHeartbeat()
        stopAutoWatch()
        stopBatchAutoWatch()

        let courseName = selectedStudyCourse?.courseName ?? "当前课程"
        let chapterTitle = selectedChapter?.title ?? "直播章节"
        isBatchWatching = true
        batchScopeLabel = "单场直播回放：\(live.title)"
        batchCompletedCount = 0
        batchCompletedDocsCount = 0
        batchSkippedCount = 0
        batchTotalDiscoveredCount = 1
        batchQueueCount = 0
        batchCooldownRemainingSec = 0
        batchScanProgress = nil
        updatePowerAssertion()

        let worker = MacBatchWatchWorker(
            courseName: courseName,
            chapterTitle: chapterTitle,
            liveTask: live,
            initialSec: live.watchedSeconds
        )
        batchActiveWorkers = [worker]
        appendLog("📺 开始合法挂机直播回放：\(live.title)（zhibo.chaoxing.com 每 30s 心跳上报）")

        batchRunner = Task { [weak self] in
            guard let self else { return }
            defer {
                self.cancelAllBatchWorkerTasks()
                self.cleanupAllBatchWorkers()
                self.isBatchWatching = false
                self.batchCooldownRemainingSec = 0
                self.updatePowerAssertion()
            }
            let outcome = await self.runLegalLiveReplayLoop(
                for: live,
                client: client,
                isReadyForStudy: { worker.isPlaybackReadyForStudy },
                isCurrentlyBuffering: { worker.isBuffering && worker.playerError == nil },
                onProgress: { displayed, reported, nextTick, status in
                    worker.displayedSec = displayed
                    worker.reportedSec = reported
                    worker.nextTickInSec = nextTick
                    worker.statusText = status
                },
                onSessionReady: { duration, streamURLs in
                    if duration > 0 { worker.duration = duration }
                    worker.updateStreamURLs(streamURLs, startAtSec: worker.displayedSec)
                }
            )
            switch outcome {
            case .passed:
                self.batchCompletedCount = 1
                self.markLiveAsPassedLocally(live)
                self.appendLog("✅ 直播回放已通过：\(live.title)")
            case .skipped:
                self.batchSkippedCount = 1
            case .rateLimited:
                self.batchSkippedCount = 1
                self.appendLog("⚠️ 直播回放触发 403 限流，请稍后再试：\(live.title)", isError: true)
            case .failed(let reason):
                self.batchSkippedCount = 1
                self.appendLog("❌ 直播回放挂机失败 [\(live.title)]: \(reason)", isError: true)
            }
        }
    }

    /// Suspends auto-watch heartbeat startup until the player finishes loading & initial buffering
    /// (or permanently fails / has no stream URL). Returns `false` if cancelled while waiting.
    func waitUntilPlaybackReady(
        title: String,
        isReady: @MainActor () -> Bool,
        onWaiting: @MainActor (Int) -> Void
    ) async -> Bool {
        if isReady() {
            return true
        }
        let startedAt = Date()
        while !Task.isCancelled {
            if isReady() {
                let waited = max(1, Int(round(Date().timeIntervalSince(startedAt))))
                appendLog("[\(title)] 加载与缓冲完成（用时 \(waited)s），正式开始自动刷课")
                return true
            }
            let elapsed = Int(floor(Date().timeIntervalSince(startedAt)))
            onWaiting(elapsed)
            do {
                try await Task.sleep(for: .milliseconds(200))
            } catch {
                return false
            }
        }
        return false
    }

    /// Wall-clock-accurate 30-second heartbeat loop for `zhibo.chaoxing.com` live replays.
    /// Follows Chaoxing's official `setFunction()` (`/saveTimePc` at `isStart=0`, then `isStart=1` at 29s and every 30s
    /// plus `/apis/live/put/watchMoment`) and periodically syncs real persisted server progress via `/ananas/live/liveinfo`.
    private func runLegalLiveReplayLoop(
        for initialLive: ChaoxingLiveTask,
        client: ChaoxingHTTPClient,
        isReadyForStudy: @MainActor () -> Bool = { true },
        isCurrentlyBuffering: @MainActor () -> Bool = { false },
        onProgress: @MainActor (Int, Int, Int, String) -> Void,
        onSessionReady: @MainActor (Int, [String]) -> Void
    ) async -> WorkerOutcome {
        onProgress(initialLive.watchedSeconds, initialLive.watchedSeconds, 0, "关联课程并初始化直播回放会话...")
        var session: ChaoxingLiveSessionContext
        do {
            session = try await ChaoxingCourseStudyService.prepareLiveReplaySession(client: client, task: initialLive)
        } catch is CancellationError {
            return .skipped
        } catch {
            return .failed("初始化直播回放失败: \(error.localizedDescription)")
        }

        let refreshedInfo = try? await ChaoxingCourseStudyService.fetchLiveInfo(client: client, task: initialLive)
        var targetDuration = max(refreshedInfo?.duration ?? 0, session.duration, initialLive.duration)
        var serverConfirmedSec = max(initialLive.watchedSeconds, (refreshedInfo?.playbackTimelongMs ?? 0) / 1000)
        var estimatedSec = serverConfirmedSec
        var serverPercent = max(initialLive.percentValue, refreshedInfo?.percentValue ?? 0)

        var candidateURLs: [String] = []
        for raw in [refreshedInfo?.m3u8URL, session.playURL, refreshedInfo?.mp4URL, initialLive.playURL, initialLive.fallbackPlayURL] {
            if let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
               !trimmed.isEmpty,
               !candidateURLs.contains(trimmed) {
                candidateURLs.append(trimmed)
            }
        }
        onSessionReady(max(1, targetDuration), candidateURLs)

        let thresholdPercent = initialLive.rt * 100.0
        if targetDuration > 0 && serverPercent >= thresholdPercent {
            if let checked = try? await ChaoxingCourseStudyService.fetchChapterContent(
                client: client,
                courseId: initialLive.courseId,
                classId: initialLive.classId,
                chapterId: initialLive.chapterId,
                cpi: initialLive.cpi
            ), checked.liveTasks.first(where: { $0.id == initialLive.id })?.isPassed == true {
                return .passed
            }
        }

        // Wait until the video stream finishes loading & initial buffering before starting auto-watch heartbeats.
        let ready = await waitUntilPlaybackReady(
            title: initialLive.title,
            isReady: isReadyForStudy,
            onWaiting: { waitedSec in
                onProgress(estimatedSec, serverConfirmedSec, 0, "等待回放缓冲完成 (\(waitedSec)s)...")
            }
        )
        guard ready, !Task.isCancelled else { return .skipped }

        // Initial `isStart=0` heartbeat (exact official `watchTime == 1` call).
        onProgress(estimatedSec, serverConfirmedSec, 29, "发送直播起播心跳 (isStart=0)...")
        do {
            let startRes = try await ChaoxingCourseStudyService.sendLiveHeartbeat(
                client: client,
                task: initialLive,
                session: session,
                isStart: true,
                watchMomentSec: estimatedSec
            )
            if startRes.duration > targetDuration {
                targetDuration = startRes.duration
                onSessionReady(max(1, targetDuration), candidateURLs)
            }
            appendLog("📺 [\(initialLive.title)] 起播心跳已接收 (isStart=0，已入库 \(String(format: "%.1f%%", serverPercent)))")
        } catch is CancellationError {
            return .skipped
        } catch ChaoxingError.networkError(let code, _) where code == 403 {
            return .rateLimited(restSeconds: 60)
        } catch {
            return .failed("起播心跳失败: \(error.localizedDescription)")
        }

        var tickCount = 0
        var postThresholdTicks = 0
        var consecutiveErrors = 0
        var ticksSinceServerAdvance = 0
        var sessionRecoveryAttempts = 0
        let maxTicks = max(240, targetDuration > 0 ? (targetDuration / 30) + 30 : 240)

        while !Task.isCancelled && tickCount < maxTicks {
            // Exact official cadence: watchTime == 1 (isStart=0) -> 30 (+29s) -> 60 (+30s) -> 90 (+30s)...
            let stepSec = tickCount == 0 ? 29 : 30
            var activeElapsed: Double = 0
            var lastTickAt = Date()
            while !Task.isCancelled {
                let now = Date()
                let delta = max(0, min(2.0, now.timeIntervalSince(lastTickAt)))
                lastTickAt = now
                let buffering = isCurrentlyBuffering()
                if !buffering {
                    activeElapsed += delta
                }
                let remain = Int(ceil(Double(stepSec) - activeElapsed))
                if remain <= 0 { break }
                let visualSec = targetDuration > 0
                    ? min(targetDuration, estimatedSec + Int(floor(activeElapsed)))
                    : (estimatedSec + Int(floor(activeElapsed)))
                if buffering {
                    onProgress(visualSec, serverConfirmedSec, remain, "缓冲中，暂停计时...")
                } else {
                    let pctText = serverPercent > 0
                        ? " · 服务端入库 \(String(format: "%.1f%%", serverPercent))"
                        : " · 每60s入库同步"
                    onProgress(visualSec, serverConfirmedSec, remain, "直播回放合法同步中\(pctText)")
                }
                do {
                    try await Task.sleep(for: .milliseconds(500))
                } catch {
                    return .skipped
                }
            }
            if Task.isCancelled { return .skipped }

            let candidateMoment = targetDuration > 0 ? min(targetDuration, estimatedSec + stepSec) : (estimatedSec + stepSec)
            onProgress(candidateMoment, serverConfirmedSec, 0, "上报直播心跳 (isStart=1)...")
            do {
                let hbRes = try await ChaoxingCourseStudyService.sendLiveHeartbeat(
                    client: client,
                    task: initialLive,
                    session: session,
                    isStart: false,
                    watchMomentSec: candidateMoment
                )
                if hbRes.duration > targetDuration {
                    targetDuration = hbRes.duration
                    onSessionReady(max(1, targetDuration), candidateURLs)
                }
                consecutiveErrors = 0
                tickCount += 1
                ticksSinceServerAdvance += 1
                estimatedSec = max(serverConfirmedSec, candidateMoment)
            } catch is CancellationError {
                return .skipped
            } catch ChaoxingError.networkError(let code, _) where code == 403 {
                return .rateLimited(restSeconds: 60)
            } catch {
                consecutiveErrors += 1
                appendLog("⚠️ [\(initialLive.title)] 直播心跳重试 (\(consecutiveErrors)/4): \(error.localizedDescription)", isError: true)
                if consecutiveErrors >= 4 {
                    return .failed("连续心跳失败: \(error.localizedDescription)")
                }
                onProgress(estimatedSec, serverConfirmedSec, 5, "心跳波动，稍后重试...")
                try? await Task.sleep(for: .seconds(5))
                continue
            }

            let requiredSec = targetDuration > 0 ? Int(ceil(Double(targetDuration) * initialLive.rt)) : 0
            // Poll `/ananas/live/liveinfo` every 60s (matching Chaoxing's 60s server DB flush cycle) or near completion.
            let nearCompletion = requiredSec > 0 && estimatedSec >= requiredSec
            if tickCount % 2 == 0 || nearCompletion {
                if let info = try? await ChaoxingCourseStudyService.fetchLiveInfo(client: client, task: initialLive) {
                    if info.duration > targetDuration {
                        targetDuration = info.duration
                        onSessionReady(max(1, targetDuration), candidateURLs)
                    }
                    let serverWatched = info.playbackTimelongMs / 1000
                    let previousConfirmed = serverConfirmedSec
                    serverPercent = info.percentValue
                    if serverWatched > serverConfirmedSec {
                        serverConfirmedSec = serverWatched
                        estimatedSec = max(estimatedSec, serverConfirmedSec)
                        ticksSinceServerAdvance = 0
                        let m = serverConfirmedSec / 60
                        let s = serverConfirmedSec % 60
                        appendLog("📺 [\(initialLive.title)] 服务端已入库 \(String(format: "%02d:%02d", m, s)) (\(String(format: "%.1f%%", serverPercent)))")
                    } else if serverWatched > 0 && previousConfirmed == 0 {
                        serverConfirmedSec = serverWatched
                        estimatedSec = max(estimatedSec, serverConfirmedSec)
                        ticksSinceServerAdvance = 0
                    }

                    onProgress(
                        estimatedSec,
                        serverConfirmedSec,
                        30,
                        "直播回放 · 服务端已入库 \(String(format: "%.1f%%", serverPercent))"
                    )
                    let refreshedRequired = targetDuration > 0 ? Int(ceil(Double(targetDuration) * initialLive.rt)) : 0
                    let reachedTarget = serverPercent >= thresholdPercent || (refreshedRequired > 0 && serverConfirmedSec >= refreshedRequired)
                    if reachedTarget {
                        postThresholdTicks += 1
                        if let refreshedChapter = try? await ChaoxingCourseStudyService.fetchChapterContent(
                            client: client,
                            courseId: initialLive.courseId,
                            classId: initialLive.classId,
                            chapterId: initialLive.chapterId,
                            cpi: initialLive.cpi
                        ), refreshedChapter.liveTasks.first(where: { $0.id == initialLive.id })?.isPassed == true {
                            return .passed
                        }
                        if postThresholdTicks >= 2 {
                            return .passed
                        }
                    } else if ticksSinceServerAdvance >= 6 && sessionRecoveryAttempts < 2 {
                        // Self-heal if 3 minutes of accepted heartbeats haven't flushed into `liveinfo`.
                        sessionRecoveryAttempts += 1
                        ticksSinceServerAdvance = 0
                        appendLog("⚠️ [\(initialLive.title)] 服务端入库时长 3 分钟未刷新，正在重新建立直播关联并重发 isStart=0...", isError: true)
                        if let refreshedSession = try? await ChaoxingCourseStudyService.prepareLiveReplaySession(client: client, task: initialLive) {
                            session = refreshedSession
                            _ = try? await ChaoxingCourseStudyService.sendLiveHeartbeat(
                                client: client,
                                task: initialLive,
                                session: session,
                                isStart: true,
                                watchMomentSec: estimatedSec
                            )
                        }
                    }
                }
            }
        }

        return Task.isCancelled ? .skipped : .failed("达到最大挂机时长仍未收到服务端通过确认")
    }

    public func stopBatchAutoWatch() {
        batchRunner?.cancel()
        batchRunner = nil
        cancelAllBatchWorkerTasks()
        cleanupAllBatchWorkers()
        isBatchWatching = false
        batchScanProgress = nil
        batchCooldownRemainingSec = 0
        batchQueueCount = 0
        updatePowerAssertion()
    }

    private func cancelAllBatchWorkerTasks() {
        for (_, task) in batchWorkerTasks {
            task.cancel()
        }
        batchWorkerTasks.removeAll()
    }

    private func initialReportPosition(for video: ChaoxingVideoTask) -> Int {
        let start = video.effectiveClipStart
        let end = video.effectiveClipEnd
        let headSec = video.headOffsetMs / 1000
        if !video.isPassed && headSec > start && headSec < max(start + 1, end - 3) {
            return headSec
        }
        return start
    }

    /// Wall-clock-accurate single-video legal heartbeat loop with loop limit (`maxPasses = 2`) and automatic recovery.
    private func runLegalHeartbeatLoop(
        for initialVideo: ChaoxingVideoTask,
        client: ChaoxingHTTPClient,
        isReadyForStudy: @MainActor () -> Bool = { true },
        isCurrentlyBuffering: @MainActor () -> Bool = { false },
        onProgress: @MainActor (Int, Int, Int, String) -> Void,
        onLoopRestart: @MainActor (Int) -> Void
    ) async -> WorkerOutcome {
        let video = initialVideo
        let clipStart = video.effectiveClipStart
        let clipEnd = video.effectiveClipEnd
        guard clipEnd > clipStart else {
            return .failed("视频时长为 0")
        }

        var reportedSec = initialReportPosition(for: video)
        let intervalSec = max(15, video.reportTimeInterval)
        let legalRate = video.allowedMaxRate
        var completedPasses = 0
        let maxPasses = 2
        var consecutiveErrors = 0

        // Wait until the player finishes loading & initial buffering before sending `play(3)` and starting auto-watch.
        let ready = await waitUntilPlaybackReady(
            title: video.name,
            isReady: isReadyForStudy,
            onWaiting: { waitedSec in
                onProgress(reportedSec, reportedSec, 0, "等待缓冲完成 (\(waitedSec)s)...")
            }
        )
        guard ready, !Task.isCancelled else { return .skipped }

        onProgress(reportedSec, reportedSec, 0, "发送 play(3)...")
        do {
            let startRes = try await ChaoxingCourseStudyService.sendVideoLog(
                client: client,
                task: video,
                playingTimeSec: reportedSec,
                event: .play
            )
            appendLog("[\(video.name)] play(3) @ \(reportedSec)s -> isPassed=\(startRes.isPassed)")
            if startRes.isPassed { return .passed }
        } catch is CancellationError {
            return .skipped
        } catch ChaoxingError.networkError(let code, let msg) where code == 403 {
            return .rateLimited(restSeconds: extractRestSeconds(from: msg))
        } catch {
            return .failed(error.localizedDescription)
        }

        while !Task.isCancelled {
            let remainingVideoSec = max(1, clipEnd - reportedSec)
            let stepRealSec = min(intervalSec, max(1, Int(ceil(Double(remainingVideoSec) / legalRate))))
            var activeElapsed: Double = 0
            var lastTickAt = Date()

            while !Task.isCancelled {
                let now = Date()
                let delta = max(0, min(2.0, now.timeIntervalSince(lastTickAt)))
                lastTickAt = now
                let buffering = isCurrentlyBuffering()
                if !buffering {
                    activeElapsed += delta
                }
                let remain = Int(ceil(Double(stepRealSec) - activeElapsed))
                let smoothPos = min(clipEnd, reportedSec + Int(floor(activeElapsed * legalRate)))
                if remain <= 0 { break }
                if buffering {
                    onProgress(smoothPos, reportedSec, remain, "缓冲中，暂停计时...")
                } else {
                    onProgress(smoothPos, reportedSec, remain, "运行中 (\(Int(legalRate))x) · \(smoothPos)/\(clipEnd)s")
                }
                do {
                    try await Task.sleep(for: .milliseconds(500))
                } catch {
                    return .skipped
                }
            }
            if Task.isCancelled { return .skipped }

            let advance = max(1, Int(floor(activeElapsed * legalRate)))
            let nextReportedSec = min(clipEnd, reportedSec + advance)
            let event: ChaoxingVideoDragEvent = nextReportedSec >= clipEnd ? .ended : .playing

            do {
                let res = try await ChaoxingCourseStudyService.sendVideoLog(
                    client: client,
                    task: video,
                    playingTimeSec: nextReportedSec,
                    event: event
                )
                consecutiveErrors = 0
                reportedSec = nextReportedSec
                appendLog("[\(video.name)] \(event.displayName) @ \(reportedSec)/\(clipEnd)s -> isPassed=\(res.isPassed)")
                onProgress(reportedSec, reportedSec, 0, "\(event.displayName) @ \(reportedSec)/\(clipEnd)s")

                if res.isPassed {
                    return .passed
                }
                if res.videoTimeLimit || res.hasJobLimit {
                    appendLog("[\(video.name)] 触发服务端时长或任务点限制", isError: true)
                    return .rateLimited(restSeconds: 300)
                }

                if reportedSec >= clipEnd {
                    completedPasses += 1
                    if completedPasses >= maxPasses {
                        return .failed("完整播放 \(maxPasses) 轮后服务端仍未通过（可能需人脸认证或章节测验）")
                    }
                    reportedSec = clipStart
                    onLoopRestart(clipStart)
                    appendLog("[\(video.name)] 第 \(completedPasses) 轮到片尾但有效时长未达 rt=\(Int(video.rt * 100))%，自动重头续播...")
                    _ = try? await ChaoxingCourseStudyService.sendVideoLog(
                        client: client,
                        task: video,
                        playingTimeSec: clipStart,
                        event: .play
                    )
                }
            } catch is CancellationError {
                return .skipped
            } catch ChaoxingError.networkError(let code, let msg) where code == 403 {
                return .rateLimited(restSeconds: extractRestSeconds(from: msg))
            } catch {
                consecutiveErrors += 1
                appendLog("[\(video.name)] 心跳重试 (\(consecutiveErrors)/3): \(error.localizedDescription)", isError: true)
                if consecutiveErrors >= 3 {
                    return .failed(error.localizedDescription)
                }
                try? await Task.sleep(for: .seconds(5))
            }
        }
        return .skipped
    }

    private func extractRestSeconds(from message: String) -> Int? {
        guard let regex = try? NSRegularExpression(pattern: #"\[restTime:(\d+)\]"#),
              let match = regex.firstMatch(in: message, range: NSRange(location: 0, length: (message as NSString).length)),
              match.numberOfRanges > 1 else {
            return nil
        }
        return Int((message as NSString).substring(with: match.range(at: 1)))
    }

    private func decrementChapterPendingCount(courseId: Int64, classId: Int, chapterId: String) {
        let courseKey = "\(courseId)_\(classId)"
        if var cEntry = chaptersCache[courseKey],
           let idx = cEntry.chapters.firstIndex(where: { $0.id == chapterId }),
           let curPending = cEntry.chapters[idx].pendingJobCount, curPending > 0 {
            let old = cEntry.chapters[idx]
            cEntry.chapters[idx] = ChaoxingChapterNode(
                id: old.id,
                title: old.title,
                sectionTitle: old.sectionTitle,
                courseId: old.courseId,
                classId: old.classId,
                pendingJobCount: curPending - 1
            )
            chaptersCache[courseKey] = cEntry
            if selectedStudyCourse?.id == courseKey {
                chapters = cEntry.chapters
            }
        } else if selectedStudyCourse?.id == courseKey,
                  let idx = chapters.firstIndex(where: { $0.id == chapterId }),
                  let curPending = chapters[idx].pendingJobCount, curPending > 0 {
            let old = chapters[idx]
            chapters[idx] = ChaoxingChapterNode(
                id: old.id,
                title: old.title,
                sectionTitle: old.sectionTitle,
                courseId: old.courseId,
                classId: old.classId,
                pendingJobCount: curPending - 1
            )
        }
    }

    private func markVideoAsPassedLocally(_ video: ChaoxingVideoTask) {
        let updated = video.updating(isPassed: true, headOffsetMs: video.effectiveClipEnd * 1000)
        if let idx = videos.firstIndex(where: { $0.id == video.id }) {
            videos[idx] = updated
        }
        if selectedVideo?.id == video.id {
            selectedVideo = updated
        }
        let cacheKey = "\(video.courseId)_\(video.classId)_\(video.chapterId)"
        if let cached = chapterContentCache[cacheKey] {
            var newVideos = cached.videos
            if let idx = newVideos.firstIndex(where: { $0.id == video.id }) {
                newVideos[idx] = updated
                chapterContentCache[cacheKey] = ChaoxingChapterContent(
                    videos: newVideos,
                    liveTasks: cached.liveTasks,
                    otherAttachments: cached.otherAttachments
                )
            }
        }
        decrementChapterPendingCount(courseId: video.courseId, classId: video.classId, chapterId: video.chapterId)
    }

    private func markLiveAsPassedLocally(_ live: ChaoxingLiveTask) {
        let updated = live.updating(
            isPassed: true,
            playbackTimelongMs: max(live.playbackTimelongMs, live.duration * 1000),
            percentValue: max(live.percentValue, 100.0)
        )
        if let idx = liveTasks.firstIndex(where: { $0.id == live.id }) {
            liveTasks[idx] = updated
        }
        let cacheKey = "\(live.courseId)_\(live.classId)_\(live.chapterId)"
        if let cached = chapterContentCache[cacheKey] {
            var newLives = cached.liveTasks
            if let idx = newLives.firstIndex(where: { $0.id == live.id }) {
                newLives[idx] = updated
                chapterContentCache[cacheKey] = ChaoxingChapterContent(
                    videos: cached.videos,
                    liveTasks: newLives,
                    otherAttachments: cached.otherAttachments
                )
            }
        }
        decrementChapterPendingCount(courseId: live.courseId, classId: live.classId, chapterId: live.chapterId)
    }

    private func markDocumentAsPassedLocally(_ doc: ChaoxingChapterAttachment) {
        let updated = doc.updating(isPassed: true)
        if let idx = chapterAttachments.firstIndex(where: { $0.id == doc.id }) {
            chapterAttachments[idx] = updated
        }
        let cacheKey = "\(doc.courseId)_\(doc.classId)_\(doc.chapterId)"
        if let cached = chapterContentCache[cacheKey] {
            var newOthers = cached.otherAttachments
            if let idx = newOthers.firstIndex(where: { $0.id == doc.id }) {
                newOthers[idx] = updated
                chapterContentCache[cacheKey] = ChaoxingChapterContent(
                    videos: cached.videos,
                    liveTasks: cached.liveTasks,
                    otherAttachments: newOthers
                )
            }
        }
        decrementChapterPendingCount(courseId: doc.courseId, classId: doc.classId, chapterId: doc.chapterId)
    }

    private func handleSingleVideoPassed(_ video: ChaoxingVideoTask) async {
        stopAutoWatch()
        markVideoAsPassedLocally(video)
        appendLog("任务点已通过 ✅: \(video.name)")

        guard autoAdvanceNextChapter else { return }
        if let nextVideoInChapter = videos.first(where: { !$0.isPassed && $0.isReportableTask && $0.id != video.id }) {
            selectVideo(nextVideoInChapter)
            startAutoWatch(for: nextVideoInChapter)
            return
        }
        if let curChapterIdx = chapters.firstIndex(where: { $0.id == video.chapterId }) {
            for nextIdx in (curChapterIdx + 1)..<chapters.count {
                let nextChapter = chapters[nextIdx]
                if nextChapter.pendingJobCount == 0 { continue }
                selectChapter(nextChapter)
                await chapterSelectTask?.value
                if let pending = videos.first(where: { !$0.isPassed && $0.isReportableTask }) {
                    selectVideo(pending)
                    startAutoWatch(for: pending)
                    return
                }
            }
        }
        appendLog("当前课程暂无更多未完成视频任务点")
    }

    private func persistAccounts() async throws {
        while let accountRemovalTask { await accountRemovalTask.value }
        try await saveMacState(accounts: accounts)
    }

    private func saveMacState(accounts: [ChaoxingAccountSession]) async throws {
        let snapshot = ChaoxingAppStateSnapshot(
            accounts: accounts, locationPresets: locationPresets, enableLocationJitter: enableLocationJitter,
            batchSignDelayMs: batchSignDelayMs, signHistory: signHistory,
            courseLocationBindings: courseLocationBindings, starredCourseIds: Array(starredCourseIds)
        )
        let previous = pendingSave
        let save = Task { [store] in
            // A later change must also be able to retry after a failed write.
            _ = try? await previous?.value
            try await store.saveMacState(snapshot)
        }
        pendingSave = save
        try await save.value
    }

    // MARK: - Sign-in, Radar & Group Activities (Isolated from Course Study)

    public func toggleStarCourse(_ courseId: Int64) async {
        if starredCourseIds.contains(courseId) {
            starredCourseIds.remove(courseId)
        } else {
            starredCourseIds.insert(courseId)
        }
        try? await persistAccounts()
    }

    public func boundLocation(for courseId: Int64) -> ChaoxingLocationPreset? {
        courseLocationBindings[String(courseId)]
    }

    public func bindLocation(_ preset: ChaoxingLocationPreset, toCourseId courseId: Int64) async {
        guard courseId > 0 else { return }
        courseLocationBindings[String(courseId)] = preset
        try? await persistAccounts()
    }

    public func fetchSignDetail(for activity: ChaoxingSignActivity) async -> ChaoxingSignDetailMetadata? {
        guard let client else { return nil }
        return try? await ChaoxingActivityService.fetchSignDetail(
            client: client,
            activeId: activity.id,
            fallbackClassId: activity.classId,
            fallbackCourseId: activity.courseId
        )
    }

    func prepareSignWorkspace(for activity: ChaoxingSignActivity) async -> MacPreparedSignWorkspace {
        let sessionID = signSessionID
        let unavailable = MacPreparedSignWorkspace(activityID: activity.id, sessionID: sessionID, status: nil, detail: nil)
        guard let client, accountRemovalTask == nil, !Task.isCancelled else { return unavailable }
        async let preSignTask = try? ChaoxingSignExecutor.preSign(client: client, activity: activity)
        async let detailTask = try? ChaoxingActivityService.fetchSignDetail(
            client: client,
            activeId: activity.id,
            fallbackClassId: activity.classId,
            fallbackCourseId: activity.courseId
        )
        let (status, detail) = await (preSignTask, detailTask)
        guard sessionID == signSessionID, !Task.isCancelled else { return unavailable }
        return MacPreparedSignWorkspace(activityID: activity.id, sessionID: sessionID, status: status, detail: detail)
    }

    public func resolveStandaloneQRCode(_ rawValue: String) async throws -> (activity: ChaoxingSignActivity, raw: String) {
        guard let client else {
            throw ChaoxingError.sessionExpired("请先登录账号")
        }
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        let parsed = try ChaoxingQRCodeParser.parse(rawValue: trimmed)
        let activity = try await ChaoxingActivityService.buildActivityFromQRCode(client: client, qrResult: parsed)
        return (activity, trimmed)
    }

    public func buildLinkedSignOutActivity(signOutId: Int64, source: ChaoxingSignActivity) async throws -> ChaoxingSignActivity {
        guard let client else {
            throw ChaoxingError.sessionExpired("请先登录账号")
        }
        let meta = try await ChaoxingActivityService.fetchSignDetail(
            client: client,
            activeId: signOutId,
            fallbackClassId: source.classId,
            fallbackCourseId: source.courseId
        )
        return ChaoxingSignActivity(
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
            courseId: meta.signOutInfo.courseId != 0 ? meta.signOutInfo.courseId : source.courseId,
            classId: meta.signOutInfo.classId != 0 ? meta.signOutInfo.classId : source.classId,
            courseName: source.courseName,
            extContent: ""
        )
    }

    public func completeCaptchaChallenge(id: UUID, validate: String) {
        guard activeCaptchaChallenge?.id == id else { return }
        let cont = captchaContinuation
        captchaContinuation = nil
        activeCaptchaChallenge = nil
        cont?.resume(returning: validate)
    }

    public func cancelCaptchaChallenge(id: UUID) {
        guard activeCaptchaChallenge?.id == id else { return }
        let cont = captchaContinuation
        captchaContinuation = nil
        activeCaptchaChallenge = nil
        cont?.resume(throwing: ChaoxingError.captchaFailed("已取消滑块验证"))
    }

    private func resolveCaptchaAutoOrManual(
        client runnerClient: ChaoxingHTTPClient,
        accountName: String,
        refererURL: String
    ) async throws -> String {
        var lastFetched: (captcha: ChaoxingCaptchaData, shadeImageData: Data, cutoutImageData: Data)?
        // 1. Try local gradient auto-solver up to 3 times before asking user to drag manually.
        for attempt in 1...3 {
            try Task.checkCancellation()
            let fetched = try await ChaoxingCaptchaService.fetchCaptcha(client: runnerClient, refererURL: refererURL)
            try Task.checkCancellation()
            lastFetched = fetched
            if let estimatedX = ChaoxingCaptchaSolver.estimateXPosition(
                shadeImageData: fetched.shadeImageData,
                cutoutImageData: fetched.cutoutImageData
            ) {
                if let validate = try? await ChaoxingCaptchaService.verifyCaptcha(
                    client: runnerClient,
                    captcha: fetched.captcha,
                    xPosition: estimatedX,
                    refererURL: refererURL
                ), !validate.isEmpty {
                    try Task.checkCancellation()
                    appendLog("[\(accountName)] 本地滑块自动验证成功 (第 \(attempt) 次)")
                    return validate
                }
            }
        }

        // 2. Fallback to manual slider sheet on macOS.
        try Task.checkCancellation()
        let manualChallenge = if let existing = lastFetched {
            existing
        } else {
            try await ChaoxingCaptchaService.fetchCaptcha(client: runnerClient, refererURL: refererURL)
        }
        try Task.checkCancellation()
        appendLog("[\(accountName)] 本地自动滑块未通过，请在弹窗中手动拖动完成验证")
        return try await waitForManualCaptcha(
            MacCaptchaChallenge(
                client: runnerClient,
                refererURL: refererURL,
                accountName: accountName,
                captcha: manualChallenge.captcha,
                shadeImageData: manualChallenge.shadeImageData,
                cutoutImageData: manualChallenge.cutoutImageData
            )
        )
    }

    func waitForManualCaptcha(_ challenge: MacCaptchaChallenge) async throws -> String {
        try Task.checkCancellation()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                guard !Task.isCancelled, activeCaptchaChallenge == nil else {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                captchaContinuation = continuation
                activeCaptchaChallenge = challenge
            }
        } onCancel: {
            Task { @MainActor in self.cancelCaptchaChallenge(id: challenge.id) }
        }
    }

    private func cancelRadarScan() {
        radarTask?.cancel()
        radarTask = nil
        radarScanID = nil
    }

    @discardableResult
    func scanPendingActivities() -> Task<Void, Never>? {
        guard let client, !courses.isEmpty, !isScanningRadar, accountRemovalTask == nil else { return nil }
        let targetCourses = starredCourseIds.isEmpty
            ? Array(courses.prefix(12))
            : courses.filter { starredCourseIds.contains($0.courseId) }
        let scanID = UUID()
        radarScanID = scanID
        radarScanMessage = nil
        radarFailedCourses = 0
        pendingRadarActivities = []
        let task = Task {
            defer {
                if radarScanID == scanID {
                    radarTask = nil
                    radarScanID = nil
                }
            }
            let (found, failures) = await withTaskGroup(of: [ChaoxingSignActivity]?.self) { group in
                for course in targetCourses {
                    _ = group.addTaskUnlessCancelled {
                        let activities = try? await ChaoxingActivityService.fetchCourseActivities(client: client, course: course)
                        return activities?.filter { $0.isAvailable && !$0.isAlreadySignedByStatus }
                    }
                }
                var found: [ChaoxingSignActivity] = []
                var failures = 0
                for await batch in group {
                    if let batch { found.append(contentsOf: batch) } else { failures += 1 }
                }
                return (found, failures)
            }
            guard radarScanID == scanID, !Task.isCancelled else { return }
            let message: String
            if targetCourses.isEmpty {
                message = "当前没有可扫描的课程"
            } else if failures == targetCourses.count {
                message = "\(failures) 门课程均查询失败，请重试"
            } else {
                let checked = targetCourses.count - failures
                message = "已检查 \(checked) 门课程，发现 \(found.count) 个待签活动"
                    + (failures > 0 ? "，另有 \(failures) 门查询失败" : "")
            }
            pendingRadarActivities = found
            radarFailedCourses = failures
            radarScanMessage = message
            appendLog("签到雷达：\(message)", isError: failures > 0)
        }
        radarTask = task
        return task
    }

    public func loadActivities(for course: ChaoxingCourse) {
        signActivitiesTask?.cancel()
        selectedSignCourse = course
        courseActivities = []
        signErrorMessage = nil
        guard let client else { return }
        let expectedGen = accountSwitchGeneration
        isLoadingActivities = true

        signActivitiesTask = Task { [weak self] in
            guard let self else { return }
            defer {
                if !Task.isCancelled, self.accountSwitchGeneration == expectedGen, self.selectedSignCourse?.id == course.id {
                    self.isLoadingActivities = false
                }
            }
            do {
                let acts = try await ChaoxingActivityService.fetchCourseActivities(client: client, course: course)
                try Task.checkCancellation()
                guard self.accountSwitchGeneration == expectedGen, self.selectedSignCourse?.id == course.id else { return }
                self.courseActivities = acts
                self.appendLog("[\(course.courseName)] 加载 \(acts.count) 个签到活动")
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, self.accountSwitchGeneration == expectedGen, self.selectedSignCourse?.id == course.id else { return }
                self.signErrorMessage = error.localizedDescription
                self.appendLog("获取课程活动失败: \(error.localizedDescription)", isError: true)
            }
        }
    }

    public func loadGroups() async {
        guard let client else { return }
        let expectedGen = accountSwitchGeneration
        isLoadingGroups = true
        signErrorMessage = nil
        defer {
            if accountSwitchGeneration == expectedGen {
                isLoadingGroups = false
            }
        }
        do {
            let config = try await ChaoxingGroupService.getEasemobConfig(client: client)
            guard accountSwitchGeneration == expectedGen, !Task.isCancelled else { return }
            let fetched = try await ChaoxingGroupService.fetchJoinedGroups(client: client, imConfig: config)
            guard accountSwitchGeneration == expectedGen else { return }
            imConfig = config
            groups = fetched
            appendLog("已加载 \(groups.count) 个群聊")
        } catch {
            guard accountSwitchGeneration == expectedGen else { return }
            signErrorMessage = error.localizedDescription
            appendLog("获取群聊失败: \(error.localizedDescription)", isError: true)
        }
    }

    public func loadGroupActivities(for group: ChaoxingIMGroup) async {
        selectedGroup = group
        guard let client else { return }
        let expectedGen = accountSwitchGeneration
        isLoadingActivities = true
        signErrorMessage = nil
        defer {
            if accountSwitchGeneration == expectedGen {
                isLoadingActivities = false
            }
        }
        do {
            let config: ChaoxingEasemobIMConfig
            if let existing = imConfig {
                config = existing
            } else {
                let fetched = try await ChaoxingGroupService.getEasemobConfig(client: client)
                guard accountSwitchGeneration == expectedGen else { return }
                imConfig = fetched
                config = fetched
            }
            let fetchedGroupActs = try await ChaoxingGroupService.fetchGroupSignActivities(client: client, group: group, imConfig: config)
            guard accountSwitchGeneration == expectedGen, selectedGroup?.id == group.id else { return }
            groupActivities = fetchedGroupActs.map { gAct in
                ChaoxingSignActivity(
                    id: gAct.activeId,
                    startTimeMs: gAct.messageTimestampMs,
                    endTimeMs: nil,
                    userStatus: 0,
                    otherId: gAct.signType.rawValue,
                    isLook: false,
                    type: 2,
                    activeType: 2,
                    nameOne: gAct.title,
                    status: 1,
                    nameFour: gAct.startTimeTitle,
                    courseId: gAct.courseId,
                    classId: gAct.classId,
                    courseName: gAct.courseName,
                    extContent: ""
                )
            }
            appendLog("[\(group.name)] 加载 \(groupActivities.count) 个群聊活动")
        } catch {
            guard accountSwitchGeneration == expectedGen else { return }
            signErrorMessage = error.localizedDescription
            appendLog("获取群聊活动失败: \(error.localizedDescription)", isError: true)
        }
    }

    public func addLocationPreset(name: String, address: String, latitude: Double, longitude: Double) async {
        let preset = ChaoxingLocationPreset(name: name, address: address, latitude: latitude, longitude: longitude)
        locationPresets.insert(preset, at: 0)
        try? await persistAccounts()
    }

    public func deleteLocationPreset(_ id: UUID) async {
        locationPresets.removeAll { $0.id == id }
        try? await persistAccounts()
    }

    private func invalidateSignWorkspace() {
        cancelSignBatch()
        signSessionID = UUID()
    }

    func cancelSignBatch(owner: UUID? = nil) {
        if let owner, signBatch?.owner != owner { return }
        signBatch?.task.cancel()
        signBatch = nil
        signRunnerClient?.invalidate()
        signRunnerClient = nil
        if let challenge = activeCaptchaChallenge { cancelCaptchaChallenge(id: challenge.id) }
    }

    func isSubmitting(owner: UUID) -> Bool { signBatch?.owner == owner }

    func canExecuteSign(activity: ChaoxingSignActivity, prepared: MacPreparedSignWorkspace?,
                        targetPhones: Set<String>) -> Bool {
        guard signBatch == nil, accountRemovalTask == nil, !isSwitchingAccount,
              let activePhoneNumber, let prepared, prepared.sessionID == signSessionID,
              prepared.activityID == activity.id, activity.isAvailable, !activity.isLate,
              let status = prepared.status, status != .expired, !targetPhones.isEmpty,
              prepared.signType(for: activity) != .unknown else { return false }
        let selected = accounts.map(\.phoneNumber).filter(targetPhones.contains)
        guard selected.count == targetPhones.count, Set(selected) == targetPhones else { return false }
        return status != .alreadySigned || targetPhones != [activePhoneNumber]
    }

    @discardableResult
    func executeSign(
        owner: UUID,
        activity: ChaoxingSignActivity,
        prepared: MacPreparedSignWorkspace?,
        location: ChaoxingLocationPreset?,
        code: String,
        qrCodeRaw: String,
        useProfileFacePhoto: Bool = false,
        photoImageData: Data? = nil,
        rememberLocationForCourse: Bool = true,
        targetPhones: Set<String>,
        onCompletion: @escaping ([MacSignResult]) -> Void = { _ in }
    ) -> Task<Void, Never>? {
        guard let prepared, canExecuteSign(activity: activity, prepared: prepared, targetPhones: targetPhones) else { return nil }
        let runners = accounts.filter { targetPhones.contains($0.phoneNumber) }
        let id = UUID()
        let task = Task {
            defer { if signBatch?.id == id { signBatch = nil } }
            do {
                let results = try await runSignBatch(
                    activity: activity, prepared: prepared, location: location, code: code, qrCodeRaw: qrCodeRaw,
                    useProfileFacePhoto: useProfileFacePhoto, photoImageData: photoImageData,
                    rememberLocationForCourse: rememberLocationForCourse, runners: runners
                )
                try Task.checkCancellation()
                onCompletion(results)
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                appendLog("操作中断：\(error.localizedDescription)", isError: true)
            }
        }
        signBatch = (id, owner, task)
        return task
    }

    private func runSignBatch(
        activity: ChaoxingSignActivity, prepared: MacPreparedSignWorkspace,
        location: ChaoxingLocationPreset?, code: String, qrCodeRaw: String,
        useProfileFacePhoto: Bool, photoImageData: Data?, rememberLocationForCourse: Bool,
        runners: [ChaoxingAccountSession]
    ) async throws -> [MacSignResult] {
        let effectiveSignType = prepared.signType(for: activity)
        let detail = prepared.detail
        let requiresPosition = effectiveSignType == .location || (detail?.isPositionRequired ?? false)
        let requiresFace = detail?.isFaceRequired ?? false
        var results: [MacSignResult] = []

        for (index, session) in runners.enumerated() {
            try Task.checkCancellation()
            if index > 0 {
                try await waitBetweenSignAccounts(.milliseconds(max(200, batchSignDelayMs)))
            }
            try Task.checkCancellation()
            let runnerClient = makeClient(for: session)
            signRunnerClient = runnerClient
            defer {
                if signRunnerClient === runnerClient { signRunnerClient = nil }
                runnerClient.invalidate()
            }
            let accountLabel = accountDisplayTitle(for: session)
            func record(_ disposition: ChaoxingSignDisposition, _ message: String) {
                results.append(MacSignResult(id: session.phoneNumber, accountName: accountLabel,
                                             disposition: disposition, message: message))
            }

            do {
                let status = try await ChaoxingSignExecutor.preSign(client: runnerClient, activity: activity)
                try Task.checkCancellation()
                if status == .alreadySigned {
                    record(.alreadySigned, "此前已签")
                    continue
                }
                if status == .expired {
                    record(.failed, "活动已结束")
                    continue
                }

                // Pre-check sign code / QR code before burning attempts
                if effectiveSignType == .password || effectiveSignType == .gesture {
                    let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !trimmed.isEmpty else {
                        record(.failed, "请输入签到码/手势数字")
                        continue
                    }
                    if let valid = try? await ChaoxingSignExecutor.checkSignCode(
                        client: runnerClient,
                        activeId: activity.id,
                        signCode: trimmed
                    ), !valid {
                        record(.failed, "签到码或手势不正确")
                        continue
                    }
                }

                try Task.checkCancellation()
                let parsedQR: ChaoxingQRCodeParseResult?
                if effectiveSignType == .qrCode {
                    let trimmed = qrCodeRaw.trimmingCharacters(in: .whitespacesAndNewlines)
                    let qr = try ChaoxingQRCodeParser.parse(rawValue: trimmed)
                    if let expired = await ChaoxingSignExecutor.isQRCodeExpired(
                        client: runnerClient,
                        activeId: activity.id,
                        qrResult: qr
                    ), expired {
                        record(.failed, "二维码已过期，请重新扫描最新二维码")
                        continue
                    }
                    parsedQR = qr
                } else {
                    parsedQR = nil
                }

                try Task.checkCancellation()
                if requiresPosition && location == nil {
                    record(.failed, "该活动要求位置校验，请先选择位置预设")
                    continue
                }

                let submitOnce: (_ validate: String?, _ enc2: String?) async throws -> ChaoxingSignOutcome = { validate, enc2 in
                    try Task.checkCancellation()
                    let locationInfo = location.map {
                        ChaoxingLocationSignInfo(
                            latitude: $0.latitude,
                            longitude: $0.longitude,
                            address: $0.address,
                            enableRandomOffset: self.enableLocationJitter
                        )
                    }
                    let faceObjId: String? = if requiresFace {
                        try await ChaoxingFaceService.fetchUserProfileFaceObjectId(client: runnerClient)
                    } else {
                        nil
                    }

                    try Task.checkCancellation()
                    switch effectiveSignType {
                    case .unknown:
                        throw ChaoxingError.unsupportedSignType()
                    case .photoOrClick:
                        let uploadedObjId: String?
                        if useProfileFacePhoto {
                            uploadedObjId = try await ChaoxingFaceService.fetchUserProfileFaceObjectId(client: runnerClient)
                        } else if let photoData = photoImageData {
                            uploadedObjId = try await ChaoxingCloudDriveService.uploadImage(
                                client: runnerClient,
                                imageData: photoData
                            )
                        } else {
                            uploadedObjId = nil
                        }
                        try Task.checkCancellation()
                        return try await ChaoxingSignExecutor.signPhotoOrClick(
                            client: runnerClient,
                            activity: activity,
                            objectId: uploadedObjId,
                            captchaValidate: validate,
                            enc2: enc2
                        )
                    case .location:
                        guard let locInfo = locationInfo else {
                            throw ChaoxingError.invalidInput("请先选择位置预设")
                        }
                        return try await ChaoxingSignExecutor.signLocation(
                            client: runnerClient,
                            activity: activity,
                            location: locInfo,
                            faceImageObjectId: faceObjId,
                            captchaValidate: validate,
                            enc2: enc2
                        )
                    case .password, .gesture:
                        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
                        return try await ChaoxingSignExecutor.signCodeOrGesture(
                            client: runnerClient,
                            activity: activity,
                            signCode: trimmed,
                            location: requiresPosition ? locationInfo : nil,
                            captchaValidate: validate,
                            enc2: enc2
                        )
                    case .qrCode:
                        guard let qr = parsedQR else {
                            throw ChaoxingError.qrCodeParseFailed("请先粘贴或识别二维码")
                        }
                        return try await ChaoxingSignExecutor.signQRCode(
                            client: runnerClient,
                            activity: activity,
                            qrResult: qr,
                            location: requiresPosition ? locationInfo : nil,
                            faceImageObjectId: faceObjId,
                            captchaValidate: validate,
                            enc2: enc2
                        )
                    }
                }

                // Execute with automatic device-code regeneration and captcha solve-and-retry.
                let outcome: ChaoxingSignOutcome
                var deviceRetried = false
                var captchaValidate: String? = nil
                var captchaEnc2: String? = nil
                var finalOutcome: ChaoxingSignOutcome?

                for _ in 0..<4 {
                    try Task.checkCancellation()
                    do {
                        finalOutcome = try await submitOnce(captchaValidate, captchaEnc2)
                        break
                    } catch ChaoxingError.deviceDuplicated where !deviceRetried {
                        try Task.checkCancellation()
                        deviceRetried = true
                        var updatedSession = runnerClient.session
                        updatedSession.deviceCode = ChaoxingCrypto.randomizedDeviceCode()
                        runnerClient.updateSession(updatedSession)
                        if let accIdx = accounts.firstIndex(where: { $0.phoneNumber == session.phoneNumber }) {
                            accounts[accIdx].deviceCode = updatedSession.deviceCode
                        }
                        appendLog("[\(accountLabel)] 检测到设备码冲突，已自动重置设备码并重试")
                    } catch ChaoxingError.captchaRequired(let enc2) {
                        try Task.checkCancellation()
                        let referer = ChaoxingSignExecutor.preSignURL(client: runnerClient, activity: activity).absoluteString
                        captchaValidate = try await resolveCaptchaAutoOrManual(
                            client: runnerClient,
                            accountName: accountLabel,
                            refererURL: referer
                        )
                        captchaEnc2 = enc2
                    }
                }
                try Task.checkCancellation()
                guard let resolvedOutcome = finalOutcome else {
                    throw ChaoxingError.serverMessage("签到重试次数已达上限")
                }
                outcome = resolvedOutcome
                // Sync any refreshed cookies back into stored account pool
                if let accIdx = accounts.firstIndex(where: { $0.phoneNumber == session.phoneNumber }) {
                    var synced = runnerClient.session
                    synced.isPrimary = accounts[accIdx].isPrimary
                    synced.tags = accounts[accIdx].tags
                    accounts[accIdx] = synced
                }
                record(outcome.disposition, outcome.message)
            } catch {
                try Task.checkCancellation()
                record(ChaoxingSignDisposition(error: error), error.localizedDescription)
            }
        }

        try Task.checkCancellation()
        if let location, rememberLocationForCourse, activity.courseId > 0 {
            courseLocationBindings[String(activity.courseId)] = location
        }
        let counts = ChaoxingSignCounts(dispositions: results.map(\.disposition))
        let summary = results.map { "\($0.accountName): \($0.message)" }.joined(separator: "；")
        let entry = ChaoxingSignHistoryEntry(
            activeId: activity.id,
            activityName: activity.nameOne,
            courseName: activity.courseName,
            signTypeRaw: effectiveSignType.rawValue,
            successCount: counts.accepted,
            alreadySignedCount: counts.alreadySigned,
            totalCount: results.count,
            summary: summary
        )
        signHistory.insert(entry, at: 0)
        if signHistory.count > 100 {
            signHistory.removeLast(signHistory.count - 100)
        }
        do {
            try await persistAccounts()
        } catch {
            try Task.checkCancellation()
            appendLog("签到历史未能保存：\(error.localizedDescription)", isError: true)
        }
        try Task.checkCancellation()
        appendLog("签到执行 [\(activity.nameOne)] \(counts.summary)")

        if results.contains(where: { $0.id == activePhoneNumber && $0.disposition != .failed }) {
            if let curCourse = selectedSignCourse, curCourse.courseId == activity.courseId {
                loadActivities(for: curCourse)
            }
            pendingRadarActivities.removeAll { $0.id == activity.id }
        }
        return results
    }

    public static func detectQRCodeFromImageURL(_ url: URL) -> String? {
        guard let nsImage = NSImage(contentsOf: url),
              let tiff = nsImage.tiffRepresentation,
              let ciImage = CIImage(data: tiff) else {
            return nil
        }
        let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        let features = detector?.features(in: ciImage) as? [CIQRCodeFeature] ?? []
        return features.first?.messageString
    }

    private static func redactPhones(in message: String, known: [String]) -> String {
        var redacted = message.replacingOccurrences(
            of: #"(?<![0-9])((?:\+?86[ -]?)?1[0-9]{2})[ -]?[0-9]{4}[ -]?([0-9]{4})(?![0-9])"#,
            with: "$1****$2", options: .regularExpression
        )
        for phone in known where phone.filter(\.isNumber).count >= 7 && redacted.contains(phone) {
            redacted = redacted.replacingOccurrences(of: phone, with: "****" + phone.suffix(4))
        }
        return redacted
    }

    public func appendLog(_ message: String, isError: Bool = false) {
        let redacted = Self.redactPhones(in: message, known: accounts.map(\.phoneNumber))
        logs.insert(MacStudyLogLine(message: redacted, isError: isError), at: 0)
        if logs.count > 150 {
            logs.removeLast(logs.count - 150)
        }
    }
}
#endif
