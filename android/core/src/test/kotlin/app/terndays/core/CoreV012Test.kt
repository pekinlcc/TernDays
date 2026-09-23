package app.terndays.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v0.12 新增的 :core 纯函数:格式化、区间补记、锚点、行程、区间统计、地区汇总、阈值、备份。 */
class CoreV012Test {

    private fun punch(date: String, slot: Slot, key: String, name: String, hour: Int, zone: String = "Asia/Shanghai") =
        LocalDate.parse(date).let { d ->
            Punch(
                localDate = d, slot = slot,
                epochMs = ZonedDateTime.of(d.atTime(hour, 0), ZoneId.of(zone)).toInstant().toEpochMilli(),
                zoneId = zone, lat = 0.0, lng = 0.0, accuracyM = null, cityKey = key, cityName = name,
            )
        }

    private fun day(date: String, city: String, key: String = "CN:$city") = listOf(
        punch(date, Slot.MORNING, key, city, 7), punch(date, Slot.EVENING, key, city, 17),
    )

    // ---------- Fmt ----------

    @Test
    fun `星期、时刻、时区标签`() {
        assertEquals("周四", Fmt.weekdayCn(LocalDate.of(2026, 1, 1)))
        val tokyo = punch("2026-03-02", Slot.MORNING, "JP:40:Tokyo", "东京", 7, "Asia/Tokyo")
        assertEquals("07:00", Fmt.clock(tokyo))
        assertEquals("Asia/Tokyo(UTC+9)", Fmt.zoneLabel("Asia/Tokyo", tokyo.epochMs))
        assertEquals("Asia/Kolkata(UTC+5:30)", Fmt.zoneLabel("Asia/Kolkata", tokyo.epochMs))
        assertEquals("Europe/London(UTC)", Fmt.zoneLabel("Europe/London", tokyo.epochMs))
        // 系统区域是阿拉伯语等时,导出的时刻仍是 ASCII 数字
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("07:00", Fmt.clock(tokyo))
            assertEquals("Asia/Kolkata(UTC+5:30)", Fmt.zoneLabel("Asia/Kolkata", tokyo.epochMs))
        } finally {
            java.util.Locale.setDefault(saved)
        }
        // 夏令时按那一刻算
        val july = ZonedDateTime.of(2026, 7, 1, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals("Europe/London(UTC+1)", Fmt.zoneLabel("Europe/London", july))
        assertEquals("America/New_York(UTC-4)", Fmt.zoneLabel("America/New_York", july))
    }

    // ---------- Backfill ----------

