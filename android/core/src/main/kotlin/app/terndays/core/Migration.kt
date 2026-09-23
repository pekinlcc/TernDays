package app.terndays.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 换手机数据迁移的跨端交换格式（Android ↔ iOS 通用）。
 * 与 iOS 端 Codable 天然对齐：localDate 编码为 "yyyy-MM-dd"，slot 用 MORNING/EVENING/EXTRA，
 * delayed/fromCache 恒写出（Swift 合成解码器要求键存在），accuracyM 缺省可省略。
 */
object MigrationCodec {
    const val APP = "TernDays"
    /** v2 新增 override.scope(半天更正)。仅当数据里真的有半天更正才标 2,老版本仍可接收全天数据。 */
    const val FORMAT = 2

    data class Payload(
        val formatVersion: Int,
        val datasetVersion: Int,
        val exportedAtMs: Long,
        val punches: List<Punch>,
        val overrides: List<DayOverride>,
    )

    fun toJson(datasetVersion: Int, exportedAtMs: Long, punches: List<Punch>, overrides: List<DayOverride>): String {
        val root = JSONObject()
        root.put("app", APP)
        val needsV2 = overrides.any { it.scope != OverrideScope.FULL }
        root.put("format", if (needsV2) 2 else 1)
        root.put("datasetVersion", datasetVersion)
        root.put("exportedAtMs", exportedAtMs)
        val pArr = JSONArray()
        for (p in punches) {
            val o = JSONObject()
            o.put("localDate", p.localDate.toString())
            o.put("slot", p.slot.name)
            o.put("epochMs", p.epochMs)
            o.put("zoneId", p.zoneId)
            o.put("lat", p.lat)
            o.put("lng", p.lng)
            if (p.accuracyM != null) o.put("accuracyM", p.accuracyM)
            o.put("cityKey", p.cityKey)
            o.put("cityName", p.cityName)
            o.put("delayed", p.delayed)
            o.put("fromCache", p.fromCache)
            if (p.viaContext) o.put("viaContext", true)
            pArr.put(o)
        }
        root.put("punches", pArr)
        val oArr = JSONArray()
        for (ov in overrides) {
            val o = JSONObject()
            o.put("localDate", ov.localDate.toString())
            o.put("cityKey", ov.cityKey)
            o.put("cityName", ov.cityName)
            if (ov.scope != OverrideScope.FULL) o.put("scope", ov.scope.name)
            oArr.put(o)
        }
        root.put("overrides", oArr)
        return root.toString()
    }

    /** @throws IllegalArgumentException 非本应用数据 / 格式不兼容 / 字段损坏 */
    fun parse(json: String): Payload {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("不是有效的迁移数据", e)
        }
        require(root.optString("app") == APP) { "不是 TernDays 的迁移数据" }
        val format = root.optInt("format", -1)
        require(format in 1..FORMAT) { "数据格式版本 $format 不受支持,请先升级本机应用" }

        val punches = ArrayList<Punch>()
        val pArr = root.optJSONArray("punches") ?: JSONArray()
        for (i in 0 until pArr.length()) {
            val o = pArr.getJSONObject(i)
            try {
                punches.add(
                    Punch(
                        localDate = LocalDate.parse(o.getString("localDate")),
                        slot = Slot.valueOf(o.getString("slot")),
                        epochMs = o.getLong("epochMs"),
                        zoneId = o.getString("zoneId"),
                        lat = o.getDouble("lat"),
                        lng = o.getDouble("lng"),
                        accuracyM = if (o.has("accuracyM") && !o.isNull("accuracyM")) o.getDouble("accuracyM") else null,
                        cityKey = o.getString("cityKey"),
                        cityName = o.getString("cityName"),
                        delayed = o.optBoolean("delayed", false),
                        fromCache = o.optBoolean("fromCache", false),
                        viaContext = o.optBoolean("viaContext", false),
                    ),
                )
            } catch (e: Exception) {
                throw IllegalArgumentException("第 ${i + 1} 条打卡记录损坏", e)
            }
        }
        val overrides = ArrayList<DayOverride>()
        val oArr = root.optJSONArray("overrides") ?: JSONArray()
        for (i in 0 until oArr.length()) {
            val o = oArr.getJSONObject(i)
            try {
                overrides.add(
                    DayOverride(
                        localDate = LocalDate.parse(o.getString("localDate")),
                        cityKey = o.getString("cityKey"),
                        cityName = o.getString("cityName"),
                        scope = OverrideScope.valueOf(o.optString("scope", "FULL")),
                    ),
                )
            } catch (e: Exception) {
                throw IllegalArgumentException("第 ${i + 1} 条手动记录损坏", e)
            }
        }
        return Payload(format, root.optInt("datasetVersion", 0), root.optLong("exportedAtMs", 0L), punches, overrides)
    }
}

/**
 * 迁移传输加密：AES-256-GCM。密文布局 = 12 字节 nonce + 密文 + 16 字节 tag,
 * 与 iOS CryptoKit `AES.GCM.SealedBox.combined` 完全兼容。密钥只经二维码(线下信道)传递。
 */
object MigrationCrypto {
    const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun newKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    fun seal(key: ByteArray, plain: ByteArray): ByteArray {
        require(key.size == KEY_BYTES)
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return nonce + cipher.doFinal(plain)
    }

    /** @throws IllegalArgumentException 密钥不对或数据被篡改 */
    fun open(key: ByteArray, blob: ByteArray): ByteArray {
        require(key.size == KEY_BYTES)
        require(blob.size > NONCE_BYTES + TAG_BITS / 8) { "数据不完整" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES),
        )
        return try {
            cipher.doFinal(blob, NONCE_BYTES, blob.size - NONCE_BYTES)
        } catch (e: Exception) {
            throw IllegalArgumentException("解密失败:密钥不匹配或数据被篡改", e)
        }
    }

    fun fingerprint(key: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(key)
}

