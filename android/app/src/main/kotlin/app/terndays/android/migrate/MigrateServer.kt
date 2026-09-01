package app.terndays.android.migrate

import android.content.Context
import app.terndays.android.db.PunchDb
import app.terndays.android.geo.Cities
import app.terndays.core.MigrationCodec
import app.terndays.core.MigrationCrypto
import app.terndays.core.MigrationLink
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 旧手机侧:导出全部数据 → 加密 → 起一次性局域网服务,把连接参数与密钥放进二维码。
 * 协议见 :core MigrationLink 注释。指纹不匹配的连接直接断开且不消耗服务;
 * 成功传输一次(收到 TERNDONE)即停止。页面离开时必须调用 stop()。
 */
class MigrateServer(
    private val context: Context,
    private val onReady: (qrText: String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onDone: (importedCount: Int) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        thread = Thread {
            try {
                val db = PunchDb.get(context)
                val json = MigrationCodec.toJson(
                    Cities.DATASET_VERSION,
                    System.currentTimeMillis(),
                    db.allPunches(),
                    db.allOverrides(),
                )
                val key = MigrationCrypto.newKey()
                val blob = MigrationCrypto.seal(key, json.toByteArray(Charsets.UTF_8))
                val fingerprint = MigrationCrypto.fingerprint(key)

                val addresses = siteLocalAddresses()
                if (addresses.isEmpty()) {
                    onError("本机没有局域网地址:请先连接 Wi-Fi(或开启热点让新手机连接)")
                    return@Thread
                }
                val server = ServerSocket(0).also { serverSocket = it }
                onReady(MigrationLink.build(addresses, server.localPort, key))

                while (!stopped.get()) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break // stop() 关闭了 socket
                    }
                    try {
                        socket.soTimeout = 20_000
                        val input = DataInputStream(socket.getInputStream())
                        val hello = ByteArray(MigrationLink.MAGIC_HELLO.size + fingerprint.size)
                        input.readFully(hello)
                        val magicOk = hello.copyOfRange(0, MigrationLink.MAGIC_HELLO.size)
                            .contentEquals(MigrationLink.MAGIC_HELLO)
                        val fpOk = hello.copyOfRange(MigrationLink.MAGIC_HELLO.size, hello.size)
                            .contentEquals(fingerprint)
                        if (!magicOk || !fpOk) {
                            socket.close()
                            continue // 陌生连接:丢弃,继续等真正的新手机
                        }
                        onStatus("新手机已连接,正在传输…")
                        val out = DataOutputStream(socket.getOutputStream())
                        out.writeInt(blob.size)
                        out.write(blob)
                        out.flush()
                        val done = ByteArray(MigrationLink.MAGIC_DONE.size)
                        input.readFully(done)
                        if (done.contentEquals(MigrationLink.MAGIC_DONE)) {
                            val count = input.readInt()
                            socket.close()
                            onDone(count)
                            return@Thread
                        }
                        socket.close()
                    } catch (_: Exception) {
                        runCatching { socket.close() }
                        if (!stopped.get()) onStatus("连接中断,可让新手机重新扫码")
                    }
                }
            } catch (e: Exception) {
                if (!stopped.get()) onError("启动迁移服务失败:${e.message ?: e.javaClass.simpleName}")
            } finally {
                runCatching { serverSocket?.close() }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        stopped.set(true)
        runCatching { serverSocket?.close() }
    }

    companion object {
        /** 本机可被局域网访问的 IPv4 地址,Wi-Fi/热点接口优先。 */
        fun siteLocalAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .sortedBy { ni ->
                    val n = ni.name.lowercase()
                    when {
                        n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") -> 0
                        n.startsWith("eth") -> 1
                        else -> 2
                    }
                }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