    @Test
    fun `区间补记跨月且首末日可选半天`() {
        val plan = Backfill.planRange(
            LocalDate.of(2026, 1, 30), LocalDate.of(2026, 2, 2), "CN:成都", "成都",
            startScope = OverrideScope.EVENING, endScope = OverrideScope.MORNING,
        )
        assertEquals(
            listOf(OverrideScope.EVENING, OverrideScope.FULL, OverrideScope.FULL, OverrideScope.MORNING),
            plan.map { it.scope },
        )
        assertEquals(LocalDate.of(2026, 2, 2), plan.last().localDate)
        // 单日:取非整天的那一端
        assertEquals(
            OverrideScope.MORNING,
            Backfill.planRange(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5), "CN:成都", "成都",
                endScope = OverrideScope.MORNING).single().scope,
        )
        assertFailsWith<IllegalArgumentException> {
            Backfill.planRange(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5), "CN:成都", "成都",
                OverrideScope.EVENING, OverrideScope.MORNING)
        }
        assertFailsWith<IllegalArgumentException> {
            Backfill.planRange(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 4), "CN:成都", "成都")
        }
    }

    @Test
    fun `区间补记与已有更正互斥`() {
        val d1 = LocalDate.of(2026, 5, 1)
        val d2 = LocalDate.of(2026, 5, 2)
        val existing = listOf(
            DayOverride(d1, "CN:北京", "北京", OverrideScope.MORNING),
            DayOverride(d1, "CN:天津", "天津", OverrideScope.EVENING),
            DayOverride(d2, "CN:北京", "北京"),
        )
        val merged = Backfill.merge(
            existing,
            Backfill.planRange(d1, d2, "CN:上海", "上海", startScope = OverrideScope.EVENING, endScope = OverrideScope.MORNING),
        )
        // d1:上半天北京保留,下半天换成上海;d2:整天北京被上半天上海取代(整天与半天互斥)
        assertEquals(
            listOf(
                DayOverride(d1, "CN:北京", "北京", OverrideScope.MORNING),
                DayOverride(d1, "CN:上海", "上海", OverrideScope.EVENING),
                DayOverride(d2, "CN:上海", "上海", OverrideScope.MORNING),
            ),
            merged,
        )
    }

    @Test
    fun `补记更早的一天后开始日前移、无记录天数随之增加`() {
        val punches = day("2026-03-10", "上海")
        val before = DayCounting.computeYearStats(2026, LocalDate.parse("2026-03-12"), punches, emptyList())
        assertEquals(2, before.unrecordedDates.size) // 3/11、3/12
        val o = DayOverride(LocalDate.parse("2026-03-01"), "CN:杭州", "杭州")
        val after = DayCounting.computeYearStats(
            2026, LocalDate.parse("2026-03-12"), punches, listOf(o), earliestRecordDate = o.localDate,
        )
        assertEquals(LocalDate.parse("2026-03-01"), after.trackingSince)
        assertEquals(10, after.unrecordedDates.size) // 3/2–3/9 + 3/11、3/12
    }

    // ---------- Anchors ----------

    @Test
    fun `未来记录不能当锚点`() {
        val now = punch("2026-06-01", Slot.MORNING, "CN:上海", "上海", 7).epochMs
        val past = punch("2026-05-31", Slot.EVENING, "CN:上海", "上海", 17)
        val future = punch("2026-12-31", Slot.MORNING, "CN:北京", "北京", 7) // 时间曾被拨到年底
        val sticky = punch("2026-06-01", Slot.EXTRA, "CN:深圳", "深圳", 6).copy(viaContext = true)
        assertEquals(past, Anchors.pick(listOf(past, future, sticky), now))
        assertEquals(listOf(future), Anchors.future(listOf(past, future, sticky), now))
        assertNull(Anchors.pick(listOf(future), now))
    }

    // ---------- Stays ----------

    @Test
    fun `行程以跨城日分界、手动更正参与、无记录断段`() {
        val punches = day("2026-04-01", "上海") + day("2026-04-02", "上海") +
            listOf(
                punch("2026-04-03", Slot.MORNING, "CN:上海", "上海", 7),
                punch("2026-04-03", Slot.EVENING, "CN:杭州", "杭州", 17),
            ) + day("2026-04-04", "杭州") +
            // 4/5 无记录
            day("2026-04-06", "北京")
        val overrides = listOf(DayOverride(LocalDate.parse("2026-04-07"), "CN:北京", "北京")) // 手动补记一天
        val stats = DayCounting.computeRangeStats(
            LocalDate.parse("2026-04-01"), LocalDate.parse("2026-04-07"), LocalDate.parse("2026-04-07"),
            punches, overrides,
        )
        val stays = Stays.fold(stats.days)
        assertEquals(
            listOf(
                Triple("上海", "2026-04-01..2026-04-03", 2.5),
                Triple("杭州", "2026-04-03..2026-04-04", 1.5),
                Triple("北京", "2026-04-06..2026-04-07", 2.0),
            ),
            stays.map { Triple(it.cityName, "${it.from}..${it.to}", it.days) },
        )
        assertEquals("北京", Stays.current(stays, LocalDate.parse("2026-04-07"))?.cityName)
        assertEquals(2, Stays.current(stays, LocalDate.parse("2026-04-08"))?.spanDays)
        assertNull(Stays.current(stays, LocalDate.parse("2026-04-10")))
    }

    // ---------- 区间统计 ----------

    @Test
    fun `自然年区间与年度统计完全一致`() {
        val punches = day("2025-12-31", "广州") + day("2026-01-01", "上海") + day("2026-01-02", "深圳") +
            listOf(punch("2026-01-04", Slot.MORNING, "HK:香港", "香港", 7))
        val overrides = listOf(DayOverride(LocalDate.parse("2026-01-03"), "CN:杭州", "杭州", OverrideScope.EVENING))
        val today = LocalDate.parse("2026-01-04")
        val year = DayCounting.computeYearStats(2026, today, punches, overrides, nowHour = 9,
            earliestRecordDate = LocalDate.parse("2025-12-31"))
        val range = DayCounting.computeRangeStats(LocalDate.parse("2026-01-01"), today, today, punches, overrides,
            nowHour = 9, earliestRecordDate = LocalDate.parse("2025-12-31"))
        // 口径完全一致,只差「整年」标记(导出标题用)
        assertEquals(year, range.copy(wholeYear = true))
        assertFalse(range.wholeYear)
    }

    @Test
    fun `跨年区间与滚动 180 天`() {
        val punches = day("2025-12-30", "广州") + day("2026-01-02", "上海")
        val today = LocalDate.parse("2026-01-02")
        val cross = DayCounting.computeRangeStats(LocalDate.parse("2025-12-30"), today, today, punches, emptyList())
        assertEquals(mapOf("广州" to 1.0, "上海" to 1.0), cross.cities.associate { it.cityName to it.days })
        assertEquals(listOf(LocalDate.parse("2025-12-31"), LocalDate.parse("2026-01-01")), cross.unrecordedDates)
        assertEquals("2025-12-30 至 2026-01-02", Exporter.periodLabel(cross))

        // 自定义区间恰好从 1 月 1 日开始(只导出上半年):不能标成整年
        val h1 = DayCounting.computeRangeStats(
            LocalDate.parse("2025-01-01"), LocalDate.parse("2025-06-30"), today, punches, emptyList(),
        )
        assertEquals("2025-01-01 至 2025-06-30", Exporter.periodLabel(h1))
        assertEquals("2025 年", Exporter.periodLabel(DayCounting.computeYearStats(2025, today, punches, emptyList())))

        val (from, to) = Thresholds.range(Thresholds.Window.ROLLING_180, today)
        assertEquals(LocalDate.parse("2025-07-07"), from)
        val rolling = DayCounting.computeRangeStats(from, to, today, punches, emptyList())
        assertEquals(2.0, rolling.recordedDays)
        assertEquals(180, rolling.days.size)
    }

    // ---------- 地区汇总 ----------

    @Test
    fun `按国家地区汇总且港澳台分开`() {
        val punches = day("2026-02-01", "深圳") + day("2026-02-02", "广州") +
            day("2026-02-03", "香港", "HK:香港") + day("2026-02-04", "东京", "JP:40:Tokyo")
        val stats = DayCounting.computeYearStats(2026, LocalDate.parse("2026-02-04"), punches, emptyList())
        val regions = Regions.summarize(stats)
        assertEquals(listOf("CN", "HK", "JP"), regions.map { it.code })
        assertEquals(listOf("中国大陆", "中国香港", "日本"), regions.map { it.name })
        assertEquals(2.0, regions[0].days)
        assertEquals(2, regions[0].cities)
        assertEquals("XX", Regions.nameOf("XX"))
    }

    // ---------- 阈值 ----------

    @Test
    fun `阈值接近与达到、存储往返`() {
        val t = Thresholds.Threshold("CN", 183)
        assertEquals(Thresholds.Level.OK, Thresholds.status(t, 150.0).level)
        assertEquals(Thresholds.Level.NEAR, Thresholds.status(t, 165.0).level) // 剩 18 ≤ 18.3
        assertEquals(Thresholds.Level.REACHED, Thresholds.status(t, 183.0).level)
        assertEquals(0.0, Thresholds.status(t, 190.0).remaining)
        // 7 天以内的小阈值:刚设好、一天没用时不能已经「快到上限」
        val tiny = Thresholds.Threshold("JP", 5)
        assertEquals(Thresholds.Level.OK, Thresholds.status(tiny, 0.0).level)
        assertEquals(Thresholds.Level.NEAR, Thresholds.status(tiny, 3.0).level)
        assertEquals(Thresholds.Level.REACHED, Thresholds.status(tiny, 5.0).level)
        val small = Thresholds.Threshold("JP", 30, Thresholds.Window.ROLLING_180)
        assertEquals(Thresholds.Level.NEAR, Thresholds.status(small, 23.0).level) // 剩 7 天
        val list = listOf(t, small)
        assertEquals(list, Thresholds.decode(Thresholds.encode(list)))
        assertEquals(listOf(t), Thresholds.decode("CN:183:YEAR;bad;JP:0:YEAR;HK:30:WEEK;:5:YEAR"))
        assertTrue(Thresholds.decode(null).isEmpty())
        assertFalse(t.notifyKey(LocalDate.parse("2026-01-01")) == t.notifyKey(LocalDate.parse("2027-01-01")))
    }

    // ---------- 冲突计数 ----------

    @Test
    fun `导入冲突只在城市不同时计数`() {
        val a = punch("2026-02-01", Slot.MORNING, "CN:深圳", "深圳", 7)
        assertFalse(MergeRules.isConflict(a, a.copy(epochMs = a.epochMs + 1)))
        assertTrue(MergeRules.isConflict(a, a.copy(cityKey = "HK:香港", cityName = "香港")))
        val o = DayOverride(a.localDate, "CN:深圳", "深圳")
        assertTrue(MergeRules.isConflict(o, o.copy(cityKey = "CN:广州", cityName = "广州")))
    }

    // ---------- 备份 ----------

    @Test
    fun `PBKDF2 与 RFC 7914 测试向量一致`() {
        val dk = Backup.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1, 64)
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            dk.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `备份往返、口令错误明确失败`() {
        val json = MigrationCodec.toJson(3, 1L, day("2026-02-01", "深圳"), emptyList())
        val file = Backup.seal("correct horse", json.toByteArray(), iterations = 1000)
        assertTrue(Backup.isBackup(file))
        assertEquals(json, String(Backup.open("correct horse", file)))
        val wrong = assertFailsWith<IllegalArgumentException> { Backup.open("wrong horse", file) }
        assertEquals("口令不对,或备份文件已损坏", wrong.message)
        assertFailsWith<IllegalArgumentException> { Backup.open("correct horse", "hello".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { Backup.seal("short", json.toByteArray()) }
        // 恢复走 MergeRules:本机已有的整天更正不被半天覆盖
        assertFalse(MergeRules.shouldImportOverride(setOf(OverrideScope.FULL), OverrideScope.MORNING))
    }

    // ---------- 导出:时区列与行程段 ----------

    @Test
    fun `导出带时区列与行程段`() {
        val punches = day("2026-03-01", "上海") +
            listOf(punch("2026-03-02", Slot.MORNING, "JP:40:Tokyo", "东京", 9, "Asia/Tokyo"))
        val stats = DayCounting.computeYearStats(2026, LocalDate.parse("2026-03-02"), punches, emptyList())
        val csv = Exporter.exportCsv(stats, punches, includeSummary = false, includeDaily = true, includeStays = true)
        assertTrue(csv.contains("2026-03-01,周日,07:00,上海,17:00,上海,,上海 +1,,Asia/Shanghai(UTC+8)"))
        assertTrue(csv.contains("2026-03-02,周一,09:00,东京,,,,东京 +1,,Asia/Tokyo(UTC+9)"))
        assertTrue(csv.contains("# 行程段 · 2026 年\r\n城市,开始,结束,天数\r\n上海,2026-03-01,2026-03-01,1\r\n东京,2026-03-02,2026-03-02,1"))
    }

    // ---------- 小组件外观 ----------

    @Test
    fun `外观名称与说明下沉 core`() {
        assertEquals(listOf("素面", "系统材质", "品牌渐变"), WidgetStyle.entries.map { it.label })
        assertTrue(WidgetStyle.entries.all { it.androidHint.isNotBlank() })
    }
}
