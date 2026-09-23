package app.terndays.android.ui

import android.content.Context
import app.terndays.android.DataBus
import app.terndays.android.db.PunchDb
import app.terndays.android.widget.TernDaysWidgetProvider
import app.terndays.core.DayOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 更正 / 补记 / 恢复自动判定的唯一写库入口(此前首页、城市详情、设置页各写一遍,
 * 有两处还在主线程写库)。统一做四件事:IO 线程写库 → 刷新小组件 → 通知各页面重读 →
 * 弹出带「撤销」的提示(撤销 = 把涉及日期的更正整体换回写入前的快照)。
 */
object Corrections {

    suspend fun apply(context: Context, writes: List<DayOverride>, message: String) {
        if (writes.isEmpty()) return
        val dates = writes.map { it.localDate }.distinct()
        val snapshot = write(context) { db ->
            db.overridesOn(dates).also { db.setOverrides(writes) }
        }
        announce(context, message, dates, snapshot)
    }

    /** 恢复自动判定:删掉这一天的全部更正。 */
    suspend fun restoreAuto(context: Context, date: LocalDate, message: String = "已恢复自动判定") {
        val snapshot = write(context) { db ->
            db.overridesOn(listOf(date)).also { db.removeOverride(date) }
        }
        announce(context, message, listOf(date), snapshot)
    }

    /** 删掉某天某一范围的更正(设置页手动记录列表里的「恢复自动」)。 */
    suspend fun removeScope(context: Context, date: LocalDate, scope: app.terndays.core.OverrideScope) {
        val snapshot = write(context) { db ->
            db.overridesOn(listOf(date)).also { db.removeOverride(date, scope) }
        }
        announce(context, "已恢复自动判定", listOf(date), snapshot)
    }

    private suspend fun <T> write(context: Context, block: (PunchDb) -> T): T {
        val app = context.applicationContext
        return withContext(Dispatchers.IO) { block(PunchDb.get(app)) }.also { changed(app) }
    }

    private fun changed(context: Context) {
        TernDaysWidgetProvider.updateAll(context)
        DataBus.bump()
    }

    private fun announce(context: Context, message: String, dates: List<LocalDate>, snapshot: List<DayOverride>) {
        val app = context.applicationContext
        UndoCenter.show(message, "撤销") {
            withContext(Dispatchers.IO) { PunchDb.get(app).replaceOverrides(dates, snapshot) }
            changed(app)
            UndoCenter.show("已撤销")
        }
    }
}
