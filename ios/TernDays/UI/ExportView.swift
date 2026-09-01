import SwiftUI
import UIKit

struct ExportView: View {
    let initialYear: Int

    @State private var year: Int
    @State private var useXlsx = true
    @State private var incSummary = true
    @State private var incDaily = true
    @State private var data: YearData?
    @State private var shareURL: URL?
    @State private var busy = false
    @State private var failure: String?

    init(initialYear: Int) {
        self.initialYear = initialYear
        _year = State(initialValue: initialYear)
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    sectionLabel("导出范围")
                    HStack(spacing: 10) {
                        ForEach(data?.years ?? [year], id: \.self) { y in
                            let selected = y == year
                            Text("\(String(y)) 年")
                                .font(.system(size: 14, weight: selected ? .semibold : .regular))
                                .foregroundColor(selected ? Td.accentDeep : Td.muted)
                                .padding(.horizontal, 14).padding(.vertical, 10)
                                .background(
                                    RoundedRectangle(cornerRadius: 12)
                                        .fill(selected ? Td.accentFaintBg : Td.surface)
                                )
                                .overlay(
                                    RoundedRectangle(cornerRadius: 12)
                                        .stroke(selected ? Td.accent : Td.border, lineWidth: selected ? 1.5 : 1)
                                )
                                .onTapGesture { year = y }
                        }
                    }
                    sectionLabel("文件格式")
                    HStack(spacing: 10) {
                        formatCard("Excel", ".xlsx · 分表：汇总 + 明细", selected: useXlsx) { useXlsx = true }
                        formatCard("CSV", ".csv · 通用纯文本格式", selected: !useXlsx) { useXlsx = false }
                    }
                    sectionLabel("导出内容")
                    TdCard {
                        VStack(spacing: 0) {
                            checkRow("城市汇总", "每个城市的累计天数", checked: $incSummary)
                            Divider().overlay(Td.divider)
                            checkRow("每日明细", "每天早 / 晚打卡的时间、城市与计天结果", checked: $incDaily)
                        }
                        .padding(.horizontal, 16)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)

                previewCard
                    .padding(.horizontal, 20)
                    .padding(.top, 12)
            }

