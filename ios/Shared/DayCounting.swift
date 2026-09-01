import Foundation

/// 计天规则（与 Android :core 对齐，见 docs/requirements.md §2）：
/// 一天 = 上半天样本 + 下半天样本；正式早/晚点优先；
/// 首点（extra）只在对应半天样本缺失时兜底（<12 点顶早点，≥12 点顶晚点）。
enum DayCounting {

    static func attributeDay(
        date: LocalDate,
        morning: Punch?,
        evening: Punch?,
        extra: Punch?,
        override o: DayOverride?
    ) -> DayAttribution {
        if let o {
            return DayAttribution(date: date, shares: [CityShare(cityKey: o.cityKey, cityName: o.cityName, weight: 1.0)], manual: true)
        }
        let m = morning ?? extra.flatMap { $0.localHour < 12 ? $0 : nil }
        let e = evening ?? extra.flatMap { $0.localHour >= 12 ? $0 : nil }
        return attributeSamples(date: date, morning: m, evening: e)
    }

    private static func attributeSamples(date: LocalDate, morning: Punch?, evening: Punch?) -> DayAttribution {
        switch (morning, evening) {
        case let (m?, e?):
            if m.cityKey == e.cityKey {
                return DayAttribution(date: date, shares: [CityShare(cityKey: m.cityKey, cityName: m.cityName, weight: 1.0)])
            }
            return DayAttribution(date: date, shares: [
                CityShare(cityKey: m.cityKey, cityName: m.cityName, weight: 0.5),
                CityShare(cityKey: e.cityKey, cityName: e.cityName, weight: 0.5),
            ])
        case let (m?, nil):
            return DayAttribution(date: date, shares: [CityShare(cityKey: m.cityKey, cityName: m.cityName, weight: 1.0)])
        case let (nil, e?):
            return DayAttribution(date: date, shares: [CityShare(cityKey: e.cityKey, cityName: e.cityName, weight: 1.0)])
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
        let overrideByDate = Dictionary(
            overrides.filter { $0.localDate.year == year }.map { ($0.localDate, $0) },
            uniquingKeysWith: { a, _ in a }
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
                override: overrideByDate[d]
            )
            days[d] = attr
            if attr.shares.isEmpty { unrecorded.append(d) } else { recorded += 1 }
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
