package app.terndays.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** 首点（EXTRA）兜底规则：正式早/晚点优先；首点按所在半天顶替缺失样本；一天最多 1 天。 */
class FirstPunchTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun punch(date: String, slot: Slot, city: String, hour: Int, minute: Int = 0): Punch {
        val d = LocalDate.parse(date)
        val epoch = ZonedDateTime.of(d.atTime(hour, minute), zone).toInstant().toEpochMilli()
        return Punch(
            localDate = d, slot = slot, epochMs = epoch, zoneId = zone.id,
            lat = 0.0, lng = 0.0, accuracyM = null, cityKey = "CN:$city", cityName = city,
        )
    }

    private val d = LocalDate.parse("2026-09-01")

    private fun attr(morning: Punch?, evening: Punch?, extra: Punch?) =
        DayCounting.attributeDay(d, morning, evening, extra, null)

    @Test
    fun `凌晨首点加正常早晚点 三个点仍只计一天且正式点优先`() {
        val a = attr(
            punch("2026-09-01", Slot.MORNING, "北京", 7),
            punch("2026-09-01", Slot.EVENING, "北京", 17),
            punch("2026-09-01", Slot.EXTRA, "廊坊", 5),
        )
        assertEquals(listOf(CityShare("CN:北京", "北京", 1.0)), a.shares)
    }

    @Test
    fun `下午首点加晚点 同属下半天不拆半天`() {
        // 16:00 装机在上海，17:00 晚点也在上海 → 上海 +1（不是 0.5+0.5）
        val same = attr(
            null,
            punch("2026-09-01", Slot.EVENING, "上海", 17),
            punch("2026-09-01", Slot.EXTRA, "上海", 16),
        )
        assertEquals(listOf(CityShare("CN:上海", "上海", 1.0)), same.shares)

        // 16:00 在上海、17:00 已到苏州 → 正式晚点优先，苏州 +1
        val moved = attr(
            null,
            punch("2026-09-01", Slot.EVENING, "苏州", 17),
            punch("2026-09-01", Slot.EXTRA, "上海", 16),
        )
        assertEquals(listOf(CityShare("CN:苏州", "苏州", 1.0)), moved.shares)
    }

    @Test
    fun `晚点缺失时下午首点顶替晚点`() {
        val a = attr(null, null, punch("2026-09-01", Slot.EXTRA, "上海", 16))
        assertEquals(listOf(CityShare("CN:上海", "上海", 1.0)), a.shares)
    }

    @Test
    fun `早点缺失时凌晨首点顶替早点 与晚点异城各半天`() {
        // 凌晨 5 点装机在城市 A，早点没打成，晚上到了城市 B → A 0.5 + B 0.5
        val a = attr(
            null,
            punch("2026-09-01", Slot.EVENING, "杭州", 17),
            punch("2026-09-01", Slot.EXTRA, "上海", 5),
        )
        assertEquals(
            listOf(CityShare("CN:上海", "上海", 0.5), CityShare("CN:杭州", "杭州", 0.5)),
            a.shares,
        )
    }

    @Test
    fun `下午首点加正常早点 首点顶晚点位`() {
        // 13:00 装机（下半天），早点已有 → 首点作为下半天样本参与
        val a = attr(
            punch("2026-09-01", Slot.MORNING, "北京", 7),
            null,
            punch("2026-09-01", Slot.EXTRA, "天津", 13),
        )
        assertEquals(
            listOf(CityShare("CN:北京", "北京", 0.5), CityShare("CN:天津", "天津", 0.5)),
            a.shares,
        )
    }

    @Test
    fun `首点参与年度统计`() {
        val punches = listOf(
            punch("2026-09-01", Slot.EXTRA, "上海", 16),
            punch("2026-09-02", Slot.MORNING, "上海", 7),
            punch("2026-09-02", Slot.EVENING, "上海", 17),
        )
        val stats = DayCounting.computeYearStats(2026, LocalDate.parse("2026-09-02"), punches, emptyList())
        assertEquals(2.0, stats.recordedDays)
        assertEquals("上海", stats.cities.single().cityName)
        assertEquals(2.0, stats.cities.single().days)
    }

    @Test
    fun `补记仍优先于首点`() {
        val a = DayCounting.attributeDay(
            d, null, null,
            punch("2026-09-01", Slot.EXTRA, "上海", 16),
            DayOverride(d, "CN:成都", "成都"),
        )
        assertEquals(listOf(CityShare("CN:成都", "成都", 1.0)), a.shares)
    }

    @Test
    fun `EXTRA 无延迟标记`() {
        assertEquals(false, PunchRules.isDelayed(java.time.LocalTime.of(23, 0), Slot.EXTRA))
    }
}
