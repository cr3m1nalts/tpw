package com.derinliksaati

import android.hardware.SensorEvent
import android.hardware.SensorManager
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

    fun reset() {
        base[0] = Float.NaN
    }

    fun update(event: SensorEvent) {
        SensorManager.getQuaternionFromVector(q, event.values)
        if (base[0].isNaN()) q.copyInto(base)
        nlerp(base, q, RECENTER)
        // Rotation since the resting pose, in the phone's own axes: r = conj(base) * q.
        // For small angles, turning around the screen's vertical axis is about 2*r.y, around the horizontal 2*r.x.
        val (bw, bx, by, bz) = base
        val (w, qx, qy, qz) = q
        val rw = bw * w + bx * qx + by * qy + bz * qz
        val sign = if (rw < 0) -1f else 1f
        val rx = sign * (bw * qx - bx * w - by * qz + bz * qy)
        val ry = sign * (bw * qy + bx * qz - by * w - bz * qx)
        x += ((2 * ry / MAX_TILT_RAD).coerceIn(-1f, 1f) - x) * SMOOTHING
        y += ((2 * rx / MAX_TILT_RAD).coerceIn(-1f, 1f) - y) * SMOOTHING
    }

    private companion object {
        /** Tilting this far from the resting pose gives the full viewing angle. */
        val MAX_TILT_RAD = Math.toRadians(25.0).toFloat()
        /** Share of the gap to the current pose the resting pose closes per event (~4 s at game rate). */
        const val RECENTER = 0.005f
        const val SMOOTHING = 0.2f

        /** Moves quaternion a toward b by t, keeping it unit length. */
        fun nlerp(a: FloatArray, b: FloatArray, t: Float) {
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
