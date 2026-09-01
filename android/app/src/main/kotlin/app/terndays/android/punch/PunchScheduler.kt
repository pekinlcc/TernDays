package app.terndays.android.punch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.terndays.core.PunchRules
import app.terndays.core.Slot
import java.time.ZonedDateTime

/**
 * 用 AlarmManager 精确闹钟驱动每天两次打卡。
 * 每次只排「下一次」，触发后（或时区/时间变更后）再排下一次。
 */
object PunchScheduler {

    const val ACTION_PUNCH = "app.terndays.action.PUNCH"
    const val EXTRA_SLOT = "slot"
    private const val REQUEST_CODE = 1001

    fun scheduleNext(context: Context) {
        val next = PunchRules.nextPunchTime(ZonedDateTime.now())
        val slot = if (next.hour < 12) Slot.MORNING else Slot.EVENING

        val intent = Intent(context, PunchReceiver::class.java)
            .setAction(ACTION_PUNCH)
            .putExtra(EXTRA_SLOT, slot.name)
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val am = context.getSystemService(AlarmManager::class.java)
        val at = next.toInstant().toEpochMilli()
        if (canExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // 没有精确闹钟权限时降级为非精确（设置页会提示用户开启）
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun canExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}
