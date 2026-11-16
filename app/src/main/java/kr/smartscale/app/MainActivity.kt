package kr.smartscale.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Ui {
    const val BG = 0xFFF4F7F6.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val FIG_BG = 0xFFF2F6F5.toInt()
    const val TEXT = 0xFF14201C.toInt()
    const val TEXT2 = 0xFF3D4B47.toInt()
    const val SUB = 0xFF5B6B66.toInt()
    const val LINE = 0xFFE6ECEA.toInt()
    const val TEAL = 0xFF12A594.toInt()
    const val TEAL_DARK = 0xFF0B7F72.toInt()
    const val TEAL_BG = 0xFFE3F5F2.toInt()
    const val LOW_BG = 0xFFCFE0F5.toInt()
    const val STD_BG = 0xFFCDEBE6.toInt()
    const val HIGH_BG = 0xFFF8DDBE.toInt()
    const val LOW_ACC = 0xFF3B82F6.toInt()
    const val HIGH_ACC = 0xFFE8891C.toInt()
    const val LOW_CHIP = 0xFFE4EEFB.toInt()
    const val LOW_TEXT = 0xFF1D4E89.toInt()
    const val HIGH_CHIP = 0xFFFDEBD6.toInt()
    const val HIGH_TEXT = 0xFF9A4A06.toInt()
    const val INFO_CHIP = 0xFFEEF2F1.toInt()
    const val SOFT = 0xFFF1F5F4.toInt()
    const val DARK = 0xFF0F1A17.toInt()
    const val DARK2 = 0xFF16241F.toInt()
    const val MINT = 0xFF2DD4BF.toInt()
    const val MUTED_DARK = 0xFF9FB3AD.toInt()
    const val ORANGE = 0xFFE8891C.toInt()
}

@SuppressLint("MissingPermission", "SetTextI18n")
@Suppress("DEPRECATION")
class MainActivity : Activity(), Hub.UiListener {

    private val REQ_PERM = 1
    private val REQ_CSV = 2
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private val prefs: Prefs get() = Hub.prefs
    private val db: Db get() = Hub.db
    private val store: ProfileStore get() = Hub.store
    private val me: Profile get() = Hub.store.current()
    private fun profileOf(uid: Int): Profile = Hub.store.get(uid) ?: me
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var content: FrameLayout
    private val tabs = ArrayList<TextView>()
    private var currentTab = 0
    private var showingDetail = false


    private var vLiveWeight: TextView? = null
    private var vRing: RingView? = null
    private var vConn: TextView? = null

    private var pendingPerm: (() -> Unit)? = null
    private var graphDays = 30
    private var graphMetric = 0


    private val dateHead = SimpleDateFormat("M월 d일 EEEE · a h:mm", Locale.KOREA)
    private val fullDate = SimpleDateFormat("M월 d일 (E) a h:mm", Locale.KOREA)
    private val timeOnly = SimpleDateFormat("a h:mm", Locale.KOREA)

    // ───────────────────────── 생명주기

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.init(this)

        window.statusBarColor = Ui.BG
        window.navigationBarColor = Color.WHITE

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val line = View(this)
        line.setBackgroundColor(Ui.LINE)
        root.addView(line, LinearLayout.LayoutParams(MATCH, 1))
        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(Color.WHITE)
        nav.setPadding(dp(4), dp(8), dp(4), dp(10))
        listOf("홈", "기록", "추이", "내 정보").forEachIndexed { i, t ->
            val tv = TextView(this)
            tv.text = t
            tv.gravity = Gravity.CENTER
            tv.textSize = 12f
            tv.compoundDrawablePadding = dp(3)
            tv.setCompoundDrawablesWithIntrinsicBounds(null, NavIcon(this, i), null, null)
            tv.setPadding(0, dp(4), 0, dp(4))
            tv.setOnClickListener { showingDetail = false; showTab(i) }
            tabs.add(tv)
            nav.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(MATCH, WRAP))
        setContentView(root)

        if (!me.profileReady) {
            showTab(3)
            toast("먼저 내 정보(키·출생연도·성별)를 입력해 주세요")
        } else showTab(0)

