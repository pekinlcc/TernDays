import SwiftUI
import UIKit
import UserNotifications
import WidgetKit

@main
struct TernDaysApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    @AppStorage("onboardingDone") private var onboardingDone = false

    var body: some Scene {
        WindowGroup {
            RootView()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active && onboardingDone {
                // 冷启动若赶上锁屏(数据保护未解除),数据曾被封存为只读:回到前台先解封重读
                DataStore.shared.reloadIfSealed()
                PunchManager.shared.punchIfNeeded()
                PunchManager.shared.scheduleBackgroundRefresh()
                // 城市库升级后，按原始坐标重放修正历史误判（如深圳被判成香港），完成后提示条数
                Cities.reResolveHistoryIfNeeded { changed in
                    ToastCenter.shared.show("城市库已更新，自动修正了 \(changed) 条历史记录")
                }
                // 回到前台重新加载:后台打卡/小组件补记过的数据要立刻反映在界面上
                NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
            }
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    private var observers: [NSObjectProtocol] = []

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // 落盘失败要让用户看见:否则界面提示「已保存」,重启后改动却消失了
        DataStore.shared.onWriteFailure = { ToastCenter.shared.show($0) }
        UNUserNotificationCenter.current().delegate = self
        PunchManager.shared.registerBackgroundTask()
        if UserDefaults.standard.bool(forKey: "onboardingDone") {
            PunchManager.shared.activate()
        }
        let center = NotificationCenter.default
        // 锁屏冷启动时数据被封存,解锁那一刻就解封,不必等下一次回前台
        observers.append(center.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main
        ) { _ in
            guard DataStore.shared.isSealed, DataStore.shared.reloadIfSealed() else { return }
            WidgetCenter.shared.reloadAllTimelines()
            NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
        })
        // 换时区/跨天/改系统时间:「今天」「今年」都可能变了,界面与小组件立刻重算
        for name in [Notification.Name.NSSystemTimeZoneDidChange, UIApplication.significantTimeChangeNotification] {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { _ in
                WidgetCenter.shared.reloadAllTimelines()
                NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
            })
        }
        return true
    }

    // 应用在前台时系统默认不展示通知:打卡失败/权限提醒照样要让人看见
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }
}
