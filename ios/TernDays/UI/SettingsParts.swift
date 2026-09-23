import SwiftUI
import WidgetKit

/// 设置页分组标题
struct SettingsHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.system(size: 13, weight: .medium)).foregroundColor(Td.muted)
            .padding(.leading, 2).padding(.top, 4)
    }
}

// MARK: - 小组件外观

/// 小组件的底色(与 TernDaysWidget.swift 里的 WColor 同值;扩展里的定义应用拿不到,这里对照一份)
private enum WidgetPreviewColor {
    static let surface = Color(light: 0xFFFFFF, dark: 0x1C1C1E)
    static let accent = Color(light: 0x1F6289, dark: 0x7CC0E8)
    static let gradTop = Color(light: 0x1F6289, dark: 0x1F5C7F)
    static let gradBottom = Color(light: 0x154766, dark: 0x0F3247)
}

/// 三种外观各一张 72pt 的缩略预览(年份 + 三行假数据),选中的描边并带「已选中」无障碍特征
struct WidgetStylePicker: View {
    @Binding var selection: WidgetStyle

    var body: some View {
        HStack(spacing: 10) {
            ForEach(WidgetStyle.allCases, id: \.rawValue) { s in
                let selected = s == selection
                Button {
                    selection = s
                    WidgetStyle.current = s
                    WidgetCenter.shared.reloadAllTimelines()
                } label: {
                    VStack(spacing: 6) {
                        WidgetMiniPreview(style: s)
                            .frame(width: 72, height: 72)
                            .overlay(
                                RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .stroke(selected ? Td.accent : Td.border, lineWidth: selected ? 2 : 1)
                            )
                        Text(s.label)
                            .font(.system(size: 12, weight: selected ? .semibold : .regular))
                            .foregroundColor(selected ? Td.accentDeep : Td.muted)
                    }
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("小组件外观:\(s.label)")
                .accessibilityAddTraits(selected ? AccessibilityTraits.isSelected : AccessibilityTraits())
            }
        }
    }
}

struct WidgetMiniPreview: View {
    let style: WidgetStyle

    private var primary: Color { style == .gradient ? .white : .primary }
    private var secondary: Color { style == .gradient ? Color.white.opacity(0.8) : .secondary }
    private var year: Color { style == .gradient ? Color.white.opacity(0.9) : WidgetPreviewColor.accent }

