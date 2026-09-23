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
import androidx.compose.runtime.mutableStateOf
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
import app.terndays.core.DayOverride
import app.terndays.core.Fmt
import app.terndays.core.Punch
import app.terndays.core.Slot
import java.time.LocalDate
import java.time.YearMonth
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.launch

/** manual:这座城市这天的份额来自手动更正;provisional:进行中的今天,先算半天 */
private data class CityDay(val weight: Double, val manual: Boolean, val provisional: Boolean)

@Composable
fun CityDetailScreen(cityKey: String, year: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    // 写库后 DataBus 会触发重读,不再需要本地 tick
    val load = rememberYearData(year, cityKey)
    val d = load.data
    val scope = rememberCoroutineScope()
    // 旋转 / 切深浅色不丢正在更正的那一天和当前月份
    var correcting by rememberSaveable { mutableStateOf<LocalDate?>(null) }

    // 该城市在本年已无任何记录(如最后一天被更正走):自动返回列表,不停留在空页
    LaunchedEffect(d) {
        if (d != null && d.stats.cities.none { it.cityKey == cityKey }) onBack()
    }

    val cityDays: Map<LocalDate, CityDay> = remember(d) {
        d?.stats?.days?.mapNotNull { (date, attr) ->
            attr.shares.firstOrNull { it.cityKey == cityKey }
                ?.let { date to CityDay(it.weight, it.manual, attr.provisional) }
        }?.toMap() ?: emptyMap()
    }
    // 月历上别的日子也要画出来:记在其他城市的淡底,开始记录之后的无记录日空心描边
    val today = LocalDate.now()
    val otherDays: Set<LocalDate> = remember(d) {
        d?.stats?.days?.filter { (_, a) -> a.shares.isNotEmpty() && a.shares.none { it.cityKey == cityKey } }
            ?.keys ?: emptySet()
    }
    val missingDays: Set<LocalDate> = remember(d) { d?.stats?.unrecordedDates?.toSet() ?: emptySet() }
    val stat = d?.stats?.cities?.firstOrNull { it.cityKey == cityKey }
    val cityName = stat?.cityName ?: ""

    var month by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(d) {
        if (month == 0 && d != null) {
            month = cityDays.keys.maxOrNull()?.monthValue
                ?: if (year == LocalDate.now().year) LocalDate.now().monthValue else 12
        }
    }

    val punchesByDate = remember(d) { (d?.punches ?: emptyList()).groupBy { it.localDate } }

    Column(Modifier.fillMaxSize().background(Td.Bg).statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(10.dp))
        ScreenHeader(cityName, onBack)
        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
            if (load.failed) item { LoadErrorCard(load.retry) }
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
                        otherDays = otherDays, missingDays = missingDays, today = today,
                        onPrev = { if (month > 1) month-- },
                        onNext = { if (month < 12) month++ },
                        onDayClick = { correcting = it },
                    )
                }
            }
            item {
                Row(Modifier.padding(horizontal = 2.dp)) {
                    Text("打卡明细", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted)
                    Spacer(Modifier.weight(1f))
                    Text("点一天可更正城市", fontSize = 11.sp, color = Td.Faint)
                }
            }
            item {
                DetailListCard(cityDays, punchesByDate, onRowClick = { correcting = it })
            }
            item { Spacer(Modifier.navigationBarsPadding().height(8.dp)) }
        }
    }

    val target = correcting
    if (target != null) {
        val current = d?.stats?.days?.get(target)
        val dayPunches = punchesByDate[target] ?: emptyList()
        CityCorrectDialog(
            date = target,
            currentCityName = current?.shares?.joinToString(" + ") { it.cityName },
            // 在城市详情里点开的:本城排第一
            recentCities = listOf(cityKey to cityName).filter { it.second.isNotEmpty() } +
                (d?.stats?.cities?.map { it.cityKey to it.cityName } ?: emptyList()),
            allowHalfScope = DayCounting.halfSampleFlags(
                dayPunches.firstOrNull { it.slot == Slot.MORNING },
                dayPunches.firstOrNull { it.slot == Slot.EVENING },
                dayPunches.firstOrNull { it.slot == Slot.EXTRA },
            ).let { it.first || it.second } || (target == today && java.time.LocalTime.now().hour >= 12),
            existing = d?.overrides?.filter { it.localDate == target } ?: emptyList(),
            hasPunches = dayPunches.isNotEmpty(),
            onDismiss = { correcting = null },
            onPick = { key, name, scope0 ->
                correcting = null
                scope.launch {
                    Corrections.apply(
                        context, listOf(DayOverride(target, key, name, scope0)),
                        "${Fmt.monthDay(target)} 已改为 $name",
                    )
                }
            },
            onRestoreAuto = if (d?.overrides?.any { it.localDate == target } == true) {
                {
                    correcting = null
                    scope.launch { Corrections.restoreAuto(context, target) }
                }
            } else {
                null
            },
        )
    }
}

