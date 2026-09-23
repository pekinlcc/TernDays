package app.terndays.android.punch

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import app.terndays.android.Prefs
import app.terndays.android.R
import app.terndays.android.TernDaysApp
import app.terndays.android.db.PunchDb
import app.terndays.android.util.Intents
import app.terndays.core.DayCounting
import app.terndays.core.Regions
import app.terndays.core.Thresholds
import java.time.LocalDate
import java.time.LocalTime

/**
 * 天数阈值(设置 → 天数提醒):算出每条阈值在其窗口(自然年 / 最近 180 天)里的已用天数,
 * 接近或达到时发一次本地通知。同一阈值同一窗口期同一级别只提醒一次。全部离线。
 */
object ThresholdAlerts {

    /** 读库 + 计算,调用方负责放在后台线程。 */
    fun statuses(context: Context): List<Thresholds.Status> {
        val list = Thresholds.decode(Prefs.thresholds(context))
        if (list.isEmpty()) return emptyList()
        val db = PunchDb.get(context)
        val today = LocalDate.now()
        val punches = db.allPunches()
        val overrides = db.allOverrides()
        val earliest = db.earliestRecordDate()
        val hour = LocalTime.now().hour
        return list.map { t ->
            val (from, to) = Thresholds.range(t.window, today)
            val stats = DayCounting.computeRangeStats(from, to, today, punches, overrides, hour, earliest)
            val used = stats.cities.filter { Regions.codeOf(it.cityKey) == t.regionCode }.sumOf { it.days }
            Thresholds.status(t, used)
        }
    }

    fun check(context: Context) {
        val today = LocalDate.now()
        val notified = Prefs.thresholdNotified(context)
        for (s in statuses(context)) {
            if (s.level == Thresholds.Level.OK) continue
            val key = s.threshold.notifyKey(today) + "|" + s.level.name
            if (key in notified) continue
            Prefs.addThresholdNotified(context, key)
            notify(context, s)
        }
    }

    fun describe(s: Thresholds.Status): String {
        val t = s.threshold
        val region = Regions.nameOf(t.regionCode)
        val span = if (t.window == Thresholds.Window.YEAR) "今年" else "最近 180 天"
        val used = DayCounting.formatDays(s.used)
        return if (s.level == Thresholds.Level.REACHED) {
            "$region${span}已 $used 天，已达到 ${t.days} 天上限"
        } else {
            "$region${span}已 $used 天，距 ${t.days} 天上限还剩 ${DayCounting.formatDays(s.remaining)} 天"
        }
    }

    private fun notify(context: Context, s: Thresholds.Status) {
        val text = describe(s)
        val n = NotificationCompat.Builder(context, TernDaysApp.CHANNEL_REMIND)
            .setSmallIcon(R.drawable.ic_stat_tern)
            .setContentTitle(if (s.level == Thresholds.Level.REACHED) "已达到天数上限" else "天数快到上限了")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(Intents.openApp(context))
            .setAutoCancel(true)
            .build()
        // 每个地区一个通知 id,多个阈值同时触发时不互相覆盖
        val id = 2000 + (s.threshold.regionCode.hashCode() and 0x3FF)
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
