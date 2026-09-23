package app.terndays.android.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.terndays.android.ui.MainActivity

object Intents {
    /** 点通知 / 小组件打开应用首页(此前 5 处各抄一份)。 */
    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
