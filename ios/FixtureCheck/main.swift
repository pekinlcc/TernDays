import Foundation

// 双端口径对齐检查(CI 的 macOS job 用 swiftc 直接编译运行,不属于任何 Xcode target)。
//
// 读 Android :core FixtureParityTest 生成的 fixtures/core-cases.json:用 Swift 实现重新解析 payload、
// 重算统计,逐项比对 expect(统计、逐日计入、行程段、地区汇总、完整 CSV)。任何一项不一致都打印差异并 exit(1)。
// 顺带跑几条 v0.12 纯函数的小断言(时区标签、区间补记、阈值编解码、锚点、PBKDF2 向量、备份往返),
// 因为 iOS 端没有单元测试 target,这是 Swift 实现唯一能在 CI 上真正「跑一遍」的地方。
//
// 注意:本程序只用到 Shared 与 Core 里的纯 Foundation 代码,绝不能碰 DataStore.shared(会去读 App Group 容器)。

// MARK: - fixture 结构

struct FixtureFile: Decodable {
    let version: Int
    let cases: [FixtureCase]
}

struct FixtureCase: Decodable {
    let name: String
    let today: String
    let nowHour: Int?
    let earliest: String?
    let year: Int?
    let from: String?
    let to: String?
    let payload: String
    let expect: Expect
}

struct Expect: Decodable {
    let firstDate: String
    let lastDate: String
    let recordedDays: Double
    let trackingSince: String?
    let unrecorded: [String]
    let cities: [ECity]
    let days: [EDay]
    let stays: [EStay]
    let regions: [ERegion]
    let csv: String
}

struct ECity: Decodable {
    let cityKey: String
    let cityName: String
    let days: Double
    let fullDays: Int
    let halfDays: Int
    let provisionalHalf: Int
}

struct EDay: Decodable {
    let date: String
    let manual: Bool
    let provisional: Bool
    let shares: [EShare]
}

struct EShare: Decodable {
    let cityKey: String
    let weight: Double
    let manual: Bool
}

struct EStay: Decodable {
    let cityKey: String
    let from: String
    let to: String
    let days: Double
}

struct ERegion: Decodable {
    let code: String
    let days: Double
    let cities: Int
}

// MARK: - 比对工具

var failures: [String] = []

func fail(_ ctx: String, _ message: String) {
    failures.append("[\(ctx)] \(message)")
}

func expectEqual<T: Equatable>(_ ctx: String, _ field: String, actual: T, expected: T) {
    if actual != expected {
        fail(ctx, "\(field): 期望 \(expected),实际 \(actual)")
    }
}

func describeCity(_ c: CityStat) -> String {
    "\(c.cityKey)/\(c.cityName) days=\(c.days) full=\(c.fullDays) half=\(c.halfDays) prov=\(c.provisionalHalf)"
}

func describeCity(_ c: ECity) -> String {
    "\(c.cityKey)/\(c.cityName) days=\(c.days) full=\(c.fullDays) half=\(c.halfDays) prov=\(c.provisionalHalf)"
}

/// CSV 必须逐字节一致:按 UTF-8 字节比较(Swift 的 String == 走 Unicode 规范等价,比字节宽松)
func compareCsv(_ ctx: String, actual: String, expected: String) {
    if Array(actual.utf8) == Array(expected.utf8) { return }
    let a = actual.components(separatedBy: "\r\n")
    let e = expected.components(separatedBy: "\r\n")
    var lines: [String] = []
    for i in 0..<max(a.count, e.count) {
        let x = i < a.count ? a[i] : "<缺>"
        let y = i < e.count ? e[i] : "<缺>"
        if Array(x.utf8) != Array(y.utf8) {
            lines.append("  第 \(i + 1) 行\n    期望: \(y.debugDescription)\n    实际: \(x.debugDescription)")
        }
    }
    if lines.isEmpty { lines.append("  (逐行相同,差在换行或 BOM)") }
    fail(ctx, "CSV 不一致:\n" + lines.joined(separator: "\n"))
}

