import SwiftUI
import UIKit

/// 首页除年度统计外的附加内容:按国家/地区、行程、打卡诊断、未来记录。后台线程一次算好。
struct HomeExtras {

    struct RegionRow: Identifiable {
        let code: String
        let name: String
        let days: Double
        let cities: Int
        /// 这个地区配置的天数上限(只在看今年时附上:阈值按「现在」算)
        let limits: [ThresholdAlerts.Item]

        var id: String { code }
    }

    let regions: [RegionRow]
    let hasThresholds: Bool
    /// 这一年的行程段(时间倒序)
    let stays: [Stays.Stay]
    /// 当前这一段(跨年也算:单独按最近 400 天折叠)
    let currentStay: Stays.Stay?
    /// 最近 3 天(不含今天、且不早于开始记录日)里没有任何记录的天数
    let recentGap: Int
    /// 打卡时刻晚于现在的记录(系统时间曾被拨到未来)
    let future: [Punch]

    static func load(year: Int, stats: YearStats, now: Date = Date()) -> HomeExtras {
        let today = LocalDate(from: now, in: .current)
        let hour = Calendar.current.component(.hour, from: now)
        let isCurrentYear = year == today.year

        // 按国家/地区 + 阈值
        let items = ThresholdAlerts.evaluate(now: now)
        var rows: [RegionRow] = Regions.summarize(stats).map { r in
            RegionRow(code: r.code, name: r.name, days: r.days, cities: r.cities,
                      limits: isCurrentYear ? items.filter { $0.status.threshold.regionCode == r.code } : [])
        }
        if isCurrentYear {
            // 配了上限、本年还没去过的地区也列出来(本年 0 天;滚动窗口的用量可能来自去年)
            var seen = Set(rows.map(\.code))
            for item in items where !seen.contains(item.status.threshold.regionCode) {
                let code = item.status.threshold.regionCode
                seen.insert(code)
                rows.append(RegionRow(code: code, name: item.regionName, days: 0, cities: 0,
                                      limits: items.filter { $0.status.threshold.regionCode == code }))
            }
        }

        // 当前行程 + 最近几天的缺口:最近 400 天一起算
        let from = today.minusDays(400)
        let recent = DayCounting.computeRangeStats(
            from: from, to: today, today: today,
            punches: DataStore.shared.punchesBetween(from, today),
            overrides: DataStore.shared.overridesBetween(from, today),
            nowHour: hour,
            earliestRecordDate: DataStore.shared.earliestRecordDate()
        )
        let gapFrom = today.minusDays(3)
        let gap = recent.unrecordedDates.filter { $0 >= gapFrom && $0 < today }.count

        return HomeExtras(
            regions: rows,
            hasThresholds: !items.isEmpty,
            stays: Array(Stays.fold(stats.days).reversed()),
            currentStay: Stays.current(Stays.fold(recent.days), today: today),
            recentGap: gap,
            future: Anchors.future(DataStore.shared.allPunches(), nowMs: Int64(now.timeIntervalSince1970 * 1000))
        )
    }
}

/// 首页的提示卡:暖色底、左图标、可带一到两个动作
struct NoticeCard<Actions: View>: View {
    let icon: String
    let title: String
    let detail: String?
    @ViewBuilder let actions: Actions

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 15)).foregroundColor(Td.warmDeep)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 13, weight: .semibold)).foregroundColor(Td.warmDeep)
                        .fixedSize(horizontal: false, vertical: true)
                    if let detail = detail {
                        Text(detail)
                            .font(.system(size: 11)).foregroundColor(Td.warmDeep)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 10) {
                actions
            }
        }
        .padding(.horizontal, 14).padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Td.warmSoft))
    }
}

/// 提示卡里的动作按钮外观(真正的按钮 / 导航链接由调用方包)
struct NoticeActionLabel: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 12, weight: .semibold))
            .foregroundColor(Td.warmDeep)
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(Capsule().stroke(Td.warmDeep.opacity(0.5), lineWidth: 1))
            .contentShape(Rectangle())
    }
}

/// 「按国家/地区」卡片:每行 名称 + 天数 + 城市数;配了上限的地区再写一行「183 天上限 · 还剩 33 天」
struct RegionCard: View {
    let rows: [HomeExtras.RegionRow]

