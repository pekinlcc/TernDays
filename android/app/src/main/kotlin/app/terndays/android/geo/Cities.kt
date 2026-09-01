package app.terndays.android.geo

import android.content.Context
import app.terndays.android.DataBus
import app.terndays.android.Prefs
import app.terndays.android.db.PunchDb
import app.terndays.android.widget.TernDaysWidgetProvider
import app.terndays.core.CityMatcher
import java.util.concurrent.atomic.AtomicBoolean

/** 内置离线城市库（assets/cities.tsv），进程内单例、首次使用时加载。 */
object Cities {
    /** 随包城市库版本：每次重建 cities.tsv 时 +1，触发历史打卡按原始坐标重解析。 */
    const val DATASET_VERSION = 3

    @Volatile private var matcher: CityMatcher? = null
    private val remapping = AtomicBoolean(false)

    fun get(context: Context): CityMatcher =
        matcher ?: synchronized(this) {
            matcher ?: context.applicationContext.assets.open("cities.tsv")
                .use(CityMatcher::load)
                .also { matcher = it }
        }

    /**
     * 城市库升级后，把历史打卡按时间**重放**一遍——每条都走与实时打卡相同的
     * 交叉验证（HistoryReplay），而不是裸最近邻,不会逆转此前依据上下文的正确改判。
     * 手动更正不动。后台线程执行，幂等。
     */
    fun reResolveHistoryIfNeeded(context: Context, onChanged: (Int) -> Unit = {}) {
        val app = context.applicationContext
        if (Prefs.datasetVersionResolved(app) >= DATASET_VERSION) return
        if (!remapping.compareAndSet(false, true)) return
        Thread {
            try {
                val changed = PunchDb.get(app).replayResolveAll(get(app))
                Prefs.setDatasetVersionResolved(app, DATASET_VERSION)
                if (changed > 0) {
                    TernDaysWidgetProvider.updateAll(app)
                    DataBus.bump()
                    onChanged(changed)
                }
            } catch (_: Exception) {
                // 版本号不写,下次启动重试
            } finally {
                remapping.set(false)
            }
        }.apply { isDaemon = true }.start()
    }
}
