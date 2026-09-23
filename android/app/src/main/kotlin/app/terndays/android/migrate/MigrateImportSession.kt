package app.terndays.android.migrate

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import app.terndays.core.MigrationLink

/**
 * 迁移接收端的导入状态放在**进程级**(与发送端 [MigrateSession] 同一写法)。
 *
 * 此前状态是设置页里的 `remember`:导入途中旋转屏幕或切深浅色,Activity 重建,
 * 后台线程仍在导入,但进度与结果弹窗都丢了,用户不知道成没成功。
 */
object MigrateImportSession {

    sealed interface State {
        data class Working(val message: String) : State
        data class Done(val outcome: MigrateClient.Outcome) : State
        data class Failed(val message: String) : State
    }

    /** null = 空闲。只在主线程写。 */
    val state = mutableStateOf<State?>(null)

    private val main = Handler(Looper.getMainLooper())

    val isWorking: Boolean get() = state.value is State.Working

    /** 扫到的码交给这里;导入进行中再扫一次会被忽略(防止重复启动)。 */
    fun start(context: Context, scanned: String) {
        if (isWorking) return
        val link = MigrationLink.parse(scanned)
        if (link == null) {
            state.value = State.Failed("这不是 TernDays 的迁移二维码")
            return
        }
        state.value = State.Working("正在连接旧手机…")
        MigrateClient.run(
            context.applicationContext, link,
            onStatus = { msg -> main.post { if (isWorking) state.value = State.Working(msg) } },
            onDone = { outcome -> main.post { state.value = State.Done(outcome) } },
            onError = { msg -> main.post { state.value = State.Failed(msg) } },
        )
    }

    /** 结果弹窗关掉时调用;进行中不可清除。 */
    fun clear() {
        if (!isWorking) state.value = null
    }
}
