// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import Testing
import Foundation
@testable import ChaoxingCore

@Suite("ChaoxingCourseStudyService Tests")
struct ChaoxingCourseStudyServiceTests {
    @Test("buildEncSignature matches independent MD5 reference vectors")
    func testBuildEncSignature() {
        // Synthetic test vector at playingTime=13s
        let enc13 = ChaoxingCourseStudyService.buildEncSignature(
            clazzId: 200001,
            userId: "1000001",
            jobId: "job-demo-001",
            objectId: "0123456789abcdef0123456789abcdef",
            playingTimeMs: 13_000,
            durationMs: 571_000,
            clipTime: "0_571"
        )
        #expect(enc13 == "32eda22ad175618495f92e4b35cc6320")

        // Synthetic test vector at playingTime=0s
        let enc0 = ChaoxingCourseStudyService.buildEncSignature(
            clazzId: 200001,
            userId: "1000001",
            jobId: "job-demo-001",
            objectId: "0123456789abcdef0123456789abcdef",
            playingTimeMs: 0,
            durationMs: 571_000,
            clipTime: "0_571"
        )
        #expect(enc0 == "e62813166084e621739d268180008003")
    }

    @Test("parseCourseChaptersHTML extracts cpi, section headers, and chapter nodes")
    func testParseCourseChaptersHTML() throws {
        let html = """
        <script>var cpi = '900001';</script>
        <h3 id="800001">第1章 导论</h3>
        <h4 id="cur800101" class="currents">
            <span onclick="getTeacherAjax('300001','200001','800101');"></span>
            <span>1</span> 1.1 课程导论
        </h4>
        <h4 id="cur800102">
            <span onclick="getTeacherAjax('300001','200001','800102');"></span>
            1.2 章节测验
        </h4>
        <h3 id="800002">第2章 核心概念</h3>
        <h4 id="cur800201">
            <span onclick="getTeacherAjax('300001','200001','800201');"></span>
            2.1 基础理论
        </h4>
        """

        let result = try ChaoxingCourseStudyService.parseCourseChaptersHTML(html, courseId: 300001, classId: 200001)
        #expect(result.cpi == 900001)
        #expect(result.chapters.count == 3)

        #expect(result.chapters[0].id == "800101")
        #expect(result.chapters[0].title == "1.1 课程导论")
        #expect(result.chapters[0].sectionTitle == "第1章 导论")
        #expect(result.chapters[0].pendingJobCount == 1)

        #expect(result.chapters[1].id == "800102")
        #expect(result.chapters[1].title == "1.2 章节测验")
        #expect(result.chapters[1].pendingJobCount == nil)

        #expect(result.chapters[2].id == "800201")
        #expect(result.chapters[2].sectionTitle == "第2章 核心概念")
    }

    @Test("parseChapterCardsHTML extracts mArg video task points accurately")
    func testParseChapterCardsHTML() throws {
        let html = """
        <script>
        var isChapterCards = true;
        (function(){
          mArg = "";
          try{
            mArg = {"defaults":{"fid":"100","userid":"1000001","reportTimeInterval":60,"cpi":900001,"reportUrl":"https://mooc1-2.chaoxing.com/mooc-ans/multimedia/log/a/900001"},"attachments":[{"jobid":"job-demo-001","type":"video","isPassed":false,"otherInfo":"nodeId_800101-cpi_900001-rt_d-ds_0-ff_1-be_0_0-vt_1-v_6-enc_demo0000000000000000000000000001&courseId=300001","headOffset":571000,"attDuration":571,"attDurationEnc":"demoattdurationenc00000000000001","videoFaceCaptureEnc":"demofacecaptureenc00000000000001","playTime":571000,"rt":0.9,"property":{"name":"示例视频课程.mp4","doublespeed":0,"objectid":"0123456789abcdef0123456789abcdef"}}]};
          }catch(e){}
        })();
        </script>
        """

        let videos = try ChaoxingCourseStudyService.parseChapterCardsHTML(
            html,
            courseId: 300001,
            classId: 200001,
            chapterId: "800101"
        )
        #expect(videos.count == 1)
        let video = try #require(videos.first)
        #expect(video.jobid == "job-demo-001")
        #expect(video.objectId == "0123456789abcdef0123456789abcdef")
        #expect(video.name == "示例视频课程.mp4")
        #expect(video.doublespeed == 0)
        #expect(video.allowedMaxRate == 1.0)
        #expect(video.requiredWatchSeconds == 514)
        #expect(video.otherInfo == "nodeId_800101-cpi_900001-rt_d-ds_0-ff_1-be_0_0-vt_1-v_6-enc_demo0000000000000000000000000001")
        #expect(video.videoFaceCaptureEnc == "demofacecaptureenc00000000000001")
        #expect(!video.isPassed)
    }

