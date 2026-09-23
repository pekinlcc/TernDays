import Foundation
import UserNotifications

/// 天数阈值的存储(App Group 键 thresholds,格式见 Thresholds.encode)。
enum ThresholdStore {

    static func load() -> [Thresholds.Threshold] {
        Thresholds.decode(AppPrefs.store.string(forKey: AppPrefs.thresholdsKey))
    }

    static func save(_ list: [Thresholds.Threshold]) {
        AppPrefs.store.set(Thresholds.encode(list), forKey: AppPrefs.thresholdsKey)
    }

    /// 同一地区 + 同一窗口只留一条:再加一次就是改天数
    static func upsert(_ t: Thresholds.Threshold) {
        var list = load().filter { !($0.regionCode == t.regionCode && $0.window == t.window) }
        list.append(t)
        save(list)
    }

    static func remove(_ t: Thresholds.Threshold) {
        save(load().filter { $0 != t })
    }

    /// 已经提醒过的键(notifyKey + 级别:同一窗口期里「接近」「达到」各提醒一次)
    static func notifiedKeys() -> Set<String> {
        Set(AppPrefs.store.stringArray(forKey: AppPrefs.thresholdNotifiedKey) ?? [])
    }

    static func markNotified(_ key: String) {
        var keys = AppPrefs.store.stringArray(forKey: AppPrefs.thresholdNotifiedKey) ?? []
        guard !keys.contains(key) else { return }
        keys.append(key)
        // 只留最近的一批,免得年复一年越攒越多
        if keys.count > 200 { keys.removeFirst(keys.count - 200) }
        AppPrefs.store.set(keys, forKey: AppPrefs.thresholdNotifiedKey)
    }

    static func clearNotified() {
        AppPrefs.store.removeObject(forKey: AppPrefs.thresholdNotifiedKey)
    }
}

/// 阈值用量计算与提醒:打卡成功后、回到前台时各查一次。
/// 接近(NEAR)或达到(REACHED)且这一级别在本窗口期还没提醒过,发一条本地通知。
enum ThresholdAlerts {

    /// 一条阈值的当前状态(界面展示用)
    struct Item {
        let status: Thresholds.Status
        let regionName: String
    }

    /// 按当前时刻计算每条阈值的用量(与首页、导出同一份计天口径:进行中的今天按半天计)。
    /// 读 DataStore,放后台线程调用。
    static func evaluate(now: Date = Date()) -> [Item] {
        let list = ThresholdStore.load()
        guard !list.isEmpty else { return [] }
        let today = LocalDate(from: now, in: .current)
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let hour = cal.component(.hour, from: now)
        let earliest = DataStore.shared.earliestRecordDate()
        var regionsByWindow: [Thresholds.Window: [Regions.RegionStat]] = [:]
        var out: [Item] = []
        for t in list {
            let regions: [Regions.RegionStat]
            if let cached = regionsByWindow[t.window] {
                regions = cached
            } else {
                let r = Thresholds.range(t.window, today: today)
                let stats = DayCounting.computeRangeStats(
                    from: r.from, to: r.to, today: today,
                    punches: DataStore.shared.punchesBetween(r.from, r.to),
                    overrides: DataStore.shared.overridesBetween(r.from, r.to),
                    nowHour: hour, earliestRecordDate: earliest
                )
                regions = Regions.summarize(stats)
                regionsByWindow[t.window] = regions
            }
            let used = regions.first(where: { $0.code == t.regionCode })?.days ?? 0
            out.append(Item(status: Thresholds.status(t, used: used), regionName: Regions.nameOf(t.regionCode)))
        }
        return out
    }

    /// 「中国大陆今年已 170 天,距 183 天上限还剩 13 天」/「中国大陆今年已达到 183 天上限」
    static func message(_ item: Item) -> String {
        let t = item.status.threshold
        let span = t.window == .year ? "今年" : "最近 180 天"
        if item.status.level == .reached {
            return "\(item.regionName)\(span)已达到 \(t.days) 天上限"
        }
        return "\(item.regionName)\(span)已 \(DayCounting.formatDays(item.status.used)) 天,"
            + "距 \(t.days) 天上限还剩 \(DayCounting.formatDays(item.status.remaining)) 天"
    }

    /// 同步检查并按需发通知(调用方已在后台线程)。
    static func checkAndNotify(now: Date = Date()) {
        guard !DataStore.shared.isSealed else { return }
        let items = evaluate(now: now)
        guard !items.isEmpty else { return }
        let today = LocalDate(from: now, in: .current)
        let notified = ThresholdStore.notifiedKeys()
        for item in items where item.status.level != .ok {
            let t = item.status.threshold
            // 去重键带上级别(与 Android 一致):先「接近」提醒过,之后「达到」仍要再提醒一次
            let key = t.notifyKey(today: today) + "|" + (item.status.level == .reached ? "REACHED" : "NEAR")
            guard !notified.contains(key) else { continue }
            ThresholdStore.markNotified(key)
            let content = UNMutableNotificationContent()
            content.title = "天数提醒"
            content.body = message(item)
            content.sound = .default
            // 通知标识按「这条阈值」(地区 + 窗口,不带级别):「达到」那条替换掉通知中心里的「接近」
            UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: "threshold-\(t.regionCode)|\(t.window.rawValue)",
                                      content: content, trigger: nil)
            )
        }
    }

    /// 回到前台时:后台线程里查一次
    static func checkInBackground() {
        DispatchQueue.global(qos: .utility).async { checkAndNotify() }
    }
}
