# Caochaoxing

[![Release](https://img.shields.io/github/v/release/Pybsama/Caochaoxing?label=Release&color=2563eb)](https://github.com/Pybsama/Caochaoxing/releases/latest)
[![Android CI](https://github.com/Pybsama/Caochaoxing/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Pybsama/Caochaoxing/actions/workflows/android-ci.yml)
[![Swift CI](https://github.com/Pybsama/Caochaoxing/actions/workflows/swift-ci.yml/badge.svg)](https://github.com/Pybsama/Caochaoxing/actions/workflows/swift-ci.yml)
[![Windows CI](https://github.com/Pybsama/Caochaoxing/actions/workflows/windows-ci.yml/badge.svg)](https://github.com/Pybsama/Caochaoxing/actions/workflows/windows-ci.yml)
[![License: AGPL-3.0-only](https://img.shields.io/badge/License-AGPL--3.0--only-orange.svg)](LICENSE)
![Platforms](https://img.shields.io/badge/Platforms-macOS%20%7C%20Windows%20%7C%20iOS%20%7C%20iPadOS%20%7C%20Android-2ea44f)

> **快速下载**：前往 [**GitHub Releases**](https://github.com/Pybsama/Caochaoxing/releases/latest) 直接获取 **macOS（Apple Silicon `.app.zip`）**、**Windows（便携 ZIP / `.msi` 安装包）** 与 **Android（APK）**；iOS / iPadOS 目前可通过 Xcode 自行签名安装，后续计划开放 TestFlight。

![项目封面：蓝色短发的动画角色站在咖啡机前](docs/artwork/project-cover.jpg)

Caochaoxing 是一个面向 **macOS、Windows、iOS / iPadOS 和 Android** 的非官方学习通客户端。你可以用自己的账号查看课程、群聊、课堂签到活动和章节任务点。Apple 两端提供课程视频和直播回放的内嵌播放器（macOS 还内置免重新登录网页视图可直接作答章节测验）；Windows 与 Android 可查看任务进度、多路合法同步看课并按需在浏览器打开回放原网页。

> **代签功能尚未经过大范围测试，实际考勤效果仍待验证。** 如果遇到问题，欢迎在本仓库的 **Issues → New issue** 反馈。请写明设备、版本、操作步骤及预期和实际结果，并隐去账号、Cookie、课堂二维码等个人信息。详见[反馈与贡献](#反馈与贡献)。

- **macOS 桌面端（`CaochaoxingMac`）**：原生 Swift 6 / SwiftUI 暖白纸感极简界面，支持多账号快捷切换气泡面板与 `⌥⌘1..9` 快捷键、完整 5 类签到/签退/独立扫码/本地滑块与底库人脸核验、**内置免重新登录测验网页视图**（`WKWebView`，自动注入当前账号 Cookie，关页自动刷新任务点状态），以及内嵌视频播放器与 **1/2/3/5/8/10 路并发同步看课引擎**（默认 5 路、严格等待画面加载缓冲完成后再开始自动刷课、中途缓冲自动暂停计时、支持跨课程续看与文档自动完成）。
- **Windows 桌面端（`windows/`）**：基于 Kotlin 2.0.21 / Compose Multiplatform for Desktop（Material 3）并直接复用 `android/core` 纯 JVM 协议库，100% 纯开源依赖；使用 Windows 原生 DPAPI 加密持久化会话（`state.dpapi`）与 `SetThreadExecutionState` 防息屏，提供三栏课程看课、5 类签到/签退、本地图片与剪贴板二维码识别以及 `<canvas>` 围栏选点。
- **iOS / iPadOS 端（`CaochaoxingIOS`）**：使用 Swift 6 / SwiftUI 与本地 Swift Package `ChaoxingCore`，支持 **iPadOS 双栏 `NavigationSplitView` 与多列并行视频网格**、顶栏多账号快捷切换菜单、完整 5 类签到工作台，以及与 macOS / Android 对齐的「课程看课」分栏（支持 1/2/3/5 路并发、普通视频/音频、`zhibo.chaoxing.com` 外站直播回放并行播放与 HLS→MP4 自动回退、PDF/文档一键完成、全屏视频预览、403 自动限流降级与防息屏）。
- **Android 端（`android/`）**：使用 Kotlin 2.0.21 / Jetpack Compose（Material 3）与纯 JVM 核心模块 `:core`（`com.caochaoxing.core`）。提供签到工作台、课程章节和多任务进度面板。回放原网页通过浏览器打开。地图使用开放数据，扫码使用 CameraX 和 ZXing。

本项目参考并改写了 [aquamarine5/ChaoxingSignFaker](https://github.com/aquamarine5/ChaoxingSignFaker) 的部分协议实现，采用 **AGPL-3.0-only** 发布；详见 [LICENSE](LICENSE) 与 [来源和版权说明](THIRD_PARTY_NOTICES.md)。本项目由 Pybsama 维护，与超星、学校及上游作者没有官方隶属或背书关系。

## 作者的话

大家好。我试着写了一个学习通的代签软件。起因是我觉得水课太多，很浪费我们大学生的时间，很多其他课程也将我们的时间切成小段，没办法长期做自己的事情。如果让室友代签，需要多个设备，实践上是很麻烦的。后续我也加了刷课程观看的功能，自动过课程任务点。

需要注意的是，在目前，我用授权账号测过登录、会话恢复和课程读取，也用模拟任务走过签到详情、加载失败重试和返回列表的流程，以及测试过刷需要观看的课程。由于我们学校高数、英语上课会收手机，并且很多水课都结课了，加上我和同学的关系并不是很好，我没有什么亲自验证的机会，所以我还没有在真实课堂提交过签到，也没有核对过后台考勤记录。

欢迎在自己的手机上试用。遇到问题，可以在 Issues 里写下设备、系统版本、项目版本，以及哪一步和预期不同。若在教师或管理员授权的独立测试班级核验过结果，也欢迎分别写明客户端提示和后台记录。反馈时请隐去账号、Cookie、活动二维码等个人信息。

计划在 TestFlight 发布 iOS 版本，[Releases](https://github.com/Pybsama/Caochaoxing/releases) 中已放上去安卓版和 Mac 版（Mac 显然不能扫码，所以主要是刷课而已）。现在有 Mac 的用户可以用 Xcode 打开源码，自行签名安装到 iPhone 或 iPad。

## iPhone / iPad 如何安装

项目支持 iOS / iPadOS 17 及以上。目前仓库提供源码，尚无公开的 TestFlight 邀请或 App Store 下载链接。

- **有 Mac，想在自己的设备试用**：使用 Xcode 和自己的 Apple 账号签名安装，步骤见下方「iOS / iPadOS」。免费账号的 Personal Team 描述文件有效期为 7 天，到期需重新构建安装。[Apple 个人开发账号说明](https://developer.apple.com/help/account/basics/about-your-developer-account)
- **只有 iPhone 或 iPad**：等项目开放 TestFlight 后，从 App Store 安装免费的 TestFlight，再用邀请链接安装 Caochaoxing。测试者无需 Mac 或开发者账号，每个测试构建最多可用 90 天。[Apple TestFlight 说明](https://developer.apple.com/help/app-store-connect/test-a-beta-version/testflight-overview/)

## 验证状态

新版本分别显示本次请求获接受、此前已签、失败或未确认的数量。旧记录保留原来的合计，并标明可能包含此前已签。客户端收到的回执仍缺少与真实课堂后台考勤记录的核对。

| 环境 | 已验证的范围 |
| --- | --- |
| iOS / iPadOS | Xcode 26.3 完成通用模拟器 Release 编译、链接及资源打包，并在全新的 iPhone 17 模拟器启动至登录页。此前的授权账号登录及课程、活动读取见 [iOS 验证记录](docs/VALIDATION.md)。 |
| Android | 90 项单元测试通过，Debug 和未签名 Release APK 均构建成功。Lint 有 12 个警告、0 个错误。API 35 模拟器通过 6 项设备测试，覆盖活动详情与重试、三种活动状态、系统存储和密码表单重建。模拟活动提交次数为 0。[活动导航测试](android/app/src/androidTest/kotlin/com/caochaoxing/android/SimulatedActivityNavigationTest.kt) · [密码表单测试](android/app/src/androidTest/kotlin/com/caochaoxing/android/PasswordRecreationTest.kt) · [此前的授权账号验证](docs/ANDROID_UI_VALIDATION.md) |
| macOS / Swift Package | 126 项 Swift 测试通过，覆盖共享库、测验直达链接解析、iOS 状态控制器以及 macOS 账号切换/并发缓冲等待/Cookie 隔离回归。macOS Release 构建及本地签名校验通过。[macOS 回归测试](Tests/CaochaoxingMacTests/MacAppStateRegressionTests.swift) |
| Windows 桌面端 | 复用 `android/core` 并在 `windows/` 下运行 `:core:test` 与 `:app:test`（含 DPAPI 存储、状态流转、看课控制器与 UI 渲染冒烟测试），并由 GitHub Actions `windows-latest` 自动构建便携版 ZIP 与 `.msi` 安装包。 |

欢迎在自己的设备上试用，并通过 GitHub Issues 反馈成功或失败的结果。如果希望验证提交后的记录，请使用由教师或管理员明确授权、与正式考勤隔离的测试班级和测试账号，并请组织者核对后台记录。

## 功能概览

以下是三端源码中已实现的能力，在线可用性取决于服务端接口、账号权限及活动设置：

- **多账号与灵活切换**：账号登录、多账号会话池、灵活切换主账号（macOS 支持顶栏/侧边栏/设置页与菜单栏 `⌥⌘1..9` 快捷键，iOS / iPadOS 支持课程顶栏快捷切号菜单与一键刷新会话，切换自动隔离并清理课程与看课缓存）、学校 FID 切换、设备码重置、账号分组标签与多账号错峰批量签到（支持自定义批量间隔、内置客户端标识及自定义 User-Agent 配置）。
- **课程、雷达与群聊**：课程列表、常用课程标星置顶、待签活动一键扫描雷达、课程活动列表，以及基于环信 IM 接口的群聊与群聊签到活动查看。
- **课程章节、任务进度与播放**：
  - 与签到功能分栏独立，互不干扰。
  - 兼容经典章节页（`h3/h4/h5` + `roundpoint`）、`mooc2-ans` 课程页与 `gas/clazz` 章节树解析，并发拉取全部多标签卡片（`cardcount` + `knowledge/cards` + `/ananas/status` + `/ananas/live/liveinfo`）。
  - 可查看普通视频、音频、PDF/PPT 文档及超星直播的外部回放任务。Apple 两端支持 HLS/MP4 播放回退，Android 可从有链接的回放进入原网页。
  - 默认仅处理当前选中的课程。「自动匹配其他课程」默认关闭，「顺便完成 PDF/文档任务点」默认开启。页面显示的是客户端读取到的任务进度，最终学习记录需在课程后台核对。
  - macOS 内嵌播放器，iPadOS 提供双栏和视频网格，iPhone 支持紧凑卡片及单独预览。iOS / iPadOS 离开看课页、进入后台、关闭画面或打开单独预览时，会停止当前同步。返回后可手动重新开始。Android 任务运行期间可保持屏幕常亮。
- **5 类签到与签退（macOS / iOS / Android）**：支持普通/拍照、二维码、手势、位置、签到码 5 类签到流程；支持在统一签到工作台内直接钻取关联签退活动、独立二维码粘贴/扫码签到、二维码过期与签到码预检、设备重复自动换码重试，以及服务端人脸底库照片自动核验。
- **扫码与图像支持**：
  - 相机实时扫码（iOS 使用 AVFoundation；Android 使用 CameraX + ZXing Core）。
  - 系统选图识别二维码（iOS 使用 `PhotosPicker`；Android 使用 `PickVisualMedia`，无需宽泛相册读取权限）与文本/链接粘贴解析（三端均支持）。
  - 拍照防重复处理（Android `AndroidImageObfuscator` 支持 EXIF 方向归一化、微裁切与缩放、稀疏像素扰动及 JPEG `COM` 注释块写入）。
- **滑块验证**：三端统一使用本地像素边缘梯度匹配自动识别（纯代码计算，不使用 ONNX 推理），自动识别失败时回退手动滑块弹窗，并支持多账号并发验证排队。
- **数据安全与备份**：
  - iOS 在应用沙盒内保存 `state.json`。按下方命令构建的 macOS 版将会话保存在当前用户的 `~/Library/Application Support/Caochaoxing/state.json`，文件权限为 `0600`。Android 使用 **Android Keystore AES-256-GCM** 加密存储 `filesDir/caochaoxing/state.enc`。
  - 本地保留最近 100 条签到历史。iOS 和 Android 支持导入、导出格式兼容的 JSON 备份，默认不含账号凭据。Android 使用系统文件选择器。
  - iOS 和 Android 支持通过 `caochaoxing://` 打开活动或导入二维码内容。

界面遵循各自平台原生规范（macOS / iOS 原生 SwiftUI / Android Material 3 Edge-to-Edge），提供一致的中性画布与强调色、无障碍语义状态与清晰对比度。

## 零密钥开放地图与三坐标系协作（Android）

为保持项目 100% 纯开源且不绑定商业地图开发者密钥，Android 端采用开放地图组件配合纯 Kotlin 坐标转换：

- **免 API Key 嵌入式选点**：位置签到工作台通过内嵌 `WebView` 加载本地静态页 `assets/leaflet_picker.html`，在网络可达时通过 HTTPS 按需加载 **Leaflet 1.9.4** 与 **OpenStreetMap** 瓦片，支持点击选点、拖动图钉并实时绘制教师电子围栏；当处于离线、校园网受限或瓦片加载失败时，自动切换为纯本地交互式 `<canvas>` 坐标网格（支持点击选点、拖动、双指/按钮缩放与围栏绘制）。
- **开放地理编码与原生定位**：地点关键词搜索使用 **OpenStreetMap Nominatim**（`NominatimGeocoder`，不携带任何超星 Cookie）；“当前定位 (GPS)”按钮调用 Android 原生 `LocationManager`（`SystemLocationProvider`）请求单次前台 `WGS-84` 坐标（不申请后台定位权限）。
- **三坐标系互转与国内地图协作**：纯 Kotlin 实现 **`WGS-84 <-> GCJ-02 <-> BD-09`** 双向转换（`CoordinateConverter`）与外部坐标文本/链接解析（`ExternalCoordinateParser`）：
  - **外部地图跳转**：支持通过隐式 Intent 启动设备已安装的 **高德地图（`amapuri://`）**、**百度地图（`baidumap://`）**、**腾讯地图（`qqmap://`）** 或系统通用地图（`geo:`）查看位置或辅助选点（`ExternalMapLauncher`），不集成任何闭源地图 SDK。
  - **剪贴板坐标导入**：支持从高德/百度/腾讯/OSM 分享文本、地图链接或纯经纬度中提取坐标，自动识别来源坐标系（或按用户选定的默认坐标系）并转换为超星接口所需的 **BD-09** 坐标。

iOS 端地点搜索与选点则使用系统内置 **Apple MapKit**，当前未调用 `CLLocationManager` 请求设备定位。

## 环境要求与编译运行

### macOS 桌面端（`CaochaoxingMac`）

| 项目 | 要求 |
| --- | --- |
| 系统版本 | macOS 14.0 或更高 |
| 工具链 | Swift 6.0+（Command Line Tools 或完整 Xcode） |

在仓库根目录直接构建并运行 macOS 客户端：

```sh
swift test
swift build -c release --product CaochaoxingMac
.build/release/CaochaoxingMac
```

`swift test` 同时验证 `ChaoxingCore` 协议核心（含课程看课、文档完成、滑块与 5 类签到解析）及 `IOSAppStateSupport`。

---

### iOS / iPadOS

| 项目 | 要求 |
| --- | --- |
| App 部署目标 | iOS / iPadOS 17.0 或更高 |
| 开发工具 | 完整 Xcode，支持 Swift 6 与当前工程格式；本轮检查使用 Xcode 26.3 |
| Swift Package | `swift-tools-version: 6.0` |
| 本机核心测试 | macOS 14.0 或更高 |
| 模拟器 | 与所选 Xcode 兼容的 iOS 平台支持和 Simulator Runtime |
| 真机 | Apple Account、可用的开发 Team、唯一 Bundle Identifier，以及设备要求的开发者模式 |

Apple 平台工程仅依赖系统框架（SwiftUI / AVKit / MapKit / CryptoKit），没有远程 Swift Package 依赖。`IOSAppStateSupport` 仅将实际 iOS 状态控制器编译为本机测试支持模块。

1. 获取完整源码后，在 Xcode 中打开 **`Caochaoxing.xcodeproj`**（打开 `Package.swift` 只会得到核心库与测试，不会得到 iOS App Target）。
2. 选择 **Caochaoxing** scheme，等待本地 Package 解析完成。选择模拟器运行时，在 Xcode → Settings → Components 安装对应的 Simulator Runtime。
3. 真机运行需在 **Signing & Capabilities** 启用 **Automatically manage signing**，填入自己的 Team 与唯一 Bundle Identifier（例如 `com.yourname.caochaoxing`）；仓库中 `DEVELOPMENT_TEAM` 保持为空，不包含任何签名凭据。
4. 在 Xcode → Settings → Accounts 登录自己的 Apple 账号，用数据线连接并解锁 iPhone，按提示信任并配对。在 iPhone 的「设置 → 隐私与安全性 → 开发者模式」按提示重启并确认，然后在 Xcode 顶部选中这台 iPhone，按 **⌘R** 安装并启动。[Apple 开发者模式说明](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)

#### iOS 命令行验证

在仓库根目录运行：

```sh
swift test
swift build
```

`swift test` 验证 Swift 核心库与实际 iOS 状态控制器；`swift build` 构建 Package 目标。两者**不会构建完整 iOS App**。如需构建完整 iOS 模拟器工程，可按实际安装位置指定 `DEVELOPER_DIR`：

```sh
export DEVELOPER_DIR=/Applications/Xcode-26.3.app/Contents/Developer
xcodebuild -version
xcodebuild -showsdks
xcrun simctl list runtimes
xcodebuild -project Caochaoxing.xcodeproj \
  -scheme Caochaoxing \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath DerivedData \
  build
```

Apple 文档：[安装 Xcode 组件](https://developer.apple.com/documentation/xcode/downloading-and-installing-additional-xcode-components)、[模拟器与真机运行和签名](https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices)。

---

### Android

| 项目 | 要求 |
| --- | --- |
| Android 部署目标 | Android 8.0 或更高（`minSdk 26`、`targetSdk 35`、`compileSdk 35`） |
| 开发工具 | Android Studio（支持 AGP `8.7.3` 与 Kotlin `2.0.21`）或独立 Gradle CLI |
| 构建与语言 | AGP `8.7.3`、Kotlin `2.0.21`、Gradle `8.10.2`、JDK `17` 或 `21`（编译目标字节码 `JVM 17`） |
| 开源依赖管理 | Gradle Version Catalog（[android/gradle/libs.versions.toml](android/gradle/libs.versions.toml)） |

仓库包含 Gradle Wrapper（`gradlew`、`gradlew.bat`、Wrapper JAR 和配置），固定 Gradle `8.10.2` 并校验分发包 SHA-256；首次运行需要网络下载。

#### Android 构建与测试方式

1. **Android Studio**：选择 **Open** 并打开仓库下的 **`android/`** 目录，等待 Gradle Sync 完成后即可运行 `:core`（JUnit 5）与 `:app`（JUnit 4）单元测试，或运行/打包 `:app` Debug 构建。
2. **命令行（需安装 JDK 17 或 JDK 21、Android SDK Platform 35 / Build Tools 35，并设置 `JAVA_HOME`、`ANDROID_HOME`）**：

```sh
./android/gradlew -p android :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

3. **GitHub Actions CI**：仓库已配置 [.github/workflows/android-ci.yml](.github/workflows/android-ci.yml)，在 `ubuntu-latest` 上使用 Temurin JDK `17`、`android-actions/setup-android@v3` 与 `gradle/actions/setup-gradle@v4`（Gradle `8.10.2`）依次执行 `gradle :core:test`、`gradle :app:testDebugUnitTest` 与 `gradle :app:assembleDebug`，并上传 `Caochaoxing-android-debug` Debug APK 产物。

上述 JVM 测试使用 `OkHttp MockWebServer`、合成账号与内存/临时目录依赖，不涉及真实登录或线上签到请求。连接专用测试模拟器后，可运行 `./android/gradlew -p android :app:connectedDebugAndroidTest`，验证真实 Keystore、文件迁移/损坏恢复和未登录 Activity 生命周期。APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`；这是开发签名的测试包。线上登录、课程与真实签到的 Android 验收边界见验证记录。

---

### Windows 桌面端（`windows/`）

| 项目 | 要求 |
| --- | --- |
| 目标系统 | Windows 10 / 11（x64） |
| 开发工具 | IntelliJ IDEA 或独立 Gradle CLI（内置 Gradle `8.10.2` Wrapper） |
| 运行与构建 | Kotlin `2.0.21`、Compose Multiplatform `1.7.1`、JDK `17` 或 `21`（编译目标字节码 `JVM 17`；打包 `.msi` 需要 Windows 上的 WiX Toolset） |
| 协议核心 | 通过 `windows/settings.gradle.kts` 直接复用 `android/core` 纯 JVM 核心库 |

#### Windows 构建、测试与打包方式

在仓库根目录执行（Windows PowerShell / CMD 使用 `windows\gradlew.bat`，macOS / Linux 开发验证使用 `./windows/gradlew`）：

```powershell
# 运行 :core 与 Windows 桌面端全部 JVM 单元测试
.\windows\gradlew.bat -p windows :core:test :app:test

# 直接在本地启动桌面客户端
.\windows\gradlew.bat -p windows :app:run

# 构建免安装便携版目录（自带裁剪 JRE，开箱即用，输出位于 windows\app\build\compose\binaries\main\app\）
.\windows\gradlew.bat -p windows :app:createDistributable

# 构建 Windows MSI 安装包（免管理员权限单用户安装，输出位于 windows\app\build\compose\binaries\main\msi\）
.\windows\gradlew.bat -p windows :app:packageMsi
```

仓库已配置 [.github/workflows/windows-ci.yml](.github/workflows/windows-ci.yml)，在 `windows-latest` 上使用 Temurin JDK `21` 与 Gradle `8.10.2` 自动运行 `:core:test`、`:app:test`，并打包上传 `Caochaoxing-windows-portable`（便携 ZIP）与 `Caochaoxing-windows-msi`（MSI 安装包）产物。

## 项目结构

```text
Caochaoxing.xcodeproj/                 iOS App 工程与共享 scheme
Package.swift                          Swift Package（ChaoxingCore、CaochaoxingMac、状态测试支持与测试目标）
Sources/
  CaochaoxingMac/                      macOS 原生 SwiftUI 客户端（多账号切换、5 类签到/滑块/人脸、视频看课与文档完成）
  CaochaoxingIOS/                      iOS / iPadOS SwiftUI 页面、应用状态、扫码支持、Info.plist 与资源
  ChaoxingCore/                        Swift 模型、网络、加密、签到与课程看课协议服务、解析、本地存储与设计系统
Tests/ChaoxingCoreTests/               Swift 离线单元测试与合成样例（含看课协议与对比度校验）
Tests/CaochaoxingMacTests/             macOS 账号切换、移除、取消与雷达回归测试
android/
  build.gradle.kts                     Android 根工程插件声明
  settings.gradle.kts                  Gradle 模块定义（:core、:app）
  gradle.properties                    Gradle JVM、并行与 AndroidX 配置
  gradle/
    libs.versions.toml                 Kotlin / AndroidX / Compose / OkHttp / CameraX / ZXing / JUnit 依赖目录
    wrapper/
      gradle-wrapper.properties        Gradle 8.10.2 分发配置与 SHA-256
      gradle-wrapper.jar               Gradle Wrapper 启动器
  gradlew / gradlew.bat                 macOS / Linux 与 Windows 构建入口
  core/                                纯 JVM Kotlin 核心库（模型、OkHttp、加解密、签到与课程看课协议服务、坐标转换、测试）
  app/                                 Android Jetpack Compose 应用（签到工作台、课程看课引擎、AES-GCM 存储、CameraX、WebView Leaflet）
windows/
  build.gradle.kts / settings.gradle.kts Windows 根工程配置（直接复用 ../android/core）
  gradle/libs.versions.toml            Kotlin / Compose Multiplatform / OkHttp / ZXing / JNA / JUnit 5 依赖目录
  gradlew / gradlew.bat                macOS / Linux 与 Windows 构建入口
  app/                                 Windows Compose Desktop 应用（DPAPI 加密存储、防息屏、三栏看课、5 类签到与 Canvas 围栏、打包配置与测试）
.github/workflows/
  android-ci.yml                       Android 单元测试与 Debug APK 自动构建工作流
  swift-ci.yml                         Swift 单元测试与 macOS Release 构建工作流
  windows-ci.yml                       Windows 单元测试、便携版 ZIP 与 MSI 安装包自动构建工作流
README.md                              使用、构建、权限与风险说明
LICENSE                                AGPL v3 完整协议文本
THIRD_PARTY_NOTICES.md                 上游出处、版权声明、双端改写范围与开源依赖列表
docs/                                  各端验证记录、手动验收清单和图标定稿（artwork/Avatar.png）
```

历史设计讨论和本机审计日志不纳入公开仓库。

## URL Scheme

双端均注册自定义 Scheme `caochaoxing`（Android 在 `AndroidManifest.xml` 的 `MainActivity` 中以 `launchMode="singleTop"` 注册 `ACTION_VIEW` / `BROWSABLE` / `DEFAULT`），支持下列入口：

```text
caochaoxing://sign?activeId=12345&courseId=23456&classId=34567
caochaoxing://qr?data=SIGNIN%3Aid%3D12345%26c%3Ddemo%26enc%3Ddemo
```

示例参数为虚构值，不可用于真实活动。`sign` 接受 `activeId`（或 `id`），以及可选 `courseId`、`classId`、`type`；`qr` 接受 `data`（或 `url`），参数值必须进行 URL 百分号编码。入口仅用于打开签到工作台或导入二维码内容，**不会自动提交签到**；`sign` 在已有会话时可能请求活动详情。

未配置 iOS Universal Links 与 Android App Links（`autoVerify`）；普通 HTTPS 链接不会自动唤起应用。不要在公开链接、问题报告或日志中携带真实二维码 `enc`、账号信息或会话凭据。

## 权限、网络与数据

| 项目 | iOS 当前行为 | Android 当前行为 |
| --- | --- | --- |
| 相机 | 扫描二维码，`Info.plist` 包含用途说明；需真机验证权限和扫码 | 声明 `CAMERA`（`hardware.camera` 为 `required="false"`），由 CameraX + ZXing 仅在开启扫码时请求使用 |
| 照片 / 媒体 | 使用系统 `PhotosPicker` 选择单张图片；不会主动扫描整个相册 | 使用系统 `PickVisualMedia` 选择单张图片用于拍照签到或识别二维码，**不申请**宽泛相册读取权限 |
| 定位与地图 | 使用系统 MapKit 搜索和手动选点；未调用 `CLLocationManager` 请求设备定位，无后台定位权限 | 声明前台定位权限 `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`（**无后台定位权限**）；仅点击定位时通过 `LocationManager` 执行单次 GPS 定位；内嵌 WebView 加载 OpenStreetMap + Leaflet 1.9.4（离线切换本地网格），支持 Nominatim 搜索及通过 `<queries>` 白名单跳转外部高德/百度/腾讯/系统地图 |
| 网络与明文限制 | 请求超星及其图片/云盘服务、环信群聊接口与 Apple MapKit；未配置 ATS 全局放宽 | 声明 `INTERNET`，设置 `android:usesCleartextTraffic="false"` 禁止明文 HTTP；Cookie 限制在可信 HTTPS `chaoxing.com` 域；Nominatim 隔离 Cookie；WebView 禁用本地文件访问与混合内容 |
| 账号与状态存储 | 沙盒 Application Support 下的 `Caochaoxing/state.json`，包含账号标识、Cookie 与协议加密密码字段 | 使用 **Android Keystore AES-256-GCM**（随机 12 字节 IV + 128 位认证标签）加密保存至应用私有目录 `filesDir/caochaoxing/state.enc`；清单配置 `android:allowBackup="false"` 禁止系统云备份；支持旧版 `state.json` 自动加密迁移 |
| 备份导入 / 导出 | JSON 导出默认不含账号凭据；勾选包含会话后导出含凭据明文 JSON | 通过系统 SAF 导出/导入与 iOS 格式兼容的 JSON 备份；默认不含账号（`caochaoxing_presets_backup.json`），勾选包含会话后导出含凭据 JSON（`caochaoxing_full_backup.json`） |

**存储安全边界说明：**
- **iOS 端当前未采用 Keychain 存储会话，也未对整个 `state.json` 进行应用层加密**；登录字段协议加密使用公开固定参数，不可当作安全的本地密码保险箱。
- **Android 端**本地持久化状态已由 Android Keystore AES-256-GCM 加密保护，但导出的含账号 JSON 备份、以及尚未迁移清理的旧版明文文件仍包含敏感凭据，同样不可公开分享或在共享设备中留存。
- 双端在读取本地文件失败或解密失败时，**都不会**当成首次运行静默覆盖原文件，而是进入恢复页面，允许在保留恢复副本（iOS：`state.recovery-*.json`；Android：`state.recovery-*.enc`）后显式重置。退出账号会清理本地会话并停止后续请求，但不承诺撤回已发送请求、撤销服务端会话或销毁外部已导出备份。导入备份时会校验文件体积（上限 8 MB）、经纬度与参数合法性，并按账号合并会话、地点与最近 100 条签到历史。

## 许可证与使用声明

Copyright (c) 2026 Pybsama。上游相关部分的版权仍归原作者和贡献者所有；详细范围、Android 开源依赖清单及保留声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。代码按 **GNU Affero General Public License version 3（AGPL-3.0-only）** 分发，完整条款以 [LICENSE](LICENSE) 为准。

应用图标取自《BLACK SOULS II》的游戏画面。图标和 README 封面的公开使用授权均待确认。[素材来源与授权状态](THIRD_PARTY_NOTICES.md#图标及其他素材)

**使用声明：本项目仅限学习研究使用，使用后果自负。** 请遵守适用法律、学校管理规定及服务条款，不要用于未经授权的账号操作、代签、伪造考勤或侵犯他人隐私。该声明表达项目用途倡议和风险提示，不对 AGPL 已授予的权利增加额外限制，也不意味着任何责任都能依法免除。

软件按“现状”提供，不保证接口持续可用、数据安全或特定用途适用性。离线测试通过不能证明实际考勤有效，也不能代替学校或服务提供方的认可。

## 反馈与贡献

反馈时请注明项目版本或 commit、设备型号、系统版本、测试环境和操作步骤。请分别写明客户端显示了什么，以及测试班级后台是否已核对、看到了什么。只走到活动详情也可以反馈，注明“仅查看详情”即可。请隐去账号、密码、Cookie 和课堂二维码，打码后再分享截图。即使选择“不含账号”，导出的备份仍可能包含课程名称、历史记录和地点，请勿上传到 Issue。

- 修改 Swift / iOS 逻辑后，运行 `swift test && swift build`（涉及界面、资源或工程设置时同步执行 `xcodebuild` 验证并说明测试范围）。
- 修改 Android 逻辑后，在本地 Gradle/JDK 17 环境运行 `./android/gradlew -p android :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`（或通过 CI 工作流验证）并说明真机/单元测试覆盖范围。