/**
 * 二维码内容：terndays://migrate?v=1&a=<ip[,ip…]>&p=<port>&k=<base64url 无填充的 32 字节密钥>。
 * 二进制传输协议(双端一致):
 *   客户端 → "TERNMIG1"(8B) + sha256(key)(32B)
 *   服务端 → 指纹匹配:4B 大端长度 + AES-GCM 密文;不匹配:直接断开
 *   客户端 → "TERNDONE"(8B) + 4B 大端导入条数,服务端据此显示完成并停止
 */
object MigrationLink {
    const val SCHEME_PREFIX = "terndays://migrate?"
    val MAGIC_HELLO = "TERNMIG1".toByteArray(Charsets.US_ASCII)
    val MAGIC_DONE = "TERNDONE".toByteArray(Charsets.US_ASCII)
    /** 单次传输上限:纯文字记录不可能到这个量级,防异常膨胀 */
    const val MAX_BLOB_BYTES = 32 * 1024 * 1024

    data class Link(val addresses: List<String>, val port: Int, val key: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Link && addresses == other.addresses && port == other.port && key.contentEquals(other.key)

        override fun hashCode(): Int = 31 * (31 * addresses.hashCode() + port) + key.contentHashCode()
    }

    /**
     * 迁移只走局域网。二维码里的地址必须是私网/链路本地地址——
     * 否则一张伪造的二维码就能把应用引到任意公网主机,既破坏「零联网」承诺,
     * 又能往用户库里塞记录。
     */
    fun isLanAddress(address: String): Boolean {
        val trimmed = address.trim()
        val a = trimmed.substringBefore('%') // 去掉 IPv6 的 zone id
        if (a.isEmpty()) return false
        if (a.contains(':')) {
            // 必须能完整解析成 IPv6 数字字面量:"fd:x.attacker.example" 这种带冒号的主机名一律拒绝,
            // 否则接收端会拿它去做域名解析
            val h = parseIpv6(a) ?: return false
            if (trimmed.contains('%') && !ZONE_ID.matches(trimmed.substringAfter('%'))) return false
            return (h.take(7).all { it == 0 } && h[7] == 1) || // ::1
                (h[0] and 0xFE00) == 0xFC00 || // 唯一本地地址 fc00::/7
                (h[0] and 0xFFC0) == 0xFE80 // 链路本地 fe80::/10
        }
        if (trimmed.contains('%')) return false
        val n = parseIpv4(a) ?: return false
        return when {
            n[0] == 10 -> true
            n[0] == 127 -> true
            n[0] == 192 && n[1] == 168 -> true
            n[0] == 172 && n[1] in 16..31 -> true
            n[0] == 169 && n[1] == 254 -> true // 链路本地
            else -> false
        }
    }

    private val ZONE_ID = Regex("[A-Za-z0-9_.-]{1,32}")

    /** 严格的点分十进制:四段、每段 1–3 位纯数字、0..255(不接受 "+1"、"0x0a" 这类写法)。 */
    private fun parseIpv4(s: String): IntArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        return IntArray(4) { i ->
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            p.toInt().takeIf { it in 0..255 } ?: return null
        }
    }

    /** RFC 4291 文本形式 → 8 个 16 位分组;支持 "::" 压缩与末尾内嵌 IPv4。非法返回 null。 */
    private fun parseIpv6(s: String): IntArray? {
        if (s.any { !(it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return null
        val dbl = s.indexOf("::")
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) return null
        fun groups(part: String, allowV4Tail: Boolean): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val items = part.split(':')
            val out = ArrayList<Int>(8)
            for ((i, g) in items.withIndex()) {
                if (g.contains('.')) {
                    if (!allowV4Tail || i != items.lastIndex) return null
                    val v4 = parseIpv4(g) ?: return null
                    out += (v4[0] shl 8) or v4[1]
                    out += (v4[2] shl 8) or v4[3]
                } else {
                    if (g.isEmpty() || g.length > 4) return null
                    out += g.toInt(16)
                }
            }
            return out
        }
        if (dbl < 0) {
            val all = groups(s, allowV4Tail = true) ?: return null
            return if (all.size == 8) all.toIntArray() else null
        }
        val head = groups(s.substring(0, dbl), allowV4Tail = false) ?: return null
        val tail = groups(s.substring(dbl + 2), allowV4Tail = true) ?: return null
        if (head.size + tail.size > 7) return null
        return (head + List(8 - head.size - tail.size) { 0 } + tail).toIntArray()
    }

    fun build(addresses: List<String>, port: Int, key: ByteArray): String {
        require(addresses.isNotEmpty() && port in 1..65535 && key.size == MigrationCrypto.KEY_BYTES)
        val k = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        return SCHEME_PREFIX + "v=1&a=" + addresses.joinToString(",") + "&p=" + port + "&k=" + k
    }

    /** 解析二维码内容;不是本应用的迁移码返回 null。 */
    fun parse(text: String): Link? {
        val t = text.trim()
        if (!t.startsWith(SCHEME_PREFIX)) return null
        val params = HashMap<String, String>()
        for (pair in t.removePrefix(SCHEME_PREFIX).split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0) params[pair.substring(0, eq)] = pair.substring(eq + 1)
        }
        if (params["v"] != "1") return null
        val addresses = params["a"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return null
        val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val key = try {
            Base64.getUrlDecoder().decode(params["k"] ?: return null)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // 只接受局域网地址:公网地址的二维码一律当作非法码
        val lan = addresses.filter { isLanAddress(it) }
        if (lan.isEmpty() || key.size != MigrationCrypto.KEY_BYTES) return null
        return Link(lan, port, key)
    }
}
