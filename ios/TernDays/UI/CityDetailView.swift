import SwiftUI

struct CityDetailView: View {
    let cityKey: String
    let year: Int

    @State private var data: YearData?
    @State private var month = 0
    @State private var correcting: CorrectTarget?
    @Environment(\.dismiss) private var dismiss

    /// manual:这座城市这天的份额来自手动更正;provisional:进行中的今天,先算半天
    private struct CityDay {
        let weight: Double
        let manual: Bool
        let provisional: Bool
    }

    private var cityDays: [LocalDate: CityDay] {
        guard let d = data else { return [:] }
        var out: [LocalDate: CityDay] = [:]
        for (date, attr) in d.stats.days {
            if let share = attr.shares.first(where: { $0.cityKey == cityKey }) {
                out[date] = CityDay(weight: share.weight, manual: share.manual, provisional: attr.provisional)
            }
        }
        return out
    }

    private var stat: CityStat? { data?.stats.cities.first { $0.cityKey == cityKey } }

    /// 日历格子的样子:这座城市(全天 / 半天 / 进行中)、别的城市、无记录(开始记录日之后、今天之前)、其余留白
    private enum CellKind {
        case city(CityDay)
        case other
        case missing
        case blank
    }

    private func kind(of date: LocalDate, days: [LocalDate: CityDay]) -> CellKind {
        if let d = days[date] { return .city(d) }
        guard let attr = data?.stats.days[date] else { return .blank }
        if !attr.shares.isEmpty { return .other }
        if let since = data?.stats.trackingSince, date >= since, date < LocalDate.today() { return .missing }
        return .blank
    }

    /// 这座城市作为更正候选的第一项
    private var thisCity: CityOption? {
        stat.map { CityOption(key: $0.cityKey, name: $0.cityName, note: "本页城市") }
    }

    var body: some View {
        let days = cityDays
        ScrollView {
            VStack(spacing: 12) {
                HStack(alignment: .lastTextBaseline) {
                    Text(stat.map { DayCounting.formatDays($0.days) } ?? "0")
                        .font(.system(size: 32, weight: .bold)).foregroundColor(Td.accentDeep)
                    Text("天").font(.system(size: 13)).foregroundColor(Td.muted)
                    Spacer()
                    Text("\(String(year)) 年累计 · 全天 \(stat?.fullDays ?? 0) 天 + 半天 \(stat?.halfDays ?? 0) 次")
                        .font(.system(size: 12)).foregroundColor(Td.muted)
                }
                .padding(.horizontal, 2)

                if month != 0 { calendarCard(days: days) }

                HStack {
                    Text("打卡明细").font(.system(size: 13, weight: .medium)).foregroundColor(Td.muted)
                    Spacer()
                    Text("点日历或明细里的一天可更正 / 补记").font(.system(size: 11)).foregroundColor(Td.faint)
                }
                .padding(.horizontal, 2)

                detailList(days: days)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
        }
        .background(Td.bg)
        .navigationTitle(stat?.cityName ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { reload() }
        // 后台打卡、别处更正、城市库重解析之后这里也要跟上(此前只在进入时读一次)
        .onReceive(NotificationCenter.default.publisher(for: .terndaysDataChanged)) { _ in reload() }
        .sheet(item: $correcting, onDismiss: dismissIfCityGone) { target in
            // 写入由 Corrections 统一完成(刷新小组件、通知本页与首页重读、toast 带撤销)
            CityCorrectSheet(context: target.context) { correcting = nil }
        }
    }

    private func openCorrection(_ date: LocalDate) {
        correcting = CorrectTarget(date: date, preferred: thisCity)
    }

    /// 后台读、主线程赋值(城市库大、年份长时读档不该卡住界面)
    private func reload() {
        let y = year
        DispatchQueue.global(qos: .userInitiated).async {
            let d = YearData.load(year: y)
            DispatchQueue.main.async {
                data = d
                if month == 0 {
                    let latest = d.stats.days.keys
                        .filter { date in d.stats.days[date]?.shares.contains { $0.cityKey == cityKey } ?? false }
                        .max()
                    month = latest?.month ?? (y == LocalDate.today().year ? LocalDate.today().month : 12)
                }
                if correcting == nil { dismissIfCityGone() }
            }
        }
    }

    /// 这座城市在本年已无任何记录(如最后一天被更正走):返回列表,不停在空页(与 Android 一致)
    private func dismissIfCityGone() {
        guard let d = data, !d.stats.cities.contains(where: { $0.cityKey == cityKey }) else { return }
        dismiss()
    }

    private func calendarCard(days: [LocalDate: CityDay]) -> some View {
        let monthSum = days.filter { $0.key.month == month }.values.reduce(0.0) { $0 + $1.weight }
        let leading = LocalDate(year: year, month: month, day: 1).weekday - 1
        let count = LocalDate.daysIn(year: year, month: month)

        return TdCard {
            VStack(spacing: 8) {
                HStack {
                    monthButton(system: "chevron.left") { if month > 1 { month -= 1 } }
                    Text("\(String(year)) 年 \(month) 月")
                        .font(.system(size: 15, weight: .semibold)).foregroundColor(Td.ink)
                        .frame(maxWidth: .infinity)
                    monthButton(system: "chevron.right") { if month < 12 { month += 1 } }
                }
                HStack {
                    ForEach(["一", "二", "三", "四", "五", "六", "日"], id: \.self) { w in
                        Text(w).font(.system(size: 11)).foregroundColor(Td.faint).frame(maxWidth: .infinity)
                    }
                }
                let cells: [LocalDate?] = Array(repeating: nil, count: leading) +
                    (1...count).map { LocalDate(year: year, month: month, day: $0) }
                let todayDate = LocalDate.today()
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 7), spacing: 4) {
                    ForEach(0..<cells.count, id: \.self) { i in
                        calendarCell(cells[i], days: days, today: todayDate)
                    }
                }
                legend
                HStack {
                    Spacer()
                    Text("本月 \(DayCounting.formatDays(monthSum)) 天")
                        .font(.system(size: 12, weight: .semibold)).foregroundColor(Td.accentDeep)
                }
            }
            .padding(14)
        }
    }

