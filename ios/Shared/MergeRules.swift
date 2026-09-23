import Foundation

/// 换手机迁移导入时的合并判定（与 Android :core MergeRules 逐行对齐）。
///
/// 两条原则：
///  1. **新手机已有的记录一律保留**（导入只补空缺，不覆盖）；
///  2. 导入不得破坏「同一天整天更正与半天更正互斥」这条存储层不变量。
enum MergeRules {

    /// - Parameters:
    ///   - existing: 本机该日期已有的更正范围
    ///   - incoming: 待导入的这条更正的范围
    /// - Returns: true = 可以写入；false = 跳过
    static func shouldImportOverride(existing: Set<OverrideScope>, incoming: OverrideScope) -> Bool {
        if existing.contains(.full) { return false }          // 本机整天更正优先
        if incoming == .full { return existing.isEmpty }        // 本机已有半天更正时整天不得覆盖
        return !existing.contains(incoming)                     // 另半天可以补进来
    }

    /// 导入时键(日期+时段 / 日期+范围)相同、本机保留的那条是否与对方「不一致」:
    /// 城市不同才算冲突(完全相同的只是重复)。结果页据此写「其中 K 条与旧手机不一致,已保留本机版本」。
    static func isConflict(local: Punch, incoming: Punch) -> Bool { local.cityKey != incoming.cityKey }

    static func isConflict(local: DayOverride, incoming: DayOverride) -> Bool { local.cityKey != incoming.cityKey }
}
