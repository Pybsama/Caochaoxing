// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Foundation

public enum ChaoxingCourseStudyService {
    private static let studyBase = "https://mooc1-2.chaoxing.com"
    private static let mooc2Base = "https://mooc2-ans.chaoxing.com"
    public static let zhiboBase = "https://zhibo.chaoxing.com"
    private static let videoReferer = "https://mooc1-2.chaoxing.com/ananas/modules/video/index.html"
    private static let audioReferer = "https://mooc1-2.chaoxing.com/ananas/modules/audio/index.html"
    private static let liveReferer = "https://mooc1-2.chaoxing.com/ananas/modules/live/index.html"
    private static let documentReferer = "https://mooc1-2.chaoxing.com/ananas/modules/pdf/index.html"
    private static let encSalt = "d_yHJ!$pdA~5"

    /// Desktop Chrome User-Agent used on PC web study endpoints (`mooc1-2`, `mooc2-ans`, `view=pc`).
    /// Mobile API endpoints such as `mooc1-api.chaoxing.com/gas/clazz` intentionally retain the client's App UA.
    public static let pcWebUserAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private static let pcWebHeaders: [String: String] = [
        "User-Agent": pcWebUserAgent
    ]

    // MARK: - Chapters

    public static func fetchCourseChapters(
        client: ChaoxingHTTPClient,
        courseId: Int64,
        classId: Int,
        knownCpi: Int64 = 0
    ) async throws -> (cpi: Int64, chapters: [ChaoxingChapterNode]) {
        // 1. Fastest & richest path when `cpi` is known: `studentstudycourselist` returns full 3-level tree (`h3`/`h4`/`h5`) + exact `jobUnfinishCount`.
        if knownCpi > 0 {
            if let listChapters = try? await fetchCourseListEndpoint(
                client: client,
                courseId: courseId,
                classId: classId,
                cpi: knownCpi
            ), !listChapters.isEmpty {
                return (knownCpi, listChapters)
            }
        }

        // 2. Primary HTML redirect path: `visit/stucoursemiddle` yields valid `cpi` and `studentcourse` page.
        var discoveredCpi = knownCpi
        var confirmedValidEmptyPage = false
        if let middleURL = URL(
            string: "\(studyBase)/visit/stucoursemiddle?courseid=\(courseId)&clazzid=\(classId)&vc=1&cpi=\(max(0, knownCpi))&ismooc2=0"
        ) {
            if let html = try? await getHTMLWithReauth(
                client: client,
                url: middleURL,
                headers: pcWebHeaders,
                failureMessage: "获取课程章节失败"
            ) {
                let parsed = try parseCourseChaptersHTML(html, courseId: courseId, classId: classId)
                if parsed.cpi > 0 {
                    discoveredCpi = parsed.cpi
                }
                if discoveredCpi > 0,
                   let enriched = try? await fetchCourseListEndpoint(
                    client: client,
                    courseId: courseId,
                    classId: classId,
                    cpi: discoveredCpi
                   ), !enriched.isEmpty {
                    return (discoveredCpi, enriched)
                }
                if !parsed.chapters.isEmpty {
                    return (discoveredCpi, parsed.chapters)
                }
                if html.contains("暂无章节") || html.contains("studentcourse") {
                    confirmedValidEmptyPage = true
                }
            }
        }

        // 3. `mooc2-ans/mycourse/studentcourse` fallback (supports modern `toOld(...)` chapter lists & empty-course confirmation).
        if let mooc2URL = URL(
            string: "\(mooc2Base)/mooc2-ans/mycourse/studentcourse?courseid=\(courseId)&clazzid=\(classId)&cpi=\(max(0, discoveredCpi))&ut=s"
        ) {
            if let html = try? await getHTMLWithReauth(
                client: client,
                url: mooc2URL,
                headers: pcWebHeaders,
                failureMessage: "获取新版课程章节失败"
            ) {
                let parsed = try parseCourseChaptersHTML(html, courseId: courseId, classId: classId)
                if parsed.cpi > 0 {
                    discoveredCpi = parsed.cpi
                }
                if !parsed.chapters.isEmpty {
                    return (discoveredCpi, parsed.chapters)
                }
                if html.contains("暂无章节内容") || html.contains("已完成任务点") {
                    confirmedValidEmptyPage = true
                }
            }
        }

        // 4. Fallback: `mooc1-api.chaoxing.com/gas/clazz` JSON knowledge tree (requires default Chaoxing App UA).
        guard let gasURL = URL(
            string: "https://mooc1-api.chaoxing.com/gas/clazz?id=\(classId)&fields=id,cpi,name,course.fields(id,name,knowledge.fields(id,name,indexOrder,parentnodeid,status,layer,label))&view=json"
        ) else {
            throw ChaoxingError.invalidInput("无效的课程章节接口地址")
        }
        let (data, response) = try await getWithReauth(client: client, url: gasURL, reauthOnForbidden: true)
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "获取课程章节失败 (\(response.statusCode))")
        }
        let gasParsed = try parseGasClazzChaptersJSONDetailed(data, courseId: courseId, classId: classId)
        let finalCpi = gasParsed.cpi > 0 ? gasParsed.cpi : discoveredCpi
        if finalCpi > 0,
           let enriched = try? await fetchCourseListEndpoint(
            client: client,
            courseId: courseId,
            classId: classId,
            cpi: finalCpi
           ), !enriched.isEmpty {
            return (finalCpi, enriched)
        }
        if !gasParsed.chapters.isEmpty {
            return (finalCpi, gasParsed.chapters)
        }
        // Legitimately empty course (teacher has published 0 chapters): return empty cleanly instead of throwing an error.
        if gasParsed.isValidCourseResponse || confirmedValidEmptyPage {
            return (finalCpi, [])
        }
        throw ChaoxingError.parseFailed("未解析到课程章节，可能需要重新登录")
    }

    private static func fetchCourseListEndpoint(
        client: ChaoxingHTTPClient,
        courseId: Int64,
        classId: Int,
        cpi: Int64
    ) async throws -> [ChaoxingChapterNode] {
        guard let url = URL(
            string: "\(studyBase)/mooc-ans/mycourse/studentstudycourselist?courseId=\(courseId)&chapterId=0&clazzid=\(classId)&cpi=\(cpi)"
        ) else {
            return []
        }
        var headers = pcWebHeaders
        headers["X-Requested-With"] = "XMLHttpRequest"
        let html = try await getHTMLWithReauth(
            client: client,
            url: url,
            headers: headers,
            failureMessage: "获取章节列表失败"
        )
        let parsed = try parseCourseChaptersHTML(html, courseId: courseId, classId: classId, hasExplicitJobCounts: true)
        return parsed.chapters
    }

    public static func parseGasClazzChaptersJSON(
        _ data: Data,
        courseId: Int64,
        classId: Int
    ) throws -> (cpi: Int64, chapters: [ChaoxingChapterNode]) {
        let detailed = try parseGasClazzChaptersJSONDetailed(data, courseId: courseId, classId: classId)
        return (detailed.cpi, detailed.chapters)
    }

    private static func parseGasClazzChaptersJSONDetailed(
        _ data: Data,
        courseId: Int64,
        classId: Int
    ) throws -> (cpi: Int64, chapters: [ChaoxingChapterNode], isValidCourseResponse: Bool) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let clazzArr = json["data"] as? [[String: Any]],
              let clazz = clazzArr.first else {
            return (0, [], false)
        }
        let cpi = ChaoxingCourseService.positiveIdentifier(clazz["cpi"]) ?? 0
        guard let courseWrapper = clazz["course"] as? [String: Any],
              let courseArr = courseWrapper["data"] as? [[String: Any]],
              let courseObj = courseArr.first else {
            return (cpi, [], true)
        }
        guard let knowledgeWrapper = courseObj["knowledge"] as? [String: Any],
              let rawNodes = knowledgeWrapper["data"] as? [Any] else {
            return (cpi, [], true)
        }

        struct RawNode {
            let id: Int64
            let name: String
            let label: String
            let indexOrder: Int
            let parentId: Int64
            let layer: Int
        }

        var seenIDs = Set<Int64>()
        let nodes: [RawNode] = rawNodes.compactMap { raw in
            guard let item = raw as? [String: Any],
                  let id = ChaoxingCourseService.positiveIdentifier(item["id"]),
                  seenIDs.insert(id).inserted else { return nil }
            let name = (item["name"] as? String) ?? ""
            let label = (item["label"] as? String) ?? ""
            let indexOrder = (item["indexOrder"] as? NSNumber)?.intValue ?? 0
            let parentId = ChaoxingCourseService.positiveIdentifier(item["parentnodeid"]) ?? 0
            let layer = (item["layer"] as? NSNumber)?.intValue ?? 1
            return RawNode(id: id, name: name, label: label, indexOrder: indexOrder, parentId: parentId, layer: layer)
        }

        let byId = Dictionary(uniqueKeysWithValues: nodes.map { ($0.id, $0) })
        let parentIds = Set(nodes.map(\.parentId).filter { $0 > 0 })

        func orderPath(for node: RawNode) -> [Int] {
            var path: [Int] = [node.indexOrder]
            var current = node
            var guardDepth = 0
            while current.parentId > 0, let parent = byId[current.parentId], guardDepth < 8 {
                path.append(parent.indexOrder)
                current = parent
                guardDepth += 1
            }
            return path.reversed()
        }

        let leafNodes = nodes.filter { !parentIds.contains($0.id) }
            .sorted { lhs, rhs in
                let lp = orderPath(for: lhs)
                let rp = orderPath(for: rhs)
                for (a, b) in zip(lp, rp) where a != b {
                    return a < b
                }
                if lp.count != rp.count { return lp.count < rp.count }
                return lhs.id < rhs.id
            }

        let chapters = leafNodes.map { node in
            var ancestors: [String] = []
            var curParentId = node.parentId
            var depth = 0
            while curParentId > 0, let p = byId[curParentId], depth < 6 {
                let text = p.label.isEmpty ? p.name : "\(p.label) \(p.name)"
                if !text.isEmpty { ancestors.append(text) }
                curParentId = p.parentId
                depth += 1
            }
            let sectionTitle = ancestors.reversed().joined(separator: " / ")
            let title = node.label.isEmpty ? node.name : "\(node.label) \(node.name)"
            return ChaoxingChapterNode(
                id: String(node.id),
                title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                sectionTitle: sectionTitle.trimmingCharacters(in: .whitespacesAndNewlines),
                courseId: courseId,
                classId: classId,
                pendingJobCount: nil
            )
        }
        return (cpi, chapters, true)
    }

    public static func parseCourseChaptersHTML(
        _ html: String,
        courseId: Int64,
        classId: Int,
        hasExplicitJobCounts: Bool = false
    ) throws -> (cpi: Int64, chapters: [ChaoxingChapterNode]) {
        let cpi = extractCpi(from: html) ?? 0
        var chapters: [ChaoxingChapterNode] = []
        var seenIds: Set<String> = []

        // Format A: Classic `studentcourse` page (`<a href="...studentstudy?chapterId=..." aria-label="...">`)
        if !hasExplicitJobCounts {
            let linkPattern = #"<a\b([^>]*studentstudy\?chapterId=(\d+)[^>]*)>([\s\S]*?)</a>"#
            if let linkRegex = try? NSRegularExpression(pattern: linkPattern, options: [.caseInsensitive]) {
                let nsHTML = html as NSString
                let matches = linkRegex.matches(in: html, range: NSRange(location: 0, length: nsHTML.length))
                for match in matches {
                    let attrs = nsHTML.substring(with: match.range(at: 1))
                    let chapterId = nsHTML.substring(with: match.range(at: 2))
                    let inner = nsHTML.substring(with: match.range(at: 3))
                    guard !chapterId.isEmpty, !seenIds.contains(chapterId) else { continue }
                    seenIds.insert(chapterId)

                    let aria = firstRegexGroup(pattern: #"aria-label\s*=\s*["']([^"']+)["']"#, in: attrs)
                    let title = (aria.map { stripHTML($0) } ?? stripHTML(inner))
                    chapters.append(
                        ChaoxingChapterNode(
                            id: chapterId,
                            title: title.isEmpty ? "章节 \(chapterId)" : title,
                            sectionTitle: "",
                            courseId: courseId,
                            classId: classId,
                            pendingJobCount: nil
                        )
                    )
                }
                if !chapters.isEmpty {
                    return (cpi, chapters)
                }
            }
        }

        // Format B: `studentstudycourselist` / `studentstudy` (`<h3>` + `<h4 id="cur...">` + `<h5 id="cur...">`)
        let pattern = #"<(h3|h4|h5)\b([^>]*)>([\s\S]*?)</\1>"#
        if let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) {
            let nsHTML = html as NSString
            let matches = regex.matches(in: html, range: NSRange(location: 0, length: nsHTML.length))
            var currentH3 = ""
            var currentH4 = ""

            for match in matches {
                let tag = nsHTML.substring(with: match.range(at: 1)).lowercased()
                let attrs = nsHTML.substring(with: match.range(at: 2))
                let inner = nsHTML.substring(with: match.range(at: 3))

                if tag == "h3" {
                    let text = stripHTML(inner)
                    if !text.isEmpty, text != "提示" {
                        currentH3 = text
                        currentH4 = ""
                    }
                    continue
                }

                let chapterId = firstRegexGroup(
                    pattern: #"getTeacherAjax\s*\(\s*['"][^'"]*['"]\s*,\s*['"][^'"]*['"]\s*,\s*['"](\d+)['"]"#,
                    in: inner
                ) ?? firstRegexGroup(pattern: #"id\s*=\s*['"]cur(\d+)['"]"#, in: attrs)

                guard let chapterId, !chapterId.isEmpty, !seenIds.contains(chapterId) else {
                    continue
                }
                seenIds.insert(chapterId)

                let rawText = stripHTML(inner)
                let (badgeCount, cleanTitle) = splitLeadingPendingBadge(rawText)
                let finalTitle = cleanTitle.isEmpty ? "章节 \(chapterId)" : cleanTitle

                let sectionTitle: String
                if tag == "h4" {
                    currentH4 = finalTitle
                    sectionTitle = currentH3
                } else {
                    if !currentH3.isEmpty && !currentH4.isEmpty {
                        sectionTitle = "\(currentH3) / \(currentH4)"
                    } else {
                        sectionTitle = currentH4.isEmpty ? currentH3 : currentH4
                    }
                }

                let pendingCount = extractPendingJobCount(
                    from: inner,
                    leadingBadgeCount: badgeCount,
                    hasExplicitJobCounts: hasExplicitJobCounts
                )

                chapters.append(
                    ChaoxingChapterNode(
                        id: chapterId,
                        title: finalTitle,
                        sectionTitle: sectionTitle,
                        courseId: courseId,
                        classId: classId,
                        pendingJobCount: pendingCount
                    )
                )
            }
            if !chapters.isEmpty {
                return (cpi, chapters)
            }
        }

        // Format C: `mooc2-ans/mycourse/studentcourse` (`<div class="chapter_item" ... onclick="toOld('courseId','chapterId','clazzId',...)" title="...">`)
        let mooc2Pattern = #"<div\b([^>]*\bchapter_item\b[^>]*)>([\s\S]*?)</div>\s*</div>"#
        if let mooc2Regex = try? NSRegularExpression(pattern: mooc2Pattern, options: [.caseInsensitive]) {
            let nsHTML = html as NSString
            let matches = mooc2Regex.matches(in: html, range: NSRange(location: 0, length: nsHTML.length))
            for match in matches {
                let attrs = nsHTML.substring(with: match.range(at: 1))
                let inner = nsHTML.substring(with: match.range(at: 2))
                let chapterId = firstRegexGroup(
                    pattern: #"toOld\s*\(\s*['"][^'"]*['"]\s*,\s*['"](\d+)['"]"#,
                    in: attrs
                ) ?? firstRegexGroup(pattern: #"id\s*=\s*['"]cur(\d+)['"]"#, in: attrs)
                guard let chapterId, !chapterId.isEmpty, !seenIds.contains(chapterId) else { continue }
                seenIds.insert(chapterId)

                let sbar = firstRegexGroup(pattern: #"class\s*=\s*["'][^"']*catalog_sbar[^"']*["'][^>]*>([^<]+)<"#, in: inner)
                    .map { stripHTML($0) } ?? ""
                let titleAttr = firstRegexGroup(pattern: #"title\s*=\s*["']([^"']+)["']"#, in: attrs)
                    .map { stripHTML($0) } ?? ""
                let combinedTitle: String
                if !sbar.isEmpty && !titleAttr.isEmpty && !titleAttr.hasPrefix(sbar) {
                    combinedTitle = "\(sbar) \(titleAttr)"
                } else if !titleAttr.isEmpty {
                    combinedTitle = titleAttr
                } else {
                    combinedTitle = stripHTML(inner)
                }

                let pendingCount: Int?
                if let numStr = firstRegexGroup(pattern: #"class\s*=\s*["'][^"']*catalog_points_yi[^"']*["'][^>]*>(\d+)<"#, in: inner),
                   let num = Int(numStr) {
                    pendingCount = num
                } else if inner.contains("icon_yiwanc") {
                    pendingCount = 0
                } else {
                    pendingCount = nil
                }

                chapters.append(
                    ChaoxingChapterNode(
                        id: chapterId,
                        title: combinedTitle.isEmpty ? "章节 \(chapterId)" : combinedTitle,
                        sectionTitle: "",
                        courseId: courseId,
                        classId: classId,
                        pendingJobCount: pendingCount
                    )
                )
            }
        }

        return (cpi, chapters)
    }

    private static func extractPendingJobCount(
        from inner: String,
        leadingBadgeCount: Int?,
        hasExplicitJobCounts: Bool
    ) -> Int? {
        // 1. Exact `<input type="hidden" value="N" class="jobUnfinishCount"/>`
        if let hiddenStr = firstRegexGroup(
            pattern: #"<input[^>]*value\s*=\s*["'](\d+)["'][^>]*class\s*=\s*["'][^"']*jobUnfinishCount[^"']*["']"#,
            in: inner
        ) ?? firstRegexGroup(
            pattern: #"<input[^>]*class\s*=\s*["'][^"']*jobUnfinishCount[^"']*["'][^>]*value\s*=\s*["'](\d+)["']"#,
            in: inner
        ), let val = Int(hiddenStr) {
            return val
        }

        // 2. Explicit orange badge `<span class="...orange01...jobCount">N</span>`
        if let orangeStr = firstRegexGroup(
            pattern: #"<span[^>]*class\s*=\s*["'][^"']*(?:orange01|jobCount)[^"']*["'][^>]*>\s*(\d+)\s*<"#,
            in: inner
        ), let val = Int(orangeStr) {
            return val
        }

        // 3. Leading numeric badge before chapter title (e.g. `<span>1</span> 1.1 Title`)
        if let leadingBadgeCount {
            return leadingBadgeCount
        }

        // 4. Explicit review / lock markers MUST stay `nil` (unknown) so unpassed videos like `roundpointStudent review` are never skipped.
        if inner.range(of: #"class\s*=\s*["'][^"']*roundpoint[^"']*\b(review|lock)\b"#, options: .regularExpression) != nil {
            return nil
        }

        // 5. Explicit completed (`blue`) or zero-job (`noJob`) markers
        if inner.range(of: #"class\s*=\s*["'][^"']*roundpoint[^"']*\b(blue|noJob)\b"#, options: .regularExpression) != nil {
            return 0
        }

        return hasExplicitJobCounts ? 0 : nil
    }

    // MARK: - Chapter Content & Tasks (Multi-tab `cardcount` + `knowledge/cards` + `/ananas/status`)

    public static func fetchChapterCardCount(
        client: ChaoxingHTTPClient,
        courseId: Int64,
        classId: Int,
        chapterId: String,
        cpi: Int64
    ) async -> Int {
        guard let ajaxURL = URL(
            string: "\(studyBase)/mooc-ans/mycourse/studentstudyAjax?courseId=\(courseId)&clazzid=\(classId)&chapterId=\(chapterId)&cpi=\(cpi)&verificationcode="
        ) else {
            return 1
        }
        var headers = pcWebHeaders
        headers["X-Requested-With"] = "XMLHttpRequest"
        guard let html = try? await getHTMLWithReauth(
            client: client,
            url: ajaxURL,
            headers: headers,
            failureMessage: "获取章节标签数失败"
        ) else {
            return 1
        }
        return parseCardCount(from: html)
    }

    public static func parseCardCount(from html: String) -> Int {
        let countStr = firstRegexGroup(
            pattern: #"id\s*=\s*["']cardcount["'][^>]*value\s*=\s*["'](\d+)["']"#,
            in: html
        ) ?? firstRegexGroup(
            pattern: #"value\s*=\s*["'](\d+)["'][^>]*id\s*=\s*["']cardcount["']"#,
            in: html
        )
        if let countStr, let val = Int(countStr), val >= 1 {
            return min(val, 12)
        }
        return 1
    }

    public static func fetchChapterVideos(
        client: ChaoxingHTTPClient,
        courseId: Int64,
        classId: Int,
        chapterId: String,
        cpi: Int64
    ) async throws -> [ChaoxingVideoTask] {
        let content = try await fetchChapterContent(
            client: client,
            courseId: courseId,
            classId: classId,
            chapterId: chapterId,
            cpi: cpi
        )
        return content.videos
    }

    /// Fetches all video tasks and non-video attachments (PDF/PPT documents, quizzes, live, audio) across all tabs of a chapter.
    public static func fetchChapterContent(
        client: ChaoxingHTTPClient,
        courseId: Int64,
        classId: Int,
        chapterId: String,
        cpi: Int64
    ) async throws -> ChaoxingChapterContent {
        var resolvedCpi = cpi
        if resolvedCpi <= 0 {
            let fetched = try await fetchCourseChapters(client: client, courseId: courseId, classId: classId)
            resolvedCpi = fetched.cpi
        }
        let immutableCpi = resolvedCpi

        let cardCount = await fetchChapterCardCount(
            client: client,
            courseId: courseId,
            classId: classId,
            chapterId: chapterId,
            cpi: immutableCpi
        )

        struct CardTabResult: Sendable {
            let tabIndex: Int
            let fid: String
            let content: ChaoxingChapterContent
            let error: Error?
        }

        let perCardResults = await withTaskGroup(of: CardTabResult.self) { group in
            for num in 0..<cardCount {
                group.addTask {
                    guard let cardsURL = URL(
                        string: "\(studyBase)/mooc-ans/knowledge/cards?clazzid=\(classId)&courseid=\(courseId)&knowledgeid=\(chapterId)&num=\(num)&ut=s&cpi=\(immutableCpi)&mooc2=0&isMicroCourse=false&editorPreview=0"
                    ) else {
                        return CardTabResult(tabIndex: num, fid: "0", content: ChaoxingChapterContent(videos: []), error: nil)
                    }
                    do {
                        let html = try await getHTMLWithReauth(
                            client: client,
                            url: cardsURL,
                            headers: pcWebHeaders,
                            failureMessage: "获取章节内容失败"
                        )
                        let cardsFid = firstRegexGroup(pattern: #""fid"\s*:\s*['"]?(\d+)['"]?"#, in: html) ?? "0"
                        let parsed = try parseChapterCardsContent(
                            html,
                            courseId: courseId,
                            classId: classId,
                            chapterId: chapterId,
                            fallbackCpi: immutableCpi
                        )
                        return CardTabResult(tabIndex: num, fid: cardsFid, content: parsed, error: nil)
                    } catch {
                        return CardTabResult(tabIndex: num, fid: "0", content: ChaoxingChapterContent(videos: []), error: error)
                    }
                }
            }
            var collected: [CardTabResult] = []
            for await item in group {
                collected.append(item)
            }
            return collected.sorted { $0.tabIndex < $1.tabIndex }
        }

        // If EVERY card tab failed with an error, propagate the first error instead of misleadingly returning empty.
        if perCardResults.allSatisfy({ $0.error != nil }), let firstError = perCardResults.first?.error {
            throw firstError
        }

        var uniqueVideos: [(task: ChaoxingVideoTask, fid: String)] = []
        var seenVideoIds: Set<String> = []
        var uniqueLives: [ChaoxingLiveTask] = []
        var seenLiveIds: Set<String> = []
        var uniqueOthers: [ChaoxingChapterAttachment] = []
        var seenOtherIds: Set<String> = []

        for tab in perCardResults {
            for v in tab.content.videos where !seenVideoIds.contains(v.id) {
                seenVideoIds.insert(v.id)
                uniqueVideos.append((v, tab.fid))
            }
            for live in tab.content.liveTasks where !seenLiveIds.contains(live.id) {
                seenLiveIds.insert(live.id)
                uniqueLives.append(live)
            }
            for o in tab.content.otherAttachments where !seenOtherIds.contains(o.id) {
                seenOtherIds.insert(o.id)
                uniqueOthers.append(o)
            }
        }

        // Resolve `/ananas/status/{objectId}` concurrently for videos/audios AND `/ananas/live/liveinfo` for live replays.
        async let enrichedVideosTask: [ChaoxingVideoTask] = withTaskGroup(of: (Int, ChaoxingVideoTask).self) { group in
            for (idx, pair) in uniqueVideos.enumerated() {
                group.addTask {
                    let task = pair.task
                    if let status = try? await fetchVideoAssetStatus(client: client, objectId: task.objectId, fid: pair.fid) {
                        let updatedDuration = status.duration > 0 ? status.duration : task.duration
                        let updatedTask = ChaoxingVideoTask(
                            courseId: task.courseId,
                            classId: task.classId,
                            chapterId: task.chapterId,
                            cpi: task.cpi,
                            userid: task.userid,
                            jobid: task.jobid,
                            objectId: task.objectId,
                            name: task.name,
                            otherInfo: task.otherInfo,
                            dtoken: status.dtoken.isEmpty ? task.dtoken : status.dtoken,
                            duration: updatedDuration,
                            attDuration: task.attDuration > 0 ? task.attDuration : updatedDuration,
                            attDurationEnc: task.attDurationEnc,
                            videoFaceCaptureEnc: task.videoFaceCaptureEnc,
                            rt: task.rt,
                            doublespeed: task.doublespeed,
                            reportTimeInterval: task.reportTimeInterval,
                            headOffsetMs: task.headOffsetMs,
                            playTimeMs: task.playTimeMs,
                            isPassed: task.isPassed,
                            isJob: task.isJob,
                            begins: task.begins,
                            ends: task.ends,
                            requiresFaceCapture: task.requiresFaceCapture,
                            reportUrl: task.reportUrl,
                            videoPlayURL: status.videoPlayURL ?? task.videoPlayURL,
                            screenshotURL: status.screenshotURL ?? task.screenshotURL,
                            dtype: task.dtype
                        )
                        return (idx, updatedTask)
                    }
                    return (idx, task)
                }
            }
            var out: [(Int, ChaoxingVideoTask)] = []
            for await item in group {
                out.append(item)
            }
            return out.sorted { $0.0 < $1.0 }.map(\.1)
        }

        async let enrichedLivesTask: [ChaoxingLiveTask] = withTaskGroup(of: (Int, ChaoxingLiveTask).self) { group in
            for (idx, live) in uniqueLives.enumerated() {
                group.addTask {
                    if let info = try? await fetchLiveInfo(client: client, task: live) {
                        let resolvedLiveId = info.resolvedLiveId.isEmpty ? live.liveId : info.resolvedLiveId
                        let baseUpdated = live.updating(
                            playbackTimelongMs: info.playbackTimelongMs,
                            percentValue: info.percentValue,
                            duration: info.duration > 0 ? info.duration : live.duration,
                            liveStatus: info.liveStatus,
                            ifReview: info.ifReview,
                            title: info.title.isEmpty ? live.title : info.title,
                            liveId: resolvedLiveId,
                            streamName: info.streamName.isEmpty ? live.streamName : info.streamName,
                            vdoid: info.vdoid.isEmpty ? live.vdoid : info.vdoid,
                            playURL: info.preferredPlayURL ?? live.playURL,
                            fallbackPlayURL: info.fallbackPlayURL ?? live.fallbackPlayURL
                        )
                        let webURL = buildLiveWebURL(for: baseUpdated, courseEndState: info.courseEndState)
                        return (idx, baseUpdated.updating(webPageURL: webURL))
                    }
                    return (idx, live)
                }
            }
            var out: [(Int, ChaoxingLiveTask)] = []
            for await item in group {
                out.append(item)
            }
            return out.sorted { $0.0 < $1.0 }.map(\.1)
        }

        let (enrichedVideos, enrichedLives) = await (enrichedVideosTask, enrichedLivesTask)
        return ChaoxingChapterContent(
            videos: enrichedVideos,
            liveTasks: enrichedLives,
            otherAttachments: uniqueOthers
        )
    }

    public static func parseChapterCardsHTML(
        _ html: String,
        courseId: Int64,
        classId: Int,
        chapterId: String,
        fallbackCpi: Int64 = 0
    ) throws -> [ChaoxingVideoTask] {
        try parseChapterCardsContent(
            html,
            courseId: courseId,
            classId: classId,
            chapterId: chapterId,
            fallbackCpi: fallbackCpi
        ).videos
    }

    public static func parseChapterCardsContent(
        _ html: String,
        courseId: Int64,
        classId: Int,
        chapterId: String,
        fallbackCpi: Int64 = 0
    ) throws -> ChaoxingChapterContent {
        guard let jsonString = extractMArgJSONObject(from: html),
              let data = jsonString.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return ChaoxingChapterContent(videos: [])
        }

        let defaults = root["defaults"] as? [String: Any] ?? [:]
        let cpi = (defaults["cpi"] as? NSNumber)?.int64Value ?? fallbackCpi
        let userid = stringValue(defaults["userid"]) ?? ""
        let reportTimeInterval = (defaults["reportTimeInterval"] as? NSNumber)?.intValue ?? 60
        let schoolDoubleSpeed = intValue(defaults["schooldoublespeed"]) ?? 1
        let chapterCapture = intValue(defaults["chapterCapture"]) ?? 0
        let rawReportUrl = (defaults["reportUrl"] as? String) ?? "\(studyBase)/mooc-ans/multimedia/log/a/\(cpi)"
        let normalizedReportUrl = rawReportUrl.hasPrefix("//") ? "https:" + rawReportUrl : rawReportUrl
        let reportUrl = normalizedReportUrl.replacingOccurrences(of: "http://", with: "https://")

        let attachments = root["attachments"] as? [[String: Any]] ?? []
        var videos: [ChaoxingVideoTask] = []
        var liveTasks: [ChaoxingLiveTask] = []
        var others: [ChaoxingChapterAttachment] = []

        for item in attachments {
            let typeStr = (item["type"] as? String)?.lowercased() ?? ""
            let property = item["property"] as? [String: Any] ?? [:]
            let moduleStr = (property["module"] as? String)?.lowercased() ?? ""
            let jobid = (item["jobid"] as? String)
                ?? (property["jobid"] as? String)
                ?? (property["_jobid"] as? String)
                ?? ""
            let rawJobFlag = boolValue(item["job"])
            let isJob = rawJobFlag ?? !jobid.isEmpty
            let rawPassedFlag = boolValue(item["isPassed"])
            let isPassed = rawPassedFlag ?? false
            let objectId = (property["objectid"] as? String) ?? (item["objectid"] as? String) ?? ""

            let isAudioAsset = (typeStr == "audio" || moduleStr == "insertaudio") && !objectId.isEmpty
            if typeStr == "video" || isAudioAsset {
                guard !objectId.isEmpty else { continue }

                let name = (property["name"] as? String) ?? (isAudioAsset ? "课程音频" : "课程视频")
                let rawOtherInfo = (item["otherInfo"] as? String) ?? ""
                let cleanOtherInfo = rawOtherInfo.components(separatedBy: "&").first ?? rawOtherInfo
                let headOffsetMs = intValue(item["headOffset"]) ?? 0
                let playTimeMs = intValue(item["playTime"]) ?? 0
                let attDuration = intValue(item["attDuration"]) ?? 0
                let attDurationEnc = (item["attDurationEnc"] as? String) ?? ""
                let videoFaceCaptureEnc = (item["videoFaceCaptureEnc"] as? String) ?? ""
                let begins = intValue(item["begins"]) ?? 0
                let ends = intValue(item["ends"]) ?? 0
                let rt = doubleValue(item["rt"]) ?? doubleValue(property["rt"]) ?? 0.9

                // Replicate Chaoxing `index.js` effective doublespeed precedence.
                let propDoubleSpeed = intValue(property["doublespeed"])
                let effectiveDoubleSpeed: Int
                if isAudioAsset {
                    effectiveDoubleSpeed = 0
                } else if let propDS = propDoubleSpeed {
                    if propDS == 0 {
                        effectiveDoubleSpeed = 0
                    } else if propDS == 2 {
                        effectiveDoubleSpeed = schoolDoubleSpeed
                    } else {
                        effectiveDoubleSpeed = schoolDoubleSpeed == 0 ? 0 : propDS
                    }
                } else {
                    effectiveDoubleSpeed = schoolDoubleSpeed
                }

                let task = ChaoxingVideoTask(
                    courseId: courseId,
                    classId: classId,
                    chapterId: chapterId,
                    cpi: cpi,
                    userid: userid,
                    jobid: jobid,
                    objectId: objectId,
                    name: name,
                    otherInfo: cleanOtherInfo,
                    dtoken: "",
                    duration: attDuration,
                    attDuration: attDuration,
                    attDurationEnc: attDurationEnc,
                    videoFaceCaptureEnc: videoFaceCaptureEnc,
                    rt: rt,
                    doublespeed: effectiveDoubleSpeed,
                    reportTimeInterval: reportTimeInterval,
                    headOffsetMs: headOffsetMs,
                    playTimeMs: playTimeMs,
                    isPassed: isPassed,
                    isJob: isJob,
                    begins: begins,
                    ends: ends,
                    requiresFaceCapture: !isAudioAsset && chapterCapture == 1,
                    reportUrl: reportUrl,
                    dtype: isAudioAsset ? "Audio" : "Video"
                )
                videos.append(task)
                continue
            }

            // Parse `live` / `insertlive` (external `zhibo.chaoxing.com` live replays) into dedicated `ChaoxingLiveTask`.
            if typeStr == "live" || moduleStr == "insertlive" {
                let rawJobId = (item["jobid"] as? String) ?? (property["jobid"] as? String) ?? ""
                let rawUnderscoreJobId = (property["_jobid"] as? String) ?? ""
                var liveId = stringValue(property["liveId"]) ?? stringValue(item["liveId"]) ?? ""
                if liveId.isEmpty, let range = rawUnderscoreJobId.range(of: "live-") {
                    liveId = String(rawUnderscoreJobId[range.upperBound...])
                }
                if !liveId.isEmpty {
                    let streamName = (property["streamName"] as? String) ?? ""
                    let vdoid = (property["vdoid"] as? String) ?? ""
                    let aid = stringValue(item["aid"]) ?? ""
                    let title = ((property["title"] as? String) ?? (property["name"] as? String) ?? "")
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                    let rt = doubleValue(item["rt"]) ?? doubleValue(property["rt"]) ?? 0.9
                    let ds = intValue(property["doublespeed"]) ?? 1
                    let fastforwardRaw = stringValue(property["fastforward"])?.lowercased()
                    let isNotDrag = (fastforwardRaw == nil || fastforwardRaw == "true") ? 1 : 0
                    let switchWindow = stringValue(property["switchwindow"])?.lowercased() == "true" ? 1 : 0
                    let liveSetEnc = (item["liveSetEnc"] as? String) ?? ""
                    let authEnc = (item["authEnc"] as? String) ?? ""
                    let liveDragEnc = (item["liveDragEnc"] as? String) ?? ""
                    let liveSwDsEnc = (item["liveSwDsEnc"] as? String) ?? ""
                    // Per `ananas/modules/live/index.html`: `jobid` present + `item.job == true` means unfinished;
                    // `jobid` present + `item.job` absent/false means already finished (`greenligth()`).
                    // Empty `jobid` means non-job reference live replay.
                    let hasLiveJobId = !rawJobId.isEmpty
                    let liveIsJob = hasLiveJobId
                    let liveIsPassed: Bool
                    if let explicit = rawPassedFlag {
                        liveIsPassed = explicit
                    } else if hasLiveJobId {
                        liveIsPassed = !(rawJobFlag ?? false)
                    } else {
                        liveIsPassed = false
                    }
                    let baseTask = ChaoxingLiveTask(
                        courseId: courseId,
                        classId: classId,
                        chapterId: chapterId,
                        cpi: cpi,
                        userid: userid,
                        jobid: rawJobId,
                        aid: aid,
                        liveId: liveId,
                        streamName: streamName,
                        vdoid: vdoid,
                        title: title.isEmpty ? "直播回放" : title,
                        rt: rt,
                        doublespeed: ds,
                        isNotDrag: isNotDrag,
                        switchWindow: switchWindow,
                        liveSetEnc: liveSetEnc,
                        authEnc: authEnc,
                        liveDragEnc: liveDragEnc,
                        liveSwDsEnc: liveSwDsEnc,
                        isJob: liveIsJob,
                        isPassed: liveIsPassed
                    )
                    let webURL = buildLiveWebURL(for: baseTask)
                    liveTasks.append(baseTask.updating(webPageURL: webURL))
                    continue
                }
            }

            // Parse non-video task points (documents/PDF/PPT, audio, live, quiz/workid) so chapters never look mysteriously empty.
            let kind: ChaoxingAttachmentKind
            if typeStr == "document" || moduleStr == "insertdoc" {
                kind = .document
            } else if typeStr == "audio" || moduleStr == "insertaudio" {
                kind = .audio
            } else if typeStr == "live" || moduleStr == "insertlive" {
                kind = .live
            } else if typeStr == "workid" || moduleStr == "work" {
                kind = .quiz
            } else {
                kind = .other
            }

            let rawName = ((property["name"] as? String) ?? (property["title"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let fallbackName: String
            switch kind {
            case .document: fallbackName = "文档任务点"
            case .audio: fallbackName = "音频任务点"
            case .live: fallbackName = "直播任务点"
            case .quiz: fallbackName = "章节测验"
            case .video, .other: fallbackName = "学习任务点"
            }
            let jtoken = (item["jtoken"] as? String) ?? ""
            let workId = stringValue(property["workid"]) ?? stringValue(item["workid"]) ?? ""
            let workEnc = (item["enc"] as? String) ?? (property["enc"] as? String) ?? ""
            let ktoken = stringValue(defaults["ktoken"]) ?? ""
            let externalURL = (property["url"] as? String)
                ?? (property["href"] as? String)
                ?? (property["link"] as? String)
            // Per Chaoxing `documentJob.js` (`checkJobFinish`): when a non-video job has a non-empty `jobid`
            // and `item.job` is absent/false, the task point is already completed even when `isPassed` is omitted.
            let effectiveOtherPassed: Bool
            if let explicitPassed = rawPassedFlag {
                effectiveOtherPassed = explicitPassed
            } else if !jobid.isEmpty && (rawJobFlag == nil || rawJobFlag == false) {
                effectiveOtherPassed = true
            } else {
                effectiveOtherPassed = false
            }

            others.append(
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
                    name: rawName.isEmpty ? fallbackName : rawName,
                    isJob: isJob,
                    isPassed: effectiveOtherPassed,
                    externalURL: externalURL
                )
            )
        }

        return ChaoxingChapterContent(videos: videos, liveTasks: liveTasks, otherAttachments: others)
    }

    /// Completes a PDF / DOC / PPT document task point using Chaoxing's official `/mooc-ans/job/document` endpoint
    /// (verified against `documentJob.js`).
    public static func completeDocumentTask(
        client: ChaoxingHTTPClient,
        task: ChaoxingChapterAttachment,
        nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) async throws -> Bool {
        guard task.kind == .document, !task.jobid.isEmpty, !task.jtoken.isEmpty else {
            throw ChaoxingError.invalidInput("该文档任务点缺少 jobid 或 jtoken")
        }
        var components = URLComponents(string: "\(studyBase)/mooc-ans/job/document")
        components?.queryItems = [
            URLQueryItem(name: "jobid", value: task.jobid),
            URLQueryItem(name: "knowledgeid", value: task.chapterId),
            URLQueryItem(name: "courseid", value: String(task.courseId)),
            URLQueryItem(name: "clazzid", value: String(task.classId)),
            URLQueryItem(name: "jtoken", value: task.jtoken),
            URLQueryItem(name: "checkMicroTopic", value: "true"),
            URLQueryItem(name: "microTopicId", value: ""),
            URLQueryItem(name: "courseEngineInfo", value: "false"),
            URLQueryItem(name: "_dc", value: String(nowMs))
        ]
        guard let url = components?.url else {
            throw ChaoxingError.invalidInput("构造文档完成请求 URL 失败")
        }
        var headers = pcWebHeaders
        headers["Referer"] = documentReferer
        headers["X-Requested-With"] = "XMLHttpRequest"

        let (data, response) = try await getWithReauth(client: client, url: url, headers: headers)
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "文档任务点上报失败 (\(response.statusCode))")
        }
        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            if let status = boolValue(json["status"]) {
                return status
            }
            if let msg = json["msg"] as? String, msg.lowercased().contains("ok") || msg.contains("成功") || msg.contains("已完成") {
                return true
            }
        }
        let body = String(data: data, encoding: .utf8) ?? ""
        return body.contains("\"status\":true") || body.contains("ok")
    }

    // MARK: - External Live Replay (`zhibo.chaoxing.com`)

    /// Builds the official course-context `zhibo.chaoxing.com` replay URL matching `ananas/modules/live/index.html`.
    public static func buildLiveWebURL(
        for task: ChaoxingLiveTask,
        baseURL: String = zhiboBase,
        courseEndState: Int = 0
    ) -> String {
        let trimmedBase = baseURL.hasSuffix("/") ? String(baseURL.dropLast()) : baseURL
        var components = URLComponents(string: "\(trimmedBase)/\(task.liveId)")
        var query: [URLQueryItem] = []
        if courseEndState != 1 {
            query.append(URLQueryItem(name: "courseId", value: String(task.courseId)))
        }
        let isJobFlag = task.isJob && !task.isPassed
        let effectiveNotDrag = isJobFlag ? task.isNotDrag : 0
        let effectiveSw = isJobFlag ? task.switchWindow : 0
        let effectiveDs = isJobFlag ? task.doublespeed : 1
        query.append(contentsOf: [
            URLQueryItem(name: "classId", value: String(task.classId)),
            URLQueryItem(name: "knowledgeId", value: task.chapterId),
            URLQueryItem(name: "jobId", value: task.jobid),
            URLQueryItem(name: "userId", value: task.userid),
            URLQueryItem(name: "rt", value: formatRT(task.rt)),
            URLQueryItem(name: "livesetenc", value: task.liveSetEnc),
            URLQueryItem(name: "isjob", value: isJobFlag ? "true" : "false"),
            URLQueryItem(name: "watchingInCourse", value: "1"),
            URLQueryItem(name: "customPara1", value: "\(task.classId)_\(task.courseId)"),
            URLQueryItem(name: "customPara2", value: task.authEnc),
            URLQueryItem(name: "jobfs", value: isJobFlag ? "0" : "1"),
            URLQueryItem(name: "isNotDrag", value: String(effectiveNotDrag)),
            URLQueryItem(name: "livedragenc", value: task.liveDragEnc),
            URLQueryItem(name: "sw", value: String(effectiveSw)),
            URLQueryItem(name: "ds", value: String(effectiveDs)),
            URLQueryItem(name: "liveswdsenc", value: task.liveSwDsEnc)
        ])
        components?.queryItems = query
        return components?.url?.absoluteString ?? "\(trimmedBase)/\(task.liveId)"
    }

    /// Parses `/ananas/live/liveinfo` JSON payload into `ChaoxingLiveInfo`.
    public static func parseLiveInfoJSON(_ data: Data, fallbackLiveId: String) throws -> ChaoxingLiveInfo {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let temp = root["temp"] as? [String: Any],
              let detail = temp["data"] as? [String: Any] else {
            throw ChaoxingError.parseFailed("直播详情 JSON 解析失败")
        }
        let resolvedId = stringValue(detail["liveId"]) ?? fallbackLiveId
        let title = (detail["title"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let liveStatus = intValue(detail["liveStatus"]) ?? 4
        let ifReview = intValue(detail["ifReview"]) ?? 0
        let duration = intValue(detail["duration"]) ?? 0
        let playbackMs = intValue(detail["playbackTimelong"]) ?? intValue(detail["timeLong"]) ?? 0
        let percent = doubleValue(detail["percentValue"]) ?? 0
        let streamName = (detail["streamName"] as? String) ?? ""
        let vdoid = (detail["vdoid"] as? String) ?? ""
        let m3u8 = normalizeHTTPSURL(detail["m3u8Url"] as? String)
        let mp4 = normalizeHTTPSURL(detail["mp4Url"] as? String)
        let viewURL = normalizeHTTPSURL(detail["viewUrl"] as? String)
        let courseEndState = intValue(root["courseEndState"]) ?? 0
        return ChaoxingLiveInfo(
            resolvedLiveId: resolvedId.isEmpty ? fallbackLiveId : resolvedId,
            title: title,
            liveStatus: liveStatus,
            ifReview: ifReview,
            duration: duration,
            playbackTimelongMs: playbackMs,
            percentValue: percent,
            streamName: streamName,
            vdoid: vdoid,
            m3u8URL: m3u8,
            mp4URL: mp4,
            viewURL: viewURL,
            courseEndState: courseEndState
        )
    }

    /// Extracts playback session tokens (`shortLiveId`, `uInfo`, `liveduration`, `fid`, `streamName`, `vdoid`, `m3u8`)
    /// from the `zhibo.chaoxing.com` course-replay page HTML.
    public static func parseLiveSessionHTML(
        _ html: String,
        pageURL: String,
        fallbackTask: ChaoxingLiveTask
    ) -> ChaoxingLiveSessionContext {
        let shortLiveId = firstRegexGroup(pattern: #"var\s+liveId\s*=\s*['"]?(\d+)['"]?"#, in: html) ?? fallbackTask.shortLiveId
        let uInfo = firstRegexGroup(pattern: #"var\s+uInfo\s*=\s*['"]([^'"]+)['"]"#, in: html) ?? ""
        let parsedDuration = firstRegexGroup(pattern: #"var\s+liveduration\s*=\s*(\d+)"#, in: html).flatMap(Int.init) ?? 0
        let streamName = firstRegexGroup(pattern: #"var\s+streamName\s*=\s*['"]([^'"]+)['"]"#, in: html) ?? fallbackTask.streamName
        let vdoid = firstRegexGroup(pattern: #"var\s+vdoid\s*=\s*['"]([^'"]+)['"]"#, in: html) ?? fallbackTask.vdoid
        let fidRaw = firstRegexGroup(pattern: #"var\s+fid\s*=\s*['"]?(\d+)['"]?"#, in: html)
        let playURL = firstRegexGroup(pattern: #"urls\s*=\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]"#, in: html)
            ?? firstRegexGroup(pattern: #"'originM3u8Record'\s*:\s*'(https?://[^']+)'"#, in: html)
            ?? fallbackTask.playURL
        return ChaoxingLiveSessionContext(
            pageURL: pageURL,
            shortLiveId: shortLiveId,
            uInfo: uInfo,
            streamName: streamName.isEmpty ? fallbackTask.streamName : streamName,
            vdoid: vdoid.isEmpty ? fallbackTask.vdoid : vdoid,
            duration: parsedDuration > 0 ? parsedDuration : fallbackTask.duration,
            fid: fidRaw,
            playURL: normalizeHTTPSURL(playURL)
        )
    }

    /// Queries `/ananas/live/liveinfo` (converting legacy `<16`-digit `liveId` via `/ananas/live/getnewliveid` first).
    public static func fetchLiveInfo(
        client: ChaoxingHTTPClient,
        task: ChaoxingLiveTask
    ) async throws -> ChaoxingLiveInfo {
        var resolvedLiveId = task.liveId
        if !resolvedLiveId.isEmpty && resolvedLiveId.count < 16,
           let convURL = URL(string: "\(studyBase)/ananas/live/getnewliveid?oliveid=\(resolvedLiveId)") {
            var convHeaders = pcWebHeaders
            convHeaders["Referer"] = liveReferer
            convHeaders["X-Requested-With"] = "XMLHttpRequest"
            if let (convData, convResp) = try? await getWithReauth(client: client, url: convURL, headers: convHeaders),
               (200..<300).contains(convResp.statusCode),
               let convJSON = try? JSONSerialization.jsonObject(with: convData) as? [String: Any],
               let newId = stringValue(convJSON["newLiveId"]), !newId.isEmpty {
                resolvedLiveId = newId
            }
        }

        var components = URLComponents(string: "\(studyBase)/ananas/live/liveinfo")
        components?.queryItems = [
            URLQueryItem(name: "liveid", value: resolvedLiveId),
            URLQueryItem(name: "userid", value: task.userid),
            URLQueryItem(name: "clazzid", value: String(task.classId)),
            URLQueryItem(name: "knowledgeid", value: task.chapterId),
            URLQueryItem(name: "courseid", value: String(task.courseId)),
            URLQueryItem(name: "jobid", value: task.jobid),
            URLQueryItem(name: "ut", value: "s")
        ]
        guard let url = components?.url else {
            throw ChaoxingError.invalidInput("构造直播详情请求 URL 失败")
        }
        var headers = pcWebHeaders
        headers["Referer"] = liveReferer
        headers["X-Requested-With"] = "XMLHttpRequest"
        let (data, response) = try await getWithReauth(client: client, url: url, headers: headers)
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "获取直播详情失败 (\(response.statusCode))")
        }
        return try parseLiveInfoJSON(data, fallbackLiveId: resolvedLiveId)
    }

    /// Binds course↔live job relation (`/mooc-ans/live/relation`), loads the `zhibo.chaoxing.com` course replay
    /// page to initialize cookies/session tokens, and triggers `/live/savecourselive` when `fid` is present.
    public static func prepareLiveReplaySession(
        client: ChaoxingHTTPClient,
        task: ChaoxingLiveTask
    ) async throws -> ChaoxingLiveSessionContext {
        // 1. Bind live job to course chapter (matches `setLiveANDCourseRelation()` in `ananas/modules/live/index.html`).
        if !task.jobid.isEmpty, !task.chapterId.isEmpty {
            var relComp = URLComponents(string: "\(studyBase)/mooc-ans/live/relation")
            relComp?.queryItems = [
                URLQueryItem(name: "courseid", value: String(task.courseId)),
                URLQueryItem(name: "knowledgeid", value: task.chapterId),
                URLQueryItem(name: "ut", value: "s"),
                URLQueryItem(name: "jobid", value: task.jobid),
                URLQueryItem(name: "aid", value: task.aid)
            ]
            if let relURL = relComp?.url {
                var relHeaders = pcWebHeaders
                relHeaders["Referer"] = liveReferer
                relHeaders["X-Requested-With"] = "XMLHttpRequest"
                _ = try? await getWithReauth(client: client, url: relURL, headers: relHeaders)
            }
        }

        // 2. Open `zhibo.chaoxing.com` course-replay page.
        let pageURLString = task.webPageURL ?? buildLiveWebURL(for: task)
        guard let pageURL = URL(string: pageURLString) else {
            throw ChaoxingError.invalidInput("无效的直播回放地址")
        }
        var pageHeaders = pcWebHeaders
        pageHeaders["Referer"] = "\(studyBase)/"
        let (pageData, pageResp) = try await getWithReauth(client: client, url: pageURL, headers: pageHeaders)
        guard (200..<300).contains(pageResp.statusCode) else {
            throw ChaoxingError.networkError(statusCode: pageResp.statusCode, message: "打开直播回放页面失败 (\(pageResp.statusCode))")
        }
        let html = String(data: pageData, encoding: .utf8) ?? ""
        let ctx = parseLiveSessionHTML(html, pageURL: pageURLString, fallbackTask: task)

        // 3. Warm `zhibo.chaoxing.com` SSO iframe using the rendered `uInfo` token
        // (avoids matching the unrendered Thymeleaf `th:if` template literal in the HTML).
        if !ctx.uInfo.isEmpty {
            var ssoComp = URLComponents(string: "\(zhiboBase)/iframeLoginSSO")
            ssoComp?.queryItems = [URLQueryItem(name: "u", value: ctx.uInfo)]
            if let ssoURL = ssoComp?.url {
                var ssoHeaders = pcWebHeaders
                ssoHeaders["Referer"] = pageURLString
                _ = try? await client.get(ssoURL, headers: ssoHeaders)
            }
        } else if let ssoPath = firstRegexGroup(pattern: #"src\s*=\s*["'](/iframeLoginSSO\?u=[A-Za-z0-9_-]+)["']"#, in: html),
                  let ssoURL = URL(string: "\(zhiboBase)\(ssoPath)") {
            var ssoHeaders = pcWebHeaders
            ssoHeaders["Referer"] = pageURLString
            _ = try? await client.get(ssoURL, headers: ssoHeaders)
        }

        // 4. Optional `savecourselive` binding when `fid` is populated on the page.
        if let fid = ctx.fid, !fid.isEmpty, !ctx.streamName.isEmpty, !ctx.vdoid.isEmpty {
            var saveComp = URLComponents(string: "\(zhiboBase)/live/savecourselive")
            saveComp?.queryItems = [
                URLQueryItem(name: "streamName", value: ctx.streamName),
                URLQueryItem(name: "vdoid", value: ctx.vdoid),
                URLQueryItem(name: "courseId", value: String(task.courseId)),
                URLQueryItem(name: "fid", value: fid)
            ]
            if let saveURL = saveComp?.url {
                var saveHeaders = pcWebHeaders
                saveHeaders["Referer"] = pageURLString
                saveHeaders["X-Requested-With"] = "XMLHttpRequest"
                _ = try? await client.get(saveURL, headers: saveHeaders)
            }
        }

        return ctx
    }

    /// Parses `/saveTimePc` JSON response payload into `ChaoxingLiveHeartbeatResponse`.
    public static func parseLiveHeartbeatJSON(_ data: Data) throws -> ChaoxingLiveHeartbeatResponse {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            let preview = String(data: data, encoding: .utf8)?.prefix(120) ?? ""
            throw ChaoxingError.parseFailed("直播心跳响应解析失败: \(preview)")
        }
        let result = intValue(root["result"]) ?? (boolValue(root["status"]) == true ? 1 : 0)
        let msg = (root["msg"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let payload = root["data"] as? [String: Any]
        let accepted = boolValue(payload?["accepted"]) ?? (result == 1)
        let liveObj = payload?["live"] as? [String: Any]
        let duration = intValue(liveObj?["duration"]) ?? 0
        let liveStatus = intValue(liveObj?["livestatus"]) ?? intValue(liveObj?["liveStatus"]) ?? 4
        return ChaoxingLiveHeartbeatResponse(
            result: result,
            accepted: accepted,
            message: msg,
            duration: duration,
            liveStatus: liveStatus
        )
    }

    /// Sends Chaoxing's official 30-second live-replay watch heartbeat (`/saveTimePc`)
    /// plus resume checkpoint (`/apis/live/put/watchMoment`) on `zhibo.chaoxing.com`.
    @discardableResult
    public static func sendLiveHeartbeat(
        client: ChaoxingHTTPClient,
        task: ChaoxingLiveTask,
        session: ChaoxingLiveSessionContext,
        isStart: Bool,
        watchMomentSec: Int,
        nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) async throws -> ChaoxingLiveHeartbeatResponse {
        guard !session.streamName.isEmpty, !session.vdoid.isEmpty else {
            throw ChaoxingError.invalidInput("缺少直播 streamName 或 vdoid")
        }
        var comp = URLComponents(string: "\(zhiboBase)/saveTimePc")
        comp?.queryItems = [
            URLQueryItem(name: "streamName", value: session.streamName),
            URLQueryItem(name: "vdoid", value: session.vdoid),
            URLQueryItem(name: "userId", value: task.userid),
            URLQueryItem(name: "isStart", value: isStart ? "0" : "1"),
            URLQueryItem(name: "t", value: String(nowMs)),
            URLQueryItem(name: "courseId", value: String(task.courseId))
        ]
        guard let url = comp?.url else {
            throw ChaoxingError.invalidInput("构造直播心跳 URL 失败")
        }
        var headers = pcWebHeaders
        headers["Referer"] = session.pageURL
        headers["X-Requested-With"] = "XMLHttpRequest"

        var (data, response) = try await getWithReauth(client: client, url: url, headers: headers)
        if response.statusCode == 403 {
            throw ChaoxingError.networkError(statusCode: 403, message: "直播回放心跳被限流 (403)")
        }
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "直播回放心跳上报失败 (\(response.statusCode))")
        }

        var parsed = try parseLiveHeartbeatJSON(data)
        if parsed.result != 1 || !parsed.accepted {
            let loweredMsg = parsed.message.lowercased()
            if parsed.message.contains("登录") || loweredMsg.contains("login") || loweredMsg.contains("auth") || loweredMsg.contains("session") {
                _ = try await ChaoxingAuthService.reLogin(client: client)
                (data, response) = try await client.get(url, headers: headers)
                if response.statusCode == 403 {
                    throw ChaoxingError.networkError(statusCode: 403, message: "直播回放心跳被限流 (403)")
                }
                guard (200..<300).contains(response.statusCode) else {
                    throw ChaoxingError.networkError(statusCode: response.statusCode, message: "直播回放心跳上报失败 (\(response.statusCode))")
                }
                parsed = try parseLiveHeartbeatJSON(data)
            }
        }
        guard parsed.result == 1 && parsed.accepted else {
            let reason = parsed.message.isEmpty ? "服务端未接收直播心跳 (result=\(parsed.result), accepted=\(parsed.accepted))" : parsed.message
            throw ChaoxingError.serverMessage(reason)
        }

        // Sync resume checkpoint every periodic tick (matches `handleWatchMomentJob()`).
        if !isStart, !session.shortLiveId.isEmpty, !session.uInfo.isEmpty {
            var wmComp = URLComponents(string: "\(zhiboBase)/apis/live/put/watchMoment")
            wmComp?.queryItems = [
                URLQueryItem(name: "liveId", value: session.shortLiveId),
                URLQueryItem(name: "streamName", value: session.streamName),
                URLQueryItem(name: "vdoid", value: session.vdoid),
                URLQueryItem(name: "watchMoment", value: String(max(0, watchMomentSec))),
                URLQueryItem(name: "t", value: String(nowMs)),
                URLQueryItem(name: "u", value: session.uInfo)
            ]
            if let wmURL = wmComp?.url {
                _ = try? await client.get(wmURL, headers: headers)
            }
        }
        return parsed
    }

    private static func normalizeHTTPSURL(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        if trimmed.hasPrefix("//") {
            return "https:" + trimmed
        }
        return trimmed.replacingOccurrences(of: "http://", with: "https://")
    }

    public struct VideoAssetStatus: Sendable, Equatable {
        public let dtoken: String
        public let duration: Int
        public let videoPlayURL: String?
        public let screenshotURL: String?
    }

    public static func fetchVideoAssetStatus(
        client: ChaoxingHTTPClient,
        objectId: String,
        fid: String
    ) async throws -> VideoAssetStatus {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        guard let url = URL(string: "\(studyBase)/ananas/status/\(objectId)?k=\(fid)&flag=normal&ro=0&_dc=\(nowMs)") else {
            throw ChaoxingError.invalidInput("无效的视频状态地址")
        }
        var headers = pcWebHeaders
        headers["Referer"] = videoReferer
        headers["X-Requested-With"] = "XMLHttpRequest"

        let (data, response) = try await getWithReauth(client: client, url: url, headers: headers)
        guard (200..<300).contains(response.statusCode),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ChaoxingError.parseFailed("视频元数据解析失败")
        }

        let dtoken = (json["dtoken"] as? String) ?? ""
        let duration = (json["duration"] as? NSNumber)?.intValue ?? 0
        let rawPlayURL = (json["http"] as? String)
            ?? (json["https"] as? String)
            ?? (json["httphd"] as? String)
            ?? (json["httpmd"] as? String)
            ?? (json["hls"] as? String)
        let playURL = rawPlayURL?.replacingOccurrences(of: "http://", with: "https://")
        let shotURL = (json["screenshot"] as? String)?.replacingOccurrences(of: "http://", with: "https://")
        return VideoAssetStatus(dtoken: dtoken, duration: duration, videoPlayURL: playURL, screenshotURL: shotURL)
    }

    // MARK: - Video Log Signing & Reporting

    /// Computes Chaoxing's MD5 log signature:
    /// `md5("[{clazzId}][{userId}][{jobId}][{objectId}][{playingTimeMs}][d_yHJ!$pdA~5][{durationMs}][{clipTime}]")`.
    public static func buildEncSignature(
        clazzId: Int,
        userId: String,
        jobId: String,
        objectId: String,
        playingTimeMs: Int,
        durationMs: Int,
        clipTime: String
    ) -> String {
        let raw = "[\(clazzId)][\(userId)][\(jobId)][\(objectId)][\(playingTimeMs)][\(encSalt)][\(durationMs)][\(clipTime)]"
        return ChaoxingCrypto.md5Hex(raw)
    }

    public static func sendVideoLog(
        client: ChaoxingHTTPClient,
        task: ChaoxingVideoTask,
        playingTimeSec: Int,
        event: ChaoxingVideoDragEvent,
        nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) async throws -> ChaoxingVideoLogResponse {
        guard !task.dtoken.isEmpty else {
            throw ChaoxingError.invalidInput("缺少视频 dtoken")
        }
        let duration = task.totalDuration
        let clipStart = task.effectiveClipStart
        let clipEnd = max(clipStart, task.effectiveClipEnd)
        let clampedSec = max(clipStart, min(playingTimeSec, clipEnd > 0 ? clipEnd : playingTimeSec))
        let clipTime = task.effectiveClipTime

        let enc = buildEncSignature(
            clazzId: task.classId,
            userId: task.userid,
            jobId: task.jobid,
            objectId: task.objectId,
            playingTimeMs: clampedSec * 1000,
            durationMs: duration * 1000,
            clipTime: clipTime
        )

        var components = URLComponents(string: "\(task.reportUrl)/\(task.dtoken)")
        components?.queryItems = [
            URLQueryItem(name: "clazzId", value: String(task.classId)),
            URLQueryItem(name: "playingTime", value: String(clampedSec)),
            URLQueryItem(name: "duration", value: String(duration)),
            URLQueryItem(name: "clipTime", value: clipTime),
            URLQueryItem(name: "objectId", value: task.objectId),
            URLQueryItem(name: "otherInfo", value: task.otherInfo),
            URLQueryItem(name: "courseId", value: String(task.courseId)),
            URLQueryItem(name: "jobid", value: task.jobid),
            URLQueryItem(name: "userid", value: task.userid),
            URLQueryItem(name: "isdrag", value: String(event.rawValue)),
            URLQueryItem(name: "view", value: "pc"),
            URLQueryItem(name: "enc", value: enc),
            URLQueryItem(name: "rt", value: formatRT(task.rt)),
            URLQueryItem(name: "videoFaceCaptureEnc", value: task.videoFaceCaptureEnc),
            URLQueryItem(name: "dtype", value: task.dtype),
            URLQueryItem(name: "_t", value: String(nowMs)),
            URLQueryItem(name: "attDuration", value: String(task.attDuration > 0 ? task.attDuration : duration)),
            URLQueryItem(name: "attDurationEnc", value: task.attDurationEnc),
            URLQueryItem(name: "courseEngineInfo", value: "false")
        ]

        guard let url = components?.url else {
            throw ChaoxingError.invalidInput("构造视频日志请求 URL 失败")
        }

        var headers = pcWebHeaders
        headers["Referer"] = task.dtype == "Audio" ? audioReferer : videoReferer
        headers["Content-Type"] = "application/json"

        var (data, response) = try await client.get(url, headers: headers)
        var bodyText = String(data: data, encoding: .utf8) ?? ""

        // Retry once after reLogin ONLY on true auth expiry (`401`/`302`/`202` or login HTML), never on `403` rate-limit.
        if response.statusCode == 401 || response.statusCode == 302 || response.statusCode == 202
            || isLoginPage(data: data, response: response)
            || ((200..<300).contains(response.statusCode) && !bodyText.contains("isPassed")) {
            _ = try await ChaoxingAuthService.reLogin(client: client)
            (data, response) = try await client.get(url, headers: headers)
            bodyText = String(data: data, encoding: .utf8) ?? ""
        }

        if response.statusCode == 403 {
            if let restSecondsStr = firstRegexGroup(pattern: #"\[restTime:(\d+)\]"#, in: bodyText),
               let restSeconds = Int(restSecondsStr) {
                let minutes = max(1, Int(ceil(Double(restSeconds) / 60.0)))
                throw ChaoxingError.networkError(statusCode: 403, message: "本次学习时长已达上限，请休息 \(minutes) 分钟后继续 [restTime:\(restSeconds)]")
            }
            throw ChaoxingError.networkError(statusCode: 403, message: "视频日志被拒绝 (403): \(bodyText)")
        }
        guard (200..<300).contains(response.statusCode) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: "视频进度上报失败 (\(response.statusCode))")
        }

        if let parsed = try? JSONDecoder().decode(ChaoxingVideoLogResponse.self, from: data) {
            return parsed
        }
        throw ChaoxingError.parseFailed("视频日志响应解析失败: \(bodyText.prefix(120))")
    }

    // MARK: - Helpers

    private static func isLoginPage(data: Data, response: HTTPURLResponse) -> Bool {
        let host = response.url?.host?.lowercased() ?? ""
        if host == "passport2.chaoxing.com" || host == "passport.chaoxing.com" {
            return true
        }
        guard let html = String(data: data, encoding: .utf8), html.count < 80_000 else { return false }
        return html.range(of: #"(?is)<title[^>]*>[^<]*登录|<input[^>]+type\s*=\s*["']password["']"#, options: .regularExpression) != nil
    }

    private static func getWithReauth(
        client: ChaoxingHTTPClient,
        url: URL,
        headers: [String: String] = [:],
        reauthOnForbidden: Bool = false
    ) async throws -> (Data, HTTPURLResponse) {
        var (data, response) = try await client.get(url, headers: headers)
        let shouldReauth = response.statusCode == 401
            || response.statusCode == 302
            || (reauthOnForbidden && response.statusCode == 403)
            || isLoginPage(data: data, response: response)
        if shouldReauth {
            _ = try await ChaoxingAuthService.reLogin(client: client)
            (data, response) = try await client.get(url, headers: headers)
        }
        return (data, response)
    }

    private static func getHTMLWithReauth(
        client: ChaoxingHTTPClient,
        url: URL,
        headers: [String: String] = [:],
        failureMessage: String
    ) async throws -> String {
        let (data, response) = try await getWithReauth(client: client, url: url, headers: headers)
        guard (200..<300).contains(response.statusCode),
              let html = String(data: data, encoding: .utf8) else {
            throw ChaoxingError.networkError(statusCode: response.statusCode, message: failureMessage)
        }
        return html
    }

    static func extractMArgJSONObject(from html: String) -> String? {
        guard let anchorRange = html.range(of: #"mArg\s*=\s*\{"#, options: .regularExpression) else {
            return nil
        }
        let braceStart = html.index(before: anchorRange.upperBound)
        var depth = 0
        var inString = false
        var escaped = false

        var idx = braceStart
        while idx < html.endIndex {
            let ch = html[idx]
            if inString {
                if escaped {
                    escaped = false
                } else if ch == "\\" {
                    escaped = true
                } else if ch == "\"" {
                    inString = false
                }
            } else {
                if ch == "\"" {
                    inString = true
                } else if ch == "{" {
                    depth += 1
                } else if ch == "}" {
                    depth -= 1
                    if depth == 0 {
                        return String(html[braceStart...idx])
                    }
                }
            }
            idx = html.index(after: idx)
        }
        return nil
    }

    private static func extractCpi(from html: String) -> Int64? {
        if let raw = firstRegexGroup(pattern: #"cpi\s*[:=]\s*['"]?(\d{5,})"#, in: html) {
            return Int64(raw)
        }
        return nil
    }

    private static func stripHTML(_ value: String) -> String {
        let withoutTags = value.replacingOccurrences(of: #"<[^>]+>"#, with: " ", options: .regularExpression)
        return withoutTags
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func splitLeadingPendingBadge(_ text: String) -> (Int?, String) {
        let parts = text.split(separator: " ", maxSplits: 1, omittingEmptySubsequences: true)
        if parts.count == 2, parts[0].allSatisfy(\.isNumber), let count = Int(parts[0]) {
            return (count, String(parts[1]))
        }
        return (nil, text)
    }

    private static func firstRegexGroup(pattern: String, in text: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: []) else { return nil }
        let nsText = text as NSString
        guard let match = regex.firstMatch(in: text, range: NSRange(location: 0, length: nsText.length)),
              match.numberOfRanges > 1 else {
            return nil
        }
        let range = match.range(at: 1)
        guard range.location != NSNotFound else { return nil }
        return nsText.substring(with: range)
    }

    private static func stringValue(_ any: Any?) -> String? {
        if let str = any as? String { return str }
        if let num = any as? NSNumber { return num.stringValue }
        return nil
    }

    private static func boolValue(_ any: Any?) -> Bool? {
        if let b = any as? Bool { return b }
        if let num = any as? NSNumber { return num.boolValue }
        if let str = any as? String {
            let lowered = str.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if lowered == "true" || lowered == "1" { return true }
            if lowered == "false" || lowered == "0" { return false }
        }
        return nil
    }

    private static func intValue(_ any: Any?) -> Int? {
        if let num = any as? NSNumber { return num.intValue }
        if let str = any as? String { return Int(str.trimmingCharacters(in: .whitespacesAndNewlines)) }
        return nil
    }

    private static func doubleValue(_ any: Any?) -> Double? {
        if let num = any as? NSNumber { return num.doubleValue }
        if let str = any as? String { return Double(str.trimmingCharacters(in: .whitespacesAndNewlines)) }
        return nil
    }

    private static func formatRT(_ value: Double) -> String {
        if abs(value - 0.9) < 0.0001 { return "0.9" }
        return String(format: "%.2g", value)
    }
}
