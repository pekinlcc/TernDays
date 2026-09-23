package app.terndays.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.terndays.core.DayOverride
import app.terndays.core.OverrideScope
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
    /** 该日是否有早/晚两个半天样本:只要有半天样本就允许半天更正 */
    allowHalfScope: Boolean = false,
    /** 该日已有的手动更正:重新打开时要照原样回填,不能把半天更正静默升级成整天 */
    existing: List<DayOverride> = emptyList(),
    /** 这一天有没有打卡:没有的话「恢复自动判定」= 变成无记录,要先确认 */
    hasPunches: Boolean = true,
    onDismiss: () -> Unit,
    onPick: (String, String, OverrideScope) -> Unit,
    onRestoreAuto: (() -> Unit)?,
) {
    var confirmRestore by remember { mutableStateOf(false) }
    // 已经改过半天的日子重新打开时停在那个半天上,否则一次重选就把另半天也吞掉了
    var scope by remember {
        mutableStateOf(
            existing.firstOrNull { it.scope != OverrideScope.FULL }?.scope ?: OverrideScope.FULL,
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        TdCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "更正 ${date.monthValue}月${date.dayOfMonth}日 的城市",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Td.Ink,
                )
                val manualNow = existing.sortedBy { it.scope.name }.joinToString("；") {
                    when (it.scope) {
                        OverrideScope.FULL -> "整天 → ${it.cityName}"
                        OverrideScope.MORNING -> "上半天 → ${it.cityName}"
                        else -> "下半天 → ${it.cityName}"
                    }
                }
                if (manualNow.isNotEmpty()) {
                    Text("已手动更正：$manualNow", fontSize = 12.sp, color = Td.WarmDeep, lineHeight = 18.sp)
                }
                val hint = when {
                    currentCityName.isNullOrBlank() -> "这一天还没有记录，选择城市后按全天补记。"
                    scope == OverrideScope.FULL -> "当前判定：$currentCityName。选正确的城市后，这一天将按所选城市记全天。"
                    scope == OverrideScope.MORNING -> "只改上半天（早上那次）：下半天仍按打卡判定，跨城日可保留各半天。"
                    else -> "只改下半天（傍晚那次）：上半天仍按打卡判定，跨城日可保留各半天。"
                }
                Text(hint, fontSize = 12.sp, color = Td.Muted, lineHeight = 18.sp)

                if (allowHalfScope) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScopeChip("整天", scope == OverrideScope.FULL) { scope = OverrideScope.FULL }
                        ScopeChip("只改上半天", scope == OverrideScope.MORNING) { scope = OverrideScope.MORNING }
                        ScopeChip("只改下半天", scope == OverrideScope.EVENING) { scope = OverrideScope.EVENING }
                    }
                }
                CityPicker(recentCities) { key, name -> onPick(key, name, scope) }
                if (confirmRestore && onRestoreAuto != null) {
                    Text(
                        "这一天没有打卡，恢复后将变为无记录。",
                        fontSize = 12.sp, color = Td.WarmDeep, lineHeight = 18.sp,
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (onRestoreAuto != null) {
                        TdTextButton(
                            if (confirmRestore) "确定恢复" else "恢复自动判定",
                            color = Td.WarmDeep, fontSize = 13.sp,
                        ) {
                            if (hasPunches || confirmRestore) onRestoreAuto() else confirmRestore = true
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TdTextButton("取消", color = Td.Muted, fontSize = 13.sp, onClick = onDismiss)
                }
            }
        }
    }
}

/** 单选芯片(整天 / 半天、单日 / 区间…):读屏念「单选按钮,已选中」 */
@Composable
internal fun ScopeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Td.Accent else Td.NeutralSoft)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Td.OnAccent else Td.Muted,
        )
    }
}
