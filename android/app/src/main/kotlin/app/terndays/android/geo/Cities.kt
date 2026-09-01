package app.terndays.android.geo

import android.content.Context
import app.terndays.android.Prefs
import app.terndays.android.db.PunchDb
import app.terndays.android.widget.TernDaysWidgetProvider
import app.terndays.core.CityMatcher
import java.util.concurrent.atomic.AtomicBoolean

/** 内置离线城市库（assets/cities.tsv），进程内单例、首次使用时加载。 */
object Cities {
    /** 随包城市库版本：每次重建 cities.tsv 时 +1，触发历史打卡按原始坐标重解析。 */
    const val DATASET_VERSION = 2

    @Volatile private var matcher: CityMatcher? = null
    private val remapping = AtomicBoolean(false)

    fun get(context: Context): CityMatcher =
        matcher ?: synchronized(this) {
            matcher ?: context.applicationContext.assets.open("cities.tsv")
                .use(CityMatcher::load)
                .also { matcher = it }
        }

    /**
     * 城市库升级后，用存下来的原始坐标把历史打卡重解析一遍（修正诸如
     * 深圳被判成香港的历史记录）。手动更正不动。后台线程执行，幂等。
     */
    fun reResolveHistoryIfNeeded(context: Context, onChanged: (Int) -> Unit = {}) {
        val app = context.applicationContext
        if (Prefs.datasetVersionResolved(app) >= DATASET_VERSION) return
        if (!remapping.compareAndSet(false, true)) return
        Thread {
            try {
                val m = get(app)
                val changed = PunchDb.get(app).remapCities { lat, lng ->
                    m.nearest(lat, lng)?.let { it.cityKey to it.cityName }
                }
                Prefs.setDatasetVersionResolved(app, DATASET_VERSION)
                if (changed > 0) {
                    TernDaysWidgetProvider.updateAll(app)
                    onChanged(changed)
                }
            } finally {
                remapping.set(false)
            }
        }.start()
    }
}
