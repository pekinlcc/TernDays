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
import app.terndays.android.util.Perms
import app.terndays.android.R
import app.terndays.android.db.PunchDb
import app.terndays.android.widget.TernDaysWidgetProvider
import app.terndays.core.DayCounting
import app.terndays.core.DayOverride
import app.terndays.core.Punch
import app.terndays.core.PunchRules
import app.terndays.core.Slot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import android.content.Context
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.window.Dialog
import app.terndays.android.Prefs
import app.terndays.android.punch.PunchScheduler
import app.terndays.android.punch.PunchService
import app.terndays.android.punch.ThresholdAlerts
import app.terndays.core.Fmt
import app.terndays.core.Regions
import app.terndays.core.Stays
import app.terndays.core.Thresholds
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onOpenCity: (String, Int) -> Unit,
    onExport: (Int) -> Unit,
    onSettings: () -> Unit,
    onBackfill: (Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    // 只记「用户主动切到的往年」;没切过就跟着当前年走——跨过元旦回到前台自动换到新年,
    // 不会停在去年(此前 rememberSaveable 存的是具体年份)
    var pinnedYear by rememberSaveable { mutableStateOf<Int?>(null) }
    val currentYear = remember(tick) { LocalDate.now().year }
    val year = pinnedYear ?: currentYear
    val isCurrentYear = year == currentYear
    val load = rememberYearData(year, tick)
    val data = load.data
    // 旋转 / 切深浅色不丢正在进行的更正
    var correctingToday by rememberSaveable { mutableStateOf(false) }
    val dataVersion = DataBus.version.intValue
    val paused = remember(tick, dataVersion) { Prefs.punchPaused(context) }
    val attempt = remember(tick, dataVersion) { Prefs.lastAttempt(context) }
    val issue = remember(tick) { Health.firstIssue(context) }
    val future by produceState(emptyList<Punch>(), tick, dataVersion) {
        value = withContext(Dispatchers.IO) {
            runCatching { PunchDb.get(context).futurePunches() }.getOrDefault(emptyList())
        }
    }
    val thresholdStatus by produceState(emptyList<Thresholds.Status>(), tick, dataVersion) {
        value = withContext(Dispatchers.IO) { runCatching { ThresholdAlerts.statuses(context) }.getOrDefault(emptyList()) }
    }
    var confirmDeleteFuture by remember { mutableStateOf(false) }

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
        val today = LocalDate.now()
        // 最近 3 天(不含今天)出现过无记录日:多半是被系统限制了后台
        val recentGaps = if (isCurrentYear) {
            d?.stats?.unrecordedDates?.count { !it.isBefore(today.minusDays(3)) && it.isBefore(today) } ?: 0
        } else {
            0
        }
        val stays = remember(d) { d?.let { Stays.fold(it.stats.days) } ?: emptyList() }
        val regions = remember(d) { d?.let { Regions.summarize(it.stats) } ?: emptyList() }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                paused -> item {
                    NoticeCard(
                        title = "自动打卡已暂停",
                        text = "不会再定时记录位置；手动补记和更正照常可用",
                        critical = false,
                        actions = listOf(
                            "恢复" to {
                                Prefs.setPunchPaused(context, false)
                                PunchScheduler.scheduleNext(context)
                                PunchService.maybeBackfill(context, fromForeground = true)
                                tick += 1
                            },
                        ),
                    )
                }
                issue != null && issue.critical -> item { IssueCard(issue, onSettings) }
                recentGaps > 0 -> item {
                    NoticeCard(
                        title = "最近 $recentGaps 天没有自动记录",
                        text = "可能被系统限制了后台运行，检查一下打卡保障；漏掉的日子可以补记",
                        critical = true,
                        actions = listOf("检查打卡保障" to onSettings, "去补记" to { onBackfill(year) }),
                    )
                }
                issue != null -> item { IssueCard(issue, onSettings) }
            }
            if (future.isNotEmpty()) {
                item {
                    NoticeCard(
                        title = "有 ${future.size} 条记录的时间晚于现在",
                        text = "系统时间可能被调过。这些记录不会再被当作行程参照；确认有误可以删掉",
                        critical = false,
                        actions = listOf("删除这些记录" to { confirmDeleteFuture = true }),
                    )
                }
            }
            if (load.failed) item { LoadErrorCard(load.retry) }
            item {
                SummaryCard(
                    year, d,
                    onYearChange = { pinnedYear = it.takeIf { y -> y != LocalDate.now().year } },
                    onBackfill = { onBackfill(year) },
                )
            }
            if (isCurrentYear) {
                item { TodayCard(d, attempt, paused, onCorrect = { correctingToday = true }) }
            }
            if (regions.size >= 2 || (isCurrentYear && thresholdStatus.isNotEmpty())) {
                item { RegionCard(regions, if (isCurrentYear) thresholdStatus else emptyList()) }
            }
            if (stays.isNotEmpty()) {
                item { StaysCard(stays, if (isCurrentYear) Stays.current(stays, today) else null) }
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
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 72.dp).navigationBarsPadding(),
                )
            }
        }
    }

    if (confirmDeleteFuture) {
        ConfirmDialog(
            title = "删除 ${future.size} 条「未来」记录？",
            text = future.take(5).joinToString("\n") { "${it.localDate} ${Fmt.clock(it)} ${it.cityName}" } +
                if (future.size > 5) "\n…" else "",
            confirm = "删除",
            onDismiss = { confirmDeleteFuture = false },
            onConfirm = {
                confirmDeleteFuture = false
                val list = future
                scope.launch {
                    withContext(Dispatchers.IO) { PunchDb.get(context).deletePunches(list) }
                    TernDaysWidgetProvider.updateAll(context)
                    DataBus.bump()
                    UndoCenter.show("已删除 ${list.size} 条记录")
                }
            },
        )
    }

    if (correctingToday) {
        val current = data?.stats?.days?.get(today())
        val todayPunches = data?.punches?.filter { it.localDate == today() } ?: emptyList()
        // 只要有半天样本就允许半天更正:进行中的今天只打了早点时,整天更正会把还没到的
        // 晚点那半天一起吞掉;上午窗口已关、上午又没样本时,也允许只补那一半
        val (hasM, hasE) = DayCounting.halfSampleFlags(
            todayPunches.firstOrNull { it.slot == Slot.MORNING },
            todayPunches.firstOrNull { it.slot == Slot.EVENING },
            todayPunches.firstOrNull { it.slot == Slot.EXTRA },
        )
        CityCorrectDialog(
            date = today(),
            currentCityName = current?.shares?.joinToString(" + ") { it.cityName },
            recentCities = data?.stats?.cities?.map { it.cityKey to it.cityName } ?: emptyList(),
            allowHalfScope = hasM || hasE || LocalTime.now().hour >= 12,
            existing = data?.overrides?.filter { it.localDate == today() } ?: emptyList(),
            hasPunches = todayPunches.isNotEmpty(),
            onDismiss = { correctingToday = false },
            onPick = { key, name, scope0 ->
                correctingToday = false
                scope.launch {
                    Corrections.apply(context, listOf(DayOverride(today(), key, name, scope0)), "已改为 $name")
                }
            },
            onRestoreAuto = if (data?.overrides?.any { it.localDate == today() } == true) {
                {
                    correctingToday = false
                    scope.launch { Corrections.restoreAuto(context, today()) }
                }
            } else {
                null
            },
        )
    }
}

