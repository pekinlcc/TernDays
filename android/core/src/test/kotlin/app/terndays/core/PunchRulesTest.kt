package app.terndays.core

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PunchRulesTest {

    private val sh = ZoneId.of("Asia/Shanghai")

    @Test
    fun `窗口判断`() {
        assertEquals(Slot.MORNING, PunchRules.slotInWindow(LocalTime.of(7, 0)))
        assertEquals(Slot.MORNING, PunchRules.slotInWindow(LocalTime.of(11, 59)))
        assertNull(PunchRules.slotInWindow(LocalTime.of(12, 0)))
        assertNull(PunchRules.slotInWindow(LocalTime.of(6, 59)))
        assertNull(PunchRules.slotInWindow(LocalTime.of(16, 59)))
        assertEquals(Slot.EVENING, PunchRules.slotInWindow(LocalTime.of(17, 0)))
        assertEquals(Slot.EVENING, PunchRules.slotInWindow(LocalTime.of(23, 59)))
    }

    @Test
    fun `延迟标记`() {
        assertEquals(false, PunchRules.isDelayed(LocalTime.of(7, 14), Slot.MORNING))
        assertEquals(true, PunchRules.isDelayed(LocalTime.of(7, 16), Slot.MORNING))
        assertEquals(true, PunchRules.isDelayed(LocalTime.of(18, 0), Slot.EVENING))
    }

    @Test
    fun `下一次闹钟`() {
        fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 9, 1, h, m, 0, 0, sh)
        assertEquals(at(7, 0), PunchRules.nextPunchTime(at(6, 30)))
        assertEquals(at(17, 0), PunchRules.nextPunchTime(at(7, 0)))   // 严格晚于 now
        assertEquals(at(17, 0), PunchRules.nextPunchTime(at(12, 0)))
        assertEquals(
            ZonedDateTime.of(2026, 9, 2, 7, 0, 0, 0, sh),
            PunchRules.nextPunchTime(at(17, 0)),
        )
        assertEquals(
            ZonedDateTime.of(2026, 9, 2, 7, 0, 0, 0, sh),
            PunchRules.nextPunchTime(at(23, 30)),
        )
    }

    @Test
    fun `时区变更后按新时区排闹钟`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val nowInTokyo = ZonedDateTime.of(2026, 9, 1, 8, 30, 0, 0, tokyo)
        val next = PunchRules.nextPunchTime(nowInTokyo)
        assertEquals(tokyo, next.zone)
        assertEquals(LocalTime.of(17, 0), next.toLocalTime())
    }

    @Test
    fun `补打判断`() {
        val morningWindow = LocalDateTime.of(2026, 9, 1, 9, 0)
        assertEquals(Slot.MORNING, PunchRules.slotToBackfill(morningWindow, hasMorning = false, hasEvening = false))
        assertNull(PunchRules.slotToBackfill(morningWindow, hasMorning = true, hasEvening = false))

        val noon = LocalDateTime.of(2026, 9, 1, 13, 0)
        assertNull(PunchRules.slotToBackfill(noon, hasMorning = false, hasEvening = false))

        val evening = LocalDateTime.of(2026, 9, 1, 20, 0)
        assertEquals(Slot.EVENING, PunchRules.slotToBackfill(evening, hasMorning = false, hasEvening = false))
        assertNull(PunchRules.slotToBackfill(evening, hasMorning = false, hasEvening = true))
    }

    @Test
    fun `还有机会补上的时段`() {
        // 早点窗口 [07,12):未过 12 点两个半天都还有机会
        assertEquals(setOf(Slot.MORNING, Slot.EVENING), PunchRules.pendingSlots(6))
        assertEquals(setOf(Slot.MORNING, Slot.EVENING), PunchRules.pendingSlots(11))
        // 过了 12 点早点补不上了;晚点窗口开到当天结束
        assertEquals(setOf(Slot.EVENING), PunchRules.pendingSlots(12))
        assertEquals(setOf(Slot.EVENING), PunchRules.pendingSlots(23))
    }
}
