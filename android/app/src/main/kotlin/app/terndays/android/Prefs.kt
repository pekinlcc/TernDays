package app.terndays.android

import android.content.Context
import app.terndays.core.WidgetStyle

object Prefs {
    private fun sp(context: Context) = context.getSharedPreferences("terndays", Context.MODE_PRIVATE)

    fun onboardingDone(context: Context): Boolean = sp(context).getBoolean("onboarding_done", false)

    fun setOnboardingDone(context: Context) {
        sp(context).edit().putBoolean("onboarding_done", true).apply()
    }

    /** 最近一次提醒的去重键「日期|时段|原因」:同一原因同一时段只提醒一次。 */
    fun lastRemindKey(context: Context): String? = sp(context).getString("last_remind_key", null)

    fun setLastRemindKey(context: Context, key: String) {
        sp(context).edit().putString("last_remind_key", key).apply()
    }

    /** 桌面小组件的底面外观（素面 / 系统材质 / 品牌渐变）。 */
    fun widgetStyle(context: Context): WidgetStyle =
        WidgetStyle.from(sp(context).getString("widget_style", null))

    fun setWidgetStyle(context: Context, style: WidgetStyle) {
        sp(context).edit().putString("widget_style", style.id).apply()
    }

    /** 历史打卡最近一次是用哪个版本的城市库解析的（用于升级后重解析）。 */
    fun datasetVersionResolved(context: Context): Int = sp(context).getInt("dataset_version_resolved", 1)

    fun setDatasetVersionResolved(context: Context, version: Int) {
        sp(context).edit().putInt("dataset_version_resolved", version).apply()
    }

    // ---- v0.12:暂停、最近一次尝试、阈值、备份 ----

    /** 暂停自动打卡(换了新手机后旧手机停用;或临时不想记录)。 */
    fun punchPaused(context: Context): Boolean = sp(context).getBoolean("punch_paused", false)

    fun setPunchPaused(context: Context, paused: Boolean) {
        sp(context).edit().putBoolean("punch_paused", paused).apply()
    }

    /**
     * 最近一次打卡尝试:今日卡片显示「最近一次尝试 07:02 · 早点 · 已记录 深圳」。
     * result:ok / no_fix / location_off / permission / needs_always / paused
     */
    data class Attempt(val atMs: Long, val slot: String, val result: String, val detail: String, val retryAtMs: Long?)

    fun lastAttempt(context: Context): Attempt? {
        val raw = sp(context).getString("last_attempt", null) ?: return null
        val f = raw.split('\u0001')
        if (f.size < 5) return null
        return Attempt(f[0].toLongOrNull() ?: return null, f[1], f[2], f[3], f[4].toLongOrNull())
    }

    fun setLastAttempt(context: Context, a: Attempt) {
        val raw = listOf(a.atMs.toString(), a.slot, a.result, a.detail, a.retryAtMs?.toString() ?: "")
            .joinToString("\u0001")
        sp(context).edit().putString("last_attempt", raw).apply()
    }

    /** 天数阈值(:core Thresholds.encode 的格式)。 */
    fun thresholds(context: Context): String? = sp(context).getString("thresholds", null)

    fun setThresholds(context: Context, encoded: String) {
        sp(context).edit().putString("thresholds", encoded).apply()
    }

    /** 已经提醒过的阈值键(Thresholds.Threshold.notifyKey),同一窗口期只提醒一次。 */
    fun thresholdNotified(context: Context): Set<String> =
        sp(context).getStringSet("threshold_notified", emptySet())?.toSet() ?: emptySet()

    fun addThresholdNotified(context: Context, key: String) {
        sp(context).edit().putStringSet("threshold_notified", thresholdNotified(context) + key).apply()
    }

    /** 上次加密备份的时间(0 = 从未备份)。 */
    fun lastBackupAt(context: Context): Long = sp(context).getLong("last_backup_at", 0L)

    fun setLastBackupAt(context: Context, ms: Long) {
        sp(context).edit().putLong("last_backup_at", ms).apply()
    }

    /** 清除本机数据时一并清掉的偏好(引导完成、外观等保留)。 */
    fun clearRecordState(context: Context) {
        sp(context).edit()
            .remove("last_attempt").remove("last_remind_key").remove("threshold_notified")
            .apply()
    }
}
