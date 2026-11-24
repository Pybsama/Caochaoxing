// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation

/// A chapter or section entry extracted from a Chaoxing course study tree.
public struct ChaoxingChapterNode: Codable, Sendable, Hashable, Identifiable {
    public let id: String
    public let title: String
    public let sectionTitle: String
    public let courseId: Int64
    public let classId: Int
    /// `nil` = unknown, `0` = explicitly zero unfinished tasks, `>0` = unfinished task count.
    public let pendingJobCount: Int?

    public init(
        id: String,
        title: String,
        sectionTitle: String = "",
        courseId: Int64,
        classId: Int,
        pendingJobCount: Int? = nil
    ) {
        self.id = id
        self.title = title
        self.sectionTitle = sectionTitle
        self.courseId = courseId
        self.classId = classId
        self.pendingJobCount = pendingJobCount
    }
}

/// Playback speed options exposed in the player and controller UI.
public enum ChaoxingPlaybackSpeed: Double, CaseIterable, Identifiable, Sendable, Codable {
    case x1 = 1
    case x2 = 2
    case x4 = 4
    case x16 = 16
    case x32 = 32
    case x64 = 64

    public var id: Double { rawValue }

    public var label: String {
        "\(Int(rawValue))x"
    }

    public var isNativeRateSupported: Bool {
        rawValue <= 16
    }
}

/// Event codes accepted by Chaoxing's `/mooc-ans/multimedia/log/a/{cpi}/{dtoken}` `isdrag` parameter.
public enum ChaoxingVideoDragEvent: Int, Sendable, Codable {
    case playing = 0
    case drag = 1
    case pause = 2
    case play = 3
    case ended = 4

    public var displayName: String {
        switch self {
        case .playing: return "playing(0)"
        case .drag: return "drag(1)"
        case .pause: return "pause(2)"
        case .play: return "play(3)"
        case .ended: return "ended(4)"
        }
    }
}

/// Response returned by `/mooc-ans/multimedia/log/a/{cpi}/{dtoken}`.
public struct ChaoxingVideoLogResponse: Codable, Sendable, Equatable {
    public let isPassed: Bool
    public let videoTimeLimit: Bool
    public let hasJobLimit: Bool

    public init(isPassed: Bool, videoTimeLimit: Bool = false, hasJobLimit: Bool = false) {
        self.isPassed = isPassed
        self.videoTimeLimit = videoTimeLimit
        self.hasJobLimit = hasJobLimit
    }
}

/// A video task point parsed from `knowledge/cards` `mArg` and `/ananas/status/{objectId}`.
public struct ChaoxingVideoTask: Codable, Sendable, Hashable, Identifiable {
    public var id: String { "\(chapterId)_\(jobid)_\(objectId)" }

    public let courseId: Int64
    public let classId: Int
    public let chapterId: String
    public let cpi: Int64
    public let userid: String
    public let jobid: String
    public let objectId: String
    public let name: String
    public let otherInfo: String
    public let dtoken: String
    public let duration: Int
    public let attDuration: Int
    public let attDurationEnc: String
    public let videoFaceCaptureEnc: String
    public let rt: Double
    public let doublespeed: Int
    public let reportTimeInterval: Int
    public let headOffsetMs: Int
    public let playTimeMs: Int
    public let isPassed: Bool
    public let isJob: Bool
    public let begins: Int
    public let ends: Int
    public let requiresFaceCapture: Bool
    public let reportUrl: String
    public let videoPlayURL: String?
    public let screenshotURL: String?
    /// `"Video"` for standard video tasks, `"Audio"` for `insertaudio` tasks.
    public let dtype: String

