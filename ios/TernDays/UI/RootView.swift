import SwiftUI

/// 轻量提示中心:后台任务(城市库重解析等)完成后给用户一句反馈,
/// 对齐 Android 的 Toast——不再静默改数据。
final class ToastCenter: ObservableObject {
    static let shared = ToastCenter()
    @Published var message: String?

    func show(_ text: String) {
        DispatchQueue.main.async { [weak self] in
            self?.message = text
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                if self?.message == text { self?.message = nil }
            }
        }
    }
}

struct CityRoute: Hashable {
    let cityKey: String
    let year: Int
}

struct RootView: View {
    @AppStorage("onboardingDone") private var onboardingDone = false
    @ObservedObject private var toast = ToastCenter.shared

    var body: some View {
        ZStack(alignment: .bottom) {
            if onboardingDone {
                NavigationStack {
                    HomeView()
                }
                .tint(Td.accentDeep)
            } else {
                OnboardingView {
                    onboardingDone = true
                    PunchManager.shared.activate()
                    PunchManager.shared.punchIfNeeded()
                }
            }
            if let msg = toast.message {
                Text(msg)
                    .font(.system(size: 13))
                    .foregroundColor(Td.onAccent)
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(Capsule().fill(Td.accent))
                    .padding(.bottom, 28)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: toast.message)
    }
}

/// 年度数据快照 + 加载
struct YearData {
    let stats: YearStats
    let punches: [Punch]
    let overrides: [DayOverride]
    let years: [Int]

    static func load(year: Int) -> YearData {
        let punches = DataStore.shared.punchesForYear(year)
        let overrides = DataStore.shared.overridesForYear(year)
        let stats = DayCounting.computeYearStats(
            year: year, today: LocalDate.today(), punches: punches, overrides: overrides
        )
        return YearData(
            stats: stats, punches: punches, overrides: overrides,
            years: DataStore.shared.yearsWithData(currentYear: LocalDate.today().year)
        )
    }
}
