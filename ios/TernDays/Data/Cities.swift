import Foundation

/// 内置离线城市库（bundle 里的 cities.tsv），懒加载单例。
enum Cities {
    private static var _matcher: CityMatcher?
    private static let lock = NSLock()

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
}
