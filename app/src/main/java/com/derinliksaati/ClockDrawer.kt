package com.derinliksaati

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.format.DateFormat
import androidx.annotation.RequiresApi
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the clock in its user-set box, in one of three styles. Text is laid out once in fixed "design units"
 * and the canvas is scaled to the box, so the box can be any shape (non-proportional resize just stretches it).
 */
class ClockDrawer(private val context: Context) {

    private val prefs = Layers.prefs(context)
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val regular = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.FILTER_BITMAP_FLAG)
    private val digit = Rect()

    private var key = ""
    private var lines: List<String> = emptyList()
    private var date = ""
    private var vertical = false
    private var tall = false
    private val tallPath = Path()
    private val oval = RectF()
    private val thicken = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = TALL_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private var naturalW = 1f
    private var naturalH = 1f
    private var digitH = 1f

    private val box = RectF()
    private val local = Matrix()
    private val planeInverse = Matrix()
    private var bgMove: Matrix? = null
    private var clockPlane: Matrix? = null
    private val source = RectF()

    private var bgSource: Bitmap? = null
    private var bgShader: BitmapShader? = null
    private var blurSource: Bitmap? = null
    private var blurShader: BitmapShader? = null
    private var sharpShader: BitmapShader? = null // text shape, rebuilt when the text changes
    private var softShader: BitmapShader? = null  // blurred text shape: its slope gives the glass edge
    private val liquid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LiquidEffect() else null

    /** Rebuilds the text and its layout only when the minute, the language or the layout changes (date formatting is slow). */
    private fun refreshText() {
        val locale = Layers.locale(context)
        val is24 = DateFormat.is24HourFormat(context)
        val isVertical = prefs.getBoolean(Layers.KEY_CLOCK_VERTICAL, false)
        val isTall = prefs.getInt(Layers.KEY_CLOCK_FONT, Layers.FONT_BOLD) == Layers.FONT_TALL
        val k = "${System.currentTimeMillis() / 60_000}|$locale|$is24|$isVertical|$isTall"
        if (k == key) return
        key = k
        vertical = isVertical
        tall = isTall
        val now = Date()
        lines = if (isVertical) {
            listOf(DateFormat.format(if (is24) "HH" else "hh", now).toString(), DateFormat.format("mm", now).toString())
        } else {
            listOf(DateFormat.format(if (is24) "HH:mm" else "h:mm", now).toString())
        }
        date = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(now)
        // Measured on zeros, not the real digits, so the clock does not change width from minute to minute.
        val widest = if (isVertical) "00" else "00:00"
        if (isTall) {
            digitH = TALL_H + TALL_STROKE
            naturalW = tallWidth(widest)
        } else {
            paint.typeface = bold
            paint.textSize = TIME_SIZE
            paint.getTextBounds("0", 0, 1, digit)
            digitH = digit.height().toFloat()
            naturalW = paint.measureText(widest)
        }
        naturalH = DATE_H + GAP + lines.size * digitH + (lines.size - 1) * LINE_GAP
        if (isTall) buildTallPath()
        sharpShader = null
        softShader = null
    }

    /** A glyph's width before thickening. The colon is two round dots. */
    private fun tallAdvance(line: String, i: Int) = if (line[i] == ':') 2 * TALL_DOT - TALL_STROKE else TALL_W

    /**
     * The centre line of a tall digit in a TALL_W × TALL_H box. The digits are drawn here rather than taken
     * from a font so that the curves stay round and only the straight parts get long.
     */
    private fun tallGlyph(ch: Char, out: Path) {
        val w = TALL_W
        val h = TALL_H
        val r = w / 2
        val mid = h * 0.47f
        out.rewind()
        when (ch) {
            '0' -> out.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW)
            '1' -> {
                out.moveTo(w * 0.18f, r * 0.9f)
                out.lineTo(w * 0.62f, 0f)
                out.lineTo(w * 0.62f, h)
            }
            '2' -> {
                // The top arc runs on until it points straight at the bottom left corner.
                var a = 0.0
                repeat(6) { a = atan2(r + r * cos(a), h - r - r * sin(a)) }
                oval.set(0f, 0f, w, w)
                out.arcTo(oval, 180f, 180f + Math.toDegrees(a).toFloat(), true)
                out.lineTo(0f, h)
                out.lineTo(w, h)
            }
            '3' -> {
                oval.set(0f, 0f, w, w)
                out.arcTo(oval, 200f, 160f, true)
                out.lineTo(w, mid - r)
                oval.set(0f, mid - w, w, mid)
                out.arcTo(oval, 0f, 90f, false)
                out.lineTo(w * 0.4f, mid)
                out.moveTo(r, mid)
                oval.set(0f, mid, w, mid + w)
                out.arcTo(oval, 270f, 90f, false)
                out.lineTo(w, h - r)
                oval.set(0f, h - w, w, h)
                out.arcTo(oval, 0f, 160f, false)
            }
            '4' -> {
                out.moveTo(w * 0.7f, 0f)
                out.lineTo(0f, h * 0.7f)
                out.lineTo(w, h * 0.7f)
                out.moveTo(w * 0.7f, 0f)
                out.lineTo(w * 0.7f, h)
            }
            '5' -> {
                val bowl = h * 0.4f
                out.moveTo(w * 0.95f, 0f)
                out.lineTo(w * 0.1f, 0f)
                out.lineTo(w * 0.03f, bowl)
                oval.set(0f, bowl - w * 0.329f, w, bowl + w * 0.671f)
                out.arcTo(oval, 200f, 160f, false)
                out.lineTo(w, h - r)
                oval.set(0f, h - w, w, h)
                out.arcTo(oval, 0f, 160f, false)
            }
            '6', '9' -> {
                val bowl = h * 0.42f
                oval.set(0f, 0f, w, w)
                out.arcTo(oval, -30f, -150f, true)
                out.lineTo(0f, h - r)
                oval.set(0f, h - w, w, h)
                out.arcTo(oval, 180f, -180f, false)
                out.lineTo(w, bowl + r)
                oval.set(0f, bowl, w, bowl + w)
                out.arcTo(oval, 0f, -180f, false)
                if (ch == '9') {
                    local.setRotate(180f, r, h / 2)
                    out.transform(local)
                }
            }
            '7' -> {
                out.moveTo(0f, 0f)
                out.lineTo(w, 0f)
                out.lineTo(w * 0.3f, h)
            }
            '8' -> {
                out.addRoundRect(0f, 0f, w, mid, r, r, Path.Direction.CW)
                out.addRoundRect(0f, mid, w, h, r, r, Path.Direction.CW)
            }
        }
    }

    private fun tallWidth(line: String): Float {
        var w = TALL_STROKE + (line.length - 1) * TALL_SPACING
        for (i in line.indices) w += tallAdvance(line, i)
        return w
    }

    /** The tall digits are centre lines thickened with a round stroke, so every stroke is equally thick. */
    private fun buildTallPath() {
        val thinLines = Path()
        val glyph = Path()
        val dots = Path()
        var top = DATE_H + GAP + TALL_STROKE / 2
        for (line in lines) {
            var x = (naturalW - tallWidth(line)) / 2 + TALL_STROKE / 2
            for (i in line.indices) {
                val advance = tallAdvance(line, i)
                if (line[i] == ':') {
                    dots.addCircle(x + advance / 2, top + TALL_H * 0.3f, TALL_DOT, Path.Direction.CW)
                    dots.addCircle(x + advance / 2, top + TALL_H * 0.7f, TALL_DOT, Path.Direction.CW)
                } else {
                    tallGlyph(line[i], glyph)
                    thinLines.addPath(glyph, x, top)
                }
                x += advance + TALL_SPACING
            }
            top += digitH + LINE_GAP
        }
        thicken.getFillPath(thinLines, tallPath)
        // Merges the overlapping pieces into one outline, so the frosted style's edge line stays on the outside.
        tallPath.op(dots, Path.Op.UNION)
    }

    /** The clock's box on a w×h screen: the user's, or the default for the current layout. */
    fun box(w: Float, h: Float, out: RectF) {
        refreshText()
        val l = prefs.getFloat(Layers.KEY_CLOCK_L, Float.NaN)
        if (!l.isNaN()) {
            val t = prefs.getFloat(Layers.KEY_CLOCK_T, 0f)
            out.set(l * w, t * h, (l + prefs.getFloat(Layers.KEY_CLOCK_W, 0.5f)) * w, (t + prefs.getFloat(Layers.KEY_CLOCK_H, 0.2f)) * h)
            return
        }
        var bw = w * if (vertical) 0.55f else 0.91f
        var bh = bw * naturalH / naturalW
        if (bh > h * 0.7f) {
            bw *= h * 0.7f / bh
            bh = h * 0.7f
        }
        val top = h * 0.10f
        out.set((w - bw) / 2, top, (w + bw) / 2, top + bh)
    }

    /**
     * [bgRect] is where [bg] and [blur] sit at rest, [bgMove] how the background is moved this frame, and
     * [clockPlane] the transform the canvas already has for the clock (null if none). Without a background
     * the glass styles fall back to classic.
     */
    fun draw(c: Canvas, w: Float, h: Float, bg: Bitmap?, blur: Bitmap?, bgRect: RectF?, bgMove: Matrix?, clockPlane: Matrix?, tiltX: Float, tiltY: Float) {
        this.bgMove = bgMove
        this.clockPlane = clockPlane
        val alpha = prefs.getInt(Layers.KEY_CLOCK_ALPHA, Layers.DEFAULT_CLOCK_ALPHA) * 255 / 100
        if (alpha == 0) return
        box(w, h, box)
        val style = prefs.getInt(Layers.KEY_CLOCK_STYLE, Layers.STYLE_CLASSIC)
        c.save()
        c.translate(box.left, box.top)
        c.scale(box.width() / naturalW, box.height() / naturalH)
        when {
            style == Layers.STYLE_LIQUID && bg != null && bgRect != null && liquid != null ->
                drawLiquid(c, bg, bgRect, alpha, tiltX, tiltY)
            style != Layers.STYLE_CLASSIC && blur != null && bgRect != null -> drawFrosted(c, blur, bgRect, alpha)
            else -> drawClassic(c, alpha)
        }
        c.restore()
    }

    private fun drawText(c: Canvas, p: Paint) {
        p.typeface = regular
        p.textSize = DATE_SIZE
        val dw = p.measureText(date)
        if (dw > naturalW) p.textSize = DATE_SIZE * naturalW / dw
        c.drawText(date, naturalW / 2, DATE_H * 0.8f, p)
        if (tall) {
            c.drawPath(tallPath, p)
            return
        }
        p.typeface = bold
        p.textSize = TIME_SIZE
        var y = DATE_H + GAP + digitH
        for (line in lines) {
            c.drawText(line, naturalW / 2, y, p)
            y += digitH + LINE_GAP
        }
    }

    private fun drawClassic(c: Canvas, alpha: Int) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(alpha, 255, 255, 255)
        paint.setShadowLayer(32f, 0f, 0f, Color.argb(alpha * 89 / 255, 20, 10, 40))
        drawText(c, paint)
        paint.clearShadowLayer()
    }

    /** Maps a picture placed at [rect] on screen into the design units this clock draws in. */
    private fun place(shader: Shader, bitmap: Bitmap, rect: RectF) {
        source.set(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        local.setRectToRect(source, rect, Matrix.ScaleToFit.FILL)
        bgMove?.let { local.postConcat(it) }
        clockPlane?.let { if (it.invert(planeInverse)) local.postConcat(planeInverse) }
        local.postTranslate(-box.left, -box.top)
        local.postScale(naturalW / box.width(), naturalH / box.height())
        shader.setLocalMatrix(local)
    }

    /** The digits show a blurred copy of the background with a white tint and a thin bright outline. */
    private fun drawFrosted(c: Canvas, blur: Bitmap, rect: RectF, alpha: Int) {
        if (blurSource !== blur) {
            blurSource = blur
            blurShader = BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val shader = blurShader ?: return
        place(shader, blur, rect)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(alpha, 255, 255, 255)
        paint.shader = shader
        drawText(c, paint)
        paint.shader = null
        paint.color = Color.argb(alpha * 70 / 255, 255, 255, 255)
        drawText(c, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(alpha * 140 / 255, 255, 255, 255)
        drawText(c, paint)
        paint.style = Paint.Style.FILL
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawLiquid(c: Canvas, bg: Bitmap, rect: RectF, alpha: Int, tiltX: Float, tiltY: Float) {
        val effect = liquid ?: return
        if (sharpShader == null) {
            sharpShader = BitmapShader(textShape(0f), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            softShader = BitmapShader(textShape(SOFT_RADIUS), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        if (bgSource !== bg) {
            bgSource = bg
            bgShader = BitmapShader(bg, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val background = bgShader ?: return
        place(background, bg, rect)
        // The highlight slides around the digit edges as the phone tilts.
        var lx = tiltX - 0.35f
        var ly = tiltY - 0.8f
        val len = sqrt(lx * lx + ly * ly).coerceAtLeast(0.001f)
        lx /= len
        ly /= len
        fill.shader = effect.bind(background, sharpShader ?: return, softShader ?: return, lx, ly, alpha / 255f)
        c.drawRect(0f, 0f, naturalW, naturalH, fill)
        fill.shader = null
    }

    private fun textShape(blurRadius: Float): Bitmap {
        val b = Bitmap.createBitmap(naturalW.toInt().coerceAtLeast(1), naturalH.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = Color.BLACK
            if (blurRadius > 0f) maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        }
        drawText(Canvas(b), p)
        return b
    }

    /** Bends the background at the digit edges like thick glass and adds a highlight. Needs Android 13's shaders. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private class LiquidEffect {
        private val shader = RuntimeShader(AGSL)

        fun bind(bg: BitmapShader, sharp: BitmapShader, soft: BitmapShader, lx: Float, ly: Float, alpha: Float): RuntimeShader {
            for (s in listOf(bg, sharp, soft)) s.filterMode = BitmapShader.FILTER_MODE_LINEAR
            shader.setInputShader("bg", bg)
            shader.setInputShader("sharp", sharp)
            shader.setInputShader("soft", soft)
            shader.setFloatUniform("light", lx, ly)
            shader.setFloatUniform("alpha", alpha)
            return shader
        }

        private companion object {
            const val AGSL = """
                uniform shader bg;
                uniform shader sharp;
                uniform shader soft;
                uniform float2 light;
                uniform float alpha;

                half4 main(float2 p) {
                    float m = sharp.eval(p).a;
                    if (m <= 0.004) { return half4(0.0); }
                    float e = 7.0;
                    float2 n = float2(soft.eval(p + float2(e, 0.0)).a - soft.eval(p - float2(e, 0.0)).a,
                                      soft.eval(p + float2(0.0, e)).a - soft.eval(p - float2(0.0, e)).a);
                    float inner = soft.eval(p).a;
                    half4 c = bg.eval(p + n * 120.0);
                    float len = length(n);
                    float spec = 0.0;
                    if (len > 0.0001) {
                        spec = pow(max(dot(n / len, light), 0.0), 8.0) * min(len * 6.0, 1.0);
                    }
                    float rim = (1.0 - inner) * 0.35;
                    half3 col = c.rgb * 1.08 + half3(0.06 + spec * 0.9 + rim);
                    return half4(col, 1.0) * half(m * alpha);
                }
            """
        }
    }

    private companion object {
        // Design units; the canvas scale maps them to the box.
        const val TIME_SIZE = 400f
        const val DATE_SIZE = 50f
        const val DATE_H = 62f
        const val GAP = 44f
        const val LINE_GAP = 56f
        const val SOFT_RADIUS = 22f
        // Tall digits: centre-line box, stroke thickness, gap between glyph boxes and the colon's dot radius.
        const val TALL_W = 178f
        const val TALL_H = 1140f
        const val TALL_STROKE = 62f
        const val TALL_SPACING = 104f
        const val TALL_DOT = 38f
    }
}
