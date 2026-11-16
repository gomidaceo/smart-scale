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
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object Ui {
    const val BG = 0xFFF4F6F8.toInt()
    const val TEAL = 0xFF0EA5A4.toInt()
    const val TEXT = 0xFF1B1F24.toInt()
    const val SUB = 0xFF6B7280.toInt()
    const val LINE = 0xFFE5E7EB.toInt()
    const val RED = 0xFFEF4444.toInt()
    const val ORANGE = 0xFFF59E0B.toInt()
    const val GREEN = 0xFF10B981.toInt()
    const val BLUE = 0xFF3B82F6.toInt()
    const val CHIP = 0xFFEEF0F3.toInt()
}

@SuppressLint("MissingPermission", "SetTextI18n")
@Suppress("DEPRECATION")
class MainActivity : Activity(), ScaleBle.Listener {

    private val REQ_PERM = 1
    private val REQ_CSV = 2

    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private lateinit var ble: ScaleBle
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var content: FrameLayout
    private val tabs = ArrayList<TextView>()
    private var currentTab = 0

    // 측정 상태
    private var statusText = "등록된 체중계가 없어요"
    private var bleConnected = false
    private var liveWeight: Double? = null
    private var lastMeasure: Measure? = null
    private var lastSavedId: Long = -1
    private var lastPacket: ByteArray? = null
    private val logs = ArrayDeque<String>()
    private val recent = ArrayList<Pair<Long, Double>>()

    // 측정 화면 뷰
    private var vStatus: TextView? = null
    private var vWeight: TextView? = null
    private var vSub: TextView? = null
    private var vResult: LinearLayout? = null

    private var pendingPerm: (() -> Unit)? = null
    private var graphDays = 30
    private var graphMetric = 0

    data class Measure(val ts: Long, val weight: Double, var res: Int, val sfat: Double?, var saved: Boolean)