func date(_ s: String, _ ctx: String) -> LocalDate {
    guard let d = LocalDate(parse: s) else {
        fail(ctx, "日期解析失败: \(s)")
        return LocalDate(year: 1970, month: 1, day: 1)
    }
    return d
}

// MARK: - fixture 用例

func runCase(_ c: FixtureCase) {
    let w = c.name
    let payload: MigrationPayload
    do {
        payload = try MigrationCodec.parse(Data(c.payload.utf8))
    } catch {
        fail(w, "payload 解析失败: \(error.localizedDescription)")
        return
    }
    let today = date(c.today, w)
    let earliest = c.earliest.map { date($0, w) }
    let stats: YearStats
    if let year = c.year {
        stats = DayCounting.computeYearStats(
            year: year, today: today, punches: payload.punches, overrides: payload.overrides,
            nowHour: c.nowHour, earliestRecordDate: earliest
        )
    } else {
        guard let from = c.from, let to = c.to else {
            fail(w, "用例既没有 year 也没有 from/to")
            return
        }
        stats = DayCounting.computeRangeStats(
            from: date(from, w), to: date(to, w), today: today,
            punches: payload.punches, overrides: payload.overrides,
            nowHour: c.nowHour, earliestRecordDate: earliest
        )
    }
    let e = c.expect

    expectEqual(w, "firstDate", actual: stats.firstDate.description, expected: e.firstDate)
    expectEqual(w, "lastDate", actual: stats.lastDate.description, expected: e.lastDate)
    expectEqual(w, "recordedDays", actual: stats.recordedDays, expected: e.recordedDays)
    expectEqual(w, "trackingSince", actual: stats.trackingSince?.description ?? "null", expected: e.trackingSince ?? "null")
    expectEqual(w, "unrecorded", actual: stats.unrecordedDates.map(\.description), expected: e.unrecorded)

    // 城市:顺序、每个字段
    let actualCities = stats.cities.map { describeCity($0) }
    let expectedCities = e.cities.map { describeCity($0) }
    if actualCities != expectedCities {
        fail(w, "cities 不一致:\n    期望 \(expectedCities)\n    实际 \(actualCities)")
    }

    // 逐日:日期集合、每天的 manual / provisional、每份 share
    let dates = stats.days.keys.sorted()
    expectEqual(w, "days.count", actual: dates.count, expected: e.days.count)
    for (i, ed) in e.days.enumerated() where i < dates.count {
        let d = dates[i]
        let dw = "\(w) · \(ed.date)"
        expectEqual(dw, "date", actual: d.description, expected: ed.date)
        guard let attr = stats.days[d] else { continue }
        expectEqual(dw, "manual", actual: attr.manual, expected: ed.manual)
        expectEqual(dw, "provisional", actual: attr.provisional, expected: ed.provisional)
        let actualShares = attr.shares.map { "\($0.cityKey) \($0.weight) manual=\($0.manual)" }
        let expectedShares = ed.shares.map { "\($0.cityKey) \($0.weight) manual=\($0.manual)" }
        expectEqual(dw, "shares", actual: actualShares, expected: expectedShares)
    }

    // 行程段
    let actualStays = Stays.fold(stats.days).map { "\($0.cityKey) \($0.from)..\($0.to) \($0.days)" }
    let expectedStays = e.stays.map { "\($0.cityKey) \($0.from)..\($0.to) \($0.days)" }
    expectEqual(w, "stays", actual: actualStays, expected: expectedStays)

    // 国家 / 地区
    let actualRegions = Regions.summarize(stats).map { "\($0.code) \($0.days) cities=\($0.cities)" }
    let expectedRegions = e.regions.map { "\($0.code) \($0.days) cities=\($0.cities)" }
    expectEqual(w, "regions", actual: actualRegions, expected: expectedRegions)

    // 完整 CSV(不带导出时间)
    let csv = Exporter.exportCsv(
        stats: stats, punches: payload.punches, includeSummary: true, includeDaily: true, includeStays: true
    )
    compareCsv(w, actual: csv, expected: e.csv)
}

