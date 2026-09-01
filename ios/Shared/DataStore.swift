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
        punches = Self.loadList(punchesURL) ?? []
        overrides = Self.loadList(overridesURL) ?? []
    }

    /// 读档。解码失败时把损坏文件改名保留(.corrupt),绝不让后续 persist 静默清空全部历史。
    private static func loadList<T: Decodable>(_ url: URL) -> [T]? {
        guard let d = try? Data(contentsOf: url) else { return nil }
        if let list = try? JSONDecoder().decode([T].self, from: d) {
            return list
        }
        let backup = url.deletingPathExtension()
            .appendingPathExtension("corrupt-\(Int(Date().timeIntervalSince1970)).json")
        try? FileManager.default.moveItem(at: url, to: backup)
        return nil
    }

    /// 小组件进程可能长期复用:每次生成时间线前重读磁盘,避免展示过期数据。
    func reloadFromDisk() {
        queue.sync {
            punches = Self.loadList(punchesURL) ?? punches
            overrides = Self.loadList(overridesURL) ?? overrides
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

    /// 行程连续性锚点:最近一条**非改判**(viaContext != true)且解析成功的打卡。
    /// 被连续性/误差圈粘住的点不作锚,36h 上限才能真正限制整条粘滞链。
    func latestAnchorPunch() -> Punch? {
        queue.sync {
            punches.filter { $0.cityKey != "unknown" && $0.viaContext != true }
                .max { $0.epochMs < $1.epochMs }
        }
    }

    func overrideFor(date: LocalDate) -> DayOverride? {
        queue.sync { overrides.first { $0.localDate == date } }
    }

    /// 历史重放的结果落盘:按 (日期, 时段) 定位并替换城市与改判标记。@return 城市被改动的条数。
    func applyResolveOutcomes(_ outcomes: [(date: LocalDate, slot: Slot, key: String, name: String, via: Bool)]) -> Int {
        queue.sync {
            var changed = 0
            for o in outcomes {
                guard let i = punches.firstIndex(where: { $0.localDate == o.date && $0.slot == o.slot }) else { continue }
                var p = punches[i]
                if p.cityKey != o.key || p.cityName != o.name { changed += 1 }
                p = Punch(
                    localDate: p.localDate, slot: p.slot, epochMs: p.epochMs, zoneId: p.zoneId,
                    lat: p.lat, lng: p.lng, accuracyM: p.accuracyM,
                    cityKey: o.key, cityName: o.name,
                    delayed: p.delayed, fromCache: p.fromCache, viaContext: o.via
                )
                punches[i] = p
            }
            persist()
            return changed
        }
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

    func allPunches() -> [Punch] {
        queue.sync { punches.sorted { $0.epochMs < $1.epochMs } }
    }

    func allOverrides() -> [DayOverride] {
        queue.sync { overrides }
    }

    struct MergeResult {
        let punchesAdded: Int
        let punchesSkipped: Int
        let overridesAdded: Int
        let overridesSkipped: Int
    }

    /// 迁移导入合并:打卡按 (日期, 时段)、手动记录按日期去重,本机已有的一律保留。
    func mergeImported(punches newPunches: [Punch], overrides newOverrides: [DayOverride]) -> MergeResult {
        queue.sync {
            var pAdded = 0, pSkipped = 0, oAdded = 0, oSkipped = 0
            for p in newPunches {
                if punches.contains(where: { $0.localDate == p.localDate && $0.slot == p.slot }) {
                    pSkipped += 1
                } else {
                    punches.append(p)
                    pAdded += 1
                }
            }
            for o in newOverrides {
                if overrides.contains(where: { $0.localDate == o.localDate }) {
                    oSkipped += 1
                } else {
                    overrides.append(o)
                    oAdded += 1
                }
            }
            if pAdded + oAdded > 0 { persist() }
            return MergeResult(punchesAdded: pAdded, punchesSkipped: pSkipped, overridesAdded: oAdded, overridesSkipped: oSkipped)
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
