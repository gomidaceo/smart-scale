package kr.smartscale.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar
import kotlin.math.sqrt

/** 사용자 설정 (프로필·기기·해석 방식) */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var name: String
        get() = sp.getString("name", "") ?: ""
        set(v) { sp.edit().putString("name", v).apply() }
    var male: Boolean
        get() = sp.getBoolean("male", true)
        set(v) { sp.edit().putBoolean("male", v).apply() }
    var birthYear: Int
        get() = sp.getInt("birthYear", 0)
        set(v) { sp.edit().putInt("birthYear", v).apply() }
    var heightCm: Float
        get() = sp.getFloat("height", 0f)
        set(v) { sp.edit().putFloat("height", v).apply() }
    var goal: Float
        get() = sp.getFloat("goal", 0f)
        set(v) { sp.edit().putFloat("goal", v).apply() }
    var deviceAddr: String
        get() = sp.getString("devAddr", "") ?: ""
        set(v) { sp.edit().putString("devAddr", v).apply() }
    var deviceName: String
        get() = sp.getString("devName", "") ?: ""
        set(v) { sp.edit().putString("devName", v).apply() }
    var autoSave: Boolean
        get() = sp.getBoolean("autoSave", true)
        set(v) { sp.edit().putBoolean("autoSave", v).apply() }
    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) { sp.edit().putBoolean("vibrate", v).apply() }

    /** 0 = 자동(YUNMAI 형식), 1 = 사용자 지정 위치 */
    var parseMode: Int
        get() = sp.getInt("parseMode", 0)
        set(v) { sp.edit().putInt("parseMode", v).apply() }
    var customOffset: Int
        get() = sp.getInt("cOffset", 13)
        set(v) { sp.edit().putInt("cOffset", v).apply() }
    var customDiv: Int
        get() = sp.getInt("cDiv", 100)
        set(v) { sp.edit().putInt("cDiv", v).apply() }
    var customLE: Boolean
        get() = sp.getBoolean("cLE", false)
        set(v) { sp.edit().putBoolean("cLE", v).apply() }

    val age: Int
        get() = if (birthYear <= 0) 0 else Calendar.getInstance().get(Calendar.YEAR) - birthYear
    val profileReady: Boolean
        get() = heightCm > 50f && birthYear > 1900
}

/** 측정 기록 1건 */
data class Rec(
    val id: Long,
    val ts: Long,
    val weight: Double,
    val res: Int,
    val sfat: Double?,
    val src: String
)

class Db(ctx: Context) : SQLiteOpenHelper(ctx, "scale.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE rec (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "weight REAL NOT NULL, res INTEGER NOT NULL DEFAULT 0, sfat REAL, src TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(ts: Long, weight: Double, res: Int, sfat: Double?, src: String): Long {
        val cv = ContentValues()
        cv.put("ts", ts)
        cv.put("weight", weight)
        cv.put("res", res)
        if (sfat != null) cv.put("sfat", sfat) else cv.putNull("sfat")
        cv.put("src", src)
        return writableDatabase.insert("rec", null, cv)
    }

    fun all(): List<Rec> {
        val out = ArrayList<Rec>()
        readableDatabase.rawQuery(
            "SELECT id, ts, weight, res, sfat, src FROM rec ORDER BY ts DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Rec(
                        c.getLong(0), c.getLong(1), c.getDouble(2), c.getInt(3),
                        if (c.isNull(4)) null else c.getDouble(4),
                        c.getString(5) ?: ""
                    )
                )
            }
        }
        return out
    }

    fun delete(id: Long) {
        writableDatabase.delete("rec", "id=?", arrayOf(id.toString()))
    }

    fun clear() {
        writableDatabase.delete("rec", null, null)
    }
}

/** 체성분 계산 결과 (가정용 추정치) */
data class Body(
    val bmi: Double,
    val stdWeight: Double,
    val obesity: Double,
    val fatPct: Double?,
    val fatKg: Double?,
    val lbm: Double?,
    val musclePct: Double?,
    val muscleKg: Double?,
    val skeletalPct: Double?,
    val waterPct: Double?,
    val bmr: Double,
    val scaleFat: Double?
)

object BodyCalc {
    /**
     * 임피던스(저항) 기반 체지방률 추정.
     * 오픈소스 openScale 의 YUNMAI 공식을 참고한 근사식이라 공식 앱 수치와 다를 수 있음.
     */
    private fun fatFromRes(age: Int, weight: Double, heightCm: Double, res: Int, male: Boolean): Double? {
        if (res <= 0) return null
        var r = (res - 100.0) / 100.0
        val h = heightCm / 100.0
        if (r >= 1) r = sqrt(r)
        var f = weight * 1.5 / h / h + age * 0.08
        if (male) f -= 10.8
        f = f - 7.4 + r
        return if (f < 3.0 || f > 75.0) null else f
    }

