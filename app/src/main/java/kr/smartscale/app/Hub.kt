package kr.smartscale.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** 방금 측정한 값 (저장 전/후) */
data class Measure(
    val ts: Long,
    val weight: Double,
    var res: Int,
    val sfat: Double?,
    var saved: Boolean = false,
    var uid: Int = -1,          // -1 = 아직 누군지 모름
    var recId: Long = -1,
    var rid: String = "",
    var auto: Boolean = false    // 체중으로 자동 판단했는지
)

/**
 * 앱 전체가 함께 쓰는 측정 엔진.
 * 백그라운드 서비스와 화면(Activity)이 같은 상태를 공유한다.
 */
@SuppressLint("StaticFieldLeak")
@Suppress("DEPRECATION")
object Hub : ScaleBle.Listener {
    const val EV_STATE = 1
    const val EV_LIVE = 2
    const val EV_FINAL = 3
    const val EV_DATA = 4
    const val EV_ASK = 5

    interface UiListener { fun onHubChanged(kind: Int) }

    lateinit var app: Context
    lateinit var prefs: Prefs
    lateinit var db: Db
    lateinit var store: ProfileStore
    var ble: ScaleBle? = null
    private var inited = false

    var statusText = "등록된 체중계가 없어요"
    var bleConnected = false
    var liveWeight: Double? = null
    var liveStart = 0L
    var lastMeasure: Measure? = null
    var lastPacket: ByteArray? = null
    val logs = ArrayDeque<String>()
    private val recent = ArrayList<Pair<Long, Double>>()
    var activityVisible = false

    val listeners = LinkedHashSet<UiListener>()

    fun init(ctx: Context) {
        if (inited) return
        app = ctx.applicationContext
        prefs = Prefs(app)
        db = Db(app)
        store = ProfileStore(app)
        store.all()
        ble = ScaleBle(app, this)
        Notifier.channels(app)
        inited = true
    }

    fun emit(kind: Int) {
        for (l in listeners.toList()) l.onHubChanged(kind)
    }

    fun connect() {
        val a = prefs.deviceAddr
        if (a.isNotEmpty()) ble?.connect(a)
    }

    fun addLog(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        logs.addLast("[$t] $msg")
        while (logs.size > 600) logs.removeFirst()
    }

    // ── BLE

    override fun onBleState(text: String, connected: Boolean) {
        statusText = text
        bleConnected = connected
        emit(EV_STATE)
    }

    override fun onBleLog(msg: String) = addLog(msg)

    override fun onBleData(uuid: String, data: ByteArray) {
        lastPacket = data
        val short = if (uuid.length >= 8) uuid.substring(4, 8) else uuid
        addLog("← $short (${data.size}B) ${Parser.hex(data)}")
        val p = Parser.parse(data, prefs) ?: return
        if (!prefs.knownScales.contains(prefs.deviceAddr)) prefs.addKnownScale(prefs.deviceAddr)
        if (p.final) { onFinal(p); return }
        val lm = lastMeasure
        if (lm != null && System.currentTimeMillis() - lm.ts < 8000) return
        val wasLive = liveWeight != null && lastMeasure == null
        lastMeasure = null
        if (!wasLive) liveStart = System.currentTimeMillis()
        liveWeight = p.weight
        if (prefs.parseMode == 1) checkStable(p.weight)
        if (lastMeasure == null) emit(EV_LIVE)
    }

    private fun checkStable(w: Double) {
        val now = System.currentTimeMillis()
        recent.add(now to w)
        recent.removeAll { now - it.first > 3000 }
        if (recent.size >= 4) {
            val vals = recent.map { it.second }
            if (vals.max() - vals.min() <= 0.05 && now - recent.first().first >= 1500) {
                recent.clear()
                onFinal(Packet(true, vals.average(), 0, null))
            }
        }
    }