@Composable
private fun CalendarCard(
    year: Int,
    month: Int,
    cityDays: Map<LocalDate, CityDay>,
    otherDays: Set<LocalDate>,
    missingDays: Set<LocalDate>,
    today: LocalDate,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDayClick: (LocalDate) -> Unit,
) {
    val ym = YearMonth.of(year, month)
    val monthSum = cityDays.entries.filter { it.key.monthValue == month }.sumOf { it.value.weight }

    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clickable(onClick = onPrev),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Td.Bg),
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(R.drawable.ic_chev_left), "上个月", Modifier.size(16.dp), tint = Td.Ink) }
                }
                Text(
                    "$year 年 $month 月", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink,
                    modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Box(
                    Modifier.size(48.dp).clickable(onClick = onNext),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Td.Bg),
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(R.drawable.ic_chev_right), "下个月", Modifier.size(16.dp), tint = Td.Ink) }
                }
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
                    week.forEach { date ->
                        DayCell(
                            date, date?.let { cityDays[it] },
                            other = date != null && date in otherDays,
                            missing = date != null && date in missingDays,
                            // 过去的每一天都能点:补记 / 改到本城,不必先回设置页
                            clickable = date != null && !date.isAfter(today),
                            modifier = Modifier.weight(1f), onClick = onDayClick,
                        )
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Legend(LegendKind.FULL, "全天")
                Legend(LegendKind.HALF, "半天")
                Legend(LegendKind.PROVISIONAL, "进行中")
                Spacer(Modifier.weight(1f))
                Text(
                    "本月 ${DayCounting.formatDays(monthSum)} 天",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Td.AccentDeep,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Legend(LegendKind.OTHER, "其他城市")
                Legend(LegendKind.MISSING, "无记录")
            }
        }
    }
}

/** 半天:左上三角填色(与导出、iOS 同一个视觉语言) */
private fun Modifier.halfFill(color: androidx.compose.ui.graphics.Color, shape: RoundedCornerShape) =
    clip(shape).border(1.dp, color, shape).drawBehind {
        val p = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(p, color)
    }

@Composable
private fun DayCell(
    date: LocalDate?,
    day: CityDay?,
    other: Boolean,
    missing: Boolean,
    clickable: Boolean,
    modifier: Modifier,
    onClick: (LocalDate) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val base = if (date != null && clickable) {
        modifier.height(42.dp).clip(shape).clickable(role = Role.Button) { onClick(date) }
    } else {
        modifier.height(42.dp)
    }
    val styled = when {
        date == null -> base
        day != null && day.provisional -> base.halfFill(Td.WarmSoft, shape)
        day != null && day.weight >= 1.0 -> base.clip(shape).background(Td.AccentSoft)
        day != null -> base.halfFill(Td.AccentSoft, shape)
        other -> base.clip(shape).background(Td.NeutralSoft)
        missing -> base.clip(shape).border(1.dp, Td.Border, shape)
        else -> base
    }
    Box(styled, contentAlignment = Alignment.Center) {
        if (date != null) {
            Text(
                date.dayOfMonth.toString(),
                fontSize = 13.sp,
                fontWeight = if (day != null) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    day == null -> Td.Faint
                    day.provisional -> Td.WarmDeep
                    day.weight >= 1.0 -> Td.AccentDeep
                    else -> Td.Ink
                },
            )
        }
    }
}

private enum class LegendKind { FULL, HALF, PROVISIONAL, OTHER, MISSING }

@Composable
private fun Legend(kind: LegendKind, label: String) {
    val shape = RoundedCornerShape(4.dp)
    val m = Modifier.size(14.dp)
    when (kind) {
        LegendKind.FULL -> Box(m.clip(shape).background(Td.AccentSoft))
        LegendKind.HALF -> Box(m.halfFill(Td.AccentSoft, shape))
        LegendKind.PROVISIONAL -> Box(m.halfFill(Td.WarmSoft, shape))
        LegendKind.OTHER -> Box(m.clip(shape).background(Td.NeutralSoft))
        LegendKind.MISSING -> Box(m.clip(shape).border(1.dp, Td.Border, shape))
    }
    Spacer(Modifier.width(5.dp))
    Text(label, fontSize = 11.sp, color = Td.Muted)
    Spacer(Modifier.width(12.dp))
}

@Composable
private fun DetailListCard(
    cityDays: Map<LocalDate, CityDay>,
    punchesByDate: Map<LocalDate, List<Punch>>,
    onRowClick: (LocalDate) -> Unit,
) {
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
                val x = punches.firstOrNull { it.slot == Slot.EXTRA }
                Row(
                    Modifier.fillMaxWidth().clickable { onRowClick(date) }.padding(vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            "${date.monthValue}月${date.dayOfMonth}日 · ${Fmt.weekdayCn(date)}",
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink,
                        )
                        val detail = listOfNotNull(
                            m?.let { it.epochMs to "早 ${Fmt.clock(it)} ${it.cityName}" },
                            e?.let { it.epochMs to "晚 ${Fmt.clock(it)} ${it.cityName}" },
                            x?.let { it.epochMs to "首 ${Fmt.clock(it)} ${it.cityName}" },
                        ).sortedBy { it.first }.joinToString(" · ") { it.second }
                        val sub = when {
                            day.provisional -> listOf(detail, "今天先算半天,另半天打上后补满")
                                .filter { it.isNotEmpty() }.joinToString(" · ")
                            day.manual && detail.isEmpty() -> "手动补记"
                            day.manual -> "已手动更正 · 当天打卡:$detail"
                            detail.isEmpty() -> "无打卡记录"
                            else -> detail
                        }
                        Text(sub, fontSize = 12.sp, color = Td.Muted)
                    }
                    // 主标签永远说「算了多少」:全天 / 半天 / 进行中(暂时算半天,与跨城日的半天不是一回事);
                    // 「手动」退为次级角标,且只在这座城市的份额确实来自更正时出现
                    val (label, bg, fg) = when {
                        day.provisional -> Triple("进行中", Td.WarmSoft, Td.WarmDeep)
                        day.weight >= 1.0 -> Triple("全天", Td.AccentSoft, Td.AccentDeep)
                        else -> Triple("半天", Td.AccentSoft, Td.AccentDeep)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg)
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                        if (day.manual) {
                            Text("手动", fontSize = 10.sp, color = Td.WarmDeep)
                        }
                    }
                }
            }
        }
    }
}
