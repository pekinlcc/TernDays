import Foundation

/// 按国家 / 地区汇总(cityKey 前缀即 ISO 国家码),与 Android :core Regions 同一口径。
/// 中国大陆、香港、澳门、台湾分开统计——出入境与税务口径上它们是不同的地区。
enum Regions {

    struct RegionStat: Identifiable {
        let code: String
        let name: String
        let days: Double
        let cities: Int

        var id: String { code }
    }

    static func codeOf(_ cityKey: String) -> String {
        guard let i = cityKey.firstIndex(of: ":") else { return cityKey }
        return String(cityKey[..<i])
    }

    static func nameOf(_ code: String) -> String {
        switch code {
        case "CN": return "中国大陆"
        case "HK": return "中国香港"
        case "MO": return "中国澳门"
        case "TW": return "中国台湾"
        default: return CityMatcher.countryName(code)
        }
    }

    static func summarize(_ stats: YearStats) -> [RegionStat] {
        // 按城市在统计里的顺序分组、组内按同一顺序求和:与 Kotlin groupBy + sumOf 的浮点累加顺序一致
        var order: [String] = []
        var groups: [String: [CityStat]] = [:]
        for c in stats.cities where c.cityKey != "unknown" {
            let code = codeOf(c.cityKey)
            if groups[code] == nil {
                order.append(code)
                groups[code] = []
            }
            groups[code]?.append(c)
        }
        let list: [RegionStat] = order.map { code in
            let cs = groups[code] ?? []
            return RegionStat(code: code, name: nameOf(code), days: cs.reduce(0.0) { $0 + $1.days }, cities: cs.count)
        }
        return list.sorted { a, b in a.days != b.days ? a.days > b.days : a.code < b.code }
    }
}
