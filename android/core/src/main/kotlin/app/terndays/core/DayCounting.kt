package app.terndays.core

import java.time.LocalDate

/**
 * 计天规则（已与产品确认）：
 *  - 一天看作「上半天样本 + 下半天样本」：早点是上半天样本，晚点是下半天样本
 *  - 两个样本同城 → 该城市 +1 天；异城 → 各 +0.5 天；只有一个样本 → 该城市 +1 天
 *  - 例外「进行中的今天」：另一半天的补捕窗口还没关（晚点还没到 / 还没过 12 点），
 *    这一天先按 0.5 天计——今天只打了早点，就是只待了半天，等晚点打上再补满
 *  - 全天无样本 → 计入「无记录」，可手动补记（补记整天 +1，优先于打卡点）
 *  - 首点（EXTRA，首次安装立即记录的点）只做兜底：正式早/晚点缺失时，按首点捕获时刻
 *    所在半天顶替对应样本（<12:00 顶早点，≥12:00 顶晚点）；正式点永远优先。
 *    因此任何一天最多计 1 天，同一半天内的多个点不会拆出额外的半天。
 */
object DayCounting {

    private fun localTime(p: Punch) =
        java.time.Instant.ofEpochMilli(p.epochMs).atZone(java.time.ZoneId.of(p.zoneId)).toLocalTime()

    /** 半天样本:来自打卡、首点兜底,或半天手动更正。 */
    private data class Sample(val cityKey: String, val cityName: String)

    private fun Punch.sample() = Sample(cityKey, cityName)

    fun attributeDay(
        date: LocalDate,
        morning: Punch?,
        evening: Punch?,
        extra: Punch?,
        override: DayOverride?,
    ): DayAttribution = attributeDay(date, morning, evening, extra, listOfNotNull(override))

    /**
     * @param pending 还可能补上样本的时段（只对进行中的今天非空，见 PunchRules.pendingSlots）。
     *   缺的那半天还没到点时，单样本只计 0.5 天，而不是整天。
     */
    fun attributeDay(
        date: LocalDate,
        morning: Punch?,
        evening: Punch?,
        extra: Punch?,
        overrides: List<DayOverride>,
        pending: Set<Slot> = emptySet(),
    ): DayAttribution {
        val full = overrides.firstOrNull { it.scope == OverrideScope.FULL }
        if (full != null) {
            return DayAttribution(date, listOf(CityShare(full.cityKey, full.cityName, 1.0)), manual = true)
        }
        val mo = overrides.firstOrNull { it.scope == OverrideScope.MORNING }
        val eo = overrides.firstOrNull { it.scope == OverrideScope.EVENING }
        val m = mo?.let { Sample(it.cityKey, it.cityName) }
            ?: (morning ?: extra?.takeIf { PunchRules.isMorningHalf(localTime(it)) })?.sample()
        val e = eo?.let { Sample(it.cityKey, it.cityName) }
            ?: (evening ?: extra?.takeIf { !PunchRules.isMorningHalf(localTime(it)) })?.sample()
        return attributeSamples(date, m, e, manual = mo != null || eo != null, pending = pending)
    }

    private fun attributeSamples(
        date: LocalDate,
        morning: Sample?,
        evening: Sample?,
        manual: Boolean,
        pending: Set<Slot>,
    ): DayAttribution {
        return when {
            morning != null && evening != null ->
                if (morning.cityKey == evening.cityKey) {
                    DayAttribution(date, listOf(CityShare(morning.cityKey, morning.cityName, 1.0)), manual)
                } else {
                    DayAttribution(
                        date,
                        listOf(
                            CityShare(morning.cityKey, morning.cityName, 0.5),
                            CityShare(evening.cityKey, evening.cityName, 0.5),
                        ),
                        manual,
                    )
                }
            // 单样本:另一半天还没到点(进行中的今天)只算 0.5 天;窗口已关则按整天
            morning != null -> DayAttribution(
                date,
                listOf(CityShare(morning.cityKey, morning.cityName, if (Slot.EVENING in pending) 0.5 else 1.0)),
                manual,
            )
            evening != null -> DayAttribution(
                date,
                listOf(CityShare(evening.cityKey, evening.cityName, if (Slot.MORNING in pending) 0.5 else 1.0)),
                manual,
            )
            else -> DayAttribution(date, emptyList())
        }
    }

    /**
     * 统计某个自然年（1.1 至 今天/12.31 中较早者）。
     * 同一 (日期, 时段) 出现多条记录时（如向西跨时区飞行），只取最早一条。
     *
     * @param nowHour 当前本地小时（0–23）。给了就把 today 当作「进行中」：
     *   还没打的那半天不算漏记，单样本先按 0.5 天计。传 null 表示按已结束的日子统计（历史/导出）。
     */
    fun computeYearStats(
        year: Int,
        today: LocalDate,
        punches: List<Punch>,
        overrides: List<DayOverride>,
        nowHour: Int? = null,
    ): YearStats {
        val first = LocalDate.of(year, 1, 1)
        val yearEnd = LocalDate.of(year, 12, 31)
        val last = if (today.isBefore(yearEnd)) today else yearEnd
        if (last.isBefore(first)) {
            return YearStats(year, first, first, 0.0, emptyList(), emptyList(), emptyMap())
        }

        val bySlot = HashMap<Pair<LocalDate, Slot>, Punch>()
        for (p in punches) {
            if (p.localDate.year != year) continue
            val k = p.localDate to p.slot
            val cur = bySlot[k]
            if (cur == null || p.epochMs < cur.epochMs) bySlot[k] = p
        }
        val overridesByDate = overrides.filter { it.localDate.year == year }.groupBy { it.localDate }

        // 「无记录」从当年首条记录之日起算:安装/开始使用之前的日子不是「漏记」,
        // 不再让新装用户首页一上来就显示「另有 240+ 天无记录」
        val firstRecordDate = minOf(
            bySlot.keys.minOfOrNull { it.first } ?: LocalDate.MAX,
            overridesByDate.keys.minOrNull() ?: LocalDate.MAX,
        )

        val days = LinkedHashMap<LocalDate, DayAttribution>()
        val unrecorded = ArrayList<LocalDate>()
        var recorded = 0.0
        var d = first
        while (!d.isAfter(last)) {
            val pending = if (d == today && nowHour != null) PunchRules.pendingSlots(nowHour) else emptySet()
            val attr = attributeDay(
                d,
                bySlot[d to Slot.MORNING],
                bySlot[d to Slot.EVENING],
                bySlot[d to Slot.EXTRA],
                overridesByDate[d] ?: emptyList(),
                pending,
            )
            days[d] = attr
            if (attr.shares.isEmpty()) {
                // 今天还没过完就不算「漏记」——还有机会自动打上
                if (!d.isBefore(firstRecordDate) && pending.isEmpty()) unrecorded.add(d)
            } else {
                recorded += attr.shares.sumOf { it.weight }
            }
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
