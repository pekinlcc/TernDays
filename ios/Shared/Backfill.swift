import Foundation

/// 按区间补记:出差回来一次补上一整段(与 Android :core Backfill 同一口径)。
///
/// 首日可以只补下半天(当天下午才到),末日可以只补上半天(当天中午就走了),
/// 中间的日子一律整天。写入时与已有更正的互斥规则见 merge(与存储层 setOverride 同一口径)。
enum Backfill {

    /// 文案可直接展示
    struct PlanError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    /// - Parameters:
    ///   - startScope: 首日范围:full 或 evening(下午才到)
    ///   - endScope: 末日范围:full 或 morning(中午就走)
    /// - Throws: 区间倒置、范围不合法,或单日同时选了「下午才到」和「中午就走」
    static func planRange(
        from: LocalDate,
        to: LocalDate,
        cityKey: String,
        cityName: String,
        startScope: OverrideScope = .full,
        endScope: OverrideScope = .full
    ) throws -> [DayOverride] {
        if to < from { throw PlanError(message: "结束日期不能早于开始日期") }
        if startScope == .morning { throw PlanError(message: "首日只能整天或下半天") }
        if endScope == .evening { throw PlanError(message: "末日只能整天或上半天") }
        if from == to {
            if startScope != .full && endScope != .full {
                throw PlanError(message: "同一天不能既「下午才到」又「中午就走」")
            }
            let scope = startScope != .full ? startScope : endScope
            return [DayOverride(localDate: from, cityKey: cityKey, cityName: cityName, scope: scope)]
        }
        var out: [DayOverride] = []
        var d = from
        while d <= to {
            let scope: OverrideScope
            if d == from {
                scope = startScope
            } else if d == to {
                scope = endScope
            } else {
                scope = .full
            }
            out.append(DayOverride(localDate: d, cityKey: cityKey, cityName: cityName, scope: scope))
            if d == to { break }
            d = d.next()
        }
        return out
    }

    /// 把计划写入的更正并入已有更正(与存储层 setOverride 同一口径):
    /// 整天更正清掉当天所有更正;半天更正清掉当天的整天更正与同一半天的旧更正,另一半天保留。
    static func merge(existing: [DayOverride], planned: [DayOverride]) -> [DayOverride] {
        var result = existing
        for p in planned {
            result.removeAll { o in
                o.localDate == p.localDate && (p.scope == .full || o.scope == .full || o.scope == p.scope)
            }
            result.append(p)
        }
        return result.sorted { a, b in
            if a.localDate != b.localDate { return a.localDate < b.localDate }
            return scopeOrder(a.scope) < scopeOrder(b.scope)
        }
    }

    /// 与 Kotlin 枚举声明顺序(FULL, MORNING, EVENING)一致
    private static func scopeOrder(_ s: OverrideScope) -> Int {
        switch s {
        case .full: return 0
        case .morning: return 1
        case .evening: return 2
        }
    }
}
