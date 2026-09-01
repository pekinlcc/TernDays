package app.terndays.android.punch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.terndays.core.Slot

class PunchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PunchScheduler.ACTION_PUNCH) return
        // 先排下一次，保证链条不中断
        PunchScheduler.scheduleNext(context)
        val slot = intent.getStringExtra(PunchScheduler.EXTRA_SLOT)
            ?.let { runCatching { Slot.valueOf(it) }.getOrNull() }
        PunchService.start(context, slot)
    }
}
