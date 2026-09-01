import Foundation

/// 本地存储：punches.json / overrides.json（Application Support），串行队列保护。
final class DataStore {
    static let shared = DataStore()

    private let queue = DispatchQueue(label: "app.terndays.datastore")
    private var punches: [Punch] = []
    private var overrides: [DayOverride] = []

    private let dir: URL
    private var punchesURL: URL { dir.appendingPathComponent("punches.json") }
    private var overridesURL: URL { dir.appendingPathComponent("overrides.json") }

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = base.appendingPathComponent("TernDays", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        if let d = try? Data(contentsOf: punchesURL),
           let list = try? JSONDecoder().decode([Punch].self, from: d) {
            punches = list
        }
        if let d = try? Data(contentsOf: overridesURL),
           let list = try? JSONDecoder().decode([DayOverride].self, from: d) {
            overrides = list
        }
    }

    private func persist() {
        if let d = try? JSONEncoder().encode(punches) { try? d.write(to: punchesURL, options: .atomic) }
        if let d = try? JSONEncoder().encode(overrides) { try? d.write(to: overridesURL, options: .atomic) }
    }

    /// @return true = 新插入；false = 该 (日期, 时段) 已有记录
    @discardableResult
    func insertPunch(_ p: Punch) -> Bool {
        queue.sync {
            if punches.contains(where: { $0.localDate == p.localDate && $0.slot == p.slot }) {
                return false
            }
            punches.append(p)
            persist()
            return true
        }
    }

    func hasPunch(date: LocalDate, slot: Slot) -> Bool {
        queue.sync { punches.contains { $0.localDate == date && $0.slot == slot } }
    }

    func punchesForYear(_ year: Int) -> [Punch] {
        queue.sync { punches.filter { $0.localDate.year == year }.sorted { $0.epochMs < $1.epochMs } }
    }

    func overridesForYear(_ year: Int) -> [DayOverride] {
        queue.sync { overrides.filter { $0.localDate.year == year } }
    }

    func setOverride(_ o: DayOverride) {
        queue.sync {
            overrides.removeAll { $0.localDate == o.localDate }
            overrides.append(o)
            persist()
        }
    }

    func removeOverride(date: LocalDate) {
        queue.sync {
            overrides.removeAll { $0.localDate == date }
            persist()
        }
    }

    func yearsWithData(currentYear: Int) -> [Int] {
        queue.sync {
            var years = Set([currentYear])
            punches.forEach { years.insert($0.localDate.year) }
            overrides.forEach { years.insert($0.localDate.year) }
            return years.sorted(by: >)
        }
    }
}
