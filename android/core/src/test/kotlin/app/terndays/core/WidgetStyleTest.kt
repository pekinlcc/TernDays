package app.terndays.core

import kotlin.test.Test
import kotlin.test.assertEquals

class WidgetStyleTest {

    @Test
    fun `按 id 解析`() {
        assertEquals(WidgetStyle.PLAIN, WidgetStyle.from("PLAIN"))
        assertEquals(WidgetStyle.MATERIAL, WidgetStyle.from("MATERIAL"))
        assertEquals(WidgetStyle.GRADIENT, WidgetStyle.from("GRADIENT"))
    }

    @Test
    fun `未知值与空值回落到默认,不抛异常`() {
        assertEquals(WidgetStyle.DEFAULT, WidgetStyle.from(null))
        assertEquals(WidgetStyle.DEFAULT, WidgetStyle.from(""))
        assertEquals(WidgetStyle.DEFAULT, WidgetStyle.from("plain"))       // 大小写不宽容:只认规范 id
        assertEquals(WidgetStyle.DEFAULT, WidgetStyle.from("GLASS"))       // 未来版本写入的新值
        assertEquals(WidgetStyle.PLAIN, WidgetStyle.DEFAULT)
    }

    @Test
    fun `id 与枚举名一致,双端可直接互认`() {
        WidgetStyle.entries.forEach { assertEquals(it.name, it.id) }
    }
}
