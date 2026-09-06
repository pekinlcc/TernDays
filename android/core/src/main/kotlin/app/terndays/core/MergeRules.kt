package app.terndays.core

/**
 * 换手机迁移导入时的合并判定（Android / iOS 同一套口径）。
 *
 * 两条原则：
 *  1. **新手机已有的记录一律保留**（导入只补空缺，不覆盖）；
 *  2. 导入不得破坏「同一天整天更正与半天更正互斥」这条存储层不变量。
 *
 * 因此单纯按「日期」去重会丢掉跨城日的另半天更正，单纯按「(日期, 时段)」去重
 * 又会让整天与半天共存 —— 两端此前各错一头，这里统一。
 */
object MergeRules {

    /**
     * @param existing 本机该日期已有的更正范围（可能是空、{FULL}、{MORNING}、{EVENING} 或两个半天）
     * @param incoming 待导入的这条更正的范围
     * @return true = 可以写入；false = 跳过（本机已有的优先，或写入会破坏互斥）
     */
    fun shouldImportOverride(existing: Set<OverrideScope>, incoming: OverrideScope): Boolean = when {
        // 本机是整天更正：整天已经盖住这一天，半天再进来会同时存在两种范围
        existing.contains(OverrideScope.FULL) -> false
        // 本机已有半天更正：整天进来会作废本机那半天，且破坏互斥
        incoming == OverrideScope.FULL -> existing.isEmpty()
        // 同一半天本机已有则保留本机；另一半天可以补进来（跨城日的 0.5 + 0.5）
        else -> !existing.contains(incoming)
    }
}
