package app.terndays.core

import java.time.LocalDate

enum class Slot { MORNING, EVENING }

/** 一次打卡记录（早点/晚点）。localDate 为捕获时刻在当时时区下的本地日期。 */
data class Punch(
    val localDate: LocalDate,
    val slot: Slot,
    val epochMs: Long,
    val zoneId: String,
    val lat: Double,
    val lng: Double,
    val accuracyM: Double?,
    val cityKey: String,
    val cityName: String,
    val delayed: Boolean = false,
    val fromCache: Boolean = false,
)

/** 手动补记：整天记入某个城市。 */
data class DayOverride(
    val localDate: LocalDate,
    val cityKey: String,
    val cityName: String,
)

data class CityShare(val cityKey: String, val cityName: String, val weight: Double)

/** 一天的计入结果；shares 为空表示无记录。 */
data class DayAttribution(
    val date: LocalDate,
    val shares: List<CityShare>,
    val manual: Boolean = false,
)

data class CityStat(
    val cityKey: String,
    val cityName: String,
    val days: Double,
    val fullDays: Int,
    val halfDays: Int,
)

data class YearStats(
    val year: Int,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val recordedDays: Int,
    val cities: List<CityStat>,
    val unrecordedDates: List<LocalDate>,
    val days: Map<LocalDate, DayAttribution>,
)
