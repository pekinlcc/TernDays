import Foundation

/// 计天规则（与 Android :core 对齐，见 docs/requirements.md §2）：
/// 一天 = 上半天样本 + 下半天样本；正式早/晚点优先；
/// 首点（extra）只在对应半天样本缺失时兜底（<12 点顶早点，≥12 点顶晚点）。
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

    static func attributeDay(
        date: LocalDate,
        morning: Punch?,
        evening: Punch?,
        extra: Punch?,
        overrides: [DayOverride]
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
        return attributeSamples(date: date, morning: m, evening: e, manual: mo != nil || eo != nil)
    }

    private static func attributeSamples(date: LocalDate, morning: Sample?, evening: Sample?, manual: Bool) -> DayAttribution {
        switch (morning, evening) {
        case let (m?, e?):
            if m.cityKey == e.cityKey {
                return DayAttribution(date: date, shares: [CityShare(cityKey: m.cityKey, cityName: m.cityName, weight: 1.0)], manual: manual)
            }
            return DayAttribution(date: date, shares: [
                CityShare(cityKey: m.cityKey, cityName: m.cityName, weight: 0.5),
                CityShare(cityKey: e.cityKey, cityName: e.cityName, weight: 0.5),
            ], manual: manual)
        case let (m?, nil):
            return DayAttribution(date: date, shares: [CityShare(cityKey: m.cityKey, cityName: m.cityName, weight: 1.0)], manual: manual)
        case let (nil, e?):
            return DayAttribution(date: date, shares: [CityShare(cityKey: e.cityKey, cityName: e.cityName, weight: 1.0)], manual: manual)
        default:
            return DayAttribution(date: date, shares: [])
        }
    }

    static func computeYearStats(
        year: Int,
        today: LocalDate,
        punches: [Punch],
        overrides: [DayOverride]
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
        var recorded = 0
        var d = first
        while d <= last {
            let attr = attributeDay(
                date: d,
                morning: bySlot["\(d)|\(Slot.morning.rawValue)"],
                evening: bySlot["\(d)|\(Slot.evening.rawValue)"],
                extra: bySlot["\(d)|\(Slot.extra.rawValue)"],
                overrides: overridesByDate[d] ?? []
            )
            days[d] = attr
            if attr.shares.isEmpty {
                if d >= firstRecordDate { unrecorded.append(d) }
            } else {
                recorded += 1
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
