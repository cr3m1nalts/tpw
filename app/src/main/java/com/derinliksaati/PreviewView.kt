package com.derinliksaati

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Path
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/** Live preview of the wallpaper inside the app, drawn by the same renderer and driven by the same sensor. */
class PreviewView(context: Context, attrs: AttributeSet?) : View(context, attrs), SensorEventListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs = Layers.prefs(context)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val rotationSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val renderer = DepthRenderer(context)
    private val tilt = TiltTracker()
    private val clip = Path()
    private var listening = false
    // Same shape as the phone screen, so the preview matches the wallpaper.
    private val aspect = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let {
        max(it.width(), it.height()).toFloat() / min(it.width(), it.height())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * aspect).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clip.reset()
        val r = w * 0.09f
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), r, r, Path.Direction.CW)
    }

    // Sensor only while the preview is actually on screen (also off when the app goes to the background).
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible == listening) return
        listening = isVisible
        if (isVisible) {
            renderer.loadIfChanged()
            tilt.reset()
            rotationSensor?.let { sensors.registerListener(this, it, TiltTracker.SAMPLING_PERIOD_US) }
            prefs.registerOnSharedPreferenceChangeListener(this)
            invalidate()
        } else {
            sensors.unregisterListener(this)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
        }
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
        renderer.loadIfChanged()
        invalidate()
    }

    override fun onSensorChanged(event: SensorEvent) {
        tilt.update(event)
        invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clip)
        renderer.render(canvas, width.toFloat(), height.toFloat(), tilt.x, tilt.y, showClock = true)
        canvas.restore()
        // Keep the clock current even when the phone lies still.
        postInvalidateDelayed(60_000 - System.currentTimeMillis() % 60_000)
    }
}
