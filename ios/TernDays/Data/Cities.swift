import Foundation
import WidgetKit

/// 内置离线城市库（bundle 里的 cities.tsv），懒加载单例。
enum Cities {
    /// 随包城市库版本：每次重建 cities.tsv 时 +1，触发历史打卡按原始坐标重解析。
    static let datasetVersion = 2

    private static var _matcher: CityMatcher?
    private static let lock = NSLock()
    private static var remapping = false

    static var matcher: CityMatcher {
        lock.lock()
        defer { lock.unlock() }
        if let m = _matcher { return m }
        let url = Bundle.main.url(forResource: "cities", withExtension: "tsv")
        let tsv = url.flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
        let m = CityMatcher(tsv: tsv)
        _matcher = m
        return m
    }

    /// 城市库升级后，用存下来的原始坐标把历史打卡重解析一遍
    /// （修正诸如深圳被判成香港的历史记录）。手动更正不动。后台执行，幂等。
    static func reResolveHistoryIfNeeded(onChanged: @escaping (Int) -> Void = { _ in }) {
        let defaults = UserDefaults(suiteName: AppGroup.id) ?? .standard
        let key = "dataset_version_resolved"
        guard defaults.integer(forKey: key) < datasetVersion else { return }
        lock.lock()
        if remapping {
            lock.unlock()
            return
        }
        remapping = true
        lock.unlock()
        DispatchQueue.global(qos: .utility).async {
            let m = matcher
            let changed = DataStore.shared.remapCities { lat, lng in
                m.nearest(lat: lat, lng: lng).map { ($0.cityKey, $0.cityName) }
            }
            defaults.set(datasetVersion, forKey: key)
            lock.lock()
            remapping = false
            lock.unlock()
            if changed > 0 {
                WidgetCenter.shared.reloadAllTimelines()
                NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
                DispatchQueue.main.async { onChanged(changed) }
            }
        }
    }
}
