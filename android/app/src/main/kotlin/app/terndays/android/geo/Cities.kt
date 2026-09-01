package app.terndays.android.geo

import android.content.Context
import app.terndays.core.CityMatcher

/** 内置离线城市库（assets/cities.tsv），进程内单例、首次使用时加载。 */
object Cities {
    @Volatile private var matcher: CityMatcher? = null

    fun get(context: Context): CityMatcher =
        matcher ?: synchronized(this) {
            matcher ?: context.applicationContext.assets.open("cities.tsv")
                .use(CityMatcher::load)
                .also { matcher = it }
        }
}
