package app.terndays.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.app.AlarmManager
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
import java.time.ZoneId

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
                scheduleMidnightRefresh(context)
            } catch (_: Throwable) {
                // 裸线程里的异常会直接杀掉进程:小组件读库失败不该带崩应用
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_MIDNIGHT) {
            // 天数会在零点自己变化(昨天的半天补满 1 天、元旦换年),必须主动刷一次
            val pending = goAsync()
            Thread {
                try {
                    pushAllSync(context)
                    scheduleMidnightRefresh(context)
                } catch (_: Throwable) {
                    // 同上:不能把进程带崩
                } finally {
                    pending.finish()
                }
            }.start()
            return
        }
        super.onReceive(context, intent)
    }

    override fun onEnabled(context: Context) {
        scheduleMidnightRefresh(context)
    }

    override fun onDisabled(context: Context) {
        cancelMidnightRefresh(context)
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

        const val ACTION_MIDNIGHT = "app.terndays.action.WIDGET_MIDNIGHT"
        private const val MIDNIGHT_REQUEST_CODE = 2001

        private fun midnightIntent(context: Context) = PendingIntent.getBroadcast(
            context, MIDNIGHT_REQUEST_CODE,
            Intent(context, TernDaysWidgetProvider::class.java).setAction(ACTION_MIDNIGHT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /**
         * 排下一个零点的刷新。v0.9 起天数会随时间变化(进行中的今天先算 0.5,过了零点补满),
         * 而刷新一直是纯打卡驱动的——不排这一次,凌晨到次日首次打卡之间小组件会一直少半天,
         * 元旦凌晨甚至还写着去年。用非精确闹钟即可(差几分钟无所谓,也不耗电)。
         */
        fun scheduleMidnightRefresh(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            if (manager.getAppWidgetIds(ComponentName(app, TernDaysWidgetProvider::class.java)).isEmpty()) return
            val at = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli() + 5_000
            runCatching {
                app.getSystemService(AlarmManager::class.java)
                    .setAndAllowWhileIdle(AlarmManager.RTC, at, midnightIntent(app))
            }
        }

        private fun cancelMidnightRefresh(context: Context) {
            runCatching {
                context.getSystemService(AlarmManager::class.java).cancel(midnightIntent(context.applicationContext))
            }
        }

        /** 打卡 / 补记后调用,后台线程刷新所有实例(应用进程存活场景)。 */
        fun updateAll(context: Context) {
            val app = context.applicationContext
            Thread {
                runCatching {
                    pushAllSync(app)
                    scheduleMidnightRefresh(app)
                }
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
                nowHour = java.time.LocalTime.now().hour,
                earliestRecordDate = db.earliestRecordDate(),
            )
            return WidgetSummary.build(stats)
        }

        private fun buildViews(context: Context, model: WidgetSummary.Model, heightDp: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_terndays)
            views.setTextViewText(R.id.widget_year, model.yearLabel)

            // 矮格子放不下三行就少显示一行,宁可少显示也不截断(0 = 启动器没给尺寸,按标准 2×2 处理)。
            // 阈值随系统字体缩放放大:大字号下每行更高,否则第三行会被裁掉。
            val scale = context.resources.configuration.fontScale.coerceIn(1f, 2f)
            val maxRows = when {
                heightDp == 0 || heightDp >= HEIGHT_THREE_ROWS_DP * scale -> 3
                heightDp >= HEIGHT_TWO_ROWS_DP * scale -> 2
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
                        // 只念真正显示出来的行,别念被收掉的城市
                        model.topCities.take(maxRows).forEach {
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
