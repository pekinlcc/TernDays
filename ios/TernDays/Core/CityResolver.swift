import Foundation

/// 城市判定的交叉验证（与 Android :core CityResolver 同一套规则）：
/// 最近邻在真实边界（深圳/香港、珠海/澳门…）附近天然模糊，且网络定位在口岸带
/// 可能给出公里级误差的坐标。三个本地信号消歧，全程离线、确定性：
///  1. 候选边距——前两个候选城市距离差小于「基础边距 + 定位误差」即视为模糊区；
///  2. 行程连续性——模糊区内若近期（≤36h）上一次打卡的城市也在候选中，则粘住它；
///  3. 误差圈优先——定位误差达公里级时坐标不可信，误差范围内沿用上次城市。
/// 防错误传播：top1 距离极近（<0.8km）且定位可信时不粘。
enum CityResolver {
    struct Prev {
        let cityKey: String
        let lat: Double
        let lng: Double
        let ageHours: Double
    }

    struct Resolution {
        let match: CityMatcher.Match
        let viaContext: Bool
        let ambiguous: Bool
    }

    static let maxPrevAgeHours = 36.0
    static let baseMarginKm = 1.5
    static let accuracyMarginCapKm = 3.0
    static let minStickTop1Km = 0.8
    static let poorAccuracyM = 1500.0

    static func resolve(
        candidates: [CityMatcher.Match],
        accuracyM: Double?,
        prev: Prev?
    ) -> Resolution? {
        guard let top1 = candidates.first else { return nil }
        let accuracyKm = max((accuracyM ?? 0) / 1000.0, 0)
        let ambiguousMargin = baseMarginKm + min(accuracyKm, accuracyMarginCapKm)
        let runnerUp = candidates.count > 1 ? candidates[1] : nil
        let ambiguous = runnerUp.map { $0.distanceKm - top1.distanceKm < ambiguousMargin } ?? false

        guard let prev, prev.ageHours <= maxPrevAgeHours, prev.cityKey != top1.cityKey else {
            return Resolution(match: top1, viaContext: false, ambiguous: ambiguous)
        }
        guard let prevCand = candidates.first(where: { $0.cityKey == prev.cityKey }) else {
            return Resolution(match: top1, viaContext: false, ambiguous: ambiguous)
        }
        let prevMargin = prevCand.distanceKm - top1.distanceKm

        if let accuracyM, accuracyM >= poorAccuracyM, prevMargin <= accuracyKm {
            return Resolution(match: prevCand, viaContext: true, ambiguous: true)
        }
        if prevMargin < ambiguousMargin, top1.distanceKm > minStickTop1Km {
            return Resolution(match: prevCand, viaContext: true, ambiguous: true)
        }
        return Resolution(match: top1, viaContext: false, ambiguous: ambiguous)
    }
}
