package app.terndays.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.terndays.core.Backfill
import app.terndays.core.DayOverride
import app.terndays.core.Fmt
import app.terndays.core.OverrideScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 补记:
 *  - 单日:日期不再只能从「无记录列表」里挑(此前受开始日与年份限制,更早的日子补不了);
 *    写完不关弹窗,可以接着补下一天;
 *  - 区间:出差回来一次补一整段,首日可选「下午才到」、末日可选「中午就走」(:core Backfill.planRange);
 *  - 选城市时把前一天、后一天记的城市排在最前(多半就是它)。
 * 写库走 [Corrections],自带「撤销」。
 */
@Composable
internal fun BackfillDialog(
    unrecorded: List<LocalDate>,
    recentCities: List<Pair<String, String>>,
    trackingSince: LocalDate?,
    /** 某天前后一天记的城市(建议置顶) */
    neighbors: (LocalDate) -> List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var rangeMode by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf(unrecorded.maxOrNull() ?: today.minusDays(1)) }
    var to by rememberSaveable { mutableStateOf(unrecorded.maxOrNull() ?: today.minusDays(1)) }
    var arriveAfternoon by rememberSaveable { mutableStateOf(false) }
    var leaveNoon by rememberSaveable { mutableStateOf(false) }
    var done by rememberSaveable { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<String?>(null) } // "from" / "to"

    fun write(key: String, name: String) {
        error = null
        val plan = try {
            if (rangeMode) {
                Backfill.planRange(
                    from, to, key, name,
                    startScope = if (arriveAfternoon) OverrideScope.EVENING else OverrideScope.FULL,
                    endScope = if (leaveNoon) OverrideScope.MORNING else OverrideScope.FULL,
                )
            } else {
                listOf(DayOverride(from, key, name))
            }
        } catch (e: IllegalArgumentException) {
            error = e.message
            return
        }
        val label = if (rangeMode && from != to) {
            "${Fmt.monthDay(from)} – ${Fmt.monthDay(to)}"
        } else {
            Fmt.monthDay(from)
        }
        val earlier = trackingSince == null || from.isBefore(trackingSince)
        scope.launch { Corrections.apply(context, plan, "已补记 $label · $name") }
        if (rangeMode) {
            onDismiss()
        } else {
            // 单日:不关弹窗,接着补下一天(换成下一个还没补的日子)
            done = "已补记 $label · $name" +
                if (earlier) "\n开始记录日提前到 ${Fmt.monthDay(from)}，之后没有记录的日子会算作可补记" else ""
            unrecorded.filter { it != from && it.isBefore(from) }.maxOrNull()?.let {
                from = it
                to = it
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        TdCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(18.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("补记", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Td.Ink)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ScopeChip("单日", !rangeMode) { rangeMode = false }
                    ScopeChip("一段日子", rangeMode) { rangeMode = true; if (to.isBefore(from)) to = from }
                }
                if (rangeMode) {
                    DateRow("开始", from) { picking = "from" }
                    DateRow("结束", to) { picking = "to" }
                    SwitchRow("首日下午才到", "首日只记下半天", arriveAfternoon) { arriveAfternoon = it }
                    SwitchRow("末日中午就走", "末日只记上半天", leaveNoon) { leaveNoon = it }
                } else {
                    DateRow("日期", from) { picking = "from" }
                    if (unrecorded.isNotEmpty()) {
                        Text("还没有记录的日子", fontSize = 11.sp, color = Td.Faint)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            unrecorded.sortedDescending().take(14).forEach { d ->
                                ScopeChip(Fmt.monthDay(d), d == from) { from = d; to = d }
                            }
                        }
                    }
                }
                done?.let { Text(it, fontSize = 12.sp, color = Td.AccentDeep, lineHeight = 18.sp) }
                error?.let { Text(it, fontSize = 12.sp, color = Td.Danger) }
                Text(
                    if (rangeMode) "这段日子在哪个城市？" else "${Fmt.monthDay(from)} 在哪个城市？",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Td.Ink,
                )
                CityPicker(
                    suggestions = (neighbors(from) + neighbors(to) + recentCities).distinctBy { it.first },
                    suggestionTitle = "前后几天的城市 · 常去城市",
                    maxHeight = 220.dp,
                ) { key, name -> write(key, name) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TdTextButton(if (done != null) "完成" else "取消", color = Td.Muted, onClick = onDismiss)
                }
            }
        }
    }

    picking?.let { which ->
        DatePick(
            initial = if (which == "from") from else to,
            max = today,
            onDismiss = { picking = null },
        ) { picked ->
            picking = null
            if (which == "from") {
                from = picked
                if (!rangeMode || to.isBefore(picked)) to = picked
            } else {
                to = picked
                if (from.isAfter(picked)) from = picked
            }
        }
    }
}

@Composable
internal fun DateRow(label: String, date: LocalDate, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = Td.Muted, modifier = Modifier.weight(1f))
        Text(
            "${date.year} 年 ${Fmt.monthDay(date)} ${Fmt.weekdayCn(date)}",
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.AccentDeep,
        )
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = Td.Ink)
            Text(sub, fontSize = 11.sp, color = Td.Faint)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 系统风格的日期选择(不晚于 max);DatePicker 以 UTC 零点毫秒表示日期。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DatePick(initial: LocalDate, max: LocalDate, onDismiss: () -> Unit, onPicked: (LocalDate) -> Unit) {
    fun toMs(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val maxMs = toMs(max)
    val state = rememberDatePickerState(
        initialSelectedDateMillis = toMs(initial),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= maxMs
            override fun isSelectableYear(year: Int): Boolean = year <= max.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TdTextButton("确定") {
                state.selectedDateMillis?.let {
                    onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                } ?: onDismiss()
            }
        },
        dismissButton = { TdTextButton("取消", color = Td.Muted, onClick = onDismiss) },
    ) {
        DatePicker(state = state)
    }
}