    public init(
        courseId: Int64,
        classId: Int,
        chapterId: String,
        cpi: Int64,
        userid: String,
        jobid: String,
        objectId: String,
        name: String,
        otherInfo: String,
        dtoken: String,
        duration: Int,
        attDuration: Int,
        attDurationEnc: String,
        videoFaceCaptureEnc: String = "",
        rt: Double = 0.9,
        doublespeed: Int = 0,
        reportTimeInterval: Int = 60,
        headOffsetMs: Int = 0,
        playTimeMs: Int = 0,
        isPassed: Bool = false,
        isJob: Bool = true,
        begins: Int = 0,
        ends: Int = 0,
        requiresFaceCapture: Bool = false,
        reportUrl: String,
        videoPlayURL: String? = nil,
        screenshotURL: String? = nil,
        dtype: String = "Video"
    ) {
        self.courseId = courseId
        self.classId = classId
        self.chapterId = chapterId
        self.cpi = cpi
        self.userid = userid
        self.jobid = jobid
        self.objectId = objectId
        self.name = name
        self.otherInfo = otherInfo
        self.dtoken = dtoken
        self.duration = duration
        self.attDuration = attDuration
        self.attDurationEnc = attDurationEnc
        self.videoFaceCaptureEnc = videoFaceCaptureEnc
        self.rt = rt
        self.doublespeed = doublespeed
        self.reportTimeInterval = reportTimeInterval
        self.headOffsetMs = headOffsetMs
        self.playTimeMs = playTimeMs
        self.isPassed = isPassed
        self.isJob = isJob
        self.begins = begins
        self.ends = ends
        self.requiresFaceCapture = requiresFaceCapture
        self.reportUrl = reportUrl
        self.videoPlayURL = videoPlayURL
        self.screenshotURL = screenshotURL
        self.dtype = dtype
    }

    /// Total video length in seconds.
    public var totalDuration: Int {
        max(duration, attDuration)
    }

    /// Clip start second (`begins` when trimmed, otherwise `0`).
    public var effectiveClipStart: Int {
        begins > 0 ? begins : 0
    }

    /// Clip end second (`ends` when trimmed and valid, otherwise `totalDuration`).
    public var effectiveClipEnd: Int {
        if ends > effectiveClipStart {
            return totalDuration > 0 ? min(ends, totalDuration) : ends
        }
        return totalDuration
    }

    /// Effective clip range string sent as `clipTime` (`"{start}_{end}"`).
    public var effectiveClipTime: String {
        "\(effectiveClipStart)_\(effectiveClipEnd)"
    }

    /// Whether this video is a valid task point eligible for progress reporting.
    public var isReportableTask: Bool {
        isJob && !jobid.isEmpty && !dtoken.isEmpty && effectiveClipEnd > effectiveClipStart
    }

    /// Maximum server-accepted playback multiplier inferred from course `doublespeed`.
    public var allowedMaxRate: Double {
        doublespeed != 0 ? 2.0 : 1.0
    }

    /// Minimum effective watch seconds required to satisfy `rt`.
    public var requiredWatchSeconds: Int {
        let span = max(0, effectiveClipEnd - effectiveClipStart)
        guard span > 0 else { return 0 }
        return Int(ceil(Double(span) * rt))
    }

    public func updating(isPassed: Bool, headOffsetMs: Int? = nil) -> ChaoxingVideoTask {
        ChaoxingVideoTask(
            courseId: courseId,
            classId: classId,
            chapterId: chapterId,
            cpi: cpi,
            userid: userid,
            jobid: jobid,
            objectId: objectId,
            name: name,
            otherInfo: otherInfo,
            dtoken: dtoken,
            duration: duration,
            attDuration: attDuration,
            attDurationEnc: attDurationEnc,
            videoFaceCaptureEnc: videoFaceCaptureEnc,
            rt: rt,
            doublespeed: doublespeed,
            reportTimeInterval: reportTimeInterval,
            headOffsetMs: headOffsetMs ?? self.headOffsetMs,
            playTimeMs: headOffsetMs ?? self.playTimeMs,
            isPassed: isPassed,
            isJob: isJob,
            begins: begins,
            ends: ends,
            requiresFaceCapture: requiresFaceCapture,
            reportUrl: reportUrl,
            videoPlayURL: videoPlayURL,
            screenshotURL: screenshotURL,
            dtype: dtype
        )
    }
}

