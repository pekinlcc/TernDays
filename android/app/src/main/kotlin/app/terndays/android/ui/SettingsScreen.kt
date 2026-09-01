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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.terndays.android.R
import app.terndays.android.db.PunchDb
import app.terndays.android.geo.Cities
import app.terndays.android.util.Perms
import app.terndays.android.util.VendorKeepAlive
import app.terndays.core.DayOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }

    val year = LocalDate.now().year
    val data by produceState<YearData?>(initialValue = null, tick) {
        value = loadYearData(context, year)
    }

    val finePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { tick++ }
    val singlePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }

    var backfillOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Td.Bg).statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(R.drawable.ic_chev_left) { onBack() }
            Text(
                "设置", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Td.Ink,
                modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(36.dp))
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    "打卡保障 · 每一项都会影响后台自动打卡的成功率",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
            item {
                key(tick) {
                    TdCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            PermRow("定位权限", "打卡时获取一次 GPS 位置", Perms.fineLocation(context)) {
                                if (!Perms.fineLocation(context)) {
                                    finePermLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        ),
                                    )
                                } else {
                                    Perms.openAppSettings(context)
                                }
                            }
                            HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                            PermRow("后台定位「始终允许」", "熄屏 / 后台时也能完成定点打卡", Perms.backgroundLocation(context)) {
                                if (Build.VERSION.SDK_INT >= 29 && Perms.fineLocation(context) &&
                                    !Perms.backgroundLocation(context)
                                ) {
                                    singlePermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                } else {
                                    Perms.openAppSettings(context)
                                }
                            }
                            HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                            PermRow("通知权限", "打卡失败时提醒你补打", Perms.notifications(context)) {
                                if (Build.VERSION.SDK_INT >= 33 && !Perms.notifications(context)) {
                                    singlePermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    Perms.openAppSettings(context)
                                }
                            }
                            HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                            PermRow("精确闹钟", "保证在 07:00 / 17:00 准点触发", Perms.exactAlarm(context)) {
                                Perms.openExactAlarmSettings(context)
                            }
                            HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                            PermRow("忽略电池优化", "降低系统休眠对打卡的影响", Perms.ignoringBatteryOptimizations(context)) {
                                Perms.requestIgnoreBatteryOptimizations(context)
                            }
                            HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                            PermRow(
                                "自启动 / 后台运行（${VendorKeepAlive.vendorLabel}）",
                                "国产系统请在安全中心把 TernDays 加入自启动白名单",
                                ok = null,
                            ) {
                                VendorKeepAlive.openAutoStartSettings(context)
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "手动补记与更正", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted,
                    modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                )
            }
            item {
                TdCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { backfillOpen = true }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("补记无记录的日子", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
                                Text(
                                    "今年还有 ${data?.stats?.unrecordedDates?.size ?: 0} 天没有任何记录",
                                    fontSize = 12.sp, color = Td.Muted,
                                )
                            }
                            Icon(painterResource(R.drawable.ic_chev_right), null, Modifier.size(16.dp), tint = Color(0xFFC3CCD4))
                        }
                        HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                        Text(
                            "记录的城市不对？在首页「今日打卡」点「纠正」，或到城市详情里点那一天即可更正",
                            fontSize = 12.sp, color = Td.Muted, lineHeight = 18.sp,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        val overrides = data?.overrides ?: emptyList()
                        if (overrides.isNotEmpty()) {
                            HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                            overrides.sortedByDescending { it.localDate }.forEach { o ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${o.localDate.monthValue}月${o.localDate.dayOfMonth}日 → ${o.cityName}（手动）",
                                        fontSize = 13.sp, color = Td.Ink, modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "恢复自动", fontSize = 12.sp, color = Td.WarmDeep,
                                        modifier = Modifier.clickable {
                                            PunchDb.get(context).removeOverride(o.localDate)
                                            app.terndays.android.widget.TernDaysWidgetProvider.updateAll(context)
                                            tick++
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "关于", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted,
                    modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                )
            }
            item {
                val versionName = remember {
                    runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull() ?: "?"
                }
                TdCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AboutLine("版本", "TernDays $versionName")
                        AboutLine("打卡时间", "每天 07:00 / 17:00（本地时间，暂不可调）")
                        AboutLine("数据", "全部保存在本机，无账号、不上传；删除应用即清除")
                        AboutLine("城市库", "GeoNames（CC-BY 4.0）+ 中国行政区划（modood），离线匹配")
                        AboutLine(
                            "开源地址", "github.com/pekinlcc/TernDays",
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://github.com/pekinlcc/TernDays"),
                                        ),
                                    )
                                }
                            },
                        )
                    }
                }
            }
            item { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
        }
    }

    if (backfillOpen) {
        BackfillDialog(
            unrecorded = data?.stats?.unrecordedDates ?: emptyList(),
            recentCities = data?.stats?.cities?.map { it.cityKey to it.cityName } ?: emptyList(),
            onDismiss = { backfillOpen = false },
            onConfirm = { date, key, name ->
                PunchDb.get(context).setOverride(DayOverride(date, key, name))
                app.terndays.android.widget.TernDaysWidgetProvider.updateAll(context)
                backfillOpen = false
                tick++
            },
        )
    }
}

