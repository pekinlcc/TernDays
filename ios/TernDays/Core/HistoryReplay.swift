import Foundation

/// 城市库升级/导入后的历史重解析(与 Android :core HistoryReplay 同一套规则):
/// 按打卡时间重放整条时间线,每条走与实时打卡相同的 CityResolver 交叉验证,
/// 而不是裸最近邻;锚点 = 最近一条非改判结果,当日手动更正优先作准。
enum HistoryReplay {

    /// 重放并落盘。@return 城市被修正的条数。
    static func replayAll(store: DataStore, matcher: CityMatcher) -> Int {
        let punches = store.allPunches().sorted { $0.epochMs < $1.epochMs }
        guard !punches.isEmpty else { return 0 }
        let overrideByDate = Dictionary(
            uniqueKeysWithValues: store.allOverrides().map { ($0.localDate, $0.cityKey) }
        )

        var outcomes: [(date: LocalDate, slot: Slot, key: String, name: String, via: Bool)] = []
        var anchorKey: String?
        var anchorEpochMs: Int64 = 0
        for p in punches {
            let candidates = matcher.nearestByCity(lat: p.lat, lng: p.lng, k: 3)
            let prev = anchorKey.map {
                CityResolver.Prev(cityKey: $0, ageHours: Double(p.epochMs - anchorEpochMs) / 3_600_000)
            }
            let resolution = CityResolver.resolve(candidates: candidates, accuracyM: p.accuracyM, prev: prev)
            let newKey = resolution?.match.cityKey ?? p.cityKey
            let newName = resolution?.match.cityName ?? p.cityName
            let via = resolution?.viaContext ?? false
            outcomes.append((p.localDate, p.slot, newKey, newName, via))
            if let overrideKey = overrideByDate[p.localDate] {
                anchorKey = overrideKey
                anchorEpochMs = p.epochMs
            } else if !via {
                anchorKey = newKey
                anchorEpochMs = p.epochMs
            }
        }
        return store.applyResolveOutcomes(outcomes)
    }
}
