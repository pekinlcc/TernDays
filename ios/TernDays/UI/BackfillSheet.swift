import SwiftUI

/// 手动补记:单日,或出差回来一次补一整段(首日可以只补下半天、末日可以只补上半天)。
/// 日期不限于今年、也不限于「开始记录日」之后——更早的日子补上后,开始记录日随之提前。
/// 单日补完不关页,可以接着补下一天;区间补完直接收起。写入统一走 Corrections(带「撤销」)。
struct BackfillSheet: View {
    /// 打开时默认选中的日期(通常是这一年最近的一个无记录日)
    let initialDate: LocalDate

    @Environment(\.dismiss) private var dismiss

    private enum Mode: Hashable { case single, range }

    @State private var mode: Mode = .single
    @State private var day: Date
    @State private var start: Date
    @State private var end: Date
    /// 首日下午才到(首日只补下半天)
    @State private var arriveAfternoon = false
    /// 末日中午就走(末日只补上半天)
    @State private var leaveNoon = false
    /// 这一年还空着的日子(倒序),补一天少一天
    @State private var pending: [LocalDate]
    @State private var info = SelectionInfo()
    /// 补了比开始记录日更早的日子:说明一句为什么「可补记」的天数变多了
    @State private var sinceNote: String?
    /// 说明的参照:这次打开后第一次把开始记录日提前之前的那个日期(之前一条记录都没有时是 9999-12-31)。
    /// 撤销后开始记录日不再早于它,说明就不成立了
    @State private var sinceBaseline: LocalDate?
    /// 这次打开后从「还没有记录的日子」里补掉的日子:撤销后又空了就放回去
    @State private var taken: [LocalDate] = []
    @State private var planError: String?

    /// 选中日期(区间)的现状与候选城市
    private struct SelectionInfo {
        var current: String?
        var recordedCount = 0
        var dayCount = 0
        var suggestions: [CityOption] = []
    }

    init(initialDate: LocalDate, unrecorded: [LocalDate]) {
        self.initialDate = initialDate
        let d = initialDate.noonDate()
        _day = State(initialValue: d)
        _start = State(initialValue: d)
        _end = State(initialValue: d)
        _pending = State(initialValue: unrecorded.sorted(by: >))
    }

    /// DatePicker 的上限:今天(取今晚 23 点,避免「今天中午」因晚于此刻而选不了)
    private var upperBound: Date { LocalDate.today().noonDate().addingTimeInterval(11 * 3600) }

    private var selected: (from: LocalDate, to: LocalDate) {
        switch mode {
        case .single:
            let d = LocalDate(from: day, in: .current)
            return (d, d)
        case .range:
            return (LocalDate(from: start, in: .current), LocalDate(from: end, in: .current))
        }
    }

