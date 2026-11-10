package com.derinliksaati

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The wallpaper at rest: two fingers zoom the photo, one finger moves it within the zoomed-in margin. */
class FrameEditView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val renderer = DepthRenderer(context).apply { loadIfChanged() }
    private val prefs = Layers.prefs(context)
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 1f
    private var zooming = false
    // Same shape as the phone screen, so the framing means the same thing here and on the lock screen.
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
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                zooming = true
                lastSpan = span(e)
            }
            MotionEvent.ACTION_MOVE -> {
                if (zooming && e.pointerCount >= 2) {
                    val s = span(e)
                    val zoom = (prefs.getFloat(Layers.KEY_FRAME_ZOOM, 1f) * s / lastSpan).coerceIn(1f, Layers.MAX_FRAME_ZOOM)
                    prefs.edit().putFloat(Layers.KEY_FRAME_ZOOM, zoom).apply()
                    lastSpan = s
                } else if (!zooming) {
                    // Pan is stored as a share of the margin zooming created, so the photo always covers the screen.
                    val mx = renderer.frameMarginX
                    val my = renderer.frameMarginY
                    val edit = prefs.edit()
                    if (mx > 0f) edit.putFloat(Layers.KEY_FRAME_X, (prefs.getFloat(Layers.KEY_FRAME_X, 0f) + (e.x - lastX) / mx).coerceIn(-1f, 1f))
                    if (my > 0f) edit.putFloat(Layers.KEY_FRAME_Y, (prefs.getFloat(Layers.KEY_FRAME_Y, 0f) + (e.y - lastY) / my).coerceIn(-1f, 1f))
                    edit.apply()
                }
                lastX = e.x
                lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> zooming = false // stays until the last finger lifts, so a leftover finger does not pan
        }
        return true
    }

    private fun span(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
}
