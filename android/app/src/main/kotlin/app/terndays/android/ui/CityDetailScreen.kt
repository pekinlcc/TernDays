package app.terndays.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terndays.android.R
import app.terndays.core.DayCounting
import app.terndays.core.Punch
import app.terndays.core.Slot
import java.time.LocalDate
import java.time.YearMonth

private data class CityDay(val weight: Double, val manual: Boolean)

@Composable
fun CityDetailScreen(cityKey: String, year: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val data by produceState<YearData?>(initialValue = null, cityKey, year) {
        value = loadYearData(context, year)
    }
    val d = data

    val cityDays: Map<LocalDate, CityDay> = remember(d) {
        d?.stats?.days?.mapNotNull { (date, attr) ->
            attr.shares.firstOrNull { it.cityKey == cityKey }?.let { date to CityDay(it.weight, attr.manual) }
        }?.toMap() ?: emptyMap()
    }
    val cityName = d?.stats?.cities?.firstOrNull { it.cityKey == cityKey }?.cityName
        ?: cityDays.keys.firstOrNull()?.toString() ?: ""
    val stat = d?.stats?.cities?.firstOrNull { it.cityKey == cityKey }

    var month by remember { mutableIntStateOf(0) }
    LaunchedEffect(d) {
        if (month == 0 && d != null) {
            month = cityDays.keys.maxOrNull()?.monthValue
                ?: if (year == LocalDate.now().year) LocalDate.now().monthValue else 12
        }
    }

    val punchesByDate = remember(d) { (d?.punches ?: emptyList()).groupBy { it.localDate } }

    Column(Modifier.fillMaxSize().background(Td.Bg).statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconSquare(R.drawable.ic_chev_left) { onBack() }
            Text(
                cityName, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Td.Ink,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.width(36.dp))
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Row(Modifier.padding(horizontal = 2.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        stat?.let { DayCounting.formatDays(it.days) } ?: "0",
                        fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Td.AccentDeep, lineHeight = 32.sp,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("天", fontSize = 13.sp, color = Td.Muted, modifier = Modifier.padding(bottom = 4.dp))
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$year 年累计 · 全天 ${stat?.fullDays ?: 0} 天 + 半天 ${stat?.halfDays ?: 0} 次",
                        fontSize = 12.sp, color = Td.Muted, modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            item {
                if (month != 0) {
                    CalendarCard(
                        year = year, month = month, cityDays = cityDays,
                        onPrev = { if (month > 1) month-- },
                        onNext = { if (month < 12) month++ },
                    )
                }
            }
            item {
                Row(Modifier.padding(horizontal = 2.dp)) {
                    Text("打卡明细", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted)
                    Spacer(Modifier.weight(1f))
                    Text("最近在前", fontSize = 11.sp, color = Td.Faint)
                }
            }
            item {
                DetailListCard(cityDays, punchesByDate)
            }
            item { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
        }
    }
}

@Composable
private fun CalendarCard(
    year: Int,
    month: Int,
    cityDays: Map<LocalDate, CityDay>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    val ym = YearMonth.of(year, month)
    val monthSum = cityDays.entries.filter { it.key.monthValue == month }.sumOf { it.value.weight }

    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Td.Bg).clickable(onClick = onPrev),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_chev_left), null, Modifier.size(16.dp), tint = Td.Ink) }
                Text(
                    "$year 年 $month 月", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Td.Bg).clickable(onClick = onNext),
                    contentAlignment = Alignment.Center,
                ) { Icon(painterResource(R.drawable.ic_chev_right), null, Modifier.size(16.dp), tint = Td.Ink) }
            }
            Row {
                for (w in listOf("一", "二", "三", "四", "五", "六", "日")) {
                    Text(
                        w, fontSize = 11.sp, color = Td.Faint, modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            val leading = ym.atDay(1).dayOfWeek.value - 1
            val cells: List<LocalDate?> = List(leading) { null } + (1..ym.lengthOfMonth()).map { ym.atDay(it) }
            cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { date -> DayCell(date, date?.let { cityDays[it] }, Modifier.weight(1f)) }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(full = true)
                Spacer(Modifier.width(6.dp))
                Text("全天", fontSize = 11.sp, color = Td.Muted)
                Spacer(Modifier.width(14.dp))
                LegendSwatch(full = false)
                Spacer(Modifier.width(6.dp))
                Text("半天", fontSize = 11.sp, color = Td.Muted)
                Spacer(Modifier.weight(1f))
                Text(
                    "本月 ${DayCounting.formatDays(monthSum)} 天",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Td.AccentDeep,
                )
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate?, day: CityDay?, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    val base = modifier.height(42.dp)
    val styled = when {
        date == null || day == null -> base
        day.weight >= 1.0 -> base.clip(shape).background(Td.AccentSoft)
        else -> base.clip(shape).border(1.dp, Td.AccentSoft, shape).drawBehind {
            val p = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(p, Td.AccentSoft)
        }
    }
    Box(styled, contentAlignment = Alignment.Center) {
        if (date != null) {
            Text(
                date.dayOfMonth.toString(),
                fontSize = 13.sp,
                fontWeight = if (day != null) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    day == null -> Td.Faint
                    day.weight >= 1.0 -> Td.AccentDeep
                    else -> Td.Ink
                },
            )
        }
    }
}

@Composable
private fun LegendSwatch(full: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    val m = Modifier.size(14.dp)
    if (full) {
        Box(m.clip(shape).background(Td.AccentSoft))
    } else {
        Box(
            m.clip(shape).border(1.dp, Td.AccentSoft, shape).drawBehind {
                val p = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(p, Td.AccentSoft)
            },
        )
    }
}

@Composable
private fun DetailListCard(cityDays: Map<LocalDate, CityDay>, punchesByDate: Map<LocalDate, List<Punch>>) {
    val dates = cityDays.keys.sortedDescending()
    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            if (dates.isEmpty()) {
                Text(
                    "暂无记录", fontSize = 13.sp, color = Td.Faint,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            dates.forEachIndexed { i, date ->
                if (i > 0) HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                val day = cityDays[date]!!
                val punches = punchesByDate[date] ?: emptyList()
                val m = punches.firstOrNull { it.slot == Slot.MORNING }
                val e = punches.firstOrNull { it.slot == Slot.EVENING }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "${date.monthValue}月${date.dayOfMonth}日 · ${weekCn(date)}",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink,
                        )
                        val sub = when {
                            day.manual -> "手动补记"
                            m == null && e == null -> "无打卡记录"
                            else -> listOfNotNull(
                                m?.let { "早 ${punchClock(it)} ${it.cityName}" },
                                e?.let { "晚 ${punchClock(it)} ${it.cityName}" },
                            ).joinToString(" · ")
                        }
                        Text(sub, fontSize = 12.sp, color = Td.Muted)
                    }
                    val (label, bg, fg) = when {
                        day.manual -> Triple("补记", Td.WarmSoft, Td.WarmDeep)
                        day.weight >= 1.0 -> Triple("全天", Td.AccentSoft, Td.AccentDeep)
                        else -> Triple("半天", Td.WarmSoft, Td.WarmDeep)
                    }
                    Text(
                        label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg)
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    )
                }
            }
        }
    }
}