    @Test("parseCourseChaptersHTML extracts studentcourse anchor links")
    func testParseStudentCourseHTML() throws {
        let html = """
        <script>var cpi = 900001;</script>
        <a href='/mooc-ans/mycourse/studentstudy?chapterId=800101&courseId=300001&clazzid=200001&enc=demoenc0000000000000000000000001' aria-label="1.1 课程导论">
            <span>1.1 课程导论</span>
        </a>
        """
        let result = try ChaoxingCourseStudyService.parseCourseChaptersHTML(html, courseId: 300001, classId: 200001)
        #expect(result.cpi == 900001)
        #expect(result.chapters.count == 1)
        #expect(result.chapters.first?.id == "800101")
        #expect(result.chapters.first?.title == "1.1 课程导论")
    }

    @Test("parseGasClazzChaptersJSON extracts knowledge tree")
    func testParseGasClazzChaptersJSON() throws {
        let json = """
        {"data":[{"id":200001,"cpi":900001,"course":{"data":[{"id":300001,"knowledge":{"data":[{"id":800001,"name":"导论","label":"1","indexOrder":1,"parentnodeid":0,"layer":1},{"id":800101,"name":"课程导论","label":"1.1","indexOrder":1,"parentnodeid":800001,"layer":2}]}}]}}]}
        """.data(using: .utf8)!
        let result = try ChaoxingCourseStudyService.parseGasClazzChaptersJSON(json, courseId: 300001, classId: 200001)
        #expect(result.cpi == 900001)
        #expect(result.chapters.count == 1)
        #expect(result.chapters.first?.id == "800101")
        #expect(result.chapters.first?.title == "1.1 课程导论")
        #expect(result.chapters.first?.sectionTitle == "1 导论")
    }

    @Test("parseCourseChaptersHTML extracts explicit jobUnfinishCount and zero for completed chapters")
    func testParseStudentStudyCourseListJobCounts() throws {
        let html = """
        <h3 id="100">第1章 导论</h3>
        <h4 id="cur101">
            <input type="hidden" value="2" class="jobUnfinishCount"/>
            <span class="roundpointStudent">2</span>
            <a href="javascript:getTeacherAjax('1','2','101');">1.1 未完成节</a>
        </h4>
        <h4 id="cur102">
            <a href="javascript:getTeacherAjax('1','2','102');">1.2 已完成节</a>
        </h4>
        """
        let result = try ChaoxingCourseStudyService.parseCourseChaptersHTML(html, courseId: 1, classId: 2, hasExplicitJobCounts: true)
        #expect(result.chapters.count == 2)
        #expect(result.chapters[0].pendingJobCount == 2)
        #expect(result.chapters[1].pendingJobCount == 0)
    }

    @Test("Duplicate chapter and folder IDs preserve the first row and independent leaves")
    func testGasChapterDuplicateIDs() throws {
        let json = """
        {"data":[{"cpi":"1234","course":{"data":[{"knowledge":{"data":[
          null, {"id":true,"name":"Invalid boolean"}, {"id":11.5,"name":"Invalid fraction"},
          {"id":10,"name":"First folder","indexOrder":1},
          {"id":12,"name":"Second leaf","parentnodeid":10,"indexOrder":2},
          {"id":11,"name":"First leaf","parentnodeid":10,"indexOrder":1},
          {"id":20,"name":"Standalone","indexOrder":2},
          {"id":10,"name":"Duplicate folder","parentnodeid":20},
          {"id":11,"name":"Duplicate leaf","parentnodeid":20}
        ]}}]}}]}
        """
        let result = try ChaoxingCourseStudyService.parseGasClazzChaptersJSON(Data(json.utf8), courseId: 1, classId: 2)
        #expect(result.cpi == 1234)
        #expect(result.chapters.map(\.id) == ["11", "12", "20"])
        #expect(result.chapters.map(\.title) == ["First leaf", "Second leaf", "Standalone"])
        #expect(result.chapters.map(\.sectionTitle) == ["First folder", "First folder", ""])
    }

