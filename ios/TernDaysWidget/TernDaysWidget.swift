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
    /// 品牌渐变的两个色标(深浅各一组,与 Android colors.xml 一致)。
    /// 浅色顶部由 #2E7FA8 压深到 #1F6289:白字此前只有约 4.45:1
    static let gradTop = Color(uiColor: UIColor { trait in
        trait.userInterfaceStyle == .dark ? UIColor(rgb: 0x1F5C7F) : UIColor(rgb: 0x1F6289)
    })
    static let gradBottom = Color(uiColor: UIColor { trait in
        trait.userInterfaceStyle == .dark ? UIColor(rgb: 0x0F3247) : UIColor(rgb: 0x154766)
    })
}

/// 一套外观对应的文字配色。渐变底上不能再用 .primary/.secondary（那是给浅/深底面的语义色）。
private struct WidgetPalette {
    let primary: Color      // 城市名与天数
    let secondary: Color    // 单位「天」、空态
    let year: Color         // 年份眉题
    let accentable: Bool    // 年份是否参与 iOS 18 着色模式

    static func of(_ style: WidgetStyle) -> WidgetPalette {
        switch style {
        case .plain, .material:
            return WidgetPalette(primary: .primary, secondary: .secondary, year: WColor.accent, accentable: true)
        case .gradient:
            return WidgetPalette(
                primary: .white,
                // 年份 90% ≥5.7:1、次级 80% ≥4.9:1(两个色标上都成立)
                secondary: .white.opacity(0.8),
                year: .white.opacity(0.9),
                accentable: false
            )
        }
    }
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
    /// 数据暂时读不到(重启后首次解锁前):显示「解锁后显示」,而不是误导性的「还没有打卡记录」
    var unavailable = false
    /// 今年还空着、但往年有记录(典型:元旦凌晨):别让人以为数据丢了
    var newYearEmpty = false
    var year = LocalDate.today().year
}

/// 只展示最关键的信息:今年 Top 3 城市及天数(三行等权重)。
/// 刷新是打卡驱动的:应用每次打卡 / 补记 / 切换外观 / 时区变化会主动 reload;
/// 时间线另排一条零点条目(半天补满、元旦换年),并在下一个打卡时间点之后兜底刷一次,不做高频轮询。
struct TernProvider: TimelineProvider {
    func placeholder(in context: Context) -> TernEntry {
        TernEntry(
            date: Date(), yearLabel: "\(String(LocalDate.today().year)) 年",
            top: [
                TopCity(id: 0, name: "北京", days: "152"),
                TopCity(id: 1, name: "上海", days: "38.5"),
                TopCity(id: 2, name: "杭州", days: "21"),
            ]
        )
    }

