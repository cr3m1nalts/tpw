package com.derinliksaati

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Estimates relative depth with Depth Anything V2 Small (assets/depth_anything_v2.tflite), on the device. */
object DepthEstimator {
    private const val MODEL = "depth_anything_v2.tflite"
    private const val SIZE = 518 // the model's fixed input and output size

    /**
     * The photo's relative depth as a SIZE×SIZE grey picture, stretched over the photo's own range (0..255).
     * The model card does not say which end is near, so the reader decides that from the selection.
     */
    fun estimate(context: Context, photo: Bitmap): Bitmap {
        val start = System.currentTimeMillis()
        val px = IntArray(SIZE * SIZE)
        Bitmap.createScaledBitmap(photo, SIZE, SIZE, true).getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (p in px) input.put(Color.red(p) / 255f).put(Color.green(p) / 255f).put(Color.blue(p) / 255f)
        val output = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        Inpainter.withModel(context, MODEL) { it.run(input.rewind(), output) }

        val depth = FloatArray(SIZE * SIZE).also { output.rewind(); output.get(it) }
        val lo = depth.min()
        val range = (depth.max() - lo).takeIf { it > 0f } ?: 1f
        for (i in px.indices) {
            val v = ((depth[i] - lo) / range * 255).toInt()
            px[i] = Color.rgb(v, v, v)
        }
        Log.i("DerinlikSaati", "Depth estimate took ${System.currentTimeMillis() - start} ms")
        return Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }
}
