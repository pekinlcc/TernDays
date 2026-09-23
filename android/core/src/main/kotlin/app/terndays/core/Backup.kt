package app.terndays.core

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 加密文件备份(全程离线):内容 = 迁移交换 JSON([MigrationCodec]),
 * 口令经 PBKDF2-HMAC-SHA256 派生 256 位密钥,再用与迁移相同的 AES-GCM([MigrationCrypto])加密。
 *
 * 文件格式(双端一致):"TERNBAK1"(8B) + 迭代次数(4B 大端) + 盐(16B) + AES-GCM(nonce‖密文‖tag)。
 * 迭代次数写在文件头,以后调高也能读旧备份。
 *
 * PBKDF2 在这里手写而不用 PBEKeySpec:各平台对 char[] 口令的字节编码不一致,
 * 手写后口令一律按 UTF-8,Android 与 iOS(CommonCrypto)派生出的密钥逐字节相同。
 */
object Backup {

    val MAGIC = "TERNBAK1".toByteArray(Charsets.US_ASCII)
    const val ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    const val MIN_PASSPHRASE = 8
    const val FILE_EXTENSION = "terndays"

    fun seal(passphrase: String, plain: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(passphrase.length >= MIN_PASSPHRASE) { "口令至少 $MIN_PASSPHRASE 位" }
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val key = pbkdf2(passphrase.toByteArray(Charsets.UTF_8), salt, iterations, MigrationCrypto.KEY_BYTES)
        val blob = MigrationCrypto.seal(key, plain)
        return ByteBuffer.allocate(MAGIC.size + 4 + SALT_BYTES + blob.size)
            .put(MAGIC).putInt(iterations).put(salt).put(blob).array()
    }

    /** @throws IllegalArgumentException 不是 TernDays 备份、口令不对,或文件损坏(文案可直接展示) */
    fun open(passphrase: String, file: ByteArray): ByteArray {
        require(isBackup(file)) { "这不是 TernDays 的备份文件" }
        val buf = ByteBuffer.wrap(file, MAGIC.size, file.size - MAGIC.size)
        val iterations = buf.int
        require(iterations in 1..10_000_000) { "备份文件已损坏" }
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val blob = ByteArray(buf.remaining()).also { buf.get(it) }
        val key = pbkdf2(passphrase.toByteArray(Charsets.UTF_8), salt, iterations, MigrationCrypto.KEY_BYTES)
        return try {
            MigrationCrypto.open(key, blob)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("口令不对,或备份文件已损坏", e)
        }
    }

    fun isBackup(file: ByteArray): Boolean =
        file.size > MAGIC.size + 4 + SALT_BYTES + 28 && file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    /** RFC 8018 PBKDF2,PRF = HMAC-SHA256。 */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        require(iterations > 0 && keyLength > 0)
        val mac = Mac.getInstance("HmacSHA256")
        // 空口令时 SecretKeySpec 会拒绝空数组:HMAC 对空键等价于全零单字节键
        mac.init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (keyLength + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(ByteBuffer.allocate(4).putInt(i).array())
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return out.copyOf(keyLength)
    }
}
