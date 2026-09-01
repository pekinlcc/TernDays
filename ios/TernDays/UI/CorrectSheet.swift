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
    let onPick: (String, String) -> Void
    let onRestoreAuto: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let currentCityName {
                        Text("当前判定：\(currentCityName)。选正确的城市后，这一天将按所选城市记全天。")
                            .font(.system(size: 13)).foregroundColor(Td.muted)
                    } else {
                        Text("这一天还没有记录，选择城市后按全天补记。")
                            .font(.system(size: 13)).foregroundColor(Td.muted)
                    }
                }
                Section(query.isEmpty && !recentCities.isEmpty ? "常去城市" : "选择城市") {
                    TextField("搜索城市名", text: $query)
                    let options = query.isEmpty
                        ? recentCities
                        : Cities.matcher.search(name: query, limit: 12).map { ($0.key, $0.name) }
                    ForEach(options, id: \.0) { key, name in
                        Button(name) { onPick(key, name) }
                            .foregroundColor(Td.ink)
                    }
                }
                if hasOverride {
                    Section {
                        Button("恢复自动判定") { onRestoreAuto() }
                            .foregroundColor(Td.warmDeep)
                    }
                }
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
