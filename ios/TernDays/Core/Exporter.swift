import Foundation

/// 导出：CSV（UTF-8 带 BOM）与最小实现的 .xlsx（STORE 方式打包，无压缩依赖）。
enum Exporter {

    struct DailyRow {
        let date: LocalDate
        let morning: Punch?
        let evening: Punch?
        let extra: Punch?
        let attribution: DayAttribution
    }

    static func dailyRows(stats: YearStats, punches: [Punch]) -> [DailyRow] {
        var bySlot: [String: Punch] = [:]
        for p in punches {
            let k = "\(p.localDate)|\(p.slot.rawValue)"
            if let cur = bySlot[k], cur.epochMs <= p.epochMs { continue }
            bySlot[k] = p
        }
        // 「开始使用」之前的日子不是漏记,不写进明细(与汇总里的无记录天数保持一致)
        // 一条记录都还没有:明细为空(导出里写一行「尚未开始记录」)
        guard let since = stats.trackingSince else { return [] }
        return stats.days.keys.sorted().filter { $0 >= since }.map { date in
            DailyRow(
                date: date,
                morning: bySlot["\(date)|\(Slot.morning.rawValue)"],
                evening: bySlot["\(date)|\(Slot.evening.rawValue)"],
                extra: bySlot["\(date)|\(Slot.extra.rawValue)"],
                attribution: stats.days[date]!
            )
        }
    }

    /// 「2026 年」;自定义区间 / 滚动窗口写成「2026-03-29 至 2026-09-24」(与 Android 逐字一致)
    static func periodLabel(_ stats: YearStats) -> String {
        if stats.firstDate == LocalDate(year: stats.year, month: 1, day: 1) && stats.lastDate.year == stats.year {
            return "\(stats.year) 年"
        }
        return "\(stats.firstDate) 至 \(stats.lastDate)"
    }

    /// 「手动」只标在确实来自更正的那一份上(与 Android :core Exporter 逐字一致)
    private static func attributionText(_ attr: DayAttribution) -> String {
        if attr.shares.isEmpty { return attr.provisional ? "今天进行中（待记录）" : "无记录" }
        return attr.shares.map {
            $0.cityName + ($0.weight >= 1.0 ? " +1" : " +0.5") + ($0.manual ? "（手动）" : "")
        }.joined(separator: " / ")
    }

