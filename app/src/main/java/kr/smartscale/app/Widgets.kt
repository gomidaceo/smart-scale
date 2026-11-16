package kr.smartscale.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** 표준 이하 / 표준 / 표준 이상 3구간 막대 + 현재 위치 표시 */
class ZoneBarView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var pos = 0.5f        // 0..1
    private var showMarker = true
    private var highlight = -1    // 작은 막대: 강조할 구간
    private var compact = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 큰 막대: 값과 표준 범위로 위치 계산 */
    fun setValue(v: Double, lo: Double, hi: Double) {
        var x = (1.0 / 3.0) + (v - lo) / (hi - lo) / 3.0
        x = x.coerceIn(0.02, 0.98)
        pos = x.toFloat(); showMarker = true; compact = false; highlight = -1
        invalidate()
    }

    /** 작은 막대: 구간만 강조 (0 이하, 1 표준, 2 이상) */
    fun setZone(zone: Int) {
        highlight = zone; showMarker = false; compact = true
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (compact) 4 * d else 22 * d
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val gap = 3 * d
        val segW = (w - gap * 2) / 3f
        val barH = if (compact) 4 * d else 8 * d
        val top = if (compact) 0f else 7 * d
        val r = barH / 2
        val base = intArrayOf(Ui.LOW_BG, Ui.STD_BG, Ui.HIGH_BG)
        val strong = intArrayOf(Ui.LOW_ACC, Ui.TEAL, Ui.HIGH_ACC)
        for (i in 0..2) {
            p.color = if (compact && i == highlight) strong[i] else base[i]
            val left = i * (segW + gap)
            c.drawRoundRect(RectF(left, top, left + segW, top + barH), r, r, p)
        }
        if (showMarker) {
            p.color = Ui.TEXT
            val x = w * pos
            c.drawRoundRect(RectF(x - 2 * d, 0f, x + 2 * d, 22 * d), 2 * d, 2 * d, p)
        }
    }
}

/** 측정 중 진행 링 */
class RingView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    var progress = 0.3f
        set(v) { field = v; invalidate() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 12 * d; color = 0xFF1F302C.toInt()
    }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 12 * d; color = 0xFF2DD4BF.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val inset = 8 * d
        val rect = RectF(inset, inset, s - inset, s - inset)
        c.drawArc(rect, 0f, 360f, false, bg)
        c.drawArc(rect, -90f, 360f * progress.coerceIn(0f, 1f), false, fg)
    }
}

/** 작은 추세선 */
class SparkView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var vals: List<Double> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setData(v: List<Double>, color: Int) {
        vals = v; p.color = color; invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), (26 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (vals.size < 2) return
        val mn = vals.min(); var mx = vals.max()
        if (mx - mn < 1e-6) mx = mn + 1
        val w = width.toFloat() - 4 * d
        val h = height.toFloat() - 4 * d
        val path = Path()
        vals.forEachIndexed { i, v ->
            val x = 2 * d + w * i / (vals.size - 1)
            val y = 2 * d + h - h * ((v - mn) / (mx - mn)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, p)
    }
}

/** 하단 탭 아이콘 (선 아이콘) */
class NavIcon(ctx: Context, private val kind: Int) : Drawable() {
    private val d = ctx.resources.displayMetrics.density
    var active = false
        set(v) { field = v; invalidateSelf() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun getIntrinsicWidth(): Int = (24 * d).toInt()
    override fun getIntrinsicHeight(): Int = (24 * d).toInt()

    override fun draw(c: Canvas) {
        p.color = if (active) Ui.TEAL_DARK else Ui.SUB
        val s = d
        c.save()
        c.translate(bounds.left.toFloat(), bounds.top.toFloat())
        when (kind) {
            0 -> {
                c.drawRoundRect(RectF(4 * s, 4 * s, 20 * s, 20 * s), 4 * s, 4 * s, p)
                c.drawLine(9 * s, 9 * s, 15 * s, 9 * s, p)
            }
            1 -> {
                for (y in listOf(6f, 12f, 18f)) {
                    c.drawLine(8 * s, y * s, 20 * s, y * s, p)
                    c.drawCircle(4 * s, y * s, 1 * s, p)
                }
            }
            2 -> {
                val path = Path()
                path.moveTo(3 * s, 17 * s); path.lineTo(9 * s, 11 * s)
                path.lineTo(13 * s, 15 * s); path.lineTo(21 * s, 7 * s)
                path.moveTo(15 * s, 7 * s); path.lineTo(21 * s, 7 * s); path.lineTo(21 * s, 13 * s)
                c.drawPath(path, p)
            }
            else -> {
                c.drawCircle(12 * s, 8 * s, 4 * s, p)
                val path = Path()
                path.moveTo(4 * s, 21 * s)
                path.cubicTo(5.5f * s, 17 * s, 8.5f * s, 15 * s, 12 * s, 15 * s)
                path.cubicTo(15.5f * s, 15 * s, 18.5f * s, 17 * s, 20 * s, 21 * s)
                c.drawPath(path, p)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
