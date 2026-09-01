package app.terndays.core

import java.io.BufferedReader
import java.io.InputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 离线反地理编码：对内置城市点云做最近邻。
 * 数据格式（TSV）：lat \t lng \t key \t display，见 tools/build_city_dataset.py。
 */
class CityMatcher private constructor(
    private val lats: DoubleArray,
    private val lngs: DoubleArray,
    private val keys: Array<String>,
    private val names: Array<String>,
) {
    data class Match(val cityKey: String, val cityName: String, val distanceKm: Double)

    val size: Int get() = lats.size

    fun nearest(lat: Double, lng: Double): Match? {
        if (lats.isEmpty()) return null
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in lats.indices) {
            val d = haversineKm(lat, lng, lats[i], lngs[i])
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return Match(keys[best], names[best], bestD)
    }

    /** 手动补记时按名称搜索（去重后的城市列表，最多 limit 个）。 */
    fun searchByName(query: String, limit: Int = 20): List<Pair<String, String>> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, String>()
        for (i in keys.indices) {
            if (names[i].contains(q, ignoreCase = true) || keys[i].contains(q, ignoreCase = true)) {
                out.putIfAbsent(keys[i], names[i])
                if (out.size >= limit) break
            }
        }
        return out.map { it.key to it.value }
    }

    companion object {
        fun load(input: InputStream): CityMatcher {
            val lats = ArrayList<Double>(36000)
            val lngs = ArrayList<Double>(36000)
            val keys = ArrayList<String>(36000)
            val names = ArrayList<String>(36000)
            BufferedReader(input.reader(Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty()) continue
                    val t1 = line.indexOf('\t')
                    val t2 = line.indexOf('\t', t1 + 1)
                    val t3 = line.indexOf('\t', t2 + 1)
                    if (t1 < 0 || t2 < 0 || t3 < 0) continue
                    lats.add(line.substring(0, t1).toDouble())
                    lngs.add(line.substring(t1 + 1, t2).toDouble())
                    keys.add(line.substring(t2 + 1, t3))
                    names.add(line.substring(t3 + 1))
                }
            }
            return CityMatcher(lats.toDoubleArray(), lngs.toDoubleArray(), keys.toTypedArray(), names.toTypedArray())
        }

        fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r1 = Math.toRadians(lat1)
            val r2 = Math.toRadians(lat2)
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) + cos(r1) * cos(r2) * sin(dLng / 2) * sin(dLng / 2)
            return 6371.0 * 2 * asin(sqrt(a))
        }
    }
}