    fun calc(weight: Double, res: Int, scaleFat: Double?, p: Prefs): Body? {
        if (!p.profileReady) return null
        val hCm = p.heightCm.toDouble()
        val h = hCm / 100.0
        val bmi = weight / (h * h)
        val std = 22.0 * h * h
        val obesity = weight / std * 100.0
        val sf = scaleFat?.takeIf { it in 3.0..70.0 }
        val fat = fatFromRes(p.age, weight, hCm, res, p.male) ?: sf

        if (fat == null) {
            // 체지방을 모르면 Mifflin-St Jeor 식으로 기초대사량만
            val bmr = 10 * weight + 6.25 * hCm - 5 * p.age + (if (p.male) 5 else -161)
            return Body(bmi, std, obesity, null, null, null, null, null, null, null, bmr, sf)
        }
        val fatKg = weight * fat / 100.0
        val lbm = weight - fatKg
        val musclePct = (100.0 - fat) * 0.67
        val muscleKg = weight * musclePct / 100.0
        val skeletal = (100.0 - fat) * 0.53
        val water = (100.0 - fat) * 0.726
        val bmr = 370 + 21.6 * lbm // Katch-McArdle
        return Body(bmi, std, obesity, fat, fatKg, lbm, musclePct, muscleKg, skeletal, water, bmr, sf)
    }

    fun bmiLabel(b: Double): String = when {
        b < 18.5 -> "저체중"
        b < 23.0 -> "정상"
        b < 25.0 -> "비만 전단계"
        else -> "비만"
    }

    fun fatRange(male: Boolean): Pair<Double, Double> = if (male) 10.0 to 20.0 else 18.0 to 28.0
    fun waterRange(male: Boolean): Pair<Double, Double> = if (male) 50.0 to 65.0 else 45.0 to 60.0

    /** 0 = 표준 이하, 1 = 표준, 2 = 표준 이상 */
    fun zone(v: Double, lo: Double, hi: Double): Int = when {
        v < lo -> 0
        v <= hi -> 1
        else -> 2
    }

    /** 화면에 보여줄 항목 목록 (섹션별) */
    fun metrics(weight: Double, b: Body, male: Boolean): List<Pair<String, List<Metric>>> {
        val (flo, fhi) = fatRange(male)
        val (wlo, whi) = waterRange(male)
        val comp = ArrayList<Metric>()
        comp.add(Metric("체중", weight, "kg", 2, b.stdWeight * 0.85, b.stdWeight * 1.15,
            "표준 ${f1(b.stdWeight * 0.85)}~${f1(b.stdWeight * 1.15)}kg (표준체중 ${f1(b.stdWeight)}kg의 85~115%)"))
        if (b.fatKg != null) comp.add(Metric("체지방량", b.fatKg, "kg", 1, weight * flo / 100, weight * fhi / 100,
            "표준 ${f1(weight * flo / 100)}~${f1(weight * fhi / 100)}kg (체중의 ${flo.toInt()}~${fhi.toInt()}%)"))
        if (b.muscleKg != null) comp.add(Metric("근육량", b.muscleKg, "kg", 1, null, null,
            "체지방을 뺀 몸에서 근육이 차지하는 양의 추정치"))
        if (b.waterPct != null) comp.add(Metric("체수분", b.waterPct, "%", 1, wlo, whi,
            "표준 ${wlo.toInt()}~${whi.toInt()}%"))

        val obe = ArrayList<Metric>()
        obe.add(Metric("BMI", b.bmi, "", 1, 18.5, 22.9,
            "정상 18.5~22.9 · 23~24.9 비만 전단계 · 25 이상 비만", bmiLabel(b.bmi)))
        if (b.fatPct != null) obe.add(Metric("체지방률", b.fatPct, "%", 1, flo, fhi,
            "${if (male) "남성" else "여성"} 표준 ${flo.toInt()}~${fhi.toInt()}%"))
        obe.add(Metric("비만도", b.obesity, "%", 1, 90.0, 110.0, "표준체중 대비 90~110%가 표준"))

        val meta = ArrayList<Metric>()
        meta.add(Metric("기초대사량", b.bmr, "kcal", 0, null, null,
            "가만히 있어도 하루에 쓰는 에너지. 근육이 늘면 함께 올라가요"))
        if (b.skeletalPct != null) meta.add(Metric("골격근 비율", b.skeletalPct, "%", 1, null, null,
            "체중 대비 골격근 비율 추정치"))

        return listOf("체성분 구성" to comp, "비만 진단" to obe, "대사" to meta)
    }

    private fun f1(v: Double) = String.format(java.util.Locale.KOREA, "%.1f", v)

