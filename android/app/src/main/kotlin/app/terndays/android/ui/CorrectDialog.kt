package app.terndays.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.terndays.android.geo.Cities
import app.terndays.core.CityMatcher
import app.terndays.core.OverrideScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 更正某一天的城市：搜索/常去城市里选一个 → 以手动更正（DayOverride）记录，
 * 优先于自动打卡判定；已更正的日子可「恢复自动判定」。
 */
@Composable
fun CityCorrectDialog(
    date: LocalDate,
    currentCityName: String?,
    recentCities: List<Pair<String, String>>,
    /** 该日是否有早/晚两个半天样本:只有跨城日才值得提供半天选择 */
    hasBothHalves: Boolean = false,
    onDismiss: () -> Unit,
    onPick: (String, String, OverrideScope) -> Unit,
    onRestoreAuto: (() -> Unit)?,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(OverrideScope.FULL) }
    val results by produceState(initialValue = emptyList<CityMatcher.SearchHit>(), query) {
        value = if (query.isBlank()) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { Cities.get(context).search(query, 12) }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        TdCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "更正 ${date.monthValue}月${date.dayOfMonth}日 的城市",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Td.Ink,
                )
                val hint = when {
                    currentCityName == null -> "这一天还没有记录，选择城市后按全天补记。"
                    scope == OverrideScope.FULL -> "当前判定：$currentCityName。选正确的城市后，这一天将按所选城市记全天。"
                    scope == OverrideScope.MORNING -> "只改上半天（早上那次）：下半天仍按打卡判定，跨城日可保留各半天。"
                    else -> "只改下半天（傍晚那次）：上半天仍按打卡判定，跨城日可保留各半天。"
                }
                Text(hint, fontSize = 12.sp, color = Td.Muted, lineHeight = 18.sp)

                if (hasBothHalves) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScopeChip("整天", scope == OverrideScope.FULL) { scope = OverrideScope.FULL }
                        ScopeChip("只改上半天", scope == OverrideScope.MORNING) { scope = OverrideScope.MORNING }
                        ScopeChip("只改下半天", scope == OverrideScope.EVENING) { scope = OverrideScope.EVENING }
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("搜索城市名（支持拼音）", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                if (query.isBlank() && recentCities.isNotEmpty()) {
                    Text("常去城市", fontSize = 11.sp, color = Td.Faint)
                }
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    if (query.isBlank()) {
                        items(recentCities) { (key, name) ->
                            Text(
                                name, fontSize = 14.sp, color = Td.Ink,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { onPick(key, name, scope) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    } else {
                        items(results) { hit ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onPick(hit.cityKey, hit.cityName, scope) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(hit.cityName, fontSize = 14.sp, color = Td.Ink)
                                if (hit.region.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(hit.region, fontSize = 12.sp, color = Td.Faint)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (onRestoreAuto != null) {
                        Text(
                            "恢复自动判定", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Td.WarmDeep,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onRestoreAuto).padding(8.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "取消", fontSize = 13.sp, color = Td.Muted,
                        modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) Td.OnAccent else Td.Muted,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Td.Accent else Td.Bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