private fun today(): LocalDate = LocalDate.now()

/**
 * 首页警示按优先级只挑第一个没满足的项:系统定位 → 定位权限 → 后台定位 → 通知 → 电池优化。
 * 前三项直接决定能不能打卡(醒目样式),后两项影响可靠性(弱一级样式)。
 */
private object Health {
    enum class Issue(val critical: Boolean) {
        LOCATION_SERVICES(true), LOCATION(true), BACKGROUND(true), NOTIFICATIONS(false), BATTERY(false), COARSE(false)
    }

    fun firstIssue(context: Context): Issue? = when (Perms.missing(context)) {
        Perms.Missing.LOCATION_SERVICES -> Issue.LOCATION_SERVICES
        Perms.Missing.LOCATION -> Issue.LOCATION
        Perms.Missing.BACKGROUND -> Issue.BACKGROUND
        null -> when {
            !Perms.notifications(context) -> Issue.NOTIFICATIONS
            !Perms.ignoringBatteryOptimizations(context) -> Issue.BATTERY
            !Perms.fineLocation(context) -> Issue.COARSE
            else -> null
        }
    }
}

@Composable
private fun IssueCard(issue: Health.Issue, onSettings: () -> Unit) {
    val context = LocalContext.current
    // 按真正缺的那一项说原因,并直接跳到能修好它的地方
    val (title, text, action) = when (issue) {
        Health.Issue.LOCATION_SERVICES ->
            Triple("自动打卡还没就绪", "系统定位服务已关闭，点击打开", { Perms.openLocationSettings(context) })
        Health.Issue.LOCATION -> Triple("自动打卡还没就绪", "还没有定位权限，点击去授权", onSettings)
        Health.Issue.BACKGROUND -> Triple("自动打卡还没就绪", "定位权限未设为「始终允许」，点击去完成设置", onSettings)
        Health.Issue.NOTIFICATIONS -> Triple("通知已关闭", "打卡失败时没法提醒你补打，点击去打开", onSettings)
        Health.Issue.BATTERY -> Triple("电池优化可能拦下打卡", "把 TernDays 设为「不优化」更稳，点击去设置", onSettings)
        Health.Issue.COARSE -> Triple("只有「大致位置」", "城市交界处可能判错，改为「精确位置」更准", { Perms.openAppSettings(context) })
    }
    val bg = if (issue.critical) Td.WarmSoft else Td.NeutralSoft
    val fg = if (issue.critical) Td.WarmDeep else Td.Muted
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(role = Role.Button, onClick = action)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_pin), null, Modifier.size(18.dp), tint = fg)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg)
            Text(text, fontSize = 11.sp, color = fg)
        }
        Icon(painterResource(R.drawable.ic_chev_right), null, Modifier.size(14.dp), tint = fg)
    }
}

