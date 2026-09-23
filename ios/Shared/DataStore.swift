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
    /// 应用沙盒里的旧数据目录(v0.1 / 未开 App Group 的自签构建写在这里);清除全部数据时一并删掉
    private let legacyDir: URL
    private var punchesURL: URL { dir.appendingPathComponent("punches.json") }
    private var overridesURL: URL { dir.appendingPathComponent("overrides.json") }

    private init() {
        let fm = FileManager.default
        let legacy = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("TernDays", isDirectory: true)
        legacyDir = legacy
        if let group = fm.containerURL(forSecurityApplicationGroupIdentifier: AppGroup.id) {
            dir = group.appendingPathComponent("TernDays", isDirectory: true)
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
            Self.migrate(from: legacy, to: dir)
        } else {
            // App Group 未配置（例如自定义签名时没开 capability）：应用可用，但小组件读不到数据
            dir = legacy
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        // 「数据只存本机」:整个数据目录不进 iCloud / 电脑备份(换机用应用内扫码迁移)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var excluded = dir
        try? excluded.setResourceValues(values)
        let p: LoadResult<Punch> = Self.loadList(punchesURL)
        let o: LoadResult<DayOverride> = Self.loadList(overridesURL)
        punches = p.list ?? []
        overrides = o.list ?? []
        punchesUnreadable = p.unreadable
        overridesUnreadable = o.unreadable
    }

    // MARK: 引导完成标记
    // onboardingDone 存在 UserDefaults 里,会随 iCloud 备份恢复;数据目录却被排除在备份外。
    // 在数据目录里再放一个标记,两者不一致时以标记为准,避免「跳过引导、库却是空的」。

    private var onboardedMarkerURL: URL { dir.appendingPathComponent(".onboarded") }

    var hasOnboardingMarker: Bool { FileManager.default.fileExists(atPath: onboardedMarkerURL.path) }

    /// 本机有没有数据文件(只看文件在不在,锁屏读不出内容时也成立)
    var hasStoredDataFiles: Bool {
        let fm = FileManager.default
        return fm.fileExists(atPath: punchesURL.path) || fm.fileExists(atPath: overridesURL.path)
    }

    func markOnboarded() {
        FileManager.default.createFile(atPath: onboardedMarkerURL.path, contents: Data())
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

    /// 文件在但读不出来(典型:重启后首次解锁前被后台拉起,文件还处于数据保护中)。
    /// 封印期间内存里的空数组不是真相:不落盘、不打卡、不做重解析。
    var isSealed: Bool {
        queue.sync { punchesUnreadable || overridesUnreadable }
    }

    /// 仍处于封印时重读一次(回到前台、受保护数据可用时调用)。@return 调用后是否已解封
    @discardableResult
    func reloadIfSealed() -> Bool {
        if isSealed { reloadFromDisk() }
        return !isSealed
    }

    /// 重读磁盘。小组件进程可能长期复用,每次生成时间线前调用;主应用在解封时调用。
    /// 从封印状态解封时,把封印期间内存里新增的记录按去重键并回磁盘数据再落盘,不丢。
    func reloadFromDisk() {
        queue.sync {
            let wasSealed = punchesUnreadable || overridesUnreadable
            let p: LoadResult<Punch> = Self.loadList(punchesURL)
            let o: LoadResult<DayOverride> = Self.loadList(overridesURL)
            var needsPersist = false
            if let list = p.list {
                if punchesUnreadable {
                    let extra = punches.filter { m in !list.contains { $0.localDate == m.localDate && $0.slot == m.slot } }
                    punches = list + extra
                    needsPersist = needsPersist || !extra.isEmpty
                } else {
                    punches = list
                }
            }
            if let list = o.list {
                if overridesUnreadable {
                    let extra = overrides.filter { m in !list.contains { $0.localDate == m.localDate && $0.scope == m.scope } }
                    overrides = list + extra
                    needsPersist = needsPersist || !extra.isEmpty
                } else {
                    overrides = list
                }
            }
            punchesUnreadable = p.unreadable
            overridesUnreadable = o.unreadable
            if wasSealed && needsPersist && !punchesUnreadable && !overridesUnreadable {
                persist()
            }
        }
    }

    /// 老版本（v0.1）数据写在应用沙盒；启用 App Group 后做一次性搬迁。
    /// 搬完删掉沙盒里的源文件:否则「清除本机全部数据」(或损坏文件被改名)后 App Group 里没有文件,
    /// 下次启动又会把这份冻结的旧记录搬回来。App Group 可用时沙盒这份从来不读,删掉不丢数据。
    private static func migrate(from old: URL, to new: URL) {
        let fm = FileManager.default
        for name in ["punches.json", "overrides.json"] {
            let src = old.appendingPathComponent(name)
            let dst = new.appendingPathComponent(name)
            guard fm.fileExists(atPath: src.path) else { continue }
            if !fm.fileExists(atPath: dst.path) {
                do {
                    try fm.copyItem(at: src, to: dst)
                } catch {
                    continue // 没搬成(如首次解锁前读不出源文件):源文件留着,下次启动再搬
                }
            }
            // 刚搬完,或早先版本已搬过却留着源文件:沙盒里只剩一份旧副本
            try? fm.removeItem(at: src)
        }
    }

    /// 落盘。原子写;读不出来的那份一律不写(避免用内存里的空数组覆盖真实历史);
    /// 写失败不再静默——回调出去让界面提示,否则用户以为已保存。
    @discardableResult
    private func persist() -> Bool {
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
        return failed.isEmpty
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

    /// 全库最早一条记录（打卡或手动更正）的日期：跨年后 1 月初的漏记要靠它才认得出来。
    func earliestRecordDate() -> LocalDate? {
        queue.sync {
            let a = punches.map(\.localDate).min()
            let b = overrides.map(\.localDate).min()
            switch (a, b) {
            case let (x?, y?): return min(x, y)
            case let (x?, nil): return x
            case let (nil, y?): return y
            default: return nil
            }
        }
    }

    /// 是否已有任何打卡记录（用于判定「首次安装的首点」）
    func hasAnyPunch() -> Bool {
        queue.sync { !punches.isEmpty }
    }

    /// 行程连续性锚点:最近一条**非改判**(viaContext != true)、解析成功、且**不晚于现在**的打卡
    /// (Anchors.pick,与 Android 同一口径)。被连续性/误差圈粘住的点不作锚,36h 上限才能真正限制整条粘滞链;
    /// 系统时间曾被拨到未来留下的记录也不作锚,否则链龄为负、粘滞链永不过期。
    func latestAnchorPunch(nowMs: Int64 = Anchors.nowMs()) -> Punch? {
        queue.sync { Anchors.pick(punches, nowMs: nowMs) }
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

    /// 城市库升级后按 cityKey 刷新手动更正里的城市名。@return 改了几条
    func refreshOverrideNames(_ lookup: (String) -> String?) -> Int {
        queue.sync {
            var changed = 0
            for i in overrides.indices {
                let o = overrides[i]
                guard let name = lookup(o.cityKey), name != o.cityName else { continue }
                overrides[i] = DayOverride(localDate: o.localDate, cityKey: o.cityKey, cityName: name, scope: o.scope)
                changed += 1
            }
            if changed > 0 { persist() }
            return changed
        }
    }

    /// 区间(含首尾)内的打卡,按时间升序。跨年区间 / 最近 180 天 / 相邻日建议都走这里。
    func punchesBetween(_ from: LocalDate, _ to: LocalDate) -> [Punch] {
        queue.sync {
            punches.filter { $0.localDate >= from && $0.localDate <= to }.sorted { $0.epochMs < $1.epochMs }
        }
    }

    func overridesBetween(_ from: LocalDate, _ to: LocalDate) -> [DayOverride] {
        queue.sync { overrides.filter { $0.localDate >= from && $0.localDate <= to } }
    }

    /// 打卡 + 手动记录总条数(「确定清除全部 N 条记录?」)
    func recordCount() -> Int {
        queue.sync { punches.count + overrides.count }
    }

    /// 最近去过的城市(按最近一次出现的日期倒序、去重),补记 / 更正时作候选。
    func recentCities(limit: Int = 8) -> [(key: String, name: String)] {
        queue.sync {
            var items: [(date: LocalDate, ms: Int64, key: String, name: String)] = []
            items.reserveCapacity(punches.count + overrides.count)
            for p in punches where p.cityKey != "unknown" {
                items.append((p.localDate, p.epochMs, p.cityKey, p.cityName))
            }
            for o in overrides {
                items.append((o.localDate, Int64.max, o.cityKey, o.cityName))
            }
            items.sort { a, b in a.date != b.date ? a.date > b.date : a.ms > b.ms }
            var seen = Set<String>()
            var out: [(key: String, name: String)] = []
            for it in items where !seen.contains(it.key) {
                seen.insert(it.key)
                out.append((key: it.key, name: it.name))
                if out.count >= limit { break }
            }
            return out
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

    /// 批量写入(区间补记):与 setOverride 同一套互斥规则(Backfill.merge),只落盘一次。
    func setOverrides(_ list: [DayOverride]) {
        guard !list.isEmpty else { return }
        queue.sync {
            overrides = Backfill.merge(existing: overrides, planned: list)
            persist()
        }
    }

    /// 这些日子现有的手动更正(写入前的快照,撤销用)。
    func overridesOn(_ dates: Set<LocalDate>) -> [DayOverride] {
        queue.sync { overrides.filter { dates.contains($0.localDate) } }
    }

    /// 撤销:把这些日子的手动更正原样换回快照(快照为空 = 这些日子恢复为没有更正)。
    func replaceOverrides(on dates: Set<LocalDate>, with snapshot: [DayOverride]) {
        queue.sync {
            overrides.removeAll { dates.contains($0.localDate) }
            overrides.append(contentsOf: snapshot.filter { dates.contains($0.localDate) })
            persist()
        }
    }

    /// 删除指定的打卡(系统时间被拨到未来留下的记录)。按 (日期, 时段, 时刻) 精确定位。@return 删了几条
    @discardableResult
    func deletePunches(_ targets: [Punch]) -> Int {
        queue.sync {
            let keys = Set(targets.map { "\($0.localDate)|\($0.slot.rawValue)|\($0.epochMs)" })
            let before = punches.count
            punches.removeAll { keys.contains("\($0.localDate)|\($0.slot.rawValue)|\($0.epochMs)") }
            let removed = before - punches.count
            if removed > 0 { persist() }
            return removed
        }
    }

    /// 清除本机全部记录:删数据文件(含沙盒旧副本、损坏备份)、清内存。用户明确要求清除,封印中(读不出来)的文件也照删。
    /// 引导标记保留(清空后仍按已完成引导使用,下一次打卡即新的「首点」)。
    /// @return false = 有文件没删掉(被数据保护锁着),界面提示解锁后重试
    @discardableResult
    func clearAll() -> Bool {
        queue.sync {
            let fm = FileManager.default
            var ok = true
            if fm.fileExists(atPath: punchesURL.path) {
                do {
                    try fm.removeItem(at: punchesURL)
                    punches = []
                    punchesUnreadable = false
                } catch {
                    ok = false
                }
            } else {
                punches = []
                punchesUnreadable = false
            }
            if fm.fileExists(atPath: overridesURL.path) {
                do {
                    try fm.removeItem(at: overridesURL)
                    overrides = []
                    overridesUnreadable = false
                } catch {
                    ok = false
                }
            } else {
                overrides = []
                overridesUnreadable = false
            }
            // 「不可恢复」要名副其实:沙盒里的旧目录(否则下次启动被 migrate 搬回来)
            // 和读档时改名保留的损坏文件(里面同样是完整的位置历史)也一并删掉
            var leftovers: [URL] = []
            if legacyDir.standardizedFileURL.path != dir.standardizedFileURL.path {
                leftovers.append(legacyDir.appendingPathComponent("punches.json"))
                leftovers.append(legacyDir.appendingPathComponent("overrides.json"))
            }
            let names = (try? fm.contentsOfDirectory(atPath: dir.path)) ?? []
            for name in names where name.hasPrefix("punches.corrupt-") || name.hasPrefix("overrides.corrupt-") {
                leftovers.append(dir.appendingPathComponent(name))
            }
            for url in leftovers where fm.fileExists(atPath: url.path) {
                do { try fm.removeItem(at: url) } catch { ok = false }
            }
            return ok
        }
    }

    /// 恢复整天自动判定:删除该日全部手动更正。
    func removeOverride(date: LocalDate) {
        queue.sync {
            overrides.removeAll { $0.localDate == date }
            persist()
        }
    }

    /// 只恢复某一天某个范围的自动判定（同一天的另半天更正保持不变）。
    func removeOverride(date: LocalDate, scope: OverrideScope) {
        queue.sync {
            overrides.removeAll { $0.localDate == date && $0.scope == scope }
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
        /// 跳过的记录里,键相同但城市不同的条数(完全相同的只是重复,不算):
        /// 结果页写「其中 K 条与旧手机不一致,已保留本机版本」
        var punchesConflicting: Int = 0
        var overridesConflicting: Int = 0

        var added: Int { punchesAdded + overridesAdded }
        var skipped: Int { punchesSkipped + overridesSkipped }
        var conflicting: Int { punchesConflicting + overridesConflicting }
    }

    /// 迁移导入合并:打卡按 (日期, 时段)、手动记录按 (日期, 范围) 去重,本机已有的一律保留;
    /// 手动记录的判定与 Android :core MergeRules 同口径,且不破坏整天/半天互斥。
    enum MergeError: LocalizedError {
        case notSaved
        var errorDescription: String? { "数据没能保存到本机。请清理存储空间、保持手机解锁后,在旧手机上重新打开迁移页再扫一次。" }
    }

    /// 落盘失败时整体回滚并抛错:不能一边告诉旧手机「导入完成」,一边重启后什么都没有。
    func mergeImported(punches newPunches: [Punch], overrides newOverrides: [DayOverride]) throws -> MergeResult {
        try queue.sync {
            guard !(punchesUnreadable || overridesUnreadable) else { throw MergeError.notSaved }
            let savedPunches = punches
            let savedOverrides = overrides
            var pAdded = 0, pSkipped = 0, oAdded = 0, oSkipped = 0, pConflict = 0, oConflict = 0
            // (日期|时段) → 本机那条:几千条对几千条逐一 contains 是平方级,换成字典
            var local: [String: Punch] = [:]
            for p in punches {
                let k = "\(p.localDate)|\(p.slot.rawValue)"
                if local[k] == nil { local[k] = p }
            }
            for p in newPunches {
                let k = "\(p.localDate)|\(p.slot.rawValue)"
                if let mine = local[k] {
                    pSkipped += 1
                    if MergeRules.isConflict(local: mine, incoming: p) { pConflict += 1 }
                } else {
                    punches.append(p)
                    local[k] = p
                    pAdded += 1
                }
            }
            for o in newOverrides {
                let sameDay = overrides.filter { $0.localDate == o.localDate }
                let existing = Set(sameDay.map(\.scope))
                if MergeRules.shouldImportOverride(existing: existing, incoming: o.scope) {
                    overrides.append(o)
                    oAdded += 1
                } else {
                    oSkipped += 1
                    // 键(日期+范围)相同且城市不同才算不一致;整天 vs 半天这类互斥跳过不算
                    if let mine = sameDay.first(where: { $0.scope == o.scope }),
                       MergeRules.isConflict(local: mine, incoming: o) {
                        oConflict += 1
                    }
                }
            }
            if pAdded + oAdded > 0 && !persist() {
                punches = savedPunches
                overrides = savedOverrides
                throw MergeError.notSaved
            }
            return MergeResult(
                punchesAdded: pAdded, punchesSkipped: pSkipped, overridesAdded: oAdded, overridesSkipped: oSkipped,
                punchesConflicting: pConflict, overridesConflicting: oConflict
            )
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
