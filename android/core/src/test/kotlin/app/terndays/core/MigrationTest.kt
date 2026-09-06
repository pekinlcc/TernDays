package app.terndays.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {

    private val punch = Punch(
        localDate = LocalDate.of(2026, 9, 1),
        slot = Slot.MORNING,
        epochMs = 1_756_700_000_000L,
        zoneId = "Asia/Shanghai",
        lat = 22.5455,
        lng = 114.0545,
        accuracyM = 30.0,
        cityKey = "CN:深圳",
        cityName = "深圳",
        delayed = false,
        fromCache = false,
    )
    private val extra = punch.copy(
        slot = Slot.EXTRA, accuracyM = null, delayed = true, fromCache = true,
        cityKey = "HK:香港", cityName = "香港",
    )
    private val override = DayOverride(LocalDate.of(2026, 9, 1), "CN:深圳", "深圳")

    @Test
    fun `交换格式 round-trip`() {
        val json = MigrationCodec.toJson(2, 123L, listOf(punch, extra), listOf(override))
        val p = MigrationCodec.parse(json)
        assertEquals(1, p.formatVersion)
        assertEquals(2, p.datasetVersion)
        assertEquals(123L, p.exportedAtMs)
        assertEquals(listOf(punch, extra), p.punches)
        assertEquals(listOf(override), p.overrides)
        // Swift 合成解码器要求 delayed/fromCache 键恒在
        assertTrue(json.contains("\"delayed\""))
        assertTrue(json.contains("\"fromCache\""))
        // accuracyM 为空时省略
        assertEquals(1, Regex("\"accuracyM\"").findAll(json).count())
    }

    @Test
    fun `空数据与坏输入`() {
        val empty = MigrationCodec.parse(MigrationCodec.toJson(2, 0L, emptyList(), emptyList()))
        assertTrue(empty.punches.isEmpty() && empty.overrides.isEmpty())
        assertFailsWith<IllegalArgumentException> { MigrationCodec.parse("not json") }
        assertFailsWith<IllegalArgumentException> { MigrationCodec.parse("""{"app":"Other","format":1}""") }
        assertFailsWith<IllegalArgumentException> { MigrationCodec.parse("""{"app":"TernDays","format":99}""") }
        assertFailsWith<IllegalArgumentException> {
            MigrationCodec.parse("""{"app":"TernDays","format":1,"punches":[{"localDate":"bad"}]}""")
        }
    }

    @Test
    fun `加密 round-trip 与防篡改`() {
        val key = MigrationCrypto.newKey()
        val plain = MigrationCodec.toJson(2, 1L, listOf(punch), listOf(override)).toByteArray()
        val blob = MigrationCrypto.seal(key, plain)
        assertTrue(blob.size >= plain.size + 28) // 12B nonce + 16B tag
        assertTrue(MigrationCrypto.open(key, blob).contentEquals(plain))

        val tampered = blob.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFailsWith<IllegalArgumentException> { MigrationCrypto.open(key, tampered) }
        assertFailsWith<IllegalArgumentException> { MigrationCrypto.open(MigrationCrypto.newKey(), blob) }
        // 每次 seal 的 nonce 不同
        assertTrue(!MigrationCrypto.seal(key, plain).contentEquals(blob))
    }

    @Test
    fun `密钥指纹稳定`() {
        val key = MigrationCrypto.newKey()
        assertTrue(MigrationCrypto.fingerprint(key).contentEquals(MigrationCrypto.fingerprint(key)))
        assertEquals(32, MigrationCrypto.fingerprint(key).size)
    }

    @Test
    fun `二维码链接 round-trip`() {
        val key = MigrationCrypto.newKey()
        val uri = MigrationLink.build(listOf("192.168.1.5", "10.0.0.3"), 52731, key)
        val link = MigrationLink.parse(uri)!!
        assertEquals(listOf("192.168.1.5", "10.0.0.3"), link.addresses)
        assertEquals(52731, link.port)
        assertTrue(link.key.contentEquals(key))
    }

    @Test
    fun `非迁移码一律返回空`() {
        assertNull(MigrationLink.parse("https://example.com"))
        assertNull(MigrationLink.parse("terndays://migrate?v=2&a=1.2.3.4&p=1&k=AA"))
        assertNull(MigrationLink.parse("terndays://migrate?v=1&a=&p=80&k=AA"))
        assertNull(MigrationLink.parse("terndays://migrate?v=1&a=1.2.3.4&p=99999&k=AA"))
        assertNull(MigrationLink.parse("terndays://migrate?v=1&a=1.2.3.4&p=80&k=!!!"))
        // 密钥长度不对
        assertNull(MigrationLink.parse("terndays://migrate?v=1&a=1.2.3.4&p=80&k=AAAA"))
    }

    @Test
    fun `二维码只接受局域网地址`() {
        val key = ByteArray(MigrationCrypto.KEY_BYTES) { it.toByte() }
        // 私网 / 链路本地可用
        for (a in listOf("192.168.1.7", "10.0.0.3", "172.20.1.1", "169.254.3.4", "127.0.0.1", "fe80::1", "fd12::9")) {
            assertTrue(MigrationLink.isLanAddress(a), a)
            assertNotNull(MigrationLink.parse(MigrationLink.build(listOf(a), 4321, key)))
        }
        // 公网地址一律拒绝(伪造二维码不能把应用引到外网主机)
        for (a in listOf("8.8.8.8", "203.0.113.9", "172.32.0.1", "2001:db8::1", "example.com")) {
            assertFalse(MigrationLink.isLanAddress(a), a)
        }
        assertNull(MigrationLink.parse(MigrationLink.build(listOf("8.8.8.8"), 4321, key)))
        // 混合时只保留局域网那条
        val mixed = MigrationLink.parse(MigrationLink.build(listOf("8.8.8.8", "192.168.0.5"), 4321, key))
        assertEquals(listOf("192.168.0.5"), mixed?.addresses)
    }
}