    var body: some View {
        ZStack(alignment: .topLeading) {
            background
            VStack(alignment: .leading, spacing: 3) {
                Text("\(String(LocalDate.today().year)) 年")
                    .font(.system(size: 7, weight: .semibold)).foregroundColor(year)
                Spacer(minLength: 2)
                row("北京", "152")
                row("上海", "38.5")
                row("杭州", "21")
            }
            .padding(8)
        }
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private var background: some View {
        switch style {
        case .plain:
            WidgetPreviewColor.surface
        case .material:
            // 材质要有东西透出来才看得出:垫一层淡色「壁纸」
            ZStack {
                LinearGradient(colors: [Td.accentSoft, Td.warmSoft], startPoint: .topLeading, endPoint: .bottomTrailing)
                Rectangle().fill(.regularMaterial)
            }
        case .gradient:
            LinearGradient(colors: [WidgetPreviewColor.gradTop, WidgetPreviewColor.gradBottom],
                           startPoint: .top, endPoint: .bottom)
        }
    }

    private func row(_ name: String, _ days: String) -> some View {
        HStack(spacing: 2) {
            Text(name).font(.system(size: 7, weight: .semibold)).foregroundColor(primary)
            Spacer(minLength: 2)
            Text(days).font(.system(size: 9, weight: .semibold)).foregroundColor(primary)
            Text("天").font(.system(size: 5)).foregroundColor(secondary)
        }
    }
}

// MARK: - 天数提醒

/// 「天数提醒」:列出已配置的上限(地区 · 天数 · 窗口 · 已用),可删除、可添加
struct ThresholdSettingsSection: View {
    @State private var items: [ThresholdAlerts.Item] = []
    @State private var list: [Thresholds.Threshold] = []
    @State private var adding = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsHeader(title: "天数提醒")
            TdCard {
                VStack(spacing: 0) {
                    Text("某个国家/地区的天数接近或达到上限时发通知提醒(如中国大陆 183 天)。快到上限和达到上限各提醒一次。")
                        .font(.system(size: 12)).foregroundColor(Td.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.vertical, 12)
                    ForEach(list, id: \.self) { t in
                        Divider().overlay(Td.divider)
                        thresholdRow(t)
                    }
                    Divider().overlay(Td.divider)
                    Button {
                        adding = true
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "plus.circle")
                            Text("添加提醒")
                            Spacer()
                        }
                        .font(.system(size: 14, weight: .semibold)).foregroundColor(Td.accentDeep)
                        .tapTarget()
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 16)
            }
        }
        .onAppear { refresh() }
        .onReceive(NotificationCenter.default.publisher(for: .terndaysDataChanged)) { _ in refresh() }
        .sheet(isPresented: $adding) {
            ThresholdAddSheet { t in
                ThresholdStore.upsert(t)
                changed()
            }
        }
    }

    private func thresholdRow(_ t: Thresholds.Threshold) -> some View {
        let item = items.first { $0.status.threshold == t }
        let usage: String = item.map { "已 \(DayCounting.formatDays($0.status.used)) 天" } ?? ""
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(Regions.nameOf(t.regionCode)) · \(t.days) 天")
                    .font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                Text(usage.isEmpty ? t.window.label : "\(t.window.label) · \(usage)")
                    .font(.system(size: 12))
                    .foregroundColor(item.map { RegionCard.limitColor($0.status.level) } ?? Td.muted)
            }
            Spacer()
            Button {
                ThresholdStore.remove(t)
                changed()
            } label: {
                Image(systemName: "trash")
                    .font(.system(size: 14)).foregroundColor(Td.warmDeep)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("删除 \(Regions.nameOf(t.regionCode)) \(t.days) 天提醒")
        }
        .padding(.vertical, 6)
    }

    private func refresh() {
        list = ThresholdStore.load()
        DispatchQueue.global(qos: .userInitiated).async {
            let evaluated = ThresholdAlerts.evaluate()
            DispatchQueue.main.async { items = evaluated }
        }
    }

    /// 改了阈值:本页、首页(地区卡)一起刷新,并立即检查一次是否需要提醒
    private func changed() {
        refresh()
        NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
        ThresholdAlerts.checkInBackground()
    }
}

struct ThresholdAddSheet: View {
    let onAdd: (Thresholds.Threshold) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var codes: [String] = []
    @State private var region = "CN"
    @State private var days = 183
    @State private var window: Thresholds.Window = .year

    /// 数据里出现过的地区在前,再补上常用的几个
    static func candidateCodes() -> [String] {
        var out: [String] = []
        let present = DataStore.shared.allPunches().map { Regions.codeOf($0.cityKey) }
            + DataStore.shared.allOverrides().map { Regions.codeOf($0.cityKey) }
        let defaults = ["CN", "HK", "MO", "TW", "JP", "US", "SG", "GB"]
        for c in present + defaults where c != "unknown" && !c.isEmpty && !out.contains(c) {
            out.append(c)
        }
        return out
    }

    private var clampedDays: Int { min(max(days, 1), 366) }

    var body: some View {
        NavigationStack {
            Form {
                Section("国家/地区") {
                    Picker("国家/地区", selection: $region) {
                        ForEach(codes, id: \.self) { c in
                            Text(Regions.nameOf(c)).tag(c)
                        }
                    }
                }
                Section {
                    HStack {
                        TextField("天数", value: $days, format: .number)
                            .keyboardType(.numberPad)
                            .frame(maxWidth: 90)
                        Text("天")
                        Spacer()
                        Stepper("天数", value: $days, in: 1...366).labelsHidden()
                    }
                } header: {
                    Text("天数上限")
                } footer: {
                    Text("1–366 天。剩余不到 7 天(或上限的 10%)时算「接近」。")
                }
                Section("统计窗口") {
                    Picker("统计窗口", selection: $window) {
                        ForEach(Thresholds.Window.allCases, id: \.self) { w in
                            Text(w.label).tag(w)
                        }
                    }
                    .pickerStyle(.segmented)
                }
            }
            .navigationTitle("添加天数提醒")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("添加") {
                        onAdd(Thresholds.Threshold(regionCode: region, days: clampedDays, window: window))
                        dismiss()
                    }
                    .disabled(codes.isEmpty)
                }
            }
            .onAppear {
                if codes.isEmpty {
                    codes = Self.candidateCodes()
                    if !codes.contains(region), let first = codes.first { region = first }
                }
            }
        }
    }
}