/** 带若干按钮的提示卡(暂停中、最近漏记、未来记录)。 */
@Composable
private fun NoticeCard(title: String, text: String, critical: Boolean, actions: List<Pair<String, () -> Unit>>) {
    val bg = if (critical) Td.WarmSoft else Td.NeutralSoft
    val fg = if (critical) Td.WarmDeep else Td.Ink
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(bg)
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg)
        Spacer(Modifier.height(2.dp))
        Text(text, fontSize = 11.sp, color = if (critical) Td.WarmDeep else Td.Muted, lineHeight = 16.sp,
            modifier = Modifier.padding(end = 8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            actions.forEach { (label, onClick) ->
                TdTextButton(label, color = if (critical) Td.WarmDeep else Td.AccentDeep, fontSize = 13.sp, onClick = onClick)
            }
        }
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    danger: Boolean = true,
) {
    Dialog(onDismissRequest = onDismiss) {
        TdCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 20.dp, bottom = 8.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Td.Ink)
                Spacer(Modifier.height(8.dp))
                Text(text, fontSize = 13.sp, color = Td.Muted, lineHeight = 20.sp, modifier = Modifier.padding(end = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TdTextButton("取消", color = Td.Muted, onClick = onDismiss)
                    TdTextButton(confirm, color = if (danger) Td.Danger else Td.AccentDeep, onClick = onConfirm)
                }
            }
        }
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
private fun SummaryCard(year: Int, data: YearData?, onYearChange: (Int) -> Unit, onBackfill: () -> Unit) {
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
                // 年中才开始用的:写「3月15日起」,别让人以为 1 月到 3 月也统计过
                val range = data?.stats?.let {
                    val since = it.trackingSince
                    val start = if (since != null && since.year == year && since.isAfter(it.firstDate)) {
                        "${Fmt.monthDay(since)}起"
                    } else {
                        Fmt.monthDay(it.firstDate)
                    }
                    "$start – ${Fmt.monthDay(it.lastDate)}"
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
                    TdTextButton("另有 $missing 天可补记", fontSize = 12.sp, onClick = onBackfill)
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
private fun TodayCard(data: YearData?, attempt: Prefs.Attempt?, paused: Boolean, onCorrect: () -> Unit) {
    val today = LocalDate.now()
    val punches = data?.punches?.filter { it.localDate == today } ?: emptyList()
    val morning = punches.firstOrNull { it.slot == Slot.MORNING }
    val evening = punches.firstOrNull { it.slot == Slot.EVENING }
    val extra = punches.firstOrNull { it.slot == Slot.EXTRA }
    val hasToday = punches.isNotEmpty() || data?.stats?.days?.containsKey(today) == true

    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "今日打卡 · ${Fmt.monthDay(today)} ${Fmt.weekdayCn(today)}",
                    fontSize = 12.sp, color = Td.Muted, modifier = Modifier.weight(1f),
                )
                if (hasToday) {
                    // 定位/城市库偶有边界误判（如深圳被判成香港），提供一键人工更正
                    TdTextButton("纠正", fontSize = 12.sp, onClick = onCorrect)
                } else {
                    Spacer(Modifier.height(48.dp))
                }
            }
            // 补捕窗口已关的半天不会再自动补上,别再显示「待记录」让人白等
            val pending = PunchRules.pendingSlots(LocalTime.now().hour)
            Row(Modifier.padding(end = 8.dp)) {
                PunchCell(
                    R.drawable.ic_sun, Td.Sunrise, "早 · 07:00", morning,
                    Slot.MORNING in pending, Modifier.weight(1f),
                )
                Box(Modifier.width(1.dp).height(40.dp).background(Td.Border))
                PunchCell(
                    R.drawable.ic_sunset, Td.Faint, "晚 · 17:00", evening,
                    Slot.EVENING in pending, Modifier.weight(1f).padding(start = 16.dp),
                )
            }
            if (extra != null) {
                Text(
                    "首点 ${Fmt.clock(extra)} · ${extra.cityName} ✓（已记录当前位置）",
                    fontSize = 11.sp, color = Td.Faint,
                )
            }
            // 今天还没打完:单个样本先算 0.5 天,说明清楚免得以为少算了
            val todayAttr = data?.stats?.days?.get(today)
            if (todayAttr != null && todayAttr.provisional && todayAttr.shares.isNotEmpty()) {
                Text(
                    if (evening == null) "今天先算半天 · 晚点打上后补满一天" else "今天先算半天 · 早点补上后补满一天",
                    fontSize = 11.sp, color = Td.Faint,
                )
            }
            AttemptLine(attempt, paused)
        }
    }
}

