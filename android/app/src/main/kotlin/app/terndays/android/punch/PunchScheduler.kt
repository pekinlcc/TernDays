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
    const val ACTION_RETRY = "app.terndays.action.PUNCH_RETRY"
    const val EXTRA_SLOT = "slot"
    const val EXTRA_RETRY = "retry"
    private const val REQUEST_CODE = 1001
    private const val REQUEST_CODE_RETRY = 1002
    private const val RETRY_DELAY_MS = 10 * 60 * 1000L

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

    /**
     * 定位失败后在补捕窗口内自己再试一次(此前只能干等用户打开应用)。
     * @return true = 已排上重试;false = 窗口内已经来不及,调用方去发失败提醒
     */
    fun scheduleRetryIfInWindow(context: Context, slot: Slot): Boolean {
        val now = ZonedDateTime.now()
        val windowEnd = when (slot) {
            Slot.MORNING -> now.toLocalDate().atTime(PunchRules.MORNING_WINDOW_END).atZone(now.zone)
            Slot.EVENING -> now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
            Slot.EXTRA -> return false // 首点不重试:用户打开应用就会再打
        }
        val at = now.plusNanos(RETRY_DELAY_MS * 1_000_000)
        if (!at.isBefore(windowEnd)) return false

        val intent = Intent(context, PunchReceiver::class.java)
            .setAction(ACTION_RETRY)
            .putExtra(EXTRA_SLOT, slot.name)
            .putExtra(EXTRA_RETRY, true)
        val pi = PendingIntent.getBroadcast(
            context, REQUEST_CODE_RETRY, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(AlarmManager::class.java)
        val ms = at.toInstant().toEpochMilli()
        runCatching {
            if (canExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            }
        }.onFailure { return false }
        return true
    }

    fun canExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}
