package app.terndays.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetSummaryTest {

    private fun punch(date: String, slot: Slot, city: String, epochMs: Long = 0) = Punch(
        localDate = LocalDate.parse(date), slot = slot, epochMs = epochMs, zoneId = "Asia/Shanghai",
        lat = 0.0, lng = 0.0, accuracyM = null, cityKey = "CN:$city", cityName = city,
    )

    @Test
    fun `摘要内容与排序`() {
        val punches = listOf(
            punch("2026-08-30", Slot.MORNING, "北京"), punch("2026-08-30", Slot.EVENING, "北京"),
            punch("2026-08-31", Slot.MORNING, "上海"), punch("2026-08-31", Slot.EVENING, "上海"),
            punch("2026-08-29", Slot.MORNING, "上海"), punch("2026-08-29", Slot.EVENING, "上海"),
            punch("2026-09-01", Slot.MORNING, "北京"),
        )
        val today = LocalDate.parse("2026-09-01")
        val stats = DayCounting.computeYearStats(2026, today, punches, emptyList())
        val m = WidgetSummary.build(stats, today, punches)

        assertEquals("2026 年", m.yearLabel)
        assertEquals("4", m.bigDays)
        assertEquals("天已记录 · 2 城", m.statsLabel)
        assertEquals("今日 · 早 北京 ✓ · 晚 待记录", m.todayLabel)
        assertEquals(listOf("上海" to "2", "北京" to "2"), m.topCities.map { it.name to it.days })
    }

    @Test
    fun `早晚都缺与都全`() {
        val today = LocalDate.parse("2026-09-01")
        val none = WidgetSummary.build(
            DayCounting.computeYearStats(2026, today, emptyList(), emptyList()), today, emptyList(),
        )
        assertEquals("今日 · 早 待记录 · 晚 待记录", none.todayLabel)
        assertEquals("0", none.bigDays)
        assertEquals(0, none.topCities.size)

        val punches = listOf(
            punch("2026-09-01", Slot.MORNING, "上海"),
            punch("2026-09-01", Slot.EVENING, "深圳"),
        )
        val both = WidgetSummary.build(
            DayCounting.computeYearStats(2026, today, punches, emptyList()), today, punches,
        )
        assertEquals("今日 · 早 上海 ✓ · 晚 深圳 ✓", both.todayLabel)
    }

    @Test
    fun `非当年不显示今日行且 topN 截断`() {
        val punches = (1..5).map { i ->
            listOf(
                punch("2025-03-0$i", Slot.MORNING, "城$i"),
                punch("2025-03-0$i", Slot.EVENING, "城$i"),
            )
        }.flatten()
        val stats = DayCounting.computeYearStats(2025, LocalDate.parse("2026-09-01"), punches, emptyList())
        val m = WidgetSummary.build(stats, LocalDate.parse("2026-09-01"), punches, topN = 3)
        assertNull(m.todayLabel)
        assertEquals(3, m.topCities.size)
    }
}
