package app.terndays.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.terndays.android.R
import app.terndays.android.util.Perms
import app.terndays.android.util.VendorKeepAlive

private enum class Step(val button: String) {
    FINE("允许定位"),
    BACKGROUND("把定位改为「始终允许」"),
    NOTIFY("允许通知"),
    EXACT_ALARM("允许精确闹钟"),
    BATTERY("忽略电池优化"),
    DONE("开始统计"),
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    // 记录「弹过窗但仍被拒」的步骤:永久拒绝后系统不再弹窗,按钮改为跳系统设置,不做死循环
    var deniedSteps by remember { mutableStateOf(setOf<Step>()) }
    var launchedStep by remember { mutableStateOf<Step?>(null) }
    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (!Perms.fineLocation(context)) deniedSteps = deniedSteps + Step.FINE
        tick++
    }
    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) launchedStep?.let { deniedSteps = deniedSteps + it }
        tick++
    }

    // tick 参与计算以便授权后刷新
    val step = remember(tick) {
        when {
            !Perms.fineLocation(context) -> Step.FINE
            !Perms.backgroundLocation(context) -> Step.BACKGROUND
            !Perms.notifications(context) -> Step.NOTIFY
            !Perms.exactAlarm(context) -> Step.EXACT_ALARM
            !Perms.ignoringBatteryOptimizations(context) -> Step.BATTERY
            else -> Step.DONE
        }
    }

    Column(
        Modifier.fillMaxSize().background(Td.Bg).statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(36.dp))
        Box(
            Modifier.size(148.dp).clip(CircleShape).background(Td.AccentSoft).align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_pin), null, Modifier.size(64.dp), tint = Td.Accent)
        }
        Row(
            Modifier.align(Alignment.CenterHorizontally).offset(y = (-16).dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TimeChip(R.drawable.ic_sun, Color(0xFFA9762F), "07:00")
            TimeChip(R.drawable.ic_sunset, Td.Muted, "17:00")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "每天两次，自动记住\n你在哪座城市",
            fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Td.Ink,
            textAlign = TextAlign.Center, lineHeight = 32.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "每天早上 7:00 和下午 5:00，TernDays 在后台各记录一次 GPS 定位，" +
                "只保留“城市”级别的结果，用来统计你一年里在每座城市待了多少天。",
            fontSize = 14.sp, color = Td.Muted, lineHeight = 24.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(22.dp))

        TdCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Bullet(
                    R.drawable.ic_pin, "需要「始终允许」定位权限",
                    "仅在每天两个时间点各取一次位置，不持续追踪",
                    done = Perms.backgroundLocation(context),
                )
                HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                Bullet(
                    R.drawable.ic_check, "数据只保存在手机本地",
                    "无账号、不上传，删除应用即清除全部数据",
                    done = null,
                )
                HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                Bullet(
                    R.drawable.ic_sun, "几乎不耗电",
                    "每天仅定位两次，没有后台常驻服务",
                    done = null,
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Box(
            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(Td.Accent)
                .clickable {
                    if (step in deniedSteps) {
                        Perms.openAppSettings(context)
                        return@clickable
                    }
                    when (step) {
                        Step.FINE -> multiLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                        Step.BACKGROUND ->
                            if (Build.VERSION.SDK_INT >= 29) {
                                launchedStep = Step.BACKGROUND
                                singleLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            } else {
                                tick++
                            }
                        Step.NOTIFY ->
                            if (Build.VERSION.SDK_INT >= 33) {
                                launchedStep = Step.NOTIFY
                                singleLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                Perms.openAppSettings(context)
                            }
                        Step.EXACT_ALARM -> Perms.openExactAlarmSettings(context)
                        Step.BATTERY -> Perms.requestIgnoreBatteryOptimizations(context)
                        Step.DONE -> onDone()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (step in deniedSteps) "去系统设置开启" else step.button,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Td.OnAccent,
            )
        }
        if (step in deniedSteps) {
            Spacer(Modifier.height(4.dp))
            Text(
                "系统不再弹出授权窗口,请在 设置 → 应用 → TernDays → 权限 中手动开启",
                fontSize = 12.sp, color = Td.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        if (step == Step.DONE) {
            Text(
                "建议再到「${VendorKeepAlive.vendorLabel} 安全中心」把 TernDays 加入自启动白名单",
                fontSize = 12.sp, color = Td.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { VendorKeepAlive.openAutoStartSettings(context) }
                    .padding(vertical = 10.dp),
            )
        } else {
            Text(
                "稍后再说",
                fontSize = 14.sp, color = Td.Muted, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onDone).padding(vertical = 10.dp),
            )
        }
        Text(
            "后台任务由手机系统调度，实际记录时间可能\n在 07:00 / 17:00 附近略有浮动",
            fontSize = 11.sp, color = Td.Faint, textAlign = TextAlign.Center, lineHeight = 17.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).navigationBarsPadding(),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TimeChip(iconRes: Int, tint: Color, text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(Td.Surface)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(iconRes), null, Modifier.size(14.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
    }
}

@Composable
private fun Bullet(iconRes: Int, title: String, sub: String, done: Boolean?) {
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Td.AccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(iconRes), null, Modifier.size(19.dp), tint = Td.AccentDeep)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
            Text(sub, fontSize = 12.sp, color = Td.Muted)
        }
        if (done == true) {
            Icon(painterResource(R.drawable.ic_check), null, Modifier.size(16.dp), tint = Td.Accent)
        }
    }
}
