package com.derinliksaati

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The wallpaper at rest with a frame around the clock: drag inside to move, a corner to resize
 * proportionally, an edge handle to change only the width or the height.
 */
class ClockEditView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private enum class Grab { NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    private val renderer = DepthRenderer(context).apply { loadIfChanged() }
    private val density = resources.displayMetrics.density
    private val box = RectF()
    private val start = RectF()
    private var grab = Grab.NONE
    private var downX = 0f
    private var downY = 0f
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2 * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 180, 84)
        strokeWidth = 2 * density
    }
    private var snappedX = false
    private var snappedY = false
    // Same shape as the phone screen, so the box means the same thing here and on the lock screen.
    private val aspect = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let {
        max(it.width(), it.height()).toFloat() / min(it.width(), it.height())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableW = MeasureSpec.getSize(widthMeasureSpec)
        val availableH = MeasureSpec.getSize(heightMeasureSpec)
        if (availableH >= availableW * aspect) {
            setMeasuredDimension(availableW, (availableW * aspect).toInt())
        } else {
            setMeasuredDimension((availableH / aspect).toInt(), availableH)
        }
    }

    override fun onDraw(canvas: Canvas) {
        renderer.loadIfChanged()
        renderer.render(canvas, width.toFloat(), height.toFloat(), 0f, 0f, showClock = true)
        if (grab == Grab.NONE) renderer.clockBox(width.toFloat(), height.toFloat(), box)
        if (grab == Grab.MOVE && snappedX) canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), guidePaint)
        if (grab == Grab.MOVE && snappedY) canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, guidePaint)
        canvas.drawRect(box, framePaint)
        val r = HANDLE_RADIUS * density
        for (x in listOf(box.left, box.centerX(), box.right)) for (y in listOf(box.top, box.centerY(), box.bottom)) {
            if (x != box.centerX() || y != box.centerY()) canvas.drawCircle(x, y, r, handlePaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                renderer.clockBox(width.toFloat(), height.toFloat(), box)
                start.set(box)
                downX = e.x
                downY = e.y
                grab = grabAt(e.x, e.y)
                if (grab == Grab.NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                applyDrag(e.x, e.y)
                Layers.saveClockBox(context, box.left / width, box.top / height, box.width() / width, box.height() / height)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                grab = Grab.NONE
                snappedX = false
                snappedY = false
                invalidate()
            }
        }
        return true
    }

    private fun grabAt(x: Float, y: Float): Grab {
        val reach = HANDLE_REACH * density
        fun near(px: Float, py: Float) = abs(x - px) <= reach && abs(y - py) <= reach
        return when {
            near(box.left, box.top) -> Grab.TOP_LEFT
            near(box.right, box.top) -> Grab.TOP_RIGHT
            near(box.left, box.bottom) -> Grab.BOTTOM_LEFT
            near(box.right, box.bottom) -> Grab.BOTTOM_RIGHT
            near(box.left, box.centerY()) -> Grab.LEFT
            near(box.right, box.centerY()) -> Grab.RIGHT
            near(box.centerX(), box.top) -> Grab.TOP
            near(box.centerX(), box.bottom) -> Grab.BOTTOM
            box.contains(x, y) -> Grab.MOVE
            else -> Grab.NONE
        }
    }

    private fun applyDrag(x: Float, y: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        val minW = w * MIN_SIZE
        val minH = h * MIN_SIZE
        box.set(start)
        when (grab) {
            Grab.MOVE -> {
                val dx = (x - downX).coerceIn(-start.left, w - start.right)
                val dy = (y - downY).coerceIn(-start.top, h - start.bottom)
                box.offset(dx, dy)
                snapToCenter(w, h)
            }
            Grab.LEFT -> box.left = x.coerceIn(0f, start.right - minW)
            Grab.RIGHT -> box.right = x.coerceIn(start.left + minW, w)
            Grab.TOP -> box.top = y.coerceIn(0f, start.bottom - minH)
            Grab.BOTTOM -> box.bottom = y.coerceIn(start.top + minH, h)
            Grab.TOP_LEFT -> corner(start.right, start.bottom, x, -1f, -1f)
            Grab.TOP_RIGHT -> corner(start.left, start.bottom, x, 1f, -1f)
            Grab.BOTTOM_LEFT -> corner(start.right, start.top, x, -1f, 1f)
            Grab.BOTTOM_RIGHT -> corner(start.left, start.top, x, 1f, 1f)
            Grab.NONE -> Unit
        }
    }

    /** Magnet: close to the screen's center line, the box jumps onto it, with a guide line and a small tick. */
    private fun snapToCenter(w: Float, h: Float) {
        val reach = SNAP_REACH * density
        val nearX = abs(box.centerX() - w / 2) < reach
        val nearY = abs(box.centerY() - h / 2) < reach
        if (nearX) box.offset(w / 2 - box.centerX(), 0f)
        if (nearY) box.offset(0f, h / 2 - box.centerY())
        if ((nearX && !snappedX) || (nearY && !snappedY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        snappedX = nearX
        snappedY = nearY
    }

    /** Proportional resize around the opposite corner ([ax], [ay]); [dx]/[dy] say which way the box grows from it. */
    private fun corner(ax: Float, ay: Float, x: Float, dx: Float, dy: Float) {
        val ratio = start.height() / start.width()
        val room = min(if (dx > 0) width - ax else ax, (if (dy > 0) height - ay else ay) / ratio)
        val minW = max(width * MIN_SIZE, height * MIN_SIZE / ratio).coerceAtMost(room)
        val bw = ((x - ax) * dx).coerceIn(minW, room)
        val bh = bw * ratio
        box.set(min(ax, ax + dx * bw), min(ay, ay + dy * bh), max(ax, ax + dx * bw), max(ay, ay + dy * bh))
    }

    private companion object {
        const val HANDLE_RADIUS = 7f  // dp
        const val HANDLE_REACH = 28f  // dp, large enough for a fingertip
        const val MIN_SIZE = 0.08f    // share of the screen
        const val SNAP_REACH = 12f    // dp
    }
}
