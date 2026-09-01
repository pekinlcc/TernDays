import Foundation

// MARK: - LocalDate（与时区无关的日历日期，格式 yyyy-MM-dd）

struct LocalDate: Hashable, Comparable, Codable, CustomStringConvertible {
    let year: Int
    let month: Int
    let day: Int

    init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    init?(parse s: String) {
        let parts = s.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        self.init(year: parts[0], month: parts[1], day: parts[2])
    }

    init(from date: Date, in timeZone: TimeZone) {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let c = cal.dateComponents([.year, .month, .day], from: date)
        self.init(year: c.year!, month: c.month!, day: c.day!)
    }

    static func isLeap(_ y: Int) -> Bool { (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 }

    static func daysIn(year: Int, month: Int) -> Int {
        switch month {
        case 1, 3, 5, 7, 8, 10, 12: return 31
        case 4, 6, 9, 11: return 30
        default: return isLeap(year) ? 29 : 28
        }
    }

    func next() -> LocalDate {
        if day < LocalDate.daysIn(year: year, month: month) {
            return LocalDate(year: year, month: month, day: day + 1)
        }
        if month < 12 { return LocalDate(year: year, month: month + 1, day: 1) }
        return LocalDate(year: year + 1, month: 1, day: 1)
    }

    /// 1 = 周一 … 7 = 周日（蔡勒公式）
    var weekday: Int {
        var y = year
        var m = month
        if m < 3 { m += 12; y -= 1 }
        let k = y % 100
        let j = y / 100
        let h = (day + 13 * (m + 1) / 5 + k + k / 4 + j / 4 + 5 * j) % 7  // 0=周六
        return ((h + 5) % 7) + 1
    }

    var weekdayCn: String { ["周一", "周二", "周三", "周四", "周五", "周六", "周日"][weekday - 1] }

    var description: String { String(format: "%04d-%02d-%02d", year, month, day) }

    static func < (lhs: LocalDate, rhs: LocalDate) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }

    static func today() -> LocalDate { LocalDate(from: Date(), in: TimeZone.current) }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(description)
    }

    init(from decoder: Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let d = LocalDate(parse: s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "bad date \(s)"))
        }
        self = d
    }
}

// MARK: - 领域模型（与 Android :core 对齐）

enum Slot: String, Codable, CaseIterable {
    case morning = "MORNING"
    case evening = "EVENING"
    /// 非定时的「首点」（首次安装立即记录），不占早/晚槽，仅作半天兜底样本
    case extra = "EXTRA"
}

struct Punch: Codable, Identifiable {
    var id: String { "\(localDate)-\(slot.rawValue)" }
    let localDate: LocalDate
    let slot: Slot
    let epochMs: Int64
    let zoneId: String
    let lat: Double
    let lng: Double
    let accuracyM: Double?
    let cityKey: String
    let cityName: String
    var delayed: Bool = false
    var fromCache: Bool = false
    /// true = 城市由行程连续性/误差圈改判(非几何最近);此类点不作连续性锚点。
    /// Optional 以兼容旧版存档(缺键按 nil 处理)。
    var viaContext: Bool? = false

    var clock: String {
        let date = Date(timeIntervalSince1970: Double(epochMs) / 1000)
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: zoneId) ?? .current
        let c = cal.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    /// 捕获时刻在其所在时区的小时（判定首点归属的半天）
    var localHour: Int {
        let date = Date(timeIntervalSince1970: Double(epochMs) / 1000)
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: zoneId) ?? .current
        return cal.component(.hour, from: date)
    }
}

struct DayOverride: Codable {
    let localDate: LocalDate
    let cityKey: String
    let cityName: String
}

struct CityShare: Equatable {
    let cityKey: String
    let cityName: String
    let weight: Double
}

struct DayAttribution {
    let date: LocalDate
    let shares: [CityShare]
    var manual: Bool = false
}

struct CityStat: Identifiable {
    var id: String { cityKey }
    let cityKey: String
    let cityName: String
    let days: Double
    let fullDays: Int
    let halfDays: Int
}

struct YearStats {
    let year: Int
    let firstDate: LocalDate
    let lastDate: LocalDate
    let recordedDays: Int
    let cities: [CityStat]
    let unrecordedDates: [LocalDate]
    let days: [LocalDate: DayAttribution]
}
