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

/**
 * 桌面小组件：只显示今年 Top 3 城市及天数。
 * 不做周期轮询（updatePeriodMillis=0）：数据只在打卡时变化，
 * 由打卡 / 补记 / 开机 / 添加小组件时主动刷新（每天通常两次）。
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

    companion object {
        private val CITY_ROW_IDS = intArrayOf(R.id.widget_city_row_1, R.id.widget_city_row_2, R.id.widget_city_row_3)
        private val CITY_NAME_IDS = intArrayOf(R.id.widget_city_name_1, R.id.widget_city_name_2, R.id.widget_city_name_3)
        private val CITY_DAYS_IDS = intArrayOf(R.id.widget_city_days_1, R.id.widget_city_days_2, R.id.widget_city_days_3)

        /** 打卡 / 补记后调用，后台线程刷新所有实例(应用进程存活场景)。 */
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
            val views = buildViews(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context): RemoteViews {
            val today = LocalDate.now()
            val db = PunchDb.get(context)
            val stats = DayCounting.computeYearStats(
                today.year, today, db.punchesForYear(today.year), db.overridesForYear(today.year),
            )
            val model = WidgetSummary.build(stats)

            val views = RemoteViews(context.packageName, R.layout.widget_terndays)
            views.setTextViewText(R.id.widget_year, model.yearLabel)
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