    /** 체중으로 누구인지 추정. 애매하면 null */
    fun guessUser(w: Double): Int? {
        val people = store.all()
        if (people.size == 1) return people[0].id
        val now = System.currentTimeMillis()
        val cands = people.mapNotNull { p ->
            db.all(p.id).firstOrNull { it.ts > now - 120L * 86_400_000L }?.let { p.id to abs(it.weight - w) }
        }.sortedBy { it.second }
        if (cands.isEmpty()) return null
        val best = cands[0]
        if (best.second > 3.0) return null
        if (cands.size > 1 && cands[1].second - best.second < 1.5) return null
        return best.first
    }

    private fun onFinal(p: Packet) {
        val now = System.currentTimeMillis()
        val lm = lastMeasure
        if (lm != null && now - lm.ts < 20_000 && abs(lm.weight - p.weight) < 0.3) {
            if (p.res > 0 && lm.res == 0) {
                lm.res = p.res
                if (lm.saved) {
                    // 임피던스가 늦게 온 경우: 기록 교체
                    db.delete(lm.recId)
                    Sync.delete(lm.rid)
                    lm.saved = false
                    save(lm, notify = false)
                }
                emit(EV_FINAL)
            }
            return
        }
        liveWeight = null
        val m = Measure(now, p.weight, p.res, p.scaleFat)
        lastMeasure = m
        addLog("최종값 ${p.weight}kg, 임피던스 ${p.res}")
        if (activityVisible && prefs.vibrate) vibrate()

        val guess = guessUser(p.weight)
        if (guess != null) {
            m.uid = guess
            m.auto = store.all().size > 1
            if (activityVisible) store.currentId = guess
            if (prefs.autoSave || !activityVisible) save(m, notify = true)
            emit(EV_FINAL)
        } else {
            emit(EV_FINAL)
            if (activityVisible) emit(EV_ASK)
            else Notifier.askWho(app, m, store.all())
        }
    }

    /** 측정을 기록으로 저장하고 동기화·알림 */
    fun save(m: Measure, notify: Boolean = true) {
        if (m.saved || m.uid < 0) return
        val r = db.insert(m.ts, m.weight, m.res, m.sfat, "체중계", m.uid)
        m.saved = true
        m.recId = r.id
        m.rid = r.rid
        Sync.upload(r)
        if (notify && !activityVisible) notifyFor(r)
        emit(EV_DATA)
    }

    /** 이 폰 주인의 기록이면(또는 동기화를 안 쓰면) 이 폰에 알림 */
    fun notifyFor(r: Rec) {
        val owner = prefs.ownerUid
        if (!Sync.active || owner == r.uid || owner <= 0) {
            val p = store.get(r.uid) ?: return
            Notifier.measured(app, r, p)
        }
    }

    /** 측정의 주인을 지정/변경 */
    fun assign(m: Measure, uid: Int) {
        m.uid = uid
        m.auto = false
        store.currentId = uid
        if (!m.saved) save(m, notify = !activityVisible)
        else {
            db.setUid(m.recId, uid)
            Sync.setUid(m.rid, uid)
            emit(EV_DATA)
        }
    }

    fun reassignRecord(r: Rec, uid: Int) {
        db.setUid(r.id, uid)
        Sync.setUid(r.rid, uid)
        val m = lastMeasure
        if (m != null && m.recId == r.id) m.uid = uid
        emit(EV_DATA)
    }

    fun deleteRecord(r: Rec) {
        db.delete(r.id)
        Sync.delete(r.rid)
        val m = lastMeasure
        if (m != null && m.recId == r.id) lastMeasure = null
        emit(EV_DATA)
    }

    fun addManual(weight: Double, uid: Int) {
        val r = db.insert(System.currentTimeMillis(), weight, 0, null, "수동", uid)
        Sync.upload(r)
        emit(EV_DATA)
    }

    fun profilesChanged() {
        Sync.pushProfiles(store.raw())
        emit(EV_DATA)
    }

    private fun vibrate() {
        try {
            val v = app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {}
    }
}
