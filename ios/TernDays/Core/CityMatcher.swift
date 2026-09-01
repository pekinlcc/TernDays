import Foundation

/// 离线反地理编码：对内置城市点云（cities.tsv）做最近邻。
final class CityMatcher {
    struct Match {
        let cityKey: String
        let cityName: String
        let distanceKm: Double
    }

    private let lats: [Double]
    private let lngs: [Double]
    private let keys: [String]
    private let names: [String]

    var size: Int { lats.count }

    init(tsv: String) {
        var lats: [Double] = []
        var lngs: [Double] = []
        var keys: [String] = []
        var names: [String] = []
        lats.reserveCapacity(36000)
        for line in tsv.split(separator: "\n", omittingEmptySubsequences: true) {
            let f = line.split(separator: "\t", maxSplits: 3, omittingEmptySubsequences: false)
            guard f.count == 4, let lat = Double(f[0]), let lng = Double(f[1]) else { continue }
            lats.append(lat)
            lngs.append(lng)
            keys.append(String(f[2]))
            names.append(String(f[3]))
        }
        self.lats = lats
        self.lngs = lngs
        self.keys = keys
        self.names = names
    }

    func nearest(lat: Double, lng: Double) -> Match? {
        guard !lats.isEmpty else { return nil }
        var best = 0
        var bestD = Double.greatestFiniteMagnitude
        for i in 0..<lats.count {
            let d = CityMatcher.haversineKm(lat, lng, lats[i], lngs[i])
            if d < bestD {
                bestD = d
                best = i
            }
        }
        return Match(cityKey: keys[best], cityName: names[best], distanceKm: bestD)
    }

    /// 按城市去重的前 k 个最近候选（每城取其最近点位），距离升序。供边界交叉验证用。
    func nearestByCity(lat: Double, lng: Double, k: Int = 3) -> [Match] {
        guard !lats.isEmpty, k > 0 else { return [] }
        var cand: [Match] = []
        cand.reserveCapacity(k + 1)
        for i in 0..<lats.count {
            let d = CityMatcher.haversineKm(lat, lng, lats[i], lngs[i])
            let worst = cand.count < k ? Double.greatestFiniteMagnitude : cand[cand.count - 1].distanceKm
            if d >= worst { continue }
            if let existing = cand.firstIndex(where: { $0.cityKey == keys[i] }) {
                if d < cand[existing].distanceKm {
                    cand[existing] = Match(cityKey: keys[i], cityName: names[i], distanceKm: d)
                    cand.sort { $0.distanceKm < $1.distanceKm }
                }
            } else {
                cand.append(Match(cityKey: keys[i], cityName: names[i], distanceKm: d))
                cand.sort { $0.distanceKm < $1.distanceKm }
                if cand.count > k { cand.removeLast() }
            }
        }
        return cand
    }

    /// 手动补记时按名称搜索（去重）
    func search(name query: String, limit: Int = 20) -> [(key: String, name: String)] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return [] }
        var seen = Set<String>()
        var out: [(String, String)] = []
        for i in 0..<keys.count {
            if names[i].localizedCaseInsensitiveContains(q) || keys[i].localizedCaseInsensitiveContains(q) {
                if seen.insert(keys[i]).inserted {
                    out.append((keys[i], names[i]))
                    if out.count >= limit { break }
                }
            }
        }
        return out
    }

    static func haversineKm(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
        let r1 = lat1 * .pi / 180
        let r2 = lat2 * .pi / 180
        let dLat = (lat2 - lat1) * .pi / 180
        let dLng = (lng2 - lng1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(r1) * cos(r2) * sin(dLng / 2) * sin(dLng / 2)
        return 6371.0 * 2 * asin(sqrt(a))
    }
}
