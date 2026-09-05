package app.terndays.android.ui

import android.content.Context
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
    // 传入当前小时:今天还没打完的半天不算漏记,单点先按 0.5 天计
    val stats = DayCounting.computeYearStats(year, LocalDate.now(), punches, overrides, LocalTime.now().hour)
    YearData(stats, punches, overrides, db.yearsWithData(LocalDate.now().year))
}
