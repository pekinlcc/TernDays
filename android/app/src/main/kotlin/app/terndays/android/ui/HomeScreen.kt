package app.terndays.android.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.terndays.android.DataBus
import app.terndays.android.R
import app.terndays.android.db.PunchDb
import app.terndays.android.widget.TernDaysWidgetProvider
import app.terndays.core.DayCounting
import app.terndays.core.DayOverride
import app.terndays.core.Punch
import app.terndays.core.Slot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private val WEEK_CN = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
internal fun weekCn(d: LocalDate) = WEEK_CN[d.dayOfWeek.value - 1]
internal fun punchClock(p: Punch): String {
    val t = Instant.ofEpochMilli(p.epochMs).atZone(ZoneId.of(p.zoneId)).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

@Composable
fun HomeScreen(
    onOpenCity: (String, Int) -> Unit,
    onExport: (Int) -> Unit,
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    var year by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val dataVersion = DataBus.version.intValue
    val data by produceState<YearData?>(initialValue = null, year, tick, dataVersion) {
        value = loadYearData(context, year)
    }
    var correctingToday by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Td.Bg).statusBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Td.Accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_tern), null, Modifier.size(22.dp), tint = Td.OnAccent)
            }
            Spacer(Modifier.width(10.dp))
            Text("TernDays", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Td.Ink)
            Spacer(Modifier.weight(1f))
            IconSquare(R.drawable.ic_share, "导出数据") { onExport(year) }
            Spacer(Modifier.width(8.dp))
            IconSquare(R.drawable.ic_sliders, "设置") { onSettings() }
        }
        Spacer(Modifier.height(14.dp))

        val d = data
        val permsOk = remember(tick) { app.terndays.android.util.Perms.allCoreGranted(context) }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (!permsOk) {
                item { PermissionWarningCard(onSettings) }
            }
            item { SummaryCard(year, d, onYearChange = { year = it }, onSettings) }
            if (year == LocalDate.now().year) {
                item { TodayCard(d, onCorrect = { correctingToday = true }) }
            }
            item {
                Row(Modifier.padding(horizontal = 2.dp)) {
                    Text("${year} 年去过的城市", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted)
                    Spacer(Modifier.weight(1f))
                    Text("按天数排序", fontSize = 11.sp, color = Td.Faint)
                }
            }
            item { CityListCard(d, onOpenCity = { onOpenCity(it, year) }) }
            item {
                Text(
                    "每天 07:00 / 17:00 自动定位打卡\n所有数据仅保存在本机",
                    fontSize = 11.sp, color = Td.Faint, textAlign = TextAlign.Center, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).navigationBarsPadding(),
                )
            }
        }
    }

    if (correctingToday) {
        val today = LocalDate.now()
        val current = data?.stats?.days?.get(today)
        val todayPunches = data?.punches?.filter { it.localDate == today } ?: emptyList()
        CityCorrectDialog(
            date = today,
            currentCityName = current?.shares?.joinToString(" + ") { it.cityName },
            recentCities = data?.stats?.cities?.map { it.cityKey to it.cityName } ?: emptyList(),
            hasBothHalves = todayPunches.any { it.slot == Slot.MORNING } && todayPunches.any { it.slot == Slot.EVENING },
            onDismiss = { correctingToday = false },
            onPick = { key, name, scope ->
                PunchDb.get(context).setOverride(DayOverride(today, key, name, scope))
                TernDaysWidgetProvider.updateAll(context)
                correctingToday = false
                tick++
            },
            onRestoreAuto = if (data?.overrides?.any { it.localDate == today } == true) {
                {
                    PunchDb.get(context).removeOverride(today)
                    TernDaysWidgetProvider.updateAll(context)
                    correctingToday = false
                    tick++
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun PermissionWarningCard(onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Td.WarmSoft)
            .clickable(onClick = onSettings)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_pin), null, Modifier.size(18.dp), tint = Td.WarmDeep)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("自动打卡还没就绪", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Td.WarmDeep)
            Text("定位权限未设为「始终允许」，点击去完成设置", fontSize = 11.sp, color = Td.WarmDeep)
        }
        Icon(painterResource(R.drawable.ic_chev_right), null, Modifier.size(14.dp), tint = Td.WarmDeep)
    }
}

@Composable
internal fun IconSquare(
    iconRes: Int,
    contentDescription: String,
    tint: Color = Td.Muted,
    onClick: () -> Unit,
) {
    // 触摸目标 48dp(可视方块仍是 36dp),满足无障碍最小尺寸
    Box(
        Modifier.size(48.dp).clickable(onClick = onClick).semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(Td.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(iconRes), contentDescription, Modifier.size(19.dp), tint = tint)
        }
    }
}

