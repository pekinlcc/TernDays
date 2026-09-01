package app.terndays.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import app.terndays.android.punch.PunchScheduler

class TernDaysApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        if (Prefs.onboardingDone(this)) {
            PunchScheduler.scheduleNext(this)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PUNCH, "定位打卡", NotificationManager.IMPORTANCE_LOW).apply {
                description = "每天 07:00 / 17:00 打卡时短暂显示"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_REMIND, "打卡提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "打卡失败或需要补打时提醒"
            },
        )
    }

    companion object {
        const val CHANNEL_PUNCH = "punch"
        const val CHANNEL_REMIND = "remind"
    }
}
