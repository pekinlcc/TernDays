package app.terndays.android.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.terndays.core.CityMatcher
import app.terndays.core.DayOverride
import app.terndays.core.HistoryReplay
import app.terndays.core.OverrideScope
import app.terndays.core.Punch
import app.terndays.core.Slot
import java.time.LocalDate

/** 本地 SQLite 存储：打卡记录 + 手动补记。同一 (日期, 时段) 只保留最早插入的一条。 */
class PunchDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "terndays.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE punch(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                local_date TEXT NOT NULL,
                slot TEXT NOT NULL,
                epoch_ms INTEGER NOT NULL,
                zone_id TEXT NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                accuracy REAL,
                city_key TEXT NOT NULL,
                city_name TEXT NOT NULL,
                delayed INTEGER NOT NULL DEFAULT 0,
                from_cache INTEGER NOT NULL DEFAULT 0,
                via_context INTEGER NOT NULL DEFAULT 0,
                UNIQUE(local_date, slot) ON CONFLICT IGNORE
            )""",
        )
        db.execSQL(
            """CREATE TABLE day_override(
                local_date TEXT NOT NULL,
                scope TEXT NOT NULL DEFAULT 'FULL',
                city_key TEXT NOT NULL,
                city_name TEXT NOT NULL,
                PRIMARY KEY(local_date, scope)
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE punch ADD COLUMN via_context INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            // day_override 升级为 (日期, 范围) 复合主键,旧数据全部视为整天更正
            db.execSQL("ALTER TABLE day_override RENAME TO day_override_v2")
            db.execSQL(
                """CREATE TABLE day_override(
                    local_date TEXT NOT NULL,
                    scope TEXT NOT NULL DEFAULT 'FULL',
                    city_key TEXT NOT NULL,
                    city_name TEXT NOT NULL,
                    PRIMARY KEY(local_date, scope)
                )""",
            )
            db.execSQL(
                "INSERT INTO day_override(local_date, scope, city_key, city_name) " +
                    "SELECT local_date, 'FULL', city_key, city_name FROM day_override_v2",
            )
            db.execSQL("DROP TABLE day_override_v2")
        }
    }

    private val punchColumns =
        "local_date, slot, epoch_ms, zone_id, lat, lng, accuracy, city_key, city_name, delayed, from_cache, via_context"

    private fun Cursor.readPunch() = Punch(
        localDate = LocalDate.parse(getString(0)),
        slot = Slot.valueOf(getString(1)),
        epochMs = getLong(2),
        zoneId = getString(3),
        lat = getDouble(4),
        lng = getDouble(5),
        accuracyM = if (isNull(6)) null else getDouble(6),
        cityKey = getString(7),
        cityName = getString(8),
        delayed = getInt(9) == 1,
        fromCache = getInt(10) == 1,
        viaContext = getInt(11) == 1,
    )

    private fun queryPunches(where: String, args: Array<String>?): List<Punch> =
        readableDatabase.rawQuery("SELECT $punchColumns FROM punch $where", args).use { c ->
            val out = ArrayList<Punch>(c.count)
            while (c.moveToNext()) out.add(c.readPunch())
            out
        }

    /** @return true = 新插入；false = 该时段已有记录（被忽略） */
    fun insertPunch(p: Punch): Boolean {
        val values = ContentValues().apply {
            put("local_date", p.localDate.toString())
            put("slot", p.slot.name)
            put("epoch_ms", p.epochMs)
            put("zone_id", p.zoneId)
            put("lat", p.lat)
            put("lng", p.lng)
            put("accuracy", p.accuracyM)
            put("city_key", p.cityKey)
            put("city_name", p.cityName)
            put("delayed", if (p.delayed) 1 else 0)
            put("from_cache", if (p.fromCache) 1 else 0)
            put("via_context", if (p.viaContext) 1 else 0)
        }
        return writableDatabase.insertWithOnConflict("punch", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun hasAnyPunch(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM punch LIMIT 1", null).use { it.moveToFirst() }

    fun hasPunch(date: LocalDate, slot: Slot): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM punch WHERE local_date=? AND slot=? LIMIT 1",
            arrayOf(date.toString(), slot.name),
        ).use { it.moveToFirst() }

    /**
     * 行程连续性锚点:最近一条**非改判**(via_context=0)且解析成功的打卡。
     * 被连续性/误差圈粘住的点不作锚,36h 上限才能真正限制整条粘滞链。
     * 若锚点当日已被手动更正,调用方应以更正城市为准(见 [overrideFor])。
     */
    fun latestAnchorPunch(): Punch? =
        queryPunches("WHERE city_key != 'unknown' AND via_context = 0 ORDER BY epoch_ms DESC LIMIT 1", null)
            .firstOrNull()

    /** 该日的整天更正(锚点参照只认整天更正;半天更正不整体改写当日城市)。 */
    fun overrideFor(date: LocalDate): DayOverride? =
        readableDatabase.rawQuery(
            "SELECT local_date, city_key, city_name, scope FROM day_override WHERE local_date=? AND scope='FULL'",
            arrayOf(date.toString()),
        ).use { c ->
            if (c.moveToNext()) readOverride(c) else null
        }

    private fun readOverride(c: Cursor) = DayOverride(
        localDate = LocalDate.parse(c.getString(0)),
        cityKey = c.getString(1),
        cityName = c.getString(2),
        scope = OverrideScope.valueOf(c.getString(3)),
    )

    fun punchesForYear(year: Int): List<Punch> =
        queryPunches("WHERE local_date LIKE ? ORDER BY local_date, slot", arrayOf("$year-%"))

    fun allPunches(): List<Punch> = queryPunches("ORDER BY local_date, slot", null)

    fun allOverrides(): List<DayOverride> =
        readableDatabase.rawQuery("SELECT local_date, city_key, city_name, scope FROM day_override", null).use { c ->
            val out = ArrayList<DayOverride>(c.count)
            while (c.moveToNext()) out.add(readOverride(c))
            out
        }

    /**
     * 城市库升级/导入后的历史重解析:按时间**重放**并走与实时打卡相同的交叉验证
     * (HistoryReplay),而不是裸最近邻。手动更正不动。@return 城市被修正的记录数。
     */
    fun replayResolveAll(matcher: CityMatcher): Int {
        val items = readableDatabase.rawQuery(
            "SELECT id, local_date, epoch_ms, lat, lng, accuracy, city_key, city_name FROM punch",
            null,
        ).use { c ->
            val out = ArrayList<HistoryReplay.Item>(c.count)
            while (c.moveToNext()) {
                out.add(
                    HistoryReplay.Item(
                        id = c.getLong(0),
                        localDate = LocalDate.parse(c.getString(1)),
                        epochMs = c.getLong(2),
                        lat = c.getDouble(3),
                        lng = c.getDouble(4),
                        accuracyM = if (c.isNull(5)) null else c.getDouble(5),
                        cityKey = c.getString(6),
                        cityName = c.getString(7),
                    ),
                )
            }
            out
        }
        if (items.isEmpty()) return 0
        val overrides = allOverrides().filter { it.scope == OverrideScope.FULL }.associate { it.localDate to it.cityKey }
        val outcomes = HistoryReplay.replay(matcher, items, overrides)

        val db = writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            for (o in outcomes) {
                val values = ContentValues().apply {
                    put("via_context", if (o.viaContext) 1 else 0)
                    if (o.changed) {
                        put("city_key", o.cityKey)
                        put("city_name", o.cityName)
                    }
                }
                db.update("punch", values, "id=?", arrayOf(o.id.toString()))
                if (o.changed) changed++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    data class MergeResult(val punchesAdded: Int, val punchesSkipped: Int, val overridesAdded: Int, val overridesSkipped: Int)

    /**
     * 迁移导入合并:打卡按 (日期, 时段) 去重、手动记录按日期去重,本机已有的一律保留。
     * 单事务执行,失败整体回滚。
     */
    fun mergeImported(punches: List<Punch>, overrides: List<DayOverride>): MergeResult {
        val db = writableDatabase
        var pAdd = 0
        var pSkip = 0
        var oAdd = 0
        var oSkip = 0
        db.beginTransaction()
        try {
            for (p in punches) {
                if (insertPunch(p)) pAdd++ else pSkip++
            }
            for (o in overrides) {
                val exists = db.rawQuery(
                    "SELECT 1 FROM day_override WHERE local_date=? LIMIT 1",
                    arrayOf(o.localDate.toString()),
                ).use { it.moveToFirst() }
                if (exists) {
                    oSkip++
                } else {
                    setOverride(o)
                    oAdd++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return MergeResult(pAdd, pSkip, oAdd, oSkip)
    }

    fun overridesForYear(year: Int): List<DayOverride> =
        readableDatabase.rawQuery(
            "SELECT local_date, city_key, city_name, scope FROM day_override WHERE local_date LIKE ?",
            arrayOf("$year-%"),
        ).use { c ->
            val out = ArrayList<DayOverride>(c.count)
            while (c.moveToNext()) out.add(readOverride(c))
            out
        }

    fun setOverride(o: DayOverride) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // 整天与半天更正互斥:写整天时清掉半天,写半天时清掉整天
            if (o.scope == OverrideScope.FULL) {
                db.delete("day_override", "local_date=?", arrayOf(o.localDate.toString()))
            } else {
                db.delete("day_override", "local_date=? AND scope='FULL'", arrayOf(o.localDate.toString()))
            }
            val values = ContentValues().apply {
                put("local_date", o.localDate.toString())
                put("scope", o.scope.name)
                put("city_key", o.cityKey)
                put("city_name", o.cityName)
            }
            db.insertWithOnConflict("day_override", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 恢复整天自动判定:删除该日全部手动更正。 */
    fun removeOverride(date: LocalDate) {
        writableDatabase.delete("day_override", "local_date=?", arrayOf(date.toString()))
    }

    /** 删除单条(某日某范围)的手动更正。 */
    fun removeOverride(date: LocalDate, scope: OverrideScope) {
        writableDatabase.delete("day_override", "local_date=? AND scope=?", arrayOf(date.toString(), scope.name))
    }

    /** 有数据的年份（含当前年），降序。 */
    fun yearsWithData(currentYear: Int): List<Int> {
        val years = sortedSetOf(currentYear)
        readableDatabase.rawQuery("SELECT DISTINCT substr(local_date,1,4) FROM punch", null).use { c ->
            while (c.moveToNext()) c.getString(0).toIntOrNull()?.let(years::add)
        }
        readableDatabase.rawQuery("SELECT DISTINCT substr(local_date,1,4) FROM day_override", null).use { c ->
            while (c.moveToNext()) c.getString(0).toIntOrNull()?.let(years::add)
        }
        return years.reversed().toList()
    }

    companion object {
        @Volatile private var instance: PunchDb? = null

        fun get(context: Context): PunchDb =
            instance ?: synchronized(this) {
                instance ?: PunchDb(context).also { instance = it }
            }
    }
}
