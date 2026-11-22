# 来源、版权与改写说明

## 发布协议

Caochaoxing 的项目代码采用 GNU Affero General Public License version 3（`AGPL-3.0-only`）。协议原文保留了 Free Software Foundation 的版权声明。[LICENSE](LICENSE)

本项目的 Swift / SwiftUI（macOS & iOS）与 Kotlin / Jetpack Compose（Android）实现及整理修改：Copyright (c) 2026 Pybsama。

## ChaoxingSignFaker

- 原项目：[aquamarine5/ChaoxingSignFaker](https://github.com/aquamarine5/ChaoxingSignFaker)。
- 本次比对基准：[`ff4aec464e4dfbacdf46a1975c558e3d9be69d44`](https://github.com/aquamarine5/ChaoxingSignFaker/tree/ff4aec464e4dfbacdf46a1975c558e3d9be69d44)，检查日期 2026-11-14。
- 上游协议：[AGPL v3](https://github.com/aquamarine5/ChaoxingSignFaker/blob/ff4aec464e4dfbacdf46a1975c558e3d9be69d44/LICENSE)。
- 下列为相关 Kotlin 源文件中的原始作者声明，保留其文字；其中年份随文件不同为 `2025-2026` 或 `2026`：

```text
Copyright (c) 2025-2026, @aquamarine5 (@海蓝色的咕咕鸽). All Rights Reserved.
Copyright (c) 2026, @aquamarine5 (@海蓝色的咕咕鸽). All Rights Reserved.
Author: aquamarine5@163.com (Github: https://github.com/aquamarine5) and Brainspark (previously RenegadeCreation)
Repository: https://github.com/aquamarine5/ChaoxingSignFaker
```

上游的相关版权属于 aquamarine5 及相应贡献者。以上公开作者联系方式用于归属声明，不是本项目维护者的联系方式。Caochaoxing 与上游作者没有官方隶属或背书关系。

## 对应范围与证据

本仓库首次提交前没有开发提交历史，无法逐段还原最初的编写过程。本次比对发现的不仅是共同接口地址，还包括相同固定参数、参数拼接次序、设备码生成步骤和内置 User-Agent。结合原设计文档明确记载参考上游，本项目保守地将这些协议实现按上游的 Swift 与 Kotlin 改写部分处理，并保留归属；不宣称它们是完全独立的净室实现。

以下两张表中的上游 Kotlin 路径均位于 `app/src/main/java/org/aquamarine5/brainspark/chaoxingsignfaker/`：

### iOS / Swift 核心（`Sources/ChaoxingCore/`）

| 本地文件 / 模块 | 上游对应来源 | 比对事实 |
| --- | --- | --- |
| `Crypto/ChaoxingCrypto.swift`、`Services/ChaoxingAuthService.swift` | `api/ChaoxingHttpClient.kt`、`api/ChaoxingDeviceInfoHelper.kt`、`api/ChaoxingIMHelper.kt` | 登录 AES 参数与密钥/IV、DES 参数、两次 UUID → SHA-256 → 重复摘要 → Base64 的设备码步骤及登录请求字段对应 |
| `Models/ChaoxingAccount.swift` | `components/CustomizeClientCard.kt` | 两套内置客户端的完整 User-Agent 和预设标识相同 |
| `Network/ChaoxingHTTPClient.swift` | `api/ChaoxingHttpClient.kt`、`api/ChaoxingHttpRequester.kt` | 账号 Cookie、FID 与请求职责对应；网络层用 URLSession 和 Swift 锁机制重写 |
| `Services/ChaoxingCourseService.swift`、`Services/ChaoxingActivityService.swift` | `api/ChaoxingCourseHelper.kt`、`api/ChaoxingActivityHelper.kt`、`signer/ChaoxingSigner.kt` 及具体 signer | 课程与活动端点、字段读取和签到详情对应 |
| `Services/ChaoxingCaptchaService.swift` | `signer/ChaoxingSigner.kt` | captchaId、JSONP 回调、版本字段、captchaKey/iv/token 拼接和校验参数对应 |
| `Services/ChaoxingCloudDriveAndFaceService.swift` | `api/ChaoxingCloudDriveHelper.kt`、`api/ChaoxingFaceHelper.kt`、`signer/ChaoxingPhotoSigner.kt` | 云盘 token、图片上传、底库照片及人脸核验接口对应 |
| `Services/ChaoxingGroupService.swift`、`Crypto/EasemobProtobufDecoder.swift` | `api/ChaoxingIMHelper.kt`；另见 `app/src/main/proto/easemob.proto` | 环信端点和消息附件解析对应；手写 Swift 解码器使用相同 protobuf 字段编号 |
| `Signers/ChaoxingSignExecutor.swift`、`Crypto/ChaoxingQRCodeParser.swift` | `signer/ChaoxingSigner.kt`、`signer/ChaoxingQRCodeSigner.kt` 等 | 预签到、analysis/analysis2、提交与返回值处理，以及 SIGNIN / URL 二维码参数处理对应 |

上表本地相对路径均以 `Sources/ChaoxingCore/` 为起点。其对应源文件附有 SPDX 与上游归属提示。

### Android / Kotlin 核心（`android/core/src/main/kotlin/com/caochaoxing/core/`）

| 本地文件 / 模块 | 上游对应来源 | 比对事实 |
| --- | --- | --- |
| `crypto/ChaoxingCrypto.kt`、`service/ChaoxingAuthService.kt` | `api/ChaoxingHttpClient.kt`、`api/ChaoxingDeviceInfoHelper.kt`、`api/ChaoxingIMHelper.kt` | 登录 AES 参数与密钥/IV、DES 参数、两次 UUID → SHA-256 → 重复摘要 → Base64 的设备码步骤及登录/重登录/学校列表请求字段对应 |
| `model/ChaoxingAccount.kt` | `components/CustomizeClientCard.kt` | 两套内置客户端的完整 User-Agent 和预设标识相同，并与跨端账号会话模型对齐 |
| `network/ChaoxingHttpClient.kt`、`network/ChaoxingCookiePolicy.kt` | `api/ChaoxingHttpClient.kt`、`api/ChaoxingHttpRequester.kt` | 账号 Cookie、FID、并发重登录协调与请求职责对应；网络层以 OkHttp 与 Kotlin 协程重写并独立实现严格域/跳转 Cookie 隔离策略 |
| `service/ChaoxingCourseService.kt`、`service/ChaoxingActivityService.kt` | `api/ChaoxingCourseHelper.kt`、`api/ChaoxingActivityHelper.kt`、`signer/ChaoxingSigner.kt` 及具体 signer | 课程与活动端点、字段读取和签到详情（含关联签退、电子围栏、人脸/定位要求）对应 |
| `service/ChaoxingCaptchaService.kt` | `signer/ChaoxingSigner.kt` | captchaId、JSONP 回调、版本字段、captchaKey/iv/token 拼接和校验参数对应 |
| `service/ChaoxingCloudDriveAndFaceService.kt` | `api/ChaoxingCloudDriveHelper.kt`、`api/ChaoxingFaceHelper.kt`、`signer/ChaoxingPhotoSigner.kt` | 云盘 token、图片上传、底库照片及人脸核验接口对应 |
| `service/ChaoxingGroupService.kt`、`crypto/EasemobProtobufDecoder.kt` | `api/ChaoxingIMHelper.kt`；另见 `app/src/main/proto/easemob.proto` | 环信端点和消息附件解析对应；手写纯 Kotlin Protobuf 解码器使用相同 protobuf 字段编号 |
| `signer/ChaoxingSignExecutor.kt`、`crypto/ChaoxingQrCodeParser.kt` | `signer/ChaoxingSigner.kt`、`signer/ChaoxingQRCodeSigner.kt` 等 | 预签到、analysis/analysis2、5 类签到提交与返回值处理，以及 `SIGNIN:` / URL 二维码参数解析对应 |

上表本地相对路径均以 `android/core/src/main/kotlin/com/caochaoxing/core/` 为起点，其对应源文件头部均附有 SPDX 与上游归属声明。

### 本项目独立新增模块（不含上游代码）

- **滑块本地识别**：Swift `ChaoxingCaptchaSolver.swift` 与 Kotlin `crypto/ChaoxingCaptchaSolver.kt` 均使用纯本地像素边缘梯度匹配；本次上游快照的 `ChaoxingCaptchaPredictor.kt` 使用 ONNX 模型推理，二者算法不同。仓库没有引入上游 ONNX 模型、二进制依赖或字体文件。
- **课程章节、视频看课、外部直播回放与文档完成协议**：Swift `Models/ChaoxingCourseStudy.swift`、`Services/ChaoxingCourseStudyService.swift` 以及 Kotlin `model/ChaoxingCourseStudy.kt`、`service/ChaoxingCourseStudyService.kt` 均由本项目独立编写实现，包含章节目录兼容解析（`h3/h4/h5` + `roundpoint` / `mooc2-ans` / `gas/clazz`）、并发卡片解析、普通视频与音频（`dtype=Audio`）合法倍速进度心跳签名与 403 `restTime` 处理、`zhibo.chaoxing.com` 外部直播回放并行会话与 30s 心跳响应校验、文档任务点完成上报及纯合成单元测试。
- **Android 核心原创补充**（`android/core/src/main/kotlin/com/caochaoxing/core/` 下其余模块）：`crypto/CoordinateConverter.kt`（`WGS-84 <-> GCJ-02 <-> BD-09` 三坐标系转换）、`crypto/ExternalCoordinateParser.kt`（外部地图链接/文本坐标解析）、`service/NominatimGeocoder.kt`（Nominatim 搜索客户端）、`store/ChaoxingPersistenceStore.kt`（持久化接口与 JSON 快照逻辑）、`util/ImageObfuscationHelpers.kt`（JPEG `COM` 注释块辅助）以及纯数据/错误模型（`model/ChaoxingCourse.kt`、`model/ChaoxingCaptcha.kt`、`model/ChaoxingError.kt`、`model/ChaoxingGroup.kt`、`model/ChaoxingLocation.kt`）。
- **三端应用层**：macOS SwiftUI 桌面端（`Sources/CaochaoxingMac/`，含多账号快捷切换、防触控板误触快进视频播放器、多路合法同步看课及完整签到/滑块/底库人脸工作台）、iOS / iPadOS SwiftUI 客户端与 Apple 框架适配（`Sources/CaochaoxingIOS/`，含 iPadOS 双栏 `NavigationSplitView`、顶栏快捷切号菜单、`IOSCourseStudyController` 多路并行视频与外站直播回放引擎、`IOSCourseStudyView` 内嵌 `AVPlayer` HLS/MP4 自动回退网格与全屏播放器），以及 Android Jetpack Compose 界面、多路合法同步看课控制器与面板（`CourseStudyController.kt`、`CourseStudyPanel.kt`）、Android Keystore AES-256-GCM 加密存储（`EncryptedFilePersistenceStore.kt`）、CameraX + ZXing 扫码、WebView Leaflet 地图选点、原生 `LocationManager` 定位、`ExternalMapLauncher` 与 Bitmap 图像混淆等（`android/app/`），均由本项目独立编写实现。上述差异不消除已确认协议改写部分的归属。

**2026-11-22 开源整理说明：** 统一完善 macOS / iOS / iPadOS / Android 全端能力（含三端合法同步看课、外部 `zhibo.chaoxing.com` 直播回放多路并行观看、文档任务点完成、顶栏多账号快捷切换及三端完整签到能力对齐），将所有测试样例清理为 100% 合成数据，移除了本地临时调试扩展，并补全协议、归属、构建与隐私说明。采用 AGPL v3 不表示拥有或获准使用上游的商标或所有第三方素材。

## Android 第三方开源依赖与地图服务声明

Android 端仅使用纯开源（FOSS）依赖与开放地图接口，**未引入**高德/百度/腾讯地图闭源 SDK、Google Play Services（GMS）、ML Kit 或 ONNX Runtime：

| 组件 / 服务 | 版本 | 许可证 | 用途说明 |
| --- | --- | --- | --- |
| Kotlin / `kotlinx-coroutines` / `kotlinx-serialization-json` | `2.0.21` / `1.9.0` / `1.7.3` | Apache-2.0 | 核心语言运行时、异步协程与 JSON 序列化（详见 [android/gradle/libs.versions.toml](android/gradle/libs.versions.toml)） |
| Android Gradle Plugin / Gradle | `8.7.3` / `8.10.2` | Apache-2.0 | Android 构建工具链（仅用于构建与打包） |
| AndroidX (`core-ktx`、`activity-compose`、`navigation-compose`、`lifecycle`、`exifinterface`) | `1.15.0` / `1.9.3` / `2.8.4` / `2.8.7` / `1.3.7` | Apache-2.0 | Android 系统兼容、Compose 宿主、导航与 EXIF 方向读取 |
| Jetpack Compose BOM (`ui`、`ui-graphics`、`ui-tooling-preview`、`material3`、`material-icons-extended`；`ui-tooling` 仅 `debugImplementation`) | `2024.10.01` | Apache-2.0 | Material 3 声明式 UI、开源矢量图标与调试预览工具 |
| AndroidX CameraX (`core`、`camera2`、`lifecycle`、`view`) | `1.4.0` | Apache-2.0 | 实时相机取景与帧分析管道 |
| OkHttp & MockWebServer | `4.12.0` | Apache-2.0 | HTTP 客户端与单元测试本地回环模拟服务端 |
| ZXing Core (`com.google.zxing:core`) | `3.5.3` | Apache-2.0 | 本地纯 Java 二维码解码 |
| JUnit 5（含 `junit5-launcher` `testRuntimeOnly`） / JUnit 4 / `kotlinx-coroutines-test` | `5.11.3` / `4.13.2` / `1.9.0` | EPL-2.0 / EPL-1.0 / Apache-2.0 | 仅用于测试作用域（`testImplementation` / `testRuntimeOnly`） |
| Leaflet | `1.9.4` | BSD-2-Clause | 在 `android/app/src/main/assets/leaflet_picker.html` 中通过 HTTPS 按需加载用于交互选点（离线回退本地 `<canvas>` 网格） |
| OpenStreetMap 瓦片与 Nominatim 地理编码服务 | 在线接口 | ODbL 1.0 | 开放地图底图瓦片与关键词地理编码，地图数据 © OpenStreetMap contributors |

## 图标及其他素材

本次检查的原有蓝绿图标与上游当前 launcher 图标外观不同；对上游快照的全部普通文件进行 SHA-256 比较，未发现完全相同的文件。这个结果不能证明它从未使用第三方素材，也不能确定其创作来源。

当前三端图标改自《BLACK SOULS II》（开发者 Eeny, meeny, miny, moe?）中爱丽丝捧着绘本的游戏画面，截图见 FGGuides 的角色资料。项目对截图做了灰度转换、嘴型局部调整和满版方形裁切。[原始截图](https://fgguides.com/wp-content/uploads/2024/10/Daughter-Alice.png) · [游戏官方页面](https://store.steampowered.com/app/3855540/BLACK_SOULS_II/)

代码和图标导出脚本采用 AGPL-3.0-only。游戏角色与画面的权利归原权利人，图标定稿、各端图标及预览的公开分发授权待确认。[图标定稿](docs/artwork/Avatar.png) · [导出脚本](docs/artwork/render-icon.swift) · [平台图标预览](docs/artwork/ANDROID_ICON.md)

README 封面由维护者提供。作品来源及公开使用授权尚待确认，本项目不对该图片授予 AGPL 许可。

Apple 系统框架、系统提供的 SF Symbols、Android 平台系统框架、外部地图 App（经隐式 Intent 跳转调起，并非内置 SDK）以及服务端返回的数据分别受其自身条款约束；本项目的 LICENSE 不对这些内容重新授权。地图瓦片、账号头像、课程封面和人脸照片不作为静态资源打包到仓库中。

## 分发说明

保留本文件和 LICENSE，以及相关源文件中的归属和修改说明。分发修改版或二进制时，应按 AGPL v3 的适用条款提供相应源代码；协议第 13 条规定的网络交互情形还涉及向交互用户提供源码。具体权利和义务以 LICENSE 原文为准。

README 的学习研究用途倡议不改写 AGPL 条款，也不增加“禁止商业使用”等许可证外的限制。本次对比只覆盖上述上游快照，不构成对所有历史版本和第三方权利的穷尽调查。
