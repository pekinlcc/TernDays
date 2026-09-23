import SwiftUI
import UniformTypeIdentifiers
import WidgetKit

/// 加密备份文件(导出时交给系统「存储到文件」)
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        guard let d = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        data = d
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

/// 输入口令:备份时输两遍(≥ 8 位且一致),恢复时输一遍。
/// 提交后显示「正在加密…/正在恢复…」,由调用方在后台处理,完成后回调 done(nil 成功 / 错误文案)。
struct PassphraseSheet: View {
    enum Kind {
        case create
        case unlock
    }

    let kind: Kind
    let onSubmit: (_ passphrase: String, _ done: @escaping (String?) -> Void) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var pass = ""
    @State private var pass2 = ""
    @State private var working = false
    @State private var error: String?

    private var valid: Bool {
        switch kind {
        case .create: return Backup.isPassphraseLongEnough(pass) && pass == pass2
        case .unlock: return !pass.isEmpty
        }
    }

    private var hint: String? {
        guard kind == .create else { return nil }
        if !pass.isEmpty && !Backup.isPassphraseLongEnough(pass) { return "口令至少 \(Backup.minPassphrase) 位" }
        if !pass2.isEmpty && pass != pass2 { return "两次输入的口令不一致" }
        return nil
    }

    private var placeholder: String { kind == .create ? "设置口令(至少 8 位)" : "备份时设置的口令" }

    private var footer: String {
        kind == .create
            ? "备份文件用这个口令加密,恢复时需要它。口令不会保存在任何地方,忘了就无法恢复。"
            : "输入备份时设置的口令。本机已有的记录保持不变,只补上备份里多出来的。"
    }

    private var title: String { kind == .create ? "加密备份" : "从备份恢复" }
    private var actionText: String { kind == .create ? "加密并保存" : "解密并恢复" }
    private var workingText: String { kind == .create ? "正在加密…" : "正在恢复…" }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField(placeholder, text: $pass)
                    if kind == .create {
                        SecureField("再输入一次", text: $pass2)
                    }
                } footer: {
                    Text(footer)
                }
                if let msg = error ?? hint {
                    Section {
                        Text(msg).font(.system(size: 13)).foregroundColor(Td.warmDeep)
                    }
                }
                Section {
                    Button {
                        submit()
                    } label: {
                        HStack {
                            Spacer()
                            if working {
                                ProgressView()
                                Text(workingText).padding(.leading, 6)
                            } else {
                                Text(actionText).fontWeight(.semibold)
                            }
                            Spacer()
                        }
                        .tapTarget()
                    }
                    .disabled(!valid || working)
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }.disabled(working)
                }
            }
        }
        .interactiveDismissDisabled(working)
    }

    private func submit() {
        guard valid, !working else { return }
        working = true
        error = nil
        onSubmit(pass) { err in
            working = false
            if let err {
                error = err
            } else {
                dismiss()
            }
        }
    }
}

/// 设置页「数据」分组:暂停自动打卡、加密备份到文件、从备份恢复、清除本机全部数据。
/// 自带状态与弹窗(文件导出 / 导入各挂在不同的子视图上:同一视图上同时挂两个会互相吞掉)。
struct DataSettingsSection: View {
    @ObservedObject private var punch = PunchManager.shared
    @State private var lastBackup: Date? = AppPrefs.lastBackupAt
    @State private var passSheet: PassSheet?
    @State private var exportDoc: BackupDocument?
    @State private var exporterShown = false
    @State private var importerShown = false
    @State private var dataAlert: DataAlert?

    private enum PassSheet: Identifiable {
        case create
        case unlock(Data)

        var id: String {
            switch self {
            case .create: return "create"
            case .unlock: return "unlock"
            }
        }
    }

    private enum DataAlert: Identifiable {
        case clearFirst
        case clearSecond(Int)
        case result(title: String, message: String)

