import Foundation

/// 城市库升级/导入后的历史重解析(与 Android :core HistoryReplay 同一套规则):
/// 按打卡时间重放整条时间线,每条走与实时打卡相同的 CityResolver 交叉验证,
/// 而不是裸最近邻;锚点 = 最近一条非改判结果,当日手动更正优先作准。
enum HistoryReplay {

    /// 重放并落盘。@return 城市被修正的条数。
    static func replayAll(store: DataStore, matcher: CityMatcher) -> Int {
        let punches = store.allPunches().sorted { $0.epochMs < $1.epochMs }
        guard !punches.isEmpty else { return 0 }
        // 只认整天更正(与 Android PunchDb.replayResolveAll 同口径);
        // 同一天可以同时有上/下半天两条更正,uniqueKeysWithValues 会因重复键直接 trap,
        // 这里先按 scope 过滤再用 uniquingKeysWith 兜底,任何重复都不会崩。
        let overrideByDate = Dictionary(
            store.allOverrides().filter { $0.scope == .full }.map { ($0.localDate, $0.cityKey) },
            uniquingKeysWith: { first, _ in first }
        )

        // 同一个地方(家、公司)天天打卡,坐标几乎不变:最近邻按 1e-4°(约 11 米)缓存一次重放内的结果
        // (与 Android 同一键):3.4 万点全表扫描不再逐条重复,结果逐条不变
        // (1e-4° 以内的两个点,top-3 候选与距离差异远小于判定边距)。
        var memo: [Int64: [CityMatcher.Match]] = [:]
        func candidatesFor(_ lat: Double, _ lng: Double) -> [CityMatcher.Match] {
            // 导入的数据可能带着离谱坐标:转 Int64 前先挡住,否则溢出直接崩
            guard lat.isFinite, lng.isFinite, abs(lat) <= 90, abs(lng) <= 180 else {
                return matcher.nearestByCity(lat: lat, lng: lng, k: 3)
            }
            let k = Int64((lat * 10_000).rounded()) * 4_000_000 + Int64((lng * 10_000).rounded())
            if let hit = memo[k] { return hit }
            let found = matcher.nearestByCity(lat: lat, lng: lng, k: 3)
            memo[k] = found
            return found
        }

        var outcomes: [(date: LocalDate, slot: Slot, key: String, name: String, via: Bool)] = []
        var anchorKey: String?
        var anchorEpochMs: Int64 = 0
        for p in punches {
            let candidates = candidatesFor(p.lat, p.lng)
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
        // 手动更正里存的城市名也跟着城市库走(与 Android PunchDb.replayResolveAll 同口径)
        return store.applyResolveOutcomes(outcomes) + store.refreshOverrideNames { matcher.nameOf($0) }
    }
}