    @Test("parseCardCount extracts multi-tab count and clamps upper bound")
    func testParseCardCount() {
        #expect(ChaoxingCourseStudyService.parseCardCount(from: #"<input id="cardcount" type="hidden" value="3">"#) == 3)
        #expect(ChaoxingCourseStudyService.parseCardCount(from: #"<input type="hidden" value="2" id="cardcount">"#) == 2)
        #expect(ChaoxingCourseStudyService.parseCardCount(from: "no cardcount") == 1)
    }

    @Test("parseChapterCardsHTML handles trimmed clips, non-job videos, schooldoublespeed, and face capture")
    func testParseChapterCardsEdgeCases() throws {
        let html = """
        <script>
        mArg = {"defaults":{"userid":"42","cpi":999,"schooldoublespeed":0,"chapterCapture":1,"reportTimeInterval":60,"reportUrl":"//mooc1-2.chaoxing.com/mooc-ans/multimedia/log/a/999"},"attachments":[
            {"job":true,"jobid":"j1","type":"video","isPassed":false,"begins":20,"ends":120,"attDuration":200,"otherInfo":"nodeId_1&courseId=1","property":{"name":"trimmed.mp4","doublespeed":1,"objectid":"obj1"}},
            {"job":false,"type":"video","isPassed":false,"attDuration":100,"otherInfo":"nodeId_1","property":{"name":"reference.mp4","doublespeed":1,"objectid":"obj2"}}
        ]};
        </script>
        """
        let videos = try ChaoxingCourseStudyService.parseChapterCardsHTML(html, courseId: 1, classId: 2, chapterId: "10")
        #expect(videos.count == 2)

        let v1 = videos[0]
        #expect(v1.isJob == true)
        #expect(v1.doublespeed == 0) // blocked by schooldoublespeed=0
        #expect(v1.requiresFaceCapture == true)
        #expect(v1.effectiveClipTime == "20_120")
        #expect(v1.requiredWatchSeconds == 90)
        #expect(v1.reportUrl == "https://mooc1-2.chaoxing.com/mooc-ans/multimedia/log/a/999")

        let v2 = videos[1]
        #expect(v2.isJob == false)
        #expect(v2.isReportableTask == false)
    }

    @Test("parseGasClazzChaptersJSON filters out intermediate parent folders in 3-level trees and sorts stably")
    func testParseThreeLevelGasClazzTree() throws {
        let json = """
        {"data":[{"id":2,"cpi":99,"course":{"data":[{"id":1,"knowledge":{"data":[
            {"id":10,"name":"Ch1","label":"1","indexOrder":1,"parentnodeid":0,"layer":1},
            {"id":20,"name":"Sec1.1","label":"1.1","indexOrder":1,"parentnodeid":10,"layer":2},
            {"id":31,"name":"LeafB","label":"1.1.2","indexOrder":2,"parentnodeid":20,"layer":3},
            {"id":30,"name":"LeafA","label":"1.1.1","indexOrder":1,"parentnodeid":20,"layer":3}
        ]}}]}}]}
        """.data(using: .utf8)!
        let res = try ChaoxingCourseStudyService.parseGasClazzChaptersJSON(json, courseId: 1, classId: 2)
        #expect(res.chapters.map(\.id) == ["30", "31"])
        #expect(res.chapters[0].sectionTitle == "1 Ch1 / 1.1 Sec1.1")
    }

    @Test("parseCourseChaptersHTML extracts h5 level-3 chapters and preserves review status as unknown")
    func testParseThreeLevelHTMLAndReviewStatus() throws {
        let html = """
        <h3 id="10">第1章 微积分</h3>
        <h4 id="cur20">
            <span class="roundpointStudent noJob"></span>
            <a href="javascript:getTeacherAjax('1','2','20');">1.1 极限</a>
        </h4>
        <h5 id="cur30">
            <input type="hidden" value="1" class="jobUnfinishCount"/>
            <span class="roundpoint orange01 jobCount">1</span>
            <a href="javascript:getTeacherAjax('1','2','30');">1.1.1 客观题</a>
        </h5>
        <h5 id="cur31">
            <span class="roundpoint blue"></span>
            <a href="javascript:getTeacherAjax('1','2','31');">1.1.2 概念</a>
        </h5>
        <h4 id="cur40">
            <span class="roundpointStudent review"></span>
            <a href="javascript:getTeacherAjax('1','2','40');">1.2 复习讲座</a>
        </h4>
        """
        let res = try ChaoxingCourseStudyService.parseCourseChaptersHTML(html, courseId: 1, classId: 2, hasExplicitJobCounts: true)
        #expect(res.chapters.count == 4)
        #expect(res.chapters[0].id == "20")
        #expect(res.chapters[0].pendingJobCount == 0)

        #expect(res.chapters[1].id == "30")
        #expect(res.chapters[1].sectionTitle == "第1章 微积分 / 1.1 极限")
        #expect(res.chapters[1].pendingJobCount == 1)

        #expect(res.chapters[2].id == "31")
        #expect(res.chapters[2].pendingJobCount == 0)

        // `roundpointStudent review` MUST remain nil (unknown), never falsely treated as 0 completed!
        #expect(res.chapters[3].id == "40")
        #expect(res.chapters[3].pendingJobCount == nil)
    }

