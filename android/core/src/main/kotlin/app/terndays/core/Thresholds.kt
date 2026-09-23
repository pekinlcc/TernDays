package app.terndays.core

import java.time.LocalDate

/**
 * 天数阈值(如「中国大陆 183 天」「申根 90/180」):接近或达到时提醒。
 * 统计窗口:自然年,或截至今天的滚动 180 天。
 */
object Thresholds {

    enum class Window(val label: String) { YEAR("自然年"), ROLLING_180("最近 180 天") }

    data class Threshold(val regionCode: String, val days: Int, val window: Window = Window.YEAR) {
        /** 同一条阈值在同一窗口期里只提醒一次:自然年按年份,滚动窗口按月份去重 */
        fun notifyKey(today: LocalDate): String = when (window) {
            Window.YEAR -> "$regionCode|$days|$window|${today.year}"
            Window.ROLLING_180 -> "$regionCode|$days|$window|${today.year}-${today.monthValue}"
        }
    }

    enum class Level { OK, NEAR, REACHED }

    data class Status(val threshold: Threshold, val used: Double, val level: Level) {
        val remaining: Double get() = (threshold.days - used).coerceAtLeast(0.0)
    }

    /** 剩余 ≤ max(7 天, 阈值的 10%) 算「接近」。 */
    fun status(threshold: Threshold, used: Double): Status {
        val near = maxOf(7.0, threshold.days * 0.1)
        val level = when {
            used >= threshold.days -> Level.REACHED
            threshold.days - used <= near -> Level.NEAR
            else -> Level.OK
        }
        return Status(threshold, used, level)
    }

    /** 某个窗口的统计区间(含首尾)。 */
    fun range(window: Window, today: LocalDate): Pair<LocalDate, LocalDate> = when (window) {
        Window.YEAR -> LocalDate.of(today.year, 1, 1) to today
        Window.ROLLING_180 -> today.minusDays(179) to today
    }

    // ---- 存储:"CN:183:YEAR;SCHENGEN:90:ROLLING_180",坏条目跳过 ----

    fun encode(list: List<Threshold>): String = list.joinToString(";") { "${it.regionCode}:${it.days}:${it.window.name}" }

    fun decode(text: String?): List<Threshold> =
        text.orEmpty().split(';').mapNotNull { item ->
            val f = item.split(':')
            if (f.size != 3) return@mapNotNull null
            val days = f[1].toIntOrNull()?.takeIf { it in 1..366 } ?: return@mapNotNull null
            val window = Window.entries.firstOrNull { it.name == f[2] } ?: return@mapNotNull null
            if (f[0].isBlank()) null else Threshold(f[0], days, window)
        }
}
