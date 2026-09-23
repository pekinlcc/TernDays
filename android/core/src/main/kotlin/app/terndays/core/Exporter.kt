package app.terndays.core

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 导出：CSV（UTF-8 带 BOM，Excel 直接打开不乱码）与最小实现的 .xlsx。全部本地生成。 */
object Exporter {

    private val DATE = DateTimeFormatter.ISO_LOCAL_DATE

    private fun weekday(d: LocalDate) = Fmt.weekdayCn(d)

    private fun punchTime(p: Punch): String = Fmt.clock(p)

    /**
     * 按年统计写「2026 年」;自定义区间 / 滚动窗口写成「2026-03-29 至 2026-09-24」——
     * 哪怕区间恰好从 1 月 1 日开始(只导出上半年),也不能标成整年。
     */
    fun periodLabel(stats: YearStats): String =
        if (stats.wholeYear) {
            "${stats.year} 年"
        } else {
            "${stats.firstDate.format(DATE)} 至 ${stats.lastDate.format(DATE)}"
        }

    /** 「手动」只标在确实来自更正的那一份上,不贴到自动判定的另一半天 */
    private fun attributionText(attr: DayAttribution): String = when {
        attr.shares.isEmpty() && attr.provisional -> "今天进行中（待记录）"
        attr.shares.isEmpty() -> "无记录"
        else -> attr.shares.joinToString(" / ") {
            it.cityName + (if (it.weight >= 1.0) " +1" else " +0.5") + (if (it.manual) "（手动）" else "")
        }
    }

    /** 进行中的今天单样本先计 0.5,导出里要说清楚,别和跨城的 0.5 混为一谈 */
    private fun inProgressNote(attr: DayAttribution): String? =
        if (attr.provisional && attr.shares.isNotEmpty()) "今天进行中，先计半天" else null

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
        // 「开始使用」之前的日子不是漏记,不该在明细里写成一堆"无记录"
        // (此前与汇总里的「无记录天数」自相矛盾)
        // 一条记录都还没有:没有「开始使用」之日,明细为空(导出里写一行「尚未开始记录」)
        val since = stats.trackingSince ?: return emptyList()
        return stats.days
            .filterKeys { !it.isBefore(since) }
            .map { (date, attr) ->
            DailyRow(
                date,
                bySlot[date to Slot.MORNING],
                bySlot[date to Slot.EVENING],
                bySlot[date to Slot.EXTRA],
                attr,
            )
        }
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * 城市表(城市 | 天数 | 全天数 | 半天数 | 备注)之后空一行,接一张「项目 | 数值」两列小表:
     * 合计、无记录天数等数值都在第 2 列,不再落到「半天数」那一列下面(对列求和会被污染)。
     * 半天数只数已定型的半天;进行中的今天先计的 0.5 写在备注里。
     */
    private fun summaryTable(stats: YearStats, exportedAt: LocalDateTime?): List<List<String>> {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("城市", "天数", "全天数", "半天数", "备注"))
        for (c in stats.cities) {
            rows.add(
                listOf(
                    c.cityName,
                    DayCounting.formatDays(c.days),
                    c.fullDays.toString(),
                    c.halfDays.toString(),
                    if (c.provisionalHalf > 0) "含今天进行中的半天" else "",
                ),
            )
        }
        rows.add(listOf("", "", "", "", ""))
        rows.add(listOf("项目", "数值"))
        rows.add(listOf("合计（天）", DayCounting.formatDays(stats.recordedDays)))
        rows.add(listOf("无记录天数", stats.unrecordedDates.size.toString()))
        rows.add(listOf("统计区间", "${stats.firstDate.format(DATE)} 至 ${stats.lastDate.format(DATE)}"))
        rows.add(listOf("开始记录日", stats.trackingSince?.format(DATE) ?: "尚未开始记录"))
        if (exportedAt != null) rows.add(listOf("导出时间", exportedAt.format(STAMP)))
        return rows
    }

    private fun dailyTable(stats: YearStats, punches: List<Punch>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("日期", "星期", "早打卡", "早城市", "晚打卡", "晚城市", "首点", "计入", "备注", "时区"))
        val daily = dailyRows(stats, punches)
        if (daily.isEmpty()) {
            rows.add(listOf("尚未开始记录"))
            return rows
        }
        for (r in daily) {
            val notes = ArrayList<String>()
            if (r.attribution.manual) notes.add("手动更正/补记")
            inProgressNote(r.attribution)?.let { notes.add(it) }
            if (r.morning?.delayed == true) notes.add("早点延迟")
            if (r.evening?.delayed == true) notes.add("晚点延迟")
            if (r.morning?.fromCache == true || r.evening?.fromCache == true || r.extra?.fromCache == true) {
                notes.add("用了缓存位置")
            }
            rows.add(
                listOf(
                    r.date.format(DATE),
                    weekday(r.date),
                    r.morning?.let(::punchTime) ?: "",
                    r.morning?.cityName ?: "",
                    r.evening?.let(::punchTime) ?: "",
                    r.evening?.cityName ?: "",
                    r.extra?.let { "首 ${punchTime(it)} ${it.cityName}" } ?: "",
                    attributionText(r.attribution),
                    notes.joinToString("；"),
                    // 出差跨时区时,打卡时刻按当地时间写,这一列说明是哪里的时间
                    listOfNotNull(r.morning, r.evening, r.extra)
                        .map { Fmt.zoneLabel(it.zoneId, it.epochMs) }.distinct().joinToString(" / "),
                ),
            )
        }
        return rows
    }

    /** 行程段:同城连续的日子合成一段(见 [Stays])。 */
    private fun staysTable(stats: YearStats): List<List<String>> {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("城市", "开始", "结束", "天数"))
        for (s in Stays.fold(stats.days)) {
            rows.add(listOf(s.cityName, s.from.format(DATE), s.to.format(DATE), DayCounting.formatDays(s.days)))
        }
        if (rows.size == 1) rows.add(listOf("尚未开始记录"))
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
        exportedAt: LocalDateTime? = null,
        includeStays: Boolean = false,
    ): String {
        val parts = ArrayList<String>()
        val period = periodLabel(stats)
        if (includeSummary) parts.add("# 城市汇总 · $period\r\n" + csv(summaryTable(stats, exportedAt)))
        if (includeDaily) parts.add("# 每日明细 · $period\r\n" + csv(dailyTable(stats, punches)))
        if (includeStays) parts.add("# 行程段 · $period\r\n" + csv(staysTable(stats)))
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
        exportedAt: LocalDateTime? = null,
        includeStays: Boolean = false,
    ): ByteArray {
        val sheets = ArrayList<Pair<String, List<List<String>>>>()
        if (includeSummary) sheets.add("城市汇总" to summaryTable(stats, exportedAt))
        if (includeDaily) sheets.add("每日明细" to dailyTable(stats, punches))
        if (includeStays) sheets.add("行程段" to staysTable(stats))
        if (sheets.isEmpty()) sheets.add("城市汇总" to summaryTable(stats, exportedAt))

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