@Composable
private fun PermRow(title: String, sub: String, ok: Boolean?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
            Text(sub, fontSize = 12.sp, color = Td.Muted)
        }
        Spacer(Modifier.width(10.dp))
        when (ok) {
            true -> Tag("已开启", Td.AccentSoft, Td.AccentDeep)
            false -> Tag("未开启", Td.WarmSoft, Td.WarmDeep)
            null -> Tag("去检查", Color(0xFFEDF1F4), Td.Muted)
        }
    }
}

@Composable
private fun Tag(text: String, bg: Color, fg: Color) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg,
        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun AboutLine(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        Text(label, fontSize = 12.sp, color = Td.Faint, modifier = Modifier.width(64.dp))
        Text(
            value, fontSize = 12.sp, lineHeight = 18.sp,
            color = if (onClick != null) Td.AccentDeep else Td.Muted,
        )
    }
}

@Composable
private fun BackfillDialog(
    unrecorded: List<LocalDate>,
    recentCities: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate, String, String) -> Unit,
) {
    val context = LocalContext.current
    var pickedDate by remember { mutableStateOf<LocalDate?>(null) }
    var query by remember { mutableStateOf("") }
    val results by produceState(initialValue = emptyList<Pair<String, String>>(), query) {
        value = if (query.isBlank()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { Cities.get(context).searchByName(query, 12) }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        TdCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val date = pickedDate
                if (date == null) {
                    Text("选择要补记的日期", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Td.Ink)
                    if (unrecorded.isEmpty()) {
                        Text("今年没有缺记录的日子 🎉", fontSize = 13.sp, color = Td.Muted)
                    }
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(unrecorded.sortedDescending()) { d ->
                            Text(
                                "${d.monthValue}月${d.dayOfMonth}日 · ${weekCn(d)}",
                                fontSize = 14.sp, color = Td.Ink,
                                modifier = Modifier.fillMaxWidth().clickable { pickedDate = d }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                } else {
                    Text(
                        "补记 ${date.monthValue}月${date.dayOfMonth}日 在哪个城市？",
                        fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Td.Ink,
                    )
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("搜索城市名", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    val options = if (query.isBlank()) recentCities else results
                    if (query.isBlank() && recentCities.isNotEmpty()) {
                        Text("常去城市", fontSize = 11.sp, color = Td.Faint)
                    }
                    LazyColumn(Modifier.heightIn(max = 260.dp)) {
                        items(options) { (key, name) ->
                            Text(
                                name, fontSize = 14.sp, color = Td.Ink,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onConfirm(date, key, name) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (pickedDate != null) {
                        Text(
                            "重选日期", fontSize = 13.sp, color = Td.Muted,
                            modifier = Modifier.clickable { pickedDate = null }.padding(8.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        "取消", fontSize = 13.sp, color = Td.Muted,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                    )
                }
            }
        }
    }
}
