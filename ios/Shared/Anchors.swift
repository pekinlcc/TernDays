import Foundation

/// 行程连续性锚点的选取(实时打卡用;重放另见 HistoryReplay),与 Android :core Anchors 同一口径。
///
/// 系统时间被拨乱过(拨到未来打了卡、又拨回来)时,那条「未来」记录会一直是
/// 「最近一条」,此后每次打卡都拿它当锚——链龄算出负数,粘滞链永不过期。
/// 所以锚点只从「不晚于现在」的记录里挑。
enum Anchors {

    /// 时钟校准的容差:手机间几十秒的偏差不算「未来」
    static let skewMs: Int64 = 5 * 60_000

    static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// 最近一条可作锚的打卡:解析成功、非改判、且不晚于现在。
    static func pick(_ punches: [Punch], nowMs: Int64) -> Punch? {
        punches
            .filter { $0.cityKey != "unknown" && $0.viaContext != true && $0.epochMs <= nowMs + skewMs }
            .max { $0.epochMs < $1.epochMs }
    }

    /// 打卡时刻晚于现在的记录(系统时间曾被拨到未来),供界面提示用户确认或清理。
    static func future(_ punches: [Punch], nowMs: Int64) -> [Punch] {
        punches.filter { $0.epochMs > nowMs + skewMs }.sorted { $0.epochMs < $1.epochMs }
    }
}
