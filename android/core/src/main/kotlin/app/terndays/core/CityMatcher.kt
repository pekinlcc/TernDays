package app.terndays.core

import java.io.BufferedReader
import java.io.InputStream
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 离线反地理编码：对内置城市点云做最近邻。
 * 数据格式（TSV）：lat \t lng \t key \t display [\t 拼音别名]，见 tools/build_city_dataset.py。
 */
class CityMatcher private constructor(
    private val lats: DoubleArray,
    private val lngs: DoubleArray,
    private val keys: Array<String>,
    private val names: Array<String>,
    private val aliases: Array<String>,
) {
    data class Match(val cityKey: String, val cityName: String, val distanceKm: Double)

    /** 搜索命中:region 用于区分重名城市(如加拿大伦敦 vs 英国伦敦),中国城市为空。 */
    data class SearchHit(val cityKey: String, val cityName: String, val region: String)

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

    /** 按城市去重的前 k 个最近候选（每城取其最近点位），距离升序。供边界交叉验证用。 */
    fun nearestByCity(lat: Double, lng: Double, k: Int = 3): List<Match> {
        if (lats.isEmpty() || k <= 0) return emptyList()
        val cand = ArrayList<Match>(k + 1)
        for (i in lats.indices) {
            val d = haversineKm(lat, lng, lats[i], lngs[i])
            val worst = if (cand.size < k) Double.MAX_VALUE else cand.last().distanceKm
            if (d >= worst) continue
            val existing = cand.indexOfFirst { it.cityKey == keys[i] }
            if (existing >= 0) {
                if (d < cand[existing].distanceKm) {
                    cand[existing] = Match(keys[i], names[i], d)
                    cand.sortBy { it.distanceKm }
                }
            } else {
                cand.add(Match(keys[i], names[i], d))
                cand.sortBy { it.distanceKm }
                if (cand.size > k) cand.removeAt(cand.size - 1)
            }
        }
        return cand
    }

    /** 手动补记/更正时按名称搜索：全等 > 前缀 > 包含,中国城市优先;拼音可搜;带重名消歧地区。 */
    fun search(query: String, limit: Int = 20): List<SearchHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        // rank: 0 全等 / 1 前缀 / 2 包含;每城取最好命中
        val best = HashMap<String, Int>()
        val nameOf = HashMap<String, String>()
        for (i in keys.indices) {
            val name = names[i].lowercase()
            val alias = aliases[i]
            val cityPart = keys[i].substringAfterLast(':').lowercase()
            val rank = when {
                name == q || alias == q || cityPart == q -> 0
                name.startsWith(q) || alias.startsWith(q) || cityPart.startsWith(q) -> 1
                name.contains(q) || (alias.isNotEmpty() && alias.contains(q)) || cityPart.contains(q) -> 2
                else -> continue
            }
            val prev = best[keys[i]]
            if (prev == null || rank < prev) {
                best[keys[i]] = rank
                nameOf[keys[i]] = names[i]
            }
        }
        return best.entries
            .sortedWith(compareBy({ it.value }, { if (isDomestic(it.key)) 0 else 1 }, { it.key }))
            .take(limit)
            .map { SearchHit(it.key, nameOf[it.key]!!, regionOf(it.key)) }
    }

    /** 旧签名兼容:等价 search() 去掉 region。 */
    fun searchByName(query: String, limit: Int = 20): List<Pair<String, String>> =
        search(query, limit).map { it.cityKey to it.cityName }

    companion object {
        private fun isDomestic(key: String) =
            key.startsWith("CN:") || key.startsWith("HK:") || key.startsWith("MO:")

        /** 重名消歧:境外城市给出国家(+一级行政区码),中国城市无需消歧返回空。 */
        fun regionOf(key: String): String {
            if (isDomestic(key)) return ""
            val cc = key.substringBefore(':')
            val country = CC_NAMES[cc] ?: cc
            val admin1 = key.substringAfter(':').substringBefore(':')
            return if (admin1.isEmpty() || admin1 == cc) country else "$country·$admin1"
        }

        /** 常见国家码 → 中文名(仅搜索消歧展示用,未覆盖的显示原码)。 */
        private val CC_NAMES = mapOf(
            "SG" to "新加坡", "JP" to "日本", "KR" to "韩国", "TH" to "泰国", "MY" to "马来西亚",
            "ID" to "印尼", "VN" to "越南", "PH" to "菲律宾", "IN" to "印度", "US" to "美国",
            "CA" to "加拿大", "MX" to "墨西哥", "BR" to "巴西", "AR" to "阿根廷", "GB" to "英国",
            "FR" to "法国", "DE" to "德国", "IT" to "意大利", "ES" to "西班牙", "PT" to "葡萄牙",
            "NL" to "荷兰", "BE" to "比利时", "CH" to "瑞士", "AT" to "奥地利", "SE" to "瑞典",
            "NO" to "挪威", "DK" to "丹麦", "FI" to "芬兰", "RU" to "俄罗斯", "TR" to "土耳其",
            "AU" to "澳大利亚", "NZ" to "新西兰", "AE" to "阿联酋", "SA" to "沙特", "QA" to "卡塔尔",
            "EG" to "埃及", "ZA" to "南非", "KH" to "柬埔寨", "LA" to "老挝", "MM" to "缅甸",
            "NP" to "尼泊尔", "LK" to "斯里兰卡", "PK" to "巴基斯坦", "BD" to "孟加拉",
            "IE" to "爱尔兰", "PL" to "波兰", "CZ" to "捷克", "HU" to "匈牙利", "GR" to "希腊",
            "IL" to "以色列", "KZ" to "哈萨克斯坦", "MN" to "蒙古", "TW" to "台湾",
        )

        fun load(input: InputStream): CityMatcher {
            val lats = ArrayList<Double>(36000)
            val lngs = ArrayList<Double>(36000)
            val keys = ArrayList<String>(36000)
            val names = ArrayList<String>(36000)
            val aliases = ArrayList<String>(36000)
            BufferedReader(input.reader(Charsets.UTF_8)).useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty()) continue
                    val f = line.split('\t')
                    if (f.size < 4) continue
                    // 单行损坏只跳过该行,不让整个城市库加载失败
                    val lat = f[0].toDoubleOrNull() ?: continue
                    val lng = f[1].toDoubleOrNull() ?: continue
                    if (lat !in -90.0..90.0 || lng !in -180.0..180.0) continue
                    if (f[2].isEmpty() || f[3].isEmpty()) continue
                    lats.add(lat)
                    lngs.add(lng)
                    keys.add(f[2])
                    names.add(f[3])
                    aliases.add(if (f.size >= 5) f[4].lowercase() else "")
                }
            }
            return CityMatcher(
                lats.toDoubleArray(), lngs.toDoubleArray(),
                keys.toTypedArray(), names.toTypedArray(), aliases.toTypedArray(),
            )
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
