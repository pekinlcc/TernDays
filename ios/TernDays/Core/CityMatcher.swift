import Foundation

/// 离线反地理编码：对内置城市点云（cities.tsv）做最近邻。
final class CityMatcher {
    struct Match {
        let cityKey: String
        let cityName: String
        let distanceKm: Double
    }

    /// 搜索命中:region 用于区分重名城市(加拿大伦敦 vs 英国伦敦),中国城市为空。
    struct SearchHit {
        let cityKey: String
        let cityName: String
        let region: String
    }

    private let lats: [Double]
    private let lngs: [Double]
    private let keys: [String]
    private let names: [String]
    private let aliases: [String]

    var size: Int { lats.count }

    init(tsv: String) {
        var lats: [Double] = []
        var lngs: [Double] = []
        var keys: [String] = []
        var names: [String] = []
        var aliases: [String] = []
        lats.reserveCapacity(36000)
        for line in tsv.split(separator: "\n", omittingEmptySubsequences: true) {
            let f = line.split(separator: "\t", maxSplits: 4, omittingEmptySubsequences: false)
            // 单行损坏只跳过该行,不让整个城市库加载失败
            guard f.count >= 4, let lat = Double(f[0]), let lng = Double(f[1]),
                  (-90...90).contains(lat), (-180...180).contains(lng),
                  !f[2].isEmpty, !f[3].isEmpty else { continue }
            lats.append(lat)
            lngs.append(lng)
            keys.append(String(f[2]))
            names.append(String(f[3]))
            aliases.append(f.count >= 5 ? String(f[4]).lowercased() : "")
        }
        self.lats = lats
        self.lngs = lngs
        self.keys = keys
        self.names = names
        self.aliases = aliases
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

    /// 手动补记/更正时按名称搜索:全等 > 前缀 > 包含,中国城市优先;拼音可搜;带重名消歧地区。
    func searchHits(_ query: String, limit: Int = 20) -> [SearchHit] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return [] }
        var best: [String: Int] = [:]      // cityKey -> rank(0 全等 / 1 前缀 / 2 包含)
        var nameOf: [String: String] = [:]
        for i in 0..<keys.count {
            let name = names[i].lowercased()
            let alias = aliases[i]
            let cityPart = (keys[i].split(separator: ":").last.map(String.init) ?? keys[i]).lowercased()
            let rank: Int
            if name == q || alias == q || cityPart == q {
                rank = 0
            } else if name.hasPrefix(q) || (!alias.isEmpty && alias.hasPrefix(q)) || cityPart.hasPrefix(q) {
                rank = 1
            } else if name.contains(q) || (!alias.isEmpty && alias.contains(q)) || cityPart.contains(q) {
                rank = 2
            } else {
                continue
            }
            if let prev = best[keys[i]], prev <= rank { continue }
            best[keys[i]] = rank
            nameOf[keys[i]] = names[i]
        }
        return best.map { (key: $0.key, rank: $0.value) }
            .sorted { a, b in
                if a.rank != b.rank { return a.rank < b.rank }
                let da = Self.isDomestic(a.key), db = Self.isDomestic(b.key)
                if da != db { return da }
                return a.key < b.key
            }
            .prefix(limit)
            .map { SearchHit(cityKey: $0.key, cityName: nameOf[$0.key] ?? $0.key, region: Self.regionOf($0.key)) }
    }

    /// 旧签名兼容
    func search(name query: String, limit: Int = 20) -> [(key: String, name: String)] {
        searchHits(query, limit: limit).map { (key: $0.cityKey, name: $0.cityName) }
    }

    static func isDomestic(_ key: String) -> Bool {
        key.hasPrefix("CN:") || key.hasPrefix("HK:") || key.hasPrefix("MO:")
    }

    /// 重名消歧:境外城市给出国家(+一级行政区码),中国城市返回空。
    static func regionOf(_ key: String) -> String {
        if isDomestic(key) { return "" }
        let parts = key.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard let cc = parts.first else { return "" }
        let country = ccNames[cc] ?? cc
        let admin1 = parts.count > 1 ? parts[1] : ""
        return (admin1.isEmpty || admin1 == cc) ? country : "\(country)·\(admin1)"
    }

    /// 常见国家码 → 中文名(仅搜索消歧展示用)
    private static let ccNames: [String: String] = [
        "SG": "新加坡", "JP": "日本", "KR": "韩国", "TH": "泰国", "MY": "马来西亚",
        "ID": "印尼", "VN": "越南", "PH": "菲律宾", "IN": "印度", "US": "美国",
        "CA": "加拿大", "MX": "墨西哥", "BR": "巴西", "AR": "阿根廷", "GB": "英国",
        "FR": "法国", "DE": "德国", "IT": "意大利", "ES": "西班牙", "PT": "葡萄牙",
        "NL": "荷兰", "BE": "比利时", "CH": "瑞士", "AT": "奥地利", "SE": "瑞典",
        "NO": "挪威", "DK": "丹麦", "FI": "芬兰", "RU": "俄罗斯", "TR": "土耳其",
        "AU": "澳大利亚", "NZ": "新西兰", "AE": "阿联酋", "SA": "沙特", "QA": "卡塔尔",
        "EG": "埃及", "ZA": "南非", "KH": "柬埔寨", "LA": "老挝", "MM": "缅甸",
        "NP": "尼泊尔", "LK": "斯里兰卡", "PK": "巴基斯坦", "BD": "孟加拉",
        "IE": "爱尔兰", "PL": "波兰", "CZ": "捷克", "HU": "匈牙利", "GR": "希腊",
        "IL": "以色列", "KZ": "哈萨克斯坦", "MN": "蒙古", "TW": "台湾",
    ]

    static func haversineKm(_ lat1: Double, _ lng1: Double, _ lat2: Double, _ lng2: Double) -> Double {
        let r1 = lat1 * .pi / 180
        let r2 = lat2 * .pi / 180
        let dLat = (lat2 - lat1) * .pi / 180
        let dLng = (lng2 - lng1) * .pi / 180
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(r1) * cos(r2) * sin(dLng / 2) * sin(dLng / 2)
        return 6371.0 * 2 * asin(sqrt(a))
    }
}
