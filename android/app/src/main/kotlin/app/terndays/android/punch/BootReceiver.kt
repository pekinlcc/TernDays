package app.terndays.android.punch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.terndays.android.Prefs

/** 开机 / 时区变更 / 时间调整 / 应用升级后：重排闹钟；开机场景顺带尝试补打。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.onboardingDone(context)) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                PunchScheduler.scheduleNext(context)
                PunchService.maybeBackfill(context)
                app.terndays.android.widget.TernDaysWidgetProvider.updateAll(context)
            }
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            -> PunchScheduler.scheduleNext(context)
        }
    }
}