    /// 今天及以前的每一天都能点:别的城市、没有记录的日子也能直接改成(补成)这座城市
    @ViewBuilder
    private func calendarCell(_ date: LocalDate?, days: [LocalDate: CityDay], today: LocalDate) -> some View {
        if let date {
            let k = kind(of: date, days: days)
            if date <= today {
                dayCell(date: date, kind: k)
                    .contentShape(Rectangle())
                    .onTapGesture { openCorrection(date) }
                    .accessibilityAddTraits(.isButton)
            } else {
                dayCell(date: date, kind: k)
            }
        } else {
            Color.clear.frame(height: 42)
        }
    }

    private var legend: some View {
        HStack(spacing: 10) {
            legendItem("全天") { RoundedRectangle(cornerRadius: 4).fill(Td.accentSoft) }
            legendItem("半天") { halfSwatch(Td.accentSoft) }
            legendItem("进行中") { halfSwatch(Td.warmSoft) }
            legendItem("其他城市") { RoundedRectangle(cornerRadius: 4).fill(Td.neutralSoft) }
            legendItem("无记录") {
                RoundedRectangle(cornerRadius: 4).stroke(Td.chevron, style: StrokeStyle(lineWidth: 1, dash: [3, 2]))
            }
            Spacer(minLength: 0)
        }
        .padding(.top, 2)
    }

    private func legendItem<S: View>(_ label: String, @ViewBuilder swatch: () -> S) -> some View {
        HStack(spacing: 4) {
            swatch().frame(width: 12, height: 12)
            Text(label).font(.system(size: 11)).foregroundColor(Td.muted).lineLimit(1)
        }
    }

