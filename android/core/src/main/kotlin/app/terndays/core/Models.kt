package app.terndays.core

import java.time.LocalDate

/** EXTRA：非定时的「首点」（首次安装立即记录），不占早/晚槽，仅作半天兜底样本。 */
enum class Slot { MORNING, EVENING, EXTRA }

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
    /** true = 该点城市由行程连续性/误差圈改判(非几何最近);此类点不作连续性锚点 */
    val viaContext: Boolean = false,
)

/** 手动更正的作用范围:整天,或只改上/下半天样本。 */
enum class OverrideScope { FULL, MORNING, EVENING }

/**
 * 手动补记/更正。FULL = 整天记入该城市(优先于一切打卡);
 * MORNING/EVENING = 只替换对应半天样本,另一半天仍按打卡判定
 * (跨城出行日可只改错的那半天,保住正确的 0.5+0.5 拆分)。
 * 同一天 FULL 与半天更正互斥,由存储层保证。
 */
data class DayOverride(
    val localDate: LocalDate,
    val cityKey: String,
    val cityName: String,
    val scope: OverrideScope = OverrideScope.FULL,
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
    /** 已记录天数（= 各城市天数之和；跨城日 0.5+0.5，进行中的今天可能是 0.5） */
    val recordedDays: Double,
    val cities: List<CityStat>,
    val unrecordedDates: List<LocalDate>,
    val days: Map<LocalDate, DayAttribution>,
)
