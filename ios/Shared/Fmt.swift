import Foundation

/// 界面与导出共用的文本格式(与 Android :core Fmt 逐字一致)。
/// 星期与打卡时刻早已是 LocalDate.weekdayCn / Punch.clock,这里只做统一入口,
/// 新增的时区标签两端必须逐字节相同(导出 CSV 由 fixtures/core-cases.json 对齐)。
enum Fmt {

    static func weekdayCn(_ d: LocalDate) -> String { d.weekdayCn }

    /// 打卡时刻按**打卡当时**的时区显示(出差回来后看东京那条仍是东京时间)
    static func clock(_ p: Punch) -> String { p.clock }

    /// 「3月5日」
    static func monthDay(_ d: LocalDate) -> String { "\(d.month)月\(d.day)日" }

    /// 「3月5日」;不在 relativeTo 那一年时带上年份「2025年12月30日」
    static func monthDay(_ d: LocalDate, relativeTo ref: LocalDate) -> String {
        d.year == ref.year ? monthDay(d) : "\(d.year)年\(d.month)月\(d.day)日"
    }

    /// "Asia/Tokyo(UTC+9)"、"Asia/Kolkata(UTC+5:30)"、"Europe/London(UTC)":按那一刻的实际偏移(含夏令时)。
    static func zoneLabel(zoneId: String, epochMs: Int64) -> String {
        let at = Date(timeIntervalSince1970: Double(epochMs) / 1000)
        let offset = DayCounting.zoneOf(zoneId).secondsFromGMT(for: at)
        if offset == 0 { return "\(zoneId)(UTC)" }
        let sign = offset > 0 ? "+" : "-"
        let a = abs(offset)
        let h = a / 3600
        let m = a % 3600 / 60
        let minutes = m == 0 ? "" : String(format: ":%02d", m)
        return "\(zoneId)(UTC\(sign)\(h)\(minutes))"
    }
}
