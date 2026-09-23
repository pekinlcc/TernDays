import Foundation

/// 主应用的 App Group 偏好(与小组件共用同一个 suite,键名与 Android 同义)。
/// 放在 App Group 而不是 UserDefaults.standard:后台被系统拉起时读写的是同一份。
enum AppPrefs {
    static var store: UserDefaults { UserDefaults(suiteName: AppGroup.id) ?? .standard }

    static let pausedKey = "punchPaused"
    static let lastAttemptKey = "lastAttempt"
    static let remindersOnKey = "remindersOn"
    static let remindersSilentKey = "remindersSilent"
    static let thresholdsKey = "thresholds"
    static let thresholdNotifiedKey = "thresholdNotified"
    static let lastBackupAtKey = "lastBackupAt"

    /// 暂停自动打卡(换了新手机、旧手机留作备用时):停 SLC / 提醒 / 后台刷新,打卡直接跳过
    static var punchPaused: Bool {
        get { store.bool(forKey: pausedKey) }
        set { store.set(newValue, forKey: pausedKey) }
    }

    /// 每日 07:00 / 17:00 提醒;没设置过 = 开
    static var remindersOn: Bool {
        get { store.object(forKey: remindersOnKey) as? Bool ?? true }
        set { store.set(newValue, forKey: remindersOnKey) }
    }

    /// 静默提醒:不响铃、只进通知中心(iOS 15+ 的 passive 中断级别)
    static var remindersSilent: Bool {
        get { store.bool(forKey: remindersSilentKey) }
        set { store.set(newValue, forKey: remindersSilentKey) }
    }

    /// 上次成功导出加密备份的时刻
    static var lastBackupAt: Date? {
        get {
            guard let t = store.object(forKey: lastBackupAtKey) as? Double else { return nil }
            return Date(timeIntervalSince1970: t)
        }
        set {
            if let d = newValue {
                store.set(d.timeIntervalSince1970, forKey: lastBackupAtKey)
            } else {
                store.removeObject(forKey: lastBackupAtKey)
            }
        }
    }
}

/// 最近一次自动打卡尝试(首页「最近一次尝试 07:02 · 早点 · 已记录 深圳」)。
/// 打卡没成功时用户此前只能猜:是没到点、没权限、没定位还是被系统限制了——这里把结论留下来。
struct LastAttempt: Codable {
    let atMs: Int64
    /// MORNING / EVENING / EXTRA;还没决定时段就退出的(暂停、数据读不到)为空
    let slot: String
    /// 已记录 / 没拿到定位 / 缺定位权限 / 需要「始终允许」/ 数据暂时读不到 / 已暂停
    let result: String
    /// 已记录时是城市名,其余为空
    let detail: String

    enum Outcome: String {
        case recorded = "已记录"
        case noLocation = "没拿到定位"
        case noPermission = "缺定位权限"
        case needsAlways = "需要「始终允许」"
        case sealed = "数据暂时读不到"
        case paused = "已暂停"
    }

    static func load() -> LastAttempt? {
        guard let data = AppPrefs.store.data(forKey: AppPrefs.lastAttemptKey) else { return nil }
        return try? JSONDecoder().decode(LastAttempt.self, from: data)
    }

    @discardableResult
    static func record(_ outcome: Outcome, slot: Slot?, detail: String = "", at: Date = Date()) -> LastAttempt {
        let attempt = LastAttempt(
            atMs: Int64(at.timeIntervalSince1970 * 1000),
            slot: slot?.rawValue ?? "",
            result: outcome.rawValue,
            detail: detail
        )
        if let data = try? JSONEncoder().encode(attempt) {
            AppPrefs.store.set(data, forKey: AppPrefs.lastAttemptKey)
        }
        return attempt
    }

    static func clear() {
        AppPrefs.store.removeObject(forKey: AppPrefs.lastAttemptKey)
    }

    var date: Date { Date(timeIntervalSince1970: Double(atMs) / 1000) }

    private var slotLabel: String {
        switch Slot(rawValue: slot) {
        case .morning?: return "早点"
        case .evening?: return "晚点"
        case .extra?: return "首点"
        case nil: return ""
        }
    }

    /// 「最近一次尝试 07:02 · 早点 · 已记录 深圳」;不是今天的写上日期
    func summary(now: Date = Date()) -> String {
        let at = date
        var when = TimeFmt.hhmm(at)
        let day = LocalDate(from: at, in: .current)
        let today = LocalDate(from: now, in: .current)
        if day == today.minusDays(1) {
            when = "昨天 " + when
        } else if day != today {
            when = Fmt.monthDay(day) + " " + when
        }
        var parts = ["最近一次尝试 " + when]
        if !slotLabel.isEmpty { parts.append(slotLabel) }
        parts.append(detail.isEmpty ? result : "\(result) \(detail)")
        return parts.joined(separator: " · ")
    }
}

/// 界面上的时刻格式(当前时区)
enum TimeFmt {
    static func hhmm(_ date: Date) -> String {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let c = cal.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    /// 「下一次 17:00」;明天的写「明天 07:00」
    static func nextPunchText(now: Date = Date()) -> String {
        let next = PunchRules.nextPunchDate(after: now)
        let sameDay = LocalDate(from: next, in: .current) == LocalDate(from: now, in: .current)
        return "下一次 " + (sameDay ? "" : "明天 ") + hhmm(next)
    }

    /// 「2026-09-23 10:30」
    static func stamp(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.string(from: date)
    }

    /// 「2026-09-23」
    static func isoDay(_ date: Date) -> String {
        LocalDate(from: date, in: .current).description
    }
}
