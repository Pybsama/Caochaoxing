// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

import SwiftUI
@preconcurrency import AVFoundation
import ChaoxingCore

struct QRCodeCameraScannerView: View {
    let onScanned: (String) -> Void
    @Environment(\.scenePhase) private var scenePhase
    @State private var cameraError: String?
    @State private var cameraSessionID = UUID()
    @State private var zoomFactor: CGFloat = 1.0
    @State private var maxZoomFactor: CGFloat = 1.0
    @State private var hasTorch = false
    @State private var torchEnabled = false

    private var zoomPresets: [CGFloat] {
        let candidates: [CGFloat] = [1.0, 2.0, 3.0, 5.0]
        return candidates.filter { $0 <= maxZoomFactor + 0.05 }
    }

    var body: some View {
        ZStack {
            QRCodeCameraPreviewRepresentable(
                zoomFactor: $zoomFactor,
                maxZoomFactor: $maxZoomFactor,
                hasTorch: $hasTorch,
                torchEnabled: $torchEnabled,
                onScanned: onScanned,
                onError: { cameraError = $0 }
            )
            .id(cameraSessionID)
            .ignoresSafeArea()

            if let cameraError {
                VStack(spacing: 16) {
                    Image(systemName: "camera.fill").font(.largeTitle)
                    Text(cameraError).multilineTextAlignment(.center)
                    Button("打开系统设置") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                    .buttonStyle(.borderedProminent)
                }
                .foregroundStyle(.white)
                .padding(28)
                .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
                .padding()
            } else {
                // Non-interactive viewfinder so pinch-to-zoom & tap-to-focus pass directly to the camera preview.
                VStack(spacing: 20) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .strokeBorder(Color.white.opacity(0.22), lineWidth: 1)
                            .frame(width: 240, height: 240)

                        ViewfinderCornersShape(cornerLength: 26, cornerRadius: 24)
                            .stroke(
                                CxPalette.accent,
                                style: StrokeStyle(lineWidth: 3.5, lineCap: .round, lineJoin: .round)
                            )
                            .frame(width: 240, height: 240)
                    }

                    HStack(spacing: 7) {
                        Circle()
                            .fill(CxPalette.accent)
                            .frame(width: 6, height: 6)
                        Text("双指捏合或拖动下方滑块缩放 · 轻点对焦")
                            .font(CxTypography.mono(11.5))
                            .foregroundStyle(CxPalette.onAccent)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Color.black.opacity(0.62), in: Capsule())
                    .overlay(Capsule().strokeBorder(Color.white.opacity(0.16), lineWidth: 0.8))
                }
                .allowsHitTesting(false)

                // Interactive bottom zoom & torch control bar
                if maxZoomFactor > 1.05 || hasTorch {
                    VStack {
                        Spacer()
                        VStack(spacing: 10) {
                            HStack(spacing: 8) {
                                if maxZoomFactor > 1.05 {
                                    HStack(spacing: 6) {
                                        ForEach(zoomPresets, id: \.self) { preset in
                                            let isSelected = abs(zoomFactor - preset) < 0.15
                                            Button {
                                                zoomFactor = min(preset, maxZoomFactor)
                                            } label: {
                                                Text(preset == floor(preset) ? "\(Int(preset))x" : String(format: "%.1fx", preset))
                                                    .font(CxTypography.mono(12, weight: isSelected ? .bold : .medium))
                                                    .foregroundStyle(isSelected ? Color.black : Color.white)
                                                    .padding(.horizontal, 10)
                                                    .padding(.vertical, 5)
                                                    .background(
                                                        Capsule().fill(isSelected ? Color.white : Color.white.opacity(0.16))
                                                    )
                                            }
                                            .buttonStyle(.plain)
                                        }
                                    }
                                }

                                Spacer()

                                if hasTorch {
                                    Button {
                                        torchEnabled.toggle()
                                    } label: {
                                        Image(systemName: torchEnabled ? "flashlight.on.fill" : "flashlight.off.fill")
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(torchEnabled ? Color.yellow : Color.white)
                                            .frame(width: 34, height: 34)
                                            .background(Color.white.opacity(0.16), in: Circle())
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel(torchEnabled ? "关闭手电筒" : "开启手电筒")
                                }
                            }

                            if maxZoomFactor > 1.05 {
                                HStack(spacing: 10) {
                                    Image(systemName: "minus.magnifyingglass")
                                        .font(.caption)
                                        .foregroundStyle(.white.opacity(0.75))

                                    Slider(
                                        value: Binding(
                                            get: { Double(min(max(zoomFactor, 1.0), maxZoomFactor)) },
                                            set: { zoomFactor = CGFloat($0) }
                                        ),
                                        in: 1.0...Double(maxZoomFactor)
                                    )
                                    .tint(.white)

                                    Image(systemName: "plus.magnifyingglass")
                                        .font(.caption)
                                        .foregroundStyle(.white.opacity(0.75))

                                    Text(String(format: "%.1fx", zoomFactor))
                                        .font(CxTypography.mono(12, weight: .semibold))
                                        .foregroundStyle(.white)
                                        .frame(width: 40, alignment: .trailing)
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .background(Color.black.opacity(0.65), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.16), lineWidth: 0.8)
                        )
                        .padding(.horizontal, 18)
                        .padding(.bottom, 22)
                    }
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active, cameraError != nil {
                cameraError = nil
                zoomFactor = 1.0
                torchEnabled = false
                cameraSessionID = UUID()
            }
        }
    }
}


private struct ViewfinderCornersShape: Shape {
    let cornerLength: CGFloat
    let cornerRadius: CGFloat

    func path(in rect: CGRect) -> Path {
        var p = Path()
        let r = cornerRadius
        let l = cornerLength

        // Top-left
        p.move(to: CGPoint(x: rect.minX, y: rect.minY + l))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        p.addQuadCurve(to: CGPoint(x: rect.minX + r, y: rect.minY), control: CGPoint(x: rect.minX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.minX + l, y: rect.minY))

        // Top-right
        p.move(to: CGPoint(x: rect.maxX - l, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        p.addQuadCurve(to: CGPoint(x: rect.maxX, y: rect.minY + r), control: CGPoint(x: rect.maxX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + l))

        // Bottom-right
        p.move(to: CGPoint(x: rect.maxX, y: rect.maxY - l))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        p.addQuadCurve(to: CGPoint(x: rect.maxX - r, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.maxX - l, y: rect.maxY))

        // Bottom-left
        p.move(to: CGPoint(x: rect.minX + l, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        p.addQuadCurve(to: CGPoint(x: rect.minX, y: rect.maxY - r), control: CGPoint(x: rect.minX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - l))

        return p
    }
}

private struct QRCodeCameraPreviewRepresentable: UIViewControllerRepresentable {
    @Binding var zoomFactor: CGFloat
    @Binding var maxZoomFactor: CGFloat
    @Binding var hasTorch: Bool
    @Binding var torchEnabled: Bool
    let onScanned: (String) -> Void
    let onError: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onScanned = onScanned
        controller.onError = onError
        controller.onZoomChanged = { newZoom in
            zoomFactor = newZoom
        }
        controller.onCapabilitiesReady = { maxZoom, torchAvailable in
            maxZoomFactor = maxZoom
            hasTorch = torchAvailable
        }
        return controller
    }

    func updateUIViewController(_ controller: ScannerViewController, context: Context) {
        controller.onScanned = onScanned
        controller.onError = onError
        controller.onZoomChanged = { newZoom in
            zoomFactor = newZoom
        }
        controller.onCapabilitiesReady = { maxZoom, torchAvailable in
            maxZoomFactor = maxZoom
            hasTorch = torchAvailable
        }
        controller.applyZoomFromUI(zoomFactor)
        controller.applyTorchFromUI(torchEnabled)
    }

    static func dismantleUIViewController(_ controller: ScannerViewController, coordinator: ()) {
        controller.tearDown()
    }

    final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onScanned: ((String) -> Void)?
        var onError: ((String) -> Void)?
        var onZoomChanged: ((CGFloat) -> Void)?
        var onCapabilitiesReady: ((CGFloat, Bool) -> Void)?

        private let camera = SerializedCameraSession()
        private var previewLayer: AVCaptureVideoPreviewLayer?
        private var didEmit = false
        private var isVisible = false
        private var appearanceID = UUID()

        private var currentDisplayZoom: CGFloat = 1.0
        private var maxDisplayZoom: CGFloat = 1.0
        private var pinchStartZoom: CGFloat = 1.0
        private var lastAppliedTorch = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            let preview = AVCaptureVideoPreviewLayer(session: camera.session)
            preview.frame = view.bounds
            preview.videoGravity = .resizeAspectFill
            view.layer.addSublayer(preview)
            previewLayer = preview

            let pinch = UIPinchGestureRecognizer(target: self, action: #selector(handlePinch(_:)))
            view.addGestureRecognizer(pinch)

            let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
            doubleTap.numberOfTapsRequired = 2
            view.addGestureRecognizer(doubleTap)

            let singleTap = UITapGestureRecognizer(target: self, action: #selector(handleSingleTap(_:)))
            singleTap.numberOfTapsRequired = 1
            singleTap.require(toFail: doubleTap)
            view.addGestureRecognizer(singleTap)
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            previewLayer?.frame = view.bounds
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            isVisible = true
            didEmit = false
            appearanceID = UUID()
            let request = appearanceID
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: startCamera(for: request)
            case .notDetermined:
                AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                    Task { @MainActor in
                        guard let self, self.isVisible, self.appearanceID == request else { return }
                        if granted { self.startCamera(for: request) }
                        else { self.onError?("相机权限未开启。请在系统设置中允许访问相机，或关闭后改用相册识码。") }
                    }
                }
            case .denied, .restricted:
                onError?("无法访问相机。请检查系统设置中的相机权限，或关闭后改用相册识码。")
            @unknown default: onError?("当前设备无法使用相机，请改用相册或粘贴二维码。")
            }
        }

        private func startCamera(for request: UUID) {
            guard isVisible, appearanceID == request else { return }
            camera.start(
                delegate: self,
                onReady: { [weak self] maxZoom, hasTorch in
                    guard let self, self.isVisible, self.appearanceID == request else { return }
                    self.maxDisplayZoom = maxZoom
                    self.currentDisplayZoom = 1.0
                    self.onCapabilitiesReady?(maxZoom, hasTorch)
                    self.onZoomChanged?(1.0)
                },
                onError: { [weak self] message in
                    guard let self, self.isVisible, self.appearanceID == request else { return }
                    self.onError?(message)
                }
            )
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            tearDown()
        }

        func tearDown() {
            isVisible = false
            appearanceID = UUID()
            camera.stop()
        }

        func applyZoomFromUI(_ target: CGFloat) {
            let clamped = min(max(target, 1.0), max(1.0, maxDisplayZoom))
            guard abs(clamped - currentDisplayZoom) > 0.01 else { return }
            currentDisplayZoom = clamped
            camera.setDisplayZoom(clamped)
        }

        func applyTorchFromUI(_ enabled: Bool) {
            guard enabled != lastAppliedTorch else { return }
            lastAppliedTorch = enabled
            camera.setTorch(enabled)
        }

        @objc private func handlePinch(_ recognizer: UIPinchGestureRecognizer) {
            guard maxDisplayZoom > 1.05 else { return }
            switch recognizer.state {
            case .began:
                pinchStartZoom = currentDisplayZoom
            case .changed:
                let next = min(max(pinchStartZoom * recognizer.scale, 1.0), maxDisplayZoom)
                currentDisplayZoom = next
                camera.setDisplayZoom(next)
                onZoomChanged?(next)
            default:
                break
            }
        }

        @objc private func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
            guard maxDisplayZoom > 1.05 else { return }
            let target: CGFloat = currentDisplayZoom < 1.75 ? min(2.0, maxDisplayZoom) : 1.0
            currentDisplayZoom = target
            camera.setDisplayZoom(target)
            onZoomChanged?(target)
        }

        @objc private func handleSingleTap(_ recognizer: UITapGestureRecognizer) {
            guard let previewLayer else { return }
            let location = recognizer.location(in: view)
            let devicePoint = previewLayer.captureDevicePointConverted(fromLayerPoint: location)
            camera.focus(at: devicePoint)
        }

        nonisolated func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard let readable = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
                  let value = readable.stringValue else { return }
            Task { @MainActor in
                guard self.isVisible, !self.didEmit else { return }
                self.didEmit = true
                self.camera.stop()
                self.onScanned?(value)
            }
        }
    }
}

/// Only this serial queue configures or starts/stops the capture session and mutates device zoom/focus/torch.
/// The unchecked conformance is confined to AVFoundation's queue-bound session ownership.
private final class SerializedCameraSession: @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "Caochaoxing.camera.session", qos: .userInitiated)
    private var configured = false
    private var captureDevice: AVCaptureDevice?
    /// Virtual multi-camera devices (triple / dual-wide) map 1.0x standard wide lens to their first switchover factor.
    private var baseZoomFactor: CGFloat = 1.0
    private var maxDisplayZoom: CGFloat = 1.0

    func start(
        delegate: QRCodeCameraPreviewRepresentable.ScannerViewController,
        onReady: @escaping @MainActor @Sendable (CGFloat, Bool) -> Void,
        onError: @escaping @MainActor @Sendable (String) -> Void
    ) {
        queue.async { [self] in
            do {
                if !configured {
                    guard let device = Self.preferredBackVideoDevice() else {
                        throw ChaoxingError.invalidInput("未找到可用相机，请改用相册或粘贴二维码。")
                    }
                    let input = try AVCaptureDeviceInput(device: device)
                    let output = AVCaptureMetadataOutput()
                    session.beginConfiguration()
                    defer { session.commitConfiguration() }
                    guard session.canAddInput(input), session.canAddOutput(output) else {
                        throw ChaoxingError.invalidInput("相机暂不可用，请关闭后重试或使用相册识码。")
                    }
                    session.addInput(input)
                    session.addOutput(output)
                    output.setMetadataObjectsDelegate(delegate, queue: .main)
                    output.metadataObjectTypes = [.qr]

                    captureDevice = device
                    baseZoomFactor = Self.standardWideBaseFactor(for: device)
                    let rawMax = device.activeFormat.videoMaxZoomFactor
                    maxDisplayZoom = min(max(1.0, rawMax / baseZoomFactor), 10.0)
                    configured = true
                }

                if let device = captureDevice {
                    applyDeviceZoom(displayFactor: 1.0, on: device)
                }

                if !session.isRunning { session.startRunning() }

                let readyMax = maxDisplayZoom
                let torchAvailable = captureDevice?.hasTorch ?? false
                Task { @MainActor in onReady(readyMax, torchAvailable) }
            } catch {
                let message = error.localizedDescription
                Task { @MainActor in onError(message) }
            }
        }
    }

    func setDisplayZoom(_ displayFactor: CGFloat) {
        queue.async { [self] in
            guard let device = captureDevice else { return }
            applyDeviceZoom(displayFactor: displayFactor, on: device)
        }
    }

    func setTorch(_ enabled: Bool) {
        queue.async { [self] in
            guard let device = captureDevice, device.hasTorch else { return }
            do {
                try device.lockForConfiguration()
                defer { device.unlockForConfiguration() }
                if enabled && device.isTorchModeSupported(.on) {
                    try device.setTorchModeOn(level: AVCaptureDevice.maxAvailableTorchLevel)
                } else if device.isTorchModeSupported(.off) {
                    device.torchMode = .off
                }
            } catch {
                // Ignore transient hardware torch lock failures.
            }
        }
    }

    func focus(at devicePoint: CGPoint) {
        queue.async { [self] in
            guard let device = captureDevice else { return }
            do {
                try device.lockForConfiguration()
                defer { device.unlockForConfiguration() }
                if device.isFocusPointOfInterestSupported {
                    device.focusPointOfInterest = devicePoint
                    if device.isFocusModeSupported(.continuousAutoFocus) {
                        device.focusMode = .continuousAutoFocus
                    } else if device.isFocusModeSupported(.autoFocus) {
                        device.focusMode = .autoFocus
                    }
                }
                if device.isExposurePointOfInterestSupported {
                    device.exposurePointOfInterest = devicePoint
                    if device.isExposureModeSupported(.continuousAutoExposure) {
                        device.exposureMode = .continuousAutoExposure
                    }
                }
            } catch {
                // Ignore transient focus configuration errors.
            }
        }
    }

    func stop() {
        queue.async { [self] in
            if let device = captureDevice, device.hasTorch, device.torchMode != .off {
                if (try? device.lockForConfiguration()) != nil {
                    device.torchMode = .off
                    device.unlockForConfiguration()
                }
            }
            if session.isRunning { session.stopRunning() }
        }
    }

    private func applyDeviceZoom(displayFactor: CGFloat, on device: AVCaptureDevice) {
        let clampedDisplay = min(max(displayFactor, 1.0), maxDisplayZoom)
        let targetRaw = clampedDisplay * baseZoomFactor
        let hardwareMin = device.minAvailableVideoZoomFactor
        let hardwareMax = min(device.activeFormat.videoMaxZoomFactor, baseZoomFactor * 10.0)
        let clampedRaw = min(max(targetRaw, hardwareMin), max(hardwareMin, hardwareMax))
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            device.videoZoomFactor = clampedRaw
        } catch {
            // Ignore transient zoom lock errors.
        }
    }

    private static func preferredBackVideoDevice() -> AVCaptureDevice? {
        let types: [AVCaptureDevice.DeviceType] = [
            .builtInTripleCamera,
            .builtInDualWideCamera,
            .builtInDualCamera,
            .builtInWideAngleCamera,
        ]
        let discovery = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video, position: .back)
        if let preferred = types.compactMap({ t in discovery.devices.first(where: { $0.deviceType == t }) }).first {
            return preferred
        }
        return AVCaptureDevice.default(for: .video)
    }

    private static func standardWideBaseFactor(for device: AVCaptureDevice) -> CGFloat {
        if device.deviceType == .builtInTripleCamera || device.deviceType == .builtInDualWideCamera,
           let firstSwitch = device.virtualDeviceSwitchOverVideoZoomFactors.first?.doubleValue,
           firstSwitch >= 1.0 {
            return CGFloat(firstSwitch)
        }
        return 1.0
    }
}
