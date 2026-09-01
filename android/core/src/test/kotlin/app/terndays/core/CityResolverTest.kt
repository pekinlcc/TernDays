package app.terndays.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CityResolverTest {

    private fun m(key: String, name: String, d: Double) = CityMatcher.Match(key, name, d)
    private val sz = "CN:深圳"
    private val hk = "HK:香港"

    private fun prevSz(age: Double = 14.0) = CityResolver.Prev(sz, age)

    @Test
    fun `无上次记录取最近`() {
        val r = CityResolver.resolve(listOf(m(hk, "香港", 0.9), m(sz, "深圳", 1.0)), 30.0, null)!!
        assertEquals(hk, r.match.cityKey)
        assertFalse(r.viaContext)
        assertTrue(r.ambiguous)
    }

    @Test
    fun `上次城市即最近候选`() {
        val r = CityResolver.resolve(listOf(m(sz, "深圳", 0.2), m(hk, "香港", 0.8)), 30.0, prevSz())!!
        assertEquals(sz, r.match.cityKey)
        assertFalse(r.viaContext)
    }

    @Test
    fun `口岸模糊区按行程连续性粘住上次城市`() {
        // 人在福田口岸深圳侧，GPS 漂到河边：香港 0.9km / 深圳 1.0km
        val r = CityResolver.resolve(listOf(m(hk, "香港", 0.9), m(sz, "深圳", 1.0)), 30.0, prevSz())!!
        assertEquals(sz, r.match.cityKey)
        assertTrue(r.viaContext)
        assertTrue(r.ambiguous)
    }

    @Test
    fun `真正进入邻城后边距拉大不再粘`() {
        // 过关到旺角：香港 0.1km / 深圳 16km
        val r = CityResolver.resolve(listOf(m(hk, "香港", 0.1), m(sz, "深圳", 16.0)), 30.0, prevSz())!!
        assertEquals(hk, r.match.cityKey)
        assertFalse(r.viaContext)
        assertFalse(r.ambiguous)
    }

    @Test
    fun `top1 极近为明确证据不被上次的错误记录带偏`() {
        // 上次错判成香港，如今人明确落在深圳锚点上（0.2km）
        val prevHk = CityResolver.Prev(hk, 14.0)
        val r = CityResolver.resolve(listOf(m(sz, "深圳", 0.2), m(hk, "香港", 0.8)), 30.0, prevHk)!!
        assertEquals(sz, r.match.cityKey)
        assertFalse(r.viaContext)
    }

    @Test
    fun `公里级定位误差时误差圈内沿用上次城市`() {
        // 基站定位把人漂过深圳河：香港 0.5km / 深圳 2.3km，误差 2000m
        val r = CityResolver.resolve(listOf(m(hk, "香港", 0.5), m(sz, "深圳", 2.3)), 2000.0, prevSz())!!
        assertEquals(sz, r.match.cityKey)
        assertTrue(r.viaContext)
    }

    @Test
    fun `公里级误差但上次城市远超误差圈仍取最近`() {
        val r = CityResolver.resolve(listOf(m(hk, "香港", 0.5), m(sz, "深圳", 28.0)), 2000.0, prevSz())!!
        assertEquals(hk, r.match.cityKey)
        assertFalse(r.viaContext)
    }

    @Test
    fun `定位误差放宽模糊边距`() {
        val cands = listOf(m(hk, "香港", 1.0), m(sz, "深圳", 3.2))
        // 高精度：边距 2.2km > 1.5km，不粘
        assertEquals(hk, CityResolver.resolve(cands, 50.0, prevSz())!!.match.cityKey)
        // 900m 误差：边距阈值 2.4km，粘住深圳
        val r = CityResolver.resolve(cands, 900.0, prevSz())!!
        assertEquals(sz, r.match.cityKey)
        assertTrue(r.viaContext)
    }

    @Test
    fun `上次记录过旧或不在候选中都取最近`() {
        val cands = listOf(m(hk, "香港", 0.9), m(sz, "深圳", 1.0))
        assertEquals(hk, CityResolver.resolve(cands, 30.0, prevSz(age = 48.0))!!.match.cityKey)
        val prevBj = CityResolver.Prev("CN:北京", 14.0)
        assertEquals(hk, CityResolver.resolve(cands, 30.0, prevBj)!!.match.cityKey)
    }

    @Test
    fun `空候选返回空`() {
        assertNull(CityResolver.resolve(emptyList(), 30.0, prevSz()))
    }
}
