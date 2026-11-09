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
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

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
            c.drawText(context.getString(R.string.wallpaper_no_photo), w / 2, h * 0.62f, datePaint)
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
        // The person turns too, but less than the background, so the depth gap between them stays.
        viewMatrix(w, h, tiltX * maxDeg * SUBJECT_TURN, tiltY * maxDeg * PITCH_SHARE * SUBJECT_TURN)
        c.save()
        c.concat(view)
        c.scale(s, s, w / 2, h / 2)
        c.drawBitmap(subject, null, dst, layerPaint)
        c.restore()
        if (showClock && clockFront) drawClock(c, w, h)
    }

    /** Perspective turn of the background plane around the center of the w×h area (where the person stands). */
    private fun viewMatrix(w: Float, h: Float, yawDeg: Float, pitchDeg: Float) {
        camera.save()
        camera.setLocation(0f, 0f, -CAMERA_DISTANCE * w / 72f) // Camera works in inches at 72 px each
        camera.rotateY(yawDeg)
        camera.rotateX(pitchDeg)
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
        val date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), now).toString()
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
        /** The person turns this share of the background's angle. */
        const val SUBJECT_TURN = 0.5f
    }
}
