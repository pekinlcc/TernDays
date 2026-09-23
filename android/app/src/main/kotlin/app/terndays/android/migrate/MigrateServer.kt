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
import java.net.Socket
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
    private val finished = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var key: ByteArray? = null
    @Volatile private var lastAddresses: List<String> = emptyList()
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
                this.key = key
                lastAddresses = addresses
                onReady(MigrationLink.build(addresses, server.localPort, key))

                while (!stopped.get() && !finished.get()) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break // stop() 关闭了 socket
                    }
                    // 每个连接独立线程:一个连上却不说话的陌生连接不能把真正的新手机挡在门外
                    Thread { serve(socket, blob, fingerprint) }.apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                if (!stopped.get()) onError("启动迁移服务失败:${e.message ?: e.javaClass.simpleName}")
            } finally {
                runCatching { serverSocket?.close() }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** 单个连接。暗号/指纹不对、5 秒内不说话:静默丢弃,不改页面状态(那不是用户的新手机)。 */
    private fun serve(socket: Socket, blob: ByteArray, fingerprint: ByteArray) {
        var verified = false
        try {
            socket.soTimeout = HELLO_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val hello = ByteArray(MigrationLink.MAGIC_HELLO.size + fingerprint.size)
            input.readFully(hello)
            val magicOk = hello.copyOfRange(0, MigrationLink.MAGIC_HELLO.size)
                .contentEquals(MigrationLink.MAGIC_HELLO)
            val fpOk = hello.copyOfRange(MigrationLink.MAGIC_HELLO.size, hello.size)
                .contentEquals(fingerprint)
            if (!magicOk || !fpOk || stopped.get() || finished.get()) return
            verified = true
            socket.soTimeout = TRANSFER_TIMEOUT_MS
            onStatus("新手机已连接,正在传输…")
            val out = DataOutputStream(socket.getOutputStream())
            out.writeInt(blob.size)
            out.write(blob)
            out.flush()
            val done = ByteArray(MigrationLink.MAGIC_DONE.size)
            input.readFully(done)
            if (done.contentEquals(MigrationLink.MAGIC_DONE)) {
                val count = input.readInt()
                if (finished.compareAndSet(false, true)) {
                    runCatching { serverSocket?.close() } // 成功一次即停止
                    onDone(count)
                }
            } else {
                onStatus("连接中断,可让新手机重新扫码")
            }
        } catch (_: Exception) {
            if (verified && !stopped.get() && !finished.get()) onStatus("连接中断,可让新手机重新扫码")
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * 网络变了(换了 Wi-Fi、开了热点):监听绑在全部网卡上,不必重启服务,
     * 用同一个密钥和端口按新地址重新出码即可(新手机扫旧码也仍然连得上还在的那个地址)。
     */
    fun refreshAddresses() {
        val k = key ?: return
        val port = serverSocket?.takeIf { !it.isClosed }?.localPort ?: return
        if (stopped.get() || finished.get()) return
        val addresses = siteLocalAddresses()
        if (addresses.isEmpty() || addresses == lastAddresses) return
        lastAddresses = addresses
        onReady(MigrationLink.build(addresses, port, k))
    }

    fun stop() {
        stopped.set(true)
        runCatching { serverSocket?.close() }
    }

    companion object {
        private const val HELLO_TIMEOUT_MS = 5_000
        private const val TRANSFER_TIMEOUT_MS = 30_000

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
