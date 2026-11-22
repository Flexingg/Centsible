package app.centsible.core.ui.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.centsible.core.designsystem.theme.CentsibleColors
import kotlin.math.max
import kotlin.math.min

/**
 * Centsible's charts drawn straight to bitmaps, for places Compose can't draw (home screen
 * widgets) and for their previews in the app. Transparent background (the card behind is
 * the widget's); the palette is the app's, light or dark. Sizes are pixels; [density] turns
 * the dp and sp below into pixels.
 */
class ChartArt(
    private val colors: CentsibleColors,
    private val density: Float,
    private val expressive: Boolean = false,
    private val customPalette: List<Color>? = null,
) {
    private fun dp(v: Float) = v * density
    private fun Color.argb(alpha: Float = 1f) = copy(alpha = alpha * this.alpha).toArgb()

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(10.5f)
        color = colors.textTertiary.argb()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun canvas(w: Int, h: Int): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp)
    }

    /** A smooth curve through [pts] (monotone, so it never overshoots between points). */
    private fun smooth(pts: List<Pair<Float, Float>>): Path {
        val path = Path()
        if (pts.isEmpty()) return path
        path.moveTo(pts[0].first, pts[0].second)
        if (pts.size == 1) return path
        val n = pts.size
        val slopes = FloatArray(n)
        val d = FloatArray(n - 1) { i -> (pts[i + 1].second - pts[i].second) / max(1e-3f, pts[i + 1].first - pts[i].first) }
        slopes[0] = d[0]
        slopes[n - 1] = d[n - 2]
        for (i in 1 until n - 1) slopes[i] = if (d[i - 1] * d[i] <= 0) 0f else (d[i - 1] + d[i]) / 2
        for (i in 0 until n - 1) {
            val (x0, y0) = pts[i]
            val (x1, y1) = pts[i + 1]
            val h = (x1 - x0) / 3
            path.cubicTo(x0 + h, y0 + slopes[i] * h, x1 - h, y1 - slopes[i + 1] * h, x1, y1)
        }
        return path
    }

    private fun stroke(color: Int, width: Float, dash: FloatArray? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
        if (dash != null) pathEffect = DashPathEffect(dash, 0f)
    }

    private fun Canvas.label(text: String, x: Float, y: Float, align: Paint.Align = Paint.Align.LEFT, color: Int? = null, bold: Boolean = false) {
        val p = Paint(labelPaint).apply {
            textAlign = align
            if (color != null) this.color = color
            if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        drawText(text, x, y, p)
    }

    /**
     * A running total as a smooth line with a soft gradient under it, over [total] points
     * (days in the month): [series] so far, [compare] (last period) dashed behind it, a
     * [reference] line (the budget) dotted with its [referenceLabel], [xLabels] under the axis.
     */
    fun area(
        width: Int, height: Int, series: List<Long>, total: Int, compare: List<Long> = emptyList(),
        reference: Long? = null, referenceLabel: String? = null, xLabels: List<Pair<Int, String>> = emptyList(),
        tone: Color = colors.accent, over: Boolean = false,
    ): Bitmap {
        val (bmp, c) = canvas(width, height)
        val bottomPad = if (xLabels.isEmpty()) dp(4f) else dp(16f)
        val top = dp(10f)
        val plotH = height - top - bottomPad
        val dataMax = (listOfNotNull(series.maxOrNull(), compare.maxOrNull()).maxOrNull() ?: 0L).coerceAtLeast(1)
        // A far-off budget would squash the lines flat: it only stretches the chart when it's near them.
        val showRef = reference != null && reference > 0 && reference <= dataMax * 1.6f
        val maxV = maxOf(dataMax.toFloat(), if (showRef) reference.toFloat() else 0f) * 1.12f
        val count = max(total, max(series.size, compare.size)).coerceAtLeast(2)
        val inset = dp(5f)
        fun x(i: Int) = inset + (width - 2 * inset) * i / (count - 1).toFloat()
        fun y(v: Long) = top + plotH * (1f - v / maxV)

        // Three faint gridlines.
        val grid = stroke(colors.chartGrid.argb(), dp(1f))
        for (k in 0..2) c.drawLine(0f, top + plotH * k / 2f, width.toFloat(), top + plotH * k / 2f, grid)

        if (compare.size > 1) {
            c.drawPath(smooth(compare.mapIndexed { i, v -> x(i) to y(v) }), stroke(colors.textTertiary.argb(0.7f), dp(1.6f), floatArrayOf(dp(4f), dp(4f))))
        }
        if (showRef) {
            val ry = y(reference)
            c.drawLine(0f, ry, width.toFloat(), ry, stroke(colors.textSecondary.argb(0.55f), dp(1.2f), floatArrayOf(dp(1.5f), dp(3.5f))))
            referenceLabel?.let { c.label(it, width - dp(2f), ry - dp(4f), Paint.Align.RIGHT, colors.textSecondary.argb()) }
        } else if (reference != null && reference > 0 && referenceLabel != null) {
            // Off the top: say so in the corner instead.
            c.label("$referenceLabel ↑", width - dp(2f), top + dp(2f), Paint.Align.RIGHT, colors.textSecondary.argb())
        }
        val lineColor = if (over) colors.negative else tone
        if (series.isNotEmpty()) {
            val pts = series.mapIndexed { i, v -> x(i) to y(v) }
            val line = smooth(pts)
            val fill = Path(line).apply {
                lineTo(pts.last().first, top + plotH)
                lineTo(pts.first().first, top + plotH)
                close()
            }
            c.drawPath(fill, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, top, 0f, top + plotH, lineColor.argb(0.32f), lineColor.argb(0f), Shader.TileMode.CLAMP)
            })
            c.drawPath(line, stroke(lineColor.argb(), dp(2.6f)))
            val (ex, ey) = pts.last()
            c.drawCircle(ex, ey, dp(8f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor.argb(0.18f) })
            c.drawCircle(ex, ey, dp(4f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor.argb() })
            c.drawCircle(ex, ey, dp(1.8f), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.card.argb() })
        }
        xLabels.forEach { (i, text) ->
            val align = when {
                i <= 0 -> Paint.Align.LEFT
                i >= count - 1 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            c.label(text, x(i.coerceIn(0, count - 1)), height - dp(3f), align)
        }
        return bmp
    }

    /**
     * Rounded bars with [labels] under them. [highlight] is drawn in the accent (today,
     * this month), others softer; bars over [reference] turn red; days not reached yet
     * ([future]) are stubs. [reference] is dashed across with its [referenceLabel].
     */
    fun bars(
        width: Int, height: Int, values: List<Long>, labels: List<String>, highlight: Int = -1, future: Set<Int> = emptySet(),
        reference: Long? = null, referenceLabel: String? = null, valueLabel: String? = null, tone: Color = colors.accent,
        /** Only the highlighted bar turns red over [reference] (months against their average). */
        overOnlyHighlight: Boolean = false,
    ): Bitmap {
        val (bmp, c) = canvas(width, height)
        if (values.isEmpty()) return bmp
        val bottomPad = dp(16f)
        val top = dp(if (valueLabel != null) 16f else 8f)
        val plotH = height - top - bottomPad
        val maxV = (listOfNotNull(values.maxOrNull(), reference).maxOrNull() ?: 0L).coerceAtLeast(1) * 1.05f
        val slot = width / values.size.toFloat()
        val barW = min(slot * if (expressive) 0.66f else 0.58f, dp(if (expressive) 34f else 30f))
        val r = if (expressive) min(barW / 2f, dp(12f)) else min(barW / 2.4f, dp(8f))
        values.forEachIndexed { i, v ->
            val cx = slot * i + slot / 2
            val isFuture = i in future
            val h = if (isFuture) 0f else plotH * (v / maxV)
            val drawn = max(h, dp(3f))
            val overRef = reference != null && reference > 0 && v > reference && !isFuture && (!overOnlyHighlight || i == highlight)
            val color = when {
                isFuture -> colors.track.argb()
                overRef -> colors.negative.argb(if (i == highlight) 1f else 0.75f)
                i == highlight -> tone.argb()
                else -> tone.argb(0.38f)
            }
            val rect = RectF(cx - barW / 2, top + plotH - drawn, cx + barW / 2, top + plotH)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            if (i == highlight && !isFuture) {
                p.shader = LinearGradient(0f, rect.top, 0f, rect.bottom, color, tone.argb(0.75f), Shader.TileMode.CLAMP).takeIf { !overRef }
            }
            c.drawPath(if (expressive) pillRounded(rect, r) else topRounded(rect, r), p)
            if (i == highlight && valueLabel != null && !isFuture) {
                c.label(valueLabel, cx, rect.top - dp(4f), Paint.Align.CENTER, colors.textPrimary.argb(), bold = true)
            }
            labels.getOrNull(i)?.let { c.label(it, cx, height - dp(3f), Paint.Align.CENTER, if (i == highlight) tone.argb() else null, bold = i == highlight) }
        }
        if (reference != null && reference > 0) {
            val ry = top + plotH * (1f - reference / maxV)
            c.drawLine(0f, ry, width.toFloat(), ry, stroke(colors.textSecondary.argb(0.6f), dp(1.2f), floatArrayOf(dp(4f), dp(3f))))
            referenceLabel?.let { c.label(it, width - dp(2f), ry - dp(4f), Paint.Align.RIGHT, colors.textSecondary.argb()) }
        }
        return bmp
    }

    /** Two bars a month (income in green, spending in the accent), months labelled underneath. */
    fun pairedBars(width: Int, height: Int, income: List<Long>, spending: List<Long>, labels: List<String>): Bitmap {
        val (bmp, c) = canvas(width, height)
        val n = max(income.size, spending.size)
        if (n == 0) return bmp
        val bottomPad = dp(16f)
        val top = dp(6f)
        val plotH = height - top - bottomPad
        val maxV = (listOf(income.maxOrNull() ?: 0, spending.maxOrNull() ?: 0).max()).coerceAtLeast(1) * 1.05f
        val slot = width / n.toFloat()
        val barW = min(slot * if (expressive) 0.34f else 0.3f, dp(if (expressive) 16f else 14f))
        val gap = dp(2.5f)
        val r = if (expressive) barW / 2f else barW / 2.2f
        fun bar(cx: Float, v: Long, color: Int) {
            val h = max(plotH * (v / maxV), dp(3f))
            val rect = RectF(cx - barW / 2, top + plotH - h, cx + barW / 2, top + plotH)
            c.drawPath(if (expressive) pillRounded(rect, r) else topRounded(rect, r), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        for (i in 0 until n) {
            val cx = slot * i + slot / 2
            val last = i == n - 1
            bar(cx - barW / 2 - gap / 2, income.getOrElse(i) { 0 }, colors.positive.argb(if (last) 1f else 0.6f))
            bar(cx + barW / 2 + gap / 2, spending.getOrElse(i) { 0 }, colors.accent.argb(if (last) 1f else 0.6f))
            labels.getOrNull(i)?.let { c.label(it, cx, height - dp(3f), Paint.Align.CENTER, if (last) colors.textPrimary.argb() else null, bold = last) }
        }
        c.drawLine(0f, top + plotH, width.toFloat(), top + plotH, stroke(colors.chartGrid.argb(), dp(1f)))
        return bmp
    }

    /** A bar with rounded top corners and a flat base, drawn once (no darker overlap where shapes meet). */
    private fun topRounded(rect: RectF, radius: Float): Path {
        val r = min(radius, rect.height())
        return Path().apply { addRoundRect(rect, floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW) }
    }

    /** Expressive pill bar with full top rounding and soft bottom rounding. */
    private fun pillRounded(rect: RectF, radius: Float): Path {
        val topR = min(radius, rect.height() / 2f)
        val botR = min(radius * 0.45f, rect.height() / 2f)
        return Path().apply { addRoundRect(rect, floatArrayOf(topR, topR, topR, topR, botR, botR, botR, botR), Path.Direction.CW) }
    }

    /** A ring of [slices] (value, color) with small gaps, [centre] text in the middle. */
    fun donut(size: Int, slices: List<Pair<Long, Color>>, centre: String, caption: String): Bitmap {
        val (bmp, c) = canvas(size, size)
        val total = slices.sumOf { it.first }.coerceAtLeast(1)
        val thickness = size * if (expressive) 0.155f else 0.13f
        val rect = RectF(thickness / 2 + dp(2f), thickness / 2 + dp(2f), size - thickness / 2 - dp(2f), size - thickness / 2 - dp(2f))
        c.drawArc(rect, 0f, 360f, false, stroke(colors.track.argb(), thickness))
        val gap = if (slices.size > 1) (if (expressive) 7f else 4f) else 0f
        var start = -90f
        slices.forEach { (v, color) ->
            val sweep = 360f * v / total
            if (sweep - gap > 0.5f) {
                val p = stroke(color.argb(), thickness).apply {
                    strokeCap = if (expressive && sweep - gap > 14f) Paint.Cap.ROUND else Paint.Cap.BUTT
                }
                c.drawArc(rect, start + gap / 2, sweep - gap, false, p)
            }
            start += sweep
        }
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = colors.textPrimary.argb()
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = min(size * 0.15f, dp(20f))
            // Long amounts shrink to fit inside the ring.
            while (measureText(centre) > size - thickness * 2.6f && textSize > dp(9f)) textSize -= dp(1f)
        }
        c.drawText(centre, size / 2f, size / 2f + big.textSize * 0.25f, big)
        c.label(caption, size / 2f, size / 2f + big.textSize * 0.25f + dp(13f), Paint.Align.CENTER)
        return bmp
    }

    /** One category's budget as a ring: [share] used (0..1+), red past full. */
    fun ring(size: Int, share: Float, centre: String, caption: String, tone: Color = colors.accent): Bitmap {
        val over = share > 1f
        val (bmp, c) = canvas(size, size)
        val thickness = size * if (expressive) 0.14f else 0.11f
        val rect = RectF(thickness / 2 + dp(2f), thickness / 2 + dp(2f), size - thickness / 2 - dp(2f), size - thickness / 2 - dp(2f))
        c.drawArc(rect, 0f, 360f, false, stroke(colors.track.argb(), thickness))
        val ringTone = if (over) colors.negative else tone
        val sweep = 360f * share.coerceIn(0f, 1f)
        if (sweep > 0.5f) c.drawArc(rect, -90f, sweep, false, stroke(ringTone.argb(), thickness))
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = (if (over) colors.negative else colors.textPrimary).argb()
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            textSize = min(size * 0.16f, dp(20f))
            while (measureText(centre) > size - thickness * 2.8f && textSize > dp(9f)) textSize -= dp(1f)
        }
        c.drawText(centre, size / 2f, size / 2f + big.textSize * 0.25f, big)
        c.label(caption, size / 2f, size / 2f + big.textSize * 0.25f + dp(13f), Paint.Align.CENTER)
        return bmp
    }

    /** Resolves a user-picked [app.centsible.core.domain.ChartAccent] to a colour, or null for Default. */
    fun accentColor(accent: app.centsible.core.domain.ChartAccent): Color? = when (accent) {
        app.centsible.core.domain.ChartAccent.Default -> null
        app.centsible.core.domain.ChartAccent.Brand -> colors.accent
        app.centsible.core.domain.ChartAccent.Green -> colors.positive
        app.centsible.core.domain.ChartAccent.Coral -> colors.negative
        app.centsible.core.domain.ChartAccent.Ocean -> colors.series1
        app.centsible.core.domain.ChartAccent.Plum -> Color(0xFF8E6BD8)
        app.centsible.core.domain.ChartAccent.Amber -> colors.warning
    }

    /** Category colours for the donut and its legend: Material You palette in expressive mode, or the app's brand colours. */
    val palette: List<Color>
        get() = customPalette ?: listOf(colors.accent, colors.series1, colors.positive, colors.warning, Color(0xFF8E6BD8), colors.textTertiary)
}
