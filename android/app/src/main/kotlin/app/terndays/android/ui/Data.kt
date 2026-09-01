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
    val stats = DayCounting.computeYearStats(year, LocalDate.now(), punches, overrides)
    YearData(stats, punches, overrides, db.yearsWithData(LocalDate.now().year))
}
