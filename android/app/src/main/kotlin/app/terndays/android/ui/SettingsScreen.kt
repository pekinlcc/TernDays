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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.terndays.android.DataBus
import app.terndays.android.Prefs
import app.terndays.android.R
import app.terndays.android.db.PunchDb
import app.terndays.android.geo.Cities
import app.terndays.android.migrate.MigrateImportSession
import app.terndays.android.util.Perms
import app.terndays.android.util.VendorKeepAlive
import app.terndays.core.DayOverride
import app.terndays.core.Fmt
import app.terndays.core.OverrideScope
import app.terndays.core.WidgetStyle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.rememberCoroutineScope
import app.terndays.core.Regions
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    initialYear: Int? = null,
    openBackfill: Boolean = false,
    onBack: () -> Unit,
    onMigrate: () -> Unit,
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }

    // 年份可切换:此前写死当前年,往年的无记录日在应用里根本补不了
    // 从首页「另有 N 天可补记」进来时带着首页正在看的年份;齿轮进来默认今年
    var year by rememberSaveable { mutableIntStateOf(initialYear ?: LocalDate.now().year) }
    val load = rememberYearData(year, tick)
    val data = load.data

    val finePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { tick++ }
    val singlePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }

    // 从首页「另有 N 天可补记」进来时直接打开补记(只在首次进入时,旋转后按保存的状态)
    var backfillOpen by rememberSaveable { mutableStateOf(openBackfill) }
    val scope = rememberCoroutineScope()
    val importState = MigrateImportSession.state.value
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        MigrateImportSession.start(context, text)
    }
    // 导入完成要刷新本页数据(可补记天数等);导入期间保持亮屏,息屏会让传输中断
    LaunchedEffect(importState is MigrateImportSession.State.Done) {
        if (importState is MigrateImportSession.State.Done) tick++
    }
    val view = LocalView.current
    DisposableEffect(importState is MigrateImportSession.State.Working) {
        val working = importState is MigrateImportSession.State.Working
        view.keepScreenOn = working
        onDispose { if (working) view.keepScreenOn = false }
    }

    Column(Modifier.fillMaxSize().background(Td.Bg).statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        ScreenHeader("设置", onBack)
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            if (load.failed) item { LoadErrorCard(load.retry) }
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
                            PermRow(
                                "定位权限",
                                if (Perms.anyLocation(context) && !Perms.fineLocation(context)) {
                                    "当前只有「大致位置」,边界城市可能判不准,建议改为「精确位置」"
                                } else {
                                    "打卡时获取一次 GPS 位置"
                                },
                                Perms.anyLocation(context),
                            ) {
                                if (!Perms.anyLocation(context)) {
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
                            // 权限全绿也可能因为系统定位总开关是关的而一条都打不上
                            PermRow("系统定位服务", "关掉的话权限再全也拿不到位置", Perms.locationServicesEnabled(context)) {
                                Perms.openLocationSettings(context)
                            }
                            HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                            PermRow("后台定位「始终允许」", "熄屏 / 后台时也能完成定点打卡", Perms.backgroundLocation(context)) {
                                if (Build.VERSION.SDK_INT >= 29 && Perms.anyLocation(context) &&
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
                    "桌面小组件", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted,
                    modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                )
            }
            item { WidgetStyleCard() }

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
                                Text("补记", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
                                Text(
                                    "单日或一段日子；$year 年还有 ${data?.stats?.unrecordedDates?.size ?: 0} 天没有任何记录",
                                    fontSize = 12.sp, color = Td.Muted,
                                )
                            }
                            Icon(painterResource(R.drawable.ic_chev_right), null, Modifier.size(16.dp), tint = Td.Chevron)
                        }
                        // 往年也能补记:切到那一年即可
                        val years = data?.years ?: listOf(year)
                        if (years.size > 1) {
                            // 年份多了要能横向滚动,不然早期年份点不到
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp)
                                    .selectableGroup(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                years.forEach { y -> ScopeChip("$y 年", y == year) { year = y } }
                            }
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
                            // 同一天可能有上/下两条半天更正:必须分别显示、分别恢复,
                            // 此前两行长得一样且点任一行都会把整天的更正一起删掉
                            overrides
                                .sortedWith(compareByDescending<DayOverride> { it.localDate }.thenBy { it.scope.name })
                                .forEach { o ->
                                    val scopeLabel = when (o.scope) {
                                        OverrideScope.FULL -> "整天"
                                        OverrideScope.MORNING -> "上半天"
                                        else -> "下半天"
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            "${o.localDate.monthValue}月${o.localDate.dayOfMonth}日 · $scopeLabel → ${o.cityName}",
                                            fontSize = 13.sp, color = Td.Ink, modifier = Modifier.weight(1f),
                                        )
                                        TdTextButton("恢复自动", color = Td.WarmDeep, fontSize = 12.sp) {
                                            Corrections.scope.launch { Corrections.removeScope(context, o.localDate, o.scope) }
                                        }
                                    }
                                }
                        }
                    }
                }
            }

            item {
                Text(
                    "天数提醒", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted,
                    modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                )
            }
            item { ThresholdsCard(data?.stats?.cities?.map { Regions.codeOf(it.cityKey) }?.distinct() ?: emptyList()) }

            item {
                Text(
                    "数据", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted,
                    modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                )
            }
            item { DataCard() }

            item {
                Text(
                    "换手机", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted,
                    modifier = Modifier.padding(start = 2.dp, top = 4.dp),
                )
            }
            item {
                TdCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        MigrateRow(
                            "迁移到新手机", "本机是旧手机:展示二维码,让新手机扫码接收全部数据",
                            onClick = onMigrate,
                        )
                        HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                        MigrateRow(
                            "从旧手机导入", "本机是新手机:扫旧手机上的二维码,数据经加密局域网直传",
                            onClick = {
                                scanLauncher.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("扫描旧手机 TernDays 迁移页上的二维码")
                                        .setBeepEnabled(false)
                                        .setOrientationLocked(false),
                                )
                            },
                        )
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

    when (val s = importState) {
        null -> Unit
        is MigrateImportSession.State.Working -> Dialog(onDismissRequest = { }) {
            TdCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(color = Td.Accent)
                    Text(s.message, fontSize = 13.sp, color = Td.Muted, textAlign = TextAlign.Center)
                }
            }
        }
        is MigrateImportSession.State.Done -> Dialog(onDismissRequest = { MigrateImportSession.clear() }) {
            TdCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("导入完成 ✓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Td.Ink)
                    val r = s.outcome.result
                    Text(
                        mergeSummary(r, s.outcome.remapped, "旧手机"),
                        fontSize = 13.sp, color = Td.Muted, lineHeight = 20.sp,
                    )
                    TdTextButton("好", modifier = Modifier.align(Alignment.End)) { MigrateImportSession.clear() }
                }
            }
        }
        is MigrateImportSession.State.Failed -> Dialog(onDismissRequest = { MigrateImportSession.clear() }) {
            TdCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("导入没有成功", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Td.Ink)
                    Text(s.message, fontSize = 13.sp, color = Td.Muted, lineHeight = 20.sp)
                    TdTextButton("知道了", modifier = Modifier.align(Alignment.End)) { MigrateImportSession.clear() }
                }
            }
        }
    }

    // 数据读到之后再打开:补记弹窗只在第一次组合时按「最近一个无记录日」定默认日期,
    // 先用空列表打开会停在昨天(多半已有记录),一选城市就把昨天整天改掉了
    if (backfillOpen && data != null) {
        val days = data.stats.days
        BackfillDialog(
            unrecorded = data.stats.unrecordedDates,
            recentCities = data.stats.cities.map { it.cityKey to it.cityName },
            trackingSince = data.stats.trackingSince,
            neighbors = { d ->
                listOf(d.minusDays(1), d.plusDays(1)).flatMap { n ->
                    days[n]?.shares?.map { it.cityKey to it.cityName } ?: emptyList()
                }
            },
            onDismiss = { backfillOpen = false },
        )
    }
}

@Composable
private fun MigrateRow(title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
            Text(sub, fontSize = 12.sp, color = Td.Muted, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        Icon(painterResource(R.drawable.ic_chev_right), null, Modifier.size(16.dp), tint = Td.Chevron)
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
            null -> Tag("去检查", Td.NeutralSoft, Td.Muted)
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
