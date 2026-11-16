package kr.smartscale.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 외부 라이브러리 없이 그리는 꺾은선 그래프 */
class ChartView(ctx: Context) : View(ctx) {
    private var pts: List<Pair<Long, Double>> = emptyList()
    private var goal: Double? = null
    private var unit = "kg"
    private val d = resources.displayMetrics.density
    private val df = SimpleDateFormat("M/d", Locale.KOREA)

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEAL; strokeWidth = 2.5f * d; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x220EA5A4; style = Paint.Style.FILL }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.TEAL; style = Paint.Style.FILL }
    private val grid = Paint().apply { color = 0xFFE5E7EB.toInt(); strokeWidth = 1f * d }
    private val goalP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.ORANGE; strokeWidth = 1.5f * d; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f * d, 6f * d), 0f)
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.SUB; textSize = 11f * d }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.TEXT; textSize = 12f * d; isFakeBoldText = true
    }

    fun setData(points: List<Pair<Long, Double>>, goalValue: Double?, unitText: String) {
        pts = points.sortedBy { it.first }
        goal = goalValue
        unit = unitText
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), (240 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (pts.isEmpty()) {
            txt.textAlign = Paint.Align.CENTER
            canvas.drawText("이 기간에 기록이 없어요", w / 2, h / 2, txt)
            txt.textAlign = Paint.Align.LEFT
            return
        }
        val left = 40 * d
        val right = w - 14 * d
        val top = 18 * d
        val bottom = h - 26 * d

        var minV = pts.minOf { it.second }
        var maxV = pts.maxOf { it.second }
        goal?.let { if (it > 0) { minV = minOf(minV, it); maxV = maxOf(maxV, it) } }
        if (maxV - minV < 1.0) { minV -= 0.5; maxV += 0.5 }
        val pad = (maxV - minV) * 0.1
        minV -= pad; maxV += pad

        var minT = pts.first().first
        var maxT = pts.last().first
        if (maxT - minT < 60_000L) { minT -= 43_200_000L; maxT += 43_200_000L }

        fun x(t: Long) = left + (right - left) * ((t - minT).toFloat() / (maxT - minT).toFloat())
        fun y(v: Double) = (bottom - (bottom - top) * ((v - minV) / (maxV - minV))).toFloat()

        // 가로 눈금
        txt.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val v = minV + (maxV - minV) * i / 4.0
            val yy = y(v)
            canvas.drawLine(left, yy, right, yy, grid)
            canvas.drawText(String.format(Locale.KOREA, "%.1f", v), left - 6 * d, yy + 4 * d, txt)
        }
        // 날짜
        txt.textAlign = Paint.Align.LEFT
        canvas.drawText(df.format(Date(minT)), left, h - 6 * d, txt)
        txt.textAlign = Paint.Align.RIGHT
        canvas.drawText(df.format(Date(maxT)), right, h - 6 * d, txt)
        if (maxT - minT > 3 * 86_400_000L) {
            txt.textAlign = Paint.Align.CENTER
            canvas.drawText(df.format(Date((minT + maxT) / 2)), (left + right) / 2, h - 6 * d, txt)
        }
        txt.textAlign = Paint.Align.LEFT

        // 목표선
        goal?.let {
            if (it > 0) {
                val gy = y(it)
                val gp = Path(); gp.moveTo(left, gy); gp.lineTo(right, gy)
                canvas.drawPath(gp, goalP)
                txt.color = Ui.ORANGE
                canvas.drawText("목표 ${String.format(Locale.KOREA, "%.1f", it)}", left + 4 * d, gy - 4 * d, txt)
                txt.color = Ui.SUB
            }
        }

        // 선 + 채우기
        val path = Path()
        val area = Path()
        pts.forEachIndexed { i, p ->
            val px = x(p.first); val py = y(p.second)
            if (i == 0) { path.moveTo(px, py); area.moveTo(px, bottom); area.lineTo(px, py) }
            else { path.lineTo(px, py); area.lineTo(px, py) }
        }
        area.lineTo(x(pts.last().first), bottom)
        area.close()
        canvas.drawPath(area, fill)
        canvas.drawPath(path, line)
        val r = if (pts.size > 60) 1.5f * d else 3f * d
        for (p in pts) canvas.drawCircle(x(p.first), y(p.second), r, dot)

        // 마지막 값
        val last = pts.last()
        val lx = x(last.first); val ly = y(last.second)
        canvas.drawCircle(lx, ly, 5f * d, dot)
        bold.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format(Locale.KOREA, "%.1f%s", last.second, unit), right, ly - 10 * d, bold)
    }
}
