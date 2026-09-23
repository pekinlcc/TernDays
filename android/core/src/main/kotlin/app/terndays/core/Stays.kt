package app.terndays.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 行程时间线:把逐日计入结果折叠成「在某城连续待了一段」。
 *
 *  - 同城连续的日子合成一段;跨城日(0.5 + 0.5)是分界:上半天并入前一段,下半天开启新一段;
 *  - 手动更正与自动判定一视同仁(按计入结果折叠);
 *  - 无记录的日子断开行程(不知道那几天在哪,不能硬连)。
 */
object Stays {

    /**
     * @param days 计入天数(整天 1、半天各 0.5,进行中的今天可能是 0.5)
     */
    data class Stay(
        val cityKey: String,
        val cityName: String,
        val from: LocalDate,
        val to: LocalDate,
        val days: Double,
    ) {
        /** 跨越的自然日数(含首尾;跨城日两段各算一天) */
        val spanDays: Int get() = ChronoUnit.DAYS.between(from, to).toInt() + 1
    }

    fun fold(days: Map<LocalDate, DayAttribution>): List<Stay> {
        val out = ArrayList<Stay>()
        var cur: Stay? = null
        for (date in days.keys.sorted()) {
            val shares = days.getValue(date).shares
            if (shares.isEmpty()) {
                cur?.let { out.add(it) }
                cur = null
                continue
            }
            // shares 的顺序是 [上半天, 下半天];同城整天只有一份
            for (s in shares) {
                val c = cur
                cur = if (c != null && c.cityKey == s.cityKey) {
                    c.copy(to = date, days = c.days + s.weight, cityName = s.cityName)
                } else {
                    c?.let { out.add(it) }
                    Stay(s.cityKey, s.cityName, date, date, s.weight)
                }
            }
        }
        cur?.let { out.add(it) }
        return out
    }

    /** 「当前:上海 · 连续第 12 天」——最后一段一直延续到 today(或昨天,今天还没打)才算「当前」。 */
    fun current(stays: List<Stay>, today: LocalDate): Stay? =
        stays.lastOrNull()?.takeIf { !it.to.isBefore(today.minusDays(1)) }
}