// MARK: - v0.12 纯函数小断言(与 Android CoreV012Test 同一组数值)

func zonedMs(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ zone: String) -> Int64 {
    var cal = Calendar(identifier: .gregorian)
    cal.timeZone = TimeZone(identifier: zone)!
    let at = cal.date(from: DateComponents(year: y, month: mo, day: d, hour: h))!
    return Int64(at.timeIntervalSince1970 * 1000)
}

func makePunch(_ day: String, _ slot: Slot, _ key: String, _ name: String, _ hour: Int,
               zone: String = "Asia/Shanghai", via: Bool = false) -> Punch {
    let d = LocalDate(parse: day)!
    return Punch(
        localDate: d, slot: slot, epochMs: zonedMs(d.year, d.month, d.day, hour, zone), zoneId: zone,
        lat: 0, lng: 0, accuracyM: nil, cityKey: key, cityName: name, viaContext: via
    )
}

func runUnitChecks() {
    let w = "unit"
    // Fmt
    let tokyoMs = zonedMs(2026, 3, 2, 7, "Asia/Tokyo")
    expectEqual(w, "weekdayCn", actual: Fmt.weekdayCn(LocalDate(year: 2026, month: 1, day: 1)), expected: "周四")
    expectEqual(w, "zone Tokyo", actual: Fmt.zoneLabel(zoneId: "Asia/Tokyo", epochMs: tokyoMs), expected: "Asia/Tokyo(UTC+9)")
    expectEqual(w, "zone Kolkata", actual: Fmt.zoneLabel(zoneId: "Asia/Kolkata", epochMs: tokyoMs), expected: "Asia/Kolkata(UTC+5:30)")
    expectEqual(w, "zone London", actual: Fmt.zoneLabel(zoneId: "Europe/London", epochMs: tokyoMs), expected: "Europe/London(UTC)")
    let july = zonedMs(2026, 7, 1, 12, "UTC")
    expectEqual(w, "zone London DST", actual: Fmt.zoneLabel(zoneId: "Europe/London", epochMs: july), expected: "Europe/London(UTC+1)")
    expectEqual(w, "zone NY DST", actual: Fmt.zoneLabel(zoneId: "America/New_York", epochMs: july), expected: "America/New_York(UTC-4)")

    // LocalDate 加减天数
    let d0 = LocalDate(year: 2026, month: 1, day: 2)
    expectEqual(w, "minusDays 179", actual: d0.minusDays(179).description, expected: "2025-07-07")
    expectEqual(w, "plusDays leap", actual: LocalDate(year: 2028, month: 2, day: 28).plusDays(1).description, expected: "2028-02-29")
    expectEqual(w, "daysBetween", actual: LocalDate.daysBetween(LocalDate(year: 2025, month: 12, day: 30), d0), expected: 3)

    // Backfill
    do {
        let plan = try Backfill.planRange(
            from: LocalDate(year: 2026, month: 1, day: 30), to: LocalDate(year: 2026, month: 2, day: 2),
            cityKey: "CN:成都", cityName: "成都", startScope: .evening, endScope: .morning
        )
        expectEqual(w, "planRange scopes", actual: plan.map(\.scope.rawValue), expected: ["EVENING", "FULL", "FULL", "MORNING"])
        expectEqual(w, "planRange last", actual: plan.last?.localDate.description ?? "", expected: "2026-02-02")
        let d1 = LocalDate(year: 2026, month: 5, day: 1)
        let d2 = LocalDate(year: 2026, month: 5, day: 2)
        let merged = Backfill.merge(
            existing: [
                DayOverride(localDate: d1, cityKey: "CN:北京", cityName: "北京", scope: .morning),
                DayOverride(localDate: d1, cityKey: "CN:天津", cityName: "天津", scope: .evening),
                DayOverride(localDate: d2, cityKey: "CN:北京", cityName: "北京"),
            ],
            planned: try Backfill.planRange(from: d1, to: d2, cityKey: "CN:上海", cityName: "上海",
                                            startScope: .evening, endScope: .morning)
        )
        expectEqual(w, "merge", actual: merged.map { "\($0.localDate) \($0.cityName) \($0.scope.rawValue)" },
                    expected: ["2026-05-01 北京 MORNING", "2026-05-01 上海 EVENING", "2026-05-02 上海 MORNING"])
    } catch {
        fail(w, "planRange 不该抛错: \(error.localizedDescription)")
    }
    let sameDay = LocalDate(year: 2026, month: 1, day: 5)
    if (try? Backfill.planRange(from: sameDay, to: sameDay, cityKey: "k", cityName: "n",
                                startScope: .evening, endScope: .morning)) != nil {
        fail(w, "同一天「下午才到」+「中午就走」应当失败")
    }
    if (try? Backfill.planRange(from: sameDay, to: sameDay.minusDays(1), cityKey: "k", cityName: "n")) != nil {
        fail(w, "倒置区间应当失败")
    }

    // Anchors
    let now = makePunch("2026-06-01", .morning, "CN:上海", "上海", 7).epochMs
    let past = makePunch("2026-05-31", .evening, "CN:上海", "上海", 17)
    let future = makePunch("2026-12-31", .morning, "CN:北京", "北京", 7)
    let sticky = makePunch("2026-06-01", .extra, "CN:深圳", "深圳", 6, via: true)
    expectEqual(w, "Anchors.pick", actual: Anchors.pick([past, future, sticky], nowMs: now)?.epochMs ?? -1, expected: past.epochMs)
    expectEqual(w, "Anchors.future", actual: Anchors.future([past, future, sticky], nowMs: now).map(\.epochMs), expected: [future.epochMs])

    // Stays.current / spanDays
    let stays = [Stays.Stay(cityKey: "CN:北京", cityName: "北京", from: LocalDate(year: 2026, month: 4, day: 6),
                            to: LocalDate(year: 2026, month: 4, day: 7), days: 2)]
    expectEqual(w, "Stays.current", actual: Stays.current(stays, today: LocalDate(year: 2026, month: 4, day: 8))?.spanDays ?? -1, expected: 2)
    expectEqual(w, "Stays.current stale", actual: Stays.current(stays, today: LocalDate(year: 2026, month: 4, day: 10)) == nil, expected: true)

    // Thresholds
    let t = Thresholds.Threshold(regionCode: "CN", days: 183)
    expectEqual(w, "threshold ok", actual: Thresholds.status(t, used: 150).level == .ok, expected: true)
    expectEqual(w, "threshold near", actual: Thresholds.status(t, used: 165).level == .near, expected: true)
    expectEqual(w, "threshold reached", actual: Thresholds.status(t, used: 183).level == .reached, expected: true)
    expectEqual(w, "threshold remaining", actual: Thresholds.status(t, used: 190).remaining, expected: 0)
    let small = Thresholds.Threshold(regionCode: "JP", days: 30, window: .rolling180)
    expectEqual(w, "threshold small near", actual: Thresholds.status(small, used: 23).level == .near, expected: true)
    expectEqual(w, "threshold roundtrip", actual: Thresholds.decode(Thresholds.encode([t, small])), expected: [t, small])
    expectEqual(w, "threshold decode bad", actual: Thresholds.decode("CN:183:YEAR;bad;JP:0:YEAR;HK:30:WEEK;:5:YEAR"), expected: [t])
    expectEqual(w, "threshold decode nil", actual: Thresholds.decode(nil).isEmpty, expected: true)
    expectEqual(w, "threshold key", actual: t.notifyKey(today: LocalDate(year: 2026, month: 3, day: 1)), expected: "CN|183|YEAR|2026")
    expectEqual(w, "threshold key rolling", actual: small.notifyKey(today: LocalDate(year: 2026, month: 3, day: 1)), expected: "JP|30|ROLLING_180|2026-3")
    let rolling = Thresholds.range(.rolling180, today: d0)
    expectEqual(w, "rolling range", actual: "\(rolling.from)..\(rolling.to)", expected: "2025-07-07..2026-01-02")

    // Regions 名称
    expectEqual(w, "region names", actual: ["CN", "HK", "JP", "XX"].map { Regions.nameOf($0) }, expected: ["中国大陆", "中国香港", "日本", "XX"])

    // WidgetSummary
    let empty = DayCounting.computeYearStats(year: 2027, today: LocalDate(year: 2027, month: 1, day: 1), punches: [],
                                             overrides: [], nowHour: 3, earliestRecordDate: LocalDate(year: 2026, month: 12, day: 30))
    let model = WidgetSummary.build(stats: empty)
    expectEqual(w, "widget yearLabel", actual: model.yearLabel, expected: "2027 年")
    expectEqual(w, "widget newYearEmpty", actual: model.newYearEmpty, expected: true)

    // 冲突计数口径
    expectEqual(w, "isConflict same", actual: MergeRules.isConflict(local: past, incoming: past), expected: false)
    expectEqual(w, "isConflict diff", actual: MergeRules.isConflict(local: past, incoming: future), expected: true)

    // PBKDF2(RFC 7914 §11 向量)与备份往返
    do {
        let dk = try Backup.pbkdf2(password: Data("passwd".utf8), salt: Data("salt".utf8), iterations: 1, keyLength: 64)
        let hex = dk.map { String(format: "%02x", $0) }.joined()
        expectEqual(w, "pbkdf2 vector", actual: hex, expected:
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
            + "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783")
        let json = try MigrationCodec.toJson(datasetVersion: 3, exportedAtMs: 1, punches: [past], overrides: [])
        let file = try Backup.seal(passphrase: "correct horse", plain: json, iterations: 1000)
        expectEqual(w, "isBackup", actual: Backup.isBackup(file), expected: true)
        let opened = try Backup.open(passphrase: "correct horse", file: file)
        expectEqual(w, "backup roundtrip", actual: opened, expected: json)
        do {
            _ = try Backup.open(passphrase: "wrong horse", file: file)
            fail(w, "错误口令应当失败")
        } catch {
            expectEqual(w, "wrong passphrase message", actual: error.localizedDescription, expected: "口令不对,或备份文件已损坏")
        }
        do {
            _ = try Backup.open(passphrase: "correct horse", file: Data("hello".utf8))
            fail(w, "非备份文件应当失败")
        } catch {
            expectEqual(w, "not backup message", actual: error.localizedDescription, expected: "这不是 TernDays 的备份文件")
        }
        do {
            _ = try Backup.seal(passphrase: "short", plain: json)
            fail(w, "短口令应当失败")
        } catch {
            expectEqual(w, "short passphrase message", actual: error.localizedDescription, expected: "口令至少 8 位")
        }
    } catch {
        fail(w, "备份检查异常: \(error.localizedDescription)")
    }
}

// MARK: - 入口

let args = CommandLine.arguments
let path = args.count > 1 ? args[1] : "fixtures/core-cases.json"
guard let raw = FileManager.default.contents(atPath: path) else {
    print("读不到 fixture 文件: \(path)")
    exit(1)
}
let fixture: FixtureFile
do {
    fixture = try JSONDecoder().decode(FixtureFile.self, from: raw)
} catch {
    print("fixture 解析失败: \(error)")
    exit(1)
}
if fixture.cases.isEmpty {
    print("fixture 里没有用例")
    exit(1)
}
for c in fixture.cases {
    runCase(c)
}
runUnitChecks()

if failures.isEmpty {
    print("fixture parity OK (\(fixture.cases.count) cases)")
    exit(0)
}
print("fixture parity FAILED(\(failures.count) 处不一致):")
for f in failures {
    print("- " + f)
}
exit(1)
