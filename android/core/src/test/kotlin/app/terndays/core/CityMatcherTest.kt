package app.terndays.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityMatcherTest {

    private val tsv = listOf(
        "39.90750\t116.39723\tCN:北京\t北京",
        "39.08333\t117.18000\tCN:天津\t天津",
        "31.22222\t121.45806\tCN:上海\t上海",
        "30.29365\t120.16142\tCN:杭州\t杭州",
        "30.86828\t120.09433\tCN:湖州\t湖州",
        "35.68950\t139.69171\tJP:40:Tokyo\t东京",
    ).joinToString("\n", postfix = "\n")

    private val matcher = CityMatcher.load(tsv.byteInputStream())

    @Test
    fun `加载与最近邻`() {
        assertEquals(6, matcher.size)
        val m = matcher.nearest(39.99, 116.47)!!   // 望京
        assertEquals("CN:北京", m.cityKey)
        assertEquals("北京", m.cityName)
        assertTrue(m.distanceKm < 15)

        assertEquals("东京", matcher.nearest(35.67, 139.76)!!.cityName)
        assertEquals("杭州", matcher.nearest(30.25, 120.15)!!.cityName)
    }

    @Test
    fun `按名称搜索去重`() {
        val results = matcher.searchByName("州")
        assertEquals(listOf("CN:杭州" to "杭州", "CN:湖州" to "湖州"), results)
        assertTrue(matcher.searchByName("").isEmpty())
        assertEquals(listOf("JP:40:Tokyo" to "东京"), matcher.searchByName("tokyo"))
    }

    @Test
    fun `haversine 距离`() {
        // 北京-上海 大圆距离约 1067km
        val d = CityMatcher.haversineKm(39.9075, 116.39723, 31.22222, 121.45806)
        assertTrue(d in 1050.0..1090.0, "actual: $d")
    }
}
