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
        assertTrue(csv.contains("2026-01-01,周四,07:02,上海,17:05,上海,,上海 +1,"))
        assertTrue(csv.contains("2026-01-02,周五,07:00,上海,17:30,深圳,,上海 +0.5 / 深圳 +0.5,"))
        assertTrue(csv.contains("2026-01-03,周六,,,,,,无记录,"))
    }

    @Test
    fun `汇总小表数值在第二列且进行中单独说明`() {
        // 1/3 是今天、只有早点 → 先计 0.5,不进「半天数」
        val today = punch("2026-01-03", Slot.MORNING, "深圳", 7, 1)
        val s2 = DayCounting.computeYearStats(
            2026, LocalDate.parse("2026-01-03"), punches + today, emptyList(), nowHour = 9,
        )
        val csv = Exporter.exportCsv(
            s2, punches + today, includeSummary = true, includeDaily = true,
            exportedAt = java.time.LocalDateTime.of(2026, 1, 3, 9, 30),
        )
        assertTrue(csv.contains("城市,天数,全天数,半天数,备注"))
        assertTrue(csv.contains("深圳,1,0,1,含今天进行中的半天"))
        assertTrue(csv.contains("\r\n项目,数值\r\n"))
        assertTrue(csv.contains("\r\n合计（天）,2.5\r\n"))
        assertTrue(csv.contains("\r\n无记录天数,0\r\n"))
        assertTrue(csv.contains("统计区间,2026-01-01 至 2026-01-03"))
        assertTrue(csv.contains("开始记录日,2026-01-01"))
        assertTrue(csv.contains("导出时间,2026-01-03 09:30"))
        assertTrue(csv.contains("2026-01-03,周六,07:01,深圳,,,,深圳 +0.5,今天进行中，先计半天"))

        // 今天还一条都没有:写「今天进行中（待记录）」,不写「无记录」
        val s3 = DayCounting.computeYearStats(2026, LocalDate.parse("2026-01-03"), punches, emptyList(), nowHour = 9)
        val csv3 = Exporter.exportCsv(s3, punches, includeSummary = false, includeDaily = true)
        assertTrue(csv3.contains("2026-01-03,周六,,,,,,今天进行中（待记录）,"))
    }

    @Test
    fun `还没有任何记录时明细只写一行`() {
        val empty = DayCounting.computeYearStats(2026, LocalDate.parse("2026-03-01"), emptyList(), emptyList())
        assertTrue(Exporter.dailyRows(empty, emptyList()).isEmpty())
        val csv = Exporter.exportCsv(empty, emptyList(), includeSummary = true, includeDaily = true)
        assertTrue(csv.contains("开始记录日,尚未开始记录"))
        assertTrue(csv.endsWith("日期,星期,早打卡,早城市,晚打卡,晚城市,首点,计入,备注,时区\r\n尚未开始记录"))
        assertTrue(!csv.contains("无记录,"))
    }

    @Test
    fun `手动只标在更正的那一份上`() {
        val d = LocalDate.parse("2026-01-02")
        val eo = DayOverride(d, "CN:广州", "广州", OverrideScope.EVENING)
        val s2 = DayCounting.computeYearStats(2026, LocalDate.parse("2026-01-02"), punches, listOf(eo))
        val csv = Exporter.exportCsv(s2, punches, includeSummary = false, includeDaily = true)
        assertTrue(csv.contains("上海 +0.5 / 广州 +0.5（手动）,手动更正/补记"))
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
    fun `落盘样例文件供外部工具校验`() {
        val dir = java.io.File("build/sample-exports").apply { mkdirs() }
        val xlsx = java.io.File(dir, "sample.xlsx")
        xlsx.writeBytes(Exporter.exportXlsx(stats, punches, includeSummary = true, includeDaily = true))
        val csv = java.io.File(dir, "sample.csv")
        csv.writeText(Exporter.exportCsv(stats, punches, includeSummary = true, includeDaily = true))
        assertTrue(xlsx.length() > 800)
        assertTrue(csv.length() > 200)
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
