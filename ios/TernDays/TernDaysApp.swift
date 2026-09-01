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