    private val fullDate = SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREA)
    private val timeOnly = SimpleDateFormat("a h:mm", Locale.KOREA)

    // ───────────────────────── 생명주기

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        db = Db(this)
        ble = ScaleBle(this, this)

        window.statusBarColor = Ui.BG
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Ui.BG)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.setBackgroundColor(Color.WHITE)
        nav.elevation = dp(8).toFloat()
        listOf("⚖️\n측정", "📋\n기록", "📈\n그래프", "⚙️\n설정").forEachIndexed { i, t ->
            val tv = TextView(this)
            tv.text = t
            tv.gravity = Gravity.CENTER
            tv.textSize = 12f
            tv.setPadding(0, dp(8), 0, dp(8))
            tv.setOnClickListener { showTab(i) }
            tabs.add(tv)
            nav.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)

        if (!prefs.profileReady) {
            showTab(3)
            toast("먼저 내 프로필(키·출생연도·성별)을 입력해 주세요")
        } else {
            showTab(0)
        }

        if (prefs.deviceAddr.isNotEmpty()) {
            ensurePerms { ensureBtOn { ble.connect(prefs.deviceAddr) } }
        }
    }

    override fun onDestroy() {
        ble.disconnect()
        super.onDestroy()
    }

    // ───────────────────────── 공통 UI 도우미

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int = 16): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun label(s: String, size: Float = 15f, color: Int = Ui.TEXT, bold: Boolean = false): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    private fun page(): Pair<ScrollView, LinearLayout> {
        val sv = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(20), dp(16), dp(28))
        sv.addView(col)
        return sv to col
    }

    private fun title(parent: LinearLayout, s: String, sub: String? = null) {
        parent.addView(label(s, 24f, Ui.TEXT, true))
        if (sub != null) parent.addView(label(sub, 13f, Ui.SUB))
        val sp = View(this)
        parent.addView(sp, LinearLayout.LayoutParams(1, dp(12)))
    }

    private fun card(parent: LinearLayout, heading: String? = null): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(Color.WHITE, 18)
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(12)
        parent.addView(c, lp)
        if (heading != null) {
            val h = label(heading, 13f, Ui.SUB, true)
            h.setPadding(0, 0, 0, dp(6))
            c.addView(h)
        }
        return c
    }

    private fun button(parent: LinearLayout, s: String, primary: Boolean = true, onClick: () -> Unit): TextView {
        val b = label(s, 15f, if (primary) Color.WHITE else Ui.TEXT, true)
        b.gravity = Gravity.CENTER
        b.background = rounded(if (primary) Ui.TEAL else Ui.CHIP, 12)
        b.setPadding(dp(12), dp(13), dp(12), dp(13))
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(8)
        parent.addView(b, lp)
        return b
    }

    private fun buttonRow(parent: LinearLayout, items: List<Pair<String, () -> Unit>>) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { i, (s, f) ->
            val b = label(s, 14f, Ui.TEXT, true)
            b.gravity = Gravity.CENTER
            b.background = rounded(Ui.CHIP, 12)
            b.setPadding(dp(8), dp(12), dp(8), dp(12))
            b.setOnClickListener { f() }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(8)
            row.addView(b, lp)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(12)
        parent.addView(row, lp)
    }

    private fun chips(parent: LinearLayout, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        items.forEachIndexed { i, s ->
            val on = i == selected
            val b = label(s, 13f, if (on) Color.WHITE else Ui.TEXT, on)
            b.gravity = Gravity.CENTER
            b.background = rounded(if (on) Ui.TEAL else Color.WHITE, 20)
            b.setPadding(dp(4), dp(8), dp(4), dp(8))
            b.setOnClickListener { onPick(i) }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(6)
            row.addView(b, lp)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(10)
        parent.addView(row, lp)
    }

    private fun metricRow(parent: LinearLayout, name: String, value: String, grade: Pair<String, Int>? = null) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(9), 0, dp(9))
        row.addView(label(name, 15f, Ui.SUB), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(label(value, 16f, Ui.TEXT, true))
        if (grade != null) {
            val chip = label(grade.first, 12f, Color.WHITE, true)
            chip.background = rounded(grade.second, 10)
            chip.setPadding(dp(8), dp(3), dp(8), dp(3))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.leftMargin = dp(8)
            row.addView(chip, lp)
        }
        parent.addView(row)
        val line = View(this)
        line.setBackgroundColor(Ui.LINE)
        parent.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
    }

    private fun field(parent: LinearLayout, name: String, value: String, type: Int): EditText {
        parent.addView(label(name, 13f, Ui.SUB))
        val e = EditText(this)
        e.setText(value)
        e.inputType = type
        e.textSize = 16f
        e.setSingleLine()
        parent.addView(e, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return e
    }

    private fun switchRow(parent: LinearLayout, name: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, dp(6))
        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(label(name, 15f))
        texts.addView(label(desc, 12f, Ui.SUB))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        row.addView(sw)
        parent.addView(row)
    }

    private fun f1(v: Double) = String.format(Locale.KOREA, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.KOREA, "%.2f", v)
    private fun signed(v: Double, unit: String): String {
        val arrow = when { v > 0.05 -> "▲ "; v < -0.05 -> "▼ "; else -> "" }
        return arrow + f1(abs(v)) + unit
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ───────────────────────── 탭 전환

    private fun showTab(i: Int) {
        currentTab = i
        tabs.forEachIndexed { idx, tv ->
            tv.setTextColor(if (idx == i) Ui.TEAL else Ui.SUB)
            tv.setTypeface(null, if (idx == i) Typeface.BOLD else Typeface.NORMAL)
        }
        vStatus = null; vWeight = null; vSub = null; vResult = null
        content.removeAllViews()
        val v = when (i) {
            0 -> buildMeasure()
            1 -> buildRecords()
            2 -> buildGraph()
            else -> buildSettings()
        }
        content.addView(v)
    }

    // ───────────────────────── 측정 화면

    private fun buildMeasure(): View {
        val (sv, col) = page()
        val hello = if (prefs.name.isNotEmpty()) "${prefs.name}님" else "내 체중계"
        title(col, hello, fullDate.format(Date()))

        val c1 = card(col, "체중계")
        c1.addView(label(if (prefs.deviceAddr.isEmpty()) "등록된 체중계가 없어요" else "🔗 ${prefs.deviceName}", 14f, Ui.SUB))
        val st = label(statusText, 16f, if (bleConnected) Ui.TEAL else Ui.TEXT, true)
        st.setPadding(0, dp(4), 0, 0)
        c1.addView(st)
        vStatus = st
        if (prefs.deviceAddr.isEmpty()) {
            button(c1, "체중계 찾기") { startScan() }
        } else if (!bleConnected) {
            button(c1, "다시 연결", false) { ensurePerms { ensureBtOn { ble.connect(prefs.deviceAddr) } } }
        }

        val c2 = card(col)
        val w = label("-- kg", 54f, Ui.TEXT, true)
        w.gravity = Gravity.CENTER_HORIZONTAL
        c2.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val s = label("", 14f, Ui.SUB)
        s.gravity = Gravity.CENTER_HORIZONTAL
        c2.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        vWeight = w; vSub = s

        val res = LinearLayout(this)
        res.orientation = LinearLayout.VERTICAL
        col.addView(res)
        vResult = res

        renderMeasure()
        return sv
    }

    private fun renderMeasure() {
        val w = vWeight ?: return
        val s = vSub ?: return
        val res = vResult ?: return
        res.removeAllViews()
        val m = lastMeasure
        if (m == null) {
            val lw = liveWeight
            if (lw != null) {
                w.text = f2(lw) + " kg"
                w.setTextColor(Ui.SUB)
                s.text = "측정 중… 움직이지 말고 서 계세요"
            } else {
                val last = db.all().firstOrNull()
                w.text = if (last != null) f2(last.weight) + " kg" else "-- kg"
                w.setTextColor(if (last != null) Ui.SUB else Ui.LINE)
                s.text = if (last != null) "마지막 측정 · ${fullDate.format(Date(last.ts))}" else "체중계에 맨발로 올라가면 자동으로 측정돼요"
                val tip = card(res, "측정 팁")
                tip.addView(label("• 아침 공복, 화장실 다녀온 뒤 같은 시간에 재면 추세가 정확해요\n• 체성분은 맨발로, 손잡이를 양손으로 잡고 측정하세요\n• 운동·샤워 직후엔 수분 때문에 체지방이 다르게 나올 수 있어요", 14f, Ui.TEXT))
            }
            return
        }

        w.text = f2(m.weight) + " kg"
        w.setTextColor(Ui.TEXT)
        val prev = db.all().firstOrNull { it.id != lastSavedId && it.ts < m.ts - 1000 }
        val parts = ArrayList<String>()
        parts.add(timeOnly.format(Date(m.ts)) + " 측정")
        if (prev != null) parts.add("지난번보다 " + signed(m.weight - prev.weight, "kg"))
        s.text = parts.joinToString(" · ")

        val body = BodyCalc.calc(m.weight, m.res, m.sfat, prefs)
        if (body == null) {
            val c = card(res, "체성분")
            c.addView(label("설정에서 키·출생연도·성별을 입력하면 BMI와 체성분을 계산해 드려요.", 14f))
            button(c, "프로필 입력하기") { showTab(3) }
        } else {
            renderBody(res, m.weight, m.res, body)
        }

        if (prefs.goal > 0) {
            val c = card(res, "목표")
            val diff = m.weight - prefs.goal
            metricRow(c, "목표 체중", f1(prefs.goal.toDouble()) + " kg")
            metricRow(c, "남은 양", if (abs(diff) < 0.1) "달성! 🎉" else signed(-diff, "kg"))
        }

        if (!m.saved) {
            button(res, "이 측정 기록 저장") { saveMeasure(m); renderMeasure() }
        } else {
            val ok = label("✓ 기록에 저장됨", 13f, Ui.TEAL, true)
            ok.gravity = Gravity.CENTER_HORIZONTAL
            res.addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        button(res, "새로 측정하기", false) { lastMeasure = null; liveWeight = null; renderMeasure() }
    }

    private fun renderBody(parent: LinearLayout, weight: Double, resistance: Int, b: Body) {
        val c = card(parent, "체성분 분석 (추정)")
        metricRow(c, "BMI", f1(b.bmi), BodyCalc.bmiGrade(b.bmi))
        val fat = b.fatPct
        if (fat != null) {
            metricRow(c, "체지방률", f1(fat) + " %", BodyCalc.fatGrade(fat, prefs.male))
            metricRow(c, "체지방량", f1(b.fatKg ?: 0.0) + " kg")
            metricRow(c, "근육량", f1(b.muscleKg ?: 0.0) + " kg")
            metricRow(c, "골격근 비율", f1(b.skeletalPct ?: 0.0) + " %")
            metricRow(c, "체수분", f1(b.waterPct ?: 0.0) + " %")
            metricRow(c, "제지방량", f1(b.lbm ?: 0.0) + " kg")
        } else {
            c.addView(label(if (resistance <= 0) "⚠ 임피던스 값이 없어요. 맨발로 다시 측정하면 체지방·근육량이 나와요." else "⚠ 체지방을 계산하지 못했어요.", 13f, Ui.ORANGE))
        }
        metricRow(c, "기초대사량", String.format(Locale.KOREA, "%,d kcal", b.bmr.toInt()))
        metricRow(c, "표준 체중", f1(b.stdWeight) + " kg")
        metricRow(c, "비만도", f1(b.obesity) + " %")
        if (resistance > 0) metricRow(c, "임피던스", "$resistance Ω")
        b.scaleFat?.let { metricRow(c, "체중계 제공 체지방(참고)", f1(it) + " %") }
        val note = label("가정용 체중계의 체성분은 추정치예요. 절대값보다 변화 추이로 보세요.", 12f, Ui.SUB)
        note.setPadding(0, dp(8), 0, 0)
        c.addView(note)
    }

    // ───────────────────────── 기록 화면

    private fun buildRecords(): View {
        val (sv, col) = page()
        val list = db.all()
        title(col, "기록", "총 ${list.size}회 측정")

        if (list.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val c = card(col, "요약")
            metricRow(c, "최근 체중", f2(list[0].weight) + " kg")
            val week = list.filter { it.ts >= now - 7 * 86_400_000L }
            if (week.isNotEmpty()) metricRow(c, "최근 7일 평균", f2(week.map { it.weight }.average()) + " kg")
            val month = list.filter { it.ts >= now - 30 * 86_400_000L }
            if (month.size >= 2) metricRow(c, "30일 변화", signed(month.first().weight - month.last().weight, "kg"))
            if (list.size >= 2) metricRow(c, "처음 대비", signed(list.first().weight - list.last().weight, "kg"))
        }

        buttonRow(col, listOf(
            "✏️ 직접 입력" to { manualEntry() },
            "📤 CSV 내보내기" to { exportCsv() }
        ))

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
            }
            val body = BodyCalc.calc(r.weight, r.res, r.sfat, prefs)
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(10), 0, dp(10))
            val left = LinearLayout(this)
            left.orientation = LinearLayout.VERTICAL
            left.addView(label(timeOnly.format(Date(r.ts)) + if (r.src == "수동") "  ✏️" else "", 14f, Ui.SUB))
            val detail = ArrayList<String>()
            if (body != null) {
                detail.add("BMI " + f1(body.bmi))
                body.fatPct?.let { detail.add("체지방 " + f1(it) + "%") }
                body.muscleKg?.let { detail.add("근육 " + f1(it) + "kg") }
            }
            if (detail.isNotEmpty()) left.addView(label(detail.joinToString(" · "), 12f, Ui.SUB))
            row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(label(f2(r.weight) + " kg", 18f, Ui.TEXT, true))
            row.setOnClickListener { recordDetail(r) }
            g.addView(row)
        }
        return sv
    }

    private fun recordDetail(r: Rec) {
        val b = BodyCalc.calc(r.weight, r.res, r.sfat, prefs)
        val sb = StringBuilder()
        sb.append("체중: ${f2(r.weight)} kg\n")
        if (b != null) {
            sb.append("BMI: ${f1(b.bmi)} (${BodyCalc.bmiGrade(b.bmi).first})\n")
            b.fatPct?.let { sb.append("체지방률: ${f1(it)} % (${BodyCalc.fatGrade(it, prefs.male).first})\n") }
            b.fatKg?.let { sb.append("체지방량: ${f1(it)} kg\n") }
            b.muscleKg?.let { sb.append("근육량: ${f1(it)} kg\n") }
            b.skeletalPct?.let { sb.append("골격근 비율: ${f1(it)} %\n") }
            b.waterPct?.let { sb.append("체수분: ${f1(it)} %\n") }
            b.lbm?.let { sb.append("제지방량: ${f1(it)} kg\n") }
            sb.append("기초대사량: ${b.bmr.toInt()} kcal\n")
        }
        if (r.res > 0) sb.append("임피던스: ${r.res} Ω\n")
        sb.append("입력 방식: ${r.src}")
        AlertDialog.Builder(this)
            .setTitle(fullDate.format(Date(r.ts)))
            .setMessage(sb.toString())
            .setPositiveButton("닫기", null)
            .setNegativeButton("삭제") { _, _ ->
                AlertDialog.Builder(this)
                    .setMessage("이 기록을 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ -> db.delete(r.id); toast("삭제했어요"); showTab(1) }
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
                else { db.insert(System.currentTimeMillis(), v, 0, null, "수동"); toast("저장했어요"); showTab(1) }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ───────────────────────── 그래프 화면

    private fun buildGraph(): View {
        val (sv, col) = page()
        title(col, "그래프")
        val dayOpts = listOf(7, 30, 90, 365, 0)
        chips(col, listOf("1주", "1개월", "3개월", "1년", "전체"), dayOpts.indexOf(graphDays).coerceAtLeast(0)) {
            graphDays = dayOpts[it]; showTab(2)
        }
        chips(col, listOf("체중", "체지방률", "근육량", "BMI"), graphMetric) {
            graphMetric = it; showTab(2)
        }

        val now = System.currentTimeMillis()
        val recs = db.all().filter { graphDays == 0 || it.ts >= now - graphDays * 86_400_000L }
        val pts = ArrayList<Pair<Long, Double>>()
        for (r in recs) {
            val v: Double? = when (graphMetric) {
                0 -> r.weight
                else -> {
                    val b = BodyCalc.calc(r.weight, r.res, r.sfat, prefs)
                    when (graphMetric) {
                        1 -> b?.fatPct
                        2 -> b?.muscleKg
                        else -> b?.bmi
                    }
                }
            }
            if (v != null) pts.add(r.ts to v)
        }
        val unit = when (graphMetric) { 1 -> "%"; 3 -> ""; else -> "kg" }

        val c = card(col)
        val chart = ChartView(this)
        chart.setData(pts, if (graphMetric == 0 && prefs.goal > 0) prefs.goal.toDouble() else null, unit)
        c.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (pts.isNotEmpty()) {
            val sorted = pts.sortedBy { it.first }
            val st = card(col, "이 기간 통계")
            metricRow(st, "측정 횟수", "${pts.size}회")
            metricRow(st, "최저", f1(pts.minOf { it.second }) + unit)
            metricRow(st, "최고", f1(pts.maxOf { it.second }) + unit)
            metricRow(st, "평균", f1(pts.map { it.second }.average()) + unit)
            if (sorted.size >= 2) metricRow(st, "변화", signed(sorted.last().second - sorted.first().second, unit))
        } else if (graphMetric != 0 && !prefs.profileReady) {
            val n = card(col)
            n.addView(label("체성분 그래프를 보려면 설정에서 프로필을 입력하세요.", 14f, Ui.SUB))
        }
        return sv
    }

    // ───────────────────────── 설정 화면

    private fun buildSettings(): View {
        val (sv, col) = page()
        title(col, "설정")

        val pc = card(col, "내 프로필 (체성분 계산에 사용)")
        val eName = field(pc, "이름", prefs.name, InputType.TYPE_CLASS_TEXT)
        pc.addView(label("성별", 13f, Ui.SUB))
        val rg = RadioGroup(this)
        rg.orientation = RadioGroup.HORIZONTAL
        val rbM = RadioButton(this); rbM.text = "남성"; rbM.id = View.generateViewId()
        val rbF = RadioButton(this); rbF.text = "여성"; rbF.id = View.generateViewId()
        rg.addView(rbM); rg.addView(rbF)
        rg.check(if (prefs.male) rbM.id else rbF.id)
        pc.addView(rg)
        val eYear = field(pc, "출생연도 (예: 1981)", if (prefs.birthYear > 0) prefs.birthYear.toString() else "", InputType.TYPE_CLASS_NUMBER)
        val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val eHeight = field(pc, "키 (cm)", if (prefs.heightCm > 0) f1(prefs.heightCm.toDouble()) else "", dec)
        val eGoal = field(pc, "목표 체중 (kg, 선택)", if (prefs.goal > 0) f1(prefs.goal.toDouble()) else "", dec)
        button(pc, "프로필 저장") {
            val y = eYear.text.toString().trim().toIntOrNull()
            val h = eHeight.text.toString().trim().toFloatOrNull()
            if (y == null || y < 1900 || y > 2030) { toast("출생연도를 확인해 주세요"); return@button }
            if (h == null || h < 80 || h > 250) { toast("키를 확인해 주세요"); return@button }
            prefs.name = eName.text.toString().trim()
            prefs.male = rg.checkedRadioButtonId == rbM.id
            prefs.birthYear = y
            prefs.heightCm = h
            prefs.goal = eGoal.text.toString().trim().toFloatOrNull() ?: 0f
            toast("저장했어요")
            showTab(0)
        }

        val dc = card(col, "체중계")
        if (prefs.deviceAddr.isEmpty()) {
            dc.addView(label("등록된 체중계가 없어요", 15f))
            button(dc, "체중계 찾기") { startScan() }
        } else {
            dc.addView(label(prefs.deviceName, 16f, Ui.TEXT, true))
            dc.addView(label(prefs.deviceAddr + " · " + statusText, 12f, Ui.SUB))
            buttonRow(dc, listOf(
                "다시 연결" to { ensurePerms { ensureBtOn { ble.connect(prefs.deviceAddr) } } },
                "다른 기기 찾기" to { startScan() }
            ))
            button(dc, "체중계 등록 해제", false) {
                ble.disconnect()
                prefs.deviceAddr = ""; prefs.deviceName = ""
                statusText = "등록된 체중계가 없어요"
                showTab(3)
            }
        }

        val mc = card(col, "측정")
        switchRow(mc, "자동 저장", "측정이 끝나면 바로 기록에 저장", prefs.autoSave) { prefs.autoSave = it }
        switchRow(mc, "완료 진동", "측정이 끝나면 짧게 진동", prefs.vibrate) { prefs.vibrate = it }

        val data = card(col, "데이터")
        data.addView(label("측정 기록은 이 폰에만 저장돼요. 폰을 바꾸기 전에 CSV로 내보내 두세요.", 13f, Ui.SUB))
        button(data, "CSV로 내보내기", false) { exportCsv() }
        button(data, "전체 기록 삭제", false) {
            AlertDialog.Builder(this)
                .setTitle("전체 기록 삭제")
                .setMessage("모든 측정 기록을 지울까요? 되돌릴 수 없어요.")
                .setPositiveButton("삭제") { _, _ -> db.clear(); toast("모두 삭제했어요"); showTab(3) }
                .setNegativeButton("취소", null)
                .show()
        }

        val adv = card(col, "고급 · 문제 해결")
        val mode = if (prefs.parseMode == 0) "자동 (YUNMAI 형식)"
        else "사용자 지정 (바이트 ${prefs.customOffset}, ÷${prefs.customDiv}, ${if (prefs.customLE) "LE" else "BE"})"
        adv.addView(label("데이터 해석: $mode", 14f))
        adv.addView(label("체중이 이상하게 나오면 보정 도구로 실제 체중에 맞추세요.", 12f, Ui.SUB))
        button(adv, "보정 도구 (실제 체중으로 맞추기)", false) { calibrate() }
        if (prefs.parseMode != 0) button(adv, "자동 해석으로 되돌리기", false) { prefs.parseMode = 0; toast("자동 해석으로 바꿨어요"); showTab(3) }
        button(adv, "통신 로그 보기", false) { showLogs() }

        val info = card(col, "앱 정보")
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        info.addView(label("내 체중계 v$ver", 14f))
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
        if (requestCode != REQ_PERM) return
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

    private fun doScan() {
        val scanner = btAdapter()?.bluetoothLeScanner
        if (scanner == null) { toast("블루투스 검색을 시작할 수 없어요"); return }
        val names = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        val addrs = ArrayList<String>()
        val devNames = ArrayList<String>()
        var stopped = false
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = result.device.name ?: result.scanRecord?.deviceName ?: return
                val a = result.device.address
                runOnUiThread {
                    if (!addrs.contains(a)) {
                        val star = if (n.uppercase(Locale.ROOT).contains("YUNMAI") || n.uppercase(Locale.ROOT).contains("SCALE")) "⚖️ " else ""
                        if (star.isNotEmpty()) { addrs.add(0, a); devNames.add(0, n); names.insert("$star$n\n$a", 0) }
                        else { addrs.add(a); devNames.add(n); names.add("$n\n$a") }
                    }
                }
            }
        }
        fun stop() {
            if (stopped) return
            stopped = true
            try { scanner.stopScan(cb) } catch (e: Exception) {}
        }
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) {
            toast("검색 오류: ${e.message}"); return
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("체중계에 올라가 주세요… (검색 중)")
            .setAdapter(names) { _, which ->
                stop()
                val a = addrs[which]
                val n = devNames[which]
                prefs.deviceAddr = a
                prefs.deviceName = n
                addLog("기기 등록: $n ($a)")
                toast("$n 등록 완료")
                ble.connect(a)
                showTab(0)
            }
            .setNegativeButton("취소", null)
            .setOnDismissListener { stop() }
            .show()
        handler.postDelayed({
            stop()
            if (dlg.isShowing) dlg.setTitle(if (addrs.isEmpty()) "기기를 찾지 못했어요" else "검색 완료 — 기기를 선택하세요")
        }, 20_000)
    }

    // ───────────────────────── BLE 콜백

    override fun onBleState(text: String, connected: Boolean) {
        val wasConnected = bleConnected
        statusText = text
        bleConnected = connected
        vStatus?.let {
            it.text = text
            it.setTextColor(if (connected) Ui.TEAL else Ui.TEXT)
        }
        if (wasConnected != connected && currentTab == 0 && lastMeasure == null) showTab(0)
    }

    override fun onBleLog(msg: String) = addLog(msg)

    override fun onBleData(uuid: String, data: ByteArray) {
        lastPacket = data
        val short = if (uuid.length >= 8) uuid.substring(4, 8) else uuid
        addLog("← $short (${data.size}B) ${Parser.hex(data)}")
        val p = Parser.parse(data, prefs) ?: return
        if (p.final) {
            onFinal(p)
        } else {
            val lm = lastMeasure
            if (lm != null && System.currentTimeMillis() - lm.ts < 8000) return // 최종값 직후의 잔여 패킷 무시
            lastMeasure = null
            liveWeight = p.weight
            if (prefs.parseMode == 1) checkStable(p.weight)
            if (currentTab == 0) renderMeasure() else showTab(0)
        }
    }

    /** 사용자 지정 모드: 같은 값이 1.5초 이상 유지되면 최종값으로 판단 */
    private fun checkStable(w: Double) {
        val now = System.currentTimeMillis()
        recent.add(now to w)
        recent.removeAll { now - it.first > 3000 }
        if (recent.size >= 4) {
            val vals = recent.map { it.second }
            val span = now - recent.first().first
            if (vals.max() - vals.min() <= 0.05 && span >= 1500) {
                recent.clear()
                onFinal(Packet(true, vals.average(), 0, null))
            }
        }
    }

    private fun onFinal(p: Packet) {
        val now = System.currentTimeMillis()
        val lm = lastMeasure
        if (lm != null && now - lm.ts < 20_000 && abs(lm.weight - p.weight) < 0.3) {
            if (p.res > 0 && lm.res == 0) { lm.res = p.res; if (currentTab == 0) renderMeasure() }
            return // 같은 측정의 중복 패킷
        }
        liveWeight = null
        val m = Measure(now, p.weight, p.res, p.scaleFat, false)
        lastMeasure = m
        addLog("최종값 ${f2(p.weight)}kg, 임피던스 ${p.res}")
        if (prefs.vibrate) vibrate()
        if (prefs.autoSave) saveMeasure(m)
        if (currentTab == 0) renderMeasure() else showTab(0)
    }

    private fun saveMeasure(m: Measure) {
        if (m.saved) return
        lastSavedId = db.insert(m.ts, m.weight, m.res, m.sfat, "체중계")
        m.saved = true
        toast("기록을 저장했어요")
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {}
    }

    // ───────────────────────── 로그 · 보정

    private fun addLog(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date())
        logs.addLast("[$t] $msg")
        while (logs.size > 600) logs.removeFirst()
    }

    private fun showLogs() {
        val sv = ScrollView(this)
        val tv = label(if (logs.isEmpty()) "아직 로그가 없어요" else logs.joinToString("\n"), 11f, Ui.TEXT)
        tv.typeface = Typeface.MONOSPACE
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(16), dp(8), dp(16), dp(8))
        sv.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("통신 로그")
            .setView(sv)
            .setPositiveButton("복사") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("scale-log", logs.joinToString("\n")))
                toast("로그를 복사했어요")
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun calibrate() {
        val pkt = lastPacket
        if (pkt == null) { toast("먼저 체중계에 올라가 데이터를 받아주세요"); return }
        val box = FrameLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        e.hint = "예: 72.4"
        box.addView(e)
        AlertDialog.Builder(this)
            .setTitle("실제 체중 입력")
            .setMessage("방금 측정할 때의 실제 체중(공식 앱 값 등)을 입력하세요.\n마지막 데이터: ${Parser.hex(pkt)}")
            .setView(box)
            .setPositiveButton("찾기") { _, _ ->
                val real = e.text.toString().toDoubleOrNull()
                if (real == null) { toast("숫자를 입력해 주세요"); return@setPositiveButton }
                val cands = Parser.candidates(pkt).filter { abs(it.value - real) <= 0.2 }
                if (cands.isEmpty()) { toast("일치하는 위치를 못 찾았어요. 로그를 확인해 주세요"); return@setPositiveButton }
                val items = cands.map { "바이트 ${it.offset}-${it.offset + 1} · ${if (it.le) "LE" else "BE"} · ÷${it.div} → ${f2(it.value)} kg" }.toTypedArray()
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
        val list = db.all()
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
        val sb = StringBuilder("일시,체중(kg),BMI,체지방률(%),체지방량(kg),근육량(kg),골격근비율(%),체수분(%),기초대사량(kcal),임피던스,입력\n")
        for (r in db.all().reversed()) {
            val b = BodyCalc.calc(r.weight, r.res, r.sfat, prefs)
            fun o(v: Double?) = if (v == null) "" else f1(v)
            sb.append(listOf(
                df.format(Date(r.ts)), f2(r.weight), o(b?.bmi), o(b?.fatPct), o(b?.fatKg), o(b?.muscleKg),
                o(b?.skeletalPct), o(b?.waterPct), b?.bmr?.toInt()?.toString() ?: "",
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