    var body: some View {
        TdCard {
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.element.id) { i, r in
                    if i > 0 { Divider().overlay(Td.divider) }
                    row(r)
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func row(_ r: HomeExtras.RegionRow) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .lastTextBaseline, spacing: 8) {
                Text(r.name).font(.system(size: 15, weight: .semibold)).foregroundColor(Td.ink)
                // 只因配了上限才列出的地区(本年 0 天)不写「还没去过」:「最近 180 天」的上限
                // 可能算到去年的天数,下面那行「还剩 N 天」会与之矛盾(与 Android 一样只在有城市时写)
                if r.cities > 0 {
                    Text("\(r.cities) 个城市")
                        .font(.system(size: 11)).foregroundColor(Td.faint)
                }
                Spacer()
                Text(DayCounting.formatDays(r.days))
                    .font(.system(size: 20, weight: .bold)).foregroundColor(Td.accentDeep)
                Text("天").font(.system(size: 11)).foregroundColor(Td.faint)
            }
            ForEach(Array(r.limits.enumerated()), id: \.offset) { _, item in
                Text(Self.limitText(item))
                    .font(.system(size: 11, weight: item.status.level == .ok ? .regular : .semibold))
                    .foregroundColor(Self.limitColor(item.status.level))
            }
        }
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }

    static func limitText(_ item: ThresholdAlerts.Item) -> String {
        let t = item.status.threshold
        let window = t.window == .year ? "" : "最近 180 天 · "
        if item.status.level == .reached {
            return window + "已达到 \(t.days) 天上限"
        }
        return window + "\(t.days) 天上限 · 还剩 \(DayCounting.formatDays(item.status.remaining)) 天"
    }

    static func limitColor(_ level: Thresholds.Level) -> Color {
        switch level {
        case .ok: return Td.muted
        case .near: return Td.warmDeep
        case .reached: return Color(uiColor: .systemRed)
        }
    }
}

/// 「行程」:当前这一段 + 本年最近 5 段,可展开全部
struct StaysCard: View {
    let current: Stays.Stay?
    /// 时间倒序
    let stays: [Stays.Stay]

    @State private var showAll = false

    private var visible: [Stays.Stay] { showAll ? stays : Array(stays.prefix(5)) }

    private var toggleText: String { showAll ? "收起" : "展开全部(\(stays.count) 段)" }

    var body: some View {
        TdCard {
            VStack(alignment: .leading, spacing: 0) {
                if let current = current {
                    HStack(spacing: 6) {
                        Image(systemName: "location.fill")
                            .font(.system(size: 11)).foregroundColor(Td.accent)
                        Text("当前:\(current.cityName) · 连续第 \(current.spanDays) 天")
                            .font(.system(size: 13, weight: .semibold)).foregroundColor(Td.ink)
                    }
                    .padding(.vertical, 12)
                    if !visible.isEmpty { Divider().overlay(Td.divider) }
                }
                ForEach(Array(visible.enumerated()), id: \.offset) { i, s in
                    if i > 0 { Divider().overlay(Td.divider) }
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.cityName).font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                            Text(Self.rangeText(s)).font(.system(size: 11)).foregroundColor(Td.muted)
                        }
                        Spacer()
                        Text("\(DayCounting.formatDays(s.days)) 天")
                            .font(.system(size: 13, weight: .semibold)).foregroundColor(Td.accentDeep)
                    }
                    .padding(.vertical, 10)
                    .accessibilityElement(children: .combine)
                }
                if stays.count > 5 {
                    Divider().overlay(Td.divider)
                    Button {
                        showAll.toggle()
                    } label: {
                        Text(toggleText)
                            .font(.system(size: 12, weight: .semibold)).foregroundColor(Td.accentDeep)
                            .frame(maxWidth: .infinity)
                            .tapTarget()
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
        }
    }

    /// 「3月1日 – 3月5日」;同一天只写一个日期
    static func rangeText(_ s: Stays.Stay) -> String {
        if s.from == s.to { return Fmt.monthDay(s.from) }
        return "\(Fmt.monthDay(s.from)) – \(Fmt.monthDay(s.to))"
    }
}
