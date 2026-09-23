import Foundation
import WidgetKit

/// 导入(扫码迁移 / 从备份恢复)合并完成之后的收尾,两条入口同一口径:
/// 先把结果告诉用户,再在后台按本机城市库重放(3.4 万点最近邻 × 几千条,不该卡住结果页),
/// 重放改动了城市判定再用 toast 补一句。
enum ImportFinisher {

    static func afterMerge(_ result: DataStore.MergeResult) {
        // 只补进了手动更正的那次导入同样会改变天数:界面与小组件都要刷新
        if result.added > 0 {
            WidgetCenter.shared.reloadAllTimelines()
            DispatchQueue.main.async {
                NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
            }
        }
        // 导入的记录可能来自不同版本的城市库:按时间重放交叉验证重解析(幂等)
        guard result.punchesAdded > 0 else { return }
        DispatchQueue.global(qos: .utility).async {
            let changed = HistoryReplay.replayAll(store: DataStore.shared, matcher: Cities.matcher)
            guard changed > 0 else { return }
            WidgetCenter.shared.reloadAllTimelines()
            DispatchQueue.main.async {
                NotificationCenter.default.post(name: .terndaysDataChanged, object: nil)
                ToastCenter.shared.show("已按本机城市库修正 \(changed) 条城市判定")
            }
        }
    }

    /// 结果页文案。source:「旧手机」/「备份」
    static func report(_ r: DataStore.MergeResult, source: String) -> String {
        var text = "新增 \(r.punchesAdded) 条打卡、\(r.overridesAdded) 条手动记录"
        if r.skipped > 0 {
            text += ";本机已有的 \(r.skipped) 条保持不变"
        }
        if r.conflicting > 0 {
            text += "\n其中 \(r.conflicting) 条与\(source)不一致,已保留本机版本"
        }
        return text
    }
}
