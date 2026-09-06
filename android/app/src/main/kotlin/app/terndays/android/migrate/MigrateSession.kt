package app.terndays.android.migrate

import android.content.Context
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

    /** 已经有服务在跑就直接复用（保持同一个端口与密钥）。 */
    fun start(context: Context) {
        if (server != null) return
        reset()
        val app = context.applicationContext
        val s = MigrateServer(
            app,
            onReady = { qrText.value = it },
            onStatus = { status.value = it },
            onDone = { doneCount.value = it },
            onError = { failure.value = it },
        )
        server = s
        s.start()
    }

    fun stop() {
        server?.let { runCatching { it.stop() } }
        server = null
        reset()
    }

    private fun reset() {
        qrText.value = null
        status.value = null
        doneCount.value = null
        failure.value = null
    }
}
