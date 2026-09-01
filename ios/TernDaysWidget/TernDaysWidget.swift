import SwiftUI
import UIKit
import WidgetKit

// 与应用设计稿一致的强调色（小组件 target 拿不到应用的 Theme，这里独立定义）
private enum WColor {
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
    let top: [TopCity]
}

/// 只展示最关键的信息：今年 Top 3 城市及天数。
/// 刷新是打卡驱动的：应用每次打卡 / 补记会主动 reload；
/// 时间线只在下一个打卡时间点之后兜底刷一次（每天至多两次），不做高频轮询。
struct TernProvider: TimelineProvider {
    func placeholder(in context: Context) -> TernEntry {
        TernEntry(
            date: Date(), yearLabel: "2026 年",
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
        let next = PunchRules.nextPunchDate().addingTimeInterval(30 * 60)
        completion(Timeline(entries: [load()], policy: .after(next)))
    }

    private func load() -> TernEntry {
        let today = LocalDate.today()
        let stats = DayCounting.computeYearStats(
            year: today.year,
            today: today,
            punches: DataStore.shared.punchesForYear(today.year),
            overrides: DataStore.shared.overridesForYear(today.year)
        )
        return TernEntry(
            date: Date(),
            yearLabel: "\(String(today.year)) 年",
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
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("TernDays").font(.system(size: 11, weight: .bold)).foregroundColor(.secondary)
                Spacer()
                Text(entry.yearLabel).font(.system(size: 11)).foregroundColor(.secondary)
            }
            if entry.top.isEmpty {
                Spacer()
                Text("还没有打卡记录")
                    .font(.system(size: 12)).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                Spacer()
            } else {
                Spacer(minLength: 6)
                ForEach(entry.top) { c in
                    HStack(alignment: .lastTextBaseline) {
                        Text(c.name)
                            .font(.system(size: family == .systemMedium ? 17 : 15, weight: .bold))
                            .lineLimit(1)
                        Spacer()
                        Text(c.days)
                            .font(.system(size: family == .systemMedium ? 18 : 16, weight: .bold))
                            .foregroundColor(WColor.accentDeep)
                        Text("天").font(.system(size: 10)).foregroundColor(.secondary)
                    }
                    if c.id < entry.top.count - 1 {
                        Spacer(minLength: 4)
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .padding(14)
        .widgetBackgroundCompat()
    }
}

struct TernDaysWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "TernDaysWidget", provider: TernProvider()) { entry in
            TernDaysWidgetView(entry: entry)
        }
        .configurationDisplayName("城市天数")
        .description("今年去过最久的 Top 3 城市及天数")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

@main
struct TernDaysWidgetBundle: WidgetBundle {
    var body: some Widget {
        TernDaysWidget()
    }
}