/** 「最近一次尝试 07:02 · 早点 · 已记录 深圳 ｜ 下一次 17:00」:打卡链路是否在工作,一眼可见 */
@Composable
private fun AttemptLine(attempt: Prefs.Attempt?, paused: Boolean) {
    val zone = ZoneId.systemDefault()
    fun hm(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
    val last = attempt?.takeIf {
        Instant.ofEpochMilli(it.atMs).atZone(zone).toLocalDate() == LocalDate.now()
    }?.let { a ->
        val slot = when (a.slot) {
            Slot.MORNING.name -> " · 早点"
            Slot.EVENING.name -> " · 晚点"
            Slot.EXTRA.name -> " · 首点"
            else -> ""
        }
        val result = when (a.result) {
            "ok" -> "已记录 ${a.detail}"
            "no_fix" -> a.detail + (a.retryAtMs?.let { " · ${hm(it)} 再试一次" } ?: "")
            else -> a.detail
        }
        "最近一次尝试 ${hm(a.atMs)}$slot · $result"
    }
    val next = if (paused) {
        "自动打卡已暂停"
    } else {
        val n = PunchRules.nextPunchTime(ZonedDateTime.now())
        (if (n.toLocalDate() == LocalDate.now()) "下一次 " else "下一次 明天 ") + "%02d:%02d".format(n.hour, n.minute)
    }
    Text(
        listOfNotNull(last, next).joinToString("\n"),
        fontSize = 11.sp, color = Td.Faint, lineHeight = 16.sp,
    )
}

@Composable
private fun PunchCell(
    iconRes: Int,
    iconTint: Color,
    label: String,
    punch: Punch?,
    stillPossible: Boolean,
    modifier: Modifier,
) {
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
                // 实际时刻 + 延迟 / 缓存位置标记:被系统推迟或用了旧位置时一眼可见
                val marks = listOfNotNull(
                    Fmt.clock(punch),
                    "延迟".takeIf { punch.delayed },
                    "缓存位置".takeIf { punch.fromCache },
                )
                Text(marks.joinToString(" · "), fontSize = 10.sp, color = Td.Faint)
            } else {
                Text(
                    if (stillPossible) "待记录" else "未记录",
                    fontSize = 14.sp, color = Td.Faint,
                )
            }
        }
    }
}