        var id: String {
            switch self {
            case .clearFirst: return "clear1"
            case .clearSecond: return "clear2"
            case .result(let t, let m): return "result|\(t)|\(m)"
            }
        }

        var title: String {
            switch self {
            case .clearFirst: return "清除本机全部数据?"
            case .clearSecond(let n): return "确定清除全部 \(n) 条记录?"
            case .result(let t, _): return t
            }
        }

        var message: String {
            switch self {
            case .clearFirst:
                return "清除后无法恢复。建议先「加密备份到文件」,需要时可以再恢复回来。"
            case .clearSecond:
                return "打卡记录与手动补记都会从本机删除,设置保留。"
            case .result(_, let m):
                return m
            }
        }
    }

    private var backupFileName: String {
        "TernDays-备份-\(TimeFmt.isoDay(Date())).\(Backup.fileExtension)"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SettingsHeader(title: "数据")
            TdCard {
                VStack(spacing: 0) {
                    pauseRow
                    Divider().overlay(Td.divider)
                    backupRow
                    Divider().overlay(Td.divider)
                    restoreRow
                    Divider().overlay(Td.divider)
                    clearRow
                }
                .padding(.horizontal, 16)
            }
        }
        .sheet(item: $passSheet) { sheet in
            switch sheet {
            case .create:
                PassphraseSheet(kind: .create) { pass, done in seal(pass, done: done) }
            case .unlock(let file):
                PassphraseSheet(kind: .unlock) { pass, done in restore(file, pass: pass, done: done) }
            }
        }
        .alert(
            dataAlert?.title ?? "",
            isPresented: Binding(get: { dataAlert != nil }, set: { if !$0 { dataAlert = nil } }),
            presenting: dataAlert
        ) { a in
            switch a {
            case .clearFirst:
                Button("先去备份") { later { passSheet = .create } }
                Button("继续清除", role: .destructive) {
                    let n = DataStore.shared.recordCount()
                    later { dataAlert = .clearSecond(n) }
                }
                Button("取消", role: .cancel) {}
            case .clearSecond:
                Button("清除", role: .destructive) { clearAll() }
                Button("取消", role: .cancel) {}
            case .result:
                Button("好", role: .cancel) {}
            }
        } message: { a in
            Text(a.message)
        }
    }

    // MARK: 行

    private var pauseRow: some View {
        Toggle(isOn: Binding(get: { punch.paused }, set: { punch.setPaused($0) })) {
            VStack(alignment: .leading, spacing: 2) {
                Text("暂停自动打卡").font(.system(size: 14, weight: .semibold)).foregroundColor(Td.ink)
                Text("换了新手机、这台留作备用时打开:不再定位、不再提醒")
                    .font(.system(size: 12)).foregroundColor(Td.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .tint(Td.accent)
        .padding(.vertical, 12)
    }

    private var backupRow: some View {
        Button {
            passSheet = .create
        } label: {
            rowLabel(
                "加密备份到文件",
                lastBackup.map { "上次备份 " + TimeFmt.stamp($0) } ?? "用口令加密后存到「文件」或网盘,换机、重装都能恢复"
            )
        }
        .buttonStyle(.plain)
        .fileExporter(
            isPresented: $exporterShown,
            document: exportDoc,
            contentType: .data,
            defaultFilename: backupFileName
        ) { result in
            exportDoc = nil
            switch result {
            case .success:
                let now = Date()
                AppPrefs.lastBackupAt = now
                lastBackup = now
                ToastCenter.shared.show("已保存加密备份")
            case .failure(let e):
                let msg = e.localizedDescription
                later { dataAlert = .result(title: "备份没有成功", message: msg) }
            }
        }
    }

    private var restoreRow: some View {
        Button {
            importerShown = true
        } label: {
            rowLabel("从备份恢复", "选择 .terndays 备份文件;本机已有的记录保持不变")
        }
        .buttonStyle(.plain)
        .fileImporter(isPresented: $importerShown, allowedContentTypes: [.data, .item]) { result in
            switch result {
            case .success(let url):
                openPicked(url)
            case .failure(let e):
                let msg = e.localizedDescription
                later { dataAlert = .result(title: "恢复没有成功", message: msg) }
            }
        }
    }

    private var clearRow: some View {
        Button {
            dataAlert = .clearFirst
        } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("清除本机全部数据").font(.system(size: 14, weight: .semibold)).foregroundColor(Td.warmDeep)
                    Text("删除本机所有打卡与手动记录,不可恢复")
                        .font(.system(size: 12)).foregroundColor(Td.muted)
                }
                Spacer()
            }
            .padding(.vertical, 12)
            .tapTarget()
        }
        .buttonStyle(.plain)
    }

    private func rowLabel(_ title: String, _ sub: String) -> some View {
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

    // MARK: 动作

    /// 弹窗 / 文件面板正在收起时立刻弹下一个会被系统吞掉:稍等一下再弹
    private func later(_ action: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45, execute: action)
    }

    /// 后台加密 → 回到主线程交给文件导出面板
    private func seal(_ pass: String, done: @escaping (String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            // 数据被锁着(读不出来)时导出的是空库:不能生成一份「空备份」让人以为备份好了
            guard DataStore.shared.reloadIfSealed() else {
                DispatchQueue.main.async { done("数据暂时读不到:请先解锁手机,再重新备份。") }
                return
            }
            do {
                let json = try MigrationCodec.toJson(
                    datasetVersion: Cities.datasetVersion,
                    exportedAtMs: Int64(Date().timeIntervalSince1970 * 1000),
                    punches: DataStore.shared.allPunches(),
                    overrides: DataStore.shared.allOverrides()
                )
                let blob = try Backup.seal(passphrase: pass, plain: json)
                DispatchQueue.main.async {
                    exportDoc = BackupDocument(data: blob)
                    done(nil)
                    later { exporterShown = true }
                }
            } catch {
                let msg = error.localizedDescription
                DispatchQueue.main.async { done(msg) }
            }
        }
    }

    /// 选中的文件:先确认是 TernDays 备份,再问口令(安全作用域访问只在读文件的这一刻打开)
    private func openPicked(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else {
            later { dataAlert = .result(title: "恢复没有成功", message: "读不到这个文件,请确认它已下载到本机。") }
            return
        }
        guard Backup.isBackup(data) else {
            later { dataAlert = .result(title: "恢复没有成功", message: "这不是 TernDays 的备份文件") }
            return
        }
        later { passSheet = .unlock(data) }
    }

    /// 后台解密 → 解析 → 合并(本机已有的优先) → 先报结果,再后台按本机城市库重放
    private func restore(_ file: Data, pass: String, done: @escaping (String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let plain = try Backup.open(passphrase: pass, file: file)
                let payload = try MigrationCodec.parse(plain)
                let result: DataStore.MergeResult
                do {
                    result = try DataStore.shared.mergeImported(punches: payload.punches, overrides: payload.overrides)
                } catch {
                    DispatchQueue.main.async { done("数据没能保存到本机。请清理存储空间、保持手机解锁后重试。") }
                    return
                }
                DispatchQueue.main.async {
                    done(nil)
                    ImportFinisher.afterMerge(result)
                    let text = ImportFinisher.report(result, source: "备份")
                    later { dataAlert = .result(title: "恢复完成 ✓", message: text) }
                }
            } catch {
                let msg = error.localizedDescription
                DispatchQueue.main.async { done(msg) }
            }
        }
    }

    private func clearAll() {
        let ok = DataStore.shared.clearAll()
        punch.clearLastAttempt()
        ThresholdStore.clearNotified()
        WidgetCenter.shared.reloadAllTimelines()
        NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
        ToastCenter.shared.show(ok ? "已清除本机全部记录" : "有数据没能清除,请解锁手机后重试")
    }
}
