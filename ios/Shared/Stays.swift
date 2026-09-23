import Foundation

/// 行程时间线:把逐日计入结果折叠成「在某城连续待了一段」(与 Android :core Stays 同一口径)。
///
///  - 同城连续的日子合成一段;跨城日(0.5 + 0.5)是分界:上半天并入前一段,下半天开启新一段;
///  - 手动更正与自动判定一视同仁(按计入结果折叠);
///  - 无记录的日子断开行程(不知道那几天在哪,不能硬连)。
enum Stays {

    /// days:计入天数(整天 1、半天各 0.5,进行中的今天可能是 0.5)
    struct Stay {
        let cityKey: String
        var cityName: String
        let from: LocalDate
        var to: LocalDate
        var days: Double

        /// 跨越的自然日数(含首尾;跨城日两段各算一天)
        var spanDays: Int { LocalDate.daysBetween(from, to) + 1 }
    }

    static func fold(_ days: [LocalDate: DayAttribution]) -> [Stay] {
        var out: [Stay] = []
        var cur: Stay?
        for date in days.keys.sorted() {
            guard let shares = days[date]?.shares, !shares.isEmpty else {
                if let c = cur { out.append(c) }
                cur = nil
                continue
            }
            // shares 的顺序是 [上半天, 下半天];同城整天只有一份
            for s in shares {
                if var c = cur, c.cityKey == s.cityKey {
                    c.to = date
                    c.days += s.weight
                    c.cityName = s.cityName
                    cur = c
                } else {
                    if let c = cur { out.append(c) }
                    cur = Stay(cityKey: s.cityKey, cityName: s.cityName, from: date, to: date, days: s.weight)
                }
            }
        }
        if let c = cur { out.append(c) }
        return out
    }

    /// 「当前:上海 · 连续第 12 天」——最后一段一直延续到 today(或昨天,今天还没打)才算「当前」。
    static func current(_ stays: [Stay], today: LocalDate) -> Stay? {
        guard let last = stays.last, !(last.to < today.minusDays(1)) else { return nil }
        return last
    }
}
