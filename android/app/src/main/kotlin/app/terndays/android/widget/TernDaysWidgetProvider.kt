package app.terndays.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import app.terndays.android.R
import app.terndays.android.db.PunchDb
import app.terndays.android.ui.MainActivity
import app.terndays.core.DayCounting
import app.terndays.core.WidgetSummary
import java.time.LocalDate

/**
 * 2×2 桌面小组件:今年 Top 3 城市及天数,三行等权重(同字号同色)。
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
        private val CITY_ROW_IDS = intArrayOf(R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3)
        private val CITY_NAME_IDS = intArrayOf(R.id.widget_city_1, R.id.widget_city_2, R.id.widget_city_3)
        private val CITY_DAYS_IDS = intArrayOf(R.id.widget_days_1, R.id.widget_days_2, R.id.widget_days_3)

        /** 第 2、3 行前面的弹性间距:行不显示时一并收掉,免得留一段空白 */
        private val CITY_GAP_IDS = intArrayOf(View.NO_ID, R.id.widget_gap_2, R.id.widget_gap_3)

        /** 竖屏高度够放满 3 行 / 2 行所需的 dp（行 ≈ 27dp,含年份与 16dp 上下边距）。 */
        private const val HEIGHT_THREE_ROWS_DP = 134
        private const val HEIGHT_TWO_ROWS_DP = 106

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
                // 竖屏高度 = OPTION_APPWIDGET_MAX_HEIGHT(MIN_HEIGHT 是横屏值,会偏小)
                val heightDp = runCatching {
                    manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                }.getOrDefault(0)
                manager.updateAppWidget(id, buildViews(context, model, heightDp))
            }
        }

        private fun loadModel(context: Context): WidgetSummary.Model {
            val today = LocalDate.now()
            val db = PunchDb.get(context)
            val stats = DayCounting.computeYearStats(
                today.year, today, db.punchesForYear(today.year), db.overridesForYear(today.year),
                java.time.LocalTime.now().hour,
            )
            return WidgetSummary.build(stats)
        }

        private fun buildViews(context: Context, model: WidgetSummary.Model, heightDp: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_terndays)
            views.setTextViewText(R.id.widget_year, model.yearLabel)

            // 矮格子放不下三行就少显示一行,宁可少显示也不截断(0 = 启动器没给尺寸,按标准 2×2 处理)
            val maxRows = when {
                heightDp == 0 || heightDp >= HEIGHT_THREE_ROWS_DP -> 3
                heightDp >= HEIGHT_TWO_ROWS_DP -> 2
                else -> 1
            }
            for (i in CITY_ROW_IDS.indices) {
                val line = model.topCities.getOrNull(i)?.takeIf { i < maxRows }
                bindRow(views, CITY_ROW_IDS[i], CITY_NAME_IDS[i], CITY_DAYS_IDS[i], line)
                if (CITY_GAP_IDS[i] != View.NO_ID) {
                    views.setViewVisibility(CITY_GAP_IDS[i], if (line == null) View.GONE else View.VISIBLE)
                }
            }
            views.setViewVisibility(
                R.id.widget_empty,
                if (model.topCities.isEmpty()) View.VISIBLE else View.GONE,
            )

            val unit = context.getString(R.string.widget_unit_day)
            views.setContentDescription(
                R.id.widget_root,
                if (model.topCities.isEmpty()) {
                    "${model.yearLabel},${context.getString(R.string.widget_empty)}"
                } else {
                    buildString {
                        append(model.yearLabel)
                        model.topCities.forEach {
                            append(",").append(it.name).append(" ").append(it.days).append(" ").append(unit)
                        }
                    }
                },
            )

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
