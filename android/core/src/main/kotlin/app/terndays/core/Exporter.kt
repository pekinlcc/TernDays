package app.terndays.core

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出：CSV（UTF-8 带 BOM，Excel 直接打开不乱码）与最小实现的 .xlsx。全部本地生成。 */
object Exporter {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val WEEK = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private fun weekday(d: LocalDate) = WEEK[d.dayOfWeek.value - 1]

    private fun punchTime(p: Punch): String =
        Instant.ofEpochMilli(p.epochMs).atZone(ZoneId.of(p.zoneId)).toLocalTime().format(TIME)

    private fun attributionText(attr: DayAttribution): String = when {
        attr.shares.isEmpty() -> "无记录"
        else -> attr.shares.joinToString(" / ") {
            it.cityName + if (it.weight >= 1.0) " +1" else " +0.5"
        } + if (attr.manual) "（补记）" else ""
    }

    data class DailyRow(
        val date: LocalDate,
        val morning: Punch?,
        val evening: Punch?,
        val extra: Punch?,
        val attribution: DayAttribution,
    )

    fun dailyRows(stats: YearStats, punches: List<Punch>): List<DailyRow> {
        val bySlot = HashMap<Pair<LocalDate, Slot>, Punch>()
        for (p in punches) {
            val k = p.localDate to p.slot
            val cur = bySlot[k]
            if (cur == null || p.epochMs < cur.epochMs) bySlot[k] = p
        }
        return stats.days.map { (date, attr) ->
            DailyRow(
                date,
                bySlot[date to Slot.MORNING],
                bySlot[date to Slot.EVENING],
                bySlot[date to Slot.EXTRA],
                attr,
            )
        }
    }

    private fun summaryTable(stats: YearStats): List<List<String>> {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("城市", "天数", "全天数", "半天数"))
        for (c in stats.cities) {
            rows.add(listOf(c.cityName, DayCounting.formatDays(c.days), c.fullDays.toString(), c.halfDays.toString()))
        }
        rows.add(listOf("（无记录天数）", stats.unrecordedDates.size.toString(), "", ""))
        return rows
    }

    private fun dailyTable(stats: YearStats, punches: List<Punch>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("日期", "星期", "早打卡", "早城市", "晚打卡", "晚城市", "计入", "备注"))
        for (r in dailyRows(stats, punches)) {
            val notes = ArrayList<String>()
            if (r.attribution.manual) notes.add("手动补记")
            if (r.morning?.delayed == true) notes.add("早点延迟")
            if (r.evening?.delayed == true) notes.add("晚点延迟")
            if (r.morning?.fromCache == true || r.evening?.fromCache == true) notes.add("用了缓存位置")
            if (r.extra != null) notes.add("首点 ${punchTime(r.extra)} ${r.extra.cityName}")
            rows.add(
                listOf(
                    r.date.format(DATE),
                    weekday(r.date),
                    r.morning?.let(::punchTime) ?: "",
                    r.morning?.cityName ?: "",
                    r.evening?.let(::punchTime) ?: "",
                    r.evening?.cityName ?: "",
                    attributionText(r.attribution),
                    notes.joinToString("；"),
                ),
            )
        }
        return rows
    }

    // ---------- CSV ----------

    private fun csvEscape(v: String): String =
        if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun csv(rows: List<List<String>>): String =
        rows.joinToString("\r\n") { row -> row.joinToString(",") { csvEscape(it) } }

    fun exportCsv(
        stats: YearStats,
        punches: List<Punch>,
        includeSummary: Boolean,
        includeDaily: Boolean,
    ): String {
        val parts = ArrayList<String>()
        if (includeSummary) parts.add("# 城市汇总 · ${stats.year} 年\r\n" + csv(summaryTable(stats)))
        if (includeDaily) parts.add("# 每日明细 · ${stats.year} 年\r\n" + csv(dailyTable(stats, punches)))
        return "\uFEFF" + parts.joinToString("\r\n\r\n")
    }

    // ---------- 最小 XLSX ----------

    private fun xmlEscape(v: String): String = buildString {
        for (ch in v) when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }

    private fun colRef(i: Int): String {
        var n = i
        val sb = StringBuilder()
        while (n >= 0) {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        }
        return sb.toString()
    }

    private val NUMERIC = Regex("^-?\\d+(\\.\\d+)?$")

    private fun sheetXml(rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        rows.forEachIndexed { ri, row ->
            sb.append("<row r=\"").append(ri + 1).append("\">")
            row.forEachIndexed { ci, cell ->
                if (cell.isEmpty()) return@forEachIndexed
                val ref = colRef(ci) + (ri + 1)
                if (ri > 0 && NUMERIC.matches(cell)) {
                    sb.append("<c r=\"").append(ref).append("\"><v>").append(cell).append("</v></c>")
                } else {
                    sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(xmlEscape(cell)).append("</t></is></c>")
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    fun exportXlsx(
        stats: YearStats,
        punches: List<Punch>,
        includeSummary: Boolean,
        includeDaily: Boolean,
    ): ByteArray {
        val sheets = ArrayList<Pair<String, List<List<String>>>>()
        if (includeSummary) sheets.add("城市汇总" to summaryTable(stats))
        if (includeDaily) sheets.add("每日明细" to dailyTable(stats, punches))
        if (sheets.isEmpty()) sheets.add("城市汇总" to summaryTable(stats))

        val contentTypes = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>")
            sheets.forEachIndexed { i, _ ->
                append("<Override PartName=\"/xl/worksheets/sheet${i + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>")
            }
            append("</Types>")
        }
        val rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"
        val workbook = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
            append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>")
            sheets.forEachIndexed { i, (name, _) ->
                append("<sheet name=\"${xmlEscape(name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>")
            }
            append("</sheets></workbook>")
        }
        val workbookRels = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            sheets.forEachIndexed { i, _ ->
                append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${i + 1}.xml\"/>")
            }
            append("<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
            append("</Relationships>")
        }
        val styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>" +
            "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>" +
            "<borders count=\"1\"><border/></borders>" +
            "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs>" +
            "<cellXfs count=\"1\"><xf xfId=\"0\"/></cellXfs>" +
            "</styleSheet>"

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rootRels)
            put("xl/workbook.xml", workbook)
            put("xl/_rels/workbook.xml.rels", workbookRels)
            put("xl/styles.xml", styles)
            sheets.forEachIndexed { i, (_, rows) -> put("xl/worksheets/sheet${i + 1}.xml", sheetXml(rows)) }
        }
        return out.toByteArray()
    }
}
