import AVFoundation
import SwiftUI

/// 新手机:「从旧手机导入」扫码页(sheet)。扫到迁移码即回调并关闭取景。
struct MigrateScanView: View {
    let onCode: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    /// 授权结果回来之前不渲染取景(否则先闪一下黑屏,拒绝后又换成提示)
    private enum CameraState { case checking, authorized, denied, unavailable }
    @State private var camera: CameraState = .checking

    var body: some View {
        NavigationStack {
            ZStack {
                switch camera {
                case .checking:
                    ProgressView()
                case .authorized:
                    ZStack {
                        Color.black.ignoresSafeArea()
                        QRCameraView(onCode: onCode, onUnavailable: { camera = .unavailable })
                            .ignoresSafeArea()
                        VStack {
                            Spacer()
                            Text("扫描旧手机 TernDays 迁移页上的二维码")
                                .font(.system(size: 13)).foregroundColor(.white)
                                .padding(.horizontal, 14).padding(.vertical, 8)
                                .background(Capsule().fill(Color.black.opacity(0.55)))
                                .padding(.bottom, 40)
                        }
                    }
                case .denied:
                    message(
                        "需要相机权限才能扫码",
                        "请到 系统设置 → TernDays 中开启相机",
                        showSettings: true
                    )
                case .unavailable:
                    message(
                        "相机暂时不可用",
                        "可能正被其他应用占用,或本机没有可用的摄像头。关闭其他使用相机的应用后重新打开本页。",
                        showSettings: false
                    )
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Td.bg)
            .navigationTitle("从旧手机导入")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
            .onAppear(perform: checkCamera)
        }
    }

    private func checkCamera() {
        guard AVCaptureDevice.default(for: .video) != nil else { camera = .unavailable; return }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            camera = .authorized
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async { camera = ok ? .authorized : .denied }
            }
        default:
            camera = .denied
        }
    }

    private func message(_ title: String, _ detail: String, showSettings: Bool) -> some View {
        VStack(spacing: 10) {
            Text(title)
                .font(.system(size: 15, weight: .semibold)).foregroundColor(Td.ink)
            Text(detail)
                .font(.system(size: 13)).foregroundColor(Td.muted)
                .multilineTextAlignment(.center)
            if showSettings {
                Button("去设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .font(.system(size: 14, weight: .semibold)).foregroundColor(Td.accentDeep)
                .frame(minHeight: 44)
            }
        }
        .padding(.horizontal, 32)
    }
}

private struct QRCameraView: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    let onUnavailable: () -> Void

    func makeUIViewController(context: Context) -> QRCameraController {
        let vc = QRCameraController()
        vc.onCode = onCode
        vc.onUnavailable = onUnavailable
        return vc
    }

    func updateUIViewController(_ uiViewController: QRCameraController, context: Context) {}
}

final class QRCameraController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    /// 配置失败(相机被占用、输入/输出加不上)时回调,界面换成错误态而不是一直黑屏
    var onUnavailable: (() -> Void)?
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "app.terndays.scan")
    private var delivered = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        sessionQueue.async { [weak self] in self?.configure() }
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else {
            DispatchQueue.main.async { [weak self] in self?.onUnavailable?() }
            return
        }
        session.beginConfiguration()
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            DispatchQueue.main.async { [weak self] in self?.onUnavailable?() }
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        session.commitConfiguration()

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let layer = AVCaptureVideoPreviewLayer(session: self.session)
            layer.frame = self.view.bounds
            layer.videoGravity = .resizeAspectFill
            self.view.layer.insertSublayer(layer, at: 0)
        }
        session.startRunning()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        view.layer.sublayers?.first(where: { $0 is AVCaptureVideoPreviewLayer })?.frame = view.bounds
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sessionQueue.async { [weak self] in
            if self?.session.isRunning == true { self?.session.stopRunning() }
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !delivered,
              let obj = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              obj.type == .qr, let text = obj.stringValue else { return }
        delivered = true
        sessionQueue.async { [weak self] in self?.session.stopRunning() }
        onCode?(text)
    }
}
