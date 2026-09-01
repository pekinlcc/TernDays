package app.terndays.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryReplayTest {

    // 合成边界带:深圳锚点在 (22.52, 114.03),香港锚点在 (22.50, 114.06) 附近
    private val tsv = listOf(
        "22.52000\t114.03000\tCN:深圳\t深圳\tshenzhen",
        "22.54000\t114.06000\tCN:深圳\t深圳\tshenzhen",
        "22.50000\t114.06000\tHK:香港\t香港\txianggang",
        "22.30000\t114.17000\tHK:香港\t香港\txianggang",
        "39.90750\t116.39723\tCN:北京\t北京\tbeijing",
    ).joinToString("\n", postfix = "\n")
    private val matcher = CityMatcher.load(tsv.byteInputStream())

    private fun item(
        id: Long,
        date: LocalDate,
        hourOffset: Long,
        lat: Double,
        lng: Double,
        acc: Double?,
        key: String,
        name: String,
    ) = HistoryReplay.Item(
        id, date, date.toEpochDay() * 86_400_000L + hourOffset * 3_600_000L, lat, lng, acc, key, name,
    )

    @Test
    fun `重放走交叉验证而不是裸最近邻`() {
        val d = LocalDate.of(2026, 9, 1)
        // 早点明确在深圳(高精度);晚点 GPS 漂到河边,几何最近是香港但边距小
        val items = listOf(
            item(1, d, 7, 22.521, 114.031, 30.0, "CN:深圳", "深圳"),
            item(2, d, 17, 22.510, 114.048, 30.0, "CN:深圳", "深圳"), // 香港 ~1.7km vs 深圳 ~2.2km,模糊区
        )
        val changes = HistoryReplay.replay(matcher, items).filter { it.changed }
        // 两条都不应被改:第 2 条靠行程连续性粘回深圳(裸最近邻会把它改成香港)
        assertTrue(changes.isEmpty(), "预期无改动,实际: $changes")
    }

    @Test
    fun `粘滞链受 36h 锚点上限约束不会无限自续期`() {
        val d0 = LocalDate.of(2026, 9, 1)
        // 第 0 条:明确深圳锚点(高精度,anchor);之后连续 4 天粗定位落在香港锚点上
        val items = buildList {
            add(item(0, d0, 7, 22.520, 114.030, 30.0, "CN:深圳", "深圳"))
            var id = 1L
            for (day in 1..4) {
                val d = d0.plusDays(day.toLong())
                add(item(id++, d, 7, 22.507, 114.052, 2000.0, "CN:深圳", "深圳"))
                add(item(id++, d, 17, 22.507, 114.052, 2000.0, "CN:深圳", "深圳"))
            }
        }
        val changes = HistoryReplay.replay(matcher, items).filter { it.changed }
        // 锚点始终是第 0 条(改判结果不当锚),36h 后连续性失效 → 后面的点改回香港
        val changedIds = changes.map { it.id }.toSet()
        assertTrue(changes.all { it.cityKey == "HK:香港" })
        // 第 3 天(id 5 起,距锚点 >48h)必须已经脱离粘滞
        assertTrue(5L in changedIds && 6L in changedIds && 7L in changedIds && 8L in changedIds, "实际改动: $changes")
    }

    @Test
    fun `当日手动更正作为锚点参与链条`() {
        val d = LocalDate.of(2026, 9, 1)
        val items = listOf(
            // 打卡本身漂在香港侧(粗定位),但当日被用户更正为深圳
            item(1, d, 7, 22.501, 114.060, 2000.0, "HK:香港", "香港"),
            // 次日早上仍是边界粗定位:应因更正锚点粘回深圳
            item(2, d.plusDays(1), 7, 22.508, 114.055, 2000.0, "HK:香港", "香港"),
        )
        val changes = HistoryReplay.replay(matcher, items, overrideByDate = mapOf(d to "CN:深圳")).filter { it.changed }
        val second = changes.firstOrNull { it.id == 2L }
        assertEquals("CN:深圳", second?.cityKey, "实际: $changes")
    }

    @Test
    fun `空列表与无变化`() {
        assertTrue(HistoryReplay.replay(matcher, emptyList()).isEmpty())
        val d = LocalDate.of(2026, 9, 1)
        val stable = listOf(item(1, d, 7, 39.9075, 116.3972, 20.0, "CN:北京", "北京"))
        assertTrue(HistoryReplay.replay(matcher, stable).none { it.changed })
    }
}
