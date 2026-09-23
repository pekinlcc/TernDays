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

    /// 1970-01-01 起的天数(与 java.time.LocalDate.toEpochDay 同义)。纯整数运算、与时区无关,
    /// 区间补记 / 滚动 180 天 / 行程跨度都靠它加减天数,不经过 Calendar(夏令时零点空洞不影响)。
    var epochDay: Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = (month + 9) % 12 // 三月 = 0
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    init(epochDay: Int) {
        let z = epochDay + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365
        let y = yoe + era * 400
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        self.init(year: m <= 2 ? y + 1 : y, month: m, day: d)
    }

    func plusDays(_ n: Int) -> LocalDate { n == 0 ? self : LocalDate(epochDay: epochDay + n) }

    func minusDays(_ n: Int) -> LocalDate { plusDays(-n) }

    /// b - a 的天数(b 在 a 之后为正)
    static func daysBetween(_ a: LocalDate, _ b: LocalDate) -> Int { b.epochDay - a.epochDay }

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

/// 手动更正的作用范围:整天,或只改上/下半天样本。
enum OverrideScope: String, Codable {
    case full = "FULL"
    case morning = "MORNING"
    case evening = "EVENING"
}

/// 手动补记/更正。full = 整天;morning/evening = 只替换对应半天样本
/// (跨城出行日可只改错的那半天,保住 0.5+0.5 的拆分)。
/// scope 为 Optional 以兼容旧版存档(缺键即 full)。
struct DayOverride: Codable {
    let localDate: LocalDate
    let cityKey: String
    let cityName: String
    var scopeRaw: OverrideScope? = .full

    var scope: OverrideScope { scopeRaw ?? .full }

    /// 列表行的稳定 id：同一天的两条半天更正必须区分开
    var rowId: String { "\(localDate)|\(scope.rawValue)" }

    enum CodingKeys: String, CodingKey {
        case localDate, cityKey, cityName
        case scopeRaw = "scope"
    }

    init(localDate: LocalDate, cityKey: String, cityName: String, scope: OverrideScope = .full) {
        self.localDate = localDate
        self.cityKey = cityKey
        self.cityName = cityName
        self.scopeRaw = scope
    }
}

/// manual:这份权重是否来自手动更正(整天更正,或构成它的某个半天样本是半天更正)
struct CityShare: Equatable {
    let cityKey: String
    let cityName: String
    let weight: Double
    var manual: Bool = false
}

/// 与 Android :core DayAttribution 同义。
/// provisional = 进行中的今天:缺的那半天还没到点,结果还会变(单样本先计 0.5,或还一条都没有)
struct DayAttribution {
    let date: LocalDate
    let shares: [CityShare]
    var manual: Bool = false
    var provisional: Bool = false
}

struct CityStat: Identifiable {
    var id: String { cityKey }
    let cityKey: String
    let cityName: String
    let days: Double
    let fullDays: Int
    /// 已定型的半天数(不含进行中的今天)
    let halfDays: Int
    /// 进行中的今天先计的那 0.5 天(0 或 1)
    var provisionalHalf: Int = 0
}

struct YearStats {
    let year: Int
    let firstDate: LocalDate
    let lastDate: LocalDate
    /// 已记录天数（= 各城市天数之和；跨城日 0.5+0.5，进行中的今天可能是 0.5）
    let recordedDays: Double
    let cities: [CityStat]
    let unrecordedDates: [LocalDate]
    let days: [LocalDate: DayAttribution]
    /// 「开始使用」之日：早于它的日子既不算漏记，也不出现在导出的每日明细里
    var trackingSince: LocalDate? = nil
}
