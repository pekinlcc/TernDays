import Foundation

/// 打卡时间规则（与 Android :core 对齐）：
/// 早点目标 07:00，补捕窗口 [07:00, 12:00)；晚点目标 17:00，补捕窗口 [17:00, 24:00)。
enum PunchRules {
    static let morningHour = 7
    static let eveningHour = 17
    static let morningWindowEndHour = 12
    static let delayToleranceMinutes = 15

    static func slotInWindow(hour: Int, minute: Int) -> Slot? {
        if hour >= morningHour && hour < morningWindowEndHour { return .morning }
        if hour >= eveningHour { return .evening }
        return nil
    }

    static func slotInWindow(at date: Date = Date(), timeZone: TimeZone = .current) -> Slot? {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let c = cal.dateComponents([.hour, .minute], from: date)
        return slotInWindow(hour: c.hour!, minute: c.minute!)
    }

    /// 是否延迟:严格超过「目标时刻 + 容差」即算(含秒,与 Android LocalTime 比较一致)
    static func isDelayed(at date: Date, slot: Slot, timeZone: TimeZone = .current) -> Bool {
        guard slot != .extra else { return false } // 首点没有目标时刻
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let c = cal.dateComponents([.hour, .minute, .second], from: date)
        let seconds = c.hour! * 3600 + c.minute! * 60 + (c.second ?? 0)
        let target = ((slot == .morning ? morningHour : eveningHour) * 60 + delayToleranceMinutes) * 60
        return seconds > target
    }

    /// 下一次打卡时刻（严格晚于 now，当前时区）
    static func nextPunchDate(after now: Date = Date(), timeZone: TimeZone = .current) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        for dayOffset in 0...1 {
            guard let base = cal.date(byAdding: .day, value: dayOffset, to: now) else { continue }
            for hour in [morningHour, eveningHour] {
                var c = cal.dateComponents([.year, .month, .day], from: base)
                c.hour = hour
                c.minute = 0
                c.second = 0
                if let t = cal.date(from: c), t > now { return t }
            }
        }
        return now.addingTimeInterval(12 * 3600)
    }

    static func slotToBackfill(now: Date = Date(), hasMorning: Bool, hasEvening: Bool) -> Slot? {
        switch slotInWindow(at: now) {
        case .morning: return hasMorning ? nil : .morning
        case .evening: return hasEvening ? nil : .evening
        case .extra, nil: return nil // slotInWindow 只会返回早/晚
        }
    }
}
