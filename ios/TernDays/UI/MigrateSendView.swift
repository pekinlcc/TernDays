import CoreImage.CIFilterBuiltins
import SwiftUI

/// 旧手机:「迁移到新手机」页——展示二维码,等新手机扫码连入并取走数据。
struct MigrateSendView: View {
    @StateObject private var model = MigrateSendModel()

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                switch model.state {
                case .preparing:
                    ProgressView().padding(.top, 60)
                case .showing(let qr, let status):
                    TdCard {
                        VStack(spacing: 14) {
                            Image(uiImage: qr)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .background(Color.white)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                            Text(status ?? "在新手机上打开 TernDays → 设置 → 从旧手机导入,扫这个码")
                                .font(.system(size: 13))
                                .foregroundColor(status != nil ? Td.accentDeep : Td.muted)
                                .multilineTextAlignment(.center)
                        }
                        .padding(20)
                    }
                    Text("两台手机需连接同一个 Wi-Fi(或旧手机开热点、新手机连上)。\n数据经加密直接在两台手机之间传输,不经过任何服务器;\n离开此页面后二维码立即失效。")
                        .font(.system(size: 12))
                        .foregroundColor(Td.faint)
                        .multilineTextAlignment(.center)
                        .lineSpacing(4)
                case .done(let count):
                    VStack(spacing: 10) {
                        Text("✓").font(.system(size: 44)).foregroundColor(Td.accent)
                        Text("迁移完成").font(.system(size: 17, weight: .bold)).foregroundColor(Td.ink)
                        Text("新手机已导入 \(count) 条记录。本机数据保持不变。")
                            .font(.system(size: 13)).foregroundColor(Td.muted)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, 60)
                case .failed(let message):
                    Text(message)
                        .font(.system(size: 13)).foregroundColor(Td.warmDeep)
                        .multilineTextAlignment(.center)
                        .padding(.top, 60)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
        }
        .background(Td.bg)
        .navigationTitle("迁移到新手机")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .onDisappear { model.stop() }
    }
}

/// server 的回调均已切回主线程,直接更新 @Published 即可
final class MigrateSendModel: ObservableObject {
    enum State {
        case preparing
        case showing(qr: UIImage, status: String?)
        case done(count: Int)
        case failed(String)
    }

    @Published var state: State = .preparing
    private var server: MigrateSendServer?

    func start() {
        guard server == nil else { return }
        let server = MigrateSendServer()
        self.server = server
        server.onReady = { [weak self] qrText in
            guard let self, let qr = Self.qrImage(qrText) else { return }
            self.state = .showing(qr: qr, status: nil)
        }
        server.onStatus = { [weak self] msg in
            if case .showing(let qr, _) = self?.state {
                self?.state = .showing(qr: qr, status: msg)
            }
        }
        server.onDone = { [weak self] count in self?.state = .done(count: count) }
        server.onError = { [weak self] msg in self?.state = .failed(msg) }
        server.start()
    }

    func stop() {
        server?.stop()
        server = nil
    }

    private static func qrImage(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}
