package com.derinliksaati

import android.hardware.SensorEvent
import android.hardware.SensorManager
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Turns TYPE_GAME_ROTATION_VECTOR events into a smoothed tilt, -1..1 on each axis, measured from a
 * resting pose that slowly follows the phone (so holding it at a new angle settles back to straight-on).
 */
class TiltTracker {
    var x = 0f
        private set
    var y = 0f
        private set

    // Resting pose as a quaternion (w, x, y, z). NaN until the first event after reset().
    private val base = floatArrayOf(Float.NaN, 0f, 0f, 0f)
    private val q = FloatArray(4)
    private var lastNanos = 0L

    fun reset() {
        base[0] = Float.NaN
        lastNanos = 0L
    }

    fun update(event: SensorEvent) {
        SensorManager.getQuaternionFromVector(q, event.values)
        if (base[0].isNaN()) q.copyInto(base)
        // Smoothing by elapsed time, not per event: Samsung sends fewer events on the lock screen,
        // and per-event smoothing made the view lag behind there.
        val dt = if (lastNanos == 0L) 0f else ((event.timestamp - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = event.timestamp
        nlerp(base, q, 1 - exp(-dt / RECENTER_SECONDS))
        val k = 1 - exp(-dt / SMOOTHING_SECONDS)
        // Rotation since the resting pose, in the phone's own axes: r = conj(base) * q.
        // For small angles, turning around the screen's vertical axis is about 2*r.y, around the horizontal 2*r.x.
        val (bw, bx, by, bz) = base
        val (w, qx, qy, qz) = q
        val rw = bw * w + bx * qx + by * qy + bz * qz
        val sign = if (rw < 0) -1f else 1f
        val rx = sign * (bw * qx - bx * w - by * qz + bz * qy)
        val ry = sign * (bw * qy + bx * qz - by * w - bz * qx)
        x += ((2 * ry / MAX_TILT_RAD).coerceIn(-1f, 1f) - x) * k
        y += ((2 * rx / MAX_TILT_RAD).coerceIn(-1f, 1f) - y) * k
    }

    companion object {
        /**
         * Ask for 120 events/s explicitly. SENSOR_DELAY_GAME means 50/s, which Samsung's lock screen gives
         * literally (the home screen gives ~125 anyway), so the lock screen only redrew 50 times a second.
         */
        const val SAMPLING_PERIOD_US = 8_333

        /** Tilting this far from the resting pose gives the full viewing angle. */
        private val MAX_TILT_RAD = Math.toRadians(25.0).toFloat()
        // Time constants equal to the old per-event values at the home screen's measured ~125 events/s,
        // so the feel there is unchanged.
        /** The resting pose follows the phone with this time constant (the slow "back to straight-on"). */
        private const val RECENTER_SECONDS = 1.6f
        /** The view follows the phone with this time constant (light smoothing of sensor noise). */
        private const val SMOOTHING_SECONDS = 0.036f

        /** Moves quaternion a toward b by t, keeping it unit length. */
        private fun nlerp(a: FloatArray, b: FloatArray, t: Float) {
            val sign = if (a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3] < 0) -1f else 1f
            var n = 0f
            for (i in 0..3) {
                a[i] += (sign * b[i] - a[i]) * t
                n += a[i] * a[i]
            }
            val inv = 1f / sqrt(n)
            for (i in 0..3) a[i] *= inv
        }
    }
}
