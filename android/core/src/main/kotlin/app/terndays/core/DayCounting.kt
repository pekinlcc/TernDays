package app.terndays.core

import java.time.LocalDate

/**
 * 计天规则（已与产品确认）：
 *  - 早晚两点同城 → 该城市 +1 天
 *  - 早晚两点异城 → 各 +0.5 天
 *  - 只有一个点   → 该点城市 +1 天
 *  - 全天无记录   → 计入「无记录」，可手动补记（补记整天 +1，优先于打卡点）
 */
object DayCounting {

    fun attributeDay(
        date: LocalDate,
        morning: Punch?,
        evening: Punch?,
        override: DayOverride?,
    ): DayAttribution {
        if (override != null) {
            return DayAttribution(date, listOf(CityShare(override.cityKey, override.cityName, 1.0)), manual = true)
        }
        return when {
            morning != null && evening != null ->
                if (morning.cityKey == evening.cityKey) {
                    DayAttribution(date, listOf(CityShare(morning.cityKey, morning.cityName, 1.0)))
                } else {
                    DayAttribution(
                        date,
                        listOf(
                            CityShare(morning.cityKey, morning.cityName, 0.5),
                            CityShare(evening.cityKey, evening.cityName, 0.5),
                        ),
                    )
                }
            morning != null -> DayAttribution(date, listOf(CityShare(morning.cityKey, morning.cityName, 1.0)))
            evening != null -> DayAttribution(date, listOf(CityShare(evening.cityKey, evening.cityName, 1.0)))
            else -> DayAttribution(date, emptyList())
        }
    }

    /**
     * 统计某个自然年（1.1 至 今天/12.31 中较早者）。
     * 同一 (日期, 时段) 出现多条记录时（如向西跨时区飞行），只取最早一条。
     */
    fun computeYearStats(
        year: Int,
        today: LocalDate,
        punches: List<Punch>,
        overrides: List<DayOverride>,
    ): YearStats {
        val first = LocalDate.of(year, 1, 1)
        val yearEnd = LocalDate.of(year, 12, 31)
        val last = if (today.isBefore(yearEnd)) today else yearEnd
        if (last.isBefore(first)) {
            return YearStats(year, first, first, 0, emptyList(), emptyList(), emptyMap())
        }

        val bySlot = HashMap<Pair<LocalDate, Slot>, Punch>()
        for (p in punches) {
            if (p.localDate.year != year) continue
            val k = p.localDate to p.slot
            val cur = bySlot[k]
            if (cur == null || p.epochMs < cur.epochMs) bySlot[k] = p
        }
        val overrideByDate = overrides.filter { it.localDate.year == year }.associateBy { it.localDate }

        val days = LinkedHashMap<LocalDate, DayAttribution>()
        val unrecorded = ArrayList<LocalDate>()
        var recorded = 0
        var d = first
        while (!d.isAfter(last)) {
            val attr = attributeDay(d, bySlot[d to Slot.MORNING], bySlot[d to Slot.EVENING], overrideByDate[d])
            days[d] = attr
            if (attr.shares.isEmpty()) unrecorded.add(d) else recorded++
            d = d.plusDays(1)
        }

        data class Acc(var days: Double, var full: Int, var half: Int, val name: String)
        val acc = LinkedHashMap<String, Acc>()
        for (attr in days.values) {
            for (s in attr.shares) {
                val a = acc.getOrPut(s.cityKey) { Acc(0.0, 0, 0, s.cityName) }
                a.days += s.weight
                if (s.weight >= 1.0) a.full++ else a.half++
            }
        }
        val cities = acc.entries
            .map { CityStat(it.key, it.value.name, it.value.days, it.value.full, it.value.half) }
            .sortedWith(compareByDescending<CityStat> { it.days }.thenBy { it.cityName })

        return YearStats(year, first, last, recorded, cities, unrecorded, days)
    }

    /** 38.5 -> "38.5"，152.0 -> "152" */
    fun formatDays(days: Double): String {
        val rounded = Math.round(days * 2) / 2.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }
}
