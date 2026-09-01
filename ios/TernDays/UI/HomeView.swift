import SwiftUI

struct HomeView: View {
    @State private var year = LocalDate.today().year
    @State private var data: YearData?
    @ObservedObject private var punch = PunchManager.shared

    private var today: LocalDate { LocalDate.today() }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if punch.authStatus != .authorizedAlways {
                    NavigationLink(value: "settings") {
                        HStack(spacing: 10) {
                            Image(systemName: "exclamationmark.triangle")
                                .font(.system(size: 15)).foregroundColor(Td.warmDeep)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("自动打卡还没就绪")
                                    .font(.system(size: 13, weight: .semibold)).foregroundColor(Td.warmDeep)
                                Text("定位权限未设为「始终允许」，点击去完成设置")
                                    .font(.system(size: 11)).foregroundColor(Td.warmDeep)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 12)).foregroundColor(Td.warmDeep)
                        }
                        .padding(.horizontal, 14).padding(.vertical, 12)
                        .background(RoundedRectangle(cornerRadius: 14).fill(Td.warmSoft))
                    }
                    .buttonStyle(.plain)
                }
                summaryCard
                if year == today.year { todayCard }
                HStack {
                    Text("\(String(year)) 年去过的城市")
                        .font(.system(size: 13, weight: .medium)).foregroundColor(Td.muted)
                    Spacer()
                    Text("按天数排序").font(.system(size: 11)).foregroundColor(Td.faint)
                }
                .padding(.horizontal, 2)
                cityList
                Text("每天 07:00 / 17:00 打开或唤醒时自动记录\n所有数据仅保存在本机")
                    .font(.system(size: 11)).foregroundColor(Td.faint)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 6)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
        }
        .background(Td.bg)
        .navigationTitle("TernDays")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(value: "export") { Image(systemName: "square.and.arrow.up") }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(value: "settings") { Image(systemName: "slider.horizontal.3") }
            }
        }
        .navigationDestination(for: String.self) { route in
            if route == "export" { ExportView(initialYear: year) }
            if route == "settings" { SettingsView() }
        }
        .navigationDestination(for: CityRoute.self) { route in
            CityDetailView(cityKey: route.cityKey, year: route.year)
        }
        .task(id: year) { reload() }
        .onReceive(NotificationCenter.default.publisher(for: .terndaysDataChanged)) { _ in reload() }
    }

    private func reload() {
        let y = year
        DispatchQueue.global().async {
            let d = YearData.load(year: y)
            DispatchQueue.main.async { if year == y { data = d } }
        }
    }

    private var summaryCard: some View {
        TdCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Menu {
                        ForEach(data?.years ?? [year], id: \.self) { y in
                            Button("\(String(y)) 年") { year = y }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text("\(String(year)) 年")
                                .font(.system(size: 15, weight: .semibold)).foregroundColor(Td.ink)
                            Image(systemName: "chevron.down")
                                .font(.system(size: 11, weight: .semibold)).foregroundColor(Td.faint)
                        }
                    }
                    Spacer()
                    if let s = data?.stats {
                        Text("\(s.firstDate.month)月\(s.firstDate.day)日 – \(s.lastDate.month)月\(s.lastDate.day)日")
                            .font(.system(size: 12)).foregroundColor(Td.muted)
                    }
                }
                HStack(alignment: .bottom, spacing: 26) {
                    bigStat(value: data.map { String($0.stats.recordedDays) } ?? "–", label: "天已记录")
                    bigStat(value: data.map { String($0.stats.cities.count) } ?? "–", label: "个城市")
                    Spacer()
                    if let missing = data?.stats.unrecordedDates.count, missing > 0 {
                        Text("另有 \(missing) 天无记录")
                            .font(.system(size: 11)).foregroundColor(Td.faint)
                    }
                }
            }
            .padding(16)
        }
    }

    private func bigStat(value: String, label: String) -> some View {
        HStack(alignment: .lastTextBaseline, spacing: 4) {
            Text(value).font(.system(size: 30, weight: .bold)).foregroundColor(Td.accentDeep)
            Text(label).font(.system(size: 12)).foregroundColor(Td.muted)
        }
    }

    private var todayCard: some View {
        let punches = data?.punches.filter { $0.localDate == today } ?? []
        let morning = punches.first { $0.slot == .morning }
        let evening = punches.first { $0.slot == .evening }
        return TdCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("今日打卡 · \(today.month)月\(today.day)日 \(today.weekdayCn)")
                    .font(.system(size: 12)).foregroundColor(Td.muted)
                HStack(spacing: 0) {
                    punchCell(icon: "sun.max", tint: Color(hex: 0xA9762F), label: "早 · 07:00", punch: morning)
                    Rectangle().fill(Td.border).frame(width: 1, height: 40)
                    punchCell(icon: "sunset", tint: Td.faint, label: "晚 · 17:00", punch: evening)
                        .padding(.leading, 16)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 13)
        }
    }

    private func punchCell(icon: String, tint: Color, label: String, punch: Punch?) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 17)).foregroundColor(tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(label).font(.system(size: 11)).foregroundColor(Td.faint)
                if let p = punch {
                    HStack(spacing: 5) {
                        Text(p.cityName).font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .bold)).foregroundColor(Td.accent)
                    }
                } else {
                    Text("待记录").font(.system(size: 14)).foregroundColor(Td.faint)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
    }

    private var cityList: some View {
        TdCard {
            VStack(spacing: 0) {
                let cities = data?.stats.cities ?? []
                if data != nil && cities.isEmpty {
                    VStack(spacing: 6) {
                        Text("还没有打卡记录")
                            .font(.system(size: 15, weight: .semibold)).foregroundColor(Td.ink)
                        Text("到点后打开应用（或被系统唤醒）会自动记录所在城市")
                            .font(.system(size: 12)).foregroundColor(Td.muted)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 28)
                }
                ForEach(Array(cities.enumerated()), id: \.element.id) { i, c in
                    if i > 0 { Divider().overlay(Td.divider) }
                    NavigationLink(value: CityRoute(cityKey: c.cityKey, year: year)) {
                        HStack(spacing: 10) {
                            Text(c.cityName)
                                .font(.system(size: 20, weight: .bold)).foregroundColor(Td.ink)
                            Spacer()
                            HStack(alignment: .lastTextBaseline, spacing: 3) {
                                Text(DayCounting.formatDays(c.days))
                                    .font(.system(size: 26, weight: .bold)).foregroundColor(Td.accentDeep)
                                Text("天").font(.system(size: 11)).foregroundColor(Td.faint)
                            }
                            Image(systemName: "chevron.right")
                                .font(.system(size: 13, weight: .semibold)).foregroundColor(Color(hex: 0xC3CCD4))
                        }
                        .padding(.vertical, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
        }
    }
}
