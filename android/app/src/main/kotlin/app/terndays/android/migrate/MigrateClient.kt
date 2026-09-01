package app.terndays.android.migrate

import android.content.Context
import app.terndays.android.DataBus
import app.terndays.android.db.PunchDb
import app.terndays.android.geo.Cities
import app.terndays.android.widget.TernDaysWidgetProvider
import app.terndays.core.MigrationCodec
import app.terndays.core.MigrationCrypto
import app.terndays.core.MigrationLink
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 新手机侧:扫码得到 Link → 依次尝试各地址连接旧手机 → 校验指纹 → 拉取并解密数据 →
 * 合并导入(本机已有的保留)→ 按本机城市库重解析导入的坐标 → 回执 TERNDONE。
 */
object MigrateClient {

    data class Outcome(val result: PunchDb.MergeResult, val remapped: Int)

    fun run(
        context: Context,
        link: MigrationLink.Link,
        onStatus: (String) -> Unit,
        onDone: (Outcome) -> Unit,
        onError: (String) -> Unit,
    ) {
        Thread {
            var socket: Socket? = null
            try {
                onStatus("正在连接旧手机…")
                for (addr in link.addresses) {
                    val s = Socket()
                    try {
                        s.connect(InetSocketAddress(addr, link.port), 5_000)
                        socket = s
                        break
                    } catch (_: Exception) {
                        runCatching { s.close() }
                    }
                }
                val sock = socket ?: run {
                    onError("连不上旧手机。请确认:两台手机连着同一个 Wi-Fi(或新手机连上旧手机的热点),旧手机的迁移页面还开着,然后重新扫码。")
                    return@Thread
                }
                sock.soTimeout = 30_000
                val out = DataOutputStream(sock.getOutputStream())
                out.write(MigrationLink.MAGIC_HELLO)
                out.write(MigrationCrypto.fingerprint(link.key))
                out.flush()

                onStatus("正在接收数据…")
                val input = DataInputStream(sock.getInputStream())
                val len = input.readInt()
                if (len <= 0 || len > MigrationLink.MAX_BLOB_BYTES) {
                    onError("收到的数据长度异常,已中止")
                    return@Thread
                }
                val blob = ByteArray(len)
                input.readFully(blob)
                val payload = MigrationCodec.parse(
                    String(MigrationCrypto.open(link.key, blob), Charsets.UTF_8),
                )

                onStatus("正在合并导入…")
                val db = PunchDb.get(context)
                val result = db.mergeImported(payload.punches, payload.overrides)

                // 回执,让旧手机显示「已完成」
                runCatching {
                    out.write(MigrationLink.MAGIC_DONE)
                    out.writeInt(result.punchesAdded + result.overridesAdded)
                    out.flush()
                }
                runCatching { sock.close() }

                // 导入的记录可能来自不同版本的城市库:按时间重放交叉验证重解析(幂等)
                var remapped = 0
                if (result.punchesAdded > 0) {
                    remapped = db.replayResolveAll(Cities.get(context))
                    TernDaysWidgetProvider.updateAll(context)
                    DataBus.bump()
                }
                onDone(Outcome(result, remapped))
            } catch (e: Exception) {
                onError(
                    when (e) {
                        is IllegalArgumentException -> e.message ?: "数据校验失败"
                        else -> "传输中断:${e.message ?: e.javaClass.simpleName}。请重新扫码再试。"
                    },
                )
            } finally {
                runCatching { socket?.close() }
            }
        }.apply { isDaemon = true }.start()
    }
}
