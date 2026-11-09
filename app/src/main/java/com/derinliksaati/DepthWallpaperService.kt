package com.derinliksaati

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.abs

/** Live wallpaper: the person and the clock stay put; tilting shows the background from an angle. */
class DepthWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = DepthEngine()

    private inner class DepthEngine : Engine(), SensorEventListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val prefs = Layers.prefs(this@DepthWallpaperService)
        private val sensors = getSystemService(SensorManager::class.java)
        private val keyguard = getSystemService(KeyguardManager::class.java)
        // Gyroscope-based orientation without the compass, so it does not jump near metal.
        private val rotationSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        private val renderer = DepthRenderer(this@DepthWallpaperService)
        private val tilt = TiltTracker()
        private var visible = false
        private var drawnX = Float.NaN
        private var drawnY = Float.NaN

        // Minute change, or unlock (same wallpaper now shows as the home screen, maybe without the clock).
        private val redraw = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = draw(force = true)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            if (visible) stopListening()
            super.onDestroy()
        }

        // The sensor runs only while the wallpaper is on screen. Screen off also calls this with false.
        override fun onVisibilityChanged(visible: Boolean) {
            if (visible == this.visible) return
            this.visible = visible
            if (visible) {
                renderer.loadIfChanged()
                tilt.reset()
                rotationSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                registerReceiver(redraw, IntentFilter(Intent.ACTION_TIME_TICK).apply { addAction(Intent.ACTION_USER_PRESENT) })
                draw(force = true)
            } else {
                stopListening()
            }
        }

        private fun stopListening() {
            sensors.unregisterListener(this)
            unregisterReceiver(redraw)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw(force = true)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) = draw(force = true)

        // New photo or setting from the app. While hidden, the next onVisibilityChanged(true) picks it up.
        override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
            if (!visible) return
            renderer.loadIfChanged()
            draw(force = true)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

        override fun onSensorChanged(event: SensorEvent) {
            tilt.update(event)
            draw(force = false)
        }

        private fun onLockScreen(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                when (wallpaperFlags) {
                    WallpaperManager.FLAG_LOCK -> return true
                    WallpaperManager.FLAG_SYSTEM -> return false
                }
            }
            // Same wallpaper on both screens: decide by whether the phone is locked right now.
            return keyguard.isKeyguardLocked
        }

        /** Redraw only when the view really moved, or when forced (minute change, unlock, new photo, new surface). */
        private fun draw(force: Boolean) {
            if (!force && abs(tilt.x - drawnX) < 0.002f && abs(tilt.y - drawnY) < 0.002f) return
            val holder = surfaceHolder
            val canvas = try { holder.lockHardwareCanvas() } catch (e: IllegalStateException) { null } ?: return
            try {
                val showClock = onLockScreen() || prefs.getBoolean(Layers.KEY_CLOCK_HOME, true)
                renderer.render(canvas, canvas.width.toFloat(), canvas.height.toFloat(), tilt.x, tilt.y, showClock)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            drawnX = tilt.x
            drawnY = tilt.y
        }
    }
}
