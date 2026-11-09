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

    /**
     * Sensor events (main thread) only update the tilt and ask for a frame. A separate render thread draws:
     * lockHardwareCanvas waits for the next free buffer, which paces it to the screen's refresh, and each
     * frame uses the newest tilt. So nothing queues up (no lag) and no refresh is skipped (full smoothness).
     * Drawing on the main thread did one or the other: per event it lagged, per Choreographer frame it
     * missed every other refresh on the lock screen.
     */
    private inner class DepthEngine : Engine(), SensorEventListener,
        SharedPreferences.OnSharedPreferenceChangeListener {

        private val prefs = Layers.prefs(this@DepthWallpaperService)
        private val sensors = getSystemService(SensorManager::class.java)
        private val keyguard = getSystemService(KeyguardManager::class.java)
        // Gyroscope-based orientation without the compass, so it does not jump near metal.
        private val rotationSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        private val renderer = DepthRenderer(this@DepthWallpaperService) // used on the render thread only
        private val tilt = TiltTracker() // updated on the main thread, read under [lock]
        private var visible = false

        // Shared with the render thread; all guarded by [lock].
        private val lock = Object()
        private var renderThread: Thread? = null
        private var frameWanted = false
        private var forceWanted = false
        private var reloadWanted = false

        // Render-thread only.
        private var drawnX = Float.NaN
        private var drawnY = Float.NaN

        // Minute change, or unlock (same wallpaper now shows as the home screen, maybe without the clock).
        private val redraw = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = requestFrame(force = true)
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onDestroy() {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            if (visible) stopListening()
            stopRendering()
            super.onDestroy()
        }

        // The sensor and the render thread run only while the wallpaper is on screen. Screen off also calls this with false.
        override fun onVisibilityChanged(visible: Boolean) {
            if (visible == this.visible) return
            this.visible = visible
            if (visible) {
                synchronized(lock) { tilt.reset() }
                rotationSensor?.let { sensors.registerListener(this, it, TiltTracker.SAMPLING_PERIOD_US) }
                registerReceiver(redraw, IntentFilter(Intent.ACTION_TIME_TICK).apply { addAction(Intent.ACTION_USER_PRESENT) })
                startRendering()
                requestFrame(force = true, reload = true)
            } else {
                stopListening()
                stopRendering()
            }
        }

        private fun stopListening() {
            sensors.unregisterListener(this)
            unregisterReceiver(redraw)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            requestFrame(force = true)
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) = requestFrame(force = true)

        // The surface must not be drawn on after this returns, so the render thread is stopped first.
        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopRendering()
            super.onSurfaceDestroyed(holder)
        }

        // New photo or setting from the app. While hidden, the next onVisibilityChanged(true) picks it up.
        override fun onSharedPreferenceChanged(p: SharedPreferences, key: String?) {
            if (visible) requestFrame(force = true, reload = true)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

        override fun onSensorChanged(event: SensorEvent) {
            synchronized(lock) {
                tilt.update(event)
                frameWanted = true
                lock.notifyAll()
            }
        }

        private fun requestFrame(force: Boolean = false, reload: Boolean = false) = synchronized(lock) {
            frameWanted = true
            if (force) forceWanted = true
            if (reload) reloadWanted = true
            lock.notifyAll()
        }

        private fun startRendering() {
            synchronized(lock) {
                if (renderThread != null) return
                // Assigned before start(), so the loop's "am I still the render thread" check sees itself.
                val t = Thread(::renderLoop, "DerinlikRender")
                renderThread = t
                t.start()
            }
        }

        private fun stopRendering() {
            val t = synchronized(lock) {
                val t = renderThread ?: return
                renderThread = null
                lock.notifyAll()
                t
            }
            t.join()
        }

        private fun renderLoop() {
            while (true) {
                var force: Boolean
                var reload: Boolean
                var x: Float
                var y: Float
                synchronized(lock) {
                    while (!frameWanted && renderThread === Thread.currentThread()) lock.wait()
                    if (renderThread !== Thread.currentThread()) return
                    force = forceWanted
                    reload = reloadWanted
                    x = tilt.x
                    y = tilt.y
                    frameWanted = false
                    forceWanted = false
                    reloadWanted = false
                }
                if (reload) renderer.loadIfChanged()
                draw(force, x, y)
            }
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

        /** Render thread. Redraw only when the view really moved, or when forced (minute change, unlock, new photo, new surface). */
        private fun draw(force: Boolean, x: Float, y: Float) {
            if (!force && abs(x - drawnX) < 0.002f && abs(y - drawnY) < 0.002f) return
            val holder = surfaceHolder
            val canvas = try { holder.lockHardwareCanvas() } catch (e: IllegalStateException) { null } ?: return
            try {
                val showClock = onLockScreen() || prefs.getBoolean(Layers.KEY_CLOCK_HOME, true)
                renderer.render(canvas, canvas.width.toFloat(), canvas.height.toFloat(), x, y, showClock)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            drawnX = x
            drawnY = y
        }
    }
}
