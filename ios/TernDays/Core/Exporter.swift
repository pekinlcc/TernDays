import Foundation

/// 导出：CSV（UTF-8 带 BOM）与最小实现的 .xlsx（STORE 方式打包，无压缩依赖）。
enum Exporter {

    struct DailyRow {
        let date: LocalDate
        let morning: Punch?
        let evening: Punch?
        let attribution: DayAttribution
    }

    static func dailyRows(stats: YearStats, punches: [Punch]) -> [DailyRow] {
        var bySlot: [String: Punch] = [:]
        for p in punches {
            let k = "\(p.localDate)|\(p.slot.rawValue)"
            if let cur = bySlot[k], cur.epochMs <= p.epochMs { continue }
            bySlot[k] = p
        }
        return stats.days.keys.sorted().map { date in
            DailyRow(
                date: date,
                morning: bySlot["\(date)|\(Slot.morning.rawValue)"],
                evening: bySlot["\(date)|\(Slot.evening.rawValue)"],
                attribution: stats.days[date]!
            )
        }
    }

    private static func attributionText(_ attr: DayAttribution) -> String {
        if attr.shares.isEmpty { return "无记录" }
        let body = attr.shares.map { $0.cityName + ($0.weight >= 1.0 ? " +1" : " +0.5") }.joined(separator: " / ")
        return body + (attr.manual ? "（补记）" : "")
    }

    private static func summaryTable(_ stats: YearStats) -> [[String]] {
        var rows: [[String]] = [["城市", "天数", "全天数", "半天数"]]
        for c in stats.cities {
            rows.append([c.cityName, DayCounting.formatDays(c.days), String(c.fullDays), String(c.halfDays)])
        }
        rows.append(["（无记录天数）", String(stats.unrecordedDates.count), "", ""])
        return rows
    }

    private static func dailyTable(_ stats: YearStats, _ punches: [Punch]) -> [[String]] {
        var rows: [[String]] = [["日期", "星期", "早打卡", "早城市", "晚打卡", "晚城市", "计入", "备注"]]
        for r in dailyRows(stats: stats, punches: punches) {
            var notes: [String] = []
            if r.attribution.manual { notes.append("手动补记") }
            if r.morning?.delayed == true { notes.append("早点延迟") }
            if r.evening?.delayed == true { notes.append("晚点延迟") }
            if r.morning?.fromCache == true || r.evening?.fromCache == true { notes.append("用了缓存位置") }
            rows.append([
                r.date.description,
                r.date.weekdayCn,
                r.morning?.clock ?? "",
                r.morning?.cityName ?? "",
                r.evening?.clock ?? "",
                r.evening?.cityName ?? "",
                attributionText(r.attribution),
                notes.joined(separator: "；"),
            ])
        }
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

    static func exportCsv(stats: YearStats, punches: [Punch], includeSummary: Bool, includeDaily: Bool) -> String {
        var parts: [String] = []
        if includeSummary { parts.append("# 城市汇总 · \(stats.year) 年\r\n" + csv(summaryTable(stats))) }
        if includeDaily { parts.append("# 每日明细 · \(stats.year) 年\r\n" + csv(dailyTable(stats, punches))) }
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

    static func exportXlsx(stats: YearStats, punches: [Punch], includeSummary: Bool, includeDaily: Bool) -> Data {
        var sheets: [(String, [[String]])] = []
        if includeSummary { sheets.append(("城市汇总", summaryTable(stats))) }
        if includeDaily { sheets.append(("每日明细", dailyTable(stats, punches))) }
        if sheets.isEmpty { sheets.append(("城市汇总", summaryTable(stats))) }

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