            VStack(spacing: 8) {
                Button {
                    generate()
                } label: {
                    HStack(spacing: 8) {
                        if busy {
                            ProgressView().tint(Td.onAccent)
                            Text("正在生成…").font(.system(size: 15, weight: .semibold))
                        } else {
                            Image(systemName: "square.and.arrow.up").font(.system(size: 15, weight: .semibold))
                            Text("生成文件并分享").font(.system(size: 15, weight: .semibold))
                        }
                    }
                    .foregroundColor(Td.onAccent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .background(RoundedRectangle(cornerRadius: 14).fill(Td.accent))
                }
                .disabled(busy || (!incSummary && !incDaily))
                Text("通过系统分享面板保存到手机或发送给其他 App\n文件在本机生成，不经过网络")
                    .font(.system(size: 11)).foregroundColor(Td.faint)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
        }
        .background(Td.bg)
        .navigationTitle("导出数据")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: year) { data = YearData.load(year: year) }
        .sheet(item: $shareURL) { url in
            ActivityView(items: [url])
        }
        .alert("导出没有成功", isPresented: .init(
            get: { failure != nil },
            set: { if !$0 { failure = nil } }
        )) {
            Button("知道了") { failure = nil }
        } message: {
            Text(failure ?? "")
        }
    }

    /// 明细预览:最近 3 天,让用户导出前先看一眼内容(对齐 Android)
    @ViewBuilder
    private var previewCard: some View {
        let rows = data.map { Exporter.dailyRows(stats: $0.stats, punches: $0.punches) }?
            .filter { $0.morning != nil || $0.evening != nil }
            .suffix(3) ?? []
        if !rows.isEmpty {
            TdCard {
                VStack(alignment: .leading, spacing: 6) {
                    Text("预览 · 每日明细").font(.system(size: 12)).foregroundColor(Td.faint)
                    previewRow(["日期", "早7点", "晚5点", "计入"], header: true)
                    ForEach(Array(rows), id: \.date) { r in
                        previewRow([
                            String(format: "%02d-%02d", r.date.month, r.date.day),
                            r.morning?.cityName ?? "–",
                            r.evening?.cityName ?? "–",
                            r.attribution.shares
                                .map { $0.cityName + ($0.weight >= 1.0 ? " +1" : " +0.5") }
                                .joined(separator: " "),
                        ])
                    }
                }
                .padding(14)
            }
        }
    }

    private func previewRow(_ cells: [String], header: Bool = false) -> some View {
        HStack(spacing: 6) {
            ForEach(Array(cells.enumerated()), id: \.offset) { _, c in
                Text(c)
                    .font(.system(size: 11, weight: header ? .semibold : .regular))
                    .foregroundColor(header ? Td.muted : Td.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .lineLimit(1)
            }
        }
    }

    private func sectionLabel(_ t: String) -> some View {
        Text(t).font(.system(size: 13, weight: .medium)).foregroundColor(Td.muted).padding(.leading, 2)
    }

    private func formatCard(_ title: String, _ sub: String, selected: Bool, action: @escaping () -> Void) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(title).font(.system(size: 15, weight: .bold))
                    .foregroundColor(selected ? Td.accentDeep : Td.ink)
                Spacer()
                if selected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 16)).foregroundColor(Td.accent)
                }
            }
            Text(sub).font(.system(size: 11)).foregroundColor(selected ? Td.muted : Td.faint)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(selected ? Td.accentFaintBg : Td.surface))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(selected ? Td.accent : Td.border, lineWidth: selected ? 1.5 : 1))
        .onTapGesture(perform: action)
    }

    private func checkRow(_ title: String, _ sub: String, checked: Binding<Bool>) -> some View {
        HStack(spacing: 12) {
            Image(systemName: checked.wrappedValue ? "checkmark.square.fill" : "square")
                .font(.system(size: 20))
                .foregroundColor(checked.wrappedValue ? Td.accent : Td.border)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                Text(sub).font(.system(size: 12)).foregroundColor(Td.muted)
            }
            Spacer()
        }
        .padding(.vertical, 13)
        .contentShape(Rectangle())
        .onTapGesture { checked.wrappedValue.toggle() }
    }

    private func generate() {
        guard let d = data, !busy else { return }
        busy = true
        let year = self.year, useXlsx = self.useXlsx
        let incSummary = self.incSummary, incDaily = self.incDaily
        DispatchQueue.global(qos: .userInitiated).async {
            var result: Result<URL, Error>
            do {
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let url: URL
                if useXlsx {
                    url = dir.appendingPathComponent("TernDays-\(year).xlsx")
                    let bytes = Exporter.exportXlsx(stats: d.stats, punches: d.punches,
                                                    includeSummary: incSummary, includeDaily: incDaily)
                    try bytes.write(to: url)
                } else {
                    url = dir.appendingPathComponent("TernDays-\(year).csv")
                    let text = Exporter.exportCsv(stats: d.stats, punches: d.punches,
                                                  includeSummary: incSummary, includeDaily: incDaily)
                    guard let data = text.data(using: .utf8) else {
                        throw NSError(domain: "TernDays", code: -1,
                                      userInfo: [NSLocalizedDescriptionKey: "内容编码失败"])
                    }
                    try data.write(to: url)
                }
                result = .success(url)
            } catch {
                result = .failure(error)
            }
            DispatchQueue.main.async {
                busy = false
                switch result {
                case .success(let url): shareURL = url
                case .failure(let e): failure = "生成文件失败:\(e.localizedDescription)"
                }
            }
        }
    }
}

extension URL: Identifiable {
    public var id: String { absoluteString }
}

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