    private var validation: String? {
        guard mode == .range else { return nil }
        let r = selected
        if r.to < r.from { return "结束日期不能早于开始日期" }
        if r.from == r.to && arriveAfternoon && leaveNoon { return "同一天不能既「下午才到」又「中午就走」" }
        return nil
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Picker("补记方式", selection: $mode) {
                        Text("单日").tag(Mode.single)
                        Text("区间").tag(Mode.range)
                    }
                    .pickerStyle(.segmented)
                    if mode == .single {
                        DatePicker("日期", selection: $day, in: ...upperBound, displayedComponents: .date)
                    } else {
                        DatePicker("开始", selection: $start, in: ...upperBound, displayedComponents: .date)
                        DatePicker("结束", selection: $end, in: ...upperBound, displayedComponents: .date)
                        Toggle("首日下午才到", isOn: $arriveAfternoon)
                        Toggle("末日中午就走", isOn: $leaveNoon)
                    }
                    statusLines
                }
                if mode == .single && !pending.isEmpty {
                    Section("还没有记录的日子") {
                        pendingChips
                    }
                }
                Section("在哪个城市?") {
                    if let msg = validation {
                        Text(msg).font(.system(size: 13)).foregroundColor(Td.warmDeep)
                    } else {
                        CityPicker(suggestions: info.suggestions) { key, name in pick(key, name) }
                    }
                }
            }
            .navigationTitle("手动补记")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .onAppear { refresh() }
            .onChange(of: mode) { _ in refresh() }
            .onChange(of: day) { _ in refresh() }
            .onChange(of: start) { _ in refresh() }
            .onChange(of: end) { _ in refresh() }
            // 单日补完不关页,toast 里的「撤销」就在这一页上点:数据一变,现状、待补列表、说明都要跟上
            .onReceive(NotificationCenter.default.publisher(for: .terndaysDataChanged)) { _ in
                refresh()
                resync()
            }
        }
        .toastHost()
    }

    /// 选中日期的现状、区间内已有记录的天数、开始记录日提前的说明、写入失败的原因
    @ViewBuilder
    private var statusLines: some View {
        if mode == .single, let current = info.current {
            Text(current).font(.system(size: 12)).foregroundColor(Td.muted)
        }
        if mode == .range && validation == nil && info.dayCount > 0 {
            Text(rangeSummary).font(.system(size: 12)).foregroundColor(Td.muted)
        }
        if let note = sinceNote {
            Text(note).font(.system(size: 12)).foregroundColor(Td.warmDeep)
        }
        if let e = planError {
            Text(e).font(.system(size: 12)).foregroundColor(Td.warmDeep)
        }
    }

    private var rangeSummary: String {
        if info.recordedCount > 0 {
            return "共 \(info.dayCount) 天,其中 \(info.recordedCount) 天已有记录,会一并改为所选城市"
        }
        return "共 \(info.dayCount) 天"
    }

    private var pendingChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(pending.prefix(30), id: \.self) { d in
                    let isOn = LocalDate(from: day, in: .current) == d
                    Button {
                        day = d.noonDate()
                    } label: {
                        Text("\(d.month)月\(d.day)日 \(d.weekdayCn)")
                            .font(.system(size: 12, weight: isOn ? .semibold : .regular))
                            .foregroundColor(isOn ? Td.onAccent : Td.muted)
                            .padding(.horizontal, 12)
                            .frame(minHeight: 44)
                            .background(Capsule().fill(isOn ? Td.accent : Td.neutralSoft))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    /// 重新读选中日期(区间)及前后一天:现状、已有记录的天数、候选城市
    private func refresh() {
        planError = nil
        let r = selected
        guard r.from <= r.to else {
            info = SelectionInfo()
            return
        }
        let today = LocalDate.today()
        let hour = Calendar.current.component(.hour, from: Date())
        let lo = r.from.minusDays(1)
        let after = r.to.plusDays(1)
        let hi = after <= today ? after : max(r.to, today)
        let stats = DayCounting.computeRangeStats(
            from: lo, to: hi, today: today,
            punches: DataStore.shared.punchesBetween(lo, hi),
            overrides: DataStore.shared.overridesBetween(lo, hi),
            nowHour: hour
        )
        var recorded = 0
        var d = r.from
        while d <= r.to {
            if let shares = stats.days[d]?.shares, !shares.isEmpty { recorded += 1 }
            if d == r.to { break }
            d = d.next()
        }
        var current: String?
        if r.from == r.to {
            if let shares = stats.days[r.from]?.shares, !shares.isEmpty {
                let text = shares.map { $0.cityName + ($0.weight >= 1.0 ? " +1" : " +0.5") }.joined(separator: " / ")
                current = "这一天现在记为:\(text)。补记会按整天改为所选城市"
            } else {
                current = "这一天现在没有记录"
            }
        }
        let prev = (stats.days[lo]?.shares ?? []).reversed().map {
            CityOption(key: $0.cityKey, name: $0.cityName, note: "前一天")
        }
        var next: [CityOption] = []
        if hi == after {
            next = (stats.days[after]?.shares ?? []).map { CityOption(key: $0.cityKey, name: $0.cityName, note: "后一天") }
        }
        info = SelectionInfo(
            current: current,
            recordedCount: recorded,
            dayCount: LocalDate.daysBetween(r.from, r.to) + 1,
            suggestions: CityOption.merge([prev, next, CityOption.recent()])
        )
    }

    private func pick(_ key: String, _ name: String) {
        let r = selected
        let startScope: OverrideScope = mode == .range && arriveAfternoon ? .evening : .full
        let endScope: OverrideScope = mode == .range && leaveNoon ? .morning : .full
        let plan: [DayOverride]
        do {
            plan = try Backfill.planRange(
                from: r.from, to: r.to, cityKey: key, cityName: name, startScope: startScope, endScope: endScope
            )
        } catch {
            planError = error.localizedDescription
            return
        }
        let before = DataStore.shared.earliestRecordDate()
        // 与 Android 同一判断:之前一条记录都没有时,补上的这天同样成为开始记录日
        let earlier = before.map { r.from < $0 } ?? true
        let label = r.from == r.to ? Fmt.monthDay(r.from) : "\(Fmt.monthDay(r.from)) – \(Fmt.monthDay(r.to))"
        // 区间补完就收起,页上的说明看不到:开始记录日前移只能写进 toast
        let note = (mode == .range && earlier) ? "；开始记录日提前到 \(Fmt.monthDay(r.from))" : ""
        taken.append(contentsOf: pending.filter { $0 >= r.from && $0 <= r.to })
        Corrections.setMany(plan, toast: "已补记 \(label) · \(name)" + note)
        pending.removeAll { $0 >= r.from && $0 <= r.to }
        if earlier {
            if sinceBaseline == nil { sinceBaseline = before ?? LocalDate(year: 9999, month: 12, day: 31) }
            sinceNote = sinceText(r.from)
        }
        if mode == .range {
            dismiss()
        } else {
            refresh()
        }
    }

    private func sinceText(_ d: LocalDate) -> String {
        "开始记录日提前到 \(Fmt.monthDay(d, relativeTo: LocalDate.today())),之后没有记录的日子会算作可补记"
    }

    /// 数据变了(典型:在这一页点了「撤销」):补掉的日子又空了就放回待补列表;
    /// 开始记录日不再早于补记前,收起那句说明(仍提前时按现在的开始记录日重写)
    private func resync() {
        if let lo = taken.min(), let top = taken.max() {
            let today = LocalDate.today()
            let hi = min(top, today)
            if lo <= hi {
                let stats = DayCounting.computeRangeStats(
                    from: lo, to: hi, today: today,
                    punches: DataStore.shared.punchesBetween(lo, hi),
                    overrides: DataStore.shared.overridesBetween(lo, hi),
                    nowHour: Calendar.current.component(.hour, from: Date())
                )
                let back = Set(taken.filter { $0 <= hi && (stats.days[$0]?.shares.isEmpty ?? false) })
                if !back.isEmpty {
                    taken.removeAll { back.contains($0) }
                    pending = Array(Set(pending).union(back)).sorted(by: >)
                }
            }
        }
        if let base = sinceBaseline {
            if let e = DataStore.shared.earliestRecordDate(), e < base {
                sinceNote = sinceText(e)
            } else {
                sinceNote = nil
                sinceBaseline = nil
            }
        }
    }
}
