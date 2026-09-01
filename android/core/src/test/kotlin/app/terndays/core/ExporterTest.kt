package app.terndays.core

import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExporterTest {

    private fun punch(date: String, slot: Slot, city: String, hour: Int, minute: Int): Punch {
        val d = LocalDate.parse(date)
        val zdt = ZonedDateTime.of(d.atTime(hour, minute), ZoneId.of("Asia/Shanghai"))
        return Punch(
            localDate = d, slot = slot, epochMs = zdt.toInstant().toEpochMilli(), zoneId = "Asia/Shanghai",
            lat = 31.0, lng = 121.0, accuracyM = 20.0, cityKey = "CN:$city", cityName = city,
        )
    }

    private val punches = listOf(
        punch("2026-01-01", Slot.MORNING, "上海", 7, 2),
        punch("2026-01-01", Slot.EVENING, "上海", 17, 5),
        punch("2026-01-02", Slot.MORNING, "上海", 7, 0),
        punch("2026-01-02", Slot.EVENING, "深圳", 17, 30),
    )
    private val stats = DayCounting.computeYearStats(2026, LocalDate.parse("2026-01-03"), punches, emptyList())

    @Test
    fun `csv 内容`() {
        val csv = Exporter.exportCsv(stats, punches, includeSummary = true, includeDaily = true)
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("# 城市汇总 · 2026 年"))
        assertTrue(csv.contains("上海,1.5,1,1"))
        assertTrue(csv.contains("深圳,0.5,0,1"))
        assertTrue(csv.contains("2026-01-01,周四,07:02,上海,17:05,上海,上海 +1,"))
        assertTrue(csv.contains("2026-01-02,周五,07:00,上海,17:30,深圳,上海 +0.5 / 深圳 +0.5,"))
        assertTrue(csv.contains("2026-01-03,周六,,,,,无记录,"))
    }

    @Test
    fun `xlsx 结构可解包`() {
        val bytes = Exporter.exportXlsx(stats, punches, includeSummary = true, includeDaily = true)
        val entries = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                entries[e.name] = zip.readBytes().toString(Charsets.UTF_8)
                e = zip.nextEntry
            }
        }
        assertEquals(
            setOf(
                "[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels",
                "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml",
            ),
            entries.keys,
        )
        assertTrue(entries["xl/workbook.xml"]!!.contains("城市汇总"))
        assertTrue(entries["xl/workbook.xml"]!!.contains("每日明细"))
        assertTrue(entries["xl/worksheets/sheet1.xml"]!!.contains("<t xml:space=\"preserve\">上海</t>"))
        assertTrue(entries["xl/worksheets/sheet1.xml"]!!.contains("<v>1.5</v>"))
        assertTrue(entries["xl/worksheets/sheet2.xml"]!!.contains("07:02"))
    }

    @Test
    fun `csv 转义`() {
        val tricky = listOf(
            Punch(
                localDate = LocalDate.parse("2026-02-01"), slot = Slot.MORNING,
                epochMs = ZonedDateTime.of(2026, 2, 1, 7, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
                zoneId = "Asia/Shanghai", lat = 0.0, lng = 0.0, accuracyM = null,
                cityKey = "US:XX:A,B", cityName = "A,B\"C",
            ),
        )
        val s = DayCounting.computeYearStats(2026, LocalDate.parse("2026-02-01"), tricky, emptyList())
        val csv = Exporter.exportCsv(s, tricky, includeSummary = true, includeDaily = false)
        assertTrue(csv.contains("\"A,B\"\"C\""))
    }
}
