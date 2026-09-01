package app.terndays.core

/**
 * 城市判定的交叉验证：最近邻在真实边界（深圳/香港、珠海/澳门、燕郊/北京…）附近
 * 天然模糊，且网络定位在口岸带可能给出公里级误差的坐标。这里用三个本地信号
 * 消歧，全程离线、确定性、可单测：
 *
 *  1. 候选边距——前两个候选城市距离差小于「基础边距 + 定位误差」即视为模糊区；
 *  2. 行程连续性——模糊区内若近期（≤36h）上一次打卡的城市也在候选中，则粘住它
 *     （跨城通常伴随明确的距离变化；真正过关后的下一次打卡会深入新城市，边距拉大，
 *     连续性自动失效）；
 *  3. 误差圈优先——定位误差本身达到公里级（典型为基站/网络定位）时，坐标不可信，
 *     只要上一次城市仍在误差范围内的候选里就沿用它。
 *
 * 防错误传播：top1 距离极近（< 0.8km，明确落在该城锚点上）且定位可信时不粘，
 * 避免上一条记录本身错了把错误链式带下去。
 */
object CityResolver {

    /**
     * 行程连续性上下文。cityKey 应取最近一条「锚点」记录——即非改判(viaContext=false)
     * 的打卡,当日有手动更正时以更正城市为准;ageHours 也按该锚点计。
     * 这样 36h 上限约束的是整条粘滞链,而不是相邻两次打卡的间隔,
     * 链条不会因为「被粘住的打卡成为下一次的 prev」而无限自续期。
     */
    data class Prev(val cityKey: String, val ageHours: Double)

    data class Resolution(
        val match: CityMatcher.Match,
        /** true = 由行程连续性/误差圈改判（未采用几何最近的候选） */
        val viaContext: Boolean,
        /** true = 处于边界模糊区（无论是否改判），可用于界面提示 */
        val ambiguous: Boolean,
    )

    const val MAX_PREV_AGE_HOURS = 36.0
    const val BASE_MARGIN_KM = 1.5
    const val ACCURACY_MARGIN_CAP_KM = 3.0
    const val MIN_STICK_TOP1_KM = 0.8
    const val POOR_ACCURACY_M = 1500.0

    fun resolve(
        candidates: List<CityMatcher.Match>,
        accuracyM: Double?,
        prev: Prev?,
    ): Resolution? {
        val top1 = candidates.firstOrNull() ?: return null
        val accuracyKm = ((accuracyM ?: 0.0) / 1000.0).coerceAtLeast(0.0)
        val ambiguousMargin = BASE_MARGIN_KM + minOf(accuracyKm, ACCURACY_MARGIN_CAP_KM)
        val runnerUp = candidates.getOrNull(1)
        val ambiguous = runnerUp != null && runnerUp.distanceKm - top1.distanceKm < ambiguousMargin

        val prevValid = prev != null && prev.ageHours <= MAX_PREV_AGE_HOURS
        if (!prevValid || prev!!.cityKey == top1.cityKey) {
            return Resolution(top1, viaContext = false, ambiguous = ambiguous)
        }
        val prevCand = candidates.firstOrNull { it.cityKey == prev.cityKey }
            ?: return Resolution(top1, viaContext = false, ambiguous = ambiguous)
        val prevMargin = prevCand.distanceKm - top1.distanceKm

        // 规则 3：误差圈达到公里级，坐标本身不可信，误差范围内沿用上次城市
        if (accuracyM != null && accuracyM >= POOR_ACCURACY_M && prevMargin <= accuracyKm) {
            return Resolution(prevCand, viaContext = true, ambiguous = true)
        }
        // 规则 2：边界模糊区粘住上次城市；top1 极近视为明确证据，不粘
        if (prevMargin < ambiguousMargin && top1.distanceKm > MIN_STICK_TOP1_KM) {
            return Resolution(prevCand, viaContext = true, ambiguous = true)
        }
        return Resolution(top1, viaContext = false, ambiguous = ambiguous)
    }
}
