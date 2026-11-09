package com.derinliksaati

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Draws the wallpaper. Shared by the wallpaper service and the in-app preview, so both look the same.
 *
 * The person and the clock stay in place. The background is a picture plane through the person's
 * position; tilting turns that plane in perspective around the person, so you see the scene
 * from an angle instead of watching it slide. The person turns the same way, by a smaller angle.
 */
class DepthRenderer(private val context: Context) {

    private val prefs = Layers.prefs(context)
    private var bg: Bitmap? = null
    private var subject: Bitmap? = null
    private var loadedVersion = -1L

    private val dst = RectF()
    private val camera = Camera()
    private val view = Matrix()
    private val inverse = Matrix()
    private val corners = FloatArray(8)
    private var bulge: FloatArray? = null
    private val planeAt = Matrix()
    private val frontAt = Matrix()
    private val frontAtRest = Matrix()
    private val point = FloatArray(6)
    private val mesh = FloatArray((MESH_X + 1) * (MESH_Y + 1) * 2)
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val datePaint = Paint(timePaint).apply {
        typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    }

    /** Reloads the picture layers if the app saved new ones. */
    fun loadIfChanged() {
        val version = prefs.getLong(Layers.KEY_VERSION, 0L)
        if (version == loadedVersion) return
        loadedVersion = version
        bg = Layers.load(Layers.bgFile(context))
        subject = Layers.load(Layers.subjectFile(context))
        bulge = subject?.let { bulgeOf(it) }
    }

    /**
     * How far each mesh vertex sticks out toward the viewer, 0..1: 0 at the person's edge and outside,
     * rising to 1 at the thickest part, with a rounded (circle-like) profile so the person reads as a body.
     * Worked out on the mesh grid from the person's outline (chamfer distance to the nearest edge).
     */
    private fun bulgeOf(subject: Bitmap): FloatArray {
        val cols = MESH_X + 1
        val rows = MESH_Y + 1
        // The layer is a hardware bitmap (not readable); a scaled software copy is, and is tiny.
        val small = Bitmap.createScaledBitmap(subject.copy(Bitmap.Config.ARGB_8888, false), cols, rows, true)
        val px = IntArray(cols * rows).also { small.getPixels(it, 0, cols, 0, 0, cols, rows) }
        val cw = subject.width.toFloat() / MESH_X
        val ch = subject.height.toFloat() / MESH_Y
        val diag = sqrt(cw * cw + ch * ch)
        val far = Float.MAX_VALUE / 2
        val d = FloatArray(cols * rows) { if (Color.alpha(px[it]) > 128) far else 0f }
        fun relax(i: Int, x: Int, y: Int, dx: Int, dy: Int, step: Float) {
            val nx = x + dx
            val ny = y + dy
            val n = if (nx in 0 until cols && ny in 0 until rows) d[ny * cols + nx] else 0f
            if (n + step < d[i]) d[i] = n + step
        }
        for (y in 0 until rows) for (x in 0 until cols) {
            val i = y * cols + x
            if (d[i] == 0f) continue
            relax(i, x, y, -1, 0, cw); relax(i, x, y, 0, -1, ch); relax(i, x, y, -1, -1, diag); relax(i, x, y, 1, -1, diag)
        }
        for (y in rows - 1 downTo 0) for (x in cols - 1 downTo 0) {
            val i = y * cols + x
            if (d[i] == 0f) continue
            relax(i, x, y, 1, 0, cw); relax(i, x, y, 0, 1, ch); relax(i, x, y, 1, 1, diag); relax(i, x, y, -1, 1, diag)
        }
        val max = d.maxOrNull()?.takeIf { it > 0f } ?: return d
        for (i in d.indices) {
            val t = d[i] / max
            d[i] = sqrt(t * (2 - t))
        }
        return d
    }

    /** Draws into the w×h area at the canvas origin. [tiltX]/[tiltY] are -1..1. */
    fun render(c: Canvas, w: Float, h: Float, tiltX: Float, tiltY: Float, showClock: Boolean) {
        val bg = bg
        val subject = subject
        if (bg == null || subject == null) {
            c.drawColor(Color.rgb(27, 39, 87))
            if (showClock) drawClock(c, w, h)
            datePaint.color = Color.WHITE
            datePaint.clearShadowLayer()
            datePaint.textSize = w * 40f / 900f
            c.drawText(Layers.localized(context).getString(R.string.wallpaper_no_photo), w / 2, h * 0.62f, datePaint)
            return
        }

        val maxDeg = Layers.MAX_VIEW_DEG * prefs.getInt(Layers.KEY_DEPTH, Layers.DEFAULT_DEPTH) / 100f
        val clockFront = prefs.getBoolean(Layers.KEY_CLOCK_FRONT, false)
        // Layers were built VERTICAL_ROOM times the screen height; place them centered at that size.
        val u = h * Layers.VERTICAL_ROOM / bg.height
        dst.set(w / 2 - bg.width * u / 2, h / 2 - bg.height * u / 2, w / 2 + bg.width * u / 2, h / 2 + bg.height * u / 2)
        // One fixed scale for both layers, just big enough that the background still covers the screen
        // at the widest angle. Fixed, so the person stays lined up with their spot in the background.
        viewMatrix(w, h, maxDeg, maxDeg * PITCH_SHARE)
        val s = coverScale(w, h)

        c.drawColor(Color.BLACK)
        viewMatrix(w, h, tiltX * maxDeg, tiltY * maxDeg * PITCH_SHARE)
        c.save()
        c.concat(view)
        c.scale(s, s, w / 2, h / 2)
        c.drawBitmap(bg, null, dst, layerPaint)
        c.restore()
        if (showClock && !clockFront) drawClock(c, w, h)
        val turn = prefs.getInt(Layers.KEY_SUBJECT_TURN, Layers.DEFAULT_SUBJECT_TURN) / 100f
        drawSubject(c, w, h, s, subject, tiltX * maxDeg * turn, tiltY * maxDeg * PITCH_SHARE * turn)
        if (showClock && clockFront) drawClock(c, w, h)
    }

