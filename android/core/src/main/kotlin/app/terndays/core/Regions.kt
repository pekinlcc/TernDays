package app.terndays.core

/**
 * 按国家 / 地区汇总(cityKey 前缀即 ISO 国家码)。中国大陆、香港、澳门、台湾分开统计——
 * 出入境与税务口径上它们是不同的地区。
 */
object Regions {

    data class RegionStat(val code: String, val name: String, val days: Double, val cities: Int)

    fun codeOf(cityKey: String): String = cityKey.substringBefore(':')

    fun nameOf(code: String): String = when (code) {
        "CN" -> "中国大陆"
        "HK" -> "中国香港"
        "MO" -> "中国澳门"
        "TW" -> "中国台湾"
        else -> CityMatcher.countryName(code)
    }

    fun summarize(stats: YearStats): List<RegionStat> =
        stats.cities
            .filter { it.cityKey != "unknown" }
            .groupBy { codeOf(it.cityKey) }
            .map { (code, cities) -> RegionStat(code, nameOf(code), cities.sumOf { it.days }, cities.size) }
            .sortedWith(compareByDescending<RegionStat> { it.days }.thenBy { it.code })
}