    @Test("parseCourseChaptersHTML extracts mooc2-ans chapter_item toOld nodes")
    func testParseMooc2ChapterItems() throws {
        let html = """
        <div class="chapter_item" id="cur500001" onclick="toOld('300002', '500001', '200002',0)" title="第一课示例">
            <div class="catalog_title"><span class="catalog_sbar">1.1</span> 第一课示例</div>
            <div class="catalog_task"><div class="catalog_state icon_yiwanc"></div></div>
        </div>
        <div class="chapter_item" id="cur500002" onclick="toOld('300002', '500002', '200002',0)" title="第二课示例">
            <div class="catalog_title"><span class="catalog_sbar">1.2</span> 第二课示例</div>
            <div class="catalog_task"><span class="catalog_points_yi">2</span></div>
        </div>
        """
        let res = try ChaoxingCourseStudyService.parseCourseChaptersHTML(html, courseId: 300002, classId: 200002)
        #expect(res.chapters.count == 2)
        #expect(res.chapters[0].id == "500001")
        #expect(res.chapters[0].title == "1.1 第一课示例")
        #expect(res.chapters[0].pendingJobCount == 0)
        #expect(res.chapters[1].id == "500002")
        #expect(res.chapters[1].pendingJobCount == 2)
    }

    @Test("parseChapterCardsContent extracts videos alongside document, live, and quiz tasks")
    func testParseChapterCardsNonVideoAttachments() throws {
        let html = """
        <script>
        mArg = {"defaults":{"userid":"42","cpi":999,"reportTimeInterval":60},"attachments":[
            {"job":true,"jobid":"doc1","type":"document","jtoken":"abcdef1234567890","isPassed":false,"property":{"module":"insertdoc","name":"实验指导书.pdf","objectid":"objDoc"}},
            {"jobid":"docDone","type":"document","jtoken":"fedcba0987654321","property":{"module":"insertdoc","name":"已完成文档.pdf","objectid":"objDocDone"}},
            {"job":true,"jobid":"live-1","type":"live","property":{"module":"insertlive","name":"专题直播"}},
            {"job":true,"jobid":"work-1","type":"workid","property":{"module":"work","title":"章节测验"}}
        ]};
        </script>
        """
        let content = try ChaoxingCourseStudyService.parseChapterCardsContent(html, courseId: 1, classId: 2, chapterId: "10")
        #expect(content.videos.isEmpty)
        #expect(content.otherAttachments.count == 4)
        #expect(content.otherAttachments[0].kind == .document)
        #expect(content.otherAttachments[0].isPassed == false)
        #expect(content.otherAttachments[0].canCompleteAsDocument == true)
        #expect(content.otherAttachments[1].kind == .document)
        #expect(content.otherAttachments[1].isPassed == true)
        #expect(content.otherAttachments[1].canCompleteAsDocument == false)
        #expect(content.otherAttachments[2].kind == .live)
        #expect(content.otherAttachments[3].kind == .quiz)
        #expect(content.otherAttachments[3].name == "章节测验")
        #expect(content.otherAttachments[3].chapterStudyURL?.absoluteString.contains("chapterId=10") == true)
    }

