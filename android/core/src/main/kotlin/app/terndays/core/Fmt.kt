package app.terndays.core

import java.time.Instant
import java.time.LocalDate

/** 界面与导出共用的文本格式(此前 HomeScreen 与 Exporter 各有一份星期 / 时刻实现)。 */
object Fmt {

    private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun weekdayCn(d: LocalDate): String = WEEK[d.dayOfWeek.value - 1]

    /** 打卡时刻按**打卡当时**的时区显示(出差回来后看东京那条仍是东京时间)。 */
    fun clock(p: Punch): String {
        val t = Instant.ofEpochMilli(p.epochMs).atZone(DayCounting.zoneOf(p.zoneId)).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }

    fun monthDay(d: LocalDate): String = "${d.monthValue}月${d.dayOfMonth}日"

    /** "Asia/Tokyo(UTC+9)"、"Asia/Kolkata(UTC+5:30)"、"Europe/London(UTC)":按那一刻的实际偏移(含夏令时)。 */
    fun zoneLabel(zoneId: String, epochMs: Long): String {
        val offset = DayCounting.zoneOf(zoneId).rules.getOffset(Instant.ofEpochMilli(epochMs)).totalSeconds
        if (offset == 0) return "$zoneId(UTC)"
        val sign = if (offset > 0) "+" else "-"
        val abs = Math.abs(offset)
        val h = abs / 3600
        val m = abs % 3600 / 60
        return "$zoneId(UTC$sign$h${if (m == 0) "" else ":%02d".format(m)})"
    }
}
