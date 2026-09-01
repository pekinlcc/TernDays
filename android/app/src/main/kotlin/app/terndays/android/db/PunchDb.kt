package app.terndays.android.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.terndays.core.DayOverride
import app.terndays.core.Punch
import app.terndays.core.Slot
import java.time.LocalDate

/** 本地 SQLite 存储：打卡记录 + 手动补记。同一 (日期, 时段) 只保留最早插入的一条。 */
class PunchDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "terndays.db", null, 1) {

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
                UNIQUE(local_date, slot) ON CONFLICT IGNORE
            )""",
        )
        db.execSQL(
            """CREATE TABLE day_override(
                local_date TEXT PRIMARY KEY,
                city_key TEXT NOT NULL,
                city_name TEXT NOT NULL
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
        }
        return writableDatabase.insertWithOnConflict("punch", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun hasAnyPunch(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM punch LIMIT 1", null).use { it.moveToFirst() }

    /** 最近一次成功解析的打卡（按打卡时间），供城市判定的行程连续性交叉验证。 */
    fun latestResolvedPunch(): Punch? =
        readableDatabase.rawQuery(
            "SELECT local_date, slot, epoch_ms, zone_id, lat, lng, accuracy, city_key, city_name, delayed, from_cache " +
                "FROM punch WHERE city_key != 'unknown' ORDER BY epoch_ms DESC LIMIT 1",
            null,
        ).use { c ->
            if (!c.moveToNext()) return null
            Punch(
                localDate = LocalDate.parse(c.getString(0)),
                slot = Slot.valueOf(c.getString(1)),
                epochMs = c.getLong(2),
                zoneId = c.getString(3),
                lat = c.getDouble(4),
                lng = c.getDouble(5),
                accuracyM = if (c.isNull(6)) null else c.getDouble(6),
                cityKey = c.getString(7),
                cityName = c.getString(8),
                delayed = c.getInt(9) == 1,
                fromCache = c.getInt(10) == 1,
            )
        }

    /**
     * 用当前城市库按原始坐标重解析全部打卡（城市库升级后修正历史误判）。
     * 手动更正（day_override）不受影响。@return 实际改动的记录数。
     */
    fun remapCities(mapper: (lat: Double, lng: Double) -> Pair<String, String>?): Int {
        val db = writableDatabase
        var changed = 0
        db.beginTransaction()
        try {
            val updates = ArrayList<Triple<Long, String, String>>()
            db.rawQuery("SELECT id, lat, lng, city_key, city_name FROM punch", null).use { c ->
                while (c.moveToNext()) {
                    val (key, name) = mapper(c.getDouble(1), c.getDouble(2)) ?: continue
                    if (key != c.getString(3) || name != c.getString(4)) {
                        updates.add(Triple(c.getLong(0), key, name))
                    }
                }
            }
            for ((id, key, name) in updates) {
                val values = ContentValues().apply {
                    put("city_key", key)
                    put("city_name", name)
                }
                changed += db.update("punch", values, "id=?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return changed
    }

    fun hasPunch(date: LocalDate, slot: Slot): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM punch WHERE local_date=? AND slot=? LIMIT 1",
            arrayOf(date.toString(), slot.name),
        ).use { it.moveToFirst() }

    fun punchesForYear(year: Int): List<Punch> =
        readableDatabase.rawQuery(
            "SELECT local_date, slot, epoch_ms, zone_id, lat, lng, accuracy, city_key, city_name, delayed, from_cache " +
                "FROM punch WHERE local_date LIKE ? ORDER BY local_date, slot",
            arrayOf("$year-%"),
        ).use { c ->
            val out = ArrayList<Punch>(c.count)
            while (c.moveToNext()) {
                out.add(
                    Punch(
                        localDate = LocalDate.parse(c.getString(0)),
                        slot = Slot.valueOf(c.getString(1)),
                        epochMs = c.getLong(2),
                        zoneId = c.getString(3),
                        lat = c.getDouble(4),
                        lng = c.getDouble(5),
                        accuracyM = if (c.isNull(6)) null else c.getDouble(6),
                        cityKey = c.getString(7),
                        cityName = c.getString(8),
                        delayed = c.getInt(9) == 1,
                        fromCache = c.getInt(10) == 1,
                    ),
                )
            }
            out
        }

    fun overridesForYear(year: Int): List<DayOverride> =
        readableDatabase.rawQuery(
            "SELECT local_date, city_key, city_name FROM day_override WHERE local_date LIKE ?",
            arrayOf("$year-%"),
        ).use { c ->
            val out = ArrayList<DayOverride>(c.count)
            while (c.moveToNext()) {
                out.add(DayOverride(LocalDate.parse(c.getString(0)), c.getString(1), c.getString(2)))
            }
            out
        }

    fun allPunches(): List<Punch> =
        readableDatabase.rawQuery(
            "SELECT local_date, slot, epoch_ms, zone_id, lat, lng, accuracy, city_key, city_name, delayed, from_cache " +
                "FROM punch ORDER BY local_date, slot",
            null,
        ).use { c ->
            val out = ArrayList<Punch>(c.count)
            while (c.moveToNext()) {
                out.add(
                    Punch(
                        localDate = LocalDate.parse(c.getString(0)),
                        slot = Slot.valueOf(c.getString(1)),
                        epochMs = c.getLong(2),
                        zoneId = c.getString(3),
                        lat = c.getDouble(4),
                        lng = c.getDouble(5),
                        accuracyM = if (c.isNull(6)) null else c.getDouble(6),
                        cityKey = c.getString(7),
                        cityName = c.getString(8),
                        delayed = c.getInt(9) == 1,
                        fromCache = c.getInt(10) == 1,
                    ),
                )
            }
            out
        }

    fun allOverrides(): List<DayOverride> =
        readableDatabase.rawQuery("SELECT local_date, city_key, city_name FROM day_override", null).use { c ->
            val out = ArrayList<DayOverride>(c.count)
            while (c.moveToNext()) {
                out.add(DayOverride(LocalDate.parse(c.getString(0)), c.getString(1), c.getString(2)))
            }
            out
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

    fun setOverride(o: DayOverride) {
        val values = ContentValues().apply {
            put("local_date", o.localDate.toString())
            put("city_key", o.cityKey)
            put("city_name", o.cityName)
        }
        writableDatabase.insertWithOnConflict("day_override", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeOverride(date: LocalDate) {
        writableDatabase.delete("day_override", "local_date=?", arrayOf(date.toString()))
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
