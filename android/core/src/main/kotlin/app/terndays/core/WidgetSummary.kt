package app.terndays.core

/**
 * 桌面小组件展示内容（纯函数，便于单测；Android/iOS 共用同一口径）。
 * 只保留最关键的信息：Top N 城市及天数。
 */
object WidgetSummary {

    data class CityLine(val name: String, val days: String)

    data class Model(
        val yearLabel: String,          // "2026 年"
        val topCities: List<CityLine>,  // 按天数降序，最多 topN 个
    )

    fun build(stats: YearStats, topN: Int = 3): Model = Model(
        yearLabel = "${stats.year} 年",
        topCities = stats.cities.take(topN).map {
            CityLine(it.cityName, DayCounting.formatDays(it.days))
        },
    )
}
