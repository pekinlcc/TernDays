package app.terndays.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 对随包发布的真实离线城市库（app/src/main/assets/cities.tsv）做集成校验。 */
class CityDatasetTest {

    companion object {
        private val file = File("../app/src/main/assets/cities.tsv")
        private val matcher: CityMatcher by lazy { file.inputStream().use(CityMatcher::load) }
    }

    @Test
    fun `数据文件存在且规模正常`() {
        assertTrue(file.exists(), "缺少 ${file.path}（用 tools/build_city_dataset.py 生成）")
        assertTrue(matcher.size in 30_000..40_000, "行数异常: ${matcher.size}")
    }

    @Test
    fun `数据行格式合法`() {
        var lines = 0
        file.forEachLine { line ->
            if (line.isEmpty()) return@forEachLine
            lines++
            val f = line.split('\t')
            assertTrue(f.size in 4..5, "字段数异常: $line")
            val lat = f[0].toDouble()
            val lng = f[1].toDouble()
            assertTrue(lat in -90.0..90.0 && lng in -180.0..180.0, "坐标越界: $line")
            assertTrue(f[2].isNotBlank() && f[3].isNotBlank(), "空字段: $line")
        }
        assertEquals(lines, matcher.size, "解析行数与文件行数不一致")
    }

    /** 同一 cityKey 的所有行 display 必须一致(曾有 8 组 key 被两座不同城市共用)。 */
    @Test
    fun `同 key 无歧义`() {
        val names = HashMap<String, String>()
        file.forEachLine { line ->
            if (line.isEmpty()) return@forEachLine
            val f = line.split('\t')
            val prev = names.put(f[2], f[3])
            assertTrue(prev == null || prev == f[3], "key ${f[2]} 有两个名字: $prev / ${f[3]}")
        }
    }

    @Test
    fun `关键坐标解析到正确城市`() {
        val cases = listOf(
            Triple(30.243, 120.15, "杭州"),    // 杭州西湖
            Triple(39.908, 116.46, "北京"),    // 北京国贸
            Triple(22.540, 114.06, "深圳"),    // 深圳福田
            Triple(31.385, 120.98, "苏州"),    // 昆山（县级市归并到苏州）
            Triple(39.520, 116.70, "廊坊"),    // 廊坊市区
            Triple(22.197, 113.541, "澳门"),   // 澳门大三巴
            Triple(22.281, 114.158, "香港"),   // 香港中环
            Triple(35.672, 139.765, "东京"),   // 东京银座
            Triple(34.700, 135.50, "大阪"),    // 大阪梅田
            Triple(1.300, 103.85, "新加坡"),
        )
        for ((lat, lng, expected) in cases) {
            val m = matcher.nearest(lat, lng)!!
            assertEquals(expected, m.cityName, "($lat, $lng) -> ${m.cityName}，期望 $expected")
            assertTrue(m.distanceKm < 30, "($lat, $lng) 距离异常: ${m.distanceKm}km")
        }
    }

    @Test
    fun `名称搜索可用`() {
        val hits = matcher.searchByName("杭州")
        assertTrue(hits.any { it.second == "杭州" }, "搜索『杭州』无结果")
    }

    /** 深港/珠澳边界回归：v0.4 前救援框吞点 + 锚点密度失衡曾把深圳判成香港。 */
    @Test
    fun `深港珠澳边界两侧判定正确`() {
        val cases = listOf(
            // 深圳侧
            Triple(22.4819, 113.9160, "深圳"),  // 蛇口
            Triple(22.5280, 113.8930, "深圳"),  // 前海
            Triple(22.5080, 113.9430, "深圳"),  // 深圳湾口岸
            Triple(22.5185, 114.0670, "深圳"),  // 福田口岸
            Triple(22.5320, 114.1130, "深圳"),  // 罗湖口岸
            Triple(22.5570, 114.2370, "深圳"),  // 盐田港
            Triple(22.6395, 113.8145, "深圳"),  // 宝安机场
            // 香港侧
            Triple(22.5110, 114.0655, "香港"),  // 落马洲
            Triple(22.5010, 114.1280, "香港"),  // 上水
            Triple(22.4445, 114.0225, "香港"),  // 元朗
            Triple(22.3908, 113.9725, "香港"),  // 屯门
            // 珠澳
            Triple(22.2200, 113.5520, "珠海"),  // 拱北口岸广场
            Triple(22.1300, 113.5450, "珠海"),  // 横琴东岸
            Triple(22.2130, 113.5490, "澳门"),  // 关闸
            Triple(22.1460, 113.5590, "澳门"),  // 路氹城
        )
        for ((lat, lng, expected) in cases) {
            val m = matcher.nearest(lat, lng)!!
            assertEquals(expected, m.cityName, "($lat, $lng) -> ${m.cityName}，期望 $expected")
        }
    }

    /** 环核心城市卫星城(v0.6.1):此前整片被判给相邻核心城市。 */
    @Test
    fun `卫星城按行政归属判定`() {
        val cases = listOf(
            Triple(39.947, 116.800, "廊坊"),   // 燕郊
            Triple(39.886, 116.989, "廊坊"),   // 大厂
            Triple(39.909, 116.656, "北京"),   // 通州(京/苏撞名曾被词典丢弃)
            Triple(31.257, 121.100, "苏州"),   // 花桥
            Triple(31.297, 121.166, "上海"),   // 安亭
            Triple(23.104, 113.211, "佛山"),   // 黄岐
            Triple(23.157, 113.194, "佛山"),   // 里水
            Triple(23.160, 113.215, "广州"),   // 金沙洲
            Triple(1.437, 103.786, "新加坡"),  // 兀兰(城市国家整体一城)
            Triple(37.775, -122.419, "旧金山"),
            Triple(40.728, -74.078, "泽西城"), // 曾被并进纽约
        )
        for ((lat, lng, expected) in cases) {
            val m = matcher.nearest(lat, lng)!!
            assertEquals(expected, m.cityName, "($lat, $lng) -> ${m.cityName}，期望 $expected")
        }
    }
}
