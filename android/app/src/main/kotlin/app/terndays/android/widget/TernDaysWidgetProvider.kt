package app.terndays.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import app.terndays.android.R
import app.terndays.android.db.PunchDb
import app.terndays.android.ui.MainActivity
import app.terndays.core.DayCounting
import app.terndays.core.WidgetSummary
import java.time.LocalDate

/**
 * 2×2 桌面小组件,一城一数:今年待得最久的城市 + 天数做唯一锚点,第 2、3 名是两行脚注。
 * 不做周期轮询(updatePeriodMillis=0):数据只在打卡时变化,
 * 由打卡 / 补记 / 开机 / 添加小组件时主动刷新(每天通常两次)。
 */
class TernDaysWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        Thread {
            try {
                push(context, manager, ids)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** 用户拖拽改尺寸后按新高度决定脚注显示几行。 */
    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle,
    ) {
        val pending = goAsync()
        Thread {
            try {
                push(context, manager, intArrayOf(appWidgetId))
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        /** 脚注两行都显示所需的最小高度;再矮先收第 3 名,再矮全收。 */
        private const val MIN_HEIGHT_TWO_ROWS_DP = 128
        private const val MIN_HEIGHT_ONE_ROW_DP = 112

        /** 打卡 / 补记后调用,后台线程刷新所有实例(应用进程存活场景)。 */
        fun updateAll(context: Context) {
            val app = context.applicationContext
            Thread {
                runCatching { pushAllSync(app) }
            }.apply { isDaemon = true }.start()
        }

        /** 同步刷新:广播接收器等短生命周期场景用(配合 goAsync,防止进程提前被杀)。 */
        fun pushAllSync(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, TernDaysWidgetProvider::class.java))
            if (ids.isEmpty()) return
            push(app, manager, ids)
        }

        private fun push(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val model = loadModel(context)
            ids.forEach { id ->
                val minHeightDp = runCatching {
                    manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
                }.getOrDefault(0)
                manager.updateAppWidget(id, buildViews(context, model, minHeightDp))
            }
        }

        private fun loadModel(context: Context): WidgetSummary.Model {
            val today = LocalDate.now()
            val db = PunchDb.get(context)
            val stats = DayCounting.computeYearStats(
                today.year, today, db.punchesForYear(today.year), db.overridesForYear(today.year),
            )
            return WidgetSummary.build(stats)
        }

        private fun buildViews(context: Context, model: WidgetSummary.Model, minHeightDp: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_terndays)
            views.setTextViewText(R.id.widget_year, model.yearLabel)

            val first = model.topCities.firstOrNull()
            if (first == null) {
                // 空态:年份留在表头左侧,下方一行提示;其余隐藏
                views.setViewVisibility(R.id.widget_city_1, View.GONE)
                views.setViewVisibility(R.id.widget_hero, View.GONE)
                views.setViewVisibility(R.id.widget_row_2, View.GONE)
                views.setViewVisibility(R.id.widget_row_3, View.GONE)
                views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
                views.setContentDescription(
                    R.id.widget_root, "${model.yearLabel},${context.getString(R.string.widget_empty)}",
                )
            } else {
                views.setViewVisibility(R.id.widget_empty, View.GONE)
                views.setViewVisibility(R.id.widget_city_1, View.VISIBLE)
                views.setViewVisibility(R.id.widget_hero, View.VISIBLE)
                views.setTextViewText(R.id.widget_city_1, first.name)
                views.setTextViewText(R.id.widget_days_1, first.days)
                // 最宽值(如 156.5)在 2×2 内容区放不下 44sp,降一档;其余保持锚点字号
                val heroSp = if (first.days.length >= 5) 38f else 44f
                views.setTextViewTextSize(R.id.widget_days_1, TypedValue.COMPLEX_UNIT_SP, heroSp)

                // 脚注:按实际高度决定显示几行(0 表示启动器未提供尺寸,按默认 2×2 处理)
                val rowsAllowed = when {
                    minHeightDp == 0 || minHeightDp >= MIN_HEIGHT_TWO_ROWS_DP -> 2
                    minHeightDp >= MIN_HEIGHT_ONE_ROW_DP -> 1
                    else -> 0
                }
                val second = model.topCities.getOrNull(1)?.takeIf { rowsAllowed >= 1 }
                val third = model.topCities.getOrNull(2)?.takeIf { rowsAllowed >= 2 }
                bindRow(views, R.id.widget_row_2, R.id.widget_city_2, R.id.widget_days_2, second)
                bindRow(views, R.id.widget_row_3, R.id.widget_city_3, R.id.widget_days_3, third)

                val unit = context.getString(R.string.widget_unit_day)
                val spoken = buildString {
                    append(model.yearLabel)
                    model.topCities.forEach { append(",").append(it.name).append(" ").append(it.days).append(" ").append(unit) }
                }
                views.setContentDescription(R.id.widget_root, spoken)
            }

            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            return views
        }

        private fun bindRow(views: RemoteViews, rowId: Int, nameId: Int, daysId: Int, line: WidgetSummary.CityLine?) {
            if (line == null) {
                views.setViewVisibility(rowId, View.GONE)
            } else {
                views.setViewVisibility(rowId, View.VISIBLE)
                views.setTextViewText(nameId, line.name)
                views.setTextViewText(daysId, line.days)
            }
        }
    }
}