    private static let stampFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f
    }()

    /// 城市表(城市 | 天数 | 全天数 | 半天数 | 备注)之后空一行,接「项目 | 数值」两列小表;
    /// 半天数只数已定型的半天,进行中的今天写在备注里。
    private static func summaryTable(_ stats: YearStats, exportedAt: Date?) -> [[String]] {
        var rows: [[String]] = [["城市", "天数", "全天数", "半天数", "备注"]]
        for c in stats.cities {
            rows.append([
                c.cityName, DayCounting.formatDays(c.days), String(c.fullDays), String(c.halfDays),
                c.provisionalHalf > 0 ? "含今天进行中的半天" : "",
            ])
        }
        rows.append(["", "", "", "", ""])
        rows.append(["项目", "数值"])
        rows.append(["合计（天）", DayCounting.formatDays(stats.recordedDays)])
        rows.append(["无记录天数", String(stats.unrecordedDates.count)])
        rows.append(["统计区间", "\(stats.firstDate) 至 \(stats.lastDate)"])
        rows.append(["开始记录日", stats.trackingSince?.description ?? "尚未开始记录"])
        if let exportedAt { rows.append(["导出时间", stampFormatter.string(from: exportedAt)]) }
        return rows
    }

    private static func dailyTable(_ stats: YearStats, _ punches: [Punch]) -> [[String]] {
        var rows: [[String]] = [["日期", "星期", "早打卡", "早城市", "晚打卡", "晚城市", "首点", "计入", "备注", "时区"]]
        let daily = dailyRows(stats: stats, punches: punches)
        if daily.isEmpty {
            rows.append(["尚未开始记录"])
            return rows
        }
        for r in daily {
            var notes: [String] = []
            if r.attribution.manual { notes.append("手动更正/补记") }
            // 进行中的今天先计 0.5,别和跨城的 0.5 混为一谈
            if r.attribution.provisional && !r.attribution.shares.isEmpty {
                notes.append("今天进行中，先计半天")
            }
            if r.morning?.delayed == true { notes.append("早点延迟") }
            if r.evening?.delayed == true { notes.append("晚点延迟") }
            if r.morning?.fromCache == true || r.evening?.fromCache == true || r.extra?.fromCache == true {
                notes.append("用了缓存位置")
            }
            rows.append([
                r.date.description,
                r.date.weekdayCn,
                r.morning?.clock ?? "",
                r.morning?.cityName ?? "",
                r.evening?.clock ?? "",
                r.evening?.cityName ?? "",
                r.extra.map { "首 \($0.clock) \($0.cityName)" } ?? "",
                attributionText(r.attribution),
                notes.joined(separator: "；"),
                zoneText(r),
            ])
        }
        return rows
    }

    /// 出差跨时区时,打卡时刻按当地时间写,这一列说明是哪里的时间(早、晚、首点顺序去重)
    private static func zoneText(_ r: DailyRow) -> String {
        var labels: [String] = []
        for p in [r.morning, r.evening, r.extra].compactMap({ $0 }) {
            let label = Fmt.zoneLabel(zoneId: p.zoneId, epochMs: p.epochMs)
            if !labels.contains(label) { labels.append(label) }
        }
        return labels.joined(separator: " / ")
    }

    /// 行程段:同城连续的日子合成一段(见 Stays)。
    private static func staysTable(_ stats: YearStats) -> [[String]] {
        var rows: [[String]] = [["城市", "开始", "结束", "天数"]]
        for s in Stays.fold(stats.days) {
            rows.append([s.cityName, s.from.description, s.to.description, DayCounting.formatDays(s.days)])
        }
        if rows.count == 1 { rows.append(["尚未开始记录"]) }
        return rows
    }

    // MARK: CSV

    private static func csvEscape(_ v: String) -> String {
        if v.contains(",") || v.contains("\"") || v.contains("\n") {
            return "\"" + v.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return v
    }

    private static func csv(_ rows: [[String]]) -> String {
        rows.map { $0.map(csvEscape).joined(separator: ",") }.joined(separator: "\r\n")
    }

    static func exportCsv(stats: YearStats, punches: [Punch], includeSummary: Bool, includeDaily: Bool,
                          exportedAt: Date? = nil, includeStays: Bool = false) -> String {
        var parts: [String] = []
        let period = periodLabel(stats)
        if includeSummary { parts.append("# 城市汇总 · \(period)\r\n" + csv(summaryTable(stats, exportedAt: exportedAt))) }
        if includeDaily { parts.append("# 每日明细 · \(period)\r\n" + csv(dailyTable(stats, punches))) }
        if includeStays { parts.append("# 行程段 · \(period)\r\n" + csv(staysTable(stats))) }
        return "\u{FEFF}" + parts.joined(separator: "\r\n\r\n")
    }

    // MARK: 最小 XLSX

    private static func xmlEscape(_ v: String) -> String {
        v.replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
    }

    private static func colRef(_ i: Int) -> String {
        var n = i
        var s = ""
        while n >= 0 {
            s = String(UnicodeScalar(UInt8(65 + n % 26))) + s
            n = n / 26 - 1
        }
        return s
    }

    private static func isNumeric(_ s: String) -> Bool {
        guard !s.isEmpty else { return false }
        return s.range(of: "^-?\\d+(\\.\\d+)?$", options: .regularExpression) != nil
    }

    private static func sheetXml(_ rows: [[String]]) -> String {
        var sb = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        sb += "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
        for (ri, row) in rows.enumerated() {
            sb += "<row r=\"\(ri + 1)\">"
            for (ci, cell) in row.enumerated() where !cell.isEmpty {
                let ref = colRef(ci) + String(ri + 1)
                if ri > 0 && isNumeric(cell) {
                    sb += "<c r=\"\(ref)\"><v>\(cell)</v></c>"
                } else {
                    sb += "<c r=\"\(ref)\" t=\"inlineStr\"><is><t xml:space=\"preserve\">\(xmlEscape(cell))</t></is></c>"
                }
            }
            sb += "</row>"
        }
        sb += "</sheetData></worksheet>"
        return sb
    }

    static func exportXlsx(stats: YearStats, punches: [Punch], includeSummary: Bool, includeDaily: Bool,
                           exportedAt: Date? = nil, includeStays: Bool = false) -> Data {
        var sheets: [(String, [[String]])] = []
        if includeSummary { sheets.append(("城市汇总", summaryTable(stats, exportedAt: exportedAt))) }
        if includeDaily { sheets.append(("每日明细", dailyTable(stats, punches))) }
        if includeStays { sheets.append(("行程段", staysTable(stats))) }
        if sheets.isEmpty { sheets.append(("城市汇总", summaryTable(stats, exportedAt: exportedAt))) }

        var contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        contentTypes += "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        contentTypes += "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        contentTypes += "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        contentTypes += "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
        contentTypes += "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
        for i in sheets.indices {
            contentTypes += "<Override PartName=\"/xl/worksheets/sheet\(i + 1).xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }
        contentTypes += "</Types>"

        let rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>"

        var workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        workbook += "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
        workbook += "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>"
        for (i, s) in sheets.enumerated() {
            workbook += "<sheet name=\"\(xmlEscape(s.0))\" sheetId=\"\(i + 1)\" r:id=\"rId\(i + 1)\"/>"
        }
        workbook += "</sheets></workbook>"

        var wbRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
        wbRels += "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        for i in sheets.indices {
            wbRels += "<Relationship Id=\"rId\(i + 1)\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet\(i + 1).xml\"/>"
        }
        wbRels += "<Relationship Id=\"rId\(sheets.count + 1)\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
        wbRels += "</Relationships>"

        let styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>"
            + "<borders count=\"1\"><border/></borders>"
            + "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs>"
            + "<cellXfs count=\"1\"><xf xfId=\"0\"/></cellXfs>"
            + "</styleSheet>"

        var zip = ZipWriter()
        zip.add(name: "[Content_Types].xml", text: contentTypes)
        zip.add(name: "_rels/.rels", text: rootRels)
        zip.add(name: "xl/workbook.xml", text: workbook)
        zip.add(name: "xl/_rels/workbook.xml.rels", text: wbRels)
        zip.add(name: "xl/styles.xml", text: styles)
        for (i, s) in sheets.enumerated() {
            zip.add(name: "xl/worksheets/sheet\(i + 1).xml", text: sheetXml(s.1))
        }
        return zip.finish()
    }
}

