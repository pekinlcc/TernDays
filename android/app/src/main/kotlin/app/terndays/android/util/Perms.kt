package app.terndays.android.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.terndays.android.punch.PunchScheduler

object Perms {

    /** 精确定位。用户在权限弹窗里选「大致位置」时为 false，但仍然可以打卡（见 [anyLocation]）。 */
    fun fineLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun coarseLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 能不能打卡:精确或大致任一即可(大致定位误差大,交叉验证的误差圈规则会兜底)。 */
    fun anyLocation(context: Context): Boolean = fineLocation(context) || coarseLocation(context)

    fun backgroundLocation(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            anyLocation(context)
        }

    fun notifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    fun exactAlarm(context: Context): Boolean = PunchScheduler.canExact(context)

    /** 系统「定位服务」总开关。关掉时权限全绿也一条都打不上。 */
    fun locationServicesEnabled(context: Context): Boolean =
        runCatching {
            val lm = context.getSystemService(LocationManager::class.java)
            if (Build.VERSION.SDK_INT >= 28) {
                lm.isLocationEnabled
            } else {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }
        }.getOrDefault(true)

    fun openLocationSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).newTask())
        }.onFailure { openAppSettings(context) }
    }

    fun ignoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    /** 自动打卡跑不起来的原因,按修复顺序排:先总开关,再定位权限,再「始终允许」。 */
    enum class Missing { LOCATION_SERVICES, LOCATION, BACKGROUND }

    fun missing(context: Context): Missing? = when {
        !locationServicesEnabled(context) -> Missing.LOCATION_SERVICES
        !anyLocation(context) -> Missing.LOCATION
        !backgroundLocation(context) -> Missing.BACKGROUND
        else -> null
    }

    fun allCoreGranted(context: Context): Boolean = missing(context) == null

    // ---- 跳转 ----

    private fun Intent.newTask() = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openAppSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).newTask(),
            )
        }
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")).newTask(),
            )
        }.onFailure { openAppSettings(context) }
    }

    @Suppress("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ).newTask(),
            )
        }.onFailure { openAppSettings(context) }
    }
}