/// Live broadcast / live replay task point (`type == "live"`, `module == "insertlive"`)
/// hosted on `zhibo.chaoxing.com` and enriched via `/ananas/live/liveinfo`.
public struct ChaoxingLiveTask: Codable, Sendable, Hashable, Identifiable {
    public var id: String { "live_\(chapterId)_\(jobid)_\(liveId)" }

    public let courseId: Int64
    public let classId: Int
    public let chapterId: String
    public let cpi: Int64
    public let userid: String
    public let jobid: String
    public let aid: String
    public let liveId: String
    public let shortLiveId: String
    public let streamName: String
    public let vdoid: String
    public let title: String
    public let rt: Double
    public let doublespeed: Int
    public let isNotDrag: Int
    public let switchWindow: Int
    public let liveSetEnc: String
    public let authEnc: String
    public let liveDragEnc: String
    public let liveSwDsEnc: String
    public let isJob: Bool
    public let isPassed: Bool
    /// `0` = not started, `1` = live now, `4` = ended (`ifReview == 0` means replay available).
    public let liveStatus: Int
    /// When `liveStatus == 4`: `0` = replay available, `1` = no replay.
    public let ifReview: Int
    /// Total replay duration in seconds.
    public let duration: Int
    /// Server-confirmed accumulated replay watch time in milliseconds.
    public let playbackTimelongMs: Int
    /// Server-confirmed completion percentage (`0.0 ... 100.0`).
    public let percentValue: Double
    /// Playable HLS (`m3u8`) or MP4 replay URL when available.
    public let playURL: String?
    /// Secondary fallback replay URL (e.g. progressive `mp4` when primary is `m3u8`).
    public let fallbackPlayURL: String?
    /// Full `zhibo.chaoxing.com` course-context web URL.
    public let webPageURL: String?

    public init(
        courseId: Int64,
        classId: Int,
        chapterId: String,
        cpi: Int64,
        userid: String,
        jobid: String,
        aid: String = "",
        liveId: String,
        shortLiveId: String = "",
        streamName: String = "",
        vdoid: String = "",
        title: String,
        rt: Double = 0.9,
        doublespeed: Int = 1,
        isNotDrag: Int = 0,
        switchWindow: Int = 0,
        liveSetEnc: String = "",
        authEnc: String = "",
        liveDragEnc: String = "",
        liveSwDsEnc: String = "",
        isJob: Bool = true,
        isPassed: Bool = false,
        liveStatus: Int = 4,
        ifReview: Int = 0,
        duration: Int = 0,
        playbackTimelongMs: Int = 0,
        percentValue: Double = 0,
        playURL: String? = nil,
        fallbackPlayURL: String? = nil,
        webPageURL: String? = nil
    ) {
        self.courseId = courseId
        self.classId = classId
        self.chapterId = chapterId
        self.cpi = cpi
        self.userid = userid
        self.jobid = jobid
        self.aid = aid
        self.liveId = liveId
        self.shortLiveId = shortLiveId
        self.streamName = streamName
        self.vdoid = vdoid
        self.title = title
        self.rt = rt
        self.doublespeed = doublespeed
        self.isNotDrag = isNotDrag
        self.switchWindow = switchWindow
        self.liveSetEnc = liveSetEnc
        self.authEnc = authEnc
        self.liveDragEnc = liveDragEnc
        self.liveSwDsEnc = liveSwDsEnc
        self.isJob = isJob
        self.isPassed = isPassed
        self.liveStatus = liveStatus
        self.ifReview = ifReview
        self.duration = duration
        self.playbackTimelongMs = playbackTimelongMs
        self.percentValue = percentValue
        self.playURL = playURL
        self.fallbackPlayURL = fallbackPlayURL
        self.webPageURL = webPageURL
    }

    /// Deduplicated ordered playback stream URLs (primary + fallback).
    public var allPlayURLs: [String] {
        var result: [String] = []
        for candidate in [playURL, fallbackPlayURL] {
            if let trimmed = candidate?.trimmingCharacters(in: .whitespacesAndNewlines),
               !trimmed.isEmpty,
               !result.contains(trimmed) {
                result.append(trimmed)
            }
        }
        return result
    }

