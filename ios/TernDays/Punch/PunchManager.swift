import BackgroundTasks
import CoreLocation
import Foundation
import UserNotifications

/// iOS 打卡策略（系统不允许后台精确定时任务，组合多路兜底）：
///  1. 显著位置变化（SLC）：系统在设备明显移动时唤醒应用，若正处打卡窗口且缺记录则就地记录
///  2. BGAppRefreshTask：系统择机唤醒，窗口内补打
///  3. 每天 07:00 / 17:00 本地通知提醒，点开应用即补打
///  4. 应用进入前台时补打
/// 打卡时间精度低于 Android，属平台限制（见 README）。
final class PunchManager: NSObject, ObservableObject, CLLocationManagerDelegate {

    static let shared = PunchManager()
    static let refreshTaskId = "app.terndays.refresh"

    private let lm = CLLocationManager()
    @Published var authStatus: CLAuthorizationStatus = .notDetermined
    private var oneShotCompletion: ((CLLocation?) -> Void)?

    override private init() {
        super.init()
        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyHundredMeters
        lm.pausesLocationUpdatesAutomatically = true
        authStatus = lm.authorizationStatus
    }

    // MARK: 权限与常驻监听

    func requestWhenInUse() { lm.requestWhenInUseAuthorization() }
    func requestAlways() { lm.requestAlwaysAuthorization() }

    var hasAlways: Bool { lm.authorizationStatus == .authorizedAlways }
    var hasAnyAuth: Bool {
        lm.authorizationStatus == .authorizedAlways || lm.authorizationStatus == .authorizedWhenInUse
    }

    /// 引导完成后调用：开启 SLC、注册每日提醒、排后台刷新
    func activate() {
        if hasAlways {
            lm.startMonitoringSignificantLocationChanges()
        }
        scheduleDailyReminders()
        scheduleBackgroundRefresh()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        DispatchQueue.main.async { self.authStatus = manager.authorizationStatus }
        if manager.authorizationStatus == .authorizedAlways {
            manager.startMonitoringSignificantLocationChanges()
        }
    }

    // MARK: 打卡

    /// 窗口内缺记录则取一次定位记录；SLC 唤醒时会带现成位置，直接用
    func punchIfNeeded(with location: CLLocation? = nil, completion: (() -> Void)? = nil) {
        let now = Date()
        let today = LocalDate(from: now, in: .current)
        guard let slot = PunchRules.slotToBackfill(
            now: now,
            hasMorning: DataStore.shared.hasPunch(date: today, slot: .morning),
            hasEvening: DataStore.shared.hasPunch(date: today, slot: .evening)
        ) else {
            completion?()
            return
        }
        if let location {
            record(slot: slot, location: location, fromCache: false)
            completion?()
            return
        }
        requestOneShotLocation { [weak self] loc in
            guard let self else { completion?(); return }
            if let loc {
                self.record(slot: slot, location: loc, fromCache: false)
            } else if let cached = self.lm.location,
                      Date().timeIntervalSince(cached.timestamp) < 6 * 3600 {
                self.record(slot: slot, location: cached, fromCache: true)
            }
            completion?()
        }
    }

    private func requestOneShotLocation(_ completion: @escaping (CLLocation?) -> Void) {
        guard hasAnyAuth else { completion(nil); return }
        oneShotCompletion = completion
        lm.requestLocation()
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
            if let pending = self?.oneShotCompletion {
                self?.oneShotCompletion = nil
                pending(nil)
            }
        }
    }

    private func record(slot: Slot, location: CLLocation, fromCache: Bool) {
        let match = Cities.matcher.nearest(lat: location.coordinate.latitude, lng: location.coordinate.longitude)
        let now = Date()
        let punch = Punch(
            localDate: LocalDate(from: now, in: .current),
            slot: slot,
            epochMs: Int64(now.timeIntervalSince1970 * 1000),
            zoneId: TimeZone.current.identifier,
            lat: location.coordinate.latitude,
            lng: location.coordinate.longitude,
            accuracyM: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil,
            cityKey: match?.cityKey ?? "unknown",
            cityName: match?.cityName ?? "未知位置",
            delayed: PunchRules.isDelayed(at: now, slot: slot),
            fromCache: fromCache
        )
        DataStore.shared.insertPunch(punch)
        NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
    }

    // MARK: CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if let completion = oneShotCompletion {
            oneShotCompletion = nil
            completion(locations.last)
        } else if let loc = locations.last {
            // SLC 后台唤醒路径
            punchIfNeeded(with: loc)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if let completion = oneShotCompletion {
            oneShotCompletion = nil
            completion(nil)
        }
    }

    // MARK: 本地通知（07:00 / 17:00 提醒）

    func scheduleDailyReminders() {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            center.removePendingNotificationRequests(withIdentifiers: ["punch-morning", "punch-evening"])
            for (id, hour, text) in [
                ("punch-morning", PunchRules.morningHour, "早上 7 点：打开应用记录当前城市"),
                ("punch-evening", PunchRules.eveningHour, "下午 5 点：打开应用记录当前城市"),
            ] {
                let content = UNMutableNotificationContent()
                content.title = "TernDays 打卡"
                content.body = text
                var dc = DateComponents()
                dc.hour = hour
                dc.minute = 0
                let trigger = UNCalendarNotificationTrigger(dateMatching: dc, repeats: true)
                center.add(UNNotificationRequest(identifier: id, content: content, trigger: trigger))
            }
        }
    }

    // MARK: BGAppRefresh

    func registerBackgroundTask() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshTaskId, using: nil) { [weak self] task in
            guard let self, let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            self.scheduleBackgroundRefresh()
            refresh.expirationHandler = { refresh.setTaskCompleted(success: false) }
            self.punchIfNeeded { refresh.setTaskCompleted(success: true) }
        }
    }

    func scheduleBackgroundRefresh() {
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshTaskId)
        request.earliestBeginDate = PunchRules.nextPunchDate()
        try? BGTaskScheduler.shared.submit(request)
    }
}

extension Notification.Name {
    static let terndaysDataChanged = Notification.Name("terndaysDataChanged")
}
