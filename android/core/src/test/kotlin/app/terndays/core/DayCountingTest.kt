package app.terndays.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayCountingTest {

    private fun punch(
        date: String,
        slot: Slot,
        city: String,
        epochMs: Long = 0,
        zone: String = "Asia/Shanghai",
    ) = Punch(
        localDate = LocalDate.parse(date), slot = slot, epochMs = epochMs, zoneId = zone,
        lat = 0.0, lng = 0.0, accuracyM = null, cityKey = "CN:$city", cityName = city,
    )

    @Test
    fun `早晚同城计一天`() {
        val attr = DayCounting.attributeDay(
            LocalDate.parse("2026-08-24"),
            punch("2026-08-24", Slot.MORNING, "上海"),
            punch("2026-08-24", Slot.EVENING, "上海"),
            null,
            null,
        )
        assertEquals(listOf(CityShare("CN:上海", "上海", 1.0)), attr.shares)
    }

    @Test
    fun `早晚异城各半天`() {
        val attr = DayCounting.attributeDay(
            LocalDate.parse("2026-08-25"),
            punch("2026-08-25", Slot.MORNING, "上海"),
            punch("2026-08-25", Slot.EVENING, "深圳"),
            null,
            null,
        )
        assertEquals(2, attr.shares.size)
        assertEquals(0.5, attr.shares[0].weight)
        assertEquals(0.5, attr.shares[1].weight)
        assertEquals("上海", attr.shares[0].cityName)
        assertEquals("深圳", attr.shares[1].cityName)
    }

    @Test
    fun `单点计整天`() {
        val morningOnly = DayCounting.attributeDay(
            LocalDate.parse("2026-08-26"), punch("2026-08-26", Slot.MORNING, "北京"), null, null, null,
        )
        assertEquals(listOf(CityShare("CN:北京", "北京", 1.0)), morningOnly.shares)
        val eveningOnly = DayCounting.attributeDay(
            LocalDate.parse("2026-08-26"), null, punch("2026-08-26", Slot.EVENING, "北京"), null, null,
        )
        assertEquals(listOf(CityShare("CN:北京", "北京", 1.0)), eveningOnly.shares)
    }

    @Test
    fun `无记录为空且补记优先`() {
        val none = DayCounting.attributeDay(LocalDate.parse("2026-08-27"), null, null, null, null)
        assertTrue(none.shares.isEmpty())

        val overridden = DayCounting.attributeDay(
            LocalDate.parse("2026-08-27"),
            punch("2026-08-27", Slot.MORNING, "北京"),
            null,
            null,
            DayOverride(LocalDate.parse("2026-08-27"), "CN:杭州", "杭州"),
        )
        assertEquals(listOf(CityShare("CN:杭州", "杭州", 1.0)), overridden.shares)
        assertTrue(overridden.manual)
    }

    @Test
    fun `年度统计聚合与排序`() {
        val punches = listOf(
            punch("2026-01-01", Slot.MORNING, "北京", 1), punch("2026-01-01", Slot.EVENING, "北京", 2),
            punch("2026-01-02", Slot.MORNING, "北京", 3), punch("2026-01-02", Slot.EVENING, "上海", 4),
            punch("2026-01-03", Slot.EVENING, "上海", 5),
        )
        val overrides = listOf(DayOverride(LocalDate.parse("2026-01-04"), "CN:上海", "上海"))
        val stats = DayCounting.computeYearStats(2026, LocalDate.parse("2026-01-05"), punches, overrides)

        assertEquals(4, stats.recordedDays)
        assertEquals(listOf(LocalDate.parse("2026-01-05")), stats.unrecordedDates)
        assertEquals(2, stats.cities.size)
        // 上海 0.5 + 1 + 1 = 2.5 排第一；北京 1 + 0.5 = 1.5
        assertEquals("上海", stats.cities[0].cityName)
        assertEquals(2.5, stats.cities[0].days)
        assertEquals(2, stats.cities[0].fullDays)
        assertEquals(1, stats.cities[0].halfDays)
        assertEquals("北京", stats.cities[1].cityName)
        assertEquals(1.5, stats.cities[1].days)
    }

    @Test
    fun `同一时段重复记录取最早`() {
        // 向西飞：同一本地日期出现两个「早点」
        val punches = listOf(
            punch("2026-03-01", Slot.MORNING, "东京", epochMs = 100),
            punch("2026-03-01", Slot.MORNING, "北京", epochMs = 200),
        )
        val stats = DayCounting.computeYearStats(2026, LocalDate.parse("2026-03-01"), punches, emptyList())
        assertEquals("东京", stats.days[LocalDate.parse("2026-03-01")]!!.shares.single().cityName)
    }

    @Test
    fun `跨年与未来日期边界`() {
        // 全年零记录:开始使用之前的日子不算「漏记」,无记录列表为空
        val stats = DayCounting.computeYearStats(2025, LocalDate.parse("2026-09-01"), emptyList(), emptyList())
        assertEquals(LocalDate.parse("2025-12-31"), stats.lastDate)
        assertTrue(stats.unrecordedDates.isEmpty())

        val empty = DayCounting.computeYearStats(2027, LocalDate.parse("2026-09-01"), emptyList(), emptyList())
        assertEquals(0, empty.recordedDays)
        assertTrue(empty.days.isEmpty())
    }

    @Test
    fun `无记录从首条记录之日起算`() {
        val p = punch("2026-03-10", Slot.MORNING, "杭州")
        val stats = DayCounting.computeYearStats(2026, LocalDate.parse("2026-03-13"), listOf(p), emptyList())
        // 1/1–3/9 不算漏记;3/11、3/12、3/13 算
        assertEquals(listOf("2026-03-11", "2026-03-12", "2026-03-13").map(LocalDate::parse), stats.unrecordedDates)
        // 手动补记更早日期后,起算点前移
        val o = DayOverride(LocalDate.parse("2026-03-08"), "CN:杭州", "杭州")
        val stats2 = DayCounting.computeYearStats(2026, LocalDate.parse("2026-03-13"), listOf(p), listOf(o))
        assertEquals(LocalDate.parse("2026-03-09"), stats2.unrecordedDates.first())
    }

    @Test
    fun `半天更正只替换对应样本`() {
        val d = LocalDate.parse("2026-08-25")
        val m = punch("2026-08-25", Slot.MORNING, "深圳")
        val e = punch("2026-08-25", Slot.EVENING, "香港")
        // 晚点被误判成香港,只更正下半天 → 深圳全天 1 天
        val eo = DayOverride(d, "CN:深圳", "深圳", OverrideScope.EVENING)
        val a1 = DayCounting.attributeDay(d, m, e, null, listOf(eo))
        assertEquals(listOf(CityShare("CN:深圳", "深圳", 1.0)), a1.shares)
        assertTrue(a1.manual)
        // 只更正上半天为北京 → 北京 0.5 + 香港 0.5,保住拆分
        val mo = DayOverride(d, "CN:北京", "北京", OverrideScope.MORNING)
        val a2 = DayCounting.attributeDay(d, m, e, null, listOf(mo))
        assertEquals(
            listOf(CityShare("CN:北京", "北京", 0.5), CityShare("CN:香港", "香港", 0.5)),
            a2.shares,
        )
        // FULL 与半天并存时 FULL 优先(存储层本会互斥,算法兜底)
        val full = DayOverride(d, "CN:上海", "上海", OverrideScope.FULL)
        val a3 = DayCounting.attributeDay(d, m, e, null, listOf(full, mo))
        assertEquals(listOf(CityShare("CN:上海", "上海", 1.0)), a3.shares)
        // 无打卡的日子,单独一个半天更正 → 单样本整天
        val a4 = DayCounting.attributeDay(d, null, null, null, listOf(eo))
        assertEquals(listOf(CityShare("CN:深圳", "深圳", 1.0)), a4.shares)
    }

    @Test
    fun `天数格式化`() {
        assertEquals("152", DayCounting.formatDays(152.0))
        assertEquals("38.5", DayCounting.formatDays(38.5))
        assertEquals("0", DayCounting.formatDays(0.0))
    }
}
