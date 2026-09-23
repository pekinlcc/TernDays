package app.terndays.core

import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 双端口径对齐:把一组有代表性的输入与 :core 的输出写成 fixtures/core-cases.json,
 * iOS 端由 ios/FixtureCheck(CI 的 macOS job 编译运行)读同一份文件、用 Swift 实现重算并逐项比对。
 *
 * 本测试同时防漂移:算法变了而 fixture 没更新,这里就会失败。
 * 更新方法:UPDATE_FIXTURES=1 跑一遍 :core:test,把新文件一起提交。
 */
class FixtureParityTest {

    private fun p(date: String, slot: Slot, key: String, name: String, hour: Int, minute: Int = 0,
                  zone: String = "Asia/Shanghai", via: Boolean = false, acc: Double? = 25.0) =
        LocalDate.parse(date).let { d ->
            Punch(
                localDate = d, slot = slot,
                epochMs = ZonedDateTime.of(d.atTime(hour, minute), ZoneId.of(zone)).toInstant().toEpochMilli(),
                zoneId = zone, lat = 22.5, lng = 114.0, accuracyM = acc, cityKey = key, cityName = name,
                viaContext = via,
            )
        }

    private data class Case(
        val name: String,
        val today: String,
        val nowHour: Int?,
        val earliest: String?,
        val year: Int? = null,
        val from: String? = null,
        val to: String? = null,
        val punches: List<Punch>,
        val overrides: List<DayOverride> = emptyList(),
    )

    private val cases = listOf(
        Case(
            name = "年度:跨城日、半天/整天更正、首点兜底、进行中的今天",
            today = "2026-03-06", nowHour = 9, earliest = "2026-03-01", year = 2026,
            punches = listOf(
                p("2026-03-01", Slot.EXTRA, "CN:深圳", "深圳", 15, 20),
                p("2026-03-02", Slot.MORNING, "CN:深圳", "深圳", 7, 2),
                p("2026-03-02", Slot.EVENING, "HK:香港", "香港", 17, 40),
                p("2026-03-03", Slot.MORNING, "HK:香港", "香港", 7, 0),
                p("2026-03-03", Slot.EVENING, "HK:香港", "香港", 17, 1, via = true),
                p("2026-03-05", Slot.MORNING, "CN:广州", "广州", 8, 30),
                p("2026-03-05", Slot.EVENING, "CN:广州", "广州", 17, 0),
                p("2026-03-06", Slot.MORNING, "CN:深圳", "深圳", 7, 0),
            ),
            overrides = listOf(
                DayOverride(LocalDate.parse("2026-03-03"), "CN:深圳", "深圳", OverrideScope.EVENING),
                DayOverride(LocalDate.parse("2026-03-04"), "CN:东莞", "东莞"),
            ),
        ),
        Case(
            name = "元旦凌晨:今年还空着,往年有记录",
            today = "2027-01-01", nowHour = 3, earliest = "2026-12-30", year = 2027,
            punches = listOf(
                p("2026-12-30", Slot.MORNING, "CN:上海", "上海", 7),
                p("2026-12-31", Slot.EVENING, "CN:上海", "上海", 17),
            ),
        ),
        Case(
            name = "跨年区间 + 跨时区 + 同 key 改名 + 同天数排序兜底",
            today = "2026-01-03", nowHour = null, earliest = "2025-12-30",
            from = "2025-12-30", to = "2026-01-03",
            punches = listOf(
                p("2025-12-30", Slot.MORNING, "JP:40:Tokyo", "Tokyo", 7, zone = "Asia/Tokyo"),
                p("2025-12-30", Slot.EVENING, "JP:40:Tokyo", "东京", 17, zone = "Asia/Tokyo"),
                p("2025-12-31", Slot.MORNING, "US:NY:New York City", "纽约", 7, zone = "America/New_York"),
                p("2026-01-02", Slot.MORNING, "B:x", "同名", 7, acc = null),
                p("2026-01-02", Slot.EVENING, "A:x", "同名", 17, acc = null),
                p("2026-01-03", Slot.EVENING, "IN:Mumbai", "孟买", 18, zone = "Asia/Kolkata"),
            ),
        ),
        Case(
            name = "还没有任何记录",
            today = "2026-05-01", nowHour = 12, earliest = null, year = 2026,
            punches = emptyList(),
        ),
    )

