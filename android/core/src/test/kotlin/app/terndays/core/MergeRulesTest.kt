package app.terndays.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MergeRulesTest {

    private val none = emptySet<OverrideScope>()
    private val full = setOf(OverrideScope.FULL)
    private val morning = setOf(OverrideScope.MORNING)
    private val bothHalves = setOf(OverrideScope.MORNING, OverrideScope.EVENING)

    @Test
    fun `本机没有更正时全部可导入`() {
        assertTrue(MergeRules.shouldImportOverride(none, OverrideScope.FULL))
        assertTrue(MergeRules.shouldImportOverride(none, OverrideScope.MORNING))
        assertTrue(MergeRules.shouldImportOverride(none, OverrideScope.EVENING))
    }

    @Test
    fun `本机整天更正优先,任何导入都跳过`() {
        assertFalse(MergeRules.shouldImportOverride(full, OverrideScope.FULL))
        assertFalse(MergeRules.shouldImportOverride(full, OverrideScope.MORNING))
        assertFalse(MergeRules.shouldImportOverride(full, OverrideScope.EVENING))
    }

    @Test
    fun `本机有半天更正时整天不得覆盖`() {
        assertFalse(MergeRules.shouldImportOverride(morning, OverrideScope.FULL))
        assertFalse(MergeRules.shouldImportOverride(bothHalves, OverrideScope.FULL))
    }

    @Test
    fun `另半天可以补进来,同半天保留本机`() {
        // 跨城日:本机只改了上半天,导入的下半天要补上(此前按日期去重会丢掉)
        assertTrue(MergeRules.shouldImportOverride(morning, OverrideScope.EVENING))
        assertFalse(MergeRules.shouldImportOverride(morning, OverrideScope.MORNING))
        assertFalse(MergeRules.shouldImportOverride(bothHalves, OverrideScope.MORNING))
        assertFalse(MergeRules.shouldImportOverride(bothHalves, OverrideScope.EVENING))
    }
}
