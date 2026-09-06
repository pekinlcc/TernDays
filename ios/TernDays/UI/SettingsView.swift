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
    @State private var scanOpen = false
    @State private var importWorking: String?
    @State private var importResult: String?
    @State private var importResultTitle = ""

    /// 年份可切换:此前写死当前年,往年的无记录日在应用里根本补不了
    @State private var year: Int = LocalDate.today().year

    /// 同一天可能有上/下两条半天更正:排序键与 ForEach 的 id 都要带上范围,
    /// 否则两行撞 ID 只显示一条,文案也完全一样
    private var sortedOverrides: [DayOverride] {
        (data?.overrides ?? []).sorted {
            $0.localDate == $1.localDate ? $0.scope.rawValue < $1.scope.rawValue : $0.localDate > $1.localDate
        }
    }

    private func scopeLabel(_ scope: OverrideScope) -> String {
        switch scope {
        case .full: return "整天"
        case .morning: return "上半天"
        case .evening: return "下半天"
        }
    }

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
                                    Text("\(String(year)) 年还有 \(data?.stats.unrecordedDates.count ?? 0) 天没有任何记录")
                                        .font(.system(size: 12)).foregroundColor(Td.muted)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 13)).foregroundColor(Td.chevron)
                            }
                            .padding(.vertical, 14)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        // 往年也能补记:切到那一年即可
                        if let years = data?.years, years.count > 1 {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(years, id: \.self) { y in
                                        Button {
                                            year = y
                                            reload()
                                        } label: {
                                            Text("\(String(y)) 年")
                                                .font(.system(size: 12, weight: y == year ? .semibold : .regular))
                                                .foregroundColor(y == year ? Td.onAccent : Td.muted)
                                                .padding(.horizontal, 12).padding(.vertical, 6)
                                                .background(y == year ? Td.accent : Td.bg)
                                                .clipShape(Capsule())
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                            .padding(.bottom, 10)
                        }
                        Divider().overlay(Td.divider)
                        Text("记录的城市不对？在首页「今日打卡」点「纠正」，或到城市详情里点那一天即可更正")
                            .font(.system(size: 12)).foregroundColor(Td.muted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.vertical, 12)
                        ForEach(sortedOverrides, id: \.rowId) { o in
                            Divider().overlay(Td.divider)
                            HStack {
                                Text("\(o.localDate.month)月\(o.localDate.day)日 · \(scopeLabel(o.scope)) → \(o.cityName)")
                                    .font(.system(size: 13)).foregroundColor(Td.ink)
                                Spacer()
                                Button("恢复自动") {
                                    DataStore.shared.removeOverride(date: o.localDate, scope: o.scope)
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

                Text("换手机").font(.system(size: 13, weight: .medium)).foregroundColor(Td.muted)
                    .padding(.leading, 2).padding(.top, 4)
                TdCard {
                    VStack(spacing: 0) {
                        NavigationLink {
                            MigrateSendView()
                        } label: {
                            migrateRow("迁移到新手机", "本机是旧手机:展示二维码,让新手机扫码接收全部数据")
                        }
                        .buttonStyle(.plain)
                        Divider().overlay(Td.divider)
                        Button { scanOpen = true } label: {
                            migrateRow("从旧手机导入", "本机是新手机:扫旧手机上的二维码,数据经加密局域网直传")
                        }
                        .buttonStyle(.plain)
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
        .onReceive(NotificationCenter.default.publisher(for: .terndaysDataChanged)) { _ in reload() }
        .sheet(isPresented: $scanOpen) {
            MigrateScanView { code in
                scanOpen = false
                guard let link = MigrationLink.parse(code) else {
                    importResultTitle = "导入没有成功"
                    importResult = "这不是 TernDays 的迁移二维码"
                    return
                }
                importWorking = "正在连接旧手机…"
                MigrateImportClient.run(
                    link: link,
                    onStatus: { msg in importWorking = msg },
                    onDone: { outcome in
                        importWorking = nil
                        importResultTitle = "导入完成 ✓"
                        let r = outcome.result
                        var text = "新增 \(r.punchesAdded) 条打卡、\(r.overridesAdded) 条手动记录"
                        if r.punchesSkipped + r.overridesSkipped > 0 {
                            text += ";本机已有的 \(r.punchesSkipped + r.overridesSkipped) 条保持不变"
                        }
                        if outcome.remapped > 0 {
                            text += "\n已按本机城市库修正 \(outcome.remapped) 条城市判定"
                        }
                        importResult = text
                        reload()
                    },
                    onError: { msg in
                        importWorking = nil
                        importResultTitle = "导入没有成功"
                        importResult = msg
                    }
                )
            }
        }
        .overlay {
            if let msg = importWorking {
                ZStack {
                    Color.black.opacity(0.35).ignoresSafeArea()
                    VStack(spacing: 14) {
                        ProgressView()
                        Text(msg).font(.system(size: 13)).foregroundColor(Td.muted)
                    }
                    .padding(28)
                    .background(RoundedRectangle(cornerRadius: 16).fill(Td.surface))
                }
            }
        }
        .alert(importResultTitle, isPresented: .init(
            get: { importResult != nil },
            set: { if !$0 { importResult = nil } }
        )) {
            Button("好") { importResult = nil }
        } message: {
            Text(importResult ?? "")
        }
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

    private func migrateRow(_ title: String, _ sub: String) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                Text(sub).font(.system(size: 12)).foregroundColor(Td.muted)
                    .multilineTextAlignment(.leading)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.system(size: 13)).foregroundColor(Td.chevron)
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())
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
    @State private var hits: [CityMatcher.SearchHit] = []

    var body: some View {
        NavigationStack {
            List {
                if let date = pickedDate {
                    Section("补记 \(date.month)月\(date.day)日 在哪个城市？") {
                        TextField("搜索城市名（支持拼音）", text: $query)
                        if query.isEmpty {
                            ForEach(recentCities, id: \.0) { key, name in
                                Button(name) { onConfirm(date, key, name) }
                                    .foregroundColor(Td.ink)
                            }
                        } else {
                            ForEach(hits, id: \.cityKey) { hit in
                                Button {
                                    onConfirm(date, hit.cityKey, hit.cityName)
                                } label: {
                                    HStack {
                                        Text(hit.cityName).foregroundColor(Td.ink)
                                        if !hit.region.isEmpty {
                                            Text(hit.region).font(.system(size: 12)).foregroundColor(Td.faint)
                                        }
                                    }
                                }
                            }
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
            .task(id: query) {
                let q = query
                guard !q.isEmpty else { hits = []; return }
                let found = await Task.detached(priority: .userInitiated) {
                    Cities.matcher.searchHits(q, limit: 12)
                }.value
                if q == query { hits = found }
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
