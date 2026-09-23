import SwiftUI

/// 更正某一天的城市:搜索 / 建议城市里选一个 → 以手动更正(DayOverride)记录,优先于自动打卡判定;
/// 已更正的日子可「恢复自动判定」。写入统一走 Corrections(写后 toast 带「撤销」)。
/// 首页「纠正」与城市详情日历共用这一份。
struct CityCorrectSheet: View {
    let context: DayContext
    /// 写入或恢复之后调用(调用方负责收起 sheet)
    let onDone: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var scope: OverrideScope = .full
    @State private var confirmRestore = false

    private var date: LocalDate { context.date }

    /// 已经改过半天的日子重新打开时停在那个半天上
    private var initialScope: OverrideScope {
        context.overrides.first { $0.scope != .full }?.scope ?? .full
    }

    private var manualNow: String {
        context.overrides.sorted { $0.scope.rawValue < $1.scope.rawValue }.map {
            switch $0.scope {
            case .full: return "整天 → \($0.cityName)"
            case .morning: return "上半天 → \($0.cityName)"
            case .evening: return "下半天 → \($0.cityName)"
            }
        }.joined(separator: "；")
    }

    private var hint: String {
        let current = context.currentCityName ?? ""
        switch scope {
        case .full:
            if current.isEmpty { return "这一天还没有记录，选择城市后按全天补记。" }
            return "当前判定：\(current)。选正确的城市后，这一天将按所选城市记全天。"
        case .morning:
            return "只改上半天（早上那次）：下半天仍按打卡判定，跨城日可保留各半天。"
        case .evening:
            return "只改下半天（傍晚那次）：上半天仍按打卡判定，跨城日可保留各半天。"
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if !manualNow.isEmpty {
                        Text("已手动更正：\(manualNow)")
                            .font(.system(size: 12)).foregroundColor(Td.warmDeep)
                    }
                    Text(hint)
                        .font(.system(size: 13)).foregroundColor(Td.muted)
                    if context.allowHalfScope {
                        Picker("更正范围", selection: $scope) {
                            Text("整天").tag(OverrideScope.full)
                            Text("只改上半天").tag(OverrideScope.morning)
                            Text("只改下半天").tag(OverrideScope.evening)
                        }
                        .pickerStyle(.segmented)
                    }
                }
                Section(context.suggestions.isEmpty ? "选择城市" : "选择城市 · 前后一天与常去的在前") {
                    CityPicker(suggestions: context.suggestions) { key, name in pick(key, name) }
                }
                if context.hasOverride {
                    Section {
                        Button("恢复自动判定") {
                            // 这一天没有任何打卡:恢复后就是空的,先问一句
                            if context.hasPunches {
                                restoreAuto()
                            } else {
                                confirmRestore = true
                            }
                        }
                        .foregroundColor(Td.warmDeep)
                        .tapTarget()
                    }
                }
            }
            .onAppear { scope = initialScope }
            .navigationTitle("更正 \(date.month)月\(date.day)日")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
            .alert("恢复自动判定？", isPresented: $confirmRestore) {
                Button("恢复", role: .destructive) { restoreAuto() }
                Button("取消", role: .cancel) {}
            } message: {
                Text("恢复后这一天将变为无记录")
            }
        }
    }

    private func pick(_ key: String, _ name: String) {
        let s = context.allowHalfScope ? scope : .full
        Corrections.set(
            DayOverride(localDate: date, cityKey: key, cityName: name, scope: s),
            toast: "已改为 \(name)"
        )
        onDone()
    }

    private func restoreAuto() {
        Corrections.restoreAuto(date: date, toast: "已恢复自动判定")
        onDone()
    }
}
