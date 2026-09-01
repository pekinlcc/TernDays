package app.terndays.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import app.terndays.android.R
import app.terndays.android.db.PunchDb
import app.terndays.android.ui.MainActivity
import app.terndays.core.DayCounting
import app.terndays.core.WidgetSummary
import java.time.LocalDate

/** 桌面小组件：当年概览（已记录天数 / 城市数 / 今日打卡状态 / Top 3 城市）。 */
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

    companion object {
        private val CITY_ROW_IDS = intArrayOf(R.id.widget_city_row_1, R.id.widget_city_row_2, R.id.widget_city_row_3)
        private val CITY_NAME_IDS = intArrayOf(R.id.widget_city_name_1, R.id.widget_city_name_2, R.id.widget_city_name_3)
        private val CITY_DAYS_IDS = intArrayOf(R.id.widget_city_days_1, R.id.widget_city_days_2, R.id.widget_city_days_3)

        /** 打卡 / 补记 / 开机后调用，立刻刷新所有实例。 */
        fun updateAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, TernDaysWidgetProvider::class.java))
            if (ids.isEmpty()) return
            Thread {
                runCatching { push(app, manager, ids) }
            }.start()
        }

        private fun push(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val today = LocalDate.now()
            val db = PunchDb.get(context)
            val punches = db.punchesForYear(today.year)
            val overrides = db.overridesForYear(today.year)
            val stats = DayCounting.computeYearStats(today.year, today, punches, overrides)
            val model = WidgetSummary.build(stats, today, punches)

            val views = RemoteViews(context.packageName, R.layout.widget_terndays)
            views.setTextViewText(R.id.widget_year, model.yearLabel)
            views.setTextViewText(R.id.widget_big_days, model.bigDays)
            views.setTextViewText(R.id.widget_stats, model.statsLabel)
            views.setTextViewText(R.id.widget_today, model.todayLabel ?: "")
            views.setViewVisibility(R.id.widget_today, if (model.todayLabel != null) View.VISIBLE else View.GONE)

            for (i in CITY_ROW_IDS.indices) {
                val line = model.topCities.getOrNull(i)
                if (line != null) {
                    views.setViewVisibility(CITY_ROW_IDS[i], View.VISIBLE)
                    views.setTextViewText(CITY_NAME_IDS[i], line.name)
                    views.setTextViewText(CITY_DAYS_IDS[i], "${line.days} 天")
                } else {
                    views.setViewVisibility(CITY_ROW_IDS[i], View.GONE)
                }
            }
            views.setViewVisibility(
                R.id.widget_empty,
                if (model.topCities.isEmpty()) View.VISIBLE else View.GONE,
            )

            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            return views
        }
    }
}
