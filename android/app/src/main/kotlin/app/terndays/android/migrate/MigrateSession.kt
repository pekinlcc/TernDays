package app.terndays.android.migrate

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf

/**
 * 迁移发送端在**进程级**持有服务实例。
 *
 * 此前二维码页把 MigrateServer 放在 `DisposableEffect(Unit)` 里：旋转屏幕、切换深浅模式、
 * 甚至输入法弹出引起的配置变更都会重建 Activity → 旧服务停掉、新服务换一个端口与新密钥，
 * 用户手上那张已经扫了一半的码就作废了。现在配置变更时复用同一个服务，
 * 只有真正离开页面（或传输结束）才停。
 */
object MigrateSession {

    val qrText = mutableStateOf<String?>(null)
    val status = mutableStateOf<String?>(null)
    val doneCount = mutableStateOf<Int?>(null)
    val failure = mutableStateOf<String?>(null)

    private var server: MigrateServer? = null
    private var appContext: Context? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val main = Handler(Looper.getMainLooper())

    /** 已经有服务在跑就直接复用（保持同一个端口与密钥）。 */
    fun start(context: Context) {
        if (server != null) return
        reset()
        val app = context.applicationContext
        appContext = app
        val s = MigrateServer(
            app,
            onReady = { qr -> main.post { qrText.value = qr } },
            onStatus = { msg -> main.post { status.value = msg } },
            onDone = { n -> main.post { doneCount.value = n } },
            onError = { msg -> main.post { failure.value = msg } },
        )
        server = s
        s.start()
        watchNetwork(app)
    }

    /** 失败态的「重试」:换一个新的密钥与端口重新开始。 */
    fun retry(context: Context) {
        stop()
        start(context)
    }

    fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
        unwatchNetwork()
        reset()
    }

    /** 网络一变就刷新二维码里的地址;此前因为没有局域网地址失败的,连上网络后自动重来。 */
    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkChanged()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = onNetworkChanged()
            override fun onLost(network: Network) = onNetworkChanged()
        }
        netCallback = runCatching { cm.registerDefaultNetworkCallback(cb); cb }.getOrNull()
    }

    private var lastAutoRetryAt = 0L

    private fun onNetworkChanged() {
        main.post {
            val app = appContext ?: return@post
            if (failure.value != null && doneCount.value == null) {
                // 只有真的有了局域网地址才重来,并且限频:注册回调本身就会立刻回调一次,
                // 只有蜂窝网络时不能陷入「失败 → 重试 → 失败」的循环
                val now = System.currentTimeMillis()
                if (now - lastAutoRetryAt < 3_000) return@post
                Thread {
                    if (MigrateServer.siteLocalAddresses().isNotEmpty()) {
                        main.post {
                            if (failure.value != null && appContext != null) {
                                lastAutoRetryAt = System.currentTimeMillis()
                                retry(app)
                            }
                        }
                    }
                }.apply { isDaemon = true }.start()
            } else {
                val s = server ?: return@post
                Thread { runCatching { s.refreshAddresses() } }.apply { isDaemon = true }.start()
            }
        }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching { appContext?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private fun reset() {
        qrText.value = null
        status.value = null
        doneCount.value = null
        failure.value = null
    }
}