/// 极简 ZIP 写入器：STORE（无压缩）方式，足够 xlsx 使用。
struct ZipWriter {
    private var data = Data()
    private var central = Data()
    private var count: UInt16 = 0

    private static let crcTable: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 {
            c = (c & 1) == 1 ? (0xEDB88320 ^ (c >> 1)) : (c >> 1)
        }
        return c
    }

    private static func crc32(_ bytes: Data) -> UInt32 {
        var c: UInt32 = 0xFFFFFFFF
        for b in bytes {
            c = crcTable[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8)
        }
        return c ^ 0xFFFFFFFF
    }

    private mutating func le16(_ v: UInt16, into d: inout Data) { d.append(contentsOf: [UInt8(v & 0xFF), UInt8(v >> 8)]) }
    private mutating func le32(_ v: UInt32, into d: inout Data) {
        d.append(contentsOf: [UInt8(v & 0xFF), UInt8((v >> 8) & 0xFF), UInt8((v >> 16) & 0xFF), UInt8(v >> 24)])
    }

    mutating func add(name: String, text: String) {
        add(name: name, bytes: Data(text.utf8))
    }

    mutating func add(name: String, bytes: Data) {
        let nameData = Data(name.utf8)
        let crc = ZipWriter.crc32(bytes)
        let offset = UInt32(data.count)
        let size = UInt32(bytes.count)

        var local = Data()
        le32(0x04034B50, into: &local)
        le16(20, into: &local)              // version needed
        le16(0x0800, into: &local)          // UTF-8 filename flag
        le16(0, into: &local)               // method: STORE
        le16(0, into: &local)               // time
        le16(0, into: &local)               // date
        le32(crc, into: &local)
        le32(size, into: &local)            // compressed
        le32(size, into: &local)            // uncompressed
        le16(UInt16(nameData.count), into: &local)
        le16(0, into: &local)               // extra len
        local.append(nameData)
        data.append(local)
        data.append(bytes)

        var c = Data()
        le32(0x02014B50, into: &c)
        le16(20, into: &c)                  // version made by
        le16(20, into: &c)                  // version needed
        le16(0x0800, into: &c)
        le16(0, into: &c)
        le16(0, into: &c)
        le16(0, into: &c)
        le32(crc, into: &c)
        le32(size, into: &c)
        le32(size, into: &c)
        le16(UInt16(nameData.count), into: &c)
        le16(0, into: &c)                   // extra
        le16(0, into: &c)                   // comment
        le16(0, into: &c)                   // disk
        le16(0, into: &c)                   // internal attrs
        le32(0, into: &c)                   // external attrs
        le32(offset, into: &c)
        c.append(nameData)
        central.append(c)
        count += 1
    }

    mutating func finish() -> Data {
        let centralOffset = UInt32(data.count)
        data.append(central)
        var eocd = Data()
        le32(0x06054B50, into: &eocd)
        le16(0, into: &eocd)
        le16(0, into: &eocd)
        le16(count, into: &eocd)
        le16(count, into: &eocd)
        le32(UInt32(central.count), into: &eocd)
        le32(centralOffset, into: &eocd)
        le16(0, into: &eocd)
        data.append(eocd)
        return data
    }
}
