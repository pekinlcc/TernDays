import Foundation

/// 桌面小组件展示内容(纯函数;与 Android :core WidgetSummary 同一口径)。
/// 只保留最关键的信息:Top N 城市及天数。
enum WidgetSummary {

    struct CityLine {
        let name: String
        let days: String
    }

    struct Model {
        /// "2026 年"
        let yearLabel: String
        /// 按天数降序,最多 topN 个
        let topCities: [CityLine]
        /// 今年还空着、但往年有记录(典型:元旦凌晨):空态要说「今年的第一条会在下次打卡后出现」
        var newYearEmpty: Bool = false
    }

    static func build(stats: YearStats, topN: Int = 3) -> Model {
        Model(
            yearLabel: "\(stats.year) 年",
            topCities: stats.cities.prefix(max(topN, 0)).map {
                CityLine(name: $0.cityName, days: DayCounting.formatDays($0.days))
            },
            newYearEmpty: stats.cities.isEmpty && (stats.trackingSince.map { $0.year < stats.year } ?? false)
        )
    }
}