    public var canWatchReplay: Bool {
        (liveStatus == 4 && ifReview == 0) || liveStatus == 1
    }

    public var isReportableTask: Bool {
        isJob && !jobid.isEmpty && !liveId.isEmpty && !streamName.isEmpty && !vdoid.isEmpty && canWatchReplay
    }

    public var requiredWatchSeconds: Int {
        guard duration > 0 else { return 0 }
        return Int(ceil(Double(duration) * rt))
    }

    public var watchedSeconds: Int {
        max(0, playbackTimelongMs / 1000)
    }

    public var statusDescription: String {
        switch (liveStatus, ifReview) {
        case (0, _): return "直播未开始"
        case (1, _): return "直播进行中"
        case (4, 0): return "直播回放"
        case (4, _): return "已结束（无回放）"
        default: return "直播回放"
        }
    }

    public func updating(
        isPassed: Bool? = nil,
        playbackTimelongMs: Int? = nil,
        percentValue: Double? = nil,
        duration: Int? = nil,
        liveStatus: Int? = nil,
        ifReview: Int? = nil,
        title: String? = nil,
        liveId: String? = nil,
        shortLiveId: String? = nil,
        streamName: String? = nil,
        vdoid: String? = nil,
        playURL: String? = nil,
        fallbackPlayURL: String? = nil,
        webPageURL: String? = nil
    ) -> ChaoxingLiveTask {
        ChaoxingLiveTask(
            courseId: courseId,
            classId: classId,
            chapterId: chapterId,
            cpi: cpi,
            userid: userid,
            jobid: jobid,
            aid: aid,
            liveId: liveId ?? self.liveId,
            shortLiveId: shortLiveId ?? self.shortLiveId,
            streamName: streamName ?? self.streamName,
            vdoid: vdoid ?? self.vdoid,
            title: title ?? self.title,
            rt: rt,
            doublespeed: doublespeed,
            isNotDrag: isNotDrag,
            switchWindow: switchWindow,
            liveSetEnc: liveSetEnc,
            authEnc: authEnc,
            liveDragEnc: liveDragEnc,
            liveSwDsEnc: liveSwDsEnc,
            isJob: isJob,
            isPassed: isPassed ?? self.isPassed,
            liveStatus: liveStatus ?? self.liveStatus,
            ifReview: ifReview ?? self.ifReview,
            duration: duration ?? self.duration,
            playbackTimelongMs: playbackTimelongMs ?? self.playbackTimelongMs,
            percentValue: percentValue ?? self.percentValue,
            playURL: playURL ?? self.playURL,
            fallbackPlayURL: fallbackPlayURL ?? self.fallbackPlayURL,
            webPageURL: webPageURL ?? self.webPageURL
        )
    }
}

/// Live room metadata returned by `/ananas/live/liveinfo`.
public struct ChaoxingLiveInfo: Sendable, Equatable {
    public let resolvedLiveId: String
    public let title: String
    public let liveStatus: Int
    public let ifReview: Int
    public let duration: Int
    public let playbackTimelongMs: Int
    public let percentValue: Double
    public let streamName: String
    public let vdoid: String
    public let m3u8URL: String?
    public let mp4URL: String?
    public let viewURL: String?
    public let courseEndState: Int

    public init(
        resolvedLiveId: String,
        title: String,
        liveStatus: Int,
        ifReview: Int,
        duration: Int,
        playbackTimelongMs: Int,
        percentValue: Double,
        streamName: String,
        vdoid: String,
        m3u8URL: String? = nil,
        mp4URL: String? = nil,
        viewURL: String? = nil,
        courseEndState: Int = 0
    ) {
        self.resolvedLiveId = resolvedLiveId
        self.title = title
        self.liveStatus = liveStatus
        self.ifReview = ifReview
        self.duration = duration
        self.playbackTimelongMs = playbackTimelongMs
        self.percentValue = percentValue
        self.streamName = streamName
        self.vdoid = vdoid
        self.m3u8URL = m3u8URL
        self.mp4URL = mp4URL
        self.viewURL = viewURL
        self.courseEndState = courseEndState
    }

