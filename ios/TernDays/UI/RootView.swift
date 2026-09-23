import SwiftUI

/// 轻量提示中心:后台任务(城市库重解析等)完成后给用户一句反馈,
/// 对齐 Android 的 Toast——不再静默改数据。
/// 可带一个动作按钮(「撤销」):带动作的提示停留约 5 秒,点了按钮立即执行并收起。
final class ToastCenter: ObservableObject {
    static let shared = ToastCenter()

    struct Toast: Equatable {
        let id: Int
        let text: String
        let actionTitle: String?

        static func == (a: Toast, b: Toast) -> Bool { a.id == b.id }
    }

    @Published private(set) var current: Toast?
    /// 动作闭包不进 Toast(闭包不能比较相等),只在主线程读写
    private var action: (() -> Void)?
    private var counter = 0

    /// 兼容旧调用点:当前提示的文字
    var message: String? { current?.text }

    func show(_ text: String, actionTitle: String? = nil, action: (() -> Void)? = nil) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.counter += 1
            let toast = Toast(id: self.counter, text: text, actionTitle: action == nil ? nil : actionTitle)
            self.current = toast
            self.action = action
            let life: Double = action == nil ? 4 : 5
            DispatchQueue.main.asyncAfter(deadline: .now() + life) { [weak self] in
                guard let self, self.current?.id == toast.id else { return }
                self.current = nil
                self.action = nil
            }
        }
    }

    /// 点了提示上的按钮:先收起,再执行(执行里可能再弹一条新提示)
    func performAction() {
        let run = action
        action = nil
        current = nil
        run?()
    }
}

/// 提示条。根视图与仍开着的 sheet(连续补记)各挂一份:sheet 盖住根视图时也看得见。
struct ToastHost: ViewModifier {
    @ObservedObject private var toast = ToastCenter.shared

    func body(content: Content) -> some View {
        ZStack(alignment: .bottom) {
            content
            if let t = toast.current {
                HStack(spacing: 14) {
                    Text(t.text)
                        .font(.system(size: 13))
                        .foregroundColor(Td.onAccent)
                        .fixedSize(horizontal: false, vertical: true)
                    if let title = t.actionTitle {
                        Button {
                            toast.performAction()
                        } label: {
                            Text(title)
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(Td.onAccent)
                                .frame(minWidth: 44, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, t.actionTitle == nil ? 10 : 0)
                .background(Capsule().fill(Td.accent))
                .padding(.horizontal, 20)
                .padding(.bottom, 28)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: toast.current)
    }
}

extension View {
    func toastHost() -> some View { modifier(ToastHost()) }
}

struct CityRoute: Hashable {
    let cityKey: String
    let year: Int
}

struct RootView: View {
    @AppStorage("onboardingDone") private var onboardingDone = false

    var body: some View {
        // 用 ZStack 而不是 Group:修饰符挂在 Group 上会分发到每个子视图
        ZStack {
            if onboardingDone {
                NavigationStack {
                    HomeView()
                }
                .tint(Td.accentDeep)
            } else {
                OnboardingView {
                    DataStore.shared.markOnboarded()
                    onboardingDone = true
                    PunchManager.shared.activate()
                    PunchManager.shared.punchIfNeeded()
                }
            }
        }
        .toastHost()
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
        // 传入当前小时:今天还没打完的半天不算漏记,单点先按 0.5 天计
        let stats = DayCounting.computeYearStats(
            year: year, today: LocalDate.today(), punches: punches, overrides: overrides,
            nowHour: Calendar.current.component(.hour, from: Date()),
            earliestRecordDate: DataStore.shared.earliestRecordDate()
        )
        return YearData(
            stats: stats, punches: punches, overrides: overrides,
            years: DataStore.shared.yearsWithData(currentYear: LocalDate.today().year)
        )
    }
}
