import BackgroundTasks
import CoreLocation
import Foundation
import UIKit
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
    /// 每日提醒的通知类别与「就记在这里」动作(后台执行,不打开应用)
    static let punchCategoryId = "punch"
    static let punchHereActionId = "punch-here"
    private static let reminderIds = ["punch-morning", "punch-evening"]

    private let lm = CLLocationManager()
    @Published var authStatus: CLAuthorizationStatus = .notDetermined
    /// 用户在系统里关了「精确位置」:城市级判定会漂到几公里外,设置页要提示
    @Published var accuracyReduced = false
    /// 暂停自动打卡(App Group 持久化,见 AppPrefs.punchPaused)
    @Published private(set) var paused = AppPrefs.punchPaused
    /// 最近一次自动打卡尝试的结论(首页「最近一次尝试 …」)
    @Published private(set) var lastAttempt: LastAttempt? = LastAttempt.load()
    /// 待决的一次性定位回调。只在主线程访问(CLLocationManager 在主线程创建,
    /// delegate 回调也在主线程),新请求追加而不是覆盖——BGAppRefresh 与开屏打卡
    /// 同时到来时,两个回调都会被兑现,后台任务不再因回调被覆盖而以失败收场。
    private var pendingLocationCallbacks: [(CLLocation?) -> Void] = []
    /// 代次标记:每次分发后 +1,让 30s 超时与迟到的 delegate 回调自动失效。
    private var locationGeneration = 0
    /// 当前在途定位请求的发起时刻(用来识别被挂起后遗留的老请求)
    private var locationRequestedAt: Date?

    override private init() {
        super.init()
        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyHundredMeters
        lm.pausesLocationUpdatesAutomatically = true
        authStatus = lm.authorizationStatus
        accuracyReduced = lm.accuracyAuthorization == .reducedAccuracy
    }

    // MARK: 权限与常驻监听

    func requestWhenInUse() { lm.requestWhenInUseAuthorization() }
    func requestAlways() {
        UserDefaults.standard.set(true, forKey: Self.askedAlwaysKey)
        lm.requestAlwaysAuthorization()
    }

    private static let askedAlwaysKey = "askedAlwaysLocation"

    /// 按当前授权状态走「下一步」:从未问过 → 弹「使用期间」;只有使用期间且还没问过始终 → 弹「始终允许」;
    /// 其余(被拒、已问过始终但用户没给)→ 去系统设置。
    /// 引导页点了「稍后再说」的用户此前在应用里再也没有地方能发起授权,只能被带到一个没有「位置」项的系统设置页。
    func fixLocationPermission() {
        switch lm.authorizationStatus {
        case .notDetermined:
            lm.requestWhenInUseAuthorization()
        case .authorizedWhenInUse where !UserDefaults.standard.bool(forKey: Self.askedAlwaysKey):
            requestAlways()
        default:
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        }
    }

    /// 首页警示卡与设置页用的一句话原因;nil = 已是「始终允许」
    var locationIssue: String? {
        switch authStatus {
        case .authorizedAlways: return nil
        case .notDetermined: return "还没有定位权限，点击授权"
        case .authorizedWhenInUse: return "定位权限未设为「始终允许」，点击完成设置"
        default: return "定位权限被关闭，点击去系统设置打开"
        }
    }

    var hasAlways: Bool { lm.authorizationStatus == .authorizedAlways }
    var hasAnyAuth: Bool {
        lm.authorizationStatus == .authorizedAlways || lm.authorizationStatus == .authorizedWhenInUse
    }

    /// 引导完成后调用：开启 SLC、注册每日提醒、排后台刷新。
    /// 暂停期间反过来:停 SLC、撤掉待发的每日提醒、取消后台刷新(旧手机留作备用时不再各记各的)。
    func activate() {
        if AppPrefs.punchPaused {
            lm.stopMonitoringSignificantLocationChanges()
            UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: Self.reminderIds)
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.refreshTaskId)
            return
        }
        if hasAlways {
            lm.startMonitoringSignificantLocationChanges()
        }
        scheduleDailyReminders()
        scheduleBackgroundRefresh()
    }

    /// 暂停 / 恢复自动打卡。恢复时重新 activate,并立刻按当前时段补打一次。
    func setPaused(_ on: Bool) {
        AppPrefs.punchPaused = on
        DispatchQueue.main.async { self.paused = on }
        activate()
        if !on { punchIfNeeded() }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let reduced = manager.accuracyAuthorization == .reducedAccuracy
        DispatchQueue.main.async {
            self.authStatus = manager.authorizationStatus
            self.accuracyReduced = reduced
        }
        if manager.authorizationStatus == .authorizedAlways && !AppPrefs.punchPaused {
            manager.startMonitoringSignificantLocationChanges()
        }
    }

    /// 记下这次尝试的结论(App Group 持久化 + 首页即时刷新)
    private func noteAttempt(_ outcome: LastAttempt.Outcome, slot: Slot?, detail: String = "") {
        let attempt = LastAttempt.record(outcome, slot: slot, detail: detail)
        DispatchQueue.main.async { self.lastAttempt = attempt }
    }

    /// 清除全部数据后:最近一次尝试也一并清掉
    func clearLastAttempt() {
        LastAttempt.clear()
        DispatchQueue.main.async { self.lastAttempt = nil }
    }

    // MARK: 打卡

    /// 窗口内缺记录则取一次定位记录；SLC 唤醒时会带现成位置，直接用。
    /// 从未有过任何记录（首次安装）时，无论时段立即打一个「首点」（extra）。
    func punchIfNeeded(with location: CLLocation? = nil, completion: (() -> Void)? = nil) {
        // SLC 唤醒与前台触发都没有 BG 任务兜底:整段「取定位→解析→落盘」包在后台时间里,
        // 否则应用刚被切走,系统就可能在落盘前把进程挂起
        let bgTask = BackgroundTaskBox()
        bgTask.begin()
        punchIfNeededInner(with: location) {
            completion?()
            bgTask.end()
        }
    }

    private func punchIfNeededInner(with location: CLLocation?, completion: (() -> Void)?) {
        // 暂停期间:SLC / 后台刷新 / 通知动作都可能还会把应用拉起来,一律不打
        if AppPrefs.punchPaused {
            noteAttempt(.paused, slot: nil)
            completion?()
            return
        }
        // 数据文件还读不出来(重启后首次解锁前被后台拉起):此时既存不进去,
        // 也会把「空库」误当成首次安装去打首点。先试着解封,解不开就这次不打。
        guard DataStore.shared.reloadIfSealed() else {
            noteAttempt(.sealed, slot: nil)
            completion?()
            return
        }
        let decidedAt = Date()
        let today = LocalDate(from: decidedAt, in: .current)
        let backfill = PunchRules.slotToBackfill(
            now: decidedAt,
            hasMorning: DataStore.shared.hasPunch(date: today, slot: .morning),
            hasEvening: DataStore.shared.hasPunch(date: today, slot: .evening)
        )
        let firstPunch: Slot? = DataStore.shared.hasAnyPunch() ? nil : .extra
        guard let slot = backfill ?? firstPunch else {
            completion?()
            return
        }
        // 归属日期/时段/延迟都在这一刻定下:23:59 决定的晚点若 00:01 才拿到定位,
        // 也仍然记在昨天的晚点上,不会跑去占第二天的槽位
        let decision = Decision(
            date: today,
            slot: slot,
            delayed: PunchRules.isDelayed(at: decidedAt, slot: slot),
            decidedAt: decidedAt
        )
        if let location {
            // completion 等落盘之后再调:后台任务一报完成,系统随时可能挂起应用
            record(decision, location: location, fromCache: false, then: completion)
            return
        }
        requestOneShotLocation { [weak self] loc in
            guard let self else { completion?(); return }
            // 在途期间应用可能被挂起、跨过了窗口或零点:旧决策作废,按现在重新判断
            if Date().timeIntervalSince(decidedAt) > 120, !decision.stillValid(at: Date()) {
                self.punchIfNeededInner(with: nil, completion: completion)
                return
            }
            if let loc {
                self.record(decision, location: loc, fromCache: false, then: completion)
            } else if let cached = self.lm.location,
                      cached.timestamp <= Date(),
                      decidedAt.timeIntervalSince(cached.timestamp) < 6 * 3600 {
                self.record(decision, location: cached, fromCache: true, then: completion)
            } else {
                if !self.hasAnyAuth {
                    // 权限被关掉是另一回事:提示要说清原因,别让用户以为是定位没搜到
                    self.noteAttempt(.noPermission, slot: slot)
                    self.notifyPermissionMissing()
                } else if !self.hasAlways, UIApplication.shared.applicationState != .active {
                    // 只有「使用期间」:应用在后台时拿不到位置,原因要说对
                    self.noteAttempt(.needsAlways, slot: slot)
                    self.notifyNeedsAlways()
                } else {
                    // 完全拿不到位置:提醒用户打开应用补打(对齐 Android 的失败通知)
                    self.noteAttempt(.noLocation, slot: slot)
                    self.notifyPunchFailed(slot: slot)
                }
                completion?()
            }
        }
    }

    /// 一次打卡的「决策上下文」,与 Android PunchService.Decision 同义。
    private struct Decision {
        let date: LocalDate
        let slot: Slot
        let delayed: Bool
        let decidedAt: Date

        /// 拖了很久才拿到定位时,这次决策是否仍然成立(同一天、仍在该时段窗口内;首点不受窗口限制)
        func stillValid(at now: Date) -> Bool {
            guard LocalDate(from: now, in: .current) == date else { return false }
            return slot == .extra || PunchRules.slotInWindow(at: now) == slot
        }
    }

    private func requestOneShotLocation(_ completion: @escaping (CLLocation?) -> Void) {
        guard hasAnyAuth else { completion(nil); return }
        DispatchQueue.main.async { [self] in
            pendingLocationCallbacks.append(completion)
            // 已有请求在途就搭车等结果;但在途请求如果已经很老(应用被挂起过),重新发起一次
            let inFlightIsStale = locationRequestedAt.map { Date().timeIntervalSince($0) > 45 } ?? true
            guard pendingLocationCallbacks.count == 1 || inFlightIsStale else { return }
            locationGeneration += 1 // 旧请求的超时作废
            let generation = locationGeneration
            locationRequestedAt = Date()
            lm.requestLocation()
            DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in
                guard let self, generation == self.locationGeneration else { return }
                self.flushLocationCallbacks(nil)
            }
        }
    }

    /// 取消在途定位请求:代次 +1 让迟到回调作废,待决回调按失败收尾。
    func cancelPendingLocation() {
        DispatchQueue.main.async { [self] in
            guard !pendingLocationCallbacks.isEmpty else { return }
            flushLocationCallbacks(nil)
        }
    }

    /// 主线程:一次性取出全部待决回调并分发;代次 +1 使超时/迟到回调作废。
    private func flushLocationCallbacks(_ location: CLLocation?) {
        locationGeneration += 1
        locationRequestedAt = nil
        let callbacks = pendingLocationCallbacks
        pendingLocationCallbacks = []
        callbacks.forEach { $0(location) }
    }

    /// 城市库解析(3.4 万点最近邻)与 JSON 全量落盘都不该占主线程;
    /// `then` 在落盘并通知界面之后才调——后台任务靠它判断「做完了」。
    private func record(_ decision: Decision, location: CLLocation, fromCache: Bool, then done: (() -> Void)? = nil) {
        DispatchQueue.global(qos: .userInitiated).async { [self] in
            let saved = recordSync(decision, location: location, fromCache: fromCache)
            if let city = saved {
                noteAttempt(.recorded, slot: decision.slot, detail: city)
                // 天数变了:看看有没有阈值接近 / 达到(同一窗口期「接近」「达到」各提醒一次)
                ThresholdAlerts.checkAndNotify()
            }
            DispatchQueue.main.async {
                if saved != nil {
                    WidgetCenter.shared.reloadAllTimelines()
                    NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
                    // 打上了:撤掉这个时段的旧失败提醒
                    UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["punch-failed"])
                }
                done?()
            }
        }
    }

    /// @return 记下的城市名;没有新插入(该时段已有记录)返回 nil
    @discardableResult
    private func recordSync(_ decision: Decision, location: CLLocation, fromCache: Bool) -> String? {
        let slot = decision.slot
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
        // 打卡时刻取定位的实际时刻(缓存兜底时可能早几小时),不取落库那一刻
        let at = min(location.timestamp, Date())
        let punch = Punch(
            localDate: decision.date,
            slot: slot,
            epochMs: Int64(at.timeIntervalSince1970 * 1000),
            zoneId: TimeZone.current.identifier,
            lat: location.coordinate.latitude,
            lng: location.coordinate.longitude,
            accuracyM: accuracy,
            cityKey: match?.cityKey ?? "unknown",
            cityName: match?.cityName ?? "未知位置",
            delayed: decision.delayed,
            fromCache: fromCache,
            viaContext: resolution?.viaContext ?? false
        )
        return DataStore.shared.insertPunch(punch) ? punch.cityName : nil
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

    /// 只有「使用期间」、应用在后台时拿不到位置:说清原因,每天最多提醒一次
    private func notifyNeedsAlways() {
        let key = "needsAlwaysNotified"
        let today = LocalDate.today().description
        guard UserDefaults.standard.string(forKey: key) != today else { return }
        UserDefaults.standard.set(today, forKey: key)
        let content = UNMutableNotificationContent()
        content.title = "后台自动打卡需要「始终允许」"
        content.body = "定位权限现在是「使用 App 期间」,到点时应用在后台拿不到位置。请在系统设置里把 TernDays 的位置权限改为「始终」。"
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "punch-needs-always", content: content, trigger: nil)
        )
    }

    /// 定位权限被关掉时的提醒(与"没搜到定位"区分开)
    private func notifyPermissionMissing() {
        let content = UNMutableNotificationContent()
        content.title = "打卡需要定位权限"
        content.body = "定位权限被关闭了,请到系统设置里把 TernDays 的位置权限改为「始终」。"
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "punch-noauth", content: content, trigger: nil)
        )
    }

    /// 打卡失败提醒:打开应用会自动补打,也可手动补记
    private func notifyPunchFailed(slot: Slot) {
        let label = slot == .morning ? "早上 7 点" : (slot == .evening ? "下午 5 点" : "首次")
        let content = UNMutableNotificationContent()
        content.title = "\(label)打卡没成功"
        content.body = "没拿到定位。打开 TernDays 会立即补打;已过窗口的话可在首页点「纠正」手动指定城市。"
        content.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: "punch-failed", content: content, trigger: nil)
        )
    }

    // MARK: 本地通知（07:00 / 17:00 提醒）

    /// 通知类别:每日提醒上长按 / 下拉出「就记在这里」,后台直接打卡,不必打开应用。
    /// options 为空(不带 .foreground):点了不拉起界面,由 AppDelegate 在后台跑一次打卡。
    func registerNotificationCategories() {
        let here = UNNotificationAction(identifier: Self.punchHereActionId, title: "就记在这里", options: [])
        let category = UNNotificationCategory(
            identifier: Self.punchCategoryId, actions: [here], intentIdentifiers: [], options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    /// 每日提醒:可在设置里关掉,或改为静默(不响铃、不亮屏,只进通知中心)。暂停期间不排。
    func scheduleDailyReminders() {
        let center = UNUserNotificationCenter.current()
        guard AppPrefs.remindersOn, !AppPrefs.punchPaused else {
            center.removePendingNotificationRequests(withIdentifiers: Self.reminderIds)
            return
        }
        let silent = AppPrefs.remindersSilent
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            guard granted else { return }
            center.removePendingNotificationRequests(withIdentifiers: Self.reminderIds)
            for (id, hour, text) in [
                ("punch-morning", PunchRules.morningHour, "早上 7 点：打开应用记录当前城市"),
                ("punch-evening", PunchRules.eveningHour, "下午 5 点：打开应用记录当前城市"),
            ] {
                let content = UNMutableNotificationContent()
                content.title = "TernDays 打卡"
                content.body = text
                content.categoryIdentifier = Self.punchCategoryId
                if silent {
                    content.sound = nil
                    content.interruptionLevel = .passive
                } else {
                    content.sound = .default
                }
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
            // setTaskCompleted 调用两次会 crash:到期与完成竞争时只让第一个生效,
            // 并且到期时取消在途定位请求(否则后台配额白白耗着)
            var finished = false
            let finish: (Bool) -> Void = { ok in
                guard !finished else { return }
                finished = true
                refresh.setTaskCompleted(success: ok)
            }
            refresh.expirationHandler = {
                self.cancelPendingLocation()
                finish(false)
            }
            self.punchIfNeeded { finish(true) }
        }
    }

    func scheduleBackgroundRefresh() {
        guard !AppPrefs.punchPaused else { return }
        let request = BGAppRefreshTaskRequest(identifier: Self.refreshTaskId)
        request.earliestBeginDate = PunchRules.nextPunchDate()
        try? BGTaskScheduler.shared.submit(request)
    }
}

extension Notification.Name {
    static let terndaysDataChanged = Notification.Name("terndaysDataChanged")
}

/// 一段需要后台时间的工作:begin/end 各一次,过期回调与正常结束谁先到都只 end 一次。
private final class BackgroundTaskBox {
    private let lock = NSLock()
    private var id: UIBackgroundTaskIdentifier = .invalid

    func begin() {
        let newId = UIApplication.shared.beginBackgroundTask(withName: "terndays.punch") { [weak self] in
            self?.end()
        }
        lock.lock(); id = newId; lock.unlock()
    }

    func end() {
        lock.lock()
        let old = id
        id = .invalid
        lock.unlock()
        if old != .invalid { UIApplication.shared.endBackgroundTask(old) }
    }
}
