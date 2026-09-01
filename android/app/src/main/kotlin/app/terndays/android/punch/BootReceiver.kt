package app.terndays.android.punch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.terndays.android.Prefs
import app.terndays.android.widget.TernDaysWidgetProvider

/**
 * 开机 / 时区变更 / 时间调整 / 应用升级后：重排闹钟；开机场景顺带尝试补打。
 * 另监听「闹钟和提醒」权限恢复(Android 12+ 收回该权限时系统会清掉已排闹钟并停掉应用,
 * 重新授予时借这个广播把打卡链条重新接上,而不是静默死亡)。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.onboardingDone(context)) return
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                PunchScheduler.scheduleNext(context)
                PunchService.maybeBackfill(context)
                // 广播接收器返回即可能被杀:用 goAsync 保住小组件刷新线程
                val pending = goAsync()
                Thread {
                    try {
                        runCatching { TernDaysWidgetProvider.pushAllSync(context) }
                    } finally {
                        pending.finish()
                    }
                }.start()
            }
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
            -> PunchScheduler.scheduleNext(context)
        }
    }

    companion object {
        // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31+)
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
    }
}