    @Test("parseChapterCardsContent builds directQuizURL and chapterStudyURL for quiz attachments")
    func testParseQuizDirectURL() throws {
        let html = """
        <script>
        mArg = {"defaults":{"userid":"42","cpi":8888,"ktoken":"ktok_demo","reportTimeInterval":60},"attachments":[
            {"job":true,"jobid":"work-777","type":"workid","enc":"enc_demo_123","property":{"module":"work","title":"第一章测验","workid":777}},
            {"job":true,"jobid":"work-888","type":"workid","property":{"module":"work","title":"无workId测验"}},
            {"job":false,"jobid":"ext-1","type":"other","property":{"module":"insertlink","name":"危险外链","url":"javascript:alert(1)"}}
        ]};
        </script>
        """
        let content = try ChaoxingCourseStudyService.parseChapterCardsContent(html, courseId: 100, classId: 200, chapterId: "300")
        #expect(content.otherAttachments.count == 3)

        let quiz = content.otherAttachments[0]
        #expect(quiz.kind == .quiz)
        #expect(quiz.workId == "777")
        #expect(quiz.workEnc == "enc_demo_123")
        #expect(quiz.ktoken == "ktok_demo")
        #expect(quiz.directQuizURL?.absoluteString.contains("https://mooc1.chaoxing.com/mooc-ans/api/work") == true)
        #expect(quiz.directQuizURL?.absoluteString.contains("workId=777") == true)
        #expect(quiz.directQuizURL?.absoluteString.contains("enc=enc_demo_123") == true)
        #expect(quiz.directQuizURL?.absoluteString.contains("cpi=8888") == true)
        #expect(quiz.preferredInAppURL == quiz.directQuizURL)
        #expect(quiz.chapterStudyURL?.absoluteString.contains("https://mooc1.chaoxing.com/mycourse/studentstudy") == true)

        let updatedQuiz = quiz.updating(isPassed: true)
        #expect(updatedQuiz.isPassed == true)
        #expect(updatedQuiz.workId == "777")
        #expect(updatedQuiz.workEnc == "enc_demo_123")
        #expect(updatedQuiz.ktoken == "ktok_demo")
        #expect(updatedQuiz.cpi == 8888)

        let fallbackQuiz = content.otherAttachments[1]
        #expect(fallbackQuiz.directQuizURL == nil)
        #expect(fallbackQuiz.preferredInAppURL == fallbackQuiz.chapterStudyURL)

        let unsafeExt = content.otherAttachments[2]
        #expect(unsafeExt.preferredInAppURL == unsafeExt.chapterStudyURL)
    }

    @Test("parseChapterCardsContent extracts insertlive tasks and audio dtype")
    func testParseLiveReplayAndAudioTasks() throws {
        let html = """
        <script>
        mArg = {"defaults":{"userid":"1000001","cpi":900001,"reportTimeInterval":60},"attachments":[
            {
                "job":true,"jobid":"live-5000123456789001","aid":88001,"type":"live",
                "liveSetEnc":"setenc1","authEnc":"authenc1","liveDragEnc":"dragenc1","liveSwDsEnc":"swdsenc1",
                "property":{"module":"insertlive","title":"演示回放-第一场","liveId":5000123456789001,"streamName":"STREAM_DEMO_1","vdoid":"vdoid_demo_1","doublespeed":1,"fastforward":"false","rt":0.9}
            },
            {
                "jobid":"live-5000123456789002","aid":88002,"type":"live",
                "property":{"module":"insertlive","title":"演示回放-已完成","liveId":5000123456789002,"streamName":"STREAM_DEMO_2","vdoid":"vdoid_demo_2"}
            },
            {
                "type":"live",
                "property":{"module":"insertlive","title":"演示回放-非任务点","liveId":5000123456789003,"streamName":"STREAM_DEMO_3","vdoid":"vdoid_demo_3"}
            },
            {
                "job":true,"jobid":"audio-1","type":"audio","otherInfo":"nodeId_8001","attDuration":180,"attDurationEnc":"audenc",
                "property":{"module":"insertaudio","name":"示范音频.mp3","objectid":"audioobj1234567890"}
            }
        ]};
        </script>
        """
        let content = try ChaoxingCourseStudyService.parseChapterCardsContent(html, courseId: 300001, classId: 200001, chapterId: "8001")
        #expect(content.videos.count == 1)
        #expect(content.videos[0].dtype == "Audio")
        #expect(content.videos[0].name == "示范音频.mp3")

        #expect(content.liveTasks.count == 3)
        let pending = content.liveTasks[0]
        #expect(pending.liveId == "5000123456789001")
        #expect(pending.streamName == "STREAM_DEMO_1")
        #expect(pending.vdoid == "vdoid_demo_1")
        #expect(pending.isJob == true)
        #expect(pending.isPassed == false)
        #expect(pending.isReportableTask == true)
        #expect(pending.webPageURL?.contains("https://zhibo.chaoxing.com/5000123456789001") == true)
        #expect(pending.webPageURL?.contains("watchingInCourse=1") == true)
        #expect(pending.webPageURL?.contains("livesetenc=setenc1") == true)

        let finished = content.liveTasks[1]
        #expect(finished.isJob == true)
        #expect(finished.isPassed == true)

        let referenceOnly = content.liveTasks[2]
        #expect(referenceOnly.isJob == false)
        #expect(referenceOnly.isReportableTask == false)
    }

