import SwiftUI
import WidgetKit

/// 城市候选(补记 / 更正的建议列表)
struct CityOption: Identifiable, Hashable {
    let key: String
    let name: String
    /// 「前一天」「后一天」「本页城市」等提示;空 = 常去城市
    var note: String = ""

    var id: String { key }

    /// 按顺序合并、按 cityKey 去重(先出现的保留,提示也保留先出现的那个)
    static func merge(_ groups: [[CityOption]], limit: Int = 10) -> [CityOption] {
        var seen = Set<String>()
        var out: [CityOption] = []
        for group in groups {
            for c in group where c.key != "unknown" && !seen.contains(c.key) {
                seen.insert(c.key)
                out.append(c)
            }
        }
        return Array(out.prefix(limit))
    }

    /// 最近去过的城市(全库按最近出现的日期倒序)
    static func recent(limit: Int = 8) -> [CityOption] {
        DataStore.shared.recentCities(limit: limit).map { CityOption(key: $0.key, name: $0.name) }
    }
}

/// 更正某一天需要的上下文:当天的打卡与更正、当前计入结果、候选城市(前一天 / 后一天的城市在前)。
/// 点开时在主线程读一次(只涉及前后三天,很便宜),sheet 内不再访问存储。
struct DayContext {
    let date: LocalDate
    let punches: [Punch]
    let overrides: [DayOverride]
    let attribution: DayAttribution?
    let suggestions: [CityOption]
    /// 允许只改半天:有任一半天样本;已有半天更正;或是今天且早点窗口已关(过了 12 点,早上那半天不会再自动补上)
    let allowHalfScope: Bool

    var hasPunches: Bool { !punches.isEmpty }
    var hasOverride: Bool { !overrides.isEmpty }
    var currentCityName: String? {
        guard let shares = attribution?.shares, !shares.isEmpty else { return nil }
        return shares.map(\.cityName).joined(separator: " + ")
    }

    static func load(_ date: LocalDate, preferred: CityOption? = nil, now: Date = Date()) -> DayContext {
        let today = LocalDate(from: now, in: .current)
        let hour = Calendar.current.component(.hour, from: now)
        let from = date.minusDays(1)
        let next = date.plusDays(1)
        // 明天还没发生:只看到今天为止
        let to = next <= today ? next : date
        let punches = DataStore.shared.punchesBetween(from, to)
        let overrides = DataStore.shared.overridesBetween(from, to)
        let stats = DayCounting.computeRangeStats(
            from: from, to: to, today: today, punches: punches, overrides: overrides, nowHour: hour
        )
        let dayPunches = punches.filter { $0.localDate == date }
        let dayOverrides = overrides.filter { $0.localDate == date }

        // 前一天从晚上那半天往前数(离这一天最近的城市在前);后一天从早上那半天往后数
        let prev = (stats.days[from]?.shares ?? []).reversed().map {
            CityOption(key: $0.cityKey, name: $0.cityName, note: "前一天")
        }
        var after: [CityOption] = []
        if to == next {
            after = (stats.days[next]?.shares ?? []).map { CityOption(key: $0.cityKey, name: $0.cityName, note: "后一天") }
        }
        var first: [CityOption] = []
        if let preferred { first.append(preferred) }
        let suggestions = CityOption.merge([first, prev, after, CityOption.recent()])

        let flags = DayCounting.halfSampleFlags(
            morning: dayPunches.first { $0.slot == .morning },
            evening: dayPunches.first { $0.slot == .evening },
            extra: dayPunches.first { $0.slot == .extra }
        )
        let morningClosed = date == today && hour >= PunchRules.morningWindowEndHour
        let hasHalfOverride = dayOverrides.contains { $0.scope != .full }
        return DayContext(
            date: date,
            punches: dayPunches,
            overrides: dayOverrides,
            attribution: stats.days[date],
            suggestions: suggestions,
            allowHalfScope: flags.0 || flags.1 || morningClosed || hasHalfOverride
        )
    }
}

/// sheet(item:) 的包装:要更正的那一天(连同点开那一刻读到的上下文)
struct CorrectTarget: Identifiable {
    let context: DayContext

    var date: LocalDate { context.date }
    var id: String { date.description }

    init(date: LocalDate, preferred: CityOption? = nil) {
        context = DayContext.load(date, preferred: preferred)
    }
}

/// 所有「写手动更正」的入口(首页纠正 / 城市详情 / 设置补记与恢复自动)走同一条路:
/// 写前快照这些日子已有的更正 → 写入 → 刷新小组件与界面 → toast 带「撤销」,点了按快照原样换回。
enum Corrections {

    static func afterWrite() {
        WidgetCenter.shared.reloadAllTimelines()
        NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
    }

    /// - Parameters:
    ///   - dates: 这次会动到的日子(快照范围)
    ///   - toast: 写入后的提示(如「已改为 重庆」),会带「撤销」按钮;nil = 不提示
    static func apply(dates: Set<LocalDate>, toast: String?, write: () -> Void) {
        let snapshot = DataStore.shared.overridesOn(dates)
        write()
        afterWrite()
        guard let toast else { return }
        ToastCenter.shared.show(toast, actionTitle: "撤销") {
            DataStore.shared.replaceOverrides(on: dates, with: snapshot)
            afterWrite()
            ToastCenter.shared.show("已撤销")
        }
    }

    static func set(_ o: DayOverride, toast: String?) {
        apply(dates: [o.localDate], toast: toast) { DataStore.shared.setOverride(o) }
    }

    static func setMany(_ list: [DayOverride], toast: String?) {
        guard !list.isEmpty else { return }
        apply(dates: Set(list.map(\.localDate)), toast: toast) { DataStore.shared.setOverrides(list) }
    }

    /// scope 为 nil = 恢复整天(删掉这一天全部更正)
    static func restoreAuto(date: LocalDate, scope: OverrideScope? = nil, toast: String?) {
        apply(dates: [date], toast: toast) {
            if let scope {
                DataStore.shared.removeOverride(date: date, scope: scope)
            } else {
                DataStore.shared.removeOverride(date: date)
            }
        }
    }
}

extension LocalDate {
    /// 当地中午(DatePicker 用;取中午避开夏令时切换时的零点空洞)
    func noonDate(in tz: TimeZone = .current) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = tz
        return cal.date(from: DateComponents(year: year, month: month, day: day, hour: 12)) ?? Date()
    }
}

/// 列表里的文字按钮:点击区域至少 44pt 高(无障碍),整行都能点
struct TapTarget: ViewModifier {
    func body(content: Content) -> some View {
        content
            .frame(minHeight: 44)
            .contentShape(Rectangle())
    }
}

extension View {
    func tapTarget() -> some View { modifier(TapTarget()) }
}