    func getSnapshot(in context: Context, completion: @escaping (TernEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : load(at: Date()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<TernEntry>) -> Void) {
        let now = Date()
        let first = load(at: now)
        if first.unavailable {
            // 数据被锁在数据保护里:15 分钟后再试,解锁后主应用也会主动刷新
            completion(Timeline(entries: [first], policy: .after(now.addingTimeInterval(15 * 60))))
            return
        }
        let nextPunch = PunchRules.nextPunchDate().addingTimeInterval(30 * 60)
        var entries = [first]
        // v0.9 起天数会在零点自己变化(昨天的半天补满 1 天、元旦换年):
        // 只按打卡时点刷新的话,凌晨到早上 7 点半会一直显示旧数字
        if let midnight = Self.nextMidnight(after: now), midnight < nextPunch {
            entries.append(load(at: midnight))
        }
        completion(Timeline(entries: entries, policy: .after(nextPunch)))
    }

    private static func nextMidnight(after date: Date) -> Date? {
        var dc = DateComponents()
        dc.hour = 0
        dc.minute = 0
        dc.second = 5
        return Calendar.current.nextDate(after: date, matching: dc, matchingPolicy: .nextTime)
    }

    /// 按给定时刻算一份内容（零点那条时间线条目要按第二天算）。
    private func load(at when: Date) -> TernEntry {
        // 小组件进程可能被系统复用:每次生成时间线前重读磁盘,避免展示主应用早已更新过的旧数据
        DataStore.shared.reloadFromDisk()
        let day = LocalDate(from: when, in: .current)
        if DataStore.shared.isSealed {
            return TernEntry(date: when, yearLabel: "\(String(day.year)) 年", top: [], unavailable: true)
        }
        let stats = DayCounting.computeYearStats(
            year: day.year,
            today: day,
            punches: DataStore.shared.punchesForYear(day.year),
            overrides: DataStore.shared.overridesForYear(day.year),
            nowHour: Calendar.current.component(.hour, from: when),
            earliestRecordDate: DataStore.shared.earliestRecordDate()
        )
        // 展示口径(Top 3、天数格式、元旦空态)与 Android 共用 WidgetSummary,不再各写一份
        let model = WidgetSummary.build(stats: stats, topN: 3)
        return TernEntry(
            date: when,
            yearLabel: model.yearLabel,
            top: model.topCities.enumerated().map { i, c in
                TopCity(id: i, name: c.name, days: c.days)
            },
            newYearEmpty: model.newYearEmpty,
            year: day.year
        )
    }
}

private extension View {
    /// iOS 17+ 交给系统合成底面(自动拿到系统内容边距、StandBy / 锁屏自动去底);
    /// iOS 16 没有 containerBackground,手动补 16pt 边距与底色。
    ///
    /// 三种外观:素面 = 实心语义底;系统材质 = .regularMaterial(半透明底、跟随深浅模式,
    /// iOS 16 回落素面);品牌渐变 = 竖向两色标。
    @ViewBuilder
    func widgetBackgroundCompat(_ style: WidgetStyle) -> some View {
        if #available(iOS 17.0, *) {
            switch style {
            case .plain:
                containerBackground(for: .widget) { WColor.surface }
            case .material:
                containerBackground(.regularMaterial, for: .widget)
            case .gradient:
                containerBackground(for: .widget) {
                    LinearGradient(
                        colors: [WColor.gradTop, WColor.gradBottom],
                        startPoint: .top, endPoint: .bottom
                    )
                }
            }
        } else {
            switch style {
            case .plain, .material: // iOS 16 没有系统材质,回落素面
                padding(16).background(WColor.surface)
            case .gradient:
                padding(16).background(
                    LinearGradient(
                        colors: [WColor.gradTop, WColor.gradBottom],
                        startPoint: .top, endPoint: .bottom
                    )
                )
            }
        }
    }
}

/// 年份眉题 + 今年 Top 3 城市,三行等权重(同字号、同字重、同颜色)。
/// 每行内部:城市名 15 + 天数 20 + 单位 11,基线对齐;右缘对齐成一列。
/// 整块唯一的品牌色是年份;不放应用名,不画圆角与装饰。
struct TernDaysWidgetView: View {
    let entry: TernEntry
    /// 用户在设置里选的外观（主应用与扩展共享同一份 App Group 偏好）
    var style: WidgetStyle = WidgetStyle.current
    private var palette: WidgetPalette { WidgetPalette.of(style) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(entry.yearLabel)
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(palette.year)
                .widgetAccentable(palette.accentable)
                .lineLimit(1)

            if entry.top.isEmpty {
                Text(entry.unavailable ? "解锁手机后显示"
                     : entry.newYearEmpty ? "\(String(entry.year)) 年的第一条记录会在下次打卡后出现"
                     : "还没有打卡记录")
                    .font(.system(size: 13))
                    .foregroundStyle(palette.secondary)
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
        .foregroundStyle(palette.primary)
        .accessibilityElement(children: .combine)
        .widgetBackgroundCompat(style)
    }

    private func row(_ c: TopCity) -> some View {
        HStack(alignment: .lastTextBaseline, spacing: 8) {
            Text(c.name)
                .font(.system(size: 15, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            Spacer(minLength: 0)
            (Text(c.days).font(.system(size: 20, weight: .semibold).monospacedDigit())
                + Text(" 天").font(.system(size: 11)).foregroundColor(palette.secondary))
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
