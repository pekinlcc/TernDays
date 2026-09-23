package app.terndays.android.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.terndays.android.DataBus
import app.terndays.android.db.PunchDb
import app.terndays.core.DayCounting
import app.terndays.core.DayOverride
import app.terndays.core.Punch
import app.terndays.core.YearStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

data class YearData(
    val stats: YearStats,
    val punches: List<Punch>,
    val overrides: List<DayOverride>,
    val years: List<Int>,
)

suspend fun loadYearData(context: Context, year: Int): YearData = withContext(Dispatchers.IO) {
    val db = PunchDb.get(context)
    val punches = db.punchesForYear(year)
    val overrides = db.overridesForYear(year)
    // nowHour:今天还没打完的半天不算漏记,单点先按 0.5 天计
    // (导出走的是同一份统计,所以导出里的今天也是 0.5,并在备注里标「今天进行中」)
    // earliestRecordDate:跨年后 1 月初的漏记要靠全库最早记录才认得出来
    val stats = DayCounting.computeYearStats(
        year, LocalDate.now(), punches, overrides,
        nowHour = LocalTime.now().hour,
        earliestRecordDate = db.earliestRecordDate(),
    )
    YearData(stats, punches, overrides, db.yearsWithData(LocalDate.now().year))
}

/**
 * 一年数据的读取状态。data == null && !failed 是加载中。
 * 读库异常(存储损坏、磁盘满…)此前会直接抛到协程里把应用带崩;现在变成可重试的错误态。
 */
class YearDataState(val data: YearData?, val failed: Boolean, val retry: () -> Unit)

/** 四个页面共用的读取入口:数据变更(DataBus)、重试、调用方给的 keys 都会触发重读。 */
@Composable
fun rememberYearData(year: Int, vararg keys: Any?): YearDataState {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    val dataVersion = DataBus.version.intValue
    val result by produceState<Result<YearData>?>(null, year, dataVersion, attempt, *keys) {
        value = runCatching { loadYearData(context, year) }
    }
    return YearDataState(
        data = result?.getOrNull(),
        failed = result?.isFailure == true,
        retry = { attempt++ },
    )
}

/** 读取失败的卡片:说明 + 重试。 */
@Composable
fun LoadErrorCard(onRetry: () -> Unit) {
    TdCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(role = Role.Button, onClick = onRetry)
                .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("读取失败", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.WarmDeep)
            Spacer(Modifier.weight(1f))
            Text("重试", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Td.AccentDeep)
        }
    }
}
