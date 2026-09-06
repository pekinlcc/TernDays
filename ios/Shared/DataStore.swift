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

    /// 文件存在但读不出来(加密锁屏、I/O 错误…)时置位:此时内存里的空数组**不是**真相,
    /// 一律禁止落盘,否则下一次写入就把整份历史覆盖成空。
    private var punchesUnreadable = false
    private var overridesUnreadable = false

    /// 落盘失败时回调(主应用挂一个 toast;小组件不设)。写失败必须让用户知道,
    /// 否则界面提示「已保存」而重启后改动消失。
    var onWriteFailure: ((String) -> Void)?

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
        let p: LoadResult<Punch> = Self.loadList(punchesURL)
        let o: LoadResult<DayOverride> = Self.loadList(overridesURL)
        punches = p.list ?? []
        overrides = o.list ?? []
        punchesUnreadable = p.unreadable
        overridesUnreadable = o.unreadable
    }

    /// 读档结果:要区分「文件不存在」(全新安装,空数组是真相)与
    /// 「文件在但读不出来」(内存空数组不是真相,禁止落盘)。
    private struct LoadResult<T> {
        let list: [T]?
        let unreadable: Bool
    }

    /// 读档。解码失败时把损坏文件改名保留(.corrupt),绝不让后续 persist 静默清空全部历史。
    private static func loadList<T: Decodable>(_ url: URL) -> LoadResult<T> {
        guard FileManager.default.fileExists(atPath: url.path) else {
            return LoadResult(list: [], unreadable: false) // 全新安装
        }
        guard let d = try? Data(contentsOf: url) else {
            return LoadResult(list: nil, unreadable: true) // 文件在但读不出来:保持沉默,别覆盖
        }
        if let list = try? JSONDecoder().decode([T].self, from: d) {
            return LoadResult(list: list, unreadable: false)
        }
        // 解码失败 = 内容已损坏:改名保留,之后按空库继续(写入是安全的)
        let backup = url.deletingPathExtension()
            .appendingPathExtension("corrupt-\(Int(Date().timeIntervalSince1970)).json")
        try? FileManager.default.moveItem(at: url, to: backup)
        return LoadResult(list: [], unreadable: false)
    }

    /// 小组件进程可能长期复用:每次生成时间线前重读磁盘,避免展示过期数据。
    func reloadFromDisk() {
        queue.sync {
            let p: LoadResult<Punch> = Self.loadList(punchesURL)
            let o: LoadResult<DayOverride> = Self.loadList(overridesURL)
            if let list = p.list { punches = list }
            if let list = o.list { overrides = list }
            // 这次读成功就解除封印,后续写入恢复正常
            punchesUnreadable = p.unreadable
            overridesUnreadable = o.unreadable
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

    /// 落盘。原子写;读不出来的那份一律不写(避免用内存里的空数组覆盖真实历史);
    /// 写失败不再静默——回调出去让界面提示,否则用户以为已保存。
    private func persist() {
        var failed: [String] = []
        if punchesUnreadable {
            failed.append("打卡记录")
        } else if let d = try? JSONEncoder().encode(punches) {
            do { try d.write(to: punchesURL, options: .atomic) } catch { failed.append("打卡记录") }
        } else {
            failed.append("打卡记录")
        }
        if overridesUnreadable {
            failed.append("手动记录")
        } else if let d = try? JSONEncoder().encode(overrides) {
            do { try d.write(to: overridesURL, options: .atomic) } catch { failed.append("手动记录") }
        } else {
            failed.append("手动记录")
        }
        if !failed.isEmpty, let cb = onWriteFailure {
            let what = failed.joined(separator: "、")
            DispatchQueue.main.async { cb("\(what)没能保存到本机,请重试(存储空间不足或设备被锁定时会出现)") }
        }
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

    /// 该日的整天更正(锚点参照只认整天更正)。
    func overrideFor(date: LocalDate) -> DayOverride? {
        queue.sync { overrides.first { $0.localDate == date && $0.scope == .full } }
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

    /// 写入手动更正。整天与半天互斥:写整天清掉该日半天,写半天清掉该日整天。
    func setOverride(_ o: DayOverride) {
        queue.sync {
            if o.scope == .full {
                overrides.removeAll { $0.localDate == o.localDate }
            } else {
                overrides.removeAll { $0.localDate == o.localDate && ($0.scope == .full || $0.scope == o.scope) }
            }
            overrides.append(o)
            persist()
        }
    }

    /// 恢复整天自动判定:删除该日全部手动更正。
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

    /// 迁移导入合并:打卡按 (日期, 时段)、手动记录按 (日期, 范围) 去重,本机已有的一律保留;
    /// 手动记录的判定与 Android :core MergeRules 同口径,且不破坏整天/半天互斥。
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
                let existing = Set(overrides.filter { $0.localDate == o.localDate }.map(\.scope))
                if MergeRules.shouldImportOverride(existing: existing, incoming: o.scope) {
                    overrides.append(o)
                    oAdded += 1
                } else {
                    oSkipped += 1
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
