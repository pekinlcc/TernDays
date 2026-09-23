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
    @State private var remindersOn = AppPrefs.remindersOn
    @State private var remindersSilent = AppPrefs.remindersSilent

    /// 年份可切换:此前写死当前年,往年的无记录日在应用里根本补不了。
    /// 从首页「另有 N 天可补记」进来时带着首页正在看的年份
    @State private var year: Int
    /// 从首页「另有 N 天可补记」/「去补记」进来:页面出现后直接打开补记
    private let openBackfillOnAppear: Bool
    @State private var autoOpened = false

    init(initialYear: Int? = nil, openBackfill: Bool = false) {
        _year = State(initialValue: initialYear ?? LocalDate.today().year)
        openBackfillOnAppear = openBackfill
    }

    /// 小组件外观(与扩展共享 App Group 偏好)
    @State private var widgetStyle: WidgetStyle = WidgetStyle.current

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

    /// 补记页默认选中的日子:这一年最近的一个无记录日;没有就是今天(往年则是 12 月 31 日)
    private var backfillInitialDate: LocalDate {
        if let d = data?.stats.unrecordedDates.max() { return d }
        let today = LocalDate.today()
        let yearEnd = LocalDate(year: year, month: 12, day: 31)
        return yearEnd < today ? yearEnd : today
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                guardSection
                reminderSection
                widgetSection
                ThresholdSettingsSection()
                correctionSection
                migrateSection
                DataSettingsSection()
                aboutSection
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
        }
        .background(Td.bg)
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            reload()
            // 从首页「可补记」进来:等页面推入动画结束再弹,否则会与导航动画冲突而弹不出来
            if openBackfillOnAppear && !autoOpened {
                autoOpened = true
                try? await Task.sleep(nanoseconds: 450_000_000)
                backfillOpen = true
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .terndaysDataChanged)) { _ in reload() }
        .sheet(isPresented: $scanOpen) {
            MigrateScanView { code in
                scanOpen = false
                startImport(code)
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
            BackfillSheet(initialDate: backfillInitialDate, unrecorded: data?.stats.unrecordedDates ?? [])
        }
    }

    private func reload() {
        var d = YearData.load(year: year)
        // 看的那一年已经没有数据(清除全部数据、或往年唯一的补记被恢复自动):年份条只剩一项会整条隐藏,
        // 页面就回不到今年了。退回今年(yearsWithData 总含今年,多读一次即可)
        if !d.years.contains(year) {
            year = LocalDate.today().year
            d = YearData.load(year: year)
        }
        data = d
        UNUserNotificationCenter.current().getNotificationSettings { s in
            let ok = s.authorizationStatus == .authorized || s.authorizationStatus == .provisional
            DispatchQueue.main.async { notifGranted = ok }
        }
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    // MARK: 打卡保障

    private var guardSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsHeader(title: "打卡保障 · 每一项都会影响自动打卡的成功率")
            TdCard {
                VStack(spacing: 0) {
                    permRow(
                        "定位权限「始终允许」",
                        "允许系统在移动时唤醒应用记录城市",
                        ok: punch.authStatus == .authorizedAlways
                    ) { punch.fixLocationPermission() }
                    Divider().overlay(Td.divider)
                    permRow(
                        "精确位置",
                        "关掉后系统只给「大致位置」,可能偏出好几公里、判错城市",
                        ok: !punch.accuracyReduced
                    ) { openSystemSettings() }
                    Divider().overlay(Td.divider)
                    permRow("通知权限", "07:00 / 17:00 提醒打卡、打卡失败与天数提醒", ok: notifGranted) {
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
        }
    }

    // MARK: 每日提醒

    private var reminderSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsHeader(title: "每日提醒")
            TdCard {
                VStack(alignment: .leading, spacing: 0) {
                    Toggle(isOn: $remindersOn) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("07:00 / 17:00 提醒")
                                .font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                            Text("在通知上长按选「就记在这里」,不用打开应用就能打卡")
                                .font(.system(size: 12)).foregroundColor(Td.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .tint(Td.accent)
                    .padding(.vertical, 12)
                    .onChange(of: remindersOn) { on in
                        AppPrefs.remindersOn = on
                        PunchManager.shared.scheduleDailyReminders()
                    }
                    if remindersOn {
                        Divider().overlay(Td.divider)
                        HStack {
                            Text("提醒方式").font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                            Spacer()
                            Picker("提醒方式", selection: $remindersSilent) {
                                Text("有声").tag(false)
                                Text("静默").tag(true)
                            }
                            .pickerStyle(.segmented)
                            .frame(width: 140)
                        }
                        .padding(.top, 12)
                        .onChange(of: remindersSilent) { silent in
                            AppPrefs.remindersSilent = silent
                            PunchManager.shared.scheduleDailyReminders()
                        }
                        Text(remindersSilent ? "静默:不响铃、不亮屏,只出现在通知中心" : "有声:到点响铃提醒")
                            .font(.system(size: 12)).foregroundColor(Td.muted)
                            .padding(.top, 6).padding(.bottom, 12)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    // MARK: 桌面小组件

    private var widgetSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsHeader(title: "桌面小组件")
            TdCard {
                VStack(alignment: .leading, spacing: 10) {
                    Text("外观").font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                    WidgetStylePicker(selection: $widgetStyle)
                    Text(widgetStyle.hint)
                        .font(.system(size: 12)).foregroundColor(Td.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(16)
            }
        }
    }

    // MARK: 手动补记与更正

    private var correctionSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsHeader(title: "手动补记与更正")
            TdCard {
                VStack(spacing: 0) {
                    Button { backfillOpen = true } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("补记")
                                    .font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                                Text("\(String(year)) 年还有 \(data?.stats.unrecordedDates.count ?? 0) 天没有任何记录;也可以按区间补一整段")
                                    .font(.system(size: 12)).foregroundColor(Td.muted)
                                    .multilineTextAlignment(.leading)
                            }
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.system(size: 13)).foregroundColor(Td.chevron)
                        }
                        .padding(.vertical, 14)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    yearChips
                    Divider().overlay(Td.divider)
                    Text("记录的城市不对？在首页「今日打卡」点「纠正」，或到城市详情里点那一天即可更正")
                        .font(.system(size: 12)).foregroundColor(Td.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 12)
                    ForEach(sortedOverrides, id: \.rowId) { o in
                        Divider().overlay(Td.divider)
                        overrideRow(o)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }

    /// 往年也能补记:切到那一年即可
    @ViewBuilder
    private var yearChips: some View {
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
                                .padding(.horizontal, 12)
                                .frame(minHeight: 32)
                                .background(Capsule().fill(y == year ? Td.accent : Td.neutralSoft))
                                .frame(minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(y == year ? AccessibilityTraits.isSelected : AccessibilityTraits())
                    }
                }
            }
            .padding(.bottom, 4)
        }
    }

    private func overrideRow(_ o: DayOverride) -> some View {
        HStack {
            Text("\(o.localDate.month)月\(o.localDate.day)日 · \(scopeLabel(o.scope)) → \(o.cityName)")
                .font(.system(size: 13)).foregroundColor(Td.ink)
            Spacer()
            Button {
                Corrections.restoreAuto(date: o.localDate, scope: o.scope, toast: "已恢复自动判定")
            } label: {
                Text("恢复自动")
                    .font(.system(size: 12)).foregroundColor(Td.warmDeep)
                    .frame(minWidth: 44)
                    .tapTarget()
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 2)
    }

    // MARK: 换手机

    private var migrateSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsHeader(title: "换手机")
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
        }
    }

    private func startImport(_ code: String) {
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
                // 先报导入条数;按本机城市库重放在后台跑,改了城市判定再用提示补一句
                importResult = ImportFinisher.report(outcome.result, source: "旧手机")
                reload()
            },
            onError: { msg in
                importWorking = nil
                importResultTitle = "导入没有成功"
                // 旧手机那边的二维码可能已失效(离开过迁移页、换了网络):统一给出下一步
                importResult = msg + "\n\n请在旧手机上重新打开迁移页后再扫"
            }
        )
    }

    // MARK: 关于

    private var aboutSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsHeader(title: "关于")
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
    }

    // MARK: 行样式

    private func permRow(_ title: String, _ sub: String, ok: Bool?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                    Text(sub).font(.system(size: 12)).foregroundColor(Td.muted)
                        .multilineTextAlignment(.leading)
                }
                Spacer()
                switch ok {
                case true: TagView(text: "已开启", bg: Td.accentSoft, fg: Td.accentDeep)
                case false: TagView(text: "未开启", bg: Td.warmSoft, fg: Td.warmDeep)
                default: TagView(text: "检查中", bg: Td.neutralSoft, fg: Td.muted)
                }
            }
            .padding(.vertical, 12)
            .tapTarget()
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
        .tapTarget()
    }

    private func aboutLine(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label).font(.system(size: 12)).foregroundColor(Td.faint).frame(width: 64, alignment: .leading)
            Text(value).font(.system(size: 12)).foregroundColor(Td.muted)
        }
    }
}
