package app.terndays.core

import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HalfSamplesTest {
    private val day = LocalDate.parse("2026-10-05")

    private fun punch(slot: Slot, city: String = "广州", hour: Int = 7) = Punch(
        localDate = day, slot = slot,
        epochMs = ZonedDateTime.parse("2026-10-05T%02d:00:00+08:00[Asia/Shanghai]".format(hour)).toInstant().toEpochMilli(),
        zoneId = "Asia/Shanghai", lat = 0.0, lng = 0.0, accuracyM = null,
        cityKey = "CN:$city", cityName = city, delayed = true, fromCache = true,
    )

    private fun correction(scope: OverrideScope, city: String = "北京") =
        DayOverride(day, "CN:$city", city, scope)

    @Test
    fun `下半天更正立即显示有效城市且保留原始定位`() {
        val m = punch(Slot.MORNING)
        val e = punch(Slot.EVENING, hour = 17)
        val corrections = listOf(correction(OverrideScope.EVENING))
        val (morning, evening) = DayCounting.halfSamples(m, e, null, corrections)
        assertEquals("广州", morning?.cityName)
        assertFalse(morning!!.manual)
        assertEquals("北京", evening?.cityName)
        assertTrue(evening!!.manual)
        assertSame(e, evening.punch)
        assertEquals("广州", evening.punch!!.cityName)
        assertTrue(evening.punch!!.delayed && evening.punch!!.fromCache)
        val attribution = DayCounting.attributeDay(day, m, e, null, corrections)
        assertEquals(listOf(morning.cityName, evening.cityName), attribution.shares.map { it.cityName })
        assertEquals(listOf(0.5, 0.5), attribution.shares.map { it.weight })
    }

    @Test
    fun `上半天更正不覆盖下半天`() {
        val (morning, evening) = DayCounting.halfSamples(
            punch(Slot.MORNING), punch(Slot.EVENING, "深圳", 17), null,
            listOf(correction(OverrideScope.MORNING)),
        )
        assertEquals("北京", morning?.cityName)
        assertTrue(morning!!.manual)
        assertEquals("深圳", evening?.cityName)
        assertFalse(evening!!.manual)
    }

    @Test
    fun `整天更正优先并更新两个半天`() {
        val corrections = listOf(correction(OverrideScope.MORNING, "深圳"), correction(OverrideScope.FULL))
        val (morning, evening) = DayCounting.halfSamples(punch(Slot.MORNING), null, null, corrections)
        assertEquals("北京", morning?.cityName)
        assertEquals("北京", evening?.cityName)
        assertTrue(morning!!.manual && evening!!.manual)
        assertNull(evening.punch)
        assertEquals(1.0, DayCounting.attributeDay(day, punch(Slot.MORNING), null, null, corrections).shares.single().weight)
    }

    @Test
    fun `仅手动记录也能显示且不会伪造定位`() {
        val (morning, evening) = DayCounting.halfSamples(null, null, null, listOf(correction(OverrideScope.EVENING)))
        assertNull(morning)
        assertEquals("北京", evening?.cityName)
        assertTrue(evening!!.manual)
        assertNull(evening.punch)
    }

    @Test
    fun `恢复自动和撤销更正后回到原始城市`() {
        val e = punch(Slot.EVENING, hour = 17)
        assertEquals("北京", DayCounting.halfSamples(null, e, null, listOf(correction(OverrideScope.EVENING))).second?.cityName)
        val restored = DayCounting.halfSamples(null, e, null, emptyList()).second!!
        assertEquals("广州", restored.cityName)
        assertFalse(restored.manual)
        assertSame(e, restored.punch)
    }

    @Test
    fun `首点按所在半天兜底且可以被更正`() {
        for (hour in listOf(11, 12)) {
            val extra = punch(Slot.EXTRA, hour = hour)
            val scope = if (hour < 12) OverrideScope.MORNING else OverrideScope.EVENING
            val (morning, evening) = DayCounting.halfSamples(null, null, extra, listOf(correction(scope)))
            val corrected = if (hour < 12) morning else evening
            assertEquals("北京", corrected?.cityName)
            assertSame(extra, corrected!!.punch)
            assertNull(if (hour < 12) evening else morning)
            val automatic = DayCounting.halfSamples(null, null, extra, emptyList())
            assertEquals("广州", (if (hour < 12) automatic.first else automatic.second)?.cityName)
        }
    }

    @Test
    fun `正式打卡优先于首点且空记录不显示城市`() {
        val e = punch(Slot.EVENING, "深圳", 17)
        val samples = DayCounting.halfSamples(null, e, punch(Slot.EXTRA, hour = 13), emptyList())
        assertSame(e, samples.second?.punch)
        assertEquals("深圳", samples.second?.cityName)
        assertEquals(null to null, DayCounting.halfSamples(null, null, null, emptyList()))
    }
}