    /** 체형 분석: BMI 구간 × 체지방률 구간 */
    val typeLabels = arrayOf(
        arrayOf("마른형", "근육형", "운동선수형"),       // 체지방 낮음
        arrayOf("저체중", "표준", "근육형 과체중"),       // 체지방 표준
        arrayOf("마른 비만", "비만 경계", "비만")          // 체지방 높음
    )
    val typeNotes = arrayOf(
        arrayOf(
            "체지방과 체중이 모두 적어요. 근력 운동과 단백질 섭취로 근육을 늘려 보세요.",
            "체지방은 낮고 체중은 표준이에요. 근육이 잘 발달한 건강한 체형이에요.",
            "체중은 많지만 체지방이 낮아요. 근육량이 아주 많은 체형이에요."
        ),
        arrayOf(
            "체지방은 표준이지만 체중이 적어요. 근육량을 늘리면 좋아요.",
            "체중과 체지방률이 모두 표준이에요. 지금 상태를 유지하세요.",
            "BMI는 높지만 체지방률이 표준이라, 체중 대비 근육이 많은 편이에요."
        ),
        arrayOf(
            "체중은 적은데 체지방 비율이 높아요. 근력 운동으로 근육을 늘려 보세요.",
            "체중은 표준이지만 체지방이 많아요. 유산소와 근력 운동을 함께 해보세요.",
            "체중과 체지방이 모두 많아요. 식단 조절과 꾸준한 운동이 필요해요."
        )
    )

    /** 체형 칸 (fatRow, bmiCol) — 체지방률이 없으면 null */
    fun bodyType(b: Body, male: Boolean): Pair<Int, Int>? {
        val f = b.fatPct ?: return null
        val (flo, fhi) = fatRange(male)
        val row = zone(f, flo, fhi)
        val col = when {
            b.bmi < 18.5 -> 0
            b.bmi < 23.0 -> 1
            else -> 2
        }
        return row to col
    }
}

/** 화면 표시용 항목. lo/hi 가 null 이면 기준 없는 참고값 */
data class Metric(
    val name: String,
    val value: Double,
    val unit: String,
    val decimals: Int,
    val lo: Double?,
    val hi: Double?,
    val note: String,
    val customGrade: String? = null
) {
    val hasRange: Boolean get() = lo != null && hi != null
    val zone: Int get() = if (lo != null && hi != null) BodyCalc.zone(value, lo, hi) else -1
    val grade: String
        get() = customGrade ?: when (zone) {
            0 -> "표준 이하"
            1 -> "표준"
            2 -> "표준 이상"
            else -> "참고"
        }
    fun text(): String {
        val v = when (decimals) {
            0 -> String.format(java.util.Locale.KOREA, "%,d", value.toInt())
            2 -> String.format(java.util.Locale.KOREA, "%.2f", value)
            else -> String.format(java.util.Locale.KOREA, "%.1f", value)
        }
        return if (unit.isEmpty()) v else "$v $unit"
    }
}

/** 체중계에서 받은 패킷 해석 결과 */
data class Packet(val final: Boolean, val weight: Double, val res: Int, val scaleFat: Double?)

object Parser {
    fun u16be(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    fun u16le(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)

    fun hex(b: ByteArray): String = b.joinToString(" ") { String.format("%02x", it.toInt() and 0xff) }

    fun parse(b: ByteArray, p: Prefs): Packet? {
        if (p.parseMode == 1) {
            val o = p.customOffset
            if (o < 0 || b.size < o + 2) return null
            val raw = if (p.customLE) u16le(b, o) else u16be(b, o)
            val w = raw.toDouble() / p.customDiv
            return if (w in 2.0..300.0) Packet(false, w, 0, null) else null
        }
        // YUNMAI 형식: 0x0D 로 시작, 4번째 바이트가 종류 (0x01 측정 중, 0x02 최종)
        if (b.size < 10 || (b[0].toInt() and 0xff) != 0x0D) return null
        val type = b[3].toInt() and 0xff
        if (type == 0x02 && b.size >= 17) {
            val w = u16be(b, 13) / 100.0
            val res = u16be(b, 15)
            val f: Double? = if (b.size >= 19) u16be(b, 17) / 100.0 else null
            return if (w in 2.0..300.0) Packet(true, w, res, f) else null
        }
        if (type == 0x01) {
            val w = u16be(b, 8) / 100.0
            return if (w in 2.0..300.0) Packet(false, w, 0, null) else null
        }
        return null
    }

    data class Cand(val offset: Int, val le: Boolean, val div: Int, val value: Double)

    /** 보정 도구: 패킷 안에서 체중일 수 있는 모든 위치 */
    fun candidates(b: ByteArray): List<Cand> {
        val out = ArrayList<Cand>()
        for (i in 0 until b.size - 1) {
            for (le in listOf(false, true)) {
                val raw = if (le) u16le(b, i) else u16be(b, i)
                for (div in listOf(100, 10, 20, 200)) {
                    val v = raw.toDouble() / div
                    if (v in 2.0..300.0) out.add(Cand(i, le, div, v))
                }
            }
        }
        return out
    }
}
