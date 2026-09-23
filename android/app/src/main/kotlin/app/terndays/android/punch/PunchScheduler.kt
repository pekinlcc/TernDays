package app.terndays.android.punch

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.terndays.core.PunchRules
import app.terndays.core.Slot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
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
     * 定位失败后在补捕窗口内再试**一次**(判定见 :core PunchRules.retryAt:重试本身失败不再排,
     * 窗口终点按决策日期算)。
     * @return true = 已排上重试;false = 不该或来不及重试,调用方去发失败提醒
     */
    fun scheduleRetry(context: Context, decisionDate: LocalDate, slot: Slot, isRetry: Boolean): Boolean {
        val zone = ZoneId.systemDefault()
        val at = PunchRules.retryAt(decisionDate, slot, LocalDateTime.now(zone), isRetry) ?: return false
        val am = context.getSystemService(AlarmManager::class.java)
        val ms = at.atZone(zone).toInstant().toEpochMilli()
        return runCatching {
            if (canExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, retryIntent(context, slot))
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, retryIntent(context, slot))
            }
        }.isSuccess
    }

    /** 该时段已经打上:撤掉还没触发的重试,免得到点又闪一次前台通知。 */
    fun cancelRetry(context: Context) {
        runCatching {
            context.getSystemService(AlarmManager::class.java).cancel(retryIntent(context, Slot.MORNING))
        }
    }

    private fun retryIntent(context: Context, slot: Slot): PendingIntent {
        val intent = Intent(context, PunchReceiver::class.java)
            .setAction(ACTION_RETRY)
            .putExtra(EXTRA_SLOT, slot.name)
            .putExtra(EXTRA_RETRY, true)
        // 同一个 requestCode + FLAG_UPDATE_CURRENT:任何时刻最多只有一个重试在排队
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE_RETRY, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun canExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }
}
