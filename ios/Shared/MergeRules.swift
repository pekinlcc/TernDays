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
}
