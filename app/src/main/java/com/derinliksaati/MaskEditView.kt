package com.derinliksaati

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.nio.ByteBuffer
import kotlin.math.hypot

/** Shows the photo with the selection tinted on top. One finger paints or erases, two fingers zoom and pan. */
class MaskEditView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    var erase = false
    /** Brush radius on screen, in dp, so it feels the same at any zoom. */
    var brushDp = 20f

    private var photo: Bitmap? = null
    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null

    private val view = Matrix()
    private val inverse = Matrix()
    private val values = FloatArray(9)
    private val point = FloatArray(2)
    private var fitScale = 1f

    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val overlayPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.argb(150, 255, 180, 84) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val paintMode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
    private val eraseMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    private val undoStack = ArrayDeque<ByteBuffer>()
    private var strokeStart: ByteBuffer? = null
    private var painting = false
    private var zooming = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var lastSpan = 1f

    /** [selection] is an editable ALPHA_8 bitmap the same size as [photo]; strokes change it directly. */
    fun setImage(photo: Bitmap, selection: Bitmap) {
        this.photo = photo
        mask = selection
        maskCanvas = Canvas(selection)
        fit()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fit()

    private fun fit() {
        val p = photo ?: return
        if (width == 0 || height == 0) return
        view.setRectToRect(
            RectF(0f, 0f, p.width.toFloat(), p.height.toFloat()),
            RectF(0f, 0f, width.toFloat(), height.toFloat()),
            Matrix.ScaleToFit.CENTER
        )
        fitScale = scale()
    }

    private fun scale(): Float {
        view.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    override fun onDraw(canvas: Canvas) {
        val p = photo ?: return
        canvas.drawBitmap(p, view, photoPaint)
        canvas.drawBitmap(mask ?: return, view, overlayPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                strokeStart = snapshot()
                painting = true
                toPhoto(e.x, e.y)
                lastX = point[0]
                lastY = point[1]
                strokeTo(point[0], point[1])
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                // The first finger was the start of a zoom, not a stroke: take its mark back.
                if (painting) strokeStart?.let { restore(it) }
                painting = false
                zooming = true
                startGesture(e)
            }
            MotionEvent.ACTION_MOVE -> when {
                zooming && e.pointerCount >= 2 -> zoomBy(e)
                painting -> {
                    for (i in 0 until e.historySize) {
                        toPhoto(e.getHistoricalX(i), e.getHistoricalY(i))
                        strokeTo(point[0], point[1])
                    }
                    toPhoto(e.x, e.y)
                    strokeTo(point[0], point[1])
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (painting) strokeStart?.let { pushUndo(it) }
                painting = false
                zooming = false // stays true until the last finger lifts, so a leftover finger never paints
                strokeStart = null
            }
        }
        invalidate()
        return true
    }

    private fun toPhoto(x: Float, y: Float) {
        view.invert(inverse)
        point[0] = x
        point[1] = y
        inverse.mapPoints(point)
    }

    private fun strokeTo(x: Float, y: Float) {
        val width = 2 * brushDp * resources.displayMetrics.density / scale()
        strokePaint.strokeWidth = width
        strokePaint.maskFilter = BlurMaskFilter(width * 0.08f, BlurMaskFilter.Blur.NORMAL)
        strokePaint.xfermode = if (erase) eraseMode else paintMode
        // A zero-length line still draws a dot with round caps, which is what a single tap needs.
        maskCanvas?.drawLine(lastX, lastY, x, y, strokePaint)
        lastX = x
        lastY = y
    }

    private fun startGesture(e: MotionEvent) {
        lastCx = (e.getX(0) + e.getX(1)) / 2
        lastCy = (e.getY(0) + e.getY(1)) / 2
        lastSpan = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
    }

    private fun zoomBy(e: MotionEvent) {
        val cx = (e.getX(0) + e.getX(1)) / 2
        val cy = (e.getY(0) + e.getY(1)) / 2
        val span = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
        val now = scale()
        val factor = (span / lastSpan).coerceIn(fitScale / now, MAX_ZOOM * fitScale / now)
        view.postTranslate(cx - lastCx, cy - lastCy)
        view.postScale(factor, factor, cx, cy)
        if (scale() <= fitScale * 1.001f) fit() // fully zoomed out: back to the whole photo, never lost off-screen
        lastCx = cx
        lastCy = cy
        lastSpan = span
    }

    private fun snapshot(): ByteBuffer? {
        val m = mask ?: return null
        val b = ByteBuffer.allocate(m.allocationByteCount)
        m.copyPixelsToBuffer(b)
        b.rewind()
        return b
    }

    private fun restore(b: ByteBuffer) {
        b.rewind()
        mask?.copyPixelsFromBuffer(b)
        b.rewind()
    }

    private fun pushUndo(b: ByteBuffer) {
        undoStack.addLast(b)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
    }

    fun undo() {
        restore(undoStack.removeLastOrNull() ?: return)
        invalidate()
    }

    fun clear() {
        snapshot()?.let { pushUndo(it) }
        mask?.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    fun isEmpty(): Boolean {
        val b = snapshot() ?: return true
        return b.array().all { it == 0.toByte() }
    }

    private companion object {
        const val MAX_ZOOM = 8f
        // Each step holds a full copy of the selection (about 5 MB).
        const val MAX_UNDO = 6
    }
}
