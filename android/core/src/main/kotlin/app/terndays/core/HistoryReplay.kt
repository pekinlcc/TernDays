package app.terndays.core

import java.time.LocalDate

/**
 * 城市库升级后的历史重解析:按打卡时间**重放**整条时间线,每一条都走与实时打卡
 * 完全相同的 CityResolver 交叉验证(top-K 候选 + 行程连续性 + 误差圈),
 * 而不是裸最近邻——否则会把此前依据上下文正确改判的边界记录批量改回错城市。
 *
 * 链条锚定规则(与 CityResolver.Prev 的约定一致):
 *  - prev 取重放序列中最近一条「锚点」= 非改判(viaContext=false)结果;
 *  - 当日存在手动更正(DayOverride)时,以更正城市作为该日贡献的 prev 城市;
 *  - ageHours 按锚点的打卡时间计,粘滞链整体受 36h 上限约束。
 */
object HistoryReplay {

    data class Item(
        val id: Long,
        val localDate: LocalDate,
        val epochMs: Long,
        val lat: Double,
        val lng: Double,
        val accuracyM: Double?,
        val cityKey: String,
        val cityName: String,
    )

    /** 每条打卡的重放结果;changed=true 表示城市与库中现值不同,需要写回。 */
    data class Outcome(
        val id: Long,
        val cityKey: String,
        val cityName: String,
        val viaContext: Boolean,
        val changed: Boolean,
    )

    fun replay(
        matcher: CityMatcher,
        items: List<Item>,
        overrideByDate: Map<LocalDate, String> = emptyMap(),
    ): List<Outcome> = replayWith({ lat, lng -> matcher.nearestByCity(lat, lng, 3) }, items, overrideByDate)

    /**
     * 同一个地方(家、公司)天天打卡,坐标几乎不变:3.4 万点的最近邻按 1e-4°(约 11 米)
     * 缓存一次重放内的结果。5 年数据在桌面 JVM 上从约 12.7 秒降到亚秒级,结果逐条不变
     * (1e-4° 以内的两个点,top-3 候选与距离差异远小于判定边距)。
     */
    internal fun replayWith(
        lookup: (Double, Double) -> List<CityMatcher.Match>,
        items: List<Item>,
        overrideByDate: Map<LocalDate, String> = emptyMap(),
        cache: Boolean = true,
    ): List<Outcome> {
        val memo = HashMap<Long, List<CityMatcher.Match>>()
        fun candidatesFor(lat: Double, lng: Double): List<CityMatcher.Match> {
            if (!cache) return lookup(lat, lng)
            val k = Math.round(lat * 10_000) * 4_000_000L + Math.round(lng * 10_000)
            return memo.getOrPut(k) { lookup(lat, lng) }
        }
        val out = ArrayList<Outcome>(items.size)
        var anchorKey: String? = null
        var anchorEpochMs = 0L
        for (item in items.sortedBy { it.epochMs }) {
            val candidates = candidatesFor(item.lat, item.lng)
            val prev = anchorKey?.let {
                CityResolver.Prev(it, (item.epochMs - anchorEpochMs) / 3_600_000.0)
            }
            val resolution = CityResolver.resolve(candidates, item.accuracyM, prev)
            val newKey = resolution?.match?.cityKey ?: item.cityKey
            val newName = resolution?.match?.cityName ?: item.cityName
            val via = resolution?.viaContext ?: false
            out.add(Outcome(item.id, newKey, newName, via, newKey != item.cityKey || newName != item.cityName))
            // 更新锚点:改判结果不能当锚(防粘滞链自续期);当日手动更正优先作准
            val overrideKey = overrideByDate[item.localDate]
            when {
                overrideKey != null -> {
                    anchorKey = overrideKey
                    anchorEpochMs = item.epochMs
                }
                !via -> {
                    anchorKey = newKey
                    anchorEpochMs = item.epochMs
                }
                // viaContext 的结果:锚点保持不动,链龄继续累积
            }
        }
        return out
    }
}
