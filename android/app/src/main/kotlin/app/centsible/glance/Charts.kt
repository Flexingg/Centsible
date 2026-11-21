package app.centsible.glance

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import app.centsible.core.domain.Glanceable

/**
 * The widget charts, drawn to bitmaps (widgets can't draw lines or bars themselves). Sizes
 * are in pixels; colours come from the widget's theme so they match light and dark.
 */
internal object Charts {
    data class Colors(val line: Int, val muted: Int, val grid: Int, val over: Int)

    /**
     * This month's running spending (solid, to today) over last month's (dashed, whole
     * month), with the month's budget as a faint line when there is one.
     */
    fun pace(t: Glanceable.Trend, budget: Long?, width: Int, height: Int, c: Colors): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val pad = height * 0.06f
        val max = listOfNotNull(t.lastMonth.maxOrNull(), t.monthToDate.maxOrNull(), budget?.takeIf { it > 0 }).maxOrNull()?.coerceAtLeast(1) ?: 1
        val days = maxOf(t.daysInMonth, t.lastMonth.size).coerceAtLeast(2)
        fun x(i: Int) = width * i / (days - 1).toFloat()
        fun y(v: Long) = height - pad - (height - 2 * pad) * (v.toFloat() / max)
        val stroke = (height / 45f).coerceAtLeast(3f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

        paint.color = c.grid; paint.strokeWidth = stroke / 2
        canvas.drawLine(0f, height - pad, width.toFloat(), height - pad, paint)
        if (budget != null && budget > 0) {
            paint.pathEffect = DashPathEffect(floatArrayOf(stroke * 2, stroke * 2), 0f)
            canvas.drawLine(0f, y(budget), width.toFloat(), y(budget), paint)
            paint.pathEffect = null
        }
        fun series(values: List<Long>, color: Int, dashed: Boolean) {
            if (values.isEmpty()) return
            val path = Path().apply {
                moveTo(0f, y(0))
                values.forEachIndexed { i, v -> lineTo(x(i), y(v)) }
            }
            paint.color = color; paint.strokeWidth = stroke
            paint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(stroke * 1.5f, stroke * 1.5f), 0f) else null
            canvas.drawPath(path, paint)
            paint.pathEffect = null
        }
        series(t.lastMonth, c.muted, dashed = true)
        val ahead = t.spentThisMonth > t.lastMonthByNow
        series(t.monthToDate, if (budget != null && budget > 0 && t.spentThisMonth > budget) c.over else c.line, dashed = false)
        t.monthToDate.lastOrNull()?.let { v ->
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (ahead) c.over else c.line }
            canvas.drawCircle(x(t.monthToDate.size - 1), y(v), stroke * 1.6f, dot)
        }
        return bmp
    }

    /**
     * Monday to Sunday as bars, today's in the accent colour and days ahead empty, with the
     * daily allowance (the weekly one over seven) as a dashed line.
     */
    fun week(t: Glanceable.Trend, width: Int, height: Int, c: Colors): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val perDay = t.weekAllowance?.let { it / 7 }
        val max = listOfNotNull(t.week.maxOrNull(), perDay).maxOrNull()?.coerceAtLeast(1) ?: 1
        val slot = width / 7f
        val barW = slot * 0.56f
        val radius = barW / 3
        val top = height * 0.04f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        t.week.forEachIndexed { i, v ->
            val left = i * slot + (slot - barW) / 2
            val h = if (i > t.weekdayToday) 0f else (height - top) * (v.toFloat() / max)
            fill.color = when {
                i > t.weekdayToday -> c.grid
                perDay != null && v > perDay -> c.over
                i == t.weekdayToday -> c.line
                else -> c.muted
            }
            // Days ahead (and empty days) show as a stub, so the week's shape reads.
            val drawn = maxOf(h, height * 0.03f)
            canvas.drawRoundRect(RectF(left, height - drawn, left + barW, height.toFloat()), radius, radius, fill)
        }
        if (perDay != null && perDay > 0) {
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = c.over; style = Paint.Style.STROKE; strokeWidth = (height / 60f).coerceAtLeast(2f)
                pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
            }
            val y = height - (height - top) * (perDay.toFloat() / max)
            canvas.drawLine(0f, y, width.toFloat(), y, line)
        }
        return bmp
    }
}