        if (prefs.deviceAddr.isNotEmpty() || prefs.familyCode.isNotEmpty()) {
            ensurePerms { ensureBtOn { startBackground() } }
        }
    }

    override fun onResume() {
        super.onResume()
        Hub.activityVisible = true
        Hub.listeners.add(this)
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancelAll().also {
            if (prefs.deviceAddr.isNotEmpty() || prefs.familyCode.isNotEmpty()) startBackground()
        }
        if (currentTab != 3) showTab(currentTab)
        val m = Hub.lastMeasure
        if (m != null && m.uid < 0 && System.currentTimeMillis() - m.ts < 30 * 60_000L) askWho(m)
    }

    override fun onPause() {
        Hub.activityVisible = false
        Hub.listeners.remove(this)
        super.onPause()
    }

    /** 백그라운드 서비스 시작 (권한이 있을 때만) */
    private fun startBackground() {
        if (!hasPerms()) return
        ScaleService.start(this)
    }

    override fun onBackPressed() {
        when {
            showingDetail -> { showingDetail = false; showTab(0) }
            currentTab != 0 -> showTab(0)
            else -> super.onBackPressed()
        }
    }

    // ───────────────────────── UI 도우미

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int = 16, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    private fun label(s: String, size: Float = 15f, color: Int = Ui.TEXT, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.includeFontPadding = false
        t.setLineSpacing(0f, 1.15f)
        if (bold) t.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        return t
    }

    private fun heavy(s: String, size: Float, color: Int = Ui.TEXT): TextView {
        val t = label(s, size, color)
        t.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        t.letterSpacing = -0.03f
        return t
    }

    /** w, h 는 MATCH/WRAP 또는 픽셀값 */
    private fun lp(w: Int = MATCH, h: Int = WRAP, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.topMargin = dp(top); p.bottomMargin = dp(bottom); p.leftMargin = dp(left); p.rightMargin = dp(right)
        return p
    }

    private fun weighted(left: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, WRAP, 1f)
        p.leftMargin = dp(left)
        return p
    }

    private fun vbox(): LinearLayout { val l = LinearLayout(this); l.orientation = LinearLayout.VERTICAL; return l }
    private fun hbox(): LinearLayout {
        val l = LinearLayout(this); l.orientation = LinearLayout.HORIZONTAL; l.gravity = Gravity.CENTER_VERTICAL; return l
    }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        val col = vbox()
        col.setPadding(dp(16), dp(22), dp(16), dp(28))
        sv.addView(col)
        return sv to col
    }

    /** 가족이 2명 이상이면 보이는 사람 선택 줄 */
    private fun personSwitcher(parent: LinearLayout) {
        val people = store.all()
        if (people.size < 2) return
        val row = hbox()
        for (p in people) {
            val on = p.id == me.id
            val c = label(p.display, 14f, if (on) Color.WHITE else Ui.TEXT2, on)
            c.background = rounded(if (on) Ui.TEXT else Ui.CARD, 99)
            c.setPadding(dp(16), dp(9), dp(16), dp(9))
            c.setOnClickListener { store.currentId = p.id; showTab(currentTab) }
            row.addView(c, lp(WRAP, right = 8))
        }
        parent.addView(row, lp(bottom = 14))
    }

    private fun header(parent: LinearLayout, title: String, sub: String?) {
        val h = vbox()
        h.setPadding(dp(4), 0, dp(4), 0)
        if (sub != null) h.addView(label(sub, 13f, Ui.SUB), lp(bottom = 4))
        h.addView(label(title, 22f, Ui.TEXT, true))
        parent.addView(h, lp(bottom = 14))
    }

    private fun card(parent: LinearLayout, heading: String? = null, color: Int = Ui.CARD, radius: Int = 22): LinearLayout {
        val c = vbox()
        c.background = rounded(color, radius)
        c.setPadding(dp(18), dp(16), dp(18), dp(16))
        parent.addView(c, lp(bottom = 12))
        if (heading != null) c.addView(label(heading, 14f, Ui.TEXT2, true), lp(bottom = 6))
        return c
    }

    /** style 0: 검정, 1: 연회색, 2: 청록 */
    private fun button(parent: LinearLayout, s: String, style: Int = 0, onClick: () -> Unit): TextView {
        val bg = when (style) { 0 -> Ui.TEXT; 2 -> Ui.TEAL; else -> Ui.SOFT }
        val fg = if (style == 1) Ui.TEXT else Color.WHITE
        val b = label(s, 15f, fg, true)
        b.gravity = Gravity.CENTER
        b.background = rounded(bg, 14)
        b.setPadding(dp(14), dp(14), dp(14), dp(14))
        b.setOnClickListener { onClick() }
        parent.addView(b, lp(top = 8))
        return b
    }

    private fun chip(text: String, zone: Int): TextView {
        val bg: Int
        val fg: Int
        when (zone) {
            0 -> { bg = Ui.LOW_CHIP; fg = Ui.LOW_TEXT }
            1 -> { bg = Ui.TEAL_BG; fg = 0xFF0B6B60.toInt() }
            2 -> { bg = Ui.HIGH_CHIP; fg = Ui.HIGH_TEXT }
            else -> { bg = Ui.INFO_CHIP; fg = Ui.TEXT2 }
        }
        val c = label(text, 12f, fg, true)
        c.background = rounded(bg, 8)
        c.setPadding(dp(8), dp(4), dp(8), dp(4))
        return c
    }

    private fun segmented(parent: LinearLayout, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val row = hbox()
        row.background = rounded(Ui.CARD, 14)
        row.setPadding(dp(4), dp(4), dp(4), dp(4))
        items.forEachIndexed { i, s ->
            val on = i == selected
            val b = label(s, 13f, if (on) Color.WHITE else Ui.SUB, on)
            b.gravity = Gravity.CENTER
            if (on) b.background = rounded(Ui.TEXT, 10)
            b.setPadding(0, dp(10), 0, dp(10))
            b.setOnClickListener { onPick(i) }
            row.addView(b, weighted())
        }
        parent.addView(row, lp(bottom = 12))
    }

    private fun divider(parent: LinearLayout) {
        val v = View(this)
        v.setBackgroundColor(0xFFEEF2F1.toInt())
        parent.addView(v, LinearLayout.LayoutParams(MATCH, 1))
    }

    private fun kvRow(parent: LinearLayout, k: String, v: String) {
        val r = hbox()
        r.setPadding(0, dp(11), 0, dp(11))
        r.addView(label(k, 15f, Ui.SUB), weighted())
        r.addView(label(v, 16f, Ui.TEXT, true))
        parent.addView(r)
        divider(parent)
    }

    private fun field(parent: LinearLayout, name: String, value: String, type: Int): EditText {
        parent.addView(label(name, 13f, Ui.SUB), lp(top = 8))
        val e = EditText(this)
        e.setText(value)
        e.inputType = type
        e.textSize = 16f
        e.setSingleLine()
        parent.addView(e, lp())
        return e
    }

    private fun switchRow(parent: LinearLayout, name: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = hbox()
        row.setPadding(0, dp(8), 0, dp(8))
        val texts = vbox()
        texts.addView(label(name, 15f), lp(bottom = 3))
        texts.addView(label(desc, 12f, Ui.SUB))
        row.addView(texts, weighted())
        val sw = Switch(this)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        row.addView(sw)
        parent.addView(row)
    }

    private fun f1(v: Double) = String.format(Locale.KOREA, "%.1f", v)
    private fun signed(v: Double, unit: String): String {
        val arrow = when { v > 0.05 -> "▲ "; v < -0.05 -> "▼ "; else -> "― " }
        return arrow + f1(abs(v)) + unit
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ───────────────────────── 탭

    private fun showTab(i: Int) {
        currentTab = i
        tabs.forEachIndexed { idx, tv ->
            val on = idx == i
            tv.setTextColor(if (on) Ui.TEAL_DARK else Ui.SUB)
            tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            val icon = tv.compoundDrawables[1]
            if (icon is NavIcon) icon.active = on
        }
        vLiveWeight = null; vRing = null; vConn = null
        content.removeAllViews()
        val live = i == 0 && !showingDetail && Hub.lastMeasure == null && Hub.liveWeight != null
        window.statusBarColor = if (live) Ui.DARK else Ui.BG
        window.decorView.systemUiVisibility = if (live) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val v = when {
            i == 0 && showingDetail -> buildDetail()
            i == 0 && live -> buildLive()
            i == 0 -> buildHome()
            i == 1 -> buildRecords()
            i == 2 -> buildTrend()
            else -> buildSettings()
        }
        content.addView(v)
    }

    private fun refreshHome() {
        if (currentTab == 0 && !showingDetail) showTab(0)
    }

    // ───────────────────────── 측정 중

    private fun buildLive(): View {
        val root = vbox()
        root.setBackgroundColor(Ui.DARK)
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.setPadding(dp(24), dp(24), dp(24), dp(24))

        val top = hbox()
        top.addView(label("측정 중", 15f, Color.WHITE, true), weighted())
        val conn = label("● " + prefs.deviceName.ifEmpty { "체중계" }, 12f, 0xFF9FD8CF.toInt())
        conn.background = rounded(0xFF1B2B27.toInt(), 99)
        conn.setPadding(dp(10), dp(6), dp(10), dp(6))
        top.addView(conn)
        root.addView(top, lp())

        val size = dp(270)
        val ringBox = FrameLayout(this)
        val ring = RingView(this)
        ring.progress = ((System.currentTimeMillis() - Hub.liveStart) / 6000f).coerceIn(0.08f, 0.92f)
        ringBox.addView(ring, FrameLayout.LayoutParams(size, size))
        val center = vbox()
        center.gravity = Gravity.CENTER
        center.addView(label("체중", 13f, Ui.MUTED_DARK), lp(WRAP, bottom = 6))
        val wRow = hbox()
        wRow.gravity = Gravity.BOTTOM
        val wv = heavy(f1(Hub.liveWeight ?: 0.0), 62f, Color.WHITE)
        wRow.addView(wv)
        wRow.addView(label(" kg", 20f, Ui.MUTED_DARK, true), lp(WRAP, bottom = 8))
        center.addView(wRow, lp(WRAP))
        center.addView(label("체성분 분석 중…", 13f, Ui.MINT, true), lp(WRAP, top = 6))
        ringBox.addView(center, FrameLayout.LayoutParams(size, size))
        root.addView(ringBox, lp(size, size, top = 48))
        vLiveWeight = wv
        vRing = ring

        val t1 = label("움직이지 말고 그대로 서 계세요", 20f, Color.WHITE, true)
        t1.gravity = Gravity.CENTER
        root.addView(t1, lp(top = 32))
        val t2 = label("맨발로 발판 전극에 발을 고르게 올려야\n체지방과 근육량이 정확하게 나와요", 14f, Ui.MUTED_DARK)
        t2.gravity = Gravity.CENTER
        root.addView(t2, lp(top = 10))

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        val steps = vbox()
        steps.background = rounded(Ui.DARK2, 20)
        steps.setPadding(dp(18), dp(12), dp(18), dp(12))
        stepRow(steps, "체중 측정", "진행 중", true)
        stepRow(steps, "임피던스 측정", "대기", false)
        stepRow(steps, "체성분 계산", "대기", false)
        root.addView(steps, lp())
        return root
    }

    private fun stepRow(parent: LinearLayout, name: String, state: String, on: Boolean) {
        val r = hbox()
        r.setPadding(0, dp(8), 0, dp(8))
        val dot = View(this)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        if (on) g.setColor(Ui.MINT) else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(2), 0xFF3A4D48.toInt()) }
        dot.background = g
        r.addView(dot, lp(dp(18), dp(18), right = 12))
        val off = 0xFF7F948E.toInt()
        r.addView(label(name, 15f, if (on) Color.WHITE else off, on), weighted())
        r.addView(label(state, 13f, if (on) Ui.MINT else off))
        parent.addView(r)
    }

    // ───────────────────────── 홈

    private class Shown(val ts: Long, val weight: Double, val res: Int, val sfat: Double?, val uid: Int)

    /** 방금 측정한 값, 없으면 마지막 기록 */
    private fun currentData(): Shown? {
        val m = Hub.lastMeasure
        if (m != null && (m.uid == me.id || m.uid < 0)) return Shown(m.ts, m.weight, m.res, m.sfat, if (m.uid < 0) me.id else m.uid)
        val r = db.all(me.id).firstOrNull() ?: return null
        return Shown(r.ts, r.weight, r.res, r.sfat, r.uid)
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..10 -> "좋은 아침이에요"
        in 11..16 -> "좋은 오후예요"
        in 17..21 -> "좋은 저녁이에요"
        else -> "편안한 밤 되세요"
    }

    private fun buildHome(): View {
        val (sv, col) = page()

        val head = hbox()
        head.gravity = Gravity.TOP
        val hl = vbox()
        hl.addView(label(dateHead.format(Date()), 13f, Ui.SUB), lp(bottom = 4))
        hl.addView(label(if (me.name.isEmpty()) greeting() else "${me.name}님, ${greeting()}", 21f, Ui.TEXT, true))
        head.addView(hl, weighted())
        val pill = label(
            if (Hub.bleConnected) "● 연결됨" else if (prefs.deviceAddr.isEmpty()) "기기 없음" else "○ 대기",
            12f, if (Hub.bleConnected) Ui.TEAL_DARK else Ui.SUB, true
        )
        pill.background = rounded(Ui.CARD, 99)
        pill.setPadding(dp(10), dp(6), dp(10), dp(6))
        pill.setOnClickListener { showTab(3) }
        head.addView(pill)
        vConn = pill
        col.addView(head, lp(bottom = 14))
        personSwitcher(col)

        val data = currentData()
        if (prefs.deviceAddr.isEmpty()) {
            val c = card(col)
            c.addView(label("체중계를 연결해 주세요", 17f, Ui.TEXT, true), lp(bottom = 6))
            c.addView(label("체중계에 올라가면 자동으로 측정하고 기록해요.", 14f, Ui.SUB))
            quickConnectButton(c)
            button(c, "체중계 찾기", 2) { startScan() }
        }

        if (data == null) {
            val c = card(col, "측정 팁")
            c.addView(label("• 아침 공복, 화장실 다녀온 뒤 같은 시간에 재면 추세가 정확해요\n• 맨발로 발판 전극에 발을 고르게 올리세요\n• 운동·샤워 직후엔 체지방이 다르게 나올 수 있어요", 14f, Ui.TEXT2))
            return sv
        }
        val body = BodyCalc.calc(data.weight, data.res, data.sfat, profileOf(data.uid))

        // 체중 카드
        val wc = hbox()
        wc.gravity = Gravity.BOTTOM
        wc.background = rounded(Ui.CARD, 24)
        wc.setPadding(dp(20), dp(18), dp(20), dp(18))
        val wl = vbox()
        val isNew = Hub.lastMeasure?.ts == data.ts
        wl.addView(label(if (isNew) "방금 측정한 체중" else "마지막 체중 · " + fullDate.format(Date(data.ts)), 13f, Ui.SUB), lp(bottom = 6))
        val wr = hbox()
        wr.gravity = Gravity.BOTTOM
        wr.addView(heavy(f1(data.weight), 46f))
        wr.addView(label(" kg", 18f, Ui.TEXT2, true), lp(WRAP, bottom = 6))
        wl.addView(wr, lp(WRAP))
        val prev = db.all(me.id).firstOrNull { it.ts < data.ts - 1000 }
        if (prev != null) {
            val dlt = label(signed(data.weight - prev.weight, "kg") + " · 지난 측정보다", 12f, Ui.TEAL_DARK, true)
            dlt.background = rounded(Ui.TEAL_BG, 8)
            dlt.setPadding(dp(8), dp(4), dp(8), dp(4))
            wl.addView(dlt, lp(WRAP, top = 8))
        }
        wc.addView(wl, weighted())
        if (me.goal > 0) {
            val gl = vbox()
            gl.gravity = Gravity.END
            val remain = data.weight - me.goal
            gl.addView(label("목표 ${f1(me.goal.toDouble())}kg까지", 12f, Ui.SUB), lp(WRAP, bottom = 4))
            gl.addView(label(if (abs(remain) < 0.1) "달성!" else f1(abs(remain)) + "kg", 18f, Ui.TEXT, true), lp(WRAP, bottom = 8))
            val first = db.all(me.id).lastOrNull()?.weight ?: data.weight
            val total = abs(first - me.goal)
            val prog = if (total < 0.1) 1.0 else (1.0 - abs(remain) / total).coerceIn(0.03, 1.0)
            val track = FrameLayout(this)
            track.background = rounded(Ui.LINE, 3)
            val fill = View(this)
            fill.background = rounded(Ui.TEAL, 3)
            track.addView(fill, FrameLayout.LayoutParams((dp(116) * prog).toInt(), dp(6)))
            gl.addView(track, lp(dp(116), dp(6)))
            wc.addView(gl)
        }
        col.addView(wc, lp(bottom = 12))

        if (body == null) {
            val c = card(col)
            c.addView(label("내 정보에서 키·출생연도·성별을 입력하면\n체성분과 기준을 보여드려요.", 15f, Ui.TEXT))
            button(c, "내 정보 입력", 0) { showTab(3) }
            return sv
        }

        // 인체 그림 카드
        val fig = FrameLayout(this)
        fig.background = rounded(Ui.FIG_BG, 24, 0xFFE3EAE8.toInt())
        val img = ImageView(this)
        img.setImageResource(R.drawable.body)
        img.scaleType = ImageView.ScaleType.FIT_START
        img.contentDescription = "전신 인체 그림"
        val imgLp = FrameLayout.LayoutParams(dp(220), dp(330))
        imgLp.gravity = Gravity.BOTTOM or Gravity.START
        imgLp.leftMargin = -dp(10)
        fig.addView(img, imgLp)

        val typeBox = vbox()
        typeBox.setPadding(dp(16), dp(14), 0, 0)
        val bt = BodyCalc.bodyType(body, me.male)
        typeBox.addView(label("체형 분석", 12f, Ui.SUB), lp(WRAP, bottom = 3))
        typeBox.addView(label(if (bt != null) BodyCalc.typeLabels[bt.first][bt.second] else "BMI " + BodyCalc.bmiLabel(body.bmi), 17f, Ui.TEXT, true))
        fig.addView(typeBox, FrameLayout.LayoutParams(WRAP, WRAP))

        val side = vbox()
        val sideLp = FrameLayout.LayoutParams(dp(152), WRAP)
        sideLp.gravity = Gravity.END or Gravity.TOP
        sideLp.topMargin = dp(14); sideLp.rightMargin = dp(12)
        val all = BodyCalc.metrics(data.weight, body, me.male).flatMap { it.second }
        for (n in listOf("체지방률", "근육량", "BMI", "기초대사량")) {
            val m = all.firstOrNull { it.name == n } ?: continue
            side.addView(miniCard(m), lp(bottom = 8))
        }
        if (body.fatPct == null) {
            side.addView(label(if (data.res <= 0) "맨발로 측정하면 체지방·근육량도 나와요" else "체지방을 계산하지 못했어요", 12f, Ui.HIGH_TEXT), lp(top = 4))
        }
        fig.addView(side, sideLp)
        col.addView(fig, lp(h = dp(400), bottom = 12))

        // 요약
        val ranged = all.filter { it.hasRange }
        val inStd = ranged.count { it.zone == 1 }
        val sum = hbox()
        sum.background = rounded(Ui.CARD, 20)
        sum.setPadding(dp(18), dp(14), dp(14), dp(14))
        val sl = vbox()
        sl.addView(label("표준 범위 안", 13f, Ui.SUB), lp(bottom = 3))
        sl.addView(label("${ranged.size}개 중 ${inStd}개 항목", 17f, Ui.TEXT, true))
        sum.addView(sl, weighted())
        val go = label("상세 분석 보기", 14f, Color.WHITE, true)
        go.background = rounded(Ui.TEXT, 14)
        go.setPadding(dp(16), dp(12), dp(16), dp(12))
        go.setOnClickListener { showingDetail = true; showTab(0) }
        sum.addView(go)
        col.addView(sum, lp(bottom = 12))

        val m = Hub.lastMeasure
        if (m != null && (m.uid == me.id || m.uid < 0)) {
            if (m.uid < 0) button(col, "누구의 측정인지 선택", 2) { askWho(m) }
            else if (!m.saved) button(col, "이 측정 기록 저장", 2) { Hub.save(m, notify = false); refreshHome() }
            else {
                if (m.auto) {
                    val who = hbox()
                    who.background = rounded(Ui.CARD, 16)
                    who.setPadding(dp(16), dp(12), dp(12), dp(12))
                    who.addView(label("체중으로 ${me.display}님 기록으로 저장했어요", 13f, Ui.TEXT2), weighted())
                    val ch = label("다른 사람", 13f, Ui.TEAL_DARK, true)
                    ch.setPadding(dp(8), dp(4), dp(4), dp(4))
                    ch.setOnClickListener { pickPerson("누구의 측정인가요?") { p -> Hub.assign(m, p.id); showTab(0) } }
                    who.addView(ch)
                    col.addView(who, lp(bottom = 8))
                }
                val ok = label("✓ 기록에 저장됨 · " + timeOnly.format(Date(m.ts)), 13f, Ui.TEAL_DARK, true)
                ok.gravity = Gravity.CENTER
                col.addView(ok, lp(top = 4))
            }
        }
        return sv
    }

    private fun miniCard(m: Metric): View {
        val c = vbox()
        c.background = rounded(Ui.CARD, 16)
        c.setPadding(dp(12), dp(10), dp(12), dp(10))
        val top = hbox()
        top.addView(label(m.name, 12f, Ui.SUB), weighted())
        val gc = when (m.zone) { 0 -> Ui.LOW_TEXT; 1 -> Ui.TEAL_DARK; 2 -> Ui.HIGH_TEXT; else -> Ui.SUB }
        val gt = if (m.zone >= 0) m.grade else if (m.unit == "kcal") "하루" else "추정"
        top.addView(label(gt, 11f, gc, true))
        c.addView(top, lp(bottom = 4))
        c.addView(label(m.text(), 19f, Ui.TEXT, true))
        if (m.hasRange) {
            val zb = ZoneBarView(this)
            zb.setZone(m.zone)
            c.addView(zb, lp(top = 6))
        }
        return c
    }

    // ───────────────────────── 상세 분석

    private fun buildDetail(): View {
        val (sv, col) = page()
        val data = currentData()

        val back = hbox()
        val bb = label("‹", 26f, Ui.TEXT, true)
        bb.gravity = Gravity.CENTER
        bb.background = rounded(Ui.CARD, 12)
        bb.contentDescription = "뒤로"
        bb.setOnClickListener { showingDetail = false; showTab(0) }
        back.addView(bb, lp(dp(42), dp(42), right = 10))
        val bt = vbox()
        bt.addView(label("상세 분석", 20f, Ui.TEXT, true), lp(bottom = 3))
        val who = "${if (me.male) "남성" else "여성"} · ${f1(me.heightCm.toDouble())}cm · ${me.age}세"
        bt.addView(label((if (data != null) fullDate.format(Date(data.ts)) + " · " else "") + who, 12f, Ui.SUB))
        back.addView(bt, weighted())
        col.addView(back, lp(bottom = 14))

        if (data == null) { col.addView(label("측정 기록이 없어요.", 15f, Ui.SUB)); return sv }
        val body = BodyCalc.calc(data.weight, data.res, data.sfat, profileOf(data.uid))
        if (body == null) { col.addView(label("내 정보를 먼저 입력해 주세요.", 15f, Ui.SUB)); return sv }

        val legend = hbox()
        legend.setPadding(dp(4), 0, 0, 0)
        for ((t, c) in listOf("표준 이하" to Ui.LOW_BG, "표준" to Ui.STD_BG, "표준 이상" to Ui.HIGH_BG)) {
            val sw = View(this)
            sw.background = rounded(c, 3)
            legend.addView(sw, lp(dp(10), dp(10), right = 5))
            legend.addView(label(t, 12f, Ui.TEXT2), lp(WRAP, right = 14))
        }
        col.addView(legend, lp(bottom = 12))

        for ((title, list) in BodyCalc.metrics(data.weight, body, me.male)) {
            if (list.isEmpty()) continue
            val c = card(col, title)
            c.setPadding(dp(18), dp(16), dp(18), dp(6))
            list.forEachIndexed { i, m ->
                val r = vbox()
                r.setPadding(0, dp(12), 0, dp(12))
                val top = hbox()
                top.addView(label(m.name, 15f, Ui.TEXT), weighted())
                top.addView(label(m.text(), 19f, Ui.TEXT, true), lp(WRAP, right = 8))
                top.addView(chip(m.grade, m.zone))
                r.addView(top, lp())
                val lo = m.lo
                val hi = m.hi
                if (lo != null && hi != null) {
                    val zb = ZoneBarView(this)
                    zb.setValue(m.value, lo, hi)
                    r.addView(zb, lp(top = 10))
                }
                r.addView(label(m.note, 12f, Ui.SUB), lp(top = 8))
                c.addView(r)
                if (i < list.size - 1) divider(c)
            }
        }

        // 체형 분석표
        val tc = card(col)
        val th = hbox()
        th.addView(label("체형 분석", 14f, Ui.TEXT2, true), weighted())
        th.addView(label("BMI × 체지방률", 12f, Ui.SUB))
        tc.addView(th, lp(bottom = 10))
        val type = BodyCalc.bodyType(body, me.male)
        val hdr = hbox()
        hdr.addView(View(this), lp(dp(50), 1))
        for (t in listOf("BMI 낮음", "표준", "높음")) {
            val l = label(t, 12f, Ui.SUB)
            l.gravity = Gravity.CENTER
            hdr.addView(l, weighted())
        }
        tc.addView(hdr, lp(bottom = 4))
        val rowNames = listOf("체지방\n높음", "표준", "낮음")
        val rowIdx = listOf(2, 1, 0)
        for (k in 0..2) {
            val r = hbox()
            r.addView(label(rowNames[k], 12f, Ui.SUB), lp(dp(50), WRAP))
            for (cIdx in 0..2) {
                val on = type != null && type.first == rowIdx[k] && type.second == cIdx
                val cell = label(BodyCalc.typeLabels[rowIdx[k]][cIdx], 12f, if (on) Color.WHITE else Ui.SUB, on)
                cell.gravity = Gravity.CENTER
                cell.background = rounded(if (on) Ui.TEXT else Ui.SOFT, 10)
                cell.setPadding(dp(2), dp(13), dp(2), dp(13))
                r.addView(cell, weighted(4))
            }
            tc.addView(r, lp(bottom = 4))
        }
        val note = if (type != null) BodyCalc.typeNotes[type.first][type.second]
        else "체지방률이 있어야 체형을 분석할 수 있어요. 맨발로 측정해 주세요."
        tc.addView(label(note, 14f, Ui.TEXT2), lp(top = 8))

        val (flo, fhi) = BodyCalc.fatRange(me.male)
        val foot = label(
            "표준 범위: BMI는 대한비만학회 기준(18.5~22.9 정상, 23~24.9 비만 전단계), 체지방률은 " +
                "${if (me.male) "남성" else "여성"} ${flo.toInt()}~${fhi.toInt()}%를 사용해요. " +
                "가정용 체중계의 체성분 값은 추정치이니 변화 추이로 보세요.", 12f, Ui.SUB
        )
        foot.setPadding(dp(6), dp(4), dp(6), 0)
        col.addView(foot)
        return sv
    }

    // ───────────────────────── 기록

    private fun buildRecords(): View {
        val (sv, col) = page()
        val list = db.all(me.id)
        header(col, "기록", "${me.display} · 총 ${list.size}회 측정")
        personSwitcher(col)

        if (list.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val c = card(col, "요약")
            kvRow(c, "최근 체중", f1(list[0].weight) + " kg")
            val week = list.filter { it.ts >= now - 7 * 86_400_000L }
            if (week.isNotEmpty()) kvRow(c, "최근 7일 평균", f1(week.map { it.weight }.average()) + " kg")
            val month = list.filter { it.ts >= now - 30 * 86_400_000L }
            if (month.size >= 2) kvRow(c, "30일 변화", signed(month.first().weight - month.last().weight, "kg"))
            if (list.size >= 2) kvRow(c, "처음 대비", signed(list.first().weight - list.last().weight, "kg"))
        }

        val row = hbox()
        val b1 = label("직접 입력", 14f, Ui.TEXT, true)
        val b2 = label("CSV 내보내기", 14f, Ui.TEXT, true)
        for (b in listOf(b1, b2)) {
            b.gravity = Gravity.CENTER
            b.background = rounded(Ui.CARD, 14)
            b.setPadding(0, dp(13), 0, dp(13))
        }
        b1.setOnClickListener { manualEntry() }
        b2.setOnClickListener { exportCsv() }
        row.addView(b1, weighted())
        row.addView(b2, weighted(8))
        col.addView(row, lp(bottom = 12))

        if (list.isEmpty()) {
            val c = card(col)
            c.addView(label("아직 기록이 없어요.\n체중계에 올라가면 자동으로 저장돼요.", 15f, Ui.SUB))
            return sv
        }

        val dayFmt = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREA)
        var lastDay = ""
        var g: LinearLayout = col
        for (r in list.take(500)) {
            val day = dayFmt.format(Date(r.ts))
            if (day != lastDay) {
                lastDay = day
                g = card(col, day)
                g.setPadding(dp(18), dp(14), dp(18), dp(6))
            }
            val body = BodyCalc.calc(r.weight, r.res, r.sfat, profileOf(r.uid))
            val item = hbox()
            item.setPadding(0, dp(10), 0, dp(10))
            val left = vbox()
            left.addView(label(timeOnly.format(Date(r.ts)) + (if (r.src == "수동") " · 직접 입력" else ""), 14f, Ui.SUB), lp(bottom = 3))
            val detail = ArrayList<String>()
            if (body != null) {
                detail.add("BMI " + f1(body.bmi))
                body.fatPct?.let { detail.add("체지방 " + f1(it) + "%") }
                body.muscleKg?.let { detail.add("근육 " + f1(it) + "kg") }
            }
            if (detail.isNotEmpty()) left.addView(label(detail.joinToString(" · "), 12f, Ui.SUB))
            item.addView(left, weighted())
            item.addView(label(f1(r.weight) + " kg", 18f, Ui.TEXT, true))
            item.setOnClickListener { recordDetail(r) }
            g.addView(item)
        }
        return sv
    }

    private fun recordDetail(r: Rec) {
        val b = BodyCalc.calc(r.weight, r.res, r.sfat, profileOf(r.uid))
        val sb = StringBuilder()
        if (b != null) {
            for (sec in BodyCalc.metrics(r.weight, b, me.male)) {
                for (m in sec.second) sb.append("${m.name}: ${m.text()}  (${m.grade})\n")
            }
        } else sb.append("체중: ${f1(r.weight)} kg\n")
        if (r.res > 0) sb.append("임피던스: ${r.res} Ω\n")
        sb.append("입력 방식: ${r.src}")
        AlertDialog.Builder(this)
            .setTitle(fullDate.format(Date(r.ts)))
            .setMessage(sb.toString())
            .setPositiveButton("닫기", null)
            .setNeutralButton("사람 바꾸기") { _, _ ->
                pickPerson("누구의 기록인가요?") { p -> Hub.reassignRecord(r, p.id); toast("${p.display}님 기록으로 옮겼어요") }
            }
            .setNegativeButton("삭제") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("이 기록을 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ -> Hub.deleteRecord(r); toast("삭제했어요"); showTab(1) }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .show()
    }

    private fun manualEntry() {
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        e.hint = "예: 72.4"
        box.addView(e)
        AlertDialog.Builder(this)
            .setTitle("체중 직접 입력 (kg)")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val v = e.text.toString().toDoubleOrNull()
                if (v == null || v < 2 || v > 300) toast("올바른 체중을 입력해 주세요")
                else { Hub.addManual(v, me.id); toast("저장했어요"); showTab(1) }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ───────────────────────── 추이

    private fun metricValue(r: Rec, metric: Int): Double? {
        if (metric == 0) return r.weight
        val b = BodyCalc.calc(r.weight, r.res, r.sfat, profileOf(r.uid)) ?: return null
        return when (metric) { 1 -> b.fatPct; 2 -> b.muscleKg; else -> b.bmi }
    }

    private fun buildTrend(): View {
        val (sv, col) = page()
        val dayOpts = listOf(7, 30, 90, 365)
        val sel = dayOpts.indexOf(graphDays).coerceAtLeast(0)
        val periodName = listOf("최근 1주", "최근 30일", "최근 3개월", "최근 1년")[sel]
        val now = System.currentTimeMillis()
        val recs = db.all(me.id).filter { it.ts >= now - graphDays * 86_400_000L }.sortedBy { it.ts }
        header(col, "변화 추이", "${me.display} · $periodName · ${recs.size}회 측정")
        personSwitcher(col)
        segmented(col, listOf("1주", "1개월", "3개월", "1년"), sel) { graphDays = dayOpts[it]; showTab(2) }

        val names = listOf("체중", "체지방률", "근육량", "BMI")
        val units = listOf("kg", "%", "kg", "")
        val series = (0..3).map { m -> recs.mapNotNull { r -> metricValue(r, m)?.let { v -> r.ts to v } } }

        fun changeUnit(m: Int) = if (m == 1) "%p" else units[m]
        fun isGood(m: Int, ch: Double) = if (m == 2) ch >= -0.05 else ch <= 0.05

        val main = series[graphMetric]
        val c = card(col, radius = 24)
        val top = hbox()
        top.gravity = Gravity.BOTTOM
        val tl = vbox()
        tl.addView(label(names[graphMetric], 13f, Ui.SUB), lp(bottom = 4))
        val vr = hbox()
        vr.gravity = Gravity.BOTTOM
        vr.addView(heavy(if (main.isEmpty()) "--" else f1(main.last().second), 28f))
        vr.addView(label(" " + units[graphMetric], 15f, Ui.SUB, true), lp(WRAP, bottom = 3))
        tl.addView(vr, lp(WRAP))
        top.addView(tl, weighted())
        if (main.size >= 2) {
            val ch = main.last().second - main.first().second
            val good = isGood(graphMetric, ch)
            val t = label(signed(ch, changeUnit(graphMetric)), 13f, if (good) Ui.TEAL_DARK else Ui.HIGH_TEXT, true)
            t.background = rounded(if (good) Ui.TEAL_BG else Ui.HIGH_CHIP, 8)
            t.setPadding(dp(9), dp(5), dp(9), dp(5))
            top.addView(t)
        }
        c.addView(top, lp(bottom = 10))
        val chart = ChartView(this)
        chart.setData(main, if (graphMetric == 0 && me.goal > 0) me.goal.toDouble() else null, units[graphMetric])
        c.addView(chart, lp())

        val row = hbox()
        row.gravity = Gravity.TOP
        var added = 0
        for (m in 0..3) {
            if (m == graphMetric) continue
            val s = series[m]
            val mc = vbox()
            mc.background = rounded(Ui.CARD, 18)
            mc.setPadding(dp(12), dp(12), dp(12), dp(10))
            mc.addView(label(names[m], 12f, Ui.SUB), lp(bottom = 4))
            mc.addView(label(if (s.isEmpty()) "--" else f1(s.last().second) + units[m], 17f, Ui.TEXT, true), lp(bottom = 4))
            if (s.size >= 2) {
                val ch = s.last().second - s.first().second
                val flat = abs(ch) < 0.05
                val good = isGood(m, ch)
                mc.addView(label(signed(ch, changeUnit(m)), 12f, if (flat) Ui.TEXT2 else if (good) Ui.TEAL_DARK else Ui.HIGH_TEXT, true), lp(bottom = 4))
                val sp = SparkView(this)
                sp.setData(s.map { it.second }, if (flat) Ui.SUB else if (good) Ui.TEAL else Ui.HIGH_ACC)
                mc.addView(sp, lp())
            } else mc.addView(label("기록 부족", 12f, Ui.SUB))
            mc.setOnClickListener { graphMetric = m; showTab(2) }
            row.addView(mc, weighted(if (added > 0) 8 else 0))
            added++
        }
        col.addView(row, lp(bottom = 12))

        val w = series[0]
        val mus = series[2]
        if (w.size >= 2) {
            val dw = w.last().second - w.first().second
            val sb = StringBuilder()
            sb.append(if (abs(dw) < 0.2) "체중은 거의 그대로예요." else "체중이 ${f1(abs(dw))}kg ${if (dw < 0) "줄었어요" else "늘었어요"}.")
            if (mus.size >= 2) {
                val dm = mus.last().second - mus.first().second
                sb.append(" 근육량은 ")
                sb.append(if (abs(dm) < 0.3) "거의 그대로예요." else if (dm > 0) "${f1(dm)}kg 늘었어요." else "${f1(-dm)}kg 줄었어요.")
                if (dw < -0.3 && dm > -0.3) sb.append(" 빠진 무게 대부분이 체지방이라 좋은 흐름이에요.")
                else if (dw < -0.3) sb.append(" 근육도 함께 빠지고 있어요. 단백질 섭취와 근력 운동을 챙겨 보세요.")
                else if (dw > 0.3 && dm > 0.3) sb.append(" 늘어난 무게에 근육이 포함돼 있어요.")
            } else sb.append(" 맨발로 측정하면 체지방·근육 변화도 알려드려요.")
            val s = card(col, color = Ui.TEXT, radius = 20)
            s.addView(label("$periodName 요약", 13f, 0xFF9FD8CF.toInt(), true), lp(bottom = 6))
            s.addView(label(sb.toString(), 15f, Color.WHITE))
        }
        return sv
    }

    // ───────────────────────── 내 정보

    private fun buildSettings(): View {
        val (sv, col) = page()
        header(col, "내 정보", null)
        personSwitcher(col)

        val pc = card(col, "${me.display}님 프로필 · 체성분 계산에 사용")
        val eName = field(pc, "이름", me.name, InputType.TYPE_CLASS_TEXT)
        pc.addView(label("성별", 13f, Ui.SUB), lp(top = 8))
        val rg = RadioGroup(this)
        rg.orientation = RadioGroup.HORIZONTAL
        val rbM = RadioButton(this); rbM.text = "남성"; rbM.id = View.generateViewId()
        val rbF = RadioButton(this); rbF.text = "여성"; rbF.id = View.generateViewId()
        rg.addView(rbM); rg.addView(rbF)
        rg.check(if (me.male) rbM.id else rbF.id)
        pc.addView(rg)
        val eYear = field(pc, "출생연도 (예: 1981)", if (me.birthYear > 0) me.birthYear.toString() else "", InputType.TYPE_CLASS_NUMBER)
        val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val eHeight = field(pc, "키 (cm)", if (me.heightCm > 0) f1(me.heightCm.toDouble()) else "", dec)
        val eGoal = field(pc, "목표 체중 (kg, 선택)", if (me.goal > 0) f1(me.goal.toDouble()) else "", dec)
        button(pc, "저장", 0) {
            val y = eYear.text.toString().trim().toIntOrNull()
            val h = eHeight.text.toString().trim().toFloatOrNull()
            if (y == null || y < 1900 || y > 2030) { toast("출생연도를 확인해 주세요"); return@button }
            if (h == null || h < 80 || h > 250) { toast("키를 확인해 주세요"); return@button }
            val pr = me
            pr.name = eName.text.toString().trim()
            pr.male = rg.checkedRadioButtonId == rbM.id
            pr.birthYear = y
            pr.heightCm = h
            pr.goal = eGoal.text.toString().trim().toFloatOrNull() ?: 0f
            store.update(pr)
            Hub.profilesChanged()
            toast("저장했어요")
            showTab(0)
        }

        val dc = card(col, "체중계")
        if (prefs.deviceAddr.isEmpty()) {
            dc.addView(label("등록된 체중계가 없어요", 15f))
            quickConnectButton(dc)
            button(dc, "체중계 찾기", 2) { startScan() }
        } else {
            dc.addView(label(prefs.deviceName, 16f, Ui.TEXT, true), lp(bottom = 3))
            dc.addView(label(Hub.statusText, 13f, if (Hub.bleConnected) Ui.TEAL_DARK else Ui.SUB))
            button(dc, "다시 연결", 1) { ensurePerms { ensureBtOn { startBackground(); Hub.connect() } } }
            button(dc, "다른 체중계 찾기", 1) { startScan() }
            button(dc, "체중계 등록 해제", 1) {
                Hub.ble?.disconnect()
                prefs.deviceAddr = ""; prefs.deviceName = ""
                Hub.statusText = "등록된 체중계가 없어요"
                showTab(3)
            }
        }

        // 가족 · 이 폰 주인
        val fc = card(col, "가족")
        val people = store.all()
        for (p in people) {
            val r = hbox()
            r.setPadding(0, dp(10), 0, dp(10))
            val tag = (if (p.id == prefs.ownerUid) "  · 이 폰 주인" else "")
            r.addView(label(p.display + tag, 15f, Ui.TEXT, p.id == me.id), weighted())
            r.addView(label(if (p.profileReady) "${p.heightCm.toInt()}cm · ${p.age}세" else "정보 미입력", 12f, Ui.SUB))
            r.setOnClickListener { store.currentId = p.id; showTab(3) }
            fc.addView(r)
            divider(fc)
        }
        fc.addView(label("체중계에 올라가면 체중이 가장 가까운 사람의 기록으로 자동 저장돼요. 애매하면 물어봐요.", 12f, Ui.SUB), lp(top = 8))
        button(fc, "+ 사람 추가", 1) { addPerson { p -> store.currentId = p.id; showTab(3) } }
        button(fc, "이 폰 주인 정하기", 1) {
            pickPerson("이 폰은 누구 폰인가요?") { p -> prefs.ownerUid = p.id; toast("${p.display}님 폰으로 정했어요"); showTab(3) }
        }
        if (people.size > 1) button(fc, "${me.display}님 삭제", 1) {
            val target = me
            AlertDialog.Builder(this)
                .setTitle("${target.display}님 삭제")
                .setMessage("${target.display}님과 그 기록을 모두 지울까요?")
                .setPositiveButton("삭제") { _, _ ->
                    for (r in db.all(target.id)) Hub.deleteRecord(r)
                    store.remove(target.id)
                    if (prefs.ownerUid == target.id) prefs.ownerUid = 0
                    Hub.profilesChanged()
                    showTab(3)
                }
                .setNegativeButton("취소", null)
                .show()
        }

        // 폰끼리 기록 공유
        val sc = card(col, "가족 폰과 기록 공유")
        val code = prefs.familyCode
        if (!Sync.firebaseReady(this)) {
            sc.addView(label("Firebase 설정이 들어간 버전에서 쓸 수 있어요.", 13f, Ui.SUB))
        } else if (code.isEmpty()) {
            sc.addView(label("가족 코드를 만들어 다른 폰에 입력하면, 누가 측정하든 그 사람 폰으로 기록과 알림이 가요.", 13f, Ui.SUB))
            button(sc, "새 가족 코드 만들기", 2) {
                prefs.familyCode = Sync.newCode()
                ScaleService.start(this, ScaleService.ACTION_RESYNC)
                toast("가족 코드를 만들었어요. 다른 폰에 입력해 주세요")
                showTab(3)
            }
            button(sc, "가족 코드 입력", 1) { enterFamilyCode() }
        } else {
            sc.addView(label(if (Sync.active) "● 공유 중" else if (Sync.lastError.isNotEmpty()) "오류: ${Sync.lastError}" else "연결 중…", 14f,
                if (Sync.active) Ui.TEAL_DARK else Ui.SUB, true), lp(bottom = 6))
            val ct = label(code.chunked(4).joinToString("-"), 17f, Ui.TEXT, true)
            ct.typeface = Typeface.MONOSPACE
            ct.setTextIsSelectable(true)
            sc.addView(ct, lp(bottom = 4))
            sc.addView(label("이 코드를 다른 폰의 「가족 코드 입력」에 넣으세요. 코드는 비밀번호처럼 관리하세요.", 12f, Ui.SUB))
            if (!Sync.active) button(sc, "공유 다시 연결", 2) {
                ScaleService.start(this, ScaleService.ACTION_RESYNC)
                toast("다시 연결하는 중이에요")
                handler.postDelayed({ if (currentTab == 3) showTab(3) }, 3000)
            }
            button(sc, "코드 복사", 1) {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("family", code))
                toast("가족 코드를 복사했어요")
            }
            button(sc, "공유 끊기", 1) {
                prefs.familyCode = ""
                Sync.stop()
                showTab(3)
            }
        }

        // 백그라운드 · 알림
        val bc = card(col, "알림 · 백그라운드")
        bc.addView(label("앱을 닫아도 체중계를 기다리고, 측정되면 알림을 보내요. 갤럭시는 배터리 설정을 「제한 없음」으로 해야 끊기지 않아요.", 13f, Ui.SUB))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            button(bc, "알림 허용하기", 2) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9) }
        }
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            button(bc, "배터리 제한 해제하기", 2) {
                try {
                    val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = android.net.Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (e: Exception) { toast("설정 화면을 열 수 없어요") }
            }
        } else bc.addView(label("✓ 배터리 제한 없음", 13f, Ui.TEAL_DARK, true), lp(top = 6))

        val mc = card(col, "측정")
        switchRow(mc, "자동 저장", "측정이 끝나면 바로 기록에 저장", prefs.autoSave) { prefs.autoSave = it }
        switchRow(mc, "완료 진동", "측정이 끝나면 짧게 진동", prefs.vibrate) { prefs.vibrate = it }

        val data = card(col, "데이터")
        data.addView(label("측정 기록은 이 폰에만 저장돼요. 폰을 바꾸기 전에 CSV로 내보내 두세요.", 13f, Ui.SUB))
        button(data, "CSV로 내보내기", 1) { exportCsv() }
        button(data, "${me.display}님 기록 전체 삭제", 1) {
            AlertDialog.Builder(this)
                .setTitle("기록 전체 삭제")
                .setMessage("${me.display}님의 모든 측정 기록을 지울까요? 가족 폰에서도 지워지고 되돌릴 수 없어요.")
                .setPositiveButton("삭제") { _, _ -> for (r in db.all(me.id)) Hub.deleteRecord(r); Hub.lastMeasure = null; toast("모두 삭제했어요"); showTab(3) }
                .setNegativeButton("취소", null)
                .show()
        }

        val adv = card(col, "고급 · 문제 해결")
        val mode = if (prefs.parseMode == 0) "자동 (YUNMAI 형식)"
        else "사용자 지정 (바이트 ${prefs.customOffset}, ÷${prefs.customDiv}, ${if (prefs.customLE) "LE" else "BE"})"
        adv.addView(label("데이터 해석: $mode", 14f), lp(bottom = 3))
        adv.addView(label("체중이 이상하게 나오면 보정 도구로 실제 체중에 맞추세요.", 12f, Ui.SUB))
        button(adv, "보정 도구", 1) { calibrate() }
        if (prefs.parseMode != 0) button(adv, "자동 해석으로 되돌리기", 1) { prefs.parseMode = 0; toast("자동 해석으로 바꿨어요"); showTab(3) }
        button(adv, "통신 로그 보기", 1) { showLogs() }

        val info = card(col, "앱 정보")
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        info.addView(label("내 체중계 v$ver", 14f), lp(bottom = 4))
        info.addView(label("체성분 값은 공개된 근사 공식으로 계산한 추정치이며 의료용이 아닙니다.", 12f, Ui.SUB))
        return sv
    }

    // ───────────────────────── 권한 · 블루투스

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms(): Boolean = neededPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun ensurePerms(then: () -> Unit) {
        if (hasPerms()) then()
        else { pendingPerm = then; requestPermissions(neededPerms(), REQ_PERM) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9) { if (currentTab == 3) showTab(3); return }
        if (requestCode != REQ_PERM) return
        if (hasPerms() && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            handler.postDelayed({ requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9) }, 400)
        }
        val p = pendingPerm
        pendingPerm = null
        if (hasPerms()) p?.invoke()
        else toast("블루투스 권한이 있어야 체중계에 연결할 수 있어요")
    }

    private fun btAdapter(): BluetoothAdapter? = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun ensureBtOn(then: () -> Unit) {
        val a = btAdapter()
        if (a == null) { toast("이 폰은 블루투스를 지원하지 않아요"); return }
        if (a.isEnabled) then()
        else {
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: Exception) {}
            toast("블루투스를 켠 뒤 다시 시도해 주세요")
        }
    }

    private fun startScan() {
        ensurePerms { ensureBtOn { doScan() } }
    }

    /** 검색된 기기 1개 */
    private class Found(val addr: String, var name: String, var rssi: Int, var scaleLike: Boolean)

    private val SCALE_SERVICES = listOf("0000ffe0", "0000ffe5", "d618d000", "0000181d", "0000181b")
    private val SCALE_WORDS = listOf("YUNMAI", "SCALE", "FEELFIT", "BODY", "FAT", "WEIGH", "INBODY", "MI SCALE", "MIBFS", "QN-", "CHIPSEA", "FITDAYS")

    private fun doScan() {
        val scanner = btAdapter()?.bluetoothLeScanner
        if (scanner == null) { toast("블루투스 검색을 시작할 수 없어요"); return }
        val known = prefs.knownScales
        val found = LinkedHashMap<String, Found>()
        val shown = ArrayList<Found>()
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        var stopped = false

        fun rank(f: Found): Int = when {
            known.contains(f.addr) || f.addr == prefs.deviceAddr -> 0
            f.scaleLike -> 1
            else -> 2
        }
        fun line(f: Found): String {
            val dist = when {
                f.addr == prefs.deviceAddr && Hub.bleConnected -> "지금 연결됨"
                f.rssi <= -200 -> "신호 대기 중 · 선택해도 돼요"
                f.rssi > -60 -> "아주 가까움"
                f.rssi > -75 -> "가까움"
                else -> "멂"
            }
            val nm = f.name.ifEmpty { "(이름 없음)" }
            return when (rank(f)) {
                0 -> "✅ 내 체중계 · 예전에 연결한 기기\n$nm · ${f.addr} · $dist"
                1 -> "⚖ 체중계로 보여요\n$nm · ${f.addr} · $dist"
                else -> "$nm\n${f.addr} · $dist"
            }
        }
        fun refresh() {
            shown.clear()
            shown.addAll(found.values.sortedWith(compareBy<Found> { rank(it) }.thenByDescending { it.rssi }))
            adapter.setNotifyOnChange(false)
            adapter.clear()
            adapter.addAll(shown.map { line(it) })
            adapter.notifyDataSetChanged()
        }

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val a = result.device.address
                val n = result.device.name ?: result.scanRecord?.deviceName ?: ""
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase(Locale.ROOT) } ?: emptyList()
                val up = n.uppercase(Locale.ROOT)
                val byName = SCALE_WORDS.any { up.contains(it) }
                val bySvc = uuids.any { u -> SCALE_SERVICES.any { u.startsWith(it) } }
                val isKnown = known.contains(a) || a == prefs.deviceAddr
                // 이름도 없고 체중계 단서도 없는 기기는 숨김
                if (n.isEmpty() && !bySvc && !isKnown) return
                runOnUiThread {
                    val f = found[a]
                    if (f == null) found[a] = Found(a, n, result.rssi, byName || bySvc)
                    else {
                        if (n.isNotEmpty()) f.name = n
                        f.rssi = result.rssi
                        if (byName || bySvc) f.scaleLike = true
                    }
                    refresh()
                }
            }
        }
        fun stop() {
            if (stopped) return
            stopped = true
            try { scanner.stopScan(cb) } catch (e: Exception) {}
        }
        // 연결 중인 체중계는 신호를 내보내지 않아 검색에 안 잡힘 → 저장된 체중계를 미리 목록에 넣음
        for (a in (listOf(prefs.deviceAddr) + known).filter { it.isNotEmpty() }.distinct()) {
            val nm = if (a == prefs.deviceAddr && prefs.deviceName.isNotEmpty()) prefs.deviceName else prefs.knownName(a)
            found[a] = Found(a, nm, if (a == prefs.deviceAddr && Hub.bleConnected) -40 else -200, true)
        }
        refresh()
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) {
            toast("검색 오류: ${e.message}"); return
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("체중계에 올라가 주세요… (검색 중)")
            .setAdapter(adapter) { _, which ->
                stop()
                val f = shown.getOrNull(which) ?: return@setAdapter
                registerScale(f.addr, f.name.ifEmpty { "체중계" })
            }
            .setNegativeButton("취소", null)
            .setOnDismissListener { stop() }
            .show()
        handler.postDelayed({
            stop()
            if (dlg.isShowing) dlg.setTitle(
                when {
                    found.isEmpty() -> "기기를 찾지 못했어요 — 체중계에 올라간 채로 다시 검색하세요"
                    found.values.any { rank(it) == 0 } -> "내 체중계를 찾았어요 — ✅ 표시를 누르세요"
                    found.values.any { rank(it) == 1 } -> "⚖ 표시가 체중계예요"
                    else -> "검색 완료 — 기기를 선택하세요"
                }
            )
        }, 20_000)
    }

    private fun registerScale(addr: String, name: String) {
        prefs.deviceAddr = addr
        prefs.deviceName = name
        prefs.addKnownScale(addr)
        Hub.addLog("기기 등록: $name ($addr)")
        toast("$name 등록 완료")
        startBackground()
        Hub.connect()
        showTab(0)
    }

    /** 검색 없이 예전에 연결한 체중계로 바로 등록 */
    private fun quickConnectButton(parent: LinearLayout) {
        val known = prefs.knownScales
        if (known.isEmpty()) return
        val addr = known.first()
        button(parent, "예전에 연결한 체중계로 바로 연결 (${addr.takeLast(5)})", 0) {
            ensurePerms { ensureBtOn { registerScale(addr, prefs.knownName(addr)) } }
        }
    }

    // ───────────────────────── 엔진(Hub) 이벤트

    override fun onHubChanged(kind: Int) {
        when (kind) {
            Hub.EV_STATE -> {
                vConn?.let {
                    it.text = if (Hub.bleConnected) "● 연결됨" else "○ 대기"
                    it.setTextColor(if (Hub.bleConnected) Ui.TEAL_DARK else Ui.SUB)
                }
                if (currentTab == 3) showTab(3)
            }
            Hub.EV_LIVE -> {
                val wv = vLiveWeight
                val lw = Hub.liveWeight
                if (currentTab == 0 && !showingDetail && wv != null && lw != null) {
                    wv.text = f1(lw)
                    vRing?.progress = ((System.currentTimeMillis() - Hub.liveStart) / 6000f).coerceIn(0.08f, 0.92f)
                } else {
                    showingDetail = false
                    showTab(0)
                }
            }
            Hub.EV_FINAL -> { showingDetail = false; showTab(0) }
            Hub.EV_ASK -> Hub.lastMeasure?.let { askWho(it) }
            Hub.EV_DATA -> if (currentTab != 3) showTab(currentTab)
        }
    }

    /** 누구의 측정인지 고르기 */
    private fun askWho(m: Measure) {
        val people = store.all()
        val names = people.map { it.display }.toMutableList()
        names.add("+ 새 사람 추가")
        AlertDialog.Builder(this)
            .setTitle("누구의 측정인가요? · ${f1(m.weight)}kg")
            .setItems(names.toTypedArray()) { _, which ->
                if (which < people.size) {
                    Hub.assign(m, people[which].id)
                    toast("${people[which].display}님 기록으로 저장했어요")
                } else addPerson { p -> Hub.assign(m, p.id) }
            }
            .setNegativeButton("저장 안 함", null)
            .show()
    }

    private fun pickPerson(title: String, onPick: (Profile) -> Unit) {
        val people = store.all()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(people.map { it.display }.toTypedArray()) { _, which -> onPick(people[which]) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun addPerson(then: (Profile) -> Unit) {
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val e = EditText(this)
        e.hint = "예: 아내, 지수"
        e.setSingleLine()
        box.addView(e)
        AlertDialog.Builder(this)
            .setTitle("새 사람 이름")
            .setView(box)
            .setPositiveButton("추가") { _, _ ->
                val n = e.text.toString().trim()
                if (n.isEmpty()) { toast("이름을 입력해 주세요"); return@setPositiveButton }
                val p = store.add(n)
                Hub.profilesChanged()
                then(p)
                toast("$n 님을 추가했어요. 내 정보에서 키·출생연도를 입력해 주세요")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ───────────────────────── 로그 · 보정

    private fun showLogs() {
        val sv = ScrollView(this)
        val tv = label(if (Hub.logs.isEmpty()) "아직 로그가 없어요" else Hub.logs.joinToString("\n"), 11f, Ui.TEXT)
        tv.typeface = Typeface.MONOSPACE
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(16), dp(8), dp(16), dp(8))
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("통신 로그")
            .setView(sv)
            .setPositiveButton("복사") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("scale-log", Hub.logs.joinToString("\n")))
                toast("로그를 복사했어요")
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun enterFamilyCode() {
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val e = EditText(this)
        e.hint = "예: ABCD-EFGH-…"
        e.setSingleLine()
        box.addView(e)
        AlertDialog.Builder(this)
            .setTitle("가족 코드 입력")
            .setMessage("다른 폰의 내 정보 → 가족 폰과 기록 공유에 있는 코드를 입력하세요.")
            .setView(box)
            .setPositiveButton("연결") { _, _ ->
                val c = e.text.toString().uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }
                if (c.length < 20) { toast("코드를 다시 확인해 주세요"); return@setPositiveButton }
                prefs.familyCode = c
                ScaleService.start(this, ScaleService.ACTION_RESYNC)
                toast("연결했어요. 잠시 후 가족 정보가 들어와요")
                handler.postDelayed({
                    if (store.all().size > 1 && prefs.ownerUid == 0)
                        pickPerson("이 폰은 누구 폰인가요?") { p -> prefs.ownerUid = p.id; store.currentId = p.id; showTab(0) }
                    else showTab(3)
                }, 2500)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun calibrate() {
        val pkt = Hub.lastPacket
        if (pkt == null) { toast("먼저 체중계에 올라가 데이터를 받아주세요"); return }
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        e.hint = "예: 72.4"
        box.addView(e)
        AlertDialog.Builder(this)
            .setTitle("실제 체중 입력")
            .setMessage("방금 측정할 때의 실제 체중을 입력하세요.\n마지막 데이터: ${Parser.hex(pkt)}")
            .setView(box)
            .setPositiveButton("찾기") { _, _ ->
                val real = e.text.toString().toDoubleOrNull()
                if (real == null) { toast("숫자를 입력해 주세요"); return@setPositiveButton }
                val cands = Parser.candidates(pkt).filter { abs(it.value - real) <= 0.2 }
                if (cands.isEmpty()) { toast("일치하는 위치를 못 찾았어요. 로그를 확인해 주세요"); return@setPositiveButton }
                val items = cands.map {
                    "바이트 ${it.offset}-${it.offset + 1} · ${if (it.le) "LE" else "BE"} · ÷${it.div} → ${String.format(Locale.KOREA, "%.2f", it.value)} kg"
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("체중 위치 선택")
                    .setItems(items) { _, which ->
                        val c = cands[which]
                        prefs.parseMode = 1
                        prefs.customOffset = c.offset
                        prefs.customLE = c.le
                        prefs.customDiv = c.div
                        toast("적용했어요. 다시 측정해 보세요")
                        showTab(3)
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ───────────────────────── CSV

    private fun exportCsv() {
        val list = db.all(me.id)
        if (list.isEmpty()) { toast("내보낼 기록이 없어요"); return }
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "text/csv"
        i.putExtra(Intent.EXTRA_TITLE, "체중기록_" + SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date()) + ".csv")
        try { startActivityForResult(i, REQ_CSV) } catch (e: Exception) { toast("파일 저장 화면을 열 수 없어요") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CSV || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA)
        val sb = StringBuilder("이름,일시,체중(kg),BMI,체지방률(%),체지방량(kg),근육량(kg),골격근비율(%),체수분(%),기초대사량(kcal),임피던스,입력\n")
        for (r in db.all().reversed()) {
            val b = BodyCalc.calc(r.weight, r.res, r.sfat, profileOf(r.uid))
            fun o(v: Double?) = if (v == null) "" else f1(v)
            sb.append(listOf(
                profileOf(r.uid).display, df.format(Date(r.ts)), String.format(Locale.KOREA, "%.2f", r.weight), o(b?.bmi), o(b?.fatPct), o(b?.fatKg),
                o(b?.muscleKg), o(b?.skeletalPct), o(b?.waterPct), b?.bmr?.toInt()?.toString() ?: "",
                if (r.res > 0) r.res.toString() else "", r.src
            ).joinToString(",")).append('\n')
        }
        try {
            contentResolver.openOutputStream(uri)?.use { os ->
                os.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                os.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
            toast("CSV로 저장했어요")
        } catch (e: Exception) {
            toast("저장 실패: ${e.message}")
        }
    }
}
