import SwiftUI
import UIKit

/// 导出用的数据快照:统计(年度或任意区间)+ 区间内的打卡
struct ExportData {
    let stats: YearStats
    let punches: [Punch]
}

struct ExportView: View {
    let initialYear: Int

    /// 导出范围:按年(年份卡片)/ 最近 180 天 / 自定义起止日期,统计口径完全相同(computeRangeStats)
    private enum RangeMode: Hashable {
        case year
        case rolling
        case custom
    }

    @State private var mode: RangeMode = .year
    @State private var year: Int
    @State private var customFrom: Date
    @State private var customTo: Date
    @State private var years: [Int] = []
    @State private var useXlsx = true
    @State private var incSummary = true
    @State private var incDaily = true
    @State private var incStays = false
    @State private var data: ExportData?
    @State private var shareURL: URL?
    @State private var busy = false
    @State private var failure: String?

    init(initialYear: Int) {
        self.initialYear = initialYear
        _year = State(initialValue: initialYear)
        let today = LocalDate.today()
        _customFrom = State(initialValue: LocalDate(year: today.year, month: 1, day: 1).noonDate())
        _customTo = State(initialValue: today.noonDate())
    }

    private var nothingSelected: Bool { !incSummary && !incDaily && !incStays }

    private var customRange: (from: LocalDate, to: LocalDate) {
        (LocalDate(from: customFrom, in: .current), LocalDate(from: customTo, in: .current))
    }

    private var rangeInvalid: Bool { mode == .custom && customRange.to < customRange.from }

    /// 数据重读的键:范围任一项变了就重算
    private var loadKey: String {
        switch mode {
        case .year: return "year-\(year)"
        case .rolling: return "rolling"
        case .custom: return "custom-\(customRange.from)-\(customRange.to)"
        }
    }

    /// DatePicker 上限:今天(取今晚 23 点,今天中午也能选)
    private var upperBound: Date { LocalDate.today().noonDate().addingTimeInterval(11 * 3600) }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    sectionLabel("导出范围")
                    Picker("导出范围", selection: $mode) {
                        Text("按年").tag(RangeMode.year)
                        Text("最近 180 天").tag(RangeMode.rolling)
                        Text("自定义").tag(RangeMode.custom)
                    }
                    .pickerStyle(.segmented)
                    rangeDetail
                    sectionLabel("文件格式")
                    HStack(spacing: 10) {
                        formatCard("Excel", ".xlsx · 分表：汇总 / 明细 / 行程", selected: useXlsx) { useXlsx = true }
                        formatCard("CSV", ".csv · 通用纯文本格式", selected: !useXlsx) { useXlsx = false }
                    }
                    sectionLabel("导出内容")
                    TdCard {
                        VStack(spacing: 0) {
                            checkRow("城市汇总", "每个城市的累计天数", checked: $incSummary)
                            Divider().overlay(Td.divider)
                            checkRow("每日明细", "每天早 / 晚打卡的时间、城市、时区与计天结果", checked: $incDaily)
                            Divider().overlay(Td.divider)
                            checkRow("行程段", "同城连续的日子合成一段:城市、起止日期、天数", checked: $incStays)
                        }
                        .padding(.horizontal, 16)
                    }
                    if nothingSelected {
                        Text("请至少选择一项导出内容")
                            .font(.system(size: 12)).foregroundColor(Td.warmDeep)
                            .padding(.leading, 2)
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
                    .foregroundColor(exportDisabled ? Td.muted : Td.onAccent)
                    .frame(maxWidth: .infinity)
                    .frame(height: 52)
                    .background(RoundedRectangle(cornerRadius: 14).fill(exportDisabled ? Td.neutralSoft : Td.accent))
                }
                .disabled(busy || exportDisabled)
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
        .task(id: loadKey) { load() }
        // 后台打卡、历史重解析都会改数据:回到这一页要重新读,别导出进页面时的旧快照
        .onReceive(NotificationCenter.default.publisher(for: .terndaysDataChanged)) { _ in load() }
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

    private var exportDisabled: Bool { nothingSelected || rangeInvalid || data == nil }

    private var rollingText: String {
        let r = Thresholds.range(.rolling180, today: LocalDate.today())
        return "\(r.from) 至 \(r.to)(含今天)"
    }

