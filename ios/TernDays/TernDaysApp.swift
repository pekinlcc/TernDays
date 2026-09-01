import SwiftUI
import UIKit

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

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        PunchManager.shared.registerBackgroundTask()
        if UserDefaults.standard.bool(forKey: "onboardingDone") {
            PunchManager.shared.activate()
        }
        return true
    }
}
