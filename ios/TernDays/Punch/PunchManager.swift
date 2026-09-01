import BackgroundTasks
import CoreLocation
import Foundation
import UserNotifications
import WidgetKit

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
    /// 待决的一次性定位回调。只在主线程访问(CLLocationManager 在主线程创建,
    /// delegate 回调也在主线程),新请求追加而不是覆盖——BGAppRefresh 与开屏打卡
    /// 同时到来时,两个回调都会被兑现,后台任务不再因回调被覆盖而以失败收场。
    private var pendingLocationCallbacks: [(CLLocation?) -> Void] = []
    /// 代次标记:每次分发后 +1,让 30s 超时与迟到的 delegate 回调自动失效。
    private var locationGeneration = 0

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

    /// 窗口内缺记录则取一次定位记录；SLC 唤醒时会带现成位置，直接用。
    /// 从未有过任何记录（首次安装）时，无论时段立即打一个「首点」（extra）。
    func punchIfNeeded(with location: CLLocation? = nil, completion: (() -> Void)? = nil) {
        let now = Date()
        let today = LocalDate(from: now, in: .current)
        let backfill = PunchRules.slotToBackfill(
            now: now,
            hasMorning: DataStore.shared.hasPunch(date: today, slot: .morning),
            hasEvening: DataStore.shared.hasPunch(date: today, slot: .evening)
        )
        let firstPunch: Slot? = DataStore.shared.hasAnyPunch() ? nil : .extra
        guard let slot = backfill ?? firstPunch else {
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
            } else {
                // 完全拿不到位置:提醒用户打开应用补打(对齐 Android 的失败通知)
                self.notifyPunchFailed(slot: slot)
            }
            completion?()
        }
    }

    private func requestOneShotLocation(_ completion: @escaping (CLLocation?) -> Void) {
        guard hasAnyAuth else { completion(nil); return }
        DispatchQueue.main.async { [self] in
            pendingLocationCallbacks.append(completion)
            guard pendingLocationCallbacks.count == 1 else { return } // 已有请求在途,搭车等结果
            let generation = locationGeneration
            lm.requestLocation()
            DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
                guard let self, generation == self.locationGeneration else { return }
                self.flushLocationCallbacks(nil)
            }
        }
    }

    /// 主线程:一次性取出全部待决回调并分发;代次 +1 使超时/迟到回调作废。
    private func flushLocationCallbacks(_ location: CLLocation?) {
        locationGeneration += 1
        let callbacks = pendingLocationCallbacks
        pendingLocationCallbacks = []
        callbacks.forEach { $0(location) }
    }

    private func record(slot: Slot, location: CLLocation, fromCache: Bool) {
        // 交叉验证：top-3 候选 + 上一次打卡的行程连续性 + 定位误差圈，
        // 消掉真实边界（深圳/香港、珠海/澳门…）附近的最近邻模糊
        let accuracy = location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil
        let candidates = Cities.matcher.nearestByCity(
            lat: location.coordinate.latitude, lng: location.coordinate.longitude, k: 3
        )
        // 锚点 = 最近一条非改判打卡;当日有手动更正时以更正城市为准(防粘滞链自续期/覆盖用户判断)
        let prev = DataStore.shared.latestAnchorPunch().map { anchor in
            CityResolver.Prev(
                cityKey: DataStore.shared.overrideFor(date: anchor.localDate)?.cityKey ?? anchor.cityKey,
                ageHours: (Date().timeIntervalSince1970 * 1000 - Double(anchor.epochMs)) / 3_600_000
            )
        }
        let resolution = CityResolver.resolve(candidates: candidates, accuracyM: accuracy, prev: prev)
        let match = resolution?.match
        let now = Date()
        let punch = Punch(
            localDate: LocalDate(from: now, in: .current),
            slot: slot,
            epochMs: Int64(now.timeIntervalSince1970 * 1000),
            zoneId: TimeZone.current.identifier,
            lat: location.coordinate.latitude,
            lng: location.coordinate.longitude,
            accuracyM: accuracy,
            cityKey: match?.cityKey ?? "unknown",
            cityName: match?.cityName ?? "未知位置",
            delayed: PunchRules.isDelayed(at: now, slot: slot),
            fromCache: fromCache,
            viaContext: resolution?.viaContext ?? false
        )
        DataStore.shared.insertPunch(punch)
        WidgetCenter.shared.reloadAllTimelines()
        NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
    }

    // MARK: CLLocationManagerDelegate

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        if !pendingLocationCallbacks.isEmpty {
            flushLocationCallbacks(locations.last)
        } else if let loc = locations.last {
            // SLC 后台唤醒路径
            punchIfNeeded(with: loc)
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if !pendingLocationCallbacks.isEmpty {
            flushLocationCallbacks(nil)
        }
    }

    /// 打卡失败提醒:打开应用会自动补打,也可手动补记
    private func notifyPunchFailed(slot: Slot) {
        let label = slot == .morning ? "早上 7 点" : (slot == .evening ? "下午 5 点" : "首次")
        let content = UNMutableNotificationContent()
        content.title = "\(label)打卡没成功"
        content.body = "没拿到定位。打开 TernDays 会自动补打,也可在设置中手动补记。"
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "punch-failed", content: content, trigger: nil)
        )
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
                content.sound = .default
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
        BGTaskScheduler.shared.register(forTaskWithIdentifier: Self.refreshTaskId, using: .main) { [weak self] task in
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
