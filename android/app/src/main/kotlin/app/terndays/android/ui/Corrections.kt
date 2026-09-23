package app.terndays.android.ui

import android.content.Context
import app.terndays.android.DataBus
import app.terndays.android.db.PunchDb
import app.terndays.android.widget.TernDaysWidgetProvider
import app.terndays.core.DayOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 更正 / 补记 / 恢复自动判定的唯一写库入口(此前首页、城市详情、设置页各写一遍,
 * 有两处还在主线程写库)。统一做四件事:IO 线程写库 → 刷新小组件 → 通知各页面重读 →
 * 弹出带「撤销」的提示(撤销 = 把涉及日期的更正整体换回写入前的快照)。
 */
object Corrections {

    /**
     * 应用级作用域:更正 / 补记都在弹窗关闭的同一瞬间发起,不能挂在马上就要被遗忘的弹窗作用域上
     * (否则写完库后的刷新、底部提示与撤销都会被取消)。
     */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * @return 撤销动作(已同时挂在底部提示上);弹窗里需要就地撤销时用它
     *   (例如补记弹窗盖在底部提示之上,提示点不到)
     */
    suspend fun apply(context: Context, writes: List<DayOverride>, message: String): (suspend () -> Unit)? {
        if (writes.isEmpty()) return null
        val dates = writes.map { it.localDate }.distinct()
        val snapshot = write(context) { db ->
            db.overridesOn(dates).also { db.setOverrides(writes) }
        }
        return announce(context, message, dates, snapshot)
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

    /**
     * 写库一旦开始就必须走完「刷新小组件 + 通知各页面」:调用方的作用域可能在写库途中被取消
     * (弹窗写完就关、撤销提示被点掉),NonCancellable 保证库和界面不会各说各话。
     */
    private suspend fun <T> write(context: Context, block: (PunchDb) -> T): T {
        val app = context.applicationContext
        return withContext(NonCancellable) {
            withContext(Dispatchers.IO) { block(PunchDb.get(app)) }.also { changed(app) }
        }
    }

    private fun changed(context: Context) {
        TernDaysWidgetProvider.updateAll(context)
        DataBus.bump()
    }

    private fun announce(
        context: Context,
        message: String,
        dates: List<LocalDate>,
        snapshot: List<DayOverride>,
    ): suspend () -> Unit {
        val app = context.applicationContext
        val undo: suspend () -> Unit = {
            withContext(NonCancellable) {
                withContext(Dispatchers.IO) { PunchDb.get(app).replaceOverrides(dates, snapshot) }
                changed(app)
                UndoCenter.show("已撤销")
            }
        }
        UndoCenter.show(message, "撤销", undo)
        return undo
    }
}