    @Test("parseLiveInfoJSON and parseLiveSessionHTML decode synthetic zhibo payloads")
    func testParseLiveInfoAndSessionHTML() throws {
        let infoJSON = Data("""
        {
            "status": true,
            "courseEndState": 0,
            "temp": {
                "data": {
                    "liveId": "5000123456789001",
                    "title": "示范直播回放",
                    "liveStatus": 4,
                    "ifReview": 0,
                    "duration": 3000,
                    "playbackTimelong": 60000,
                    "percentValue": 2.0,
                    "streamName": "STREAM_DEMO_1",
                    "vdoid": "vdoid_demo_1",
                    "m3u8Url": "http://example.invalid/replay.m3u8",
                    "mp4Url": "http://example.invalid/replay.mp4"
                }
            }
        }
        """.utf8)
        let info = try ChaoxingCourseStudyService.parseLiveInfoJSON(infoJSON, fallbackLiveId: "12345")
        #expect(info.resolvedLiveId == "5000123456789001")
        #expect(info.duration == 3000)
        #expect(info.playbackTimelongMs == 60000)
        #expect(info.percentValue == 2.0)
        #expect(info.preferredPlayURL == "https://example.invalid/replay.m3u8")
        #expect(info.fallbackPlayURL == "https://example.invalid/replay.mp4")
        #expect(info.allPlayURLs == ["https://example.invalid/replay.m3u8", "https://example.invalid/replay.mp4"])

        let fallbackTask = ChaoxingLiveTask(
            courseId: 300001, classId: 200001, chapterId: "8001", cpi: 900001,
            userid: "1000001", jobid: "live-5000123456789001", liveId: "5000123456789001",
            streamName: "STREAM_DEMO_1", vdoid: "vdoid_demo_1", title: "示范直播回放",
            playURL: info.preferredPlayURL, fallbackPlayURL: info.fallbackPlayURL
        )
        #expect(fallbackTask.allPlayURLs == ["https://example.invalid/replay.m3u8", "https://example.invalid/replay.mp4"])
        let pageHTML = """
        <iframe style="display:none" th:if="${userId!=null}" src="/iframeLoginSSO?u='+${uInfo}"></iframe>
        <script>
            var liveId = 99001001;
            var uInfo = 'demo_uinfo_token';
            var liveduration=3000;
            var fid = '12345';
            urls = 'https://example.invalid/hls/demo.m3u8';
        </script>
        """
        let ctx = ChaoxingCourseStudyService.parseLiveSessionHTML(
            pageHTML,
            pageURL: "https://zhibo.chaoxing.com/5000123456789001?watchingInCourse=1",
            fallbackTask: fallbackTask
        )
        #expect(ctx.shortLiveId == "99001001")
        #expect(ctx.uInfo == "demo_uinfo_token")
        #expect(ctx.duration == 3000)
        #expect(ctx.fid == "12345")
        #expect(ctx.playURL == "https://example.invalid/hls/demo.m3u8")
    }

    @Test("parseLiveHeartbeatJSON validates accepted flag, duration, and rejections")
    func testParseLiveHeartbeatJSON() throws {
        let okData = Data("""
        {"result":1,"msg":"","legacy":"@success","data":{"accepted":true,"live":{"ifreview":0,"duration":3340,"livestatus":4,"playback":true}}}
        """.utf8)
        let ok = try ChaoxingCourseStudyService.parseLiveHeartbeatJSON(okData)
        #expect(ok.result == 1)
        #expect(ok.accepted == true)
        #expect(ok.duration == 3340)
        #expect(ok.liveStatus == 4)

        let rejectedData = Data("""
        {"result":1,"msg":"并发受限","data":{"accepted":false}}
        """.utf8)
        let rejected = try ChaoxingCourseStudyService.parseLiveHeartbeatJSON(rejectedData)
        #expect(rejected.result == 1)
        #expect(rejected.accepted == false)
        #expect(rejected.message == "并发受限")

        let errorData = Data("""
        {"result":0,"msg":"请先登录"}
        """.utf8)
        let err = try ChaoxingCourseStudyService.parseLiveHeartbeatJSON(errorData)
        #expect(err.result == 0)
        #expect(err.accepted == false)
        #expect(err.message == "请先登录")
    }
}
