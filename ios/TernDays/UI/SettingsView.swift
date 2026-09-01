import CoreLocation
import SwiftUI
import UIKit
import UserNotifications
import WidgetKit

struct SettingsView: View {
    @ObservedObject private var punch = PunchManager.shared
    @State private var notifGranted: Bool?
    @State private var data: YearData?
    @State private var backfillOpen = false

    private var year: Int { LocalDate.today().year }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("打卡保障 · 每一项都会影响自动打卡的成功率")
                    .font(.system(size: 13, weight: .medium)).foregroundColor(Td.muted)
                    .padding(.leading, 2)
                TdCard {
                    VStack(spacing: 0) {
                        permRow(
                            "定位权限「始终允许」",
                            "允许系统在移动时唤醒应用记录城市",
                            ok: punch.authStatus == .authorizedAlways
                        ) { openSystemSettings() }
                        Divider().overlay(Td.divider)
                        permRow("通知权限", "07:00 / 17:00 提醒打卡", ok: notifGranted) {
                            openSystemSettings()
                        }
                        Divider().overlay(Td.divider)
                        permRow(
                            "后台 App 刷新",
                            "在系统设置中为 TernDays 打开「后台 App 刷新」",
                            ok: UIApplication.shared.backgroundRefreshStatus == .available
                        ) { openSystemSettings() }
                    }
                    .padding(.horizontal, 16)
                }

                Text("手动补记与更正").font(.system(size: 13, weight: .medium)).foregroundColor(Td.muted)
                    .padding(.leading, 2).padding(.top, 4)
                TdCard {
                    VStack(spacing: 0) {
                        Button { backfillOpen = true } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("补记无记录的日子")
                                        .font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                                    Text("今年还有 \(data?.stats.unrecordedDates.count ?? 0) 天没有任何记录")
                                        .font(.system(size: 12)).foregroundColor(Td.muted)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 13)).foregroundColor(Color(hex: 0xC3CCD4))
                            }
                            .padding(.vertical, 14)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        Divider().overlay(Td.divider)
                        Text("记录的城市不对？在首页「今日打卡」点「纠正」，或到城市详情里点那一天即可更正")
                            .font(.system(size: 12)).foregroundColor(Td.muted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 12)
                        ForEach(data?.overrides.sorted(by: { $0.localDate > $1.localDate }) ?? [], id: \.localDate) { o in
                            Divider().overlay(Td.divider)
                            HStack {
                                Text("\(o.localDate.month)月\(o.localDate.day)日 → \(o.cityName)（手动）")
                                    .font(.system(size: 13)).foregroundColor(Td.ink)
                                Spacer()
                                Button("恢复自动") {
                                    DataStore.shared.removeOverride(date: o.localDate)
                                    WidgetCenter.shared.reloadAllTimelines()
                                    reload()
                                }
                                .font(.system(size: 12)).foregroundColor(Td.warmDeep)
                            }
                            .padding(.vertical, 10)
                        }
                    }
                    .padding(.horizontal, 16)
                }

                Text("关于").font(.system(size: 13, weight: .medium)).foregroundColor(Td.muted)
                    .padding(.leading, 2).padding(.top, 4)
                TdCard {
                    VStack(alignment: .leading, spacing: 8) {
                        aboutLine("版本", "TernDays \(appVersion)")
                        aboutLine("打卡机制", "iOS 无法后台精确定时：位置变化唤醒 + 定时提醒 + 打开补打")
                        aboutLine("数据", "全部保存在本机，无账号、不上传；删除应用即清除")
                        aboutLine("城市库", "GeoNames（CC-BY 4.0）+ 中国行政区划（modood），离线匹配")
                        HStack(alignment: .top) {
                            Text("开源地址").font(.system(size: 12)).foregroundColor(Td.faint)
                                .frame(width: 64, alignment: .leading)
                            Link("github.com/pekinlcc/TernDays",
                                 destination: URL(string: "https://github.com/pekinlcc/TernDays")!)
                                .font(.system(size: 12)).foregroundColor(Td.accentDeep)
                        }
                    }
                    .padding(16)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
        }
        .background(Td.bg)
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
        .task { reload() }
        .sheet(isPresented: $backfillOpen) {
            BackfillSheet(
                unrecorded: data?.stats.unrecordedDates.sorted(by: >) ?? [],
                recentCities: data?.stats.cities.map { ($0.cityKey, $0.cityName) } ?? []
            ) { date, key, name in
                DataStore.shared.setOverride(DayOverride(localDate: date, cityKey: key, cityName: name))
                WidgetCenter.shared.reloadAllTimelines()
                backfillOpen = false
                reload()
            }
        }
    }

    private func reload() {
        data = YearData.load(year: year)
        UNUserNotificationCenter.current().getNotificationSettings { s in
            DispatchQueue.main.async { notifGranted = s.authorizationStatus == .authorized }
        }
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    private func permRow(_ title: String, _ sub: String, ok: Bool?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                    Text(sub).font(.system(size: 12)).foregroundColor(Td.muted)
                }
                Spacer()
                switch ok {
                case true: TagView(text: "已开启", bg: Td.accentSoft, fg: Td.accentDeep)
                case false: TagView(text: "未开启", bg: Td.warmSoft, fg: Td.warmDeep)
                default: TagView(text: "检查中", bg: Color(hex: 0xEDF1F4), fg: Td.muted)
                }
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func aboutLine(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label).font(.system(size: 12)).foregroundColor(Td.faint).frame(width: 64, alignment: .leading)
            Text(value).font(.system(size: 12)).foregroundColor(Td.muted)
        }
    }
}

struct BackfillSheet: View {
    let unrecorded: [LocalDate]
    let recentCities: [(String, String)]
    let onConfirm: (LocalDate, String, String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var pickedDate: LocalDate?
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                if let date = pickedDate {
                    Section("补记 \(date.month)月\(date.day)日 在哪个城市？") {
                        TextField("搜索城市名", text: $query)
                        let options = query.isEmpty
                            ? recentCities
                            : Cities.matcher.search(name: query, limit: 12).map { ($0.key, $0.name) }
                        ForEach(options, id: \.0) { key, name in
                            Button(name) { onConfirm(date, key, name) }
                                .foregroundColor(Td.ink)
                        }
                    }
                } else {
                    Section("选择要补记的日期") {
                        if unrecorded.isEmpty {
                            Text("今年没有缺记录的日子").foregroundColor(Td.muted)
                        }
                        ForEach(unrecorded, id: \.self) { d in
                            Button("\(d.month)月\(d.day)日 · \(d.weekdayCn)") { pickedDate = d }
                                .foregroundColor(Td.ink)
                        }
                    }
                }
            }
            .navigationTitle("手动补记")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                if pickedDate != nil {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button("重选日期") { pickedDate = nil }
                    }
                }
            }
        }
    }
}
