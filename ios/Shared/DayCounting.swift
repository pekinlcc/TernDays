import Foundation

/// 计天规则（与 Android :core 对齐，见 docs/requirements.md §2）：
/// 一天 = 上半天样本 + 下半天样本；正式早/晚点优先；
/// 首点（extra）只在对应半天样本缺失时兜底（<12 点顶早点，≥12 点顶晚点）。
/// 「进行中的今天」例外：缺的那半天补捕窗口还没关时，单样本只计 0.5 天。
enum DayCounting {

    /// 半天样本:来自打卡、首点兜底,或半天手动更正
    private struct Sample {
        let cityKey: String
        let cityName: String
    }

    static func attributeDay(
        date: LocalDate,
        morning: Punch?,
        evening: Punch?,
        extra: Punch?,
        override o: DayOverride?
    ) -> DayAttribution {
        attributeDay(date: date, morning: morning, evening: evening, extra: extra,
                     overrides: o.map { [$0] } ?? [])
    }

    /// - Parameter pending: 还可能补上样本的时段（只对进行中的今天非空，见 PunchRules.pendingSlots）
    static func attributeDay(
        date: LocalDate,
        morning: Punch?,
        evening: Punch?,
        extra: Punch?,
        overrides: [DayOverride],
        pending: Set<Slot> = []
    ) -> DayAttribution {
        if let full = overrides.first(where: { $0.scope == .full }) {
            return DayAttribution(
                date: date,
                shares: [CityShare(cityKey: full.cityKey, cityName: full.cityName, weight: 1.0)],
                manual: true
            )
        }
        let mo = overrides.first { $0.scope == .morning }
        let eo = overrides.first { $0.scope == .evening }
        let mPunch = morning ?? extra.flatMap { $0.localHour < 12 ? $0 : nil }
        let ePunch = evening ?? extra.flatMap { $0.localHour >= 12 ? $0 : nil }
        let m = mo.map { Sample(cityKey: $0.cityKey, cityName: $0.cityName) }
            ?? mPunch.map { Sample(cityKey: $0.cityKey, cityName: $0.cityName) }
        let e = eo.map { Sample(cityKey: $0.cityKey, cityName: $0.cityName) }
            ?? ePunch.map { Sample(cityKey: $0.cityKey, cityName: $0.cityName) }
        return attributeSamples(date: date, morning: m, evening: e, manual: mo != nil || eo != nil, pending: pending)
    }

    private static func attributeSamples(date: LocalDate, morning: Sample?, evening: Sample?, manual: Bool,
                                         pending: Set<Slot>) -> DayAttribution {
        switch (morning, evening) {
        case let (m?, e?):
            if m.cityKey == e.cityKey {
                return DayAttribution(date: date, shares: [CityShare(cityKey: m.cityKey, cityName: m.cityName, weight: 1.0)], manual: manual)
            }
            return DayAttribution(date: date, shares: [
                CityShare(cityKey: m.cityKey, cityName: m.cityName, weight: 0.5),
                CityShare(cityKey: e.cityKey, cityName: e.cityName, weight: 0.5),
            ], manual: manual)
        // 单样本:另一半天还没到点(进行中的今天)只算 0.5 天;窗口已关则按整天
        case let (m?, nil):
            let w = pending.contains(.evening) ? 0.5 : 1.0
            return DayAttribution(date: date, shares: [CityShare(cityKey: m.cityKey, cityName: m.cityName, weight: w)], manual: manual)
        case let (nil, e?):
            let w = pending.contains(.morning) ? 0.5 : 1.0
            return DayAttribution(date: date, shares: [CityShare(cityKey: e.cityKey, cityName: e.cityName, weight: w)], manual: manual)
        default:
            return DayAttribution(date: date, shares: [])
        }
    }

    /// - Parameter nowHour: 当前本地小时（0–23）。给了就把 today 当作「进行中」：
    ///   还没打的那半天不算漏记，单样本先按 0.5 天计。传 nil 表示按已结束的日子统计。
    static func computeYearStats(
        year: Int,
        today: LocalDate,
        punches: [Punch],
        overrides: [DayOverride],
        nowHour: Int? = nil
    ) -> YearStats {
        let first = LocalDate(year: year, month: 1, day: 1)
        let yearEnd = LocalDate(year: year, month: 12, day: 31)
        let last = min(today, yearEnd)
        if last < first {
            return YearStats(year: year, firstDate: first, lastDate: first, recordedDays: 0,
                             cities: [], unrecordedDates: [], days: [:])
        }

        // 同一 (日期, 时段) 多条记录（向西跨时区）只取最早
        var bySlot: [String: Punch] = [:]
        for p in punches where p.localDate.year == year {
            let k = "\(p.localDate)|\(p.slot.rawValue)"
            if let cur = bySlot[k], cur.epochMs <= p.epochMs { continue }
            bySlot[k] = p
        }
        let overridesByDate = Dictionary(grouping: overrides.filter { $0.localDate.year == year }) { $0.localDate }

        // 「无记录」从当年首条记录之日起算:开始使用之前的日子不是漏记
        let firstRecordDate = min(
            bySlot.values.map(\.localDate).min() ?? LocalDate(year: 9999, month: 12, day: 31),
            overridesByDate.keys.min() ?? LocalDate(year: 9999, month: 12, day: 31)
        )

        var days: [LocalDate: DayAttribution] = [:]
        var unrecorded: [LocalDate] = []
        var recorded = 0.0
        var d = first
        while d <= last {
            let pending: Set<Slot> = (d == today && nowHour != nil) ? PunchRules.pendingSlots(hour: nowHour!) : []
            let attr = attributeDay(
                date: d,
                morning: bySlot["\(d)|\(Slot.morning.rawValue)"],
                evening: bySlot["\(d)|\(Slot.evening.rawValue)"],
                extra: bySlot["\(d)|\(Slot.extra.rawValue)"],
                overrides: overridesByDate[d] ?? [],
                pending: pending
            )
            days[d] = attr
            if attr.shares.isEmpty {
                // 今天还没过完就不算「漏记」——还有机会自动打上
                if d >= firstRecordDate && pending.isEmpty { unrecorded.append(d) }
            } else {
                recorded += attr.shares.reduce(0) { $0 + $1.weight }
            }
            if d == last { break }
            d = d.next()
        }

        var acc: [String: (name: String, days: Double, full: Int, half: Int)] = [:]
        for attr in days.values {
            for s in attr.shares {
                var a = acc[s.cityKey] ?? (s.cityName, 0, 0, 0)
                a.days += s.weight
                if s.weight >= 1.0 { a.full += 1 } else { a.half += 1 }
                acc[s.cityKey] = a
            }
        }
        let cities = acc
            .map { CityStat(cityKey: $0.key, cityName: $0.value.name, days: $0.value.days,
                            fullDays: $0.value.full, halfDays: $0.value.half) }
            .sorted { ($0.days, $1.cityName) > ($1.days, $0.cityName) }

        return YearStats(year: year, firstDate: first, lastDate: last, recordedDays: recorded,
                         cities: cities, unrecordedDates: unrecorded.sorted(), days: days)
    }

    /// 38.5 -> "38.5"，152.0 -> "152"
    static func formatDays(_ days: Double) -> String {
        let rounded = (days * 2).rounded() / 2
        if rounded == rounded.rounded(.down) { return String(Int(rounded)) }
        return String(rounded)
    }
}
