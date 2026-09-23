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
        java.time.Instant.ofEpochMilli(p.epochMs).atZone(zoneOf(p.zoneId)).toLocalTime()

    /**
     * 时区 id 来自落库时的系统值，也可能来自另一台手机导入的数据；
     * 系统升级后个别 id 会失效，`ZoneId.of` 直接抛异常会把首页/导出整个带崩。
     */
    fun zoneOf(id: String): java.time.ZoneId =
        runCatching { java.time.ZoneId.of(id) }.getOrElse { java.time.ZoneId.systemDefault() }

    /** 半天样本:来自打卡、首点兜底,或半天手动更正。 */
    private data class Sample(val cityKey: String, val cityName: String, val manual: Boolean = false)

    private fun Punch.sample() = Sample(cityKey, cityName)

    /**
     * 这一天上/下半天各自有没有样本（含首点兜底）。
     * 用于界面判断能否做半天更正：只有一个样本时也允许改那半天，
     * 否则「整天更正」会把另半天（可能还没打或属于另一座城市）一起吞掉。
     */
    fun halfSampleFlags(morning: Punch?, evening: Punch?, extra: Punch?): Pair<Boolean, Boolean> {
        val m = morning ?: extra?.takeIf { PunchRules.isMorningHalf(localTime(it)) }
        val e = evening ?: extra?.takeIf { !PunchRules.isMorningHalf(localTime(it)) }
        return (m != null) to (e != null)
    }

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
            return DayAttribution(date, listOf(CityShare(full.cityKey, full.cityName, 1.0, manual = true)), manual = true)
        }
        val mo = overrides.firstOrNull { it.scope == OverrideScope.MORNING }
        val eo = overrides.firstOrNull { it.scope == OverrideScope.EVENING }
        val m = mo?.let { Sample(it.cityKey, it.cityName, manual = true) }
            ?: (morning ?: extra?.takeIf { PunchRules.isMorningHalf(localTime(it)) })?.sample()
        val e = eo?.let { Sample(it.cityKey, it.cityName, manual = true) }
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
                    val share = CityShare(morning.cityKey, morning.cityName, 1.0, morning.manual || evening.manual)
                    DayAttribution(date, listOf(share), manual)
                } else {
                    DayAttribution(
                        date,
                        listOf(
                            CityShare(morning.cityKey, morning.cityName, 0.5, morning.manual),
                            CityShare(evening.cityKey, evening.cityName, 0.5, evening.manual),
                        ),
                        manual,
                    )
                }
            // 单样本:另一半天还没到点(进行中的今天)只算 0.5 天,并标记 provisional;窗口已关则按整天
            morning != null -> {
                val open = Slot.EVENING in pending
                DayAttribution(
                    date,
                    listOf(CityShare(morning.cityKey, morning.cityName, if (open) 0.5 else 1.0, morning.manual)),
                    manual,
                    provisional = open,
                )
            }
            evening != null -> {
                val open = Slot.MORNING in pending
                DayAttribution(
                    date,
                    listOf(CityShare(evening.cityKey, evening.cityName, if (open) 0.5 else 1.0, evening.manual)),
                    manual,
                    provisional = open,
                )
            }
            // 还一条都没有:仍有时段没到点就是「进行中」,不是「无记录」
            else -> DayAttribution(date, emptyList(), provisional = pending.isNotEmpty())
        }
    }

    /**
     * 统计某个自然年（1.1 至 今天/12.31 中较早者）。
     * 同一 (日期, 时段) 出现多条记录时（如向西跨时区飞行），只取最早一条。
     *
     * @param nowHour 当前本地小时（0–23）。给了就把 today 当作「进行中」：
     *   还没打的那半天不算漏记，单样本先按 0.5 天计。传 null 表示按已结束的日子统计。
     *   （应用与导出走同一份统计，都会传 nowHour；导出会在备注里标出「今天进行中」。）
     * @param earliestRecordDate 全库最早一条记录的日期（跨年份）。跨年后 1 月初的漏记
     *   必须靠它才能被认出来——只看当年最早记录的话，1 月初永远排在它之前、永远不算「无记录」。
     */
    fun computeYearStats(
        year: Int,
        today: LocalDate,
        punches: List<Punch>,
        overrides: List<DayOverride>,
        nowHour: Int? = null,
        earliestRecordDate: LocalDate? = null,
    ): YearStats {
        val first = LocalDate.of(year, 1, 1)
        val yearEnd = LocalDate.of(year, 12, 31)
        val last = if (today.isBefore(yearEnd)) today else yearEnd
        if (last.isBefore(first)) {
            return YearStats(year, first, first, 0.0, emptyList(), emptyList(), emptyMap(), wholeYear = true)
        }
        return computeRangeStats(first, last, today, punches, overrides, nowHour, earliestRecordDate)
            .copy(year = year, wholeYear = true)
    }

    /**
     * 任意区间(含首尾)的统计,口径与 [computeYearStats] 完全相同:自定义区间、「最近 180 天」、
     * 跨年区间都走这里。结果的 year 取区间末日所在年份(仅作标签)。
     */
    fun computeRangeStats(
        from: LocalDate,
        to: LocalDate,
        today: LocalDate,
        punches: List<Punch>,
        overrides: List<DayOverride>,
        nowHour: Int? = null,
        earliestRecordDate: LocalDate? = null,
    ): YearStats {
        require(!to.isBefore(from)) { "区间结束不能早于开始" }
        val first = from
        val last = to
        fun inRange(d: LocalDate) = !d.isBefore(first) && !d.isAfter(last)

        val bySlot = HashMap<Pair<LocalDate, Slot>, Punch>()
        for (p in punches) {
            if (!inRange(p.localDate)) continue
            val k = p.localDate to p.slot
            val cur = bySlot[k]
            if (cur == null || p.epochMs < cur.epochMs) bySlot[k] = p
        }
        val overridesByDate = overrides.filter { inRange(it.localDate) }.groupBy { it.localDate }

        // 「无记录」从全库最早一条记录之日起算(跨年份):安装/开始使用之前的日子不是「漏记」,
        // 不再让新装用户首页一上来就显示「另有 240+ 天无记录」
        val firstRecordDate = minOf(
            earliestRecordDate ?: LocalDate.MAX,
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

        data class Acc(var days: Double, var full: Int, var half: Int, var provisional: Int, var name: String)
        val acc = LinkedHashMap<String, Acc>()
        // days 按日期升序:同一 cityKey 在不同日子名字不一样(城市库改过名)时,取最近那天的名字——
        // 两端都按日期顺序遍历,显示名是确定的
        for (attr in days.values) {
            for (s in attr.shares) {
                val a = acc.getOrPut(s.cityKey) { Acc(0.0, 0, 0, 0, s.cityName) }
                a.days += s.weight
                a.name = s.cityName
                when {
                    s.weight >= 1.0 -> a.full++
                    attr.provisional -> a.provisional++
                    else -> a.half++
                }
            }
        }
        val cities = acc.entries
            .map { CityStat(it.key, it.value.name, it.value.days, it.value.full, it.value.half, it.value.provisional) }
            .sortedWith(compareByDescending<CityStat> { it.days }.thenBy { it.cityName }.thenBy { it.cityKey })

        return YearStats(
            last.year, first, last, recorded, cities, unrecorded, days,
            trackingSince = firstRecordDate.takeIf { it != LocalDate.MAX },
        )
    }

    /** 38.5 -> "38.5"，152.0 -> "152" */
    fun formatDays(days: Double): String {
        val rounded = Math.round(days * 2) / 2.0
        return if (rounded == Math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }
}