    public var preferredPlayURL: String? {
        m3u8URL ?? mp4URL
    }

    public var fallbackPlayURL: String? {
        if let m3u8URL, !m3u8URL.isEmpty {
            return mp4URL
        }
        return nil
    }

    public var allPlayURLs: [String] {
        var result: [String] = []
        for candidate in [m3u8URL, mp4URL] {
            if let trimmed = candidate?.trimmingCharacters(in: .whitespacesAndNewlines),
               !trimmed.isEmpty,
               !result.contains(trimmed) {
                result.append(trimmed)
            }
        }
        return result
    }
}

/// Response returned by `zhibo.chaoxing.com/saveTimePc`.
public struct ChaoxingLiveHeartbeatResponse: Sendable, Equatable {
    public let result: Int
    public let accepted: Bool
    public let message: String
    public let duration: Int
    public let liveStatus: Int

    public init(
        result: Int,
        accepted: Bool,
        message: String = "",
        duration: Int = 0,
        liveStatus: Int = 4
    ) {
        self.result = result
        self.accepted = accepted
        self.message = message
        self.duration = duration
        self.liveStatus = liveStatus
    }
}

/// Active `zhibo.chaoxing.com` course-replay playback session context used for 30s heartbeats.
public struct ChaoxingLiveSessionContext: Sendable, Equatable {
    public let pageURL: String
    public let shortLiveId: String
    public let uInfo: String
    public let streamName: String
    public let vdoid: String
    public let duration: Int
    public let fid: String?
    public let playURL: String?

    public init(
        pageURL: String,
        shortLiveId: String,
        uInfo: String,
        streamName: String,
        vdoid: String,
        duration: Int,
        fid: String? = nil,
        playURL: String? = nil
    ) {
        self.pageURL = pageURL
        self.shortLiveId = shortLiveId
        self.uInfo = uInfo
        self.streamName = streamName
        self.vdoid = vdoid
        self.duration = duration
        self.fid = fid
        self.playURL = playURL
    }
}

/// Kind of attachment / task point inside a Chaoxing chapter card.
public enum ChaoxingAttachmentKind: String, Codable, Sendable, Hashable {
    case video
    case document
    case audio
    case live
    case quiz
    case other

    public var displayName: String {
        switch self {
        case .video: return "视频"
        case .document: return "文档/PPT"
        case .audio: return "音频"
        case .live: return "直播"
        case .quiz: return "测验/作业"
        case .other: return "其他"
        }
    }

    public var systemImage: String {
        switch self {
        case .video: return "play.rectangle"
        case .document: return "doc.text"
        case .audio: return "waveform"
        case .live: return "dot.radiowaves.left.and.right"
        case .quiz: return "checklist"
        case .other: return "square.grid.2x2"
        }
    }
}

/// Non-video (or general) task point parsed from `knowledge/cards` `mArg`.
public struct ChaoxingChapterAttachment: Codable, Sendable, Hashable, Identifiable {
    public var id: String { "\(chapterId)_\(kind.rawValue)_\(jobid)_\(objectId)_\(workId)_\(name)" }

    public let kind: ChaoxingAttachmentKind
    public let courseId: Int64
    public let classId: Int
    public let chapterId: String
    public let cpi: Int64
    public let jobid: String
    public let objectId: String
    public let jtoken: String
    public let workId: String
    public let workEnc: String
    public let ktoken: String
    public let name: String
    public let isJob: Bool
    public let isPassed: Bool
    public let externalURL: String?

    public init(
        kind: ChaoxingAttachmentKind,
        courseId: Int64,
        classId: Int,
        chapterId: String,
        cpi: Int64 = 0,
        jobid: String,
        objectId: String = "",
        jtoken: String = "",
        workId: String = "",
        workEnc: String = "",
        ktoken: String = "",
        name: String,
        isJob: Bool = true,
        isPassed: Bool = false,
        externalURL: String? = nil
    ) {
        self.kind = kind
        self.courseId = courseId
        self.classId = classId
        self.chapterId = chapterId
        self.cpi = cpi
        self.jobid = jobid
        self.objectId = objectId
        self.jtoken = jtoken
        self.workId = workId
        self.workEnc = workEnc
        self.ktoken = ktoken
        self.name = name
        self.isJob = isJob
        self.isPassed = isPassed
        self.externalURL = externalURL
    }