    /// 按年:年份卡片;最近 180 天:写出起止日期;自定义:两个日期选择
    @ViewBuilder
    private var rangeDetail: some View {
        switch mode {
        case .year:
            // 年份变多后早期年份此前点不到(Android v0.6.1 已修,这里补上)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(years.isEmpty ? [year] : years, id: \.self) { y in
                        yearChip(y)
                    }
                }
            }
        case .rolling:
            Text(rollingText)
                .font(.system(size: 12)).foregroundColor(Td.muted)
                .padding(.leading, 2)
        case .custom:
            TdCard {
                VStack(spacing: 0) {
                    DatePicker("开始", selection: $customFrom, in: ...upperBound, displayedComponents: .date)
                        .padding(.vertical, 6)
                    Divider().overlay(Td.divider)
                    DatePicker("结束", selection: $customTo, in: ...upperBound, displayedComponents: .date)
                        .padding(.vertical, 6)
                }
                .padding(.horizontal, 16)
            }
            if rangeInvalid {
                Text("结束日期不能早于开始日期")
                    .font(.system(size: 12)).foregroundColor(Td.warmDeep)
                    .padding(.leading, 2)
            }
        }
    }

    private func yearChip(_ y: Int) -> some View {
        let selected = y == year
        return Button {
            year = y
        } label: {
            Text("\(String(y)) 年")
                .font(.system(size: 14, weight: selected ? .semibold : .regular))
                .foregroundColor(selected ? Td.accentDeep : Td.muted)
                .padding(.horizontal, 14)
                .frame(minHeight: 44)
                .background(
                    RoundedRectangle(cornerRadius: 12)
                        .fill(selected ? Td.accentFaintBg : Td.surface)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(selected ? Td.accent : Td.border, lineWidth: selected ? 1.5 : 1)
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? AccessibilityTraits.isSelected : AccessibilityTraits())
    }

    /// 按当前范围读数据(与首页同一份计天口径:进行中的今天按半天计)
    private func load() {
        years = DataStore.shared.yearsWithData(currentYear: LocalDate.today().year)
        switch mode {
        case .year:
            let d = YearData.load(year: year)
            data = ExportData(stats: d.stats, punches: d.punches)
        case .rolling:
            let r = Thresholds.range(.rolling180, today: LocalDate.today())
            data = Self.loadRange(from: r.from, to: r.to)
        case .custom:
            let r = customRange
            data = r.to < r.from ? nil : Self.loadRange(from: r.from, to: r.to)
        }
    }

    private static func loadRange(from: LocalDate, to: LocalDate) -> ExportData {
        let today = LocalDate.today()
        let punches = DataStore.shared.punchesBetween(from, to)
        let stats = DayCounting.computeRangeStats(
            from: from, to: to, today: today, punches: punches,
            overrides: DataStore.shared.overridesBetween(from, to),
            nowHour: Calendar.current.component(.hour, from: Date()),
            earliestRecordDate: DataStore.shared.earliestRecordDate()
        )
        return ExportData(stats: stats, punches: punches)
    }

    /// 文件名:按年「TernDays-2026」;区间「TernDays-2026-03-29至2026-09-23」
    private func fileBaseName(_ stats: YearStats) -> String {
        if mode == .year { return "TernDays-\(String(stats.year))" }
        return "TernDays-\(stats.firstDate.description)至\(stats.lastDate.description)"
    }

    /// 明细预览:最近 3 天,让用户导出前先看一眼内容(与 Android 同一口径:
    /// 只有首点或纯手动补记的日子也要能预览,早点列缺失时回落到「首 城市」)
    @ViewBuilder
    private var previewCard: some View {
        let rows = data.map { Exporter.dailyRows(stats: $0.stats, punches: $0.punches) }?
            .filter { $0.morning != nil || $0.evening != nil || $0.extra != nil || !$0.attribution.shares.isEmpty }
            .suffix(3) ?? []
        if !rows.isEmpty {
            TdCard {
                VStack(alignment: .leading, spacing: 6) {
                    Text("预览 · 每日明细").font(.system(size: 12)).foregroundColor(Td.faint)
                    previewRow(["日期", "早7点", "晚5点", "计入"], header: true)
                    ForEach(Array(rows), id: \.date) { r in
                        previewRow([
                            String(format: "%02d-%02d", r.date.month, r.date.day),
                            r.morning?.cityName ?? r.extra.map { "首 \($0.cityName)" } ?? "–",
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
        guard let d = data, !busy, !exportDisabled else { return }
        busy = true
        let useXlsx = self.useXlsx
        let incSummary = self.incSummary, incDaily = self.incDaily, incStays = self.incStays
        let base = fileBaseName(d.stats)
        DispatchQueue.global(qos: .userInitiated).async {
            var result: Result<URL, Error>
            do {
                let dir = FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true)
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let url: URL
                if useXlsx {
                    url = dir.appendingPathComponent("\(base).xlsx")
                    let bytes = Exporter.exportXlsx(stats: d.stats, punches: d.punches,
                                                    includeSummary: incSummary, includeDaily: incDaily,
                                                    exportedAt: Date(), includeStays: incStays)
                    try bytes.write(to: url)
                } else {
                    url = dir.appendingPathComponent("\(base).csv")
                    let text = Exporter.exportCsv(stats: d.stats, punches: d.punches,
                                                  includeSummary: incSummary, includeDaily: incDaily,
                                                  exportedAt: Date(), includeStays: incStays)
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
