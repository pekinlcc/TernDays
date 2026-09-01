package app.terndays.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 打卡时间规则。所有时刻都以「手机当前时区的本地时间」为准；
 * 时区变更后重算下一次闹钟即可（调用方监听系统广播）。
 */
object PunchRules {
    val MORNING_TARGET: LocalTime = LocalTime.of(7, 0)
    val EVENING_TARGET: LocalTime = LocalTime.of(17, 0)

    /** 早点补捕窗口 [07:00, 12:00)；晚点补捕窗口 [17:00, 24:00)。 */
    val MORNING_WINDOW_END: LocalTime = LocalTime.of(12, 0)

    val DELAY_TOLERANCE: Duration = Duration.ofMinutes(15)

    fun targetTime(slot: Slot): LocalTime? = when (slot) {
        Slot.MORNING -> MORNING_TARGET
        Slot.EVENING -> EVENING_TARGET
        Slot.EXTRA -> null
    }

    /** EXTRA 点按捕获时刻归属的半天：true = 上半天（可兜底早点），false = 下半天（可兜底晚点）。 */
    fun isMorningHalf(time: LocalTime): Boolean = time.hour < 12

    /** 此刻捕获的定位属于哪个时段；不在任何窗口内返回 null。 */
    fun slotInWindow(time: LocalTime): Slot? = when {
        !time.isBefore(MORNING_TARGET) && time.isBefore(MORNING_WINDOW_END) -> Slot.MORNING
        !time.isBefore(EVENING_TARGET) -> Slot.EVENING
        else -> null
    }

    fun isDelayed(time: LocalTime, slot: Slot): Boolean =
        targetTime(slot)?.let { time.isAfter(it.plus(DELAY_TOLERANCE)) } ?: false

    /** 下一次打卡闹钟时刻（严格晚于 now；处理跨天与时区，DST 间隙由 ZonedDateTime 自动顺延）。 */
    fun nextPunchTime(now: ZonedDateTime): ZonedDateTime {
        val zone = now.zone
        val candidates = listOf(
            now.toLocalDate().atTime(MORNING_TARGET),
            now.toLocalDate().atTime(EVENING_TARGET),
            now.toLocalDate().plusDays(1).atTime(MORNING_TARGET),
        )
        for (c in candidates) {
            val z = c.atZone(zone)
            if (z.isAfter(now)) return z
        }
        // 理论到不了这里；兜底次日早点
        return now.toLocalDate().plusDays(1).atTime(MORNING_TARGET).atZone(zone)
    }

    /**
     * 打开应用/闹钟延迟触发时需要补打的时段：
     * 当前处于某窗口内且该时段还没有记录 → 返回该时段；否则 null。
     */
    fun slotToBackfill(now: LocalDateTime, hasMorning: Boolean, hasEvening: Boolean): Slot? =
        when (slotInWindow(now.toLocalTime())) {
            Slot.MORNING -> if (hasMorning) null else Slot.MORNING
            Slot.EVENING -> if (hasEvening) null else Slot.EVENING
            Slot.EXTRA, null -> null // slotInWindow 只会返回早/晚
        }
}
