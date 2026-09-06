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
import java.time.LocalDate
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

    /** 候选定位里目前最准的一个(全部回调都在主线程,不需要加锁)。 */
    private var bestFix: Location? = null
    private var settleScheduled = false

    /**
     * 一次打卡的「决策上下文」:归属日期、时段、是否延迟,都在决定打这一次时就定下来。
     * 拿到定位后再重算会踩跨零点竞态——23:59 触发的晚点若 00:01 才拿到定位,
     * 会被写成第二天并占掉次日的晚点槽。
     */
    private data class Decision(val date: LocalDate, val slot: Slot, val delayed: Boolean)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (_: SecurityException) {
            // Android 14+ 定位权限被收回后,location 类型前台服务直接抛异常:
            // 不能崩,发提醒并安静退出(下一次闹钟到点会再试)
            notifyRemind("定位权限被关闭", "打卡需要「始终允许」定位权限,请到设置中重新开启")
            stopSelf()
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            stopSelf()
            return START_NOT_STICKY
        }

        val now = ZonedDateTime.now()
        val requested = intent?.getStringExtra(PunchScheduler.EXTRA_SLOT)
            ?.let { runCatching { Slot.valueOf(it) }.getOrNull() }
        val isRetry = intent?.getBooleanExtra(PunchScheduler.EXTRA_RETRY, false) == true
        val inWindow = PunchRules.slotInWindow(now.toLocalTime())
        val slot = when {
            inWindow != null -> inWindow
            requested == Slot.EXTRA -> Slot.EXTRA // 首点：首次安装立即记录，不限时段
            else -> {
                // 闹钟被系统推迟到窗口外：本时段作废，提醒可补记（重试落到窗口外时不再重复打扰）
                if (requested != null && !isRetry) {
                    notifyRemind("未能按时记录${slotLabel(requested)}", "打开应用可查看，无记录的日子可手动补记")
                }
                finish()
                return START_NOT_STICKY
            }
        }

        val decision = Decision(
            date = now.toLocalDate(),
            slot = slot,
            delayed = PunchRules.isDelayed(now.toLocalTime(), slot),
        )

        val db = PunchDb.get(this)
        if (db.hasPunch(decision.date, slot)) {
            finish()
            return START_NOT_STICKY
        }
        // 用户选「大致位置」时只有 COARSE:精度差但仍能判到城市(误差圈规则会兜底),
        // 不该当成「没有权限」直接不打卡
        if (!hasLocationPermission()) {
            notifyRemind("定位权限被关闭", "打卡需要「始终允许」定位权限，请到设置中重新开启")
            finish()
            return START_NOT_STICKY
        }

        requestLocation(decision)
        return START_NOT_STICKY
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocation(decision: Decision) {
        val lm = getSystemService(LocationManager::class.java)

        // 系统定位总开关关掉时不必白等 90 秒(还会常驻一条前台通知)
        if (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) {
            notifyRemind("定位服务已关闭", "打开系统「定位服务」后会自动恢复打卡；现在打开应用可立即补打")
            onLocation(decision, bestCached(lm), fromCache = true)
            return
        }

        val providers = buildList {
            if (Build.VERSION.SDK_INT >= 31 &&
                lm.allProviders.contains(LocationManager.FUSED_PROVIDER) &&
                lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)
            ) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) {
            onLocation(decision, bestCached(lm), fromCache = true)
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                for (p in providers) {
                    val signal = CancellationSignal()
                    cancelSignals.add(signal)
                    lm.getCurrentLocation(p, signal, mainExecutor) { loc -> onCandidate(decision, loc) }
                }
            } else {
                for (p in providers) {
                    val listener = LocationListener { loc -> onCandidate(decision, loc) }
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
            onLocation(decision, bestFix ?: bestCached(lm), fromCache = bestFix == null)
        }, TIMEOUT_MS)
    }

    /**
     * 收到一个候选定位。**不再谁先回调就用谁**——NETWORK 往往最先回来但误差上千米，
     * 正是边界城市误判的上游。够准就立即采用，否则再给更准的 provider 一小段时间。
     */
    private fun onCandidate(decision: Decision, loc: Location?) {
        if (loc == null || delivered.get()) return
        if (isBetter(loc, bestFix)) bestFix = loc
        val acc = bestFix?.takeIf { it.hasAccuracy() }?.accuracy
        if (acc != null && acc <= GOOD_ACCURACY_M) {
            onLocation(decision, bestFix, fromCache = false)
            return
        }
        if (!settleScheduled) {
            settleScheduled = true
            handler.postDelayed({
                onLocation(decision, bestFix, fromCache = false)
            }, SETTLE_MS)
        }
    }

    /** 有精度的优先；都有精度时误差小的优先。 */
    private fun isBetter(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        if (!candidate.hasAccuracy()) return false
        if (!current.hasAccuracy()) return true
        return candidate.accuracy < current.accuracy
    }

    private fun bestCached(lm: LocationManager): Location? = try {
        lm.allProviders
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < CACHE_MAX_AGE_MS }
    } catch (_: SecurityException) {
        null
    }

    private fun onLocation(decision: Decision, location: Location?, fromCache: Boolean) {
        val slot = decision.slot
        if (!delivered.compareAndSet(false, true)) return
        handler.removeCallbacksAndMessages(null)
        cancelSignals.forEach { runCatching { it.cancel() } }
        val lm = getSystemService(LocationManager::class.java)
        legacyListeners.forEach { runCatching { lm.removeUpdates(it) } }

        if (location == null) {
            // 窗口内还有时间就自己再试一次,不必等用户打开应用
            if (PunchScheduler.scheduleRetryIfInWindow(this, slot)) {
                finish()
                return
            }
            notifyRemind(
                "${slotLabel(slot)}打卡失败",
                "没拿到定位。打开应用会立即补打；已过窗口的日子可在首页点「纠正」手动指定城市",
            )
            finish()
            return
        }

        Thread {
            try {
                // 交叉验证：top-3 候选 + 行程连续性锚点 + 定位误差圈，
                // 消掉真实边界（深圳/香港、珠海/澳门…）附近的最近邻模糊。
                // 锚点 = 最近一条非改判打卡(当日有手动更正则以更正为准),防粘滞链自续期。
                val db = PunchDb.get(this)
                val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null
                val candidates = Cities.get(this).nearestByCity(location.latitude, location.longitude, 3)
                val prev = db.latestAnchorPunch()?.let { anchor ->
                    val key = db.overrideFor(anchor.localDate)?.cityKey ?: anchor.cityKey
                    CityResolver.Prev(
                        cityKey = key,
                        ageHours = (System.currentTimeMillis() - anchor.epochMs) / 3_600_000.0,
                    )
                }
                val resolution = CityResolver.resolve(candidates, accuracy, prev)
                val match = resolution?.match
                val zone = ZoneId.systemDefault()
                val punch = Punch(
                    // 归属日期/时段/延迟一律取决策时刻:跨零点拿到的定位不会跑到第二天去
                    localDate = decision.date,
                    slot = slot,
                    epochMs = System.currentTimeMillis(),
                    zoneId = zone.id,
                    lat = location.latitude,
                    lng = location.longitude,
                    accuracyM = accuracy,
                    cityKey = match?.cityKey ?: "unknown",
                    cityName = match?.cityName ?: "未知位置",
                    delayed = decision.delayed,
                    fromCache = fromCache,
                    viaContext = resolution?.viaContext == true,
                )
                db.insertPunch(punch)
                app.terndays.android.widget.TernDaysWidgetProvider.updateAll(this)
                app.terndays.android.DataBus.bump()
            } catch (_: Exception) {
                // 落库线程不允许把整个进程带崩;失败提醒用户手动补
                runCatching { notifyRemind("打卡保存失败", "打开应用可自动补打,或在设置中手动补记") }
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
        /** 收到第一个候选后再等这么久,给更准的 provider 机会 */
        private const val SETTLE_MS = 12_000L
        /** 误差小于此值即视为够准,不再等待 */
        private const val GOOD_ACCURACY_M = 80f
        private const val CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        private fun slotLabel(slot: Slot) = when (slot) {
            Slot.MORNING -> "早上 7 点"
            Slot.EVENING -> "下午 5 点"
            Slot.EXTRA -> "首点"
        }

        fun start(context: Context, slot: Slot?, isRetry: Boolean = false) {
            val intent = Intent(context, PunchService::class.java)
            if (slot != null) intent.putExtra(PunchScheduler.EXTRA_SLOT, slot.name)
            if (isRetry) intent.putExtra(PunchScheduler.EXTRA_RETRY, true)
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
            val app = context.applicationContext
            // DB 查询(首次含建库)不占主线程
            Thread {
                try {
                    val db = PunchDb.get(app)
                    val now = LocalDateTime.now()
                    val slot = PunchRules.slotToBackfill(
                        now,
                        hasMorning = db.hasPunch(now.toLocalDate(), Slot.MORNING),
                        hasEvening = db.hasPunch(now.toLocalDate(), Slot.EVENING),
                    )
                    when {
                        slot != null -> start(app, slot)
                        !db.hasAnyPunch() -> start(app, Slot.EXTRA)
                    }
                } catch (_: Exception) {
                    // 补打检查失败不影响主流程,下次打开再试
                }
            }.apply { isDaemon = true }.start()
        }
    }
}
