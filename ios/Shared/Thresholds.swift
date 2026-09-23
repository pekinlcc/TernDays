import Foundation

/// 天数阈值(如「中国大陆 183 天」):接近或达到时提醒(与 Android :core Thresholds 同一口径)。
/// 统计窗口:自然年,或截至今天的滚动 180 天。
enum Thresholds {

    enum Window: String, CaseIterable {
        case year = "YEAR"
        case rolling180 = "ROLLING_180"

        var label: String {
            switch self {
            case .year: return "自然年"
            case .rolling180: return "最近 180 天"
            }
        }
    }

    struct Threshold: Hashable {
        let regionCode: String
        let days: Int
        var window: Window = .year

        /// 同一条阈值在同一窗口期里只提醒一次:自然年按年份,滚动窗口按月份去重
        func notifyKey(today: LocalDate) -> String {
            switch window {
            case .year: return "\(regionCode)|\(days)|\(window.rawValue)|\(today.year)"
            case .rolling180: return "\(regionCode)|\(days)|\(window.rawValue)|\(today.year)-\(today.month)"
            }
        }
    }

    enum Level { case ok, near, reached }

    struct Status {
        let threshold: Threshold
        let used: Double
        let level: Level

        var remaining: Double { max(Double(threshold.days) - used, 0) }
    }

    /// 剩余 ≤ max(7 天, 阈值的 10%) 算「接近」。
    static func status(_ threshold: Threshold, used: Double) -> Status {
        let limit = Double(threshold.days)
        let near = max(7.0, limit * 0.1)
        let level: Level
        if used >= limit {
            level = .reached
        } else if limit - used <= near {
            level = .near
        } else {
            level = .ok
        }
        return Status(threshold: threshold, used: used, level: level)
    }

    /// 某个窗口的统计区间(含首尾)。
    static func range(_ window: Window, today: LocalDate) -> (from: LocalDate, to: LocalDate) {
        switch window {
        case .year: return (LocalDate(year: today.year, month: 1, day: 1), today)
        case .rolling180: return (today.minusDays(179), today)
        }
    }

    // MARK: 存储:"CN:183:YEAR;SCHENGEN:90:ROLLING_180",坏条目跳过

    static func encode(_ list: [Threshold]) -> String {
        list.map { "\($0.regionCode):\($0.days):\($0.window.rawValue)" }.joined(separator: ";")
    }

    static func decode(_ text: String?) -> [Threshold] {
        (text ?? "").split(separator: ";", omittingEmptySubsequences: false).compactMap { item -> Threshold? in
            let f = item.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
            guard f.count == 3, let days = Int(f[1]), (1...366).contains(days),
                  let window = Window(rawValue: f[2]) else { return nil }
            if f[0].trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
            return Threshold(regionCode: f[0], days: days, window: window)
        }
    }
}
