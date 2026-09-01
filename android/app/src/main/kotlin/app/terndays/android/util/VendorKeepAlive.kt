package app.terndays.android.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 国产 ROM 自启动 / 后台运行白名单设置页跳转。
 * 系统没有统一入口，逐厂商 try；全部失败则回落到应用详情页。
 */
object VendorKeepAlive {

    private data class Entry(val keywords: List<String>, val component: ComponentName)

    private val ENTRIES = listOf(
        Entry(
            listOf("xiaomi", "redmi", "poco"),
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        Entry(
            listOf("huawei", "honor"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ),
        Entry(
            listOf("huawei", "honor"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ),
        Entry(
            listOf("oppo", "realme", "oneplus"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ),
        Entry(
            listOf("oppo", "realme"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
        Entry(
            listOf("oneplus"),
            ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        ),
        Entry(
            listOf("vivo", "iqoo"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ),
        Entry(
            listOf("vivo", "iqoo"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ),
        Entry(
            listOf("meizu"),
            ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"),
        ),
        Entry(
            listOf("samsung"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        ),
    )

    val vendorLabel: String get() = Build.MANUFACTURER ?: "手机"

    /** @return true = 成功打开某个厂商设置页 */
    fun openAutoStartSettings(context: Context): Boolean {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val ordered = ENTRIES.sortedByDescending { e ->
            e.keywords.any { manufacturer.contains(it) || brand.contains(it) }
        }
        for (entry in ordered) {
            try {
                val intent = Intent().setComponent(entry.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) {
                // try next
            }
        }
        Perms.openAppSettings(context)
        return false
    }
}