    private func monthButton(system: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.system(size: 13, weight: .semibold)).foregroundColor(Td.ink)
                .frame(width: 32, height: 32)
                .background(RoundedRectangle(cornerRadius: 10).fill(Td.bg))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(system == "chevron.left" ? "上个月" : "下个月")
    }

    /// 左上三角填色的「半天」色块
    private func halfSwatch(_ color: Color, radius: CGFloat = 4) -> some View {
        GeometryReader { geo in
            Path { p in
                p.move(to: .zero)
                p.addLine(to: CGPoint(x: geo.size.width, y: 0))
                p.addLine(to: CGPoint(x: 0, y: geo.size.height))
                p.closeSubpath()
            }
            .fill(color)
        }
        .background(RoundedRectangle(cornerRadius: radius).stroke(color, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: radius))
    }

    @ViewBuilder
    private func cellBackground(_ kind: CellKind) -> some View {
        switch kind {
        case .city(let day):
            if day.provisional {
                halfSwatch(Td.warmSoft, radius: 10)
            } else if day.weight >= 1.0 {
                RoundedRectangle(cornerRadius: 10).fill(Td.accentSoft)
            } else {
                halfSwatch(Td.accentSoft, radius: 10)
            }
        case .other:
            RoundedRectangle(cornerRadius: 10).fill(Td.neutralSoft)
        case .missing:
            RoundedRectangle(cornerRadius: 10).stroke(Td.chevron, style: StrokeStyle(lineWidth: 1, dash: [3, 2]))
        case .blank:
            Color.clear
        }
    }

    private func dayCell(date: LocalDate, kind: CellKind) -> some View {
        var weight: Font.Weight = .regular
        var color: Color = Td.faint
        if case .city(let day) = kind {
            weight = .semibold
            color = day.weight >= 1.0 && !day.provisional ? Td.accentDeep : Td.ink
        } else if case .other = kind {
            color = Td.muted
        }
        return ZStack {
            cellBackground(kind)
            Text("\(date.day)")
                .font(.system(size: 13, weight: weight))
                .foregroundColor(color)
        }
        .frame(height: 42)
    }

    private func detailList(days: [LocalDate: CityDay]) -> some View {
        let dates = days.keys.sorted(by: >)
        let punchesByDate = Dictionary(grouping: data?.punches ?? [], by: { $0.localDate })
        return TdCard {
            VStack(spacing: 0) {
                if dates.isEmpty {
                    Text("暂无记录").font(.system(size: 13)).foregroundColor(Td.faint)
                        .frame(maxWidth: .infinity).padding(.vertical, 20)
                }
                ForEach(Array(dates.enumerated()), id: \.element) { i, date in
                    if i > 0 { Divider().overlay(Td.divider) }
                    let day = days[date]!
                    let punches = punchesByDate[date] ?? []
                    let m = punches.first { $0.slot == .morning }
                    let e = punches.first { $0.slot == .evening }
                    let x = punches.first { $0.slot == .extra }
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("\(date.month)月\(date.day)日 · \(date.weekdayCn)")
                                .font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                            Text(subText(day: day, m: m, e: e, x: x))
                                .font(.system(size: 12)).foregroundColor(Td.muted)
                        }
                        Spacer()
                        // 主标签永远说「算了多少」:全天 / 半天 / 进行中(暂时算半天,与跨城日的半天不是一回事);
                        // 「手动」退为次级角标,且只在这座城市的份额确实来自更正时出现
                        VStack(alignment: .trailing, spacing: 3) {
                            if day.provisional {
                                TagView(text: "进行中", bg: Td.warmSoft, fg: Td.warmDeep)
                            } else if day.weight >= 1.0 {
                                TagView(text: "全天", bg: Td.accentSoft, fg: Td.accentDeep)
                            } else {
                                TagView(text: "半天", bg: Td.accentSoft, fg: Td.accentDeep)
                            }
                            if day.manual {
                                Text("手动").font(.system(size: 10)).foregroundColor(Td.warmDeep)
                            }
                        }
                    }
                    .padding(.vertical, 11)
                    .contentShape(Rectangle())
                    .onTapGesture { openCorrection(date) }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private func subText(day: CityDay, m: Punch?, e: Punch?, x: Punch?) -> String {
        var parts: [(Int64, String)] = []
        if let m { parts.append((m.epochMs, "早 \(m.clock) \(m.cityName)")) }
        if let e { parts.append((e.epochMs, "晚 \(e.clock) \(e.cityName)")) }
        if let x { parts.append((x.epochMs, "首 \(x.clock) \(x.cityName)")) }
        let detail = parts.sorted { $0.0 < $1.0 }.map(\.1).joined(separator: " · ")
        if day.provisional {
            return [detail, "今天先算半天,另半天打上后补满"].filter { !$0.isEmpty }.joined(separator: " · ")
        }
        if day.manual { return detail.isEmpty ? "手动补记" : "已手动更正 · 当天打卡:" + detail }
        return detail.isEmpty ? "无打卡记录" : detail
    }
}
