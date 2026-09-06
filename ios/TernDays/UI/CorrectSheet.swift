import SwiftUI

/// sheet(item:) 的包装：要更正的那一天
struct CorrectTarget: Identifiable {
    let date: LocalDate
    var id: String { "\(date.year)-\(date.month)-\(date.day)" }
}

/// 更正某一天的城市：搜索/常去城市里选一个 → 以手动更正（DayOverride）记录，
/// 优先于自动打卡判定；已更正的日子可「恢复自动判定」。
struct CityCorrectSheet: View {
    let date: LocalDate
    let currentCityName: String?
    let recentCities: [(String, String)]
    let hasOverride: Bool
    /// 该日是否有半天样本:有一个就允许半天更正(进行中的今天只打了早点也算)
    var hasBothHalves: Bool = false
    /// 该日已有的手动更正:重新打开时照原样回填,不把半天更正静默升级成整天
    var existing: [DayOverride] = []
    let onPick: (String, String, OverrideScope) -> Void
    let onRestoreAuto: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var scope: OverrideScope = .full
    /// 搜索在后台线程跑:3.4 万行全表扫描不该卡住输入
    @State private var hits: [CityMatcher.SearchHit] = []

    /// 已经改过半天的日子重新打开时停在那个半天上
    private var initialScope: OverrideScope {
        existing.first { $0.scope != .full }?.scope ?? .full
    }

    private var manualNow: String {
        existing.sorted { $0.scope.rawValue < $1.scope.rawValue }.map {
            switch $0.scope {
            case .full: return "整天 → \($0.cityName)"
            case .morning: return "上半天 → \($0.cityName)"
            case .evening: return "下半天 → \($0.cityName)"
            }
        }.joined(separator: "；")
    }

    private var hint: String {
        guard let currentCityName, !currentCityName.isEmpty else {
            return "这一天还没有记录，选择城市后按全天补记。"
        }
        switch scope {
        case .full: return "当前判定：\(currentCityName)。选正确的城市后，这一天将按所选城市记全天。"
        case .morning: return "只改上半天（早上那次）：下半天仍按打卡判定，跨城日可保留各半天。"
        case .evening: return "只改下半天（傍晚那次）：上半天仍按打卡判定，跨城日可保留各半天。"
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
                    if hasBothHalves {
                        Picker("更正范围", selection: $scope) {
                            Text("整天").tag(OverrideScope.full)
                            Text("只改上半天").tag(OverrideScope.morning)
                            Text("只改下半天").tag(OverrideScope.evening)
                        }
                        .pickerStyle(.segmented)
                    }
                }
                Section(query.isEmpty && !recentCities.isEmpty ? "常去城市" : "选择城市") {
                    TextField("搜索城市名（支持拼音）", text: $query)
                    if query.isEmpty {
                        ForEach(recentCities, id: \.0) { key, name in
                            Button(name) { onPick(key, name, scope) }
                                .foregroundColor(Td.ink)
                        }
                    } else {
                        ForEach(hits, id: \.cityKey) { hit in
                            Button {
                                onPick(hit.cityKey, hit.cityName, scope)
                            } label: {
                                HStack {
                                    Text(hit.cityName).foregroundColor(Td.ink)
                                    if !hit.region.isEmpty {
                                        Text(hit.region).font(.system(size: 12)).foregroundColor(Td.faint)
                                    }
                                }
                            }
                        }
                    }
                }
                if hasOverride {
                    Section {
                        Button("恢复自动判定") { onRestoreAuto() }
                            .foregroundColor(Td.warmDeep)
                    }
                }
            }
            .onAppear { scope = initialScope }
            .task(id: query) {
                let q = query
                guard !q.isEmpty else { hits = []; return }
                let found = await Task.detached(priority: .userInitiated) {
                    Cities.matcher.searchHits(q, limit: 12)
                }.value
                if q == query { hits = found }
            }
            .navigationTitle("更正 \(date.month)月\(date.day)日")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }
}
