import SwiftUI
import UIKit
import WidgetKit

/// 小组件 target 拿不到应用的 Theme,这里独立定义。
/// 只有底面与一处强调色是自定义的,其余文字全部走系统语义色(.primary / .secondary),
/// 这样 iOS 18 着色模式、StandBy 与锁屏的 vibrant 渲染都由系统正确处理。
private enum WColor {
    static let surface = Color(uiColor: UIColor { trait in
        trait.userInterfaceStyle == .dark ? UIColor(rgb: 0x1C1C1E) : .white
    })
    static let accent = Color(uiColor: UIColor { trait in
        trait.userInterfaceStyle == .dark ? UIColor(rgb: 0x7CC0E8) : UIColor(rgb: 0x1F6289)
    })
}

private extension UIColor {
    convenience init(rgb: Int) {
        self.init(
            red: CGFloat((rgb >> 16) & 0xFF) / 255.0,
            green: CGFloat((rgb >> 8) & 0xFF) / 255.0,
            blue: CGFloat(rgb & 0xFF) / 255.0,
            alpha: 1
        )
    }
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

/// 只展示最关键的信息:今年 Top 3 城市及天数(三行等权重)。
/// 刷新是打卡驱动的:应用每次打卡 / 补记会主动 reload;
/// 时间线只在下一个打卡时间点之后兜底刷一次(每天至多两次),不做高频轮询。
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
        // 小组件进程可能被系统复用:每次生成时间线前重读磁盘,避免展示主应用早已更新过的旧数据
        DataStore.shared.reloadFromDisk()
        let today = LocalDate.today()
        let stats = DayCounting.computeYearStats(
            year: today.year,
            today: today,
            punches: DataStore.shared.punchesForYear(today.year),
            overrides: DataStore.shared.overridesForYear(today.year),
            nowHour: Calendar.current.component(.hour, from: Date())
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
    /// iOS 17+ 交给系统合成底面(自动拿到系统内容边距、StandBy / 锁屏自动去底);
    /// iOS 16 没有 containerBackground,手动补 16pt 边距与底色。
    @ViewBuilder
    func widgetBackgroundCompat() -> some View {
        if #available(iOS 17.0, *) {
            containerBackground(for: .widget) { WColor.surface }
        } else {
            padding(16).background(WColor.surface)
        }
    }
}

/// 年份眉题 + 今年 Top 3 城市,三行等权重(同字号、同字重、同颜色)。
/// 每行内部:城市名 15 + 天数 20 + 单位 11,基线对齐;右缘对齐成一列。
/// 整块唯一的品牌色是年份;不放应用名,不画圆角与装饰。
struct TernDaysWidgetView: View {
    let entry: TernEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(entry.yearLabel)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(WColor.accent)
                .widgetAccentable()
                .lineLimit(1)

            if entry.top.isEmpty {
                Text("还没有打卡记录")
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .padding(.top, 8)
            } else {
                // 行之间与末尾的 Spacer 平分余量:三行在格子里均匀铺开,不挤在顶部
                Spacer(minLength: 8)
                ForEach(entry.top) { c in
                    if c.id > 0 { Spacer(minLength: 6) }
                    row(c)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .accessibilityElement(children: .combine)
        .widgetBackgroundCompat()
    }

    private func row(_ c: TopCity) -> some View {
        HStack(alignment: .lastTextBaseline, spacing: 8) {
            Text(c.name)
                .font(.system(size: 15, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 0)
            (Text(c.days).font(.system(size: 20, weight: .semibold).monospacedDigit())
                + Text(" 天").font(.system(size: 11)).foregroundColor(.secondary))
                .lineLimit(1)
                .minimumScaleFactor(0.85)
        }
    }
}

struct TernDaysWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "TernDaysWidget", provider: TernProvider()) { entry in
            TernDaysWidgetView(entry: entry)
        }
        .configurationDisplayName("城市天数")
        .description("今年 Top 3 城市及天数")
        .supportedFamilies([.systemSmall])
    }
}

@main
struct TernDaysWidgetBundle: WidgetBundle {
    var body: some Widget {
        TernDaysWidget()
    }
}
