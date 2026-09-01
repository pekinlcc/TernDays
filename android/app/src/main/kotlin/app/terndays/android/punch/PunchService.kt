package app.terndays.android.punch

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.terndays.android.Prefs
import app.terndays.android.R
import app.terndays.android.TernDaysApp
import app.terndays.android.db.PunchDb
import app.terndays.android.geo.Cities
import app.terndays.android.ui.MainActivity
import app.terndays.core.CityResolver
import app.terndays.core.Punch
import app.terndays.core.PunchRules
import app.terndays.core.Slot
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前台服务：取一次定位 → 离线解析城市 → 落库 → 退出。
 * 由精确闹钟（PunchReceiver）、开机补打（BootReceiver）或打开应用补打触发。
 */
class PunchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val delivered = AtomicBoolean(false)
    private val cancelSignals = ArrayList<CancellationSignal>()
    private val legacyListeners = ArrayList<LocationListener>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        val now = ZonedDateTime.now()
        val requested = intent?.getStringExtra(PunchScheduler.EXTRA_SLOT)
            ?.let { runCatching { Slot.valueOf(it) }.getOrNull() }
        val inWindow = PunchRules.slotInWindow(now.toLocalTime())
        val slot = when {
            inWindow != null -> inWindow
            requested == Slot.EXTRA -> Slot.EXTRA // 首点：首次安装立即记录，不限时段
            else -> {
                // 闹钟被系统推迟到窗口外：本时段作废，提醒可补记
                if (requested != null) notifyRemind("未能按时记录${slotLabel(requested)}", "打开应用可查看，无记录的日子可手动补记")
                finish()
                return START_NOT_STICKY
            }
        }

        val db = PunchDb.get(this)
        if (db.hasPunch(now.toLocalDate(), slot)) {
            finish()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifyRemind("定位权限被关闭", "打卡需要「始终允许」定位权限，请到设置中重新开启")
            finish()
            return START_NOT_STICKY
        }

        requestLocation(slot)
        return START_NOT_STICKY
    }

    private fun requestLocation(slot: Slot) {
        val lm = getSystemService(LocationManager::class.java)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) {
            onLocation(slot, bestCached(lm), fromCache = true)
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                for (p in providers) {
                    val signal = CancellationSignal()
                    cancelSignals.add(signal)
                    lm.getCurrentLocation(p, signal, mainExecutor) { loc ->
                        if (loc != null) onLocation(slot, loc, fromCache = false)
                    }
                }
            } else {
                for (p in providers) {
                    val listener = LocationListener { loc -> onLocation(slot, loc, fromCache = false) }
                    legacyListeners.add(listener)
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(p, listener, Looper.getMainLooper())
                }
            }
        } catch (_: SecurityException) {
            notifyRemind("定位权限被关闭", "打卡需要「始终允许」定位权限，请到设置中重新开启")
            finish()
            return
        }

        handler.postDelayed({
            // 超时兜底：用最近的缓存位置（6 小时内），城市级别通常仍然正确
            onLocation(slot, bestCached(lm), fromCache = true)
        }, TIMEOUT_MS)
    }

    private fun bestCached(lm: LocationManager): Location? = try {
        lm.allProviders
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < CACHE_MAX_AGE_MS }
    } catch (_: SecurityException) {
        null
    }

    private fun onLocation(slot: Slot, location: Location?, fromCache: Boolean) {
        if (!delivered.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        cancelSignals.forEach { runCatching { it.cancel() } }
        val lm = getSystemService(LocationManager::class.java)
        legacyListeners.forEach { runCatching { lm.removeUpdates(it) } }

        if (location == null) {
            notifyRemind("${slotLabel(slot)}打卡失败", "没拿到定位。打开应用时会自动补打，或在设置中手动补记")
            finish()
            return
        }

        Thread {
            try {
                // 交叉验证：top-3 候选 + 上一次打卡的行程连续性 + 定位误差圈，
                // 消掉真实边界（深圳/香港、珠海/澳门…）附近的最近邻模糊
                val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null
                val candidates = Cities.get(this).nearestByCity(location.latitude, location.longitude, 3)
                val prev = PunchDb.get(this).latestResolvedPunch()?.let {
                    CityResolver.Prev(
                        cityKey = it.cityKey,
                        lat = it.lat,
                        lng = it.lng,
                        ageHours = (System.currentTimeMillis() - it.epochMs) / 3_600_000.0,
                    )
                }
                val match = CityResolver.resolve(candidates, accuracy, prev)?.match
                val now = ZonedDateTime.now()
                val zone = ZoneId.systemDefault()
                val punch = Punch(
                    localDate = now.toLocalDate(),
                    slot = slot,
                    epochMs = now.toInstant().toEpochMilli(),
                    zoneId = zone.id,
                    lat = location.latitude,
                    lng = location.longitude,
                    accuracyM = accuracy,
                    cityKey = match?.cityKey ?: "unknown",
                    cityName = match?.cityName ?: "未知位置",
                    delayed = PunchRules.isDelayed(now.toLocalTime(), slot),
                    fromCache = fromCache,
                )
                PunchDb.get(this).insertPunch(punch)
                app.terndays.android.widget.TernDaysWidgetProvider.updateAll(this)
            } finally {
                handler.post { finish() }
            }
        }.start()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, TernDaysApp.CHANNEL_PUNCH)
            .setSmallIcon(R.drawable.ic_stat_tern)
            .setContentTitle("正在记录当前城市…")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notifyRemind(title: String, text: String) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, TernDaysApp.CHANNEL_REMIND)
            .setSmallIcon(R.drawable.ic_stat_tern)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_REMIND_ID, n)
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val NOTIF_ID = 10
        private const val NOTIF_REMIND_ID = 11
        private const val TIMEOUT_MS = 90_000L
        private const val CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        private fun slotLabel(slot: Slot) = when (slot) {
            Slot.MORNING -> "早上 7 点"
            Slot.EVENING -> "下午 5 点"
            Slot.EXTRA -> "首点"
        }

        fun start(context: Context, slot: Slot?) {
            val intent = Intent(context, PunchService::class.java)
            if (slot != null) intent.putExtra(PunchScheduler.EXTRA_SLOT, slot.name)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // 后台前台服务被系统限制（常见于国产 ROM）：退化为提醒
                val nm = context.getSystemService(NotificationManager::class.java)
                val pi = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                nm.notify(
                    NOTIF_REMIND_ID,
                    NotificationCompat.Builder(context, TernDaysApp.CHANNEL_REMIND)
                        .setSmallIcon(R.drawable.ic_stat_tern)
                        .setContentTitle("打卡被系统拦下了")
                        .setContentText("点这里打开应用完成打卡，并在设置中开启自启动/后台运行")
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .build(),
                )
            }
        }

        /**
         * 打开应用/开机时调用：
         *  1. 处于打卡窗口内且该时段缺记录 → 补打该时段；
         *  2. 否则若从未有过任何记录（首次安装）→ 立即打一个「首点」（EXTRA），
         *     不占早/晚槽，只作所在半天的兜底样本。
         */
        fun maybeBackfill(context: Context) {
            if (!Prefs.onboardingDone(context)) return
            val db = PunchDb.get(context)
            val now = LocalDateTime.now()
            val slot = PunchRules.slotToBackfill(
                now,
                hasMorning = db.hasPunch(now.toLocalDate(), Slot.MORNING),
                hasEvening = db.hasPunch(now.toLocalDate(), Slot.EVENING),
            )
            when {
                slot != null -> start(context, slot)
                !db.hasAnyPunch() -> start(context, Slot.EXTRA)
            }
        }
    }
}
