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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.terndays.android.DataBus
import app.terndays.android.Prefs
import app.terndays.android.R
import app.terndays.android.TernDaysApp
import app.terndays.android.db.PunchDb
import app.terndays.android.geo.Cities
import app.terndays.android.util.Intents
import app.terndays.android.util.Perms
import app.terndays.android.widget.TernDaysWidgetProvider
import app.terndays.core.CityResolver
import app.terndays.core.Punch
import app.terndays.core.PunchRules
import app.terndays.core.Slot
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 前台服务：取一次定位 → 离线解析城市 → 落库 → 退出。
 * 由精确闹钟（PunchReceiver）、重试闹钟、开机/时区变更补打（BootReceiver）或打开应用补打触发。
 *
 * 状态按**请求**管理:一轮定位期间又来的请求(例如首点还在定位时 17:00 晚点闹钟到了)排进
 * [pending],拿到定位后为每个待决请求各落一条;落库期间才到的请求,落库后再开新一轮。
 * 此前整个服务共用一份「已交付」标记且永不复位,后到的请求会被静默吞掉。
 */
class PunchService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** 等这一轮定位的请求(同一个定位可以同时满足首点与正式时段)。以下字段只在主线程读写。 */
    private val pending = ArrayList<Decision>()
    private var locating = false
    private var writing = 0
    /** 每轮定位的代次:过期的超时、迟到的回调一律作废 */
    private var generation = 0
    private val cancelSignals = ArrayList<CancellationSignal>()
    private val legacyListeners = ArrayList<LocationListener>()
    private var bestFix: Location? = null
    private var settleScheduled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastStartId = 0

    /**
     * 一次打卡的「决策上下文」:归属日期、时段、是否延迟,都在决定打这一次时就定下来。
     * 拿到定位后再重算会踩跨零点竞态——23:59 触发的晚点若 00:01 才拿到定位,
     * 会被写成第二天并占掉次日的晚点槽。isRetry 决定失败后还要不要再排重试(只重试一次)。
     */
    private data class Decision(val date: LocalDate, val slot: Slot, val delayed: Boolean, val isRetry: Boolean)

    /** 一轮定位拿不到位置的原因:决定提醒文案、以及要不要排重试。 */
    private enum class Failure { NO_FIX, LOCATION_OFF, PERMISSION }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (_: SecurityException) {
            // Android 14+ 定位权限被收回后,location 类型前台服务直接抛异常:
            // 不能崩,发提醒并安静退出(下一次闹钟到点会再试)
            recordAttempt(null, "permission", "缺定位权限")
            remindOnce(LocalDate.now(), null, "perm", PERM_TITLE, PERM_TEXT)
            stopIfIdle()
            return START_NOT_STICKY
        } catch (_: IllegalStateException) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        // 暂停期间来的请求(已经排上的闹钟、通知里的按钮)一律不打
        if (Prefs.punchPaused(this)) {
            recordAttempt(null, "paused", "自动打卡已暂停")
            stopIfIdle()
            return START_NOT_STICKY
        }

        val now = ZonedDateTime.now()
        val requested = intent?.getStringExtra(PunchScheduler.EXTRA_SLOT)
            ?.let { runCatching { Slot.valueOf(it) }.getOrNull() }
        val isRetry = intent?.getBooleanExtra(PunchScheduler.EXTRA_RETRY, false) == true
        val fromForeground = intent?.getBooleanExtra(EXTRA_FOREGROUND, false) == true
        val inWindow = PunchRules.slotInWindow(now.toLocalTime())
        val slot = when {
            inWindow != null -> inWindow
            requested == Slot.EXTRA -> Slot.EXTRA // 首点：首次安装立即记录，不限时段
            else -> {
                // 用户在通知里点「立即打卡」时这个时段的窗口已经关了:说清楚,别悄无声息
                // (通知按钮不会自动收起,不说的话用户会一直对着一个点了没反应的按钮)
                if (fromForeground && requested != null) {
                    recordAttempt(requested, "late", "打卡时间已过")
                    notifyRemind(
                        "${slotLabel(requested)}的打卡时间已过",
                        "这个时段已经不能自动记录了，可在首页点「纠正」手动指定城市",
                    )
                    stopIfIdle()
                    return START_NOT_STICKY
                }
                // 闹钟(或重试)被系统推迟到窗口外：本时段作废。重试落到窗口外同样要说一声,不能静默
                if (requested != null) {
                    recordAttempt(requested, "late", "被系统推迟到了窗口之外")
                    remindOnce(
                        now.toLocalDate(), requested, "late",
                        "${slotLabel(requested)}没能按时记录", LATE_TEXT,
                    )
                }
                stopIfIdle()
                return START_NOT_STICKY
            }
        }

        val decision = Decision(
            date = now.toLocalDate(),
            slot = slot,
            delayed = PunchRules.isDelayed(now.toLocalTime(), slot),
            isRetry = isRetry,
        )

        if (PunchDb.get(this).hasPunch(decision.date, slot) ||
            pending.any { it.date == decision.date && it.slot == slot }
        ) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        // 用户选「大致位置」时只有 COARSE:精度差但仍能判到城市(误差圈规则会兜底)
        if (!hasLocationPermission()) {
            recordAttempt(slot, "permission", "缺定位权限")
            remindOnce(decision.date, slot, "perm", PERM_TITLE, PERM_TEXT)
            stopIfIdle()
            return START_NOT_STICKY
        }
        // 后台触发(闹钟/开机/时区变化/重试)而定位只是「仅使用期间」:拿不到位置,
        // 不必白等 90 秒再报一个错的原因
        if (!fromForeground && !Perms.backgroundLocation(this)) {
            recordAttempt(slot, "needs_always", "需要「始终允许」定位")
            remindOnce(decision.date, slot, "bg", BG_TITLE, BG_TEXT)
            stopIfIdle()
            return START_NOT_STICKY
        }

        pending.add(decision)
        if (!locating && writing == 0) requestLocation()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopLocationUpdates()
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        super.onDestroy()
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        val lm = getSystemService(LocationManager::class.java)
        generation++
        val gen = generation
        locating = true
        bestFix = null
        settleScheduled = false
        // 最长要等 90 秒:Handler 按 uptime 计时,深睡时不走,必须持有唤醒锁
        acquireWakeLock()

        // 系统定位总开关关掉时不必白等 90 秒,也不排重试(重试同样拿不到)
        if (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) {
            deliver(gen, bestCached(lm), fromCache = true, failure = Failure.LOCATION_OFF)
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
            deliver(gen, bestCached(lm), fromCache = true, failure = Failure.LOCATION_OFF)
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                for (p in providers) {
                    val signal = CancellationSignal()
                    cancelSignals.add(signal)
                    lm.getCurrentLocation(p, signal, mainExecutor) { loc -> onCandidate(gen, loc) }
                }
            } else {
                for (p in providers) {
                    val listener = LocationListener { loc -> onCandidate(gen, loc) }
                    legacyListeners.add(listener)
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(p, listener, Looper.getMainLooper())
                }
            }
        } catch (_: SecurityException) {
            deliver(gen, null, fromCache = false, failure = Failure.PERMISSION)
            return
        }

        handler.postDelayed({
            // 超时兜底：用本轮最好的候选,没有就用最近的缓存位置（6 小时内），城市级别通常仍然正确
            deliver(gen, bestFix ?: bestCached(lm), fromCache = bestFix == null, failure = Failure.NO_FIX)
        }, TIMEOUT_MS)
    }

    /**
     * 收到一个候选定位。**不谁先回调就用谁**——NETWORK 往往最先回来但误差上千米，
     * 正是边界城市误判的上游。够准就立即采用，否则再给更准的定位源一小段时间。
     */
    private fun onCandidate(gen: Int, loc: Location?) {
        if (loc == null || gen != generation || !locating) return
        if (isBetter(loc, bestFix)) bestFix = loc
        val acc = bestFix?.takeIf { it.hasAccuracy() }?.accuracy
        if (acc != null && acc <= GOOD_ACCURACY_M) {
            deliver(gen, bestFix, fromCache = false, failure = Failure.NO_FIX)
            return
        }
        if (!settleScheduled) {
            settleScheduled = true
            handler.postDelayed({
                deliver(gen, bestFix, fromCache = false, failure = Failure.NO_FIX)
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

    private fun stopLocationUpdates() {
        cancelSignals.forEach { runCatching { it.cancel() } }
        cancelSignals.clear()
        val lm = getSystemService(LocationManager::class.java)
        legacyListeners.forEach { runCatching { lm.removeUpdates(it) } }
        legacyListeners.clear()
    }

    /**
     * 结束一轮定位。location 为 null = 没拿到,按原因提醒/重试;
     * 否则为本轮所有待决请求各落一条(已有记录的跳过)。
     */
    private fun deliver(gen: Int, location: Location?, fromCache: Boolean, failure: Failure) {
        if (gen != generation || !locating) return
        locating = false
        handler.removeCallbacksAndMessages(null)
        stopLocationUpdates()
        val decisions = pending.toList()
        pending.clear()

        if (location == null) {
            decisions.forEach { onFailed(it, failure) }
            stopIfIdle()
            return
        }

        writing++
        val app = applicationContext
        Thread {
            var saved = false
            try {
                // 交叉验证：top-3 候选 + 行程连续性锚点 + 定位误差圈，
                // 消掉真实边界（深圳/香港、珠海/澳门…）附近的最近邻模糊。
                // 锚点 = 最近一条非改判打卡(当日有手动更正则以更正为准),防粘滞链自续期。
                val db = PunchDb.get(app)
                val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null
                val candidates = Cities.get(app).nearestByCity(location.latitude, location.longitude, 3)
                // 锚点只取不晚于现在的记录(系统时间被拨到未来时打下的那条不能当锚)
                val prev = db.latestAnchorPunch(System.currentTimeMillis())?.let { anchor ->
                    val key = db.overrideFor(anchor.localDate)?.cityKey ?: anchor.cityKey
                    CityResolver.Prev(
                        cityKey = key,
                        ageHours = (System.currentTimeMillis() - anchor.epochMs) / 3_600_000.0,
                    )
                }
                val resolution = CityResolver.resolve(candidates, accuracy, prev)
                val match = resolution?.match
                val zone = ZoneId.systemDefault()
                val nowMs = System.currentTimeMillis()
                for (d in decisions) {
                    if (db.hasPunch(d.date, d.slot)) continue
                    val punch = Punch(
                        // 归属日期/时段/延迟一律取决策时刻:跨零点拿到的定位不会跑到第二天去
                        localDate = d.date,
                        slot = d.slot,
                        epochMs = nowMs,
                        zoneId = zone.id,
                        lat = location.latitude,
                        lng = location.longitude,
                        accuracyM = accuracy,
                        cityKey = match?.cityKey ?: "unknown",
                        cityName = match?.cityName ?: "未知位置",
                        delayed = d.delayed,
                        fromCache = fromCache,
                        viaContext = resolution?.viaContext == true,
                    )
                    if (db.insertPunch(punch)) {
                        saved = true
                        recordAttempt(d.slot, "ok", punch.cityName + if (fromCache) " · 缓存位置" else "")
                    }
                }
                if (saved) {
                    TernDaysWidgetProvider.updateAll(app)
                    DataBus.bump()
                    // 天数变了:看看有没有阈值接近 / 达到(同一窗口期只提醒一次)
                    runCatching { ThresholdAlerts.check(app) }
                }
            } catch (_: Exception) {
                // 落库线程不允许把整个进程带崩;失败提醒用户
                decisions.firstOrNull()?.let { recordAttempt(it.slot, "failed", "保存失败") }
                runCatching { notifyRemind("打卡保存失败", FAIL_TEXT) }
            } finally {
                handler.post {
                    writing--
                    if (saved) {
                        // 打上了:撤掉这个时段的重试与旧的失败提醒,别留着和首页矛盾的通知
                        PunchScheduler.cancelRetry(this)
                        if (failure == Failure.LOCATION_OFF) {
                            // 定位关着但有缓存:这次先记上了,提醒一次打开定位
                            decisions.firstOrNull()?.let {
                                remindOnce(it.date, it.slot, "off", OFF_TITLE, OFF_CACHED_TEXT)
                            }
                        } else {
                            getSystemService(NotificationManager::class.java).cancel(NOTIF_REMIND_ID)
                        }
                    }
                    if (pending.isNotEmpty() && !locating) requestLocation() else stopIfIdle()
                }
            }
        }.start()
    }

    private fun onFailed(d: Decision, failure: Failure) {
        when (failure) {
            Failure.LOCATION_OFF -> {
                recordAttempt(d.slot, "location_off", "系统定位服务已关闭")
                remindOnce(d.date, d.slot, "off", OFF_TITLE, OFF_TEXT, punchAction = true)
            }
            Failure.PERMISSION -> {
                recordAttempt(d.slot, "permission", "缺定位权限")
                remindOnce(d.date, d.slot, "perm", PERM_TITLE, PERM_TEXT)
            }
            Failure.NO_FIX -> {
                // 窗口内还有时间且这次不是重试:10 分钟后自己再试一次(只一次)
                val retryAt = PunchScheduler.scheduleRetry(this, d.date, d.slot, d.isRetry)
                recordAttempt(d.slot, "no_fix", "没拿到定位", retryAt)
                if (retryAt == null) {
                    remindOnce(d.date, d.slot, "fail", "${slotLabel(d.slot)}打卡失败", FAIL_TEXT, punchAction = true)
                }
            }
        }
    }

    /** 今日卡片的「最近一次尝试」 */
    private fun recordAttempt(slot: Slot?, result: String, detail: String, retryAtMs: Long? = null) {
        runCatching {
            Prefs.setLastAttempt(
                applicationContext,
                Prefs.Attempt(System.currentTimeMillis(), slot?.name ?: "", result, detail, retryAtMs),
            )
            DataBus.bump()
        }
    }

    /** 没有进行中的定位、落库与待决请求时才真正退出。 */
    private fun stopIfIdle() {
        if (locating || writing > 0 || pending.isNotEmpty()) return
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(lastStartId)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TernDays:punch")
                .apply { acquire(TIMEOUT_MS + 30_000L) }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, TernDaysApp.CHANNEL_PUNCH)
            .setSmallIcon(R.drawable.ic_stat_tern)
            .setContentTitle("正在记录当前城市…")
            .setContentIntent(Intents.openApp(this))
            .setOngoing(true)
            .build()

    /**
     * 同一天同一时段同一类原因只提醒一次:此前定位总开关关着时每 10 分钟响一次铃。
     */
    private fun remindOnce(
        date: LocalDate, slot: Slot?, kind: String, title: String, text: String, punchAction: Boolean = false,
    ) {
        val key = "$date|${slot?.name ?: "-"}|$kind"
        if (Prefs.lastRemindKey(this) == key) return
        Prefs.setLastRemindKey(this, key)
        notifyRemind(title, text, if (punchAction) slot else null)
    }

    /** punchSlot 非空时带「立即打卡」按钮,并记下是哪个时段(窗口关了再点要能说清楚) */
    private fun notifyRemind(title: String, text: String, punchSlot: Slot? = null) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_REMIND_ID, remindNotification(this, title, text, punchSlot))
    }

    companion object {
        private const val NOTIF_ID = 10
        const val NOTIF_REMIND_ID = 11
        private const val TIMEOUT_MS = 90_000L
        /** 收到第一个候选后再等这么久,给更准的定位源机会 */
        private const val SETTLE_MS = 12_000L
        /** 误差小于此值即视为够准,不再等待 */
        private const val GOOD_ACCURACY_M = 80f
        private const val CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        /** 由用户在前台触发(打开应用):此时「仅使用期间」的定位权限也够用 */
        const val EXTRA_FOREGROUND = "foreground"

        // 提醒文案统一在这里(此前三处各写一份,还混着半角逗号)
        private const val PERM_TITLE = "定位权限被关闭"
        private const val PERM_TEXT = "打卡需要「始终允许」定位权限，请到设置中重新开启"
        private const val BG_TITLE = "后台打卡需要「始终允许」"
        private const val BG_TEXT = "定位权限现在是「仅使用期间」，到点时应用在后台拿不到位置。请在系统设置里改为「始终允许」"
        private const val OFF_TITLE = "系统定位服务已关闭"
        private const val OFF_TEXT = "这次没能记录。打开系统「定位服务」后会恢复自动打卡；现在打开应用可立即补打"
        private const val OFF_CACHED_TEXT = "这次先用最近的位置记上了。请打开系统「定位服务」，否则之后会记不上"
        private const val FAIL_TEXT = "没拿到定位。打开应用会立即补打；已过窗口的日子可在首页点「纠正」手动指定城市"
        private const val LATE_TEXT = "系统把这次打卡推迟到了窗口之外。打开应用可查看，可在首页点「纠正」手动指定城市"

        /**
         * 失败提醒。punchAction:带一个「立即打卡」按钮——从通知交互启动的前台服务
         * 不受后台启动限制,也能拿到「仅使用期间」的定位,应用被杀之后也能一键补上。
         */
        private fun remindNotification(context: Context, title: String, text: String, punchSlot: Slot?): Notification {
            val b = NotificationCompat.Builder(context, TernDaysApp.CHANNEL_REMIND)
                .setSmallIcon(R.drawable.ic_stat_tern)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(Intents.openApp(context))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
            if (punchSlot != null) {
                val intent = Intent(context, PunchService::class.java)
                    .putExtra(EXTRA_FOREGROUND, true)
                    .putExtra(PunchScheduler.EXTRA_SLOT, punchSlot.name)
                val pi = PendingIntent.getForegroundService(
                    context, REQUEST_PUNCH_NOW, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                b.addAction(0, "立即打卡", pi)
            }
            return b.build()
        }

        private const val REQUEST_PUNCH_NOW = 3001

        private fun slotLabel(slot: Slot) = when (slot) {
            Slot.MORNING -> "早上 7 点"
            Slot.EVENING -> "下午 5 点"
            Slot.EXTRA -> "首点"
        }

        fun start(context: Context, slot: Slot?, isRetry: Boolean = false, fromForeground: Boolean = false) {
            val intent = Intent(context, PunchService::class.java)
            if (slot != null) intent.putExtra(PunchScheduler.EXTRA_SLOT, slot.name)
            if (isRetry) intent.putExtra(PunchScheduler.EXTRA_RETRY, true)
            if (fromForeground) intent.putExtra(EXTRA_FOREGROUND, true)
            if (Prefs.punchPaused(context)) return
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // 后台前台服务被系统限制（常见于国产 ROM）：退化为提醒,并给「立即打卡」按钮
                Prefs.setLastAttempt(
                    context,
                    Prefs.Attempt(System.currentTimeMillis(), slot?.name ?: "", "blocked", "被系统拦下了", null),
                )
                context.getSystemService(NotificationManager::class.java).notify(
                    NOTIF_REMIND_ID,
                    remindNotification(
                        context, "打卡被系统拦下了",
                        "点「立即打卡」完成这次记录，并在设置中开启自启动 / 后台运行",
                        punchSlot = slot,
                    ),
                )
            }
        }

        /**
         * 打开应用/开机/时区变化时调用：
         *  1. 处于打卡窗口内且该时段缺记录 → 补打该时段；
         *  2. 否则若从未有过任何记录（首次安装）→ 立即打一个「首点」（EXTRA），
         *     不占早/晚槽，只作所在半天的兜底样本。
         * @param fromForeground 用户打开应用时为 true(「仅使用期间」的定位也能用)
         */
        fun maybeBackfill(context: Context, fromForeground: Boolean = false) {
            if (!Prefs.onboardingDone(context) || Prefs.punchPaused(context)) return
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
                        slot != null -> start(app, slot, fromForeground = fromForeground)
                        !db.hasAnyPunch() -> start(app, Slot.EXTRA, fromForeground = fromForeground)
                    }
                } catch (_: Exception) {
                    // 补打检查失败不影响主流程,下次打开再试
                }
            }.apply { isDaemon = true }.start()
        }
    }
}
