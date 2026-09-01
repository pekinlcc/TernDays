package app.terndays.core

import java.time.LocalDate

/** 桌面小组件展示内容（纯函数，便于单测；Android/iOS 小组件共用同一套口径）。 */
object WidgetSummary {

    data class CityLine(val name: String, val days: String)

    data class Model(
        val yearLabel: String,       // "2026 年"
        val bigDays: String,         // "240"
        val statsLabel: String,      // "天已记录 · 6 城"
        val todayLabel: String?,     // "今日 · 早 北京 ✓ · 晚 待记录"（非当年视图为 null）
        val topCities: List<CityLine>,
    )

    fun build(
        stats: YearStats,
        today: LocalDate,
        punches: List<Punch>,
        topN: Int = 3,
    ): Model {
        val isCurrentYear = stats.year == today.year
        val todayLabel = if (!isCurrentYear) {
            null
        } else {
            val todays = punches.filter { it.localDate == today }
            val m = todays.filter { it.slot == Slot.MORNING }.minByOrNull { it.epochMs }
            val e = todays.filter { it.slot == Slot.EVENING }.minByOrNull { it.epochMs }
            "今日 · 早 ${m?.cityName?.plus(" ✓") ?: "待记录"} · 晚 ${e?.cityName?.plus(" ✓") ?: "待记录"}"
        }
        return Model(
            yearLabel = "${stats.year} 年",
            bigDays = stats.recordedDays.toString(),
            statsLabel = "天已记录 · ${stats.cities.size} 城",
            todayLabel = todayLabel,
            topCities = stats.cities.take(topN).map {
                CityLine(it.cityName, DayCounting.formatDays(it.days))
            },
        )
    }
}