/** 按国家 / 地区汇总;设了天数上限的地区显示还剩多少天。 */
@Composable
private fun RegionCard(regions: List<Regions.RegionStat>, statuses: List<Thresholds.Status>) {
    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("按国家 / 地区", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted)
            val codes = (regions.map { it.code } + statuses.map { it.threshold.regionCode }).distinct()
            codes.forEach { code ->
                val r = regions.firstOrNull { it.code == code }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Regions.nameOf(code), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
                    if (r != null) {
                        Spacer(Modifier.width(6.dp))
                        Text("${r.cities} 城", fontSize = 11.sp, color = Td.Faint)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        DayCounting.formatDays(r?.days ?: 0.0) + " 天",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Td.AccentDeep,
                    )
                }
                statuses.filter { it.threshold.regionCode == code }.forEach { st ->
                    val color = when (st.level) {
                        Thresholds.Level.OK -> Td.Muted
                        Thresholds.Level.NEAR -> Td.WarmDeep
                        Thresholds.Level.REACHED -> Td.Danger
                    }
                    Text(ThresholdAlerts.describe(st), fontSize = 11.sp, color = color, lineHeight = 16.sp)
                }
            }
        }
    }
}

/** 行程:同城连续的日子合成一段;当前这段单独写「连续第 N 天」。 */
@Composable
private fun StaysCard(stays: List<Stays.Stay>, current: Stays.Stay?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val newestFirst = stays.asReversed()
    val shown = if (expanded) newestFirst else newestFirst.take(5)
    TdCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text("行程", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Td.Muted)
            if (current != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前：${current.cityName} · 连续第 ${current.spanDays} 天",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink,
                )
            }
            Spacer(Modifier.height(6.dp))
            shown.forEachIndexed { i, st ->
                if (i > 0) HorizontalDivider(color = Td.Divider, thickness = 1.dp)
                Row(Modifier.padding(vertical = 9.dp, horizontal = 0.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(st.cityName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink)
                        Text(
                            if (st.from == st.to) Fmt.monthDay(st.from) else "${Fmt.monthDay(st.from)} – ${Fmt.monthDay(st.to)}",
                            fontSize = 11.sp, color = Td.Faint,
                        )
                    }
                    Text(DayCounting.formatDays(st.days) + " 天", fontSize = 13.sp, color = Td.AccentDeep)
                }
            }
            if (stays.size > 5) {
                TdTextButton(if (expanded) "收起" else "展开全部 ${stays.size} 段", fontSize = 12.sp) { expanded = !expanded }
            } else {
                Spacer(Modifier.height(8.dp))
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
                    // 用了一年的人元旦凌晨看到「还没有打卡记录」会以为数据丢了:有往年记录时说清楚
                    val hasEarlier = data.stats.trackingSince?.let { it.year < data.stats.year } == true
                    Text(
                        if (hasEarlier) "${data.stats.year} 年还没有记录" else "还没有打卡记录",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink,
                    )
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