@Composable
private fun SummaryCard(year: Int, data: YearData?, onYearChange: (Int) -> Unit, onSettings: () -> Unit) {
    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                var menuOpen by remember { mutableStateOf(false) }
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { menuOpen = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$year 年", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
                    Spacer(Modifier.width(4.dp))
                    Icon(painterResource(R.drawable.ic_chev_down), null, Modifier.size(15.dp), tint = Td.Faint)
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        (data?.years ?: listOf(year)).forEach { y ->
                            DropdownMenuItem(
                                text = { Text("$y 年") },
                                onClick = { menuOpen = false; onYearChange(y) },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                val range = data?.stats?.let {
                    "${it.firstDate.monthValue}月${it.firstDate.dayOfMonth}日 – ${it.lastDate.monthValue}月${it.lastDate.dayOfMonth}日"
                } ?: ""
                Text(range, fontSize = 12.sp, color = Td.Muted)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                BigStat(data?.stats?.let { DayCounting.formatDays(it.recordedDays) } ?: "–", "天已记录")
                Spacer(Modifier.width(26.dp))
                BigStat(data?.stats?.cities?.size?.toString() ?: "–", "个城市")
                Spacer(Modifier.weight(1f))
                val missing = data?.stats?.unrecordedDates?.size ?: 0
                if (missing > 0) {
                    // 可点:跳设置去补记(样式上明确可点,不再是灰色死文字)
                    Text(
                        "另有 $missing 天可补记",
                        fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Td.AccentDeep,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onSettings)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BigStat(value: String, label: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Td.AccentDeep, lineHeight = 30.sp)
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, color = Td.Muted, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun TodayCard(data: YearData?, onCorrect: () -> Unit) {
    val today = LocalDate.now()
    val punches = data?.punches?.filter { it.localDate == today } ?: emptyList()
    val morning = punches.firstOrNull { it.slot == Slot.MORNING }
    val evening = punches.firstOrNull { it.slot == Slot.EVENING }
    val extra = punches.firstOrNull { it.slot == Slot.EXTRA }
    val hasToday = punches.isNotEmpty() || data?.stats?.days?.containsKey(today) == true

    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日打卡 · ${today.monthValue}月${today.dayOfMonth}日 ${weekCn(today)}",
                    fontSize = 12.sp, color = Td.Muted, modifier = Modifier.weight(1f),
                )
                if (hasToday) {
                    // 定位/城市库偶有边界误判（如深圳被判成香港），提供一键人工更正
                    Text(
                        "纠正", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Td.AccentDeep,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onCorrect)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Row {
                PunchCell(R.drawable.ic_sun, Color(0xFFA9762F), "早 · 07:00", morning, Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(40.dp).background(Td.Border))
                PunchCell(
                    R.drawable.ic_sunset, Td.Faint, "晚 · 17:00", evening,
                    Modifier.weight(1f).padding(start = 16.dp),
                )
            }
            if (extra != null) {
                Text(
                    "首点 ${punchClock(extra)} · ${extra.cityName} ✓（已记录当前位置）",
                    fontSize = 11.sp, color = Td.Faint,
                )
            }
            // 今天还没打完:单个样本先算 0.5 天,说明清楚免得以为少算了
            val pendingHalf = data?.stats?.days?.get(today)?.shares?.singleOrNull()?.takeIf { it.weight == 0.5 }
            if (pendingHalf != null) {
                Text(
                    if (evening == null) "今天先算半天 · 晚点打上后补满一天" else "今天先算半天 · 早点补上后补满一天",
                    fontSize = 11.sp, color = Td.Faint,
                )
            }
        }
    }
}

@Composable
private fun PunchCell(iconRes: Int, iconTint: Color, label: String, punch: Punch?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(iconRes), null, Modifier.size(20.dp), tint = iconTint)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontSize = 11.sp, color = Td.Faint)
            if (punch != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(punch.cityName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
                    Spacer(Modifier.width(5.dp))
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(14.dp), tint = Td.Accent)
                }
            } else {
                Text("待记录", fontSize = 14.sp, color = Td.Faint)
            }
        }
    }
}

@Composable
private fun CityListCard(data: YearData?, onOpenCity: (String) -> Unit) {
    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            val cities = data?.stats?.cities ?: emptyList()
            if (data != null && cities.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有打卡记录", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "下一个 07:00 / 17:00 会自动记录你所在的城市",
                        fontSize = 12.sp, color = Td.Muted, textAlign = TextAlign.Center,
                    )
                }
            }
            cities.forEachIndexed { i, c ->
                if (i > 0) HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenCity(c.cityKey) }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        c.cityName, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Td.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            DayCounting.formatDays(c.days),
                            fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Td.AccentDeep, lineHeight = 26.sp,
                        )
                        Spacer(Modifier.width(3.dp))
                        Text("天", fontSize = 11.sp, color = Td.Faint, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Icon(painterResource(R.drawable.ic_chev_right), null, Modifier.size(16.dp), tint = Td.Chevron)
                }
            }
        }
    }
}
