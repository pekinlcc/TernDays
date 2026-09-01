import SwiftUI
import WidgetKit

struct CityDetailView: View {
    let cityKey: String
    let year: Int

    @State private var data: YearData?
    @State private var month = 0
    @State private var correcting: CorrectTarget?

    private struct CityDay {
        let weight: Double
        let manual: Bool
    }

    private var cityDays: [LocalDate: CityDay] {
        guard let d = data else { return [:] }
        var out: [LocalDate: CityDay] = [:]
        for (date, attr) in d.stats.days {
            if let share = attr.shares.first(where: { $0.cityKey == cityKey }) {
                out[date] = CityDay(weight: share.weight, manual: attr.manual)
            }
        }
        return out
    }

    private var stat: CityStat? { data?.stats.cities.first { $0.cityKey == cityKey } }

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
                    Text("点一天可更正城市").font(.system(size: 11)).foregroundColor(Td.faint)
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
        .task {
            let d = YearData.load(year: year)
            data = d
            if month == 0 {
                let latest = d.stats.days.keys
                    .filter { date in d.stats.days[date]?.shares.contains { $0.cityKey == cityKey } ?? false }
                    .max()
                month = latest?.month ?? (year == LocalDate.today().year ? LocalDate.today().month : 12)
            }
        }
        .sheet(item: $correcting) { target in
            let dayPunches = (data?.punches ?? []).filter { $0.localDate == target.date }
            CityCorrectSheet(
                date: target.date,
                currentCityName: data?.stats.days[target.date]?.shares.map(\.cityName).joined(separator: " + "),
                recentCities: data?.stats.cities.map { ($0.cityKey, $0.cityName) } ?? [],
                hasOverride: data?.overrides.contains { $0.localDate == target.date } ?? false,
                hasBothHalves: dayPunches.contains { $0.slot == .morning } && dayPunches.contains { $0.slot == .evening },
                onPick: { key, name, scope in
                    DataStore.shared.setOverride(DayOverride(localDate: target.date, cityKey: key, cityName: name, scope: scope))
                    afterCorrection()
                },
                onRestoreAuto: {
                    DataStore.shared.removeOverride(date: target.date)
                    afterCorrection()
                }
            )
        }
    }

    private func afterCorrection() {
        WidgetCenter.shared.reloadAllTimelines()
        NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
        correcting = nil
        data = YearData.load(year: year)
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
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 4), count: 7), spacing: 4) {
                    ForEach(0..<cells.count, id: \.self) { i in
                        let date = cells[i]
                        let day = date.flatMap { days[$0] }
                        dayCell(date: date, day: day)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                if let date, day != nil { correcting = CorrectTarget(date: date) }
                            }
                    }
                }
                HStack(spacing: 6) {
                    RoundedRectangle(cornerRadius: 4).fill(Td.accentSoft).frame(width: 14, height: 14)
                    Text("全天").font(.system(size: 11)).foregroundColor(Td.muted)
                    halfSwatch.frame(width: 14, height: 14).padding(.leading, 8)
                    Text("半天").font(.system(size: 11)).foregroundColor(Td.muted)
                    Spacer()
                    Text("本月 \(DayCounting.formatDays(monthSum)) 天")
                        .font(.system(size: 12, weight: .semibold)).foregroundColor(Td.accentDeep)
                }
                .padding(.top, 2)
            }
            .padding(14)
        }
    }

    private func monthButton(system: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: system)
                .font(.system(size: 13, weight: .semibold)).foregroundColor(Td.ink)
                .frame(width: 32, height: 32)
                .background(RoundedRectangle(cornerRadius: 10).fill(Td.bg))
        }
        .buttonStyle(.plain)
    }

    private var halfSwatch: some View {
        GeometryReader { geo in
            Path { p in
                p.move(to: .zero)
                p.addLine(to: CGPoint(x: geo.size.width, y: 0))
                p.addLine(to: CGPoint(x: 0, y: geo.size.height))
                p.closeSubpath()
            }
            .fill(Td.accentSoft)
        }
        .background(RoundedRectangle(cornerRadius: 4).stroke(Td.accentSoft, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 4))
    }

    @ViewBuilder
    private func dayCell(date: LocalDate?, day: CityDay?) -> some View {
        ZStack {
            if let day {
                if day.weight >= 1.0 {
                    RoundedRectangle(cornerRadius: 10).fill(Td.accentSoft)
                } else {
                    GeometryReader { geo in
                        Path { p in
                            p.move(to: .zero)
                            p.addLine(to: CGPoint(x: geo.size.width, y: 0))
                            p.addLine(to: CGPoint(x: 0, y: geo.size.height))
                            p.closeSubpath()
                        }
                        .fill(Td.accentSoft)
                    }
                    .background(RoundedRectangle(cornerRadius: 10).stroke(Td.accentSoft, lineWidth: 1))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }
            }
            if let date {
                Text("\(date.day)")
                    .font(.system(size: 13, weight: day != nil ? .semibold : .regular))
                    .foregroundColor(day == nil ? Td.faint : (day!.weight >= 1.0 ? Td.accentDeep : Td.ink))
            }
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
                        if day.manual {
                            TagView(text: "手动", bg: Td.warmSoft, fg: Td.warmDeep)
                        } else if day.weight >= 1.0 {
                            TagView(text: "全天", bg: Td.accentSoft, fg: Td.accentDeep)
                        } else {
                            TagView(text: "半天", bg: Td.accentSoft, fg: Td.accentDeep)
                        }
                    }
                    .padding(.vertical, 11)
                    .contentShape(Rectangle())
                    .onTapGesture { correcting = CorrectTarget(date: date) }
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
        if day.manual { return detail.isEmpty ? "手动补记" : "已手动更正 · 当天打卡:" + detail }
        return detail.isEmpty ? "无打卡记录" : detail
    }
}
