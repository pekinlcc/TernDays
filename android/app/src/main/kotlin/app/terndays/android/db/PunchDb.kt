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
