package app.terndays.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityMatcherTest {

    private val tsv = listOf(
        "39.90750\t116.39723\tCN:北京\t北京\tbeijing",
        "39.08333\t117.18000\tCN:天津\t天津\ttianjin",
        "31.22222\t121.45806\tCN:上海\t上海\tshanghai",
        "30.29365\t120.16142\tCN:杭州\t杭州\thangzhou",
        "30.86828\t120.09433\tCN:湖州\t湖州\thuzhou",
        "35.68950\t139.69171\tJP:40:Tokyo\t东京",
        "42.98339\t-81.23304\tCA:08:London\t伦敦",
        "51.50853\t-0.12574\tGB:ENG:London\t伦敦",
        "bad\t116.4\tCN:坏行\t坏行",
    ).joinToString("\n", postfix = "\n")

    private val matcher = CityMatcher.load(tsv.byteInputStream())

    @Test
    fun `加载与最近邻`() {
        assertEquals(8, matcher.size) // 坏行被跳过,城市库不因单行损坏而加载失败
        val m = matcher.nearest(39.99, 116.47)!!   // 望京
        assertEquals("CN:北京", m.cityKey)
        assertEquals("北京", m.cityName)
        assertTrue(m.distanceKm < 15)

        assertEquals("东京", matcher.nearest(35.67, 139.76)!!.cityName)
        assertEquals("杭州", matcher.nearest(30.25, 120.15)!!.cityName)
    }

    @Test
    fun `按城市去重的前 k 候选`() {
        // 杭州西湖附近：杭州最近，湖州次之；同城多点只保留最近的一个
        val cands = matcher.nearestByCity(30.25, 120.15, 3)
        assertEquals(3, cands.size)
        assertEquals("CN:杭州", cands[0].cityKey)
        assertEquals("CN:湖州", cands[1].cityKey)
        assertTrue(cands[0].distanceKm < cands[1].distanceKm)
        assertEquals(1, cands.map { it.cityKey }.groupBy { it }.maxOf { it.value.size })
        assertEquals(1, matcher.nearestByCity(30.25, 120.15, 1).size)
        assertTrue(matcher.nearestByCity(30.25, 120.15, 0).isEmpty())
    }

    @Test
    fun `按名称搜索去重`() {
        val results = matcher.searchByName("州")
        assertEquals(listOf("CN:杭州" to "杭州", "CN:湖州" to "湖州"), results)
        assertTrue(matcher.searchByName("").isEmpty())
        assertEquals(listOf("JP:40:Tokyo" to "东京"), matcher.searchByName("tokyo"))
    }

    @Test
    fun `拼音可搜且全等优先`() {
        assertEquals("CN:北京", matcher.search("beijing").first().cityKey)
        assertEquals("CN:上海", matcher.search("shanghai").first().cityKey)
        // "hangzhou" 全等命中杭州;"h" 前缀命中杭州/湖州
        assertEquals("CN:杭州", matcher.search("hangzhou").first().cityKey)
        assertTrue(matcher.search("hu").any { it.cityKey == "CN:湖州" })
    }

    @Test
    fun `重名城市带地区消歧`() {
        val hits = matcher.search("伦敦")
        assertEquals(2, hits.size)
        val regions = hits.map { it.region }.toSet()
        assertTrue("英国·ENG" in regions && "加拿大·08" in regions, "实际: $regions")
        // 中国城市不需要消歧
        assertEquals("", matcher.search("北京").first().region)
    }

    @Test
    fun `haversine 距离`() {
        // 北京-上海 大圆距离约 1067km
        val d = CityMatcher.haversineKm(39.9075, 116.39723, 31.22222, 121.45806)
        assertTrue(d in 1050.0..1090.0, "actual: $d")
    }
}
