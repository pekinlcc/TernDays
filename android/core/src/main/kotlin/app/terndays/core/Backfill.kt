package app.terndays.core

import java.time.LocalDate

/**
 * 按区间补记:出差回来一次补上一整段。
 *
 * 首日可以只补下半天(当天下午才到),末日可以只补上半天(当天中午就走了),
 * 中间的日子一律整天。写入时与已有更正的互斥规则见 [merge](与存储层 setOverride 同一口径)。
 */
object Backfill {

    /**
     * @param startScope 首日范围:FULL 或 EVENING(下午才到)
     * @param endScope 末日范围:FULL 或 MORNING(中午就走)
     * @throws IllegalArgumentException 区间倒置、范围不合法,或单日同时选了「下午才到」和「中午就走」
     */
    fun planRange(
        from: LocalDate,
        to: LocalDate,
        cityKey: String,
        cityName: String,
        startScope: OverrideScope = OverrideScope.FULL,
        endScope: OverrideScope = OverrideScope.FULL,
    ): List<DayOverride> {
        require(!to.isBefore(from)) { "结束日期不能早于开始日期" }
        require(startScope != OverrideScope.MORNING) { "首日只能整天或下半天" }
        require(endScope != OverrideScope.EVENING) { "末日只能整天或上半天" }
        if (from == to) {
            require(startScope == OverrideScope.FULL || endScope == OverrideScope.FULL) {
                "同一天不能既「下午才到」又「中午就走」"
            }
            val scope = if (startScope != OverrideScope.FULL) startScope else endScope
            return listOf(DayOverride(from, cityKey, cityName, scope))
        }
        val out = ArrayList<DayOverride>()
        var d = from
        while (!d.isAfter(to)) {
            val scope = when (d) {
                from -> startScope
                to -> endScope
                else -> OverrideScope.FULL
            }
            out.add(DayOverride(d, cityKey, cityName, scope))
            d = d.plusDays(1)
        }
        return out
    }

    /**
     * 把计划写入的更正并入已有更正(与存储层 setOverride 同一口径):
     * 整天更正清掉当天所有更正;半天更正清掉当天的整天更正与同一半天的旧更正,另一半天保留。
     */
    fun merge(existing: List<DayOverride>, planned: List<DayOverride>): List<DayOverride> {
        val result = existing.toMutableList()
        for (p in planned) {
            result.removeAll { o ->
                o.localDate == p.localDate && (
                    p.scope == OverrideScope.FULL || o.scope == OverrideScope.FULL || o.scope == p.scope
                    )
            }
            result.add(p)
        }
        return result.sortedWith(compareBy({ it.localDate }, { it.scope.ordinal }))
    }
}
