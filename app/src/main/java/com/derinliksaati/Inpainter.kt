package com.derinliksaati

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/** Fills the person's spot with the LaMa inpainting model (assets/lama_dilated.tflite), on the device. */
object Inpainter {
    private const val MODEL = "lama_dilated.tflite"
    private const val SIZE = 512 // the model's fixed input and output size
    private const val TAG = "DerinlikSaati"

    /**
     * Returns [base] with the [hole] area (its alpha) painted by the model. [base] must already contain no
     * person (the smooth fill), so even if the model looked inside the hole nothing of the person comes back.
     */
    // shortcut: the hole area is squeezed into 512×512, so the fill is softer than the photo; tile it if that shows.
    fun fill(context: Context, base: Bitmap, hole: Bitmap): Bitmap {
        val box = holeBox(hole) ?: return base
        val start = System.currentTimeMillis()
        val holeCrop = Bitmap.createBitmap(hole, box.left, box.top, box.width(), box.height())
        val image = Bitmap.createScaledBitmap(Bitmap.createBitmap(base, box.left, box.top, box.width(), box.height()), SIZE, SIZE, true)
        val mask = Bitmap.createScaledBitmap(holeCrop, SIZE, SIZE, true)

        val px = IntArray(SIZE * SIZE)
        val imageIn = floats(SIZE * SIZE * 3)
        image.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        for (p in px) {
            imageIn.put(Color.red(p) / 255f).put(Color.green(p) / 255f).put(Color.blue(p) / 255f)
        }
        val maskIn = floats(SIZE * SIZE)
        mask.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        for (p in px) maskIn.put(if (Color.alpha(p) > 40) 1f else 0f)
        val out = floats(SIZE * SIZE * 3)

        context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { ch ->
                val model = ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                Interpreter(model, Interpreter.Options().setNumThreads(4)).use { interpreter ->
                    // Feed inputs by name, not by position, so a re-export with another order still works.
                    val inputs = arrayOfNulls<Any>(interpreter.inputTensorCount)
                    for (i in inputs.indices) {
                        inputs[i] = if (interpreter.getInputTensor(i).name().contains("mask")) maskIn.rewind() else imageIn.rewind()
                    }
                    interpreter.runForMultipleInputsOutputs(inputs, mapOf(0 to out))
                }
            }
        }

        out.rewind()
        for (i in px.indices) {
            px[i] = Color.rgb(channel(out.get()), channel(out.get()), channel(out.get()))
        }
        val painted = Bitmap.createScaledBitmap(Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888), box.width(), box.height(), true)
            .copy(Bitmap.Config.ARGB_8888, true)
        // Keep the model's pixels only inside the hole; the soft mask edge blends them into the photo.
        Canvas(painted).drawBitmap(holeCrop, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        val result = base.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(result).drawBitmap(painted, box.left.toFloat(), box.top.toFloat(), null)
        Log.i(TAG, "Inpainting took ${System.currentTimeMillis() - start} ms for ${box.width()}x${box.height()}")
        return result
    }

    /** Box around the hole with some margin for context, widened toward a square so the 512×512 squeeze distorts less. */
    private fun holeBox(hole: Bitmap): Rect? {
        val w = hole.width
        val h = hole.height
        val px = IntArray(w * h).also { hole.getPixels(it, 0, w, 0, 0, w, h) }
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(px[y * w + x]) > 40) {
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            }
        }
        if (r < 0) return null
        val side = max(r - l, b - t) * 1.15f
        val bw = min(w.toFloat(), max(side, (r - l) * 1.3f))
        val bh = min(h.toFloat(), max(side, (b - t) * 1.3f))
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        val left = (cx - bw / 2).coerceIn(0f, w - bw).toInt()
        val top = (cy - bh / 2).coerceIn(0f, h - bh).toInt()
        return Rect(left, top, min(w, left + bw.toInt()), min(h, top + bh.toInt()))
    }

    private fun floats(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    private fun channel(v: Float) = (v.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
}
