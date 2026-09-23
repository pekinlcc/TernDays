import SwiftUI
import UIKit
import UserNotifications

/// 设置页路由:从「另有 N 天可补记」进来带着首页正在看的年份并直接打开补记;齿轮进来不带(默认今年)
struct SettingsRoute: Hashable {
    var year: Int?
    var openBackfill: Bool = false
}

struct HomeView: View {
    // 只记「用户主动切到的往年」;没切过就跟着当前年走——跨过元旦回到前台自动换到新年
    @State private var pinnedYear: Int?
    @State private var currentYear = LocalDate.today().year
    private var year: Int { pinnedYear ?? currentYear }
    @State private var data: YearData?
    @State private var extras: HomeExtras?
    @State private var correcting: CorrectTarget?
    /// 通知权限被用户关掉了(没问过不算:引导和每日提醒会去问)
    @State private var notifDenied = false
    @State private var confirmDeleteFuture = false
    @ObservedObject private var punch = PunchManager.shared

    private var today: LocalDate { LocalDate.today() }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                statusCard
                futureCard
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
                regionSection
                staysSection
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
                NavigationLink(value: "export") {
                    Image(systemName: "square.and.arrow.up").accessibilityLabel("导出")
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(value: SettingsRoute(year: nil)) {
                    Image(systemName: "slider.horizontal.3").accessibilityLabel("设置")
                }
            }
        }
        .navigationDestination(for: String.self) { route in
            if route == "export" { ExportView(initialYear: year) }
        }
        .navigationDestination(for: SettingsRoute.self) { route in
            SettingsView(initialYear: route.year, openBackfill: route.openBackfill)
        }
        .navigationDestination(for: CityRoute.self) { route in
            CityDetailView(cityKey: route.cityKey, year: route.year)
        }
        .task(id: year) { reload() }
        .onReceive(NotificationCenter.default.publisher(for: .terndaysDataChanged)) { _ in
            // 回到前台 / 跨天也会发这个通知:顺带确认「今年」是不是已经换了
            let now = LocalDate.today().year
            if now != currentYear { currentYear = now } else { reload() }
        }
        .sheet(item: $correcting) { target in
            CityCorrectSheet(context: target.context) { correcting = nil }
        }
        .alert("删除这些记录？", isPresented: $confirmDeleteFuture) {
            Button("删除", role: .destructive) { deleteFuture() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("将删除 \(extras?.future.count ?? 0) 条时间晚于现在的打卡记录，删除后不能恢复。")
        }
    }

    private func reload() {
        let y = year
        DispatchQueue.global(qos: .userInitiated).async {
            let d = YearData.load(year: y)
            let ex = HomeExtras.load(year: y, stats: d.stats)
            DispatchQueue.main.async {
                if year == y {
                    data = d
                    extras = ex
                }
            }
        }
        UNUserNotificationCenter.current().getNotificationSettings { s in
            let denied = s.authorizationStatus == .denied
            DispatchQueue.main.async { notifDenied = denied }
        }
    }

    private func deleteFuture() {
        guard let future = extras?.future, !future.isEmpty else { return }
        let n = DataStore.shared.deletePunches(future)
        Corrections.afterWrite()
        ToastCenter.shared.show("已删除 \(n) 条记录")
    }

    // MARK: 状态卡(与 Android 同一优先级:暂停 > 定位没就绪 > 近几天漏记诊断 > 次级项:通知 / 后台 App 刷新)

    /// 打卡保障按优先级只报第一项:定位「始终允许」→ 通知 → 后台 App 刷新
    private enum SetupIssue {
        case location(String)
        case notifications
        case backgroundRefresh

        /// 定位没就绪是关键项:自动打卡根本跑不起来,也是近几天漏记最常见的原因,
        /// 所以排在漏记诊断之前、用暖色;通知、后台刷新是次级项,用弱一级的样式
        var critical: Bool {
            if case .location = self { return true }
            return false
        }
    }

    private var setupIssue: SetupIssue? {
        if let issue = punch.locationIssue { return .location(issue) }
        // 通知权限不只管每日提醒:天数提醒、打卡失败提示也靠它,关了就提示(不看每日提醒开没开)
        if notifDenied { return .notifications }
        if UIApplication.shared.backgroundRefreshStatus != .available { return .backgroundRefresh }
        return nil
    }

    @ViewBuilder
    private var statusCard: some View {
        if punch.paused {
            NoticeCard(icon: "pause.circle", title: "自动打卡已暂停", detail: "不会再自动记录所在城市，可随时恢复") {
                Button {
                    punch.setPaused(false)
                } label: {
                    NoticeActionLabel(text: "恢复")
                }
                .buttonStyle(.plain)
            }
        } else if let issue = setupIssue, issue.critical {
            // 「始终允许」被收回时先给一键修复,而不是笼统的漏记卡
            setupIssueCard(issue)
        } else if let gap = extras?.recentGap, gap > 0 {
            NoticeCard(icon: "exclamationmark.triangle", title: "最近 \(gap) 天没有自动记录,可能被系统限制了后台", detail: nil) {
                NavigationLink(value: SettingsRoute(year: nil)) {
                    NoticeActionLabel(text: "检查打卡保障")
                }
                .buttonStyle(.plain)
                NavigationLink(value: SettingsRoute(year: today.minusDays(1).year, openBackfill: true)) {
                    NoticeActionLabel(text: "去补记")
                }
                .buttonStyle(.plain)
            }
        } else if let issue = setupIssue {
            setupIssueCard(issue)
        }
    }

    @ViewBuilder
    private func setupIssueCard(_ issue: SetupIssue) -> some View {
        switch issue {
        case .location(let text):
            // 就地修:没问过就直接弹系统授权,问过了才跳系统设置(不再先绕一圈应用设置页)
            issueButton(title: "自动打卡还没就绪", detail: text, critical: true) { punch.fixLocationPermission() }
        case .notifications:
            issueButton(title: "通知权限没有打开", detail: "打卡提醒、打卡失败提示和天数提醒都发不出来，点击去系统设置打开",
                        critical: false) {
                openSystemSettings()
            }
        case .backgroundRefresh:
            issueButton(title: "后台 App 刷新已关闭", detail: "系统不会在后台唤醒 TernDays 补打，点击去系统设置打开",
                        critical: false) {
                openSystemSettings()
            }
        }
    }

    /// 关键项暖色;次级项弱一级:中性底、正文 / 次要文字色(与 Android IssueCard 一致)
    private func issueButton(title: String, detail: String, critical: Bool,
                             action: @escaping () -> Void) -> some View {
        let bg = critical ? Td.warmSoft : Td.neutralSoft
        let titleColor = critical ? Td.warmDeep : Td.ink
        let fg = critical ? Td.warmDeep : Td.muted
        return Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 15)).foregroundColor(fg)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 13, weight: .semibold)).foregroundColor(titleColor)
                    Text(detail)
                        .font(.system(size: 11)).foregroundColor(fg)
                        .multilineTextAlignment(.leading)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.system(size: 12)).foregroundColor(fg)
            }
            .padding(.horizontal, 14).padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 14).fill(bg))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func openSystemSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    /// 系统时间曾被拨到未来留下的记录:它们会一直是「最近一条」,提示用户确认后清理
    @ViewBuilder
    private var futureCard: some View {
        if let future = extras?.future, !future.isEmpty {
            NoticeCard(icon: "clock.badge.exclamationmark",
                       title: "有 \(future.count) 条记录的时间晚于现在,系统时间可能被调过",
                       detail: nil) {
                Button {
                    confirmDeleteFuture = true
                } label: {
                    NoticeActionLabel(text: "删除这些记录")
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: 年度汇总

    /// 「3月15日起 – 9月23日」:开始记录日落在这一年里(且不是 1 月 1 日)时从那天算起
    private func rangeText(_ s: YearStats) -> String {
        let start: String
        if let since = s.trackingSince, since > s.firstDate, since <= s.lastDate {
            start = "\(since.month)月\(since.day)日起"
        } else {
            start = "\(s.firstDate.month)月\(s.firstDate.day)日"
        }
        return "\(start) – \(s.lastDate.month)月\(s.lastDate.day)日"
    }

    private var summaryCard: some View {
        TdCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Menu {
                        ForEach(data?.years ?? [year], id: \.self) { y in
                            Button("\(String(y)) 年") { pinnedYear = (y == LocalDate.today().year) ? nil : y }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text("\(String(year)) 年")
                                .font(.system(size: 15, weight: .semibold)).foregroundColor(Td.ink)
                            Image(systemName: "chevron.down")
                                .font(.system(size: 11, weight: .semibold)).foregroundColor(Td.faint)
                        }
                        .tapTarget()
                    }
                    Spacer()
                    if let s = data?.stats {
                        Text(rangeText(s))
                            .font(.system(size: 12)).foregroundColor(Td.muted)
                    }
                }
                HStack(alignment: .bottom, spacing: 26) {
                    bigStat(value: data.map { DayCounting.formatDays($0.stats.recordedDays) } ?? "–", label: "天已记录")
                    bigStat(value: data.map { String($0.stats.cities.count) } ?? "–", label: "个城市")
                    Spacer()
                    if let missing = data?.stats.unrecordedDates.count, missing > 0 {
                        // 可点:带着当前年份跳设置,并直接打开补记
                        NavigationLink(value: SettingsRoute(year: year, openBackfill: true)) {
                            Text("另有 \(missing) 天可补记")
                                .font(.system(size: 11, weight: .medium)).foregroundColor(Td.accentDeep)
                                .tapTarget()
                        }
                        .buttonStyle(.plain)
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

    // MARK: 今日打卡

    private var todayCard: some View {
        let punches = data?.punches.filter { $0.localDate == today } ?? []
        let morning = punches.first { $0.slot == .morning }
        let evening = punches.first { $0.slot == .evening }
        let extra = punches.first { $0.slot == .extra }
        // 补捕窗口已关的半天不会再自动补上,别再显示「待记录」让人白等
        let pending = PunchRules.pendingSlots(hour: Calendar.current.component(.hour, from: Date()))
        return TdCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Text("今日打卡 · \(today.month)月\(today.day)日 \(today.weekdayCn)")
                        .font(.system(size: 12)).foregroundColor(Td.muted)
                    Spacer()
                    // 定位/城市库偶有边界误判(如深圳被判成香港);还没记录时也能先手动指定
                    Button {
                        correcting = CorrectTarget(date: today)
                    } label: {
                        Text("纠正")
                            .font(.system(size: 12, weight: .semibold)).foregroundColor(Td.accentDeep)
                            .frame(minWidth: 44)
                            .tapTarget()
                    }
                    .buttonStyle(.plain)
                }
                HStack(spacing: 0) {
                    punchCell(icon: "sun.max", tint: Td.sunrise, slotName: "早", target: "07:00",
                              punch: morning, stillPossible: pending.contains(.morning))
                    Rectangle().fill(Td.border).frame(width: 1, height: 40)
                    punchCell(icon: "sunset", tint: Td.faint, slotName: "晚", target: "17:00",
                              punch: evening, stillPossible: pending.contains(.evening))
                        .padding(.leading, 16)
                }
                todayFootnotes(extra: extra, evening: evening)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 13)
        }
    }

    @ViewBuilder
    private func todayFootnotes(extra: Punch?, evening: Punch?) -> some View {
        if let extra {
            Text("首点 \(extra.clock) · \(extra.cityName) ✓（已记录当前位置）")
                .font(.system(size: 11)).foregroundColor(Td.faint)
        }
        // 今天还没打完:单个样本先算 0.5 天,说明清楚免得以为少算了
        if let attr = data?.stats.days[today], attr.provisional, !attr.shares.isEmpty {
            Text(evening == nil ? "今天先算半天 · 晚点打上后补满一天" : "今天先算半天 · 早点补上后补满一天")
                .font(.system(size: 11)).foregroundColor(Td.faint)
        }
        // 自动打卡到底有没有在跑:最近一次尝试的结论 + 下一次时间
        HStack(alignment: .top, spacing: 8) {
            if let attempt = punch.lastAttempt {
                Text(attempt.summary())
                    .font(.system(size: 11)).foregroundColor(Td.faint)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            if !punch.paused {
                Text(TimeFmt.nextPunchText())
                    .font(.system(size: 11)).foregroundColor(Td.faint)
            }
        }
    }

    /// 打上了显示实际时刻(打卡当时的时区),并标出「延迟」「缓存位置」
    private func punchCell(icon: String, tint: Color, slotName: String, target: String, punch: Punch?,
                           stillPossible: Bool) -> some View {
        let time = punch?.clock ?? target
        return HStack(spacing: 10) {
            Image(systemName: icon).font(.system(size: 17)).foregroundColor(tint)
            VStack(alignment: .leading, spacing: 2) {
                Text("\(slotName) · \(time)").font(.system(size: 11)).foregroundColor(Td.faint)
                if let p = punch {
                    HStack(spacing: 5) {
                        Text(p.cityName).font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .bold)).foregroundColor(Td.accent)
                    }
                    if p.delayed || p.fromCache {
                        Text(Self.markers(p))
                            .font(.system(size: 10, weight: .medium)).foregroundColor(Td.warmDeep)
                            .padding(.horizontal, 6).padding(.vertical, 1)
                            .background(Capsule().fill(Td.warmSoft))
                    }
                } else {
                    Text(stillPossible ? "待记录" : "未记录")
                        .font(.system(size: 14)).foregroundColor(Td.faint)
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity)
    }

    private static func markers(_ p: Punch) -> String {
        var parts: [String] = []
        if p.delayed { parts.append("延迟") }
        if p.fromCache { parts.append("缓存位置") }
        return parts.joined(separator: " · ")
    }

    // MARK: 城市列表 / 国家地区 / 行程

    private var cityList: some View {
        TdCard {
            VStack(spacing: 0) {
                let cities = data?.stats.cities ?? []
                if data != nil && cities.isEmpty {
                    VStack(spacing: 6) {
                        // 用了一年的人元旦凌晨看到「还没有打卡记录」会以为数据丢了:有往年记录时说清楚
                        let hasEarlier = data?.stats.trackingSince.map { $0.year < year } ?? false
                        Text(hasEarlier ? "\(String(year)) 年还没有记录" : "还没有打卡记录")
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
                                .font(.system(size: 13, weight: .semibold)).foregroundColor(Td.chevron)
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

    /// 去过 2 个以上国家/地区,或配置了天数上限时才显示
    @ViewBuilder
    private var regionSection: some View {
        if let ex = extras, ex.regions.count >= 2 || ex.hasThresholds, !ex.regions.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                sectionTitle("按国家/地区", trailing: "中国大陆、港、澳、台分开统计")
                RegionCard(rows: ex.regions)
            }
        }
    }

    @ViewBuilder
    private var staysSection: some View {
        if let ex = extras, !ex.stays.isEmpty || (year == today.year && ex.currentStay != nil) {
            VStack(alignment: .leading, spacing: 8) {
                sectionTitle("行程", trailing: "同城连续的日子合成一段")
                StaysCard(current: year == today.year ? ex.currentStay : nil, stays: ex.stays)
            }
        }
    }

    private func sectionTitle(_ title: String, trailing: String) -> some View {
        HStack {
            Text(title).font(.system(size: 13, weight: .medium)).foregroundColor(Td.muted)
            Spacer()
            Text(trailing).font(.system(size: 11)).foregroundColor(Td.faint)
        }
        .padding(.horizontal, 2)
        .padding(.top, 4)
    }
}