    private fun stats(c: Case): YearStats {
        val today = LocalDate.parse(c.today)
        val earliest = c.earliest?.let(LocalDate::parse)
        return if (c.year != null) {
            DayCounting.computeYearStats(c.year, today, c.punches, c.overrides, c.nowHour, earliest)
        } else {
            DayCounting.computeRangeStats(
                LocalDate.parse(c.from!!), LocalDate.parse(c.to!!), today, c.punches, c.overrides, c.nowHour, earliest,
            )
        }
    }

    // ---- 极简、确定顺序的 JSON 写出 ----
    private fun q(s: String) = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch.code < 0x20 || ch == '﻿' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
        append('"')
    }
    private fun n(d: Double) = d.toString()
    private fun obj(vararg kv: Pair<String, String>) = kv.joinToString(",", "{", "}") { "${q(it.first)}:${it.second}" }
    private fun arr(items: List<String>) = items.joinToString(",", "[", "]")
    private fun optS(s: String?) = s?.let(::q) ?: "null"
    private fun optI(i: Int?) = i?.toString() ?: "null"

    private fun render(): String {
        val out = cases.map { c ->
            val s = stats(c)
            val payload = MigrationCodec.toJson(3, 0L, c.punches, c.overrides)
            obj(
                "name" to q(c.name),
                "today" to q(c.today),
                "nowHour" to optI(c.nowHour),
                "earliest" to optS(c.earliest),
                "year" to optI(c.year),
                "from" to optS(c.from),
                "to" to optS(c.to),
                "payload" to q(payload),
                "expect" to obj(
                    "firstDate" to q(s.firstDate.toString()),
                    "lastDate" to q(s.lastDate.toString()),
                    "recordedDays" to n(s.recordedDays),
                    "trackingSince" to optS(s.trackingSince?.toString()),
                    "unrecorded" to arr(s.unrecordedDates.map { q(it.toString()) }),
                    "cities" to arr(s.cities.map {
                        obj(
                            "cityKey" to q(it.cityKey), "cityName" to q(it.cityName), "days" to n(it.days),
                            "fullDays" to "${it.fullDays}", "halfDays" to "${it.halfDays}",
                            "provisionalHalf" to "${it.provisionalHalf}",
                        )
                    }),
                    "days" to arr(s.days.keys.sorted().map { d ->
                        val a = s.days.getValue(d)
                        obj(
                            "date" to q(d.toString()),
                            "manual" to "${a.manual}",
                            "provisional" to "${a.provisional}",
                            "shares" to arr(a.shares.map {
                                obj("cityKey" to q(it.cityKey), "weight" to n(it.weight), "manual" to "${it.manual}")
                            }),
                        )
                    }),
                    "stays" to arr(Stays.fold(s.days).map {
                        obj("cityKey" to q(it.cityKey), "from" to q(it.from.toString()), "to" to q(it.to.toString()),
                            "days" to n(it.days))
                    }),
                    "regions" to arr(Regions.summarize(s).map {
                        obj("code" to q(it.code), "days" to n(it.days), "cities" to "${it.cities}")
                    }),
                    "csv" to q(
                        Exporter.exportCsv(s, c.punches, includeSummary = true, includeDaily = true, includeStays = true),
                    ),
                ),
            )
        }
        return "{\"version\":1,\"cases\":[\n" + out.joinToString(",\n") + "\n]}\n"
    }

    @Test
    fun `fixture 与当前算法一致`() {
        val file = File("../../fixtures/core-cases.json")
        val fresh = render()
        if (System.getenv("UPDATE_FIXTURES") == "1" || !file.exists()) {
            file.parentFile.mkdirs()
            file.writeText(fresh)
        }
        assertEquals(file.readText(), fresh, "算法输出变了:UPDATE_FIXTURES=1 重新生成 fixtures/core-cases.json 并提交")
    }
}
