import SwiftUI

struct CityRoute: Hashable {
    let cityKey: String
    let year: Int
}

struct RootView: View {
    @AppStorage("onboardingDone") private var onboardingDone = false

    var body: some View {
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
