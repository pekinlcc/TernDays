package app.terndays.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WidgetSummaryTest {

    private fun punch(date: String, slot: Slot, city: String) = Punch(
        localDate = LocalDate.parse(date), slot = slot, epochMs = 0, zoneId = "Asia/Shanghai",
        lat = 0.0, lng = 0.0, accuracyM = null, cityKey = "CN:$city", cityName = city,
    )

    @Test
    fun `Top 城市按天数降序且带半天格式`() {
        val punches = listOf(
            punch("2026-08-29", Slot.MORNING, "上海"), punch("2026-08-29", Slot.EVENING, "上海"),
            punch("2026-08-30", Slot.MORNING, "上海"), punch("2026-08-30", Slot.EVENING, "上海"),
            punch("2026-08-31", Slot.MORNING, "北京"), punch("2026-08-31", Slot.EVENING, "北京"),
            punch("2026-09-01", Slot.MORNING, "北京"), punch("2026-09-01", Slot.EVENING, "杭州"),
        )
        val today = LocalDate.parse("2026-09-01")
        val stats = DayCounting.computeYearStats(2026, today, punches, emptyList())
        val m = WidgetSummary.build(stats)

        assertEquals("2026 年", m.yearLabel)
        assertEquals(
            listOf("上海" to "2", "北京" to "1.5", "杭州" to "0.5"),
            m.topCities.map { it.name to it.days },
        )
    }

    @Test
    fun `空数据与 topN 截断`() {
        val today = LocalDate.parse("2026-09-01")
        val empty = WidgetSummary.build(DayCounting.computeYearStats(2026, today, emptyList(), emptyList()))
        assertTrue(empty.topCities.isEmpty())

        val punches = (1..5).flatMap { i ->
            listOf(
                punch("2026-03-0$i", Slot.MORNING, "城$i"),
                punch("2026-03-0$i", Slot.EVENING, "城$i"),
            )
        }
        val stats = DayCounting.computeYearStats(2026, today, punches, emptyList())
        assertEquals(3, WidgetSummary.build(stats).topCities.size)
        assertEquals(5, WidgetSummary.build(stats, topN = 10).topCities.size)
    }
}