    /// Document task points carrying `jobid` + `jtoken` can be marked read via `/ananas/job/document`.
    public var canCompleteAsDocument: Bool {
        kind == .document && isJob && !isPassed && !jobid.isEmpty && !jtoken.isEmpty
    }

    /// Direct Chaoxing work/quiz page URL when `workId` is available from `mArg`.
    public var directQuizURL: URL? {
        guard kind == .quiz, !workId.isEmpty else { return nil }
        var comps = URLComponents(string: "https://mooc1.chaoxing.com/mooc-ans/api/work")
        var items: [URLQueryItem] = [
            URLQueryItem(name: "api", value: "1"),
            URLQueryItem(name: "workId", value: workId),
            URLQueryItem(name: "jobid", value: jobid),
            URLQueryItem(name: "needRedirect", value: "true"),
            URLQueryItem(name: "knowledgeid", value: chapterId),
            URLQueryItem(name: "ut", value: "s"),
            URLQueryItem(name: "clazzId", value: String(classId)),
            URLQueryItem(name: "type", value: ""),
            URLQueryItem(name: "enc", value: workEnc),
            URLQueryItem(name: "courseid", value: String(courseId))
        ]
        if cpi > 0 {
            items.append(URLQueryItem(name: "cpi", value: String(cpi)))
        }
        if !ktoken.isEmpty {
            items.append(URLQueryItem(name: "ktoken", value: ktoken))
        }
        comps?.queryItems = items
        return comps?.url
    }

    /// Full Chaoxing chapter study page URL (hosts quizzes, discussions, and attachments natively).
    public var chapterStudyURL: URL? {
        var comps = URLComponents(string: "https://mooc1.chaoxing.com/mycourse/studentstudy")
        var items: [URLQueryItem] = [
            URLQueryItem(name: "chapterId", value: chapterId),
            URLQueryItem(name: "courseId", value: String(courseId)),
            URLQueryItem(name: "clazzid", value: String(classId)),
            URLQueryItem(name: "mooc2", value: "1")
        ]
        if cpi > 0 {
            items.append(URLQueryItem(name: "cpi", value: String(cpi)))
        }
        comps?.queryItems = items
        return comps?.url
    }

    /// Preferred URL to open inside an authenticated in-app WebView.
    public var preferredInAppURL: URL? {
        if kind == .quiz {
            return directQuizURL ?? chapterStudyURL
        }
        if let raw = externalURL?.trimmingCharacters(in: .whitespacesAndNewlines),
           !raw.isEmpty,
           let url = URL(string: raw),
           let scheme = url.scheme?.lowercased(),
           scheme == "http" || scheme == "https" {
            return url
        }
        return chapterStudyURL
    }

    public func updating(isPassed: Bool) -> ChaoxingChapterAttachment {
        ChaoxingChapterAttachment(
            kind: kind,
            courseId: courseId,
            classId: classId,
            chapterId: chapterId,
            cpi: cpi,
            jobid: jobid,
            objectId: objectId,
            jtoken: jtoken,
            workId: workId,
            workEnc: workEnc,
            ktoken: ktoken,
            name: name,
            isJob: isJob,
            isPassed: isPassed,
            externalURL: externalURL
        )
    }
}

/// Complete parsed contents of a chapter across all card tabs.
public struct ChaoxingChapterContent: Codable, Sendable, Hashable {
    public let videos: [ChaoxingVideoTask]
    public let liveTasks: [ChaoxingLiveTask]
    public let otherAttachments: [ChaoxingChapterAttachment]

    public init(
        videos: [ChaoxingVideoTask],
        liveTasks: [ChaoxingLiveTask] = [],
        otherAttachments: [ChaoxingChapterAttachment] = []
    ) {
        self.videos = videos
        self.liveTasks = liveTasks
        self.otherAttachments = otherAttachments
    }
}
