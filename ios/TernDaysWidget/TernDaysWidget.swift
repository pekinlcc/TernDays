import SwiftUI
import UIKit
import WidgetKit

// 与应用设计稿一致的配色（小组件 target 拿不到应用的 Theme，这里独立定义）
private enum WColor {
    static let accent = Color(red: 0x2E / 255.0, green: 0x7F / 255.0, blue: 0xA8 / 255.0)
    static let accentDeep = Color(red: 0x1F / 255.0, green: 0x62 / 255.0, blue: 0x89 / 255.0)
}

struct TopCity: Identifiable {
    let id: Int
    let name: String
    let days: String
}

struct TernEntry: TimelineEntry {
    let date: Date
    let yearLabel: String
    let bigDays: String
    let statsLabel: String
    let morning: String?
    let evening: String?
    let top: [TopCity]
}

struct TernProvider: TimelineProvider {
    func placeholder(in context: Context) -> TernEntry {
        TernEntry(
            date: Date(), yearLabel: "2026 年", bigDays: "240", statsLabel: "天已记录 · 6 城",
            morning: "北京", evening: nil,
            top: [
                TopCity(id: 0, name: "北京", days: "152"),
                TopCity(id: 1, name: "上海", days: "38.5"),
                TopCity(id: 2, name: "杭州", days: "21"),
            ]
        )
    }

    func getSnapshot(in context: Context, completion: @escaping (TernEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : load())
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<TernEntry>) -> Void) {
        // 下一个打卡时刻之后刷新（应用侧每次写入也会主动 reload）
        let next = PunchRules.nextPunchDate().addingTimeInterval(15 * 60)
        completion(Timeline(entries: [load()], policy: .after(next)))
    }

    private func load() -> TernEntry {
        let today = LocalDate.today()
        let punches = DataStore.shared.punchesForYear(today.year)
        let overrides = DataStore.shared.overridesForYear(today.year)
        let stats = DayCounting.computeYearStats(
            year: today.year, today: today, punches: punches, overrides: overrides
        )
        let todays = punches.filter { $0.localDate == today }
        return TernEntry(
            date: Date(),
            yearLabel: "\(String(today.year)) 年",
            bigDays: String(stats.recordedDays),
            statsLabel: "天已记录 · \(stats.cities.count) 城",
            morning: todays.first { $0.slot == .morning }?.cityName,
            evening: todays.first { $0.slot == .evening }?.cityName,
            top: stats.cities.prefix(3).enumerated().map { i, c in
                TopCity(id: i, name: c.cityName, days: DayCounting.formatDays(c.days))
            }
        )
    }
}

private extension View {
    @ViewBuilder
    func widgetBackgroundCompat() -> some View {
        if #available(iOS 17.0, *) {
            containerBackground(for: .widget) { Color(UIColor.systemBackground) }
        } else {
            background(Color(UIColor.systemBackground))
        }
    }
}

struct TernDaysWidgetView: View {
    @Environment(\.widgetFamily) private var family
    let entry: TernEntry

    var body: some View {
        Group {
            if family == .systemMedium {
                medium
            } else {
                small
            }
        }
        .widgetBackgroundCompat()
    }

    private var header: some View {
        HStack {
            Text("TernDays").font(.system(size: 12, weight: .bold))
            Spacer()
            Text(entry.yearLabel).font(.system(size: 11)).foregroundColor(.secondary)
        }
    }

    private var bigLine: some View {
        HStack(alignment: .lastTextBaseline, spacing: 4) {
            Text(entry.bigDays)
                .font(.system(size: 30, weight: .bold))
                .foregroundColor(WColor.accentDeep)
                .minimumScaleFactor(0.6)
                .lineLimit(1)
            Text(entry.statsLabel)
                .font(.system(size: 10))
                .foregroundColor(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }

    private func slotLine(_ label: String, _ city: String?) -> some View {
        HStack(spacing: 4) {
            Circle()
                .fill(city != nil ? WColor.accent : Color.secondary.opacity(0.3))
                .frame(width: 6, height: 6)
            Text("\(label) \(city ?? "待记录")")
                .font(.system(size: 11))
                .foregroundColor(city != nil ? .primary : .secondary)
                .lineLimit(1)
        }
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 6) {
            header
            Spacer(minLength: 0)
            bigLine
            Spacer(minLength: 0)
            slotLine("早", entry.morning)
            slotLine("晚", entry.evening)
        }
        .padding(14)
    }

    private var medium: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 6) {
                header
                Spacer(minLength: 0)
                bigLine
                Spacer(minLength: 0)
                slotLine("早", entry.morning)
                slotLine("晚", entry.evening)
            }
            Divider()
            VStack(alignment: .leading, spacing: 8) {
                if entry.top.isEmpty {
                    Spacer()
                    Text("还没有打卡记录")
                        .font(.system(size: 11)).foregroundColor(.secondary)
                    Spacer()
                } else {
                    ForEach(entry.top) { c in
                        HStack(alignment: .lastTextBaseline) {
                            Text(c.name)
                                .font(.system(size: 14, weight: .semibold))
                                .lineLimit(1)
                            Spacer()
                            Text(c.days)
                                .font(.system(size: 16, weight: .bold))
                                .foregroundColor(WColor.accentDeep)
                            Text("天").font(.system(size: 10)).foregroundColor(.secondary)
                        }
                    }
                    Spacer(minLength: 0)
                }
            }
            .frame(maxWidth: .infinity)
        }
        .padding(14)
    }
}

struct TernDaysWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "TernDaysWidget", provider: TernProvider()) { entry in
            TernDaysWidgetView(entry: entry)
        }
        .configurationDisplayName("城市天数")
        .description("今年已记录的天数、今日打卡状态与去过最久的城市")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct TernDaysWidgetBundle: WidgetBundle {
    var body: some Widget {
        TernDaysWidget()
    }
}
