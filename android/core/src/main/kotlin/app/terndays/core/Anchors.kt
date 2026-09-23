package app.terndays.core

/**
 * 行程连续性锚点的选取(实时打卡用;重放另见 [HistoryReplay])。
 *
 * 系统时间被拨乱过(拨到未来打了卡、又拨回来)时,那条「未来」记录会一直是
 * 「最近一条」,此后每次打卡都拿它当锚——链龄算出负数,粘滞链永不过期。
 * 所以锚点只从「不晚于现在」的记录里挑。
 */
object Anchors {

    /** 时钟校准的容差:手机间几十秒的偏差不算「未来」 */
    const val SKEW_MS = 5 * 60_000L

    /** 最近一条可作锚的打卡:解析成功、非改判、且不晚于现在。 */
    fun pick(punches: List<Punch>, nowMs: Long): Punch? =
        punches.asSequence()
            .filter { it.cityKey != "unknown" && !it.viaContext && it.epochMs <= nowMs + SKEW_MS }
            .maxByOrNull { it.epochMs }

    /** 打卡时刻晚于现在的记录(系统时间曾被拨到未来),供界面提示用户确认或清理。 */
    fun future(punches: List<Punch>, nowMs: Long): List<Punch> =
        punches.filter { it.epochMs > nowMs + SKEW_MS }.sortedBy { it.epochMs }
}