    /**
     * The person turns too, but less than the background, so the depth gap between them stays. On top of that
     * the person is a bent mesh, not a flat card: each vertex sits [bulge] × volume in front of the person's
     * plane, so the middle of the body turns more than its edges. The offset at rest is subtracted, so with
     * the phone held still the person looks exactly like the photo.
     */
    private fun drawSubject(c: Canvas, w: Float, h: Float, s: Float, subject: Bitmap, yawDeg: Float, pitchDeg: Float) {
        val bulge = bulge ?: return
        val front = Layers.MAX_VOLUME * prefs.getInt(Layers.KEY_VOLUME, Layers.DEFAULT_VOLUME) / 100f * w
        viewMatrix(w, h, yawDeg, pitchDeg)
        planeAt.set(view)
        viewMatrix(w, h, yawDeg, pitchDeg, front)
        frontAt.set(view)
        viewMatrix(w, h, 0f, 0f, front)
        frontAtRest.set(view)

        val cols = MESH_X + 1
        for (y in 0..MESH_Y) for (x in 0..MESH_X) {
            val i = y * cols + x
            // Vertex on the layer (dst), scaled by s around the center like the background.
            val px = w / 2 + (dst.left + dst.width() * x / MESH_X - w / 2) * s
            val py = h / 2 + (dst.top + dst.height() * y / MESH_Y - h / 2) * s
            for (k in 0 until 3) { point[2 * k] = px; point[2 * k + 1] = py }
            planeAt.mapPoints(point, 0, point, 0, 1)
            frontAt.mapPoints(point, 2, point, 2, 1)
            frontAtRest.mapPoints(point, 4, point, 4, 1)
            val b = bulge[i]
            mesh[2 * i] = point[0] + b * (point[2] - point[0] - (point[4] - px))
            mesh[2 * i + 1] = point[1] + b * (point[3] - point[1] - (point[5] - py))
        }
        c.drawBitmapMesh(subject, MESH_X, MESH_Y, mesh, 0, null, 0, layerPaint)
    }

    /**
     * Perspective turn around the center of the w×h area (where the person stands). [front] moves the
     * plane that many pixels toward the viewer, in the plane's own direction, before it is turned.
     */
    private fun viewMatrix(w: Float, h: Float, yawDeg: Float, pitchDeg: Float, front: Float = 0f) {
        camera.save()
        camera.setLocation(0f, 0f, -CAMERA_DISTANCE * w / 72f) // Camera works in inches at 72 px each
        camera.rotateY(yawDeg)
        camera.rotateX(pitchDeg)
        camera.translate(0f, 0f, -front) // Camera's negative z is toward the viewer
        camera.getMatrix(view)
        camera.restore()
        view.preTranslate(-w / 2, -h / 2)
        view.postTranslate(w / 2, h / 2)
    }

    /** Smallest scale at which the turned background (dst through [view]) still covers all four corners. */
    private fun coverScale(w: Float, h: Float): Float {
        view.invert(inverse)
        corners[0] = 0f; corners[1] = 0f; corners[2] = w; corners[3] = 0f
        corners[4] = 0f; corners[5] = h; corners[6] = w; corners[7] = h
        inverse.mapPoints(corners)
        var s = 0f
        for (i in 0 until 4) {
            s = max(s, abs(corners[2 * i] - w / 2) / (dst.width() / 2))
            s = max(s, abs(corners[2 * i + 1] - h / 2) / (dst.height() / 2))
        }
        return s
    }

    private fun drawClock(c: Canvas, w: Float, h: Float) {
        val alpha = prefs.getInt(Layers.KEY_CLOCK_ALPHA, Layers.DEFAULT_CLOCK_ALPHA) * 255 / 100
        if (alpha == 0) return
        val now = Date()
        val is24 = DateFormat.is24HourFormat(context)
        val time = DateFormat.format(if (is24) "HH:mm" else "h:mm", now).toString()
        val locale = Layers.locale(context)
        val date = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(now)
        val shadow = Color.argb(alpha * 89 / 255, 20, 10, 40)

        timePaint.color = Color.argb(alpha, 255, 255, 255)
        timePaint.textSize = w * 400f / 900f
        val maxWidth = w * 820f / 900f
        val tw = timePaint.measureText(time)
        if (tw > maxWidth) timePaint.textSize *= maxWidth / tw
        timePaint.setShadowLayer(w * 40f / 900f, 0f, 0f, shadow)
        c.drawText(time, w / 2, h * 470f / 1950f - (timePaint.ascent() + timePaint.descent()) / 2, timePaint)

        datePaint.color = Color.argb(alpha * 94 / 100, 255, 255, 255)
        datePaint.textSize = w * 50f / 900f
        datePaint.setShadowLayer(w * 20f / 900f, 0f, 0f, shadow)
        c.drawText(date, w / 2, h * 238f / 1950f - (datePaint.ascent() + datePaint.descent()) / 2, datePaint)
    }

    private companion object {
        /** Forward/back tilt turns the view this share of the left/right amount (the photo has less room vertically). */
        const val PITCH_SHARE = 0.6f
        /** Viewer distance in screen widths; smaller = stronger perspective. */
        const val CAMERA_DISTANCE = 1.2f
        /** Mesh cells across and down the person layer. */
        const val MESH_X = 32
        const val MESH_Y = 64
    }
}
