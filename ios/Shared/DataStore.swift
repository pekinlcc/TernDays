import Foundation

/// 主应用与桌面小组件共享数据的 App Group。
/// 修改 Bundle ID 时同步调整，并在 Xcode 里给两个 target 都开启该 App Group capability。
enum AppGroup {
    static let id = "group.app.terndays"
}

/// 本地存储：punches.json / overrides.json，串行队列保护。
/// 优先存放在 App Group 容器（小组件可读）；未配置 App Group 时退回应用沙盒。
final class DataStore {
    static let shared = DataStore()

    private let queue = DispatchQueue(label: "app.terndays.datastore")
    private var punches: [Punch] = []
    private var overrides: [DayOverride] = []

    private let dir: URL
    private var punchesURL: URL { dir.appendingPathComponent("punches.json") }
    private var overridesURL: URL { dir.appendingPathComponent("overrides.json") }

    private init() {
        let fm = FileManager.default
        let legacy = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TernDays", isDirectory: true)
        if let group = fm.containerURL(forSecurityApplicationGroupIdentifier: AppGroup.id) {
            dir = group.appendingPathComponent("TernDays", isDirectory: true)
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
            Self.migrate(from: legacy, to: dir)
        } else {
            // App Group 未配置（例如自定义签名时没开 capability）：应用可用，但小组件读不到数据
            dir = legacy
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        if let d = try? Data(contentsOf: punchesURL),
           let list = try? JSONDecoder().decode([Punch].self, from: d) {
            punches = list
        }
        if let d = try? Data(contentsOf: overridesURL),
           let list = try? JSONDecoder().decode([DayOverride].self, from: d) {
            overrides = list
        }
    }

    /// 老版本（v0.1）数据写在应用沙盒；启用 App Group 后做一次性搬迁
    private static func migrate(from old: URL, to new: URL) {
        let fm = FileManager.default
        for name in ["punches.json", "overrides.json"] {
            let src = old.appendingPathComponent(name)
            let dst = new.appendingPathComponent(name)
            if fm.fileExists(atPath: src.path) && !fm.fileExists(atPath: dst.path) {
                try? fm.copyItem(at: src, to: dst)
            }
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

    /// 是否已有任何打卡记录（用于判定「首次安装的首点」）
    func hasAnyPunch() -> Bool {
        queue.sync { !punches.isEmpty }
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
